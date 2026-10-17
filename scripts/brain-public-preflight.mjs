#!/usr/bin/env node
// Public availability only. No actions, credentials, persisted browser state, or raw observations.
import {createHash} from 'node:crypto';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {dirname,resolve} from 'node:path';
const SITES = Object.freeze([
  ['ksl_classifieds','https://classifieds.ksl.com/'],
  ['ksl_cars','https://cars.ksl.com/'],
  ['facebook_marketplace','https://www.facebook.com/marketplace/'],
  ['offerup','https://offerup.com/']
]);
const STATUSES=['AVAILABLE','AUTH_REQUIRED','HUMAN_REQUIRED','ACCESS_BLOCKED','UNAVAILABLE'];
const LIMITS={searchboxes:380,controls:380,items:120,dialogs:380};
const hash=value=>createHash('sha256').update(value).digest('hex');
const zero=()=>({searchboxes:0,controls:0,items:0,dialogs:0});
function exactKeys(value,keys) {
  if(!value || typeof value!=='object' || Array.isArray(value) || Object.keys(value).sort().join('|')!==[...keys].sort().join('|')) throw Error('Invalid public report');
}
export function validateReport(rows) {
  if(!Array.isArray(rows)||rows.length!==4)throw Error('Invalid public report');
  const seen=new Set();
  for(const row of rows) {
    exactKeys(row,['siteId','status','counts','durationMs','sourceHash','extractorHash']);
    if(!SITES.some(([id])=>id===row.siteId)||seen.has(row.siteId)||!STATUSES.includes(row.status))throw Error('Invalid public report');
    seen.add(row.siteId); exactKeys(row.counts,Object.keys(LIMITS));
    for(const [key,max] of Object.entries(LIMITS)) if(!Number.isInteger(row.counts[key])||row.counts[key]<0||row.counts[key]>max)throw Error('Invalid public report');
    if(!Number.isInteger(row.durationMs)||row.durationMs<0||row.durationMs>30000)throw Error('Invalid public report');
    for(const key of ['sourceHash','extractorHash'])if(!/^[a-f0-9]{64}$/.test(row[key]))throw Error('Invalid public report');
  }
  return rows;
}
// This entire function executes in-page: raw titles, URLs, names and listing text never cross the browser boundary.
export function observeInPage() {
  const empty={searchboxes:0,controls:0,items:0,dialogs:0};
  const text=(document.body?.innerText||'').slice(0,6000);
  if(/access denied|request blocked|permission denied|temporarily blocked/i.test(text))return {status:'ACCESS_BLOCKED',counts:empty};
  if(/captcha|verify (that )?you are human|security check|checking your browser|challenge/i.test(location.pathname+' '+text))return {status:'HUMAN_REQUIRED',counts:empty};
  if(document.querySelector('input[type=password]')||/\/(login|signin|sign-in|auth|checkpoint)(\/|$)/i.test(location.pathname))return {status:'AUTH_REQUIRED',counts:empty};
  const obs=window.__brainObserve();
  const s=obs.signals||{};
  let status='AVAILABLE';
  if(s.challengeWidget||s.challengePhrase||s.challengePath)status='HUMAN_REQUIRED';
  else if(s.passwordFields>0||s.authPath||s.authPhrases)status='AUTH_REQUIRED';
  else if(s.errorHint||(!obs.elements?.length&&!obs.items?.length))status='UNAVAILABLE';
  if(status!=='AVAILABLE')return {status,counts:empty};
  const elements=obs.elements||[];
  return {status,counts:{
    searchboxes:Math.min(380,elements.filter(e=>e.type==='search'||e.role==='searchbox'||(e.tag==='input'&&/search/i.test((e.name||'')+' '+(e.placeholder||'')))).length),
    controls:Math.min(380,elements.length),
    items:Math.min(120,(obs.items||[]).length),
    dialogs:Math.min(380,(obs.regions||[]).filter(r=>r.role==='DIALOG').length)
  }};
}
export async function runPreflight({browser,extractor,sourceHash}) {
  const extractorHash=hash(extractor);
  const rows=await Promise.all(SITES.map(async([siteId,url])=>{
    const start=performance.now();let context,timer,stopped=false;
    let result={status:'UNAVAILABLE',counts:zero()};
    try {
      const work=async()=>{
        context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
        if(stopped){await context.close();return;}
        let documents=0;
        await context.route('**/*',async route=>{
          const req=route.request();
          if(stopped||req.method()!=='GET')return route.abort();
          if(req.isNavigationRequest()||req.resourceType()==='document') {
            documents++;
            if(documents>1)return route.abort();
          }
          return route.continue();
        });
        await context.routeWebSocket('**/*',socket=>socket.close());
        const page=await context.newPage();
        page.setDefaultTimeout(30000);
        const response=await page.goto(url,{waitUntil:'domcontentloaded',timeout:30000});
        const status=response?.status()||0;
        if(status===401){stopped=true;return {status:'AUTH_REQUIRED',counts:zero()};}
        if(status===403||status===429){stopped=true;return {status:'ACCESS_BLOCKED',counts:zero()};}
        if(status<200||status>=400){stopped=true;return {status:'UNAVAILABLE',counts:zero()};}
        await page.addScriptTag({content:extractor});
        const aggregate=await page.evaluate(observeInPage);
        stopped=true;
        return aggregate;
      };
      result=await Promise.race([work(),new Promise((_,reject)=>{timer=setTimeout(()=>{stopped=true;reject(Error('Public probe timeout'));},30000);})])||result;
    }catch{stopped=true;}
    finally{clearTimeout(timer);if(context)await context.close().catch(()=>{});}
    return {siteId,...result,durationMs:Math.min(30000,Math.max(0,Math.round(performance.now()-start))),sourceHash,extractorHash};
  }));
  return validateReport(rows);
}
async function main() {
  const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
  const extractor=await readFile(resolve(root,'ai-browser/app/src/main/assets/sitebrain/content.js'),'utf8');
  const sourceHash=hash(await readFile(fileURLToPath(import.meta.url)));
  const modulePath=process.env.PLAYWRIGHT_MODULE||'/opt/ai-browser-dev/browser-harness/node_modules/playwright/index.mjs';
  const {chromium}=await import(pathToFileURL(modulePath.endsWith('.mjs')?modulePath:resolve(modulePath,'index.mjs')).href);
  const browser=await chromium.launch({headless:true});
  try {
    const rows=await runPreflight({browser,extractor,sourceHash});
    await mkdir('/root/projects/ai-browser-live-verification',{recursive:true});
    await writeFile('/root/projects/ai-browser-live-verification/public-preflight.json',JSON.stringify(validateReport(rows),null,2)+'\n',{mode:0o600});
    process.stdout.write(JSON.stringify(rows)+'\n');
  }finally{await browser.close();}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(()=>{process.stderr.write('Public preflight failed; no raw diagnostics retained.\n');process.exitCode=1;});
