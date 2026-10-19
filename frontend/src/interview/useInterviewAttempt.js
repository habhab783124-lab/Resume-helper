import { computed, ref } from 'vue'

export function useInterviewAttempt({ api, sessionId, questionId, storage = null, scope = {},
  ids = () => globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}` } = {}) {
  const versions = ref([])
  const answer = ref('')
  const status = ref('IDLE')
  const result = ref(null)
  const error = ref('')
  const busy = ref(false)
  const key = `career-studio-attempt-draft-v1|${scope.apiBaseUrl || ''}|${scope.userId || ''}|${sessionId || ''}|${questionId || ''}|attemptDraft`
  let epoch = 0
  let inFlight = null
  let controller = null
  function restoreDraft() {
    try {
      const raw = storage?.getItem(key)
      const parsed = raw ? JSON.parse(raw) : null
      if (typeof parsed?.text === 'string') answer.value = parsed.text
    } catch { /* Storage is optional. */ }
  }
  restoreDraft()
  const latest = computed(() => versions.value.at(-1) || null)
  const canSubmit = computed(() => versions.value.length < 5 && !!answer.value.trim() && !busy.value)
  function saveDraft() {
    try { storage?.setItem(key, JSON.stringify({ text: answer.value, updatedAt: Date.now() })) } catch { /* Storage is optional. */ }
  }
  function applyVersion(value, expected = epoch) {
    if (expected !== epoch || !value?.attemptId) return false
    const index = versions.value.findIndex(item => item.attemptId === value.attemptId)
    if (index < 0) versions.value = [...versions.value, value].sort((a, b) => a.version - b.version)
    else versions.value = versions.value.map(item => item.attemptId === value.attemptId ? value : item)
    result.value = value
    status.value = value.status
    return true
  }
  async function load() {
    const expected = ++epoch
    error.value = ''
    try {
      const items = await api.listAttempts(sessionId, questionId)
      if (expected !== epoch) return false
      versions.value = [...(items || [])].sort((a, b) => a.version - b.version)
      const current = versions.value.at(-1) || null
      result.value = current
      status.value = current?.status || 'IDLE'
      return true
    } catch (cause) {
      if (expected === epoch) error.value = cause?.message || '重答记录暂时无法读取。'
      return false
    }
  }
  async function submit() {
    if (inFlight) return inFlight
    if (versions.value.length >= 5) { error.value = '每道题最多保存五版重答。'; return false }
    const text = answer.value.trim()
    if (!text) { error.value = '请先写下新的回答。'; return false }
    if (text.length > 5000) { error.value = '重答不能超过 5000 字。'; return false }
    saveDraft()
    const expected = epoch
    const requestId = ids()
    const requestController = new AbortController()
    controller = requestController
    busy.value = true
    error.value = ''
    const operation = (async () => {
      try {
        const value = await api.submitAttempt(sessionId, { questionId, requestId, answer: text }, requestController.signal)
        if (!applyVersion(value, expected)) return false
        answer.value = ''
        try { storage?.removeItem(key) } catch { /* Storage is optional. */ }
        return true
      } catch (cause) {
        if (expected === epoch) error.value = cause?.message || '重答暂未保存，请保留原文后重试。'
        return false
      } finally {
        if (controller === requestController) {
          busy.value = false
          controller = null
        }
        if (inFlight === operation) inFlight = null
      }
    })()
    inFlight = operation
    return operation
  }
  async function refresh() {
    const attemptId = result.value?.attemptId
    if (!attemptId) return false
    const expected = epoch
    try {
      const value = await api.getAttempt(sessionId, attemptId)
      return applyVersion(value, expected)
    } catch (cause) {
      if (expected === epoch) error.value = cause?.message || '重答状态暂时无法读取。'
      return false
    }
  }
  async function retry() {
    if (!result.value || result.value.status !== 'FAILED' || busy.value) return false
    const expected = epoch
    busy.value = true
    error.value = ''
    try {
      const value = await api.retryAttempt(sessionId, result.value.attemptId, { requestId: ids() })
      return applyVersion(value, expected)
    } catch (cause) {
      if (expected === epoch) error.value = cause?.message || '重答补评失败，已保存的答案仍可重试。'
      return false
    } finally { if (expected === epoch) busy.value = false }
  }
  function dispose() {
    epoch++
    controller?.abort()
    controller = null
    inFlight = null
    busy.value = false
  }
  return { answer, versions, latest, status, result, error, busy, canSubmit, saveDraft, load, submit, refresh, retry, dispose }
}
