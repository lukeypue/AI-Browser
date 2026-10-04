import {createRequire} from 'node:module';
import {readFileSync,existsSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createInterface} from 'node:readline';
import {createMarket} from './market.mjs';

const directory=dirname(fileURLToPath(import.meta.url));
const stub="window.browser={runtime:{connectNative:()=>{throw new Error('offline practice')}}};";
export async function createBrowserSession() {
  const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE||'playwright');
  const contentPath=process.env.BRAIN_CONTENT_SCRIPT||[resolve(directory,'content.js'),resolve(directory,'../ai-browser/app/src/main/assets/sitebrain/content.js')].find(existsSync);
  if(!contentPath)throw new Error('The app browser observation script is missing. Extract the whole trainer ZIP.');
  const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM||undefined});
  let context,page,market,mode='TRAIN',staleInjected=false,commands=0;
  const render=async()=>{
    await page.goto(market.url,{waitUntil:'load',timeout:5000});
    await page.evaluate(mode=>window.__brainExecute({cmd:'guard',mode}),mode);
  };
  const session={
    get page(){return page;},
    async handle(op,payload){
      if(op==='reset'){
        if(context)await context.close();
        market=createMarket(payload);staleInjected=false;commands=0;mode='TRAIN';
        context=await browser.newContext({viewport:{width:1100,height:900},serviceWorkers:'block'});
        await context.addInitScript({content:stub+'\n'+readFileSync(contentPath,'utf8')});
        await context.route('**/*',async route=>{
          const u=new URL(route.request().url());
          if(u.hostname!==market.host||route.request().method()!=='GET')return route.abort();
          return route.fulfill({status:200,contentType:'text/html',body:market.html()});
        });
        await context.routeWebSocket('**/*',ws=>ws.close());
        page=await context.newPage();page.setDefaultTimeout(5000);
        await render();return {url:market.url,browserVersion:browser.version()};
      }
      if(!page)throw new Error('practice session not initialized');
      switch(op){
        case 'observe':
          await page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:900}));
          return page.evaluate(()=>window.__brainObserve());
        case 'act':{
          commands++;
          if(market.scenario.fault==='stale'&&!staleInjected){staleInjected=true;market.markFault();await render();}
          const r=await page.evaluate(cmd=>window.__brainExecute(cmd),payload);
          const events=await page.evaluate(()=>window.__labEvents.splice(0));
          let changed=false;
          for(const e of events)changed=market.event(e)||changed;
          if(changed)await render();
          return {...r,url:page.url()};
        }
        case 'navigate':commands++;market.navigate(payload.url);await render();return {ok:true,url:page.url()};
        case 'back':commands++;market.event({kind:'back'});await render();return {ok:true,url:page.url()};
        case 'network':mode='TRAIN';await page.evaluate(()=>window.__brainExecute({cmd:'guard',mode:'TRAIN'}));return {ok:true};
        case 'settle':return page.evaluate(()=>window.__brainExecute({cmd:'settle',capMs:900}));
        case 'recover':await render();return {ok:true,url:page.url()};
        case 'truth':return {...market.truth(),rendererCommands:commands,url:page.url(),browserVersion:browser.version()};
        case 'close':await session.close();return {ok:true};
        default:throw new Error('unsupported practice operation');
      }
    },
    async close(){await browser.close();}
  };
  return session;
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const session=await createBrowserSession();
  const lines=createInterface({input:process.stdin,crlfDelay:Infinity});
  try{
    for await(const line of lines){
      let request;
      try{
        request=JSON.parse(line);
        const result=await session.handle(request.op,request.payload||{});
        process.stdout.write(JSON.stringify({id:request.id,ok:true,result})+'\n');
        if(request.op==='close')break;
      }catch(error){process.stdout.write(JSON.stringify({id:request?.id,ok:false,error:String(error.message)})+'\n');}
    }
  }finally{await session.close();}
}
