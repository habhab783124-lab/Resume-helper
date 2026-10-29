import { ref } from 'vue'

const terminalGeneration = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])

export function useInterviewPolling({ api, onSession = () => {}, onError = () => {}, clock = globalThis, getMaxDelayMs = () => 5000 }) {
  const running = ref(false)
  let controller = null
  let timer = null
  let releaseTimer = null
  let generation = 0

  function stop() {
    generation += 1
    running.value = false
    controller?.abort()
    controller = null
    if (timer !== null) clock.clearTimeout(timer)
    timer = null
    releaseTimer?.()
    releaseTimer = null
  }

  async function start(sessionId) {
    stop()
    const current = ++generation
    running.value = true
    let delay = 1000
    while (running.value && generation === current) {
      controller = new AbortController()
      try {
        const session = await api.getSession(sessionId, { signal: controller.signal })
        if (!running.value || generation !== current) return
        onSession(session)
        const feedbackPending = Object.values(session.practiceFeedbacks || {}).some(item => ['QUEUED', 'RUNNING'].includes(item.status))
        if (session.status === 'FINISHED' || terminalGeneration.has(session.generation?.state) && !feedbackPending) {
          stop()
          return
        }
      } catch (error) {
        if (!running.value || generation !== current || error?.name === 'AbortError') return
        onError(error)
        if (error?.status === 401) { stop(); return }
      } finally {
        controller = null
      }
      const requestedMax = Number(getMaxDelayMs())
      const maxDelay = Number.isFinite(requestedMax) ? Math.max(1000, requestedMax) : 5000
      delay = Math.min(delay, maxDelay)
      await new Promise(resolve => {
        releaseTimer = resolve
        timer = clock.setTimeout(resolve, delay)
      })
      timer = null
      releaseTimer = null
      delay = Math.min(delay + 1000, maxDelay)
    }
  }

  return { running, start, stop }
}
