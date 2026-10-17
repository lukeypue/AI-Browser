import fcntl
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    from release_control.controller import ReleaseController, ReleaseError, Production, PIN, atomic_json
except ImportError:
    ReleaseController = None
    ReleaseError = RuntimeError
    PIN = '118079b156fabeae30aca7ad845f1a15cd7eea89740bc1472be2163400d40239'

CANDIDATE = 'a' * 40
EXPECTED = 'b' * 40

class Fake:
    def __init__(self):
        self.calls = []
        self.head = EXPECTED
        self.fail_check = False
        self.run_sha = CANDIDATE
        self.pin = PIN
        self.ancestor = True
    def source(self, candidate): self.calls.append('source'); return CANDIDATE
    def stable_head(self): self.calls.append('head'); return self.head
    def is_ancestor(self, old, new): return self.ancestor
    def checks(self):
        self.calls.append('checks')
        if self.fail_check: raise ReleaseError('check failed')
    def push(self, candidate): self.calls.append('push'); self.head = candidate
    def wait_ci(self, candidate):
        self.calls.append('ci')
        return dict(head_sha=self.run_sha, event='push', name='Build AppGate APK',
                    head_branch='ai-browser-v6-deep-search', conclusion='success', id=33, run_number=805)
    def verify_download(self, candidate, run):
        self.calls.append('download')
        return dict(commit=CANDIDATE, versionCode=919, versionName='7.2.28-site-brain.build805',
                    certificate_sha256=self.pin, package='com.aibrowser.universal', apk_sha256='d'*64)

class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state = Path(self.tmp.name)
        self.fake = Fake()
        self.review = dict(source_sha=CANDIDATE, status='approved', independent=True,
                           reviewer='independent-reviewer', evidence='review-task-123')
    def tearDown(self): self.tmp.cleanup()
    def run_release(self):
        self.assertIsNotNone(ReleaseController, 'release controller is not implemented')
        return ReleaseController(self.fake, self.state).release(CANDIDATE, EXPECTED, self.review)
    def test_success_records_verified_completion(self):
        result = self.run_release()
        self.assertEqual(result['status'], 'VERIFIED')
        self.assertEqual(json.loads((self.state/'ledger.json').read_text())['source_sha'], CANDIDATE)
        self.assertEqual(self.fake.calls, ['source','head','checks','source','head','push','ci','download','head'])
    def test_held_lock_prevents_checks_and_push(self):
        with (self.state/'release.lock').open('w') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(ReleaseError): self.run_release()
        self.assertEqual(self.fake.calls, [])
    def test_failure_never_records_completion(self):
        for kind in ('head','check','sha','pin','ancestry','review'):
            with self.subTest(kind=kind):
                self.fake = Fake()
                self.review['source_sha'] = CANDIDATE
                if kind == 'head': self.fake.head = 'c'*40
                if kind == 'check': self.fake.fail_check = True
                if kind == 'sha': self.fake.run_sha = 'c'*40
                if kind == 'pin': self.fake.pin = 'c'*64
                if kind == 'ancestry': self.fake.ancestor = False
                if kind == 'review': self.review['source_sha'] = 'c'*40
                with self.assertRaises(ReleaseError): self.run_release()
                self.assertFalse((self.state/'ledger.json').exists())
                if kind in ('head','check','ancestry','review'):
                    self.assertNotIn('push', self.fake.calls)

    def test_lock_remains_held_through_download(self):
        original = self.fake.verify_download
        def download(candidate, run):
            with self.assertRaises(ReleaseError):
                ReleaseController(Fake(), self.state).release(CANDIDATE, EXPECTED, self.review)
            return original(candidate, run)
        self.fake.verify_download = download
        self.run_release()

    def test_final_checks_failure_preserves_previous_ledger(self):
        ledger = self.state/'ledger.json'
        ledger.write_text('{"versionCode":918,"status":"VERIFIED"}')
        before = ledger.read_bytes()
        self.fake.fail_check = True
        with self.assertRaises(ReleaseError): self.run_release()
        self.assertEqual(ledger.read_bytes(), before)

    def test_head_movement_during_checks_prevents_push(self):
        def checks(): self.fake.head = 'c'*40
        self.fake.checks = checks
        with self.assertRaises(ReleaseError): self.run_release()
        self.assertNotIn('push', self.fake.calls)

    def test_ci_pull_request_run_is_rejected(self):
        original = self.fake.wait_ci
        self.fake.wait_ci = lambda candidate: dict(original(candidate), event='pull_request')
        with self.assertRaises(ReleaseError): self.run_release()
        self.assertNotIn('download', self.fake.calls)

    def test_download_bad_version_or_source_never_completes(self):
        original = self.fake.verify_download
        for patch in ({'versionCode':918},{'commit':'c'*40},{'package':'other'}):
            self.fake = Fake()
            self.fake.verify_download = lambda candidate, run: dict(original(candidate, run), **patch)
            with self.assertRaises(ReleaseError): self.run_release()
            self.assertFalse((self.state/'ledger.json').exists())

    def test_production_environment_has_no_inherited_secrets(self):
        import os
        from unittest.mock import patch
        with patch.dict(os.environ, {'GH_TOKEN':'not-real','AI_BROWSER_SIGNING_PASSWORD':'not-real'}):
            adapter = Production(self.state)
        self.assertNotIn('GH_TOKEN', adapter.env)
        self.assertNotIn('AI_BROWSER_SIGNING_PASSWORD', adapter.env)

    def test_production_rejects_non_https_before_network(self):
        with self.assertRaises(ReleaseError): Production(self.state).download('http://example.org', 3)

    def test_git_disables_repository_hooks_and_untrusted_transport(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        with patch('release_control.controller.subprocess.run', return_value=SimpleNamespace(returncode=0, stdout='ok')) as run:
            Production(self.state).command(['/usr/bin/git','status','--porcelain'], git=True)
        argv = run.call_args.args[0]
        self.assertIn('core.hooksPath=/dev/null', argv)
        self.assertIn('protocol.allow=never', argv)
        self.assertIn('protocol.ssh.allow=always', argv)
        self.assertNotIn('GH_TOKEN', run.call_args.kwargs['env'])

    def test_atomic_ledger_failure_preserves_previous_record(self):
        from unittest.mock import patch
        ledger = self.state/'ledger.json'
        ledger.write_text('previous')
        with patch('release_control.controller.os.replace', side_effect=OSError('disk failure')):
            with self.assertRaises(OSError): atomic_json(ledger, {'status':'VERIFIED'})
        self.assertEqual(ledger.read_text(), 'previous')
        self.assertEqual(list(self.state.glob('.ledger-*')), [])

    def test_checks_include_serial_android_browser_fixtures(self):
        from unittest.mock import patch
        adapter = Production(self.state)
        with patch.object(adapter, 'command') as command:
            adapter.checks()
        expected = ['/opt/ai-browser-dev/node-v24.21.0-linux-x64/bin/node', '--test',
                    '--test-concurrency=1', str(self.state/'scripts/tests/android-fixtures.test.mjs')]
        self.assertIn(expected, [call.args[0] for call in command.call_args_list])
        self.assertEqual(adapter.env['PLAYWRIGHT_MODULE'],
                         '/opt/ai-browser-dev/browser-harness/node_modules/playwright')

    def test_android_fixture_failure_stops_before_push(self):
        from unittest.mock import patch
        adapter = Production(self.state)
        def command(argv, **kwargs):
            if any(str(item).endswith('android-fixtures.test.mjs') for item in argv):
                raise ReleaseError('Android fixture failed')
        self.fake.checks = adapter.checks
        with patch.object(adapter, 'command', side_effect=command):
            with self.assertRaises(ReleaseError): self.run_release()
        self.assertNotIn('push', self.fake.calls)
        self.assertFalse((self.state/'ledger.json').exists())

    def test_directory_fsync_failure_leaves_visible_verified_record_but_raises(self):
        from unittest.mock import patch
        ledger = self.state/'ledger.json'
        ledger.write_text('{"versionCode":918,"status":"VERIFIED"}')
        with patch('release_control.controller.os.fsync', side_effect=[None, OSError('directory fsync failed')]):
            with self.assertRaises(OSError): self.run_release()
        visible = json.loads(ledger.read_text())
        self.assertEqual(visible['status'], 'VERIFIED')
        self.assertEqual(visible['source_sha'], CANDIDATE)
        self.assertEqual(list(self.state.glob('.ledger-*')), [])

if __name__ == '__main__': unittest.main()
