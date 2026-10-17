import { createSpeechQueue } from './speechQueue.ts'
import { sameVoiceContext } from './voiceContracts.ts'
import type { CapturedSegment, Countdown, FrozenSubmission, VoiceContext, VoiceFailure, VoiceSegment, VoiceState, VoiceSubmitResult, VoiceSessionTicket } from './voiceContracts.ts'
import type { PcmFrame, VoiceSocketEvent } from './voiceProtocol.ts'

type Clock = { now(): number; setTimeout(callback: () => void, delay: number): unknown; clearTimeout(id: unknown): void }
const defaultClock: Clock = { now: () => performance.now(), setTimeout: (callback, delay) => globalThis.setTimeout(callback, delay), clearTimeout: id => globalThis.clearTimeout(id as ReturnType<typeof setTimeout>) }
type Capture = { start(context: VoiceContext): Promise<void>; seal(): Promise<void>; resume(): void; setPlayback(active: boolean): void; stop(options: { keepPartial: boolean }): Promise<CapturedSegment | null>; dispose(): Promise<void> }
type Socket = { connect(ticket: VoiceSessionTicket): Promise<void>; start(questionId: string, requestId: string): void; cancel(requestId: string, streamId?: number): void; ack(streamId: number, receivedSeq: number, playedSamples: number): void; isReady(): boolean; close(): void }
type Player = { start(streamId: number, sampleRate: number): void; enqueue(frame: PcmFrame): void; end(streamId: number, totalSamples: number): void; stop(): void; dispose(): void }
type Snapshot = { sessionId: string; status: 'ACTIVE' | 'FINISHED'; currentQuestion: { questionId: string } | null; pendingAnswer: string | null; revision: number; stateVersion: number; plan: { mode: 'QUICK' | 'FULL' } | null; generation: { state: string } | null; turns: Array<{ questionId: string; question: string; answer: string | null }> }
type Conversation = { readSession(): Snapshot | null; readDraft(): string; readPendingSubmission(): FrozenSubmission | null; writeDraft(value: string): void; sendVoice(payload: FrozenSubmission): Promise<VoiceSubmitResult> }
type Api = { createVoiceSession(id: string): Promise<VoiceSessionTicket>; closeVoiceSession(id: string, voiceSessionId: string): Promise<unknown>; voiceSocketUrl(): string; transcribe(id: string, body: { questionId: string; requestId: string; file: Blob }, signal?: AbortSignal): Promise<{ sessionId: string; questionId: string; requestId: string; text: string; durationMs: number }> }

export function useInterviewVoiceConversation({ api, conversation, getScope, getQuestionKind, getVisible = () => true,
  saveDraft = value => conversation.writeDraft(value), audioContextFactory, workletUrl,
  captureFactory, socketFactory, playerFactory, clock = defaultClock, idFactory = () => globalThis.crypto.randomUUID(),
  onChange = () => {}, onFailure = () => {}, previewMs = 3000,
}: {
  api: Api; conversation: Conversation; getScope: () => string; getQuestionKind: () => 'MAIN' | 'FOLLOW_UP' | 'ALGORITHM' | 'CASE_STUDY'
  getVisible?: () => boolean; saveDraft?: (value: string) => void; audioContextFactory: () => AudioContext; workletUrl: string
  captureFactory: (options: Record<string, unknown>) => Capture; socketFactory: (options: { url: string; onEvent(event: VoiceSocketEvent): void }) => Socket
  playerFactory: (options: { audioContext: AudioContext; onProgress(progress: { streamId: number; receivedSeq: number; playedSamples: number }): void; onDrained(streamId: number): void }) => Player
  clock?: Clock; idFactory?: () => string; onChange?: (state: VoiceState) => void; onFailure?: (failure: VoiceFailure) => void; previewMs?: number
}) {
  const state: VoiceState = { phase: 'OFF', transport: 'CLOSED', context: null, countdown: null, draftVersion: 0, editing: false,
    captureActive: false, speechActive: false, segments: [], appliedSegmentIds: new Set(), submission: null, failure: null }
  let epoch = 0
  let audioContext: AudioContext | null = null
  let capture: Capture | null = null
  let socket: Socket | null = null
  let player: Player | null = null
  let ticket: VoiceSessionTicket | null = null
  let countdownTimer: unknown = null
  let activeTts: { requestId: string; streamId?: number; context: VoiceContext } | null = null
  let submissionBusy = false
  let discharging = false
  let manualSubmitContext: VoiceContext | null = null
  let autoContinueQuestion = false
  let socketGeneration = 0
  let playerGeneration = 0
  let lastAutoRead = ''
  const emit = () => { state.segments = queue.snapshot(); onChange({ ...state, context: state.context && { ...state.context }, countdown: state.countdown && { ...state.countdown }, segments: [...state.segments], appliedSegmentIds: new Set(state.appliedSegmentIds) }) }
  const queue = createSpeechQueue({ api, getContext: () => state.context, onApplied: segment => applySegment(segment), onFailure: (failure, segment) => { state.failure = failure; state.phase = 'PAUSED'; onFailure(failure); emit() }, clock, idFactory })

  function clearCountdown() { if (countdownTimer !== null) clock.clearTimeout(countdownTimer); countdownTimer = null; state.countdown = null }
  function fail(code: string, message: string, retryable = false) { state.failure = { code, message, retryable }; state.phase = 'ERROR'; onFailure(state.failure); emit() }
  function contextFor(session: Snapshot): VoiceContext | null {
    const questionId = session.currentQuestion?.questionId
    if (!questionId || session.status !== 'ACTIVE') return null
    return { scopeKey: getScope(), sessionId: session.sessionId, questionId, epoch }
  }
  function applySegment(segment: VoiceSegment) {
    if (!sameVoiceContext(state.context, segment.context) || state.appliedSegmentIds.has(segment.segmentId)) return
    const text = segment.text.trim()
    if (!text) return
    state.appliedSegmentIds.add(segment.segmentId)
    const draft = conversation.readDraft()
    saveDraft(draft.trim() ? `${draft.trim()}\n${text}` : text)
    state.draftVersion++
    state.failure = null
    state.phase = 'LISTENING'
    emit()
    maybeReview()
  }
  function maybeReview() {
    if (!state.context || state.speechActive || captureState.captureCandidate || submissionBusy || discharging) return
    const segments = queue.snapshot()
    if (segments.some(item => item.state === 'QUEUED' || item.state === 'TRANSCRIBING' || item.state === 'FAILED')) return
    if (manualSubmitContext && sameVoiceContext(state.context, manualSubmitContext)) {
      const context = manualSubmitContext; manualSubmitContext = null
      void submitNow({ deadlineAt: clock.now(), context: { ...context }, draftVersion: state.draftVersion, lastSegmentSequence: segments.at(-1)?.sequence ?? -1 })
      return
    }
    if (state.editing) return
    if (!conversation.readDraft().trim() || !segments.some(item => item.state === 'DONE')) return
    clearCountdown()
    state.phase = 'REVIEW'
    const countdown: Countdown = { deadlineAt: clock.now() + previewMs, context: { ...state.context }, draftVersion: state.draftVersion,
      lastSegmentSequence: Math.max(...segments.filter(item => item.state === 'DONE').map(item => item.sequence)) }
    state.countdown = countdown
    countdownTimer = clock.setTimeout(() => { void submitNow(countdown) }, previewMs)
    emit()
  }
  const captureState = { captureCandidate: false, retainedBytes: 0 }
  function onCaptureState(value: { captureActive: boolean; speechActive: boolean; captureCandidate: boolean; retainedBytes: number }) {
    captureState.captureCandidate = value.captureCandidate
    captureState.retainedBytes = value.retainedBytes
    state.captureActive = value.captureActive
    state.speechActive = value.speechActive
    if (value.speechActive && activeTts) cancelPlayback()
    if (value.captureCandidate || value.speechActive) { clearCountdown(); state.phase = 'LISTENING'; emit() }
    else if (!value.captureActive && state.phase === 'LISTENING') maybeReview()
  }
  function onCaptured(segment: CapturedSegment) {
    if (!sameVoiceContext(state.context, segment.context)) return
    try { queue.enqueue(segment); state.phase = 'TRANSCRIBING'; emit() }
    catch (cause) { fail('QUEUE_LIMIT', cause instanceof Error ? cause.message : '转写队列已满，请先处理已有片段。') }
  }
  function captureBudget() {
    const queueBytes = queue.retainedBytes()
    const frameReserveBytes = 10 * 4096
    const usedBytes = queueBytes + captureState.retainedBytes + frameReserveBytes
    const segments = queue.snapshot()
    const pending = segments.filter(item => item.state !== 'DONE').length
    const answerSamplesMs = state.context ? segments.filter(item => item.context.scopeKey === state.context?.scopeKey
      && item.context.sessionId === state.context?.sessionId && item.context.questionId === state.context?.questionId)
      .reduce((sum, item) => sum + item.sampleCount * 1000 / item.sourceSampleRate, 0) : 0
    const activeSamplesMs = audioContext ? captureState.retainedBytes * 500 / audioContext.sampleRate : 0
    return { remainingBytes: Math.max(0, 12 * 1024 * 1024 - usedBytes),
      freeSegmentSlots: Math.max(0, 3 - pending - (state.speechActive || captureState.captureCandidate ? 1 : 0)),
      remainingAnswerMs: Math.max(0, 120_000 - answerSamplesMs - activeSamplesMs) }
  }
  function handleSocketEvent(event: VoiceSocketEvent) {
    const context = state.context
    if (!context) return
    if (event.type === 'closed') {
      if (state.phase !== 'OFF' && state.phase !== 'PAUSED') { player?.stop(); capture?.setPlayback(false); state.transport = 'CLOSED'; state.phase = 'PAUSED'; emit() }
      return
    }
    if (event.type === 'audio') {
      if (!activeTts?.streamId || !sameVoiceContext(context, activeTts.context) || event.frame.streamId !== activeTts.streamId) return
      try { player?.enqueue(event.frame) } catch { cancelPlayback() }
      return
    }
    if (event.type === 'session.ready') {
      state.transport = 'READY'
      const current = conversation.readSession()
      const key = current?.sessionId && current.currentQuestion ? `${current.sessionId}:${current.currentQuestion.questionId}` : ''
      if (key && key !== lastAutoRead && current?.status === 'ACTIVE' && getQuestionKind() !== 'ALGORITHM') {
        const question = current.turns.find(turn => turn.questionId === current.currentQuestion?.questionId)?.question
        if (question) { lastAutoRead = key; startQuestionSpeech(context, question) }
      }
      emit(); return
    }
    if (event.type === 'speech.started') {
      if (!sameVoiceContext(context, activeTts?.context || null) || event.questionId !== context.questionId || event.requestId !== activeTts?.requestId) return
      activeTts.streamId = event.streamId
      player?.start(event.streamId, event.sampleRate)
      capture?.setPlayback(true)
      state.phase = 'SPEAKING'; emit(); return
    }
    if (event.type === 'speech.ended') {
      if (!activeTts || event.streamId !== activeTts.streamId || event.requestId !== activeTts.requestId || !sameVoiceContext(context, activeTts.context)) return
      try { player?.end(event.streamId, event.totalSamples) } catch { cancelPlayback() }
      return
    }
    if (event.type === 'speech.failed' || event.type === 'session.failed') {
      if ('requestId' in event && activeTts?.requestId !== event.requestId) return
      player?.stop(); capture?.setPlayback(false); activeTts = null; state.phase = 'LISTENING'
      state.failure = { code: event.code, message: event.message, retryable: event.retryable }; emit()
    }
  }
  function startQuestionSpeech(context: VoiceContext, _question: string) {
    if (!socket?.isReady?.()) return
    activeTts = { requestId: idFactory(), context: { ...context } }
    try { socket.start(context.questionId, activeTts.requestId) }
    catch { activeTts = null }
  }
  function cancelPlayback() {
    if (activeTts) { player?.stop(); capture?.setPlayback(false); socket?.cancel(activeTts.requestId, activeTts.streamId); activeTts = null }
    if (state.phase === 'SPEAKING') state.phase = 'LISTENING'
    emit()
  }
  function onPlayerProgress(progress: { streamId: number; receivedSeq: number; playedSamples: number }) {
    if (activeTts?.streamId === progress.streamId) socket?.ack(progress.streamId, progress.receivedSeq, progress.playedSamples)
  }
  function onPlayerDrained(streamId: number) {
    if (activeTts?.streamId !== streamId) return
    activeTts = null; capture?.setPlayback(false); state.phase = 'LISTENING'; emit()
    if (getQuestionKind() === 'ALGORITHM') { void pause('algorithm') } else maybeReview()
  }

  async function start() {
    if (state.phase !== 'OFF' && state.phase !== 'PAUSED' && state.phase !== 'ERROR') return
    const session = conversation.readSession()
    const context = session && contextFor(session)
    if (!session || !context) { fail('NO_ACTIVE_QUESTION', '当前没有可回答的面试题。'); return }
    epoch++
    context.epoch = epoch
    state.editing = false
    state.context = context; state.phase = 'STARTING'; state.failure = null; state.transport = 'CONNECTING'; clearCountdown(); emit()
    try {
      audioContext ??= audioContextFactory()
      await audioContext.resume()
      capture = captureFactory({ audioContext, workletUrl, getBufferBudget: captureBudget,
        onCandidate: () => { clearCountdown(); state.phase = 'LISTENING'; emit() }, onSegment: onCaptured, onState: onCaptureState,
        onError: (message: string) => fail('MICROPHONE', message) })
      const thisPlayer = ++playerGeneration
      player = playerFactory({ audioContext, onProgress: progress => { if (thisPlayer === playerGeneration) onPlayerProgress(progress) }, onDrained: id => { if (thisPlayer === playerGeneration) onPlayerDrained(id) } })
      const thisSocket = ++socketGeneration
      socket = socketFactory({ url: api.voiceSocketUrl(), onEvent: event => { if (thisSocket === socketGeneration) handleSocketEvent(event) } })
      ticket = await api.createVoiceSession(session.sessionId)
      if (!sameVoiceContext(state.context, context)) { await api.closeVoiceSession(session.sessionId, ticket.voiceSessionId); return }
      await socket.connect(ticket)
      await capture.start(context)
      queue.resume(context)
      if (state.phase === 'STARTING') state.phase = 'LISTENING'
      emit()
    } catch (cause) { fail('VOICE_START_FAILED', cause instanceof Error ? cause.message : '连续语音启动失败。', true); await stopResources(false) }
  }
  async function stopResources(keepPartial: boolean) {
    socketGeneration++; playerGeneration++
    cancelPlayback(); socket?.close(); socket = null; player?.stop()
    const oldTicket = ticket; ticket = null
    if (capture) {
      const partial = await capture.stop({ keepPartial })
      if (partial && keepPartial && state.context) {
        const rebound = { ...partial, context: { ...state.context } }
        try { queue.enqueue(rebound) } catch { /* Existing audio remains in the queue for user action. */ }
      }
    }
    if (oldTicket && state.context) void api.closeVoiceSession(state.context.sessionId, oldTicket.voiceSessionId).catch(() => {})
  }
  async function pause(_reason = 'user') {
    if (state.phase === 'OFF' || state.phase === 'PAUSED' && !capture && !socket && !ticket) return
    const prior = state.context
    epoch++
    clearCountdown(); manualSubmitContext = null; queue.pause(); state.phase = 'PAUSED'; state.transport = 'CLOSED'; state.context = prior && { ...prior, epoch }; emit()
    await stopResources(true)
    emit()
  }
  async function submitNow(expected = state.countdown) {
    if (discharging || submissionBusy || !state.context || !expected || !sameVoiceContext(state.context, expected.context)) return
    discharging = true; clearCountdown();
    const submitContext = { ...state.context }
    try { await capture?.seal() } catch { discharging = false; state.phase = 'PAUSED'; emit(); return }
    if (!sameVoiceContext(state.context, submitContext) || state.speechActive || captureState.captureCandidate) { discharging = false; maybeReview(); return }
    if (queue.snapshot().some(segment => segment.state === 'QUEUED' || segment.state === 'TRANSCRIBING' || segment.state === 'FAILED')) {
      manualSubmitContext = submitContext
      discharging = false; state.phase = 'TRANSCRIBING'; emit(); return
    }
    const draft = conversation.readDraft().trim()
    const session = conversation.readSession()
    if (!draft || !session || session.currentQuestion?.questionId !== submitContext.questionId || getQuestionKind() === 'ALGORITHM' || !getVisible()) { discharging = false; state.phase = 'PAUSED'; emit(); return }
    submissionBusy = true; discharging = false; state.phase = 'SUBMITTING';
    const existing = conversation.readPendingSubmission()
    const submission = existing?.questionId === submitContext.questionId && existing.answer === draft
      ? existing : { questionId: submitContext.questionId, submissionId: idFactory(), answer: draft }
    state.submission = submission; emit()
    autoContinueQuestion = state.transport === 'READY' || state.captureActive || activeTts !== null
    await stopResources(false)
    try {
      const result = await conversation.sendVoice(submission)
      if (result.outcome !== 'SAVED' && result.outcome !== 'FINISHED') autoContinueQuestion = false
      if (!sameVoiceContext(state.context, submitContext)) return
      if (result.outcome === 'SAVED' || result.outcome === 'FINISHED') {
        state.phase = result.outcome === 'FINISHED' ? 'OFF' : 'WAITING_QUESTION'
        state.submission = null
      } else {
        state.phase = 'PAUSED'; state.failure = { code: result.outcome, message: result.outcome === 'UNCERTAIN' ? '回答保存结果尚未确认，原文已保留。请恢复会话后继续。' : '答案未提交，草稿已保留。', retryable: result.outcome === 'UNCERTAIN' }
      }
    } catch { state.phase = 'PAUSED'; state.failure = { code: 'SUBMIT_FAILED', message: '答案提交失败，草稿已保留。', retryable: true } }
    finally { submissionBusy = false; if (sameVoiceContext(state.context, submitContext)) state.context = null; emit() }
  }
  function onDraftEdited() { state.draftVersion++; state.editing = true; clearCountdown(); if (state.phase === 'REVIEW') state.phase = 'PAUSED'; emit() }
  function syncContext() {
    const session = conversation.readSession(); const next = session && contextFor(session)
    if (sameVoiceContext(state.context, next) || (!state.context && !next)) return
    const old = state.context; epoch++; clearCountdown(); manualSubmitContext = null; queue.pause(); cancelPlayback();
    queue.invalidateContext(next ? { ...next, epoch } : null)
    state.context = next ? { ...next, epoch } : null; state.phase = state.context ? 'PAUSED' : 'OFF'; state.transport = 'CLOSED'
    if (!old || !state.context || old.scopeKey !== state.context.scopeKey || old.sessionId !== state.context.sessionId || old.questionId !== state.context.questionId) {
      state.appliedSegmentIds.clear()
    }
    void stopResources(false).then(() => {
      const current = conversation.readSession()
      if (autoContinueQuestion && next && current?.currentQuestion?.questionId === next.questionId) {
        autoContinueQuestion = false
        void start()
      } else if (current?.status === 'FINISHED') autoContinueQuestion = false
    })
    emit()
  }
  function dispose() { clearCountdown(); queue.dispose(); void stopResources(false); void capture?.dispose(); player?.dispose(); audioContext?.close().catch(() => {}) }

  return { state, start, pause, resume: start, isActive: () => state.captureActive || state.transport !== 'CLOSED' || state.phase === 'SPEAKING', stop: async () => { await pause('user'); state.phase = 'OFF'; state.context = null; emit() }, stopPlayback: cancelPlayback,
    submitNow: () => submitNow(), onDraftEdited, syncContext, retrySegment: (id: string) => queue.retry(id), discardSegment: (id: string) => queue.discard(id), dispose }
}
