import test from 'node:test';
import assert from 'node:assert/strict';
import {runLab} from '../../../../../scripts/parallel-browser-lab.mjs';
test('four isolated browsers reject no-op closures and retain human boundaries',async()=>{
 const r=await runLab({workers:4});
 assert.equal(r.browser_sessions,4);assert.equal(r.peak_active_workers,4);assert.equal(r.cases.length,16);
 assert.equal(r.real_world_validated,false);assert.equal(r.teacher_requests,0);
 for(const row of r.cases){
  assert.equal(row.passed,true,row.case_id);assert.equal(row.external_requests,0);
  if(row.case_id==='noop-close')assert.equal(row.verified_closure,false);
  if(row.case_id==='human-wall'){assert.equal(row.actions,0);assert.equal(row.human_signal_detected,true);}
 }
 assert.equal(r.verified_closures,4);assert.equal(r.false_mastery,0);
 assert.equal(new Set(r.cases.map(x=>x.worker)).size,4);
});
