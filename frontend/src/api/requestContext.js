// Text-model settings stay in this tab. Never put credentials in route queries.
const storageKeys = {
  apiKey: 'resume-helper-ai-key',
  model: 'resume-helper-ai-model',
  baseUrl: 'resume-helper-ai-base-url',
}
const localApiBaseUrlKey = 'resume-helper-local-api-base-url'
let memory = null
let localApiBaseUrlMemory = null

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
}

// Match the existing demo identity used by materials and resume requests.
export function getUserId() { return 'demo-user' }
