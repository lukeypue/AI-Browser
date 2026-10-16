import copy,json,unittest
import synthetic_writer as e
from test_synthetic_export import fixture

def clinic():
    ids=['small-modal','wide-modal','nested-close','icon-close','noop-close','missing-close','header-chrome','offscreen-dialog','login-wall','captcha-wall']
    expectations=['CLOSE']*4+['NOOP','MISSING','CHROME','CHROME','HUMAN','HUMAN']
    c={'schema_version':1,'fixture_cases':12,'held_out_cases':10,'manifest_sha256':'94d28e1439971863a0867207d29fef3c986b32ffde22937e861548b720158981','fixture_sha256':'7fb2a0c66abef0626a3be05e13a5407b52ce11ad68405d43cff43e54caf3f853','extractor_sha256':'adfe635862be7b529604c65a28bb81a0ceb433aca97a2c83ead5db79aaea0be4','source':'CHROMIUM_GENERATED_REPLAY','teacher':'LOCAL_SCRIPTED','real_world_validated':False,'training_demonstrations':4,'evaluation_teacher_calls':0,'compiled_dialog_skills':1,'behavior_passes':10,'verified_closures':4,'closure_opportunities':4,'compiled_transfer_uses':4,'unsafe_actions':0,'wrong_targets':0,'false_mastery':0}
    for phase in ['baseline','after']:
        c[phase]=[{'case_id':id,'expected':kind,'status':'DONE' if kind=='CLOSE' else 'NEED_HUMAN' if kind=='HUMAN' else 'FAILED' if kind=='NOOP' else 'PARTIAL','dialog_detected':kind!='CHROME','human_detected':kind=='HUMAN','recognized_dialog_closers':1 if kind in ['CLOSE','NOOP'] else 0,'actions':1 if kind=='CLOSE' else 2 if kind=='NOOP' else 0,'decisions':3,'verified':kind=='CLOSE','physically_closed':kind in ['CLOSE','CHROME'],'wrong_targets':0,'unsafe_actions':0,'false_mastery':0,'passed':True,'compiled_procedure_used':phase=='after' and kind=='CLOSE'} for id,kind in zip(ids,expectations)]
    return c

class DialogClinicExportTests(unittest.TestCase):
    def test_export_includes_safe_teaching_and_transfer_evidence(self):
        r=fixture();r['dialog_clinic']=clinic();p=e.sanitize(r)
        self.assertEqual(4,p['dialog_clinic']['compiled_transfer_uses'])
    def test_false_mastery_changed_fixture_and_human_actions_are_rejected(self):
        for mutate in [lambda c:c.update(fixture_sha256='a'*64),lambda c:c['after'][4].update(verified=True),lambda c:c['after'][-1].update(actions=1),lambda c:c.update(behavior_passes=9)]:
            r=fixture();r['dialog_clinic']=clinic();mutate(r['dialog_clinic'])
            with self.assertRaises(ValueError):e.sanitize(r)
    def test_personal_extras_are_dropped(self):
        r=fixture();r['dialog_clinic']=clinic();r['dialog_clinic']['cookie']='private-marker';r['dialog_clinic']['after'][0]['query']='private-marker'
        self.assertNotIn('private-marker',json.dumps(e.sanitize(r)))

class DialogClinicConsistencyTests(unittest.TestCase):
    def test_reuse_requires_a_compiled_procedure_and_a_real_action(self):
        for mutate in [lambda c:c.update(compiled_dialog_skills=0),lambda c:c['after'][0].update(actions=0)]:
            r=fixture();r['dialog_clinic']=clinic();mutate(r['dialog_clinic'])
            with self.assertRaises(ValueError):e.sanitize(r)
