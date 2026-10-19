import { getAuthToken as readAuthToken, getAuthUser } from '../utils/authSession.js'

// Text-model settings stay in this tab. Never put credentials in route queries.
const storageKeys = {
  apiKey: 'resume-helper-ai-key',
  model: 'resume-helper-ai-model',
  baseUrl: 'resume-helper-ai-base-url',
}
const localApiBaseUrlKey = 'resume-helper-local-api-base-url'
let localApiBaseUrlMemory = null
let memory = null
globalThis.addEventListener?.('storage', event => {
  if (event.key === localApiBaseUrlKey) localApiBaseUrlMemory = String(event.newValue || '').trim()
})

export function getAiSettings() {
  if (memory) return { ...memory }
  const settings = {}
  for (const [field, key] of Object.entries(storageKeys)) {
    try { settings[field] = globalThis.sessionStorage?.getItem(key) || '' }
    catch { settings[field] = '' }
  }
  return settings
}

export function setAiSettings(settings = {}) {
  // Memory is authoritative after a write, even when storage rejects clearing.
  memory = Object.fromEntries(Object.keys(storageKeys).map(field => [field, String(settings[field] || '').trim()]))
  for (const [field, key] of Object.entries(storageKeys)) {
    try {
      if (memory[field]) globalThis.sessionStorage?.setItem(key, memory[field])
      else globalThis.sessionStorage?.removeItem(key)
    } catch { /* Storage is optional; the current tab still retains its settings. */ }
  }
}

// ---------------------------------------------------------------------------
// 语音密钥单独一组，不塞进上面的大模型设置里。
//
// 两套凭据走的是不同的后端配置和不同的请求头，混在同一个对象里会让
// 「设置页改的是哪个」变成靠字段名猜。这里复用同一套 memory 优先的写法。
// ------------------------------------------------------------------------- */
const speechStorageKey = 'resume-helper-speech-key'
let speechMemory = null

export function getSpeechSettings() {
  if (speechMemory) return { ...speechMemory }
  let key = ''
  try { key = globalThis.sessionStorage?.getItem(speechStorageKey) || '' }
  catch { /* Storage is optional; the current tab still retains its key. */ }
  return { speechKey: key.trim() }
}

export function setSpeechSettings(settings = {}) {
  speechMemory = { speechKey: String(settings.speechKey || '').trim() }
  try {
    if (speechMemory.speechKey) globalThis.sessionStorage?.setItem(speechStorageKey, speechMemory.speechKey)
    else globalThis.sessionStorage?.removeItem(speechStorageKey)
  } catch { /* Storage is optional; the current tab still retains its key. */ }
  try { globalThis.dispatchEvent?.(new Event('speech-settings-change')) } catch { /* Non-browser runtimes have no event target. */ }
}

/** The local Java service address is a browser preference, separate from AI credentials. */
export function getLocalApiBaseUrl() {
  if (localApiBaseUrlMemory !== null) return localApiBaseUrlMemory
  try { return globalThis.localStorage?.getItem(localApiBaseUrlKey)?.trim() || '' }
  catch { return '' }
}

export function setLocalApiBaseUrl(url = '') {
  localApiBaseUrlMemory = String(url || '').trim()
  try {
    if (localApiBaseUrlMemory) globalThis.localStorage?.setItem(localApiBaseUrlKey, localApiBaseUrlMemory)
    else globalThis.localStorage?.removeItem(localApiBaseUrlKey)
  } catch { /* The active tab still uses the in-memory address. */ }
  try { globalThis.dispatchEvent?.(new Event('request-context-change')) } catch { /* Non-browser runtimes have no event target. */ }
}

/** 当前登录用户 id。未登录时保留 demo-user，兼容旧的原型页面与脚本。 */
export function getUserId() { return getAuthUser()?.id || 'demo-user' }

/** 当前登录令牌；未登录时为空字符串。 */
export function getAuthToken() { return readAuthToken() }
