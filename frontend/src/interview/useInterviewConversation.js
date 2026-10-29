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
  let generationError = ''
  let epoch = 0
  let inFlight = null
  let inFlightPayload = null
  let lastVoiceResult = null
  let operationController = null
  let clarificationController = null
  let replacementController = null
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
    if (generation === 'FAILED') {
      generationError = typeof value.generation?.failure?.message === 'string'
        ? value.generation.failure.message : '题目生成失败，可以单独重试生成。'
      error.value = generationError
    } else if (generationError) {
      if (error.value === generationError) error.value = ''
      generationError = ''
    }
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
  async function performSubmission(payload, text, questionId, expectedEpoch, requestController) {
    error.value = ''
    phase.value = 'SAVING'
    drafts.save?.(session.value.sessionId, questionId, { text })
    let saved
    try {
      saved = await api.saveAnswer(session.value.sessionId, payload, requestController.signal)
    } catch (saveError) {
      if (saveError?.status >= 400 && saveError.status < 500 && saveError.status !== 409) {
        lastVoiceResult = { outcome: 'REJECTED', questionId, submissionId: payload.submissionId, generation: 'NONE' }
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
          lastVoiceResult = { outcome: 'UNCERTAIN', questionId, submissionId: payload.submissionId, generation: 'NONE' }
        }
        return false
      }
    }
    if (!applySession(saved, expectedEpoch)) {
      lastVoiceResult = { outcome: 'STALE', questionId, submissionId: payload.submissionId, generation: 'NONE' }
      return false
    }
    const savedTurn = saved.turns?.find(item => item.questionId === questionId)
    if (savedTurn?.answer !== text) {
      phase.value = 'ANSWERING'
      draft.value = text
      error.value = '服务端尚未确认回答已保存，请恢复会话后重试。'
      lastVoiceResult = { outcome: 'UNCERTAIN', questionId, submissionId: payload.submissionId, generation: 'NONE' }
      return false
    }
    drafts.remove?.(session.value.sessionId, questionId)
    draft.value = ''
    submission.value = null
    if (session.value.status === 'FINISHED') {
      lastVoiceResult = { outcome: 'FINISHED', questionId, submissionId: payload.submissionId, generation: 'NONE' }
      return true
    }
    if (session.value.plan?.mode === 'QUICK') {
      lastVoiceResult = { outcome: 'SAVED', questionId, submissionId: payload.submissionId, generation: 'NONE' }
      return true
    }
    const generated = await runGeneration(expectedEpoch, requestController.signal)
    lastVoiceResult = { outcome: 'SAVED', questionId, submissionId: payload.submissionId, generation: generated ? 'PENDING' : 'FAILED' }
    return generated
  }

  async function send(frozenPayload = null) {
    if (inFlight) {
      if (frozenPayload && inFlightPayload?.questionId === frozenPayload.questionId
          && inFlightPayload?.submissionId === frozenPayload.submissionId
          && inFlightPayload?.answer === frozenPayload.answer) return inFlight
      return false
    }
    if (!active.value || !session.value?.currentQuestion) return false
    if (frozenPayload && (frozenPayload.questionId !== session.value.currentQuestion.questionId
        || typeof frozenPayload.answer !== 'string' || !frozenPayload.submissionId)) {
      lastVoiceResult = { outcome: 'STALE', questionId: frozenPayload.questionId, submissionId: frozenPayload.submissionId, generation: 'NONE' }
      return false
    }
    const text = (frozenPayload?.answer ?? submission.value?.answer ?? draft.value).trim()
    if (!text || text.length > 5000) {
      error.value = text ? '回答不能超过 5000 字，请精简后提交。' : ''
      if (frozenPayload) lastVoiceResult = { outcome: 'REJECTED', questionId: frozenPayload.questionId, submissionId: frozenPayload.submissionId, generation: 'NONE' }
      return false
    }
    const questionId = session.value.currentQuestion.questionId
    const payload = frozenPayload
      ? (submission.value = { questionId, submissionId: frozenPayload.submissionId, answer: text })
      : makeSubmission(questionId, text)
    inFlightPayload = payload
    lastVoiceResult = null
    const expectedEpoch = epoch
    const requestController = new AbortController()
    operationController = requestController
    inFlight = performSubmission(payload, text, questionId, expectedEpoch, requestController).finally(() => {
      inFlight = null
      inFlightPayload = null
      if (operationController === requestController) operationController = null
    })
    return inFlight
  }
  async function sendVoice(payload) {
    if (!payload || typeof payload.questionId !== 'string' || typeof payload.submissionId !== 'string'
        || typeof payload.answer !== 'string') {
      return { outcome: 'REJECTED', questionId: payload?.questionId || '', submissionId: payload?.submissionId || '', generation: 'NONE' }
    }
    if (!active.value || session.value?.currentQuestion?.questionId !== payload.questionId) {
      return { outcome: 'STALE', questionId: payload.questionId, submissionId: payload.submissionId, generation: 'NONE' }
    }
    if (!payload.answer.trim() || payload.answer.trim().length > 5000) {
      return { outcome: 'REJECTED', questionId: payload.questionId, submissionId: payload.submissionId, generation: 'NONE' }
    }
    if (inFlight && (inFlightPayload?.questionId !== payload.questionId
        || inFlightPayload?.submissionId !== payload.submissionId || inFlightPayload?.answer !== payload.answer)) {
      return { outcome: 'REJECTED', questionId: payload.questionId, submissionId: payload.submissionId, generation: 'NONE' }
    }
    await send(payload)
    return lastVoiceResult || { outcome: 'UNCERTAIN', questionId: payload.questionId, submissionId: payload.submissionId, generation: 'NONE' }
  }
  async function retryGeneration() {
    const explicitQuickNext = session.value?.status === 'ACTIVE' && session.value?.plan?.mode === 'QUICK'
      && !session.value.currentQuestion && phase.value === 'ANSWERING'
    if (!session.value || (!explicitQuickNext && !['GENERATION_FAILED', 'RETRY_WAIT'].includes(phase.value))) return false
    polling?.stop?.()
    operationController?.abort()
    const controller = new AbortController()
    operationController = controller
    try { return await runGeneration(epoch, controller.signal) }
    finally { if (operationController === controller) operationController = null }
  }
  async function skip(questionId = session.value?.currentQuestion?.questionId) {
    if (!active.value || !questionId || inFlight) return false
    const expectedEpoch = epoch
    const controller = new AbortController()
    operationController = controller
    try {
      const value = await api.skip(session.value.sessionId, { questionId, requestId: ids(), expectedRevision: session.value.revision }, controller.signal)
      if (!applySession(value, expectedEpoch)) return false
      if (value.status === 'FINISHED' || value.plan?.mode === 'QUICK') return true
      return runGeneration(expectedEpoch, controller.signal)
    } catch (cause) {
      if (expectedEpoch === epoch) { phase.value = 'ANSWERING'; error.value = cause?.message || '跳过题目失败，请重试。' }
      return false
    } finally { if (operationController === controller) operationController = null }
  }
  async function practiceFeedback(questionId) {
    if (!active.value || session.value?.plan?.mode !== 'QUICK' || inFlight) return false
    const question = questionId || [...(session.value?.turns || [])].reverse().find(turn => turn.answer)?.questionId
    if (!question) return false
    const expectedEpoch = epoch
    try {
      const value = await api.practiceFeedback(session.value.sessionId, {
        questionId: question, requestId: ids(), expectedRevision: session.value.revision,
      })
      if (!applySession(value, expectedEpoch)) return false
      if (Object.values(value.practiceFeedbacks || {}).some(item => ['QUEUED', 'RUNNING'].includes(item.status)))
        polling?.start?.(value.sessionId)
      return true
    } catch (cause) {
      if (expectedEpoch === epoch) error.value = cause?.message || '查看本题反馈失败，可以继续下一题。'
      return false
    }
  }
  async function clarify(questionId = session.value?.currentQuestion?.questionId) {
    if (!active.value || !questionId) return false
    clarificationController?.abort()
    const controller = new AbortController()
    clarificationController = controller
    const expectedEpoch = epoch
    try {
      const value = await api.clarify(session.value.sessionId, {
        questionId, requestId: ids(), expectedRevision: session.value.revision,
      }, controller.signal)
      return applySession(value, expectedEpoch)
    } catch (cause) {
      if (expectedEpoch === epoch && !controller.signal.aborted) error.value = cause?.message || '题目解释暂不可用，请重试。'
      return false
    } finally { if (clarificationController === controller) clarificationController = null }
  }
  async function replace(questionId = session.value?.currentQuestion?.questionId, reason = '') {
    if (!active.value || !questionId || !reason.trim() || reason.trim().length > 300 || inFlight) return false
    const expectedEpoch = epoch
    const controller = new AbortController()
    replacementController?.abort()
    replacementController = controller
    drafts.save?.(session.value.sessionId, questionId, { text: draft.value })
    try {
      const value = await api.replace(session.value.sessionId, { questionId, requestId: ids(), expectedRevision: session.value.revision, reason: reason.trim() }, controller.signal)
      if (!applySession(value, expectedEpoch)) return false
      polling?.start?.(value.sessionId)
      return true
    } catch (cause) {
      if (expectedEpoch === epoch && !controller.signal.aborted) error.value = cause?.message || '替换题暂不可用，请重试。'
      return false
    } finally { if (replacementController === controller) replacementController = null }
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
    clarificationController?.abort()
    replacementController?.abort()
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
    clarificationController?.abort()
    replacementController?.abort()
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
  function dispose() { epoch += 1; operationController?.abort(); clarificationController?.abort(); replacementController?.abort(); polling?.stop?.(); voice?.dispose?.() }

  return { session, draft, phase, error, submission, active, applySession, send, sendVoice, skip, clarify, replace, practiceFeedback, retryGeneration, restore, beginFinish, cancelFinish, switchScope, dispose }
}
