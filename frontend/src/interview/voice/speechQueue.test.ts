import assert from 'node:assert/strict'
import test from 'node:test'
import { createSpeechQueue } from './speechQueue.ts'
import type { CapturedSegment, VoiceContext } from './voiceContracts.ts'

const context: VoiceContext = { scopeKey: 'scope', sessionId: 's1', questionId: 'q1', epoch: 1 }
const captured = (audio = new Blob(['wav'], { type: 'audio/wav' })): CapturedSegment => ({ context: { ...context }, sourceSampleRate: 48000, sampleCount: 12, audio })
const deferred = <T>() => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('transcribes queued segments serially and ignores completions from an expired context', async () => {
  const pending: Array<ReturnType<typeof deferred<any>>> = []
  const calls: string[] = []; const applied: string[] = []
  const queue = createSpeechQueue({
    api: { transcribe: async (_id, request) => { calls.push(request.requestId); const item = deferred<any>(); pending.push(item); return item.promise } },
    getContext: () => context,
    onApplied: segment => applied.push(segment.text),
    idFactory: () => `id-${calls.length + pending.length}`,
  })
  try {
    const first = queue.enqueue(captured())
    const second = queue.enqueue(captured())
    await new Promise(resolve => setTimeout(resolve, 0))
    assert.equal(calls.length, 1)
    pending[0]!.resolve({ sessionId: 's1', questionId: 'q1', requestId: calls[0], text: '第一句', durationMs: 10 })
    await new Promise(resolve => setTimeout(resolve, 0))
    assert.equal(calls.length, 2)
    pending[1]!.resolve({ sessionId: 's1', questionId: 'q1', requestId: calls[1], text: '第二句', durationMs: 10 })
    await new Promise(resolve => setTimeout(resolve, 0))
    assert.deepEqual(applied, ['第一句', '第二句'])
    assert.equal(queue.snapshot().find(item => item.segmentId === first)?.audio, null)
    assert.equal(queue.snapshot().find(item => item.segmentId === second)?.state, 'DONE')
  } finally { queue.dispose() }
})

test('retains failed audio, explicit retry changes request identity, and pause aborts late work', async () => {
  let callCount = 0; const applied: string[] = []; const errors: string[] = []
  const queue = createSpeechQueue({
    api: { transcribe: async (_id, request) => {
      callCount++
      if (callCount === 1) throw new Error('network')
      return { sessionId: 's1', questionId: 'q1', requestId: request.requestId, text: '重试成功', durationMs: 10 }
    } },
    getContext: () => context,
    onApplied: segment => applied.push(segment.text),
    onFailure: failure => errors.push(failure.message),
    idFactory: (() => { let id = 0; return () => `id-${++id}` })(),
  })
  const id = queue.enqueue(captured())
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.ok(queue.snapshot().find(item => item.segmentId === id)?.audio)
  assert.equal(errors.length, 1)
  queue.retry(id)
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.deepEqual(applied, ['重试成功'])
  assert.equal(callCount, 2)
  queue.pause()
  queue.dispose()
})

test('rejects response identity mismatch and limits queued audio to three segments', async () => {
  const gate = deferred<any>()
  const queue = createSpeechQueue({ api: { transcribe: () => gate.promise }, getContext: () => context,
    onApplied: () => {}, idFactory: (() => { let id = 0; return () => `id-${++id}` })() })
  queue.enqueue(captured()); queue.enqueue(captured()); queue.enqueue(captured())
  assert.throws(() => queue.enqueue(captured()), /上限/)
  gate.resolve({ sessionId: 'other', questionId: 'q1', requestId: 'wrong', text: '不能采用', durationMs: 1 })
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.ok(queue.snapshot().some(item => item.state === 'FAILED'))
  queue.dispose()
})

test('a question change keeps the old recording marked stale and ignores its late transcript', async () => {
  let current: VoiceContext | null = { ...context }
  const gate = deferred<any>(); const applied: string[] = []
  const queue = createSpeechQueue({ api: { transcribe: () => gate.promise }, getContext: () => current,
    onApplied: segment => applied.push(segment.text), idFactory: (() => { let id = 0; return () => `id-${++id}` })() })
  const id = queue.enqueue(captured())
  await new Promise(resolve => setTimeout(resolve, 0))
  current = { ...context, questionId: 'q2', epoch: 2 }
  queue.pause(); queue.invalidateContext(current)
  gate.resolve({ sessionId: 's1', questionId: 'q1', requestId: queue.snapshot()[0]!.requestId, text: '旧题转写', durationMs: 1 })
  await new Promise(resolve => setTimeout(resolve, 0))
  const segment = queue.snapshot().find(item => item.segmentId === id)!
  assert.equal(segment.state, 'FAILED')
  assert.equal(segment.failure?.code, 'STALE_CONTEXT')
  assert.ok(segment.audio)
  assert.deepEqual(applied, [])
  queue.dispose()
})
