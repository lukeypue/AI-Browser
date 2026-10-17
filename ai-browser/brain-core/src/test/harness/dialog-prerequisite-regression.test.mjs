import assert from "node:assert/strict";
import {createRequire} from "node:module";
import {readFileSync,writeFileSync} from "node:fs";
import {resolve} from "node:path";
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||"playwright");
const script=readFileSync(resolve(import.meta.dirname,"../../../../app/src/main/assets/sitebrain/content.js"),"utf8");
const fixture=resolve(import.meta.dirname,"../fixtures/dialog_heading_matrix.observation.json");
const details="<p>$6,800</p><dl><dt>Condition</dt><dd>Used</dd><dt>Year</dt><dd>2010</dd><dt>Mileage</dt><dd>150,500</dd></dl><p>Description: Synthetic vehicle in working condition with a tow package, clean interior, documented maintenance, good tires, and a spare wheel. This paragraph is fixture evidence only, describes one item, and contains enough visible primary text to exercise the existing detail threshold.</p>";
const matrix=[
 {id:"item_h2",body:"<h2>Synthetic vehicle</h2>"+details,detail:true},
 {id:"item_h3",body:"<h3>Synthetic vehicle</h3>"+details,detail:true},
 {id:"item_role_heading_2",body:'<div role="heading" aria-level="2">Synthetic vehicle</div>'+details,detail:true},
 {id:"ordinary_consent",body:"<h2>Privacy choices</h2><p>Choose whether this synthetic page may remember display preferences. These options do not describe a listing or any item facts. You may dismiss this ordinary preferences dialog.</p><button>Accept preferences</button>",detail:false},
 {id:"hidden_heading",body:'<h2 style="display:none">Hidden listing title</h2>'+details,detail:false},
 {id:"background_heading",body:details,background:"<h1>Unrelated background title</h1><p>$9,900</p>",detail:false},
 {id:"small_consent",body:"<h2>Privacy choices</h2><p>Choose optional preferences for this synthetic page.</p>",background:"<h2>Background vehicle</h2>"+details,small:true,detail:false},
 {id:"short_consent",body:"<p>Preferences</p>",background:"<h2>Background vehicle</h2>"+details,small:true,detail:false},
 {id:"login",body:'<h2>Sign in</h2><p>Log in to your account</p><input type="password"><button>Log in</button>',detail:false},
 {id:"challenge",body:'<h2>Verify you are human</h2><p>Complete the CAPTCHA verification</p><div class="g-recaptcha" data-sitekey="synthetic-fixture-only" style="width:250px;height:80px">Verification</div>',detail:false}
];
const output={};let failures=0;
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM||undefined,args:["--no-sandbox"]});
try{
 const context=await browser.newContext({viewport:{width:412,height:915}});
 await context.route("**/*",route=>route.fulfill({status:200,contentType:"text/html",body:"<html></html>"}));
 const page=await context.newPage();
 for(const row of matrix){
  await page.goto("https://fixture.market/search");
  const style=row.small?"position:fixed;left:50px;top:300px;width:260px;height:140px;":"position:fixed;inset:20px;";
  await page.setContent('<html><body><main>'+(row.background||"<p>Background recommendations</p><p>$9,900</p>")+'</main><section role="dialog" aria-modal="true" style="'+style+'background:white;padding:18px;z-index:10;overflow:auto">'+row.body+'<button aria-label="Close">Close</button></section></body></html>');
  await page.evaluate('window.browser={runtime:{connectNative:()=>{throw new Error("no native host")}}};\n'+script);
  const observation=await page.evaluate(()=>window.__brainObserve());
  delete observation.documentId;delete observation.ts;observation.settle={state:"IDLE"};
  output[row.id]=observation;
  try{
   assert.equal(observation.signals.detailHint,row.detail,row.id);
   assert.equal(observation.detailText.length>0,row.detail,row.id+" detail text");
   if(row.id==="login")assert.equal(observation.signals.passwordFields,1);
   if(row.id==="challenge")assert.equal(observation.signals.challengeWidget,true);
  }catch(error){failures++;console.error(error.message);}
  console.log(JSON.stringify({fixture:row.id,detailHint:observation.signals.detailHint,dialog:observation.signals.dialog,detailTextLength:observation.detailText.length,dialogCoverage:observation.signals.dialogCoverage}));
 }
 if(!failures&&process.argv.includes("--generate")){
  writeFileSync(fixture,JSON.stringify(output,null,1)+"\n");
  console.log("Generated synthetic parser fixture: "+fixture);
 }
}finally{await browser.close();}
assert.equal(failures,0,"dialog heading/scope regressions");
console.log("dialog heading/scope browser regressions OK");
