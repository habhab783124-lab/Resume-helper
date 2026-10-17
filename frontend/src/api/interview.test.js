import test from 'node:test'
import assert from 'node:assert/strict'
import { createInterviewApi } from './interview.js'

const response = (data, status = 200) => new Response(JSON.stringify(data), { status })
test('history listing forwards cursor and deletion targets exactly one session', async () => {
  const calls = []
  const api = createInterviewApi({ fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: { items: [], nextCursor: null } }) } })
  await api.listHistory({ limit: 10, cursor: 'abc+/=' })
  await api.deleteSession('one session')
  assert.equal(calls[0][0], '/api/interviews?limit=10&cursor=abc%2B%2F%3D')
  assert.equal(calls[0][1].method, 'GET')
  assert.equal(calls[1][0], '/api/interviews/one%20session')
  assert.equal(calls[1][1].method, 'DELETE')
})
test('session recovery forwards an optional abort signal and keeps existing reads compatible', async () => {
  const calls = []
  const api = createInterviewApi({ fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: { sessionId: 's1' } }) } })
  const controller = new AbortController()
  await api.getSession('s1', { signal: controller.signal })
  await api.getSession('s1')
  assert.notEqual(calls[0][1].signal, controller.signal)
  controller.abort()
  assert.equal(calls[0][1].signal.aborted, false)
  assert.ok(calls[1][1].signal instanceof AbortSignal)
  assert.equal(calls[0][1].method, 'GET')
})
test('uses API origin, cookies and ephemeral AI headers with JSON bodies', async () => {
  let request
  const api = createInterviewApi({ baseUrl: 'https://example.test/', fetchImpl: async (...args) => { request = args; return response({ success: true, data: { sessionId: 'abc' } }) } })
  assert.deepEqual(await api.start({ materialId: 'm', questionCount: 5 }, { apiKey: 'secret', model: 'model', baseUrl: 'https://model.example/v1' }), { sessionId: 'abc' })
  assert.equal(request[0], 'https://example.test/api/interviews')
  assert.equal(request[1].credentials, 'include')
  assert.equal(request[1].headers['X-AI-Api-Key'], 'secret')
  assert.equal(request[1].headers['X-AI-Model'], 'model')
  assert.equal(request[1].headers['X-AI-Base-Url'], 'https://model.example/v1')
  assert.equal(JSON.parse(request[1].body).materialId, 'm')
})
test('previews an owner-selected source without model credentials', async () => {
  let request
  const api = createInterviewApi({ fetchImpl: async (...args) => { request = args; return response({ success: true, data: {
    source: { kind: 'RESUME_VERSION', id: 'v1' }, sourceLabel: 'Backend CV', resumeText: 'section\n- bullet', sourceFingerprint: 'abc'
  } }) } })
  const source = { kind: 'RESUME_VERSION', id: 'v1' }
  const preview = await api.previewSource(source)
  assert.equal(request[0], '/api/interviews/source/preview')
  assert.deepEqual(JSON.parse(request[1].body), { source })
  assert.equal(request[1].headers['X-AI-Api-Key'], undefined)
  assert.equal(preview.sourceFingerprint, 'abc')
})
test('uploads multipart without overriding boundary and reads without AI credentials', async () => {
  const calls = []
  const api = createInterviewApi({ fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: { material: { id: 'm' }, text: 'resume' } }) } })
  await api.upload(new Blob(['resume']), 'resume.txt')
  await api.getSession('id with spaces')
  assert.ok(calls[0][1].body instanceof FormData)
  assert.equal(calls[0][1].headers['Content-Type'], undefined)
  assert.equal(calls[1][0], '/api/interviews/id%20with%20spaces')
  assert.equal(calls[1][1].headers['X-AI-Api-Key'], undefined)
  assert.equal(calls[1][1].headers['X-AI-Base-Url'], undefined)
})
test('sends turn and finish to their endpoints', async () => {
  const calls = []
  const api = createInterviewApi({ fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: {} }) } })
  await api.answer('abc', { questionId: 'q', answer: 'hello' })
  await api.finish('abc')
  assert.equal(calls[0][0], '/api/interviews/abc/turns')
  assert.equal(JSON.parse(calls[0][1].body).answer, 'hello')
  assert.equal(calls[1][0], '/api/interviews/abc/finish')
})
test('replacement sends the reason to the async, text-model endpoint with cancellation', async () => {
  let request
  const controller = new AbortController()
  const api = createInterviewApi({ fetchImpl: async (...args) => { request = args; return response({ success: true, data: { status: 'ACTIVE' } }) } })
  const body = { questionId: 'q1', requestId: 'r1', expectedRevision: 3, reason: '我没有参与这段经历' }
  await api.replace('s /', body, controller.signal)
  assert.equal(request[0], '/api/interviews/s%20%2F/replacements')
  assert.deepEqual(JSON.parse(request[1].body), body)
  assert.equal(request[1].headers['X-AI-Api-Key'], undefined)
  assert.notEqual(request[1].signal, controller.signal)
  controller.abort()
  assert.equal(request[1].signal.aborted, false)
})
test('separated answer and generation APIs forward cancellation, text credentials and exact bodies', async () => {
  const calls = []; const controller = new AbortController()
  const { setAiSettings } = await import('./requestContext.js')
  setAiSettings({ apiKey: 'text-key', model: 'text-model', baseUrl: 'https://text.test' })
  const api = createInterviewApi({ fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: { status: 'ACTIVE' } }) } })
  await api.saveAnswer('s /', { questionId: 'q', submissionId: 'sub', answer: 'hello' }, controller.signal)
  await api.generate('s /', { requestId: 'gen', kind: 'NEXT', expectedRevision: 2 }, controller.signal)
  assert.equal(calls[0][0], '/api/interviews/s%20%2F/answers')
  assert.deepEqual(JSON.parse(calls[0][1].body), { questionId: 'q', submissionId: 'sub', answer: 'hello' })
  assert.notEqual(calls[0][1].signal, controller.signal)
  controller.abort()
  assert.equal(calls[0][1].signal.aborted, false)
  assert.equal(calls[0][1].headers['X-User-Id'], 'demo-user')
  assert.equal(calls[0][1].headers['X-Speech-Api-Key'], undefined)
  assert.equal(calls[0][1].headers['X-AI-Api-Key'], 'text-key')
  assert.equal(calls[1][0], '/api/interviews/s%20%2F/generations')
  assert.equal(JSON.parse(calls[1][1].body).expectedRevision, 2)
  assert.notEqual(calls[1][1].signal, controller.signal)
  assert.equal(calls[1][1].signal.aborted, false)
  assert.equal(calls[1][1].headers['X-AI-Api-Key'], 'text-key')
  setAiSettings({})
})
test('finish sends optional body and forwards cancellation', async () => {
  let request
  const controller = new AbortController()
  const api = createInterviewApi({ fetchImpl: async (...args) => { request = args; return response({ success: true, data: {} }) } })
  const body = { requestId: 'finish', submission: { questionId: 'q', submissionId: 'sub', answer: 'last' } }
  await api.finish('s', body, controller.signal)
  assert.equal(request[0], '/api/interviews/s/finish')
  assert.deepEqual(JSON.parse(request[1].body), body)
  assert.notEqual(request[1].signal, controller.signal)
  assert.equal(request[1].signal.aborted, false)
})
test('retryReview targets the separate summary operation and forwards a request ID', async () => {
  let request
  const api = createInterviewApi({ fetchImpl: async (...args) => { request = args; return response({ success: true, data: { reviewStatus: 'PENDING' } }) } })
  await api.retryReview('review / id', { requestId: 'review-op-1' })
  assert.equal(request[0], '/api/interviews/review%20%2F%20id/report/review/retry')
  assert.deepEqual(JSON.parse(request[1].body), { requestId: 'review-op-1' })
  assert.equal(request[1].headers['X-AI-Api-Key'], undefined)
})
test('short request timeout is distinct from model timeout and parses structured backend errors', async () => {
  const api = createInterviewApi({ requestTimeouts: { read: 5, write: 5 }, fetchImpl: async (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })), { once: true })
  }) })
  await assert.rejects(api.getSession('s'), error => error.code === 'BROWSER_UNCONFIRMED' && error.retryable === true)
  const backend = createInterviewApi({ fetchImpl: async () => response({ success: false, message: 'busy', code: 'RATE_LIMITED', retryable: true }, 429) })
  await assert.rejects(backend.getSession('s'), error => error.code === 'RATE_LIMITED' && error.retryable === true)
})
test('external cancellation aborts the underlying short request', async () => {
  let underlying
  const api = createInterviewApi({ fetchImpl: async (_url, options) => {
    underlying = options.signal
    return new Promise((_resolve, reject) => underlying.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })), { once: true }))
  } })
  const controller = new AbortController()
  const pending = api.getSession('s', { signal: controller.signal })
  await new Promise(resolve => setImmediate(resolve))
  controller.abort()
  await assert.rejects(pending, error => error.name === 'AbortError')
  assert.equal(underlying.aborted, true)
})
test('preserves HTTP status and API message for expiry and retry UI', async () => {
  const api = createInterviewApi({ fetchImpl: async () => response({ success: false, message: 'expired' }, 404) })
  await assert.rejects(api.getSession('gone'), error => error.status === 404 && error.message === 'expired')
})
test('rejects malformed, unsuccessful and non-JSON responses', async () => {
  for (const reply of [response({ success: false, message: 'model unavailable' }), response({ success: true }), new Response('bad gateway', { status: 502 })]) {
    const api = createInterviewApi({ fetchImpl: async () => reply })
    await assert.rejects(api.getSession('abc'))
  }
})
test('sends model credentials to the connectivity check', async () => {
  let request
  const api = createInterviewApi({ fetchImpl: async (...args) => { request = args; return response({ success: true, data: { model: 'my-model' } }) } })
  assert.deepEqual(await api.checkAi({ apiKey: 'key', model: 'my-model', baseUrl: 'https://model.example/v1' }), { model: 'my-model' })
  assert.equal(request[0], '/api/ai/check')
  assert.equal(request[1].method, 'POST')
  assert.equal(request[1].headers['X-AI-Api-Key'], 'key')
  assert.equal(request[1].headers['X-AI-Model'], 'my-model')
  assert.equal(request[1].headers['X-AI-Base-Url'], 'https://model.example/v1')
})

test('maps unified material detail and preserves empty scanned text', async () => {
  for (const text of ['resume text', '']) {
    const api = createInterviewApi({ fetchImpl: async () => response({ success: true, data: { material: { id: 'm1', fileName: 'resume.pdf', status: text ? 'TEXT_EXTRACTED' : 'SCANNED' }, extraction: { ignored: true }, text } }) })
    assert.deepEqual(await api.upload(new Blob(['bytes']), 'resume.pdf'), { materialId: 'm1', fileName: 'resume.pdf', status: text ? 'TEXT_EXTRACTED' : 'SCANNED', text })
  }
})

test('rejects malformed material ids or original text without hiding deletion status', async () => {
  for (const detail of [{ text: 'text' }, { material: { id: '' }, text: '' }, { material: { id: 1 }, text: '' }, { material: { id: 'm' }, text: {} }, { material: { id: 'm' } }]) {
    const api = createInterviewApi({ fetchImpl: async () => response({ success: true, data: detail }) })
    await assert.rejects(api.getMaterial('m'), /简历.*异常/)
  }
  const api = createInterviewApi({ fetchImpl: async () => response({ success: false, message: '材料已删除' }, 404) })
  await assert.rejects(api.getMaterial('m'), error => error.status === 404 && error.message === '材料已删除')
})

test('all text routes read current shared settings and respect explicit overrides', async () => {
  const { setAiSettings } = await import('./requestContext.js')
  const calls = []
  const api = createInterviewApi({ fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: { material: { id: 'm' }, text: 'resume' } }) } })
  setAiSettings({ apiKey: 'first', model: 'm1', baseUrl: 'https://first.test' })
  await api.start({})
  setAiSettings({ apiKey: 'second', model: 'm2', baseUrl: 'https://second.test' })
  await api.answer('s', {})
  await api.checkAi()
  await api.report('s')
  await api.upload(new Blob(['text']), 'resume.txt')
  await api.upload(new Blob(['text']), 'resume.txt', { apiKey: 'override' })
  await api.report('s', {})
  assert.equal(calls[0][1].headers['X-AI-Api-Key'], 'first')
  for (const call of calls.slice(1, 5)) {
    assert.equal(call[1].headers['X-AI-Api-Key'], 'second')
    assert.equal(call[1].headers['X-AI-Model'], 'm2')
    assert.equal(call[1].headers['X-AI-Base-Url'], 'https://second.test')
  }
  assert.equal(calls[5][1].headers['X-AI-Api-Key'], 'override')
  assert.equal(calls[5][1].headers['X-AI-Model'], undefined)
  assert.equal(calls[6][1].headers['X-AI-Api-Key'], undefined)
})

test('all routes share request-time identity and speech/read routes omit credentials', async () => {
  const { setAiSettings } = await import('./requestContext.js')
  setAiSettings({ apiKey: 'text-secret', model: 'model', baseUrl: 'https://models.test', volcApiKey: 'never' })
  let user = 'user-a'
  const calls = []
  const api = createInterviewApi({ getUserId: () => user, fetchImpl: async (...args) => {
    calls.push(args)
    return args[0].endsWith('/speech') ? new Response('audio', { headers: { 'Content-Type': 'audio/mpeg' } }) : response({ success: true, data: { material: { id: 'm' }, text: 'text' } })
  } })
  await api.start({})
  user = 'user-b'
  await api.getSession('s'); await api.getMaterial('m'); await api.finish('s')
  await api.getSpeechCapabilities()
  await api.transcribe('s', { questionId: 'q', requestId: 'r', file: new File(['audio'], 'answer.webm') })
  await api.questionSpeech('s', 'q')
  assert.equal(calls[0][1].headers['X-User-Id'], 'user-a')
  for (const call of calls.slice(1)) {
    assert.equal(call[1].headers['X-User-Id'], 'user-b')
    assert.deepEqual(Object.keys(call[1].headers).filter(name => /AI|VOLC|Authorization/i.test(name)), [])
    assert.ok(!call[0].includes('secret'))
  }
  setAiSettings({})
})

test('report GET and missing-assessment retry POST preserve request-time identity and text model credentials', async () => {
  const { setAiSettings } = await import('./requestContext.js')
  setAiSettings({ apiKey: 'report-key', model: 'report-model', baseUrl: 'https://report.test/v1' })
  const calls = []
  const api = createInterviewApi({ getUserId: () => 'report-owner', fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: { reportStatus: 'PARTIAL', overallScore: null } }) } })
  await api.report('id /'); await api.retryReport('id /'); await api.retryReport('id /', {})
  assert.equal(calls[0][0], '/api/interviews/id%20%2F/report'); assert.equal(calls[0][1].method, 'GET')
  assert.equal(calls[1][0], '/api/interviews/id%20%2F/report/retry'); assert.equal(calls[1][1].method, 'POST')
  assert.equal(calls[1][1].credentials, 'include'); assert.equal(calls[1][1].headers['X-User-Id'], 'report-owner')
  assert.equal(calls[1][1].headers['X-AI-Api-Key'], 'report-key'); assert.equal(calls[1][1].headers['X-AI-Model'], 'report-model')
  assert.equal(calls[1][1].headers['X-AI-Base-Url'], 'https://report.test/v1'); assert.equal(calls[1][1].body, undefined)
  assert.equal(calls[2][1].headers['X-AI-Api-Key'], undefined)
  setAiSettings({})
})

test('async report requests expose scoring state and retries carry an idempotency requestId', async () => {
  const calls = []
  const api = createInterviewApi({ fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: { scoringState: 'QUEUED' } }) } })
  await api.report('s1', { async: true })
  await api.retryReport('s1', { async: true, requestId: 'retry-1' })
  assert.equal(calls[0][0], '/api/interviews/s1/report?async=true')
  assert.equal(calls[0][1].method, 'GET')
  assert.equal(calls[1][1].method, 'POST')
  assert.deepEqual(JSON.parse(calls[1][1].body), { async: true, requestId: 'retry-1' })
})


test('prepares, reads and starts a planned interview with request fields', async () => {
  const calls = []
  const api = createInterviewApi({ baseUrl: 'https://svc.test', fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: { status: 'READY', planId: 'p1' } }) } })
  const plan = { source: { kind: 'MATERIAL', id: 'm1' }, mode: 'QUICK', mainCount: 3, requestId: 'r1' }
  await api.preparePlan(plan); await api.getPlan('p1'); await api.startPlanned({ planId: 'p1', planVersion: 2, requestId: 'r2' })
  assert.equal(calls[0][0], 'https://svc.test/api/interviews/plans')
  assert.deepEqual(JSON.parse(calls[0][1].body), plan)
  assert.equal(calls[1][0], 'https://svc.test/api/interviews/plans/p1')
  assert.equal(calls[2][0], 'https://svc.test/api/interviews')
  assert.deepEqual(JSON.parse(calls[2][1].body), { allowAlgorithmRepeat: false, planId: 'p1', planVersion: 2, requestId: 'r2', async: true })
})

test('prepares a focused practice plan from selected review issue IDs only', async () => {
  const { setAiSettings } = await import('./requestContext.js')
  setAiSettings({ apiKey: 'focused-key', model: 'focused-model' })
  let request
  const api = createInterviewApi({ fetchImpl: async (...args) => { request = args; return response({ success: true, data: { planId: 'p-focused' } }) } })
  const body = { issueIds: ['q1', 'q2'], requestId: 'practice-1' }
  await api.preparePracticePlan('s-source', body)
  assert.equal(request[0], '/api/interviews/s-source/practice-plans')
  assert.equal(request[1].method, 'POST')
  assert.deepEqual(JSON.parse(request[1].body), body)
  assert.equal(request[1].headers['X-AI-Api-Key'], 'focused-key')
  setAiSettings({})
})

test('requests QUICK practice feedback by closing the root group with an idempotent request', async () => {
  const { setAiSettings } = await import('./requestContext.js')
  setAiSettings({ apiKey: 'feedback-key', model: 'feedback-model' })
  let request
  const api = createInterviewApi({ fetchImpl: async (...args) => { request = args; return response({ success: true, data: { practiceFeedbacks: {} } }) } })
  const body = { questionId: 'root-1', requestId: 'feedback-1', expectedRevision: 8 }
  await api.practiceFeedback('s1', body)
  assert.equal(request[0], '/api/interviews/s1/feedback')
  assert.equal(request[1].method, 'POST')
  assert.deepEqual(JSON.parse(request[1].body), body)
  assert.equal(request[1].headers['X-AI-Api-Key'], 'feedback-key')
  setAiSettings({})
})

test('attempt API saves the answer first, reads a result and retries scoring on the same attempt ID', async () => {
  const calls = []
  const { setAiSettings } = await import('./requestContext.js')
  setAiSettings({ apiKey: 'attempt-key', model: 'attempt-model' })
  const api = createInterviewApi({ fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: { attemptId: 'a1', status: 'PENDING' } }) } })
  await api.submitAttempt('s1', { questionId: 'q1', requestId: 'request-1', answer: '新回答' })
  await api.listAttempts('s1', 'q1')
  await api.getAttempt('s1', 'a1')
  await api.retryAttempt('s1', 'a1', { requestId: 'retry-1' })
  assert.equal(calls[0][0], '/api/interviews/s1/attempts')
  assert.deepEqual(JSON.parse(calls[0][1].body), { questionId: 'q1', requestId: 'request-1', answer: '新回答' })
  assert.equal(calls[0][1].headers['X-AI-Api-Key'], 'attempt-key')
  assert.equal(calls[1][0], '/api/interviews/s1/attempts?questionId=q1')
  assert.equal(calls[1][1].method, 'GET')
  assert.equal(calls[2][0], '/api/interviews/s1/attempts/a1')
  assert.equal(calls[2][1].method, 'GET')
  assert.equal(calls[3][0], '/api/interviews/s1/attempts/a1/retry')
  assert.deepEqual(JSON.parse(calls[3][1].body), { requestId: 'retry-1' })
  setAiSettings({})
})
