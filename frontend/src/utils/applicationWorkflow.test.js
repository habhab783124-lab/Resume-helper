import test from 'node:test'
import assert from 'node:assert/strict'
import {allowedStatuses,statusLabel,localDateTime,dueNow,safeSourceUrl} from './applicationWorkflow.js'
test('status choices support progress and correction without skipping from preparation to offer',()=>{
  assert.deepEqual(allowedStatuses('PREPARING').map(v=>v.value),['PREPARING','APPLIED','CLOSED'])
  assert.ok(allowedStatuses('INTERVIEW').some(v=>v.value==='APPLIED'))
  assert.equal(statusLabel('TEST'),'笔试')
})
test('due actions exclude closed records and missing deadlines',()=>{
  assert.equal(dueNow({status:'APPLIED',nextActionAt:'2000-01-01'}),true)
  assert.equal(dueNow({status:'CLOSED',nextActionAt:'2000-01-01'}),false)
  assert.equal(dueNow({status:'PREPARING'}),false)
})
test('date input round-trips the local minute and rejects invalid dates',()=>{
  const value='2026-11-03T09:30:00Z',local=localDateTime(value)
  assert.equal(new Date(local).toISOString(),value.replace('00Z','00.000Z'))
  assert.equal(localDateTime('bad'),'')
})
test('snapshot links permit only credential-free HTTP sources',()=>{
  assert.equal(safeSourceUrl('javascript:alert(1)'),'')
  assert.equal(safeSourceUrl('https://user:pass@example.test/'),'')
  assert.equal(safeSourceUrl('https://example.test/job'),'https://example.test/job')
})
