import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFileSync,existsSync,statSync} from 'node:fs';
import {createRequire} from 'node:module';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {buildFixtures} from '../android-lab/fixture_builder.mjs';
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE||'playwright');
const script=readFileSync(new URL('../../ai-browser/app/src/main/assets/sitebrain/content.js',import.meta.url),'utf8');
const cp=process.env.FIXTURE_CORE_CLASSPATH;
if(cp) {
 for(const path of cp.split(':')) assert.ok(existsSync(path),'missing pinned core classpath: '+path);
 const checkout=process.env.FIXTURE_CORE_CHECKOUT,revision=process.env.FIXTURE_CORE_REVISION;
 assert.ok(checkout&&/^[0-9a-f]{40}$/.test(revision||''),'explicit core checkout and full pinned revision required');
 assert.equal(cp.split(':')[0],checkout+'/ai-browser/brain-core/build/classes/kotlin/main');
 const head=spawnSync('git',['-C',checkout,'rev-parse','HEAD'],{encoding:'utf8'});
 assert.equal(head.status,0,head.stderr);assert.equal(head.stdout.trim(),revision,'stale core checkout');
 const diff=spawnSync('git',['-C',checkout,'diff','--exit-code',revision,'--','ai-browser/brain-core/src/main'],{encoding:'utf8'});
 assert.equal(diff.status,0,'pinned core source was modified');
 for(const [source,compiled] of [['perception/SpsParser.kt','perception/SpsParser.class'],['verify/Verifier.kt','verify/Verifier.class'],['engine/LearningOpportunities.kt','engine/LearningOpportunities.class'],['skills/BuiltinSkills.kt','skills/BuiltinSkills.class']]) {
  assert.ok(statSync(cp.split(':')[0]+'/com/appgate/brain/'+compiled).mtimeMs>=statSync(checkout+'/ai-browser/brain-core/src/main/kotlin/com/appgate/brain/'+source).mtimeMs,'stale compiled core '+source);
 }
}
const suffix=cp?' + pinned Kotlin verifier':'';
async function observe(page){await page.evaluate(script);const settled=await page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:3000}));assert.equal(settled.state,'IDLE','real renderer must settle before curriculum selection');return page.evaluate(()=>window.__brainObserve());}
async function execute(page,before,command){const result=await page.evaluate(({before,command})=>window.__brainExecute({...command,expectedDocumentId:before.documentId,expectedUrl:before.url}),{before,command});assert.equal(result.ok,true,JSON.stringify(result));}
function proof(mode,before,after){
 assert.ok(cp,'semantic proof requires explicit pinned core classpath');
 const result=spawnSync(process.env.JAVA_BIN||'/usr/lib/jvm/java-17-openjdk-amd64/bin/java',
 ['-Xmx128m','-cp',cp,fileURLToPath(new URL('./AndroidFixtureSemantics.java',import.meta.url))],
 {input:JSON.stringify({mode,before,after}),encoding:'utf8',timeout:20000,maxBuffer:1024*1024});
 assert.equal(result.status,0,result.stderr||String(result.error));
 return JSON.parse(result.stdout);
}
async function fixture(style,run){
 const files=buildFixtures(); const browser=await chromium.launch();const page=await browser.newPage({viewport:{width:412,height:720}});
 await page.route('**/*',async route=>{
  const u=new URL(route.request().url());assert.equal(u.origin,'https://reports.ryzod.com');
  let key=u.pathname.replace('/practice-fixtures/','');if(key.endsWith('/'))key+='index.html';
  assert.ok(files[key],key);await route.fulfill({body:files[key],contentType:key.endsWith('.js')?'text/javascript':'text/html'});
 });
 try{await page.goto('https://reports.ryzod.com/practice-fixtures/'+style+'/?q=mountain+bike');await run(page);}finally{await browser.close();}
}
async function openFilters(page){const button=page.getByRole('button',{name:'Filters',exact:true});if(await button.count())await button.click();}
const cards=page=>page.locator('a.card').evaluateAll(nodes=>nodes.map(n=>({id:new URL(n.href).searchParams.get('item'),text:n.innerText,price:Number(n.innerText.match(/\$(\d+)/)[1])})));
for(const style of ['classifieds','cars','marketplace','offerup']){
 test(style+' recognized description expands real visible text'+suffix,()=>fixture(style,async page=>{
  await page.locator('a.card').first().click();
  const before=await observe(page);
  const button=page.getByRole('button',{name:/^(Read more|Show more)$/i});assert.equal(await button.count(),1,'recognized generic expand control');
  assert.equal(await button.getAttribute('aria-expanded'),'false');
  await execute(page,before,{cmd:'click',id:before.elements.find(e=>e.name==='Read more').id});const after=await observe(page);
  assert.equal(await button.getAttribute('aria-expanded'),'true');
  assert.ok(after.signals.textLength-before.signals.textLength>40,'real newly visible description exceeds strict verifier floor');
  if(cp){const result=proof('expand',before,after);assert.equal(result.target_skill,'expand_description');assert.equal(result.expand_role,true);assert.equal(result.holds,true);assert.equal(proof('expand',before,before).holds,false,'unchanged text cannot pass');}
 }));
 test(style+' choice facet filters and persists across detail/back'+suffix,()=>fixture(style,async page=>{
  await openFilters(page);const before=await observe(page);
  assert.equal(await page.getByLabel('Condition',{exact:true}).count(),1,'categorical condition control');
  const target=cp?proof('condition',before,before):null;if(target){assert.equal(target.target_skill,'select_facet');assert.equal(target.target_value,'Good','actual generic target must select substantive option');}
  await execute(page,before,{cmd:'select',id:before.elements.find(e=>e.tag==='select'&&/condition/i.test(e.name)).id,option:target?.target_value||'Good'});
  await page.getByRole('button',{name:'Apply filters'}).click();
  assert.equal(new URL(page.url()).searchParams.get('condition'),'good');
  const filtered=await cards(page);assert.equal(filtered.length,2);assert.ok(filtered.every(c=>/Condition: Good/.test(c.text)));
  await openFilters(page);assert.equal(await page.getByLabel('Condition',{exact:true}).inputValue(),'good');const after=await observe(page);
  if(cp){const result=proof('condition',before,after);assert.equal(result.condition_facet,true);assert.equal(result.holds,true);assert.equal(proof('condition',before,before).holds,false);assert.equal(proof('condition',after,after).target_value,'Fair','next generic target must not choose clearing placeholder');}
  await page.locator('a.card').first().click();await page.getByRole('link',{name:'Back to results'}).click();
  assert.equal(new URL(page.url()).searchParams.get('condition'),'good');assert.deepEqual(await cards(page),filtered);
  await openFilters(page);await page.getByLabel('Condition',{exact:true}).selectOption({label:'Fair'});await page.getByRole('button',{name:'Apply filters'}).click();
  assert.ok((await cards(page)).every(c=>/Condition: Fair/.test(c.text)));
  await page.locator('a.card').first().click();assert.match(await page.locator('#detail').innerText(),/Condition: fair\./);
  await page.getByRole('button',{name:'Read more'}).click();assert.match(await page.locator('#full-description').innerText(),/Synthetic condition: fair\./);
 }));
 test(style+' price sort physically orders cards',()=>fixture(style,async page=>{
  const initial=await cards(page);await openFilters(page);const before=await observe(page);const target=cp?proof('sort',before,before):null;if(target){assert.equal(target.target_skill,'sort_results');assert.equal(target.target_order,'Price low to high','actual sort target must not be current recommendation');}await execute(page,before,{cmd:'select',id:before.elements.find(e=>e.tag==='select'&&/sort/i.test(e.name)).id,option:target?.target_order||'Price low to high'});
  await page.waitForURL('**/*sort=price');const sorted=await cards(page);const after=await observe(page);if(cp){assert.equal(proof('sort',before,after).holds,true,'actual builtin sort postcondition');assert.equal(proof('sort',before,before).holds,false,'unchanged sort is not proof');}
  assert.equal(new URL(page.url()).searchParams.get('sort'),'price');assert.deepEqual(sorted.map(c=>c.price),[...sorted.map(c=>c.price)].sort((a,b)=>a-b));
  assert.notDeepEqual(sorted.map(c=>c.id),initial.map(c=>c.id),'sort must change physical order, not just URL');
  assert.deepEqual([...sorted.map(c=>c.id)].sort(),[...initial.map(c=>c.id)].sort());
 }));
 test(style+' scrolling adds bounded distinct keys separately from Load more'+suffix,()=>fixture(style,async page=>{
  const initial=await cards(page);const before=await observe(page);
  await execute(page,before,{cmd:'scroll',dy:900});await page.waitForTimeout(250);
  const appended=await cards(page);assert.equal(appended.length,8,'scroll alone appends next batch');
  assert.deepEqual(appended.slice(0,4),initial);assert.equal(new Set(appended.map(c=>c.id)).size,8);
  const after=await observe(page);
  if(cp){const result=proof('new_results',before,after);assert.equal(result.target_skill,'scroll_results');assert.equal(result.holds,true);assert.equal(proof('new_results',before,before).holds,false);}
  await execute(page,after,{cmd:'click',id:after.elements.find(e=>e.name==='Load more').id});
  const loaded=await cards(page);assert.equal(loaded.length,12);assert.equal(new Set(loaded.map(c=>c.id)).size,12);
  await page.evaluate(()=>window.scrollTo(0,document.documentElement.scrollHeight));await page.waitForTimeout(250);assert.equal((await cards(page)).length,12);
  assert.ok(await page.getByRole('button',{name:'End of results'}).isDisabled());
 }));
}
