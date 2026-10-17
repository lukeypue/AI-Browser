import copy,hashlib,json,tempfile,unittest,uuid
from datetime import datetime,timezone
from pathlib import Path
import receiver
from test_live_receiver import progress
try:import live_publisher
except ModuleNotFoundError:live_publisher=None

def envelope(device=None,test_id=None,received=1000,elapsed=1000,state='RUNNING'):
 p=progress(elapsed,state,test_id);return {'progress':p,'receipt':{'device_id':device or str(uuid.uuid4()),'test_id':p['test_id'],'sequence':p['sequence'],'sha256':hashlib.sha256(receiver.canonical_live(p)).hexdigest(),'received_at_utc':receiver.utc(received)}}

def advisory(test_id,now=1000):
 return {'schema_version':1,'source':'PHONE_LIVE_TYPED','test_id':test_id,'signature':'b'*64,'repeat_count':3,'generated_at_utc':receiver.utc(now),'status':'TRIAGED','focus':'NO_SAFE_CONTROL','next_check':'CONTROL_DISCOVERY','confidence':'MEDIUM','needs_human':False}

class LivePublisherTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.live=self.root/'live';self.live.mkdir();self.triage=self.root/'triage';self.triage.mkdir();self.repo=self.root/'repo';self.repo.mkdir()
  (self.repo/'phone-reports').mkdir();(self.repo/'phone-reports'/'keep.json').write_text('unreviewed')
  (self.repo/'reports').mkdir();(self.repo/'reports'/'keep.json').write_text('synthetic')
 def tearDown(self):self.temp.cleanup()
 def put(self,d):
  (self.live/(d['receipt']['device_id']+'.json')).write_bytes(receiver.canonical(d));return d
 def prepare(self,now=1000):
  self.assertIsNotNone(live_publisher,'live publisher is missing');return live_publisher.prepare_live(self.live,self.triage,self.repo,now)
 def latest(self):return json.loads((self.repo/'live-progress'/'latest.json').read_bytes())
 def test_validated_latest_bounds_and_preserves_final_and_synthetic(self):
  docs=[self.put(envelope(received=900+i)) for i in range(20)];self.prepare()
  latest=self.latest();self.assertEqual(set(latest),{'schema_version','source','snapshots'});self.assertEqual(latest['source'],'PHONE_LIVE');self.assertEqual(len(latest['snapshots']),16)
  self.assertEqual({d['receipt']['device_id'] for d in latest['snapshots']},{d['receipt']['device_id'] for d in docs[4:]})
  self.assertEqual((self.repo/'phone-reports'/'keep.json').read_text(),'unreviewed');self.assertEqual((self.repo/'reports'/'keep.json').read_text(),'synthetic')
 def test_receipt_throttle_new_test_finish_and_expiry_cleanup(self):
  d=self.put(envelope());self.prepare();path=self.repo/'live-progress'/'latest.json';before=path.read_bytes()
  d=self.put(envelope(d['receipt']['device_id'],d['progress']['test_id'],1119,2000));self.prepare(1119);self.assertEqual(path.read_bytes(),before)
  d=self.put(envelope(d['receipt']['device_id'],d['progress']['test_id'],1120,3000));self.prepare(1120);self.assertNotEqual(path.read_bytes(),before);before=path.read_bytes()
  self.prepare(1121);self.assertEqual(path.read_bytes(),before)
  d=self.put(envelope(d['receipt']['device_id'],d['progress']['test_id'],1121,3000,'FINISHED'));self.prepare(1121);self.assertEqual(self.latest()['snapshots'][0]['progress']['state'],'FINISHED')
  d=self.put(envelope(d['receipt']['device_id'],received=1122));self.prepare(1122);self.assertEqual(self.latest()['snapshots'][0]['progress']['test_id'],d['progress']['test_id'])
  self.prepare(2922);self.assertEqual(self.latest()['snapshots'],[])
 def test_malformed_stale_future_and_symlink_sources_are_skipped(self):
  good=self.put(envelope());stale=self.put(envelope(received=-801));future=self.put(envelope(received=1001));bad=self.put(envelope());bad['progress']['query']='personal';self.put(bad)
  bad_hash=self.put(envelope());bad_hash['receipt']['sha256']='0'*64;self.put(bad_hash)
  (self.live/'oversized.json').write_text(' '*40000);(self.live/'linked.json').symlink_to(self.live/(good['receipt']['device_id']+'.json'))
  self.prepare();self.assertEqual(self.latest()['snapshots'],[good])
  target=self.repo/'live-progress'/'latest.json';target.unlink();target.symlink_to(self.repo/'phone-reports'/'keep.json')
  with self.assertRaises(receiver.Failure):self.prepare()
  self.assertEqual((self.repo/'phone-reports'/'keep.json').read_text(),'unreviewed')
 def test_typed_triage_validation_and_expiry_without_timestamp_churn(self):
  d=self.put(envelope());t=advisory(d['progress']['test_id']);(self.triage/'latest.json').write_bytes(receiver.canonical(t));self.prepare()
  path=self.repo/'live-progress'/'triage.json';self.assertEqual(json.loads(path.read_bytes())['advisories'],[t]);before=path.read_bytes();self.prepare(1001);self.assertEqual(path.read_bytes(),before)
  for mutate in [lambda t:t.update(explanation='personal'),lambda t:t.update(needs_human=1),lambda t:t.update(signature='private'),lambda t:t.update(repeat_count=True),lambda t:t.update(generated_at_utc=receiver.utc(1001)),lambda t:t.update(focus='private'),lambda t:t.update(test_id=str(uuid.uuid4()))]:
   invalid=copy.deepcopy(t);mutate(invalid);(self.triage/'latest.json').write_bytes(receiver.canonical(invalid));self.prepare();self.assertEqual(json.loads(path.read_bytes())['advisories'],[])
  (self.triage/'latest.json').write_bytes(receiver.canonical(t));self.prepare(2800);self.assertEqual(json.loads(path.read_bytes())['advisories'],[])

if __name__=='__main__':unittest.main()
