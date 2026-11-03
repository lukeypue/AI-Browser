import unittest,copy
from receiver import validate,Failure
from test_receiver import report
class JourneyValidationTests(unittest.TestCase):
 def fixture(self):
  r=report();r['schema_version']=2;e=r['events'][0];e.update(lesson='search',capability='search',recheck_reason='UNKNOWN',before_page_type='RESULTS',after_page_type='NOT_OBSERVED',before_settle='IDLE',after_settle='NOT_OBSERVED',program_source='SKILL');e['counters'].update(task_sequence=1,program_sequence=2,action_sequence=3);r['summary'].update(new_lessons=2,neutral_programs=11,held_programs=4,planner_errors=3);return r
 def test_optional_journey_coordinates_and_totals_are_accepted(self):validate(self.fixture());validate(report())
 def test_legacy_v2_without_optional_coordinates_remains_valid(self):
  r=report();r['schema_version']=2;r['events'][0].update(lesson='search',capability='search',recheck_reason='UNKNOWN');validate(r)
 def test_unknown_enums_or_counter_injection_are_rejected(self):
  for key in ['before_page_type','after_page_type','before_settle','after_settle','program_source']:
   r=self.fixture();r['events'][0][key]='private';
   with self.assertRaises(Failure):validate(r)
  for bad in [True,-1,1000001,'private']:
   r=self.fixture();r['summary']['new_lessons']=bad
   with self.assertRaises(Failure):validate(r)
 def test_v1_cannot_smuggle_journey_extensions(self):
  r=report();r['events'][0]['before_page_type']='RESULTS'
  with self.assertRaises(Failure):validate(r)
