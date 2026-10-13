<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createInterviewApi } from '../api/interview.js'

const router = useRouter()
const api = createInterviewApi({ baseUrl: import.meta.env.VITE_API_BASE_URL || '' })
const storageKey = 'career-studio-interview-id'
const material = ref(null)
const session = ref(null)
const paste = ref('')
const targetRole = ref('')
const questionCount = ref(5)
const apiKey = ref('')
const model = ref('')
const modelBaseUrl = ref('')
const answer = ref('')
const busy = ref('')
const error = ref('')
const aiCheck = ref('')
const aiCheckOk = ref(false)
const restoreId = ref('')
const uncertainAnswer = ref(null)
const completed = computed(() => session.value?.turns.filter(turn => turn.answer != null) || [])
const current = computed(() => session.value?.currentQuestion)
const pending = computed(() => session.value?.pendingAnswer ?? uncertainAnswer.value)
const active = computed(() => session.value?.status === 'ACTIVE')
function remember(id) {
  try { id ? sessionStorage.setItem(storageKey, id) : sessionStorage.removeItem(storageKey) } catch { /* Private browsers may disable storage. */ }
}
function applySession(value) {
  if (!value?.sessionId || !Array.isArray(value.turns) || !['ACTIVE', 'FINISHED'].includes(value.status)) throw new Error('面试数据格式异常，请重试恢复。')
  session.value = value
  remember(value.sessionId)
  uncertainAnswer.value = null
  answer.value = value.pendingAnswer || ''
}
async function run(label, work) {
  if (busy.value) return
  busy.value = label
  error.value = ''
  try { await work() } catch (cause) {
    if ([404, 410].includes(cause.status)) {
      remember('')
      restoreId.value = ''
      session.value = null
      material.value = null
      uncertainAnswer.value = null
      error.value = '本次练习或简历已过期，请重新上传并开始。'
    } else error.value = cause.message || '网络连接失败，请检查连接后重试。'
  } finally { busy.value = '' }
}
async function upload(file) {
  if (!file) return
  if (!/\.(pdf|docx|txt)$/i.test(file.name)) { error.value = '请选择 PDF、DOCX 或 TXT 文件。'; return }
  if (!file.size || file.size > 5 * 1024 * 1024) { error.value = '文件不能为空，且不能超过 5 MB。'; return }
  await run('正在解析简历…', async () => {
    material.value = null
    const value = await api.upload(file)
    if (!value?.materialId || typeof value.text !== 'string') throw new Error('简历解析结果异常，请重试上传。')
    material.value = value
  })
}
function uploadPaste() {
  if (!paste.value.trim()) { error.value = '请先粘贴简历文本。'; return }
  return upload(new File([paste.value], '粘贴的简历.txt', { type: 'text/plain' }))
}
function start() {
  return run('正在生成第一题…', async () => {
    applySession(await api.start({ materialId: material.value.materialId, targetRole: targetRole.value.trim(), questionCount: Number(questionCount.value) }, { apiKey: apiKey.value, model: model.value, baseUrl: modelBaseUrl.value }))
  })
}
function submitAnswer() {
  const text = pending.value ?? answer.value.trim()
  if (!text || !current.value) return
  // 编辑框有 maxlength 保护，但程序回填（例如语音转写）没有。超长在这里拦住，
  // 不要等服务器 400 才知道。
  if (text.length > 5000) {
    error.value = '回答不能超过 5000 字，请精简后提交。'
    return
  }
  const questionId = current.value.questionId
  return run('正在处理回答并生成后续问题…', async () => {
    uncertainAnswer.value = text
    try {
      applySession(await api.answer(session.value.sessionId, { questionId, answer: text }, { apiKey: apiKey.value, model: model.value, baseUrl: modelBaseUrl.value }))
    } catch (cause) {
      // 响应可能丢失但提交其实成功了。读回一次再决定是否提示重试。
      let recovered = false
      try {
        applySession(await api.getSession(session.value.sessionId))
        recovered = true
      } catch { /* 原提交结果未知，保留同一回答供重试。 */ }
      if (recovered) {
        const saved = session.value.turns.find(turn => turn.questionId === questionId)
        if (saved?.answer === text) return
      }
      if (session.value?.currentQuestion?.questionId === questionId && session.value.pendingAnswer == null) {
        answer.value = text
        // 服务器明确说这道题还没有待提交回答时，草稿可以继续编辑；
        // 只有网络结果不明才锁定原回答。
        uncertainAnswer.value = recovered && cause.status === 400 ? null : text
      }
      throw cause
    }
  })
}
function finish() {
  return run('正在结束面试并生成报告…', async () => {
    const finished = await api.finish(session.value.sessionId)
    applySession(finished)
    router.push({ name: 'Report', query: { sessionId: finished.sessionId } })
  })
}
function checkAi() {
  return run('正在测试连接…', async () => {
    aiCheck.value = ''
    aiCheckOk.value = false
    try {
      const result = await api.checkAi({ apiKey: apiKey.value, model: model.value, baseUrl: modelBaseUrl.value })
      aiCheckOk.value = true
      aiCheck.value = `连接成功，模型：${result.model || '默认模型'}`
    } catch (cause) {
      // 结果直接显示在模型设置面板里，不再占用页面顶部的全局错误条。
      aiCheck.value = cause.message || '无法连接模型服务，请检查密钥、Base URL 和模型名称。'
    }
  })
}
function restore() { return run('正在恢复上次练习…', async () => { applySession(await api.getSession(restoreId.value)); restoreId.value = '' }) }
function reset() {
  remember('')
  session.value = null
  material.value = null
  answer.value = ''
  paste.value = ''
  error.value = ''
  restoreId.value = ''
  uncertainAnswer.value = null
  aiCheck.value = ''
}
onMounted(() => {
  try { restoreId.value = sessionStorage.getItem(storageKey) || '' } catch { /* Storage is optional. */ }
  if (restoreId.value) restore()
})
</script>

<template>
  <section class="page active text-interview" id="page-interview" data-title="模拟面试">
    <div class="page-intro interview-intro"><div><div class="eyebrow"><span class="eyebrow-line"></span>INTERVIEW ROOM / 05</div><h1>练习表达，建立从容<span class="greeting-dot">。</span></h1><p>从你的简历出发，展开一场真实的专业对话。</p></div><span class="session-label"><i></i>文字面试 · 不会录音</span></div>
    <div class="interview-layout">
      <main class="interview-main" :aria-busy="!!busy">
        <div v-if="error" class="notice error" role="alert">{{ error }}</div>
        <div v-if="busy" class="notice" role="status">{{ busy }}</div>
        <div v-if="restoreId && !session" class="restore-actions"><p>发现上次练习，恢复后可继续回答或结束面试。</p><button class="button button-primary" :disabled="!!busy" @click="restore">重试恢复</button><button class="text-button" :disabled="!!busy" @click="reset">开始新的练习</button></div>
        <template v-else-if="!session">
          <div class="interview-session-top"><div><span class="session-overline">BEFORE WE BEGIN</span><h2>准备你的简历</h2><span class="session-company">上传、确认内容，再开始面试</span></div></div>
          <label class="field">上传简历 <span>PDF / DOCX / TXT · 最大 5 MB</span><input type="file" accept=".pdf,.docx,.txt" :disabled="!!busy" @change="upload($event.target.files[0]); $event.target.value = ''" /></label>
          <details class="paste-panel"><summary>或粘贴简历文本</summary><label class="field" for="resume-paste">简历内容</label><textarea id="resume-paste" v-model="paste" rows="6" maxlength="20000" :disabled="!!busy" placeholder="粘贴简历内容（最多 20,000 字）"></textarea><button class="button button-ghost" :disabled="!!busy || !paste.trim()" @click="uploadPaste">使用这份文本</button></details>
          <div v-if="material" class="resume-preview"><h3>已解析 · {{ material.fileName }}</h3><p>请确认文字完整；扫描版 PDF 可改用粘贴文本。</p><pre>{{ material.text }}</pre></div>
          <div class="setup-grid"><label class="field">目标岗位（可选）<input v-model="targetRole" maxlength="200" :disabled="!!busy" placeholder="例如：Java 后端开发工程师" /></label><label class="field">问题数量<input v-model="questionCount" type="number" min="1" max="10" :disabled="!!busy" /></label></div>
        </template>
        <details v-if="!session || active" class="model-panel"><summary>模型设置（可选）</summary><p>留空使用服务端配置。密钥仅保存在当前页面内存，刷新后需重新输入；Base URL 仅在填了自己的密钥时生效。</p><label class="field">API Key<input v-model="apiKey" @input="aiCheck = ''" type="password" autocomplete="off" :disabled="!!busy" placeholder="使用自己的模型密钥（可选）" /></label><label class="field">Base URL<input v-model="modelBaseUrl" @input="aiCheck = ''" autocomplete="off" spellcheck="false" :disabled="!!busy" placeholder="例如 https://api.deepseek.com/v1" /></label><label class="field">模型名称<input v-model="model" @input="aiCheck = ''" autocomplete="off" :disabled="!!busy" placeholder="留空使用服务端默认模型" /></label><button class="button button-ghost" :disabled="!!busy" @click="checkAi">测试连接</button><p v-if="aiCheck" class="notice" :class="{ error: !aiCheckOk }">{{ aiCheck }}</p></details>
        <template v-if="!session && !restoreId"><p class="consent">开始面试后，简历文本和回答将发送至配置的大模型服务</p><button class="button button-primary" :disabled="!!busy || !material || !Number.isInteger(Number(questionCount)) || Number(questionCount) < 1 || Number(questionCount) > 10" @click="start">开始面试 <span aria-hidden="true">→</span></button></template>
        <template v-if="session">
          <div class="interview-session-top"><div><span class="session-overline">{{ active ? 'IN CONVERSATION' : 'SESSION COMPLETE' }}</span><h2>{{ session.targetRole || '简历模拟面试' }}</h2><span class="session-company">{{ active ? '结合简历与回答进行提问' : '本次面试已结束，可回顾下方问答' }}</span></div></div>
          <div class="interview-progress"><div><span>已完成</span><b>{{ completed.length }} <i>/</i> {{ session.questionCount }}</b></div><div class="interview-progress-track"><i :style="{ width: `${completed.length / session.questionCount * 100}%` }"></i></div></div>
          <ol v-if="completed.length" class="history" aria-label="面试问答记录"><li v-for="(turn, index) in completed" :key="turn.questionId"><div class="session-overline">QUESTION {{ String(index + 1).padStart(2, '0') }}</div><h3>{{ turn.question }}</h3><div class="past-answer"><span>你的回答</span><p>{{ turn.answer }}</p></div></li></ol>
          <template v-if="active && current"><div class="question-card"><div class="question-number">QUESTION {{ String(completed.length + 1).padStart(2, '0') }}</div><h3>{{ current.question }}</h3></div><div class="answer-editor"><label for="interview-answer">你的回答 <span>围绕背景、行动与结果组织表达</span></label><p v-if="pending != null" class="notice">回答已保留。重试会提交同一份回答；也可以结束本次面试。</p><textarea id="interview-answer" v-model="answer" rows="7" maxlength="5000" :disabled="!!busy || pending != null" placeholder="说说你的思路、采取的行动，以及最终结果…"></textarea><div class="answer-tools"><span>{{ answer.length }} / 5000 字</span><button class="button button-primary" :disabled="!!busy || !(pending ?? answer.trim())" @click="submitAnswer">{{ pending != null ? '重试提交' : '提交回答' }} →</button></div></div></template>
          <div class="session-actions"><button v-if="active" class="text-button" :disabled="!!busy" @click="finish">结束本次面试</button><button v-else class="button button-primary" :disabled="!!busy" @click="reset">开始新的练习</button></div>
          <p class="interview-footnote">当前浏览器会话内可刷新恢复，面试记录可在一天内恢复，后端重启后失效。请勿在简历或回答中填写不必要的敏感信息。</p>
        </template>
      </main>
      <aside class="interview-aside"><div class="aside-quote-mark">“</div><div class="eyebrow">A THOUGHT BEFORE YOU BEGIN</div><h3>不必急着<br>给出完美答案。</h3><p>面试更像一次专业对话。讲清楚你的思路、选择和真实贡献，就已经很好。</p><div class="aside-divider"></div><div class="interview-aside-meta"><span>本次练习关注</span><ul><li><i>01</i> 项目表达</li><li><i>02</i> 技术理解</li><li><i>03</i> 逻辑结构</li></ul></div></aside>
    </div>
    <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>文字面试</span></footer>
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
@media (max-width: 640px) { .setup-grid { grid-template-columns: 1fr; gap: 0; } .answer-tools { flex-wrap: wrap; gap: 14px; } }
</style>