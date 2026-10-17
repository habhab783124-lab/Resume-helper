export type CaptureFrame = {
  type: 'frame'
  generation: number
  sequence: number
  sampleRate: number
  firstSample: number
  sampleCount: number
  rms: number
  pcm: ArrayBuffer
}

export type CaptureCommand =
  | { type: 'seal'; barrierId: number }
  | { type: 'frame.ack'; sequence: number }
  | { type: 'resume' }
  | { type: 'stop' }

export type CaptureBarrier = {
  type: 'sealed'
  barrierId: number
  lastSequence: number
  lastSample: number
}

export type CaptureOverrun = { type: 'capture.overrun'; generation: number }
export type CaptureEvent = CaptureFrame | CaptureBarrier | CaptureOverrun
