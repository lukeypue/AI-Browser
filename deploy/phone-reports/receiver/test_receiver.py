import hashlib,json,tempfile,time,unittest,uuid
from pathlib import Path
from receiver import Store,validate,canonical,Failure

def report():
 return {'schema_version':1,'report_id':str(uuid.uuid4()),'app_revision':'a'*40,'version_code':70000,'started_at_utc':'2026-10-13T19:00:00Z','elapsed_ms':1000,'flags':{'trimmed':False,'clock_changed':False,'interrupted':False},'summary':{'verified':1,'failed':0,'tasks':1,'verified_skills':1,'end_reason':'deadline'},'events':[{'code':'step','site':'KSL','page_type':'RESULTS','status':'VERIFIED','phase':'SEARCH','diagnostic_code':'UNKNOWN','elapsed_ms':100,'counters':{'items':2}}]}

class ReceiverTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory(); self.store=Store(Path(self.temp.name)); self.token='x'*43
  code=self.store.issue_code(); self.device=self.store.pair(code,self.token)
 def tearDown(self): self.temp.cleanup()
 def test_pairing_single_use_and_credential_upload_only(self):
  code=self.store.issue_code(); self.store.pair(code,'y'*43)
  with self.assertRaises(Failure): self.store.pair(code,'z'*43)
  with self.assertRaises(Failure): self.store.accept(self.device,'bad',report())
 def test_strict_schema_drops_nothing_silently(self):
  for mutate in [lambda r:r.update(query='personal'),lambda r:r['events'][0].update(url='https://private'),lambda r:r['events'][0].update(site='myname.example'),lambda r:r['summary'].update(verified=True),lambda r:r['events'][0]['counters'].update(items=-1),lambda r:r['events'][0].update(elapsed_ms=1001)]:
   r=report(); mutate(r)
   with self.assertRaises(Failure):validate(r)
 def test_durable_receipt_hash_and_idempotence_survive_reopen(self):
  r=report(); a=self.store.accept(self.device,self.token,r)
  self.assertEqual(a['sha256'],hashlib.sha256(canonical(r)).hexdigest())
  reopened=Store(Path(self.temp.name)); self.assertEqual(a,reopened.accept(self.device,self.token,r))
  files=list((Path(self.temp.name)/'outbox').glob('*.json')); self.assertEqual(len(files),1)
  self.assertEqual(json.loads(files[0].read_text())['report'],r)
 def test_conflicting_id_rejected(self):
  r=report(); self.store.accept(self.device,self.token,r); r['summary']['verified']=2
  with self.assertRaises(Failure) as e:self.store.accept(self.device,self.token,r)
  self.assertEqual(e.exception.status,409)
 def test_six_new_reports_per_hour_retries_do_not_count(self):
  r=report(); self.store.accept(self.device,self.token,r)
  for _ in range(8):self.store.accept(self.device,self.token,r)
  for _ in range(5):self.store.accept(self.device,self.token,report())
  with self.assertRaises(Failure) as e:self.store.accept(self.device,self.token,report())
  self.assertEqual(e.exception.status,429)
 def test_expired_codes_and_retention(self):
  code=self.store.issue_code(now=100)
  with self.assertRaises(Failure):self.store.pair(code,'y'*43,now=701)
  self.store.accept(self.device,self.token,report(),now=1000)
  self.store.prune(now=1000+31*86400)
  self.assertEqual(list((Path(self.temp.name)/'outbox').glob('*.json')),[])
 def test_size_limit_before_storage(self):
  r=report(); r['events']=r['events']*8001
  with self.assertRaises(Failure):validate(r)
 def test_crash_orphan_retry_preserves_original_receipt_and_retention(self):
  r=report();receipt=self.store.accept(self.device,self.token,r,now=1000)
  with self.store.connect() as db:db.execute('DELETE FROM reports WHERE id=?',(r['report_id'],))
  self.assertEqual(receipt,self.store.accept(self.device,self.token,r,now=2000))
  with self.store.connect() as db:db.execute('DELETE FROM reports WHERE id=?',(r['report_id'],))
  self.store.prune(now=1000+31*86400)
  self.assertFalse((Path(self.temp.name)/'outbox'/(r['report_id']+'.json')).exists())

if __name__=='__main__':unittest.main()
