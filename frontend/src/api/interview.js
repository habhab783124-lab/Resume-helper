export function createInterviewApi({ baseUrl = '', fetchImpl = globalThis.fetch } = {}) {
  const root = `${baseUrl.replace(/\/$/, '')}/api`
  async function request(path, { method = 'GET', body, ai = {} } = {}) {
    const headers = {}
    if (ai.apiKey?.trim()) headers['X-AI-Api-Key'] = ai.apiKey.trim()
    if (ai.model?.trim()) headers['X-AI-Model'] = ai.model.trim()
    // Only honored server-side when the caller supplies their own key.
    if (ai.baseUrl?.trim()) headers['X-AI-Base-Url'] = ai.baseUrl.trim()
    if (body && !(body instanceof FormData)) {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(body)
    }
    const reply = await fetchImpl(`${root}${path}`, { method, headers, body, credentials: 'include' })
    let envelope
    try { envelope = await reply.json() } catch {
      throw Object.assign(new Error(`服务返回了无效响应（HTTP ${reply.status}）`), { status: reply.status })
    }
    if (!reply.ok || envelope?.success !== true || envelope.data == null) {
      throw Object.assign(new Error(envelope?.message || `请求失败（HTTP ${reply.status}）`), { status: reply.status })
    }
    return envelope.data
  }
  const sessionPath = id => `/interviews/${encodeURIComponent(id)}`
  return {
    checkAi: ai => request('/ai/check', { method: 'POST', ai }),
    upload(file, fileName = file.name) {
      const body = new FormData()
      body.append('file', file, fileName)
      return request('/materials', { method: 'POST', body })
    },
    getMaterial: id => request(`/materials/${encodeURIComponent(id)}`),
    start: (body, ai) => request('/interviews', { method: 'POST', body, ai }),
    getSession: id => request(sessionPath(id)),
    answer: (id, body, ai) => request(`${sessionPath(id)}/turns`, { method: 'POST', body, ai }),
    finish: id => request(`${sessionPath(id)}/finish`, { method: 'POST' }),
    report: (id, ai = {}) => request(`${sessionPath(id)}/report`, { ai })
  }
}