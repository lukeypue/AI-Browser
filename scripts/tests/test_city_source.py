"""Real local Git histories; no network, credentials or existing checkout mutations."""
import importlib.util,json,os,subprocess,tempfile,unittest
from pathlib import Path
MODULE=Path(__file__).parents[1]/'city_source.py'
class CitySourceTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.exists(),'A verified stable source synchronizer is required before enabling source following')
        spec=importlib.util.spec_from_file_location('city_source',MODULE);self.module=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.module)
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);root=Path(self.temp.name)
        self.upstream=root/'upstream';self.worker=root/'worker';self.upstream.mkdir()
        self.git(self.upstream,'init','-b','ai-browser-v6-deep-search')
        (self.upstream/'sample').write_text('old');self.git(self.upstream,'add','sample');self.git(self.upstream,'commit','-m','old')
        self.old=self.git(self.upstream,'rev-parse','HEAD')
        self.git(root,'clone',str(self.upstream),str(self.worker))
        (self.upstream/'sample').write_text('new');self.git(self.upstream,'commit','-am','new')
        self.new=self.git(self.upstream,'rev-parse','HEAD')
    def git(self,cwd,*args):
        env={'PATH':'/usr/bin:/bin','HOME':self.temp.name,'GIT_CONFIG_GLOBAL':'/dev/null','GIT_CONFIG_SYSTEM':'/dev/null','GIT_AUTHOR_NAME':'Synthetic Test','GIT_AUTHOR_EMAIL':'test@example.invalid','GIT_COMMITTER_NAME':'Synthetic Test','GIT_COMMITTER_EMAIL':'test@example.invalid','LANG':'C.UTF-8'}
        return subprocess.check_output(['/usr/bin/git',*args],cwd=cwd,env=env,stderr=subprocess.DEVNULL,text=True).strip()
    def metadata(self,commit=None):return json.dumps({'commit':commit or self.new,'versionCode':947,'versionName':'synthetic.build833'}).encode()
    def sync(self,raw=None):
        return self.module.sync_source(self.worker,load_metadata=lambda:self.metadata() if raw is None else raw,
            git=self.module.GitRepository(self.worker,remote=str(self.upstream)))
    def test_only_the_published_revision_is_fast_forwarded(self):
        result=self.sync();self.assertEqual(self.new,self.git(self.worker,'rev-parse','HEAD'));self.assertEqual('new',(self.worker/'sample').read_text())
        self.assertEqual(self.new,result['source_revision']);self.assertTrue(result['updated']);self.assertEqual('',self.git(self.worker,'status','--porcelain'))
    def test_current_published_source_is_unchanged(self):
        result=self.sync(self.metadata(self.old));self.assertFalse(result['updated']);self.assertEqual(self.old,self.git(self.worker,'rev-parse','HEAD'))
    def test_dirty_human_changes_are_preserved(self):
        (self.worker/'sample').write_text('human change')
        with self.assertRaises(self.module.SourceSyncError):self.sync()
        self.assertEqual('human change',(self.worker/'sample').read_text());self.assertEqual(self.old,self.git(self.worker,'rev-parse','HEAD'))
    def test_diverged_history_is_not_rebased_reset_or_overwritten(self):
        (self.worker/'human').write_text('keep');self.git(self.worker,'add','human');self.git(self.worker,'commit','-m','human')
        before=self.git(self.worker,'rev-parse','HEAD')
        with self.assertRaises(self.module.SourceSyncError):self.sync()
        self.assertEqual(before,self.git(self.worker,'rev-parse','HEAD'));self.assertEqual('keep',(self.worker/'human').read_text())
    def test_unknown_or_duplicate_metadata_fields_are_rejected(self):
        for raw in [self.metadata()[:-1]+b',"unexpected":"discard"}',b'{"commit":"'+self.new.encode()+b'","commit":"'+self.old.encode()+b'","versionCode":947,"versionName":"test"}',b'x'*16385]:
            with self.assertRaises(self.module.SourceSyncError):self.sync(raw)
        self.assertEqual(self.old,self.git(self.worker,'rev-parse','HEAD'))
    def test_a_hash_outside_the_stable_branch_is_rejected(self):
        with self.assertRaises(self.module.SourceSyncError):self.sync(self.metadata('f'*40))
        self.assertEqual(self.old,self.git(self.worker,'rev-parse','HEAD'))
    def test_a_newer_unpublished_remote_head_does_not_replace_the_published_source(self):
        (self.upstream/'sample').write_text('not published');self.git(self.upstream,'commit','-am','unpublished')
        result=self.sync();self.assertEqual(self.new,result['source_revision']);self.assertEqual('new',(self.worker/'sample').read_text())
