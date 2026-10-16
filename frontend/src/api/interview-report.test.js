import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, computed } from 'vue'
import { algorithmUrl } from '../interview/algorithmSource.js'

const source = readFileSync(new URL('../views/Report.vue', import.meta.url), 'utf8')
const body = /<script setup>([\s\S]*?)<\/script>/.exec(source)[1]
  .replace(/^import\s.*$/gm, '').replace(/import\.meta\.env/g, '({})')
  + '\nreturn { report, session, loading, retryBusy, error, hasOverallScore, needsScoring, scoreLevel, scoreColor, statusLabel, scopeLabel, reviewedTurns, loadReport, retryScoring, printReport, questionLabel, algorithmUrl };'
function mount(api, sessionId = 's1', browser = {}) {
  const mounted = [], unmounted = []
  const view = new Function('ref', 'computed', 'onMounted', 'onUnmounted', 'useRoute', 'useRouter', 'createInterviewApi', 'window', 'document', 'algorithmUrl', body)(
    ref, computed, fn => mounted.push(fn), fn => unmounted.push(fn), () => ({ query: { sessionId } }), () => ({ push() {} }), () => api, browser.window, browser.document, algorithmUrl)
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
  assert.equal(view.hasOverallScore.value, true); assert.match(view.scoreLevel.value, /良好/)
  view.report.value = result('COMPLETE', 0)
  assert.equal(view.hasOverallScore.value, true); assert.match(view.scoreLevel.value, /需要加强/)
  for (const score of [null, undefined, '80', NaN, Infinity]) {
    view.report.value = { ...result(), overallScore: score }
    assert.equal(view.hasOverallScore.value, false); assert.equal(view.scoreLevel.value, ''); assert.equal(view.scoreColor.value, '')
  }
})
test('partial, unavailable and empty never invent an overall score or level', async () => {
  for (const [status, label, retry] of [['PARTIAL', '待评分', true], ['UNAVAILABLE', '评分暂不可用', true], ['EMPTY', '暂无评分', false]]) {
    const view = mount(api(result(status, null))); await view.loadReport()
    assert.equal(view.hasOverallScore.value, false); assert.equal(view.scoreLevel.value, ''); assert.equal(view.scoreColor.value, '')
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
  assert.match(source, /出题前锁定相对权重/); assert.match(source, /主问题合计 80%/); assert.match(source, /算法题固定 20%/)
  assert.match(source, /fb.followUps/); assert.match(source, /追问.*并入/)
  assert.match(source, /@media print/); assert.match(source, /beforeprint/); assert.match(source, /afterprint/)
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


test('print expands all question details for both button and native print, then restores open state and event listeners', async () => {
  const details = [{ open: false }, { open: true }], listeners = new Map()
  let prints = 0
  const window = { addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name),
    print: () => { prints++; assert.ok(details.every(detail => detail.open)) } }
  const document = { querySelectorAll: selector => { assert.equal(selector, '#page-report details'); return details } }
  const view = mount(api(result()), 's1', { window, document })
  view.mounted.forEach(fn => fn())
  view.printReport(); assert.equal(prints, 1); assert.deepEqual(details.map(detail => detail.open), [false, true])
  listeners.get('beforeprint')(); assert.ok(details.every(detail => detail.open))
  listeners.get('beforeprint')(); listeners.get('afterprint')(); assert.deepEqual(details.map(detail => detail.open), [false, true])
  listeners.get('beforeprint')(); view.unmounted.forEach(fn => fn())
  assert.equal(listeners.size, 0); assert.deepEqual(details.map(detail => detail.open), [false, true])
})
