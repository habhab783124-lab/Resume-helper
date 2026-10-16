import test from 'node:test'
import assert from 'node:assert/strict'
import { createDraftStore } from './draftStore.js'

const DAY = 86400000

function memoryStorage() {
  const values = new Map()
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, String(value)) },
    removeItem: key => values.delete(key),
    key: index => [...values.keys()][index] ?? null,
    get length() { return values.size },
    snapshot: () => new Map([...values].map(([k, v]) => [k, JSON.parse(v)])),
  }
}

function store(storage, now = () => 1000, scope = { apiBaseUrl: '', userId: 'demo-user' }) {
  return createDraftStore({ storage, now, scope })
}

test('draft survives a new store instance and never crosses questions', () => {
  const storage = memoryStorage()
  const options = { storage, now: () => 1000, scope: { apiBaseUrl: '', userId: 'demo-user' } }
  createDraftStore(options).save('s1', 'q1', { text: '尚未提交的回答', previewText: '' })
  assert.equal(createDraftStore(options).load('s1', 'q1').text, '尚未提交的回答')
  assert.equal(createDraftStore(options).load('s1', 'q2'), null)
})

test('draft older than seven days is dropped but a boundary-old one is kept', () => {
  const storage = memoryStorage()
  const now = () => 1000
  const options = { storage, now, scope: { apiBaseUrl: '', userId: 'demo-user' } }

  const expired = createDraftStore(options)
  expired.save('s1', 'q1', { text: '过期草稿' })

  const atLimit = createDraftStore({ ...options, now: () => 1000 + DAY * 7 })
  assert.equal(atLimit.load('s1', 'q1').text, '过期草稿')
  assert.equal(createDraftStore({ ...options, now: () => 1000 + DAY * 7 + 1 }).load('s1', 'q1'), null)
})

test('an unreadable record is treated as absent', () => {
  const storage = memoryStorage()
  const key = createDraftStore({ storage, now: () => 1000, scope: { apiBaseUrl: '', userId: 'demo-user' } }).keyFor('s1', 'q1')
  for (const raw of ['not json', '42', '{"version":1,"sessionId":"s1","questionId":"q1","updatedAt":1000}']) {
    storage.setItem(key, raw)
    assert.equal(createDraftStore({ storage, now: () => 1000, scope: { apiBaseUrl: '', userId: 'demo-user' } }).load('s1', 'q1'), null)
  }
})

test('quota exhaustion is reported as QUOTA so the page can still answer by text', () => {
  const storage = memoryStorage()
  storage.setItem = () => { throw Object.assign(new Error('quota'), { name: 'QuotaExceededError' }) }
  assert.deepEqual(createDraftStore({ storage, now: () => 1000, scope: { apiBaseUrl: '', userId: 'demo-user' } })
    .save('s1', 'q1', { text: '回答' }), { saved: false, reason: 'QUOTA' })

  storage.setItem = () => { throw new Error('storage disabled') }
  assert.deepEqual(createDraftStore({ storage, now: () => 1000, scope: { apiBaseUrl: '', userId: 'demo-user' } })
    .save('s1', 'q1', { text: '回答' }), { saved: false, reason: 'UNAVAILABLE' })
})

test('each backend address keeps its own drafts', () => {
  const storage = memoryStorage()
  const local = createDraftStore({ storage, now: () => 1000, scope: { apiBaseUrl: '', userId: 'demo-user' } })
  const other = createDraftStore({ storage, now: () => 1000, scope: { apiBaseUrl: 'http://127.0.0.1:8080', userId: 'demo-user' } })
  local.save('s1', 'q1', { text: '本机后端' })
  other.save('s1', 'q1', { text: '另一台后端' })
  assert.equal(local.load('s1', 'q1').text, '本机后端')
  assert.equal(other.load('s1', 'q1').text, '另一台后端')
})

test('users never share drafts even on the same backend', () => {
  const storage = memoryStorage()
  const a = createDraftStore({ storage, now: () => 1000, scope: { apiBaseUrl: '', userId: 'demo-user' } })
  const b = createDraftStore({ storage, now: () => 1000, scope: { apiBaseUrl: '', userId: 'another-user' } })
  a.save('s1', 'q1', { text: 'A 的草稿' })
  assert.equal(b.load('s1', 'q1'), null)
})

test('clearScope removes only the current service and user', () => {
  const storage = memoryStorage()
  const local = createDraftStore({ storage, now: () => 1000, scope: { apiBaseUrl: '', userId: 'demo-user' } })
  const other = createDraftStore({ storage, now: () => 1000, scope: { apiBaseUrl: 'http://127.0.0.1:8080', userId: 'demo-user' } })
  local.save('s1', 'q1', { text: '本机草稿' })
  local.save('s1', 'q2', { text: '本机另一题' })
  other.save('s1', 'q1', { text: '其他后端草稿' })

  assert.equal(local.clearScope(), 2)
  assert.equal(local.load('s1', 'q1'), null)
  assert.equal(local.load('s1', 'q2'), null)
  assert.equal(other.load('s1', 'q1').text, '其他后端草稿')
})

test('save is a no-op for identical content and drops empty drafts', () => {
  const storage = memoryStorage()
  const options = { storage, now: () => 1000, scope: { apiBaseUrl: '', userId: 'demo-user' } }
  const first = createDraftStore(options)
  first.save('s1', 'q1', { text: '草稿', previewText: '预览' })
  assert.equal(storage.length, 1)
  assert.deepEqual(first.save('s1', 'q1', { text: '草稿', previewText: '预览' }), { saved: true })
  assert.equal(storage.length, 1)

  assert.deepEqual(first.save('s1', 'q1', { text: '', previewText: '' }), { saved: true })
  assert.equal(storage.length, 0)
})

test('remove clears a single question and keeps siblings', () => {
  const storage = memoryStorage()
  const options = { storage, now: () => 1000, scope: { apiBaseUrl: '', userId: 'demo-user' } }
  const drafts = createDraftStore(options)
  drafts.save('s1', 'q1', { text: '甲' })
  drafts.save('s1', 'q2', { text: '乙' })
  assert.equal(drafts.remove('s1', 'q1'), true)
  assert.equal(drafts.load('s1', 'q1'), null)
  assert.equal(drafts.load('s1', 'q2').text, '乙')
})
