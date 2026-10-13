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
