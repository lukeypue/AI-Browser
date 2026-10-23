import {createRequire} from 'node:module';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE||'playwright');
const source=readFileSync(process.argv[3] || new URL('../ai-browser/app/src/main/assets/sitebrain/content.js',import.meta.url),'utf8');
const digest=x=>createHash('sha256').update(x).digest('hex');
const cards='<main><h1>Search listings</h1>'+Array.from({length:4},(_,i)=>`<a style="display:block;height:100px" href="/item/${100000+i}">Mountain bike ${i} $${100+i}</a>`).join('')+'<p>'+('Synthetic marketplace listing details. '.repeat(30))+'</p></main>';
const modal=body=>`<section id="modal" role="dialog" aria-label="Notice" style="position:fixed;top:80px;left:10px;width:360px;height:320px;background:white;z-index:50"><p>Optional notice</p>${body}</section>`;
const close=label=>`<button onclick="document.querySelector('#modal').remove()">${label}</button>`;
const specs=[
 ['train-close','TRAIN','CLOSE',412,modal(close('Close'))],
 ['train-not-now','TRAIN','CLOSE',412,modal(close('Not now'))],
 ['small-modal','HELD_OUT','CLOSE',412,modal(close('No thanks'))],
 ['wide-modal','HELD_OUT','CLOSE',1280,modal(close('Close'))],
 ['nested-close','HELD_OUT','CLOSE',412,modal('<section><div>'+close('Close')+'</div><label><input type="checkbox">Optional tips</label></section>')],
 ['icon-close','HELD_OUT','CLOSE',412,modal(`<button aria-label="Close" onclick="document.querySelector('#modal').remove()"><span>×</span></button>`)],
 ['noop-close','HELD_OUT','NOOP',412,modal('<button onclick="window.acknowledged=true">Close</button>')],
 ['missing-close','HELD_OUT','MISSING',412,'<header><button>Close</button></header>'+modal('<p>No interactive control available</p>')],
 ['header-chrome','HELD_OUT','CHROME',412,'<header style="position:fixed;top:0;width:100%;height:400px;background:white;z-index:20"><button>Close</button></header>'],
 ['offscreen-dialog','HELD_OUT','CHROME',412,'<section role="dialog" style="position:fixed;top:-2000px;height:100px"><button>Close</button></section>'],
 ['login-wall','HELD_OUT','HUMAN',412,modal('<form><label>Password<input type="password"></label><button>Log in</button></form>')],
 ['captcha-wall','HELD_OUT','HUMAN',412,modal('<div class="g-recaptcha" style="width:280px;height:120px">Human verification</div>')]
];
const browser=await chromium.launch({executablePath:process.env.CHROMIUM||undefined});
async function open(spec){
 const [id,split,expected,width,body]=spec;
 const host=split==='TRAIN'?'dialog-train.sim.invalid':`dialog-${id}.sim.invalid`;
 const page=await browser.newPage({viewport:{width,height:915}});
 await page.route('**/*',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><title>Synthetic marketplace</title>'+cards+body}));
 await page.goto(`https://${host}/${expected==='HUMAN'&&id==='login-wall'?'login':'search'}`);
 await page.evaluate(source);await page.waitForFunction(()=>window.__brainObserve().settle.state==='IDLE',null,{timeout:5000});return page;
}
const cases=[];
try{
 for(const spec of specs){
  let page=await open(spec);const before=await page.evaluate(()=>window.__brainObserve());const initial_closed=await page.evaluate(()=>!document.querySelector('#modal'));await page.close();
  const transitions=[];
  if(spec[2]!=='HUMAN')for(const control of before.elements.filter(e=>e.tag==='button')){
   page=await open(spec);const observed=await page.evaluate(()=>window.__brainObserve());
   const fresh=observed.elements.find(e=>e.id===control.id);if(!fresh)throw Error('unstable fixture target IDs');
   const inside=!!fresh.inDialog;
   const result=await page.evaluate(({o,id})=>window.__brainExecute({cmd:'dismiss',id,guardMode:'TRAIN',expectedDocumentId:o.documentId,expectedUrl:o.url}),{o:observed,id:fresh.id});
   await page.waitForFunction(()=>window.__brainObserve().settle.state==='IDLE',null,{timeout:5000});
   const after=await page.evaluate(()=>window.__brainObserve());
   const closed=await page.evaluate(()=>!document.querySelector('#modal'));
   transitions.push({id:control.id,inside,ok:result.ok!==false,after,closed});await page.close();
  }
  cases.push({id:spec[0],split:spec[1],expected:spec[2],initial_closed,before,transitions});
 }
}finally{await browser.close();}
const report={schema_version:1,extractor_sha256:digest(source),manifest_sha256:digest(cases.map(x=>x.id+'|'+x.split+'|'+x.expected).join('\n')),cases};
const target=process.argv[2];if(!target)throw Error('Output path required');mkdirSync(new URL('../ai-browser/brain-core/src/lab/fixtures/',import.meta.url),{recursive:true});writeFileSync(target,JSON.stringify(report)+'\n');
console.log(JSON.stringify({cases:cases.length,manifest_sha256:report.manifest_sha256,extractor_sha256:report.extractor_sha256}));
