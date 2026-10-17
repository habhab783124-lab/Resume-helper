import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { decodePcmFrame, encodePcmFrame, parseServerMessage } from './voiceProtocol.ts'
import { canAutoSubmit, sameVoiceContext, type AutoSubmitSnapshot, type VoiceContext } from './voiceContracts.ts'

test('PCM frame codec matches the shared cross-language fixed vector', async () => {
  const contract = JSON.parse(await readFile(new URL('../../../../docs/contracts/interview-voice-v1.json', import.meta.url), 'utf8'))
  const vector = contract.pcmFrame.fixedVector
  const frame = encodePcmFrame({
    streamId: vector.streamId,
    sequence: vector.sequence,
    sampleOffset: vector.sampleOffset,
    pcm: Int16Array.from(vector.samples),
  })
  assert.equal(Buffer.from(frame).toString('hex'), vector.frameHex)
  const bytes = Uint8Array.from(Buffer.from(vector.frameHex, 'hex'))
  const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)
  const decoded = decodePcmFrame(buffer)
  assert.equal(decoded.streamId, vector.streamId)
  assert.equal(decoded.sequence, vector.sequence)
  assert.equal(decoded.sampleOffset, vector.sampleOffset)
  assert.deepEqual([...decoded.pcm], vector.samples)
})

test('PCM decoder rejects corrupt header, odd payload, and mismatched count', () => {
  const valid = encodePcmFrame({ streamId: 1, sequence: 0, sampleOffset: 0, pcm: Int16Array.of(5) })
  const badMagic = valid.slice(0); new Uint8Array(badMagic)[0] = 0
  assert.throws(() => decodePcmFrame(badMagic), { code: 'PROTOCOL_ERROR' })
  const oddPayload = valid.slice(0, valid.byteLength - 1)
  assert.throws(() => decodePcmFrame(oddPayload), { code: 'PROTOCOL_ERROR' })
  const wrongVersion = valid.slice(0); new DataView(wrongVersion).setUint8(4, 2)
  assert.throws(() => decodePcmFrame(wrongVersion), { code: 'PROTOCOL_ERROR' })
  const wrongCount = valid.slice(0); new DataView(wrongCount).setUint32(20, 2, true)
  assert.throws(() => decodePcmFrame(wrongCount), { code: 'PROTOCOL_ERROR' })
  assert.throws(() => encodePcmFrame({ streamId: 1, sequence: 0, sampleOffset: 0, pcm: new Int16Array(2049) }), { code: 'PROTOCOL_ERROR' })
})

test('server JSON parser validates version and message fields before returning a union member', async () => {
  assert.deepEqual(parseServerMessage({ v: 1, type: 'session.pong', nonce: 'n' }), { v: 1, type: 'session.pong', nonce: 'n' })
  const contract = JSON.parse(await readFile(new URL('../../../../docs/contracts/interview-voice-v1.json', import.meta.url), 'utf8'))
  for (const message of Object.values(contract.messages) as unknown[]) parseServerMessage(message)
  assert.throws(() => parseServerMessage({ v: 2, type: 'session.pong', nonce: 'n' }), { code: 'PROTOCOL_ERROR' })
  assert.throws(() => parseServerMessage({ v: 1, type: 'speech.started', requestId: 'r', questionId: 'q', streamId: -1, format: 'pcm_s16le', sampleRate: 24000, channels: 1 }), { code: 'PROTOCOL_ERROR' })
})


test('context equality and automatic submission guard use current question and capture facts', () => {
  const context: VoiceContext = { scopeKey: 'local|u', sessionId: 's', questionId: 'q', epoch: 2 }
  assert.equal(sameVoiceContext(context, { ...context }), true)
  assert.equal(sameVoiceContext(context, { ...context, epoch: 3 }), false)
  const snapshot: AutoSubmitSnapshot = { phase: 'REVIEW', editing: false, speechActive: false, captureCandidate: false, pendingSegments: 0, failedSegments: 0, answerable: true, pendingSubmission: false, questionKind: 'MAIN', visible: true, draft: 'answer' }
  assert.equal(canAutoSubmit(snapshot), true)
  assert.equal(canAutoSubmit({ ...snapshot, captureCandidate: true }), false)
  assert.equal(canAutoSubmit({ ...snapshot, questionKind: 'ALGORITHM' }), false)
})
