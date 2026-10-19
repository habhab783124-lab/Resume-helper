import { ref } from 'vue'

function modelFailureKind(error) {
  const message = String(error?.message || '')
  if (message.includes('请填写模型 API Key，或由服务端配置 AI_API_KEY 后开始面试')) return 'missing-model'
  if (error?.code === 'BROWSER_UNCONFIRMED' || error?.name === 'TimeoutError') return 'timeout'
  if (error instanceof TypeError || error?.name === 'TypeError' || /网络|连接失败|Failed to fetch|NetworkError|offline/i.test(message)) return 'offline'
  return 'failed'
}

export function useInterviewReadiness({ api, minDurationMs = 450, timeoutMs = 15000, speechTimeoutMs = 5000 } = {}) {
  const status = ref('checking')
  const error = ref('')
  const speechAvailable = ref(false)
  let generation = 0
  let controller
  let animationTimer
  let finishAnimation

  function cancelAnimation() {
    if (animationTimer !== undefined) globalThis.clearTimeout(animationTimer)
    animationTimer = undefined
    finishAnimation?.()
    finishAnimation = null
  }
  function waitForAnimation(ms) {
    if (!ms) return Promise.resolve()
    return new Promise(resolve => {
      finishAnimation = resolve
      animationTimer = globalThis.setTimeout(() => {
        animationTimer = undefined; finishAnimation = null; resolve()
      }, ms)
    })
  }

  async function check() {
    const current = ++generation
    controller?.abort()
    cancelAnimation()
    controller = new AbortController()
    const signal = controller.signal
    status.value = 'checking'; error.value = ''; speechAvailable.value = false
    const began = Date.now()
    const results = await Promise.allSettled([
      Promise.resolve().then(() => api.checkAi(undefined, { signal, timeoutMs })),
      Promise.resolve().then(() => api.getSpeechCapabilities({ signal, timeoutMs: speechTimeoutMs })),
    ])
    const remaining = Math.max(0, minDurationMs - (Date.now() - began))
    if (remaining) await waitForAnimation(remaining)
    if (current !== generation) return { status: status.value, stale: true }
    const model = results[0]
    const speech = results[1]
    if (speech.status === 'fulfilled') speechAvailable.value = speech.value?.ttsStreamingEnabled === true
    if (model.status === 'rejected') {
      const failure = modelFailureKind(model.reason)
      status.value = failure
      error.value = failure === 'missing-model' ? model.reason?.message || ''
        : failure === 'offline' ? '服务连接失败，请检查网络后重试。'
          : failure === 'timeout' ? '服务响应超时，请重试。'
            : model.reason?.message || '模型服务暂不可用，请稍后重试。'
    } else {
      status.value = 'ready'
    }
    return { status: status.value, speechAvailable: speechAvailable.value, error: error.value }
  }

  function dispose() { generation++; controller?.abort(); controller = null; cancelAnimation() }
  return { status, error, speechAvailable, check, dispose }
}
