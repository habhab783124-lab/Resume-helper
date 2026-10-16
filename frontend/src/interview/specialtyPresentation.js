export const isSpecialty = kind => kind === 'ALGORITHM' || kind === 'CASE_STUDY'

export const specialtyLabel = kind => ({ ALGORITHM: '算法题 · Hot 100', CASE_STUDY: '专业案例题' })[kind] || '岗位专项题'

export function answerGuidance(kind) {
  if (kind === 'ALGORITHM') return '写出思路、代码或伪代码、复杂度与边界情况'
  if (kind === 'CASE_STUDY') return '围绕案例事实，说明分析、专业方法、取舍与验证方案'
  return '围绕背景、行动与结果组织表达'
}
