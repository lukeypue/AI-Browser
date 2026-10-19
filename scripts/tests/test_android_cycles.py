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
 def test_probe_transport_boundaries_are_private_closed_stages(self):
  import subprocess
  original=self.fake.probe
  phases=('CLEAN_OUTPUT','INVOKE_RUNNER','READ_OUTPUT','DECODE_OUTPUT','VALIDATE_OUTPUT')
  for target,prefix in ((1,'APP_PROBE'),(2,'EMULATOR_PROBE')):
   for phase in phases:
    calls=0
    def probe(id):
     nonlocal calls
     calls+=1
     if calls!=target:return original(id)
     def run(args,**kw):
      boundary='READ_OUTPUT' if args[3:8]==['shell','-T','run-as','com.aibrowser.universal.serverlab','cat'] else 'INVOKE_RUNNER' if args[3:6]==['shell','am','instrument'] else 'CLEAN_OUTPUT'
      if boundary==phase:raise subprocess.CalledProcessError(1,['PRIVATE_ARGV'],output=b'PRIVATE_STDOUT',stderr=b'PRIVATE_STDERR')
      if boundary=='READ_OUTPUT':return b'{PRIVATE_PAYLOAD' if phase=='DECODE_OUTPUT' else b'{"PRIVATE_FIELD":"PRIVATE_VALUE"}'
      return b''
     return Transport(run=run).probe(id)
    self.fake.probe=probe;c=self.controller();report=c.run_once()
    evidence=json.loads((c.state/'runtime-failures'/(report['cycle_id']+'.json')).read_text())
    with self.subTest(prefix=prefix,phase=phase):
     self.assertEqual(evidence['stage'],prefix+'_'+phase)
     self.assertEqual(evidence['reason'],'INVALID_RESULT' if phase in ('DECODE_OUTPUT','VALIDATE_OUTPUT') else 'COMMAND_FAILED')
     self.assertEqual(set(evidence),set('schema_version cycle_id source_revision stage reason local_sha256 expected_fingerprint observed_fingerprint app_restart_verified'.split()))
     self.assertEqual(evidence['app_restart_verified'],target==2)
     self.assertEqual(report['status'],'FAILED');self.assertIsNotNone(report['diagnostics'])
     self.assertEqual(set(report),set(fixture.report()))
     self.assertNotIn('PRIVATE_',json.dumps(evidence)+json.dumps(report))
 def test_untyped_probe_exception_cannot_inject_stage(self):
  import subprocess
  def probe(id):
   error=subprocess.SubprocessError('PRIVATE_ERROR');error.safe_phase='PRIVATE_PHASE';raise error
  self.fake.probe=probe;c=self.controller();report=c.run_once()
  evidence=json.loads((c.state/'runtime-failures'/(report['cycle_id']+'.json')).read_text())
  self.assertEqual(evidence['stage'],'APP_PROBE');self.assertEqual(evidence['reason'],'COMMAND_FAILED')
  self.assertNotIn('PRIVATE_',json.dumps(evidence)+json.dumps(report))
 def test_failed_emulator_probe_preserves_validated_app_evidence(self):
  original=self.fake.probe;calls=0
  def probe(id):
   nonlocal calls
   calls+=1
   if calls==2:raise OSError('PRIVATE_EXCEPTION_TEXT_NEVER_EXPORT')
   return original(id)
  self.fake.probe=probe;c=self.controller();r=c.run_once()
  self.assertEqual(r['status'],'FAILED');self.assertEqual(r['end_reason'],'RUNTIME_FAILED')
  self.assertIsNotNone(r['diagnostics']);self.assertEqual(r['app_elapsed_ms'],1800000)
  self.assertFalse(r['restart']['emulator_restart_verified'])
  evidence=json.loads((c.state/'runtime-failures'/(r['cycle_id']+'.json')).read_text())
  self.assertEqual(evidence['stage'],'EMULATOR_PROBE');self.assertEqual(evidence['reason'],'OS_ERROR')
  self.assertTrue(evidence['app_restart_verified'])
  self.assertNotIn('PRIVATE_EXCEPTION_TEXT_NEVER_EXPORT',json.dumps(evidence)+json.dumps(r))
 def test_emulator_fingerprint_rejection_retains_digest_only(self):
  original=self.fake.probe;calls=0
  def probe(id):
   nonlocal calls
   calls+=1;r=original(id)
   if calls==2:r['memory_after']['fingerprint']='f'*64
   return r
  self.fake.probe=probe;c=self.controller();r=c.run_once()
  self.assertIsNotNone(r['diagnostics']);self.assertEqual(r['status'],'FAILED')
  evidence=json.loads((c.state/'runtime-failures'/(r['cycle_id']+'.json')).read_text())
  self.assertEqual(evidence['stage'],'EMULATOR_MEMORY');self.assertEqual(evidence['reason'],'INVALID_RESULT')
  self.assertEqual(evidence['expected_fingerprint'],r['memory_after']['fingerprint'])
  self.assertEqual(evidence['observed_fingerprint'],'f'*64)
  self.assertEqual(set(evidence),set('schema_version cycle_id source_revision stage reason local_sha256 expected_fingerprint observed_fingerprint app_restart_verified'.split()))
 def test_unvalidated_or_wrong_source_app_payload_never_retained(self):
  original=self.fake.cycle
  for mode in ('extra','source'):
   def cycle(id):
    r=original(id)
    if mode=='extra':r['url']='PRIVATE_UNTRUSTED_URL'
    else:r['source_revision']='f'*40
    return r
   self.fake.cycle=cycle;c=self.controller();r=c.run_once()
   self.assertEqual(r['status'],'FAILED');self.assertIsNone(r['diagnostics'])
   evidence_path=c.state/'runtime-failures'/(r['cycle_id']+'.json')
   self.assertTrue(evidence_path.is_file());evidence=json.loads(evidence_path.read_text())
   self.assertIsNone(evidence['local_sha256']);self.assertNotIn('PRIVATE_UNTRUSTED_URL',json.dumps(evidence))
 def test_failure_evidence_is_private_and_existing_reports_stay_immutable(self):
  import os
  def cycle(id):raise TimeoutError('PRIVATE_TIMEOUT_CONTEXT')
  self.fake.cycle=cycle;c=self.controller();r=c.run_once()
  evidence=c.state/'runtime-failures'/(r['cycle_id']+'.json')
  self.assertTrue(evidence.is_file());self.assertEqual(evidence.stat().st_mode&0o777,0o600)
  self.assertEqual(evidence.parent.stat().st_mode&0o777,0o700)
  value=json.loads(evidence.read_text());self.assertEqual(value['reason'],'TIMEOUT')
  self.assertEqual(value['stage'],'APP_CYCLE');self.assertIsNone(r['diagnostics'])
  before=(c.outbox/(r['cycle_id']+'.json')).read_bytes()
  c.run_once();self.assertEqual((c.outbox/(r['cycle_id']+'.json')).read_bytes(),before)
 def test_inconsistent_nested_app_identity_is_never_retained(self):
  original=self.fake.cycle
  for field,value in (('app_revision','f'*40),('version_code',919)):
   def cycle(id):
    r=original(id);r['diagnostics'][field]=value;return r
   self.fake.cycle=cycle;c=self.controller()
   try:r=c.run_once()
   except ValueError:self.fail('inconsistent nested identity escaped FAILED finalization')
   self.assertEqual(r['status'],'FAILED');self.assertIsNone(r['diagnostics'])
   evidence=json.loads((c.state/'runtime-failures'/(r['cycle_id']+'.json')).read_text())
   self.assertIsNone(evidence['local_sha256']);self.assertEqual(evidence['stage'],'APP_IDENTITY')
 def test_private_evidence_write_failure_still_attempts_cleanup(self):
  from unittest.mock import patch
  from android_cycles import controller as module
  original=module.atomic_json
  def write(path,value,*args,**kwargs):
   if Path(path).parent.name=='runtime-failures':raise OSError('private evidence unavailable')
   return original(path,value,*args,**kwargs)
  def cycle(id):raise TimeoutError('private timeout text')
  self.fake.cycle=cycle;c=self.controller()
  with patch.object(module,'atomic_json',side_effect=write):
   with self.assertRaises(OSError):c.run_once()
  self.assertIn('stop',self.fake.calls)
  self.assertTrue((c.state/'journal.json').is_file())
 def test_finalization_failure_is_not_mislabeled_as_memory_rejection(self):
  seen=False
  def hook(phase):
   nonlocal seen
   if phase=='FINALIZED' and not seen:seen=True;raise OSError('private finalization failure')
  c=self.controller(hook=hook);r=c.run_once()
  self.assertEqual(r['status'],'FAILED');self.assertIsNotNone(r['diagnostics'])
  evidence=json.loads((c.state/'runtime-failures'/(r['cycle_id']+'.json')).read_text())
  self.assertEqual(evidence['stage'],'FINALIZE');self.assertEqual(evidence['reason'],'OS_ERROR')
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
 def test_missing_typed_file_is_read_command_failure_not_json_decode_failure(self):
  import subprocess
  for command in ('probe','cycle','smoke'):
   calls=[]
   def run(args,**kw):
    calls.append(args)
    if args[3]=='exec-out':return b'cat: PRIVATE_TYPED_PATH: PRIVATE_REMOTE_ERROR'
    if args[3:8]==['shell','-T','run-as','com.aibrowser.universal.serverlab','cat']:
     raise subprocess.CalledProcessError(1,args,output=b'PRIVATE_STDOUT',stderr=b'PRIVATE_STDERR')
    return b''
   with self.subTest(command=command):
    with self.assertRaises(subprocess.SubprocessError) as caught:Transport(run=run).command(command,str(uuid.uuid4()))
    error=caught.exception
    self.assertEqual(getattr(error,'safe_phase',None),'READ_OUTPUT')
    self.assertEqual(getattr(error,'safe_reason',None),'COMMAND_FAILED')
    self.assertEqual(set(vars(error)),{'safe_phase','safe_reason'})
    self.assertIsNone(error.__cause__);self.assertIsNone(error.__context__)
    self.assertNotIn('PRIVATE_',str(error)+repr(vars(error)))
    self.assertEqual(calls[-1][3:8],['shell','-T','run-as','com.aibrowser.universal.serverlab','cat'])
    self.assertEqual(len(calls),3,'a failed read must not retry or reinvoke instrumentation')
 def test_transport_failure_rejects_unbounded_phase_or_reason(self):
  from android_cycles import transport
  failure=getattr(transport,'TransportFailure',None)
  self.assertIsNotNone(failure)
  for phase,reason in (('PRIVATE_PHASE','COMMAND_FAILED'),('READ_OUTPUT','PRIVATE_REASON')):
   with self.subTest(phase=phase,reason=reason):
    with self.assertRaises(ValueError) as caught:failure(phase,reason)
    self.assertNotIn('PRIVATE_',str(caught.exception))
 def test_probe_command_failure_identifies_boundary_without_raw_context(self):
  import subprocess
  for phase in ('CLEAN_OUTPUT','INVOKE_RUNNER','READ_OUTPUT'):
   def run(args,**kw):
    boundary='READ_OUTPUT' if args[3:8]==['shell','-T','run-as','com.aibrowser.universal.serverlab','cat'] else 'INVOKE_RUNNER' if args[3:6]==['shell','am','instrument'] else 'CLEAN_OUTPUT'
    if boundary==phase:raise subprocess.CalledProcessError(1,['PRIVATE_ARGV'],output=b'PRIVATE_STDOUT',stderr=b'PRIVATE_STDERR')
    return b''
   with self.subTest(phase=phase):
    with self.assertRaises(Exception) as caught:Transport(run=run).probe(str(uuid.uuid4()))
    error=caught.exception
    self.assertEqual(getattr(error,'safe_phase',None),phase)
    self.assertEqual(getattr(error,'safe_reason',None),'COMMAND_FAILED')
    self.assertEqual(set(vars(error)),{'safe_phase','safe_reason'})
    self.assertIsNone(error.__cause__);self.assertIsNone(error.__context__)
    self.assertNotIn('PRIVATE_',str(error)+repr(vars(error)))
 def test_probe_malformed_payload_distinguishes_decode_from_validation(self):
  for raw,phase in ((b'{PRIVATE_PAYLOAD','DECODE_OUTPUT'),(b'\xffPRIVATE_PAYLOAD','DECODE_OUTPUT'),(b'{"PRIVATE_FIELD":"PRIVATE_VALUE"}','VALIDATE_OUTPUT')):
   def run(args,**kw):return raw if args[3:8]==['shell','-T','run-as','com.aibrowser.universal.serverlab','cat'] else b''
   with self.subTest(phase=phase):
    with self.assertRaises(Exception) as caught:Transport(run=run).probe(str(uuid.uuid4()))
    self.assertEqual(getattr(caught.exception,'safe_phase',None),phase)
    self.assertEqual(getattr(caught.exception,'safe_reason',None),'INVALID_RESULT')
    self.assertNotIn('PRIVATE_',str(caught.exception)+repr(vars(caught.exception)))
 def test_probe_timeout_and_os_error_keep_reason_with_boundary(self):
  import subprocess
  for error,reason in ((subprocess.TimeoutExpired(['PRIVATE_ARGV'],1,output=b'PRIVATE_OUTPUT'),'TIMEOUT'),(OSError('PRIVATE_ERROR'),'OS_ERROR')):
   def run(args,**kw):raise error
   with self.subTest(reason=reason):
    with self.assertRaises(Exception) as caught:Transport(run=run).probe(str(uuid.uuid4()))
    self.assertEqual(getattr(caught.exception,'safe_phase',None),'CLEAN_OUTPUT')
    self.assertEqual(getattr(caught.exception,'safe_reason',None),reason)
    self.assertNotIn('PRIVATE_',str(caught.exception)+repr(vars(caught.exception)))
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
    if args[3:8]==['shell','-T','run-as','com.aibrowser.universal.serverlab','cat']:
     if args[-1] not in files:raise subprocess.CalledProcessError(1,args)
     return files[args[-1]]
    # am instrument exits zero even if the runner crashes before its write.
    return b''
   with self.subTest(command=command):
    with self.assertRaises(subprocess.SubprocessError) as caught:Transport(run=run).command(command,id)
    self.assertEqual(getattr(caught.exception,'safe_phase',None),'READ_OUTPUT')
    self.assertEqual(getattr(caught.exception,'safe_reason',None),'COMMAND_FAILED')
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
   elif args[3:8]==['shell','-T','run-as','com.aibrowser.universal.serverlab','cat']:return files[args[-1]]
   return b''
  self.assertEqual(Transport(run=run).probe(id),r)
  self.assertEqual(len(calls),3)
 def test_boot_completed_is_insufficient_until_storage_and_package_context_are_ready(self):
  import subprocess
  clock=Clock();calls=[]
  def run(args,**kw):
   tail=args[3:];calls.append((clock.value,tail))
   self.assertGreater(kw['timeout'],0);self.assertLessEqual(kw['timeout'],10)
   if tail==['shell','getprop','sys.boot_completed']:return b'1'
   if tail==['shell','getprop','sys.user.0.ce_available']:return b'true' if clock.value>=2 else b'false'
   self.assertEqual(tail,['shell','run-as','com.aibrowser.universal.serverlab','/system/bin/true'])
   if clock.value<4:raise subprocess.CalledProcessError(1,args,stderr=b'PRIVATE_PACKAGE_ERROR')
   return b''
  Transport(run=run,monotonic=clock,sleep=lambda seconds:setattr(clock,'value',clock.value+seconds)).wait_boot()
  self.assertEqual(clock.value,4)
  self.assertFalse(any(tail[1:2]==['run-as'] and tick<2 for tick,tail in calls))
  self.assertTrue(all('install' not in tail and 'rm' not in tail and 'cat' not in tail for _,tail in calls))
 def test_missing_package_context_remains_a_bounded_failure_without_reinstall(self):
  import subprocess
  clock=Clock();calls=[]
  def run(args,**kw):
   calls.append(args);self.assertLessEqual(kw['timeout'],300-clock.value)
   if args[3:]==['shell','getprop','sys.boot_completed']:return b'1'
   if args[3:]==['shell','getprop','sys.user.0.ce_available']:return b'true'
   raise subprocess.CalledProcessError(1,args,stderr=b'PRIVATE_PACKAGE_ERROR')
  with self.assertRaises(TimeoutError) as caught:
   Transport(run=run,monotonic=clock,sleep=lambda seconds:setattr(clock,'value',clock.value+seconds)).wait_boot()
  self.assertEqual(clock.value,300)
  self.assertNotIn('PRIVATE_',str(caught.exception));self.assertTrue(all('install' not in x and 'clear' not in x for x in calls))
 def test_missing_credential_storage_property_never_starts_package(self):
  clock=Clock();calls=[]
  def run(args,**kw):
   calls.append(args[3:]);return b'1' if args[-1]=='sys.boot_completed' else b''
  with self.assertRaises(TimeoutError):
   Transport(run=run,monotonic=clock,sleep=lambda seconds:setattr(clock,'value',clock.value+seconds)).wait_boot()
  self.assertEqual(clock.value,300);self.assertTrue(all('run-as' not in x for x in calls))
 def test_package_readiness_shares_original_boot_deadline(self):
  clock=Clock();calls=[]
  def run(args,**kw):
   calls.append((args[3:],kw['timeout']))
   if args[-1]=='sys.boot_completed':clock.value=299.5;return b'1'
   self.assertEqual(args[-1],'sys.user.0.ce_available')
   self.assertEqual(kw['timeout'],0.5);clock.value=300;return b'true'
  with self.assertRaises(TimeoutError):
   Transport(run=run,monotonic=clock,sleep=lambda seconds:setattr(clock,'value',clock.value+seconds)).wait_boot()
  self.assertEqual(clock.value,300);self.assertEqual(len(calls),2)
 def test_wait_boot_retries_fixed_device_until_ready(self):
  import subprocess
  clock=Clock();calls=[];attempts=iter([subprocess.CalledProcessError(1,['adb']),b'0',b'1'])
  def run(args,**kw):
   calls.append(args)
   if args[-1]=='sys.user.0.ce_available':return b'true'
   if args[-1]=='/system/bin/true':return b''
   value=next(attempts)
   if isinstance(value,Exception):raise value
   return value
  def sleep(seconds):clock.value+=seconds
  Transport(run=run,monotonic=clock,sleep=sleep).wait_boot()
  self.assertEqual(clock.value,4)
  self.assertEqual([x[3:] for x in calls], [['shell','getprop','sys.boot_completed']]*3+[['shell','getprop','sys.user.0.ce_available'],['shell','run-as','com.aibrowser.universal.serverlab','/system/bin/true']])
  self.assertTrue(all(x[:3]==['/opt/ai-browser-dev/android-sdk/platform-tools/adb','-s','emulator-5580'] for x in calls))
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
   if args[3:]==['shell','getprop','sys.user.0.ce_available']:return b'true'
   if args[3:8]==['shell','-T','run-as','com.aibrowser.universal.serverlab','cat']:return json.dumps(local).encode()
   return b''
  Transport(run=run,monotonic=clock,sleep=sleep).cycle(id)
  self.assertEqual(clock.value,2);self.assertEqual(calls[0][3:],['shell','getprop','sys.boot_completed'])
 def test_fixed_argv_typed_paths_only(self):
  calls=[]
  def run(args,**kw):calls.append(args);return b'{}'
  t=Transport(run=run);id=str(uuid.uuid4());t.pull(id)
  self.assertEqual(calls[0],['/opt/ai-browser-dev/android-sdk/platform-tools/adb','-s','emulator-5580','shell','-T','run-as','com.aibrowser.universal.serverlab','cat','files/serverlab/'+id+'.json'])
  with self.assertRaises(ValueError):t.pull('../../cookies')
if __name__=='__main__':unittest.main()
