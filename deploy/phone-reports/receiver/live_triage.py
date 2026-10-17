"""Bounded advisory triage of validated phone progress; never executes suggestions.

run_once accepts a callback returning a small JSON object (text or dict). The
callback sees only the typed aggregate; it must provide its own transport timeout.
A process lock remains held through the callback. Attempts are committed before
transport, including failures/crashes, so reopening cannot retry a reservation.
"""
import fcntl,hashlib,json,os,sqlite3,time
from datetime import datetime,timezone
from pathlib import Path
from receiver import canonical,strict_load

TTL=1800
FOCUS={'NO_SAFE_CONTROL','VERIFY_FAILURE','STALE_STATE','UNSETTLED_PAGE','TEACHER_NOT_USED','INSUFFICIENT_EVIDENCE'}
NEXT_CHECK={'CONTROL_DISCOVERY','POSTCONDITION','STALE_DOCUMENT','SETTLE_TIMING','TEACHER_AVAILABILITY','REPRODUCTION'}
CONFIDENCE={'LOW','MEDIUM','HIGH'}
HUMAN_STATUSES={'HUMAN_NEEDED','NEED_HUMAN','NEED_GRANT','PAUSED','ABANDONED'}
SOURCE='PHONE_LIVE_TYPED'

def _human(e):
 return (e['page_type'] in {'AUTH_WALL','CHALLENGE'} or e['diagnostic_code'] in {'AUTH_WALL','CHALLENGE','INTERLOCK_BLOCKED'} or e['status'] in HUMAN_STATUSES or e['code'] in {'need_human','need_grant'} or any(x in e['recheck_reason'] for x in ('HUMAN','AUTH_WALL','CHALLENGE')))

def _aggregate(e):
 result={k:e[k] for k in ('site','lesson','capability','page_type','diagnostic_code')}
 for k in ('closers','controls'):result[k]=('UNKNOWN' if k not in e['counters'] else 'POSITIVE' if e['counters'][k]>0 else 'ZERO')
 return result

def _model_result(raw):
 if type(raw) is str:
  if len(raw.encode())>2048:raise ValueError('oversize')
  raw=strict_load(raw)
 if type(raw) is not dict or set(raw)!={'focus','next_check','confidence','needs_human'}:raise ValueError('shape')
 for k,allowed in [('focus',FOCUS),('next_check',NEXT_CHECK),('confidence',CONFIDENCE)]:
  if type(raw[k]) is not str or raw[k] not in allowed:raise ValueError('enum')
 if type(raw['needs_human']) is not bool:raise ValueError('bool')
 return dict(raw)

def _write_latest(state,result):
 tmp=state/'latest.tmp'
 with tmp.open('w') as out:
  json.dump(result,out,sort_keys=True,separators=(',',':'));out.flush();os.fsync(out.fileno())
 os.chmod(tmp,0o640);tmp.replace(state/'latest.json')
 fd=os.open(state,os.O_RDONLY|os.O_DIRECTORY)
 try:os.fsync(fd)
 finally:os.close(fd)

def _prune(db,now):
 db.execute('DELETE FROM seen WHERE observed<=?',(now-TTL,))
 db.execute('DELETE FROM groups WHERE observed<=?',(now-TTL,))
 db.execute('DELETE FROM attempts WHERE reserved<=?',(now-86400,))
 db.execute('DELETE FROM seen WHERE rowid IN (SELECT rowid FROM seen ORDER BY observed DESC,rowid DESC LIMIT -1 OFFSET 4096)')
 db.execute('DELETE FROM groups WHERE rowid IN (SELECT rowid FROM groups ORDER BY observed DESC,rowid DESC LIMIT -1 OFFSET 64)')

def run_once(live_dir,state_dir,model_callback,now=None):
 """Process fresh validated envelopes and return newly persisted typed results.

 State is private to this detector. At most six attempts per rolling hour and 24
 per rolling day are reserved globally. One attempt per test/signature is allowed
 in the retention period, regardless of transport success. No raw evidence or
 transport error text is persisted in results.
 """
 from live_progress import live_document
 now=time.time() if now is None else float(now)
 state=Path(state_dir);state.mkdir(parents=True,exist_ok=True)
 results=[]
 with (state/'triage.lock').open('a') as lock:
  os.chmod(state/'triage.lock',0o600);fcntl.flock(lock,fcntl.LOCK_EX)
  with sqlite3.connect(state/'triage.sqlite',timeout=10) as db:
   os.chmod(state/'triage.sqlite',0o600)
   db.execute('PRAGMA synchronous=FULL')
   db.executescript('CREATE TABLE IF NOT EXISTS seen(test TEXT,event TEXT,observed REAL,PRIMARY KEY(test,event)); CREATE TABLE IF NOT EXISTS groups(test TEXT,signature TEXT,aggregate TEXT,count INTEGER,observed REAL,PRIMARY KEY(test,signature)); CREATE TABLE IF NOT EXISTS attempts(test TEXT,signature TEXT,reserved REAL,PRIMARY KEY(test,signature));')
   _prune(db,now);db.commit()
   blocked_tests=set();active_tests=set()
   for file in sorted(Path(live_dir).glob('*.json')):
    try:
     if file.is_symlink() or file.stat().st_size>65536:continue
     doc=live_document(file.read_bytes());p=doc['progress'];receipt=doc['receipt']
     received=datetime.fromisoformat(receipt['received_at_utc'].replace('Z','+00:00')).timestamp()
     if not now-TTL<received<=now+60:continue
     if p['state']!='RUNNING':
      blocked_tests.add(p['test_id']);continue
     active_tests.add(p['test_id'])
     events=p['recent_events']
     if any(_human(e) for e in events):
      blocked_tests.add(p['test_id']);continue
    except Exception:
     continue
    for e in events:
     if e['code']!='action_outcome' or e['status']!='FAILED':continue
     event_hash=hashlib.sha256(canonical(e)).hexdigest()
     inserted=db.execute('INSERT OR IGNORE INTO seen VALUES(?,?,?)',(p['test_id'],event_hash,now)).rowcount
     if not inserted:continue
     aggregate=_aggregate(e);encoded=canonical(aggregate).decode();signature=hashlib.sha256(encoded.encode()).hexdigest()
     db.execute('INSERT INTO groups VALUES(?,?,?,1,?) ON CONFLICT(test,signature) DO UPDATE SET count=count+1,observed=excluded.observed',(p['test_id'],signature,encoded,now))
    _prune(db,now)
    db.commit()
   for test in blocked_tests:db.execute('DELETE FROM groups WHERE test=?',(test,))
   db.commit()
   active_tests-=blocked_tests
   for (test,) in db.execute('SELECT DISTINCT test FROM groups').fetchall():
    if test not in active_tests:db.execute('DELETE FROM groups WHERE test=?',(test,))
   db.commit()
   rows=db.execute('SELECT test,signature,aggregate,count FROM groups WHERE count>=3 ORDER BY observed,signature').fetchall()
   for test,signature,encoded,count in rows:
    if db.execute('SELECT 1 FROM attempts WHERE test=? AND signature=?',(test,signature)).fetchone():continue
    if db.execute('SELECT count(*) FROM attempts WHERE reserved>?',(now-3600,)).fetchone()[0]>=6:break
    if db.execute('SELECT count(*) FROM attempts').fetchone()[0]>=24:break
    db.execute('INSERT INTO attempts VALUES(?,?,?)',(test,signature,now));db.commit()
    result={'schema_version':1,'source':SOURCE,'test_id':test,'signature':signature,'repeat_count':count,'generated_at_utc':datetime.fromtimestamp(now,timezone.utc).isoformat(),'status':'MODEL_UNAVAILABLE'}
    aggregate=json.loads(encoded);aggregate.update(schema_version=1,source=SOURCE,repeat_count=count)
    try:
     result.update(_model_result(model_callback(aggregate)));result['status']='TRIAGED'
    except Exception:
     pass
    _write_latest(state,result);results.append(result)
 return results
