export const WAV_HEADER_BYTES = 44
export const MAX_WAV_BYTES = 5 * 1024 * 1024
export const MAX_WAV_SECONDS = 30

/** Encode mono signed PCM16 into a standard little-endian RIFF/WAVE blob. */
export function encodeMonoWav(samples: Int16Array, sampleRate: number,
  maxBytes = MAX_WAV_BYTES, maxSeconds = MAX_WAV_SECONDS): Blob {
  if (!(samples instanceof Int16Array) || samples.length < 1) throw new RangeError('录音为空')
  if (!Number.isInteger(sampleRate) || sampleRate < 1 || sampleRate > 384_000) throw new RangeError('采样率无效')
  if (!Number.isInteger(maxBytes) || maxBytes < WAV_HEADER_BYTES || !Number.isFinite(maxSeconds) || maxSeconds <= 0) {
    throw new RangeError('音频限制无效')
  }
  const dataBytes = samples.length * 2
  const maxSamples = Math.min(Math.floor(maxSeconds * sampleRate), Math.floor((maxBytes - WAV_HEADER_BYTES) / 2))
  if (samples.length > maxSamples) throw new RangeError('录音超过大小或时长限制')

  const buffer = new ArrayBuffer(WAV_HEADER_BYTES + dataBytes)
  const view = new DataView(buffer)
  const ascii = (offset: number, value: string) => {
    for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i))
  }
  ascii(0, 'RIFF')
  view.setUint32(4, 36 + dataBytes, true)
  ascii(8, 'WAVE')
  ascii(12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  ascii(36, 'data')
  view.setUint32(40, dataBytes, true)
  for (let i = 0; i < samples.length; i++) view.setInt16(WAV_HEADER_BYTES + i * 2, samples[i]!, true)
  return new Blob([buffer], { type: 'audio/wav' })
}
