import {createRequire} from 'node:module';
import {readFileSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {test,before,after} from 'node:test';
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE||'playwright');
const source=readFileSync(new URL('../../../../app/src/main/assets/sitebrain/content.js',import.meta.url),'utf8');
let browser;
before(async()=>{browser=await chromium.launch({executablePath:process.env.CHROMIUM||undefined});});
after(async()=>{await browser?.close();});
const recommendations='<section><h2>Similar synthetic items</h2>'+[2,3,4].map(i=>'<a class="card" href="/item/'+(910000+i)+'"><span>Synthetic recommendation '+i+'</span><span>$600</span></a>').join('')+'</section>';
const detail='<main><h1>Synthetic bicycle 1</h1><p>$500</p><p>Description: '+'A synthetic bicycle in good condition with replacement tires. '.repeat(10)+'</p><button aria-expanded="false">See more</button>'+recommendations+'</main>';
async function observe(layer,height=373){
 const page=await browser.newPage({viewport:{width:393,height}});
 try{
  await page.route('**/*',route=>route.fulfill({contentType:'text/html',body:'<style>body{margin:0}.card{display:block;height:130px;width:300px}.panel{position:fixed;left:0;right:0;bottom:0;height:184px;background:white;z-index:20}</style>'+detail+layer}));
  await page.goto('https://fixtures.test/item/900001');await page.evaluate(source);
  await page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:2000}));
  return await page.evaluate(()=>window.__brainObserve());
 }finally{await page.close();}
}
const controls='<p>Explore the synthetic catalog</p><a href="/catalog">Browse catalog</a><button aria-label="Close banner">X</button>';
for(const height of [373,915])test('docked nonmodal landmark preserves listing scope at height '+height,async()=>{
 const o=await observe('<div role="region" aria-label="Catalog banner" class="panel">'+controls+'</div>',height);
 assert.equal(o.signals.dialog,false,'nonmodal bottom landmark must not become a modal on short screens');
 assert.equal(o.signals.detailHint,true);assert.equal(o.signals.resultsHint,false);
 assert.ok(o.detailText.includes('Synthetic bicycle 1'));
 assert.ok(!o.detailText.includes('Explore the synthetic catalog'));
 assert.ok(o.elements.some(e=>e.name==='Close banner'&&!e.inDialog));
 assert.equal(o.items.length,3,'recommendations must be actually recognized');
 assert.ok(!o.detailText.includes('Synthetic recommendation'));
 if(height===373&&process.env.BANNER_FIXTURE_OUTPUT)writeFileSync(process.env.BANNER_FIXTURE_OUTPUT,JSON.stringify(o));
});
for(const attributes of ['role="dialog"','role="alertdialog"','role="region" aria-modal="true"',''])test('real modal ownership preserved for '+(attributes||'untyped overlay'),async()=>{
 const o=await observe('<div '+attributes+' class="panel">'+controls+'</div>');
 assert.equal(o.signals.dialog,true);assert.equal(o.signals.detailHint,false);
 assert.ok(o.elements.some(e=>e.name==='Close banner'&&e.inDialog));
});
test('full-screen region is still a blocking overlay',async()=>{
 const o=await observe('<div role="region" class="panel" style="height:100vh">'+controls+'</div>');
 assert.equal(o.signals.dialog,true);assert.equal(o.signals.detailHint,false);
});
test('nested explicit dialog survives a nonmodal landmark parent',async()=>{
 const o=await observe('<div role="region" class="panel"><div role="dialog" aria-label="Synthetic modal">'+controls+'</div></div>');
 assert.equal(o.signals.dialog,true);assert.equal(o.signals.detailHint,false);
 assert.ok(o.elements.some(e=>e.name==='Close banner'&&e.inDialog));
});

test('TRAIN listing navigation under a docked banner produces a replayable City observation',async()=>{
 const page=await browser.newPage({viewport:{width:393,height:373}});
 try{
  const cards=[1,2,3].map(i=>'<a class="card" href="/item/'+(900000+i)+'"><span>Synthetic bicycle '+i+'</span><span>$500</span></a>').join('');
  await page.route('**/*',route=>route.fulfill({contentType:'text/html',body:new URL(route.request().url()).pathname==='/search'?'<style>.card{display:block;height:130px;width:300px}</style><main>'+cards+'</main>':'<style>body{margin:0}.card{display:block;height:130px;width:300px}.panel{position:fixed;left:0;right:0;bottom:0;height:184px;background:white;z-index:20}</style>'+detail+'<div role="region" class="panel">'+controls+'</div>'}));
  await page.addInitScript(source);await page.goto('https://fixtures.test/search');
  await page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:2000}));
  const before=await page.evaluate(()=>window.__brainObserve());assert.equal(before.items.length,3);
  await page.evaluate(({before})=>window.__brainExecute({cmd:'click',guardMode:'TRAIN',id:before.items[0].aff,expectedDocumentId:before.documentId,expectedUrl:before.url}),{before});
  await page.waitForURL('**/item/900001');await page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:2000}));
  const opened=await page.evaluate(()=>window.__brainObserve());assert.equal(opened.signals.detailHint,true);assert.equal(opened.signals.dialog,false);
  if(process.env.BANNER_BUNDLE_OUTPUT){
   const loading=JSON.parse(readFileSync(new URL('../fixtures/delayed_item.observation.json',import.meta.url),'utf8')).loading;
   writeFileSync(process.env.BANNER_BUNDLE_OUTPUT,JSON.stringify({before,detail:opened,loading}));
  }
 }finally{await page.close();}
});

test('a separate modal backdrop keeps a docked region blocking',async()=>{
 const o=await observe('<div style="position:fixed;inset:0;z-index:19;background:#0008"></div><div role="region" class="panel">'+controls+'</div>');
 assert.equal(o.signals.dialog,true,'a noninteractive scrim is still a blocking modal backdrop');
 assert.equal(o.signals.detailHint,false);
});
test('an inert primary page does not turn a modal region into a banner',async()=>{
 const o=await observe('<script>document.querySelector("main").inert=true</script><div role="region" class="panel">'+controls+'</div>');
 assert.equal(o.signals.dialog,true);assert.equal(o.signals.detailHint,false);
});

test('hidden preloaded dialog does not convert a nonmodal banner into a modal',async()=>{
 const o=await observe('<div role="region" class="panel">'+controls+'<div role="dialog" hidden><button>Close preferences</button></div></div>');
 assert.equal(o.signals.dialog,false);assert.equal(o.signals.detailHint,true);
});
test('offscreen preloaded dialog does not block the visible listing',async()=>{
 const o=await observe('<div role="region" class="panel">'+controls+'<div role="dialog" style="position:fixed;top:-2000px"><button>Close preferences</button></div></div>');
 assert.equal(o.signals.dialog,false);assert.equal(o.signals.detailHint,true);
});

test('absolute backdrop inside primary content keeps the region blocking',async()=>{
 const o=await observe(`<script>const m=document.querySelector("main");m.style.position="relative";const b=document.createElement("div");b.style.cssText="position:absolute;inset:0;z-index:19;background:#0008";m.append(b);</script><div role="region" class="panel">`+controls+'</div>');
 assert.equal(o.signals.dialog,true);assert.equal(o.signals.detailHint,false);
});

test('ordinary absolute listing image remains reachable beneath a nonmodal banner',async()=>{
 const o=await observe(`<script>const box=document.createElement("div");box.style.cssText="position:relative;height:210px;width:100%";const image=document.createElement("img");image.alt="Synthetic listing photo";image.style.cssText="position:absolute;inset:0;width:100%;height:100%;background:silver";box.append(image);document.querySelector("main").prepend(box)</script><div role="region" class="panel">`+controls+'</div>');
 assert.equal(o.signals.dialog,false);assert.equal(o.signals.detailHint,true);
});
