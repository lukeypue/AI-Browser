import assert from 'node:assert/strict';
import {test} from 'node:test';
import {existsSync,readFileSync} from 'node:fs';
test('fixture builder exists and defines four isolated synthetic styles', async()=>{
 const path=new URL('../android-lab/fixture_builder.mjs',import.meta.url);
 assert.ok(existsSync(path),'fixture builder must exist');
 const {buildFixtures}=await import(path);
 const files=buildFixtures();
 for(const style of ['classifieds','cars','marketplace','offerup']) {
  assert.ok(files[style+'/index.html'].includes('Synthetic Android Lab'));
 }
 assert.throws(()=>buildFixtures({origin:'http://unsafe.test'}));
});

import {createRequire} from 'node:module';
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright');
for(const style of ['classifieds','cars','marketplace','offerup']) test(style+' search/filter/pagination/detail effects are physical',async()=>{
 const {buildFixtures}=await import('../android-lab/fixture_builder.mjs');
 const files=buildFixtures(); const browser=await chromium.launch(); const page=await browser.newPage();
 const origin='https://reports.ryzod.com';
 await page.route('**/*',async route=>{
  const u=new URL(route.request().url());
  assert.equal(u.origin,origin,'no external network');
  const rel=u.pathname.replace('/practice-fixtures/','');
  const key=rel.endsWith('/')?rel+'index.html':rel;
  assert.ok(files[key],key); await route.fulfill({body:files[key],contentType:key.endsWith('.js')?'text/javascript':'text/html'});
 });
 try {
  await page.goto(origin+'/practice-fixtures/'+style+'/');
  await page.getByRole('searchbox').fill('mountain bike');
  await page.getByRole('button',{name:'Search',exact:true}).click();
  await page.waitForURL('**/*q=mountain*');
  assert.equal(await page.locator('a.card').count(),4);
  await page.evaluate(readFileSync(new URL('../../ai-browser/app/src/main/assets/sitebrain/content.js',import.meta.url),'utf8'));
  const obs=await page.evaluate(()=>window.__brainObserve());
  assert.equal(obs.items.length,4,'real perception recognizes fixture cards');
  assert.ok(obs.elements.some(e=>e.role==='searchbox'||e.name==='Search'),'real perception recognizes search');
  assert.match(await page.locator('a.card').first().innerText(),/mountain bike/i);
  if(style==='marketplace'||style==='offerup') await page.getByRole('button',{name:'Filters',exact:true}).click();
  await page.getByLabel('Maximum price').fill('150');
  await page.getByRole('button',{name:'Apply filters'}).click();
  assert.equal(await page.locator('a.card').count(),2);
  if(style==='marketplace'||style==='offerup') await page.getByRole('button',{name:'Filters',exact:true}).click();
  await page.getByLabel('Maximum price').fill('');
  await page.getByRole('button',{name:'Apply filters'}).click();
  await page.getByRole('button',{name:'Load more'}).click();
  assert.equal(await page.locator('a.card').count(),8);
  await page.locator('a.card').nth(2).click();
  assert.ok(await page.getByRole('heading',{name:'Synthetic listing details'}).isVisible());
  await page.evaluate(readFileSync(new URL('../../ai-browser/app/src/main/assets/sitebrain/content.js',import.meta.url),'utf8'));
  const detailObs=await page.evaluate(()=>window.__brainObserve());
  assert.equal(detailObs.signals.detailHint,true,'real perception recognizes physical detail page');
  await page.getByRole('button',{name:'Show description'}).click();
  assert.ok(await page.getByText('Synthetic condition: good.').isVisible());
  await page.getByRole('link',{name:'Back to results'}).click();
  assert.equal(await page.locator('a.card').count(),4);
  assert.equal(await page.locator('input[type=password]').count(),0);
 } finally {await browser.close();}
});
