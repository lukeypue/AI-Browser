"""Strict bounded synthetic diagnostic evidence; no receiver state or network access."""
def require(ok,label):
    if not ok:raise ValueError('Rejected report: '+label)

DIAGNOSTIC_COUNTS=('events_seen','events_dropped','action_verified','action_failed','neutral_programs','held_programs','planner_errors','new_lessons')

def _validate_city_diagnostics(report):
    """Independent strict verification, including trace privacy and totals from every episode."""
    from receiver import validate as validate_phone,Failure
    def check(ok):require(ok,'City diagnostic evidence')
    def exact(value,keys):check(type(value) is dict and set(value)==set(keys))
    def bounded(value):check(type(value) is int and 0<=value<=1000000);return value
    def episode(value):
        exact(value,(*DIAGNOSTIC_COUNTS,'trace'))
        for key in DIAGNOSTIC_COUNTS:bounded(value[key])
        trace=value['trace'];check(type(trace) is list and len(trace)<=6 and value['events_seen']-len(trace)==value['events_dropped'])
        events=[]
        for row in trace:
            check(type(row) is dict and 'site' not in row)
            events.append(dict(row,site='UNKNOWN'))
        dummy={'schema_version':2,'report_id':'11111111-1111-4111-8111-111111111111','app_revision':'a'*40,'version_code':0,'started_at_utc':'2026-01-01T00:00:00Z','elapsed_ms':86400000,'flags':{'trimmed':False,'clock_changed':False,'interrupted':False},'summary':{'verified':0,'failed':0,'tasks':0,'verified_skills':0,'end_reason':'unknown'},'events':events}
        try:validate_phone(dummy)
        except (Failure,ValueError,TypeError,KeyError):raise ValueError('Rejected report: City trace schema') from None
        observed={'action_verified':sum(e['code']=='action_outcome' and e['status']=='VERIFIED' for e in events),
            'action_failed':sum(e['code']=='action_outcome' and e['status']=='FAILED' for e in events),
            'neutral_programs':sum(e['code']=='memory_checkpoint' and e['counters'].get('outcome_neutral')==1 for e in events),
            'held_programs':sum(e['code']=='memory_checkpoint' and e['diagnostic_code']=='STRATEGY_HELD' for e in events),
            'planner_errors':sum(e['code']=='planner_outcome' and e.get('planner_outcome')=='ERROR' for e in events),
            'new_lessons':sum(e['counters'].get('new_lessons',0) for e in events if e['code']=='learning_outcome')}
        for key,count in observed.items():check(value[key]>=count and (value['events_dropped']!=0 or value[key]==count))
        return {key:value[key] for key in (*DIAGNOSTIC_COUNTS,'trace')}
    evidence=report['diagnostics'];exact(evidence,{'schema_version','synthetic_only','scope','stage_summaries','samples','samples_truncated'}|({'learning_clinic'} if type(evidence) is dict and 'learning_clinic' in evidence else set()))
    check(type(evidence['schema_version']) is int and evidence['schema_version']==1 and evidence['synthetic_only'] is True and evidence['scope']=='FROZEN_EXAM_EPISODES')
    stages={}
    for pairs,before,after in [('fixed_pairs','baseline','after_training'),('unseen_pairs','unseen_baseline','unseen_after_training'),('city_pairs','city_baseline','city_after_training'),('challenge_pairs','challenge_baseline','challenge_after_training')]:
        if pairs not in report:continue
        for side,name in [('before',before),('after',after)]:
            stages[name]={row[side]['scenario']:episode(row[side]['diagnostics']) for row in report[pairs]}
    exact(evidence['stage_summaries'],stages)
    summaries={}
    for stage,rows in stages.items():
        expected=dict(episodes=len(rows),**{key:sum(row[key] for row in rows.values()) for key in DIAGNOSTIC_COUNTS})
        supplied=evidence['stage_summaries'][stage];exact(supplied,expected)
        for key,value in expected.items():check(bounded(supplied[key])==value)
        summaries[stage]=expected
    samples=evidence['samples'];check(type(samples) is list and len(samples)<=8)
    check(type(evidence['samples_truncated']) is bool and evidence['samples_truncated']==(len(samples)<sum(len(rows) for rows in stages.values())))
    out=[];seen=set()
    for row in samples:
        exact(row,{'stage','scenario','diagnostics'});stage=row['stage'];case=row['scenario']
        check(type(stage) is str and stage in stages and type(case) is str and case in stages[stage] and (stage,case) not in seen);seen.add((stage,case))
        check(episode(row['diagnostics'])==stages[stage][case]);out.append({'stage':stage,'scenario':case,'diagnostics':row['diagnostics']})
    result={'schema_version':1,'synthetic_only':True,'scope':'FROZEN_EXAM_EPISODES','stage_summaries':summaries,'samples':out,'samples_truncated':evidence['samples_truncated']}
    if 'learning_clinic' in evidence:
        c=evidence['learning_clinic'];exact(c,{'schema_version','real_world_validated','teacher_requests','cases'})
        check(type(c['schema_version']) is int and c['schema_version']==1 and c['real_world_validated'] is False and type(c['teacher_requests']) is int and c['teacher_requests']==0)
        check(type(c['cases']) is list and len(c['cases'])==4)
        rows=[]
        for row,case in zip(c['cases'],['detail','unknown_after_click','noop_click','human']):
            exact(row,{'case_id','lesson','status','actions','diagnostic_expectation_met','diagnostics'})
            check(row['case_id']==case and row['lesson']=='expand_description' and row['status'] in {'PENDING','RUNNING','NEED_HUMAN','NEED_GRANT','DONE','FAILED','ABANDONED','PAUSED','PARTIAL','BUDGET_EXHAUSTED'})
            check(bounded(row['actions'])<=8);d=episode(row['diagnostics'])
            actions=[e for e in d['trace'] if e['code']=='action_outcome' and e['capability']=='open_item']
            if case=='human':observed=row['status']=='NEED_HUMAN' and row['actions']==0
            elif case=='detail':observed=d['action_verified']>0 and any(e['status']=='VERIFIED' and e.get('after_page_type')=='DETAIL' for e in actions)
            else:observed=d['action_failed']>0 and any(e['status']=='FAILED' and e.get('after_page_type')==('UNKNOWN' if case=='unknown_after_click' else 'RESULTS') for e in actions)
            check(type(row['diagnostic_expectation_met']) is bool and row['diagnostic_expectation_met']==observed)
            rows.append({**row,'diagnostics':d})
        result['learning_clinic']={'schema_version':1,'real_world_validated':False,'teacher_requests':0,'cases':rows}
    return result

def sanitize_city_diagnostics(report):
    try:return _validate_city_diagnostics(report)
    except (ValueError,KeyError,TypeError,OverflowError):
        raise ValueError('Rejected report: City diagnostic evidence') from None
