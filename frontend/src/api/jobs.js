import http from './client'

/**
 * 岗位模块的接口封装（后端见 backend/src/main/java/.../job/api/）。
 *
 * 约定：所有函数返回的是**已经解包过的 data**，失败时抛 `ApiError`。
 * 因为 client.js 的响应拦截器已经把 { success, message, data } 拆开了。
 */

/** 可用岗位数据源与说明。用于在页面上标注「岗位从哪来」。 */
export function fetchSources() {
  return http.get('/api/jobs/sources')
}

/**
 * 按条件搜索岗位，带匹配度。**岗位页的主接口**。
 *
 * @param {object} payload
 * @param {string} [payload.keyword]      关键词，例如「Java」
 * @param {string} [payload.city]         城市
 * @param {string} [payload.jobType]      全职 / 校招 / 实习
 * @param {number} [payload.maxYears]     岗位最低年限上限（应届生传 1）
 * @param {boolean} [payload.campusOnly]  只看校招/应届友好岗位
 * @param {boolean} [payload.strictCity]  城市是否作为硬筛选条件
 * @param {number} [payload.limit]        返回条数
 * @param {number} [payload.explainTopN]  前 N 个岗位调用大模型生成解释；0 表示不调用（快）
 * @param {object} payload.profile        用户画像（见 utils/demoProfile.js）
 */
export function searchJobs(payload) {
  return http.post('/api/jobs/search', payload)
}

/**
 * 让智能体自己决定怎么找（多轮：规划关键词 → 检索 → 观察 → 换词扩检）。
 * 返回体里带 `trace` 执行轨迹，可以渲染成时间线。
 */
export function runJobAgent({ profile, options } = {}) {
  return http.post('/api/jobs/agent/run', { profile, options })
}

/** 不带画像的岗位列表（不做匹配打分）。 */
export function listJobs(params = {}) {
  return http.get('/api/jobs', { params })
}

/**
 * 粘贴岗位描述导入（没有爬虫数据时的兜底）。
 * @param {{ text: string, sourceUrl?: string }} payload
 */
export function importJob({ text, sourceUrl = '' }) {
  return http.post('/api/jobs/import', { text, sourceUrl })
}

/** 岗位详情（含完整岗位描述）。 */
export function fetchJobDetail(jobId, userId) {
  return http.get(`/api/jobs/${jobId}`, { params: { userId } })
}

/**
 * 计算单个岗位的匹配度与技能差距报告。
 * @param {string} jobId
 * @param {{ profile: object, explainAi?: boolean }} payload explainAi=false 时完全不调用大模型
 */
export function matchJob(jobId, { profile, explainAi = true } = {}) {
  return http.post(`/api/jobs/${jobId}/match`, { profile, explainAi })
}

export function addFavorite(jobId, userId) {
  return http.post(`/api/jobs/${jobId}/favorite`, null, { params: { userId } })
}

export function removeFavorite(jobId, userId) {
  return http.delete(`/api/jobs/${jobId}/favorite`, { params: { userId } })
}

export function fetchFavorites(userId) {
  return http.get('/api/jobs/favorites', { params: { userId } })
}
