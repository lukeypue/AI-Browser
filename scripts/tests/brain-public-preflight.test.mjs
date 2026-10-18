import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
const mod = await import('../brain-public-preflight.mjs').catch(() => ({}));
test('probe implementation is present', () => assert.equal(typeof mod.runPreflight, 'function'));
test('rejects arbitrary fields, invalid site/status, unbounded counters and duration', () => {
  assert.equal(typeof mod.validateReport, 'function');
  const row = {siteId:'ksl_cars',status:'AVAILABLE',counts:{searchboxes:1,controls:2,items:3,dialogs:0},durationMs:10,sourceHash:'a'.repeat(64),extractorHash:'b'.repeat(64)};
  const rows=['ksl_classifieds','ksl_cars','facebook_marketplace','offerup'].map(siteId=>({...row,siteId}));
  assert.deepEqual(mod.validateReport(rows),rows);
  for (const patch of [{title:'private'}, {siteId:'other'}, {status:'OK'}, {durationMs:31000}, {counts:{...row.counts,items:121}}, {counts:{...row.counts,name:'private'}}]) {
    assert.throws(()=>mod.validateReport([{...row,...patch},...rows.filter(r=>r.siteId!=='ksl_cars')]));
  }
});
test('in-page aggregation keeps raw extractor fields inside browser', () => {
  assert.equal(typeof mod.observeInPage,'function');
  const raw={title:'PRIVATE TITLE',url:'PRIVATE URL',signals:{},elements:[{type:'search',name:'PRIVATE NAME'},{}],items:[{title:'PRIVATE ITEM'}],regions:[{role:'DIALOG'}]};
  const document={body:{innerText:''},querySelector:()=>null};
  const result=vm.runInNewContext('('+mod.observeInPage.toString()+')()', {window:{__brainObserve:()=>raw},document,location:{pathname:'/'}});
  assert.equal(JSON.stringify(result),'{"status":"AVAILABLE","counts":{"searchboxes":1,"controls":2,"items":1,"dialogs":1}}');
  for (const signals of [{passwordFields:1},{authPath:true},{challengeWidget:true},{challengePhrase:true},{challengePath:true},{errorHint:true}]) {
    raw.signals=signals;
    const value=vm.runInNewContext('('+mod.observeInPage.toString()+')()', {window:{__brainObserve:()=>raw},document,location:{pathname:'/'}});
    assert.notEqual(value.status,'AVAILABLE');
    assert.equal(value.counts.controls,0);
  }
});
test('isolates four visits, blocks non-GET and extra documents, closes contexts, emits allowlisted data only', async () => {
  assert.equal(typeof mod.runPreflight,'function');
  const contexts=[]; let active=0,peak=0,visits=0;
  const browser={newContext:async options=>{
    assert.deepEqual(options,{serviceWorkers:'block',acceptDownloads:false});
    active++;peak=Math.max(peak,active);
    const c={route:async(pattern,handler)=>{c.handler=handler;},routeWebSocket:async(pattern,handler)=>{c.ws=handler;},newPage:async()=>({
      setDefaultTimeout:ms=>assert.equal(ms,30000),
      goto:async(url,options)=>{
        visits++; assert.equal(options.timeout,30000);
        let allowed=0,blocked=0;
        for(const [method,nav] of [['POST',false],['GET',true],['GET',true],['GET',false]]) {
          await c.handler({request:()=>({method:()=>method,isNavigationRequest:()=>nav,resourceType:()=>nav?'document':'script'}),abort:async()=>blocked++,continue:async()=>allowed++});
        }
        assert.equal(allowed,2);assert.equal(blocked,2);
        let wsClosed=0;c.ws({close:()=>wsClosed++});assert.equal(wsClosed,1);
        return {status:()=>contexts.length===1?403:200};
      },
      addScriptTag:async({content})=>assert.equal(content,'extractor'),
      evaluate:async()=>({status:'AVAILABLE',counts:{searchboxes:1,controls:2,items:3,dialogs:0}})
    }),close:async()=>{active--;c.closed=true;}};
    contexts.push(c); return c;
  }};
  const rows=await mod.runPreflight({browser,extractor:'extractor',sourceHash:'a'.repeat(64)});
  assert.equal(rows.length,4);assert.equal(visits,4);assert.ok(peak<=4);assert.ok(contexts.every(c=>c.closed));
  mod.validateReport(rows);
  assert.equal(JSON.stringify(rows).includes('https:'),false);
});
test('response denial and exceptions stop observation without exporting errors', async () => {
  let observed=0,closed=0;
  for(const code of [401,403,429,500,'throw']) {
    const browser={newContext:async()=>({route:async()=>{},routeWebSocket:async()=>{},newPage:async()=>({
      setDefaultTimeout:()=>{},goto:async()=>{if(code==='throw')throw Error('PRIVATE URL');return {status:()=>code};},
      addScriptTag:async()=>observed++,evaluate:async()=>{throw Error('must not observe');}
    }),close:async()=>closed++})};
    const rows=await mod.runPreflight({browser,extractor:'extractor',sourceHash:'a'.repeat(64)});
    assert.equal(rows[0].status,code===401?'AUTH_REQUIRED':[403,429].includes(code)?'ACCESS_BLOCKED':'UNAVAILABLE');
    assert.equal(JSON.stringify(rows).includes('PRIVATE'),false);
  }
  assert.equal(observed,0);assert.equal(closed,20);
});

test('protected page DOM stops before invoking the existing extractor', () => {
  for(const [document,location,status] of [
    [{body:{innerText:'Access denied'},querySelector:()=>null},{pathname:'/'},'ACCESS_BLOCKED'],
    [{body:{innerText:'Verify you are human'},querySelector:()=>null},{pathname:'/'},'HUMAN_REQUIRED'],
    [{body:{innerText:''},querySelector:()=>({})},{pathname:'/'},'AUTH_REQUIRED'],
    [{body:{innerText:''},querySelector:()=>null},{pathname:'/login/'},'AUTH_REQUIRED']
  ]) {
    const result=vm.runInNewContext('('+mod.observeInPage.toString()+')()', {window:{__brainObserve:()=>{throw Error('must stop before extraction');}},document,location});
    assert.equal(result.status,status);
    assert.equal(result.counts.controls,0);
  }
});
