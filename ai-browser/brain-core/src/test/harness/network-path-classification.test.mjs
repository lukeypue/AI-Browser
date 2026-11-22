import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {test} from 'node:test';
const source=readFileSync(new URL('../../../../app/src/main/assets/sitebrain/background.js',import.meta.url),'utf8');
function bridge(mode='TRAIN'){
 let request,control;const reports=[];
 const port={postMessage:m=>reports.push(m),onMessage:{addListener:f=>control=f},onDisconnect:{addListener(){}}};
 const browser={webRequest:{onBeforeRequest:{addListener:f=>request=f}},runtime:{connectNative:()=>port}};
 vm.runInNewContext(source,{browser,URL,TextDecoder,Date,setTimeout:()=>{throw Error('unexpected timer');}});
 control({type:'NET_MODE',mode});reports.length=0;
 return {request,control,reports};
}
const post=(host,path='/api/graphql',op='CatalogSearchQuery')=>({method:'POST',url:`https://${host}${path}`,type:'xmlhttprequest',requestBody:{formData:{operationName:[op]}}});
const cancelled=(b,r)=>b.request(r).cancel===true;
test('known read operations remain allowed on a neutral hostname',()=>{assert.equal(cancelled(bridge(),post('catalog.fixture.invalid')),false);});
test('action words in hostnames are not mutation paths',()=>{for(const host of ['offer.fixture.invalid','message.fixture.invalid','checkout.fixture.invalid','save.fixture.invalid','create.fixture.invalid']){const b=bridge();assert.equal(cancelled(b,post(host)),false,host);assert.equal(b.reports.length,0);}});
test('actual mutation paths stay blocked even with read-looking operation names',()=>{for(const path of ['/offer','/messages/send','/checkout','/create','/save','/report'])assert.equal(cancelled(bridge(),post('catalog.fixture.invalid',path)),true,path);});
test('write operation names stay blocked on neutral paths',()=>{for(const op of ['SendMessageMutation','CreateListingMutation','DeleteListing','SaveListingMutation'])assert.equal(cancelled(bridge(),post('catalog.fixture.invalid','/api/graphql',op)),true,op);});
test('unknown mutations remain blocked in training',()=>{const b=bridge();assert.equal(cancelled(b,post('catalog.fixture.invalid','/api/endpoint','OpaqueOperation')),true);assert.equal(b.reports.at(-1).type,'NET_UNKNOWN_MUTATION');});
test('malformed mutation URLs are blocked without a listener exception',()=>{const b=bridge();const r=post('catalog.fixture.invalid');r.url='not a valid absolute URL';assert.equal(cancelled(b,r),true);assert.equal(b.reports.at(-1).type,'NET_UNKNOWN_MUTATION');});
test('human browsing OFF remains unblocked',()=>{assert.equal(cancelled(bridge('OFF'),post('catalog.fixture.invalid','/checkout','SendMessageMutation')),false);});
test('ASSIST still requires a grant for a known write',()=>{const b=bridge('ASSIST'),r=post('catalog.fixture.invalid','/checkout','SendMessageMutation');assert.equal(cancelled(b,r),true);b.control({type:'NET_GRANT',ttlMs:60000});assert.equal(cancelled(b,r),false);b.control({type:'NET_GRANT_CLOSE'});assert.equal(cancelled(b,r),true);});
