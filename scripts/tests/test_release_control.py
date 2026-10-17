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
KOTLIN_STDLIB = '/opt/ai-browser-dev/verification-home/gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.10/30de6faa127a4a012db8e71bf1b9c0a99b1402b2/kotlin-stdlib-2.2.10.jar'

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
        self.harness = self.state/'ai-browser/brain-core/src/test/harness'
        self.harness.mkdir(parents=True)
        (self.harness/'document-boundary.test.mjs').write_text('// fixture')
        self.script_tests = self.state/'scripts/tests'
        self.script_tests.mkdir(parents=True)
        self.required_fixtures = ['android-fixtures.test.mjs', 'android-fixture-curriculum.test.mjs']
        for name in self.required_fixtures:
            (self.script_tests/name).write_text('// fixture')
        self.core_classes = self.state/'ai-browser/brain-core/build/classes/kotlin/main'
        for name in ('perception/SpsParser', 'verify/Verifier', 'engine/LearningOpportunities', 'skills/BuiltinSkills'):
            source = self.state/'ai-browser/brain-core/src/main/kotlin/com/appgate/brain'/f'{name}.kt'
            compiled = self.core_classes/'com/appgate/brain'/f'{name}.class'
            source.parent.mkdir(parents=True, exist_ok=True)
            compiled.parent.mkdir(parents=True, exist_ok=True)
            source.write_text('// fixture')
            compiled.write_bytes(b'fake-only')
        from unittest.mock import patch
        original_is_file = Path.is_file
        self.jar_check = patch.object(Path, 'is_file', lambda path: str(path) == KOTLIN_STDLIB or original_is_file(path))
        self.jar_check.start()
        self.addCleanup(self.jar_check.stop)
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
        (self.script_tests/'z-additional.test.mjs').write_text('// future test')
        (self.script_tests/'helper.mjs').write_text('// not a test')
        (self.state/'outside.test.mjs').write_text('// outside tests directory')
        with patch.object(adapter, 'command', return_value=CANDIDATE) as command:
            adapter.checks()
        expected = ['/opt/ai-browser-dev/node-v24.21.0-linux-x64/bin/node', '--test',
                    '--test-concurrency=1',
                    *[str(self.script_tests/name) for name in sorted(self.required_fixtures+['z-additional.test.mjs'])]]
        selected = [call.args[0] for call in command.call_args_list
                    if any(str(item).startswith(str(self.script_tests)) for item in call.args[0])]
        self.assertEqual(selected, [expected])
        self.assertEqual(adapter.env['PLAYWRIGHT_MODULE'],
                         '/opt/ai-browser-dev/browser-harness/node_modules/playwright')
        self.assertEqual(adapter.env.get('FIXTURE_CORE_CHECKOUT'), str(self.state))
        self.assertEqual(adapter.env.get('FIXTURE_CORE_REVISION'), CANDIDATE)
        self.assertEqual(adapter.env.get('FIXTURE_CORE_CLASSPATH'), str(self.core_classes)+':'+KOTLIN_STDLIB)

    def test_missing_required_fixture_coverage_stops_before_push(self):
        from unittest.mock import patch
        adapter = Production(self.state)
        for missing in self.required_fixtures:
            with self.subTest(missing=missing):
                self.fake = Fake()
                self.fake.checks = adapter.checks
                (self.state/'ledger.json').unlink(missing_ok=True)
                path = self.script_tests/missing
                path.unlink()
                try:
                    with patch.object(adapter, 'command', return_value=CANDIDATE):
                        with self.assertRaises(ReleaseError): self.run_release()
                    self.assertNotIn('push', self.fake.calls)
                    self.assertFalse((self.state/'ledger.json').exists())
                finally:
                    path.write_text('// fixture')

    def test_empty_scripts_test_discovery_stops_before_push(self):
        from unittest.mock import patch
        for path in self.script_tests.glob('*.test.mjs'):
            path.unlink()
        adapter = Production(self.state)
        self.fake.checks = adapter.checks
        with patch.object(adapter, 'command', return_value=CANDIDATE):
            with self.assertRaises(ReleaseError): self.run_release()
        self.assertNotIn('push', self.fake.calls)

    def test_checks_run_all_trusted_harness_tests_sorted_and_serial(self):
        from unittest.mock import patch
        names = ['parallel-browser-lab.test.mjs', 'learning-perception.test.mjs',
                 'dialog-prerequisite-regression.test.mjs', 'dialog-detection.test.mjs',
                 'document-boundary.test.mjs']
        for name in names + ['extract.mjs']:
            (self.harness/name).write_text('// fixture')
        (self.state/'outside.test.mjs').write_text('// outside harness')
        adapter = Production(self.state)
        with patch.object(adapter, 'command', return_value=CANDIDATE) as command:
            adapter.checks()
        node = '/opt/ai-browser-dev/node-v24.21.0-linux-x64/bin/node'
        expected = [node, '--test', '--test-concurrency=1',
                    *[str(self.harness/name) for name in sorted(names)]]
        selected = [call.args[0] for call in command.call_args_list
                    if any(str(item).startswith(str(self.harness)) for item in call.args[0])]
        self.assertEqual(selected, [expected])

    def test_missing_harness_tests_fail_closed(self):
        from unittest.mock import patch
        (self.harness/'document-boundary.test.mjs').unlink()
        adapter = Production(self.state)
        with patch.object(adapter, 'command', return_value=CANDIDATE):
            with self.assertRaises(ReleaseError): adapter.checks()

    def test_android_fixture_failure_stops_before_push(self):
        from unittest.mock import patch
        adapter = Production(self.state)
        def command(argv, **kwargs):
            if any(str(item).endswith('android-fixtures.test.mjs') for item in argv):
                raise ReleaseError('Android fixture failed')
            return CANDIDATE
        self.fake.checks = adapter.checks
        with patch.object(adapter, 'command', side_effect=command):
            with self.assertRaises(ReleaseError): self.run_release()
        self.assertNotIn('push', self.fake.calls)
        self.assertFalse((self.state/'ledger.json').exists())

    def test_untrusted_fixture_environment_is_never_inherited(self):
        import os
        from unittest.mock import patch
        untrusted = {'FIXTURE_CORE_CHECKOUT':'/untrusted', 'FIXTURE_CORE_REVISION':'c'*40,
                     'FIXTURE_CORE_CLASSPATH':'/untrusted/classes:/untrusted.jar'}
        with patch.dict(os.environ, untrusted):
            adapter = Production(self.state)
            self.assertTrue(all(name not in adapter.env for name in untrusted))
            with patch.object(adapter, 'command', return_value=CANDIDATE):
                adapter.checks()
        self.assertEqual(adapter.env.get('FIXTURE_CORE_CHECKOUT'), str(self.state))
        self.assertEqual(adapter.env.get('FIXTURE_CORE_REVISION'), CANDIDATE)
        self.assertNotIn('/untrusted', adapter.env.get('FIXTURE_CORE_CLASSPATH', ''))

    def test_missing_stale_or_invalid_core_pin_stops_before_browser_and_push(self):
        import os
        from unittest.mock import patch
        compiled = self.core_classes/'com/appgate/brain/perception/SpsParser.class'
        original_time = compiled.stat().st_mtime_ns
        for failure in ('missing_class', 'stale_class', 'invalid_revision', 'missing_stdlib'):
            with self.subTest(failure=failure):
                adapter = Production(self.state)
                self.fake = Fake()
                self.fake.checks = adapter.checks
                (self.state/'ledger.json').unlink(missing_ok=True)
                if failure == 'missing_class': compiled.unlink()
                if failure == 'stale_class': os.utime(compiled, ns=(1, 1))
                revision = 'bad' if failure == 'invalid_revision' else CANDIDATE
                original_is_file = Path.is_file
                def is_file(path):
                    return False if failure == 'missing_stdlib' and str(path) == KOTLIN_STDLIB else original_is_file(path)
                try:
                    with patch.object(adapter, 'command', return_value=revision) as command, patch.object(Path, 'is_file', is_file):
                        with self.assertRaises(ReleaseError): self.run_release()
                    self.assertNotIn('push', self.fake.calls)
                    self.assertFalse(any('--test' in call.args[0] for call in command.call_args_list))
                finally:
                    compiled.write_bytes(b'fake-only')
                    os.utime(compiled, ns=(original_time, original_time))

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

    def synthetic_download_verifier(self, entries=None, digest=None, size=None, raw_apk=None):
        import hashlib
        import io
        import zipfile
        from unittest.mock import patch
        from release_control.controller import API, PACKAGE
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in (entries or {'classes.dex': b'dex\n'+CANDIDATE.encode()}).items():
                archive.writestr(name, data)
        apk = stream.getvalue() if raw_apk is None else raw_apk
        expected_hash = hashlib.sha256(apk).hexdigest()
        prefix = 'https://github.com/lukeypue/AI-Browser/releases/download/ai-browser-latest/'
        meta = dict(commit=CANDIDATE, versionCode=919, versionName='fixture.build805')
        release = {'assets':[{'name':'latest-version.json', 'browser_download_url':prefix+'latest-version.json'},
                             {'name':'AI-Browser-latest.apk', 'browser_download_url':prefix+'AI-Browser-latest.apk',
                              'digest':digest if digest is not None else 'sha256:'+expected_hash,
                              'size':size if size is not None else len(apk)}]}
        replies = {API+'/releases/tags/ai-browser-latest':json.dumps(release).encode(),
                   prefix+'latest-version.json':json.dumps(meta).encode(), prefix+'AI-Browser-latest.apk':apk}
        (self.state/'ai-browser-release.json').write_text(json.dumps(dict(versionCode=114,versionName='fixture')))
        adapter = Production(self.state)
        adapter.sdk = self.state/'sdk'
        (adapter.sdk/'build-tools/37.0.0').mkdir(parents=True,exist_ok=True)
        def command(argv, **kwargs):
            if Path(argv[0]).name == 'apksigner': return 'Signer #1 certificate SHA-256 digest: '+PIN
            return f"package: name='{PACKAGE}' versionCode='919' versionName='fixture.build805'"
        with patch.object(adapter,'download',side_effect=lambda url,limit:replies[url]), patch.object(adapter,'command',side_effect=command):
            result = adapter.verify_download(CANDIDATE, {'run_number':805})
        self.assertEqual(result['apk_sha256'], expected_hash)
        return result

    def test_real_verifier_accepts_matching_asset_digest_and_multidex_candidate(self):
        self.synthetic_download_verifier({'classes.dex':b'other', 'classes2.dex':b'dex\n'+CANDIDATE.encode()})

    def test_real_verifier_rejects_missing_malformed_or_wrong_asset_digest(self):
        for digest in ('', 'sha1:'+'d'*64, 'sha256:bad', 'sha256:'+'0'*64):
            with self.subTest(digest=digest):
                with self.assertRaises(ReleaseError): self.synthetic_download_verifier(digest=digest)

    def test_real_verifier_rejects_wrong_asset_size(self):
        with self.assertRaises(ReleaseError): self.synthetic_download_verifier(size=1)

    def test_real_verifier_requires_candidate_inside_dex(self):
        with self.assertRaises(ReleaseError):
            self.synthetic_download_verifier({'classes.dex':b'wrong revision', 'assets/revision.txt':CANDIDATE.encode()})

    def test_real_verifier_bounds_decompressed_dex_reads(self):
        from unittest.mock import patch
        with patch('release_control.controller.DEX_ENTRY_LIMIT',16,create=True):
            with self.assertRaises(ReleaseError):
                self.synthetic_download_verifier({'classes.dex':CANDIDATE.encode()})

    def test_real_verifier_bounds_total_dex_size_and_entry_count(self):
        from unittest.mock import patch
        entries = {'classes.dex':CANDIDATE.encode(), 'classes2.dex':CANDIDATE.encode()}
        for setting, limit in (('DEX_TOTAL_LIMIT',60), ('DEX_ENTRY_COUNT',1)):
            with self.subTest(setting=setting), patch('release_control.controller.'+setting,limit):
                with self.assertRaises(ReleaseError): self.synthetic_download_verifier(entries)

    def test_real_verifier_rejects_invalid_or_truncated_apk_archive(self):
        with self.assertRaises(ReleaseError): self.synthetic_download_verifier(raw_apk=b'not a complete APK ZIP')

if __name__ == '__main__': unittest.main()
