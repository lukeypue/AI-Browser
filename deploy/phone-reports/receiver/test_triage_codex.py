import json,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import triage_codex

AGG={'schema_version':1,'source':'PHONE_LIVE_TYPED','site':'KSL','lesson':'dismiss_dialog','capability':'dismiss_dialog','page_type':'DIALOG','diagnostic_code':'NO_TARGET','closers':'ZERO','controls':'ZERO','repeat_count':3}
ANSWER={'focus':'NO_SAFE_CONTROL','next_check':'CONTROL_DISCOVERY','confidence':'MEDIUM','needs_human':False}
class CodexTests(unittest.TestCase):
 def test_supported_exec_flags_isolate_request_and_disable_tools(self):
  def invoke(args,**kw):
   self.assertEqual(args[:3],['/usr/local/bin/codex','exec','--ignore-user-config'])
   for flag in ['--ignore-rules','--ephemeral','--skip-git-repo-check','--output-schema','--output-last-message']:self.assertIn(flag,args)
   self.assertEqual(args[args.index('--sandbox')+1],'read-only');self.assertEqual(args[-1],'-')
   for name in ['shell_tool','multi_agent','apps','plugins','hooks','computer_use','browser_use','image_generation','view_image','skill_search','workspace_dependencies','unified_exec']:
    self.assertIn(['--disable',name],[args[i:i+2] for i in range(len(args)-1)])
   self.assertLessEqual(kw['timeout'],120);self.assertEqual(kw['stderr'],subprocess.DEVNULL);self.assertEqual(kw['stdout'],subprocess.DEVNULL)
   self.assertEqual(json.loads(kw['input'].split('\n')[-1]),AGG)
   self.assertEqual(kw['env']['HOME'],'/root');self.assertNotIn('OPENAI_API_KEY',kw['env'])
   schema=json.loads(Path(args[args.index('--output-schema')+1]).read_text());self.assertFalse(schema['additionalProperties'])
   Path(args[args.index('--output-last-message')+1]).write_text(json.dumps(ANSWER));return subprocess.CompletedProcess(args,0)
  with patch('triage_codex.subprocess.run',side_effect=invoke):self.assertEqual(triage_codex.CodexModel()(AGG),ANSWER)
 def test_rejects_non_typed_input_before_transport(self):
  for update in [{'url':'https://private'},{'site':'private text'},{'repeat_count':True},{'controls':'arbitrary'},{'page_type':'AUTH_WALL'},{'diagnostic_code':'CHALLENGE'}]:
   with patch('triage_codex.subprocess.run') as run:
    with self.assertRaises(ValueError):triage_codex.CodexModel()(dict(AGG,**update))
    run.assert_not_called()
 def test_timeout_nonzero_and_malformed_result_return_only_generic_failure(self):
  for mode in ['timeout','exit','malformed','oversize','extra']:
   def invoke(args,**kw):
    if mode=='timeout':raise subprocess.TimeoutExpired(args,120,stderr='private provider error')
    if mode=='exit':return subprocess.CompletedProcess(args,1)
    out=Path(args[args.index('--output-last-message')+1]);out.write_text('x'*2049 if mode=='oversize' else json.dumps(dict(ANSWER,command='bad')) if mode=='extra' else 'private text')
    return subprocess.CompletedProcess(args,0)
   with patch('triage_codex.subprocess.run',side_effect=invoke):
    with self.assertRaisesRegex(ValueError,'^MODEL_UNAVAILABLE$'):triage_codex.CodexModel()(AGG)
 def test_timeout_configuration_is_bounded(self):
  for timeout in [0,121,True]:
   with self.assertRaises(ValueError):triage_codex.CodexModel(timeout=timeout)
if __name__=='__main__':unittest.main()
