import assert from 'node:assert/strict'
import test from 'node:test'
import { createPcmPlayer } from './pcmPlayer.ts'
import type { PcmFrame } from './voiceProtocol.ts'

class FakeNode {
  onended: (() => void) | null = null
  starts: Array<{ at: number; offset?: number; duration?: number }> = []
  stopped = false
  disconnected = false
  buffer: AudioBuffer | null = null
  connect() {}
  disconnect() { this.disconnected = true }
  start(at: number, offset?: number, duration?: number) {
    this.starts.push({ at, ...(offset === undefined ? {} : { offset }), ...(duration === undefined ? {} : { duration }) })
  }
  stop() { this.stopped = true; this.onended?.() }
  finish() { this.onended?.() }
}
class FakeAudioContext {
  currentTime = 10
  destination = {}
  nodes: FakeNode[] = []
  createBuffer(_channels: number, length: number, sampleRate: number) {
    const samples = new Float32Array(length)
    return { sampleRate, length, getChannelData: () => samples } as unknown as AudioBuffer
  }
  createBufferSource() { const node = new FakeNode(); this.nodes.push(node); return node as unknown as AudioBufferSourceNode }
}
function frame(streamId: number, sequence: number, sampleOffset: number, samples: number[]): PcmFrame {
  return { streamId, sequence, sampleOffset, pcm: Int16Array.from(samples) }
}

test('schedules the first frame before end and places contiguous frames without a fresh 50ms gap', () => {
  const context = new FakeAudioContext(); const progress: any[] = []; const drained: number[] = []
  const player = createPcmPlayer({ audioContext: context as unknown as AudioContext,
    onProgress: value => progress.push(value), onDrained: id => drained.push(id) })
  player.start(7, 24_000)
  player.enqueue(frame(7, 0, 0, [1, 2, 3]))
  player.enqueue(frame(7, 1, 3, [4, 5]))
  assert.equal(context.nodes[0]!.starts[0]!.at, 10.05)
  assert.equal(context.nodes[1]!.starts[0]!.at, context.nodes[0]!.starts[0]!.at + 3 / 24_000)
  player.end(7, 5)
  assert.deepEqual(drained, [])
  context.nodes[0]!.finish(); context.nodes[1]!.finish()
  assert.deepEqual(drained, [7])
  assert.ok(progress.at(-1)!.playedSamples >= 5)
})

test('played samples add scheduled audio only and exclude the network gap between chunks', () => {
  const context = new FakeAudioContext(); const progress: any[] = []
  const player = createPcmPlayer({ audioContext: context as unknown as AudioContext, onProgress: value => progress.push(value) })
  player.start(3, 24_000)
  player.enqueue(frame(3, 0, 0, Array(240).fill(1)))
  context.currentTime = 11
  player.enqueue(frame(3, 1, 240, Array(240).fill(1)))
  context.currentTime = 11.005
  const snapshot = player.progress()
  assert.equal(snapshot.playedSamples, 360) // 第一帧 240，第二帧已播 5ms；网络空档不计入样本
  assert.equal(snapshot.receivedSeq, 1)
  player.dispose()
})

test('old stream frames are ignored, malformed offsets fail, and stop disconnects all nodes without closing context', () => {
  const context = new FakeAudioContext()
  const player = createPcmPlayer({ audioContext: context as unknown as AudioContext })
  player.start(1, 24_000); player.enqueue(frame(1, 0, 0, [1, 2]))
  player.start(2, 24_000)
  assert.throws(() => player.enqueue(frame(1, 1, 2, [3])), /语音流/)
  assert.throws(() => player.enqueue(frame(2, 1, 0, [3])), /序号或样本偏移/)
  player.stop()
  assert.equal(context.nodes[0]!.stopped, true)
  assert.equal(context.nodes[0]!.disconnected, true)
  assert.equal(context.currentTime, 10)
})

test('end with queued audio waits for every node and rejects length mismatch', () => {
  const context = new FakeAudioContext(); const drained: number[] = []
  const player = createPcmPlayer({ audioContext: context as unknown as AudioContext, onDrained: id => drained.push(id) })
  player.start(5, 24_000); player.enqueue(frame(5, 0, 0, [1, 2, 3]))
  assert.throws(() => player.end(5, 2), /样本长度/)
  player.end(5, 3); assert.deepEqual(drained, [])
  context.nodes[0]!.finish(); assert.deepEqual(drained, [5])
})
