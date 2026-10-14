import http from './client'

/**
 * 简历生成模块的接口封装（功能四，后端见 backend/src/main/java/.../resume/api/）。
 *
 * 和其他 api 模块一样，返回的是**已解包的 data**，失败抛 `ApiError`。
 * AI token 由 client.js 的请求拦截器统一加上。
 */

/**
 * 按当前画像生成一份简历内容。
 * 返回 `{ draft, empty, message }`——画像为空时 `empty` 为 true，`message` 说明该去哪里补。
 */
export function fetchResumeDraft() {
  return http.get('/api/resume')
}

/**
 * 针对目标职位 / 岗位描述要改写建议。
 *
 * 刻意把**当前屏幕上的 draft 一起发过去**：用户可能已经手动改了几处，
 * 建议应当针对他正在看的那一份，而不是后端重新按画像生成的那份。
 *
 * @param {{targetTitle?:string, jd?:string, draft?:object}} payload
 */
export function suggestResume({ targetTitle = '', jd = '', draft = null } = {}) {
  // 大模型要读整份简历 + JD，给它宽一点的超时
  return http.post('/api/resume/suggest', { targetTitle, jd, draft }, { timeout: 120000 })
}

/** 已保存的版本列表（精简信息）。 */
export function listResumeVersions() {
  return http.get('/api/resume/versions')
}

/** 载入某一版，接着改。 */
export function fetchResumeVersion(id) {
  return http.get(`/api/resume/versions/${encodeURIComponent(id)}`)
}

/** 保存为新版本。 */
export function saveResumeVersion({ name = '', targetTitle = '', jd = '', draft }) {
  return http.post('/api/resume/versions', { name, targetTitle, jd, draft })
}

export function deleteResumeVersion(id) {
  return http.delete(`/api/resume/versions/${encodeURIComponent(id)}`)
}
