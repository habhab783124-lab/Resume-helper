/**
 * 面试入口的跳转语义。
 *
 * 面试页在没有明确意图时会自动恢复上次场次。所以「再练习一次」这类
 * 动作必须显式带上 intent=new，否则用户点了"再练"却落回上一轮已完成的记录。
 */

/** 开始一轮全新练习的路由；只在 URL 里传来源 ID，不传简历、JD 或凭据。 */
export function newInterviewRoute(source) {
  if (source && typeof source === 'object'
      && ['materialId', 'resumeVersionId', 'jobId'].some(key => Object.hasOwn(source, key))) {
    const ids = {}
    for (const key of ['materialId', 'resumeVersionId', 'jobId']) {
      if (typeof source[key] === 'string' && source[key].trim()) ids[key] = source[key].trim()
    }
    return { name: 'Interview', query: { intent: 'new', ...ids } }
  }
  const session = typeof source === 'string' ? source
    : typeof source?.value === 'string' ? source.value : ''
  return { name: 'Interview', query: {
    intent: 'new', ...(session.trim() ? { sourceSessionId: session.trim() } : {}),
  } }
}

/** Continue a server-prepared focused plan; URLs carry IDs only, never report text or answers. */
export function focusedPracticeRoute(planId, originatingSessionId) {
  const plan = typeof planId === 'string' ? planId.trim() : ''
  if (!plan) return newInterviewRoute(originatingSessionId)
  const session = typeof originatingSessionId === 'string' ? originatingSessionId.trim() : ''
  return { name: 'Interview', query: { intent: 'new', planId: plan, ...(session ? { sourceSessionId: session } : {}) } }
}

/** 只有显式声明 intent=new 才跳过自动恢复。 */
export const isNewInterviewRoute = route => route?.query?.intent === 'new'

/** 从路由里取出来源场次 id；空串或非法值一律当没有来源。 */
export function sourceSessionId(route) {
  const value = route?.query?.sourceSessionId
  return typeof value === 'string' && value.trim() ? value.trim() : ''
}
