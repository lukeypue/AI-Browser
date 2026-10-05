import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {randomBytes} from 'node:crypto';
import {cpus,totalmem,freemem} from 'node:os';
import {readFileSync,writeFileSync,renameSync,mkdirSync,existsSync,unlinkSync,readdirSync,openSync,closeSync,statSync} from 'node:fs';
import {dirname,join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const root=dirname(fileURLToPath(import.meta.url));
export function parseOptions(args){
  const result={workers:4,rounds:0,trainingCount:12,evaluationCount:4,noBrowser:false};
  for(let i=0;i<args.length;i++){
    const key={'--workers':'workers','--rounds':'rounds','--training-count':'trainingCount','--evaluation-count':'evaluationCount'}[args[i]];
    if(args[i]==='--no-browser'){result.noBrowser=true;continue;}
    if(!key)throw new Error(`Unknown option: ${args[i]}`);
    const value=args[++i];if(!/^\d+$/.test(value||''))throw new Error(`Invalid number for ${key}`);result[key]=Number(value);
  }
  if(result.workers<1||result.workers>8)throw new Error('Workers must be between 1 and 8.');
  if(result.rounds<0||result.rounds>100000)throw new Error('Rounds must be between 0 and 100000; 0 means run until stopped.');
  if(result.trainingCount<2||result.trainingCount>100)throw new Error('Training count must be between 2 and 100.');
  if(result.evaluationCount<2||result.evaluationCount>12)throw new Error('Evaluation count must be between 2 and 12.');
  return result;
}
export function atomicJson(path,value){
  mkdirSync(dirname(path),{recursive:true});writeFileSync(path+'.tmp',JSON.stringify(value,null,2));renameSync(path+'.tmp',path);
}
export function trimReports(directory,keep=20){
  const names=readdirSync(directory).filter(n=>/^batch-\d+\.json$/.test(n)).sort();
  for(const name of names.slice(0,Math.max(0,names.length-keep)))unlinkSync(join(directory,name));
}
export async function createDashboard(status,stop,token,html){
  let url;
  const server=createServer(async(req,res)=>{
    if(req.headers.host!==new URL(url).host){res.writeHead(403);res.end('Local host required');return;}
    res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');
    if(req.method==='GET'&&req.url==='/'){res.setHeader('Content-Type','text/html; charset=utf-8');res.end(html.replaceAll('__STOP_TOKEN__',token));return;}
    if(req.method==='GET'&&req.url==='/status'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify(status()));return;}
    if(req.method==='POST'&&req.url==='/stop'){
      if(req.headers['x-trainer-token']!==token||(req.headers.origin&&req.headers.origin!==url)){res.writeHead(403);res.end('Stop permission missing');return;}
      stop();res.setHeader('Content-Type','application/json');res.end('{"stopping":true}');return;
    }
    res.writeHead(404);res.end('Not found');
  });
  server.requestTimeout=5000;server.headersTimeout=5000;
  await new Promise((yes,no)=>{server.once('error',no);server.listen(0,'127.0.0.1',yes);});
  url=`http://127.0.0.1:${server.address().port}`;
  return {url,close:()=>new Promise(resolve=>server.close(resolve))};
}
function terminate(child){
  if(!child||!child.pid||child.exitCode!==null)return;
  if(process.platform==='win32')spawn('taskkill.exe',['/PID',String(child.pid),'/T','/F'],{stdio:'ignore',windowsHide:true}).on('error',()=>{});
  else {try{process.kill(-child.pid,'SIGTERM');}catch{child.kill('SIGTERM');}}
}
function launchPage(url){
  if(process.platform==='win32')spawn('rundll32.exe',['url.dll,FileProtocolHandler',url],{stdio:'ignore',windowsHide:true}).on('error',()=>{});
  else if(process.platform==='darwin')spawn('open',[url],{stdio:'ignore'}).on('error',()=>{});
  else {const p=spawn('xdg-open',[url],{stdio:'ignore'});p.on('error',()=>{});}
}
const cpuTotals=()=>cpus().reduce((v,c)=>({idle:v.idle+c.times.idle,total:v.total+Object.values(c.times).reduce((a,b)=>a+b,0)}),{idle:0,total:0});

export async function run(options,trainerRoot=root){
  const root=trainerRoot;
  const data=join(root,'data');mkdirSync(data,{recursive:true});
  const lockPath=join(data,'runner.lock'),stopPath=join(data,'stop-requested');
  if(existsSync(lockPath)){
    const old=JSON.parse(readFileSync(lockPath,'utf8'));
    try{process.kill(old.pid,0);throw new Error('The trainer is already running. Use 3-Stop.cmd before starting another copy.');}
    catch(e){if(e.code!=='ESRCH')throw e;unlinkSync(lockPath);}
  }
  const lock=openSync(lockPath,'wx');writeFileSync(lock,JSON.stringify({pid:process.pid}));closeSync(lock);
  let dashboard,resources;
  try{
  if(existsSync(stopPath))unlinkSync(stopPath);
  const node=process.execPath,java=process.env.BRAIN_JAVA||'java';
  if(!existsSync(join(root,'site-brain-trainer.jar'))){unlinkSync(lockPath);throw new Error('Trainer JAR missing. Extract all files before starting.');}
  const children=new Set();let stopping=false,stopTimer;
  const state={version:'1.1.0',running:true,stopping:false,started:new Date().toISOString(),mode:'Practice websites only',workers:[],resources:{},errors:[]};
  const save=()=>atomicJson(join(data,'status.json'),state);
  function stop(){
    if(stopping)return;stopping=true;state.stopping=true;writeFileSync(stopPath,'stop');save();
    stopTimer=setTimeout(()=>{for(const child of children)terminate(child);},10000);
  }
  const token=randomBytes(24).toString('hex');
  dashboard=await createDashboard(()=>state,stop,token,readFileSync(join(root,'dashboard.html'),'utf8'));
  atomicJson(join(data,'connection.json'),{url:dashboard.url,pid:process.pid,token});
  console.log(`Site Brain trainer: ${dashboard.url}`);
  console.log('Practice is local. No marketplace accounts or paid AI calls are used.');
  if(!options.noBrowser)launchPage(dashboard.url);
  let previousCPU=cpuTotals();
  resources=setInterval(()=>{
    const current=cpuTotals(),delta=current.total-previousCPU.total;
    state.resources={cpuPercent:delta?Math.round(100*(1-(current.idle-previousCPU.idle)/delta)):0,
      memoryUsedGB:Math.round((totalmem()-freemem())/107374182.4)/10,memoryTotalGB:Math.round(totalmem()/107374182.4)/10};
    previousCPU=current;save();
  },2000);
  process.once('SIGINT',stop);process.once('SIGTERM',stop);
  async function worker(number){
    const folder=join(data,`worker-${number}`);mkdirSync(folder,{recursive:true});
    const recordPath=join(folder,'worker.json');
    let completed=existsSync(recordPath)?JSON.parse(readFileSync(recordPath,'utf8')).completedRounds||0:0;
    let failures=0,sessionRounds=0;
    const row={number,phase:'Starting',completedRounds:completed,verifiedPractice:0,latest:null,error:null};state.workers.push(row);save();
    while(!stopping&&(options.rounds===0||sessionRounds<options.rounds)){
      const round=completed+1,reportPath=join(folder,`batch-${String(round).padStart(8,'0')}.json`);
      const stderrPath=join(folder,'worker-errors.log');
      if(existsSync(stderrPath)&&statSync(stderrPath).size>1024*1024)writeFileSync(stderrPath,'');
      const errorFile=openSync(stderrPath,'a');
      const args=['-Xmx768m','-jar',join(root,'site-brain-trainer.jar'),node,join(root,'renderer.mjs'),join(folder,'memory.json'),reportPath,
        String(number*100000+round*100),String(options.trainingCount),String(options.evaluationCount)];
      const child=spawn(java,args,{cwd:root,env:{...process.env,BRAIN_STOP_FILE:stopPath},stdio:['ignore','pipe',errorFile],windowsHide:true,detached:process.platform!=='win32'});
      closeSync(errorFile);children.add(child);
      createInterface({input:child.stdout}).on('line',line=>{
        try{const event=JSON.parse(line);if(event.type==='progress'){row.phase=`${event.phase} ${event.episode}/${event.total}`;save();}}catch{}
      });
      const deadline=setTimeout(()=>terminate(child),(options.trainingCount+2*options.evaluationCount)*60000+60000);
      const exit=await new Promise(resolve=>{child.once('error',e=>resolve({code:-1,error:e.message}));child.once('exit',code=>resolve({code}));});
      clearTimeout(deadline);children.delete(child);
      if(stopping)break;
      if(exit.code!==0||!existsSync(reportPath)){
        failures++;row.error=exit.error||`Practice worker stopped unexpectedly (code ${exit.code}). See its worker-errors.log.`;row.phase='Retrying after an error';save();
        if(failures>=2){row.phase='Stopped after repeated errors';state.errors.push(`Worker ${number}: ${row.error}`);break;}
        await new Promise(resolve=>setTimeout(resolve,2000));continue;
      }
      const report=JSON.parse(readFileSync(reportPath,'utf8'));
      row.latest={baseline:report.baseline,after:report.after_training,compiledSkills:report.compiled_skills,teacherDemonstrations:report.teacher_demonstrations,
        lessonCounts:report.lesson_counts,capabilities:report.compiled_capabilities,eligibleForReview:report.eligible_for_review,browserVersion:report.browser_version};
      row.verifiedPractice+=report.verified_lesson_practice??report.verified_search_practice;row.error=null;failures=0;
      completed++;sessionRounds++;row.completedRounds=completed;row.phase='Batch saved';
      atomicJson(recordPath,{completedRounds:completed});trimReports(folder,20);save();
    }
    row.phase=row.error?'Stopped after errors':'Stopped';save();
  }
  try{await Promise.all(Array.from({length:options.workers},(_,i)=>worker(i+1)));}
  finally{
    stopping=true;
    clearInterval(resources);clearTimeout(stopTimer);for(const child of children)terminate(child);
    state.running=false;state.stopping=false;save();
    if(existsSync(stopPath))unlinkSync(stopPath);
    process.removeListener('SIGINT',stop);process.removeListener('SIGTERM',stop);
  }
  if(state.errors.length)throw new Error(state.errors.join('\n'));
  console.log('Training stopped. Saved reports and practice memory remain in the data folder.');
  }finally{
    clearInterval(resources);
    if(dashboard)await dashboard.close();
    if(existsSync(lockPath))unlinkSync(lockPath);
  }
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  try{await run(parseOptions(process.argv.slice(2)));}catch(error){console.error(error.message);process.exitCode=1;}
}
