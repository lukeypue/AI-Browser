import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from server_brain import transport as t
from server_brain.controller import LESSON, canonical

class TransportTests(unittest.TestCase):
    def test_schema_is_closed_and_no_input_queries_or_code(self):
        self.assertFalse(t.SCHEMA['additionalProperties'])
        self.assertEqual(t.SCHEMA['properties']['steps']['maxItems'],3)
        self.assertNotIn('query',LESSON)
        self.assertNotIn('code',LESSON)
        self.assertNotIn('then CLICK',t.PROMPT)
        self.assertNotIn('Use TYPE',t.PROMPT)

    def test_codex_uses_tool_disabled_ephemeral_transport_and_fixed_lesson(self):
        seen={}
        proposal={'schema_version':1,'capability':'search','steps':[{'kind':'TYPE','role':'SEARCH_BOX','submit':False}]}
        def execute(args,**kwargs):
            seen.update(args=args,kwargs=kwargs)
            Path(args[args.index('--output-last-message')+1]).write_bytes(canonical(proposal))
        with patch.object(t,'execute',execute):
            self.assertEqual(t.CodexModel()(LESSON),proposal)
        self.assertIn('--ignore-user-config',seen['args'])
        self.assertIn('--ignore-rules',seen['args'])
        self.assertIn('--ephemeral',seen['args'])
        self.assertIn('web_search="disabled"',seen['args'])
        self.assertIn('shell_tool',seen['args'])
        self.assertIn('plugins',seen['args'])
        self.assertEqual(seen['kwargs']['env']['HOME'],'/root')
        with self.assertRaises(ValueError):t.CodexModel()({'query':'private'})

    def test_timeout_kills_whole_process_group(self):
        started=time.monotonic()
        with self.assertRaises(ValueError):
            t.execute([sys.executable,'-c','import subprocess,time;subprocess.Popen(["sleep","30"]);time.sleep(30)'],timeout=.1,cwd='/tmp',env=t.ENV)
        self.assertLess(time.monotonic()-started,3)

    def test_failed_subprocess_does_not_expose_stderr(self):
        with self.assertRaisesRegex(ValueError,'PROCESS_FAILED'):
            t.execute([sys.executable,'-c','import sys;print("private",file=sys.stderr);sys.exit(2)'],timeout=2,cwd='/tmp',env=t.ENV)

    def test_java_uses_explicit_immutable_source_root_and_prebuilt_classpath(self):
        seen={}
        def execute(args,**kwargs):
            seen['args']=args
            Path(args[-1]).write_text('{}')
            Path(args[-2]).write_text('{}')
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(t,'execute',execute):
                t.JavaLab(directory,'/opt/prebuilt/*')({'proposal':None,'current_memory':b'{}'})
            self.assertIn('-Dserver.brain.sourceRoot='+directory+'/ai-browser/brain-core',seen['args'])
            self.assertIn('/opt/prebuilt/*',seen['args'])
            self.assertNotIn('gradle',' '.join(seen['args']).lower())

    def test_browser_runtime_uses_explicit_cache_and_four_workers(self):
        seen={}
        def execute(args,**kwargs):
            seen.update(args=args,kwargs=kwargs)
            Path(args[-2]).write_text('{}')
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(t,'execute',execute):
                t.BrowserLab(directory,playwright_module='/opt/playwright',playwright_browsers_path='/opt/browsers')()
        self.assertEqual(seen['kwargs']['env']['PLAYWRIGHT_BROWSERS_PATH'],'/opt/browsers')
        self.assertEqual(seen['kwargs']['env']['PLAYWRIGHT_MODULE'],'/opt/playwright')
        self.assertEqual(seen['args'][-3],'4')

if __name__=='__main__':unittest.main()
