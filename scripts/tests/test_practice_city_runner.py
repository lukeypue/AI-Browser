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
