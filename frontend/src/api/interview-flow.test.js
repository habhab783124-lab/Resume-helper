import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { ref, computed, reactive, watch } from 'vue'

// 直接执行 Interview.vue 的 <script setup> 源码，注入最小 Vue 替身与 API stub。
// 不依赖真实浏览器，就能断言提交恢复这条链路上的边界行为。
const viewSource = readFileSync(fileURLToPath(new URL('../views/Interview.vue', import.meta.url)), 'utf8')
const setupBody = /<script setup>([\s\S]*?)<\/script>/.exec(viewSource)[1]
  .replace(/^import\s.*$/gm, '')
  .replace(/import\.meta\.env/g, '({})')
  .trimEnd()
  + '\nreturn { applySession, submitAnswer, finish, chooseFinish, viewReport, reset, upload, start, checkAi, syncVoiceSession, session, answer, error, busy, uncertainAnswer, pending, current, voice, finishChoice, finishMessage, finishAlternativeLabel, material, apiKey, model, modelBaseUrl };'

function mount(apiStub) {
  const mounted = [], unmounted = [], pushes = [], events = [], settings = []
  const fakeVoice = { busy: ref(false), preview: ref(null),
    syncContext() { events.push('sync') }, dispose() { events.push('dispose') }, loadCapabilities() { events.push('capabilities') },
    acceptPreview() { return false }, cancelRecording() { events.push('cancel-recording') } }
  const view = new Function(
    'ref', 'computed', 'reactive', 'watch', 'onMounted', 'onUnmounted', 'createInterviewApi', 'useRouter', 'getAiSettings', 'setAiSettings', 'useInterviewVoice', setupBody)(
    ref, computed, reactive, watch, callback => mounted.push(callback), callback => unmounted.push(callback), () => apiStub,
    () => ({ push: route => { events.push('route'); pushes.push(route) } }),
    () => ({ apiKey: 'own-key', model: 'own-model', baseUrl: 'https://model.example/v1' }), value => settings.push(value), () => fakeVoice)
  Object.assign(view, { pushes, events, settings, mounted, unmounted })
  return view
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

const finishedSession = { ...firstQuestion, status: 'FINISHED', currentQuestion: null }

test('finish offers inline choices for a draft or unaccepted preview and continue keeps both', async () => {
  let finished = 0
  const view = mount({ finish: async () => { finished += 1; return finishedSession } })
  view.applySession(firstQuestion); view.answer.value = '未提交'
  await view.finish(); assert.equal(view.finishChoice.value, true); assert.equal(finished, 0)
  await view.chooseFinish('continue'); assert.equal(view.answer.value, '未提交'); assert.equal(view.finishChoice.value, false)
  view.answer.value = ''; view.voice.preview = { text: '未确认转写' }
  await view.finish(); assert.equal(view.finishChoice.value, true); assert.equal(finished, 0)
  await view.chooseFinish('submit'); assert.equal(finished, 0); assert.match(view.error.value, /转写/)
})
test('submit then finish confirms the original answer saved; failed submit never finishes or navigates', async () => {
  let finished = 0
  const view = mount({ answer: async () => { throw Object.assign(new Error('invalid'), { status: 400 }) }, getSession: async () => firstQuestion,
    finish: async () => { finished += 1; return finishedSession } })
  view.applySession(firstQuestion); view.answer.value = '草稿'; await view.finish(); await view.chooseFinish('submit')
  assert.equal(finished, 0); assert.equal(view.pushes.length, 0); assert.equal(view.answer.value, '草稿'); assert.equal(view.finishChoice.value, true)
})
test('lost submit response recovered as saved qualifies for finish; voice disposes before report navigation', async () => {
  const calls = []
  const view = mount({ answer: async () => { calls.push('answer'); throw new Error('lost') },
    getSession: async () => session([{ questionId: 'q1', question: '介绍项目', answer: '草稿' }], { questionId: 'q2', question: '继续' }),
    finish: async () => { calls.push('finish'); return finishedSession } })
  view.applySession(firstQuestion); view.answer.value = '草稿'; await view.finish(); await view.chooseFinish('submit')
  assert.deepEqual(calls, ['answer', 'finish']); assert.equal(view.pushes.length, 1)
  assert.ok(view.events.lastIndexOf('dispose') < view.events.indexOf('route'))
  assert.deepEqual(view.pushes[0].query, { sessionId: 's1' })
})
test('discard then finish sends no answer; automatic/restored FINISHED provide report navigation', async () => {
  let answers = 0
  const view = mount({ answer: async () => { answers += 1; return finishedSession }, finish: async () => finishedSession })
  view.applySession(firstQuestion); view.answer.value = '草稿'; await view.finish(); await view.chooseFinish('discard')
  assert.equal(answers, 0); assert.equal(view.pushes.length, 1)
  const restored = mount({}); restored.applySession(finishedSession); restored.viewReport(); assert.equal(restored.pushes.length, 1)
})
test('voice busy blocks submit, finish and model check while cancellation remains outside page lock', async () => {
  let calls = 0; const view = mount({ answer: async () => { calls += 1 }, finish: async () => { calls += 1 }, checkAi: async () => { calls += 1 } })
  view.applySession(firstQuestion); view.answer.value = '草稿'; view.voice.busy = true
  await view.submitAnswer(); await view.finish(); await view.checkAi(); assert.equal(calls, 0); assert.equal(view.finishChoice.value, false)
})
test('same-question recovery preserves text; voice conflict sync cannot apply to a newer question', async () => {
  const view = mount({ getSession: async () => firstQuestion })
  view.applySession(firstQuestion); view.answer.value = '编辑草稿'; view.applySession({ ...firstQuestion }); assert.equal(view.answer.value, '编辑草稿')
  await view.syncVoiceSession({ sessionId: 's1', questionId: 'q1' }); assert.equal(view.answer.value, '编辑草稿')
  view.applySession(session([], { questionId: 'q2', question: '新题' })); await view.syncVoiceSession({ sessionId: 's1', questionId: 'q1' }); assert.equal(view.current.value.questionId, 'q2')
})
test('voice recovery forwards cancellation and ignores a late snapshot even if its fetch ignores abort', async () => {
  let resolve, options
  const response = new Promise(ok => { resolve = ok })
  const view = mount({ getSession: (_id, value) => { options = value; return response } })
  view.applySession(firstQuestion); view.answer.value = '编辑草稿'
  const controller = new AbortController()
  const recovery = view.syncVoiceSession({ sessionId: 's1', questionId: 'q1' }, () => true, controller.signal)
  controller.abort()
  resolve(session([], { questionId: 'q2', question: '迟到的新题' }))
  await recovery
  assert.equal(view.current.value.questionId, 'q1'); assert.equal(view.answer.value, '编辑草稿')
  assert.equal(options?.signal, controller.signal)
})
test('material accepts 10 MiB boundary and rejects empty extraction without losing interview on material 404', async () => {
  let calls = 0
  const view = mount({ upload: async () => { calls += 1; return { materialId: 'm1', text: '' } } })
  await view.upload({ name: 'resume.pdf', size: 10 * 1024 * 1024 }); assert.equal(calls, 1); assert.equal(view.material.value, null); assert.match(view.error.value, /粘贴/)
  const other = mount({ upload: async () => { throw Object.assign(new Error('材料不存在'), { status: 404 }) } }); other.applySession(firstQuestion)
  await other.upload({ name: 'resume.pdf', size: 1 }); assert.equal(other.session.value.sessionId, 's1')
})
test('model defaults come from shared settings and mount/unmount manage voice lifecycle', () => {
  const view = mount({}); assert.equal(view.apiKey.value, 'own-key'); assert.equal(view.model.value, 'own-model'); assert.equal(view.modelBaseUrl.value, 'https://model.example/v1')
  view.mounted.forEach(callback => callback()); assert.ok(view.events.includes('capabilities'))
  view.unmounted.forEach(callback => callback()); assert.ok(view.events.includes('dispose'))
})

test('voice controls remain visible with explicit disabled reasons, inline finishing controls and finished report entry', () => {
  for (const id of ['question-play', 'question-stop', 'recording-start', 'recording-stop', 'recording-cancel', 'transcription-start', 'transcription-cancel', 'transcript-replace', 'transcript-append', 'transcript-keep', 'finish-interview', 'finish-submit', 'finish-discard', 'view-report']) {
    assert.ok(viewSource.includes(`data-testid="${id}"`), `missing control ${id}`)
  }
  assert.match(viewSource, /<audio[^>]+controls/)
  assert.match(viewSource, /火山引擎/)
  assert.match(viewSource, /5000.*精简/)
  assert.doesNotMatch(viewSource, /文字面试 · 不会录音/)
})

test('ordinary last answer completion keeps a usable report entry without calling finish', async () => {
  let finishes = 0
  const view = mount({ answer: async (_id, body) => ({ ...finishedSession, turns: [{ questionId: 'q1', question: '介绍项目', answer: body.answer }] }), finish: async () => { finishes += 1 } })
  view.applySession(firstQuestion); view.answer.value = '最后回答'; await view.finish(); await view.submitAnswer()
  assert.equal(view.session.value.status, 'FINISHED'); assert.equal(view.finishChoice.value, false); assert.equal(view.pushes.length, 0)
  view.viewReport(); assert.equal(view.pushes.length, 1); assert.equal(finishes, 0)
})
test('reset clears active voice and unsaved finishing state', () => {
  const view = mount({}); view.applySession(firstQuestion); view.answer.value = '草稿'; view.finish(); view.reset()
  assert.equal(view.session.value, null); assert.equal(view.finishChoice.value, false); assert.ok(view.events.includes('cancel-recording'))
})

test('next-question failure preserves server pending answer and finishing honestly offers retention, never local discard', async () => {
  let ends = 0
  const held = '服务端已保留的回答'
  const withPending = { ...firstQuestion, pendingAnswer: held }
  const view = mount({ answer: async () => { throw new Error('生成下一题失败') }, getSession: async () => withPending,
    finish: async () => { ends += 1; return { ...finishedSession, turns: [{ ...firstQuestion.turns[0], answer: held }] } } })
  view.applySession(firstQuestion); view.answer.value = held; await view.submitAnswer(); await view.finish()
  assert.equal(view.pending.value, held)
  assert.equal(view.finishAlternativeLabel.value, '保留已提交回答并结束')
  assert.match(view.finishMessage.value, /计入.*报告/)
  await view.chooseFinish('discard'); assert.equal(ends, 0, 'local-discard action cannot falsely discard submitted content')
  await view.chooseFinish('preserve'); assert.equal(ends, 1); assert.equal(view.session.value.turns[0].answer, held); assert.equal(view.pushes.length, 1)
})
test('unknown submission outcome warns finishing can retain an answer; local draft keeps real discard semantics', async () => {
  const view = mount({ answer: async () => { throw new Error('lost') }, getSession: async () => { throw new Error('offline') } })
  view.applySession(firstQuestion); view.answer.value = '待确认'; await view.submitAnswer(); await view.finish()
  assert.equal(view.finishAlternativeLabel.value, '结束并保留已收到的回答'); assert.match(view.finishMessage.value, /可能.*保留/)
  const local = mount({}); local.applySession(firstQuestion); local.answer.value = '未发送'
  assert.equal(local.finishAlternativeLabel.value, '放弃草稿并结束')
  assert.match(viewSource, /data-testid="finish-preserve"/)
})
