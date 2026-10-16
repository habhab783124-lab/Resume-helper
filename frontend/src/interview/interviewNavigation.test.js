import test from 'node:test'
import assert from 'node:assert/strict'
import { newInterviewRoute, isNewInterviewRoute, sourceSessionId } from './interviewNavigation.js'

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
