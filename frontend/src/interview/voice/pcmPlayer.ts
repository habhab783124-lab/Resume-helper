import type { PcmFrame } from './voiceProtocol.ts'

type Scheduled = { node: AudioBufferSourceNode; startAt: number; samples: number }

export function createPcmPlayer({
  audioContext,
  onProgress = () => {},
  onDrained = () => {},
  initialLeadSeconds = 0.05,
}: {
  audioContext: AudioContext
  onProgress?: (progress: { streamId: number; receivedSeq: number; playedSamples: number }) => void
  onDrained?: (streamId: number) => void
  initialLeadSeconds?: number
}) {
  let streamId: number | null = null
  let sampleRate = 24000
  let nextSeq = 0
  let receivedSamples = 0
  let completedSamples = 0
  let nextStartAt = 0
  let endedSamples: number | null = null
  let drainedSent = false
  let nodes: Scheduled[] = []

  function report() {
    if (streamId === null) return
    const inFlight = nodes.reduce((sum, scheduled) => {
      const elapsedSamples = Math.max(0, Math.min(scheduled.samples,
        Math.floor((audioContext.currentTime - scheduled.startAt) * sampleRate)))
      return sum + elapsedSamples
    }, 0)
    onProgress({ streamId, receivedSeq: nextSeq - 1, playedSamples: Math.min(receivedSamples, completedSamples + inFlight) })
  }

  function maybeDrained() {
    if (streamId === null || endedSamples === null || nodes.length !== 0 || drainedSent) return
    drainedSent = true
    onProgress({ streamId, receivedSeq: nextSeq - 1, playedSamples: endedSamples })
    onDrained(streamId)
  }

  function start(id: number, rate: number) {
    if (!Number.isInteger(id) || id < 1 || !Number.isInteger(rate) || rate < 8000 || rate > 96000) throw new Error('PCM 播放参数无效')
    stop()
    streamId = id
    sampleRate = rate
    nextSeq = 0
    receivedSamples = 0
    completedSamples = 0
    nextStartAt = audioContext.currentTime + initialLeadSeconds
    endedSamples = null
    drainedSent = false
  }

  function enqueue(frame: PcmFrame) {
    if (streamId === null || frame.streamId !== streamId) throw new Error('PCM 帧属于过期或未启动的语音流')
    if (endedSamples !== null) throw new Error('PCM 语音流已结束')
    if (frame.sequence !== nextSeq || frame.sampleOffset !== receivedSamples || frame.pcm.length < 1) {
      throw new Error('PCM 帧序号或样本偏移无效')
    }
    const buffer = audioContext.createBuffer(1, frame.pcm.length, sampleRate)
    const channel = buffer.getChannelData(0)
    for (let index = 0; index < frame.pcm.length; index++) channel[index] = frame.pcm[index]! / 32768
    const node = audioContext.createBufferSource()
    node.buffer = buffer
    node.connect(audioContext.destination)
    const startAt = Math.max(nextStartAt, audioContext.currentTime)
    const duration = frame.pcm.length / sampleRate
    const scheduled = { node, startAt, samples: frame.pcm.length }
    nodes.push(scheduled)
    node.onended = () => {
      if (!nodes.includes(scheduled)) return
      nodes = nodes.filter(item => item !== scheduled)
      completedSamples += scheduled.samples
      try { node.disconnect() } catch { /* already disconnected */ }
      report()
      maybeDrained()
    }
    node.start(startAt)
    nextStartAt = startAt + duration
    receivedSamples += frame.pcm.length
    nextSeq++
    report()
  }

  function end(id: number, totalSamples: number) {
    if (streamId === null || id !== streamId) throw new Error('PCM 结束消息属于过期语音流')
    if (!Number.isInteger(totalSamples) || totalSamples < 1 || totalSamples !== receivedSamples) throw new Error('PCM 结束样本长度不匹配')
    endedSamples = totalSamples
    maybeDrained()
  }

  function stop() {
    for (const { node } of nodes) {
      node.onended = null
      try { node.stop() } catch { /* node may have ended */ }
      try { node.disconnect() } catch { /* already disconnected */ }
    }
    nodes = []
    streamId = null
    endedSamples = null
  }

  return { start, enqueue, end, progress: () => { report(); return snapshot() }, stop, dispose: stop }

  function snapshot() {
    if (streamId === null) return { streamId: 0, receivedSeq: -1, playedSamples: 0 }
    const inFlight = nodes.reduce((sum, scheduled) => sum + Math.max(0, Math.min(scheduled.samples,
      Math.floor((audioContext.currentTime - scheduled.startAt) * sampleRate))), 0)
    return { streamId, receivedSeq: nextSeq - 1, playedSamples: Math.min(receivedSamples, completedSamples + inFlight) }
  }
}
