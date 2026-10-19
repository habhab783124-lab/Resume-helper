import test from 'node:test'
import assert from 'node:assert/strict'
import { useInterviewReadiness } from './useInterviewReadiness.js'

const tick = () => new Promise(resolve => setTimeout(resolve, 0))

test('checks model and speech in parallel and only exposes ready after the entrance animation', async () => {
  let releaseModel
  let releaseSpeech
  const order = []
  const readiness = useInterviewReadiness({ api: {
    checkAi: () => { order.push('model'); return new Promise(resolve => { releaseModel = resolve }) },
    getSpeechCapabilities: () => { order.push('speech'); return new Promise(resolve => { releaseSpeech = resolve }) },
  }, minDurationMs: 0 })
  const pending = readiness.check()
  await tick()
  assert.deepEqual(order, ['model', 'speech'])
  releaseModel({ available: true }); releaseSpeech({ ttsStreamingEnabled: true })
  assert.equal((await pending).status, 'ready')
  assert.equal(readiness.speechAvailable.value, true)
})

test('distinguishes missing model configuration and keeps text readiness when speech is unavailable', async () => {
  const missing = useInterviewReadiness({ api: { checkAi: async () => { throw new Error('请填写模型 API Key，或由服务端配置 AI_API_KEY 后开始面试') }, getSpeechCapabilities: async () => ({ ttsStreamingEnabled: true }) }, minDurationMs: 0 })
  assert.equal((await missing.check()).status, 'missing-model')
  const speechDown = useInterviewReadiness({ api: { checkAi: async () => ({ available: true }), getSpeechCapabilities: async () => { throw new Error('语音不可用') } }, minDurationMs: 0 })
  assert.equal((await speechDown.check()).status, 'ready')
  assert.equal(speechDown.speechAvailable.value, false)
  const invalidKey = useInterviewReadiness({ api: { checkAi: async () => { throw new Error('模型 API Key 无效') }, getSpeechCapabilities: async () => ({}) }, minDurationMs: 0 })
  assert.equal((await invalidKey.check()).status, 'failed', 'invalid credentials are not reported as missing configuration')
})

test('bounds model and speech checks separately and classifies request deadlines', async () => {
  let modelOptions, speechOptions
  const readiness = useInterviewReadiness({ api: {
    checkAi: async (_ai, options) => { modelOptions = options; throw Object.assign(new Error('超时'), { code: 'BROWSER_UNCONFIRMED' }) },
    getSpeechCapabilities: async options => { speechOptions = options; return { ttsStreamingEnabled: false } },
  }, minDurationMs: 0 })
  assert.equal((await readiness.check()).status, 'timeout')
  assert.equal(readiness.error.value, '服务响应超时，请重试。')
  assert.equal(modelOptions.timeoutMs, 15000)
  assert.equal(speechOptions.timeoutMs, 5000)
})

test('classifies synchronous API adapter failures without leaving the gate stuck', async () => {
  const readiness = useInterviewReadiness({ api: {
    checkAi() { throw Object.assign(new Error('offline'), { name: 'TypeError' }) },
    getSpeechCapabilities() { return { ttsStreamingEnabled: false } },
  }, minDurationMs: 0 })
  assert.equal((await readiness.check()).status, 'offline')
  assert.equal(readiness.error.value, '服务连接失败，请检查网络后重试。')
  assert.equal(readiness.status.value, 'offline')
})

test('ignores a stale readiness response after a later check has started', async () => {
  let finishFirst
  let calls = 0
  const readiness = useInterviewReadiness({ api: {
    checkAi: () => ++calls === 1 ? new Promise(resolve => { finishFirst = resolve }) : Promise.resolve({ available: true }),
    getSpeechCapabilities: async () => ({ ttsStreamingEnabled: true }),
  }, minDurationMs: 0 })
  const first = readiness.check()
  await tick()
  const second = readiness.check()
  await second
  finishFirst({ available: false })
  await first
  assert.equal(readiness.status.value, 'ready')
})
