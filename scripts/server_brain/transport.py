"""Fixed, bounded subprocess transports. No shell or model-generated commands."""
import json
import os
import signal
import subprocess
import tempfile
from pathlib import Path
from .controller import LESSON, MAX_BYTES, canonical, validate_proposal,validate_lesson

ENV={'HOME':'/root','CODEX_HOME':'/root/.codex','PATH':'/usr/local/bin:/usr/bin:/bin','LANG':'C.UTF-8'}
DISABLED=('shell_tool','shell_snapshot','unified_exec','unified_exec_tty','multi_agent','multi_agent_v2','multi_agent_v2_dynamic_tools','agent_message_board','apps','plugins','plugin_sharing','remote_plugin','hooks','computer_use','browser_use','browser_use_external','browser_use_full_cdp_access','browser_annotation_api','in_app_browser','image_generation','view_image','skill_search','skill_mcp_dependency_install','workspace_dependencies','code_mode','code_mode_host','code_mode_only','code_mode_tool_search','stable_environment_tools','artifact','goals','memories','sleep_tool','daemon_auto_start','in_app_local_automation','standalone_web_search','tool_suggest')
SCHEMA={'type':'object','additionalProperties':False,'required':['schema_version','capability','steps'],'properties':{'schema_version':{'type':'integer','enum':[1]},'capability':{'type':'string','enum':['search']},'steps':{'type':'array','minItems':1,'maxItems':3,'items':{'anyOf':[{'type':'object','additionalProperties':False,'required':['kind','role','submit'],'properties':{'kind':{'type':'string','enum':['TYPE']},'role':{'type':'string','enum':['SEARCH_BOX']},'submit':{'type':'boolean','enum':[False]}}},{'type':'object','additionalProperties':False,'required':['kind','role','submit'],'properties':{'kind':{'type':'string','enum':['CLICK']},'role':{'type':'string','enum':['SUBMIT']},'submit':{'type':'boolean','enum':[False]}}}]}}}}
PROMPT='Infer a reusable safe search procedure from the typed goal and affordances. Return only the requested strict JSON DSL. Query values bind locally. Consider any typed prior outcome and avoid previously unsuccessful proposal hashes. Do not include text, selectors, URLs, code, commands, commentary or fields outside the schema. Do not use tools or inspect files. Synthetic lesson:\n'

def execute(args, *, timeout, cwd, env, input=None, output=None):
    # A new session makes timeout cleanup include Java/Chromium/CLI descendants.
    with subprocess.Popen(args,stdin=subprocess.PIPE if input is not None else subprocess.DEVNULL,stdout=output if output is not None else subprocess.DEVNULL,stderr=subprocess.DEVNULL,cwd=cwd,env=env,start_new_session=True) as proc:
        try: proc.communicate(input=input,timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid,signal.SIGKILL);proc.communicate()
            raise ValueError('PROCESS_TIMEOUT') from None
        if proc.returncode: raise ValueError('PROCESS_FAILED')

def bounded(path,maximum=MAX_BYTES):
    if path.is_symlink() or not path.is_file() or path.stat().st_size>maximum: raise ValueError('OUTPUT_LIMIT')
    return path.read_bytes()

def strict_json(raw):
    def pairs(rows):
        value={}
        for k,v in rows:
            if k in value: raise ValueError('DUPLICATE_KEY')
            value[k]=v
        return value
    return json.loads(raw,object_pairs_hook=pairs,parse_constant=lambda _: (_ for _ in ()).throw(ValueError('INVALID_NUMBER')))

class CodexModel:
    def __init__(self,timeout=120):
        if type(timeout) is not int or not 1<=timeout<=120: raise ValueError('INVALID_TIMEOUT')
        self.timeout=timeout
    def __call__(self,lesson):
        lesson=validate_lesson(lesson)
        with tempfile.TemporaryDirectory(prefix='server-brain-model-') as directory:
            root=Path(directory);schema=root/'schema.json';output=root/'last.json';schema.write_bytes(canonical(SCHEMA))
            args=['/usr/local/bin/codex','exec','--ignore-user-config','--ignore-rules','--ephemeral','--skip-git-repo-check','--sandbox','read-only','--cd',directory,'--color','never','--output-schema',str(schema),'--output-last-message',str(output),'-c','web_search="disabled"','--enable','skip_host_skill_discovery']
            for name in DISABLED: args.extend(['--disable',name])
            args.append('-')
            execute(args,timeout=self.timeout,cwd=directory,env=ENV,input=(PROMPT+canonical(lesson).decode()).encode())
            return validate_proposal(strict_json(bounded(output,2048)))

class JavaLab:
    def __init__(self,source_root,classpath,java='/usr/bin/java',timeout=90):
        self.source_root=Path(source_root).resolve();self.classpath=classpath;self.java=java;self.timeout=timeout
    def __call__(self,request):
        with tempfile.TemporaryDirectory(prefix='server-brain-lab-') as directory:
            root=Path(directory);current=root/'current.json';candidate=root/'candidate.json';report=root/'report.json';proposal=root/'proposal.json'
            current.write_bytes(request['current_memory'])
            if request['proposal'] is not None: proposal.write_bytes(canonical(validate_proposal(request['proposal'])))
            execute([self.java,'-Xmx512m','-Dserver.brain.sourceRoot='+str(self.source_root/'ai-browser/brain-core'),'-cp',self.classpath,'com.appgate.brain.lab.ServerBrainMainKt',str(proposal) if request['proposal'] else '-',str(current),str(candidate),str(report)],timeout=self.timeout,cwd=str(self.source_root),env=ENV)
            return {'report':strict_json(bounded(report)),'candidate_memory':bounded(candidate)}

class BrowserLab:
    def __init__(self,source_root,node='/usr/bin/node',playwright_module=None,timeout=90,playwright_browsers_path=None):
        self.source_root=Path(source_root).resolve();self.node=node;self.playwright_module=playwright_module;self.timeout=timeout;self.playwright_browsers_path=playwright_browsers_path
    def __call__(self):
        with tempfile.TemporaryDirectory(prefix='server-brain-browser-') as directory:
            report=Path(directory)/'browser.json';env=dict(ENV)
            if self.playwright_module: env['PLAYWRIGHT_MODULE']=self.playwright_module
            if self.playwright_browsers_path: env['PLAYWRIGHT_BROWSERS_PATH']=self.playwright_browsers_path
            execute([self.node,str(self.source_root/'scripts/parallel-browser-lab.mjs'),'4',str(report),'1'],timeout=self.timeout,cwd=str(self.source_root),env=env)
            return strict_json(bounded(report))
