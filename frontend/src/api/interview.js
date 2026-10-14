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

  /**
   * 把材料模块的响应归一化成面试需要的样子。
   *
   * 材料接口（功能一）返回的是 `{ material, extraction, text }`，
   * 而面试只需要「id + 简历正文」。在 api 层做这一次映射，
   * 上层组件（Interview.vue）就不用关心材料模块长什么样了。
   *
   * 为什么不在 upload 之后再发一次 /text 请求：一次往返就够了，
   * 而且多一次请求会让 api 的调用时序测试（interview.test.js）失效。
   */
  function toInterviewMaterial(detail) {
    const material = detail?.material ?? {}
    return {
      materialId: material.id,
      fileName: material.fileName,
      status: material.status,
      text: typeof detail?.text === 'string' ? detail.text : ''
    }
  }

  return {
    checkAi: ai => request('/ai/check', { method: 'POST', ai }),
    upload(file, fileName = file.name) {
      const body = new FormData()
      body.append('file', file, fileName)
      return request('/materials', { method: 'POST', body }).then(toInterviewMaterial)
    },
    getMaterial: id => request(`/materials/${encodeURIComponent(id)}`).then(toInterviewMaterial),
    start: (body, ai) => request('/interviews', { method: 'POST', body, ai }),
    getSession: id => request(sessionPath(id)),
    answer: (id, body, ai) => request(`${sessionPath(id)}/turns`, { method: 'POST', body, ai }),
    finish: id => request(`${sessionPath(id)}/finish`, { method: 'POST' }),
    report: (id, ai = {}) => request(`${sessionPath(id)}/report`, { ai })
  }
}