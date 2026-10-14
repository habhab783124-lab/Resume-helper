import { getAiSettings, getUserId as sharedUserId } from './requestContext.js'

export function createInterviewApi({ baseUrl = '', fetchImpl = globalThis.fetch, getUserId = sharedUserId } = {}) {
  const root = `${baseUrl.replace(/\/$/, '')}/api`
  async function request(path, { method = 'GET', body, ai, textModel = false, signal } = {}) {
    const headers = { 'X-User-Id': getUserId() }
    ai = textModel ? (ai ?? getAiSettings()) : {}
    if (ai.apiKey?.trim()) headers['X-AI-Api-Key'] = ai.apiKey.trim()
    if (ai.model?.trim()) headers['X-AI-Model'] = ai.model.trim()
    // Only honored server-side when the caller supplies their own key.
    if (ai.baseUrl?.trim()) headers['X-AI-Base-Url'] = ai.baseUrl.trim()
    if (body && !(body instanceof FormData)) {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(body)
    }
    const reply = await fetchImpl(`${root}${path}`, { method, headers, body, credentials: 'include', signal })
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
    if (typeof material.id !== 'string' || !material.id.trim() || typeof detail?.text !== 'string') {
      throw new Error('简历解析结果异常，请重试上传或恢复材料。')
    }
    return {
      materialId: material.id,
      fileName: material.fileName,
      status: material.status,
      text: detail.text
    }
  }

  return {
    checkAi: ai => request('/ai/check', { method: 'POST', ai, textModel: true }),
    upload(file, fileName = file.name, ai) {
      const body = new FormData()
      body.append('file', file, fileName)
      return request('/materials', { method: 'POST', body, ai, textModel: true }).then(toInterviewMaterial)
    },
    getMaterial: id => request(`/materials/${encodeURIComponent(id)}`).then(toInterviewMaterial),
    start: (body, ai) => request('/interviews', { method: 'POST', body, ai, textModel: true }),
    getSession: (id, { signal } = {}) => request(sessionPath(id), { signal }),
    answer: (id, body, ai) => request(`${sessionPath(id)}/turns`, { method: 'POST', body, ai, textModel: true }),
    finish: id => request(`${sessionPath(id)}/finish`, { method: 'POST' }),
    report: (id, ai) => request(`${sessionPath(id)}/report`, { ai, textModel: true }),
    getSpeechCapabilities: () => request('/interview-speech/capabilities'),
    transcribe: (id, { questionId, requestId, file }, signal) => {
      const body = new FormData()
      body.append('questionId', questionId)
      body.append('requestId', requestId)
      body.append('file', file, file.name || 'answer.webm')
      return request(`${sessionPath(id)}/transcriptions`, { method: 'POST', body, signal })
    },
    // 三个语音接口里只有这个是成功返回二进制、失败才返回 JSON，所以不复用 request。
    async questionSpeech(id, questionId, signal) {
      const reply = await fetchImpl(`${root}${sessionPath(id)}/questions/${encodeURIComponent(questionId)}/speech`, {
        method: 'POST',
        headers: { 'X-User-Id': getUserId() },
        credentials: 'include',
        signal,
      })
      if (!reply.ok) {
        let message
        try { message = (await reply.json()).message } catch { message = null }
        throw Object.assign(
          new Error(message || `题目语音生成失败（HTTP ${reply.status}）`),
          { status: reply.status },
        )
      }
      const type = (reply.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase()
      const blob = await reply.blob()
      if (type !== 'audio/mpeg' || blob.size === 0 || blob.size > 5 * 1024 * 1024) {
        throw new Error('题目语音返回了无法播放的内容，请重试。')
      }
      return blob
    }
  }
}
