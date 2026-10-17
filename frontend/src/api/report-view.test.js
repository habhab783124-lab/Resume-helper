import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createInterviewApi } from './interview.js'
import { setAiSettings } from './requestContext.js'

const source = readFileSync(new URL('../views/Report.vue', import.meta.url), 'utf8')
const setup = /<script setup>([\s\S]*?)<\/script>/.exec(source)[1]
  .replace(/^import .*$/gm, '').replace(/import\.meta\.env/g, '({})')
  + '\nreturn { loadReport, report, error, loading, sessionRole, scopeLabel, hasOverallScore, session: typeof session === "undefined" ? null : session, onExportMarkdown: typeof onExportMarkdown === "undefined" ? null : onExportMarkdown, onExportPdf: typeof onExportPdf === "undefined" ? null : onExportPdf, warning: typeof warning === "undefined" ? null : warning }'
function mount(api, query = { sessionId: 's1' }) {
  const printed = []
  const view = new Function('ref', 'computed', 'watch', 'onMounted', 'onUnmounted', 'createInterviewApi', 'useRoute', 'useRouter', 'window', setup)(
    value => ({ value }), getter => ({ get value() { return getter() } }), () => {}, () => {}, () => {}, () => api,
    () => ({ query }), () => ({ push() {} }), { print: () => printed.push(true) })
  return { ...view, printed }
}
const history = { sessionId: 's1', targetRole: '工程师', status: 'FINISHED', turns: [{ questionId: 'q', answer: 'saved answer' }] }
const report = { overallScore: 85, turnFeedbacks: [], suggestions: [] }

test('rejects absent, repeated or empty session query before network requests', async () => {
  let calls = 0
  for (const query of [{}, { sessionId: ['s1', 's2'] }, { sessionId: '  ' }]) {
    const view = mount({ report: async () => { calls++; return report }, getSession: async () => history }, query)
    await view.loadReport()
    assert.match(view.error.value, /没有找到面试记录/)
    assert.equal(view.loading.value, false)
  }
  assert.equal(calls, 0)
})

test('report failure retains recovered history and retry uses current context', async () => {
  let fail = true
  const calls = []
  const api = createInterviewApi({ fetchImpl: async (url, init) => {
    calls.push([url, init])
    if (!url.includes('/report')) return new Response(JSON.stringify({ success: true, data: history }))
    return new Response(JSON.stringify(fail ? { success: false, message: '模型暂不可用' } : { success: true, data: report }), { status: fail ? 503 : 200 })
  } })
  const view = mount(api)
  setAiSettings({ apiKey: 'first', model: 'm1' })
  await view.loadReport()
  assert.match(view.error.value, /模型暂不可用/)
  assert.deepEqual(view.session?.value, history)
  fail = false
  setAiSettings({ apiKey: 'second', model: 'm2' })
  await view.loadReport()
  assert.equal(view.error.value, '')
  assert.equal(view.sessionRole.value, '工程师')
  assert.deepEqual(view.report.value, report)
  assert.equal(calls.filter(([url]) => url.includes('/report?async=true')).at(-1)[1].headers['X-AI-Api-Key'], 'second')
  assert.match(source, /@click="loadReport"/)
})

test('role fetch failure leaves a valid report visible with an explanation', async () => {
  const view = mount({ report: async () => report, getSession: async () => { throw Error('读取失败') } })
  await view.loadReport()
  assert.deepEqual(view.report.value, report)
  assert.equal(view.error.value, '')
  assert.match(view.warning?.value || '', /报告.*已加载.*面试记录/)
  assert.match(source, /warning/)
})

test('expired session gives a restart message and export handlers are wired', async () => {
  const view = mount({ report: async () => { throw Object.assign(Error('expired'), { status: 404 }) }, getSession: async () => { throw Error('gone') } })
  await view.loadReport()
  assert.match(view.error.value, /过期.*重新/)
  // 导出 handler 已就位（实际 DOM/依赖在 node 下不可用，仅校验契约存在且模板已绑定）
  assert.equal(typeof view.onExportMarkdown, 'function')
  assert.equal(typeof view.onExportPdf, 'function')
  assert.match(source, /@click="onExportMarkdown"/)
  assert.match(source, /@click="onExportPdf"/)
  assert.ok(!source.includes('printReport'))
  assert.ok(!source.includes('window.print'))
})

test('USER_ENDED report names the answered scope and never shows a composite score', () => {
  const view = mount({})
  view.report.value = { reportStatus: 'COMPLETE', overallScore: 88, plannedCount: 13, answeredCount: 1,
    finishReason: 'USER_ENDED', turnFeedbacks: [] }
  assert.match(view.scopeLabel.value, /用户提前结束.*已答题反馈/)
  assert.equal(view.hasOverallScore.value, false)
  view.report.value = { reportStatus: 'EMPTY', overallScore: null, plannedCount: 13, answeredCount: 0,
    finishReason: 'USER_ENDED', turnFeedbacks: [] }
  assert.match(view.scopeLabel.value, /尚无可评分内容/)
})

test('algorithm report states code is reviewed statically and never executed', () => { assert.match(source, /静态思路与代码评阅，未执行代码/) })
