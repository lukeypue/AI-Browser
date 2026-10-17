"""Durable monotonic Android cycles. Incomplete journal work never counts complete."""
import fcntl,grp,hashlib,json,os,re,stat,time,uuid
from contextlib import contextmanager
from datetime import datetime,timezone
from pathlib import Path
from . import canonical_report,validate_android_cycle,_wire
from .transport import Transport,strict_load,validate_local
class Busy(RuntimeError):pass
def safe_path(path):
 path=Path(path)
 for p in (path,*path.parents):
  if p.is_symlink():raise ValueError('symlink_rejected')
 return path
def fsync_dir(path):
 fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
 try:os.fsync(fd)
 finally:os.close(fd)
def mkdir(path,mode):
 safe_path(path);path.mkdir(parents=True,exist_ok=True);os.chmod(path,mode)
def atomic_bytes(path,raw,mode=0o600,gid=None):
 path=safe_path(path);temporary=path.parent/('.'+path.name+'.'+str(uuid.uuid4())+'.tmp')
 fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,mode)
 try:
  with os.fdopen(fd,'wb') as out:
   os.fchmod(out.fileno(),mode)
   if gid is not None:os.fchown(out.fileno(),0,gid)
   out.write(raw);out.flush();os.fsync(out.fileno())
  os.replace(temporary,path);fsync_dir(path.parent)
 finally:
  if temporary.exists():temporary.unlink()
def atomic_json(path,value,mode=0o600):atomic_bytes(Path(path),canonical_report(value),mode)
def read_bytes(path,limit=_wire.LIMIT):
 path=safe_path(path);fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
 with os.fdopen(fd,'rb') as inp:
  info=os.fstat(inp.fileno())
  if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or info.st_size>limit:raise ValueError('invalid_file')
  raw=inp.read(limit+1)
 if len(raw)>limit:raise ValueError('report_size')
 return raw
def read_json(path,limit=_wire.LIMIT):
 raw=read_bytes(path,limit)
 if limit==_wire.LIMIT:return strict_load(raw)
 # Journals can contain both local and finalized wire copies.
 return json.loads(raw,parse_constant=lambda _:(_ for _ in ()).throw(ValueError('invalid_number')))
UUID_PATTERN=r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
def cleanup_owned_temps(path,kind,upload_gid):
 # Caller holds cycle.lock. Directory FDs and no-follow stat keep cleanup
 # inside the trusted directory; unknown names and final files are untouched.
 safe_path(path)
 fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
 try:
  directory=os.fstat(fd)
  private=kind=='journal' or kind=='receipt'
  _wire.require(stat.S_ISDIR(directory.st_mode) and directory.st_uid==0)
  _wire.require(stat.S_IMODE(directory.st_mode)==(0o700 if private else 0o750))
  _wire.require(directory.st_gid in ({0,upload_gid} if private else {upload_gid}))
  pattern=(r'\.journal\.json\.'+UUID_PATTERN+r'\.tmp') if kind=='journal' else (r'\.'+UUID_PATTERN+r'\.json\.'+UUID_PATTERN+r'\.tmp')
  candidates=[]
  for name in os.listdir(fd):
   if re.fullmatch(pattern,name) is None:continue
   info=os.stat(name,dir_fd=fd,follow_symlinks=False)
   _wire.require(stat.S_ISREG(info.st_mode) and info.st_uid==0 and info.st_nlink==1)
   # SIGKILL may land between create/chmod/chown. Those intermediate
   # modes/groups remain private or uploader-readable and contain no links.
   _wire.require(stat.S_IMODE(info.st_mode) in ({0o600} if private else {0o600,0o640}))
   _wire.require(info.st_gid in {0,upload_gid})
   candidates.append(name)
  for name in candidates:os.unlink(name,dir_fd=fd)
  if candidates:os.fsync(fd)
 finally:os.close(fd)
def utc():return datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
def boot_id():return Path('/proc/sys/kernel/random/boot_id').read_text().strip()
class Controller:
 def __init__(self,config,state=Path('/var/lib/aibrowser-android-lab/controller'),outbox=Path('/var/lib/aibrowser-android-lab/outbox'),receipts=Path('/var/lib/aibrowser-android-publish/receipts'),transport=None,monotonic=time.monotonic,boot_id=boot_id,now=utc,hook=lambda phase:None):
  _wire.exact(config,set('source_revision app_apk_sha256 test_apk_sha256 fixture_sha256 version_code'.split()))
  _wire.digest(config['source_revision'],40)
  for k in ('app_apk_sha256','test_apk_sha256','fixture_sha256'):_wire.digest(config[k])
  _wire.integer(config['version_code'],2147483647);_wire.require(config['version_code']>0)
  self.config=dict(config);self.state=Path(state);self.outbox=Path(outbox);self.receipts=Path(receipts)
  self.transport=transport or Transport();self.monotonic=monotonic;self.boot_id=boot_id;self.now=now;self.hook=hook
 @contextmanager
 def locked(self):
  mkdir(self.state,0o700);mkdir(self.outbox,0o750)
  self.upload_gid=grp.getgrnam('aibrowserupload').gr_gid;os.chown(self.outbox,0,self.upload_gid)
  fd=os.open(safe_path(self.state/'cycle.lock'),os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
  try:
   try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
   except BlockingIOError as error:raise Busy('cycle_locked') from error
   cleanup_owned_temps(self.state,'journal',self.upload_gid)
   cleanup_owned_temps(self.outbox,'report',self.upload_gid)
   archive=safe_path(self.state/'published')
   if archive.exists():cleanup_owned_temps(archive,'receipt',self.upload_gid)
   yield
  finally:os.close(fd)
 def stage(self,journal,phase):
  journal['phase']=phase;atomic_json(self.state/'journal.json',journal);self.hook(phase)
 def elapsed(self,journal):
  if journal['boot_id']!=self.boot_id():return 0
  return max(0,min(7200000,int((self.monotonic()-journal['started_monotonic'])*1000)))
 def pending(self):
  files=[];total=0
  for p in self.outbox.iterdir():
   safe_path(p)
   _wire.require(p.suffix=='.json');_wire.identifier(p.stem)
   raw=read_bytes(p);files.append(p);total+=len(raw)
  _wire.require(len(files)<=10 and total<=10*1048576)
  return files,total
 def reconcile_receipts(self):
  # Called while cycle.lock is held in operational flow.
  files,_=self.pending()
  for p in files:
   receipt_file=safe_path(self.receipts/p.name)
   if not receipt_file.exists():continue
   receipt=read_json(receipt_file)
   _wire.exact(receipt,set('schema_version cycle_id report_sha256 repository_path commit_sha published_at_utc'.split()))
   _wire.require(type(receipt['schema_version']) is int and receipt['schema_version']==1 and receipt['cycle_id']==p.stem)
   _wire.digest(receipt['report_sha256']);_wire.digest(receipt['commit_sha'],40);_wire.timestamp(receipt['published_at_utc'])
   _wire.require(receipt['repository_path']=='android-cycles/'+p.name)
   _wire.require(receipt['report_sha256']==hashlib.sha256(read_bytes(p)).hexdigest())
   archive=self.state/'published';mkdir(archive,0o700);destination=archive/p.name
   if destination.exists():_wire.require(read_json(destination)==receipt)
   else:atomic_json(destination,receipt)
   self.hook('ACK_STAGED');p.unlink();fsync_dir(self.outbox);self.hook('ACKED')
 def report(self,journal,status,local=None,restart=None):
  local=local or {};diagnostics=local.get('diagnostics')
  return dict(schema_version=1,source='ANDROID_SERVER_CYCLE',cycle_id=journal['cycle_id'],runtime='ANDROID_EMULATOR',content='SYNTHETIC_FIXTURES',app_build_type='SERVER_LAB_DEBUG',**journal['config'],started_at_utc=journal['started_at_utc'],finished_at_utc=self.now(),status=status,end_reason={'COMPLETED':'DEADLINE','INTERRUPTED':'INTERRUPTED','FAILED':'RUNTIME_FAILED'}[status],elapsed_ms=journal.get('cycle_elapsed_ms',self.elapsed(journal)),app_elapsed_ms=local.get('app_elapsed_ms',0),diagnostics=diagnostics,diagnostics_sha256=hashlib.sha256(canonical_report(diagnostics)).hexdigest() if diagnostics is not None else None,memory_before=local.get('memory_before'),memory_after=local.get('memory_after'),restart=restart or dict(app_restart_verified=False,emulator_restart_verified=False,before_fingerprint=None,after_app_fingerprint=None,after_emulator_fingerprint=None,verified_at_utc=None))
 def enqueue(self,journal):
  r=validate_android_cycle(journal['report']);raw=canonical_report(r);p=self.outbox/(r['cycle_id']+'.json')
  if p.exists():_wire.require(read_bytes(p)==raw)
  else:
   files,total=self.pending();_wire.require(len(files)<10 and total+len(raw)<=10*1048576);atomic_bytes(p,raw,0o640,self.upload_gid)
  self.hook('OUTBOX');(self.state/'journal.json').unlink();fsync_dir(self.state);return r
 def finalize(self,journal,report):
  validate_android_cycle(report);journal['report']=report;self.stage(journal,'FINALIZED');return self.enqueue(journal)
 def run_once(self):
  with self.locked():
   self.reconcile_receipts();journal_file=self.state/'journal.json'
   if journal_file.exists():
    journal=read_json(journal_file,4*1048576);_wire.identifier(journal['cycle_id'])
    if journal['phase']=='FINALIZED':return self.enqueue(journal)
    # Older journals receive the exact pinned config from this deployment.
    journal.setdefault('config',self.config)
    self.transport.stop()
    return self.finalize(journal,self.report(journal,'INTERRUPTED'))
   files,total=self.pending();_wire.require(len(files)<10 and total<=9*1048576)
   journal=dict(phase='RUNNING',cycle_id=str(uuid.uuid4()),started_at_utc=self.now(),started_monotonic=self.monotonic(),boot_id=self.boot_id(),config=self.config)
   self.stage(journal,'RUNNING')
   local=None;stage='APP_CYCLE';fingerprint=None;observed=None;app_verified=False
   try:
    local=validate_local(self.transport.cycle(journal['cycle_id']),journal['cycle_id'],'cycle')
    stage='APP_IDENTITY'
    _wire.require(local['source_revision']==self.config['source_revision'] and local['version_code']==self.config['version_code'])
    if local['diagnostics'] is not None:
     _wire.require(local['diagnostics']['app_revision']==self.config['source_revision'] and
                   local['diagnostics']['version_code']==self.config['version_code'])
    journal['local']=local;journal['cycle_elapsed_ms']=self.elapsed(journal);self.stage(journal,'APP_COMPLETED')
    if local['status']!='COMPLETED':
     stage='APP_STOP';self.transport.stop()
     stage='FINALIZE';return self.finalize(journal,self.report(journal,local['status'],local))
    fingerprint=local['memory_after']['fingerprint'];stage='HOST_DURATION'
    _wire.require(journal['boot_id']==self.boot_id() and journal['cycle_elapsed_ms']>=1800000)
    stage='APP_STOP';self.transport.stop()
    stage='APP_PROBE';app=self.transport.probe(journal['cycle_id'])
    stage='APP_PROBE_SCHEMA';validate_local(app,journal['cycle_id'],'probe')
    stage='APP_PROBE_IDENTITY';_wire.require(app['source_revision']==self.config['source_revision'] and app['version_code']==self.config['version_code'])
    stage='APP_MEMORY';observed=app['memory_after']['fingerprint']
    _wire.require(observed==fingerprint);app_verified=True;self.stage(journal,'APP_RESTARTED')
    stage='EMULATOR_RESTART';observed=None;self.transport.restart()
    stage='EMULATOR_PROBE';emulator=self.transport.probe(journal['cycle_id'])
    stage='EMULATOR_PROBE_SCHEMA';validate_local(emulator,journal['cycle_id'],'probe')
    stage='EMULATOR_PROBE_IDENTITY';_wire.require(emulator['source_revision']==self.config['source_revision'] and emulator['version_code']==self.config['version_code'])
    stage='EMULATOR_MEMORY';observed=emulator['memory_after']['fingerprint']
    _wire.require(observed==fingerprint);self.stage(journal,'EMULATOR_RESTARTED')
    restart=dict(app_restart_verified=True,emulator_restart_verified=True,before_fingerprint=fingerprint,after_app_fingerprint=app['memory_after']['fingerprint'],after_emulator_fingerprint=emulator['memory_after']['fingerprint'],verified_at_utc=self.now())
    stage='FINALIZE';return self.finalize(journal,self.report(journal,'COMPLETED',local,restart))
   except (OSError,ValueError,TimeoutError,__import__('subprocess').SubprocessError) as error:
    # Retain only an app result that already passed closed-schema and source pins.
    # A restart rejection never becomes COMPLETED, and untrusted probe/exception
    # contents never enter either the report or the private failure record.
    validated=journal.get('local')
    reason=('TIMEOUT' if isinstance(error,(TimeoutError,__import__('subprocess').TimeoutExpired)) else
            'INVALID_RESULT' if isinstance(error,ValueError) else
            'COMMAND_FAILED' if isinstance(error,__import__('subprocess').SubprocessError) else 'OS_ERROR')
    try:
     failures=self.state/'runtime-failures';mkdir(failures,0o700)
     atomic_json(failures/(journal['cycle_id']+'.json'),dict(schema_version=1,cycle_id=journal['cycle_id'],
       source_revision=self.config['source_revision'],stage=stage,reason=reason,
       local_sha256=hashlib.sha256(canonical_report(validated)).hexdigest() if validated else None,
       expected_fingerprint=fingerprint,observed_fingerprint=observed,app_restart_verified=app_verified))
    finally:self.transport.stop()
    return self.finalize(journal,self.report(journal,'FAILED',validated))
