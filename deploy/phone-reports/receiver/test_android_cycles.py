import hashlib,unittest,uuid
from android_cycles import validate_android_cycle,canonical_report
def report():
 d=dict(schema_version=2,report_id=str(uuid.uuid4()),app_revision='a'*40,version_code=918,started_at_utc='2026-10-17T00:00:00Z',elapsed_ms=1800000,flags=dict(trimmed=False,clock_changed=False,interrupted=False),summary=dict(verified=1,failed=0,tasks=1,verified_skills=1,end_reason='deadline'),events=[])
 m=dict(fingerprint='b'*64,verified_lessons=1,verified_skills=1,failed_strategies=0)
 return dict(schema_version=1,source='ANDROID_SERVER_CYCLE',cycle_id=d['report_id'],runtime='ANDROID_EMULATOR',content='SYNTHETIC_FIXTURES',app_build_type='SERVER_LAB_DEBUG',source_revision='a'*40,app_apk_sha256='c'*64,test_apk_sha256='d'*64,fixture_sha256='e'*64,version_code=918,started_at_utc=d['started_at_utc'],finished_at_utc='2026-10-17T00:30:00Z',status='COMPLETED',end_reason='DEADLINE',elapsed_ms=1800000,app_elapsed_ms=1800000,diagnostics=d,diagnostics_sha256=hashlib.sha256(canonical_report(d)).hexdigest(),memory_before=m.copy(),memory_after=m.copy(),restart=dict(app_restart_verified=True,emulator_restart_verified=True,before_fingerprint='b'*64,after_app_fingerprint='b'*64,after_emulator_fingerprint='b'*64,verified_at_utc='2026-10-17T00:31:00Z'))
class ValidatorTests(unittest.TestCase):
 def test_completed(self):self.assertEqual(validate_android_cycle(report())['status'],'COMPLETED')
 def test_closed_schema_bounds_hashes_evidence(self):
  changes=[lambda r:r.update(url='https://private'),lambda r:r.update(elapsed_ms=1799999),lambda r:r.update(app_elapsed_ms=True),lambda r:r.update(source_revision='A'*40),lambda r:r.update(started_at_utc='2026-99-99T00:00:00Z'),lambda r:r['memory_after'].update(verified_skills=True),lambda r:r['restart'].update(after_app_fingerprint='f'*64),lambda r:r['restart'].update(app_restart_verified=1),lambda r:r.update(diagnostics_sha256='f'*64),lambda r:r['diagnostics']['flags'].update(interrupted=True),lambda r:r.update(diagnostics=None),lambda r:r['diagnostics'].update(schema_version=1),lambda r:r['diagnostics']['summary'].update(verified=0),lambda r:r['memory_after'].update(verified_lessons=0),lambda r:r.update(elapsed_ms=7200001)]
  for mutate in changes:
   r=report();mutate(r)
   with self.subTest(change=changes.index(mutate)):
    with self.assertRaises(ValueError):validate_android_cycle(r)
 def test_retained_lessons_with_zero_compiled_skills_completed(self):
  r=report();r['memory_before'].update(verified_lessons=0,verified_skills=0)
  r['memory_after'].update(verified_lessons=3,verified_skills=0)
  r['diagnostics']['summary'].update(verified=4,tasks=5,verified_skills=3)
  r['diagnostics_sha256']=hashlib.sha256(canonical_report(r['diagnostics'])).hexdigest()
  self.assertEqual(validate_android_cycle(r)['memory_after']['verified_skills'],0)
 def test_interrupted(self):
  r=report();r.update(status='INTERRUPTED',end_reason='INTERRUPTED',elapsed_ms=10,app_elapsed_ms=0,diagnostics=None,diagnostics_sha256=None,memory_before=None,memory_after=None)
  r['restart']={k:(False if k.endswith('verified') else None) for k in r['restart']};validate_android_cycle(r)
 def test_oversize_nan(self):
  r=report();r['diagnostics']['events']=[{}]*200000
  with self.assertRaises(ValueError):validate_android_cycle(r)
  with self.assertRaises(ValueError):canonical_report({'x':float('nan')})
if __name__=='__main__':unittest.main()
