import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

RUNNER = Path(__file__).parents[1] / 'practice-city.py'

class PracticeCityRunnerTest(unittest.TestCase):
    def runner(self):
        self.assertTrue(RUNNER.exists(), 'A bounded runner is needed before unattended training can start')
        spec = importlib.util.spec_from_file_location('practice_city', RUNNER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_source_following_holds_both_locks_and_preserves_reports_on_failure(self):
        import sys,types
        from unittest.mock import patch
        module=self.runner()
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);project=root/'checkout';output=root/'reports'
            core=project/'ai-browser/brain-core';core.mkdir(parents=True);output.mkdir()
            (project/'AGENTS.md').write_text('synthetic test checkout');(core/'settings.gradle').write_text('')
            latest=output/'latest.json';latest.write_text('{"preserve":"previous report"}')
            def unavailable(actual):
                self.assertEqual(project,actual)
                with self.assertRaises(module.AlreadyRunning):
                    with module.session_lock(project,root/'different-output'):pass
                with self.assertRaises(module.AlreadyRunning):
                    with module.run_lock(output):pass
                raise ValueError('synthetic unavailable source')
            with patch.dict(sys.modules,{'city_source':types.SimpleNamespace(sync_source=unavailable)}):
                result=module.main(['--project',str(project),'--output-dir',str(output),'--follow-stable'])
            self.assertEqual(1,result)
            self.assertEqual('{"preserve":"previous report"}',latest.read_text())


    def test_source_only_sync_holds_both_locks_without_running_or_touching_reports(self):
        import sys,types
        from unittest.mock import patch
        module=self.runner()
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);project=root/'checkout';output=root/'reports'
            core=project/'ai-browser/brain-core';core.mkdir(parents=True);output.mkdir()
            (project/'AGENTS.md').write_text('synthetic test checkout');(core/'settings.gradle').write_text('')
            latest=output/'latest.json';latest.write_text('previous')
            calls=[]
            def sync(actual):
                self.assertEqual(project,actual);calls.append(actual)
                with self.assertRaises(module.AlreadyRunning):
                    with module.session_lock(project,root/'other'):pass
                with self.assertRaises(module.AlreadyRunning):
                    with module.run_lock(output):pass
                return {'source_revision':'a'*40,'updated':True}
            with patch.dict(sys.modules,{'city_source':types.SimpleNamespace(sync_source=sync)}),patch.object(module,'run_command',side_effect=AssertionError('source updater must not run Gradle')):
                result=module.main(['--project',str(project),'--output-dir',str(output),'--sync-source-only'])
            self.assertEqual(0,result);self.assertEqual([project],calls);self.assertEqual('previous',latest.read_text())

    def test_followed_source_must_match_actual_report_revision(self):
        import sys,types
        from unittest.mock import patch
        module=self.runner()
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);project=root/'checkout';output=root/'reports'
            core=project/'ai-browser/brain-core';core.mkdir(parents=True);output.mkdir()
            (project/'AGENTS.md').write_text('synthetic test checkout');(core/'settings.gradle').write_text('')
            latest=output/'latest.json';latest.write_text('previous')
            def run(*args):
                target=core/'build/reports/brain';target.mkdir(parents=True,exist_ok=True)
                (target/'simulation.json').write_text(json.dumps({'source':{'revision':'b'*40},'manifest':{'training_episodes':3600}}))
                (target/'dialog-clinic.json').write_text('{}')
            with patch.dict(sys.modules,{'city_source':types.SimpleNamespace(sync_source=lambda _: {'source_revision':'a'*40,'updated':True})}),patch.object(module,'run_command',side_effect=run),patch.object(module,'validate_report',return_value=[]):
                result=module.main(['--project',str(project),'--output-dir',str(output),'--follow-stable'])
            self.assertNotEqual(0,result);self.assertEqual('previous',latest.read_text())

    def test_overlapping_run_is_refused(self):
        module = self.runner()
        with tempfile.TemporaryDirectory() as tmp:
            with module.run_lock(Path(tmp)):
                with self.assertRaises(module.AlreadyRunning):
                    with module.run_lock(Path(tmp)):
                        self.fail('Second run entered the same training session')

    def test_archive_is_atomic_and_retains_only_bounded_reports(self):
        module = self.runner()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            for i in range(4):
                module.archive_report(path, {'run': i}, retain=2)
            reports = list(path.glob('run-*.json'))
            self.assertEqual(2, len(reports))
            self.assertEqual(3, json.loads((path/'latest.json').read_text())['run'])
            self.assertFalse(list(path.glob('*.tmp')))

    def test_validator_rejects_unsafe_or_unidentified_report(self):
        module = self.runner()
        report = {'source': {'revision': 'a'*40, 'dirty_engine_or_lab': False},
                  'real_world_validated': False, 'synthetic_memory_imported_to_android': False,
                  'city_manifest': {'episodes': 144, 'districts': 8},
                  'manifest': {'training_episodes': 3600},
                  'baseline': {'episodes': 24}, 'after_training': {'episodes': 24},
                  'unseen_baseline': {'episodes': 24}, 'unseen_after_training': {'episodes': 24},
                  'city_baseline': {'episodes': 144}, 'city_after_training': {'episodes': 144}}
        for key in ('baseline', 'after_training', 'unseen_baseline', 'unseen_after_training', 'city_baseline', 'city_after_training'):
            report[key].update(unsafe_actions=0, false_verified_claims=0, hard_violations=0)
        self.assertEqual([], module.validate_report(report))
        report['city_after_training']['false_verified_claims'] = 1
        self.assertTrue(module.validate_report(report))
        report['city_after_training']['false_verified_claims'] = 0
        report['unseen_after_training']['unsafe_actions'] = 1
        self.assertTrue(module.validate_report(report), 'The transfer exam must pass the same safety checks')
        report['unseen_after_training']['unsafe_actions'] = 0
        report['source']['dirty_engine_or_lab'] = True
        self.assertTrue(module.validate_report(report))

    def test_timeout_kills_the_child_process_group(self):
        module = self.runner()
        import sys
        with self.assertRaises(module.RunTimeout):
            module.run_command([sys.executable, '-c', 'import time; time.sleep(10)'], Path.cwd(), timeout=0.1)

    def test_timeout_stops_term_ignoring_descendant_after_launcher_exits(self):
        module = self.runner()
        import sys, os, signal
        with tempfile.TemporaryDirectory() as tmp:
            pid_file = Path(tmp) / 'pid'
            child = "import signal,time,os,pathlib; signal.signal(signal.SIGTERM,signal.SIG_IGN); pathlib.Path(%r).write_text(str(os.getpid())); time.sleep(30)" % str(pid_file)
            parent = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c',%r]); time.sleep(30)" % child
            try:
                with self.assertRaises(module.RunTimeout):
                    module.run_command([sys.executable, '-c', parent], Path(tmp), timeout=0.5)
                pid = int(pid_file.read_text())
                stat = Path(f'/proc/{pid}/stat')
                active = stat.exists() and stat.read_text().split()[2] != 'Z'
                self.assertFalse(active, 'A surviving descendant can keep consuming resources after the lock is released')
            finally:
                if pid_file.exists():
                    try:
                        os.kill(int(pid_file.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_same_checkout_cannot_run_again_with_a_different_archive(self):
        module = self.runner()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with module.session_lock(root/'checkout', root/'archive-a'):
                with self.assertRaises(module.AlreadyRunning):
                    with module.session_lock(root/'checkout', root/'archive-b'):
                        self.fail('Same checkout raced on intermediate report')

    def test_same_archive_cannot_be_shared_by_different_checkouts(self):
        module = self.runner()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with module.session_lock(root/'checkout-a', root/'archive'):
                with self.assertRaises(module.AlreadyRunning):
                    with module.session_lock(root/'checkout-b', root/'archive'):
                        self.fail('Shared archive was overwritten')

    def test_install_preparation_must_fail_if_another_session_holds_the_lock(self):
        module = self.runner()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root/'checkout'
            core = project/'ai-browser/brain-core'
            core.mkdir(parents=True)
            (project/'AGENTS.md').write_text('Test fixture')
            (core/'settings.gradle').write_text('Test fixture')
            output = root/'reports'
            with module.session_lock(project, output):
                args = ['--project', str(project), '--output-dir', str(output)]
                self.assertEqual(0, module.main(args), 'Scheduled overlap is a safe skip')
                self.assertNotEqual(0, module.main(args + ['--prepare-dependencies']),
                                    'Installation requires a real completed session')
                self.assertNotEqual(0, module.main(args + ['--sync-source-only']), 'Source synchronization must not claim completion while busy')

class ChallengeRunnerTests(unittest.TestCase):
    def test_challenge_summary_cannot_be_ignored(self):
        module=PracticeCityRunnerTest().runner()
        r={'challenge_manifest':{'episodes':432,'districts':24},'challenge_baseline':{'episodes':432,'unsafe_actions':1}}
        errors=module.validate_report(r)
        self.assertTrue(any('challenge' in x for x in errors))

class ChallengeEvidenceRunnerTests(unittest.TestCase):
    def fixture(self):
        import sys
        directory=Path(__file__).parents[2]/'deploy/phone-reports/receiver'
        sys.path.insert(0,str(directory))
        try:
            from test_synthetic_export import ChallengeExportTests
            return ChallengeExportTests().challenge()
        finally:sys.path.remove(str(directory))
    def test_valid_frozen_case_evidence_passes(self):
        self.assertEqual([],PracticeCityRunnerTest().runner().validate_report(self.fixture(),require_challenge=True))
    def test_contradictory_case_and_changed_corpus_are_rejected(self):
        import hashlib
        module=PracticeCityRunnerTest().runner()
        r=self.fixture();r['challenge_pairs'][0]['after']['unsafe_actions']=1
        self.assertTrue(module.validate_report(r,require_challenge=True))
        r=self.fixture();c=r['challenge_pairs'][0]['before']['scenario'].replace('/100010/','/999999/')
        r['challenge_pairs'][0]={'before':{'scenario':c},'after':{'scenario':c}}
        r['challenge_manifest']['sha256']=hashlib.sha256('\n'.join(x['before']['scenario'] for x in r['challenge_pairs']).encode()).hexdigest()
        self.assertTrue(module.validate_report(r,require_challenge=True))

class DialogClinicRunnerTests(unittest.TestCase):
    def test_clinic_validator_rejects_unearned_mastery(self):
        import sys
        directory=Path(__file__).parents[2]/'deploy/phone-reports/receiver';sys.path.insert(0,str(directory))
        try:
            from test_dialog_clinic_export import clinic
            c=clinic();module=PracticeCityRunnerTest().runner();self.assertEqual(4,module.sanitize_dialog_clinic(c)['compiled_transfer_uses'])
            c['after'][4]['verified']=True
            with self.assertRaises(ValueError):module.sanitize_dialog_clinic(c)
        finally:sys.path.remove(str(directory))

class DialogClinicConsistencyRunnerTests(unittest.TestCase):
    def test_reuse_requires_compiled_memory_and_actions(self):
        import sys
        directory=Path(__file__).parents[2]/'deploy/phone-reports/receiver';sys.path.insert(0,str(directory))
        try:
            from test_dialog_clinic_export import clinic
            module=PracticeCityRunnerTest().runner()
            for mutate in [lambda c:c.update(compiled_dialog_skills=0),lambda c:c['after'][0].update(actions=0)]:
                c=clinic();mutate(c)
                with self.assertRaises(ValueError):module.sanitize_dialog_clinic(c)
        finally:sys.path.remove(str(directory))
