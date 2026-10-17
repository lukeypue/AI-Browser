import json,os,pathlib,subprocess,sys,tempfile,unittest
SCRIPT=pathlib.Path(__file__).resolve().parents[1]/"prepare-ai-browser-release.py"
class ReleaseAllocationTests(unittest.TestCase):
    def prepare(self,env):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp);(root/'scripts').mkdir();(root/'ai-browser/app').mkdir(parents=True)
            (root/'scripts/prepare-ai-browser-release.py').write_bytes(SCRIPT.read_bytes())
            (root/'ai-browser-release.json').write_text(json.dumps({'versionCode':114,'versionName':'7.2.28-site-brain'}))
            app=root/'ai-browser/app/build.gradle';original='versionCode 114\nversionName "7.2.28-site-brain"\n';app.write_text(original)
            run=subprocess.run([sys.executable,str(root/'scripts/prepare-ai-browser-release.py')],env={'PATH':os.environ['PATH'],**env},text=True,capture_output=True)
            return run,app.read_text(),original
    def stable(self,number):
        return {'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'push','GITHUB_REF':'refs/heads/ai-browser-v6-deep-search','GITHUB_RUN_NUMBER':number}
    def test_offline_uses_checked_in_floor(self):
        run,text,_=self.prepare({});self.assertEqual(0,run.returncode);self.assertIn('versionCode 114',text)
    def test_stable_push_allocates_discoverable_version(self):
        run,text,_=self.prepare(self.stable('91'));self.assertEqual(0,run.returncode);self.assertIn('versionCode 205',text);self.assertIn('7.2.28-site-brain.build91',text)
    def test_next_workflow_run_is_monotonic(self):
        _,before,_=self.prepare(self.stable('91'));_,after,_=self.prepare(self.stable('92'));self.assertIn('versionCode 205',before);self.assertIn('versionCode 206',after)
    def test_pull_request_does_not_allocate_stable_release(self):
        env=self.stable('91');env['GITHUB_EVENT_NAME']='pull_request';run,text,_=self.prepare(env);self.assertEqual(0,run.returncode);self.assertIn('versionCode 114',text)
    def test_feature_push_does_not_allocate_stable_release(self):
        env=self.stable('91');env['GITHUB_REF']='refs/heads/feature';run,text,_=self.prepare(env);self.assertEqual(0,run.returncode);self.assertIn('versionCode 114',text)
    def test_invalid_missing_or_overflow_run_fails_before_writing(self):
        for number in ['', '0','-1','x','2147483647']:
            with self.subTest(number=number):
                run,text,original=self.prepare(self.stable(number));self.assertNotEqual(0,run.returncode);self.assertEqual(original,text)
if __name__=='__main__':unittest.main()
