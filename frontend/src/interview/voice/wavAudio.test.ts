import assert from 'node:assert/strict'
import test from 'node:test'
import { encodeMonoWav } from './wavAudio.ts'

async function inspect(samples: Int16Array, rate: number) {
  const blob = encodeMonoWav(samples, rate)
  const bytes = await blob.arrayBuffer()
  const view = new DataView(bytes)
  return { blob, view }
}

test('encodes standard mono PCM16 WAV with explicit little-endian samples', async () => {
  for (const rate of [48_000, 44_100]) {
    const { blob, view } = await inspect(Int16Array.from([-32768, 0, 32767]), rate)
    assert.equal(blob.type, 'audio/wav')
    assert.equal(blob.size, 50)
    assert.equal(view.getUint32(24, true), rate)
    assert.equal(view.getUint32(28, true), rate * 2)
    assert.equal(view.getUint16(32, true), 2)
    assert.equal(view.getUint16(34, true), 16)
    assert.equal(view.getUint32(40, true), 6)
    assert.equal(view.getInt16(44, true), -32768)
    assert.equal(view.getInt16(48, true), 32767)
  }
})

test('rejects empty input, invalid sample rates, segments over 30 seconds, and WAVs over 5 MiB', () => {
  assert.throws(() => encodeMonoWav(new Int16Array(), 48_000))
  assert.throws(() => encodeMonoWav(Int16Array.of(1), 0))
  assert.throws(() => encodeMonoWav(new Int16Array(48_000 * 30 + 1), 48_000))
  const tooLarge = new Int16Array(Math.floor((5 * 1024 * 1024 - 44) / 2) + 1)
  assert.throws(() => encodeMonoWav(tooLarge, 100_000))
})
