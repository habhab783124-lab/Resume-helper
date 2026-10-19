import assert from 'node:assert/strict'
import test from 'node:test'
import { useInterviewVoiceConversation } from './useInterviewVoiceConversation.ts'
import type { CapturedSegment, VoiceContext } from './voiceContracts.ts'

class FakeClock {
  time = 0; id = 0; timers = new Map<number, { at: number; callback: () => void }>()
  now = () => this.time
  setTimeout = (callback: () => void, delay: number) => { const id = ++this.id; this.timers.set(id, { at: this.time + delay, callback }); return id }
  clearTimeout = (id: unknown) => { this.timers.delete(id as number) }
  advance(ms: number) { this.time += ms; for (const [id, timer] of [...this.timers]) if (timer.at <= this.time) { this.timers.delete(id); timer.callback() } }
}

function harness({ transcribe = async (_id: string, request: { questionId: string; requestId: string; file: Blob }, _signal?: AbortSignal) => ({ sessionId: 's1', questionId: request.questionId, requestId: request.requestId, text: '识别内容', durationMs: 1 }), apiOverrides = {} as Record<string, unknown> } = {}) {
  const clock = new FakeClock(); const state = { draft: '', sent: [] as any[], context: null as VoiceContext | null, captureStarts: 0, captureStops: 0, captureDisposals: 0, playedFrames: 0 }
  let captureOptions: any; let socketOptions: any; let sealCapture: () => Promise<void> = async () => {}; let connectSocket: () => Promise<void> = async () => {}
  const session = { sessionId: 's1', status: 'ACTIVE' as const, currentQuestion: { questionId: 'q1' } as { questionId: string } | null, pendingAnswer: null,
    revision: 1, stateVersion: 1, plan: { mode: 'FULL' as const }, generation: null, turns: [{ questionId: 'q1', question: '题目', answer: null }] }
  const controller = useInterviewVoiceConversation({
    api: {
      createVoiceSession: async () => ({ voiceSessionId: 'v1', ticket: 't', ticketExpiresAt: 'later', websocketPath: '/api/interview-voice/ws', protocolVersion: 1 as const }),
      closeVoiceSession: async () => ({}), voiceSocketUrl: () => 'ws://localhost/api/interview-voice/ws',
      transcribe,
      ...apiOverrides,
    },
    conversation: { readSession: () => session, readDraft: () => state.draft, readPendingSubmission: () => null,
      writeDraft: text => { state.draft = text }, sendVoice: async payload => { state.sent.push(payload); return { outcome: 'SAVED', questionId: 'q1', submissionId: payload.submissionId, generation: 'NONE' } } },
    getScope: () => 'scope', getQuestionKind: () => 'MAIN', audioContextFactory: () => ({ resume: async () => {}, close: async () => {} } as unknown as AudioContext), workletUrl: '/voice.worklet.js', clock,
    captureFactory: options => { captureOptions = options; return { start: async context => { state.captureStarts++; state.context = context }, seal: () => sealCapture(), resume: () => {}, setPlayback: () => {}, stop: async () => { state.captureStops++; return null }, dispose: async () => { state.captureDisposals++ } } },
    socketFactory: options => { socketOptions = options; return { connect: () => connectSocket(), start: () => {}, cancel: () => {}, ack: () => {}, isReady: () => true, close: () => {} } },
    playerFactory: () => ({ start: () => {}, enqueue: () => { state.playedFrames++ }, end: () => {}, stop: () => {}, dispose: () => {} }),
    idFactory: (() => { let id = 0; return () => `id-${++id}` })(),
    previewMs: 3000,
  })
  return { controller, state, clock, get captureOptions() { return captureOptions }, get socketOptions() { return socketOptions },
    setQuestionId: (questionId: string | null) => { session.currentQuestion = questionId ? { questionId } : null },
    setSocketConnect: (fn: () => Promise<void>) => { connectSocket = fn },
    setSealCapture: (fn: () => Promise<void>) => { sealCapture = fn } }
}
const segment = (context: VoiceContext): CapturedSegment => ({ context: { ...context }, sourceSampleRate: 48000, sampleCount: 100, audio: new Blob(['audio'], { type: 'audio/wav' }) })

test('microphone capture failure closes the voice ticket and reports a recoverable error', async () => {
  const closed: string[] = []
  const h = harness({ apiOverrides: {
    closeVoiceSession: async (_id: string, voiceSessionId: string) => { closed.push(voiceSessionId); return {} },
  } })
  await h.controller.start()
  h.captureOptions.onError('麦克风没有持续传来音频，已暂停语音。')
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.controller.state.phase, 'ERROR')
  assert.equal(h.controller.state.transport, 'CLOSED')
  assert.match(h.controller.state.failure?.message || '', /麦克风没有持续传来音频/)
  assert.deepEqual(closed, ['v1'])
})

test('transcription failure pauses microphone capture and retains the failed segment', async () => {
  const closed: string[] = []
  const h = harness({
    transcribe: async () => { throw Object.assign(new Error('语音服务暂时不可用，请重试'), { status: 502 }) },
    apiOverrides: { closeVoiceSession: async (_id: string, voiceSessionId: string) => { closed.push(voiceSessionId); return {} } },
  })
  await h.controller.start()
  h.captureOptions.onSegment(segment(h.state.context!))
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.controller.state.phase, 'PAUSED')
  assert.equal(h.state.captureStops, 1)
  assert.deepEqual(closed, ['v1'])
  assert.equal(h.controller.state.segments[0]?.state, 'FAILED')
  assert.ok(h.controller.state.segments[0]?.audio)
})

test('a transcript starts a three second preview; candidate speech cancels it and a later preview submits once', async () => {
  const h = harness(); await h.controller.start()
  h.captureOptions.onSegment(segment(h.state.context!))
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.state.draft, '识别内容')
  assert.equal(h.controller.state.phase, 'REVIEW')
  h.clock.advance(2900)
  h.captureOptions.onCandidate()
  h.clock.advance(500)
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.state.sent.length, 0)
  h.captureOptions.onState({ captureActive: true, speechActive: false, captureCandidate: false, retainedBytes: 0 })
  h.captureOptions.onSegment(segment(h.controller.state.context!))
  await new Promise(resolve => setTimeout(resolve, 0))
  h.clock.advance(3000)
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.state.sent.length, 1)
  assert.equal(h.state.sent[0].answer, '识别内容\n识别内容')
})

test('pause invalidates capture context before sealing and a user edit cancels auto-submit', async () => {
  const h = harness(); await h.controller.start()
  const before = h.controller.state.context!
  let stopObservedContext: VoiceContext | null = null
  h.captureOptions.onSegment(segment(before)); await new Promise(resolve => setTimeout(resolve, 0))
  h.controller.onDraftEdited()
  h.clock.advance(5000); await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.state.sent.length, 0)
  await h.controller.pause('hidden')
  stopObservedContext = h.controller.state.context
  assert.ok(stopObservedContext && stopObservedContext.epoch > before.epoch)
  assert.equal(h.controller.state.phase, 'PAUSED')
})

test('speech sealed during the deadline barrier is transcribed before any automatic submission', async () => {
  let resolveAsr!: (value: { sessionId: string; questionId: string; requestId: string; text: string; durationMs: number }) => void
  let requestCount = 0; let asrRequestId = ''
  const h = harness({ transcribe: (_id, request) => {
    requestCount++
    return requestCount === 1
      ? Promise.resolve({ sessionId: 's1', questionId: 'q1', requestId: request.requestId, text: '已有回答', durationMs: 1 })
      : new Promise(resolve => { asrRequestId = request.requestId; resolveAsr = resolve })
  } })
  await h.controller.start()
  h.captureOptions.onSegment(segment(h.controller.state.context!))
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.controller.state.phase, 'REVIEW')
  let releaseSeal!: () => void; let seals = 0
  h.setSealCapture(() => ++seals === 1 ? new Promise<void>(resolve => { releaseSeal = resolve }) : Promise.resolve())
  h.clock.advance(3000)
  await new Promise(resolve => setImmediate(resolve))
  h.captureOptions.onState({ captureActive: true, speechActive: true, captureCandidate: true, retainedBytes: 64 })
  h.captureOptions.onSegment(segment(h.controller.state.context!))
  assert.equal(h.state.sent.length, 0)
  releaseSeal()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(h.state.sent.length, 0)
  assert.equal(requestCount, 2)
  h.captureOptions.onState({ captureActive: true, speechActive: false, captureCandidate: false, retainedBytes: 0 })
  resolveAsr({ sessionId: 's1', questionId: 'q1', requestId: asrRequestId, text: '屏障期间的语音', durationMs: 1000 })
  await new Promise(resolve => setTimeout(resolve, 0))
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.controller.state.phase, 'REVIEW')
  assert.equal(h.state.sent.length, 0)
  h.clock.advance(3000)
  await new Promise(resolve => setTimeout(resolve, 0))
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.state.sent.length, 1)
  assert.equal(h.state.sent[0].answer, '已有回答\n屏障期间的语音')
})

test('page hide fences audio events from the old WebSocket generation', async () => {
  const h = harness(); await h.controller.start()
  const oldSocket = h.socketOptions
  oldSocket.onEvent({ type: 'session.ready' })
  oldSocket.onEvent({ type: 'speech.started', requestId: 'id-1', questionId: 'q1', streamId: 7, sampleRate: 24000 })
  await h.controller.pause('hidden')
  oldSocket.onEvent({ type: 'audio', frame: { streamId: 7 } })
  assert.equal(h.state.playedFrames, 0)
  assert.equal(h.controller.state.phase, 'PAUSED')
})

test('a voice ticket that arrives after dispose is closed without starting microphone capture', async () => {
  let resolveTicket!: (ticket: { voiceSessionId: string; ticket: string; ticketExpiresAt: string; websocketPath: string; protocolVersion: 1 }) => void
  const closed: string[] = []
  const h = harness({ apiOverrides: {
    createVoiceSession: () => new Promise(resolve => { resolveTicket = resolve }),
    closeVoiceSession: async (_id: string, voiceSessionId: string) => { closed.push(voiceSessionId); return {} },
  } })
  const starting = h.controller.start()
  while (!resolveTicket) await Promise.resolve()
  h.controller.dispose()
  h.controller.dispose()
  resolveTicket({ voiceSessionId: 'late-ticket', ticket: 'short-lived', ticketExpiresAt: 'later', websocketPath: '/api/interview-voice/ws', protocolVersion: 1 })
  await starting
  assert.equal(h.state.captureStarts, 0)
  assert.deepEqual(closed, ['late-ticket'])
  assert.equal(h.controller.state.phase, 'OFF')
})

test('dispose closes an active voice ticket using the session captured before context cleanup', async () => {
  const closed: string[] = []
  const h = harness({ apiOverrides: { closeVoiceSession: async (_id: string, voiceSessionId: string) => { closed.push(voiceSessionId); return {} } } })
  await h.controller.start()
  h.controller.dispose()
  h.controller.dispose()
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.deepEqual(closed, ['v1'])
  assert.equal(h.state.captureDisposals, 1)
})

test('route leave during WebSocket connect closes the ticket and never starts microphone capture', async () => {
  let resolveConnect!: () => void
  const closed: string[] = []
  const h = harness({ apiOverrides: {
    closeVoiceSession: async (_id: string, voiceSessionId: string) => { closed.push(voiceSessionId); return {} },
  } })
  h.setSocketConnect(() => new Promise<void>(resolve => { resolveConnect = resolve }))
  const starting = h.controller.start()
  while (!h.socketOptions) await Promise.resolve()
  while (!resolveConnect) await Promise.resolve()
  h.setQuestionId(null)
  h.controller.syncContext()
  resolveConnect()
  await starting
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.state.captureStarts, 0)
  assert.deepEqual(closed, ['v1'])
  assert.equal(h.controller.state.phase, 'OFF')
})
