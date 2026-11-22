import {createRequire} from 'node:module';
import {readFileSync,mkdtempSync,cpSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,delimiter} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {test,before,after} from 'node:test';
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE||'playwright');
const reader=readFileSync(new URL('../../../../app/src/main/assets/sitebrain/content.js',import.meta.url),'utf8');
const program=fileURLToPath(new URL('../fixtures/teacher-correction-program.json',import.meta.url));
const recordedHash='7e7d32d4730ad3d9d78494c05a2926083314345bd5841c7cdc7213b218826dec';
const host='teacher-browser.fixture.invalid',base='https://'+host+'/';
let browser;
before(async()=>{assert.equal(createHash('sha256').update(readFileSync(program)).digest('hex'),recordedHash);browser=await chromium.launch({executablePath:process.env.CHROMIUM||undefined});});
after(async()=>{await browser?.close();});
function html(variant,noOp,human){return `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Synthetic learning fixture</title><style>body{font:18px sans-serif}input,button,[role=tab]{display:block;padding:12px;margin:8px}a.card{display:block;height:110px}</style><main><h1>Synthetic inventory</h1>${human?'<input type="password" autocomplete="current-password"><button>Log in</button>':`<${variant%2?'div':'button'} role="tab" aria-selected="false" tabindex="0" id="scope">Browse inventory</${variant%2?'div':'button'}><form><label>${variant%2?'Search listings':'Search inventory'}<input type="search" name="q"></label><button type="submit">Search</button></form><section id="results"></section>`}</main><script>
window.fixture={selected:false,committed:'',submissions:0};
const tab=document.querySelector('#scope'),form=document.querySelector('form');
if(tab)tab.onclick=()=>{if(${noOp})return;window.fixture.selected=true;tab.setAttribute('aria-selected','true');history.pushState({},'', '/inventory');};
if(form)form.onsubmit=e=>{e.preventDefault();window.fixture.submissions++;if(!window.fixture.selected)return;const q=form.querySelector('input').value;window.fixture.committed=q;history.pushState({},'', '/search?q='+encodeURIComponent(q));document.querySelector('#results').innerHTML=[1,2,3].map(i=>'<a class="card" href="/item/'+i+'"><h2>'+q+' synthetic item '+i+'</h2><p>$100</p></a>').join('');};
if(${variant%2===1}&&tab)document.querySelector('main').append(tab);
</script>`;}
async function trial(memory,mode,variant,query,{noOp=false,human=false}={}){
 const page=await browser.newPage({viewport:{width:480,height:800}});
 let child,done,chain=Promise.resolve(),stderr='',protocolError;
 try{
  await page.route('**/*',route=>new URL(route.request().url()).hostname===host?route.fulfill({contentType:'text/html',body:html(variant,noOp,human)}):route.abort());
  await page.addInitScript(reader);await page.goto(base);
  const root=process.env.FIXTURE_CORE_CHECKOUT;assert.ok(root,'explicit core checkout required');
  assert.ok(process.env.FIXTURE_CORE_CLASSPATH,'compiled current core required');
  const cp=process.env.FIXTURE_CORE_CLASSPATH+delimiter+join(root,'ai-browser/brain-core/build/classes/kotlin/lab');
  child=spawn(process.env.JAVA_BIN||'/usr/lib/jvm/java-17-openjdk-amd64/bin/java',['-Xmx256m','-cp',cp,'com.appgate.brain.lab.BrowserTeacherClinicMainKt',mode,memory,query,program],{stdio:['pipe','pipe','pipe']});
  child.stderr.on('data',b=>{stderr=(stderr+b).slice(-4000);});
  const lines=createInterface({input:child.stdout});
  lines.on('line',line=>{chain=chain.then(async()=>{
   assert.ok(line.length<1024*1024,'bounded student message');const message=JSON.parse(line);
   if(protocolError)return;if(message.kind==='DONE'){done=message;return;}
   assert.equal(message.kind,'RPC');let reply={id:message.id,ok:true};
   switch(message.method){
    case 'observe':reply.observation=await page.evaluate(()=>window.__brainObserve());break;
    case 'act':reply=Object.assign(reply,await page.evaluate(c=>window.__brainExecute(c),message.command));break;
    case 'settle':await page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:1000}));break;
    case 'network':case 'recover':break;
    case 'navigate':if(message.url!==page.url())reply={id:message.id,ok:false};break;
    case 'back':reply={id:message.id,ok:false};break;
    default:throw Error('unknown student method');
   }
   child.stdin.write(JSON.stringify(reply)+'\n');
  }).catch(error=>{protocolError=error;child.kill('SIGKILL');});});
  const exit=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{child.kill('SIGKILL');reject(Error('browser student timeout'));},60000);child.once('error',e=>{clearTimeout(timer);reject(e);});child.once('close',code=>{clearTimeout(timer);resolve(code);});});
  await chain;if(protocolError)throw protocolError;assert.equal(exit,0,'student failed: '+stderr);assert.ok(done,'student completion required');
  const oracle=await page.evaluate(q=>window.fixture.committed===q&&window.fixture.selected,query);
  return {...done,oracle};
 }finally{if(child&&!child.killed)child.kill('SIGKILL');await page.close();}
}
test('recorded actual AI procedure acquires browser prerequisite and survives reload/layout changes with teacher disabled',async()=>{
 const root=mkdtempSync(join(tmpdir(),'teacher-browser-'));
 try{
  const cold=await trial(join(root,'cold'),'evaluation',0,'synthetic-cold-browser');assert.equal(cold.oracle,false);assert.equal(cold.verified,false);
  const training=await trial(join(root,'trained'),'training',0,'synthetic-trained-browser');
  assert.equal(training.oracle,true);assert.equal(training.verified,true);assert.equal(training.teacher_calls,2);assert.ok(training.compiled_skills>0);
  for(const variant of [1,2]){const memory=join(root,'retained-'+variant);cpSync(join(root,'trained'),memory,{recursive:true});const retained=await trial(memory,'evaluation',variant,'synthetic-heldout-browser-'+variant);assert.equal(retained.oracle,true);assert.equal(retained.verified,true);assert.equal(retained.teacher_calls,0);}
  const ablated=join(root,'ablated');cpSync(join(root,'trained'),ablated,{recursive:true});const removed=await trial(ablated,'ablate',3,'synthetic-ablated-browser');assert.equal(removed.oracle,false);assert.equal(removed.verified,false);
  const noOp=join(root,'noop');cpSync(join(root,'trained'),noOp,{recursive:true});const negative=await trial(noOp,'evaluation',4,'synthetic-negative-browser',{noOp:true});assert.equal(negative.oracle,false);assert.equal(negative.verified,false);
  const human=await trial(join(root,'human'),'training',5,'synthetic-human-browser',{human:true});assert.equal(human.teacher_calls,0);assert.equal(human.actions,0);assert.equal(human.verified,false);
 }finally{rmSync(root,{recursive:true,force:true});}
});
