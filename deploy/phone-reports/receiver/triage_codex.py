"""Tool-disabled Codex CLI boundary for typed advisory phone triage."""
import argparse,json,subprocess,tempfile
from pathlib import Path
from receiver import SITES,CAPABILITIES,PAGES,DIAGNOSTICS
from live_triage import FOCUS,NEXT_CHECK,CONFIDENCE,_model_result,run_once

DISABLED=('shell_tool','shell_snapshot','unified_exec','unified_exec_tty','multi_agent','multi_agent_v2','multi_agent_v2_dynamic_tools','agent_message_board','apps','plugins','plugin_sharing','remote_plugin','hooks','computer_use','browser_use','browser_use_external','browser_use_full_cdp_access','browser_annotation_api','in_app_browser','image_generation','view_image','skill_search','skill_mcp_dependency_install','workspace_dependencies','code_mode','code_mode_host','code_mode_only','code_mode_tool_search','stable_environment_tools','artifact','goals','memories','sleep_tool','daemon_auto_start','in_app_local_automation','standalone_web_search','tool_suggest')
SCHEMA={'type':'object','additionalProperties':False,'required':['focus','next_check','confidence','needs_human'],'properties':{'focus':{'type':'string','enum':sorted(FOCUS)},'next_check':{'type':'string','enum':sorted(NEXT_CHECK)},'confidence':{'type':'string','enum':sorted(CONFIDENCE)},'needs_human':{'type':'boolean'}}}
PROMPT='Classify this typed synthetic-or-consented failure aggregate as an advisory only. Return only the requested JSON enums. Do not use tools, inspect files, suggest commands, code, patches, releases, or app actions. Zero means explicitly observed zero; UNKNOWN means missing evidence. Human boundaries require human handling. Input:\n'

def validate_aggregate(value):
 keys={'schema_version','source','site','lesson','capability','page_type','diagnostic_code','closers','controls','repeat_count'}
 if type(value) is not dict or set(value)!=keys:raise ValueError('invalid aggregate')
 if type(value['schema_version']) is not int or value['schema_version']!=1 or value['source']!='PHONE_LIVE_TYPED':raise ValueError('invalid aggregate')
 if type(value['repeat_count']) is not int or not 3<=value['repeat_count']<=1000000:raise ValueError('invalid aggregate')
 for key,allowed in [('site',SITES),('lesson',CAPABILITIES),('capability',CAPABILITIES),('page_type',PAGES-{'AUTH_WALL','CHALLENGE'}),('diagnostic_code',DIAGNOSTICS-{'AUTH_WALL','CHALLENGE','INTERLOCK_BLOCKED'}),('closers',{'ZERO','POSITIVE','UNKNOWN'}),('controls',{'ZERO','POSITIVE','UNKNOWN'})]:
  if type(value[key]) is not str or value[key] not in allowed:raise ValueError('invalid aggregate')
 return dict(value)

class CodexModel:
 def __init__(self,timeout=120):
  if type(timeout) is not int or not 1<=timeout<=120:raise ValueError('invalid timeout')
  self.timeout=timeout
 def __call__(self,aggregate):
  aggregate=validate_aggregate(aggregate)
  try:
   with tempfile.TemporaryDirectory(prefix='aibrowser-triage-') as directory:
    root=Path(directory);schema=root/'schema.json';output=root/'last.json';schema.write_text(json.dumps(SCHEMA))
    args=['/usr/local/bin/codex','exec','--ignore-user-config','--ignore-rules','--ephemeral','--skip-git-repo-check','--sandbox','read-only','--cd',directory,'--color','never','--output-schema',str(schema),'--output-last-message',str(output),'-c','web_search="disabled"','--enable','skip_host_skill_discovery']
    for name in DISABLED:args.extend(['--disable',name])
    args.append('-')
    result=subprocess.run(args,input=PROMPT+json.dumps(aggregate,sort_keys=True,separators=(',',':')),text=True,cwd=directory,env={'HOME':'/root','CODEX_HOME':'/root/.codex','PATH':'/usr/local/bin:/usr/bin:/bin','LANG':'C.UTF-8'},stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=self.timeout,check=False)
    if result.returncode or output.is_symlink() or output.stat().st_size>2048:raise ValueError()
    return _model_result(output.read_text())
  except Exception:
   raise ValueError('MODEL_UNAVAILABLE') from None

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--live',type=Path,required=True);parser.add_argument('--state',type=Path,required=True);args=parser.parse_args()
 run_once(args.live,args.state,CodexModel())
if __name__=='__main__':main()
