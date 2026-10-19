import type { CapturedSegment, VoiceContext } from './voiceContracts.ts'
import type { CaptureBarrier, CaptureEvent, CaptureFrame } from './voiceCaptureProtocol.ts'
import { createVoiceVad } from './voiceVad.ts'
import { encodeMonoWav, MAX_WAV_BYTES, MAX_WAV_SECONDS, WAV_HEADER_BYTES } from './wavAudio.ts'

type Clock = { now(): number; setTimeout: typeof setTimeout; clearTimeout: typeof clearTimeout }
const defaultClock: Clock = {
  now: () => performance.now(),
  setTimeout: globalThis.setTimeout.bind(globalThis),
  clearTimeout: globalThis.clearTimeout.bind(globalThis),
}
type CaptureState = { captureActive: boolean; speechActive: boolean; captureCandidate: boolean; retainedBytes: number }
type BufferBudget = { remainingBytes: number; freeSegmentSlots: number; remainingAnswerMs: number }
export type CaptureOptions = {
  audioContext: AudioContext
  mediaDevices?: Pick<MediaDevices, 'getUserMedia'>
  workletUrl: string
  clock?: Clock
  getBufferBudget?: () => BufferBudget
  onCandidate?: () => void
  onSegment?: (segment: CapturedSegment) => void
  onState?: (state: CaptureState) => void
  onError?: (message: string) => void
  WorkletNode?: new (context: BaseAudioContext, name: string, options?: AudioWorkletNodeOptions) => AudioWorkletNode
}

type StoredFrame = { firstSample: number; samples: Int16Array; sampleRate: number }
type ActiveSegment = { context: VoiceContext; sampleRate: number; firstSample: number; chunks: Int16Array[]; sampleCount: number }

const PRE_ROLL_MS = 1000
const MAX_ANSWER_MS = 120_000
const FRAME_BUDGET_BYTES = 4096
const FRAME_TIMEOUT_MS = 4000
const MUTE_TIMEOUT_MS = 2500

/** One microphone and one worklet own all capture, VAD, pre-roll, and segment boundaries. */
export function useVoiceCapture({ audioContext, mediaDevices = globalThis.navigator?.mediaDevices,
  workletUrl, clock = defaultClock,
  getBufferBudget = () => ({ remainingBytes: MAX_WAV_BYTES, freeSegmentSlots: 3, remainingAnswerMs: MAX_ANSWER_MS }),
  onCandidate = () => {}, onSegment = () => {}, onState = () => {}, onError = () => {},
  WorkletNode = globalThis.AudioWorkletNode }: CaptureOptions) {
  let generation = 0
  let context: VoiceContext | null = null
  let stream: MediaStream | null = null
  let source: MediaStreamAudioSourceNode | null = null
  let worklet: AudioWorkletNode | null = null
  let silentGain: GainNode | null = null
  let active = false
  let disposed = false
  let workletGeneration = 0
  let captureCandidate = false
  let speechActive = false
  let ring: StoredFrame[] = []
  let ringSamples = 0
  let current: ActiveSegment | null = null
  let completedSamples = 0
  let expectedSequence = 0
  let expectedFirstSample = 0
  let lastDeliveredSample = 0
  let barrierId = 0
  let pendingBarrier: { id: number; resolve: () => void } | null = null
  let stopPartial: CapturedSegment | null = null
  let keepPartialDuringStop = false
  let candidateTimer: ReturnType<typeof setTimeout> | null = null
  let frameTimer: ReturnType<typeof setTimeout> | null = null
  let muteTimer: ReturnType<typeof setTimeout> | null = null
  const vad = createVoiceVad({ now: clock.now,
    onCandidate: () => {
      captureCandidate = true
      if (candidateTimer !== null) clock.clearTimeout(candidateTimer)
      candidateTimer = clock.setTimeout(() => { captureCandidate = false; candidateTimer = null; publish() }, 500)
      onCandidate(); publish()
    },
    onSpeechStart: () => {
      speechActive = true; captureCandidate = false
      if (candidateTimer !== null) clock.clearTimeout(candidateTimer)
      candidateTimer = null
      beginFromPreRoll(); publish()
    },
    onSpeechEnd: () => { speechActive = false; captureCandidate = false; finishCurrent(true); publish() } })

  function retainedBytes() {
    return ringSamples * 2 + (current?.sampleCount || 0) * 2
  }
  function publish() {
    onState({ captureActive: active, speechActive, captureCandidate, retainedBytes: retainedBytes() })
  }
  function closeTracks(target: MediaStream | null) {
    target?.getTracks().forEach(track => { try { track.stop() } catch { /* Device may already be closed. */ } })
  }
  function clearFrameTimer() {
    if (frameTimer !== null) clock.clearTimeout(frameTimer)
    frameTimer = null
  }
  function clearMuteTimer() {
    if (muteTimer !== null) clock.clearTimeout(muteTimer)
    muteTimer = null
  }
  function armMuteTimer() {
    clearMuteTimer()
    if (!active) return
    muteTimer = clock.setTimeout(() => {
      muteTimer = null
      if (active) failCapture('麦克风暂时无法提供声音，已暂停语音。请检查输入设备后重新开启。')
    }, MUTE_TIMEOUT_MS)
  }
  function armFrameTimer() {
    clearFrameTimer()
    if (!active) return
    frameTimer = clock.setTimeout(() => {
      frameTimer = null
      if (active) failCapture('麦克风没有持续传来音频，已暂停语音。请检查输入设备后重新开启。')
    }, FRAME_TIMEOUT_MS)
  }
  function failCapture(message = '') {
    detach(false)
    if (message) onError(message)
    publish()
  }
  function addRing(frame: StoredFrame) {
    ring.push(frame)
    ringSamples += frame.samples.length
    const keep = Math.round(frame.sampleRate * PRE_ROLL_MS / 1000)
    while (ringSamples > keep && ring.length) {
      const excess = ringSamples - keep
      const first = ring[0]!
      if (first.samples.length <= excess) { ring.shift(); ringSamples -= first.samples.length }
      else {
        first.samples = first.samples.subarray(excess).slice()
        first.firstSample += excess
        ringSamples -= excess
      }
    }
  }
  function beginFromPreRoll() {
    if (current || !context || ring.length === 0) return
    const first = ring[0]!
    const startAt = Math.max(first.firstSample, lastDeliveredSample)
    const chunks = ring.flatMap(frame => {
      const from = Math.max(0, startAt - frame.firstSample)
      return from >= frame.samples.length ? [] : [frame.samples.subarray(from).slice()]
    })
    const sampleCount = chunks.reduce((total, chunk) => total + chunk.length, 0)
    if (!sampleCount) return
    current = { context: { ...context }, sampleRate: first.sampleRate, firstSample: startAt, chunks, sampleCount }
  }
  function makeSegment(segment: ActiveSegment): CapturedSegment {
    const samples = new Int16Array(segment.sampleCount)
    let offset = 0
    for (const chunk of segment.chunks) { samples.set(chunk, offset); offset += chunk.length }
    return { context: { ...segment.context }, sourceSampleRate: segment.sampleRate, sampleCount: samples.length,
      audio: encodeMonoWav(samples, segment.sampleRate) }
  }
  function finishCurrent(emit: boolean): CapturedSegment | null {
    if (!current || current.sampleCount === 0) { current = null; return null }
    const finished = current
    current = null
    const segment = makeSegment(finished)
    lastDeliveredSample = Math.max(lastDeliveredSample, finished.firstSample + finished.sampleCount)
    completedSamples += finished.sampleCount
    if (emit) onSegment(segment)
    return segment
  }
  function ensureBudget(frameBytes: number) {
    const budget = getBufferBudget()
    const remainingAnswerMs = Math.min(budget.remainingAnswerMs, MAX_ANSWER_MS - completedSamples * 1000 / audioContext.sampleRate
      - (current?.sampleCount || 0) * 1000 / audioContext.sampleRate)
    if (budget.freeSegmentSlots <= 0 || budget.remainingBytes < frameBytes + WAV_HEADER_BYTES
        || remainingAnswerMs <= 0) {
      failCapture('连续语音缓存空间不足，已暂停采集。请先处理已有片段后继续。')
      return false
    }
    return true
  }
  function appendToSegment(samples: Int16Array, firstSample: number, rate: number) {
    const maxSamples = Math.min(Math.floor(rate * MAX_WAV_SECONDS), Math.floor((MAX_WAV_BYTES - WAV_HEADER_BYTES) / 2))
    let offset = 0
    while (offset < samples.length) {
      if (!current) current = { context: { ...context! }, sampleRate: rate, firstSample: firstSample + offset, chunks: [], sampleCount: 0 }
      const room = maxSamples - current.sampleCount
      const count = Math.min(room, samples.length - offset)
      current.chunks.push(samples.subarray(offset, offset + count).slice())
      current.sampleCount += count
      offset += count
      if (current.sampleCount >= maxSamples) finishCurrent(true)
    }
  }
  function handleFrame(frame: CaptureFrame) {
    if (!active) return
    if (frame.generation !== workletGeneration) return
    if (frame.sequence !== expectedSequence || frame.firstSample !== expectedFirstSample
        || frame.sampleRate !== audioContext.sampleRate || frame.sampleCount * 2 !== frame.pcm.byteLength) {
      failCapture('采集帧顺序异常，已暂停连续语音。'); return
    }
    expectedSequence += 1
    expectedFirstSample += frame.sampleCount
    const samples = new Int16Array(frame.pcm)
    if (!ensureBudget(samples.byteLength)) return
    const priorSegment = current
    const stored = { firstSample: frame.firstSample, samples, sampleRate: frame.sampleRate }
    addRing(stored)
    if (priorSegment || speechActive) appendToSegment(samples, frame.firstSample, frame.sampleRate)
    vad.feed(frame.rms, frame.sampleCount * 1000 / frame.sampleRate)
    worklet?.port.postMessage({ type: 'frame.ack', sequence: frame.sequence })
    armFrameTimer()
    publish()
  }
  function handleEvent(event: CaptureEvent) {
    if (event.type === 'frame') { handleFrame(event); return }
    if (event.type === 'capture.overrun') {
      failCapture('采集处理暂时跟不上麦克风，已暂停并保留当前片段。')
      return
    }
    const barrier = event as CaptureBarrier
    if (pendingBarrier?.id === barrier.barrierId) {
      pendingBarrier.resolve()
      pendingBarrier = null
    }
  }
  function sealWorklet() {
    if (!worklet || !active) return Promise.resolve()
    const id = ++barrierId
    return new Promise<void>(resolve => {
      pendingBarrier = { id, resolve }
      worklet!.port.postMessage({ type: 'seal', barrierId: id })
    })
  }
  function detach(emitState = true) {
    active = false
    clearFrameTimer()
    clearMuteTimer()
    worklet?.port.postMessage({ type: 'stop' })
    if (worklet) { worklet.port.onmessage = null; try { worklet.disconnect() } catch { } }
    try { source?.disconnect() } catch { }
    try { silentGain?.disconnect() } catch { }
    source = null; worklet = null; silentGain = null
    closeTracks(stream); stream = null
    if (emitState) publish()
  }
  async function start(nextContext: VoiceContext) {
    if (disposed) return
    const my = ++generation
    detach()
    context = { ...nextContext }
    ring = []; ringSamples = 0; current = null; completedSamples = 0
    expectedSequence = 0; expectedFirstSample = 0; lastDeliveredSample = 0; captureCandidate = false; speechActive = false
    stopPartial = null; vad.reset()
    if (!mediaDevices?.getUserMedia || !WorkletNode || !audioContext.audioWorklet) {
      onError('当前浏览器不支持连续语音，请改用文字回答。')
      publish(); return
    }
    let requested: MediaStream
    try {
      requested = await mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }, video: false })
    } catch (cause) { if (my === generation) { onError(describeMicrophoneError(cause)); publish() } return }
    if (my !== generation || disposed) { closeTracks(requested); return }
    stream = requested
    try { await audioContext.audioWorklet.addModule(workletUrl) }
    catch (cause) { if (my === generation) {
      console.error('[interview voice] AudioWorklet module load failed', cause)
      detach(); onError('连续语音模块加载失败，请改用文字回答。')
    }
      else closeTracks(requested); return }
    if (my !== generation || disposed) { closeTracks(requested); return }
    try {
      await audioContext.resume()
      const node = new WorkletNode(audioContext, 'resume-helper-voice-capture', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] })
      workletGeneration = 0
      node.port.onmessage = event => { if (worklet === node) handleEvent(event.data as CaptureEvent) }
      source = audioContext.createMediaStreamSource(requested)
      silentGain = audioContext.createGain(); silentGain.gain.value = 0
      source.connect(node); node.connect(silentGain); silentGain.connect(audioContext.destination)
      worklet = node
      active = true
      armFrameTimer()
      requested.getAudioTracks().forEach(track => {
        track.addEventListener('ended', () => {
          if (stream === requested && active) failCapture('麦克风已断开，请重新连接或改用文字回答。')
        }, { once: true })
        track.addEventListener('mute', () => { if (stream === requested && active) armMuteTimer() })
        track.addEventListener('unmute', () => { if (stream === requested) clearMuteTimer() })
        if (track.muted) armMuteTimer()
      })
      publish()
    } catch (cause) { if (my === generation) {
      console.error('[interview voice] microphone capture start failed', cause)
      detach(); onError('录音启动失败，请改用文字回答。')
    } }
  }
  async function seal() {
    if (!active) return
    clearFrameTimer()
    clearMuteTimer()
    await sealWorklet()
    finishCurrent(true)
    if (candidateTimer !== null) clock.clearTimeout(candidateTimer)
    candidateTimer = null
    captureCandidate = false
    speechActive = false
    vad.reset()
    active = false
    publish()
  }
  function resume() {
    if (!worklet || disposed) return
    expectedSequence = 0; expectedFirstSample = 0; lastDeliveredSample = 0
    ring = []; ringSamples = 0; captureCandidate = false; speechActive = false
    vad.reset()
    workletGeneration += 1
    worklet.port.postMessage({ type: 'resume' })
    active = true
    armFrameTimer()
    if (stream?.getAudioTracks().some(track => track.muted)) armMuteTimer()
    publish()
  }
  function setPlayback(value: boolean) { vad.setPlayback(value) }
  async function stop(options: { keepPartial: boolean }): Promise<CapturedSegment | null> {
    const my = ++generation
    clearFrameTimer()
    clearMuteTimer()
    keepPartialDuringStop = options.keepPartial
    if (candidateTimer !== null) clock.clearTimeout(candidateTimer)
    candidateTimer = null
    if (active && worklet) {
      const sealed = sealWorklet()
      await sealed
    }
    const partial = finishCurrent(false)
    stopPartial = keepPartialDuringStop ? partial : null
    keepPartialDuringStop = false
    detach()
    context = null
    ring = []; ringSamples = 0; captureCandidate = false; speechActive = false
    vad.reset(); publish()
    if (my !== generation) return null
    const result = stopPartial
    stopPartial = null
    return result
  }
  async function dispose() {
    if (disposed) return
    await stop({ keepPartial: false })
    disposed = true
    generation += 1
  }
  return { start, seal, resume, setPlayback, stop, dispose }
}

export function describeMicrophoneError(cause: unknown) {
  const name = (cause as { name?: string } | null)?.name || ''
  if (name === 'NotAllowedError' || name === 'PermissionDeniedError') return '麦克风权限被拒绝，请改用文字回答。'
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError') return '没有可用的麦克风，请改用文字回答。'
  if (name === 'NotReadableError' || name === 'TrackStartError') return '麦克风被占用，请改用文字回答。'
  return '麦克风不可用，请改用文字回答。'
}
