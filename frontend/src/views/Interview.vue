<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createInterviewApi } from '../api/interview.js'
import { getAiSettings, setAiSettings } from '../api/requestContext.js'
import { useInterviewVoice } from '../interview/useInterviewVoice.js'

const router = useRouter()
const api = createInterviewApi({ baseUrl: import.meta.env.VITE_API_BASE_URL || '' })
const storageKey = 'career-studio-interview-id'
const material = ref(null)
const session = ref(null)
const paste = ref('')
const targetRole = ref('')
const questionCount = ref(5)
const settings = getAiSettings()
const apiKey = ref(settings.apiKey)
const model = ref(settings.model)
const modelBaseUrl = ref(settings.baseUrl)
const answer = ref('')
const busy = ref('')
const error = ref('')
const aiCheck = ref('')
const aiCheckOk = ref(false)
const restoreId = ref('')
const uncertainAnswer = ref(null)
const finishChoice = ref(false)
const recordingAudio = ref(null)
const completed = computed(() => session.value?.turns.filter(turn => turn.answer != null) || [])
const current = computed(() => session.value?.currentQuestion)
const pending = computed(() => session.value?.pendingAnswer ?? uncertainAnswer.value)
const active = computed(() => session.value?.status === 'ACTIVE')
const finishAlternativeLabel = computed(() => session.value?.pendingAnswer != null ? '保留已提交回答并结束' : uncertainAnswer.value != null ? '结束并保留已收到的回答' : '放弃草稿并结束')
const finishMessage = computed(() => session.value?.pendingAnswer != null
  ? '这份回答已提交并保留在服务端，结束时会计入面试报告。可以重试提交以生成下一题，或保留回答并结束。'
  : uncertainAnswer.value != null
    ? '上次提交结果尚未确认，回答可能已被服务端保留。结束会保留已收到的回答，无法撤回；也可继续作答后重试提交。'
    : '还有未提交的草稿或转写预览。请选择如何结束。')
function voiceContext() {
  return { sessionId: session.value?.sessionId, questionId: current.value?.questionId, status: session.value?.status, pending: pending.value, blocked: !!busy.value }
}
function pauseRecordingReplay() {
  if (recordingAudio.value) { recordingAudio.value.pause(); recordingAudio.value.currentTime = 0 }
}
const voice = reactive(useInterviewVoice({ api, getContext: voiceContext,
  getDraft: () => answer.value, setDraft: text => { answer.value = text },
  syncSession: syncVoiceSession, pauseReplay: pauseRecordingReplay }))
const locked = computed(() => !!busy.value || voice.busy)
function saveModelSettings() {
  const value = { apiKey: apiKey.value, model: model.value, baseUrl: modelBaseUrl.value }
  setAiSettings(value)
  return value
}
watch([apiKey, model, modelBaseUrl], saveModelSettings, { flush: 'sync' })
watch(pending, () => voice.syncContext(), { flush: 'sync' })
function remember(id) {
  try { id ? sessionStorage.setItem(storageKey, id) : sessionStorage.removeItem(storageKey) } catch { /* Storage is optional. */ }
}
function applySession(value) {
  if (!value?.sessionId || !Array.isArray(value.turns) || !['ACTIVE', 'FINISHED'].includes(value.status)) throw new Error('面试数据格式异常，请重试恢复。')
  const sameQuestion = session.value?.sessionId === value.sessionId && current.value?.questionId === value.currentQuestion?.questionId && value.status === 'ACTIVE'
  const draft = answer.value
  if (!sameQuestion) finishChoice.value = false
  session.value = value
  remember(value.sessionId)
  uncertainAnswer.value = null
  answer.value = value.pendingAnswer ?? (sameQuestion ? draft : '')
  voice.syncContext()
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
    saveModelSettings()
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
    applySession(await api.start({ materialId: material.value.materialId, targetRole: targetRole.value.trim(), questionCount: Number(questionCount.value) }, saveModelSettings()))
  })
}
function submitAnswer() {
  if (locked.value || !active.value) return false
  const text = pending.value ?? answer.value.trim()
  if (!text || !current.value) return false
  if (text.length > 5000) { error.value = '回答不能超过 5000 字，请精简后提交。'; return false }
  const questionId = current.value.questionId
  const sessionId = session.value.sessionId
  return run('正在处理回答并生成后续问题…', async () => {
    uncertainAnswer.value = text
    try {
      applySession(await api.answer(sessionId, { questionId, answer: text }, saveModelSettings()))
      if (session.value.turns.find(turn => turn.questionId === questionId)?.answer !== text) throw new Error('尚未确认回答保存成功，请重试恢复后继续。')
    } catch (cause) {
      let recovered = false
      try { applySession(await api.getSession(sessionId)); recovered = true } catch { /* Keep the original answer for a safe retry. */ }
      if (recovered && session.value.turns.find(turn => turn.questionId === questionId)?.answer === text) return
      if (session.value?.currentQuestion?.questionId === questionId && session.value.pendingAnswer == null) {
        answer.value = text
        uncertainAnswer.value = recovered && cause.status === 400 ? null : text
      }
      throw cause
    }
  })
}
function viewReport() {
  if (locked.value || session.value?.status !== 'FINISHED') return
  saveModelSettings()
  voice.dispose()
  router.push({ name: 'Report', query: { sessionId: session.value.sessionId } })
}
function finish() {
  if (locked.value || !active.value) return
  if (answer.value.trim() || pending.value != null || voice.preview?.text) { finishChoice.value = true; return }
  return endSession()
}
async function endSession() {
  if (locked.value || !active.value) return false
  const result = await run('正在结束面试…', async () => {
    const value = await api.finish(session.value.sessionId)
    if (value?.status !== 'FINISHED') throw new Error('面试尚未结束，请重试。')
    applySession(value)
  })
  if (result) { finishChoice.value = false; viewReport() }
  return result
}
async function chooseFinish(choice) {
  if (locked.value) return
  if (choice === 'continue') { finishChoice.value = false; return }
  if (choice === 'submit') {
    if (voice.preview?.text) { error.value = '请先确认转写预览，再提交后结束；或选择放弃草稿并结束。'; return }
    if (!await submitAnswer()) return
    if (session.value?.status === 'FINISHED') { finishChoice.value = false; viewReport(); return }
    return endSession()
  }
  if (choice === 'preserve' && pending.value != null) return endSession()
  if (choice === 'discard') {
    if (pending.value != null) { error.value = '已提交的回答不能作为本地草稿丢弃，请选择保留回答并结束。'; return }
    return endSession()
  }
}
function checkAi() {
  return run('正在测试连接…', async () => {
    aiCheck.value = ''
    aiCheckOk.value = false
    try {
      const result = await api.checkAi(saveModelSettings())
      aiCheckOk.value = true
      aiCheck.value = `连接成功，模型：${result.model || '默认模型'}`
    } catch (cause) { aiCheck.value = cause.message || '无法连接模型服务，请检查密钥、Base URL 和模型名称。' }
  })
}
function restore() { return run('正在恢复上次练习…', async () => { applySession(await api.getSession(restoreId.value)); restoreId.value = '' }, true) }
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
  aiCheck.value = ''
  finishChoice.value = false
  voice.syncContext()
}
onMounted(() => {
  voice.loadCapabilities()
  try { restoreId.value = sessionStorage.getItem(storageKey) || '' } catch { /* Storage is optional. */ }
  if (restoreId.value) restore()
})
onUnmounted(() => voice.dispose())
</script>

<template>
  <section class="page active text-interview" id="page-interview" data-title="模拟面试">
    <div class="page-intro interview-intro"><div><div class="eyebrow"><span class="eyebrow-line"></span>INTERVIEW ROOM / 05</div><h1>练习表达，建立从容<span class="greeting-dot">。</span></h1><p>从你的简历出发，展开一场真实的专业对话。</p></div><span class="session-label"><i></i>文字作答 · 可选语音</span></div>
    <div class="interview-layout">
      <main class="interview-main" :aria-busy="locked">
        <div v-if="error" class="notice error" role="alert">{{ error }}</div>
        <div v-if="busy" class="notice" role="status">{{ busy }}</div>
        <div v-if="restoreId && !session" class="restore-actions"><p>发现上次练习，恢复后可继续回答或结束面试。</p><button class="button button-primary" :disabled="locked" @click="restore">重试恢复</button><button class="text-button" :disabled="locked" @click="reset">开始新的练习</button></div>
        <template v-else-if="!session">
          <div class="interview-session-top"><div><span class="session-overline">BEFORE WE BEGIN</span><h2>准备你的简历</h2><span class="session-company">上传、确认内容，再开始面试</span></div></div>
          <label class="field">上传简历 <span>PDF / DOCX / TXT · 最大 10 MiB</span><input type="file" accept=".pdf,.docx,.txt" :disabled="locked" @change="upload($event.target.files[0]); $event.target.value = ''" /></label>
          <details class="paste-panel"><summary>或粘贴简历文本</summary><label class="field" for="resume-paste">简历内容</label><textarea id="resume-paste" v-model="paste" rows="6" maxlength="20000" :disabled="locked" placeholder="粘贴简历内容（最多 20,000 字）"></textarea><button class="button button-ghost" :disabled="locked || !paste.trim()" @click="uploadPaste">使用这份文本</button></details>
          <div v-if="material" class="resume-preview"><h3>已解析 · {{ material.fileName }}</h3><p>请确认文字完整；扫描版 PDF 可改用粘贴文本。</p><pre>{{ material.text }}</pre></div>
          <div class="setup-grid"><label class="field">目标岗位（可选）<input v-model="targetRole" maxlength="200" :disabled="locked" placeholder="例如：Java 后端开发工程师" /></label><label class="field">问题数量<input v-model="questionCount" type="number" min="1" max="10" :disabled="locked" /></label></div>
        </template>
        <details v-if="!session || active" class="model-panel"><summary>模型设置（可选）</summary><p>留空使用服务端配置。密钥保存在当前浏览器标签页，面试与报告共用；Base URL 仅在填了自己的密钥时生效。</p><label class="field">API Key<input v-model="apiKey" @input="aiCheck = ''" type="password" autocomplete="off" :disabled="locked" placeholder="使用自己的模型密钥（可选）" /></label><label class="field">Base URL<input v-model="modelBaseUrl" @input="aiCheck = ''" autocomplete="off" spellcheck="false" :disabled="locked" placeholder="例如 https://api.deepseek.com/v1" /></label><label class="field">模型名称<input v-model="model" @input="aiCheck = ''" autocomplete="off" :disabled="locked" placeholder="留空使用服务端默认模型" /></label><button class="button button-ghost" :disabled="locked" @click="checkAi">测试连接</button><p v-if="aiCheck" class="notice" :class="{ error: !aiCheckOk }">{{ aiCheck }}</p></details>
        <template v-if="!session && !restoreId"><p class="consent">开始面试后，简历文本和回答将发送至配置的大模型服务</p><button class="button button-primary" :disabled="locked || !material || !Number.isInteger(Number(questionCount)) || Number(questionCount) < 1 || Number(questionCount) > 10" @click="start">开始面试 <span aria-hidden="true">→</span></button></template>
        <template v-if="session">
          <div class="interview-session-top"><div><span class="session-overline">{{ active ? 'IN CONVERSATION' : 'SESSION COMPLETE' }}</span><h2>{{ session.targetRole || '简历模拟面试' }}</h2><span class="session-company">{{ active ? '结合简历与回答进行提问' : '本次面试已结束，可回顾下方问答' }}</span></div></div>
          <div class="interview-progress"><div><span>已完成</span><b>{{ completed.length }} <i>/</i> {{ session.questionCount }}</b></div><div class="interview-progress-track"><i :style="{ width: `${completed.length / session.questionCount * 100}%` }"></i></div></div>
          <ol v-if="completed.length" class="history" aria-label="面试问答记录"><li v-for="(turn, index) in completed" :key="turn.questionId"><div class="session-overline">QUESTION {{ String(index + 1).padStart(2, '0') }}</div><h3>{{ turn.question }}</h3><div class="past-answer"><span>你的回答</span><p>{{ turn.answer }}</p></div></li></ol>
          <template v-if="active && current">
            <div class="question-card">
              <div class="question-number">QUESTION {{ String(completed.length + 1).padStart(2, '0') }}</div>
              <h3>{{ current.question }}</h3>
              <div class="voice-actions">
                <button data-testid="question-play" class="button button-ghost" :disabled="locked || !voice.ttsEnabled" @click="voice.playQuestion">{{ voice.speechState === 'loading' ? '正在生成语音…' : voice.speechState === 'stopped' ? '重播题目' : '播放题目' }}</button>
                <button data-testid="question-stop" class="text-button" :disabled="!['loading', 'playing'].includes(voice.speechState)" @click="voice.stopQuestion">停止朗读</button>
              </div>
              <p v-if="!voice.ttsEnabled" class="voice-note">{{ voice.capabilityState === 'loading' ? '正在确认题目朗读能力，文字作答可直接继续。' : '题目朗读暂不可用，可阅读题目并用文字回答。' }}</p>
              <p v-if="voice.speechError" class="notice error" role="alert">{{ voice.speechError }}</p>
            </div>
            <div class="answer-editor">
              <label for="interview-answer">你的回答 <span>围绕背景、行动与结果组织表达</span></label>
              <p v-if="pending != null" class="notice">回答已保留。重试会提交同一份回答；结束时会保留服务端已收到的回答。录音和转写不能覆盖待提交回答。</p>
              <div class="voice-panel">
                <p class="voice-note">可选语音作答：点击后才会申请麦克风。录音由你主动识别时发送至火山引擎；转写先预览，确认后再写入草稿，不会自动提交回答。</p>
                <p class="voice-note">录音上限 {{ voice.maxSeconds }} 秒 / {{ (voice.maxBytes / 1024 / 1024).toFixed(1) }} MiB。</p>
                <p v-if="voice.capabilityState === 'loading'" role="status" class="voice-note">正在确认语音能力，可继续文字作答。</p>
                <p v-else-if="voice.capabilityError" class="voice-note">{{ voice.capabilityError }}</p>
                <p v-else-if="!voice.asrEnabled" class="voice-note">语音识别暂不可用，请用文字回答。</p>
                <button v-if="voice.capabilityState !== 'loading' && (!voice.asrEnabled || !voice.ttsEnabled)" class="text-button" :disabled="locked" @click="voice.loadCapabilities">重试语音能力</button>
                <div class="voice-actions">
                  <button data-testid="recording-start" class="button button-ghost" :disabled="locked || pending != null || !voice.asrEnabled" @click="voice.startRecording">{{ voice.blob ? '重新录音' : '开始录音' }}</button>
                  <button data-testid="recording-stop" class="button button-ghost" :disabled="voice.recorderState !== 'recording'" @click="voice.stopRecording">停止录音</button>
                  <button data-testid="recording-cancel" class="text-button" :disabled="!['requesting-mic', 'recording', 'recorded'].includes(voice.recorderState) && !voice.blob" @click="voice.cancelRecording">{{ voice.recorderState === 'recorded' ? '丢弃录音' : '取消录音' }}</button>
                </div>
                <p v-if="voice.recorderState === 'requesting-mic'" class="voice-note" role="status">等待麦克风授权，可随时取消。</p>
                <p v-if="voice.recorderState === 'recording'" class="voice-note" role="status">录音中 · {{ voice.durationSeconds }} / {{ voice.maxSeconds }} 秒</p>
                <audio v-if="voice.recordingUrl" ref="recordingAudio" :src="voice.recordingUrl" controls :class="{ 'replay-locked': locked }" :inert="locked" aria-label="回放自己的录音" @play="voice.replayRecording"></audio>
                <div v-if="voice.blob" class="voice-actions">
                  <button data-testid="transcription-start" class="button button-ghost" :disabled="locked || pending != null || !voice.asrEnabled" @click="voice.transcribe">{{ voice.transcribing ? '正在识别…' : '识别 / 重试录音' }}</button>
                  <button v-if="voice.transcribing" data-testid="transcription-cancel" class="text-button" @click="voice.cancelTranscription">取消识别，保留录音</button>
                </div>
                <p v-if="voice.transcribing" class="voice-note" role="status">正在转写录音，最长等待 90 秒；可取消后重试。</p>
                <p v-if="voice.error || voice.recorderError" class="notice error" role="alert">{{ voice.error || voice.recorderError }}</p>
                <div v-if="voice.preview" class="transcription-preview">
                  <h4>转写预览 · 尚未写入草稿</h4>
                  <pre>{{ voice.preview.text }}</pre>
                  <p v-if="voice.preview.text.length > 5000" class="notice error">转写超过 5000 字，已完整保留。确认后请在回答框精简再提交。</p>
                  <div class="voice-actions">
                    <button data-testid="transcript-replace" class="button button-ghost" :disabled="locked || pending != null" @click="voice.acceptPreview('replace')">替换草稿</button>
                    <button data-testid="transcript-append" class="button button-ghost" :disabled="locked || pending != null" @click="voice.acceptPreview('append')">追加到草稿</button>
                    <button data-testid="transcript-keep" class="text-button" :disabled="locked || pending != null" @click="voice.acceptPreview('keep')">保留原草稿</button>
                  </div>
                </div>
              </div>
              <textarea id="interview-answer" v-model="answer" rows="7" maxlength="5000" :disabled="locked || pending != null" placeholder="说说你的思路、采取的行动，以及最终结果…"></textarea>
              <p v-if="answer.length > 5000" class="notice error">回答超过 5000 字，请精简后提交；完整内容已保留。</p>
              <div class="answer-tools"><span>{{ answer.length }} / 5000 字</span><button class="button button-primary" :disabled="locked || !(pending ?? answer.trim()) || (pending ?? answer).length > 5000" @click="submitAnswer">{{ pending != null ? '重试提交' : '提交回答' }} →</button></div>
            </div>
          </template>
          <div v-if="finishChoice && active" class="notice finish-choice" role="group" aria-label="处理未提交内容">
            <p>{{ finishMessage }}</p>
            <p v-if="voice.preview">转写尚未确认，请先替换、追加或保留原草稿，再提交后结束。</p>
            <div class="voice-actions">
              <button data-testid="finish-submit" class="button button-primary" :disabled="locked || !!voice.preview || !(pending ?? answer.trim()) || (pending ?? answer).length > 5000" @click="chooseFinish('submit')">提交后结束</button>
              <button v-if="pending != null" data-testid="finish-preserve" class="button button-ghost" :disabled="locked" @click="chooseFinish('preserve')">{{ finishAlternativeLabel }}</button>
              <button v-else data-testid="finish-discard" class="button button-ghost" :disabled="locked" @click="chooseFinish('discard')">{{ finishAlternativeLabel }}</button>
              <button class="text-button" :disabled="locked" @click="chooseFinish('continue')">继续作答</button>
            </div>
          </div>
          <div class="session-actions">
            <button v-if="active" data-testid="finish-interview" class="text-button" :disabled="locked" @click="finish">结束本次面试</button>
            <template v-else><button data-testid="view-report" class="button button-primary" :disabled="locked" @click="viewReport">查看报告</button><button class="button button-ghost" :disabled="locked" @click="reset">开始新的练习</button></template>
          </div>
          <p class="interview-footnote">本地录音、转写预览和未提交草稿在刷新后无法恢复。已提交面试记录可在一天内恢复，后端重启后失效。请勿在简历或回答中填写不必要的敏感信息。</p>
        </template>
      </main>
      <aside class="interview-aside"><div class="aside-quote-mark">“</div><div class="eyebrow">A THOUGHT BEFORE YOU BEGIN</div><h3>不必急着<br>给出完美答案。</h3><p>面试更像一次专业对话。讲清楚你的思路、选择和真实贡献，就已经很好。</p><div class="aside-divider"></div><div class="interview-aside-meta"><span>本次练习关注</span><ul><li><i>01</i> 项目表达</li><li><i>02</i> 技术理解</li><li><i>03</i> 逻辑结构</li></ul></div></aside>
    </div>
    <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>文字与语音面试</span></footer>
  </section>
</template>

<style scoped>
.text-interview .interview-main { min-width: 0; }
.field { display: flex; flex-direction: column; gap: 9px; font-size: 13px; margin: 18px 0; }
.field span, .model-panel p, .resume-preview p, .consent { color: #79766e; font-size: 12px; line-height: 1.8; }
input, textarea { width: 100%; box-sizing: border-box; font: inherit; color: #353831; background: #fafaf7; border: 1px solid #e2e4dc; border-radius: 7px; padding: 12px; }
input:focus, textarea:focus { outline: 2px solid #91a77c; outline-offset: 2px; }
input[type=file] { padding: 18px; border-style: dashed; }
button:disabled { opacity: .5; cursor: not-allowed; }
.setup-grid { display: grid; grid-template-columns: 2fr 1fr; gap: 18px; }
.paste-panel, .model-panel { border-top: 1px solid #e7e8e1; padding: 18px 0; }
summary { cursor: pointer; font-size: 13px; }
.paste-panel button, .model-panel button { margin-top: 12px; }
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
.voice-note { color: #79766e; font-size: 12px; line-height: 1.8; }
.voice-panel audio { display: block; width: 100%; max-width: 460px; margin: 16px 0; }
.replay-locked { pointer-events: none; opacity: .5; }
.transcription-preview { border-top: 1px solid #e2e4dc; margin-top: 16px; padding-top: 12px; }
.transcription-preview h4 { font-size: 13px; }
.transcription-preview pre { max-height: 280px; overflow-y: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.8; font-size: 13px; }
.finish-choice p { margin: 0 0 12px; }
@media (max-width: 640px) { .setup-grid { grid-template-columns: 1fr; gap: 0; } .answer-tools { flex-wrap: wrap; gap: 14px; } }
</style>
