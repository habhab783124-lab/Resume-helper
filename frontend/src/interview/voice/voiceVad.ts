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
const MIN_SPEECH_RMS = 0.0012
const INITIAL_NOISE_RMS = 0.0005

/** Pure VAD state machine. `rms` is the Float32 audio frame RMS. */
export function createVoiceVad({ now = () => performance.now(), onCandidate = () => {},
  onSpeechStart = () => {}, onSpeechEnd = () => {} }: VoiceVadOptions = {}) {
  let calibrationMs = 0
  let calibrationTotal = 0
  let noiseFloor = INITIAL_NOISE_RMS
  let window: Array<{ durationMs: number; above: boolean }> = []
  let windowMs = 0
  let candidate = false
  let speaking = false
  let silenceMs = 0
  let playback = false
  let playbackStartedAt = -Infinity
  let playbackTailUntil = -Infinity

  function threshold(at: number) {
    const ordinary = Math.min(0.04, Math.max(MIN_SPEECH_RMS, noiseFloor * 2.2))
    if (!playback && at >= playbackTailUntil) return ordinary
    return Math.min(0.08, Math.max(0.015, noiseFloor * 3.5))
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
    noiseFloor = INITIAL_NOISE_RMS
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
    const activePlaybackThreshold = playback || at < playbackTailUntil
    const playbackGrace = playback && at - playbackStartedAt < PLAYBACK_GRACE_MS
    const above = !playbackGrace && rms >= threshold(at)

    if (!speaking && !activePlaybackThreshold) {
      if (calibrationMs < CALIBRATION_MS) {
        // Do not learn speech as the ambient floor during the initial calibration window.
        if (rms < MIN_SPEECH_RMS) { calibrationTotal += rms * durationMs; calibrationMs += durationMs }
        if (calibrationMs >= CALIBRATION_MS) noiseFloor = calibrationTotal / calibrationMs
      } else if (!above) noiseFloor = noiseFloor * 0.98 + rms * 0.02
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
