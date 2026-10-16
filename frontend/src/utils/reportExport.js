import { isSpecialty, specialtyLabel } from '../interview/specialtyPresentation.js'
import { algorithmUrl } from '../interview/algorithmSource.js'

// 与 Report.vue 内联的小映射表保持一致；这些映射目前内联在组件里无法 import，
// 在这里复制几行极小映射，保持模块自洽（避免为单次复用做抽象提取）。
const DIMENSIONS = [
  ['CORRECTNESS', '切题与正确性'], ['COVERAGE', '关键要点覆盖'], ['REASONING', '推理与取舍'],
  ['EVIDENCE', '证据与具体性'], ['CLARITY', '表达结构'],
]
const DIFFICULTY_LABEL = { BASIC: '基础', INTERMEDIATE: '进阶', ADVANCED: '综合' }
const IMPORTANCE_LABEL = { CORE: '核心', RELATED: '相关', SUPPLEMENT: '补充' }
const ITEM_LABEL = { SCORED: '已评分', PENDING: '待评分', UNANSWERED: '未作答' }

const difficultyLabel = v => DIFFICULTY_LABEL[v] || '未分类'
const importanceLabel = v => IMPORTANCE_LABEL[v] || '未分类'
const itemLabel = v => ITEM_LABEL[v] || '待评分'
const formatNumber = v => Number.isFinite(v) ? v.toFixed(2).replace(/\.?0+$/, '') : '—'
const isScored = fb => fb.status === 'SCORED' && Number.isFinite(fb.score)

function hasOverallScore(r) {
  return r?.reportStatus === 'COMPLETE'
    && r?.answeredCount === r?.plannedCount
    && Number.isFinite(r?.overallScore)
}

function scoreLevel(score) {
  if (score >= 85) return '优秀 · 可以自信展现'
  if (score >= 75) return '良好 · 有清晰的基础'
  if (score >= 60) return '合格 · 方向对了，继续打磨'
  return '需要加强 · 把建议落到实处'
}

function statusLabel(r) {
  const base = {
    COMPLETE: hasOverallScore(r) ? '评分已完成' : (r?.answeredCount > 0 ? '逐题反馈已完成' : '暂无评分'),
    PARTIAL: (r?.turnFeedbacks || []).some(t => t.status === 'PENDING') ? '待评分' : '逐题反馈已完成',
    UNAVAILABLE: '评分暂不可用',
    EMPTY: '暂无评分',
  }[r?.reportStatus] || '暂无评分'
  return base
}

function questionLabel(fb) {
  return isSpecialty(fb.kind)
    ? `${specialtyLabel(fb.kind)} · 固定权重 20%`
    : `主问题 ${String(fb.mainQuestionIndex || fb.index).padStart(2, '0')}`
}

function stamp() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`
}

function safeRole(role) {
  return (role || '综合模拟').toString().replace(/[\\/$:?*"<>|]/g, '')
}

function reviewedTurns(report, session) {
  const saved = new Map((session?.turns || []).map(t => [t.questionId, t]))
  return (report?.turnFeedbacks || []).map(fb => ({
    ...fb,
    question: fb.question ?? saved.get(fb.questionId)?.question,
    answer: fb.answer ?? saved.get(fb.questionId)?.answer,
  }))
}

const bulletList = (items, empty = '暂无有效反馈。') =>
  (items?.length ? items : []).length
    ? (items.map(i => `- ${i}`).join('\n'))
    : `- ${empty}`

/**
 * 把面试报告数据序列化为 Markdown 字符串。纯函数，可单测。
 * @param {object} report 后端 report 接口返回的报告对象
 * @param {{session?: object, role?: string}} [opts] session 用于回填 question/answer，role 为目标岗位
 */
export function buildReportMarkdown(report, { session, role } = {}) {
  const r = report || {}
  const title = safeRole(role)
  const lines = []

  lines.push(`# 面试报告 · ${title}`, '')
  const meta = [
    `状态：${statusLabel(r)}`,
    `已答 ${r.answeredCount ?? 0}/${r.plannedCount ?? 0} 题`,
    `已评分 ${r.scoredCount ?? 0} 题`,
  ]
  if (r.aiModel) meta.push(`评分模型：${r.aiModel}`)
  if (r.scoringVersion) meta.push(`评分版本：${r.scoringVersion}`)
  meta.push(`生成时间：${new Date().toLocaleString('zh-CN')}`)
  lines.push(`> ${meta.join('  ·  ')}`, '')

  if (hasOverallScore(r)) {
    lines.push(`## 综合训练参考分`, '')
    lines.push(`**${formatNumber(r.overallScore)} / 100** — ${scoreLevel(Number(r.overallScore))}`, '')
    if (r.overallSummary) lines.push(r.overallSummary, '')
  }

  if (r.strengths?.length) {
    lines.push('## 做得不错的地方', '', bulletList(r.strengths), '')
  }
  if (r.gaps?.length) {
    lines.push('## 下一次可以加强', '', bulletList(r.gaps), '')
  }
  if (r.suggestions?.length) {
    lines.push('## 下一步练习建议', '', bulletList(r.suggestions), '')
  }

  const turns = reviewedTurns(r, session)
  if (turns.length) {
    lines.push('## 逐题反馈', '')
    turns.forEach(fb => {
      lines.push(`### ${String(fb.index).padStart(2, '0')} · ${questionLabel(fb)}`, '')
      if (fb.kind === 'ALGORITHM' && fb.algorithm?.title) {
        const url = algorithmUrl(fb.algorithm)
        lines.push(`> Hot 100 原题 · 固定权重 20%  `, `> ${fb.algorithm.title}${url ? ` ([查看原题](${url}))` : ''}`, '')
      }
      lines.push('**题目**', '', fb.question || '（题目缺失）', '')
      lines.push('**原回答**', '', fb.answer ?? '尚未回答', '')

      const statusLine = []
      statusLine.push(`状态：${itemLabel(fb.status)}`)
      if (isScored(fb)) {
        statusLine.push(`得分：${formatNumber(fb.score)} / 100`)
        if (Number.isFinite(fb.weightedContribution)) statusLine.push(`加权贡献：${formatNumber(fb.weightedContribution)} 分`)
      }
      lines.push(`*${statusLine.join('  ·  ')}*`)

      const weightLine = []
      if (Number.isFinite(fb.weightPercent)) weightLine.push(`权重：${formatNumber(fb.weightPercent)}%`)
      if (fb.weightReason) weightLine.push(`权重依据：${fb.weightReason}`)
      if (!isSpecialty(fb.kind)) weightLine.push(`相对权重点数：${fb.weightUnits ?? 0}`)
      if (weightLine.length) lines.push(`*${weightLine.join('  ·  ')}*`, '')

      if (fb.competency || fb.importance || fb.difficulty) {
        const tags = []
        if (fb.competency) tags.push(fb.competency)
        if (fb.importance) tags.push(importanceLabel(fb.importance))
        if (fb.difficulty) tags.push(difficultyLabel(fb.difficulty))
        lines.push(`*能力维度：${tags.join(' · ')}*`, '')
      }

      if (isScored(fb)) {
        const dims = DIMENSIONS
          .map(([key, label]) => `${label} ${Number.isFinite(fb.dimensionScores?.[key]) ? fb.dimensionScores[key] : '—'} / 20`)
          .join('  ·  ')
        lines.push(`**五维得分**：${dims}`, '')
        if (fb.evidence?.length) lines.push('**原文证据**', '', bulletList(fb.evidence), '')
        if (fb.missingPoints?.length) lines.push('**缺失要点与扣分依据**', '', bulletList(fb.missingPoints), '')
        if (fb.highlight) lines.push(`**✦ 回答亮点**：${fb.highlight}`)
        if (fb.improvement) lines.push(`**↗ 改进动作**：${fb.improvement}`)
        if (fb.suggestedOutline) lines.push('**复练回答框架**', '', fb.suggestedOutline, '')
      } else {
        lines.push(fb.status === 'UNANSWERED'
          ? '> 本题未作答，权重保留；尚无综合评分。'
          : '> 本题待评分，原回答已保留。', '')
      }

      if (fb.followUps?.length) {
        lines.push('**追问与补充回答**（并入本题评分）', '')
        fb.followUps.forEach((fu, i) => {
          lines.push(`- 追问 ${i + 1}：${fu.question}`, `  - 补充回答：${fu.answer ?? '尚未回答'}`)
        })
        lines.push('')
      } else {
        lines.push('')
      }
    })
  }

  return lines.join('\n').replace(/\n{3,}/g, '\n\n').trim() + '\n'
}

function downloadBlob(content, filename, type) {
  const blob = new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** 触发 Markdown 文件下载。 */
export function downloadReportMarkdown(report, { session, role } = {}) {
  const md = buildReportMarkdown(report, { session, role })
  downloadBlob(md, `面试报告-${safeRole(role)}-${stamp()}.md`, 'text/markdown;charset=utf-8')
}

/**
 * 把已渲染的报告 DOM 截图分页导出为 PDF。
 * @param {HTMLElement} reportEl 报告根元素（#page-report）
 * @param {object} opts expand/restore details 的两个 helper + role
 */
export async function exportReportToPdf(reportEl, { role, expandDetails, restoreDetails } = {}) {
  if (!reportEl) throw new Error('报告尚未渲染，无法导出 PDF')
  const [{ default: html2canvas }, { jsPDF }] = await Promise.all([
    import('html2canvas'),
    import('jspdf'),
  ])
  if (typeof expandDetails === 'function') expandDetails()
  reportEl.classList.add('is-exporting')
  try {
    const canvas = await html2canvas(reportEl, {
      scale: 2,
      useCORS: true,
      backgroundColor: '#ffffff',
      logging: false,
    })
    const imgData = canvas.toDataURL('image/jpeg', 0.92)
    const pdf = new jsPDF('p', 'mm', 'a4')
    const pageW = 210
    const pageH = 297
    const imgH = (canvas.height * pageW) / canvas.width
    let heightLeft = imgH
    let position = 0
    pdf.addImage(imgData, 'JPEG', 0, position, pageW, imgH)
    heightLeft -= pageH
    while (heightLeft > 0) {
      position -= pageH
      pdf.addPage()
      pdf.addImage(imgData, 'JPEG', 0, position, pageW, imgH)
      heightLeft -= pageH
    }
    pdf.save(`面试报告-${safeRole(role)}-${stamp()}.pdf`)
  } finally {
    reportEl.classList.remove('is-exporting')
    if (typeof restoreDetails === 'function') restoreDetails()
  }
}
