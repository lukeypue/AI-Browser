import copy,hashlib,json,tempfile,unittest
from pathlib import Path
from test_receiver import report
from receiver import canonical,Failure
from publisher import prepare

class PublisherTests(unittest.TestCase):
 def test_preserves_synthetic_and_unreviewed_reports_without_duplicates(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);out=root/'out';repo=root/'repo';out.mkdir();(repo/'reports').mkdir(parents=True);(repo/'reports/latest.json').write_text('synthetic')
   r=report();doc={'report':r,'receipt':{'report_id':r['report_id'],'sha256':hashlib.sha256(canonical(r)).hexdigest(),'received_at_utc':'2026-10-13T19:00:01+00:00'}}
   (out/(r['report_id']+'.json')).write_text(json.dumps(doc))
   prepare(out,repo);first=(repo/'phone-reports/index.json').read_bytes();prepare(out,repo)
   self.assertEqual(first,(repo/'phone-reports/index.json').read_bytes());self.assertEqual((repo/'reports/latest.json').read_text(),'synthetic')
   (out/(r['report_id']+'.json')).unlink();prepare(out,repo)
   self.assertEqual(first,(repo/'phone-reports/index.json').read_bytes());self.assertTrue((repo/'phone-reports'/(r['report_id']+'.json')).exists())
 def test_independent_validation_rejects_personal_extras_and_wrong_hash(self):
  for extra in (True,False):
   with tempfile.TemporaryDirectory() as d:
    root=Path(d);out=root/'out';repo=root/'repo';out.mkdir();repo.mkdir();r=report()
    sha=hashlib.sha256(canonical(r)).hexdigest()
    if extra:r['cookie']='secret'
    doc={'report':r,'receipt':{'report_id':r['report_id'],'sha256':'0'*64 if not extra else sha,'received_at_utc':'2026-10-13T19:00:01+00:00'}}
    (out/(r['report_id']+'.json')).write_text(json.dumps(doc))
    with self.assertRaises((Failure,ValueError)):prepare(out,repo)
    self.assertFalse((repo/'phone-reports'/ (r['report_id']+'.json')).exists())

if __name__=='__main__':unittest.main()
