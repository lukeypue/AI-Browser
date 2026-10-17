"""Independently validate bounded, private live projections for the sole Git writer.

Only live-progress/latest.json and live-progress/triage.json are touched. Payloads
have no generated publication timestamp: unchanged evidence produces no diff.
"""
import re,time,uuid
from datetime import datetime
from pathlib import Path
from receiver import canonical,strict_load,check,exact,integer,Failure
from live_progress import live_document,LIVE_LIMIT,LIVE_TTL
from live_triage import FOCUS,NEXT_CHECK,CONFIDENCE

MAX_DEVICES=16
PUBLISH_INTERVAL=120
LATEST_LIMIT=MAX_DEVICES*(LIVE_LIMIT+1024)+1024

def _received(document):return datetime.fromisoformat(document['receipt']['received_at_utc']).timestamp()

def _fresh(timestamp,now):return now-LIVE_TTL<timestamp<=now

def _read(file,limit):
 check(file.is_file() and not file.is_symlink() and file.stat().st_size<=limit)
 return file.read_bytes()

def _advisory(raw):
 t=strict_load(raw)
 check(type(t) is dict)
 base={'schema_version','source','test_id','signature','repeat_count','generated_at_utc','status'}
 check(type(t.get('status')) is str and t['status'] in {'TRIAGED','MODEL_UNAVAILABLE'})
 exact(t,base|({'focus','next_check','confidence','needs_human'} if t['status']=='TRIAGED' else set()))
 check(type(t['schema_version']) is int and t['schema_version']==1 and t['source']=='PHONE_LIVE_TYPED')
 check(type(t['test_id']) is str and str(uuid.UUID(t['test_id']))==t['test_id'])
 check(type(t['signature']) is str and re.fullmatch('[0-9a-f]{64}',t['signature']))
 integer(t['repeat_count']);check(t['repeat_count']>=3)
 check(type(t['generated_at_utc']) is str and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?\+00:00',t['generated_at_utc']))
 datetime.fromisoformat(t['generated_at_utc'])
 if t['status']=='TRIAGED':
  for key,allowed in [('focus',FOCUS),('next_check',NEXT_CHECK),('confidence',CONFIDENCE)]:check(type(t[key]) is str and t[key] in allowed)
  check(type(t['needs_human']) is bool)
 return t

def _latest(raw):
 d=strict_load(raw);exact(d,{'schema_version','source','snapshots'})
 check(type(d['schema_version']) is int and d['schema_version']==1 and d['source']=='PHONE_LIVE')
 check(type(d['snapshots']) is list and len(d['snapshots'])<=MAX_DEVICES)
 documents=[live_document(canonical(v)) for v in d['snapshots']]
 check(len({d['receipt']['device_id'] for d in documents})==len(documents))
 return d

def _write_if_changed(file,value):
 payload=canonical(value)+b'\n'
 if file.exists():
  check(file.is_file() and not file.is_symlink())
  if file.read_bytes()==payload:return False
 file.write_bytes(payload)
 return True

def prepare_live(live_dir,triage_dir,repository,now=None):
 """Project current evidence into private repository files; caller owns writer lock.

 Malformed/untrusted source files are skipped. Existing projection files must be
 regular files; a symlink or malformed prior snapshot fails closed. Return the
 paths whose bytes changed. Triage is advisory and never invokes a model here.
 """
 now=time.time() if now is None else float(now)
 live_dir=Path(live_dir);triage_dir=Path(triage_dir);target=Path(repository)/'live-progress'
 check(not live_dir.is_symlink() and not triage_dir.is_symlink() and not target.is_symlink())
 check(not target.exists() or target.is_dir())
 for name in ('latest.json','triage.json'):check(not (target/name).is_symlink())
 snapshots=[]
 for file in sorted(live_dir.glob('*.json')):
  try:
   d=live_document(_read(file,LIVE_LIMIT+1024))
   check(file.name==d['receipt']['device_id']+'.json')
   if _fresh(_received(d),now):snapshots.append(d)
  except (Failure,OSError,ValueError,TypeError,KeyError,OverflowError):continue
 # Select the freshest bounded set, then stable device ordering prevents churn.
 snapshots=sorted(snapshots,key=lambda d:(_received(d),d['receipt']['device_id']),reverse=True)[:MAX_DEVICES]
 snapshots.sort(key=lambda d:d['receipt']['device_id'])
 latest_path=target/'latest.json';existing=None
 if latest_path.exists():existing=_latest(_read(latest_path,LATEST_LIMIT))
 publish=existing is None
 if existing is not None:
  old={d['receipt']['device_id']:d for d in existing['snapshots']}
  new={d['receipt']['device_id']:d for d in snapshots}
  publish=set(old)!=set(new)
  for device in set(old)&set(new):
   a,b=old[device],new[device]
   if (a['progress']['test_id'],a['progress']['state'])!=(b['progress']['test_id'],b['progress']['state']):publish=True
   if _received(b)-_received(a)>=PUBLISH_INTERVAL:publish=True
 advisories=[];advice_path=triage_dir/'latest.json'
 if advice_path.exists():
  try:
   t=_advisory(_read(advice_path,4096))
   active_tests={d['progress']['test_id'] for d in snapshots}
   if t['test_id'] in active_tests and _fresh(datetime.fromisoformat(t['generated_at_utc']).timestamp(),now):advisories=[t]
  except (Failure,OSError,ValueError,TypeError,KeyError,OverflowError):pass
 changes=[]
 if snapshots or existing is not None or advisories or (target/'triage.json').exists():
  target.mkdir(exist_ok=True)
  if publish and _write_if_changed(latest_path,{'schema_version':1,'source':'PHONE_LIVE','snapshots':snapshots}):changes.append(latest_path)
  triage_path=target/'triage.json'
  if (advisories or triage_path.exists()) and _write_if_changed(triage_path,{'schema_version':1,'source':'PHONE_LIVE_TYPED','advisories':advisories}):changes.append(triage_path)
 return changes
