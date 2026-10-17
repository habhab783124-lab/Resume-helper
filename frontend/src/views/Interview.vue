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

const route = useRoute()
const router = useRouter()
const api = createInterviewApi({ baseUrl: import.meta.env.VITE_API_BASE_URL || (import.meta.env.PROD ? 'http://localhost:8080' : '') })
let activeScope = { apiBaseUrl: getLocalApiBaseUrl(), userId: getUserId() }
let storageKey = `career-studio-interview-id-v2|${activeScope.apiBaseUrl}|${activeScope.userId}`
const legacyStorageKey = 'career-studio-interview-id'
const material = ref(null)
const infoOpen = ref(false)
const infoButton = ref(null)
function openInfo(event) { infoButton.value = event?.currentTarget || null; infoOpen.value = true }
function closeInfo() { infoOpen.value = false; nextTick(() => infoButton.value?.focus?.()) }
let conversation
const polling = useInterviewPolling({ api, onSession: value => applySession(value), onError: () => {
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
const paste = ref('')
const targetRole = ref('')
const answer = conversation.draft
const busy = ref('')
const error = conversation.error
const restoreId = ref('')
const uncertainAnswer = ref(null)
const finishChoice = ref(false)
const finishRequestId = ref('')
const finishRequestKey = ref('')
const recordingAudio = ref(null)
const completed = computed(() => session.value?.turns.filter(turn => turn.answer != null) || [])
const completedRoots = computed(() => completed.value.filter(turn => session.value?.questionMetadata?.[turn.questionId]?.kind !== 'FOLLOW_UP'))
const conversationMessages = computed(() => session.value
  ? toConversationMessages({ ...session.value, currentQuestion: null }, conversation.submission.value, phase.value) : [])
const current = computed(() => session.value?.currentQuestion)
const currentMetadata = computed(() => session.value?.questionMetadata?.[current.value?.questionId] || {})
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
const finishButton = ref(null)
const finishFlow = useInterviewFinish({ isActive: () => active.value, voice, open: finishChoice })
const { finishing: finishFinishing } = finishFlow
watch(session, () => { voice.syncContext(); applyQuestionDraft() }, { flush: 'sync' })
const locked = computed(() => !!busy.value || voice.busy)
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
  const nextScope = { apiBaseUrl: getLocalApiBaseUrl(), userId: getUserId() }
  if (nextScope.apiBaseUrl === activeScope.apiBaseUrl && nextScope.userId === activeScope.userId) return
  saveDraft()
  polling.stop()
  voice.cancelRecording()
  const nextRestoreId = conversation.switchScope(nextScope)
  activeScope = nextScope
  storageKey = `career-studio-interview-id-v2|${activeScope.apiBaseUrl}|${activeScope.userId}`
  material.value = null
  targetRole.value = ''
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
  if (value.status === 'FINISHED') finishChoice.value = false
  uncertainAnswer.value = null
  voice.syncContext()
  applyQuestionDraft()
  return true
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
    material.value = null
    const value = await api.upload(file)
    if (!value?.materialId || typeof value.text !== 'string') throw new Error('简历解析结果异常，请重试上传。')
    if (!value.text.trim()) throw new Error('简历未提取到文字，请粘贴简历文本后开始。')
    material.value = value
  })
}
function uploadPaste() {
  if (!paste.value.trim()) { error.value = '请先粘贴简历文本。'; return }
  return upload(new File([paste.value], '粘贴的简历.txt', { type: 'text/plain' }))
}
function start() {
  if (!material.value?.text?.trim()) { error.value = '请先上传有效简历或粘贴简历文本。'; return }
  return run('正在生成第一题…', async () => {
    const value = await api.start({ materialId: material.value.materialId, targetRole: targetRole.value.trim(), questionCount: 13,
      requestId: globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`, async: true })
    applySession(value)
    if (value.generation) polling.start(value.sessionId)
    // 新场次已经建立，清掉 URL 上的「新一轮」意图，否则刷新会丢掉刚开始的练习。
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
function viewReport() {
  if (locked.value || session.value?.status !== 'FINISHED') return
  voice.dispose()
  router.push({ name: 'Report', query: { sessionId: session.value.sessionId } })
}
function finish() {
  return finishFlow.requestFinish()
}
function requestFinish(event) { finishButton.value = event?.currentTarget || null; return finish() }
function continueFinish() { finishFlow.continueInterview(); nextTick(() => finishButton.value?.focus?.()) }
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
    if (previous.materialId) material.value = await api.getMaterial(previous.materialId)
  } catch {
    error.value = '上一轮的简历和岗位暂时无法读取，请重新上传简历或填写岗位。'
  } finally {
    busy.value = ''
  }
}
function reset() {
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
onMounted(() => {
  voice.loadCapabilities()
  // 离开页面（刷新、关标签）和切到别的后端都会触发，兜住防抖窗口里的最后几笔。
  globalThis.window?.addEventListener('pagehide', saveDraft)
  globalThis.window?.addEventListener('storage', onDraftStorage)
  globalThis.addEventListener?.('request-context-change', onRequestContextChange)
  try { restoreId.value = sessionStorage.getItem(storageKey) || sessionStorage.getItem(legacyStorageKey) || '' } catch { /* Storage is optional. */ }
  if (isNewInterviewRoute(route)) return prepareNewRound()
  if (restoreId.value) restore()
})
onUnmounted(() => {
  saveDraft()
  conversation.dispose()
  globalThis.window?.removeEventListener('pagehide', saveDraft)
  globalThis.window?.removeEventListener('storage', onDraftStorage)
  globalThis.removeEventListener?.('request-context-change', onRequestContextChange)
  voice.dispose()
})
</script>

<template>
  <section class="page active text-interview" id="page-interview" data-title="模拟面试">
    <div class="page-intro interview-intro"><div><div class="eyebrow"><span class="eyebrow-line"></span>INTERVIEW ROOM / 05</div><h1>练习表达，建立从容<span class="greeting-dot">。</span></h1><p>从你的简历出发，展开一场真实的专业对话。</p></div><span class="session-label"><i></i>文字作答 · 可选语音</span></div>
    <div class="interview-layout">
      <main class="interview-main" :aria-busy="locked">
        <div v-if="error" class="notice error" role="alert">{{ error }}</div>
        <div v-if="busy" class="notice" role="status">{{ busy }}</div>
        <div v-if="session?.status === 'ACTIVE' && !current" class="notice" role="status">
          {{ phase === 'RETRY_WAIT' ? '暂时无法连接模型，正在自动重试…' : '正在准备下一题…' }}
        </div>
        <div v-if="phase === 'GENERATION_FAILED' && active" class="notice error" role="alert">
          {{ error || '后续问题生成失败。你的回答已保存，可以单独重试生成。' }}
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
        <div v-if="restoreId && !session" class="restore-actions"><p>发现上次练习，恢复后可继续回答或结束面试。</p><button class="button button-primary" :disabled="locked" @click="restore">重试恢复</button><button class="text-button" :disabled="locked" @click="reset">开始新的练习</button></div>
        <InterviewSetup v-else-if="!session" :material="material" v-model:paste="paste" v-model:target-role="targetRole" :locked="locked" @upload="upload" @use-paste="uploadPaste" @start="start" />
        <template v-if="session">
          <InterviewHeader :role="session.targetRole" :active="active" :progress="completedRoots.length" :total="session.questionCount" :can-finish="!!session && active && !busy && !finishFinishing" @finish="requestFinish" @open-info="openInfo" />
          <InterviewMessages v-if="conversationMessages.length" :messages="conversationMessages" :voice="voice" :session-id="session.sessionId" />
          <template v-if="active && current">
            <div class="question-card">
              <div class="question-number">{{ questionLabel(current, completedRoots.length) }}</div>
              <p v-if="currentMetadata.kind === 'ALGORITHM'" class="algorithm-source">LeetCode Hot 100 · {{ currentMetadata.algorithm?.title }} <a v-if="algorithmUrl(currentMetadata.algorithm)" :href="algorithmUrl(currentMetadata.algorithm)" target="_blank" rel="noopener noreferrer">查看原题 ↗</a></p>
              <h3>{{ current.question }}</h3>
              <div class="voice-actions">
                <button data-testid="question-play" class="button button-ghost" :disabled="locked || !voice.ttsEnabled" @click="voice.speechQuestionId === current.questionId && ['loading', 'playing'].includes(voice.speechState) ? voice.stopQuestion() : voice.playQuestion({ sessionId: session.sessionId, questionId: current.questionId })">{{ voice.speechQuestionId === current.questionId && voice.speechState === 'loading' ? '正在生成 · 停止' : voice.speechQuestionId === current.questionId && voice.speechState === 'playing' ? '停止朗读' : '播放题目' }}</button>
              </div>
              <p v-if="!voice.ttsEnabled" class="voice-note">{{ voice.capabilityState === 'loading' ? '正在确认题目朗读能力，文字作答可直接继续。' : '题目朗读暂不可用，可阅读题目并用文字回答。' }}</p>
              <p v-if="voice.speechError" class="notice error" role="alert">{{ voice.speechError }}</p>
            </div>
            <InterviewComposer v-model="answer" :locked="locked" :pending="pending" :kind="currentMetadata.kind" :guidance="answerGuidance(currentMetadata.kind)" :voice="voice" @send="submitAnswer" @audio="recordingAudio = $event" />
          </template>
          <div class="session-actions">
            <template v-if="!active"><button data-testid="view-report" class="button button-primary" :disabled="locked" @click="viewReport">查看报告</button><button class="button button-ghost" :disabled="locked" @click="reset">开始新的练习</button></template>
          </div>
          <p class="interview-footnote">本地录音在刷新后无法恢复，请重新录音；回答文字与转写预览按题保存在本机，保留 7 天。已提交面试记录可在一天内恢复，后端重启后失效。请勿在简历或回答中填写不必要的敏感信息。</p>
        </template>
      </main>
    </div>
    <InterviewInfoDrawer :open="infoOpen" :session="session" :material="material" :voice-max-seconds="voice.maxSeconds" :voice-max-bytes="voice.maxBytes" @close="closeInfo" />
    <InterviewFinishDialog :open="finishChoice && active" :message="finishMessage" :alternative-label="finishAlternativeLabel" :pending="pending" :answer="answer" :voice="voice" :locked="voice.busy" :finishing="finishFinishing" @continue="continueFinish" @confirm="chooseFinish" />
    <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>文字与语音面试</span></footer>
  </section>
</template>

<style scoped>
.interview-plan { align-self: center; font-size: 13px; line-height: 1.8; padding: 12px 0; }
.interview-plan p { margin: 6px 0 0; color: #79766e; }
.algorithm-source { font-size: 13px; line-height: 1.8; }
.algorithm-source a { color: #566e40; margin-left: 8px; }
.algorithm-answer { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; tab-size: 4; }
.specialty-plan p { margin: 6px 0 0; font-size: 13px; line-height: 1.8; }
.text-interview .interview-main { min-width: 0; }
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
@media (max-width: 640px) { .setup-grid { grid-template-columns: 1fr; gap: 0; } .answer-tools { flex-wrap: wrap; gap: 14px; } }
</style>
