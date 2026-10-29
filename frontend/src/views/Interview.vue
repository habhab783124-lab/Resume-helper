<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createInterviewApi } from '../api/interview.js'
import { useInterviewVoice } from '../interview/useInterviewVoice.js'
import { useInterviewPolling } from '../interview/useInterviewPolling.js'
import { useInterviewConversation } from '../interview/useInterviewConversation.js'
import { createDraftStore } from '../interview/draftStore.js'
import { getLocalApiBaseUrl, getUserId } from '../api/requestContext.js'
import { algorithmUrl } from '../interview/algorithmSource.js'
import { isSpecialty, specialtyLabel, answerGuidance } from '../interview/specialtyPresentation.js'
import { isNewInterviewRoute, sourceSessionId } from '../interview/interviewNavigation.js'
import InterviewSetup from '../components/interview/InterviewSetup.vue'
import InterviewHeader from '../components/interview/InterviewHeader.vue'
import InterviewInfoDrawer from '../components/interview/InterviewInfoDrawer.vue'
import InterviewMessages from '../components/interview/InterviewMessages.vue'
import InterviewComposer from '../components/interview/InterviewComposer.vue'
import InterviewFinishDialog from '../components/interview/InterviewFinishDialog.vue'
import { toConversationMessages } from '../interview/conversationMessages.js'
import { useInterviewFinish } from '../interview/useInterviewFinish.js'
import { useInterviewSetup } from '../interview/useInterviewSetup.js'
import * as materialsApi from '../api/materials.js'
import * as resumeApi from '../api/resume.js'
import * as jobsApi from '../api/jobs.js'
import InterviewEntryGate from '../components/interview/InterviewEntryGate.vue'
import { useInterviewReadiness } from '../interview/useInterviewReadiness.js'
import { createVoiceConsent } from '../interview/voice/voiceConsent.js'
import { createInterviewVoiceAdapter } from '../interview/voice/interviewVoiceAdapter.ts'
import { useInterviewVoiceConversation } from '../interview/voice/useInterviewVoiceConversation.ts'
import { useVoiceCapture } from '../interview/voice/useVoiceCapture.ts'
import { createVoiceSocket } from '../interview/voice/voiceSocket.ts'
import { createPcmPlayer } from '../interview/voice/pcmPlayer.ts'
import { voiceWorkletUrl } from '../interview/voice/voiceRuntime.ts'

const route = useRoute()
const router = useRouter()
const api = createInterviewApi({ baseUrl: import.meta.env.VITE_API_BASE_URL || (import.meta.env.PROD ? 'http://localhost:8080' : '') })
let activeScope = { apiBaseUrl: getLocalApiBaseUrl(), userId: getUserId() }
const setup = useInterviewSetup({ materialsApi, resumeApi, jobsApi, interviewApi: api,
  scope: () => activeScope })
const serviceReadyToast = ref(false)
let serviceReadyTimer
async function checkInterviewReadiness() {
  const result = await readiness.check()
  if (result.status === 'ready' && !session.value) {
    serviceReadyToast.value = true
    globalThis.clearTimeout(serviceReadyTimer)
    serviceReadyTimer = globalThis.setTimeout(() => { serviceReadyToast.value = false }, 1800)
  }
  return result
}
const voiceConsent = createVoiceConsent()
const voiceConsentAccepted = ref(voiceConsent.accepted())
const voiceConsentPrompt = ref(false)
let storageKey = `career-studio-interview-id-v2|${activeScope.apiBaseUrl}|${activeScope.userId}`
const legacyStorageKey = 'career-studio-interview-id'
const material = ref(null)
const uploadSuccess = ref(false)
let uploadSuccessTimer
function showUploadSuccess() {
  uploadSuccess.value = true
  serviceReadyToast.value = false
  globalThis.clearTimeout(uploadSuccessTimer)
  uploadSuccessTimer = globalThis.setTimeout(() => { uploadSuccess.value = false }, 1800)
}
const infoOpen = ref(false)
const infoButton = ref(null)
function openInfo(event) { infoButton.value = event?.currentTarget || null; infoOpen.value = true }
function closeInfo() { infoOpen.value = false; nextTick(() => infoButton.value?.focus?.()) }
let conversation
const polling = useInterviewPolling({ api, getMaxDelayMs: () => realtimeVoice?.isActive() ? 1000 : 5000, onSession: value => applySession(value), onError: () => {
  if (!error.value) error.value = '会话状态暂时无法读取，正在等待连接恢复。'
} })
conversation = useInterviewConversation({ api, polling, storage: globalThis.sessionStorage,
  scope: { apiBaseUrl: getLocalApiBaseUrl(), userId: getUserId() },
  drafts: {
    save: (...args) => draftStore().save(...args),
    remove: (...args) => draftStore().remove(...args),
  } })
const session = conversation.session
const phase = conversation.phase
const quickPracticeFeedback = computed(() => {
  const value = session.value
  if (value?.plan?.mode !== 'QUICK') return null
  const latest = [...(value.turns || [])].reverse().find(turn => value.questionMetadata?.[turn.questionId]?.kind !== 'FOLLOW_UP')
  if (!latest?.answer) return null
  const root = latest
  const feedback = value.practiceFeedbacks?.[root.questionId] || null
  if (value.closedRootIds?.includes(root.questionId) && !feedback) return null
  return { root, feedback }
})
const paste = ref('')
const targetRole = setup.targetRole
const answer = conversation.draft
const busy = ref('')
const error = conversation.error
const restoreId = ref('')
const uncertainAnswer = ref(null)
const finishChoice = ref(false)
const skipChoice = ref(false)
const replacementChoice = ref(false)
const replacementReason = ref('')
const replacementDraftChoice = ref('')
const resolvedReplacementDrafts = new Set()
const finishRequestId = ref('')
const finishRequestKey = ref('')
const recordingAudio = ref(null)
const completed = computed(() => session.value?.turns.filter(turn => turn.answer != null) || [])
const completedRoots = computed(() => completed.value.filter(turn => session.value?.questionMetadata?.[turn.questionId]?.kind !== 'FOLLOW_UP'))
const conversationMessages = computed(() => session.value
  ? toConversationMessages(session.value, conversation.submission.value, phase.value) : [])
const current = computed(() => session.value?.currentQuestion)
const currentMetadata = computed(() => session.value?.questionMetadata?.[current.value?.questionId] || {})
const currentClarification = computed(() => {
  const rootId = currentMetadata.value.parentQuestionId || current.value?.questionId
  return rootId ? session.value?.clarifications?.[rootId] || null : null
})
function questionLabel(turn, index = 0) {
  const metadata = session.value?.questionMetadata?.[turn?.questionId] || {}
  if (isSpecialty(metadata.kind)) return `${specialtyLabel(metadata.kind)} · 固定权重 20%`
  const ordinal = String(metadata.mainQuestionIndex || index + 1).padStart(2, '0')
  return `主问题 ${ordinal}${metadata.kind === 'FOLLOW_UP' ? ' · 追问（不计题数）' : ' / 12'}`
}
const pending = computed(() => session.value?.pendingAnswer ?? uncertainAnswer.value ?? conversation.submission.value?.answer
  ?? (phase.value === 'GENERATION_FAILED' ? [...(session.value?.turns || [])].reverse().find(turn => turn.answer != null)?.answer : null) ?? null)
const active = computed(() => session.value?.status === 'ACTIVE')
const finishAlternativeLabel = computed(() => session.value?.pendingAnswer != null || phase.value === 'GENERATION_FAILED'
  ? '保留已提交回答并结束' : uncertainAnswer.value != null || conversation.submission.value
    ? '结束并保留已收到的回答' : answer.value.trim() || voice?.preview?.text || voice?.blob
      ? '放弃未提交内容并结束' : '结束面试')
const finishMessage = computed(() => session.value?.pendingAnswer != null
  ? '这份回答已提交并保留在服务端，结束时会计入面试报告。可以重试提交以生成下一题，或保留回答并结束。'
  : uncertainAnswer.value != null
    ? '上次提交结果尚未确认，回答可能已被服务端保留。结束会保留已收到的回答，无法撤回；也可继续作答后重试提交。'
    : phase.value === 'GENERATION_FAILED'
      ? '回答已保存，但后续问题生成失败。可单独重试生成，或保留已保存回答并结束。'
    : answer.value.trim() || voice?.preview?.text || voice?.blob
      ? '还有未提交内容。可以提交当前回答，或放弃本机草稿并结束。'
      : '结束将保留已提交的回答，并停止后续出题。')
function voiceContext() {
  return { sessionId: session.value?.sessionId, questionId: current.value?.questionId, status: session.value?.status, pending: pending.value, blocked: !!busy.value }
}
function pauseRecordingReplay() {
  if (recordingAudio.value) { recordingAudio.value.pause(); recordingAudio.value.currentTime = 0 }
}
const voice = reactive(useInterviewVoice({ api, getContext: voiceContext,
  getDraft: () => answer.value, setDraft: text => { answer.value = text },
  syncSession: syncVoiceSession, pauseReplay: pauseRecordingReplay }))
const readiness = useInterviewReadiness({ api: { ...api, getSpeechCapabilities: options => voice.loadCapabilities(options) } })
const realtimeVoiceState = ref(null)
let realtimeVoice
const realtimePorts = createInterviewVoiceAdapter({
  readSession: () => conversation.session.value,
  readDraft: () => conversation.draft.value,
  readPendingSubmission: () => conversation.submission.value,
  writeDraft: text => { conversation.draft.value = text },
  sendVoice: payload => conversation.sendVoice(payload),
  getSession: (id, options) => api.getSession(id, options),
  transcribe: (id, body, signal) => api.transcribe(id, body, signal),
  createVoiceSession: (id, signal) => api.createVoiceSession(id, signal),
  closeVoiceSession: (id, voiceSessionId) => api.closeVoiceSession(id, voiceSessionId),
  voiceSocketUrl: () => api.voiceSocketUrl(),
})
realtimeVoice = useInterviewVoiceConversation({
  api: realtimePorts.api,
  conversation: realtimePorts.conversation,
  getScope: () => JSON.stringify([activeScope.apiBaseUrl, activeScope.userId]),
  getQuestionKind: () => ['MAIN', 'FOLLOW_UP', 'ALGORITHM', 'CASE_STUDY'].includes(currentMetadata.value.kind) ? currentMetadata.value.kind : 'MAIN',
  getVisible: () => globalThis.document?.visibilityState !== 'hidden',
  audioContextFactory: () => new (globalThis.AudioContext || globalThis.webkitAudioContext)(),
  workletUrl: voiceWorkletUrl,
  captureFactory: options => useVoiceCapture({ ...options, WorkletNode: globalThis.AudioWorkletNode }),
  socketFactory: options => createVoiceSocket(options),
  playerFactory: options => createPcmPlayer(options),
  saveDraft: text => { conversation.draft.value = text; scheduleDraftSave() },
  onChange: state => { realtimeVoiceState.value = state },
  onFailure: failure => { if (failure.code === 'VOICE_START_FAILED') error.value = failure.message },
})
realtimeVoiceState.value = { ...realtimeVoice.state }
const finishButton = ref(null)
const finishFlow = useInterviewFinish({ isActive: () => active.value, voice, open: finishChoice })
const { finishing: finishFinishing } = finishFlow
watch(session, () => { voice.syncContext(); realtimeVoice.syncContext(); applyQuestionDraft() }, { flush: 'sync' })
const locked = computed(() => !!busy.value || voice.busy || ['SUBMITTING', 'WAITING_QUESTION'].includes(realtimeVoiceState.value?.phase))
const realtimeActive = computed(() => !!realtimeVoiceState.value && (realtimeVoiceState.value.captureActive
  || realtimeVoiceState.value.transport !== 'CLOSED' || realtimeVoiceState.value.phase === 'SPEAKING'))
watch(pending, () => voice.syncContext(), { flush: 'sync' })

// 按题落在本机的草稿：300ms 防抖，离开页面或刷新立即落盘。
// 存储不可用只提示，不影响作答。
const DRAFT_DEBOUNCE_MS = 300
const draftStatus = ref('')
const draftConflict = ref(null)
let draftKey = ''
let draftStamp = 0
let draftTimer = null
function draftStore() {
  return createDraftStore({ storage: globalThis.localStorage, scope: activeScope })
}
function draftTarget() {
  const sessionId = session.value?.sessionId
  const questionId = current.value?.questionId
  return sessionId && questionId ? { sessionId, questionId } : null
}
function saveDraft() {
  if (draftTimer) { clearTimeout(draftTimer); draftTimer = null }
  const target = draftTarget()
  if (!target) return { saved: false, reason: 'UNAVAILABLE' }
  const store = draftStore()
  const result = store.save(target.sessionId, target.questionId, { text: answer.value, previewText: voice.preview?.text || '' })
  draftKey = store.keyFor(target.sessionId, target.questionId)
  draftStamp = Date.now()
  draftStatus.value = result.saved ? '' : result.reason === 'QUOTA' ? '本机存储空间已满，草稿暂无法自动保存。' : '本机自动保存不可用。'
  return result
}
async function skipQuestion(keepDraft) {
  const questionId = current.value?.questionId
  if (!questionId || !session.value) return
  saveDraft()
  if (!keepDraft) draftStore().remove(session.value.sessionId, questionId)
  skipChoice.value = false
  await conversation.skip(questionId)
}
function scheduleDraftSave() {
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = setTimeout(() => { draftTimer = null; saveDraft() }, DRAFT_DEBOUNCE_MS)
}
function applyQuestionDraft() {
  const target = draftTarget()
  const key = target ? draftStore().keyFor(target.sessionId, target.questionId) : ''
  // 换题时重置冲突状态，否则上一题的时间戳会误判新一题的草稿。
  if (draftKey !== key) { draftKey = key; draftStamp = 0 }
  if (!target || answer.value || pending.value != null) return
  const draft = draftStore().load(target.sessionId, target.questionId)
  if (!draft) return
  answer.value = draft.text
  draftStamp = draft.updatedAt || 0
  if (draft.previewText) voice.restorePreview(draft.previewText)
  draftStatus.value = ''
}
function chooseDraft(kind) {
  const other = draftConflict.value
  draftConflict.value = null
  if (!other || kind === 'local') { draftStamp = Date.now(); return }
  answer.value = other.text || ''
  voice.restorePreview(other.previewText || '')
  draftStamp = other.updatedAt || 0
  draftStatus.value = ''
}
function onDraftStorage(event) {
  if (event.key === 'resume-helper-local-api-base-url') { onRequestContextChange(); return }
  if (!event.key || event.key !== draftKey) return
  let other
  try { other = JSON.parse(event.newValue) } catch { return }
  if (!other || typeof other.updatedAt !== 'number' || other.updatedAt <= draftStamp) return
  if (other.text === answer.value) return
  draftConflict.value = other
}
function onRequestContextChange() {
  void checkInterviewReadiness()
  const nextScope = { apiBaseUrl: getLocalApiBaseUrl(), userId: getUserId() }
  if (nextScope.apiBaseUrl === activeScope.apiBaseUrl && nextScope.userId === activeScope.userId) return
  saveDraft()
  void realtimeVoice.stop()
  polling.stop()
  voice.cancelRecording()
  const nextRestoreId = conversation.switchScope(nextScope)
  activeScope = nextScope
  storageKey = `career-studio-interview-id-v2|${activeScope.apiBaseUrl}|${activeScope.userId}`
  material.value = null
  setup.switchScope()
  paste.value = ''
  uncertainAnswer.value = null
  finishChoice.value = false
  restoreId.value = nextRestoreId
  error.value = '服务地址或用户已切换。当前轮已暂停，草稿保存在原范围；请恢复此范围的练习或开始新练习。'
}
function remember(id) {
  try { id ? sessionStorage.setItem(storageKey, id) : sessionStorage.removeItem(storageKey) } catch { /* Storage is optional. */ }
}
function applySession(value) {
  if (!value?.sessionId || !Array.isArray(value.turns) || !['ACTIVE', 'FINISHED'].includes(value.status)) throw new Error('面试数据格式异常，请重试恢复。')
  const sameQuestion = session.value?.sessionId === value.sessionId && current.value?.questionId === value.currentQuestion?.questionId && value.status === 'ACTIVE'
  if (!sameQuestion && session.value) saveDraft()
  if (!sameQuestion) finishChoice.value = false
  if (!conversation.applySession(value)) return false
  const replacement = Object.entries(value.replacements || {}).find(([sourceId, operation]) =>
    operation.status === 'COMPLETE' && operation.replacementQuestionId === value.currentQuestion?.questionId)
  if (replacement && !resolvedReplacementDrafts.has(replacement[0])
      && draftStore().load(value.sessionId, replacement[0])?.text) replacementDraftChoice.value = replacement[0]
  if (value.status === 'FINISHED') finishChoice.value = false
  uncertainAnswer.value = null
  voice.syncContext()
  applyQuestionDraft()
  return true
}
async function replaceQuestion() {
  if (!current.value || replacementReason.value.trim().length < 1 || replacementReason.value.trim().length > 300) return
  const sourceId = current.value.questionId
  saveDraft()
  const accepted = await conversation.replace(sourceId, replacementReason.value)
  if (accepted) {
    replacementDraftChoice.value = sourceId
    replacementChoice.value = false
    replacementReason.value = ''
  }
}
function resolveReplacementDraft(keep) {
  const sourceId = replacementDraftChoice.value
  if (sourceId) resolvedReplacementDrafts.add(sourceId)
  if (!keep && replacementDraftChoice.value) draftStore().remove(session.value?.sessionId, replacementDraftChoice.value)
  replacementDraftChoice.value = ''
}
async function syncVoiceSession(context, canApply = () => true, signal) {
  try {
    const value = await api.getSession(context.sessionId, { signal })
    // A recovery read can itself arrive after the page moved on. Never apply that old snapshot.
    if (!signal?.aborted && canApply() && session.value?.sessionId === context.sessionId && current.value?.questionId === context.questionId && active.value && !busy.value && pending.value == null) applySession(value)
  } catch { /* A voice error never clears the interview or its text draft. */ }
}
async function run(label, work, expireSession = false) {
  if (locked.value) return false
  busy.value = label
  error.value = ''
  try { await work(); return true } catch (cause) {
    if (expireSession && [404, 410].includes(cause.status)) {
      remember('')
      restoreId.value = ''
      session.value = null
      material.value = null
      uncertainAnswer.value = null
      voice.syncContext()
      error.value = '本次练习已过期，请重新上传并开始。'
    } else error.value = cause.message || '网络连接失败，请检查连接后重试。'
    return false
  } finally { busy.value = '' }
}
async function upload(file) {
  if (!file || locked.value) return
  if (!/\.(pdf|docx|txt)$/i.test(file.name)) { error.value = '请选择 PDF、DOCX 或 TXT 文件。'; return }
  if (!file.size || file.size > 10 * 1024 * 1024) { error.value = '文件不能为空，且不能超过 10 MiB。'; return }
  await run('正在解析简历…', async () => {
    const value = await api.upload(file)
    if (!value?.materialId || typeof value.text !== 'string') throw new Error('简历解析结果异常，请重试上传。')
    if (!value.text.trim()) throw new Error('简历未提取到文字，请粘贴简历文本后开始。')
    const preview = await setup.loadSource({ kind: 'MATERIAL', id: value.materialId })
    material.value = { ...value, fileName: preview.sourceLabel, text: preview.resumeText }
    showUploadSuccess()
  })
}
function uploadPaste() {
  if (!paste.value.trim()) { error.value = '请先粘贴简历文本。'; return }
  if (locked.value) return
  return run('正在保存并解析粘贴的简历…', async () => {
    const detail = await materialsApi.pasteMaterial({ title: '粘贴的简历.txt', text: paste.value.trim() })
    const id = detail?.material?.id
    if (!id || typeof detail.text !== 'string' || !detail.text.trim()) throw new Error('粘贴内容未提取到可用简历文字。')
    const preview = await setup.loadSource({ kind: 'MATERIAL', id })
    material.value = { materialId: id, fileName: preview.sourceLabel, text: preview.resumeText }
    showUploadSuccess()
  })
}
function start() {
  const selectedSource = setup.source.value || (material.value?.materialId
    ? { kind: 'MATERIAL', id: material.value.materialId } : null)
  const confirmedText = setup.resumePreview.value?.resumeText || material.value?.text
  if (!selectedSource || !confirmedText?.trim()) { error.value = '请先选择并确认一份有效简历。'; return }
  return run('正在准备面试…', async () => {
    const value = await setup.startInterview()
    if (!value) throw new Error(setup.planError.value || '暂时无法开始面试，请重试。')
    applySession(value)
    if (value.generation) polling.start(value.sessionId)
    if (route.query.intent === 'new') router.replace({ name: 'Interview' })
  })
}
function submitAnswer() {
  if (locked.value || !active.value) return false
  const text = pending.value ?? answer.value.trim()
  if (!text || !current.value) return false
  if (text.length > 5000) { error.value = '回答不能超过 5000 字，请精简后提交。'; return false }
  const questionId = current.value.questionId
  saveDraft()
  return run('正在保存回答并生成后续问题…', async () => {
    const confirmed = await conversation.send()
    if (session.value?.status === 'FINISHED') finishChoice.value = false
    if (!confirmed) {
      const turn = session.value?.turns.find(item => item.questionId === questionId)
      if (conversation.submission.value || turn?.answer === text || session.value?.pendingAnswer === text) uncertainAnswer.value = text
      throw new Error(error.value || '回答暂未确认，请读取会话状态后重试。')
    }
  })
}
function retryGeneration() { return run('正在继续生成后续问题…', () => conversation.retryGeneration()) }
function viewPracticeFeedback() {
  if (!quickPracticeFeedback.value) return
  return run('正在整理本题反馈…', () => conversation.practiceFeedback(quickPracticeFeedback.value.root.questionId))
}
function viewReport() {
  if (locked.value || session.value?.status !== 'FINISHED') return
  voice.dispose()
  router.push({ name: 'Report', query: { sessionId: session.value.sessionId } })
}
async function finish() {
  if (realtimeVoice.isActive()) await realtimeVoice.pause('finish')
  return finishFlow.requestFinish()
}
function requestFinish(event) { finishButton.value = event?.currentTarget || null; return finish() }
function continueFinish() {
  finishFlow.continueInterview()
  nextTick(() => finishButton.value?.focus?.())
}
async function endSession(submission = null) {
  if (!active.value || voice.busy) return false
  const key = JSON.stringify([session.value.sessionId, submission])
  if (key !== finishRequestKey.value) {
    finishRequestKey.value = key
    finishRequestId.value = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`
  }
  conversation.beginFinish()
  busy.value = '正在结束面试…'
  error.value = ''
  let finished = false
  try {
    const body = { requestId: finishRequestId.value, ...(submission ? { submission } : {}) }
    const value = await api.finish(session.value.sessionId, body)
    if (value?.status !== 'FINISHED') throw new Error('面试尚未结束，请重试。')
    applySession(value)
    finished = true
  } catch (cause) {
    if (active.value) conversation.cancelFinish()
    error.value = cause?.message || '结束结果尚未确认，请读取会话状态后重试。'
    try {
      const snapshot = await api.getSession(session.value.sessionId)
      if (snapshot?.status === 'FINISHED') { applySession(snapshot); finished = true }
      else if (snapshot?.status === 'ACTIVE') applySession(snapshot)
    } catch { /* Keep the draft and show the original outcome message. */ }
  } finally { busy.value = '' }
  if (finished) { finishChoice.value = false; viewReport(); return true }
  return false
}
async function chooseFinish(choice) {
  if (choice === 'continue') { continueFinish(); return true }
  return finishFlow.confirmFinish(choice, async choice => {
  if (voice.busy) return false
  if (choice === 'submit') {
    if (pending.value != null) return endSession()
    if (voice.preview?.text) { error.value = '请先确认转写预览，再提交后结束；或选择放弃草稿并结束。'; return false }
    const questionId = current.value?.questionId
    const text = String(pending.value ?? answer.value).trim()
    if (!questionId || !text || text.length > 5000) return false
    const existing = conversation.submission.value
    const submissionId = existing?.questionId === questionId && existing.answer === text
      ? existing.submissionId : globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`
    return endSession({ questionId, submissionId, answer: text })
  }
  if (choice === 'preserve' && pending.value != null) return endSession()
  if (choice === 'discard') {
    if (pending.value != null) { error.value = '已提交的回答不能作为本地草稿丢弃，请选择保留回答并结束。'; return false }
    if (session.value && current.value?.questionId) draftStore().remove(session.value.sessionId, current.value.questionId)
    answer.value = ''
    uncertainAnswer.value = null
    voice.cancelRecording()
    if (voice.preview) voice.acceptPreview('keep')
    return endSession()
  }
  return false
  })
}
function restore() { return run('正在恢复上次练习…', async () => {
  const restored = await conversation.restore(restoreId.value)
  if (!restored) throw new Error(error.value || '恢复失败，请重试。')
  applySession(session.value)
  if (session.value.generation) polling.start(session.value.sessionId)
  try { sessionStorage.removeItem(legacyStorageKey) } catch { /* Storage is optional. */ }
  restoreId.value = ''
}, true) }
async function prepareNewRound() {
  // 新一轮只复用上一轮的简历和岗位，不继承它的题目、进度或已结束状态。
  const source = sourceSessionId(route)
  remember('')
  restoreId.value = ''
  error.value = ''
  if (!source) return
  busy.value = '正在读取上一轮的简历与岗位…'
  try {
    const previous = await api.getSession(source)
    targetRole.value = typeof previous.targetRole === 'string' ? previous.targetRole.slice(0, 200) : ''
    const priorSource = previous.plan?.source || (previous.materialId
      ? { kind: 'MATERIAL', id: previous.materialId } : null)
    if (priorSource?.kind && priorSource?.id) {
      const preview = await setup.loadSource(priorSource)
      material.value = { materialId: priorSource.kind === 'MATERIAL' ? priorSource.id : null,
        fileName: preview.sourceLabel, text: preview.resumeText }
    }
  } catch {
    error.value = '上一轮的简历和岗位暂时无法读取，请重新上传简历或填写岗位。'
  } finally {
    busy.value = ''
  }
}
function reset() {
  void realtimeVoice.stop()
  voice.cancelRecording()
  remember('')
  session.value = null
  material.value = null
  answer.value = ''
  paste.value = ''
  error.value = ''
  restoreId.value = ''
  uncertainAnswer.value = null
  finishChoice.value = false
  voice.syncContext()
}
watch([answer, () => voice.preview?.text], scheduleDraftSave)
watch(() => route.fullPath, () => { if (!session.value) void checkInterviewReadiness() })
onMounted(() => {
  void checkInterviewReadiness()
  // 离开页面（刷新、关标签）和切到别的后端都会触发，兜住防抖窗口里的最后几笔。
  globalThis.window?.addEventListener('pagehide', saveDraft)
  globalThis.window?.addEventListener('storage', onDraftStorage)
  globalThis.addEventListener?.('request-context-change', onRequestContextChange)
  globalThis.addEventListener?.('speech-settings-change', onSpeechSettingsChange)
  globalThis.document?.addEventListener('visibilitychange', onRealtimeVisibilityChange)
  try { restoreId.value = sessionStorage.getItem(storageKey) || sessionStorage.getItem(legacyStorageKey) || '' } catch { /* Storage is optional. */ }
  const requestedSessionId = typeof route.query.sessionId === 'string' ? route.query.sessionId.trim() : ''
  if (requestedSessionId) { restoreId.value = requestedSessionId; remember(requestedSessionId) }
  setup.restorePreparation()
  const queryId = value => typeof value === 'string' && value.trim() ? value.trim() : ''
  const focusedPlanId = queryId(route.query.planId)
  const querySource = queryId(route.query.materialId) ? { kind: 'MATERIAL', id: queryId(route.query.materialId) }
    : queryId(route.query.resumeVersionId) ? { kind: 'RESUME_VERSION', id: queryId(route.query.resumeVersionId) } : null
  if (isNewInterviewRoute(route)) {
    if (!route.query.sourceSessionId) remember('')
    restoreId.value = ''
    if (focusedPlanId) {
      setup.loadPreparedPlan(focusedPlanId, queryId(route.query.sourceSessionId)).then(loaded => {
        if (!loaded) { error.value = setup.planError.value || '练习计划已失效，请回到报告重新选择重点。'; return }
        const preview = setup.resumePreview.value
        material.value = { materialId: setup.source.value?.kind === 'MATERIAL' ? setup.source.value.id : null,
          fileName: preview?.sourceLabel || '已选简历', text: preview?.resumeText || '' }
      })
      return
    }
    const sourceToLoad = querySource || (!route.query.sourceSessionId ? setup.source.value : null)
    if (sourceToLoad) setup.loadSource(sourceToLoad).then(preview => {
      material.value = { materialId: sourceToLoad.kind === 'MATERIAL' ? sourceToLoad.id : null,
        fileName: preview.sourceLabel, text: preview.resumeText }
    }).catch(cause => { error.value = cause.message || '无法读取所选简历，请重新选择。' })
    if (queryId(route.query.jobId)) setup.loadJob(queryId(route.query.jobId)).catch(() => {
      error.value = '岗位已无法读取。你仍可以手动填写目标岗位和 JD。'
    })
    if (!querySource && !queryId(route.query.jobId) && route.query.sourceSessionId) return prepareNewRound()
    return
  }
  if (restoreId.value) restore()
  else if (setup.source.value) setup.loadSource(setup.source.value).then(preview => {
    material.value = { materialId: setup.source.value.kind === 'MATERIAL' ? setup.source.value.id : null,
      fileName: preview.sourceLabel, text: preview.resumeText }
  }).catch(cause => { error.value = cause.message || '无法读取已保存的练习准备。' })
})
onUnmounted(() => {
  readiness.dispose()
  globalThis.clearTimeout(serviceReadyTimer)
  globalThis.clearTimeout(uploadSuccessTimer)
  saveDraft()
  realtimeVoice.dispose()
  conversation.dispose()
  globalThis.window?.removeEventListener('pagehide', saveDraft)
  globalThis.window?.removeEventListener('storage', onDraftStorage)
  globalThis.removeEventListener?.('request-context-change', onRequestContextChange)
  globalThis.removeEventListener?.('speech-settings-change', onSpeechSettingsChange)
  globalThis.document?.removeEventListener('visibilitychange', onRealtimeVisibilityChange)
  voice.dispose()
})
function onRealtimeVisibilityChange() {
  if (globalThis.document?.visibilityState === 'hidden' && realtimeVoiceState.value?.phase !== 'OFF') void realtimeVoice.pause('hidden')
}
function onSpeechSettingsChange() {
  void checkInterviewReadiness()
  if (realtimeVoice.isActive()) void realtimeVoice.pause('speech settings changed')
}
function updateAnswer(value) { answer.value = value; realtimeVoice.onDraftEdited() }
function startRealtimeVoice() {
  if (!voiceConsentAccepted.value) { voiceConsentPrompt.value = true; return }
  if (voice.busy) { error.value = '请先结束或取消当前手动录音，再开启连续语音。'; return }
  voice.stopQuestion()
  void realtimeVoice.start()
}
function acceptRealtimeVoiceConsent() {
  voiceConsent.accept()
  voiceConsentAccepted.value = true
  voiceConsentPrompt.value = false
  startRealtimeVoice()
}
</script>

<template>
  <section class="page active text-interview" id="page-interview" data-title="模拟面试">
    <div v-if="!session" class="page-intro interview-intro"><div><h1>模拟面试</h1></div></div>
    <div class="interview-layout">
      <main class="interview-main" :aria-busy="locked">
        <div v-if="error && phase !== 'GENERATION_FAILED'" class="notice error" role="alert">{{ error }}</div>
        <div v-if="busy" class="notice" role="status">{{ busy }}</div>
        <div v-if="!session && serviceReadyToast" class="parse-success-toast" role="status">服务可用</div>
        <div v-if="uploadSuccess" class="parse-success-toast" role="status">✓ 简历解析成功</div>
        <div v-if="session?.status === 'ACTIVE' && !current && ['GENERATING', 'RETRY_WAIT'].includes(phase)" class="notice" role="status">
          {{ phase === 'RETRY_WAIT' ? '暂时无法连接模型，正在自动重试…' : '正在准备下一题…' }}
        </div>
        <div v-if="phase === 'GENERATION_FAILED' && active" class="notice error" role="alert">
          {{ error || '题目生成失败，可以单独重试生成。' }}
          <button class="button button-ghost" :disabled="locked" @click="retryGeneration">重试生成</button>
        </div>
        <div v-if="draftStatus" class="notice" role="status">{{ draftStatus }}</div>
        <div v-if="draftConflict" class="notice" role="status">
          <p>另一个标签页保存了这道题更新的草稿，请选择要保留的版本。</p>
          <div class="voice-actions">
            <button data-testid="draft-load-other" class="button button-primary" :disabled="locked" @click="chooseDraft('other')">载入另一版本</button>
            <button data-testid="draft-keep-local" class="button button-ghost" :disabled="locked" @click="chooseDraft('local')">保留当前版本</button>
          </div>
        </div>
        <InterviewEntryGate v-if="!session && readiness.status.value !== 'ready'" :status="readiness.status.value" :error="readiness.error.value" @retry="checkInterviewReadiness" />
        <div v-else-if="restoreId && !session" class="restore-actions"><p>发现上次练习</p><button class="button button-primary" :disabled="locked" @click="restore">恢复</button><button class="text-button" :disabled="locked" @click="reset">新练习</button></div>
        <InterviewSetup v-else-if="!session" :material="material" v-model:paste="paste" v-model:target-role="targetRole"
          v-model:job-description="setup.jobDescription.value" v-model:preferences="setup.preferences.value" :plan-error="setup.planError.value" :starting="setup.starting.value || setup.preparingPlan.value || setup.confirmingPlan.value" :allow-algorithm-repeat="setup.allowAlgorithmRepeat.value" :repeat-consent-required="setup.repeatConsentRequired.value" @update:allow-algorithm-repeat="setup.allowAlgorithmRepeat.value = $event" @dismiss-consent="setup.repeatConsentRequired.value = false" :locked="locked" @upload="upload" @use-paste="uploadPaste" @start="start" />
        <template v-if="session">
          <InterviewHeader :role="session.targetRole" :active="active" :progress="session.progress?.answeredRoots ?? completedRoots.length" :total="session.questionCount" :can-finish="!!session && active && !busy && !finishFinishing" @finish="requestFinish" @open-info="openInfo" />
          <InterviewMessages v-if="conversationMessages.length" :messages="conversationMessages" :voice="voice" :session-id="session.sessionId" />
          <template v-if="active && current">
            <div class="question-card">
              <p v-if="currentMetadata.kind === 'ALGORITHM'" class="algorithm-source">LeetCode Hot 100 · {{ currentMetadata.algorithm?.title }} · 算法难度 {{ currentMetadata.algorithm?.difficulty }} <a v-if="algorithmUrl(currentMetadata.algorithm)" :href="algorithmUrl(currentMetadata.algorithm)" target="_blank" rel="noopener noreferrer">查看原题 ↗</a></p>
              <div class="voice-actions">
                <button data-testid="question-play" class="button button-ghost" :disabled="locked || !voice.ttsEnabled" @click="voice.speechQuestionId === current.questionId && ['loading', 'playing'].includes(voice.speechState) ? voice.stopQuestion() : voice.playQuestion({ sessionId: session.sessionId, questionId: current.questionId })">{{ voice.speechQuestionId === current.questionId && voice.speechState === 'loading' ? '正在生成 · 停止' : voice.speechQuestionId === current.questionId && voice.speechState === 'playing' ? '停止朗读' : '播放题目' }}</button>
                <button data-testid="skip-question" class="button button-ghost" :disabled="locked" @click="skipChoice = true">跳过这题</button>
                <button data-testid="clarify-question" class="button button-ghost" :disabled="locked || ['RUNNING', 'COMPLETE'].includes(currentClarification?.status)" @click="conversation.clarify(current.questionId)">{{ currentClarification?.status === 'FAILED' || currentClarification?.status === 'CANCELLED' ? '重试解释' : currentClarification?.status === 'RUNNING' ? '正在解释…' : currentClarification?.status === 'COMPLETE' ? '已解释题意' : '解释题意' }}</button>
                <button v-if="currentMetadata.kind === 'MAIN' && !session.replacements?.[current.questionId]?.status?.match?.(/COMPLETE|RUNNING/)" data-testid="replace-question" class="button button-ghost" :disabled="locked" @click="replacementChoice = true">这段经历不适用</button>
              </div>
              <div v-if="replacementChoice" class="notice" role="group" aria-label="说明经历不适用">
                <p>请说明原因（1–300 字）。系统会据此生成同能力目标的新题，不会假设你参与过这段经历。当前未提交草稿会按原题编号保留，可在替换完成后选择保留或删除。</p>
                <textarea v-model="replacementReason" maxlength="300" rows="3" aria-label="不适用原因" placeholder="例如：这项工作由其他团队负责，我没有参与"></textarea>
                <div class="voice-actions"><button data-testid="confirm-replacement" class="button button-primary" :disabled="locked || !replacementReason.trim()" @click="replaceQuestion">提交并生成替换题</button><button class="text-button" :disabled="locked" @click="replacementChoice = false">返回作答</button></div>
              </div>
              <div v-if="session.replacements?.[current.questionId]?.status === 'RUNNING'" class="notice" role="status">正在生成替换题；原题不会计入回答或评分。</div>
              <div v-if="replacementDraftChoice" class="notice" role="group" aria-label="替换题草稿处理"><p>原题草稿仍按原题编号保存。</p><button class="button button-primary" @click="resolveReplacementDraft(true)">保留原题草稿</button><button class="button button-ghost" @click="resolveReplacementDraft(false)">删除原题草稿</button></div>
              <div v-if="currentClarification?.status === 'RUNNING'" class="notice" role="status">正在解释术语和任务要求，不会更改题目或草稿。</div>
              <div v-else-if="currentClarification?.status === 'COMPLETE'" class="notice" role="note"><strong>题意说明</strong><p>{{ currentClarification.text }}</p></div>
              <div v-else-if="currentClarification?.status === 'FAILED'" class="notice error" role="alert">{{ currentClarification.error || '题目解释暂不可用，可局部重试。' }}</div>
              <div v-if="skipChoice" class="notice" role="group" aria-label="跳过题目并处理草稿">
                <p>跳过后这题不计为已回答。{{ answer.trim() ? '可以按题目编号保留草稿，或删除草稿。' : '' }}</p>
                <div class="voice-actions">
                  <button data-testid="skip-keep-draft" class="button button-primary" :disabled="locked" @click="skipQuestion(true)">{{ answer.trim() ? '保留草稿并跳过' : '确认跳过' }}</button>
                  <button v-if="answer.trim()" data-testid="skip-discard-draft" class="button button-ghost" :disabled="locked" @click="skipQuestion(false)">删除草稿并跳过</button>
                  <button class="text-button" :disabled="locked" @click="skipChoice = false">返回作答</button>
                </div>
              </div>
              <p v-if="voice.speechError" class="notice error" role="alert">{{ voice.speechError }}</p>
            </div>
            <InterviewComposer :model-value="answer" :locked="locked" :continuous-active="realtimeActive" :pending="pending" :kind="currentMetadata.kind" :voice="voice" :realtime-state="realtimeVoiceState" :speech-available="readiness.speechAvailable.value" @update:model-value="updateAnswer" @send="submitAnswer" @audio="recordingAudio = $event" @voice-start="startRealtimeVoice" @voice-pause="realtimeVoice.pause('user')" @voice-stop="realtimeVoice.stop" @voice-submit-now="realtimeVoice.submitNow" @voice-retry="realtimeVoice.retrySegment" @voice-discard="realtimeVoice.discardSegment" />
          </template>
          <div v-if="active && !current && session.plan?.mode === 'QUICK' && phase === 'ANSWERING'" class="session-actions">
            <section v-if="quickPracticeFeedback" class="quick-practice-feedback" data-testid="quick-practice-feedback">
              <p class="notice" role="status">回答已保存。可以先查看这组题的教学反馈，再继续练习。</p>
              <div v-if="quickPracticeFeedback.feedback?.status === 'COMPLETE'" class="practice-feedback-result">
                <h3>本题组反馈</h3>
                <p><strong>做得不错：</strong>{{ quickPracticeFeedback.feedback.assessment.highlight }}</p>
                <p v-for="item in quickPracticeFeedback.feedback.assessment.evidence" :key="item"><strong>回答证据：</strong>{{ item }}</p>
                <p v-for="item in quickPracticeFeedback.feedback.assessment.missingPoints" :key="item"><strong>可补充：</strong>{{ item }}</p>
                <p><strong>下一步：</strong>{{ quickPracticeFeedback.feedback.assessment.improvement }}</p>
                <p><strong>表达框架：</strong>{{ quickPracticeFeedback.feedback.assessment.suggestedOutline }}</p>
              </div>
              <p v-else-if="['QUEUED', 'RUNNING'].includes(quickPracticeFeedback.feedback?.status)" class="notice" role="status">正在整理本题反馈；完成后会显示原回答中的依据。</p>
              <p v-else-if="quickPracticeFeedback.feedback?.status === 'FAILED'" class="notice error" role="status">{{ quickPracticeFeedback.feedback.error || '本题反馈暂不可用，可以继续下一题。' }}</p>
              <div class="voice-actions">
                <button v-if="!quickPracticeFeedback.feedback" data-testid="quick-view-feedback" class="button button-outline" :disabled="locked" @click="viewPracticeFeedback">查看本题反馈</button>
                <button data-testid="quick-next-question" class="button button-primary" :disabled="locked" @click="retryGeneration">继续生成下一题</button>
              </div>
            </section>
            <div v-else class="quick-practice-next">
              <p class="notice" role="status">这组题已保存或跳过。你可以结束本轮，或继续生成下一题。</p>
              <button data-testid="quick-next-question" class="button button-primary" :disabled="locked" @click="retryGeneration">继续生成下一题</button>
            </div>
          </div>
          <div v-if="!active" class="session-actions">
            <template v-if="!active"><button data-testid="view-report" class="button button-primary" :disabled="locked" @click="viewReport">查看报告</button><button class="button button-ghost" :disabled="locked" @click="reset">开始新的练习</button></template>
          </div>
        </template>
      </main>
    </div>
    <div v-if="voiceConsentPrompt" class="voice-consent-backdrop" @click.self="voiceConsentPrompt = false">
      <section class="voice-consent-dialog" role="dialog" aria-modal="true" aria-labelledby="voice-consent-title">
        <h2 id="voice-consent-title">开启实时语音？</h2>
        <p>你的语音会发送至语音服务转写；停顿后会自动提交回答。继续说话可取消倒计时。</p>
        <div><button class="button button-primary" type="button" @click="acceptRealtimeVoiceConsent">开启语音</button><button class="text-button" type="button" @click="voiceConsentPrompt = false">暂不</button></div>
      </section>
    </div>
    <InterviewInfoDrawer :open="infoOpen" :session="session" :material="material" :voice-max-seconds="voice.maxSeconds" :voice-max-bytes="voice.maxBytes" @close="closeInfo" />
    <InterviewFinishDialog :open="finishChoice && active" :message="finishMessage" :alternative-label="finishAlternativeLabel" :pending="pending" :answer="answer" :voice="voice" :locked="voice.busy" :finishing="finishFinishing" @continue="continueFinish" @confirm="chooseFinish" />
    <footer v-if="!session" class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>模拟面试</span></footer>
  </section>
</template>

<style scoped>
.interview-plan { align-self: center; font-size: 13px; line-height: 1.8; padding: 12px 0; }
.interview-plan p { margin: 6px 0 0; color: #79766e; }
.algorithm-source { font-size: 13px; line-height: 1.8; }
.algorithm-source a { color: #566e40; margin-left: 8px; }
.algorithm-answer { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; tab-size: 4; }
.specialty-plan p { margin: 6px 0 0; font-size: 13px; line-height: 1.8; }
.text-interview .interview-layout { width: 100%; margin: 0 auto; }
.text-interview { color: #272d29; }
.text-interview .interview-main { display: flex; flex-direction: column; min-width: 0; min-height: max(78vh, calc(100dvh - 170px)); box-sizing: border-box; padding: 0 22px 18px; border: 1px solid #e8ede9; border-radius: 20px; background: #fff; box-shadow: 0 12px 36px rgba(34,55,42,.05); }
.interview-intro { justify-content: center; margin: 0 auto 14px; }.interview-intro h1 { margin: 0; font-size: 22px; letter-spacing: -.02em; }
.parse-success-toast { position: fixed; top: 24px; left: 50%; z-index: 40; transform: translateX(-50%); padding: 9px 16px; border: 1px solid #d9e8dc; border-radius: 24px; background: #f0f7f1; color: #315e49; box-shadow: 0 8px 26px rgba(31,51,40,.11); font-size: 13px; }
.voice-consent-backdrop { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 18px; background: rgba(20,31,25,.38); }
.voice-consent-dialog { width: min(100%, 420px); padding: 24px; border: 1px solid #e4eae5; border-radius: 18px; background: white; box-shadow: 0 18px 60px rgba(0,0,0,.18); }
.voice-consent-dialog h2 { margin: 0 0 10px; font-size: 17px; }.voice-consent-dialog p { color: #64736a; line-height: 1.7; font-size: 13px; }.voice-consent-dialog > div { display: flex; gap: 12px; align-items: center; }
.text-interview .question-card { margin: 8px auto; padding: 8px 0; border: 0; background: transparent; }
.text-interview .question-card > .voice-actions { justify-content: flex-end; }
.text-interview .question-card > .voice-actions button { padding: 6px 9px; border: 0; border-radius: 9px; background: #f5f7f5; color: #627167; font-size: 12px; }
.field { display: flex; flex-direction: column; gap: 9px; font-size: 13px; margin: 18px 0; }
.field span, .resume-preview p, .consent { color: #79766e; font-size: 12px; line-height: 1.8; }
input, textarea { width: 100%; box-sizing: border-box; font: inherit; color: #353831; background: #fafaf7; border: 1px solid #e2e4dc; border-radius: 7px; padding: 12px; }
input:focus, textarea:focus { outline: 2px solid #91a77c; outline-offset: 2px; }
input[type=file] { padding: 18px; border-style: dashed; }
button:disabled { opacity: .5; cursor: not-allowed; }
.setup-grid { display: grid; grid-template-columns: 2fr 1fr; gap: 18px; }
.paste-panel { border-top: 1px solid #e7e8e1; padding: 18px 0; }
summary { cursor: pointer; font-size: 13px; }
.paste-panel button { margin-top: 12px; }
.resume-preview { margin: 20px 0; padding: 18px; background: #f4f5ed; border-radius: 8px; }
.resume-preview h3 { font-size: 14px; }
.resume-preview pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 260px; overflow-y: auto; font: inherit; font-size: 12px; line-height: 1.8; }
.notice { margin: 0 0 18px; padding: 12px 16px; background: #eef1e7; border-radius: 6px; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.error { background: #fff1e9; color: #95452c; }
.consent { margin: 8px 0 18px; }
.history { list-style: none; margin: 0; padding: 0; }
.history li { border-bottom: 1px solid #e7e8e1; margin-bottom: 24px; padding-bottom: 24px; }
.history h3 { font-size: 16px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.past-answer { padding: 16px; background: #f7f7f2; border-radius: 8px; }
.past-answer span { font-size: 11px; color: #818578; }
.past-answer p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; font-size: 13px; margin-bottom: 0; }
.question-card h3 { white-space: pre-wrap; overflow-wrap: anywhere; }
.session-actions, .restore-actions { margin: 24px 0; display: flex; flex-wrap: wrap; gap: 18px; }
.quick-practice-feedback, .quick-practice-next { width: 100%; padding: 18px; border: 1px solid #e2e4dc; border-radius: 8px; background: #fafaf7; }
.quick-practice-feedback > .notice { margin-bottom: 12px; }
.practice-feedback-result { margin: 16px 0; padding: 14px; border-left: 3px solid #9aab76; background: #f1f3eb; }
.practice-feedback-result h3 { margin: 0 0 10px; font-size: 15px; }
.practice-feedback-result p { margin: 8px 0; line-height: 1.75; font-size: 13px; overflow-wrap: anywhere; }
.restore-actions p { width: 100%; }
.voice-panel { margin: 18px 0; padding: 18px; border: 1px solid #e2e4dc; border-radius: 8px; background: #fafaf7; }
.voice-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 12px 0; }
.config-link { text-decoration: none; margin-left: 12px; }
.voice-note { color: #79766e; font-size: 12px; line-height: 1.8; }
.voice-panel audio { display: block; width: 100%; max-width: 460px; margin: 16px 0; }
.replay-locked { pointer-events: none; opacity: .5; }
.transcription-preview { border-top: 1px solid #e2e4dc; margin-top: 16px; padding-top: 12px; }
.transcription-preview h4 { font-size: 13px; }
.transcription-preview pre { max-height: 280px; overflow-y: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.8; font-size: 13px; }
.finish-choice p { margin: 0 0 12px; }
@media (max-width: 640px) { .setup-grid { grid-template-columns: 1fr; gap: 0; } .answer-tools { flex-wrap: wrap; gap: 14px; } .text-interview .interview-layout { width: 100%; padding: 0; box-sizing: border-box; } .text-interview .interview-main { min-height: max(78vh, calc(100dvh - 138px)); padding: 0 16px 14px; border-radius: 14px; } }
</style>
