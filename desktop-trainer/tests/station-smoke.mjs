// Windows integration gate: actual launchers, duplicate protection, dashboard,
// stop acknowledgement, child exit, saved memory, and results export.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {readFileSync,existsSync} from 'node:fs';
import {dirname,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
const root=dirname(dirname(fileURLToPath(import.meta.url)));
const data=join(root,'data'),connectionFile=join(data,'connection.json');
const before=JSON.parse(readFileSync(connectionFile,'utf8'));
const saved=JSON.parse(readFileSync(join(data,'worker-1','worker.json'),'utf8'));
const paths=JSON.parse(readFileSync(join(root,'.runtime','paths.json'),'utf8').replace(/^\uFEFF/,''));
const runtime=join(root,'.runtime');
const env={...process.env,BRAIN_JAVA:join(runtime,paths.java),PLAYWRIGHT_BROWSERS_PATH:join(runtime,'browsers')};
process.env.PLAYWRIGHT_BROWSERS_PATH=env.PLAYWRIGHT_BROWSERS_PATH;
function command(file,args){
  const child=spawn('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',join(root,file),...args],{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});
  let output='';child.stdout.on('data',s=>{output+=s;});child.stderr.on('data',s=>{output+=s;});
  const done=new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',code=>resolve({code,output}));});
  return {child,done};
}
async function until(fn,timeout){
  const start=Date.now();let last;
  while(Date.now()-start<timeout){try{const value=await fn();if(value)return value;}catch(e){last=e;}
    await new Promise(r=>setTimeout(r,200));}
  throw new Error('Integration condition exceeded its deadline'+(last?': '+last.message:''));
}
const start=()=>command('Run.ps1',['-Workers','1','-Rounds','0','-TrainingCount','2','-EvaluationCount','2','-NoBrowser','-NoPause']);
let station=start();
let connection,browser;
try{
  connection=await until(async()=>{
    const c=JSON.parse(readFileSync(connectionFile,'utf8'));
    if(c.pid===before.pid)return false;
    const status=await fetch(c.url+'/status',{signal:AbortSignal.timeout(1000)}).then(r=>r.json());
    return status.running&&status.workers.length===1?c:false;
  },30000);
  const duplicate=command('Run.ps1',['-Workers','1','-Rounds','1','-NoBrowser','-NoPause']);
  const result=await Promise.race([duplicate.done,new Promise((_,reject)=>setTimeout(()=>reject(new Error('Duplicate launcher hung')),15000))]);
  assert.notEqual(result.code,0);assert.match(result.output,/already running/);
  assert.equal((await fetch(connection.url+'/status').then(r=>r.json())).running,true);
  const {chromium}=createRequire(import.meta.url)('playwright');
  browser=await chromium.launch({headless:true});
  const page=await browser.newPage({viewport:{width:1280,height:960}});
  await page.goto(connection.url);
  await page.locator('#cards .worker').waitFor();
  assert.match(await page.locator('#status').innerText(),/Training is running/);
  await page.screenshot({path:join(data,'dashboard-smoke.png'),fullPage:true});
  // Exercise the real dashboard button.
  await page.locator('#stop').click();
  const stopped=await Promise.race([station.done,new Promise((_,reject)=>setTimeout(()=>reject(new Error('Station did not stop')),20000))]);
  assert.equal(stopped.code,0,stopped.output);
  assert.equal(existsSync(join(data,'runner.lock')),false);
  assert.equal(JSON.parse(readFileSync(join(data,'status.json'),'utf8')).running,false);
  assert.equal(JSON.parse(readFileSync(join(data,'worker-1','memory.json'),'utf8')).schema,1);
  assert.ok(JSON.parse(readFileSync(join(data,'worker-1','worker.json'),'utf8')).completedRounds>=saved.completedRounds);
  assert.throws(()=>process.kill(connection.pid,0));
  // A separate run exercises Control.ps1 without racing server shutdown.
  const previousPid=connection.pid;
  station=start();
  connection=await until(async()=>{
    const c=JSON.parse(readFileSync(connectionFile,'utf8'));
    if(c.pid===previousPid)return false;
    return (await fetch(c.url+'/status',{signal:AbortSignal.timeout(1000)}).then(r=>r.json())).running?c:false;
  },30000);
  const stopping=command('Control.ps1',['-Action','Stop','-NoPause']);
  assert.equal((await stopping.done).code,0);
  const controlled=await Promise.race([station.done,new Promise((_,reject)=>setTimeout(()=>reject(new Error('Control stop timed out')),20000))]);
  assert.equal(controlled.code,0,controlled.output);
  assert.equal(existsSync(join(data,'runner.lock')),false);
  assert.throws(()=>process.kill(connection.pid,0));
  const exportRun=command('Control.ps1',['-Action','Results','-NoPause','-DestinationDirectory',root]);
  assert.equal((await exportRun.done).code,0);
  assert.equal(existsSync(join(root,'Site-Brain-Training-Results.zip')),true);
  console.log('Actual Windows launch, duplicate protection, dashboard, stop, and saved memory passed.');
}finally{
  if(browser)await browser.close();
  if(station.child.exitCode===null){
    if(connection)await fetch(connection.url+'/stop',{method:'POST',headers:{'X-Trainer-Token':connection.token}}).catch(()=>{});
    const kill=spawn('taskkill.exe',['/PID',String(station.child.pid),'/T','/F'],{stdio:'ignore',windowsHide:true});
    kill.on('error',()=>{});
  }
}
