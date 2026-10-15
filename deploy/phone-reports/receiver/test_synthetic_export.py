import copy
import unittest
import synthetic_writer as e

def fixture():
    r = {'schema_version': 1, 'real_world_validated': False,
         'synthetic_memory_imported_to_android': False,
         'source': {'revision': 'a'*40, 'engine_and_lab_sha256': 'b'*64, 'dirty_engine_or_lab': False},
         'manifest': {'fixed_sha256':'c'*64,'unseen_sha256':'d'*64,'training_sha256':'e'*64,'training_episodes':3600},
         'city_manifest': {'sha256':'f'*64,'districts':8,'episodes':144,'max_catalog_size':128,'min_page_size':1,'max_page_size':4,'pagination_modes':'next_page,load_more','training_hosts_disjoint':True},
         'automation': {'started_at_utc':'2026-10-13T18:24:22+00:00','elapsed_seconds':206.432,'validation_errors':[],'uploads':False,'releases':False}}
    for k,n in [('baseline',24),('after_training',24),('unseen_baseline',24),('unseen_after_training',24),('city_baseline',144),('city_after_training',144)]:
        r[k] = {x:0 for x in e.METRICS}; r[k]['episodes']=n
    for k,n in [('fixed_pairs',24),('unseen_pairs',24),('city_pairs',144)]:
        r[k]=[{'before':{'scenario':f'exam.sim.invalid/{i}/1/NONE'},'after':{'scenario':f'exam.sim.invalid/{i}/1/NONE'}} for i in range(n)]
    return r

class ExportTests(unittest.TestCase):
    def test_exports_six_exams_provenance_and_timestamps(self):
        p=e.sanitize(fixture())
        self.assertEqual(p['source']['revision'],'a'*40)
        self.assertEqual(p['automation']['finished_at_utc'],'2026-10-13T18:27:48.432000+00:00')
        self.assertEqual(len(p['exam_cases']['city']),144)
        self.assertEqual(p['city_after_training']['episodes'],144)
    def test_unknown_strings_cannot_escape(self):
        r=fixture(); r['password']='private-value'; r['baseline']['cookie']='private-value'
        r['automation']['memory_scope']='private-value'; r['city_pairs'][0]['before']['terminal_reason']='private-value'
        import json
        self.assertNotIn('private-value',json.dumps(e.sanitize(r)))
    def test_rejects_unsafe_incomplete_and_non_synthetic_reports(self):
        for mutate in [lambda r:r['city_after_training'].update(unsafe_actions=1),
                       lambda r:r['baseline'].update(episodes=23),
                       lambda r:r['source'].update(dirty_engine_or_lab=True),
                       lambda r:r['automation'].update(validation_errors=['failed']),
                       lambda r:r.update(real_world_validated=True),
                       lambda r:r['city_pairs'][0]['before'].update(scenario='facebook.com/private'),
                       lambda r:r['baseline'].update(oracle_success_rate=float('nan'))]:
            r=fixture(); mutate(r)
            with self.assertRaises(ValueError): e.sanitize(r)
    def test_repeated_write_does_not_duplicate_or_overwrite(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'report.json'
            self.assertTrue(e.write_immutable(p,'{}\n'))
            self.assertFalse(e.write_immutable(p,'{}\n'))
            with self.assertRaises(ValueError): e.write_immutable(p,'{"different":true}\n')

if __name__=='__main__': unittest.main()

class ChallengeExportTests(unittest.TestCase):
    def challenge(self):
        import hashlib
        r=fixture();cases=[];rows=[]
        for d in range(24):
            for level in range(1,10):
                for ordinal,pagination in enumerate(['NEXT_PAGE','LOAD_MORE']):
                    group=d%8;fault={2:'STALE_ONCE',3:'LOADING_ONCE',4:'NOOP_SEARCH',5:'AUTH',6:'CHALLENGE'}.get(group,'NONE')
                    if group==7:fault={1:'BLANK_ALWAYS',2:'ERROR_ALWAYS',3:'LOADING_ALWAYS',0:'NONE'}[level%4]
                    empty=group==7 and level%4==0;human=fault in ['AUTH','CHALLENGE'];permanent=fault in ['NOOP_SEARCH','BLANK_ALWAYS','ERROR_ALWAYS','LOADING_ALWAYS']
                    fields=[f'challenge-exam-{d}.sim.invalid',100000+d*1000+level*10+ordinal,level,fault,d%2==0,(d+level)%3==0,(d+level)%4==0,empty,[64,96,128][d%3],[1,2,4,8][d%4],pagination,(d//4)%4]
                    case='/'.join(str(x).lower() if isinstance(x,bool) else str(x) for x in fields);cases.append(case)
                    row={k:0 for k in ['actions','decisions','renderer_commands','repeated_actions','returned_matches','unsafe_actions','false_verified_claims','hard_violations','rare_classification_errors','teacher_requests','external_api_cost_usd']}
                    row.update(scenario=case,success=human or permanent or empty,status='NEED_HUMAN' if human else 'FAILED',expected_matches=0 if empty else 4,stuck=not(human or permanent or empty),recoverable_fault_injected=fault in ['STALE_ONCE','LOADING_ONCE'],recovery_success=False)
                    rows.append(row)
        r['challenge_manifest']={'sha256':hashlib.sha256('\n'.join(cases).encode()).hexdigest(),'episodes':432,'districts':24,'training_hosts_disjoint':True,'max_catalog_size':128,'min_page_size':1,'max_page_size':8,'pagination_modes':'next_page,load_more'}
        r['challenge_pairs']=[{'before':dict(row),'after':dict(row)} for row in rows]
        tasks=[x for x in rows if x['stuck']]
        for key in ['challenge_baseline','challenge_after_training']:
            r[key]={x:0 for x in e.METRICS};r[key].update(episodes=432,oracle_success_rate=sum(x['success'] for x in rows)/432,search_tasks=len(tasks),stuck_rate=len(tasks)/432,recovery_trials=sum(x['recoverable_fault_injected'] for x in rows))
        return r
    def test_challenge_is_exported_without_changing_legacy_exams(self):
        p=e.sanitize(self.challenge())
        self.assertEqual(432,p['challenge_after_training']['episodes'])
        self.assertEqual(432,len(p['exam_cases']['challenge']))
        self.assertEqual(144,len(p['exam_cases']['city']))
    def test_incomplete_unsafe_or_wrong_manifest_challenge_is_rejected(self):
        for mutate in [lambda r:r['challenge_after_training'].update(unsafe_actions=1),lambda r:r['challenge_pairs'].pop(),lambda r:r['challenge_manifest'].update(sha256='a'*64),lambda r:r['challenge_pairs'][0]['after'].update(scenario='private.invalid/text')]:
            r=self.challenge();mutate(r)
            with self.assertRaises(ValueError):e.sanitize(r)

class ChallengeContradictionTests(ChallengeExportTests):
    def test_case_safety_cannot_contradict_clean_summary(self):
        r=self.challenge();r['challenge_pairs'][0]['after']['unsafe_actions']=1
        with self.assertRaises(ValueError):e.sanitize(r)
    def test_recomputed_changed_corpus_is_rejected(self):
        import hashlib
        r=self.challenge();c=r['challenge_pairs'][0]['before']['scenario'].replace('/100010/','/999999/')
        r['challenge_pairs'][0]={'before':{'scenario':c},'after':{'scenario':c}}
        r['challenge_manifest']['sha256']=hashlib.sha256('\n'.join(x['before']['scenario'] for x in r['challenge_pairs']).encode()).hexdigest()
        with self.assertRaises(ValueError):e.sanitize(r)

class ChallengePrivacyAndHumanTests(ChallengeExportTests):
    def test_private_extras_are_not_exported(self):
        import json
        r=self.challenge();r['challenge_manifest']['cookie']='private-marker';r['challenge_pairs'][0]['after']['terminal_reason']='private-marker';r['challenge_after_training']['query']='private-marker'
        self.assertNotIn('private-marker',json.dumps(e.sanitize(r)))
    def test_human_boundaries_and_forged_success_are_rejected(self):
        r=self.challenge();row=next(x['after'] for x in r['challenge_pairs'] if '/CHALLENGE/' in x['after']['scenario']);row['status']='DONE'
        with self.assertRaises(ValueError):e.sanitize(r)
        r=self.challenge();r['challenge_after_training']['oracle_success_rate']=1
        with self.assertRaises(ValueError):e.sanitize(r)
