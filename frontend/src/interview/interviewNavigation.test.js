import test from 'node:test'
import assert from 'node:assert/strict'
import { newInterviewRoute, focusedPracticeRoute, isNewInterviewRoute, sourceSessionId } from './interviewNavigation.js'

test('new interview route carries intent and keeps the source session id', () => {
  assert.deepEqual(newInterviewRoute('session-1'), {
    name: 'Interview', query: { intent: 'new', sourceSessionId: 'session-1' },
  })
  assert.equal(isNewInterviewRoute(newInterviewRoute('session-1')), true)
})

test('new interview route without a source id omits that query key', () => {
  assert.deepEqual(newInterviewRoute(), { name: 'Interview', query: { intent: 'new' } })
  assert.equal(sourceSessionId(newInterviewRoute()), '')
})

test('focused practice route carries plan and source IDs without answer content', () => {
  assert.deepEqual(focusedPracticeRoute('plan-1', 'source-1'), {
    name: 'Interview', query: { intent: 'new', planId: 'plan-1', sourceSessionId: 'source-1' }
  })
  assert.deepEqual(focusedPracticeRoute('', 'source-1'), newInterviewRoute('source-1'))
})

test('new interview route accepts a computed session id ref', () => {
  assert.deepEqual(newInterviewRoute({ value: 'session-1' }), {
    name: 'Interview', query: { intent: 'new', sourceSessionId: 'session-1' },
  })
})

test('new interview route carries only source IDs for preparation entry points', () => {
  assert.deepEqual(newInterviewRoute({ materialId: 'm1', jobId: 'j1' }), {
    name: 'Interview', query: { intent: 'new', materialId: 'm1', jobId: 'j1' }
  })
  assert.deepEqual(newInterviewRoute({ resumeVersionId: 'v1' }), {
    name: 'Interview', query: { intent: 'new', resumeVersionId: 'v1' }
  })
})

test('only an explicit intent=new suppresses auto-restore', () => {
  assert.equal(isNewInterviewRoute({ query: { intent: 'new' } }), true)
  for (const query of [{}, { intent: 'continue' }, { intent: 'new ' }, { sessionId: 'session-1' }]) {
    assert.equal(isNewInterviewRoute({ query }), false, JSON.stringify(query))
  }
  assert.equal(isNewInterviewRoute(null), false)
  assert.equal(isNewInterviewRoute(undefined), false)
})

test('source session id trims and rejects blank or non-string values', () => {
  assert.equal(sourceSessionId({ query: { sourceSessionId: ' session-1 ' } }), 'session-1')
  for (const value of [undefined, '', '   ', 0, null, ['session-1'], { id: 'session-1' }]) {
    assert.equal(sourceSessionId({ query: { sourceSessionId: value } }), '', String(value))
  }
  assert.equal(sourceSessionId({ query: {} }), '')
})
