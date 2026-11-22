import unittest,copy
from receiver import validate,Failure
from test_receiver import report
class VerificationEvidenceTests(unittest.TestCase):
 def fixture(self):
  r=report();r['schema_version']=2;e=r['events'][0];e.update(lesson='open_item',capability='open_item',recheck_reason='UNKNOWN',action_kind='WAIT',target_class='NONE',primary_expectation='NONE',verification_reason='NO_EXPECTATIONS',proposal_rejection='WRONG_TARGET');e['counters'].update(expected_checks=0,satisfied_checks=0,unmet_checks=0,optional_step=1,observation_rechecks=2,observation_read_failed=0,text_expansion_verified=0,semantic_changed=0,route_changed=0,target_controls_before=2,target_controls_after=2);return r
 def test_accepts_closed_evidence_and_legacy(self):validate(self.fixture());validate(report())
 def test_rejects_unknown_evidence_and_out_of_range_flags(self):
  for key in ['action_kind','target_class','primary_expectation','verification_reason','proposal_rejection']:
   r=self.fixture();r['events'][0][key]='personal text'
   with self.assertRaises(Failure):validate(r)
  for key in ['optional_step','observation_read_failed','text_expansion_verified','semantic_changed','route_changed']:
   r=self.fixture();r['events'][0]['counters'][key]=2
   with self.assertRaises(Failure):validate(r)
 def test_legacy_schema_cannot_add_new_fields(self):
  r=report();r['events'][0]['action_kind']='WAIT'
  with self.assertRaises(Failure):validate(r)

 def test_observation_rechecks_are_bounded(self):
  for bad in [True,-1,3,1000001,'private']:
   r=self.fixture();r['events'][0]['counters']['observation_rechecks']=bad
   with self.assertRaises(Failure):validate(r)
