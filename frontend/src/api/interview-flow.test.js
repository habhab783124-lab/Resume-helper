import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

// 直接执行 Interview.vue 的 <script setup> 源码，注入最小 Vue 替身与 API stub。
// 不依赖真实浏览器，就能断言提交恢复这条链路上的边界行为。
const viewSource = readFileSync(fileURLToPath(new URL('../views/Interview.vue', import.meta.url)), 'utf8')
const setupBody = /<script setup>([\s\S]*?)<\/script>/.exec(viewSource)[1]
  .replace(/^import\s.*$/gm, '')
  .replace(/import\.meta\.env/g, '({})')
  .trimEnd()
  + '\nreturn { applySession, submitAnswer, session, answer, error, busy, uncertainAnswer, pending, current };'

function mount(apiStub) {
  const ref = initial => ({ value: initial })
  const computed = getter => ({ get value() { return getter() } })
  const mounted = []
  return new Function('ref', 'computed', 'onMounted', 'createInterviewApi', setupBody)(
    ref, computed, callback => mounted.push(callback), () => apiStub)
}

const session = (turns, currentQuestion, pendingAnswer = null) => ({
  sessionId: 's1', materialId: 'm1', targetRole: '', questionCount: 2,
  status: 'ACTIVE', turns, currentQuestion, pendingAnswer,
})

const firstQuestion = session([{ questionId: 'q1', question: '介绍项目' }], { questionId: 'q1', question: '介绍项目' })

test('response lost but turn saved: recovers without showing an error', async () => {
  const calls = []
  const view = mount({
    answer: async (_id, body) => { calls.push(body); throw new Error('网络中断') },
    getSession: async () => session([
      { questionId: 'q1', question: '介绍项目', answer: '我的回答' },
      { questionId: 'q2', question: '后续追问' },
    ], { questionId: 'q2', question: '后续追问' }),
  })
  view.applySession(firstQuestion)
  view.answer.value = '我的回答'

  await view.submitAnswer()

  assert.equal(view.error.value, '', '提交实际已成功，不应再提示失败')
  assert.equal(view.busy.value, '')
  assert.equal(view.session.value.currentQuestion.questionId, 'q2', '应看到下一题')
  assert.equal(view.uncertainAnswer.value, null, '不锁定为待重试')
  assert.deepEqual(calls, [{ questionId: 'q1', answer: '我的回答' }])
})

test('definitive validation rejection keeps the draft editable', async () => {
  const view = mount({
    answer: async () => { throw Object.assign(new Error('回答须为 1–5000 字'), { status: 400 }) },
    getSession: async () => firstQuestion,
  })
  view.applySession(firstQuestion)
  view.answer.value = '一段回答'

  await view.submitAnswer()

  assert.equal(view.uncertainAnswer.value, null, '草稿可编辑，不被锁定为待重试')
  assert.equal(view.pending.value, null)
  assert.equal(view.answer.value, '一段回答', '草稿仍在编辑框里')
  assert.equal(view.error.value, '回答须为 1–5000 字')
})

test('unknown outcome locks the original answer for a safe retry', async () => {
  const view = mount({
    answer: async () => { throw new Error('网络中断') },
    getSession: async () => { throw new Error('恢复也失败') },
  })
  view.applySession(firstQuestion)
  view.answer.value = '一段回答'

  await view.submitAnswer()

  assert.equal(view.uncertainAnswer.value, '一段回答', '结果不明时锁定原回答，避免重复提交')
  assert.equal(view.answer.value, '一段回答')
  assert.equal(view.error.value, '网络中断')
})

test('rejects an overlong draft before submitting', () => {
  let sent = 0
  const view = mount({
    answer: async () => { sent += 1; return session([{ questionId: 'q1', question: '介绍项目', answer: 'x' }], { questionId: 'q2', question: '追问' }) },
    getSession: async () => firstQuestion,
  })
  view.applySession(firstQuestion)
  view.answer.value = '答'.repeat(5001)

  view.submitAnswer()

  assert.equal(view.error.value, '回答不能超过 5000 字，请精简后提交。')
  assert.equal(sent, 0, '超长草稿不应发请求')
  assert.equal(view.busy.value, '')
  assert.equal(view.uncertainAnswer.value, null)
})
