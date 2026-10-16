<template>
  <section class="page active settings-page">
    <div class="page-intro">
      <div>
        <div class="eyebrow"><span class="eyebrow-line"></span>CONNECTION / 04</div>
        <h1>本地服务设置</h1>
        <p>连接当前这台电脑上的 Java 服务，简历、岗位和面试功能会使用这个地址。</p>
      </div>
      <span class="settings-local-badge"><i></i>仅保存在当前浏览器</span>
    </div>

    <div class="settings-grid">
      <div class="settings-main">
        <section class="settings-connection-card">
          <div class="settings-card-top">
            <div>
              <div class="settings-overline">JAVA SERVICE</div>
              <h2>连接本机后端</h2>
              <p>保存地址后，网页的数据请求会发送到该服务。</p>
            </div>
            <span class="settings-status" :class="`is-${status}`" role="status">
              <i></i>{{ statusLabel }}
            </span>
          </div>

          <form class="settings-form" @submit.prevent="saveAndCheck">
            <label for="backend-url">Java 服务地址</label>
            <div class="settings-url-row">
              <input
                id="backend-url"
                v-model.trim="backendUrl"
                type="url"
                inputmode="url"
                autocomplete="url"
                placeholder="http://localhost:8080"
                spellcheck="false"
                aria-describedby="backend-url-help"
              >
              <button class="button button-primary" type="submit" :disabled="checking">
                {{ checking ? '正在检测…' : '保存并检测' }}
                <svg v-if="!checking"><use href="#i-arrow" /></svg>
              </button>
            </div>
            <p id="backend-url-help" class="settings-help">默认地址为 http://localhost:8080。若后端端口不同，请填写后端实际地址。</p>
          </form>

          <div class="settings-result" :class="`is-${status}`" aria-live="polite">
            <span class="settings-result-mark"><svg><use :href="status === 'connected' ? '#i-check' : '#i-more'" /></svg></span>
            <div>
              <strong>{{ resultTitle }}</strong>
              <p>{{ resultMessage }}</p>
            </div>
            <a v-if="status === 'connected'" :href="probeUrl" target="_blank" rel="noreferrer" class="settings-text-link">查看接口 <span>↗</span></a>
          </div>

          <div class="settings-actions">
            <button class="button button-outline" type="button" :disabled="checking" @click="checkConnection">重新检测</button>
            <button class="settings-reset" type="button" :disabled="checking" @click="resetAddress">恢复默认地址</button>
          </div>
        </section>

        <section class="settings-steps-card">
          <div class="panel-heading">
            <div><div class="eyebrow">START LOCALLY</div><h3>先在电脑上启动 Java 服务</h3></div>
            <span class="settings-step-count">3 STEPS</span>
          </div>
          <ol class="settings-steps">
            <li>
              <span class="settings-step-number">01</span>
              <div><strong>打开项目终端</strong><p>进入项目中的 <code>backend</code> 文件夹。</p></div>
            </li>
            <li>
              <span class="settings-step-number">02</span>
              <div><strong>运行后端</strong><p>在终端执行下面的命令，并保持终端窗口打开。</p><pre><code>mvn spring-boot:run</code></pre></div>
            </li>
            <li>
              <span class="settings-step-number">03</span>
              <div><strong>回到这里检测连接</strong><p>看到“已连接”后，网页就能向本机 Java 服务发送请求。</p></div>
            </li>
          </ol>
          <div class="settings-requirement"><span>运行要求</span><p>网页不能直接启动电脑上的 Java 程序。请手动运行命令；本机需安装 JDK 17 和 Maven，服务默认使用 8080 端口。Chrome 142 及以上版本首次连接时，允许网页访问本地网络。</p></div>
        </section>
      </div>

      <aside class="settings-side">
        <section class="settings-note-card">
          <div class="settings-note-icon"><svg><use href="#i-pin" /></svg></div>
          <div class="eyebrow">THIS DEVICE</div>
          <h3>“本机”指打开网页的这台设备</h3>
          <p>通过 Vercel 链接打开网页时，浏览器会尝试连接当前设备的 localhost。要使用后端，这台设备需要正在运行 Java 服务。</p>
          <div class="settings-note-rule"></div>
          <p class="settings-note-small">其他人打开同一个网页时，localhost 会指向他们自己的设备，不会连接到你的电脑。</p>
        </section>

        <section class="settings-flow-card">
          <div class="eyebrow">CONNECTION FLOW</div>
          <div class="settings-flow">
            <span class="settings-flow-node">网页</span><i></i><span class="settings-flow-node is-accent">本机 Java</span>
          </div>
          <p>前端仍托管在 Vercel；Java 服务只在本机运行。</p>
        </section>
      </aside>
    </div>

    <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>本地服务设置</span><span>产品预览 2026</span></footer>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { getLocalApiBaseUrl, setLocalApiBaseUrl } from '../api/requestContext.js'

const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL || (import.meta.env.PROD ? 'http://localhost:8080' : '')
const defaultBaseUrl = configuredBaseUrl || 'http://localhost:8080'
const backendUrl = ref(getLocalApiBaseUrl() || defaultBaseUrl)
const status = ref('idle')
const checking = ref(false)
const resultMessage = ref('先启动本机 Java 服务，然后点击“保存并检测”。')
const connectedSources = ref(0)

const normalizedUrl = computed(() => normalizeAddress(backendUrl.value) || defaultBaseUrl)
const probeUrl = computed(() => `${normalizedUrl.value}/api/jobs/sources`)
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
    if (payload?.success === false) throw new Error(payload.message || '服务暂不可用')
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
  backendUrl.value = address
  setLocalApiBaseUrl(address)
  await checkConnection()
}

async function resetAddress() {
  setLocalApiBaseUrl('')
  backendUrl.value = defaultBaseUrl
  await checkConnection()
}

</script>

<style scoped>
.settings-page { color: #34463b; }
.settings-local-badge { display: inline-flex; align-items: center; gap: 8px; margin-bottom: 4px; padding: 8px 11px; border: 1px solid #e3e9dc; border-radius: 4px; color: #71836d; background: #f9fbf5; font-size: 9px; white-space: nowrap; }
.settings-local-badge i, .settings-status i { width: 6px; height: 6px; border-radius: 50%; background: #91a875; }
.settings-grid { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(230px, .75fr); align-items: start; gap: 15px; }
.settings-main, .settings-side { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.settings-connection-card, .settings-steps-card, .settings-note-card, .settings-flow-card { border: 1px solid #e5e9e1; border-radius: 5px; background: rgba(255,255,252,.92); }
.settings-connection-card { padding: 23px 24px 18px; }
.settings-card-top { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; padding-bottom: 18px; border-bottom: 1px solid #edf0e9; }
.settings-overline, .settings-step-count { color: #9aa495; font: 8px var(--mono, monospace); letter-spacing: .15em; }
.settings-card-top h2 { margin: 8px 0 5px; color: #2d4236; font-size: 18px; font-weight: 600; letter-spacing: .035em; }
.settings-card-top p { margin: 0; color: #879188; font-size: 10px; line-height: 1.7; }
.settings-status { flex: 0 0 auto; display: inline-flex; align-items: center; gap: 7px; padding: 6px 9px; border: 1px solid #e4e9df; border-radius: 20px; color: #859080; background: #fafbf8; font-size: 9px; }
.settings-status.is-connected { color: #537b5b; border-color: #dce8d9; background: #f4f8f0; }
.settings-status.is-connected i { background: #76a27b; box-shadow: 0 0 0 3px rgba(118,162,123,.12); }
.settings-status.is-disconnected { color: #a3675b; border-color: #efded8; background: #fff8f5; }
.settings-status.is-disconnected i { background: #c68170; }
.settings-status.is-checking i { background: #d2aa61; animation: settings-pulse 1s ease-in-out infinite alternate; }
@keyframes settings-pulse { to { opacity: .35; } }
.settings-form { padding: 19px 0 17px; }
.settings-form label { display: block; margin-bottom: 8px; color: #526257; font-size: 10px; font-weight: 600; }
.settings-url-row { display: flex; gap: 9px; }
.settings-url-row input { flex: 1; min-width: 0; height: 42px; padding: 0 12px; border: 1px solid #dfe5dc; border-radius: 4px; color: #3a5042; background: #fff; font: 11px var(--mono, monospace); }
.settings-url-row input:focus { border-color: #89a18b; outline: 3px solid rgba(126,164,105,.17); }
.settings-url-row .button { min-height: 42px; white-space: nowrap; }
.settings-help { margin: 8px 0 0; color: #99a199; font-size: 9px; line-height: 1.6; }
.settings-result { min-height: 69px; padding: 12px 13px; border: 1px solid #e8ece5; border-radius: 4px; display: flex; align-items: center; gap: 11px; background: #fafbf8; }
.settings-result.is-connected { border-color: #dce8d9; background: #f5f8f1; }
.settings-result.is-disconnected { border-color: #efdfda; background: #fff9f6; }
.settings-result-mark { width: 27px; height: 27px; flex: 0 0 27px; border-radius: 50%; display: grid; place-items: center; color: #879287; background: #edf0e9; }
.settings-result-mark svg { width: 14px; height: 14px; }
.settings-result.is-connected .settings-result-mark { color: #537957; background: #e6eee0; }
.settings-result.is-disconnected .settings-result-mark { color: #a96759; background: #f7e9e4; }
.settings-result > div { flex: 1; min-width: 0; }
.settings-result strong { color: #4c5e50; font-size: 10px; font-weight: 600; }
.settings-result p { margin: 4px 0 0; color: #8b968b; font-size: 9px; line-height: 1.6; overflow-wrap: anywhere; }
.settings-text-link { flex: 0 0 auto; color: #648167; font-size: 9px; }
.settings-text-link span { padding-left: 2px; }
.settings-actions { display: flex; align-items: center; gap: 14px; padding-top: 15px; }
.settings-actions .button { min-height: 34px; font-size: 9px; }
.settings-reset { padding: 7px 0; border: 0; color: #90998e; background: transparent; font-size: 9px; cursor: pointer; }
.settings-reset:hover { color: #546e57; }
.settings-reset:disabled, .settings-actions .button:disabled { opacity: .55; cursor: wait; }
.settings-steps-card { padding: 19px 21px 17px; }
.settings-steps-card .panel-heading { margin-bottom: 14px; }
.settings-steps-card .panel-heading h3 { font-size: 13px; }
.settings-steps { margin: 0; padding: 0; list-style: none; }
.settings-steps li { position: relative; display: grid; grid-template-columns: 30px minmax(0, 1fr); gap: 10px; padding: 12px 0; }
.settings-steps li:not(:last-child)::after { position: absolute; top: 38px; bottom: -1px; left: 14px; width: 1px; background: #e6eae1; content: ""; }
.settings-step-number { position: relative; z-index: 1; width: 28px; height: 28px; border: 1px solid #e3e8de; border-radius: 50%; display: grid; place-items: center; color: #81927d; background: #f8faf5; font: 8px var(--mono, monospace); }
.settings-steps strong { display: block; padding-top: 2px; color: #536355; font-size: 10px; font-weight: 600; }
.settings-steps p { margin: 5px 0 0; color: #929b91; font-size: 9px; line-height: 1.65; }
.settings-steps code { color: #648068; font: 9px var(--mono, monospace); }
.settings-steps pre { margin: 8px 0 1px; padding: 10px 12px; border: 1px solid #e5e9e1; border-radius: 4px; color: #52664f; background: #f6f8f1; overflow-x: auto; }
.settings-steps pre code { font-size: 10px; }
.settings-requirement { margin-top: 9px; padding: 12px 13px; border-left: 2px solid #c1d18e; background: #f8f9f3; }
.settings-requirement span { color: #88957f; font: 8px var(--mono, monospace); letter-spacing: .1em; }
.settings-requirement p { margin: 5px 0 0; color: #7f8b80; font-size: 9px; line-height: 1.7; }
.settings-note-card { padding: 20px 19px 17px; color: #eaf0e6; background: #203f34; border-color: #203f34; }
.settings-note-icon { width: 31px; height: 31px; margin-bottom: 17px; border: 1px solid rgba(230,239,221,.2); border-radius: 5px; display: grid; place-items: center; color: #d5e4a9; background: rgba(245,249,237,.05); }
.settings-note-icon svg { width: 15px; height: 15px; }
.settings-note-card .eyebrow { color: rgba(232,240,224,.57); font-size: 8px; }
.settings-note-card h3 { margin: 12px 0 9px; color: #f1f4ec; font-size: 14px; font-weight: 500; line-height: 1.7; letter-spacing: .02em; }
.settings-note-card p { margin: 0; color: rgba(230,238,223,.7); font-size: 9px; line-height: 1.9; }
.settings-note-rule { height: 1px; margin: 15px 0 12px; background: rgba(231,239,222,.15); }
.settings-note-card p.settings-note-small { color: rgba(230,238,223,.5); font-size: 8px; }
.settings-flow-card { padding: 17px 18px; }
.settings-flow-card .eyebrow { font-size: 8px; }
.settings-flow { display: flex; align-items: center; gap: 8px; margin: 17px 0 10px; }
.settings-flow-node { padding: 7px 9px; border: 1px solid #e5e9e1; border-radius: 3px; color: #647269; background: #fbfcf8; font-size: 9px; }
.settings-flow-node.is-accent { border-color: #dce6cf; color: #53724f; background: #f2f6e9; }
.settings-flow > i { height: 1px; flex: 1; background: #dfe5db; }
.settings-flow-card > p { margin: 0; color: #909a90; font-size: 8px; line-height: 1.7; }
@media (max-width: 900px) { .settings-grid { grid-template-columns: minmax(0, 1fr); } .settings-side { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .settings-local-badge { display: none; } .settings-connection-card { padding: 17px 15px 14px; } .settings-card-top { flex-direction: column; } .settings-url-row { flex-direction: column; } .settings-url-row .button { width: 100%; } .settings-side { grid-template-columns: 1fr; } .settings-steps-card { padding: 16px 14px; } .settings-text-link { display: none; } }
</style>
