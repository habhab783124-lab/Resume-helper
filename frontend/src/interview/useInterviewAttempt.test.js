import test from 'node:test'
import assert from 'node:assert/strict'
import { useInterviewAttempt } from './useInterviewAttempt.js'

function storage() {
  const values = new Map()
  return { values, getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
}

test('attempt draft uses a separate key, keeps failed text, and clears only after server save', async () => {
  const local = storage(), calls = []
  const api = { submitAttempt: async (...args) => { calls.push(args); throw new Error('offline') } }
  const attempt = useInterviewAttempt({ api, sessionId: 's1', questionId: 'q1', storage: local,
    scope: { apiBaseUrl: '/api', userId: 'u1' }, ids: () => 'request-1' })
  attempt.answer.value = '独立重答草稿'
  attempt.saveDraft()
  assert.ok([...local.values.keys()][0].endsWith('|s1|q1|attemptDraft'))
  assert.equal(await attempt.submit(), false)
  assert.equal(attempt.answer.value, '独立重答草稿')
  assert.ok([...local.values.values()][0].includes('独立重答草稿'))
  assert.equal(calls[0][1].answer, '独立重答草稿')
})

test('duplicate submit is coalesced and a saved pending version clears only the attempt draft', async () => {
  let resolve; const calls = [], local = storage()
  const api = { submitAttempt: async (...args) => { calls.push(args); return new Promise(done => { resolve = done }) } }
  const attempt = useInterviewAttempt({ api, sessionId: 's1', questionId: 'q1', storage: local, ids: () => 'req-1' })
  attempt.answer.value = '重答一'
  const one = attempt.submit(), two = attempt.submit()
  assert.notEqual(one, undefined); assert.notEqual(two, undefined)
  resolve({ attemptId: 'a1', version: 1, requestId: 'req-1', questionId: 'q1', answer: '重答一', status: 'PENDING' })
  assert.equal(await one, true)
  assert.equal(calls.length, 1)
  assert.equal(attempt.versions.value[0].attemptId, 'a1')
  assert.equal(attempt.status.value, 'PENDING')
  assert.equal([...local.values.keys()].length, 0)
})

test('restores a pending attempt and refreshes that same version to completion', async () => {
  const pending = { attemptId: 'a1', version: 2, requestId: 'r2', questionId: 'q1', answer: '已保存', status: 'PENDING' }
  const calls = []
  const api = { listAttempts: async (...args) => { calls.push(['list', ...args]); return [pending] },
    getAttempt: async (...args) => { calls.push(['get', ...args]); return { ...pending, status: 'COMPLETE', feedback: { highlight: '有依据' } } } }
  const attempt = useInterviewAttempt({ api, sessionId: 's1', questionId: 'q1', storage: storage() })
  await attempt.load()
  assert.equal(attempt.status.value, 'PENDING')
  await attempt.refresh()
  assert.equal(attempt.status.value, 'COMPLETE')
  assert.equal(attempt.result.value.attemptId, 'a1')
  assert.deepEqual(calls.map(call => call[0]), ['list', 'get'])
})

test('five saved versions disable another submit with a clear limit status', async () => {
  const versions = Array.from({ length: 5 }, (_, i) => ({ attemptId: `a${i + 1}`, version: i + 1, status: 'COMPLETE' }))
  const attempt = useInterviewAttempt({ api: { listAttempts: async () => versions }, sessionId: 's1', questionId: 'q1', storage: storage() })
  await attempt.load()
  attempt.answer.value = '再答一次'
  assert.equal(attempt.canSubmit.value, false)
  assert.equal(await attempt.submit(), false)
  assert.match(attempt.error.value, /五版/)
})

test('dispose fences a late response after the user changes question or scope', async () => {
  let resolve
  const attempt = useInterviewAttempt({ api: { submitAttempt: async () => new Promise(done => { resolve = done }) },
    sessionId: 's1', questionId: 'q1', storage: storage(), ids: () => 'r1' })
  attempt.answer.value = '旧题重答'
  const pending = attempt.submit()
  attempt.dispose()
  resolve({ attemptId: 'a1', version: 1, questionId: 'q1', answer: '旧题重答', status: 'PENDING' })
  assert.equal(await pending, false)
  assert.equal(attempt.result.value, null)
})

test('a list refresh during submit discards the stale response and releases the busy state', async () => {
  let resolveSubmit
  const saved = { attemptId: 'a1', version: 1, questionId: 'q1', answer: '旧请求', status: 'PENDING' }
  const attempt = useInterviewAttempt({ api: {
    submitAttempt: async () => new Promise(done => { resolveSubmit = done }),
    listAttempts: async () => [],
  }, sessionId: 's1', questionId: 'q1', storage: storage(), ids: () => 'r1' })
  attempt.answer.value = '旧请求'
  const pending = attempt.submit()
  assert.equal(attempt.busy.value, true)
  await attempt.load()
  resolveSubmit(saved)
  assert.equal(await pending, false)
  assert.equal(attempt.busy.value, false)
  assert.equal(attempt.result.value, null)
})
