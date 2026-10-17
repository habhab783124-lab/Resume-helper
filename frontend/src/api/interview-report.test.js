import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, computed } from 'vue'
import { algorithmUrl } from '../interview/algorithmSource.js'
import { isSpecialty, specialtyLabel, answerGuidance } from '../interview/specialtyPresentation.js'

const source = readFileSync(new URL('../views/Report.vue', import.meta.url), 'utf8')
const body = /<script setup>([\s\S]*?)<\/script>/.exec(source)[1]
  .replace(/^import\s.*$/gm, '').replace(/import\.meta\.env/g, '({})')
  + '\nreturn { report, session, loading, retryBusy, reviewBusy: typeof reviewBusy === "undefined" ? null : reviewBusy, error, hasOverallScore, needsScoring, reviewStatusLabel: typeof reviewStatusLabel === "undefined" ? null : reviewStatusLabel, statusLabel, scopeLabel, reviewedTurns, loadReport, retryScoring, retryReview: typeof retryReview === "undefined" ? null : retryReview, expandPrintDetails, restorePrintDetails, onExportMarkdown, onExportPdf, questionLabel, algorithmUrl };'
function mount(api, sessionId = 's1', browser = {}) {
  const mounted = [], unmounted = []
  const view = new Function('ref', 'computed', 'watch', 'onMounted', 'onUnmounted', 'useRoute', 'useRouter', 'createInterviewApi', 'window', 'document', 'algorithmUrl', 'isSpecialty', 'specialtyLabel', 'answerGuidance', body)(
    ref, computed, () => {}, fn => mounted.push(fn), fn => unmounted.push(fn), () => ({ query: { sessionId } }), () => ({ push() {} }), () => api, browser.window, browser.document, algorithmUrl, isSpecialty, specialtyLabel, answerGuidance)
  return { ...view, mounted, unmounted }
}
const feedback = (questionId, status = 'SCORED', score = 80) => ({ questionId, index: 1, question: `问题 ${questionId}`, answer: `回答 ${questionId}`, status, score,
  weightPercent: 100, weightedContribution: score, competency: '缓存验证', difficulty: 'BASIC', importance: 'RELATED',
  dimensionScores: { CORRECTNESS: 20, COVERAGE: 15, REASONING: 15, EVIDENCE: 15, CLARITY: 15 }, evidence: ['回答'], missingPoints: ['统计周期'],
  highlight: '说明方法', improvement: '补充周期', suggestedOutline: '指标、方法、结果' })
const result = (reportStatus = 'COMPLETE', overallScore = 80) => ({ reportStatus, overallScore, plannedCount: 13, answeredCount: 13, scoredCount: reportStatus === 'PARTIAL' ? 12 : reportStatus === 'UNAVAILABLE' ? 0 : 13,
  overallSummary: '训练参考', turnFeedbacks: [feedback('q1', ['PARTIAL', 'UNAVAILABLE'].includes(reportStatus) ? 'PENDING' : 'SCORED')], strengths: [], gaps: [], suggestions: [] })
const api = value => ({ report: async () => value, getSession: async () => ({ turns: [] }) })

test('COMPLETE requires a finite numeric score and legal zero remains a score', async () => {
  const view = mount(api(result())); await view.loadReport()
  assert.equal(view.hasOverallScore.value, true)
  view.report.value = result('COMPLETE', 0)
  assert.equal(view.hasOverallScore.value, true)
  for (const score of [null, undefined, '80', NaN, Infinity]) {
    view.report.value = { ...result(), overallScore: score }
    assert.equal(view.hasOverallScore.value, false)
  }
})
test('partial, unavailable and empty never invent an overall score or level', async () => {
  for (const [status, label, retry] of [['PARTIAL', '待评分', true], ['UNAVAILABLE', '评分暂不可用', true], ['EMPTY', '暂无评分', false]]) {
    const view = mount(api(result(status, null))); await view.loadReport()
    assert.equal(view.hasOverallScore.value, false)
    assert.equal(view.statusLabel.value, label); assert.equal(view.needsScoring.value, retry)
  }
})
test('question IDs preserve the correct answer when session ordering differs', async () => {
  const value = result(); value.turnFeedbacks = [feedback('q2'), feedback('q1', 'PENDING', null), feedback('q3', 'UNANSWERED', null)]
  value.turnFeedbacks.forEach(item => { delete item.answer })
  const view = mount({ ...api(value), getSession: async () => ({ turns: [{ questionId: 'q1', answer: '第一题回答' }, { questionId: 'q2', answer: '第二题回答' }] }) })
  await view.loadReport()
  assert.equal(view.reviewedTurns.value[0].answer, '第二题回答'); assert.equal(view.reviewedTurns.value[1].answer, '第一题回答')
  assert.equal(view.reviewedTurns.value[1].score, null); assert.equal(view.reviewedTurns.value[2].answer, undefined)
})
test('early finish explains answered scope separately from scoring completion', async () => {
  const value = { ...result(), answeredCount: 1, scoredCount: 1 }
  const view = mount(api(value)); await view.loadReport()
  assert.match(view.scopeLabel.value, /已答 1\/13 题.*提前结束/)
  assert.match(view.scopeLabel.value, /暂无综合分/)
  assert.equal(view.hasOverallScore.value, false)
  assert.equal(view.needsScoring.value, false)
  assert.equal(view.statusLabel.value, '逐题反馈已完成')
  view.report.value = result('EMPTY', null); view.report.value.answeredCount = 0
  assert.match(view.scopeLabel.value, /尚无可评分内容/)
})
test('GET loads report; explicit retry uses POST API and keeps content visible while pending and on failure', async () => {
  const calls = []; let rejectRetry
  const value = result('PARTIAL', null)
  const view = mount({ report: async id => { calls.push(['get', id]); return value }, getSession: async () => ({ turns: [] }),
    retryReport: id => { calls.push(['retry', id]); return new Promise((_resolve, reject) => { rejectRetry = reject }) } })
  await view.loadReport(); const retry = view.retryScoring()
  assert.equal(view.loading.value, false); assert.equal(view.retryBusy.value, true); assert.equal(view.report.value.turnFeedbacks[0].answer, '回答 q1')
  await view.retryScoring(); assert.equal(calls.length, 2, 'double click does not duplicate retry')
  rejectRetry(new Error('评分服务暂不可用')); await retry
  assert.equal(view.report.value.reportStatus, 'PARTIAL'); assert.match(view.error.value, /评分服务/); assert.equal(view.retryBusy.value, false)
  assert.deepEqual(calls, [['get', 's1'], ['retry', 's1']])
})
test('successful scoring retry replaces report, refresh uses GET, complete and empty cannot retry', async () => {
  const calls = []
  const view = mount({ report: async () => { calls.push('get'); return result('PARTIAL', null) }, getSession: async () => ({ turns: [] }),
    retryReport: async () => { calls.push('retry'); return result() } })
  await view.loadReport(); await view.retryScoring(); assert.equal(view.hasOverallScore.value, true)
  await view.retryScoring(); assert.deepEqual(calls, ['get', 'retry'])
  await view.loadReport(); assert.deepEqual(calls, ['get', 'retry', 'get'])
})
test('HTTP failures preserve session history and missing route avoids requests', async () => {
  const view = mount({ report: async () => { throw Object.assign(new Error('gone'), { status: 404 }) }, getSession: async () => ({ turns: [{ questionId: 'q', answer: '已保存' }] }) })
  await view.loadReport(); assert.match(view.error.value, /不存在或已过期/); assert.equal(view.session.value.turns[0].answer, '已保存')
  const missing = mount({}, ''); await missing.loadReport(); assert.match(missing.error.value, /没有找到/); assert.equal(missing.loading.value, false)
})
test('template exposes all evidence dimensions, status guards and text-safe printable details', () => {
  assert.match(source, /v-if="hasOverallScore"/)
  for (const field of ['dimensionScores', 'evidence', 'missingPoints', 'suggestedOutline', 'weightPercent', 'weightedContribution', 'weightReason']) assert.ok(source.includes(field))
  for (const dimension of ['CORRECTNESS', 'COVERAGE', 'REASONING', 'EVIDENCE', 'CLARITY']) assert.ok(source.includes(dimension))
  assert.match(source, /:key="fb.questionId"/); assert.doesNotMatch(source, /v-html|overallScore\s*\?\?\s*0|目前回答比较完整/)
  assert.match(source, /主问题合计 80%.*岗位专项题固定 20%/s)
  assert.match(source, /fb.followUps/); assert.match(source, /追问.*并入/)
  assert.match(source, /@media print/); assert.match(source, /export-menu/); assert.match(source, /onExportPdf/)
})

test('algorithm row retains fixed weight, source identity and nested follow-up answers', async () => {
  const value = result()
  value.turnFeedbacks = [{ ...feedback('main'), kind: 'MAIN', mainQuestionIndex: 1, weightPercent: 6.67,
    followUps: [{ questionId: 'follow', question: '如何验证？', answer: '用压测对照验证' }] },
  { ...feedback('algo'), kind: 'ALGORITHM', mainQuestionIndex: 13, weightPercent: 20,
    algorithm: { id: '1', title: '两数之和', url: 'https://leetcode.cn/problems/two-sum/' }, followUps: [] }]
  const view = mount(api(value)); await view.loadReport()
  assert.equal(view.reviewedTurns.value.length, 2)
  assert.equal(view.reviewedTurns.value[0].followUps[0].answer, '用压测对照验证')
  assert.match(view.questionLabel(view.reviewedTurns.value[1]), /算法题.*20%/)
  assert.equal(view.algorithmUrl(value.turnFeedbacks[1].algorithm), 'https://leetcode.cn/problems/two-sum/')
  assert.equal(view.algorithmUrl({ url: 'javascript:alert(1)' }), '')
  assert.equal(view.algorithmUrl({ url: 'https://leetcode.cn.evil.test/problems/two-sum/' }), '')
})

test('a professional case keeps the specialty20 percent and never receives an algorithm label', async () => {
  const value = result()
  value.turnFeedbacks = [{ ...feedback('case'), kind: 'CASE_STUDY', mainQuestionIndex: 13, weightPercent: 20,
    competency: '运营增长分析', algorithm: null, question: '给定转化数据，提出改进和验证方案。', followUps: [] }]
  const view = mount(api(value)); await view.loadReport()
  assert.match(view.questionLabel(view.reviewedTurns.value[0]), /专业案例题.*20%/)
  assert.doesNotMatch(view.questionLabel(view.reviewedTurns.value[0]), /算法|Hot 100|主问题/)
  assert.equal(view.reviewedTurns.value[0].weightPercent, 20)
  assert.equal(view.hasOverallScore.value, true)
})


test('PDF export helpers expand all question details then restore prior open state', () => {
  const details = [{ open: false }, { open: true }]
  const document = { querySelectorAll: selector => { assert.equal(selector, '#page-report details'); return details } }
  const view = mount(api(result()), 's1', { document })
  view.expandPrintDetails()
  assert.ok(details.every(detail => detail.open))
  view.restorePrintDetails()
  assert.deepEqual(details.map(detail => detail.open), [false, true])
  // 可重入：再次展开并恢复仍回到原始状态
  view.expandPrintDetails(); view.restorePrintDetails()
  assert.deepEqual(details.map(detail => detail.open), [false, true])
  // 导出 handler 已绑定（实际 DOM/依赖在 node 下不可用，仅校验契约）
  assert.equal(typeof view.onExportMarkdown, 'function')
  assert.equal(typeof view.onExportPdf, 'function')
})

test('review retry is independent from scoring retry and the report presents coverage, priorities, next steps, strengths, details, then scoring', async () => {
  const value = { ...result('PARTIAL', null), scoringState: 'SUCCEEDED', reviewStatus: 'UNAVAILABLE', reviewError: '归纳不可用', priorityIssues: [] }
  const calls = []
  const view = mount({ report: async () => value, getSession: async () => ({ turns: [] }),
    retryReview: async (_id, body) => { calls.push(['review', body]); return { ...value, reviewStatus: 'PENDING' } },
    retryReport: async () => { calls.push(['scoring']); return value } })
  await view.loadReport()
  assert.equal(view.reviewStatusLabel.value, '重点归纳暂不可用')
  await view.retryReview()
  assert.deepEqual(calls.map(call => call[0]), ['review'])
  assert.equal(view.report.value.scoringState, 'SUCCEEDED')
  const order = ['report-coverage', 'priority-review', 'next-actions', 'positive-feedback', 'question-review', 'scoring-details']
    .map(marker => source.indexOf(`data-testid="${marker}"`))
  assert.ok(order.every(index => index >= 0))
  assert.deepEqual(order, [...order].sort((a, b) => a - b))
  assert.doesNotMatch(source, /优秀 · 可以自信展现|良好 · 有清晰的基础|需要加强 · 把建议落到实处/)
})
