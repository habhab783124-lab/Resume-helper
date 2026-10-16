import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, computed, watch } from 'vue'
import * as ctx from './requestContext.js'
import { createInterviewApi } from './interview.js'

const json = data => new Response(JSON.stringify({ success: true, data }))
test('existing interview client reads backend changes for JSON and binary speech while separating credentials', async () => {
  assert.equal(typeof ctx.setLocalApiBaseUrl, 'function')
  const calls = []
  const api = createInterviewApi({ baseUrl: 'https://fallback.test', fetchImpl: async (url, init) => {
    calls.push({ url, ...init })
    return url.endsWith('/speech') ? new Response(new Uint8Array(3), { headers: { 'Content-Type': 'audio/mpeg' } }) : json({})
  } })
  ctx.setAiSettings({ apiKey: 'model-key', model: 'model', baseUrl: 'https://model.test/v1' })
  ctx.setSpeechSettings({ speechKey: 'speech-key' })
  ctx.setLocalApiBaseUrl('http://localhost:8081')
  await api.checkAi()
  ctx.setLocalApiBaseUrl('http://localhost:8082')
  await api.checkSpeech()
  await api.questionSpeech('s', 'q')
  ctx.setLocalApiBaseUrl('')
  await api.getSession('s')
  assert.deepEqual(calls.map(c => c.url), ['http://localhost:8081/api/ai/check', 'http://localhost:8082/api/interview-speech/check', 'http://localhost:8082/api/interviews/s/questions/q/speech', 'https://fallback.test/api/interviews/s'])
  assert.equal(calls[0].headers['X-AI-Api-Key'], 'model-key')
  assert.equal(calls[0].headers['X-Speech-Api-Key'], undefined)
  for (const c of calls.slice(1, 3)) { assert.equal(c.headers['X-Speech-Api-Key'], 'speech-key'); assert.equal(c.headers['X-AI-Api-Key'], undefined) }
  assert.equal(calls[3].headers['X-Speech-Api-Key'], undefined)
  ctx.setAiSettings({}); ctx.setSpeechSettings({})
})

const viewSource = readFileSync(new URL('../views/Settings.vue', import.meta.url), 'utf8')
function mountSettings(api, fetchImpl, local = '') {
  const script = viewSource.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace(/import\.meta\.env/g, '({})')
  const saved = { address: local, ai: {}, speech: {} }
  const mount = new Function('ref', 'computed', 'watch', 'createInterviewApi', 'getAiSettings', 'setAiSettings', 'getSpeechSettings', 'setSpeechSettings', 'getLocalApiBaseUrl', 'setLocalApiBaseUrl', 'fetch', 'window', `${script}\nreturn { backendUrl, status, modelResult, speechResult, apiKey, speechKey, saveAndCheck, resetAddress, clearConfig, checkModel, checkSpeech }`)
  return { saved, view: mount(ref, computed, watch, () => api, () => ({}), x => { saved.ai = x }, () => ({}), x => { saved.speech = x }, () => saved.address, x => { saved.address = x }, fetchImpl, { setTimeout, clearTimeout }) }
}
test('settings keeps independent backend, model and speech controls; invalid backend never replaces saved address', async () => {
  let probes = 0
  const calls = []
  const { view, saved } = mountSettings({ checkAi: async () => { calls.push('model'); return { model: 'test-model' } }, checkSpeech: async () => { calls.push('speech'); return { audioBytes: 3 } } }, async () => { probes++; return json([]) }, 'http://localhost:8081')
  view.backendUrl.value = 'javascript:alert(1)'; await view.saveAndCheck()
  assert.equal(probes, 0); assert.equal(saved.address, 'http://localhost:8081'); assert.equal(view.status.value, 'disconnected')
  view.modelResult.value = 'old model'; view.speechResult.value = 'old speech'
  view.backendUrl.value = 'http://localhost:8082/'; await view.saveAndCheck()
  assert.equal(view.modelResult.value, ''); assert.equal(view.speechResult.value, '')
  assert.equal(probes, 1); assert.equal(saved.address, 'http://localhost:8082'); assert.equal(view.status.value, 'connected')
  await view.checkModel(); await view.checkSpeech(); assert.deepEqual(calls, ['model', 'speech'])
  view.clearConfig(); assert.equal(saved.address, 'http://localhost:8082', 'clearing credentials must retain backend preference')
  await view.resetAddress(); assert.equal(saved.address, ''); assert.equal(view.backendUrl.value, 'http://localhost:8080')
})
