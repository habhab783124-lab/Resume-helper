import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { useInterviewVoice } from './useInterviewVoice.js'

const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
function setup(options = {}) {
  const context = ref({ sessionId: 's1', questionId: 'q1', status: 'ACTIVE', pending: null, blocked: false })
  const draft = ref('原草稿')
  const calls = [], revoked = [], events = []
  const recorder = { state: ref('idle'), blob: ref(null), durationSeconds: ref(0), error: ref(''),
    async start() { events.push('record'); this.state.value = 'recording' },
    stop() { this.state.value = 'recorded'; this.blob.value = new Blob(['audio'], { type: 'audio/webm' }) },
    cancel() { this.state.value = 'idle'; this.blob.value = null }, dispose() { events.push('dispose-recorder'); this.cancel() } }
  const speech = { state: ref('idle'), error: ref(''), play(...args) { events.push(['play', ...args]) }, stop() { events.push('stop-speech'); this.state.value = 'stopped' }, dispose() { events.push('dispose-speech'); this.state.value = 'idle' } }
  const api = { getSpeechCapabilities: async () => ({ asrEnabled: true, ttsEnabled: true, maxRecordingSeconds: 60, maxAudioBytes: 100 }),
    transcribe: async (id, body, signal) => { calls.push({ id, body, signal }); return { sessionId: id, questionId: body.questionId, requestId: body.requestId, text: '转写全文' } }, ...options.api }
  const voice = useInterviewVoice({ api, getContext: () => context.value, getDraft: () => draft.value, setDraft: value => { draft.value = value }, createRecorder: limits => { events.push(limits); return recorder }, createSpeech: () => speech,
    createUrl: () => 'blob:recording', revokeUrl: url => revoked.push(url), pauseReplay: () => events.push('pause-replay'), requestId: () => 'uuid-1', ...options.dependencies })
  voice.syncContext()
  return { voice, context, draft, recorder, speech, calls, revoked, events }
}
async function recording(fixture) { await fixture.voice.loadCapabilities(); await fixture.voice.startRecording(); fixture.voice.stopRecording() }

test('transcription creates a preview without submitting or overwriting; confirmation controls full draft', async () => {
  const f = setup(); await recording(f); await f.voice.transcribe()
  assert.equal(f.draft.value, '原草稿')
  assert.equal(f.voice.preview.value.text, '转写全文')
  assert.equal(f.calls.length, 1)
  f.voice.acceptPreview('append'); assert.equal(f.draft.value, '原草稿\n转写全文'); assert.equal(f.voice.preview.value, null)
  await f.voice.transcribe(); f.voice.acceptPreview('replace'); assert.equal(f.draft.value, '转写全文')
  await f.voice.transcribe(); f.voice.acceptPreview('keep'); assert.equal(f.draft.value, '转写全文'); assert.equal(f.voice.preview.value, null)
})
test('canceled results are dropped; recording remains available for retry', async () => {
  const delayed = deferred(); const f = setup({ api: { transcribe: () => delayed.promise } }); await recording(f)
  const request = f.voice.transcribe(); f.voice.cancelTranscription(); delayed.resolve({ sessionId: 's1', questionId: 'q1', requestId: 'uuid-1', text: '迟到' }); await request
  assert.equal(f.voice.preview.value, null); assert.ok(f.voice.blob.value); assert.equal(f.voice.transcribing.value, false)
})
test('context changes cancel recorder/audio/request and release URLs; unchanged recovery keeps recording', async () => {
  const delayed = deferred(); const f = setup({ api: { transcribe: () => delayed.promise } }); await recording(f)
  f.voice.syncContext(); assert.ok(f.voice.blob.value)
  const request = f.voice.transcribe(); f.context.value = { ...f.context.value, questionId: 'q2' }; f.voice.syncContext()
  delayed.resolve({ sessionId: 's1', questionId: 'q1', requestId: 'uuid-1', text: '迟到' }); await request
  assert.equal(f.voice.preview.value, null); assert.equal(f.voice.blob.value, null); assert.deepEqual(f.revoked, ['blob:recording']); assert.ok(f.events.includes('dispose-speech'))
})
test('pending context prohibits recording, transcription and accepting an earlier preview', async () => {
  const f = setup(); await recording(f); await f.voice.transcribe(); f.context.value.pending = '已提交'
  assert.equal(f.voice.acceptPreview('replace'), false); await f.voice.startRecording(); await f.voice.transcribe()
  assert.equal(f.calls.length, 1); assert.equal(f.draft.value, '原草稿')
  f.voice.syncContext(); assert.equal(f.voice.preview.value, null)
})
test('validates request IDs and empty/oversized audio before preview or upload', async () => {
  const f = setup({ api: { transcribe: async () => ({ sessionId: 'other', questionId: 'q1', requestId: 'uuid-1', text: 'wrong' }) } }); await recording(f); await f.voice.transcribe()
  assert.equal(f.voice.preview.value, null); assert.match(f.voice.error.value, /格式/)
  f.recorder.blob.value = new Blob([]); await f.voice.transcribe(); assert.match(f.voice.error.value, /空/)
  f.recorder.blob.value = new Blob(['x'.repeat(101)]); await f.voice.transcribe(); assert.match(f.voice.error.value, /大小/)
})
test('capability failure and independent switches keep text available, and retry applies recorder limits', async () => {
  let fail = true; const f = setup({ api: { getSpeechCapabilities: async () => { if (fail) throw new Error('offline'); return { asrEnabled: true, ttsEnabled: false, maxRecordingSeconds: 60, maxAudioBytes: 100 } } } })
  await f.voice.loadCapabilities(); assert.equal(f.voice.capabilityState.value, 'unavailable'); assert.equal(f.voice.busy.value, false)
  fail = false; await f.voice.loadCapabilities(); assert.equal(f.voice.asrEnabled.value, true); assert.equal(f.voice.ttsEnabled.value, false)
  await f.voice.startRecording(); assert.deepEqual(f.events.find(value => typeof value === 'object'), { maxSeconds: 60, maxBytes: 100 }); assert.ok(f.events.indexOf('stop-speech') < f.events.indexOf('record'))
})
test('timeout aborts ASR while preserving audio and draft; 404 never resets the interview', async () => {
  let signal; const f = setup({ api: { transcribe: (_id, _body, value) => { signal = value; return new Promise(() => {}) } }, dependencies: { timeoutMs: 5 } }); await recording(f); await f.voice.transcribe()
  assert.ok(signal.aborted); assert.match(f.voice.error.value, /超时/); assert.ok(f.voice.blob.value); assert.equal(f.draft.value, '原草稿')
  const g = setup({ api: { transcribe: async () => { throw Object.assign(new Error('语音不存在'), { status: 404 }) } } }); await recording(g); await g.voice.transcribe(); assert.equal(g.context.value.sessionId, 's1'); assert.ok(g.voice.blob.value)
})
test('long transcription is preserved in full; dispose clears every resource', async () => {
  const long = '长'.repeat(5001); const f = setup({ api: { transcribe: async (_id, body) => ({ sessionId: 's1', ...body, text: long }) } }); await recording(f); await f.voice.transcribe(); f.voice.acceptPreview('replace')
  assert.equal(f.draft.value, long); assert.match(f.voice.error.value, /5000/)
  f.voice.dispose(); assert.equal(f.voice.blob.value, null); assert.equal(f.voice.preview.value, null); assert.equal(f.voice.recordingUrl.value, ''); assert.equal(f.voice.busy.value, false)
})

test('409 recovery receives a freshness guard so canceled operations cannot apply late session snapshots', async () => {
  let canApply; const f = setup({ api: { transcribe: async () => { throw Object.assign(new Error('state changed'), { status: 409 }) } }, dependencies: { syncSession: async (_context, guard) => { canApply = guard } } })
  await recording(f); await f.voice.transcribe(); assert.equal(typeof canApply, 'function'); assert.equal(canApply(), true)
  f.voice.cancelTranscription(); assert.equal(canApply(), false)
})
test('a hanging 409 recovery expires at the original ASR deadline and unlocks retained audio', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const asr = deferred()
  let signal, canApply, settled = false
  const f = setup({ api: { transcribe: () => asr.promise }, dependencies: { timeoutMs: 100,
    syncSession: (_context, guard, value) => { canApply = guard; signal = value; return new Promise(() => {}) } } })
  await recording(f)
  const retained = f.voice.blob.value
  const request = f.voice.transcribe().then(() => { settled = true })
  t.mock.timers.tick(60)
  asr.reject(Object.assign(new Error('state changed'), { status: 409 }))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(canApply(), true)
  t.mock.timers.tick(39)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(f.voice.transcribing.value, true)
  t.mock.timers.tick(1)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(settled, true, 'recovery must not extend the original deadline')
  await request
  assert.equal(f.voice.transcribing.value, false); assert.equal(f.voice.busy.value, false)
  assert.ok(signal.aborted); assert.equal(canApply(), false); assert.match(f.voice.error.value, /超时/)
  assert.equal(f.voice.blob.value, retained); assert.equal(f.voice.recordingUrl.value, 'blob:recording'); assert.equal(f.draft.value, '原草稿')
})
test('cancel settles a hanging 409 recovery and invalidates its session guard', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let signal, canApply, settled = false
  const f = setup({ api: { transcribe: async () => { throw Object.assign(new Error('state changed'), { status: 409 }) } },
    dependencies: { syncSession: (_context, guard, value) => { canApply = guard; signal = value; return new Promise(() => {}) } } })
  await recording(f)
  const retained = f.voice.blob.value
  const request = f.voice.transcribe().then(() => { settled = true })
  await new Promise(resolve => setImmediate(resolve))
  f.voice.cancelTranscription()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(settled, true, 'cancellation must settle even when recovery ignores abort')
  await request
  assert.equal(f.voice.busy.value, false); assert.ok(signal.aborted); assert.equal(canApply(), false)
  assert.equal(f.voice.blob.value, retained); assert.equal(f.draft.value, '原草稿'); assert.equal(f.voice.preview.value, null)
})
test('a late recovery response after timeout cannot apply its session snapshot', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const recovery = deferred()
  let applied = 0
  const f = setup({ api: { transcribe: async () => { throw Object.assign(new Error('state changed'), { status: 409 }) } },
    dependencies: { timeoutMs: 100, syncSession: async (_context, canApply) => { await recovery.promise; if (canApply()) applied += 1 } } })
  await recording(f)
  const request = f.voice.transcribe()
  await new Promise(resolve => setImmediate(resolve))
  t.mock.timers.tick(100)
  await new Promise(resolve => setImmediate(resolve))
  recovery.resolve()
  await request
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(applied, 0); assert.match(f.voice.error.value, /超时/); assert.equal(f.draft.value, '原草稿')
})
test('cancel settles ASR even when its transport ignores abort', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let settled = false
  const f = setup({ api: { transcribe: () => new Promise(() => {}) } })
  await recording(f)
  const request = f.voice.transcribe().then(() => { settled = true })
  f.voice.cancelTranscription()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(settled, true)
  await request
  assert.equal(f.voice.busy.value, false); assert.ok(f.voice.blob.value); assert.equal(f.draft.value, '原草稿')
})
test('new recording clears old preview/replay and stops TTS; question and recording playback never overlap', async () => {
  const f = setup(); await recording(f); await f.voice.transcribe(); f.voice.playQuestion(); assert.ok(f.events.some(event => Array.isArray(event) && event[0] === 'play'))
  f.voice.replayRecording(); assert.equal(f.events.at(-1), 'stop-speech')
  await f.voice.startRecording(); assert.equal(f.voice.preview.value, null); assert.equal(f.voice.recordingUrl.value, '')
  const plays = f.events.filter(event => Array.isArray(event) && event[0] === 'play').length
  f.voice.playQuestion(); assert.equal(f.events.filter(event => Array.isArray(event) && event[0] === 'play').length, plays)
  f.voice.dispose()
})

test('capability refresh preserves retained recording, replay URL and accepted-later preview even if only TTS is unavailable', async () => {
  const f = setup({ api: { getSpeechCapabilities: async () => ({ asrEnabled: true, ttsEnabled: false, maxRecordingSeconds: 60, maxAudioBytes: 100 }) } })
  await recording(f); await f.voice.transcribe()
  const retained = f.voice.blob.value, url = f.voice.recordingUrl.value
  await f.voice.loadCapabilities()
  assert.equal(f.voice.blob.value, retained); assert.equal(f.voice.recordingUrl.value, url); assert.equal(f.voice.recorderState.value, 'recorded'); assert.equal(f.voice.preview.value.text, '转写全文')
  assert.deepEqual(f.revoked, [])
  f.voice.dispose()
})
test('changed capability limits preserve audio but validate old duration before upload and apply new limits to the next recording', async () => {
  let seconds = 60
  const f = setup({ api: { getSpeechCapabilities: async () => ({ asrEnabled: true, ttsEnabled: false, maxRecordingSeconds: seconds, maxAudioBytes: 100 }) } })
  await recording(f); f.recorder.durationSeconds.value = 30
  const retained = f.voice.blob.value; seconds = 20; await f.voice.loadCapabilities()
  assert.equal(f.voice.blob.value, retained)
  await f.voice.transcribe(); assert.equal(f.calls.length, 0); assert.match(f.voice.error.value, /时长/)
  await f.voice.startRecording(); assert.deepEqual(f.events.filter(value => typeof value === 'object').at(-1), { maxSeconds: 20, maxBytes: 100 })
  f.voice.dispose()
})
