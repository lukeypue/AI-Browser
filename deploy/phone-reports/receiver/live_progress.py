"""Strict content-free live progress contract; independent of final report schemas."""
import hashlib,json,re,uuid
from datetime import datetime

LIVE_LIMIT=32768
LIVE_TTL=1800
LIVE_INTERVAL=25
LIVE_HOURLY=240

def canonical_live(progress):
 return json.dumps(progress,sort_keys=True,separators=(',',':'),ensure_ascii=True,allow_nan=False).encode()

def validate_live(p):
 # Import lazily: receiver re-exports this API and owns the unchanged schema2 validator.
 from receiver import check,exact,integer,validate,Failure
 exact(p,{'schema_version','test_id','app_revision','version_code','started_at_utc','elapsed_ms','sequence','state','flags','counters','recent_events'})
 check(type(p['schema_version']) is int and p['schema_version']==1)
 check(type(p['test_id']) is str)
 try:check(str(uuid.UUID(p['test_id']))==p['test_id'])
 except ValueError:raise Failure()
 check(type(p['state']) is str and p['state'] in {'RUNNING','FINISHED'})
 integer(p['elapsed_ms'],86400000);integer(p['sequence'],172800001)
 check(p['sequence']==p['elapsed_ms']*2+(p['state']=='FINISHED'))
 exact(p['counters'],{'verified_actions','failed_actions','tasks','new_lessons','teacher_attempts'})
 for value in p['counters'].values():integer(value)
 check(type(p['recent_events']) is list and len(p['recent_events'])<=32)
 validate({'schema_version':2,'report_id':p['test_id'],'app_revision':p['app_revision'],'version_code':p['version_code'],'started_at_utc':p['started_at_utc'],'elapsed_ms':p['elapsed_ms'],'flags':p['flags'],'summary':{'verified':p['counters']['verified_actions'],'failed':p['counters']['failed_actions'],'tasks':p['counters']['tasks'],'verified_skills':p['counters']['new_lessons'],'end_reason':'unknown'},'events':p['recent_events']})
 check(len(canonical_live(p))<=LIVE_LIMIT,413)
 return p

def live_document(raw):
 from receiver import check,exact,strict_load,Failure
 check(isinstance(raw,(bytes,str)) and len(raw)<=LIVE_LIMIT+1024,413)
 d=strict_load(raw);exact(d,{'progress','receipt'});p=validate_live(d['progress']);r=d['receipt']
 exact(r,{'device_id','test_id','sequence','sha256','received_at_utc'})
 check(type(r['device_id']) is str)
 try:check(str(uuid.UUID(r['device_id']))==r['device_id'])
 except ValueError:raise Failure()
 check(r['test_id']==p['test_id'] and type(r['sequence']) is int and r['sequence']==p['sequence'])
 check(r['sha256']==hashlib.sha256(canonical_live(p)).hexdigest())
 check(type(r['received_at_utc']) is str and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?\+00:00',r['received_at_utc']))
 datetime.fromisoformat(r['received_at_utc'])
 return d
