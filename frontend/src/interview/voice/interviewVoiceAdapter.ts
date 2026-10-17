import type { FrozenSubmission, TranscriptionResult, VoiceSessionSnapshot, VoiceSessionTicket, VoiceSubmitResult } from './voiceContracts.ts'

const INVALID = 'INVALID_OUTPUT'
function invalid(): never { throw Object.assign(new Error(INVALID), { code: INVALID }) }
function object(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return invalid()
  return value as Record<string, unknown>
}
function text(value: unknown): value is string { return typeof value === 'string' }
function requiredText(value: unknown): string { return text(value) && value.length > 0 && value.length <= 5000 ? value : invalid() }
function number(value: unknown): value is number { return typeof value === 'number' && Number.isFinite(value) && value >= 0 }

export function parseVoiceSession(value: unknown): VoiceSessionSnapshot {
  const raw = object(value)
  if (!text(raw.sessionId) || !raw.sessionId || (raw.status !== 'ACTIVE' && raw.status !== 'FINISHED')
      || !number(raw.revision) || !number(raw.stateVersion) || !Array.isArray(raw.turns)) return invalid()
  let currentQuestion: VoiceSessionSnapshot['currentQuestion'] = null
  if (raw.currentQuestion !== null && raw.currentQuestion !== undefined) {
    const current = object(raw.currentQuestion)
    currentQuestion = { questionId: requiredText(current.questionId) }
  }
  let plan: VoiceSessionSnapshot['plan'] = null
  if (raw.plan !== null && raw.plan !== undefined) {
    const value = object(raw.plan)
    if (value.mode !== 'QUICK' && value.mode !== 'FULL') return invalid()
    plan = { mode: value.mode }
  }
  let generation: VoiceSessionSnapshot['generation'] = null
  if (raw.generation !== null && raw.generation !== undefined) {
    const value = object(raw.generation)
    const states = ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED'] as const
    if (typeof value.state !== 'string' || !states.includes(value.state as typeof states[number])) return invalid()
    generation = { state: value.state as typeof states[number] }
  }
  const turns = raw.turns.map(value => {
    const turn = object(value)
    if (!text(turn.answer) && turn.answer !== null && turn.answer !== undefined) return invalid()
    return { questionId: requiredText(turn.questionId), question: requiredText(turn.question), answer: text(turn.answer) ? turn.answer : null }
  })
  if (!text(raw.pendingAnswer) && raw.pendingAnswer !== null && raw.pendingAnswer !== undefined) return invalid()
  return { sessionId: raw.sessionId, status: raw.status, currentQuestion, pendingAnswer: text(raw.pendingAnswer) ? raw.pendingAnswer : null,
    revision: raw.revision, stateVersion: raw.stateVersion, plan, generation, turns }
}

export function parseTranscription(value: unknown): TranscriptionResult {
  const raw = object(value)
  if (!text(raw.text) || !number(raw.durationMs)) return invalid()
  return { sessionId: requiredText(raw.sessionId), questionId: requiredText(raw.questionId), requestId: requiredText(raw.requestId), text: raw.text, durationMs: raw.durationMs }
}
export function parseVoiceSessionTicket(value: unknown): VoiceSessionTicket {
  const raw = object(value)
  if (raw.websocketPath !== '/api/interview-voice/ws' || raw.protocolVersion !== 1) return invalid()
  return { voiceSessionId: requiredText(raw.voiceSessionId), ticket: requiredText(raw.ticket), ticketExpiresAt: requiredText(raw.ticketExpiresAt), websocketPath: raw.websocketPath, protocolVersion: 1 }
}
export function parseVoiceSubmitResult(value: unknown, expected: FrozenSubmission): VoiceSubmitResult {
  const raw = object(value)
  if (!['SAVED', 'FINISHED', 'UNCERTAIN', 'REJECTED', 'STALE'].includes(String(raw.outcome))
      || !['NONE', 'PENDING', 'FAILED'].includes(String(raw.generation))
      || raw.questionId !== expected.questionId || raw.submissionId !== expected.submissionId) return invalid()
  return { outcome: raw.outcome as VoiceSubmitResult['outcome'], questionId: expected.questionId,
    submissionId: expected.submissionId, generation: raw.generation as VoiceSubmitResult['generation'] }
}

type LegacyBridge = {
  readSession(): unknown; readDraft(): unknown; readPendingSubmission(): unknown; writeDraft(value: string): void
  sendVoice(payload: FrozenSubmission): Promise<unknown>; getSession(id: string, options?: { signal?: AbortSignal }): Promise<unknown>
  transcribe(id: string, body: { questionId: string; requestId: string; file: Blob }, signal?: AbortSignal): Promise<unknown>
  createVoiceSession(id: string, signal?: AbortSignal): Promise<unknown>; closeVoiceSession(id: string, voiceSessionId: string): Promise<unknown>; voiceSocketUrl(): unknown
}

export function createInterviewVoiceAdapter(bridge: LegacyBridge) {
  for (const method of ['readSession', 'readDraft', 'readPendingSubmission', 'writeDraft', 'sendVoice', 'getSession', 'transcribe', 'createVoiceSession', 'closeVoiceSession', 'voiceSocketUrl'] as const) {
    if (!bridge || typeof bridge[method] !== 'function') invalid()
  }
  const conversation = {
    readSession: () => { const value = bridge.readSession(); return value == null ? null : parseVoiceSession(value) },
    readDraft: () => { const value = bridge.readDraft(); return text(value) ? value : invalid() },
    readPendingSubmission: () => {
      const value = bridge.readPendingSubmission()
      if (value == null) return null
      const raw = object(value)
      return { questionId: requiredText(raw.questionId), submissionId: requiredText(raw.submissionId), answer: requiredText(raw.answer) }
    },
    writeDraft: (value: string) => { if (!text(value)) invalid(); bridge.writeDraft(value) },
    sendVoice: async (payload: FrozenSubmission) => parseVoiceSubmitResult(await bridge.sendVoice(payload), payload),
  }
  const api = {
    getSession: async (id: string, options?: { signal?: AbortSignal }) => parseVoiceSession(await bridge.getSession(id, options)),
    transcribe: async (id: string, body: { questionId: string; requestId: string; file: Blob }, signal?: AbortSignal) => {
      if (!(body.file instanceof Blob)) return invalid()
      const file = new File([body.file], 'answer.wav', { type: 'audio/wav' })
      const result = parseTranscription(await bridge.transcribe(id, { ...body, file }, signal))
      if (result.sessionId !== id || result.questionId !== body.questionId || result.requestId !== body.requestId) return invalid()
      return result
    },
    createVoiceSession: async (id: string, signal?: AbortSignal) => parseVoiceSessionTicket(await bridge.createVoiceSession(id, signal)),
    closeVoiceSession: async (id: string, voiceSessionId: string) => {
      const value = object(await bridge.closeVoiceSession(id, voiceSessionId))
      if (value.closed !== true) return invalid()
      return { closed: true as const }
    },
    voiceSocketUrl: () => {
      const value = bridge.voiceSocketUrl()
      if (!text(value)) return invalid()
      let url: URL
      try { url = new URL(value) } catch { return invalid() }
      if ((url.protocol !== 'ws:' && url.protocol !== 'wss:') || url.username || url.password || url.search || url.hash
          || !url.pathname.endsWith('/api/interview-voice/ws')) return invalid()
      return url.toString()
    },
  }
  return { api, conversation }
}
