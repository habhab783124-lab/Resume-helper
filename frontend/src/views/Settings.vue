<script setup>
import { computed, ref, watch } from 'vue'
import { createInterviewApi } from '../api/interview.js'
import { getAiSettings, setAiSettings, getSpeechSettings, setSpeechSettings, getLocalApiBaseUrl, setLocalApiBaseUrl } from '../api/requestContext.js'

const api = createInterviewApi({ baseUrl: import.meta.env.VITE_API_BASE_URL || (import.meta.env.PROD ? 'http://localhost:8080' : '') })
const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL || (import.meta.env.PROD ? 'http://localhost:8080' : '')
const defaultBaseUrl = configuredBaseUrl || 'http://localhost:8080'
const backendUrl = ref(getLocalApiBaseUrl() || defaultBaseUrl)
const status = ref('idle')
const checking = ref(false)
const resultMessage = ref('先启动本机 Java 服务，然后点击“保存并检测”。')
const connectedSources = ref(0)

const statusLabel = computed(() => ({ idle: '未检测', checking: '检测中', connected: '已连接', disconnected: '未连接' })[status.value])
const resultTitle = computed(() => ({ idle: '等待检测连接', checking: '正在检测服务', connected: `Java 服务已连接${connectedSources.value ? ` · ${connectedSources.value} 个岗位来源` : ''}`, disconnected: '暂未连接到 Java 服务' })[status.value])

function normalizeAddress(value) {
  try {
    const url = new URL(String(value || '').trim())
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) return ''
    return url.origin
  } catch {
    return ''
  }
}

async function checkConnection() {
  if (checking.value) return
  const address = normalizeAddress(backendUrl.value)
  if (!address) {
    status.value = 'disconnected'
    resultMessage.value = '请输入有效的服务地址，例如 http://localhost:8080。'
    return
  }

  checking.value = true
  status.value = 'checking'
  resultMessage.value = `正在请求 ${address} 的岗位来源接口。`
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 5000)

  try {
    const response = await fetch(`${address}/api/jobs/sources`, { signal: controller.signal })
    if (!response.ok) throw new Error(`服务返回 HTTP ${response.status}`)
    const payload = await response.json()
    if (payload?.success !== true || !Array.isArray(payload.data)) throw new Error('服务响应格式不正确')
    connectedSources.value = Array.isArray(payload?.data) ? payload.data.length : 0
    status.value = 'connected'
    resultMessage.value = `已成功访问 ${address}。保存后，简历、岗位和面试请求会使用此地址。`
  } catch (error) {
    status.value = 'disconnected'
    resultMessage.value = error?.name === 'AbortError'
      ? '连接超时。请确认 Java 服务已启动，并检查服务地址和端口。'
      : '连接失败。请先在本机启动 Java 服务；如果服务已启动，请检查地址、端口和后端 CORS 配置。'
    connectedSources.value = 0
  } finally {
    window.clearTimeout(timeout)
    checking.value = false
  }
}

async function saveAndCheck() {
  const address = normalizeAddress(backendUrl.value)
  if (!address) {
    status.value = 'disconnected'
    resultMessage.value = '地址格式不正确。请只填写协议和主机端口，例如 http://localhost:8080。'
    return
  }
  clearResults()
  backendUrl.value = address
  setLocalApiBaseUrl(address)
  await checkConnection()
}

async function resetAddress() {
  clearResults()
  setLocalApiBaseUrl('')
  backendUrl.value = defaultBaseUrl
  await checkConnection()
}


const ai = getAiSettings()
const apiKey = ref(ai.apiKey)
const model = ref(ai.model)
const baseUrl = ref(ai.baseUrl)
const speechKey = ref(getSpeechSettings().speechKey)

const showApiKey = ref(false)
const showSpeechKey = ref(false)
const modelBusy = ref(false)
const speechBusy = ref(false)
const modelResult = ref('')
const speechResult = ref('')
const modelOk = ref(false)
const speechOk = ref(false)

function clearResults() {
  modelResult.value = ''
  modelOk.value = false
  speechResult.value = ''
  speechOk.value = false
}

// 改动即存，不设保存按钮：与面试页原来的写法一致，设置页关掉后凭据仍在。
watch([apiKey, model, baseUrl], () => { setAiSettings({ apiKey: apiKey.value, model: model.value, baseUrl: baseUrl.value }); modelResult.value = ''; modelOk.value = false }, { flush: 'sync' })
watch(speechKey, () => { setSpeechSettings({ speechKey: speechKey.value }); speechResult.value = ''; speechOk.value = false }, { flush: 'sync' })

async function checkModel() {
  if (modelBusy.value) return
  modelBusy.value = true
  modelResult.value = ''
  modelOk.value = false
  try {
    // 不传参：api 层会读当前设置，watch 已经把它写进存储。
    const result = await api.checkAi()
    modelOk.value = true
    modelResult.value = `连接成功${result?.model ? `，模型：${result.model}` : '，未指定模型名称'}`
  } catch (cause) {
    modelResult.value = cause.message || '无法连接模型服务，请检查密钥、Base URL 和模型名称。'
  } finally {
    modelBusy.value = false
  }
}

async function checkSpeech() {
  if (speechBusy.value) return
  speechBusy.value = true
  speechResult.value = ''
  speechOk.value = false
  try {
    const result = await api.checkSpeech()
    speechOk.value = true
    speechResult.value = result?.audioBytes
      ? `连接成功，已合成一段测试音频（${result.audioBytes} 字节）${result.speaker ? `，音色 ${result.speaker}` : ''}`
      : '连接成功'
  } catch (cause) {
    speechResult.value = cause.message || '无法连接语音服务，请检查密钥。'
  } finally {
    speechBusy.value = false
  }
}

function clearConfig() {
  apiKey.value = ''
  model.value = ''
  baseUrl.value = ''
  speechKey.value = ''
  clearResults()
  showApiKey.value = false
  showSpeechKey.value = false
}
</script>

<template>
  <section class="page active" id="page-settings" data-title="设置">
    <div class="page-intro">
      <div>
        <div class="eyebrow"><span class="eyebrow-line"></span>SETTINGS / 06</div>
        <h1>设置</h1>
        <p>配置后端连接、大模型和语音，供简历、岗位匹配与面试共用。</p>
      </div>
      <button class="button button-outline" type="button" :disabled="modelBusy || speechBusy || checking" @click="clearConfig">清空模型与语音凭据</button>
    </div>

    <section class="panel settings-panel" data-testid="backend-settings">
      <div class="panel-heading"><div><div class="eyebrow">JAVA SERVICE</div><h3>本地服务连接</h3><p class="settings-sub">先在本机 backend 目录运行 mvn spring-boot:run，再保存并检测。localhost 指打开网页的这台设备。</p></div></div>
      <form class="settings-form" @submit.prevent="saveAndCheck">
        <label class="field" for="backend-url"><span>Java 服务地址</span><input id="backend-url" v-model.trim="backendUrl" type="url" autocomplete="url" spellcheck="false" placeholder="http://localhost:8080" :disabled="checking || modelBusy || speechBusy" /></label>
        <div class="settings-actions"><button class="button button-primary" type="submit" :disabled="checking || modelBusy || speechBusy" data-testid="backend-save">{{ checking ? '正在检测…' : '保存并检测' }}</button><button class="button button-outline" type="button" :disabled="checking || modelBusy || speechBusy" @click="resetAddress" data-testid="backend-reset">恢复默认地址</button></div>
      </form>
      <p class="notice" :class="{ error: status === 'disconnected' }" role="status" data-testid="backend-result"><strong>{{ statusLabel }} · {{ resultTitle }}</strong><br />{{ resultMessage }}</p>
      <p class="settings-sub">服务地址保存在当前浏览器，模型与语音密钥仅保存在当前标签页。若浏览器提示本地网络访问，请允许；连接失败时检查服务端口和 CORS 配置。</p>
    </section>

    <p class="notice settings-notice"><svg class="notice-icon"><use href="#i-pin" /></svg><span>密钥只保存在当前浏览器标签页，关闭标签页即失效。密钥会随对应功能的请求发送给所选后端服务，请在简历与回答里避免不必要的敏感信息。<strong class="notice-strong">从 .env 这类配置文件复制时，只粘贴等号后面的值</strong>——带上 <code>ttsAndasr-key=</code> 这样的键名会被当成非法密钥。</span></p>

    <section class="panel settings-panel">
      <div class="panel-heading">
        <div>
          <div class="eyebrow">TEXT MODEL</div>
          <h3>大模型</h3>
          <p class="settings-sub">用于出题、评估回答与生成面试报告，OpenAI 兼容协议。</p>
        </div>
      </div>

      <div class="settings-form">
        <label class="field"><span>Base URL</span>
          <input :disabled="modelBusy" v-model="baseUrl" type="text" spellcheck="false" autocomplete="off" placeholder="例如 https://api.deepseek.com/v1" /></label>
        <label class="field"><span>模型名称</span>
          <input :disabled="modelBusy" v-model="model" type="text" spellcheck="false" autocomplete="off" placeholder="例如 deepseek-chat" /></label>
        <label class="field"><span>API Key</span>
          <div class="secret-input">
            <input data-testid="model-key" :disabled="modelBusy" v-model="apiKey" :type="showApiKey ? 'text' : 'password'" spellcheck="false" autocomplete="off" placeholder="只填值" />
            <button class="icon-button" type="button" :aria-label="showApiKey ? '隐藏 API Key' : '显示 API Key'" @click="showApiKey = !showApiKey"><svg><use href="#i-eye" /></svg></button>
          </div></label>
        <div class="settings-actions">
          <button class="button button-primary" type="button" :disabled="modelBusy" data-testid="model-check" @click="checkModel">
            <svg><use href="#i-spark" /></svg>{{ modelBusy ? '正在测试连接…' : '测试连接' }}</button>
          <span class="actions-hint">将向该服务发送一次极短请求</span>
        </div>
      </div>
      <p v-if="modelResult" class="notice" :class="{ error: !modelOk }" role="status">{{ modelResult }}</p>
    </section>

    <section class="panel settings-panel">
      <div class="panel-heading">
        <div>
          <div class="eyebrow">VOLCENGINE SPEECH</div>
          <h3>火山语音</h3>
          <p class="settings-sub">用于题目朗读与语音识别。与大模型是两套密钥体系，不能互相借用。</p>
        </div>
      </div>

      <div class="settings-form">
        <label class="field"><span>API Key</span>
          <div class="secret-input">
            <input data-testid="speech-key" :disabled="speechBusy" v-model="speechKey" :type="showSpeechKey ? 'text' : 'password'" spellcheck="false" autocomplete="off" placeholder="只填值" />
            <button class="icon-button" type="button" :aria-label="showSpeechKey ? '隐藏语音 Key' : '显示语音 Key'" @click="showSpeechKey = !showSpeechKey"><svg><use href="#i-eye" /></svg></button>
          </div></label>
        <div class="settings-actions">
          <button class="button button-primary" type="button" :disabled="speechBusy" data-testid="speech-check" @click="checkSpeech">
            <svg><use href="#i-mic" /></svg>{{ speechBusy ? '正在测试连接…' : '测试连接' }}</button>
          <span class="actions-hint">验证题目朗读连接，会合成短音频；语音识别需在面试中录音验证</span>
        </div>
      </div>
      <p v-if="speechResult" class="notice" :class="{ error: !speechOk }" role="status">{{ speechResult }}</p>
      <p v-else-if="!speechKey" class="settings-empty">未配置语音密钥时，面试页可继续文字作答，如服务端也未配置语音密钥，题目朗读与录音识别将不可用。</p>
    </section>

    <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>凭据仅存于当前标签页</span><span>大模型与火山语音</span></footer>
  </section>
</template>

<style scoped>
.settings-notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 0 0 26px;
  padding: 12px 16px;
  background: #eef1e7;
  border-radius: 6px;
  color: #5f6a60;
  font-size: 12px;
  line-height: 1.8;
}
.settings-notice .notice-icon { flex: none; width: 14px; height: 14px; margin-top: 3px; color: #3c594a; }
.settings-notice .notice-strong { color: #315c48; font-weight: 600; }
.settings-notice code { background: #e3e7da; border-radius: 3px; padding: 1px 5px; font-family: var(--mono, ui-monospace, monospace); font-size: 11px; }
.settings-panel { margin-bottom: 26px; padding: 24px; }
.settings-sub { margin: 6px 0 0; color: #849087; font-size: 11px; line-height: 1.8; }
.settings-form { display: grid; gap: 16px; margin-top: 20px; }
.field { display: flex; flex-direction: column; gap: 8px; font-size: 13px; }
.field span { color: #55605a; font-weight: 500; }
input {
  width: 100%;
  box-sizing: border-box;
  font: inherit;
  font-size: 13px;
  color: #353831;
  background: #fafaf7;
  border: 1px solid #e2e4dc;
  border-radius: 7px;
  padding: 12px;
}
input::placeholder { color: #b4bcb2; }
input:focus { outline: 2px solid #91a77c; outline-offset: 2px; }
.secret-input { display: flex; gap: 10px; align-items: stretch; }
.secret-input input { flex: 1 1 auto; }
.secret-input .icon-button { flex: none; }
.settings-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; margin-top: 4px; }
.actions-hint { color: #a5ada4; font-size: 11px; }
button:disabled { opacity: .55; cursor: not-allowed; }
.notice { margin: 18px 0 0; padding: 12px 16px; border-radius: 6px; background: #eef1e7; font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.notice.error { background: #fff1e9; color: #95452c; }
.settings-empty { margin: 18px 0 0; color: #a5ada4; font-size: 11px; line-height: 1.8; }
</style>
