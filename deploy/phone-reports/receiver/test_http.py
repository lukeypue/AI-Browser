import json,threading,unittest,urllib.request,urllib.error,tempfile
from pathlib import Path
from http.server import HTTPServer
from test_receiver import report
from receiver import Handler,Store

class HttpTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.store=Store(Path(self.temp.name));self.token='x'*43
  self.device=self.store.pair(self.store.issue_code(),self.token);Handler.store=self.store
  self.server=HTTPServer(('127.0.0.1',0),Handler);self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
 def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join();self.temp.cleanup()
 def post(self,path,obj,headers=None):
  request=urllib.request.Request('http://127.0.0.1:'+str(self.server.server_port)+path,data=json.dumps(obj).encode(),headers={'Content-Type':'application/json',**(headers or {})})
  try:
   with urllib.request.urlopen(request,timeout=3) as response:return response.status,json.load(response)
  except urllib.error.HTTPError as response:return response.code,json.load(response)
 def test_loopback_http_contract_rejects_non_tls_forwarding_and_checks_receipt(self):
  r=report();status,_=self.post('/v1/reports',r);self.assertEqual(status,403)
  headers={'X-Forwarded-Proto':'https','Authorization':'Bearer '+self.device+'.'+self.token}
  status,receipt=self.post('/v1/reports',r,headers);self.assertEqual(status,200)
  again=self.post('/v1/reports',r,headers);self.assertEqual(again,(200,receipt))
  r['summary']['verified']=2;self.assertEqual(self.post('/v1/reports',r,headers)[0],409)
 def test_pairing_and_upload_privileges_are_separate_from_read_access(self):
  code=self.store.issue_code();status,paired=self.post('/v1/pair',{'code':code,'credential':'z'*43},{'X-Forwarded-Proto':'https'})
  self.assertEqual(status,201);self.assertIn('device_id',paired)
  self.assertEqual(self.post('/v1/pair',{'code':code,'credential':'y'*43},{'X-Forwarded-Proto':'https'})[0],401)
  self.assertEqual(self.post('/v1/reports',report(),{'X-Forwarded-Proto':'https'})[0],401)
  with self.assertRaises(urllib.error.HTTPError) as error:urllib.request.urlopen('http://127.0.0.1:'+str(self.server.server_port)+'/v1/reports')
  self.assertEqual(error.exception.code,404)
