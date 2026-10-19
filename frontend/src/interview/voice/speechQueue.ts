import { sameVoiceContext } from './voiceContracts.ts'
import type { CapturedSegment, VoiceContext, VoiceFailure, VoiceSegment } from './voiceContracts.ts'

type TranscriptionResult = { sessionId: string; questionId: string; requestId: string; text: string; durationMs: number }
type QueueClock = { now(): number; setTimeout(callback: () => void, delay: number): unknown; clearTimeout(id: unknown): void }
const defaultClock: QueueClock = {
  now: () => performance.now(),
  setTimeout: (callback, delay) => globalThis.setTimeout(callback, delay),
  clearTimeout: id => globalThis.clearTimeout(id as ReturnType<typeof setTimeout>),
}
const MAX_PENDING = 3
const TRANSCRIPTION_DEADLINE_MS = 90_000

function failure(message = '语音转写失败，请重试。'): VoiceFailure {
  return { code: 'TRANSCRIPTION_FAILED', message, retryable: true }
}
function temporaryServiceFailure(cause: unknown): boolean {
  const status = (cause as { status?: unknown } | null)?.status
  return status === 502 || status === 503 || status === 504
}

export function createSpeechQueue({
  api,
  getContext,
  onApplied,
  onFailure = () => {},
  clock = defaultClock,
  idFactory = () => globalThis.crypto.randomUUID(),
}: {
  api: { transcribe(sessionId: string, request: { questionId: string; requestId: string; file: Blob }, signal?: AbortSignal): Promise<TranscriptionResult> }
  getContext: () => VoiceContext | null
  onApplied: (segment: VoiceSegment) => void
  onFailure?: (failure: VoiceFailure, segment: VoiceSegment) => void
  clock?: QueueClock
  idFactory?: () => string
}) {
  const segments: VoiceSegment[] = []
  let paused = false
  let disposed = false
  let activeAttempt = 0
  let running = false
  let activeController: AbortController | null = null

  const pendingCount = () => segments.filter(segment => segment.state !== 'DONE').length
  const find = (id: string) => segments.find(segment => segment.segmentId === id)
  function enqueue(captured: CapturedSegment): string {
    if (disposed) throw new Error('语音转写队列已关闭')
    const activeContext = getContext()
    if (!sameVoiceContext(activeContext, captured.context)) throw new Error('语音片段上下文已过期')
    if (pendingCount() >= MAX_PENDING) throw new Error('待转写片段已达到上限')
    const sequence = segments.filter(segment => segment.context.sessionId === captured.context.sessionId
      && segment.context.questionId === captured.context.questionId).reduce((max, segment) => Math.max(max, segment.sequence), -1) + 1
    const segment: VoiceSegment = {
      segmentId: idFactory(), sequence, context: { ...captured.context }, attempt: 0,
      requestId: '', state: 'QUEUED', sourceSampleRate: captured.sourceSampleRate,
      sampleCount: captured.sampleCount, audio: captured.audio, text: '', failure: null,
    }
    segments.push(segment)
    void pump()
    return segment.segmentId
  }

  async function transcribe(segment: VoiceSegment) {
    if (!segment.audio) {
      segment.state = 'FAILED'; segment.failure = failure('原始录音不可用，请重新录音。')
      onFailure(segment.failure, segment); return
    }
    const context = { ...segment.context }
    const attempt = ++segment.attempt
    const operation = ++activeAttempt
    let requestId = idFactory()
    segment.requestId = requestId
    segment.state = 'TRANSCRIBING'
    segment.failure = null
    const controller = new AbortController()
    activeController = controller
    let timeout: unknown = null
    const deadline = new Promise<never>((_resolve, reject) => {
      timeout = clock.setTimeout(() => {
        controller.abort()
        reject(Object.assign(new Error('deadline'), { code: 'TIMEOUT' }))
      }, TRANSCRIPTION_DEADLINE_MS)
    })
    try {
      const audio = segment.audio
      const send = () => Promise.race([
        api.transcribe(context.sessionId, { questionId: context.questionId, requestId, file: audio }, controller.signal),
        deadline,
      ])
      let response: TranscriptionResult
      try { response = await send() }
      catch (cause) {
        if (!temporaryServiceFailure(cause) || disposed || paused || controller.signal.aborted
            || operation !== activeAttempt || !sameVoiceContext(getContext(), context)) throw cause
        requestId = idFactory()
        segment.requestId = requestId
        response = await send()
      }
      if (disposed || paused || operation !== activeAttempt || segment.attempt !== attempt
          || !sameVoiceContext(getContext(), context)) return
      if (!response || response.sessionId !== context.sessionId || response.questionId !== context.questionId
          || response.requestId !== requestId || typeof response.text !== 'string'
          || !Number.isFinite(response.durationMs) || response.durationMs < 0 || !response.text.trim()) {
        throw new Error('identity')
      }
      segment.text = response.text.trim()
      segment.state = 'DONE'
      segment.audio = null
      segment.failure = null
      onApplied({ ...segment, context: { ...segment.context } })
    } catch (cause) {
      if (disposed || operation !== activeAttempt || segment.attempt !== attempt) return
      if (paused || controller.signal.aborted && activeController !== controller) return
      if (controller.signal.aborted && paused) return
      segment.state = 'FAILED'
      segment.failure = failure(cause instanceof Error && typeof (cause as { status?: unknown }).status === 'number'
        ? cause.message : undefined)
      onFailure(segment.failure, { ...segment, context: { ...segment.context } })
    } finally {
      if (timeout !== null) clock.clearTimeout(timeout)
      if (activeController === controller) activeController = null
    }
  }

  async function pump() {
    if (running || paused || disposed) return
    running = true
    try {
      while (!paused && !disposed) {
        const next = segments.find(segment => segment.state === 'QUEUED')
        if (!next) break
        if (!sameVoiceContext(getContext(), next.context)) {
          next.state = 'FAILED'; next.failure = failure('当前题目已变化，片段没有提交。')
          onFailure(next.failure, { ...next, context: { ...next.context } })
          break
        }
        await transcribe(next)
        if (next.state === 'FAILED') break
      }
    } finally { running = false }
  }

  return {
    enqueue,
    retry(segmentId: string) {
      const segment = find(segmentId)
      if (!segment || segment.state !== 'FAILED' || !segment.audio || disposed) return
      const context = getContext()
      if (!sameVoiceContext(context, segment.context)) throw new Error('只能重试当前题目的语音片段')
      paused = false
      segment.state = 'QUEUED'; segment.failure = null
      void pump()
    },
    discard(segmentId: string) {
      const index = segments.findIndex(segment => segment.segmentId === segmentId && segment.state !== 'TRANSCRIBING')
      if (index >= 0) segments.splice(index, 1)
    },
    pause() {
      paused = true
      activeAttempt++
      activeController?.abort()
      for (const segment of segments) if (segment.state === 'TRANSCRIBING') segment.state = 'QUEUED'
    },
    resume(context: VoiceContext) {
      if (disposed) return
      for (const segment of segments) {
        if (segment.state === 'DONE') continue
        if (segment.context.scopeKey === context.scopeKey && segment.context.sessionId === context.sessionId
            && segment.context.questionId === context.questionId) {
          segment.context = { ...context }
          if (segment.state === 'TRANSCRIBING') segment.state = 'QUEUED'
        }
      }
      paused = false
      void pump()
    },
    invalidateContext(context: VoiceContext | null) {
      for (const segment of segments) {
        if (segment.state === 'DONE' || context && segment.context.scopeKey === context.scopeKey
            && segment.context.sessionId === context.sessionId && segment.context.questionId === context.questionId) continue
        segment.state = 'FAILED'
        segment.failure = { code: 'STALE_CONTEXT', message: '题目已变化；这段录音没有提交，可在当前页面明确丢弃。', retryable: false }
        onFailure(segment.failure, { ...segment, context: { ...segment.context } })
      }
    },
    snapshot: () => segments.map(segment => ({ ...segment, context: { ...segment.context }, failure: segment.failure && { ...segment.failure } })),
    retainedBytes: () => segments.reduce((sum, segment) => sum + (segment.audio?.size || 0), 0),
    dispose() {
      paused = true; disposed = true; activeAttempt++
      activeController?.abort(); activeController = null
    },
  }
}
