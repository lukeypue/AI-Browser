"""One-writer, durable controller for synthetic search procedure training."""
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path

MAX_BYTES = 10 * 1024 * 1024
LESSON = {'schema_version':1,'source':'SYNTHETIC_SEARCH_LESSON','capability':'search','profiles':['KSL_CLASSIFIEDS','KSL_CARS','FACEBOOK_MARKETPLACE','OFFERUP'],'goal':'VERIFIED_SEARCH_RESULTS_FOR_LOCAL_TASK','query_binding':'LOCAL_SYNTHETIC_TASK','allowed_roles':['SEARCH_BOX','SUBMIT'],'affordances':[{'role':'SEARCH_BOX','editable':True,'activation':'EDIT_QUERY'},{'role':'SUBMIT','editable':False,'activation':'REQUEST_SEARCH'}],'human_boundaries':'NO_AUTH_CHALLENGE_PAYMENT_ACCOUNT_ACTIONS','prior_outcome':None,'failed_proposal_sha256':[]}

def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode('utf-8')

def sha(data):
    return hashlib.sha256(data).hexdigest()

def exact(value, keys):
    if type(value) is not dict or set(value)!=set(keys): raise ValueError('INVALID_SCHEMA')

def integer(value):
    if type(value) is not int or value<0: raise ValueError('INVALID_COUNTER')
    return value

def validate_proposal(value):
    exact(value,{'schema_version','capability','steps'})
    if type(value['schema_version']) is not int or value['schema_version']!=1 or value['capability']!='search': raise ValueError('INVALID_PROPOSAL')
    if type(value['steps']) is not list or not 1<=len(value['steps'])<=3: raise ValueError('INVALID_PROPOSAL')
    for step in value['steps']:
        exact(step,{'kind','role','submit'})
        if (step['kind'],step['role']) not in [('TYPE','SEARCH_BOX'),('CLICK','SUBMIT')] or step['submit'] is not False: raise ValueError('INVALID_PROPOSAL')
    return json.loads(canonical(value))

def validate_lesson(value):
    exact(value,LESSON)
    if any(value[k]!=v for k,v in LESSON.items() if k not in ('prior_outcome','failed_proposal_sha256')): raise ValueError('INVALID_LESSON')
    hashes=value['failed_proposal_sha256']
    if type(hashes) is not list or len(hashes)>=24 or len(set(hashes))!=len(hashes): raise ValueError('INVALID_FEEDBACK')
    def hash_value(x):
        if type(x) is not str or len(x)!=64 or any(c not in '0123456789abcdef' for c in x): raise ValueError('INVALID_FEEDBACK')
    for item in hashes: hash_value(item)
    prior=value['prior_outcome']
    if prior is not None:
        exact(prior,{'outcome','proposal_sha256','report_sha256','memory_sha256','totals'})
        if prior['outcome'] not in ('NO_GAIN','DUPLICATE_PROPOSAL'): raise ValueError('INVALID_FEEDBACK')
        for key in ('proposal_sha256','report_sha256','memory_sha256'): hash_value(prior[key])
        exact(prior['totals'],{'cold','current','candidate'})
        for row in prior['totals'].values():
            exact(row,{'successes','actions','compiled_reuse','unsafe_actions','false_verified'})
            for count in row.values(): integer(count)
    return json.loads(canonical(value))

def memory(raw):
    if type(raw) is not bytes or len(raw)>MAX_BYTES//2: raise ValueError('INVALID_MEMORY')
    value=json.loads(raw)
    if type(value) is not dict or any(type(k) is not str or type(v) is not str for k,v in value.items()): raise ValueError('INVALID_MEMORY')
    if canonical(value)!=raw: raise ValueError('NONCANONICAL_MEMORY')
    return value

def atomic(path, raw):
    if path.is_symlink(): raise ValueError('SYMLINK_STATE')
    temp=path.with_name(path.name+'.tmp')
    fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_TRUNC|os.O_NOFOLLOW,0o600)
    try:
        with os.fdopen(fd,'wb') as stream:
            stream.write(raw);stream.flush();os.fsync(stream.fileno())
        os.replace(temp,path)
        directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
        try: os.fsync(directory)
        finally: os.close(directory)
    finally:
        if temp.exists(): temp.unlink()

class State:
    def __init__(self, root, source_sha256, manifest_sha256):
        self.root=Path(root)
        if self.root.is_symlink(): raise ValueError('SYMLINK_STATE')
        self.root.mkdir(mode=0o700,parents=True,exist_ok=True)
        os.chmod(self.root,0o700)
        for value in (source_sha256,manifest_sha256):
            if type(value) is not str or len(value)!=64 or any(x not in '0123456789abcdef' for x in value): raise ValueError('INVALID_PROVENANCE')
        self.source_sha256=source_sha256;self.manifest_sha256=manifest_sha256
        self.path=self.root/'state.json';self.previous_path=self.root/'previous.json';self.lock_path=self.root/'cycle.lock'

    def fresh(self):
        return {'schema_version':1,'source':'PRIVATE_SYNTHETIC_SERVER_BRAIN','champion':None,'reservations':[],'reports':[],'proposals':[],'cached_proposal':None,'last_model_lesson':None,'last_model_at':None,'failed_proposal_sha256':[],'prior_outcome':None}

    def validate(self,data):
        exact(data,self.fresh())
        if data['schema_version']!=1 or data['source']!='PRIVATE_SYNTHETIC_SERVER_BRAIN': raise ValueError('INVALID_STATE')
        for value in data['reservations']:
            if type(value) not in (int,float) or not math.isfinite(value) or value<0: raise ValueError('INVALID_STATE')
        if data['reservations']!=sorted(data['reservations']): raise ValueError('INVALID_STATE')
        if len(data['reservations'])>24 or len(data['reports'])>=100 or len(data['proposals'])>=24: raise ValueError('STATE_LIMIT')
        if data['cached_proposal'] is not None: validate_proposal(data['cached_proposal'])
        validate_lesson(dict(LESSON,prior_outcome=data['prior_outcome'],failed_proposal_sha256=data['failed_proposal_sha256']))
        champion=data['champion']
        if champion is not None:
            exact(champion,{'memory','memory_sha256','source_sha256','manifest_sha256','proposal_sha256','report_sha256','promoted_at','proof'})
            for key in ('memory_sha256','source_sha256','manifest_sha256','proposal_sha256','report_sha256'):
                value=champion[key]
                if type(value) is not str or len(value)!=64 or any(x not in '0123456789abcdef' for x in value): raise ValueError('CHAMPION_PROVENANCE')
            if type(champion['promoted_at']) not in (int,float) or not math.isfinite(champion['promoted_at']) or champion['promoted_at']<0: raise ValueError('CHAMPION_PROVENANCE')
            if champion['source_sha256']!=self.source_sha256 or champion['manifest_sha256']!=self.manifest_sha256 or sha(canonical(champion['memory']))!=champion['memory_sha256']: raise ValueError('CHAMPION_PROVENANCE')
            proof=champion['proof']
            if type(proof) is not dict or sha(canonical(proof))!=champion['report_sha256'] or proof.get('candidate_memory_sha256')!=champion['memory_sha256'] or proof.get('source_sha256')!=self.source_sha256 or proof.get('manifest_sha256')!=self.manifest_sha256 or proof.get('proposal_sha256')!=champion['proposal_sha256']: raise ValueError('CHAMPION_PROVENANCE')
            memory(canonical(champion['memory']))
        for row in data['reports']:
            if type(row) is not dict: raise ValueError('INVALID_HISTORY')
            if 'proof' in row and (sha(canonical(row['proof']))!=row.get('report_sha256') or row['proof'].get('source_sha256')!=self.source_sha256 or row['proof'].get('manifest_sha256')!=self.manifest_sha256): raise ValueError('INVALID_HISTORY')
        return data

    def load(self):
        if not self.path.exists(): return self.fresh()
        if self.path.is_symlink() or self.path.stat().st_size>MAX_BYTES: raise ValueError('STATE_LIMIT')
        return self.validate(json.loads(self.path.read_bytes()))

    def save(self,data):
        data['reports']=data['reports'][-99:];data['proposals']=data['proposals'][-23:]
        raw=canonical(data)
        if len(raw)>MAX_BYTES: raise ValueError('STATE_LIMIT')
        self.validate(data);atomic(self.path,raw)

    def rollback(self):
        # Administrative rollback shares the same lock as training.
        with self.lock_path.open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            if self.previous_path.is_symlink() or self.previous_path.stat().st_size>MAX_BYTES: raise ValueError('STATE_LIMIT')
            old=self.validate(json.loads(self.previous_path.read_bytes()))
            current=self.load()
            # Budget history may never roll back with the champion.
            old['reservations']=current['reservations'];old['last_model_at']=current['last_model_at'];old['last_model_lesson']=current['last_model_lesson']
            self.save(old)

def reserve(state,now,lesson=None):
    data=state.load();recent=data['reservations']
    if type(now) not in (int,float) or not math.isfinite(now) or now<0: raise ValueError('INVALID_TIME')
    if recent and now-recent[-1]<900: return False
    recent=[t for t in recent if now-t<86400]
    if len(recent)>=24 or sum(now-t<3600 for t in recent)>=6: return False
    recent.append(now);data['reservations']=recent;data['last_model_at']=now;data['last_model_lesson']=sha(canonical(lesson or LESSON));state.save(data)
    return True

def validate_browser(report):
    if type(report) is not dict or report.get('synthetic_only') is not True or report.get('real_world_validated') is not False or report.get('browser_sessions')!=4 or not 1<=report.get('peak_active_workers',0)<=4 or report.get('teacher_requests')!=0 or report.get('false_mastery')!=0: raise ValueError('BROWSER_FAILED')
    if not report.get('cases') or any(x.get('passed') is not True or x.get('external_requests')!=0 for x in report['cases']): raise ValueError('BROWSER_FAILED')

def validate_score(row, training=False):
    keys={'success','actions','compiled_reuse','unsafe_actions','false_verified','teacher_calls','teacher_demonstrations','status','search_verified'}
    exact(row,keys|({'case_id'} if training else set()))
    if any(type(row[k]) is not bool for k in ('success','compiled_reuse','search_verified')): raise ValueError('INVALID_SCORE')
    for key in ('actions','unsafe_actions','false_verified','teacher_calls','teacher_demonstrations'): integer(row[key])
    if row['unsafe_actions'] or row['false_verified'] or row['teacher_calls']: raise ValueError('UNSAFE_SCORE')
    if row['teacher_demonstrations'] not in ((0,1) if training else (0,)): raise ValueError('EVALUATION_TEACHER')
    if row['status'] not in ('DONE','FAILED') or row['success']!=(row['status']=='DONE' and row['search_verified']): raise ValueError('INVALID_VERIFICATION')

def validate_report(result,request,state):
    exact(result,{'report','candidate_memory'})
    report=result['report'];candidate=result['candidate_memory'];memory(candidate)
    exact(report,{'schema_version','source_sha256','manifest_sha256','proposal_sha256','current_memory_sha256','candidate_memory_sha256','proposal_valid','rejection_reason','promotion_recommended','evaluation_teacher_calls','training_teacher_demonstrations','training_rows','synthetic_only','real_world_validated','cases','totals','training_manifest','evaluation_manifest'})
    if type(report['schema_version']) is not int or report['schema_version']!=1 or report['source_sha256']!=state.source_sha256 or report['manifest_sha256']!=state.manifest_sha256: raise ValueError('REPORT_PROVENANCE')
    manifest={'training':report['training_manifest'],'evaluation':report['evaluation_manifest']}
    if sha(canonical(manifest))!=state.manifest_sha256: raise ValueError('MANIFEST_HASH')
    train=manifest['training'];evaluation=manifest['evaluation']
    for rows in (train,evaluation):
        if type(rows) is not list or not rows: raise ValueError('INVALID_MANIFEST')
        for row in rows:
            exact(row,{'case_id','profile','seed','host'})
            if type(row['seed']) is not int or type(row['host']) is not str or not row['host'].endswith('.sim.invalid'): raise ValueError('INVALID_MANIFEST')
    for key in ('case_id','seed','host'):
        if set(x[key] for x in train)&set(x[key] for x in evaluation): raise ValueError('MANIFEST_OVERLAP')
    proposal=request['proposal'];expected=sha(canonical(proposal)) if proposal else None
    if report['proposal_sha256']!=expected or report['current_memory_sha256']!=sha(request['current_memory']) or report['candidate_memory_sha256']!=sha(candidate): raise ValueError('REPORT_HASH')
    if report['proposal_valid'] is not True or report['rejection_reason'] not in (None,'no_verified_gain') or type(report['promotion_recommended']) is not bool or integer(report['evaluation_teacher_calls'])!=0 or report['synthetic_only'] is not True or report['real_world_validated'] is not False: raise ValueError('REPORT_FAILED')
    integer(report['training_teacher_demonstrations'])
    rows=report['training_rows']
    if type(rows) is not list or len(rows)!=(len(train) if proposal else 0): raise ValueError('TRAINING_COVERAGE')
    for row in rows: validate_score(row,training=True)
    if proposal and {x['case_id'] for x in rows}!={x['case_id'] for x in train}: raise ValueError('TRAINING_COVERAGE')
    if sum(x['teacher_demonstrations'] for x in rows)!=report['training_teacher_demonstrations']: raise ValueError('FORGED_TEACHER_COUNT')
    if not proposal and (candidate!=request['current_memory'] or report['training_teacher_demonstrations']!=0): raise ValueError('EVALUATION_MUTATED')
    cases=report['cases']
    if type(cases) is not list or len(cases)!=len(evaluation) or {(x['case_id'],x['profile']) for x in cases}!={(x['case_id'],x['profile']) for x in evaluation}: raise ValueError('CASE_COVERAGE')
    totals={name:{'successes':0,'actions':0,'compiled_reuse':0,'unsafe_actions':0,'false_verified':0} for name in ('cold','current','candidate')}
    regression=False
    for case in cases:
        exact(case,{'case_id','profile','cold','current','candidate'})
        for name in totals:
            row=case[name];validate_score(row)
            for key,value in [('successes',int(row['success'])),('actions',row['actions']),('compiled_reuse',int(row['compiled_reuse'])),('unsafe_actions',0),('false_verified',0)]: totals[name][key]+=value
        a=case['candidate'];b=case['current']
        if int(a['success'])<int(b['success']) or (a['success']==b['success'] and a['actions']>b['actions']): regression=True
    exact(report['totals'],totals)
    for name in totals:
        exact(report['totals'][name],totals[name])
        for value in report['totals'][name].values(): integer(value)
    if report['totals']!=totals: raise ValueError('FORGED_TOTALS')
    def gain(a,b): return a['successes']>b['successes'] or (a['successes']==b['successes'] and a['actions']<b['actions'])
    improves=bool(proposal) and not regression and gain(totals['candidate'],totals['current']) and gain(totals['candidate'],totals['cold']) and any(x['candidate']['success'] and x['candidate']['compiled_reuse'] for x in cases)
    return report,candidate,improves,totals['current']['successes']==len(cases)

def run_once(state,lab_callback,model_callback,browser_callback,now):
    if state.lock_path.is_symlink(): raise ValueError('SYMLINK_LOCK')
    with state.lock_path.open('a') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: return {'status':'busy'}
        data=state.load()
        def finish(status,**fields):
            latest=state.load();row={'status':status,'at':now,**fields};latest['reports'].append(row);state.save(latest)
            return {key:value for key,value in row.items() if key!='proof'}
        try:
            validate_browser(browser_callback())
            current=canonical(data['champion']['memory']) if data['champion'] else b'{}'
            request={'proposal':None,'current_memory':current}
            baseline,_,_,mastered=validate_report(lab_callback(request),request,state)
            if data['champion'] and mastered: return finish('evaluated',report_sha256=sha(canonical(baseline)),proof=baseline)
            proposal=data['cached_proposal']
            if proposal is None:
                if data['last_model_at'] is not None and now-data['last_model_at']<900: return finish('cooldown')
                lesson=validate_lesson(dict(LESSON,prior_outcome=data['prior_outcome'],failed_proposal_sha256=data['failed_proposal_sha256']))
                if data['prior_outcome'] is not None and data['last_model_lesson']==sha(canonical(lesson)): return finish('model_input_unchanged')
                if not reserve(state,now,lesson): return finish('budget_exhausted')
                proposal=validate_proposal(model_callback(lesson))
                proposal_hash=sha(canonical(proposal))
                if proposal_hash in data['failed_proposal_sha256']:
                    latest=state.load()
                    latest['prior_outcome']=dict(latest['prior_outcome'],outcome='DUPLICATE_PROPOSAL')
                    state.save(latest)
                    return finish('duplicate_proposal')
                data=state.load();data['cached_proposal']=proposal;data['proposals'].append({'proposal':proposal,'at':now,'lesson_sha256':sha(canonical(lesson))});state.save(data)
            request={'proposal':proposal,'current_memory':current}
            report,candidate,improves,_=validate_report(lab_callback(request),request,state)
            # A candidate cannot manufacture gain by changing the independently
            # measured current or cold baseline between phases.
            before={x['case_id']:(x['cold'],x['current']) for x in baseline['cases']}
            after={x['case_id']:(x['cold'],x['current']) for x in report['cases']}
            if before!=after: raise ValueError('BASELINE_DRIFT')
            if not improves:
                data=state.load();proposal_hash=sha(canonical(proposal))
                data['cached_proposal']=None
                if proposal_hash not in data['failed_proposal_sha256']: data['failed_proposal_sha256'].append(proposal_hash)
                data['failed_proposal_sha256']=data['failed_proposal_sha256'][-23:]
                data['prior_outcome']={'outcome':'NO_GAIN','proposal_sha256':proposal_hash,'report_sha256':sha(canonical(report)),'memory_sha256':sha(candidate),'totals':report['totals']}
                state.save(data)
                return finish('no_gain',report_sha256=sha(canonical(report)),proof=report)
            data=state.load();atomic(state.previous_path,canonical(data))
            data['champion']={'memory':memory(candidate),'memory_sha256':sha(candidate),'source_sha256':state.source_sha256,'manifest_sha256':state.manifest_sha256,'proposal_sha256':sha(canonical(proposal)),'report_sha256':sha(canonical(report)),'promoted_at':now,'proof':report}
            state.save(data)
            return finish('promoted',memory_sha256=sha(candidate),report_sha256=sha(canonical(report)),proof=report)
        except Exception:
            # Never persist transport errors, model text, paths or arbitrary callback messages.
            return finish('failed')
