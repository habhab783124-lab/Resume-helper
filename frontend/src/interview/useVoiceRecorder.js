import { ref } from 'vue'

/** 浏览器能录的封装各不相同，探测到哪个用哪个，绝不手工改扩展名。 */
const RECORDABLE_TYPES = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/ogg;codecs=opus']
const UNSUPPORTED = '当前浏览器不支持录音，请改用文字回答。'
const START_FAILED = '录音启动失败，请改用文字回答。'
const TOO_LARGE = '录音超过大小限制，请缩短后重新录音。'

/**
 * 面试录音的生命周期：申请麦克风 → 录音 → 保留可重试的 Blob → 释放设备。
 *
 * <p>所有异步回调都带操作序号：许可、onstop 都是异步回来的，
 * 期间用户可能已经切题或离开页面，那些迟到回调不能启动录音、也不能把 Blob 塞回界面。
 *
 * <p>不抛错：录音失败只写 {@code error}，文字输入始终可用。
 */
export function useVoiceRecorder({
  maxSeconds = 120,
  maxBytes = 5 * 1024 * 1024,
  mediaDevices = globalThis.navigator?.mediaDevices,
  Recorder = globalThis.MediaRecorder,
} = {}) {
  // idle | requesting-mic | recording | recorded
  const state = ref('idle')
  const blob = ref(null)
  const durationSeconds = ref(0)
  const error = ref('')

  let generation = 0
  let stream = null
  let recorder = null
  let chunks = []
  let seconds = 0
  let timer = null

  function clearTimer() {
    if (timer !== null) clearInterval(timer)
    timer = null
  }

  function stopTracks() {
    stream?.getTracks().forEach(track => track.stop())
    stream = null
  }

  /** 丢弃当前录音并释放麦克风；cancel / dispose 共用。 */
  function release() {
    generation += 1
    clearTimer()
    if (recorder) {
      // 先摘掉回调：stop() 触发的 onstop 属于上一次录音，不该再执行。
      recorder.ondataavailable = null
      recorder.onstop = null
      recorder.onerror = null
      if (recorder.state !== 'inactive') {
        try { recorder.stop() } catch { /* 已经在停止过程中 */ }
      }
      recorder = null
    }
    stopTracks()
    chunks = []
    blob.value = null
    durationSeconds.value = 0
    state.value = 'idle'
  }

  function cancel() {
    release()
  }

  function dispose() {
    release()
    error.value = ''
  }

  /** 正常结束：片段留在 Blob 里供识别重试，同时释放麦克风。 */
  function stop(message = '') {
    // 许可弹窗还没回来时点「停止」：让迟到的许可直接失效，而不是开始录音。
    // 这里不能走 onstop——录音还没开始，没有片段要保留。
    if (state.value === 'requesting-mic') {
      cancel()
      error.value = message
      return
    }
    const current = recorder
    if (!current || current.state === 'inactive') return
    if (message) error.value = message
    try { current.stop() } catch { current.onstop?.() }
  }

  async function start() {
    cancel()
    if (!mediaDevices?.getUserMedia || !Recorder || typeof Recorder.isTypeSupported !== 'function') {
      fail(UNSUPPORTED)
      return
    }
    error.value = ''
    const my = ++generation
    state.value = 'requesting-mic'

    let requested
    try {
      requested = await mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
        video: false
      })
    } catch (cause) {
      if (my === generation) fail(describeMicError(cause))
      return
    }
    // 许可是异步返回的：用户可能已经在等待期间点了取消，这里必须立刻关掉麦克风。
    if (my !== generation) {
      requested.getTracks().forEach(track => track.stop())
      return
    }

    stream = requested
    chunks = []
    seconds = 0
    let mimeType
    try {
      mimeType = RECORDABLE_TYPES.find(type => Recorder.isTypeSupported(type))
      recorder = new Recorder(stream, mimeType ? { mimeType } : {})
    } catch {
      release()
      fail(START_FAILED)
      return
    }
    recorder.ondataavailable = event => {
      if (event?.data?.size) {
        chunks.push(event.data)
        if (chunks.reduce((total, chunk) => total + chunk.size, 0) > maxBytes) {
          stop(TOO_LARGE)
        }
      }
    }
    recorder.onerror = () => {
      if (my !== generation) return
      release()
      fail('录音中断，请重新录音或改用文字回答。')
    }
    recorder.onstop = () => {
      if (my !== generation) return
      clearTimer()
      if (chunks.length) blob.value = new Blob(chunks, { type: recorder.mimeType || mimeType || 'audio/webm' })
      else error.value = '录音为空，请重新录音或改用文字回答。'
      state.value = blob.value ? 'recorded' : 'idle'
      stopTracks()
    }
    try {
      recorder.start(1000)
    } catch {
      release()
      fail(START_FAILED)
      return
    }
    state.value = 'recording'
    timer = setInterval(() => {
      seconds += 1
      durationSeconds.value = seconds
      if (seconds >= maxSeconds) stop()
    }, 1000)
  }

  function fail(message) {
    state.value = 'idle'
    error.value = message
  }

  function describeMicError(cause) {
    const name = cause?.name || ''
    if (name === 'NotAllowedError' || name === 'PermissionDeniedError') return '麦克风权限被拒绝，请改用文字回答。'
    if (name === 'NotFoundError' || name === 'DevicesNotFoundError') return '没有可用的麦克风，请改用文字回答。'
    if (name === 'NotReadableError' || name === 'TrackStartError') return '麦克风被占用，请改用文字回答。'
    return '麦克风不可用，请改用文字回答。'
  }

  return { state, blob, durationSeconds, error, start, stop, cancel, dispose }
}
