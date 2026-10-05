import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
const m=await import('../update.mjs').catch(()=>({}));
function fixture(){
 const root=mkdtempSync(join(tmpdir(),'trainer update ')),stage=mkdtempSync(join(tmpdir(),'trainer staged '));
 mkdirSync(join(root,'data','worker-1'),{recursive:true});mkdirSync(join(root,'.runtime'));
 writeFileSync(join(root,'data','worker-1','memory.json'),'saved learning');writeFileSync(join(root,'.runtime','ready.txt'),'ready');
 writeFileSync(join(root,'runner.mjs'),'old program');writeFileSync(join(stage,'runner.mjs'),'new program');
 const hash=createHash('sha256').update('new program').digest('hex');
 writeFileSync(join(stage,'bundle-manifest.json'),JSON.stringify({version:'1.1.0',files:{'runner.mjs':hash}}));
 return {root,stage};
}
test('an update replaces program files and preserves saved memories and runtime',()=>{
 assert.equal(typeof m.installBundle,'function','Update installation is missing');
 const {root,stage}=fixture();m.installBundle(stage,root);
 assert.equal(readFileSync(join(root,'runner.mjs'),'utf8'),'new program');
 assert.equal(readFileSync(join(root,'data','worker-1','memory.json'),'utf8'),'saved learning');
 assert.equal(readFileSync(join(root,'.runtime','ready.txt'),'utf8'),'ready');
 assert.equal(readFileSync(join(root,'.update-backup','runner.mjs'),'utf8'),'old program');
});
test('damaged updates and packages targeting user data leave the old installation intact',()=>{
 assert.equal(typeof m.installBundle,'function','Update validation is missing');
 for(const filename of ['runner.mjs','data/worker-1/memory.json','../outside.mjs']){
  const {root,stage}=fixture();
  if(filename==='runner.mjs')writeFileSync(join(stage,'runner.mjs'),'damaged');
  else writeFileSync(join(stage,'bundle-manifest.json'),JSON.stringify({version:'1.1.0',files:{[filename]:'a'.repeat(64)}}));
  assert.throws(()=>m.installBundle(stage,root),/damaged|Unexpected/);
  assert.equal(readFileSync(join(root,'runner.mjs'),'utf8'),'old program');
  assert.equal(readFileSync(join(root,'data','worker-1','memory.json'),'utf8'),'saved learning');
 }
});
test('a live trainer blocks installation before any program file is changed',()=>{
 assert.equal(typeof m.installBundle,'function','Running trainer protection is missing');
 const {root,stage}=fixture();writeFileSync(join(root,'data','runner.lock'),JSON.stringify({pid:process.pid}));
 assert.throws(()=>m.installBundle(stage,root),/Stop training/);
 assert.equal(readFileSync(join(root,'runner.mjs'),'utf8'),'old program');
 assert.equal(existsSync(join(root,'.update-backup')),false);
});
test('the browser observation asset in a real bundle installs successfully',()=>{
 const {root,stage}=fixture();const manifest=JSON.parse(readFileSync(join(stage,'bundle-manifest.json'),'utf8'));
 writeFileSync(join(stage,'content.js'),'browser bridge');manifest.files['content.js']=createHash('sha256').update('browser bridge').digest('hex');
 writeFileSync(join(stage,'bundle-manifest.json'),JSON.stringify(manifest));m.installBundle(stage,root);
 assert.equal(readFileSync(join(root,'content.js'),'utf8'),'browser bridge');
});
test('station ownership prevents training and update from overlapping and recovers stale ownership',()=>{
 assert.equal(typeof m.acquireStationLock,'function');const {root,stage}=fixture();
 const release=m.acquireStationLock(root);
 assert.throws(()=>m.installBundle(stage,root),/already|running|updating/);release();
 writeFileSync(join(root,'.station.lock'),JSON.stringify({pid:2147483647}));
 m.installBundle(stage,root);assert.equal(readFileSync(join(root,'runner.mjs'),'utf8'),'new program');
});
