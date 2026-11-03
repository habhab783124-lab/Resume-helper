export const DEFAULT_JOB_LIMIT = 60
export const JOB_LIMITS = [30, 60, 100, 200]
export const EXPERIENCE_OPTIONS = [
  { label: '经验不限', maxYears: null },
  { label: '应届 / 1 年以内', maxYears: 1 },
  { label: '3 年以内', maxYears: 3 }
]

/** 搜索和智能体使用同一组显式筛选，避免界面条件与实际请求不一致。 */
export function jobSearchPayload({ keyword = '', city = '', maxYears = null, limit = DEFAULT_JOB_LIMIT, profile }) {
  return { keyword: keyword.trim(), city, strictCity: Boolean(city), maxYears,
    limit: JOB_LIMITS.includes(Number(limit)) ? Number(limit) : DEFAULT_JOB_LIMIT,
    explainTopN: 0, profile }
}

/** 金额由后端统一为千元/月，不能拿原文中的首个数字比较日薪、月薪和年薪。 */
export function sortJobCards(jobs, order = 'score') {
  const value = job => order === 'salary'
    ? Number(job.salaryMin) || Number(job.salaryMax) || -1
    : job.score ?? -1
  return [...jobs].sort((a, b) => value(b) - value(a))
}

export function jobSourceLabel(sources = []) {
  return sources.filter(s => s.ok || s.message !== '已通过配置关闭')
    .map(s => `${s.name}（${s.ok ? `返回 ${s.count || 0} 条` : '暂不可用'}）`).join(' + ') || '—'
}
