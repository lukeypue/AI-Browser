import copy,unittest
from receiver import validate,Failure
from test_receiver import report

class MemoryEvidenceTests(unittest.TestCase):
 def value(self):
  r=report();r['schema_version']=2
  r['events'][0].update(code='memory_checkpoint',diagnostic_code='STRATEGY_FAILURE_SAVED',lesson='expand_description',capability='open_item',recheck_reason='UNKNOWN',counters={'memory_loaded':1,'memory_strategy_holds':4,'strategy_prior_failures':0,'strategy_current_failures':1,'before_results':1,'after_dialog':1,'dialog_detail_controls':0})
  return r
 def test_memory_evidence_is_accepted_as_optional_v2_data(self):
  try:validate(self.value())
  except Failure:self.fail('optional typed memory evidence is not supported')
  validate(report())
 def test_memory_counts_are_strict_and_bounded(self):
  for value in [-1,1000001,True,'private',0.5]:
   r=self.value();r['events'][0]['counters']['memory_loaded']=value
   with self.assertRaises(Failure):validate(r)
 def test_arbitrary_identifiers_and_codes_remain_rejected(self):
  for key,value in [('strategy_key','private'),('private_url','https://private')]:
   r=self.value();r['events'][0]['counters'][key]=value
   with self.assertRaises(Failure):validate(r)
  r=self.value();r['events'][0]['diagnostic_code']='private'
  with self.assertRaises(Failure):validate(r)
 def test_legacy_v1_cannot_smuggle_memory_extensions(self):
  r=report();r['events'][0]['counters']['memory_loaded']=1
  with self.assertRaises(Failure):validate(r)
