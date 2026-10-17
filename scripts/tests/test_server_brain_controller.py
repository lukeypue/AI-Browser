import copy
import hashlib
import json
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server_brain import controller as c

PROPOSAL = {'schema_version':1,'capability':'search','steps':[{'kind':'TYPE','role':'SEARCH_BOX','submit':False},{'kind':'CLICK','role':'SUBMIT','submit':False}]}

def digest(data):
    return hashlib.sha256(data).hexdigest()

class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.state = c.State(Path(self.tmp.name), 'a'*64, 'b'*64)
        self.calls = 0

    def model(self, lesson):
        self.calls += 1
        return PROPOSAL

    def lab(self, request):
        current = request['current_memory']
        candidate = b'{"skill":"synthetic-search"}' if request['proposal'] and any(x['kind']=='CLICK' for x in request['proposal']['steps']) else current
        score = lambda ok,reuse: {'success':ok,'actions':1 if ok else 3,'compiled_reuse':reuse,'unsafe_actions':0,'false_verified':0,'teacher_calls':0,'teacher_demonstrations':0,'status':'DONE' if ok else 'FAILED','search_verified':ok}
        cold = score(False,False)
        warm = score(current != b'{}', current != b'{}')
        new = score(candidate != b'{}',candidate != b'{}')
        cases=[{'case_id':'held-1','profile':'KSL_CLASSIFIEDS','cold':cold,'current':warm,'candidate':new}]
        total=lambda r: {'successes':int(r['success']),'actions':r['actions'],'compiled_reuse':int(r['compiled_reuse']),'unsafe_actions':0,'false_verified':0}
        training=dict(new,case_id='train-1',teacher_demonstrations=1,compiled_reuse=False)
        report={'schema_version':1,'source_sha256':'a'*64,'manifest_sha256':'b'*64,'proposal_sha256':digest(c.canonical(request['proposal'])) if request['proposal'] else None,'current_memory_sha256':digest(current),'candidate_memory_sha256':digest(candidate),'proposal_valid':True,'rejection_reason':None if request['proposal'] else 'no_verified_gain','promotion_recommended':bool(request['proposal']),'evaluation_teacher_calls':0,'training_teacher_demonstrations':int(bool(request['proposal'])),'training_rows':[training] if request['proposal'] else [],'synthetic_only':True,'real_world_validated':False,'cases':cases,'totals':{'cold':total(cold),'current':total(warm),'candidate':total(new)},'training_manifest':[{'case_id':'train-1','profile':'KSL_CLASSIFIEDS','seed':-1,'host':'train.sim.invalid'}],'evaluation_manifest':[{'case_id':'held-1','profile':'KSL_CLASSIFIEDS','seed':1,'host':'held.sim.invalid'}]}
        # Manifest digest must independently cover exactly the two supplied lists.
        report['manifest_sha256']=digest(c.canonical({'training':report['training_manifest'],'evaluation':report['evaluation_manifest']}))
        self.state.manifest_sha256=report['manifest_sha256']
        return {'report':report,'candidate_memory':candidate}

    def browser(self):
        return {'synthetic_only':True,'real_world_validated':False,'browser_sessions':4,'peak_active_workers':4,'teacher_requests':0,'false_mastery':0,'cases':[{'passed':True,'external_requests':0}]}

    def test_proposal_closed_contract(self):
        self.assertEqual(c.validate_proposal(PROPOSAL), PROPOSAL)
        for mutate in [lambda p:p.update(code='evil'),lambda p:p['steps'][0].update(submit=True),lambda p:p['steps'][0].update(role='PASSWORD'),lambda p:p.update(schema_version=True),lambda p:p.update(steps=[]),lambda p:p['steps'][0].update(text='personal')]:
            value=copy.deepcopy(PROPOSAL);mutate(value)
            with self.assertRaises(ValueError):c.validate_proposal(value)

    def test_promotes_then_restart_evaluates_without_teacher(self):
        result=c.run_once(self.state,self.lab,self.model,self.browser,10000)
        self.assertEqual(result['status'],'promoted')
        champion=self.state.load()['champion']
        restarted=c.State(Path(self.tmp.name),'a'*64,self.state.manifest_sha256)
        self.assertEqual(c.run_once(restarted,self.lab,self.model,self.browser,10060)['status'],'evaluated')
        self.assertEqual(restarted.load()['champion'],champion)
        self.assertEqual(self.calls,1)

    def test_failed_teacher_is_charged_durably_and_spaced(self):
        def fail(_): raise RuntimeError('private failure must not persist')
        self.assertEqual(c.run_once(self.state,self.lab,fail,self.browser,10000)['status'],'failed')
        self.assertEqual(c.run_once(self.state,self.lab,self.model,self.browser,10060)['status'],'cooldown')
        self.assertEqual(self.state.load()['reservations'],[10000])
        self.assertNotIn('private failure',self.state.path.read_text())

    def test_budget_hour_day_and_clock_reversal(self):
        data=self.state.load();data['reservations']=sorted(100000-i*901 for i in range(24));self.state.save(data)
        self.assertFalse(c.reserve(self.state,100500))
        self.assertFalse(c.reserve(self.state,99999))
        self.assertFalse(c.reserve(self.state,101000))
        self.assertTrue(c.reserve(self.state,200000))

    def test_tampered_champion_rejected(self):
        self.assertEqual(c.run_once(self.state,self.lab,self.model,self.browser,10000)['status'],'promoted')
        data=self.state.load();data['champion']['memory']={'forged':'value'};self.state.path.write_text(json.dumps(data))
        with self.assertRaises(ValueError):self.state.load()

    def test_no_gain_and_forged_proof_cannot_promote(self):
        self.assertEqual(c.run_once(self.state,self.lab,self.model,self.browser,10000)['status'],'promoted')
        before=self.state.load()['champion']
        def forged(request):
            result=self.lab(request);result['report']['candidate_memory_sha256']='0'*64;return result
        self.assertEqual(c.run_once(self.state,forged,self.model,self.browser,10060)['status'],'failed')
        self.assertEqual(self.state.load()['champion'],before)

    def test_unsafe_browser_prevents_teacher(self):
        def bad():
            result=self.browser();result['cases'][0]['external_requests']=1;return result
        self.assertEqual(c.run_once(self.state,self.lab,self.model,bad,10000)['status'],'failed')
        self.assertEqual(self.calls,0)

    def test_single_writer_and_previous_rollback(self):
        import fcntl
        with self.state.lock_path.open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.assertEqual(c.run_once(self.state,self.lab,self.model,self.browser,10000)['status'],'busy')
        self.assertEqual(c.run_once(self.state,self.lab,self.model,self.browser,10000)['status'],'promoted')
        self.assertTrue(self.state.previous_path.exists())
        self.state.rollback()
        self.assertIsNone(self.state.load()['champion'])

    def test_retention_and_size_limit(self):
        data=self.state.load();data['reports']=[{'status':'x'}]*120;data['proposals']=[PROPOSAL]*40;self.state.save(data)
        self.assertLess(len(self.state.load()['reports']),100)
        self.assertLess(len(self.state.load()['proposals']),24)
        data=self.state.load();data['junk']='x'*(10*1024*1024)
        with self.assertRaises(ValueError):self.state.save(data)

    def test_equal_score_never_upgrade_even_if_lab_recommends(self):
        def equal(request):
            result=self.lab(request)
            if request['proposal']:
                report=result['report']
                report['cases'][0]['candidate']=copy.deepcopy(report['cases'][0]['current'])
                report['totals']['candidate']=copy.deepcopy(report['totals']['current'])
            return result
        self.assertEqual(c.run_once(self.state,equal,self.model,self.browser,10000)['status'],'no_gain')
        self.assertIsNone(self.state.load()['champion'])

    def test_forged_totals_unsafe_and_teacher_eval_rejected(self):
        mutations=[lambda r:r['totals']['candidate'].update(successes=999),lambda r:r['cases'][0]['candidate'].update(unsafe_actions=1),lambda r:r['cases'][0]['candidate'].update(false_verified=1),lambda r:r.update(evaluation_teacher_calls=1),lambda r:r['cases'][0]['candidate'].update(teacher_calls=1),lambda r:r.update(unrecognized='extra')]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                def bad(request):
                    result=self.lab(request);mutate(result['report']);return result
                self.assertEqual(c.run_once(self.state,bad,self.model,self.browser,10000)['status'],'failed')
                self.assertIsNone(self.state.load()['champion'])

    def test_candidate_must_preserve_independent_baseline_scores(self):
        def drift(request):
            result=self.lab(request)
            if request['proposal']:
                result['report']['cases'][0]['cold']['actions']=4
                result['report']['totals']['cold']['actions']=4
            return result
        self.assertEqual(c.run_once(self.state,drift,self.model,self.browser,10000)['status'],'failed')
        self.assertIsNone(self.state.load()['champion'])

    def test_crash_after_reservation_survives_restart(self):
        def crash(_):raise KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):c.run_once(self.state,self.lab,crash,self.browser,10000)
        restarted=c.State(Path(self.tmp.name),'a'*64,self.state.manifest_sha256)
        self.assertEqual(c.run_once(restarted,self.lab,self.model,self.browser,10001)['status'],'cooldown')
        self.assertEqual(self.calls,0)
        self.assertEqual(restarted.load()['reservations'],[10000])

    def test_interrupted_atomic_replace_keeps_previous_state(self):
        data=self.state.load();self.state.save(data);before=self.state.path.read_bytes()
        data['reports'].append({'status':'test'})
        with patch.object(c.os,'replace',side_effect=OSError('simulated interruption')):
            with self.assertRaises(OSError):self.state.save(data)
        self.assertEqual(self.state.path.read_bytes(),before)

    def test_gain_without_compiled_reuse_or_cold_ablation_rejected(self):
        for index,mode in enumerate(['no_reuse','no_ablation']):
            with self.subTest(mode=mode):
                state=c.State(Path(self.tmp.name)/mode,'a'*64,self.state.manifest_sha256)
                def bad(request):
                    result=self.lab(request);report=result['report']
                    if mode=='no_reuse':
                        report['cases'][0]['candidate']['compiled_reuse']=False
                        report['totals']['candidate']['compiled_reuse']=0
                    else:
                        report['cases'][0]['cold']=copy.deepcopy(report['cases'][0]['candidate'])
                        report['totals']['cold']=copy.deepcopy(report['totals']['candidate'])
                    return result
                self.assertIn(c.run_once(state,bad,self.model,self.browser,10000+900*index)['status'],['no_gain','failed'])
                self.assertIsNone(state.load()['champion'])

    def test_totals_boolean_cannot_impersonate_integer(self):
        def bad(request):
            result=self.lab(request)
            result['report']['totals']['cold']['successes']=False
            return result
        self.assertEqual(c.run_once(self.state,bad,self.model,self.browser,10000)['status'],'failed')

    def test_success_gain_can_require_more_actions_than_early_failure(self):
        def completed(request):
            result=self.lab(request)
            if request['proposal']:
                result['report']['cases'][0]['candidate']['actions']=4
                result['report']['totals']['candidate']['actions']=4
            return result
        self.assertEqual(c.run_once(self.state,completed,self.model,self.browser,10000)['status'],'promoted')

    def test_champion_requires_all_provenance_hashes(self):
        self.assertEqual(c.run_once(self.state,self.lab,self.model,self.browser,10000)['status'],'promoted')
        data=self.state.load();data['champion']['report_sha256']='missing';self.state.path.write_text(json.dumps(data))
        with self.assertRaises(ValueError):self.state.load()

    def test_champion_retains_reviewable_synthetic_proof(self):
        result=c.run_once(self.state,self.lab,self.model,self.browser,10000)
        self.assertEqual(result['status'],'promoted')
        self.assertNotIn('proof',result)
        data=self.state.load()
        self.assertIn('proof',data['champion'])
        self.assertEqual(digest(c.canonical(data['champion']['proof'])),data['champion']['report_sha256'])
        self.assertEqual(data['reports'][-1]['proof'],data['champion']['proof'])

    def test_bad_proposal_retries_after_cooldown_with_typed_feedback(self):
        bad={'schema_version':1,'capability':'search','steps':[{'kind':'TYPE','role':'SEARCH_BOX','submit':False}]}
        seen=[]
        def teacher(lesson):
            seen.append(lesson)
            return bad if len(seen)==1 else PROPOSAL
        self.assertEqual(c.run_once(self.state,self.lab,teacher,self.browser,10000)['status'],'no_gain')
        self.assertEqual(c.run_once(self.state,self.lab,teacher,self.browser,10060)['status'],'cooldown')
        self.assertEqual(c.run_once(self.state,self.lab,teacher,self.browser,10900)['status'],'promoted')
        self.assertEqual(len(seen),2)
        self.assertEqual(seen[-1]['prior_outcome']['outcome'],'NO_GAIN')
        self.assertEqual(seen[-1]['prior_outcome']['proposal_sha256'],digest(c.canonical(bad)))
        self.assertEqual(self.state.load()['failed_proposal_sha256'],[digest(c.canonical(bad))])
        c.validate_lesson(seen[-1])

    def test_duplicate_failed_proposal_is_not_retrained_or_promoted(self):
        bad={'schema_version':1,'capability':'search','steps':[{'kind':'TYPE','role':'SEARCH_BOX','submit':False}]}
        requests=[]
        def measured(request):requests.append(request);return self.lab(request)
        self.assertEqual(c.run_once(self.state,measured,lambda _:bad,self.browser,10000)['status'],'no_gain')
        self.assertEqual(c.run_once(self.state,measured,lambda _:bad,self.browser,10900)['status'],'duplicate_proposal')
        self.assertEqual(sum(x['proposal'] is not None for x in requests),1)
        self.assertIsNone(self.state.load()['champion'])
        self.assertEqual(self.state.load()['reservations'],[10000,10900])

if __name__=='__main__':unittest.main()
