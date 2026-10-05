import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Writable} from 'node:stream';
import * as renderer from '../renderer.mjs';
test('closed parent pipe ends replies quietly and stops further writes',async()=>{
  assert.equal(typeof renderer.createProtocolWriter,'function');
  let calls=0;
  const stream=new Writable({write(chunk,encoding,callback){calls++;callback(Object.assign(new Error('parent closed'),{code:'EPIPE'}));}});
  const reply=renderer.createProtocolWriter(stream);
  assert.equal(await reply({id:1}),false);
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(await reply({id:2}),false);
  assert.equal(calls,1);
});
