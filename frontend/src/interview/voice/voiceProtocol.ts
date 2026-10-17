export type VoiceClientMessage =
  | { v: 1; type: 'session.hello'; voiceSessionId: string; ticket: string }
  | { v: 1; type: 'speech.start'; requestId: string; questionId: string }
  | { v: 1; type: 'audio.ack'; streamId: number; receivedSeq: number; playedSamples: number }
  | { v: 1; type: 'speech.cancel'; requestId: string; streamId?: number }
  | { v: 1; type: 'session.ping'; nonce: string }
  | { v: 1; type: 'session.pong'; nonce: string }
  | { v: 1; type: 'session.close' }

export type VoiceServerMessage =
  | { v: 1; type: 'session.ready'; voiceSessionId: string; connectionId: string; protocolVersion: 1; expiresAt: string; sampleRate: 24000; channels: 1; maxBufferedSamples: number; maxPcmBytes: number }
  | { v: 1; type: 'speech.started'; requestId: string; questionId: string; streamId: number; format: 'pcm_s16le'; sampleRate: 24000; channels: 1 }
  | { v: 1; type: 'speech.cancelled'; requestId: string; streamId?: number }
  | { v: 1; type: 'speech.ended'; requestId: string; streamId: number; lastSeq: number; totalSamples: number; totalBytes: number }
  | { v: 1; type: 'speech.failed'; requestId: string; streamId?: number; code: VoiceFailureCode; message: string; retryable: boolean; retryAfterMs?: number }
  | { v: 1; type: 'session.pong'; nonce: string }
  | { v: 1; type: 'session.failed'; code: VoiceFailureCode; message: string; retryable: boolean }

export type VoiceFailureCode =
  | 'INPUT' | 'CONFLICT' | 'CONFIG' | 'AUTH' | 'QUOTA' | 'RATE_LIMITED'
  | 'TIMEOUT' | 'NETWORK' | 'UPSTREAM_UNAVAILABLE' | 'INVALID_OUTPUT'
  | 'INTERRUPTED' | 'SLOW_CONSUMER' | 'STALE_CONTEXT'

export type PcmFrame = {
  streamId: number
  sequence: number
  sampleOffset: number
  pcm: Int16Array
}

export type VoiceSocketEvent = VoiceServerMessage | { type: 'audio'; frame: PcmFrame }
  | { type: 'closed'; code: number; reason: string }

const HEADER_BYTES = 32
const MAX_PAYLOAD_BYTES = 4096
const MAGIC = [0x52, 0x56, 0x56, 0x31] as const

function protocolError(message: string): Error & { code: 'PROTOCOL_ERROR' } {
  return Object.assign(new Error(message), { code: 'PROTOCOL_ERROR' as const })
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw protocolError('语音消息格式无效')
  return value as Record<string, unknown>
}
function stringField(value: Record<string, unknown>, name: string): string {
  const field = value[name]
  if (typeof field !== 'string' || field.length === 0 || field.length > 2048) throw protocolError('语音消息字段无效')
  return field
}
function uint(value: unknown, max = 0xffff_ffff): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= max
}
function uintField(value: Record<string, unknown>, name: string, max?: number): number {
  const field = value[name]
  if (!uint(field, max)) throw protocolError('语音消息字段无效')
  return field
}
function booleanField(value: Record<string, unknown>, name: string): boolean {
  const field = value[name]
  if (typeof field !== 'boolean') throw protocolError('语音消息字段无效')
  return field
}
function failureCode(value: unknown): VoiceFailureCode {
  const codes: readonly VoiceFailureCode[] = [
    'INPUT', 'CONFLICT', 'CONFIG', 'AUTH', 'QUOTA', 'RATE_LIMITED', 'TIMEOUT', 'NETWORK',
    'UPSTREAM_UNAVAILABLE', 'INVALID_OUTPUT', 'INTERRUPTED', 'SLOW_CONSUMER', 'STALE_CONTEXT',
  ]
  if (typeof value !== 'string' || !codes.includes(value as VoiceFailureCode)) throw protocolError('语音错误代码无效')
  return value as VoiceFailureCode
}

export function parseServerMessage(input: unknown): VoiceServerMessage {
  const value = record(input)
  if (value.v !== 1 || typeof value.type !== 'string') throw protocolError('语音协议版本无效')
  switch (value.type) {
    case 'session.ready':
      if (value.protocolVersion !== 1 || value.sampleRate !== 24000 || value.channels !== 1) throw protocolError('语音能力参数不匹配')
      return { v: 1, type: value.type, voiceSessionId: stringField(value, 'voiceSessionId'), connectionId: stringField(value, 'connectionId'), protocolVersion: 1, expiresAt: stringField(value, 'expiresAt'), sampleRate: 24000, channels: 1, maxBufferedSamples: uintField(value, 'maxBufferedSamples'), maxPcmBytes: uintField(value, 'maxPcmBytes') }
    case 'speech.started':
      if (value.format !== 'pcm_s16le' || value.sampleRate !== 24000 || value.channels !== 1) throw protocolError('语音流格式无效')
      return { v: 1, type: value.type, requestId: stringField(value, 'requestId'), questionId: stringField(value, 'questionId'), streamId: positiveStream(value), format: 'pcm_s16le', sampleRate: 24000, channels: 1 }
    case 'speech.cancelled':
      return { v: 1, type: value.type, requestId: stringField(value, 'requestId'), ...(value.streamId === undefined ? {} : { streamId: positiveStream(value) }) }
    case 'speech.ended': {
      const totalSamples = uintField(value, 'totalSamples')
      const totalBytes = uintField(value, 'totalBytes')
      if (totalSamples < 1 || totalBytes !== totalSamples * 2) throw protocolError('语音结束长度无效')
      return { v: 1, type: value.type, requestId: stringField(value, 'requestId'), streamId: positiveStream(value), lastSeq: uintField(value, 'lastSeq'), totalSamples, totalBytes }
    }
    case 'speech.failed': {
      const retryAfterMs = value.retryAfterMs
      if (retryAfterMs !== undefined && !uint(retryAfterMs)) throw protocolError('语音重试时间无效')
      return { v: 1, type: value.type, requestId: stringField(value, 'requestId'), ...(value.streamId === undefined ? {} : { streamId: positiveStream(value) }), code: failureCode(value.code), message: stringField(value, 'message'), retryable: booleanField(value, 'retryable'), ...(retryAfterMs === undefined ? {} : { retryAfterMs }) }
    }
    case 'session.pong':
      return { v: 1, type: value.type, nonce: stringField(value, 'nonce') }
    case 'session.failed':
      return { v: 1, type: value.type, code: failureCode(value.code), message: stringField(value, 'message'), retryable: booleanField(value, 'retryable') }
    default:
      throw protocolError('未知语音消息类型')
  }
}

function positiveStream(value: Record<string, unknown>): number {
  const streamId = uintField(value, 'streamId')
  if (streamId === 0) throw protocolError('语音流编号无效')
  return streamId
}

export function encodePcmFrame(frame: PcmFrame): ArrayBuffer {
  const { streamId, sequence, sampleOffset, pcm } = frame
  if (!uint(streamId) || streamId === 0 || !uint(sequence) || !uint(sampleOffset)
      || !(pcm instanceof Int16Array) || pcm.length < 1 || pcm.byteLength > MAX_PAYLOAD_BYTES) {
    throw protocolError('PCM 帧字段无效')
  }
  const output = new ArrayBuffer(HEADER_BYTES + pcm.byteLength)
  const bytes = new Uint8Array(output)
  bytes.set(MAGIC)
  const view = new DataView(output)
  view.setUint8(4, 1)
  view.setUint8(5, 1)
  view.setUint16(6, 0, true)
  view.setUint32(8, streamId, true)
  view.setUint32(12, sequence, true)
  view.setUint32(16, sampleOffset, true)
  view.setUint32(20, pcm.length, true)
  view.setUint32(24, pcm.byteLength, true)
  view.setUint32(28, 0, true)
  for (let i = 0; i < pcm.length; i++) view.setInt16(HEADER_BYTES + i * 2, pcm[i]!, true)
  return output
}

export function decodePcmFrame(buffer: ArrayBuffer): PcmFrame {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < HEADER_BYTES + 2) throw protocolError('PCM 帧长度无效')
  const bytes = new Uint8Array(buffer)
  const view = new DataView(buffer)
  if (MAGIC.some((byte, index) => bytes[index] !== byte) || view.getUint8(4) !== 1 || view.getUint8(5) !== 1
      || view.getUint16(6, true) !== 0 || view.getUint32(28, true) !== 0) throw protocolError('PCM 帧头无效')
  const streamId = view.getUint32(8, true)
  const sequence = view.getUint32(12, true)
  const sampleOffset = view.getUint32(16, true)
  const sampleCount = view.getUint32(20, true)
  const payloadBytes = view.getUint32(24, true)
  if (streamId === 0 || payloadBytes < 2 || payloadBytes > MAX_PAYLOAD_BYTES || payloadBytes % 2 !== 0
      || buffer.byteLength !== HEADER_BYTES + payloadBytes || sampleCount !== payloadBytes / 2) throw protocolError('PCM 帧内容长度无效')
  const pcm = new Int16Array(sampleCount)
  for (let i = 0; i < sampleCount; i++) pcm[i] = view.getInt16(HEADER_BYTES + i * 2, true)
  return { streamId, sequence, sampleOffset, pcm }
}
