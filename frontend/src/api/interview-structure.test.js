import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, computed, reactive, watch } from 'vue'
import { algorithmUrl } from '../interview/algorithmSource.js'
import { isSpecialty, specialtyLabel, answerGuidance } from '../interview/specialtyPresentation.js'
import { useInterviewConversation } from '../interview/useInterviewConversation.js'
import { useInterviewPolling } from '../interview/useInterviewPolling.js'
import { useInterviewFinish } from '../interview/useInterviewFinish.js'
import { useInterviewReadiness } from '../interview/useInterviewReadiness.js'

const source = readFileSync(new URL('../views/Interview.vue', import.meta.url), 'utf8')
const setupSource = readFileSync(new URL('../components/interview/InterviewSetup.vue', import.meta.url), 'utf8')
const headerSource = readFileSync(new URL('../components/interview/InterviewHeader.vue', import.meta.url), 'utf8')
const drawerSource = readFileSync(new URL('../components/interview/InterviewInfoDrawer.vue', import.meta.url), 'utf8')
const composerSource = readFileSync(new URL('../components/interview/InterviewComposer.vue', import.meta.url), 'utf8')
const messagesSource = readFileSync(new URL('../components/interview/InterviewMessages.vue', import.meta.url), 'utf8')
const body = /<script setup>([\s\S]*?)<\/script>/.exec(source)[1]
  .replace(/^import\s.*$/gm, '').replace(/import\.meta\.env/g, '({})')
  + '\nreturn { applySession, start, material, completed, completedRoots, currentMetadata, questionLabel, algorithmUrl };'
function mount(api = {}) {
  const setupState = { source: ref(null), resumePreview: ref(null), targetRole: ref(''), jobDescription: ref(''), preferences: ref({}), planCurrent: ref(true), planPreview: ref({}), planError: ref(''), starting: ref(false), preparingPlan: ref(false), confirmingPlan: ref(false), allowAlgorithmRepeat: ref(false), repeatConsentRequired: ref(false),
    startInterview: async () => ({ sessionId: 's', status: 'ACTIVE', turns: [turn('q1', null)], currentQuestion: turn('q1', null), questionCount: 5, questionMetadata: metadata }),
    loadSource: async () => null, loadJob: async () => null, savePreparation() {}, restorePreparation() { return false },
    clearPreparation() {}, switchScope() { return false } }
  const createInterviewVoiceAdapter = bridge => ({ api: bridge, conversation: bridge })
  const useInterviewVoiceConversation = () => ({ state: { phase: 'OFF' }, start() {}, pause() {}, stop() {}, stopPlayback() {}, submitNow() {}, retrySegment() {}, discardSegment() {}, resume() {}, isActive: () => false, onDraftEdited() {}, syncContext() {}, dispose() {} })
  return new Function('ref', 'computed', 'reactive', 'watch', 'onMounted', 'onUnmounted', 'useRoute', 'useRouter', 'createInterviewApi', 'createDraftStore', 'getLocalApiBaseUrl', 'getUserId', 'useInterviewVoice', 'getAiSettings', 'setAiSettings', 'algorithmUrl', 'isSpecialty', 'specialtyLabel', 'answerGuidance', 'useInterviewPolling', 'useInterviewConversation', 'useInterviewFinish', 'useInterviewSetup', 'useInterviewReadiness', 'createVoiceConsent', 'materialsApi', 'resumeApi', 'jobsApi', 'createInterviewVoiceAdapter', 'useInterviewVoiceConversation', 'useVoiceCapture', 'createVoiceSocket', 'createPcmPlayer', 'voiceWorkletUrl', body)(
    ref, computed, reactive, watch, () => {}, () => {}, () => ({ query: {} }), () => ({ push() {} }), () => api,
    () => ({ save() { return { saved: true } }, load() { return null }, remove() { return true }, clearScope() { return 0 }, keyFor() { return '' } }), () => '', () => 'demo-user',
    () => ({ syncContext() {}, busy: false }), () => ({}), () => {}, algorithmUrl, isSpecialty, specialtyLabel, answerGuidance, useInterviewPolling, useInterviewConversation, useInterviewFinish,
    () => setupState, options => useInterviewReadiness({ ...options, api: { checkAi: async () => ({}), getSpeechCapabilities: async () => ({}), ...options.api }, minDurationMs: 0 }), () => ({ accepted: () => true, accept() {} }), {}, {}, {}, createInterviewVoiceAdapter, useInterviewVoiceConversation, () => {}, () => {}, () => {}, '/test-worklet.js')
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
test('starting prepares the plan automatically and setup keeps mode choices advanced', async () => {
  const view = mount()
  view.material.value = { materialId: 'm', text: '简历' }
  await view.start()
  assert.equal(view.currentMetadata.value.kind, 'MAIN')
  assert.match(setupSource, /value="QUICK"/)
  assert.match(setupSource, /value="FULL"/)
  assert.doesNotMatch(setupSource, /生成计划预览|确认计划并开始|class="resume-preview"|<pre>/)
  assert.match(setupSource, /开始面试/)
  assert.match(source, /setup\.startInterview\(\)/)
})

test('non-coding sessions retain the case plan and label question13 without algorithm wording', () => {
  const view = mount()
  const specialty = { kind: 'CASE_STUDY', focus: '用户增长分析', reason: '目标岗位为运营，采用专业案例考察' }
  const current = turn('case', null)
  view.applySession({ sessionId: 'operations', status: 'ACTIVE', questionCount: 13, specialty,
    turns: [turn('q1'), current], currentQuestion: current,
    questionMetadata: { q1: metadata.q1, case: { kind: 'CASE_STUDY', mainQuestionIndex: 13 } } })
  assert.deepEqual(view.currentMetadata.value, { kind: 'CASE_STUDY', mainQuestionIndex: 13 })
  assert.match(view.questionLabel(current), /专业案例题.*20%/)
  assert.doesNotMatch(view.questionLabel(current), /算法|Hot 100|主问题/)
  assert.equal(view.completedRoots.value.length, 1)
  assert.match(drawerSource, /session\.specialty\.focus/)
  assert.match(drawerSource, /session\.specialty\.reason/)
})

test('preparation, session header, and information drawer are separate accessible components', () => {
  assert.match(source, /<InterviewSetup\b/)
  assert.match(source, /@upload="upload"/)
  assert.match(source, /@use-paste="uploadPaste"/)
  assert.match(source, /@start="start"/)
  assert.match(source, /<InterviewHeader\b/)
  assert.match(source, /@finish="requestFinish"/)
  assert.match(source, /@open-info="openInfo"/)
  assert.match(source, /<InterviewInfoDrawer\b/)
  assert.doesNotMatch(source, /<aside class="interview-aside"/)
  assert.match(headerSource, /:disabled="!canFinish"/)
  assert.match(source, /const \{ finishing: finishFinishing \} = finishFlow/)
  assert.match(source, /:can-finish="[^\"]*!busy[^\"]*!finishFinishing[^\"]*"/)
  assert.match(drawerSource, /event\.key === 'Escape'/)
})

test('active interview uses the available width and anchors the composer at the bottom', () => {
  assert.match(source, /\.text-interview \.interview-layout\s*\{[^}]*width:\s*100%/)
  assert.match(source, /\.text-interview \.interview-main\s*\{[^}]*display:\s*flex/)
  assert.match(source, /\.text-interview \.interview-main\s*\{[^}]*flex-direction:\s*column/)
  assert.match(messagesSource, /\.conversation-history\s*\{[^}]*flex:\s*1/)
  assert.match(composerSource, /\.interview-composer-wrap\s*\{[^}]*margin:\s*auto\s+0\s+0/)
  const form = /<form class="interview-composer"[\s\S]*?<\/form>/.exec(composerSource)?.[0] || ''
  assert.match(form, /composer-inline-status/)
  assert.match(form, /has-failed-segment/)
  assert.doesNotMatch(source, /class="interview-footnote"/)
  assert.match(source, /<div v-if="!active" class="session-actions">/)
})
