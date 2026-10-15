import test from 'node:test'
import assert from 'node:assert/strict'
import { createInterviewApi } from './interview.js'

const response = (data, status = 200) => new Response(JSON.stringify(data), { status })
test('session recovery forwards an optional abort signal and keeps existing reads compatible', async () => {
  const calls = []
  const api = createInterviewApi({ fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: { sessionId: 's1' } }) } })
  const controller = new AbortController()
  await api.getSession('s1', { signal: controller.signal })
  await api.getSession('s1')
  assert.equal(calls[0][1].signal, controller.signal)
  assert.equal(calls[1][1].signal, undefined)
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
