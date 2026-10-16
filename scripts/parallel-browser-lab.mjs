import {createRequire} from 'node:module';
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {pathToFileURL,fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
const source=readFileSync(new URL('../ai-browser/app/src/main/assets/sitebrain/content.js',import.meta.url),'utf8');
const specs=[
 ['close',`<button aria-label="Close" onclick="document.querySelector('#modal').remove()">×</button>`],
 ['noop-close','<button aria-label="Close">×</button>'],
 ['missing-close','<p>No safe control</p>'],
 ['human-wall',`<form><input type="password"><button>Log in</button></form><button aria-label="Close" onclick="document.querySelector('#modal').remove()">×</button>`]
];
export async function runLab({workers=4,rounds=1}={}){
 if(!Number.isInteger(workers)||workers<1||workers>4)throw Error('Workers must be 1..4');
 if(!Number.isInteger(rounds)||rounds<1||rounds>4)throw Error('Rounds must be 1..4');
 const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE||'playwright');
 const start=performance.now();let active=0,peak=0;
 const groups=await Promise.all(Array.from({length:workers},async(_,worker)=>{
  const browser=await chromium.launch();active++;peak=Math.max(peak,active);
  try{
   const rows=[];
   for(let round=0;round<rounds;round++)for(const [id,body]of specs){
    const context=await browser.newContext({viewport:{width:412+worker*100,height:915}});
    try{
     const page=await context.newPage();let external=0;
     const url=`https://worker-${worker}.sim.invalid/${id==='human-wall'?'login':'search'}`;
     await page.route('**/*',route=>{
      if(route.request().url()!==url){external++;return route.abort();}
      return route.fulfill({contentType:'text/html',body:`<!doctype html><title>Synthetic fixture</title><main><h1>Search results</h1></main><section id="modal" role="dialog" aria-label="Notice" style="position:fixed;top:80px;left:10px;width:360px;height:320px;background:white;z-index:50">${body}</section>`});
     });
     await page.goto(url);await page.evaluate(source);
     await page.waitForFunction(()=>window.__brainObserve().settle.state==='IDLE',null,{timeout:5000});
     const before=await page.evaluate(()=>window.__brainObserve());
     const human=before.signals.passwordFields>0&&before.signals.authPath||before.signals.challengeWidget===true;
     let actions=0;
     // Human fixtures are observed only. Non-human targets are grounded afresh in their own document.
     if(!human){
      const close=before.elements.find(e=>e.inDialog&&e.tag==='button'&&e.name==='Close');
      if(close){
       actions++;
       await page.evaluate(({o,id})=>window.__brainExecute({cmd:'dismiss',id,guardMode:'TRAIN',expectedDocumentId:o.documentId,expectedUrl:o.url}),{o:before,id:close.id});
      }
     }
     const closed=await page.evaluate(()=>!document.querySelector('#modal'));
     const verified=actions===1&&closed;
     const passed=external===0&&(id==='close'?verified:!closed&&(id==='human-wall'?human&&actions===0:id==='missing-close'?actions===0:actions===1));
     rows.push({worker,round,case_id:id,human_signal_detected:human,actions,verified_closure:verified,physically_closed:closed,external_requests:external,passed});
    }finally{await context.close();}
   }
   return rows;
  }finally{active--;await browser.close();}
 }));
 const cases=groups.flat();
 return {schema_version:1,source_revision:execFileSync('git',['rev-parse','HEAD'],{cwd:fileURLToPath(new URL('..',import.meta.url)),encoding:'utf8'}).trim(),lab_sha256:createHash('sha256').update(readFileSync(new URL(import.meta.url))).digest('hex'),extractor_sha256:createHash('sha256').update(source).digest('hex'),browser_sessions:workers,peak_active_workers:peak,elapsed_ms:Math.round(performance.now()-start),synthetic_only:true,real_world_validated:false,teacher_requests:0,verified_closures:cases.filter(x=>x.verified_closure).length,false_mastery:cases.filter(x=>x.verified_closure&&(!x.physically_closed||x.case_id!=='close')).length,cases};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const r=await runLab({workers:Number(process.argv[2]||4),rounds:Number(process.argv[4]||1)});if(process.argv[3])writeFileSync(process.argv[3],JSON.stringify(r)+'\n');console.log(JSON.stringify(r));if(r.cases.some(x=>!x.passed))process.exitCode=1;
}
