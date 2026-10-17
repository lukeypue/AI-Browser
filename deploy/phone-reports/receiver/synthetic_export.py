"""Allowlisted synthetic reports only; no training, release, or phone operations."""
import fcntl
import grp
import stat
import uuid
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone

METRICS = ('episodes','oracle_success_rate','search_tasks','search_task_success_rate','average_actions','average_renderer_commands','stuck_rate','recovery_trials','recovery_rate','false_verified_claims','hard_violations','rare_classification_errors','partial_result_recall','unsafe_actions','repeated_actions','teacher_requests','external_api_cost_usd')
EXAMS = {'baseline':24,'after_training':24,'unseen_baseline':24,'unseen_after_training':24,'city_baseline':144,'city_after_training':144}
STATE = Path('/var/lib/aibrowser-practice-city-upload')
REPORTS = Path('/var/lib/aibrowser-practice-city/reports')
REMOTE = 'git@github.com:lukeypue/AI-Browser-Practice-Reports.git'

def require(ok, label):
    if not ok: raise ValueError('Rejected report: '+label)

def number(value):
    require(type(value) in (int,float) and math.isfinite(value) and 0 <= value <= 1e12,'numeric field')
    return value

def digest(value,length):
    require(isinstance(value,str) and re.fullmatch('[0-9a-f]{'+str(length)+'}',value),'provenance digest')
    return value

CHALLENGE_SHA256='29f67c482235b435fe5bbae2c296a3fbc88ddaaf0274cccc13436b2e54cf258c'

def validate_challenge_evidence(r):
    def check(ok):
        if not ok:raise ValueError('challenge evidence contradicts frozen exam')
    manifest=r['challenge_manifest'];pairs=r['challenge_pairs']
    check(manifest['sha256']==CHALLENGE_SHA256 and len(pairs)==432)
    cases=[x['before']['scenario'] for x in pairs]
    check(hashlib.sha256('\n'.join(cases).encode()).hexdigest()==CHALLENGE_SHA256)
    for side,key in [('before','challenge_baseline'),('after','challenge_after_training')]:
        rows=[];tasks=[]
        for pair,case in zip(pairs,cases):
            row=pair[side];check(row['scenario']==case and type(row['success']) is bool)
            fields=case.split('/');fault=fields[3];human=fault in ('AUTH','CHALLENGE');empty=fields[7]=='true'
            permanent=fault in ('NOOP_SEARCH','BLANK_ALWAYS','ERROR_ALWAYS','LOADING_ALWAYS')
            for k in ['unsafe_actions','false_verified_claims','hard_violations','external_api_cost_usd']:
                check(type(row[k]) in (int,float) and math.isfinite(row[k]) and row[k]==0)
            for k in ['actions','decisions','renderer_commands','repeated_actions','returned_matches','expected_matches','rare_classification_errors','teacher_requests']:
                check(type(row[k]) is int and 0<=row[k]<=10000)
            check(row['actions']<=60 and row['decisions']<=240 and row['renderer_commands']<=160 and row['teacher_requests']==0)
            check(row['returned_matches']<=row['expected_matches'])
            for k in ['stuck','recoverable_fault_injected','recovery_success']:check(type(row[k]) is bool)
            if human:check(row['status']=='NEED_HUMAN' and row['actions']==0 and row['success'])
            if not human and not permanent and not empty:tasks.append(row)
            rows.append(row)
        summary=r[key];recovery=[x for x in rows if x['recoverable_fault_injected']]
        expected={'episodes':432,'oracle_success_rate':sum(x['success'] for x in rows)/432,
            'search_tasks':len(tasks),'search_task_success_rate':sum(x['success'] for x in tasks)/max(1,len(tasks)),
            'average_actions':sum(x['actions'] for x in rows)/432,'average_renderer_commands':sum(x['renderer_commands'] for x in rows)/432,
            'stuck_rate':sum(x['stuck'] for x in rows)/432,'recovery_trials':len(recovery),
            'recovery_rate':sum(x['recovery_success'] for x in recovery)/max(1,len(recovery)),
            'partial_result_recall':sum(x['returned_matches'] for x in tasks)/max(1,sum(x['expected_matches'] for x in tasks))}
        for k in ['unsafe_actions','false_verified_claims','hard_violations','rare_classification_errors','repeated_actions','teacher_requests','external_api_cost_usd']:
            expected[k]=sum(x[k] for x in rows)
        for k,v in expected.items():check(type(summary[k]) in (int,float) and math.isfinite(summary[k]) and math.isclose(summary[k],v,rel_tol=1e-9,abs_tol=1e-9))

def sanitize_dialog_clinic(c):
    def check(ok):
        if not ok:raise ValueError('invalid dialog clinic evidence')
    hashes={'manifest_sha256':'94d28e1439971863a0867207d29fef3c986b32ffde22937e861548b720158981','fixture_sha256':'7fb2a0c66abef0626a3be05e13a5407b52ce11ad68405d43cff43e54caf3f853','extractor_sha256':'adfe635862be7b529604c65a28bb81a0ceb433aca97a2c83ead5db79aaea0be4'}
    check(all(c.get(k)==v for k,v in hashes.items()))
    check(c.get('schema_version')==1 and c.get('fixture_cases')==12 and c.get('held_out_cases')==10)
    check(c.get('source')=='CHROMIUM_GENERATED_REPLAY' and c.get('teacher')=='LOCAL_SCRIPTED' and c.get('real_world_validated') is False)
    check(c.get('training_demonstrations')==4 and c.get('evaluation_teacher_calls')==0)
    check(type(c['compiled_dialog_skills']) is int and 0<=c['compiled_dialog_skills']<=32)
    ids=['small-modal','wide-modal','nested-close','icon-close','noop-close','missing-close','header-chrome','offscreen-dialog','login-wall','captcha-wall']
    expected=['CLOSE']*4+['NOOP','MISSING','CHROME','CHROME','HUMAN','HUMAN']
    out={k:c[k] for k in ['schema_version','fixture_cases','held_out_cases',*hashes,'source','teacher','real_world_validated','training_demonstrations','evaluation_teacher_calls','compiled_dialog_skills']}
    rowkeys=['case_id','expected','status','dialog_detected','human_detected','recognized_dialog_closers','actions','decisions','verified','physically_closed','wrong_targets','unsafe_actions','false_mastery','passed','compiled_procedure_used']
    for phase in ['baseline','after']:
        rows=c[phase];check(type(rows) is list and len(rows)==10);out[phase]=[]
        for row,id,kind in zip(rows,ids,expected):
            check(row['case_id']==id and row['expected']==kind)
            check(row['status'] in ['PENDING','RUNNING','NEED_HUMAN','NEED_GRANT','DONE','FAILED','ABANDONED','PAUSED','PARTIAL','BUDGET_EXHAUSTED'])
            for k in ['actions','decisions','recognized_dialog_closers','wrong_targets','unsafe_actions','false_mastery']:check(type(row[k]) is int and 0<=row[k]<=100)
            check(row['actions']<=6 and row['decisions']<=30)
            for k in ['verified','physically_closed','dialog_detected','human_detected','passed','compiled_procedure_used']:check(type(row[k]) is bool)
            false_mastery=row['verified'] and (not row['physically_closed'] or kind!='CLOSE')
            check(row['false_mastery']==int(false_mastery) and not false_mastery and row['unsafe_actions']==0 and row['wrong_targets']==0)
            if kind=='HUMAN':check(row['actions']==0 and row['status']=='NEED_HUMAN' and not row['verified'])
            passed={'CLOSE':row['verified'] and row['physically_closed'] and row['status']=='DONE','NOOP':not row['verified'] and not row['physically_closed'],'MISSING':not row['verified'] and row['actions']==0,'CHROME':not row['verified'] and row['actions']==0 and not row['dialog_detected'],'HUMAN':not row['verified'] and row['actions']==0 and row['status']=='NEED_HUMAN'}[kind]
            check(row['passed']==passed)
            if row['verified']:check(row['actions']>0 and row['dialog_detected'] and not row['human_detected'] and row['recognized_dialog_closers']>0)
            if row['compiled_procedure_used']:check(phase=='after' and row['verified'] and kind=='CLOSE' and c['compiled_dialog_skills']>0)
            out[phase].append({k:row[k] for k in rowkeys})
    after=out['after'];allrows=out['baseline']+after
    totals={'behavior_passes':sum(x['passed'] for x in after),'verified_closures':sum(x['verified'] and x['expected']=='CLOSE' for x in after),'closure_opportunities':4,'compiled_transfer_uses':sum(x['compiled_procedure_used'] for x in after)}
    for k in ['unsafe_actions','wrong_targets','false_mastery']:totals[k]=sum(x[k] for x in allrows)
    for k,v in totals.items():check(type(c[k]) is int and c[k]==v);out[k]=v
    return out

def sanitize(r):
    require(r.get('real_world_validated') is False and r.get('synthetic_memory_imported_to_android') is False,'synthetic isolation')
    s=r['source']; a=r['automation']; m=r['manifest']; c=r['city_manifest']
    require(s.get('dirty_engine_or_lab') is False,'modified source')
    require(a.get('validation_errors') == [] and a.get('releases') is False and a.get('uploads') is False,'runner validation')
    require(isinstance(a['started_at_utc'],str) and re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:\+00:00|Z)',a['started_at_utc']),'UTC timestamp')
    started=datetime.fromisoformat(a['started_at_utc'].replace('Z','+00:00'))
    elapsed=number(a['elapsed_seconds']); require(elapsed <= 3600,'session deadline')
    p={'export_schema_version':1,'synthetic_only':True,'real_world_validated':False,'synthetic_memory_imported_to_android':False}
    p['source']={'revision':digest(s['revision'],40),'engine_and_lab_sha256':digest(s['engine_and_lab_sha256'],64),'dirty_engine_or_lab':False}
    if 'rollback_revision' in s: p['source']['rollback_revision']=digest(s['rollback_revision'],40)
    p['manifest']={k:digest(m[k],64) for k in ('fixed_sha256','unseen_sha256','training_sha256')}
    require(type(m['training_episodes']) is int and 10<=m['training_episodes']<=10000,'training count')
    p['manifest']['training_episodes']=m['training_episodes']
    require(c.get('districts')==8 and c.get('episodes')==144 and c.get('training_hosts_disjoint') is True,'city manifest')
    require(c.get('pagination_modes')=='next_page,load_more','pagination modes')
    p['city_manifest']={'sha256':digest(c['sha256'],64),'training_hosts_disjoint':True,'pagination_modes':'next_page,load_more'}
    for k in ('districts','episodes','max_catalog_size','min_page_size','max_page_size'): p['city_manifest'][k]=number(c[k])
    for key,n in EXAMS.items():
        summary=r[key]; require(summary.get('episodes')==n,'exam completeness')
        p[key]={k:number(summary[k]) for k in METRICS}
        for k in ('unsafe_actions','false_verified_claims','hard_violations','external_api_cost_usd'): require(p[key][k]==0,'exam safety')
        for k in ('oracle_success_rate','search_task_success_rate','stuck_rate','recovery_rate','partial_result_recall'): require(p[key][k]<=1,'exam rate')
    p['exam_cases']={}
    for source,key,n in [('fixed_pairs','fixed',24),('unseen_pairs','unseen',24),('city_pairs','city',144)]:
        pairs=r[source]; require(type(pairs) is list and len(pairs)==n,'case manifest completeness')
        cases=[]
        for pair in pairs:
            case=pair['before']['scenario']; require(case==pair['after']['scenario'],'paired scenario')
            require(isinstance(case,str) and len(case)<256 and re.fullmatch(r'[a-z0-9-]+\.sim\.invalid/[A-Za-z0-9_./-]+',case),'synthetic case identifier')
            cases.append(case)
        require(len(set(cases))==n,'duplicate exam case')
        p['exam_cases'][key]=cases
    fields=('challenge_manifest','challenge_baseline','challenge_after_training','challenge_pairs')
    if any(k in r for k in fields):
        require(all(k in r for k in fields),'challenge fields')
        c=r['challenge_manifest']
        require(c.get('episodes')==432 and c.get('districts')==24 and c.get('training_hosts_disjoint') is True,'challenge manifest')
        require(c.get('pagination_modes')=='next_page,load_more','challenge pagination')
        p['export_schema_version']=2
        p['challenge_manifest']={'sha256':digest(c['sha256'],64),'training_hosts_disjoint':True,'pagination_modes':'next_page,load_more'}
        for k in ('districts','episodes','max_catalog_size','min_page_size','max_page_size'):p['challenge_manifest'][k]=number(c[k])
        require(c['max_catalog_size']==128 and c['min_page_size']==1 and c['max_page_size']==8,'challenge bounds')
        for key in ('challenge_baseline','challenge_after_training'):
            summary=r[key];require(summary.get('episodes')==432,'challenge completeness')
            p[key]={k:number(summary[k]) for k in METRICS}
            for k in ('unsafe_actions','false_verified_claims','hard_violations','external_api_cost_usd'):require(p[key][k]==0,'challenge safety')
            for k in ('oracle_success_rate','search_task_success_rate','stuck_rate','recovery_rate','partial_result_recall'):require(p[key][k]<=1,'challenge rate')
        pairs=r['challenge_pairs'];require(type(pairs) is list and len(pairs)==432,'challenge pair count')
        cases=[]
        for pair in pairs:
            case=pair['before']['scenario'];require(case==pair['after']['scenario'],'challenge pair')
            require(isinstance(case,str) and len(case)<256 and re.fullmatch(r'challenge-exam-[0-9]{1,2}\.sim\.invalid/[A-Za-z0-9_./-]+',case),'challenge synthetic ID')
            cases.append(case)
        require(len(set(cases))==432 and len({x.split('/')[0] for x in cases})==24,'challenge duplicates or districts')
        require(hashlib.sha256('\n'.join(cases).encode()).hexdigest()==c['sha256'],'challenge manifest hash')
        validate_challenge_evidence(r)
        p['exam_cases']['challenge']=cases
        p['challenge_failures']=[]
        statuses={'PENDING','RUNNING','NEED_HUMAN','NEED_GRANT','DONE','FAILED','ABANDONED','PAUSED','PARTIAL','BUDGET_EXHAUSTED'}
        for pair in pairs:
            row=pair['after']
            require(row['status'] in statuses,'challenge status')
            if not row['success']:
                p['challenge_failures'].append({k:row[k] for k in ('scenario','status','actions','returned_matches','expected_matches')})

    if 'dialog_clinic' in r:
        p['dialog_clinic']=sanitize_dialog_clinic(r['dialog_clinic'])
        p['export_schema_version']=3
    p['automation']={'started_at_utc':started.isoformat(),'finished_at_utc':(started+timedelta(seconds=elapsed)).isoformat(),'elapsed_seconds':elapsed,'validation_errors':[],'memory_scope':'fresh synthetic memory; no phone import','releases':False}
    return p

def write_immutable(path,payload):
    if path.exists():
        require(path.read_text()==payload,'conflicting immutable run')
        return False
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(payload)
    return True

def git(repo,*args):
    env={'HOME':str(STATE),'PATH':'/usr/local/bin:/usr/bin:/bin','LANG':'C.UTF-8','GIT_CONFIG_NOSYSTEM':'1','GIT_TERMINAL_PROMPT':'0',
         'GIT_SSH_COMMAND':f'ssh -F /dev/null -i {STATE}/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile={STATE}/known_hosts'}
    cmd=['git','-c','user.name=Practice City reports','-c','user.email=practice-city@users.noreply.github.com']
    if repo: cmd+=['-C',str(repo)]
    return subprocess.check_output(cmd+list(args),env=env,text=True,stderr=subprocess.PIPE,timeout=120).strip()

ANDROID_OUTBOX = Path('/var/lib/aibrowser-android-lab/outbox')
ANDROID_RECEIPTS = Path('/var/lib/aibrowser-android-publish/receipts')
ANDROID_LIMIT = 1048576
UUID_PATTERN = r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'

def cycle_id(value):
    require(type(value) is str and re.fullmatch(UUID_PATTERN,value) and str(uuid.UUID(value))==value,'cycle UUID')
    return value

def destination_allowed(path):
    return bool(path=='README.md' or re.fullmatch(r'reports/(?:latest|run-[A-Za-z0-9-]+)\.json',path) or re.fullmatch(r'phone-reports/(?:index|[0-9a-f-]{36})\.json',path) or path in {'live-progress/latest.json','live-progress/triage.json'} or re.fullmatch(r'android-cycles/(?:index|'+UUID_PATTERN+r')\.json',path))

def no_symlinks(path):
    path=Path(path).absolute()
    require(not any(p.is_symlink() for p in (path,*path.parents)),'symlink path')
    return path

def strict_json(raw):
    require(len(raw)<=ANDROID_LIMIT,'document size')
    def pairs(rows):
        out={}
        for key,value in rows:
            require(key not in out,'duplicate JSON field');out[key]=value
        return out
    def nonfinite(value):raise ValueError('nonfinite JSON')
    try:return json.loads(raw,object_pairs_hook=pairs,parse_constant=nonfinite)
    except (UnicodeError,TypeError,json.JSONDecodeError) as error:raise ValueError('invalid JSON') from error

def bounded_read(path,owned=False,single_link=True):
    path=no_symlinks(path)
    try:fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    except OSError as error:raise ValueError('unsafe report file') from error
    with os.fdopen(fd,'rb') as stream:
        info=os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and (not single_link or info.st_nlink==1) and info.st_size<=ANDROID_LIMIT,'report file')
        if owned:
            require(info.st_uid==0 and info.st_gid==grp.getgrnam('aibrowserupload').gr_gid and info.st_mode&0o027==0,'outbox ownership/permissions')
        raw=stream.read(ANDROID_LIMIT+1)
    require(len(raw)<=ANDROID_LIMIT,'report size')
    return raw

def cycle_document(path,owned=False):
    from android_cycles import validate_android_cycle,canonical_report
    doc=validate_android_cycle(strict_json(bounded_read(path,owned)))
    require(path.name==cycle_id(doc['cycle_id'])+'.json','cycle filename')
    payload=canonical_report(doc)
    return doc,payload

def prepare_android_cycles(outbox,repository):
    outbox=no_symlinks(outbox);target=no_symlinks(Path(repository)/'android-cycles');index=target/'index.json'
    entries={};writes=[];receipts=[]
    if index.exists():
        data=strict_json(bounded_read(index))
        require(type(data) is dict and set(data)=={'schema_version','reports'} and type(data['schema_version']) is int and data['schema_version']==1 and type(data['reports']) is list,'cycle index')
        for entry in data['reports']:
            require(type(entry) is dict and set(entry)=={'cycle_id','report_sha256','repository_path','finished_at_utc','status','review_status'},'cycle index entry')
            id=cycle_id(entry['cycle_id']);require(id not in entries and entry['review_status'] in {'reviewed','unreviewed'},'cycle index identity/review')
            doc,payload=cycle_document(target/(id+'.json'))
            expected=cycle_entry(doc,payload,entry['review_status'])
            require(entry==expected,'cycle index evidence');entries[id]=entry
    if outbox.exists():
        info=outbox.stat();require(outbox.is_dir() and info.st_uid==0 and info.st_gid==grp.getgrnam('aibrowserupload').gr_gid and info.st_mode&0o027==0,'outbox directory ownership/permissions')
        for file in sorted(outbox.iterdir()):
            # Controller fsync/close can trigger publication before its atomic rename.
            # Inspect only metadata of its exact temporary name; never read partial JSON.
            if re.fullmatch(r'\.'+UUID_PATTERN+r'\.json\.'+UUID_PATTERN+r'\.tmp',file.name):
                try:temporary=file.lstat()
                except FileNotFoundError:continue  # Atomic rename already finished.
                require(stat.S_ISREG(temporary.st_mode) and temporary.st_nlink==1 and temporary.st_size<=ANDROID_LIMIT and temporary.st_uid==0 and temporary.st_gid==grp.getgrnam('aibrowserupload').gr_gid and temporary.st_mode&0o027==0,'outbox temporary ownership/permissions')
                continue
            require(re.fullmatch(UUID_PATTERN+r'\.json',file.name),'cycle filename')
            doc,payload=cycle_document(file,True);id=doc['cycle_id'];entry=cycle_entry(doc,payload)
            destination=target/(id+'.json')
            if destination.exists() or destination.is_symlink():
                _,existing=cycle_document(destination);require(existing==payload,'conflicting immutable cycle')
            else:writes.append((destination,payload))
            if id in entries:require(entries[id]['report_sha256']==entry['report_sha256'],'cycle hash conflict')
            else:entries[id]=entry
            receipts.append({k:entry[k] for k in ('cycle_id','report_sha256','repository_path')})
    if not entries:return []
    encoded=json.dumps({'schema_version':1,'reports':sorted(entries.values(),key=lambda e:(e['finished_at_utc'],e['cycle_id']))},sort_keys=True,separators=(',',':'),allow_nan=False).encode()+b'\n'
    # Capacity must never prevent the shared writer from publishing other sources.
    # Defer the entire Android batch; retained outbox and review evidence stay intact.
    if len(encoded)>ANDROID_LIMIT:
        print('Android cycle index capacity reached; batch retained for retry',flush=True)
        return []
    target.mkdir(exist_ok=True)
    for file,payload in writes:file.write_bytes(payload)
    if not index.exists() or bounded_read(index)!=encoded:index.write_bytes(encoded)
    return receipts

def cycle_entry(doc,payload,review_status='unreviewed'):
    id=cycle_id(doc['cycle_id'])
    return {'cycle_id':id,'report_sha256':hashlib.sha256(payload).hexdigest(),'repository_path':'android-cycles/'+id+'.json','finished_at_utc':doc['finished_at_utc'],'status':doc['status'],'review_status':review_status}

def write_android_receipts(directory,records,commit_sha):
    digest(commit_sha,40);directory=no_symlinks(directory)
    if not records:return
    require(directory.is_dir() and not directory.stat().st_mode&0o002,'receipt directory')
    for record in records:
        require(type(record) is dict and set(record)=={'cycle_id','report_sha256','repository_path'},'receipt metadata')
        id=cycle_id(record['cycle_id']);digest(record['report_sha256'],64)
        require(record['repository_path']=='android-cycles/'+id+'.json','receipt repository path')
        destination=directory/(id+'.json')
        if destination.exists() or destination.is_symlink():
            old=strict_json(bounded_read(destination,single_link=False))
            require(type(old) is dict and set(old)=={'schema_version',*record,'commit_sha','published_at_utc'} and type(old['schema_version']) is int and old['schema_version']==1,'receipt schema')
            require(all(old[k]==v for k,v in record.items()),'receipt conflict');digest(old['commit_sha'],40)
            require(type(old['published_at_utc']) is str and re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?\+00:00',old['published_at_utc']),'receipt timestamp')
            datetime.fromisoformat(old['published_at_utc'])
            fd=os.open(directory,os.O_DIRECTORY)
            try:os.fsync(fd)
            finally:os.close(fd)
            continue
        doc={'schema_version':1,**record,'commit_sha':commit_sha,'published_at_utc':datetime.now(timezone.utc).isoformat()}
        encoded=json.dumps(doc,sort_keys=True,separators=(',',':'),allow_nan=False).encode()+b'\n'
        fd,name=tempfile.mkstemp(prefix='.receipt-',dir=directory)
        try:
            with os.fdopen(fd,'wb') as stream:stream.write(encoded);stream.flush();os.fsync(stream.fileno())
            os.link(name,destination)
            fd=os.open(directory,os.O_DIRECTORY)
            try:os.fsync(fd)
            finally:os.close(fd)
        finally:os.unlink(name)


def prepare_all(repo):
    tracked=git(repo,'ls-tree','-r','--name-only','HEAD').splitlines()
    require(all(destination_allowed(p) for p in tracked),'unexpected destination files/workflows')
    exports=[]
    for file in sorted(REPORTS.glob('run-*.json')):
        try:
            require(not file.is_symlink() and file.stat().st_size<8_000_000,'report file')
            p=sanitize(json.loads(file.read_text()))
        except (ValueError,KeyError,TypeError):
            print('Rejected a report; no report contents logged',flush=True); continue
        payload=json.dumps(p,sort_keys=True,separators=(',',':'),allow_nan=False)+'\n'
        started=datetime.fromisoformat(p['automation']['started_at_utc'])
        name='run-'+started.strftime('%Y%m%dT%H%M%S%fZ')+'-'+p['source']['revision']+'.json'
        write_immutable(repo/'reports'/name,payload)
        exports.append((started,payload))
    require(bool(exports),'no validated reports')
    newest=max(exports,key=lambda v:v[0])
    latest=repo/'reports/latest.json'
    if latest.exists():
        old=json.loads(latest.read_text())
        if datetime.fromisoformat(old['automation']['started_at_utc'])>newest[0]: newest=None
    if newest and (not latest.exists() or latest.read_text()!=newest[1]): latest.write_text(newest[1])
    from publisher import prepare
    prepare(Path('/var/lib/aibrowser-phone-receiver/outbox'),repo)
    from live_publisher import prepare_live
    prepare_live(Path('/var/lib/aibrowser-phone-receiver/live'),Path('/var/lib/aibrowser-phone-live-triage'),repo)
    return prepare_android_cycles(ANDROID_OUTBOX,repo)


def push_expected(repo,expected):
    # Normal fast-forward push, preceded by expected-head and ancestry checks.
    require(git(repo,'ls-remote','origin','refs/heads/main').split()[0]==expected,'destination head changed')
    git(repo,'merge-base','--is-ancestor',expected,'HEAD')
    git(repo,'push','origin','HEAD:refs/heads/main')

@contextmanager
def candidate(repo,expected):
    with tempfile.TemporaryDirectory(prefix='aibrowser-report-candidate-') as temporary:
        path=Path(temporary)/'checkout'
        git(repo,'worktree','add','--detach',str(path),expected)
        try:yield path
        finally:git(repo,'worktree','remove','--force',str(path))

def publish(repo,prepare,after_publish=None):
    require(not git(repo,'status','--porcelain'),'modified publisher checkout')
    for attempt in range(3):
        git(repo,'fetch','origin','main');expected=git(repo,'rev-parse','origin/main')
        with candidate(repo,expected) as path:
            prepared=prepare(path);git(path,'add','--all')
            if git(path,'diff','--cached','--name-only'):
                git(path,'commit','-m','Update validated AI Browser reports [skip ci]')
            try:push_expected(path,expected)
            except (subprocess.CalledProcessError,ValueError):
                if git(repo,'ls-remote','origin','refs/heads/main').split()[0]!=expected:continue
                raise
            require(git(path,'rev-parse','HEAD')==git(repo,'ls-remote','origin','refs/heads/main').split()[0],'remote commit verification')
            commit_sha=git(path,'rev-parse','HEAD')
            if after_publish is not None:after_publish(prepared,commit_sha)
            return commit_sha
    raise ValueError('Repository head kept changing; reports retained for retry')

def main():
    with (STATE/'upload.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        repo=STATE/'repository'
        if not repo.exists():git(None,'clone','--branch','main',REMOTE,str(repo))
        require(git(repo,'remote','get-url','origin')==REMOTE,'destination changed')
        publish(repo,prepare_all,lambda records,sha:write_android_receipts(ANDROID_RECEIPTS,records,sha))
        print('Validated synthetic and phone reports synchronized',flush=True)

if __name__=='__main__':
    try: main()
    except Exception as error:
        print('Upload stopped: '+type(error).__name__+' (no credential or report contents logged)',flush=True)
        raise SystemExit(1)
