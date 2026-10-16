<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createInterviewApi } from '../api/interview.js'
import { algorithmUrl } from '../interview/algorithmSource.js'
import { newInterviewRoute } from '../interview/interviewNavigation.js'
import { isSpecialty, specialtyLabel } from '../interview/specialtyPresentation.js'
import { downloadReportMarkdown, exportReportToPdf } from '../utils/reportExport.js'

const route = useRoute()
const router = useRouter()
const api = createInterviewApi({ baseUrl: import.meta.env.VITE_API_BASE_URL || (import.meta.env.PROD ? 'http://localhost:8080' : '') })

const loading = ref(true)
const retryBusy = ref(false)
const error = ref('')
const report = ref(null)
const sessionRole = ref('综合模拟')
const session = ref(null)
const warning = ref('')

const sessionId = computed(() => typeof route.query.sessionId === 'string' ? route.query.sessionId.trim() : '')

const hasOverallScore = computed(() => report.value?.reportStatus === 'COMPLETE' && report.value?.answeredCount === report.value?.plannedCount && Number.isFinite(report.value?.overallScore))
const needsScoring = computed(() => (report.value?.turnFeedbacks || []).some(item => item.status === 'PENDING'))
const statusLabel = computed(() => ({ COMPLETE: hasOverallScore.value ? '评分已完成' : report.value?.answeredCount > 0 ? '逐题反馈已完成' : '暂无评分', PARTIAL: needsScoring.value ? '待评分' : '逐题反馈已完成', UNAVAILABLE: '评分暂不可用', EMPTY: '暂无评分' })[report.value?.reportStatus] || '暂无评分')
const scopeLabel = computed(() => {
  const value = report.value
  if (!value) return ''
  const scope = `已答 ${value.answeredCount}/${value.plannedCount} 题`
  if (!value.answeredCount) return `${scope}，尚无可评分内容`
  return value.answeredCount < value.plannedCount ? `${scope}，提前结束；仅展示已答题反馈，暂无综合分。` : `${scope}，已完成计划题目；追问并入主问题。`
})
const reviewedTurns = computed(() => {
  const saved = new Map((session.value?.turns || []).map(turn => [turn.questionId, turn]))
  return (report.value?.turnFeedbacks || []).map(feedback => ({ ...feedback,
    question: feedback.question ?? saved.get(feedback.questionId)?.question,
    answer: feedback.answer ?? saved.get(feedback.questionId)?.answer,
  }))
})
const dimensions = [
  ['CORRECTNESS', '切题与正确性'], ['COVERAGE', '关键要点覆盖'], ['REASONING', '推理与取舍'],
  ['EVIDENCE', '证据与具体性'], ['CLARITY', '表达结构'],
]
const difficultyLabel = value => ({ BASIC: '基础', INTERMEDIATE: '进阶', ADVANCED: '综合' })[value] || '未分类'
const importanceLabel = value => ({ CORE: '核心', RELATED: '相关', SUPPLEMENT: '补充' })[value] || '未分类'
const itemLabel = value => ({ SCORED: '已评分', PENDING: '待评分', UNANSWERED: '未作答' })[value] || '待评分'
const isScored = feedback => feedback.status === 'SCORED' && Number.isFinite(feedback.score)
const formatNumber = value => Number.isFinite(value) ? value.toFixed(2).replace(/\.?0+$/, '') : '—'
const questionLabel = feedback => isSpecialty(feedback.kind) ? `${specialtyLabel(feedback.kind)} · 固定权重 20%` : `主问题 ${String(feedback.mainQuestionIndex || feedback.index).padStart(2, '0')}`

const scoreLevel = computed(() => {
  if (!hasOverallScore.value) return ''
  const s = report.value.overallScore
  if (s >= 85) return '优秀 · 可以自信展现'
  if (s >= 75) return '良好 · 有清晰的基础'
  if (s >= 60) return '合格 · 方向对了，继续打磨'
  return '需要加强 · 把建议落到实处'
})

const scoreColor = computed(() => {
  if (!hasOverallScore.value) return ''
  const s = report.value.overallScore
  if (s >= 85) return '#7b9a5a'
  if (s >= 75) return '#8fa86b'
  if (s >= 60) return '#c4a15b'
  return '#b06a5a'
})

async function loadReport() {
  if (retryBusy.value) return
  if (!sessionId.value) {
    loading.value = false
    error.value = '没有找到面试记录。请先完成一次模拟面试。'
    return
  }
  loading.value = true
  error.value = ''
  warning.value = ''
  try {
    const [reportResult, sessionResult] = await Promise.allSettled([
      api.report(sessionId.value),
      api.getSession(sessionId.value),
    ])
    if (sessionResult.status === 'fulfilled') {
      session.value = sessionResult.value
      sessionRole.value = session.value.targetRole || '综合模拟'
    }
    if (reportResult.status === 'fulfilled') {
      report.value = reportResult.value
      if (sessionResult.status === 'rejected') {
        warning.value = '报告已加载，但面试记录暂时无法读取，岗位和回答记录可能不完整。请重试。'
      }
    } else {
      const cause = reportResult.reason
      error.value = cause.status === 404
        ? '面试不存在或已过期，请重新开始一次模拟面试。'
        : cause.message || '报告加载失败，请稍后重试。'
    }
  } finally {
    loading.value = false
  }
}

async function retryScoring() {
  if (loading.value || retryBusy.value || !sessionId.value || !needsScoring.value) return
  retryBusy.value = true
  error.value = ''
  try { report.value = await api.retryReport(sessionId.value) }
  catch (cause) { error.value = cause.message || '评分重试失败，请稍后再试。' }
  finally { retryBusy.value = false }
}

let printDetails = null
function expandPrintDetails() {
  if (typeof document === 'undefined' || printDetails) return
  printDetails = [...document.querySelectorAll('#page-report details')].map(element => [element, element.open])
  printDetails.forEach(([element]) => { element.open = true })
}
function restorePrintDetails() {
  printDetails?.forEach(([element, open]) => { element.open = open })
  printDetails = null
}

const exporting = ref(false)
const exportMenuRef = ref(null)
function closeExportMenu() {
  const el = exportMenuRef.value
  if (el && typeof el.open !== 'undefined') el.open = false
}
async function onExportMarkdown() {
  if (exporting.value || !report.value) return
  closeExportMenu()
  try { downloadReportMarkdown(report.value, { session: session.value, role: sessionRole.value }) }
  catch (cause) { error.value = cause.message || '导出 Markdown 失败，请稍后再试。' }
}
async function onExportPdf() {
  if (exporting.value || !report.value) return
  closeExportMenu()
  exporting.value = true
  error.value = ''
  try {
    const el = document.querySelector('#page-report')
    await exportReportToPdf(el, {
      role: sessionRole.value,
      expandDetails: expandPrintDetails,
      restoreDetails: restorePrintDetails,
    })
  } catch (cause) {
    error.value = cause.message || '导出 PDF 失败，请稍后再试。'
  } finally {
    exporting.value = false
  }
}

onMounted(() => { loadReport() })
onUnmounted(() => { restorePrintDetails() })
</script>

<template>
<section class="page active" id="page-report" data-title="面试报告">
  <div v-if="loading && !report" class="loading-wrap">
    <p>正在生成面试报告…</p>
  </div>

  <div v-else-if="error && !report" class="error-wrap">
    <p class="notice error" role="alert">{{ error }}</p>
    <button v-if="sessionId" class="button button-outline" @click="loadReport">重试加载报告</button>
    <button class="button button-primary" @click="router.push({ name: 'Interview' })">返回面试室</button>
    <details v-if="session?.turns?.length" class="review-detail">
      <summary>已保存的问答记录</summary>
      <div v-for="turn in session.turns" :key="turn.questionId" class="review-expand-body">
        <strong>{{ turn.question }}</strong>
        <p>{{ turn.answer || '尚未回答' }}</p>
      </div>
    </details>
  </div>

  <template v-else-if="report">
    <div v-if="error || warning" class="error-wrap">
      <p class="notice error" role="alert">{{ error || warning }}</p>
      <button class="button button-outline" :disabled="loading || retryBusy" @click="loadReport">重试加载报告</button>
    </div>
    <div class="page-intro">
      <div>
        <div class="eyebrow"><span class="eyebrow-line"></span>INTERVIEW NOTES / 06</div>
        <h1>一次练习，一点进步<span class="greeting-dot">。</span></h1>
        <p>把反馈变成下一次表达的起点。</p>
      </div>
      <details class="export-menu" ref="exportMenuRef">
        <summary class="button button-outline">
          <svg><use href="#i-download"/></svg>{{ exporting ? '正在生成 PDF…' : '导出报告' }}
        </summary>
        <div class="export-menu-list" v-if="!exporting">
          <button type="button" @click="onExportMarkdown">导出 Markdown (.md)</button>
          <button type="button" @click="onExportPdf">导出 PDF (.pdf)</button>
        </div>
      </details>
    </div>

    <div class="report-meta">
      <span class="report-meta-mark">01</span>
      <div>
        <strong>{{ sessionRole }}</strong>
        <span>{{ scopeLabel }} <i>·</i> 已评分 {{ report.scoredCount }}/{{ report.answeredCount }} 题</span>
      </div>
      <span class="report-complete"><i></i>{{ statusLabel }}</span>
    </div>

    <p class="weight-explanation">12 道主问题合计 80%，出题前锁定相对权重；1 道岗位专项题固定 20%，按目标岗位或简历职业方向选择算法题或专业案例题。追问不计题数，并入对应主问题评分。提前结束时主问题权重仅在已出主问题间分配，专项题的 20% 始终保留；完成并评完全部 13 题后才显示综合分。分数仅供训练参考。</p>
    <div v-if="needsScoring" class="scoring-retry" role="status">
      <p>{{ statusLabel }}：已保存的问答和成功评分会保留，重试仅处理待评分题目。</p>
      <button data-testid="retry-scoring" class="button button-outline" :disabled="retryBusy || loading" @click="retryScoring">{{ retryBusy ? '正在重试评分…' : '重试评分' }}</button>
    </div>
    <div class="report-overview">
      <section class="panel score-panel">
        <div class="panel-heading">
          <div>
            <div class="eyebrow">OVERALL IMPRESSION</div>
            <h3>加权训练参考分</h3>
          </div>
          <span class="score-period">本次练习</span>
        </div>
        <div class="score-main">
          <div v-if="hasOverallScore" class="score-ring" data-testid="overall-score">
            <div>
              <strong>{{ report.overallScore }}</strong>
              <span>/ 100</span>
            </div>
          </div>
          <div class="score-summary">
            <span v-if="hasOverallScore" class="score-level">{{ scoreLevel }}</span>
            <span v-else class="score-pending" data-testid="score-status">{{ statusLabel }}</span>
            <h2>{{ report.overallSummary }}</h2>
          </div>
        </div>
      </section>

      <section class="report-side-panel">
        <div class="eyebrow">A NOTE FOR YOU</div>
        <span class="report-side-icon"><svg><use href="#i-spark"/></svg></span>
        <h3>下一步可以<br>这样练习。</h3>
        <ul class="suggestion-list">
          <li v-for="(s, i) in (report.suggestions || []).slice(0, 3)" :key="i">{{ s }}</li>
          <li v-if="!report.suggestions?.length">暂无有效反馈。</li>
        </ul>
        <button data-testid="practice-again" class="button button-dark" @click="router.push(newInterviewRoute(sessionId))">
          再练习一次 <svg><use href="#i-arrow"/></svg>
        </button>
      </section>
    </div>

    <div class="report-detail-grid">
      <section class="panel report-detail">
        <div class="panel-heading">
          <div>
            <div class="eyebrow">WHAT WENT WELL</div>
            <h3>做得不错的地方</h3>
          </div>
          <span class="detail-symbol positive">＋</span>
        </div>
        <div v-if="report.strengths?.length" class="feedback-list">
          <div v-for="(s, i) in report.strengths" :key="i" class="feedback-item">
            <span class="feedback-number">{{ String(i + 1).padStart(2, '0') }}</span>
            <div><strong>{{ s }}</strong></div>
          </div>
        </div>
        <p v-else class="empty-hint">暂无有效反馈。</p>
      </section>

      <section class="panel report-detail">
        <div class="panel-heading">
          <div>
            <div class="eyebrow">ROOM TO GROW</div>
            <h3>下一次可以加强</h3>
          </div>
          <span class="detail-symbol improve">↗</span>
        </div>
        <div v-if="report.gaps?.length" class="feedback-list">
          <div v-for="(g, i) in report.gaps" :key="i" class="feedback-item">
            <span class="feedback-number">{{ String(i + 1).padStart(2, '0') }}</span>
            <div><strong>{{ g }}</strong></div>
          </div>
        </div>
        <p v-else class="empty-hint">暂无有效反馈。</p>
      </section>
    </div>

    <section class="panel question-review">
      <div class="panel-heading">
        <div>
          <div class="eyebrow">QUESTION BY QUESTION</div>
          <h3>逐题回顾</h3>
        </div>
        <span class="text-button">共 {{ report.turnFeedbacks.length }} 题</span>
      </div>
      <template v-if="reviewedTurns.length">
        <details v-for="fb in reviewedTurns" :key="fb.questionId" class="review-detail" :data-question-id="fb.questionId">
          <summary class="review-row">
            <span class="review-index">{{ String(fb.index).padStart(2, '0') }}</span>
            <div>
              <span class="review-kind">{{ questionLabel(fb) }}</span>
              <strong>{{ fb.question }}</strong>
              <span>{{ fb.competency }} · {{ importanceLabel(fb.importance) }} · {{ difficultyLabel(fb.difficulty) }}</span>
              <span class="review-weight">权重 {{ Number.isFinite(fb.weightPercent) ? `${formatNumber(fb.weightPercent)}%` : '不计入' }} · 加权贡献 {{ isScored(fb) && Number.isFinite(fb.weightedContribution) ? `${formatNumber(fb.weightedContribution)} 分` : '—' }}</span>
            </div>
            <span class="review-score" :data-status="fb.status"><template v-if="isScored(fb)">{{ fb.score }} <small>/100</small></template><template v-else>{{ itemLabel(fb.status) }}</template></span>
            <span class="review-expand">⌄</span>
          </summary>
          <div class="review-expand-body">
            <div v-if="fb.kind === 'ALGORITHM'" class="review-feedback-block algorithm-source"><div class="review-feedback-label">Hot 100 原题 · 固定权重 20%</div><p>{{ fb.algorithm?.title }} <a v-if="algorithmUrl(fb.algorithm)" :href="algorithmUrl(fb.algorithm)" target="_blank" rel="noopener noreferrer">查看原题 ↗</a></p></div>
            <div class="review-feedback-block"><div class="review-feedback-label">原题</div><p>{{ fb.question }}</p></div>
            <div class="review-feedback-block"><div class="review-feedback-label">原回答</div><p class="original-answer">{{ fb.answer ?? '尚未回答' }}</p></div>
            <div v-if="fb.followUps?.length" class="follow-up-review"><h4>追问与补充回答 · 并入本题评分</h4><div v-for="(followUp, index) in fb.followUps" :key="followUp.questionId" class="review-feedback-block"><div class="review-feedback-label">追问 {{ index + 1 }}</div><p>{{ followUp.question }}</p><div class="review-feedback-label">补充回答</div><p class="original-answer">{{ followUp.answer ?? '尚未回答' }}</p></div></div>
            <div class="review-feedback-block"><div class="review-feedback-label">权重依据</div><p>{{ fb.weightReason || '暂无权重说明' }}<template v-if="!isSpecialty(fb.kind)"> · 相对权重点数 {{ fb.weightUnits }}</template></p></div>
            <template v-if="isScored(fb)">
              <div class="review-feedback-block dimension-block">
                <div class="review-feedback-label">五维得分</div>
                <dl class="dimension-scores"><div v-for="[key, label] in dimensions" :key="key"><dt>{{ label }}</dt><dd>{{ Number.isFinite(fb.dimensionScores?.[key]) ? fb.dimensionScores[key] : '—' }} / 20</dd></div></dl>
              </div>
              <div class="review-feedback-block"><div class="review-feedback-label">原文证据</div><ul v-if="fb.evidence?.length"><li v-for="(evidence, i) in fb.evidence" :key="i">{{ evidence }}</li></ul><p v-else>暂无有效反馈。</p></div>
              <div class="review-feedback-block"><div class="review-feedback-label">缺失要点与扣分依据</div><ul v-if="fb.missingPoints?.length"><li v-for="(point, i) in fb.missingPoints" :key="i">{{ point }}</li></ul><p v-else>暂无有效反馈。</p></div>
              <div class="review-feedback-block"><div class="review-feedback-label highlight">✦ 回答亮点</div><p>{{ fb.highlight || '暂无有效反馈。' }}</p></div>
              <div class="review-feedback-block"><div class="review-feedback-label improve">↗ 改进动作</div><p>{{ fb.improvement || '暂无有效反馈。' }}</p></div>
              <div class="review-feedback-block"><div class="review-feedback-label">复练回答框架</div><p>{{ fb.suggestedOutline || '暂无有效反馈。' }}</p></div>
            </template>
            <p v-else class="empty-hint">{{ fb.status === 'UNANSWERED' ? '本题未作答，权重保留；尚无综合评分。' : '本题待评分，原回答已保留。' }}</p>
          </div>
        </details>
      </template>
      <p v-else class="empty-hint">还没有回答记录。</p>
    </section>

    <footer class="page-footer">
      <span>拾阶 · CAREER STUDIO</span>
      <span>为认真准备的人，整理下一步。</span>
      <span>{{ report.aiModel ? `AI: ${report.aiModel}` : '暂无有效评分模型' }} · {{ report.scoringVersion }}</span>
    </footer>
  </template>
</section>
</template>

<style scoped>
.review-kind { font-size: 11px; color: #687c51; }
.follow-up-review { padding: 14px 18px; margin: 16px 0; background: #f7f7f2; border-left: 3px solid #a0ad8c; }
.follow-up-review h4 { font-size: 13px; margin: 0 0 12px; }
.algorithm-source a { color: #566e40; margin-left: 8px; }
.loading-wrap, .error-wrap {
  padding: 80px 24px;
  text-align: center;
}
.notice.error {
  max-width: 480px;
  margin: 0 auto 24px;
  padding: 16px 20px;
  background: #fff1e9;
  color: #95452c;
  border-radius: 8px;
}
.suggestion-list {
  list-style: none;
  padding: 0;
  margin: 16px 0 20px;
}
.suggestion-list li {
  padding: 8px 0;
  font-size: 13px;
  line-height: 1.7;
  color: #4a4d44;
  border-bottom: 1px dashed #e2e4dc;
}
.suggestion-list li:last-child {
  border-bottom: none;
}
.feedback-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.empty-hint {
  color: #9a9a90;
  font-size: 13px;
  line-height: 1.7;
}
.review-detail {
  border-bottom: 1px solid #e7e8e1;
}
.review-detail summary {
  list-style: none;
  cursor: pointer;
}
.review-detail summary::-webkit-details-marker {
  display: none;
}
.review-row {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 18px 0;
}
.review-row > div {
  flex: 1;
  min-width: 0;
}
.review-row strong {
  display: block;
  font-size: 14px;
  line-height: 1.6;
  margin-bottom: 4px;
  color: #353831;
}
.review-row span {
  font-size: 12px;
  color: #79766e;
}
.review-score {
  font-size: 20px;
  font-weight: 700;
  color: #4a5f3a;
  white-space: nowrap;
  text-align: right;
}
.review-score small {
  font-size: 12px;
  font-weight: 500;
  color: #9a9a90;
}
.review-expand {
  color: #9a9a90;
  font-size: 14px;
}
.review-expand-body {
  padding: 0 0 20px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.review-feedback-block {
  padding: 12px 16px;
  background: #f7f7f2;
  border-radius: 6px;
}
.review-feedback-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 6px;
}
.review-feedback-label.highlight {
  color: #7b9a5a;
}
.review-feedback-label.improve {
  color: #c48a3a;
}
.review-feedback-block p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: #353831;
}
.weight-explanation, .scoring-retry { font-size: 13px; line-height: 1.8; color: #67695f; margin: 18px 0; }
.scoring-retry { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px; background: #f7f7f2; border-radius: 8px; }
.score-pending { display: block; font-size: 22px; color: #79766e; margin-bottom: 12px; }
.review-weight { display: block; margin-top: 6px; }
.original-answer, .review-feedback-block p, .review-feedback-block li { white-space: pre-wrap; overflow-wrap: anywhere; }
.dimension-scores { margin: 0; }
.dimension-scores > div { display: flex; justify-content: space-between; gap: 12px; padding: 5px 0; font-size: 13px; }
.dimension-scores dd { margin: 0; white-space: nowrap; }
.review-feedback-block ul { margin: 0; padding-left: 20px; font-size: 13px; line-height: 1.8; }
@media (max-width: 600px) {
  .review-row { gap: 8px; flex-wrap: wrap; }
  .review-row > div { min-width: 170px; }
  .scoring-retry { flex-direction: column; align-items: flex-start; }
  .report-meta { flex-wrap: wrap; }
}
.score-ring strong {
  color: v-bind(scoreColor);
}
@media print {
  #page-report {
    display: block !important;
  }
  .report-overview { display: block; }
  .review-expand-body { display: flex !important; }
  .review-feedback-block { break-inside: avoid; }
  .review-detail { overflow: visible; }
  button, .error-wrap, .scoring-retry, .text-button, .review-expand {
    display: none;
  }
}
.export-menu { position: relative; display: inline-block; }
.export-menu summary { list-style: none; cursor: pointer; }
.export-menu summary::-webkit-details-marker { display: none; }
.export-menu-list {
  position: absolute;
  right: 0;
  top: 100%;
  margin-top: 6px;
  background: #fff;
  border: 1px solid #e6e9e1;
  border-radius: 5px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  min-width: 168px;
  z-index: 20;
}
.export-menu-list button {
  border: 0;
  background: transparent;
  padding: 9px 14px;
  text-align: left;
  font: inherit;
  font-size: 12px;
  color: #405246;
  cursor: pointer;
}
.export-menu-list button:hover { background: #f4f7f0; }
#page-report.is-exporting { width: 794px; background: #fff; }
#page-report.is-exporting .export-menu,
#page-report.is-exporting .scoring-retry,
#page-report.is-exporting [data-testid="practice-again"] { display: none !important; }
</style>
