import test from 'node:test'
import assert from 'node:assert/strict'
import { createInterviewApi } from './interview.js'

test('request deadline covers response body consumption after headers arrive', async () => {
  let bodyTimer: ReturnType<typeof setTimeout> | undefined
  const api = createInterviewApi({
    requestTimeouts: { read: 5, write: 5 },
    fetchImpl: async () => new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        bodyTimer = setTimeout(() => {
          controller.enqueue(new TextEncoder().encode('{"success":true,"data":{"sessionId":"s"}}'))
          controller.close()
        }, 60)
      },
      cancel() { if (bodyTimer) clearTimeout(bodyTimer) },
    }), { headers: { 'Content-Type': 'application/json' } }),
  })
  await assert.rejects(api.getSession('s'), { code: 'BROWSER_UNCONFIRMED' })
})

test('an external abort while reading the body remains an AbortError', async () => {
  let bodyTimer: ReturnType<typeof setTimeout> | undefined
  const controller = new AbortController()
  const api = createInterviewApi({
    fetchImpl: async () => new Response(new ReadableStream<Uint8Array>({
      start(stream) {
        bodyTimer = setTimeout(() => {
          stream.enqueue(new TextEncoder().encode('{"success":true,"data":{}}'))
          stream.close()
        }, 100)
      },
      cancel() { if (bodyTimer) clearTimeout(bodyTimer) },
    }), { headers: { 'Content-Type': 'application/json' } }),
  })
  const pending = api.getSession('s', { signal: controller.signal })
  setTimeout(() => controller.abort(), 5)
  await assert.rejects(pending, error => error instanceof Error && error.name === 'AbortError')
})

test('invalid JSON response is reported as an HTTP response error', async () => {
  const api = createInterviewApi({ fetchImpl: async () => new Response('not json', { status: 502 }) })
  await assert.rejects(api.getSession('s'), { status: 502, code: 'INVALID_RESPONSE' })
})

test('a transport that ignores abort still settles at the deadline', async () => {
  const api = createInterviewApi({
    requestTimeouts: { read: 5, write: 5 },
    fetchImpl: () => new Promise(() => {}),
  })
  await assert.rejects(api.getSession('s'), { code: 'BROWSER_UNCONFIRMED' })
})
