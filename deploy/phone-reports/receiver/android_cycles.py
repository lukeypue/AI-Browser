"""Independent closed-schema Android cycle wire validation; no runtime actions."""
import hashlib,json,re,uuid
from datetime import datetime
from receiver import validate as validate_phone,Failure
LIMIT=1048576
WIRE_KEYS=set('schema_version source cycle_id runtime content app_build_type source_revision app_apk_sha256 test_apk_sha256 fixture_sha256 version_code started_at_utc finished_at_utc status end_reason elapsed_ms app_elapsed_ms diagnostics diagnostics_sha256 memory_before memory_after restart'.split())
MEMORY_KEYS=set('fingerprint verified_lessons verified_skills failed_strategies'.split())
RESTART_KEYS=set('app_restart_verified emulator_restart_verified before_fingerprint after_app_fingerprint after_emulator_fingerprint verified_at_utc'.split())
def require(condition):
 if not condition:raise ValueError('invalid_android_cycle')
def exact(obj,keys):require(type(obj) is dict and set(obj)==keys)
def integer(value,maximum=1000000):require(type(value) is int and 0<=value<=maximum)
def digest(value,size=64):require(type(value) is str and re.fullmatch('[0-9a-f]{'+str(size)+'}',value) is not None)
def timestamp(value):
 require(type(value) is str and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?(?:Z|\+00:00)',value) is not None)
 return datetime.fromisoformat(value.replace('Z','+00:00'))
def identifier(value):require(type(value) is str and str(uuid.UUID(value))==value)
def canonical_report(payload):return json.dumps(payload,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode('utf-8')
def memory(value):
 exact(value,MEMORY_KEYS);digest(value['fingerprint'])
 for key in MEMORY_KEYS-{'fingerprint'}:integer(value[key])
 return value
def diagnostics(value):
 try:
  validate_phone(value);require(type(value['schema_version']) is int and value['schema_version']==2)
 except (Failure,KeyError,TypeError,OverflowError) as error:raise ValueError('invalid_diagnostics') from error
 return value
def validate_android_cycle(payload):
 r=payload
 require(len(canonical_report(r))<=LIMIT);exact(r,WIRE_KEYS)
 require(type(r['schema_version']) is int and r['schema_version']==1)
 for k,v in dict(source='ANDROID_SERVER_CYCLE',runtime='ANDROID_EMULATOR',content='SYNTHETIC_FIXTURES',app_build_type='SERVER_LAB_DEBUG').items():require(r[k]==v)
 identifier(r['cycle_id']);digest(r['source_revision'],40)
 for k in ('app_apk_sha256','test_apk_sha256','fixture_sha256'):digest(r[k])
 integer(r['version_code'],2147483647);require(r['version_code']>0)
 start=timestamp(r['started_at_utc']);finish=timestamp(r['finished_at_utc']);require(finish>=start)
 require(r['status'] in ('COMPLETED','INTERRUPTED','FAILED'))
 require(r['end_reason']=={'COMPLETED':'DEADLINE','INTERRUPTED':'INTERRUPTED','FAILED':'RUNTIME_FAILED'}[r['status']])
 for k in ('elapsed_ms','app_elapsed_ms'):integer(r[k],7200000)
 d=r['diagnostics']
 if d is None:require(r['diagnostics_sha256'] is None)
 else:
  diagnostics(d);digest(r['diagnostics_sha256'])
  require(hashlib.sha256(canonical_report(d)).hexdigest()==r['diagnostics_sha256'])
  require(d['app_revision']==r['source_revision'] and d['version_code']==r['version_code'])
 for k in ('memory_before','memory_after'):
  if r[k] is not None:memory(r[k])
 s=r['restart'];exact(s,RESTART_KEYS)
 for k in ('app_restart_verified','emulator_restart_verified'):require(type(s[k]) is bool)
 for k in ('before_fingerprint','after_app_fingerprint','after_emulator_fingerprint'):
  if s[k] is not None:digest(s[k])
 if s['verified_at_utc'] is not None:timestamp(s['verified_at_utc'])
 if r['status']=='COMPLETED':
  require(r['elapsed_ms']>=1800000 and r['app_elapsed_ms']>=1800000 and d is not None)
  require(not d['flags']['interrupted'] and not d['flags']['clock_changed'])
  require(d['elapsed_ms']>=1800000 and d['summary']['end_reason']=='deadline')
  require(d['summary']['verified']>0 and d['summary']['verified_skills']>0)
  require(r['memory_before'] is not None and r['memory_after'] is not None)
  require(r['memory_after']['verified_lessons']>0)
  require(s['app_restart_verified'] and s['emulator_restart_verified'] and s['verified_at_utc'] is not None)
  require(all(s[k]==r['memory_after']['fingerprint'] for k in ('before_fingerprint','after_app_fingerprint','after_emulator_fingerprint')))
 else:
  require(not s['app_restart_verified'] and not s['emulator_restart_verified'])
 return r
