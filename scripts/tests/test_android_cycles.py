import fcntl,hashlib,importlib.util,json,sys,tempfile,unittest,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'scripts'))
from android_cycles.controller import Controller,Busy,atomic_json
from android_cycles.transport import Transport,run_command
spec=importlib.util.spec_from_file_location('wire_tests',ROOT/'deploy/phone-reports/receiver/test_android_cycles.py')
spec.loader.exec_module(fixture:=importlib.util.module_from_spec(spec))
class Clock:
 def __init__(self):self.value=0
 def __call__(self):return self.value
class Fake:
 def __init__(self,clock):self.clock=clock;self.calls=[]
 def cycle(self,id):
  self.calls.append('cycle');self.clock.value+=1800;r=fixture.report();r['diagnostics']['report_id']=id
  return {k:r[k] for k in ('schema_version','status','end_reason','app_elapsed_ms','diagnostics','memory_before','memory_after','version_code','source_revision')}|{'cycle_id':id,'engine_ready':True}
 def stop(self):self.calls.append('stop')
 def probe(self,id):
  self.calls.append('probe');r=fixture.report()
  return dict(schema_version=1,cycle_id=id,status='PROBE',end_reason='DEADLINE',app_elapsed_ms=0,diagnostics=None,memory_before=None,memory_after=r['memory_after'],engine_ready=True,version_code=918,source_revision='a'*40)
 def restart(self):self.calls.append('restart')
class ControllerTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.clock=Clock();self.fake=Fake(self.clock);r=fixture.report()
  self.config={k:r[k] for k in ('source_revision','version_code','app_apk_sha256','test_apk_sha256','fixture_sha256')}
 def tearDown(self):self.temp.cleanup()
 def controller(self,**kw):return Controller(self.config,self.root/'state',self.root/'outbox',self.root/'receipts',self.fake,monotonic=self.clock,boot_id=lambda:'boot1',**kw)
 def test_crash_owned_temps_cleaned_without_touching_final_files(self):
  import os
  c=self.controller();cycle=str(uuid.uuid4())
  with c.locked():
   state_temp=c.state/('.journal.json.'+str(uuid.uuid4())+'.tmp')
   outbox_temp=c.outbox/('.'+cycle+'.json.'+str(uuid.uuid4())+'.tmp')
   state_temp.write_bytes(b'partial');state_temp.chmod(0o600)
   outbox_temp.write_bytes(b'x'*(11*1048576));outbox_temp.chmod(0o640);os.chown(outbox_temp,0,c.upload_gid)
   journal=c.state/'journal.json';journal.write_bytes(b'journal')
   final=c.outbox/(cycle+'.json');final.write_bytes(b'final')
  with c.locked():
   self.assertFalse(state_temp.exists());self.assertFalse(outbox_temp.exists())
   self.assertEqual(journal.read_bytes(),b'journal');self.assertEqual(final.read_bytes(),b'final')
 def test_receipt_temp_cleanup_preserves_nonowned_state_names(self):
  c=self.controller();cycle=str(uuid.uuid4())
  with c.locked():
   archive=c.state/'published';archive.mkdir(mode=0o700)
   temp=archive/('.'+cycle+'.json.'+str(uuid.uuid4())+'.tmp');temp.write_bytes(b'partial');temp.chmod(0o600)
   final=archive/(cycle+'.json');final.write_bytes(b'receipt')
   unknown=c.state/'.journal.json.not-a-uuid.tmp';unknown.write_bytes(b'preserve')
  with c.locked():
   self.assertFalse(temp.exists());self.assertEqual(final.read_bytes(),b'receipt')
   self.assertEqual(unknown.read_bytes(),b'preserve')
 def test_orphan_cleanup_occurs_only_after_cycle_lock(self):
  c=self.controller()
  with c.locked():
   temp=c.state/('.journal.json.'+str(uuid.uuid4())+'.tmp');temp.write_bytes(b'partial');temp.chmod(0o600)
   with self.assertRaises(Busy):
    with self.controller().locked():pass
   self.assertTrue(temp.exists())
 def test_orphan_cleanup_rejects_symlink_hardlink_foreign_owner_and_bad_mode(self):
  import os
  for kind in ('symlink','hardlink','owner','mode'):
   c=self.controller()
   with c.locked():
    temp=c.outbox/('.'+str(uuid.uuid4())+'.json.'+str(uuid.uuid4())+'.tmp')
    outside=self.root/('outside-'+kind);outside.write_bytes(b'preserve')
    if kind=='symlink':temp.symlink_to(outside)
    elif kind=='hardlink':os.link(outside,temp)
    else:
     temp.write_bytes(b'partial');temp.chmod(0o640)
     if kind=='owner':os.chown(temp,65534,c.upload_gid)
     else:temp.chmod(0o666)
   with self.subTest(kind=kind):
    with self.assertRaises(ValueError):
     with c.locked():pass
    self.assertTrue(temp.is_symlink() or temp.exists());self.assertEqual(outside.read_bytes(),b'preserve')
   temp.unlink()
 def test_unknown_temp_cannot_bypass_pending_capacity(self):
  c=self.controller()
  with c.locked():
   temp=c.outbox/'.not-owned.tmp';temp.write_bytes(b'x')
  with self.assertRaises(ValueError):c.run_once()
  self.assertEqual(temp.read_bytes(),b'x')
 def test_cleanup_rejects_foreign_private_directory_owner(self):
  import os
  c=self.controller()
  with c.locked():
   temp=c.state/('.journal.json.'+str(uuid.uuid4())+'.tmp');temp.write_bytes(b'partial');temp.chmod(0o600)
  os.chown(c.state,65534,0)
  try:
   with self.assertRaises(ValueError):
    with c.locked():pass
   self.assertTrue(temp.exists())
  finally:os.chown(c.state,0,0)
 def test_full_duration_two_restart_proofs(self):
  c=self.controller();r=c.run_once();self.assertEqual(r['status'],'COMPLETED')
  self.assertEqual(self.fake.calls,['cycle','stop','probe','restart','probe']);self.assertEqual(json.loads(next(c.outbox.glob('*.json')).read_text()),r)
 def test_crash_after_completion_recovers_interrupted(self):
  def hook(phase):
   if phase=='APP_COMPLETED':raise SystemExit()
  with self.assertRaises(SystemExit):self.controller(hook=hook).run_once()
  r=self.controller().run_once();self.assertEqual(r['status'],'INTERRUPTED');self.assertEqual(self.fake.calls,['cycle','stop'])
 def test_finalized_crash_replays_same_bytes(self):
  def hook(phase):
   if phase=='FINALIZED':raise SystemExit()
  with self.assertRaises(SystemExit):self.controller(hook=hook).run_once()
  state=json.loads((self.root/'state/journal.json').read_text());r=self.controller().run_once()
  self.assertEqual(r,state['report']);self.assertEqual(len(list((self.root/'outbox').glob('*.json'))),1)
 def test_boot_reset_lock_busy(self):
  c=self.controller();c.state.mkdir(parents=True,exist_ok=True)
  atomic_json(c.state/'journal.json',dict(phase='RUNNING',cycle_id=str(uuid.uuid4()),started_at_utc='2026-10-17T00:00:00Z',started_monotonic=99999,boot_id='old'))
  self.assertEqual(c.run_once()['elapsed_ms'],0)
  with (c.state/'cycle.lock').open('w') as lock:
   fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
   with self.assertRaises(Busy):c.run_once()
 def test_receipt_verified_before_ack(self):
  c=self.controller();r=c.run_once();p=c.outbox/(r['cycle_id']+'.json')
  receipt=dict(schema_version=1,cycle_id=r['cycle_id'],report_sha256=hashlib.sha256(p.read_bytes()).hexdigest(),repository_path='android-cycles/'+r['cycle_id']+'.json',commit_sha='a'*40,published_at_utc='2026-10-17T00:32:00Z')
  c.receipts.mkdir();atomic_json(c.receipts/p.name,receipt);c.reconcile_receipts();self.assertFalse(p.exists())
  self.assertEqual(json.loads((c.state/'published'/p.name).read_text()),receipt)
 def test_queue_bounds_and_symlink(self):
  c=self.controller();c.outbox.mkdir(parents=True)
  for _ in range(10):(c.outbox/(str(uuid.uuid4())+'.json')).write_text('{}')
  with self.assertRaises(ValueError):c.run_once()
  for p in c.outbox.iterdir():p.unlink()
  (c.outbox/'bad.json').symlink_to(self.root/'outside')
  with self.assertRaises(ValueError):c.run_once()
 def test_probe_closed_schema_and_fingerprint_failure(self):
  original=self.fake.probe
  for mode in ('extra','mismatch','not_ready'):
   def bad(id):
    r=original(id)
    if mode=='extra':r['url']='https://private'
    elif mode=='not_ready':r['engine_ready']=False
    else:r['memory_after']['fingerprint']='f'*64
    return r
   self.fake.probe=bad
   r=self.controller().run_once();self.assertEqual(r['status'],'FAILED')
 def test_conflicting_finalized_uuid_never_overwrites_outbox(self):
  def crash(phase):
   if phase=='FINALIZED':raise SystemExit()
  c=self.controller(hook=crash)
  with self.assertRaises(SystemExit):c.run_once()
  journal=json.loads((c.state/'journal.json').read_text());p=c.outbox/(journal['cycle_id']+'.json');p.write_bytes(b'{}')
  with self.assertRaises(ValueError):self.controller().run_once()
  self.assertEqual(p.read_bytes(),b'{}')
 def test_oversized_pending_file_rejected(self):
  c=self.controller();c.outbox.mkdir();p=c.outbox/(str(uuid.uuid4())+'.json');p.write_bytes(b'x'*1048577)
  with self.assertRaises(ValueError):c.run_once()
 def test_malformed_local_boolean_and_extra_field_fail_closed(self):
  original=self.fake.cycle
  for bad in ('bool','extra'):
   def cycle(id):
    r=original(id)
    if bad=='bool':r['memory_after']['verified_lessons']=True
    else:r['url']='https://private'
    return r
   self.fake.cycle=cycle;r=self.controller().run_once()
   self.assertEqual(r['status'],'FAILED');self.assertIsNone(r['diagnostics'])
 def test_short_host_duration_never_completed(self):
  original=self.fake.cycle
  def short(id):
   r=original(id);self.clock.value-=1;return r
  self.fake.cycle=short;self.assertEqual(self.controller().run_once()['status'],'FAILED')
 def test_bad_receipt_preserves_outbox(self):
  c=self.controller();r=c.run_once();p=c.outbox/(r['cycle_id']+'.json');c.receipts.mkdir()
  atomic_json(c.receipts/p.name,dict(schema_version=1,cycle_id=r['cycle_id'],report_sha256='f'*64,repository_path='android-cycles/'+p.name,commit_sha='a'*40,published_at_utc='2026-10-17T00:32:00Z'))
  with self.assertRaises(ValueError):c.reconcile_receipts()
  self.assertTrue(p.exists())
 def test_crash_during_ack_retries_same_receipt(self):
  c=self.controller();r=c.run_once();p=c.outbox/(r['cycle_id']+'.json');c.receipts.mkdir()
  receipt=dict(schema_version=1,cycle_id=r['cycle_id'],report_sha256=hashlib.sha256(p.read_bytes()).hexdigest(),repository_path='android-cycles/'+p.name,commit_sha='a'*40,published_at_utc='2026-10-17T00:32:00Z')
  atomic_json(c.receipts/p.name,receipt)
  def crash(phase):
   if phase=='ACK_STAGED':raise SystemExit()
  c.hook=crash
  with self.assertRaises(SystemExit):c.reconcile_receipts()
  self.assertTrue(p.exists());c.hook=lambda _:None;c.reconcile_receipts();self.assertFalse(p.exists())
 def test_outbox_upload_group_and_permissions(self):
  c=self.controller();r=c.run_once();p=c.outbox/(r['cycle_id']+'.json');st=p.stat()
  self.assertEqual(st.st_uid,0);self.assertEqual(st.st_gid,__import__('grp').getgrnam('aibrowserupload').gr_gid);self.assertEqual(st.st_mode&0o777,0o640)
  self.assertEqual(c.outbox.stat().st_mode&0o777,0o750)
 def test_hardlinked_outbox_rejected(self):
  c=self.controller();c.outbox.mkdir(parents=True);outside=self.root/'outside';outside.write_text('{}')
  __import__('os').link(outside,c.outbox/(str(uuid.uuid4())+'.json'))
  with self.assertRaises(ValueError):c.run_once()

class RetryTests(unittest.TestCase):
 def test_persistent_cli_retries_transient_stop_transport_error(self):
  import subprocess
  from unittest.mock import patch
  module_spec=importlib.util.spec_from_file_location('cycle_cli',ROOT/'scripts/android-cycle.py')
  cli=importlib.util.module_from_spec(module_spec);module_spec.loader.exec_module(cli)
  class Transient:
   def __init__(self):self.calls=0
   def run_once(self):
    self.calls+=1
    if self.calls==1:raise subprocess.CalledProcessError(1,['fixed-adb-stop'])
    raise KeyboardInterrupt()
  controller=Transient();sleeps=[]
  with patch.object(cli,'read_json',return_value={}),patch.object(cli,'Controller',return_value=controller),patch.object(cli.time,'sleep',side_effect=lambda s:sleeps.append(s)),patch.object(sys,'argv',['android-cycle.py']):
   with self.assertRaises(KeyboardInterrupt):cli.main()
  self.assertEqual(controller.calls,2);self.assertEqual(sleeps,[60])

class TransportTests(unittest.TestCase):
 def test_capture_kills_oversize_and_times_out(self):
  with self.assertRaises(ValueError):
   run_command([sys.executable,'-c',"import os,time;os.write(1,b'x'*1048577);time.sleep(10)"],capture=True,timeout=0.5)
  with self.assertRaises(__import__('subprocess').TimeoutExpired):
   run_command([sys.executable,'-c','import time;time.sleep(10)'],capture=True,timeout=0.1)
 def test_failed_instrumentation_never_reuses_previous_typed_output(self):
  import subprocess
  for command in ('probe','cycle','smoke'):
   id=str(uuid.uuid4());wire=fixture.report()
   local={k:wire[k] for k in ('schema_version','end_reason','app_elapsed_ms','diagnostics','memory_before','memory_after','version_code','source_revision')}
   local.update(cycle_id=id,engine_ready=True,status={'probe':'PROBE','cycle':'COMPLETED','smoke':'SMOKE'}[command])
   if command=='probe':local.update(memory_before=None,diagnostics=None)
   path='files/serverlab/'+('probe' if command=='probe' else id)+'.json'
   files={path:json.dumps(local).encode()};calls=[]
   def run(args,**kw):
    calls.append(args)
    if args[3:7]==['shell','run-as','com.aibrowser.universal.serverlab','rm']:
     files.pop(args[-1],None);return b''
    if args[3]=='exec-out':
     if args[-1] not in files:raise subprocess.CalledProcessError(1,args)
     return files[args[-1]]
    # am instrument exits zero even if the runner crashes before its write.
    return b''
   with self.subTest(command=command):
    with self.assertRaises(subprocess.CalledProcessError):Transport(run=run).command(command,id)
   self.assertEqual(calls[0][3:],['shell','run-as','com.aibrowser.universal.serverlab','rm','-f','--',path])
 def test_fresh_probe_written_after_cleanup_is_accepted(self):
  id=str(uuid.uuid4());path='files/serverlab/probe.json';files={path:b'old'}
  r=dict(schema_version=1,cycle_id=id,status='PROBE',end_reason='INTERRUPTED',app_elapsed_ms=0,diagnostics=None,memory_before=None,memory_after=fixture.report()['memory_after'],engine_ready=True,version_code=918,source_revision='a'*40)
  calls=[]
  def run(args,**kw):
   calls.append(args)
   if args[3:7]==['shell','run-as','com.aibrowser.universal.serverlab','rm']:
    files.pop(args[-1],None)
   elif args[3:6]==['shell','am','instrument']:
    self.assertNotIn(path,files);files[path]=json.dumps(r).encode()
   elif args[3]=='exec-out':return files[args[-1]]
   return b''
  self.assertEqual(Transport(run=run).probe(id),r)
  self.assertEqual(len(calls),3)
 def test_wait_boot_retries_fixed_device_until_ready(self):
  import subprocess
  clock=Clock();calls=[];attempts=iter([subprocess.CalledProcessError(1,['adb']),b'0',b'1'])
  def run(args,**kw):
   calls.append(args);value=next(attempts)
   if isinstance(value,Exception):raise value
   return value
  def sleep(seconds):clock.value+=seconds
  Transport(run=run,monotonic=clock,sleep=sleep).wait_boot()
  self.assertEqual(clock.value,4)
  self.assertTrue(all(args==['/opt/ai-browser-dev/android-sdk/platform-tools/adb','-s','emulator-5580','shell','getprop','sys.boot_completed'] for args in calls))
 def test_wait_boot_stops_at_300_second_deadline(self):
  clock=Clock();calls=[]
  def run(args,**kw):calls.append(args);return b'0'
  def sleep(seconds):clock.value+=seconds
  with self.assertRaises(TimeoutError):Transport(run=run,monotonic=clock,sleep=sleep).wait_boot()
  self.assertEqual(clock.value,300);self.assertEqual(len(calls),150)
 def test_cycle_waits_for_guest_before_instrumentation(self):
  clock=Clock();calls=[];ready=False;id=str(uuid.uuid4());wire=fixture.report()
  local={k:wire[k] for k in ('schema_version','status','end_reason','app_elapsed_ms','diagnostics','memory_before','memory_after','version_code','source_revision')}
  local.update(cycle_id=id,engine_ready=True)
  def sleep(seconds):clock.value+=seconds
  def run(args,**kw):
   nonlocal ready
   calls.append(args)
   if args[3:]==['shell','getprop','sys.boot_completed']:
    ready=clock.value>=2;return b'1' if ready else b'0'
   self.assertTrue(ready)
   if args[3]=='exec-out':return json.dumps(local).encode()
   return b''
  Transport(run=run,monotonic=clock,sleep=sleep).cycle(id)
  self.assertEqual(clock.value,2);self.assertEqual(calls[0][3:],['shell','getprop','sys.boot_completed'])
 def test_fixed_argv_typed_paths_only(self):
  calls=[]
  def run(args,**kw):calls.append(args);return b'{}'
  t=Transport(run=run);id=str(uuid.uuid4());t.pull(id)
  self.assertEqual(calls[0],['/opt/ai-browser-dev/android-sdk/platform-tools/adb','-s','emulator-5580','exec-out','run-as','com.aibrowser.universal.serverlab','cat','files/serverlab/'+id+'.json'])
  with self.assertRaises(ValueError):t.pull('../../cookies')
if __name__=='__main__':unittest.main()
