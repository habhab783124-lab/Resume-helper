export type VoiceVadOptions = {
  now?: () => number
  onCandidate?: () => void
  onSpeechStart?: () => void
  onSpeechEnd?: () => void
}

const CALIBRATION_MS = 400
const WINDOW_MS = 300
const SPEECH_RATIO = 0.8
const ENDPOINT_SILENCE_MS = 2000
const PLAYBACK_GRACE_MS = 500
const PLAYBACK_TAIL_MS = 250

/** Pure VAD state machine. `rms` is the Float32 audio frame RMS. */
export function createVoiceVad({ now = () => performance.now(), onCandidate = () => {},
  onSpeechStart = () => {}, onSpeechEnd = () => {} }: VoiceVadOptions = {}) {
  let calibrationMs = 0
  let calibrationTotal = 0
  let noiseFloor = 0.02
  let window: Array<{ durationMs: number; above: boolean }> = []
  let windowMs = 0
  let candidate = false
  let speaking = false
  let silenceMs = 0
  let playback = false
  let playbackStartedAt = -Infinity
  let playbackTailUntil = -Infinity

  function threshold(at: number) {
    const ordinary = Math.min(0.37, Math.max(0.075, noiseFloor * 3.5))
    if (!playback && at >= playbackTailUntil) return ordinary
    return Math.min(0.37, Math.max(0.14, noiseFloor * 3.5))
  }
  function addWindow(durationMs: number, above: boolean) {
    window.push({ durationMs, above })
    windowMs += durationMs
    while (windowMs > WINDOW_MS && window.length) {
      const excess = windowMs - WINDOW_MS
      const first = window[0]!
      if (first.durationMs <= excess) { window.shift(); windowMs -= first.durationMs }
      else { first.durationMs -= excess; windowMs -= excess }
    }
  }
  function reset() {
    calibrationMs = 0
    calibrationTotal = 0
    noiseFloor = 0.02
    window = []
    windowMs = 0
    candidate = false
    speaking = false
    silenceMs = 0
    playback = false
    playbackStartedAt = -Infinity
    playbackTailUntil = -Infinity
  }
  function feed(rms: number, durationMs: number) {
    if (!Number.isFinite(rms) || rms < 0 || !Number.isFinite(durationMs) || durationMs <= 0) return
    const at = now()
    const level = Math.min(1, rms * 128 / 42)
    const activePlaybackThreshold = playback || at < playbackTailUntil
    const playbackGrace = playback && at - playbackStartedAt < PLAYBACK_GRACE_MS
    const above = !playbackGrace && level >= threshold(at)

    if (!speaking && !activePlaybackThreshold) {
      if (calibrationMs < CALIBRATION_MS) {
        // Do not learn speech as the ambient floor during the initial calibration window.
        if (level < 0.075) { calibrationTotal += level * durationMs; calibrationMs += durationMs }
        if (calibrationMs >= CALIBRATION_MS) noiseFloor = calibrationTotal / calibrationMs
      } else if (!above) noiseFloor = noiseFloor * 0.98 + level * 0.02
    }

    addWindow(durationMs, above)
    if (above && !candidate && !speaking) { candidate = true; onCandidate() }
    if (!above) candidate = false

    if (!speaking && windowMs >= WINDOW_MS) {
      const aboveMs = window.reduce((sum, frame) => sum + (frame.above ? frame.durationMs : 0), 0)
      if (aboveMs / windowMs >= SPEECH_RATIO) {
        speaking = true
        candidate = false
        silenceMs = 0
        onSpeechStart()
      }
    } else if (speaking) {
      if (above) silenceMs = 0
      else silenceMs += durationMs
      if (silenceMs >= ENDPOINT_SILENCE_MS) {
        speaking = false
        candidate = false
        silenceMs = 0
        window = []
        windowMs = 0
        onSpeechEnd()
      }
    }
  }
  function setPlayback(active: boolean) {
    const at = now()
    if (active && !playback) {
      playback = true
      playbackStartedAt = at
      playbackTailUntil = -Infinity
    } else if (!active && playback) {
      playback = false
      playbackTailUntil = at + PLAYBACK_TAIL_MS
    }
  }
  return { feed, setPlayback, reset }
}
