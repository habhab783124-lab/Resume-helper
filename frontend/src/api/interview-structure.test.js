import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, computed, reactive, watch } from 'vue'
import { algorithmUrl } from '../interview/algorithmSource.js'

const source = readFileSync(new URL('../views/Interview.vue', import.meta.url), 'utf8')
const body = /<script setup>([\s\S]*?)<\/script>/.exec(source)[1]
  .replace(/^import\s.*$/gm, '').replace(/import\.meta\.env/g, '({})')
  + '\nreturn { applySession, start, material, completed, completedRoots, currentMetadata, questionLabel, algorithmUrl };'
function mount(api = {}) {
  return new Function('ref', 'computed', 'reactive', 'watch', 'onMounted', 'onUnmounted', 'useRouter', 'createInterviewApi', 'useInterviewVoice', 'getAiSettings', 'setAiSettings', 'algorithmUrl', body)(
    ref, computed, reactive, watch, () => {}, () => {}, () => ({ push() {} }), () => api,
    () => ({ syncContext() {}, busy: false }), () => ({}), () => {}, algorithmUrl)
}
const turn = (id, answer = '已回答') => ({ questionId: id, question: id, answer })
const metadata = {
  q1: { kind: 'MAIN', mainQuestionIndex: 1 },
  f1: { kind: 'FOLLOW_UP', parentQuestionId: 'q1', mainQuestionIndex: 1 },
  q2: { kind: 'MAIN', mainQuestionIndex: 2 },
  algo: { kind: 'ALGORITHM', mainQuestionIndex: 13, algorithm: { id: '1', title: '两数之和', url: 'https://leetcode.cn/problems/two-sum/', difficulty: 'EASY' } },
}
test('follow-ups remain in history but do not advance root question progress, including restored sessions', () => {
  const view = mount()
  view.applySession({ sessionId: 's', status: 'ACTIVE', questionCount: 13,
    turns: [turn('q1'), turn('f1'), turn('q2', null)], currentQuestion: turn('q2', null), questionMetadata: metadata })
  assert.equal(view.completed.value.length, 2)
  assert.equal(view.completedRoots.value.length, 1)
  assert.equal(view.currentMetadata.value.mainQuestionIndex, 2)
  assert.match(view.questionLabel(turn('f1')), /主问题 01.*追问/)
  assert.match(view.questionLabel(turn('q2')), /主问题 02/)
  assert.match(view.questionLabel(turn('algo')), /算法题.*20%/)
})
test('algorithm source links accept official problem URLs only', () => {
  const view = mount()
  assert.equal(view.algorithmUrl(metadata.algo.algorithm), metadata.algo.algorithm.url)
  for (const url of ['javascript:alert(1)', 'https://leetcode.cn.evil.test/problems/two-sum/', 'https://evil.test/', 'https://leetcode.cn/settings/']) {
    assert.equal(view.algorithmUrl({ url }), '')
  }
})
test('starting uses fixed 13-question configuration and setup explains 12+1 and weights', async () => {
  const sent = []
  const first = { sessionId: 's', status: 'ACTIVE', turns: [turn('q1', null)], currentQuestion: turn('q1', null), questionCount: 13, questionMetadata: metadata }
  const view = mount({ start: async payload => { sent.push(payload); return first } })
  view.material.value = { materialId: 'm', text: '简历' }
  await view.start()
  assert.equal(sent[0].questionCount, 13)
  assert.match(source, /12 道主问题/)
  assert.match(source, /1 道 Hot 100 算法题/)
  assert.match(source, /80%/)
  assert.match(source, /追问不计入/)
  assert.doesNotMatch(source, /v-model="questionCount"/)
})
