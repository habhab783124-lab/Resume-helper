import { computed, ref } from 'vue'

const generationStates = new Set(['QUEUED', 'RUNNING', 'RETRY_WAIT'])

export function useInterviewConversation({ api, drafts = {}, voice = null, polling = null,
  ids = () => globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`,
  storage = null, scope = {} } = {}) {
  const session = ref(null)
  const draft = ref('')
  const phase = ref('PREPARING')
  const error = ref('')
  const submission = ref(null)
  const active = computed(() => session.value?.status === 'ACTIVE')
  let epoch = 0
  let inFlight = null
  let operationController = null
  let generationRequestId = null
  let currentScope = { apiBaseUrl: scope.apiBaseUrl || '', userId: scope.userId || '' }
  let pointerKey = makePointerKey(currentScope)

  function makePointerKey(value) {
    return `career-studio-interview-id-v2|${value.apiBaseUrl || ''}|${value.userId || ''}`
  }

  function remember(id) {
    try { if (id) storage?.setItem(pointerKey, id); else storage?.removeItem(pointerKey) } catch { /* Storage is optional. */ }
  }
  function applySession(value, expectedEpoch = epoch) {
    if (expectedEpoch !== epoch || !value?.sessionId || !Array.isArray(value.turns)) return false
    if (session.value?.sessionId === value.sessionId
        && Number(value.stateVersion || 0) < Number(session.value.stateVersion || 0)) return false
    const changed = session.value?.sessionId !== value.sessionId
      || session.value?.currentQuestion?.questionId !== value.currentQuestion?.questionId
    session.value = value
    if (changed) { draft.value = value.pendingAnswer || ''; submission.value = null; generationRequestId = null }
    remember(value.sessionId)
    const generation = value.generation?.state
    if (value.status === 'FINISHED') phase.value = 'FINISHED'
    else if (generation === 'FAILED') phase.value = 'GENERATION_FAILED'
    else if (generation === 'RETRY_WAIT') phase.value = 'RETRY_WAIT'
    else if (generationStates.has(generation)) phase.value = 'GENERATING'
    else phase.value = value.status === 'ACTIVE' ? 'ANSWERING' : 'PREPARING'
    voice?.syncContext?.()
    return true
  }
  function makeSubmission(questionId, text) {
    if (submission.value?.questionId === questionId && submission.value.answer === text) return submission.value
    submission.value = { questionId, submissionId: ids(), answer: text }
    return submission.value
  }
  async function runGeneration(expectedEpoch, signal = operationController?.signal) {
    if (!session.value || expectedEpoch !== epoch) return false
    generationRequestId = ids()
    phase.value = 'GENERATING'
    try {
      const result = await api.generate(session.value.sessionId, {
        requestId: generationRequestId, kind: 'NEXT', expectedRevision: session.value.revision
      }, signal)
      if (applySession(result, expectedEpoch) && generationStates.has(result.generation?.state)) polling?.start?.(result.sessionId)
      return true
    } catch (cause) {
      if (expectedEpoch !== epoch) return false
      phase.value = 'GENERATION_FAILED'
      error.value = cause?.message || '后续问题生成失败，可重试生成。'
      return false
    }
  }
  async function send() {
    if (inFlight) return inFlight
    if (!active.value || !session.value?.currentQuestion) return false
    const text = (submission.value?.answer ?? draft.value).trim()
    if (!text || text.length > 5000) { error.value = text ? '回答不能超过 5000 字，请精简后提交。' : ''; return false }
    const questionId = session.value.currentQuestion.questionId
    const payload = makeSubmission(questionId, text)
    const expectedEpoch = epoch
    const requestController = new AbortController()
    operationController = requestController
    inFlight = (async () => {
      error.value = ''
      phase.value = 'SAVING'
      drafts.save?.(session.value.sessionId, questionId, { text })
      let saved
      try {
        saved = await api.saveAnswer(session.value.sessionId, payload, requestController.signal)
      } catch (saveError) {
        if (saveError?.status >= 400 && saveError.status < 500 && saveError.status !== 409) {
          if (expectedEpoch === epoch) {
            submission.value = null
            phase.value = 'ANSWERING'
            error.value = saveError.message || '回答未被接受，请修改后重试。'
          }
          return false
        }
        try {
          const recovered = await api.getSession(session.value.sessionId, { signal: requestController.signal })
          if (expectedEpoch !== epoch) return false
          applySession(recovered, expectedEpoch)
          const turn = recovered.turns?.find(item => item.questionId === questionId)
          if (turn?.answer !== text) throw saveError
          saved = recovered
        } catch {
          if (expectedEpoch === epoch) {
            phase.value = 'ANSWERING'
            draft.value = text
            error.value = saveError?.message || '回答是否保存尚未确认；原文和提交编号已保留，请重试或恢复会话。'
          }
          return false
        }
      }
      if (!applySession(saved, expectedEpoch)) return false
      const savedTurn = saved.turns?.find(item => item.questionId === questionId)
      if (savedTurn?.answer !== text) {
        phase.value = 'ANSWERING'
        draft.value = text
        error.value = '服务端尚未确认回答已保存，请恢复会话后重试。'
        return false
      }
      drafts.remove?.(session.value.sessionId, questionId)
      draft.value = ''
      submission.value = null
      if (session.value.status === 'FINISHED') return true
      return runGeneration(expectedEpoch, requestController.signal)
    })().finally(() => {
      inFlight = null
      if (operationController === requestController) operationController = null
    })
    return inFlight
  }
  async function retryGeneration() {
    if (!session.value || !['GENERATION_FAILED', 'RETRY_WAIT'].includes(phase.value)) return false
    polling?.stop?.()
    operationController?.abort()
    const controller = new AbortController()
    operationController = controller
    try { return await runGeneration(epoch, controller.signal) }
    finally { if (operationController === controller) operationController = null }
  }
  async function restore(id) {
    const expectedEpoch = ++epoch
    polling?.stop?.()
    operationController?.abort()
    const controller = new AbortController()
    operationController = controller
    phase.value = 'PREPARING'
    error.value = ''
    try {
      const value = await api.getSession(id, { signal: controller.signal })
      if (!applySession(value, expectedEpoch)) return false
      if (generationStates.has(value.generation?.state)) polling?.start?.(value.sessionId)
      return true
    } catch (cause) {
      if (expectedEpoch === epoch) { error.value = cause?.message || '恢复面试失败'; phase.value = 'PREPARING' }
      return false
    }
  }
  function beginFinish() {
    const previousPhase = phase.value
    epoch += 1
    operationController?.abort()
    operationController = null
    inFlight = null
    polling?.stop?.()
    phase.value = 'FINISHING'
    return previousPhase
  }
  function cancelFinish() {
    if (phase.value !== 'FINISHING') return
    const generation = session.value?.generation?.state
    if (generation === 'FAILED') phase.value = 'GENERATION_FAILED'
    else if (generation === 'RETRY_WAIT') phase.value = 'RETRY_WAIT'
    else if (generationStates.has(generation)) { phase.value = 'GENERATING'; polling?.start?.(session.value.sessionId) }
    else phase.value = active.value ? 'ANSWERING' : 'FINISHED'
  }
  function switchScope(next = {}) {
    epoch += 1
    operationController?.abort()
    operationController = null
    inFlight = null
    polling?.stop?.()
    currentScope = { apiBaseUrl: next.apiBaseUrl || '', userId: next.userId || '' }
    pointerKey = makePointerKey(currentScope)
    session.value = null
    draft.value = ''
    submission.value = null
    generationRequestId = null
    phase.value = 'PREPARING'
    error.value = ''
    try { return storage?.getItem(pointerKey) || '' } catch { return '' }
  }
  function dispose() { epoch += 1; operationController?.abort(); polling?.stop?.(); voice?.dispose?.() }

  return { session, draft, phase, error, submission, active, applySession, send, retryGeneration, restore, beginFinish, cancelFinish, switchScope, dispose }
}
