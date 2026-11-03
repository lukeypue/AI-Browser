import unittest,copy,json
import synthetic_writer as e
from test_synthetic_export import fixture
COUNTS=['events_seen','events_dropped','action_verified','action_failed','neutral_programs','held_programs','planner_errors','new_lessons']
class CityDiagnosticExportTests(unittest.TestCase):
 def fixture(self):
  r=fixture();stages={}
  event=dict(code='memory_checkpoint',page_type='UNKNOWN',status='UNKNOWN',phase='UNKNOWN',diagnostic_code='MEMORY_SNAPSHOT',elapsed_ms=0,counters={},lesson='UNKNOWN',capability='UNKNOWN',recheck_reason='UNKNOWN')
  d={k:0 for k in COUNTS};d.update(events_seen=1,trace=[event])
  for pairs,before,after in [('fixed_pairs','baseline','after_training'),('unseen_pairs','unseen_baseline','unseen_after_training'),('city_pairs','city_baseline','city_after_training')]:
   for row in r[pairs]:
    for side in ['before','after']:row[side]['diagnostics']=copy.deepcopy(d)
   for name in [before,after]:stages[name]=dict(episodes=len(r[pairs]),**{k:(len(r[pairs]) if k=='events_seen' else 0) for k in COUNTS})
  r['diagnostics']=dict(schema_version=1,synthetic_only=True,scope='FROZEN_EXAM_EPISODES',stage_summaries=stages,samples=[dict(stage='baseline',scenario=r['fixed_pairs'][0]['before']['scenario'],diagnostics=copy.deepcopy(d))],samples_truncated=True)
  return r
 def test_validated_city_diagnostics_are_exported(self):
  p=e.sanitize(self.fixture());self.assertEqual(4,p['export_schema_version']);self.assertEqual(24,p['diagnostics']['stage_summaries']['baseline']['events_seen']);self.assertEqual('MEMORY_SNAPSHOT',p['diagnostics']['samples'][0]['diagnostics']['trace'][0]['diagnostic_code'])
 def test_missing_diagnostics_preserve_legacy_export_bytes(self):
  r=fixture();p=e.sanitize(r);self.assertNotIn('diagnostics',p);self.assertEqual(1,p['export_schema_version'])
 def test_unsafe_or_contradictory_diagnostics_are_rejected(self):
  for change in [lambda r:r['diagnostics']['samples'][0]['diagnostics']['trace'][0].update(url='private'),lambda r:r['diagnostics']['stage_summaries']['baseline'].update(events_seen=99),lambda r:r['diagnostics']['samples'][0].update(scenario='private.invalid/query'),lambda r:r['diagnostics']['samples'][0]['diagnostics'].update(action_failed=True),lambda r:r['diagnostics'].update(samples=r['diagnostics']['samples']*9)]:
   r=self.fixture();change(r)
   with self.assertRaises(ValueError):e.sanitize(r)

class LearningClinicExportTests(CityDiagnosticExportTests):
 def clinic_fixture(self):
  r=self.fixture();rows=[]
  for name in ['detail','unknown_after_click','noop_click','human']:
   status='VERIFIED' if name=='detail' else 'HUMAN_NEEDED' if name=='human' else 'FAILED'
   event=dict(code='action_outcome',page_type='DETAIL' if name=='detail' else 'UNKNOWN',status=status,phase='START',diagnostic_code='VERIFIED' if name=='detail' else 'AUTH_WALL' if name=='human' else 'UNMET_EXPECTATION',elapsed_ms=0,counters={},lesson='expand_description',capability='open_item',recheck_reason='UNKNOWN')
   event['after_page_type']='DETAIL' if name=='detail' else 'UNKNOWN' if name=='unknown_after_click' else 'RESULTS' if name=='noop_click' else 'NOT_OBSERVED'
   d={k:0 for k in COUNTS};d.update(events_seen=1,action_verified=int(name=='detail'),action_failed=int(name in ['unknown_after_click','noop_click']),trace=[event])
   rows.append(dict(case_id=name,lesson='expand_description',status='NEED_HUMAN' if name=='human' else 'PARTIAL',actions=0 if name=='human' else 1,diagnostic_expectation_met=True,diagnostics=d))
  r['diagnostics']['learning_clinic']=dict(schema_version=1,real_world_validated=False,teacher_requests=0,cases=rows)
  return r
 def test_learning_clinic_is_validated_and_published_separately(self):
  p=e.sanitize(self.clinic_fixture());self.assertEqual(4,len(p['diagnostics']['learning_clinic']['cases']));self.assertFalse(p['diagnostics']['learning_clinic']['real_world_validated'])
 def test_clinic_cannot_claim_human_actions_or_inject_text(self):
  for mutate in [lambda c:c['cases'][3].update(actions=1),lambda c:c['cases'][1].update(case_id='private'),lambda c:c['cases'][0].update(query='private'),lambda c:c.update(teacher_requests=1)]:
   r=self.clinic_fixture();mutate(r['diagnostics']['learning_clinic'])
   with self.assertRaises(ValueError):e.sanitize(r)

 def test_unsatisfied_control_is_a_published_finding_not_a_dropped_report(self):
  r=self.clinic_fixture();c=r['diagnostics']['learning_clinic']['cases'][0]
  c['diagnostics']['trace'][0]['after_page_type']='UNKNOWN';c['diagnostic_expectation_met']=False
  p=e.sanitize(r);self.assertFalse(p['diagnostics']['learning_clinic']['cases'][0]['diagnostic_expectation_met'])
