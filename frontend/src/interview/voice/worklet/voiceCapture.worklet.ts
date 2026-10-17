/// <reference path="./worklet-globals.d.ts" />
import type { CaptureCommand, CaptureEvent } from '../voiceCaptureProtocol.ts'

const FRAME_MS = 20
const MAX_UNACKED_MS = 200

class VoiceCaptureProcessor extends AudioWorkletProcessor {
  private sampleRateValue = sampleRate
  private frameSamples = Math.max(1, Math.round(this.sampleRateValue * FRAME_MS / 1000))
  private pending = new Float32Array(this.frameSamples)
  private pendingCount = 0
  private nextSequence = 0
  private nextSample = 0
  private generation = 0
  private unackedSequence: number | null = null
  private droppedSamples = 0
  private sealBarrier: number | null = null
  private stopped = false

  constructor() {
    super()
    this.port.onmessage = event => this.command(event.data as CaptureCommand)
  }

  private command(command: CaptureCommand) {
    switch (command.type) {
      case 'frame.ack':
        if (command.sequence === this.unackedSequence) {
          this.unackedSequence = null
          this.droppedSamples = 0
          if (this.sealBarrier !== null) this.finishSeal()
        }
        break
      case 'seal':
        this.sealBarrier = command.barrierId
        if (this.unackedSequence === null) this.finishSeal()
        break
      case 'resume':
        this.stopped = false
        this.generation += 1
        this.nextSequence = 0
        this.nextSample = 0
        this.pendingCount = 0
        this.droppedSamples = 0
        this.unackedSequence = null
        this.sealBarrier = null
        break
      case 'stop':
        this.stopped = true
        this.pendingCount = 0
        this.sealBarrier = null
        break
    }
  }

  private finishSeal() {
    if (this.pendingCount > 0) {
      const count = this.pendingCount
      this.emitFrame(this.pending.subarray(0, count))
      this.pendingCount = 0
    }
    if (this.unackedSequence !== null) return
    const event: CaptureEvent = { type: 'sealed', barrierId: this.sealBarrier!,
      lastSequence: this.nextSequence - 1, lastSample: this.nextSample }
    this.port.postMessage(event)
    this.sealBarrier = null
    this.stopped = true
  }

  private emitFrame(source: Float32Array) {
    if (!source.length || this.unackedSequence !== null) return
    const pcm = new Int16Array(source.length)
    let sum = 0
    for (let i = 0; i < source.length; i++) {
      const sample = Math.max(-1, Math.min(1, source[i]!))
      sum += sample * sample
      pcm[i] = sample < 0 ? Math.round(sample * 32768) : Math.round(sample * 32767)
    }
    const sequence = this.nextSequence++
    const firstSample = this.nextSample
    this.nextSample += source.length
    const event: CaptureEvent = { type: 'frame', generation: this.generation, sequence,
      sampleRate: this.sampleRateValue, firstSample, sampleCount: source.length,
      rms: Math.sqrt(sum / source.length), pcm: pcm.buffer }
    this.unackedSequence = sequence
    this.port.postMessage(event, [pcm.buffer])
  }

  process(inputs: Float32Array[][]): boolean {
    if (this.stopped) return true
    const channel = inputs[0]?.[0]
    if (!channel?.length) return true
    if (this.unackedSequence !== null) {
      this.droppedSamples += channel.length
      if (this.droppedSamples > this.sampleRateValue * MAX_UNACKED_MS / 1000) {
        const event: CaptureEvent = { type: 'capture.overrun', generation: this.generation }
        this.port.postMessage(event)
        this.stopped = true
      }
      return true
    }
    let offset = 0
    while (offset < channel.length) {
      const count = Math.min(this.frameSamples - this.pendingCount, channel.length - offset)
      this.pending.set(channel.subarray(offset, offset + count), this.pendingCount)
      this.pendingCount += count
      offset += count
      if (this.pendingCount === this.frameSamples) {
        this.emitFrame(this.pending)
        this.pendingCount = 0
        // The next render quantum may arrive before the main thread ACKs. The next
        // process call bounds this delay and reports overrun instead of buffering.
        if (this.unackedSequence !== null && offset < channel.length) {
          this.droppedSamples += channel.length - offset
          break
        }
      }
    }
    return true
  }
}

registerProcessor('resume-helper-voice-capture', VoiceCaptureProcessor)
