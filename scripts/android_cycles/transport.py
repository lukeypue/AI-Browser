"""Fixed emulator and lab-only transport. Never pull raw logs or user data."""
import json,os,selectors,subprocess,time,uuid
from . import _wire
ADB='/opt/ai-browser-dev/android-sdk/platform-tools/adb'
SERIAL='emulator-5580'
PACKAGE='com.aibrowser.universal.serverlab'
RUNNER='com.aibrowser.universal.serverlab.test/com.appgate.tv.serverlab.ServerLabInstrumentation'
SERVICE='aibrowser-android-emulator.service'
LOCAL_KEYS=set('schema_version cycle_id status end_reason app_elapsed_ms diagnostics memory_before memory_after engine_ready version_code source_revision'.split())
def strict_load(raw):
 def pairs(items):
  result={}
  for k,v in items:
   if k in result:raise ValueError('duplicate_field')
   result[k]=v
  return result
 if len(raw)>_wire.LIMIT:raise ValueError('report_size')
 return json.loads(raw,object_pairs_hook=pairs,parse_constant=lambda _:(_ for _ in ()).throw(ValueError('invalid_number')))
def validate_local(r,cycle_id,command):
 _wire.exact(r,LOCAL_KEYS);_wire.require(type(r['schema_version']) is int and r['schema_version']==1)
 _wire.identifier(r['cycle_id']);_wire.require(r['cycle_id']==cycle_id)
 _wire.require(r['status'] in ({'COMPLETED','INTERRUPTED','FAILED'} if command=='cycle' else {command.upper()}))
 _wire.require(r['end_reason'] in ('DEADLINE','INTERRUPTED','RUNTIME_FAILED'))
 _wire.integer(r['app_elapsed_ms'],7200000);_wire.require(type(r['engine_ready']) is bool)
 _wire.integer(r['version_code'],2147483647);_wire.require(r['version_code']>0);_wire.digest(r['source_revision'],40)
 if r['diagnostics'] is not None:_wire.diagnostics(r['diagnostics'])
 for k in ('memory_before','memory_after'):
  if r[k] is not None:_wire.memory(r[k])
 if command=='probe':_wire.require(r['engine_ready'] and r['memory_after'] is not None and r['memory_before'] is None and r['diagnostics'] is None)
 if r['status']=='COMPLETED':
  _wire.require(r['engine_ready'] and r['app_elapsed_ms']>=1800000 and r['end_reason']=='DEADLINE')
  _wire.require(r['diagnostics'] is not None and r['memory_before'] is not None and r['memory_after'] is not None)
 return r
def run_command(argv,timeout=60,capture=False):
 if not capture:
  subprocess.run(argv,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=timeout,check=True)
  return b''
 # Incremental bounded pipe reads prevent malicious typed output filling RAM/disk.
 process=subprocess.Popen(argv,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
 chunks=[];length=0;deadline=time.monotonic()+timeout
 try:
  with selectors.DefaultSelector() as selector:
   selector.register(process.stdout,selectors.EVENT_READ)
   while True:
    remaining=deadline-time.monotonic()
    if remaining<=0:raise subprocess.TimeoutExpired(argv,timeout)
    if not selector.select(remaining):raise subprocess.TimeoutExpired(argv,timeout)
    chunk=os.read(process.stdout.fileno(),min(65536,_wire.LIMIT+1-length))
    if not chunk:break
    chunks.append(chunk);length+=len(chunk)
    if length>_wire.LIMIT:raise ValueError('report_size')
  code=process.wait(timeout=max(0.001,deadline-time.monotonic()))
  if code:raise subprocess.CalledProcessError(code,argv)
  return b''.join(chunks)
 finally:
  if process.poll() is None:process.kill()
  process.wait();process.stdout.close()

class Transport:
 def __init__(self,run=run_command,monotonic=time.monotonic,sleep=time.sleep):self.run=run;self.monotonic=monotonic;self.sleep=sleep
 def adb(self,*args,**kw):return self.run([ADB,'-s',SERIAL,*args],**kw)
 def pull(self,cycle_id,probe=False):
  _wire.identifier(cycle_id)
  return strict_load(self.adb('exec-out','run-as',PACKAGE,'cat','files/serverlab/'+('probe' if probe else cycle_id)+'.json',capture=True,timeout=30))
 def command(self,command,cycle_id):
  _wire.require(command in ('cycle','probe','smoke'));_wire.identifier(cycle_id)
  # Android shell can return zero when instrumentation crashes. Remove only
  # this command's typed output first so a missing new write cannot replay an
  # earlier process/emulator probe, smoke or same-UUID cycle result.
  output='files/serverlab/'+('probe' if command=='probe' else cycle_id)+'.json'
  self.adb('shell','run-as',PACKAGE,'rm','-f','--',output,timeout=30)
  self.adb('shell','am','instrument','-w','-r','-e','command',command,'-e','cycle_id',cycle_id,RUNNER,timeout=2100 if command=='cycle' else 180)
  return validate_local(self.pull(cycle_id,command=='probe'),cycle_id,command)
 def cycle(self,cycle_id):
  self.wait_boot();return self.command('cycle',cycle_id)
 def probe(self,cycle_id):return self.command('probe',cycle_id)
 def stop(self):self.adb('shell','am','force-stop',PACKAGE,timeout=30)
 def restart(self):
  self.run(['/usr/bin/systemctl','restart',SERVICE],timeout=180)
  self.wait_boot()
 def wait_boot(self):
  deadline=self.monotonic()+300
  while self.monotonic()<deadline:
   try:
    if self.adb('shell','getprop','sys.boot_completed',capture=True,timeout=min(10,max(0.001,deadline-self.monotonic()))).strip()==b'1':return
   except (subprocess.SubprocessError,OSError):pass
   self.sleep(min(2,max(0,deadline-self.monotonic())))
  raise TimeoutError('emulator_boot_timeout')
