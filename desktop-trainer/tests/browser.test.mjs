import {test} from 'node:test';
import assert from 'node:assert/strict';
const module = await import('../renderer.mjs').catch(() => ({}));
async function session() {
  assert.equal(typeof module.createBrowserSession,'function','browser renderer has not been implemented');
  return module.createBrowserSession();
}
test('the shipped extractor sees and acts on actual search/filter/detail HTML', async()=>{
  const b=await session();
  try {
    await b.handle('reset',{seed:11,host:'practice.sim.invalid',family:'classic'});
    const before=await b.handle('observe',{});
    const search=before.elements.find(e=>e.type==='search');
    const act=command=>b.handle('act',{...command,expectedDocumentId:before.documentId,expectedUrl:before.url});
    assert.equal((await act({cmd:'type',id:search.id,text:'Ford Expedition',submit:false})).ok,true);
    let obs=await b.handle('observe',{});
    assert.equal((await b.handle('truth',{})).appliedQuery,'','typing alone cannot earn a submitted-search result');
    const submit=obs.elements.find(e=>e.submitType);
    assert.equal((await b.handle('act',{cmd:'click',id:submit.id,expectedDocumentId:obs.documentId,expectedUrl:obs.url})).ok,true);
    obs=await b.handle('observe',{});
    assert.equal(obs.items.length,2,JSON.stringify({truth:await b.handle('truth',{}),observation:obs,html:await b.page.content()}));
    const price=obs.elements.find(e=>e.name==='Price max');
    await b.handle('act',{cmd:'set_range',id:price.id,value:'8000',direction:'max',expectedDocumentId:obs.documentId,expectedUrl:obs.url});
    obs=await b.handle('observe',{});
    assert.equal((await b.handle('truth',{})).appliedFilters.price_max,'8000');
    const item=obs.elements.find(e=>e.itemKey);
    await b.handle('act',{cmd:'click',id:item.id,expectedDocumentId:obs.documentId,expectedUrl:obs.url});
    const detail=await b.handle('observe',{});
    assert.equal(detail.signals.detailHint,true);
    assert.match(detail.detailText,/142000 miles/);
    assert.equal((await b.handle('truth',{})).catalog.some(l=>l.key===item.itemKey),true);
  }finally{await b.close();}
});
test('stale document identity is refused by the actual content script',async()=>{
  const b=await session();
  try {
    await b.handle('reset',{seed:11,host:'practice.sim.invalid',fault:'stale'});
    const obs=await b.handle('observe',{}), search=obs.elements.find(e=>e.type==='search');
    const r=await b.handle('act',{cmd:'type',id:search.id,text:'Ford',submit:true,expectedDocumentId:obs.documentId,expectedUrl:obs.url});
    assert.equal(r.ok,false);assert.equal(r.detail,'STALE_DOCUMENT');
  }finally{await b.close();}
});
test('auth is redacted, disabled submit does not search, and network cannot escape',async()=>{
  const b=await session();
  try {
    await b.handle('reset',{seed:11,host:'practice.sim.invalid',fault:'auth'});
    const auth=await b.handle('observe',{});
    assert.equal(auth.signals.passwordFields,1);
    assert.equal(auth.items.length,0);
    await assert.rejects(()=>b.handle('navigate',{url:'https://offerup.com/'}),/outside practice/);
    await b.handle('reset',{seed:11,host:'practice.sim.invalid',fault:'disabled'});
    const obs=await b.handle('observe',{}), button=obs.elements.find(e=>e.tag==='button');
    assert.equal(button.enabled,false);
    assert.equal((await b.handle('truth',{})).appliedQuery,'');
    const outside=await b.page.evaluate(()=>fetch('https://example.com/').then(()=>true).catch(()=>false));
    assert.equal(outside,false);
  }finally{await b.close();}
});
