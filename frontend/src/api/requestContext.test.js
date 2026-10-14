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
