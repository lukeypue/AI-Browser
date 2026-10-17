"""Loopback-only upload receiver. No credentials, IPs or request bodies are logged."""
import argparse,hashlib,hmac,json,os,re,secrets,sqlite3,time,uuid,sys
if __name__=='__main__':sys.modules.setdefault('receiver',sys.modules[__name__])
from contextlib import contextmanager
from datetime import datetime,timezone
from http.server import BaseHTTPRequestHandler,HTTPServer
from pathlib import Path

from live_progress import validate_live,canonical_live,live_document,LIVE_LIMIT,LIVE_TTL,LIVE_INTERVAL,LIVE_HOURLY

LIMIT=1048576
CODES={'learning_test_start','learning_test_end','learning_test_interrupted','step','task_finished','verified_progress','need_human','need_grant','renderer_gone','service_timeout','job_error','watchdog','action_outcome','learning_recheck','learning_recovery','learning_outcome','net_blocked','net_unknown_mutation'}
SITES={'UNKNOWN','FACEBOOK','KSL','OFFERUP','EBAY','CRAIGSLIST','AUTOTRADER','CARS_COM'}
PAGES={'HOME','SEARCH','RESULTS','FACET_PANEL','DETAIL','PROFILE','MESSAGES','DIALOG','AUTH_WALL','CHALLENGE','ERROR','UNKNOWN'}
STATUSES={'UNKNOWN','VERIFIED','FAILED','AMBIGUOUS','HUMAN_NEEDED'}
PHASES={'UNKNOWN','START','SEARCH','CONSTRAIN','COLLECT','INSPECT','PREPARE_MESSAGE','PREVIEW','DONE'}
DIAGNOSTICS={'VERIFIED','NO_TARGET','REPEAT_STATE_LIMIT','OUTSIDE_TASK_HOST','ACTION_REJECTED','STALE_DOCUMENT','RENDERER_TIMEOUT','INTERLOCK_BLOCKED','UNMET_EXPECTATION','PAGE_UNSETTLED','AUTH_WALL','CHALLENGE','UNEXPECTED_HOST','MODEL_UNCERTAIN','UNKNOWN'}
COUNTERS={'items','controls','search_boxes','result_links','facet_controls','dialog_controls','next_controls','load_more_controls','new_items','decisions','verified_skills'}
REASONS={'deadline','stopped','browser','search','replaced','interrupted','service_destroyed','unknown'}

CAPABILITIES={'sort_results', 'UNKNOWN', 'open_facet', 'dismiss_dialog', 'commit_send', 'open_item', 'navigate', 'scroll_results', 'search', 'go_back', 'prepare_message', 'apply_filters', 'custom', 'load_more', 'select_facet', 'next_page', 'constrain_numeric', 'open_category', 'open_filters', 'seek_pagination', 'expand_description'}
RECHECK_REASONS={'NO_TARGET_ON_PROFILE', 'UNKNOWN', 'NO_TARGET_ON_RESULTS', 'PAGE_BUSY', 'NO_TARGET_ON_ERROR', 'NO_TARGET_ON_CHALLENGE', 'PROCEDURE_COOLDOWN', 'NO_TARGET_ON_MESSAGES', 'NO_TARGET_ON_HOME', 'NO_TARGET_ON_UNKNOWN', 'ALL_LESSONS_COMPLETE', 'HUMAN_HOLD', 'NO_TARGET_ON_DETAIL', 'NO_TARGET_ON_FACET_PANEL', 'NO_TARGET_ON_AUTH_WALL', 'NO_TARGET_ON_SEARCH', 'PAGINATION_DISCOVERY_COOLDOWN', 'COOLDOWN', 'NOT_OBSERVED_AVAILABLE', 'NO_TARGET_ON_DIALOG', 'PROBE_UNAVAILABLE'}
COUNTERS_V2=COUNTERS|{'teacher_attempts'}|{'pending', 'lessons_complete', 'inspected', 'lessons_total', 'new_lessons', 'facet_openers', 'closers'}
STATUSES_V2=STATUSES|{'PENDING','RUNNING','NEED_HUMAN','NEED_GRANT','DONE','ABANDONED','PAUSED','PARTIAL','BUDGET_EXHAUSTED'}

class Failure(Exception):
 def __init__(self,status=400):self.status=status

def check(value,status=400):
 if not value:raise Failure(status)

def exact(obj,keys):check(type(obj) is dict and set(obj)==set(keys))
def integer(value,maximum=1000000):check(type(value) is int and 0<=value<=maximum)
def canonical(report):return json.dumps(report,sort_keys=True,separators=(',',':'),ensure_ascii=True,allow_nan=False).encode()

def validate(r):
 exact(r,{'schema_version','report_id','app_revision','version_code','started_at_utc','elapsed_ms','flags','summary','events'})
 check(type(r['schema_version']) is int and r['schema_version'] in (1,2))
 version=r['schema_version']
 check(type(r['report_id']) is str and str(uuid.UUID(r['report_id']))==r['report_id'])
 check(type(r['app_revision']) is str and re.fullmatch('[0-9a-f]{40}',r['app_revision']))
 integer(r['version_code'],2147483647); integer(r['elapsed_ms'],86400000)
 check(type(r['started_at_utc']) is str and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?(?:Z|\+00:00)',r['started_at_utc']))
 datetime.fromisoformat(r['started_at_utc'].replace('Z','+00:00'))
 exact(r['flags'],{'trimmed','clock_changed','interrupted'})
 check(all(type(v) is bool for v in r['flags'].values()))
 exact(r['summary'],{'verified','failed','tasks','verified_skills','end_reason'})
 for k in ('verified','failed','tasks','verified_skills'):integer(r['summary'][k])
 check(r['summary']['end_reason'] in REASONS)
 check(type(r['events']) is list and len(r['events'])<=8000)
 previous=0
 for event in r['events']:
  exact(event,{'code','site','page_type','status','phase','diagnostic_code','elapsed_ms','counters'} | ({'lesson','capability','recheck_reason'} if version==2 else set()))
  if version==2:
   for key,allowed in [('lesson',CAPABILITIES),('capability',CAPABILITIES),('recheck_reason',RECHECK_REASONS)]:check(type(event[key]) is str and event[key] in allowed)
  for key,allowed in [('code',CODES),('site',SITES),('page_type',PAGES),('status',STATUSES_V2 if version==2 else STATUSES),('phase',PHASES),('diagnostic_code',DIAGNOSTICS)]:check(type(event[key]) is str and event[key] in allowed)
  integer(event['elapsed_ms'],r['elapsed_ms']);check(event['elapsed_ms']>=previous);previous=event['elapsed_ms']
  check(type(event['counters']) is dict and set(event['counters'])<=(COUNTERS_V2 if version==2 else COUNTERS))
  for value in event['counters'].values():integer(value)
 check(len(canonical(r))<=LIMIT,413)
 return r

def digest(value):return hashlib.sha256(value.encode()).hexdigest()
def utc(now):return datetime.fromtimestamp(now,timezone.utc).isoformat()
def strict_load(raw):
 def pairs(items):
  result={}
  for k,v in items:check(k not in result);result[k]=v
  return result
 return json.loads(raw,object_pairs_hook=pairs,parse_constant=lambda _:(_ for _ in ()).throw(Failure()))

class Store:
 def __init__(self,path):
  self.path=Path(path);self.path.mkdir(parents=True,exist_ok=True);(self.path/'outbox').mkdir(exist_ok=True)
  os.chmod(self.path/'outbox',0o750)
  with self.connect() as db:
   db.executescript('CREATE TABLE IF NOT EXISTS codes(hash TEXT PRIMARY KEY,expires REAL); CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,hash TEXT); CREATE TABLE IF NOT EXISTS reports(id TEXT PRIMARY KEY,device TEXT,hash TEXT,received REAL);')
  os.chmod(self.path/'receiver.sqlite',0o600)
  with self.connect() as db:
   db.executescript('CREATE TABLE IF NOT EXISTS live_receipts(device TEXT,test_id TEXT,sequence INTEGER,hash TEXT,received REAL,PRIMARY KEY(device,test_id,sequence)); CREATE TABLE IF NOT EXISTS live_rate(device TEXT,test_id TEXT,sequence INTEGER,received REAL,PRIMARY KEY(device,test_id,sequence));')
  (self.path/'live').mkdir(exist_ok=True);os.chmod(self.path/'live',0o750)
 @contextmanager
 def connect(self):
  db=sqlite3.connect(self.path/'receiver.sqlite',timeout=20)
  try:
   db.execute('PRAGMA journal_mode=WAL');db.execute('PRAGMA synchronous=FULL')
   with db:yield db
  finally:db.close()
 def issue_code(self,now=None):
  now=time.time() if now is None else now;code=secrets.token_urlsafe(24)
  with self.connect() as db:db.execute('INSERT INTO codes VALUES(?,?)',(digest(code),now+600))
  return code
 def pair(self,code,token,now=None):
  now=time.time() if now is None else now
  check(type(code) is str and len(code)<=64,401);check(type(token) is str and re.fullmatch('[A-Za-z0-9_-]{43}',token),401)
  with self.connect() as db:
   db.execute('BEGIN IMMEDIATE');row=db.execute('SELECT expires FROM codes WHERE hash=?',(digest(code),)).fetchone();check(row and row[0]>=now,401)
   device=str(uuid.uuid4());db.execute('DELETE FROM codes WHERE hash=?',(digest(code),));db.execute('INSERT INTO devices VALUES(?,?)',(device,digest(token)))
  return device
 def accept(self,device,token,r,now=None):
  now=time.time() if now is None else now;validate(r);raw=canonical(r);sha=hashlib.sha256(raw).hexdigest()
  with self.connect() as db:
   db.execute('BEGIN IMMEDIATE');row=db.execute('SELECT hash FROM devices WHERE id=?',(device,)).fetchone();check(row and hmac.compare_digest(row[0],digest(token)),401)
   old=db.execute('SELECT device,hash,received FROM reports WHERE id=?',(r['report_id'],)).fetchone()
   if old:
    check(old[0]==device and old[1]==sha,409);self._clear_completed_live(device,r['report_id']);return {'report_id':r['report_id'],'sha256':sha,'received_at_utc':utc(old[2])}
   count=db.execute('SELECT count(*) FROM reports WHERE device=? AND received>?',(device,now-3600)).fetchone()[0];check(count<6,429)
   receipt={'report_id':r['report_id'],'sha256':sha,'received_at_utc':utc(now)}
   destination=self.path/'outbox'/(r['report_id']+'.json')
   if destination.exists():
    orphan=json.loads(destination.read_text());check(orphan['receipt']['sha256']==sha and orphan['report']==r,409)
    receipt=orphan['receipt'];check(receipt['report_id']==r['report_id'],409)
    now=datetime.fromisoformat(receipt['received_at_utc']).timestamp()
   temporary=destination.with_suffix('.tmp')
   with temporary.open('w') as file:json.dump({'report':r,'receipt':receipt},file,sort_keys=True,separators=(',',':'));file.flush();os.fsync(file.fileno())
   os.chmod(temporary,0o640);temporary.replace(destination)
   fd=os.open(destination.parent,os.O_RDONLY|os.O_DIRECTORY)
   try:os.fsync(fd)
   finally:os.close(fd)
   db.execute('INSERT INTO reports VALUES(?,?,?,?)',(r['report_id'],device,sha,now))
   self._clear_completed_live(device,r['report_id'])
  return receipt
 def _clear_completed_live(self,device,report_id):
  # Optional cleanup must never change the final report's durable acknowledgement.
  destination=self.path/'live'/(device+'.json')
  try:
   if destination.exists() and live_document(destination.read_bytes())['progress']['test_id']==report_id:
    destination.unlink()
    fd=os.open(destination.parent,os.O_RDONLY|os.O_DIRECTORY)
    try:os.fsync(fd)
    finally:os.close(fd)
  except (OSError,Failure,ValueError,TypeError,KeyError):pass
 def accept_live(self,device,token,p,now=None):
  now=time.time() if now is None else now
  # Authenticate before touching live state, rate records or filesystem data.
  with self.connect() as db:
   row=db.execute('SELECT hash FROM devices WHERE id=?',(device,)).fetchone()
   check(type(token) is str and row and hmac.compare_digest(row[0],digest(token)),401)
   validate_live(p);raw=canonical_live(p);sha=hashlib.sha256(raw).hexdigest()
   db.execute('BEGIN IMMEDIATE')
   destination=self.path/'live'/(device+'.json');latest=None
   if destination.exists():
    check(destination.is_file() and not destination.is_symlink(),409)
    latest=live_document(destination.read_bytes());check(latest['receipt']['device_id']==device,409)
    received=datetime.fromisoformat(latest['receipt']['received_at_utc']).timestamp()
    if received<now-LIVE_TTL:latest=None
    else:
     # Recover a file fsynced before a process crash rolled back SQLite.
     # A previous attempt may have failed at directory fsync. Re-establish
     # durability before recovering or acknowledging the orphan receipt.
     with destination.open('rb') as file:os.fsync(file.fileno())
     fd=os.open(destination.parent,os.O_RDONLY|os.O_DIRECTORY)
     try:os.fsync(fd)
     finally:os.close(fd)
     r=latest['receipt']
     db.execute('INSERT OR IGNORE INTO live_receipts VALUES(?,?,?,?,?)',(device,r['test_id'],r['sequence'],r['sha256'],received))
     db.execute('INSERT OR IGNORE INTO live_rate VALUES(?,?,?,?)',(device,r['test_id'],r['sequence'],received))
   old=db.execute('SELECT hash,received FROM live_receipts WHERE device=? AND test_id=? AND sequence=? AND received>=?',(device,p['test_id'],p['sequence'],now-LIVE_TTL)).fetchone()
   if old:
    check(old[0]==sha,409)
    return {'device_id':device,'test_id':p['test_id'],'sequence':p['sequence'],'sha256':sha,'received_at_utc':utc(old[1])}
   check(not db.execute('SELECT 1 FROM reports WHERE id=? AND device=?',(p['test_id'],device)).fetchone(),409)
   finishing=False
   if latest:
    previous=latest['progress']
    if previous['test_id']==p['test_id']:
     check(all(previous[k]==p[k] for k in ('app_revision','version_code','started_at_utc')),409)
     check(previous['state']=='RUNNING' and p['sequence']>previous['sequence'],409)
     finishing=p['state']=='FINISHED'
    else:
     seen=db.execute('SELECT 1 FROM live_receipts WHERE device=? AND test_id=? AND received>=?',(device,p['test_id'],now-LIVE_TTL)).fetchone()
     check(not seen,409)
     check(datetime.fromisoformat(p['started_at_utc'].replace('Z','+00:00'))>=datetime.fromisoformat(previous['started_at_utc'].replace('Z','+00:00')),409)
   count,last=db.execute('SELECT count(*),max(received) FROM live_rate WHERE device=? AND received>?',(device,now-3600)).fetchone()
   check(count<LIVE_HOURLY,429);check(last is None or now-last>=LIVE_INTERVAL or finishing,429)
   receipt={'device_id':device,'test_id':p['test_id'],'sequence':p['sequence'],'sha256':sha,'received_at_utc':utc(now)}
   temporary=destination.with_suffix('.tmp')
   # Restrict access when created, before writing any content.
   fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_TRUNC|os.O_NOFOLLOW,0o640)
   with os.fdopen(fd,'wb') as file:
    os.fchmod(file.fileno(),0o640);file.write(canonical({'progress':p,'receipt':receipt}));file.flush();os.fsync(file.fileno())
   temporary.replace(destination)
   fd=os.open(destination.parent,os.O_RDONLY|os.O_DIRECTORY)
   try:os.fsync(fd)
   finally:os.close(fd)
   db.execute('INSERT INTO live_receipts VALUES(?,?,?,?,?)',(device,p['test_id'],p['sequence'],sha,now))
   db.execute('INSERT INTO live_rate VALUES(?,?,?,?)',(device,p['test_id'],p['sequence'],now))
  return receipt
 def prune_live(self,now=None):
  now=time.time() if now is None else now
  with self.connect() as db:
   db.execute('BEGIN IMMEDIATE')
   for file in (self.path/'live').iterdir():
    if file.suffix=='.json':
     try:received=datetime.fromisoformat(live_document(file.read_bytes())['receipt']['received_at_utc']).timestamp()
     except (Failure,ValueError,KeyError,TypeError):received=file.lstat().st_mtime
     if received<now-LIVE_TTL:file.unlink(missing_ok=True)
    elif file.suffix=='.tmp' and file.lstat().st_mtime<now-LIVE_TTL:file.unlink(missing_ok=True)
   db.execute('DELETE FROM live_receipts WHERE received<?',(now-LIVE_TTL,))
   db.execute('DELETE FROM live_rate WHERE received<=?',(now-3600,))
 def prune(self,now=None):
  now=time.time() if now is None else now
  with self.connect() as db:
   db.execute('BEGIN IMMEDIATE');ids=db.execute('SELECT id FROM reports WHERE received<?',(now-30*86400,)).fetchall()
   for (id,) in ids:(self.path/'outbox'/(id+'.json')).unlink(missing_ok=True)
   db.execute('DELETE FROM reports WHERE received<?',(now-30*86400,));db.execute('DELETE FROM codes WHERE expires<?',(now,))
   # A crash may leave a durable outbox document without its database row.
   for file in (self.path/'outbox').iterdir():
    if file.suffix=='.json':
     try:received=datetime.fromisoformat(json.loads(file.read_text())['receipt']['received_at_utc']).timestamp()
     except (ValueError,KeyError,TypeError):received=file.stat().st_mtime
     if received<now-30*86400:file.unlink(missing_ok=True)
    elif file.suffix=='.tmp' and file.stat().st_mtime<now-30*86400:file.unlink(missing_ok=True)

class Handler(BaseHTTPRequestHandler):
 store=None
 def setup(self):
  super().setup();self.connection.settimeout(30)
 def log_message(self,*args):pass
 def response(self,status,obj):
  raw=json.dumps(obj,separators=(',',':')).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(raw)
 def do_GET(self):
  self.response(200 if self.path=='/health' else 404,{'service':'ai-browser-phone-reports','schema_version':2,'accepted_schema_versions':[1,2]})
 def do_POST(self):
  try:
   check(self.headers.get('X-Forwarded-Proto')=='https',403)
   check(not self.headers.get('Transfer-Encoding'),400)
   length=int(self.headers.get('Content-Length','0'));check(0<length<=(LIVE_LIMIT if self.path=='/v1/live' else LIMIT),413)
   check(self.headers.get('Content-Type','').split(';')[0]=='application/json',415)
   r=strict_load(self.rfile.read(length))
   if self.path=='/v1/pair':
    exact(r,{'code','credential'});device=self.store.pair(r['code'],r['credential']);self.response(201,{'device_id':device});return
   check(self.path in {'/v1/reports','/v1/live'},404)
   auth=self.headers.get('Authorization','');match=re.fullmatch(r'Bearer ([0-9a-f-]{36})\.([A-Za-z0-9_-]{43})',auth);check(match is not None,401)
   receipt=(self.store.accept_live if self.path=='/v1/live' else self.store.accept)(match[1],match[2],r);self.response(200,receipt)
  except Failure as error:self.response(error.status,{'error':'request_rejected'})
  except (ValueError,TypeError,KeyError,OverflowError):self.response(400,{'error':'invalid_schema'})
  except Exception:self.response(503,{'error':'storage_unavailable'})

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--state',type=Path,required=True);parser.add_argument('--issue-code',action='store_true');args=parser.parse_args();store=Store(args.state)
 if args.issue_code:print(store.issue_code());return
 Handler.store=store;server=HTTPServer(('127.0.0.1',8765),Handler);server.timeout=30;pruned=0;live_pruned=0
 while True:
  if time.monotonic()-pruned>3600:store.prune();pruned=time.monotonic()
  if time.monotonic()-live_pruned>=30:store.prune_live();live_pruned=time.monotonic()
  server.handle_request()
if __name__=='__main__':main()
