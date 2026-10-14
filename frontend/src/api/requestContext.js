// Text-model settings stay in this tab. Never put credentials in route queries.
const storageKeys = {
  apiKey: 'resume-helper-ai-key',
  model: 'resume-helper-ai-model',
  baseUrl: 'resume-helper-ai-base-url',
}
let memory = null

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

// Match the existing demo identity used by materials and resume requests.
export function getUserId() { return 'demo-user' }
