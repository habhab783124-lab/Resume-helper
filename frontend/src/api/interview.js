import { getAiSettings, getSpeechSettings, getLocalApiBaseUrl, getUserId as sharedUserId } from './requestContext.js'

export function createInterviewApi({ baseUrl = '', fetchImpl = globalThis.fetch, getUserId = sharedUserId,
  requestTimeouts = { read: 10000, write: 15000 } } = {}) {
  const root = () => `${(getLocalApiBaseUrl() || baseUrl).replace(/\/$/, '')}/api`

  // 语音 key 只对语音接口生效：后端按请求解析它，服务端配置兜底。
  function speechHeaders() {
    const { speechKey } = getSpeechSettings()
    return speechKey ? { 'X-Speech-Api-Key': speechKey } : {}
  }

  async function request(path, { method = 'GET', body, ai, textModel = false, signal, speech = false, timeoutMs } = {}) {
    const headers = { 'X-User-Id': getUserId(), ...(speech ? speechHeaders() : {}) }
    ai = textModel ? (ai ?? getAiSettings()) : {}
    if (ai.apiKey?.trim()) headers['X-AI-Api-Key'] = ai.apiKey.trim()
    if (ai.model?.trim()) headers['X-AI-Model'] = ai.model.trim()
    // Only honored server-side when the caller supplies their own key.
    if (ai.baseUrl?.trim()) headers['X-AI-Base-Url'] = ai.baseUrl.trim()
    if (body && !(body instanceof FormData)) {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(body)
    }
    const controller = timeoutMs ? new AbortController() : null
    let timedOut = false
    let timer
    const abort = () => controller?.abort(signal?.reason)
    if (controller && signal) {
      if (signal.aborted) abort()
      else signal.addEventListener('abort', abort, { once: true })
    }
    if (controller) timer = setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs)
    let reply
    try {
      reply = await fetchImpl(`${root()}${path}`, { method, headers, body, credentials: 'include', signal: controller?.signal ?? signal })
    } catch (cause) {
      if (timedOut) throw Object.assign(new Error('浏览器请求未确认，请读取会话状态后再决定是否重试'), { code: 'BROWSER_UNCONFIRMED', retryable: true, cause })
      throw cause
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener('abort', abort)
    }
    let envelope
    try { envelope = await reply.json() } catch {
      throw Object.assign(new Error(`服务返回了无效响应（HTTP ${reply.status}）`), { status: reply.status })
    }
    if (!reply.ok || envelope?.success !== true || envelope.data == null) {
      throw Object.assign(new Error(envelope?.message || `请求失败（HTTP ${reply.status}）`), {
        status: reply.status, code: envelope?.code, retryable: envelope?.retryable
      })
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
    checkSpeech: () => request('/interview-speech/check', { method: 'POST', speech: true }),
    upload(file, fileName = file.name, ai) {
      const body = new FormData()
      body.append('file', file, fileName)
      return request('/materials', { method: 'POST', body, ai, textModel: true }).then(toInterviewMaterial)
    },
    getMaterial: id => request(`/materials/${encodeURIComponent(id)}`).then(toInterviewMaterial),
    previewSource: source => request('/interviews/source/preview', { method: 'POST', body: { source } }),
    preparePlan: (body, signal) => request('/interviews/plans', { method: 'POST', body, textModel: true, signal, timeoutMs: requestTimeouts.write }),
    preparePracticePlan: (id, body, signal) => request(`${sessionPath(id)}/practice-plans`, { method: 'POST', body, textModel: true, signal, timeoutMs: requestTimeouts.write }),
    getPlan: (id, signal) => request(`/interviews/plans/${encodeURIComponent(id)}`, { signal, timeoutMs: requestTimeouts.read }),
    startPlanned: body => request('/interviews', { method: 'POST', body: { allowAlgorithmRepeat: false, ...body, async: true }, textModel: true, timeoutMs: requestTimeouts.write }),
    start: (body, ai) => request('/interviews', { method: 'POST', body, ai, textModel: true, timeoutMs: requestTimeouts.write }),
    getSession: (id, { signal } = {}) => request(sessionPath(id), { signal, timeoutMs: requestTimeouts.read }),
    listHistory: ({ limit = 20, cursor, signal } = {}) => request(`/interviews?${new URLSearchParams({ limit: String(limit), ...(cursor ? { cursor } : {}) })}`, { signal, timeoutMs: requestTimeouts.read }),
    deleteSession: id => request(sessionPath(id), { method: 'DELETE', timeoutMs: requestTimeouts.write }),
    saveAnswer: (id, submission, signal) => request(`${sessionPath(id)}/answers`, { method: 'POST', body: submission, textModel: true, signal, timeoutMs: requestTimeouts.write }),
    generate: (id, body, signal) => request(`${sessionPath(id)}/generations`, { method: 'POST', body, textModel: true, signal, timeoutMs: requestTimeouts.write }),
    skip: (id, body, signal) => request(`${sessionPath(id)}/skip`, { method: 'POST', body, signal, timeoutMs: requestTimeouts.write }),
    practiceFeedback: (id, body, signal) => request(`${sessionPath(id)}/feedback`, { method: 'POST', body, textModel: true, signal, timeoutMs: requestTimeouts.write }),
    submitAttempt: (id, body, signal) => request(`${sessionPath(id)}/attempts`, { method: 'POST', body, textModel: true, signal, timeoutMs: requestTimeouts.write }),
    listAttempts: (id, questionId, signal) => request(`${sessionPath(id)}/attempts?questionId=${encodeURIComponent(questionId)}`, { signal, timeoutMs: requestTimeouts.read }),
    getAttempt: (id, attemptId, signal) => request(`${sessionPath(id)}/attempts/${encodeURIComponent(attemptId)}`, { signal, timeoutMs: requestTimeouts.read }),
    retryAttempt: (id, attemptId, body = {}, signal) => request(`${sessionPath(id)}/attempts/${encodeURIComponent(attemptId)}/retry`, { method: 'POST', body, textModel: true, signal, timeoutMs: requestTimeouts.write }),
    clarify: (id, body, signal) => request(`${sessionPath(id)}/clarifications`, { method: 'POST', body, textModel: true, signal, timeoutMs: requestTimeouts.write }),
    replace: (id, body, signal) => request(`${sessionPath(id)}/replacements`, { method: 'POST', body, textModel: true, signal, timeoutMs: requestTimeouts.write }),
    answer: (id, body, ai) => request(`${sessionPath(id)}/turns`, { method: 'POST', body, ai, textModel: true }),
    finish: (id, body, signal) => request(`${sessionPath(id)}/finish`, { method: 'POST', body, signal, timeoutMs: requestTimeouts.write }),
    report: (id, options) => {
      const opts = options || {}
      const isAsyncOptions = Object.hasOwn(opts, 'async') || Object.hasOwn(opts, 'signal') || Object.hasOwn(opts, 'ai')
      const async = isAsyncOptions && opts.async === true
      return request(`${sessionPath(id)}/report${async ? '?async=true' : ''}`, {
        ai: isAsyncOptions ? opts.ai : options, signal: isAsyncOptions ? opts.signal : undefined, textModel: true,
      })
    },
    retryReport: (id, options) => {
      const opts = options || {}
      const isAsyncOptions = opts.async === true || Object.hasOwn(opts, 'requestId') || Object.hasOwn(opts, 'ai')
      return request(`${sessionPath(id)}/report/retry`, {
        method: 'POST', body: isAsyncOptions && opts.async ? { async: true, requestId: opts.requestId } : undefined,
        ai: isAsyncOptions ? opts.ai : options, textModel: true,
      })
    },
    retryReview: (id, body) => request(`${sessionPath(id)}/report/review/retry`, {
      method: 'POST', body, textModel: true, timeoutMs: requestTimeouts.write,
    }),
    getSpeechCapabilities: () => request('/interview-speech/capabilities', { speech: true }),
    transcribe: (id, { questionId, requestId, file }, signal) => {
      const body = new FormData()
      body.append('questionId', questionId)
      body.append('requestId', requestId)
      body.append('file', file, file.name || 'answer.webm')
      return request(`${sessionPath(id)}/transcriptions`, { method: 'POST', body, signal, speech: true })
    },
    // 三个语音接口里只有这个是成功返回二进制、失败才返回 JSON，所以不复用 request。
    async questionSpeech(id, questionId, signal) {
      const reply = await fetchImpl(`${root()}${sessionPath(id)}/questions/${encodeURIComponent(questionId)}/speech`, {
        method: 'POST',
        headers: { 'X-User-Id': getUserId(), ...speechHeaders() },
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
