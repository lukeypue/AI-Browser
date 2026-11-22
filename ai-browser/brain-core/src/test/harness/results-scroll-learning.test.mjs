import {createRequire} from 'node:module';
import {readFileSync,mkdtempSync,cpSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,delimiter} from 'node:path';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import assert from 'node:assert/strict';
import {test,before,after} from 'node:test';
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE||'playwright');
const reader=readFileSync(new URL('../../../../app/src/main/assets/sitebrain/content.js',import.meta.url),'utf8');
let browser;
before(async()=>{browser=await chromium.launch({executablePath:process.env.CHROMIUM||undefined});});after(async()=>{await browser?.close();});
function html(variant,{append=true,noOp=false,human=false}={}) {
 const card=i=>`<article class="listing"><a href="/item/${variant}-${i}"><h2>Synthetic inventory item ${variant}-${i}</h2><p>$100</p></a></article>`;
 const tag=variant%2?'div':'section';
 return `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;height:1600px}main{width:350px}h1{font-size:18px}#results{height:${variant%2?360:320}px;overflow-y:auto}article{height:220px}article a{display:block;height:200px}aside{position:fixed;right:0;top:0;width:100px;height:650px;overflow-y:auto}aside p{height:2400px}</style><main><h1>3 synthetic results</h1>${human?'<input type="password"><button>Log in</button>':`<${tag} id="results">${[1,2,3].map(card).join('')}</${tag}>`}</main><aside><p>Unrelated synthetic sidebar</p></aside><script>window.fixture={loaded:false};const panel=document.querySelector('#results');if(panel){if(${noOp})panel.scrollBy=()=>{};panel.addEventListener('scroll',()=>{if(${append}&&!window.fixture.loaded&&panel.scrollTop>100){window.fixture.loaded=true;panel.insertAdjacentHTML('beforeend',${JSON.stringify([4,5,6].map(card).join(''))});}});}</script>`;
}
async function trial(memory,mode,variant,options={}) {
 const page=await browser.newPage({viewport:{width:480,height:800}});let child,done,chain=Promise.resolve(),protocolError,stderr='';
 try{
  await page.route('**/*',r=>new URL(r.request().url()).hostname==='teacher-browser.fixture.invalid'?r.fulfill({contentType:'text/html',body:html(variant,options)}):r.abort());await page.addInitScript(reader);await page.goto('https://teacher-browser.fixture.invalid/search');
  const root=process.env.FIXTURE_CORE_CHECKOUT;assert.ok(root);assert.ok(process.env.FIXTURE_CORE_CLASSPATH);
  const cp=process.env.FIXTURE_CORE_CLASSPATH+delimiter+join(root,'ai-browser/brain-core/build/classes/kotlin/lab');
  child=spawn(process.env.JAVA_BIN||'/usr/lib/jvm/java-17-openjdk-amd64/bin/java',['-Xmx256m','-cp',cp,'com.appgate.brain.lab.ResultsScrollClinicMainKt',mode,memory],{stdio:['pipe','pipe','pipe']});child.stderr.on('data',b=>{stderr=(stderr+b).slice(-4000);});
  createInterface({input:child.stdout}).on('line',line=>{chain=chain.then(async()=>{
   if(protocolError)return;assert.ok(line.length<1048576);const m=JSON.parse(line);if(m.kind==='DONE'){done=m;return;}assert.equal(m.kind,'RPC');let reply={id:m.id,ok:true};
   switch(m.method){case 'observe':reply.observation=await page.evaluate(()=>window.__brainObserve());break;case 'act':Object.assign(reply,await page.evaluate(c=>window.__brainExecute(c),m.command));break;case 'settle':await page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:1000}));break;case 'network':case 'recover':break;case 'navigate':if(m.url!==page.url())reply.ok=false;break;case 'back':reply.ok=false;break;default:throw Error('unsupported fixture RPC');}child.stdin.write(JSON.stringify(reply)+String.fromCharCode(10));
  }).catch(e=>{protocolError=e;child.kill('SIGKILL');});});
  const exit=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{child.kill('SIGKILL');reject(Error('fixture student timeout'));},60000);child.once('error',e=>{clearTimeout(timer);reject(e);});child.once('close',code=>{clearTimeout(timer);resolve(code);});});await chain;if(protocolError)throw protocolError;assert.equal(exit,0,stderr);assert.ok(done);
  const oracle=await page.evaluate(()=>({new_items:window.fixture.loaded,panel:document.querySelector('#results')?.scrollTop||0,root:window.scrollY,sidebar:document.querySelector('aside').scrollTop}));return {...done,...oracle};
 }finally{if(child&&!child.killed)child.kill('SIGKILL');await page.close();}
}
test('whole engine learns new results, retains the lesson across JVM/layout changes, and preserves old root failure holds',async()=>{
 const root=mkdtempSync(join(tmpdir(),'results-scroll-'));const proof=[];
 try{
  const trained=join(root,'trained');const first=await trial(trained,'training',0);proof.push({case:'training',...first});assert.equal(first.new_items,true);assert.equal(first.verified_scroll,true);assert.equal(first.new_lesson,true);assert.equal(first.lesson_after_reload,true);assert.equal(first.teacher_calls,0);assert.equal(first.old_root_hold_before,1);assert.equal(first.old_root_hold_after_reload,1);assert.equal(first.root,0);assert.equal(first.sidebar,0);
  for(const variant of [1,2]){const memory=join(root,'retained-'+variant);cpSync(trained,memory,{recursive:true});const row=await trial(memory,'evaluation',variant);proof.push({case:'retained-'+variant,...row});assert.equal(row.lesson_before,true);assert.equal(row.new_lesson,false);assert.equal(row.verified_scroll,true);assert.equal(row.new_items,true);assert.equal(row.teacher_calls,0);assert.equal(row.old_root_hold_after_reload,1);assert.equal(row.root,0);assert.equal(row.sidebar,0);}
  for(const [name,options]of [['end',{append:false}],['noop',{noOp:true}],['human',{human:true}]]){const row=await trial(join(root,name),'training',3,options);proof.push({case:name,...row});assert.equal(row.new_lesson,false);assert.equal(row.verified_scroll,false);assert.equal(row.new_items,false);assert.equal(row.teacher_calls,0);if(name==='human')assert.equal(row.actions,0);}
  console.log('RESULTS_SCROLL_PROOF '+JSON.stringify(proof));
 }finally{rmSync(root,{recursive:true,force:true});}
});
