import assert from 'node:assert/strict'
import test from 'node:test'
import { createInterviewVoiceAdapter } from './interviewVoiceAdapter.ts'

const snapshot = { sessionId: 's1', status: 'ACTIVE', currentQuestion: { questionId: 'q1' }, pendingAnswer: null,
  revision: 2, stateVersion: 3, plan: null, generation: null,
  turns: [{ questionId: 'q1', question: '题目', answer: null }] }
function bridge(overrides: Record<string, unknown> = {}) {
  return { readSession: () => snapshot, readDraft: () => '草稿', readPendingSubmission: () => null, writeDraft: () => {},
    sendVoice: async (payload: { questionId: string; submissionId: string }) => ({ outcome: 'SAVED', questionId: payload.questionId, submissionId: payload.submissionId, generation: 'NONE' }),
    getSession: async () => snapshot,
    transcribe: async (_id: string, body: { questionId: string; requestId: string }) => ({ sessionId: 's1', questionId: body.questionId, requestId: body.requestId, text: '识别', durationMs: 10 }),
    createVoiceSession: async () => ({ voiceSessionId: 'v1', ticket: 'one-time', ticketExpiresAt: 'later', websocketPath: '/api/interview-voice/ws', protocolVersion: 1 }),
    closeVoiceSession: async () => ({ closed: true }), voiceSocketUrl: () => 'wss://api.example.test/api/interview-voice/ws', ...overrides,
  } as any
}

test('parses legacy session defaults, converts WAV Blobs to answer.wav, and validates ticket/submit responses', async () => {
  const adapter = createInterviewVoiceAdapter(bridge())
  assert.equal(adapter.conversation.readSession()?.plan, null)
  assert.equal(adapter.conversation.readDraft(), '草稿')
  const file = new Blob(['wav'], { type: 'audio/wav' })
  let uploaded: Blob | null = null
  const ports = createInterviewVoiceAdapter(bridge({ transcribe: async (_id: string, body: { file: Blob }) => {
    uploaded = body.file
    return { sessionId: 's1', questionId: 'q1', requestId: 'r1', text: '识别', durationMs: 10 }
  } }))
  await ports.api.transcribe('s1', { questionId: 'q1', requestId: 'r1', file })
  const uploadedFile = uploaded as unknown as File
  assert.equal(uploadedFile.name, 'answer.wav')
  assert.equal(uploadedFile.type, 'audio/wav')
  assert.equal((await adapter.api.createVoiceSession('s1')).ticket, 'one-time')
  assert.deepEqual(await adapter.conversation.sendVoice({ questionId: 'q1', submissionId: 'sub1', answer: '回答' }),
    { outcome: 'SAVED', questionId: 'q1', submissionId: 'sub1', generation: 'NONE' })
})

test('rejects malformed legacy session fields, transcription identity mismatch, unsafe socket URL, and invalid ticket', async () => {
  assert.throws(() => createInterviewVoiceAdapter(bridge({ readSession: () => ({ ...snapshot, revision: '2' }) })).conversation.readSession(), /INVALID_OUTPUT/)
  const badTranscription = createInterviewVoiceAdapter(bridge({ transcribe: async () => ({ sessionId: 'other', questionId: 'q1', requestId: 'r1', text: 'bad', durationMs: 1 }) }))
  await assert.rejects(badTranscription.api.transcribe('s1', { questionId: 'q1', requestId: 'r1', file: new Blob(['x']) }), /INVALID_OUTPUT/)
  assert.throws(() => createInterviewVoiceAdapter(bridge({ voiceSocketUrl: () => 'https://user:pass@example.test/not-ws' })).api.voiceSocketUrl(), /INVALID_OUTPUT/)
  await assert.rejects(createInterviewVoiceAdapter(bridge({ createVoiceSession: async () => ({ ...snapshot }) })).api.createVoiceSession('s1'), /INVALID_OUTPUT/)
})

test('rejects a sendVoice result that does not match the frozen submission', async () => {
  const adapter = createInterviewVoiceAdapter(bridge({ sendVoice: async () => ({ outcome: 'SAVED', questionId: 'other', submissionId: 'other', generation: 'NONE' }) }))
  await assert.rejects(adapter.conversation.sendVoice({ questionId: 'q1', submissionId: 'sub1', answer: '回答' }), /INVALID_OUTPUT/)
})
