import test from 'node:test'
import assert from 'node:assert/strict'
import { createInterviewApi } from './interview.js'

const response = (data, status = 200) => new Response(JSON.stringify(data), { status })
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
  const api = createInterviewApi({ fetchImpl: async (...args) => { calls.push(args); return response({ success: true, data: {} }) } })
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
