import { ref } from 'vue'

const PLAYBACK_FAILED = '题目音频无法播放，请重试。'
const AUTOPLAY_BLOCKED = '浏览器阻止了自动播放，请再点一次播放。'
const REQUEST_FAILED = '题目语音生成失败，请稍后重试。'

/**
 * 当前题的朗读控制：同题复用已下载的 Blob URL，切题就停掉旧题的播放和下载。
 *
 * <p>用操作序号挡迟到响应——识别和朗读都可能比用户切题慢，
 * 旧题的音频回来时不能盖住新题，更不能自动播出来。
 *
 * <p>下载用独立的 AbortController，与页面其它请求互不影响。
 */
export function useQuestionSpeech({
  api,
  createAudio = source => new Audio(source),
  createUrl = blob => URL.createObjectURL(blob),
  revokeUrl = url => URL.revokeObjectURL(url),
} = {}) {
  // idle | loading | playing | stopped
  const state = ref('idle')
  const error = ref('')

  let generation = 0
  let pending = null
  let current = null

  function silence(audio) {
    audio.onended = null
    audio.onerror = null
    audio.pause()
    audio.currentTime = 0
    audio.removeAttribute('src')
    audio.load()
  }

  /** 停掉当前下载与播放，不清空状态——调用方马上要设置新的状态。 */
  function release() {
    if (pending) {
      pending.controller.abort()
      pending = null
    }
    if (current) {
      silence(current.audio)
      revokeUrl(current.url)
      current = null
    }
  }

  function beginPlayback(audio) {
    state.value = 'playing'
    audio.play().catch(cause => {
      if (current?.audio !== audio) return
      state.value = 'stopped'
      error.value = cause?.name === 'NotSupportedError' ? PLAYBACK_FAILED : AUTOPLAY_BLOCKED
    })
  }

  function play(sessionId, questionId) {
    error.value = ''
    const key = `${sessionId}:${questionId}`
    if (pending?.key === key) return
    if (current?.key === key && current.url) {
      // 同题已有音频：直接重播，不再花钱合成一次。
      current.audio.currentTime = 0
      beginPlayback(current.audio)
      return
    }
    generation += 1
    const my = generation
    release()
    const controller = new AbortController()
    pending = { key, controller }
    state.value = 'loading'
    api.questionSpeech(sessionId, questionId, controller.signal)
      .then(blob => {
        if (my !== generation) return
        pending = null
        const url = createUrl(blob)
        const audio = createAudio(url)
        current = { key, url, audio, controller }
        audio.onended = () => {
          if (current?.audio === audio) {
            state.value = 'stopped'
            audio.currentTime = 0
          }
        }
        audio.onerror = () => {
          if (current?.audio === audio) {
            state.value = 'idle'
            error.value = PLAYBACK_FAILED
          }
        }
        beginPlayback(audio)
      })
      .catch(cause => {
        if (my !== generation) return
        pending = null
        // 取消也要回到 idle，否则按钮会一直卡在「合成中」。
        state.value = 'idle'
        if (cause?.name !== 'AbortError') {
          error.value = describeRequestError(cause)
        }
      })
  }

  function stop() {
    if (!current) {
      if (pending) {
        generation += 1
        pending.controller.abort()
        pending = null
        state.value = 'idle'
      }
      return
    }
    silence(current.audio)
    state.value = 'stopped'
  }

  function dispose() {
    generation += 1
    release()
    state.value = 'idle'
    error.value = ''
  }

  return { state, error, play, stop, dispose }
}

/** fetch 断网时抛的是 TypeError，消息是英文框架噪音，对用户没有意义。 */
function describeRequestError(cause) {
  if (cause?.name === 'TypeError') return REQUEST_FAILED
  return cause?.message || REQUEST_FAILED
}
