import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,readFileSync,writeFileSync,readdirSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
const m=await import('../runner.mjs').catch(()=>({}));
test('a failed startup releases its runner lock',async()=>{
  const dir=mkdtempSync(join(tmpdir(),'brain failed startup '));
  writeFileSync(join(dir,'site-brain-trainer.jar'),'fixture');
  await assert.rejects(()=>m.run(m.parseOptions(['--no-browser']),dir),/ENOENT/);
  assert.equal(existsSync(join(dir,'data','runner.lock')),false);
});
test('worker configuration is bounded and invalid flags fail clearly',()=>{
  assert.equal(typeof m.parseOptions,'function','Windows runner is not implemented');
  assert.equal(m.parseOptions([]).workers,4);
  assert.equal(m.parseOptions(['--workers','8','--rounds','1']).rounds,1);
  assert.throws(()=>m.parseOptions(['--workers','99']),/1.*8/);
  assert.throws(()=>m.parseOptions(['--banana','1']),/Unknown/);
});
test('state is atomic and report retention keeps only latest reports',()=>{
  assert.equal(typeof m.atomicJson,'function','saved runner state is not implemented');
  const dir=mkdtempSync(join(tmpdir(),'brain path with spaces '));
  m.atomicJson(join(dir,'state.json'),{round:2});
  assert.equal(JSON.parse(readFileSync(join(dir,'state.json'),'utf8')).round,2);
  for(let i=0;i<8;i++)writeFileSync(join(dir,`batch-${String(i).padStart(3,'0')}.json`),'{}');
  m.trimReports(dir,3);
  assert.deepEqual(readdirSync(dir).filter(n=>n.startsWith('batch-')).sort(),['batch-005.json','batch-006.json','batch-007.json']);
  assert.ok(readdirSync(dir).includes('state.json'));
});
test('local dashboard rejects foreign hosts and unauthorized stop, then stops once authorized',async()=>{
  assert.equal(typeof m.createDashboard,'function','local dashboard is not implemented');
  let stops=0;
  const d=await m.createDashboard(()=>({running:true}),()=>{stops++;},'secret-token','<html>dashboard</html>');
  try{
    assert.equal((await fetch(`${d.url}/status`)).status,200);
    assert.equal((await fetch(`${d.url}/stop`,{method:'POST'})).status,403);
    assert.equal((await fetch(`${d.url}/stop`,{method:'POST',headers:{'X-Trainer-Token':'secret-token',Origin:'https://foreign.example'}})).status,403);
    assert.equal((await fetch(`${d.url}/stop`,{method:'POST',headers:{'X-Trainer-Token':'secret-token',Origin:d.url}})).status,200);
    assert.equal(stops,1);
  }finally{await d.close();}
});
