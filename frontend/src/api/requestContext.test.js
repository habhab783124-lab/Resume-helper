import test from 'node:test'
import assert from 'node:assert/strict'

async function context(storage) {
  globalThis.sessionStorage = storage
  return import(`./requestContext.js?test=${Math.random()}`)
}
const store = values => ({ getItem: key => values[key] ?? null, setItem: (key, value) => { values[key] = value }, removeItem: key => { delete values[key] } })

test('keeps the existing key and stores all settings in tab storage', async () => {
  const values = { 'resume-helper-ai-key': 'existing' }
  const ctx = await context(store(values))
  assert.deepEqual(ctx.getAiSettings(), { apiKey: 'existing', model: '', baseUrl: '' })
  ctx.setAiSettings({ apiKey: ' key ', model: ' model ', baseUrl: ' https://models.test/v1 ' })
  assert.deepEqual(ctx.getAiSettings(), { apiKey: 'key', model: 'model', baseUrl: 'https://models.test/v1' })
  const reloaded = await context(store(values))
  assert.deepEqual(reloaded.getAiSettings(), ctx.getAiSettings())
  assert.equal(ctx.getUserId(), 'demo-user')
})

test('falls back to memory when tab storage is unavailable, including clearing', async () => {
  const blocked = { getItem() { throw Error('blocked') }, setItem() { throw Error('blocked') }, removeItem() { throw Error('blocked') } }
  const ctx = await context(blocked)
  ctx.setAiSettings({ apiKey: 'key', model: 'model', baseUrl: 'url' })
  assert.equal(ctx.getAiSettings().apiKey, 'key')
  ctx.setAiSettings({ apiKey: '', model: 'model', baseUrl: 'url' })
  globalThis.sessionStorage = store({ 'resume-helper-ai-key': 'stale' })
  assert.equal(ctx.getAiSettings().apiKey, '')
})

test('clears stored settings without resurrecting previous values', async () => {
  const values = {}
  const ctx = await context(store(values))
  ctx.setAiSettings({ apiKey: 'key', model: 'model', baseUrl: 'url' })
  ctx.setAiSettings({})
  assert.deepEqual(ctx.getAiSettings(), { apiKey: '', model: '', baseUrl: '' })
  assert.deepEqual(values, {})
})

 test('backend preference persists independently of tab credentials and clears safely', async () => {
  const addresses = {}; globalThis.localStorage = store(addresses)
  const values = {}; const ctx = await context(store(values))
  ctx.setLocalApiBaseUrl(' http://localhost:8081 ')
  ctx.setSpeechSettings({ speechKey: ' speech-key ' }); ctx.setAiSettings({ apiKey: 'model-key' })
  assert.deepEqual(addresses, { 'resume-helper-local-api-base-url': 'http://localhost:8081' })
  assert.equal(values['resume-helper-speech-key'], 'speech-key')
  const fresh = await context(store(values)); assert.equal(fresh.getLocalApiBaseUrl(), 'http://localhost:8081')
  fresh.setAiSettings({}); fresh.setSpeechSettings({}); assert.equal(fresh.getLocalApiBaseUrl(), 'http://localhost:8081')
  fresh.setLocalApiBaseUrl(''); assert.equal(fresh.getLocalApiBaseUrl(), ''); assert.deepEqual(addresses, {})
})
 test('backend and speech memory survive blocked storage and do not resurrect cleared values', async () => {
  const blocked = { getItem() { throw Error('blocked') }, setItem() { throw Error('blocked') }, removeItem() { throw Error('blocked') } }
  globalThis.localStorage = blocked
  const ctx = await context(blocked)
  ctx.setLocalApiBaseUrl('http://localhost:8082'); ctx.setSpeechSettings({ speechKey: 'key' })
  assert.equal(ctx.getLocalApiBaseUrl(), 'http://localhost:8082'); assert.equal(ctx.getSpeechSettings().speechKey, 'key')
  ctx.setLocalApiBaseUrl(''); ctx.setSpeechSettings({})
  globalThis.localStorage = store({ 'resume-helper-local-api-base-url': 'stale' }); globalThis.sessionStorage = store({ 'resume-helper-speech-key': 'stale' })
  assert.equal(ctx.getLocalApiBaseUrl(), ''); assert.equal(ctx.getSpeechSettings().speechKey, '')
})
test('speech settings changes emit an event with no credential payload', async () => {
  const ctx = await context(store({}))
  const original = globalThis.dispatchEvent
  let received
  globalThis.dispatchEvent = event => { received = event; return true }
  try {
    ctx.setSpeechSettings({ speechKey: 'secret-value' })
    assert.equal(received.type, 'speech-settings-change')
    assert.equal(received.detail, undefined)
    assert.equal(JSON.stringify(received).includes('secret-value'), false)
  } finally { globalThis.dispatchEvent = original }
})
