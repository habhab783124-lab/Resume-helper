export type VoiceContext = {
  scopeKey: string
  sessionId: string
  questionId: string
  epoch: number
}

export type VoiceFailure = {
  code: string
  message: string
  retryable: boolean
  retryAfterMs?: number
}

export type VoiceSessionTicket = {
  voiceSessionId: string
  ticket: string
  ticketExpiresAt: string
  websocketPath: string
  protocolVersion: 1
}

export type VoiceSessionSnapshot = {
  sessionId: string
  status: 'ACTIVE' | 'FINISHED'
  currentQuestion: { questionId: string } | null
  pendingAnswer: string | null
  revision: number
  stateVersion: number
  plan: { mode: 'QUICK' | 'FULL' } | null
  generation: { state: 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' } | null
  turns: Array<{ questionId: string; question: string; answer: string | null }>
}

export type TranscriptionResult = { sessionId: string; questionId: string; requestId: string; text: string; durationMs: number }

export type CapturedSegment = {
  context: VoiceContext
  sourceSampleRate: number
  sampleCount: number
  audio: Blob
}

export type VoiceSegment = {
  segmentId: string
  sequence: number
  context: VoiceContext
  attempt: number
  requestId: string
  state: 'QUEUED' | 'TRANSCRIBING' | 'DONE' | 'FAILED'
  sourceSampleRate: number
  sampleCount: number
  audio: Blob | null
  text: string
  failure: VoiceFailure | null
}

export type Countdown = {
  deadlineAt: number
  context: VoiceContext
  draftVersion: number
  lastSegmentSequence: number
}

export type FrozenSubmission = {
  questionId: string
  submissionId: string
  answer: string
}

export type VoiceSubmitResult = {
  outcome: 'SAVED' | 'FINISHED' | 'UNCERTAIN' | 'REJECTED' | 'STALE'
  questionId: string
  submissionId: string
  generation: 'NONE' | 'PENDING' | 'FAILED'
}

export type VoiceState = {
  phase: 'OFF' | 'STARTING' | 'SPEAKING' | 'LISTENING' | 'TRANSCRIBING'
    | 'REVIEW' | 'SUBMITTING' | 'WAITING_QUESTION' | 'PAUSED' | 'ERROR'
  transport: 'CLOSED' | 'CONNECTING' | 'READY'
  context: VoiceContext | null
  countdown: Countdown | null
  draftVersion: number
  editing: boolean
  captureActive: boolean
  speechActive: boolean
  segments: VoiceSegment[]
  appliedSegmentIds: Set<string>
  submission: FrozenSubmission | null
  failure: VoiceFailure | null
}

export function sameVoiceContext(a: VoiceContext | null, b: VoiceContext | null): boolean {
  return !!a && !!b && a.scopeKey === b.scopeKey && a.sessionId === b.sessionId
    && a.questionId === b.questionId && a.epoch === b.epoch
}

export type AutoSubmitSnapshot = {
  phase: VoiceState['phase']
  editing: boolean
  speechActive: boolean
  captureCandidate: boolean
  pendingSegments: number
  failedSegments: number
  answerable: boolean
  pendingSubmission: boolean
  questionKind: 'MAIN' | 'FOLLOW_UP' | 'ALGORITHM' | 'CASE_STUDY'
  visible: boolean
  draft: string
}

export function canAutoSubmit(snapshot: AutoSubmitSnapshot): boolean {
  const answer = snapshot.draft.trim()
  return snapshot.phase === 'REVIEW' && !snapshot.editing && !snapshot.speechActive
    && !snapshot.captureCandidate && snapshot.pendingSegments === 0 && snapshot.failedSegments === 0
    && snapshot.answerable && !snapshot.pendingSubmission && snapshot.questionKind !== 'ALGORITHM'
    && snapshot.visible && answer.length > 0 && answer.length <= 5000
}
