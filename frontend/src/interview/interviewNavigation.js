/**
 * 面试入口的跳转语义。
 *
 * 面试页在没有明确意图时会自动恢复上次场次。所以「再练习一次」这类
 * 动作必须显式带上 intent=new，否则用户点了"再练"却落回上一轮已完成的记录。
 */

/** 开始一轮全新练习的路由；sourceSessionId 只用于读取上一轮的简历和岗位。 */
export function newInterviewRoute(sourceSessionId) {
  return { name: 'Interview', query: {
    intent: 'new', ...(sourceSessionId ? { sourceSessionId } : {}),
  } }
}

/** 只有显式声明 intent=new 才跳过自动恢复。 */
export const isNewInterviewRoute = route => route?.query?.intent === 'new'

/** 从路由里取出来源场次 id；空串或非法值一律当没有来源。 */
export function sourceSessionId(route) {
  const value = route?.query?.sourceSessionId
  return typeof value === 'string' && value.trim() ? value.trim() : ''
}
