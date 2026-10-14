import test from 'node:test'
import assert from 'node:assert/strict'
import { createInterviewApi } from './interview.js'

const json = (value, status = 200) => new Response(JSON.stringify(value), { status })

test('reads speech capabilities from the speech endpoint', async () => {
  let request
  const data = { asrEnabled: true, ttsEnabled: false, maxRecordingSeconds: 120, maxAudioBytes: 5242880 }
  const api = createInterviewApi({ fetchImpl: async (...args) => { request = args; return json({ success: true, data }) } })

  assert.deepEqual(await api.getSpeechCapabilities(), data)
  assert.equal(request[0], '/api/interview-speech/capabilities')
  assert.equal(request[1].method, 'GET')
  assert.equal(request[1].credentials, 'include')
})

test('uploads the recording as multipart with cookies and an abort signal', async () => {
  let request
  const api = createInterviewApi({ fetchImpl: async (...args) => {
    request = args
    return json({ success: true, data: { sessionId: 's1', questionId: 'q1', requestId: 'r1', text: '转写结果', durationMs: 3200 } })
  } })

  const file = new File(['audio bytes'], 'answer.webm', { type: 'audio/webm' })
  const controller = new AbortController()
  const result = await api.transcribe('s1', { questionId: 'q1', requestId: 'r1', file }, controller.signal)

  assert.deepEqual(result, { sessionId: 's1', questionId: 'q1', requestId: 'r1', text: '转写结果', durationMs: 3200 })
  const [url, init] = request
  assert.equal(url, '/api/interviews/s1/transcriptions')
  assert.equal(init.method, 'POST')
  assert.equal(init.credentials, 'include')
  assert.equal(init.signal, controller.signal)
  assert.ok(init.body instanceof FormData, 'multipart boundary 由浏览器生成')
  assert.equal(init.headers['Content-Type'], undefined, '不能自己写 multipart 的 Content-Type')
  assert.equal(init.body.get('questionId'), 'q1')
  assert.equal(init.body.get('requestId'), 'r1')
  const uploaded = init.body.get('file')
  assert.equal(uploaded.name, 'answer.webm')
  assert.equal(uploaded.type, 'audio/webm')
  assert.equal(init.headers['X-AI-Api-Key'], undefined)
  assert.equal(init.headers['X-AI-Model'], undefined)
  assert.equal(init.headers['X-AI-Base-Url'], undefined)
})

test('keeps the server message and status for a rejected transcription', async () => {
  const api = createInterviewApi({ fetchImpl: async () => json({ success: false, message: '面试状态已变化，请同步当前题目后继续' }, 409) })
  await assert.rejects(
    api.transcribe('s1', { questionId: 'q1', requestId: 'r1', file: new File([], 'answer.webm') }),
    error => error.status === 409 && error.message === '面试状态已变化，请同步当前题目后继续'
  )
})

test('returns the question audio as a blob and forwards the abort signal', async () => {
  let request
  const api = createInterviewApi({ fetchImpl: async (...args) => {
    request = args
    return new Response(new Uint8Array([1, 2, 3]), { status: 200, headers: { 'Content-Type': 'audio/mpeg' } })
  } })

  const controller = new AbortController()
  const blob = await api.questionSpeech('s1', 'q 2', controller.signal)

  assert.equal(blob.size, 3)
  assert.equal(await blob.text(), '\u0001\u0002\u0003')
  const [url, init] = request
  assert.equal(url, '/api/interviews/s1/questions/q%202/speech')
  assert.equal(init.method, 'POST')
  assert.equal(init.credentials, 'include')
  assert.equal(init.signal, controller.signal)
  assert.equal(init.body, undefined)
  assert.deepEqual(init.headers, { 'X-User-Id': 'demo-user' })
})

test('treats a JSON error reply as a failure instead of audio', async () => {
  const api = createInterviewApi({ fetchImpl: async () => json({ success: false, message: '语音服务未配置，文字面试仍可继续使用' }, 503) })
  await assert.rejects(
    api.questionSpeech('s1', 'q1'),
    error => error.status === 503 && error.message === '语音服务未配置，文字面试仍可继续使用'
  )
})

test('rejects playback replies that are not playable audio', async () => {
  // 服务端可能返回 JSON 错误；前端也拒绝空内容和不可播放的响应。
  const replies = [
    json({ success: false, message: '题目语音未完整生成，请重试' }),
    new Response('not audio at all', { status: 200, headers: { 'Content-Type': 'text/plain' } }),
    new Response(new Uint8Array(0), { status: 200, headers: { 'Content-Type': 'audio/mpeg' } }),
  ]
  for (const reply of replies) {
    const api = createInterviewApi({ fetchImpl: async () => reply })
    await assert.rejects(api.questionSpeech('s1', 'q1'), error => typeof error.message === 'string' && error.message.length > 0)
  }
})

test('surfaces an aborted request as AbortError', async () => {
  const controller = new AbortController()
  const api = createInterviewApi({ fetchImpl: async (_url, init) => new Promise((_resolve, reject) => {
    init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true })
  }) })

  const pending = api.questionSpeech('s1', 'q1', controller.signal)
  controller.abort()
  await assert.rejects(pending, error => error.name === 'AbortError')
})

test('accepts only nonempty MPEG audio no larger than 5 MiB', async () => {
  for (const [type, size] of [['audio/wav', 1], ['audio/mpeg-extra', 1], ['audio/mpeg', 5 * 1024 * 1024 + 1]]) {
    const api = createInterviewApi({ fetchImpl: async () => new Response(new Uint8Array(size), { headers: { 'Content-Type': type } }) })
    await assert.rejects(api.questionSpeech('s', 'q'), /语音/)
  }
  const api = createInterviewApi({ fetchImpl: async () => new Response(new Uint8Array(5 * 1024 * 1024), { headers: { 'Content-Type': 'audio/mpeg; charset=binary' } }) })
  assert.equal((await api.questionSpeech('s', 'q')).size, 5 * 1024 * 1024)
})
