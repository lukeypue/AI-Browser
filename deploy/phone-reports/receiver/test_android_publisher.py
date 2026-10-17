import hashlib,json,os,grp,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import synthetic_export as w

ID='12345678-1234-4234-9234-123456789abc'
def report():
 return dict(schema_version=1,source='ANDROID_SERVER_CYCLE',cycle_id=ID,runtime='ANDROID_EMULATOR',content='SYNTHETIC_FIXTURES',app_build_type='SERVER_LAB_DEBUG',source_revision='a'*40,app_apk_sha256='b'*64,test_apk_sha256='c'*64,fixture_sha256='d'*64,version_code=918,started_at_utc='2026-10-17T00:00:00Z',finished_at_utc='2026-10-17T00:01:00Z',status='FAILED',end_reason='RUNTIME_FAILED',elapsed_ms=60000,app_elapsed_ms=0,diagnostics=None,diagnostics_sha256=None,memory_before=None,memory_after=None,restart=dict(app_restart_verified=False,emulator_restart_verified=False,before_fingerprint=None,after_app_fingerprint=None,after_emulator_fingerprint=None,verified_at_utc=None))
def raw(r):return json.dumps(r,sort_keys=True,separators=(',',':'),allow_nan=False).encode()

class AndroidPublisherTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.repo=self.root/'repo';self.repo.mkdir();self.out=self.root/'out';self.out.mkdir();self.receipts=self.root/'receipts';self.receipts.mkdir();self.gid=grp.getgrnam('aibrowserupload').gr_gid
  os.chown(self.out,0,self.gid);self.out.chmod(0o750)
 def put(self,r=None,name=None):
  p=self.out/(name or ID+'.json');p.write_bytes(raw(r or report()));os.chown(p,0,self.gid);p.chmod(0o640);return p
 def prepare(self):
  self.assertTrue(hasattr(w,'prepare_android_cycles'),'Android reports must join the existing publisher')
  return w.prepare_android_cycles(self.out,self.repo)
 def test_exact_destinations_only(self):
  self.assertTrue(w.destination_allowed('android-cycles/'+ID+'.json'))
  self.assertTrue(w.destination_allowed('android-cycles/index.json'))
  for p in ['android-cycles/../private.json','android-cycles/'+('a'*36)+'.json','android-cycles/private.json','.github/workflows/x.yml']:
   self.assertFalse(w.destination_allowed(p))
 def test_immutable_retry_and_review_status_preserved(self):
  self.put();records=self.prepare();target=self.repo/'android-cycles';index=target/'index.json';data=json.loads(index.read_bytes());data['reports'][0]['review_status']='reviewed';index.write_text(json.dumps(data));self.prepare()
  self.assertEqual(json.loads(index.read_bytes())['reports'][0]['review_status'],'reviewed');self.assertEqual(len(records),1);self.assertEqual((target/(ID+'.json')).read_bytes(),raw(report()))
  r=report();r['elapsed_ms']=50000;self.put(r)
  with self.assertRaises(ValueError):self.prepare()
 def test_rejects_unknown_personal_text_duplicate_keys_and_oversize(self):
  r=report();r['raw_log']='private';p=self.put(r)
  with self.assertRaises(ValueError):self.prepare()
  p.write_bytes(b'{"schema_version":1,"schema_version":1}')
  with self.assertRaises(ValueError):self.prepare()
  p.write_bytes(b' '*1048577)
  with self.assertRaises(ValueError):self.prepare()
 def test_rejects_symlinks_wrong_name_and_unsafe_permissions(self):
  p=self.put(name='private.json')
  with self.assertRaises(ValueError):self.prepare()
  p.unlink();p=self.put();p.chmod(0o666)
  with self.assertRaises(ValueError):self.prepare()
  p.chmod(0o640);p.unlink();p.symlink_to(self.root/'outside')
  with self.assertRaises(ValueError):self.prepare()
 def test_rejects_target_directory_symlink(self):
  self.put();outside=self.root/'outside';outside.mkdir();(self.repo/'android-cycles').symlink_to(outside,target_is_directory=True)
  with self.assertRaises(ValueError):self.prepare()
  self.assertEqual(list(outside.iterdir()),[])
 def test_index_retains_unreviewed_without_outbox(self):
  self.put();self.prepare();(self.out/(ID+'.json')).unlink();self.prepare()
  self.assertEqual(json.loads((self.repo/'android-cycles/index.json').read_bytes())['reports'][0]['review_status'],'unreviewed')
 def test_receipts_are_durable_idempotent_and_conflicts_rejected(self):
  self.put();records=self.prepare();self.assertFalse(list(self.receipts.iterdir()))
  w.write_android_receipts(self.receipts,records,'e'*40);p=self.receipts/(ID+'.json');first=p.read_bytes();w.write_android_receipts(self.receipts,records,'f'*40);self.assertEqual(p.read_bytes(),first)
  d=json.loads(first);self.assertEqual(d['report_sha256'],hashlib.sha256(raw(report())).hexdigest());self.assertEqual(d['repository_path'],'android-cycles/'+ID+'.json');d['report_sha256']='0'*64;p.write_text(json.dumps(d))
  with self.assertRaises(ValueError):w.write_android_receipts(self.receipts,records,'e'*40)
 def test_local_git_publication_receipts_follow_remote_verification(self):
  remote=self.root/'remote.git';repo=self.repo
  w.git(None,'init','--bare','--initial-branch=main',str(remote));w.git(repo,'init','--initial-branch=main');w.git(repo,'remote','add','origin',str(remote));(repo/'README.md').write_text('reports');w.git(repo,'add','.');w.git(repo,'commit','-m','initial');w.git(repo,'push','origin','main');self.put()
  def prepare(candidate):
   self.assertFalse(list(self.receipts.iterdir()));return w.prepare_android_cycles(self.out,candidate)
  def finish(records,sha):
   self.assertEqual(w.git(None,'--git-dir',str(remote),'rev-parse','main'),sha);w.write_android_receipts(self.receipts,records,sha)
  sha=w.publish(repo,prepare,finish)
  self.assertEqual(json.loads((self.receipts/(ID+'.json')).read_bytes())['commit_sha'],sha)
  self.assertEqual(w.git(repo,'status','--porcelain'),'')
 def test_failed_remote_verification_never_creates_receipt(self):
  self.put();records=self.prepare()
  self.assertFalse(list(self.receipts.iterdir()))
  # A controlled Git transport failure exercises publish's actual failure path.
  repo=self.root/'bad-repo';w.git(None,'init','--initial-branch=main',str(repo));(repo/'README.md').write_text('reports');w.git(repo,'add','.');w.git(repo,'commit','-m','initial');w.git(repo,'remote','add','origin',str(self.root/'absent.git'))
  with self.assertRaises(Exception):w.publish(repo,lambda path:records,lambda rows,sha:w.write_android_receipts(self.receipts,rows,sha))
  self.assertFalse(list(self.receipts.iterdir()))
 def test_remote_advance_after_push_defers_receipts_until_verified_retry(self):
  remote=self.root/'remote.git';repo=self.repo;other=self.root/'other'
  w.git(None,'init','--bare','--initial-branch=main',str(remote));w.git(repo,'init','--initial-branch=main');w.git(repo,'remote','add','origin',str(remote));(repo/'README.md').write_text('reports');w.git(repo,'add','.');w.git(repo,'commit','-m','initial');w.git(repo,'push','origin','main');w.git(None,'clone',str(remote),str(other));self.put()
  original=w.push_expected
  def advance(candidate,expected):
   original(candidate,expected);w.git(other,'pull','--ff-only');(other/'README.md').write_text('concurrent reviewed summary');w.git(other,'add','.');w.git(other,'commit','-m','review');w.git(other,'push','origin','main')
  prepare=lambda candidate:w.prepare_android_cycles(self.out,candidate)
  finish=lambda rows,sha:w.write_android_receipts(self.receipts,rows,sha)
  with patch.object(w,'push_expected',side_effect=advance):
   with self.assertRaises(ValueError):w.publish(repo,prepare,finish)
  self.assertFalse(list(self.receipts.iterdir()))
  sha=w.publish(repo,prepare,finish)
  self.assertEqual(json.loads((self.receipts/(ID+'.json')).read_bytes())['commit_sha'],sha)
  self.assertEqual(w.git(None,'--git-dir',str(remote),'show',sha+':README.md'),'concurrent reviewed summary')
 def test_existing_receipt_retry_requires_directory_durability(self):
  self.put();records=self.prepare();w.write_android_receipts(self.receipts,records,'e'*40)
  with patch.object(w.os,'fsync',side_effect=OSError('durability unavailable')):
   with self.assertRaises(OSError):w.write_android_receipts(self.receipts,records,'e'*40)
 def test_receipt_retry_recovers_link_before_temporary_cleanup_crash(self):
  self.put();records=self.prepare();w.write_android_receipts(self.receipts,records,'e'*40)
  os.link(self.receipts/(ID+'.json'),self.receipts/'.receipt-crash')
  w.write_android_receipts(self.receipts,records,'e'*40)
  self.assertEqual(json.loads((self.receipts/(ID+'.json')).read_bytes())['commit_sha'],'e'*40)

 def test_index_capacity_defers_whole_batch_without_blocking_other_reports(self):
  target=self.repo/'android-cycles';target.mkdir();entries=[];number=1;index_size=len(raw(dict(schema_version=1,reports=[])))+1
  while True:
   id='00000000-0000-4000-8000-'+format(number,'012x');doc=report();doc['cycle_id']=id;payload=raw(doc)
   entry=dict(cycle_id=id,report_sha256=hashlib.sha256(payload).hexdigest(),repository_path='android-cycles/'+id+'.json',finished_at_utc=doc['finished_at_utc'],status='FAILED',review_status='unreviewed')
   addition=len(raw(entry))+(1 if entries else 0)
   if index_size+addition>1048576:break
   index_size+=addition
   (target/(id+'.json')).write_bytes(payload);entries.append(entry);number+=1
  original=raw(dict(schema_version=1,reports=entries))+b'\n';index=target/'index.json';index.write_bytes(original)
  self.assertLessEqual(len(original),1048576);self.assertGreater(len(original),1048000)
  pending=self.put();original_outbox=pending.read_bytes();existing_files=set(target.iterdir())
  city=self.repo/'reports';city.mkdir();(city/'latest.json').write_bytes(b'{"city":true}')
  self.assertEqual(self.prepare(),[])
  phone=self.repo/'phone-reports';phone.mkdir();(phone/'index.json').write_bytes(b'{"phone":true}')
  self.assertEqual(set(target.iterdir()),existing_files);self.assertEqual(index.read_bytes(),original)
  self.assertEqual(pending.read_bytes(),original_outbox);self.assertEqual(list(self.receipts.iterdir()),[])
  self.assertEqual((city/'latest.json').read_bytes(),b'{"city":true}');self.assertEqual((phone/'index.json').read_bytes(),b'{"phone":true}')

if __name__=='__main__':unittest.main()
