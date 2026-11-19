import unittest
from receiver import validate,Failure
from test_receiver import report
class PlannerFailureValidationTests(unittest.TestCase):
 def fixture(self):
  r=report();r['schema_version']=2;e=r['events'][0];e.update(lesson='UNKNOWN',capability='custom',recheck_reason='UNKNOWN',code='planner_outcome',planner_outcome='ERROR',planner_failure='AUTHENTICATION',planner_provider='OPENAI');e['counters'].update(http_status=401,planner_elapsed_ms=12);r['summary']['planner_auth_errors']=31;return r
 def test_typed_failure_and_trim_resistant_total_are_valid(self):validate(self.fixture());validate(report())
 def test_arbitrary_or_invalid_metadata_is_rejected(self):
  for k,v in [('planner_failure','private'),('planner_provider','private')]:
   r=self.fixture();r['events'][0][k]=v
   with self.assertRaises(Failure):validate(r)
  for status in [True,0,99,600,1000,'401']:
   r=self.fixture();r['events'][0]['counters']['http_status']=status
   with self.assertRaises(Failure):validate(r)
 def test_v1_cannot_accept_new_metadata(self):
  r=report();r['events'][0]['planner_failure']='AUTHENTICATION'
  with self.assertRaises(Failure):validate(r)

class PlannerClinicValidationTests(unittest.TestCase):
 def fixture(self):
  from test_city_diagnostics import CityDiagnosticExportTests
  r=CityDiagnosticExportTests().fixture();rows=[]
  for case,failure,status in [('authentication','AUTHENTICATION',401),('permission','PERMISSION',403),('rate_limit','RATE_LIMIT',429),('server','SERVER',503),('timeout','TIMEOUT',0),('invalid_response','INVALID_RESPONSE',0),('unknown','UNKNOWN',0),('returned','NONE',0)]:
   event=dict(code='planner_outcome',page_type='UNKNOWN',status='UNKNOWN',phase='START',diagnostic_code='UNKNOWN',elapsed_ms=0,counters={'planner_elapsed_ms':0},lesson='UNKNOWN',capability='custom',recheck_reason='UNKNOWN',planner_outcome='GIVE_UP' if case=='returned' else 'ERROR')
   if failure!='NONE':event.update(planner_failure=failure,planner_provider='UNKNOWN')
   if status:event['counters']['http_status']=status
   rows.append(dict(case_id=case,client_calls=1,diagnostic_expectation_met=True,planner_result=event))
  r['diagnostics']['planner_clinic']=dict(schema_version=1,synthetic_only=True,external_requests=0,cases=rows);return r
 def test_synthetic_clinic_is_independently_validated(self):
  import synthetic_writer
  p=synthetic_writer.sanitize(self.fixture());self.assertEqual(8,len(p['diagnostics']['planner_clinic']['cases']))
 def test_clinic_rejects_text_wrong_cause_and_external_requests(self):
  import synthetic_writer
  for change in [lambda c:c.update(external_requests=1),lambda c:c['cases'][0]['planner_result'].update(url='private'),lambda c:c['cases'][0]['planner_result'].update(planner_failure='RATE_LIMIT'),lambda c:c['cases'][0].update(client_calls=2),lambda c:c['cases'][0].update(diagnostic_expectation_met=False)]:
   r=self.fixture();change(r['diagnostics']['planner_clinic'])
   with self.assertRaises(ValueError):synthetic_writer.sanitize(r)

 def test_failed_control_is_preserved_as_a_false_finding(self):
  import synthetic_writer
  r=self.fixture();c=r['diagnostics']['planner_clinic']['cases'][0];c['planner_result']['planner_failure']='UNKNOWN';c['diagnostic_expectation_met']=False
  p=synthetic_writer.sanitize(r);self.assertFalse(p['diagnostics']['planner_clinic']['cases'][0]['diagnostic_expectation_met'])

 def test_missing_terminal_or_extra_fake_call_is_a_false_finding(self):
  import synthetic_writer
  for key,value in [('planner_result',None),('client_calls',2)]:
   r=self.fixture();c=r['diagnostics']['planner_clinic']['cases'][0];c[key]=value;c['diagnostic_expectation_met']=False
   p=synthetic_writer.sanitize(r);self.assertFalse(p['diagnostics']['planner_clinic']['cases'][0]['diagnostic_expectation_met'])
