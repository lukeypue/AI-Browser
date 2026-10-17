import copy,gc,hashlib,json,os,sqlite3,tempfile,unittest,uuid
from pathlib import Path
from unittest.mock import patch
import receiver
from receiver import Store,Failure
from test_receiver import report
from test_http import HttpTests

def progress(elapsed=1000,state='RUNNING',test_id=None):
 r=report();e=r['events'][0];e.update(lesson='UNKNOWN',capability='UNKNOWN',recheck_reason='UNKNOWN')
 return {'schema_version':1,'test_id':test_id or str(uuid.uuid4()),'app_revision':r['app_revision'],'version_code':r['version_code'],'started_at_utc':r['started_at_utc'],'elapsed_ms':elapsed,'sequence':elapsed*2+(state=='FINISHED'),'state':state,'flags':r['flags'],'counters':{'verified_actions':1,'failed_actions':0,'tasks':1,'new_lessons':0,'teacher_attempts':1},'recent_events':[e]}

class LiveSchemaTests(unittest.TestCase):
 def test_strict_schema_and_canonical_document(self):
  self.assertTrue(hasattr(receiver,'validate_live'),'live validator is missing')
  p=progress();self.assertEqual(receiver.validate_live(p),p)
  for mutate in [lambda p:p.update(url='private'),lambda p:p.update(schema_version=True),lambda p:p.update(state='PAUSED'),lambda p:p.update(sequence=1),lambda p:p.update(test_id=p['test_id'].upper()),lambda p:p.update(elapsed_ms=True),lambda p:p.update(elapsed_ms=86400001),lambda p:p.update(started_at_utc='2026-10-13T19:00:00-06:00'),lambda p:p['flags'].update(private=True),lambda p:p['flags'].update(trimmed=1),lambda p:p['counters'].update(verified_actions=True),lambda p:p['counters'].update(tasks=1000001),lambda p:p['counters'].pop('new_lessons'),lambda p:p['recent_events'][0].update(query='private'),lambda p:p['recent_events'][0].update(lesson='private'),lambda p:p['recent_events'][0].update(elapsed_ms=1001),lambda p:p.update(recent_events=p['recent_events']*33)]:
   value=copy.deepcopy(p);mutate(value)
   with self.assertRaises((Failure,ValueError)):receiver.validate_live(value)
  self.assertEqual(receiver.canonical_live(p),json.dumps(p,sort_keys=True,separators=(',',':')).encode())
  p['recent_events']*=32
  for e in p['recent_events']:e['counters']={k:1000000 for k in receiver.COUNTERS_V2}
  p['recent_events'][0]['counters']['private']='x'*32768
  with self.assertRaises(Failure) as error:receiver.validate_live(p)
  self.assertEqual(error.exception.status,400)

class LiveStoreTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.store=Store(self.root);self.token='x'*43;self.device=self.store.pair(self.store.issue_code(),self.token)
 def tearDown(self):self.temp.cleanup()
 def accept(self,p,now=1000):return self.store.accept_live(self.device,self.token,p,now=now)
 def reject(self,p,status,now=1000):
  with self.assertRaises(Failure) as error:self.accept(p,now)
  self.assertEqual(error.exception.status,status)
 def test_auth_creates_no_live_data(self):
  # Force prior connections to finalize, then delay GC during authentication.
  # Connection cleanup must be deterministic, not an incidental GC side effect.
  gc.collect();was_enabled=gc.isenabled();gc.disable()
  self.addCleanup(lambda:gc.enable() if was_enabled else None)
  self.assertTrue(hasattr(self.store,'accept_live'),'live acceptance is missing')
  before={f.relative_to(self.root):f.read_bytes() for f in self.root.rglob('*') if f.is_file() and not f.name.endswith('-shm')}
  with self.assertRaises(Failure) as error:self.store.accept_live(self.device,'bad',progress(),now=1000)
  self.assertEqual(error.exception.status,401)
  after={f.relative_to(self.root):f.read_bytes() for f in self.root.rglob('*') if f.is_file() and not f.name.endswith('-shm')}
  self.assertEqual({str(k):hashlib.sha256(v).hexdigest() for k,v in before.items()},{str(k):hashlib.sha256(v).hexdigest() for k,v in after.items()})
 def test_connection_context_closes_after_success_and_exception(self):
  for fail in (False,True):
   with self.subTest(exception=fail):
    try:
     with self.store.connect() as db:
      self.assertEqual(db.execute('SELECT 1').fetchone(),(1,))
      db.execute('INSERT INTO codes VALUES(?,?)',('close-check-'+str(fail),123))
      if fail:raise RuntimeError('test transaction failure')
    except RuntimeError:pass
    with self.assertRaises(sqlite3.ProgrammingError):db.execute('SELECT 1')
    with self.store.connect() as reopened:
     self.assertEqual(reopened.execute('SELECT count(*) FROM codes WHERE hash=?',('close-check-'+str(fail),)).fetchone()[0],0 if fail else 1)
 def test_durable_latest_receipt_reopen_and_independent_final_quota(self):
  self.assertTrue(hasattr(self.store,'accept_live'),'live acceptance is missing')
  p=progress();a=self.accept(p);path=self.root/'live'/(self.device+'.json')
  self.assertEqual(a,{'device_id':self.device,'test_id':p['test_id'],'sequence':p['sequence'],'sha256':hashlib.sha256(receiver.canonical_live(p)).hexdigest(),'received_at_utc':receiver.utc(1000)})
  self.assertEqual(receiver.live_document(path.read_bytes()),{'progress':p,'receipt':a})
  self.assertEqual(path.stat().st_mode&0o777,0o640);self.assertEqual(path.parent.stat().st_mode&0o777,0o750)
  reopened=Store(self.root);self.assertEqual(reopened.accept_live(self.device,self.token,p,now=1001),a)
  q=copy.deepcopy(p);q.update(elapsed_ms=2000,sequence=4000);self.accept(q,1025)
  self.assertEqual(self.accept(p,1026),a);self.assertEqual(len(list(path.parent.glob('*.json'))),1)
  for _ in range(6):self.store.accept(self.device,self.token,report(),now=1026)
  self.assertEqual(len(list((self.root/'outbox').glob('*.json'))),6)
  q.update(elapsed_ms=3000,sequence=6000);self.accept(q,1050)
  bad=json.loads(path.read_bytes());bad['progress']['counters']['tasks']=9
  with self.assertRaises(Failure):receiver.live_document(json.dumps(bad))
  bad=json.loads(path.read_bytes());bad['receipt']['query']='private'
  with self.assertRaises(Failure):receiver.live_document(json.dumps(bad))
 def test_sequence_conflicts_immutable_build_and_finished_terminal(self):
  self.assertTrue(hasattr(self.store,'accept_live'),'live acceptance is missing')
  p=progress();self.accept(p)
  q=copy.deepcopy(p);q['counters']['tasks']=2;self.reject(q,409)
  q=copy.deepcopy(p);q.update(elapsed_ms=999,sequence=1998);self.reject(q,409,1030)
  for key,value in [('app_revision','b'*40),('version_code',2),('started_at_utc','2026-10-13T20:00:00Z')]:
   q=copy.deepcopy(p);q.update(elapsed_ms=2000,sequence=4000);q[key]=value;self.reject(q,409,1030)
  q=copy.deepcopy(p);q.update(state='FINISHED',sequence=2001);a=self.accept(q,1001)
  self.assertEqual(self.accept(q,1002),a)
  q.update(state='RUNNING',elapsed_ms=2000,sequence=4000);self.reject(q,409,1030)
  q.update(state='FINISHED',sequence=4001);self.reject(q,409,1030)
 def test_rate_minimum_interval_and_finish_exception(self):
  self.assertTrue(hasattr(self.store,'accept_live'),'live acceptance is missing')
  p=progress();self.accept(p)
  q=copy.deepcopy(p);q.update(elapsed_ms=2000,sequence=4000);self.reject(q,429,1024)
  self.accept(q,1025);q.update(state='FINISHED',sequence=4001);self.accept(q,1026)
  self.reject(progress(),429,1027)
 def test_hourly_rate_cap_duplicates_do_not_charge(self):
  self.assertTrue(hasattr(self.store,'accept_live'),'live acceptance is missing')
  # Finish exceptions permit up to two accepted updates per 25 seconds.
  for i in range(120):
   p=progress(elapsed=1000+i*2);self.accept(p,1000+i*26)
   p.update(state='FINISHED',sequence=p['sequence']+1);self.accept(p,1001+i*26)
   self.accept(p,1002+i*26)
  self.reject(progress(),429,4200)
  self.accept(progress(),4601)
 def test_ttl_prunes_only_live_and_recovers_durable_orphan(self):
  self.assertTrue(hasattr(self.store,'accept_live'),'live acceptance is missing')
  p=progress();a=self.accept(p);self.store.accept(self.device,self.token,report(),now=1000)
  with self.store.connect() as db:
   db.execute('DELETE FROM live_receipts');db.execute('DELETE FROM live_rate')
  self.assertEqual(self.accept(p,1001),a)
  self.store.prune_live(now=2801)
  self.assertEqual(list((self.root/'live').glob('*')),[])
  self.assertEqual(len(list((self.root/'outbox').glob('*.json'))),1)
  with self.store.connect() as db:self.assertEqual(db.execute('SELECT count(*) FROM live_receipts').fetchone()[0],0)
  self.store.prune_live(now=4601)
  with self.store.connect() as db:self.assertEqual(db.execute('SELECT count(*) FROM live_rate').fetchone()[0],0)
 def test_final_report_clears_only_matching_live_snapshot(self):
  p=progress();a=self.accept(p);r=report();r['report_id']=p['test_id'];self.store.accept(self.device,self.token,r,now=1001)
  self.assertFalse((self.root/'live'/(self.device+'.json')).exists())
  self.assertEqual(self.accept(p,1002),a)
  late=copy.deepcopy(p);late.update(elapsed_ms=2000,sequence=4000);self.reject(late,409,1025)
  q=progress();self.accept(q,1025)
  self.store.accept(self.device,self.token,r,now=1026)
  self.assertTrue((self.root/'live'/(self.device+'.json')).exists())
 def test_orphan_directory_fsync_failure_requires_durability_before_retry_ack(self):
  p=progress()
  real_fsync=os.fsync
  calls=0
  def fail_directory(fd):
   nonlocal calls
   calls+=1
   if calls==2:raise OSError('directory unavailable')
   real_fsync(fd)
  with patch('os.fsync',side_effect=fail_directory):
   with self.assertRaises(OSError):self.accept(p)
  self.assertTrue((self.root/'live'/(self.device+'.json')).exists())
  with patch('os.fsync',side_effect=OSError('still unavailable')):
   with self.assertRaises(OSError):self.accept(p,1001)
  self.assertEqual(self.accept(p,1002)['received_at_utc'],receiver.utc(1000))
 def test_storage_failure_never_acknowledges(self):
  self.assertTrue(hasattr(self.store,'accept_live'),'live acceptance is missing')
  with patch('os.fsync',side_effect=OSError('disk unavailable')):
   with self.assertRaises(OSError):self.accept(progress())

class LiveHttpTests(HttpTests):
 def test_live_upload_and_no_public_read(self):
  headers={'X-Forwarded-Proto':'https','Authorization':'Bearer '+self.device+'.'+self.token}
  p=progress();status,a=self.post('/v1/live',p,headers);self.assertEqual(status,200)
  self.assertEqual(self.post('/v1/live',p,headers),(200,a))
  self.assertEqual(self.post('/v1/live',p,{'X-Forwarded-Proto':'https'})[0],401)
  import urllib.request,urllib.error
  with self.assertRaises(urllib.error.HTTPError) as error:urllib.request.urlopen('http://127.0.0.1:'+str(self.server.server_port)+'/v1/live')
  self.assertEqual(error.exception.code,404)
  self.assertEqual(self.post('/v1/live',p,{**headers,'Content-Length':'32769'})[0],413)

if __name__=='__main__':unittest.main()
