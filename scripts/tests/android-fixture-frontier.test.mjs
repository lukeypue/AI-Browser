import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {buildFixtures} from '../android-lab/fixture_builder.mjs';
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE||'playwright');
const script=readFileSync(new URL('../../ai-browser/app/src/main/assets/sitebrain/content.js',import.meta.url),'utf8');
const origin='https://reports.ryzod.com';
const cp=process.env.FIXTURE_CORE_CLASSPATH;
function proof(mode,before,after){
 assert.ok(cp,'explicit compiled core classpath required');
 const result=spawnSync(process.env.JAVA_BIN||'/usr/lib/jvm/java-17-openjdk-amd64/bin/java',['-Xmx128m','-cp',cp,fileURLToPath(new URL('./AndroidFixtureSemantics.java',import.meta.url))],{input:JSON.stringify({mode,before,after}),encoding:'utf8',timeout:20000,maxBuffer:1024*1024});
 assert.equal(result.status,0,result.stderr||String(result.error));return JSON.parse(result.stdout);
}
async function observe(page){await page.evaluate(script);const settled=await page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:3000}));assert.equal(settled.state,'IDLE');return page.evaluate(()=>window.__brainObserve());}
async function fixture(query,run){
 const files=buildFixtures();const browser=await chromium.launch();const page=await browser.newPage({viewport:{width:1000,height:800}});
 await page.route('**/*',async route=>{
  const url=new URL(route.request().url());assert.equal(url.origin,origin,'all fixture requests stay on dedicated HTTPS origin');
  let key=url.pathname.replace('/practice-fixtures/','');if(key.endsWith('/'))key+='index.html';
  assert.ok(files[key],key);await route.fulfill({body:files[key],contentType:key.endsWith('.js')?'text/javascript':'text/html'});
 });
 try{await page.goto(origin+'/practice-fixtures/classifieds/?q=mountain+bike'+query);await run(page);}finally{await browser.close();}
}
const keys=obs=>obs.items.map(item=>item.key);
const fresh=(before,after)=>keys(after).some(key=>!keys(before).includes(key));
test('next page is a physical same-origin link with distinct recognized item keys and real detail/back',()=>fixture('',async page=>{
 const before=await observe(page);
 const next=before.elements.find(element=>element.rel==='next'&&element.name==='Next page'&&element.sameSite);
 assert.ok(next,'bundled perception must expose rel=next for PAGE_NEXT classification');
 assert.equal(new URL(next.href).origin,origin);
 if(cp){const result=proof('next',before,before);assert.equal(result.next_role,true);assert.equal(result.target_skill,'next_page');assert.equal(result.holds,false);}
 await page.getByRole('link',{name:'Next page',exact:true}).click();
 assert.equal(new URL(page.url()).searchParams.get('page'),'2');
 const after=await observe(page);assert.equal(after.items.length,4);assert.equal(fresh(before,after),true);
 if(cp){assert.equal(proof('next',before,after).holds,true);assert.equal(proof('next',after,after).holds,false);}
 assert.ok(after.items.every(item=>Number(new URL(item.href).searchParams.get('item'))>=13),'page two uses distinct catalog ids');
 await page.locator('a.card').first().click();
 assert.equal((await observe(page)).signals.detailHint,true);
 assert.match(await page.locator('#detail').innerText(),/Price: \$150\. Condition: fair\./);
 await page.getByRole('link',{name:'Back to results'}).click();
 assert.equal(new URL(page.url()).searchParams.get('page'),'2');
 assert.deepEqual(keys(await observe(page)),keys(after));
}));
test('last page has finite batches and repeated same-page observations cannot supply new keys',()=>fixture('&page=2',async page=>{
 const initial=await observe(page);
 assert.ok(initial.items.every(item=>Number(new URL(item.href).searchParams.get('item'))>=13),'terminal page must be the distinct second catalog');
 assert.equal(await page.getByRole('link',{name:'Next page',exact:true}).count(),0,'terminal page has no forward/self-loop link');
 assert.equal(fresh(initial,await observe(page)),false);
 if(cp){assert.equal(proof('next',initial,initial).next_role,false);assert.equal(proof('next',initial,initial).holds,false);}
 await page.getByRole('button',{name:'Load more',exact:true}).evaluate(button=>button.click());
 assert.equal(await page.locator('a.card').count(),8);
 await page.getByRole('button',{name:'Load more',exact:true}).evaluate(button=>button.click());
 assert.equal(await page.locator('a.card').count(),12);
 const terminal=await observe(page);
 assert.equal(new Set(keys(terminal)).size,12);
 assert.equal(await page.getByRole('button',{name:'End of results'}).isDisabled(),true);
 await page.mouse.wheel(0,4000);await page.waitForTimeout(150);
 assert.deepEqual(keys(await observe(page)),keys(terminal));
 await page.evaluate(()=>{history.scrollRestoration='manual';window.scrollTo(0,0);});await page.reload();assert.deepEqual(keys(await observe(page)),keys(initial));
}));
test('optional generated notice is a blocking semantic dialog that physically closes without revisiting',()=>fixture('&notice=1',async page=>{
 const dialog=page.getByRole('dialog',{name:'Synthetic practice notice'});
 assert.equal(await dialog.count(),1,'explicit opt-in notice supplies dismiss_dialog training');
 assert.equal(await dialog.isVisible(),true);
 const before=await observe(page);assert.equal(before.signals.dialog,true);
 assert.ok(before.regions.some(region=>region.role==='DIALOG'));
 if(cp){const result=proof('dialog',before,before);assert.equal(result.close_role,true);assert.equal(result.dialog_role,true);assert.equal(result.target_skill,'dismiss_dialog');assert.equal(result.holds,false);}
 assert.ok(before.elements.some(element=>element.inDialog&&element.name==='Close'));
 await assert.rejects(page.getByRole('searchbox').click({timeout:300}),/intercepts pointer events/,'native modal must block underlying controls');
 await page.getByRole('button',{name:'Close',exact:true}).click();
 const after=await observe(page);assert.equal(after.signals.dialog,false);
 if(cp){assert.equal(proof('dialog',before,after).holds,true);assert.equal(proof('dialog',after,after).holds,false);}
 assert.equal(await dialog.isVisible(),false);
 assert.equal(new URL(page.url()).searchParams.has('notice'),false);
 await page.getByRole('searchbox').click();
 await page.locator('a.card').first().click();assert.equal((await observe(page)).signals.dialog,false,'dismissal also clears notice from already-rendered detail links');
 await page.getByRole('link',{name:'Back to results'}).click();
 await page.reload();assert.equal((await observe(page)).signals.dialog,false);
 assert.equal(await page.locator('a.card').count(),4);
}));

