import test from 'node:test'
import assert from 'node:assert/strict'
import { useInterviewConversation } from './useInterviewConversation.js'

const questionSession = (sessionId = 's1', stateVersion = 1) => ({
  sessionId, status: 'ACTIVE', stateVersion, revision: 1,
  currentQuestion: { questionId: 'q1', question: '介绍项目' },
  turns: [{ questionId: 'q1', question: '介绍项目', answer: null }],
  generation: null
})
const savedSession = (sessionId = 's1', stateVersion = 2) => ({
  ...questionSession(sessionId, stateVersion), currentQuestion: null, revision: 2,
  turns: [{ questionId: 'q1', question: '介绍项目', answer: '回答' }],
  generation: { generationId: 'g1', state: 'QUEUED', kind: 'NEXT', requestId: 'gen-1' }
})
function setup({ api = {}, draftStore = {}, polling = null } = {}) {
  const calls = []; let id = 0
  const defaults = {
    saveAnswer: async (...args) => { calls.push(['saveAnswer', ...args]); return savedSession() },
    generate: async (...args) => { calls.push(['generate', ...args]); return savedSession() },
    getSession: async (...args) => { calls.push(['getSession', ...args]); return savedSession() },
  }
  const drafts = { remove: (...args) => calls.push(['removeDraft', ...args]), save: (...args) => calls.push(['saveDraft', ...args]), ...draftStore }
  const controller = useInterviewConversation({ api: { ...defaults, ...api }, drafts, polling, ids: () => `id-${++id}` })
  controller.applySession(questionSession())
  controller.draft.value = '回答'
  return { controller, calls }
}

test('saved answer followed by generation failure clears draft and retries generation only', async () => {
  const { controller, calls } = setup({ api: { generate: async (...args) => { calls.push(['generate', ...args]); throw new Error('generation failed') } } })
  await controller.send()
  assert.equal(controller.phase.value, 'GENERATION_FAILED')
  assert.equal(controller.session.value.turns[0].answer, '回答')
  assert.equal(calls.filter(call => call[0] === 'removeDraft').length, 1)
  await controller.retryGeneration()
  assert.equal(calls.filter(call => call[0] === 'saveAnswer').length, 1)
  assert.equal(calls.filter(call => call[0] === 'generate').length, 2)
  const generations = calls.filter(call => call[0] === 'generate')
  assert.notEqual(generations[0][2].requestId, generations[1][2].requestId)
})

test('final answer that finishes the session does not request another question', async () => {
  const finishedSession = { ...savedSession(), status: 'FINISHED', currentQuestion: null, generation: null }
  const { controller, calls } = setup({ api: { saveAnswer: async (...args) => { calls.push(['saveAnswer', ...args]); return finishedSession } } })
  await controller.send()
  assert.equal(controller.session.value.status, 'FINISHED')
  assert.equal(controller.phase.value, 'FINISHED')
  assert.equal(calls.filter(call => call[0] === 'generate').length, 0)
})

test('lost save response reads the session and never resubmits an already saved answer', async () => {
  const { controller, calls } = setup({ api: {
    saveAnswer: async (...args) => { calls.push(['saveAnswer', ...args]); throw new Error('response lost') },
    getSession: async (...args) => { calls.push(['getSession', ...args]); return savedSession() }
  } })
  await controller.send()
  assert.equal(calls.filter(call => call[0] === 'saveAnswer').length, 1)
  assert.equal(calls.filter(call => call[0] === 'getSession').length, 1)
  assert.equal(calls.filter(call => call[0] === 'generate').length, 1)
})

test('unknown save outcome keeps answer and submission ID for the next send attempt', async () => {
  const { controller, calls } = setup({ api: {
    saveAnswer: async (...args) => { calls.push(['saveAnswer', ...args]); throw new Error('offline') },
    getSession: async (...args) => { calls.push(['getSession', ...args]); throw new Error('offline') }
  } })
  await controller.send()
  const firstId = calls.find(call => call[0] === 'saveAnswer')[2].submissionId
  assert.equal(controller.draft.value, '回答')
  assert.equal(controller.submission.value.submissionId, firstId)
  await controller.send()
  assert.equal(calls.filter(call => call[0] === 'saveAnswer')[1][2].submissionId, firstId)
})

test('beginFinish fences a late generation response and cancelFinish resumes the accepted phase', async () => {
  let releaseGeneration
  const { controller } = setup({ api: { generate: () => new Promise(resolve => { releaseGeneration = resolve }) } })
  const sending = controller.send()
  while (!releaseGeneration) await new Promise(resolve => setImmediate(resolve))
  controller.beginFinish()
  releaseGeneration({ ...savedSession('s1', 3), currentQuestion: { questionId: 'q2', question: '迟到的问题' } })
  await sending
  assert.equal(controller.phase.value, 'FINISHING')
  assert.equal(controller.session.value.currentQuestion, null)
  controller.cancelFinish()
  assert.equal(controller.phase.value, 'GENERATING')
})

test('replacement submits a reason, preserves the source draft, and polls the accepted operation', async () => {
  const calls = []
  const replacementPending = { ...questionSession(), replacements: { q1: { status: 'RUNNING', requestId: 'id-1' } } }
  const polling = { start: id => calls.push(['poll', id]) }
  const { controller, calls: recorded } = setup({ api: { replace: async (...args) => { calls.push(['replace', ...args]); return replacementPending } }, polling })
  controller.draft.value = '经历草稿'
  assert.equal(await controller.replace('q1', '我没有参与此项目'), true)
  assert.deepEqual(recorded.find(call => call[0] === 'saveDraft').slice(1), ['s1', 'q1', { text: '经历草稿' }])
  assert.equal(calls.find(call => call[0] === 'replace')[2].reason, '我没有参与此项目')
  assert.deepEqual(calls.find(call => call[0] === 'poll'), ['poll', 's1'])
})

test('double send is coalesced into one save and one generation', async () => {
  let release; const held = new Promise(resolve => { release = resolve })
  const { controller, calls } = setup({ api: { saveAnswer: async (...args) => { calls.push(['saveAnswer', ...args]); await held; return savedSession() } } })
  const a = controller.send(); const b = controller.send(); release(); await Promise.all([a, b])
  assert.equal(calls.filter(call => call[0] === 'saveAnswer').length, 1)
  assert.equal(calls.filter(call => call[0] === 'generate').length, 1)
})

test('restore and monotonically newer state reject a late response from an older round', async () => {
  let release; const late = new Promise(resolve => { release = resolve })
  const { controller } = setup({ api: { getSession: async id => id === 'old' ? late : questionSession('new', 4) } })
  const old = controller.restore('old')
  await controller.restore('new')
  release(savedSession('old', 99)); await old
  assert.equal(controller.session.value.sessionId, 'new')
  assert.equal(controller.session.value.stateVersion, 4)
  assert.equal(controller.phase.value, 'ANSWERING')
})

test('scope switch stops polling, preserves the old scoped pointer, and selects only the new scope pointer', () => {
  const values = new Map(); const stopped = []
  const storage = { setItem: (key, value) => values.set(key, value), getItem: key => values.get(key) || null, removeItem: key => values.delete(key) }
  const controller = useInterviewConversation({ api: {}, storage, scope: { apiBaseUrl: 'https://one.test', userId: 'alice' },
    polling: { stop: () => stopped.push(true) } })
  controller.applySession(questionSession())
  const oldKey = 'career-studio-interview-id-v2|https://one.test|alice'
  const newKey = 'career-studio-interview-id-v2|https://two.test|alice'
  values.set(newKey, 's-two')
  assert.equal(values.get(oldKey), 's1')
  assert.equal(controller.switchScope({ apiBaseUrl: 'https://two.test', userId: 'alice' }), 's-two')
  assert.equal(controller.session.value, null)
  assert.equal(values.get(oldKey), 's1')
  assert.deepEqual(stopped, [true])
})

test('same-session snapshots cannot move the authoritative state version backward', () => {
  const { controller } = setup()
  controller.applySession(questionSession('s1', 9))
  assert.equal(controller.applySession(questionSession('s1', 8)), false)
  assert.equal(controller.session.value.stateVersion, 9)
})

test('QUICK waits after saving an answer until the user explicitly requests the next question', async () => {
  const quickSaved = { ...savedSession(), plan: { mode: 'QUICK' }, generation: null }
  const next = { ...questionSession('s1', 3), plan: { mode: 'QUICK' }, currentQuestion: { questionId: 'q2', question: '下一题' },
    turns: [...quickSaved.turns, { questionId: 'q2', question: '下一题', answer: null }] }
  const { controller, calls } = setup({ api: {
    saveAnswer: async (...args) => { calls.push(['saveAnswer', ...args]); return quickSaved },
    generate: async (...args) => { calls.push(['generate', ...args]); return next }
  } })
  controller.session.value.plan = { mode: 'QUICK' }
  await controller.send()
  assert.equal(controller.session.value.currentQuestion, null)
  assert.equal(controller.phase.value, 'ANSWERING')
  assert.equal(calls.filter(call => call[0] === 'generate').length, 0)
  await controller.retryGeneration()
  assert.equal(controller.session.value.currentQuestion.questionId, 'q2')
  assert.equal(calls.filter(call => call[0] === 'generate').length, 1)
})

test('skipping closes the selected question and FULL immediately requests a next generation', async () => {
  const skipped = { ...questionSession('s1', 2), currentQuestion: null, revision: 3, generation: null,
    turns: [{ questionId: 'q1', question: '当前题', answer: null }] }
  const next = { ...questionSession('s1', 4), currentQuestion: { questionId: 'q2', question: '下一题' },
    turns: [...skipped.turns, { questionId: 'q2', question: '下一题', answer: null }] }
  const { controller, calls } = setup({ api: {
    skip: async (...args) => { calls.push(['skip', ...args]); return skipped },
    generate: async (...args) => { calls.push(['generate', ...args]); return next },
  } })
  assert.equal(await controller.skip('q1'), true)
  assert.equal(calls[0][0], 'skip')
  assert.equal(calls[0][2].questionId, 'q1')
  assert.equal(calls[1][0], 'generate')
  assert.equal(controller.session.value.currentQuestion.questionId, 'q2')
})

test('clarification requests an explanation for the current question without changing its draft', async () => {
  let request
  const { controller, calls } = setup({ api: {
    clarify: async (...args) => { calls.push(['clarify', ...args]); request = args[1]; return {
      ...questionSession('s1', 2),
      clarifications: { q1: { requestId: request.requestId, status: 'RUNNING', text: null, error: null } },
    } },
  } })
  controller.draft.value = '正在写的回答草稿'
  assert.equal(await controller.clarify(), true)
  assert.equal(calls.filter(call => call[0] === 'clarify').length, 1)
  assert.equal(request.questionId, 'q1')
  assert.equal(controller.draft.value, '正在写的回答草稿')
  assert.equal(controller.session.value.currentQuestion.questionId, 'q1')
})

test('QUICK teaching feedback closes the saved root and starts polling its independent assessment', async () => {
  const starts = []
  const polling = { start: id => starts.push(id) }
  const { controller, calls } = setup({ polling, api: { practiceFeedback: async (...args) => {
    calls.push(['practiceFeedback', ...args])
    return { ...savedSession('s1', 3), generation: { generationId: 'g1', state: 'SUCCEEDED' },
      plan: { mode: 'QUICK' }, practiceFeedbacks: { q1: { requestId: args[1].requestId, status: 'QUEUED' } } }
  } } })
  controller.applySession({ ...savedSession(), generation: null, plan: { mode: 'QUICK' },
    questionMetadata: { q1: { kind: 'MAIN' } } })
  assert.equal(await controller.practiceFeedback('q1'), true)
  const call = calls.find(item => item[0] === 'practiceFeedback')
  assert.equal(call[1], 's1')
  assert.deepEqual(call[2], { questionId: 'q1', requestId: 'id-1', expectedRevision: 2 })
  assert.equal(controller.session.value.practiceFeedbacks.q1.status, 'QUEUED')
  assert.deepEqual(starts, ['s1'])
})
