import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import axios from 'axios'
import { getAiSettings, setAiSettings, getLocalApiBaseUrl, getUserId } from './requestContext.js'

const source = readFileSync(new URL('./client.js', import.meta.url), 'utf8')
  .replace(/^import .*$/gm, '').replace(/import\.meta\.env/g, '({})')
  .replace(/export default http/, '').replace(/export /g, '')
const client = new Function('axios', 'getAiSettings', 'setAiSettings', 'getLocalApiBaseUrl', 'getUserId', 'window', `${source}\nreturn { http, getAiKey, setAiKey, hasAiKey }`)(axios, getAiSettings, setAiSettings, getLocalApiBaseUrl, getUserId, { sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} } })

test('axios uses shared current identity and settings while key helpers preserve model', async () => {
  setAiSettings({ apiKey: 'first', model: 'model', baseUrl: 'https://models.test/v1' })
  client.setAiKey(' second ')
  assert.equal(client.getAiKey(), 'second')
  assert.equal(client.hasAiKey(), true)
  assert.equal(getAiSettings().model, 'model')
  const calls = []
  const adapter = async config => { calls.push(config); return { status: 200, data: { success: true, data: {} }, config } }
  await client.http.get('/api/materials', { adapter })
  client.setAiKey('')
  assert.equal(client.hasAiKey(), false)
  await client.http.get('/api/materials', { adapter, headers: { 'X-AI-Api-Key': 'stale' } })
  assert.equal(calls[0].headers['X-User-Id'], 'demo-user')
  assert.equal(calls[0].headers['X-AI-Api-Key'], 'second')
  assert.equal(calls[0].headers['X-AI-Model'], 'model')
  assert.equal(calls[0].headers['X-AI-Base-Url'], 'https://models.test/v1')
  assert.equal(calls[1].headers['X-AI-Api-Key'], undefined)
})
