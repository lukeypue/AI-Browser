import subprocess,tempfile,unittest
from pathlib import Path
import synthetic_writer as writer

class WriterTests(unittest.TestCase):
 def test_concurrent_review_is_preserved_and_candidate_is_rebuilt(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);remote=root/'remote.git';a=root/'a';b=root/'b'
   writer.git(None,'init','--bare','--initial-branch=main',str(remote));writer.git(None,'clone',str(remote),str(a))
   (a/'README.md').write_text('reports');writer.git(a,'add','.');writer.git(a,'commit','-m','initial');writer.git(a,'push','origin','main');writer.git(None,'clone',str(remote),str(b))
   calls=[]
   def prepare(candidate):
    calls.append(1);(candidate/'report.json').write_text('validated')
    if len(calls)==1:
     (b/'review.json').write_text('reviewed');writer.git(b,'add','.');writer.git(b,'commit','-m','concurrent review');writer.git(b,'push','origin','main')
   writer.publish(a,prepare)
   tip=writer.git(None,'--git-dir',str(remote),'rev-parse','main')
   self.assertEqual(writer.git(None,'--git-dir',str(remote),'show',tip+':review.json'),'reviewed')
   self.assertEqual(writer.git(None,'--git-dir',str(remote),'show',tip+':report.json'),'validated')
   self.assertEqual(len(calls),2)
   self.assertEqual(writer.git(a,'status','--porcelain'),'')
 def test_remote_head_change_is_rejected_without_overwriting_it(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);remote=root/'remote.git';a=root/'a';b=root/'b'
   writer.git(None,'init','--bare','--initial-branch=main',str(remote))
   writer.git(None,'clone',str(remote),str(a));(a/'README.md').write_text('reports only')
   writer.git(a,'add','.');writer.git(a,'commit','-m','initial');writer.git(a,'push','origin','main')
   writer.git(None,'clone',str(remote),str(b));expected=writer.git(a,'rev-parse','HEAD')
   (b/'review.json').write_text('{}');writer.git(b,'add','.');writer.git(b,'commit','-m','another authorized writer');writer.git(b,'push','origin','main')
   advanced=writer.git(b,'rev-parse','HEAD')
   (a/'new.json').write_text('{}');writer.git(a,'add','.');writer.git(a,'commit','-m','stale writer')
   with self.assertRaises((subprocess.CalledProcessError,ValueError)):writer.push_expected(a,expected)
   self.assertEqual(writer.git(None,'--git-dir',str(remote),'rev-parse','refs/heads/main'),advanced)
 def test_expected_head_fast_forward_succeeds(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);remote=root/'remote.git';repo=root/'repo'
   writer.git(None,'init','--bare','--initial-branch=main',str(remote));writer.git(None,'clone',str(remote),str(repo))
   (repo/'README.md').write_text('reports');writer.git(repo,'add','.');writer.git(repo,'commit','-m','initial');writer.git(repo,'push','origin','main')
   expected=writer.git(repo,'rev-parse','HEAD');(repo/'new.json').write_text('{}');writer.git(repo,'add','.');writer.git(repo,'commit','-m','next')
   writer.push_expected(repo,expected)
   self.assertEqual(writer.git(repo,'rev-parse','HEAD'),writer.git(None,'--git-dir',str(remote),'rev-parse','refs/heads/main'))

 def test_push_uses_normal_fast_forward_and_expected_head(self):
  from unittest.mock import patch
  calls=[]
  def fake(repo,*args):
   calls.append(args)
   if args[0]=='ls-remote':return 'a'*40+'\trefs/heads/main'
   return ''
  with patch.object(writer,'git',side_effect=fake):writer.push_expected(Path('/unused'),'a'*40)
  self.assertIn(('ls-remote','origin','refs/heads/main'),calls)
  self.assertIn(('push','origin','HEAD:refs/heads/main'),calls)
  self.assertFalse(any('--force' in arg for args in calls for arg in args))
 def test_live_paths_are_exactly_allowlisted(self):
  self.assertTrue(writer.destination_allowed('live-progress/latest.json'))
  self.assertTrue(writer.destination_allowed('live-progress/triage.json'))
  self.assertFalse(writer.destination_allowed('live-progress/private.txt'))
  self.assertFalse(writer.destination_allowed('.github/workflows/release.yml'))
  self.assertTrue(writer.destination_allowed('reports/latest.json'))
