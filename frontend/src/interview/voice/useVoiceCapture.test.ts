import assert from 'node:assert/strict'
import test from 'node:test'
import { describeMicrophoneError, useVoiceCapture, type CaptureOptions } from './useVoiceCapture.ts'
import type { CaptureEvent } from './voiceCaptureProtocol.ts'
import type { VoiceContext } from './voiceContracts.ts'

class FakeTrack extends EventTarget {
  stopped = false
  stop() { this.stopped = true }
}
class FakeStream {
  track = new FakeTrack()
  getTracks() { return [this.track] }
  getAudioTracks() { return [this.track] }
}
class FakePort {
  onmessage: ((event: MessageEvent) => void) | null = null
  messages: unknown[] = []
  postMessage(message: unknown) {
    this.messages.push(message)
    const value = message as { type?: string; barrierId?: number }
    if (value.type === 'seal') this.emit({ type: 'sealed', barrierId: value.barrierId!, lastSequence: -1, lastSample: 0 })
  }
  emit(data: CaptureEvent) { this.onmessage?.({ data } as MessageEvent) }
}
class FakeWorkletNode {
  port = new FakePort()
  connect() { return this }
  disconnect() {}
  constructor(_context: unknown, _name: string, _options: unknown) {}
}
class FakeAudioContext {
  sampleRate = 1000
  destination = {}
  audioWorklet = { addModule: async (_url: string) => {} }
  async resume() {}
  createMediaStreamSource(_stream: MediaStream) { return { connect() {}, disconnect() {} } as unknown as MediaStreamAudioSourceNode }
  createGain() { return { gain: { value: 1 }, connect() {}, disconnect() {} } as unknown as GainNode }
}
const context: VoiceContext = { scopeKey: 'scope', sessionId: 'session', questionId: 'question', epoch: 1 }
function deliver(node: FakeWorkletNode, sequence: number, firstSample: number, sampleCount = 20, rms = 0) {
  const pcm = new Int16Array(sampleCount).fill(rms > 0 ? 1000 : 0)
  node.port.emit({ type: 'frame', generation: 0, sequence, sampleRate: 1000, firstSample,
    sampleCount, rms, pcm: pcm.buffer })
}
async function startedCapture(options: Partial<CaptureOptions> = {}) {
  const stream = new FakeStream()
  let node: FakeWorkletNode | null = null
  const FakeConstructor = class extends FakeWorkletNode { constructor(...args: ConstructorParameters<typeof FakeWorkletNode>) { super(...args); node = this } }
  const capture = useVoiceCapture({
    mediaDevices: { getUserMedia: async () => stream as unknown as MediaStream }, workletUrl: '/capture.js',
    ...options,
    audioContext: options.audioContext || new FakeAudioContext() as unknown as AudioContext,
    WorkletNode: (options.WorkletNode || FakeConstructor) as unknown as typeof AudioWorkletNode })
  await capture.start(context)
  assert.ok(node)
  return { capture, node: node!, stream }
}

test('start keeps one device stream, candidate callback is immediate, and speech segment contains one second pre-roll', async () => {
  const segments: Array<{ sampleCount: number; sourceSampleRate: number; audio: Blob }> = []
  let candidates = 0
  const { capture, node, stream } = await startedCapture({ onCandidate: () => candidates++,
    onSegment: segment => segments.push(segment) })
  for (let sequence = 0; sequence < 50; sequence++) deliver(node, sequence, sequence * 20)
  for (let sequence = 50; sequence < 62; sequence++) deliver(node, sequence, sequence * 20, 20, 0.08)
  assert.equal(candidates, 1)
  for (let sequence = 62; sequence < 162; sequence++) deliver(node, sequence, sequence * 20)
  assert.equal(segments.length, 1)
  assert.equal(segments[0]!.sourceSampleRate, 1000)
  assert.equal(segments[0]!.sampleCount, 3000)
  assert.equal(new DataView(await segments[0]!.audio.arrayBuffer()).getUint32(40, true), 6000)
  await capture.dispose(); await capture.dispose()
  assert.equal(stream.track.stopped, true)
})

test('stop keeps a sealed partial segment without firing the normal segment callback', async () => {
  let emitted = 0
  const { capture, node } = await startedCapture({ onSegment: () => emitted++ })
  for (let sequence = 0; sequence < 50; sequence++) deliver(node, sequence, sequence * 20)
  for (let sequence = 50; sequence < 62; sequence++) deliver(node, sequence, sequence * 20, 20, 0.08)
  const partial = await capture.stop({ keepPartial: true })
  assert.ok(partial)
  assert.equal(partial.sampleCount, 1000)
  assert.equal(emitted, 0)
  assert.equal(await capture.stop({ keepPartial: true }), null)
})

test('late microphone permission is stopped and never creates a worklet', async () => {
  let resolvePermission!: (stream: MediaStream) => void
  const stream = new FakeStream()
  let createdNodes = 0
  const capture = useVoiceCapture({ audioContext: new FakeAudioContext() as unknown as AudioContext,
    mediaDevices: { getUserMedia: () => new Promise(resolve => { resolvePermission = resolve }) }, workletUrl: '/capture.js',
    WorkletNode: class extends FakeWorkletNode { constructor(...args: ConstructorParameters<typeof FakeWorkletNode>) { super(...args); createdNodes++ } } as unknown as typeof AudioWorkletNode })
  const starting = capture.start(context)
  await capture.stop({ keepPartial: false })
  resolvePermission(stream as unknown as MediaStream)
  await starting
  assert.equal(stream.track.stopped, true)
  assert.equal(createdNodes, 0)
})

test('buffer exhaustion stops capture rather than retaining unbounded PCM', async () => {
  const states: Array<{ captureActive: boolean }> = []
  const { capture, node, stream } = await startedCapture({ getBufferBudget: () => ({ remainingBytes: 0, freeSegmentSlots: 2, remainingAnswerMs: 1000 }),
    onState: state => states.push(state) })
  deliver(node, 0, 0)
  assert.equal(states.at(-1)?.captureActive, false)
  assert.equal(stream.track.stopped, true)
  await capture.dispose()
})

test('a later speech segment does not duplicate samples already delivered in the previous segment', async () => {
  const segments: number[] = []
  const { capture, node } = await startedCapture({ onSegment: segment => segments.push(segment.sampleCount) })
  for (let sequence = 0; sequence < 50; sequence++) deliver(node, sequence, sequence * 20)
  for (let sequence = 50; sequence < 62; sequence++) deliver(node, sequence, sequence * 20, 20, 0.08)
  for (let sequence = 62; sequence < 162; sequence++) deliver(node, sequence, sequence * 20)
  for (let sequence = 162; sequence < 177; sequence++) deliver(node, sequence, sequence * 20, 20, 0.08)
  for (let sequence = 177; sequence < 277; sequence++) deliver(node, sequence, sequence * 20)
  assert.deepEqual(segments, [3000, 2300])
  await capture.dispose()
})

test('long silence never creates an uploadable segment or stops capture', async () => {
  const segments: unknown[] = []
  const states: Array<{ captureActive: boolean }> = []
  const { capture, node, stream } = await startedCapture({ onSegment: segment => segments.push(segment),
    onState: state => states.push(state) })
  for (let sequence = 0; sequence < 1000; sequence++) deliver(node, sequence, sequence * 20)
  assert.equal(segments.length, 0)
  assert.equal(states.at(-1)?.captureActive, true)
  assert.equal(stream.track.stopped, false)
  await capture.dispose()
  assert.equal(stream.track.stopped, true)
})

test('continuous speech is split at the 30 second WAV limit and capture remains active', async () => {
  const sampleCounts: number[] = []
  const states: Array<{ captureActive: boolean }> = []
  const { capture, node } = await startedCapture({ onSegment: segment => sampleCounts.push(segment.sampleCount),
    onState: state => states.push(state) })
  for (let sequence = 0; sequence < 50; sequence++) deliver(node, sequence, sequence * 20)
  for (let sequence = 50; sequence < 1600; sequence++) deliver(node, sequence, sequence * 20, 20, 0.08)
  assert.deepEqual(sampleCounts, [30_000])
  assert.equal(states.at(-1)?.captureActive, true)
  await capture.dispose()
})

test('late worklet module load releases the microphone after stop', async () => {
  let resolveModule!: () => void
  const stream = new FakeStream()
  let createdNodes = 0
  const audioContext = new FakeAudioContext()
  audioContext.audioWorklet.addModule = () => new Promise(resolve => { resolveModule = resolve })
  const capture = useVoiceCapture({ audioContext: audioContext as unknown as AudioContext,
    mediaDevices: { getUserMedia: async () => stream as unknown as MediaStream },
    workletUrl: '/late.js',
    WorkletNode: class extends FakeWorkletNode { constructor(...args: ConstructorParameters<typeof FakeWorkletNode>) { super(...args); createdNodes++ } } as unknown as typeof AudioWorkletNode })
  const starting = capture.start(context)
  while (!resolveModule) await Promise.resolve()
  await capture.stop({ keepPartial: false })
  resolveModule()
  await starting
  assert.equal(stream.track.stopped, true)
  assert.equal(createdNodes, 0)
})

test('microphone permission and device failures have stable user-facing messages', () => {
  assert.match(describeMicrophoneError({ name: 'NotAllowedError' }), /权限被拒绝/)
  assert.match(describeMicrophoneError({ name: 'NotFoundError' }), /没有可用的麦克风/)
  assert.match(describeMicrophoneError({ name: 'NotReadableError' }), /麦克风被占用/)
})
