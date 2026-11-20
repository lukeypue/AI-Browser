import {createRequire} from 'node:module';
import {readFileSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {test} from 'node:test';
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE||'playwright');
const source=readFileSync(new URL('../../../../app/src/main/assets/sitebrain/content.js',import.meta.url),'utf8');
test('a loading item modal is not a detail until visible listing evidence arrives',async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM||undefined});
 try{
  const page=await browser.newPage({viewport:{width:412,height:915}});
  await page.route('**/*',route=>route.fulfill({contentType:'text/html',body:`<style>a.card{display:block;height:130px;width:300px}#modal{position:fixed;inset:0;background:white;z-index:100}</style><main id="results">${[1,2,3].map(i=>`<a class="card" href="/item/${10000+i}"><span>Synthetic bicycle ${i}</span><span>$500</span></a>`).join('')}</main><div id="modal" role="dialog" hidden><button>Close</button><div id="content">Loading item</div></div><script>document.querySelectorAll('a.card').forEach(a=>a.onclick=e=>{e.preventDefault();document.querySelector('#results').hidden=true;document.querySelector('#modal').hidden=false;});window.finishItem=()=>{document.querySelector('#content').innerHTML='<h2>Synthetic bicycle 1</h2><p>$500</p><p>Description: '+ 'The synthetic bicycle is in good condition. '.repeat(12)+'</p>';};</script>`}));
  await page.goto('https://fixtures.test/search');await page.evaluate(source);
  await page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:2000}));
  const before=await page.evaluate(()=>window.__brainObserve());assert.equal(before.items.length,3);assert.equal(before.settle.state,'IDLE');
  const target=before.items[0];const result=await page.evaluate(({before,target})=>window.__brainExecute({cmd:'click',id:target.aff,expectedDocumentId:before.documentId,expectedUrl:before.url}),{before,target});assert.equal(result.ok,true);
  await page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:2000}));
  const loading=await page.evaluate(()=>window.__brainObserve());assert.equal(loading.signals.dialog,true);assert.equal(loading.signals.detailHint,false);
  await page.evaluate(()=>window.finishItem());
  await page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:2000}));
  const detail=await page.evaluate(()=>window.__brainObserve());assert.equal(detail.signals.dialog,true);assert.equal(detail.signals.detailHint,true);assert.ok(detail.detailText.includes('Synthetic bicycle 1'));
  // Snapshots are fixed synthetic browser observations, never live-page data.
  if(process.env.ITEM_FIXTURE_OUTPUT)writeFileSync(process.env.ITEM_FIXTURE_OUTPUT,JSON.stringify({before,loading,detail}));
 }finally{await browser.close();}
});
