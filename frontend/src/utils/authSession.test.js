import test from 'node:test'
import assert from 'node:assert/strict'
import {
  clearAuthSession,
  getAuthSession,
  getAuthToken,
  getAuthUser,
  saveAuthSession
} from './authSession.js'

function memoryStorage() {
  const values = new Map()
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, String(value)) },
    removeItem: key => values.delete(key),
    clear: () => values.clear()
  }
}

const local = memoryStorage()
const session = memoryStorage()
globalThis.window = { localStorage: local, sessionStorage: session }

const SESSION_KEY = 'resume-helper-auth-session'
const LEGACY_KEY = 'resume-helper-demo-session'

test.beforeEach(() => {
  local.clear()
  session.clear()
})

function payload(overrides = {}) {
  return {
    token: 'signed-token',
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
    user: { id: 'user-1', email: 'user@example.com', displayName: '林同学' },
    ...overrides
  }
}

test('remember=true stores the token locally and exposes the current user', () => {
  saveAuthSession(payload(), true)

  assert.equal(getAuthToken(), 'signed-token')
  assert.equal(getAuthUser().id, 'user-1')
  assert.equal(getAuthUser().displayName, '林同学')
  assert.ok(local.getItem(SESSION_KEY))
  assert.equal(session.getItem(SESSION_KEY), null)
})

test('remember=false keeps the session in this tab only', () => {
  saveAuthSession(payload(), false)

  assert.equal(local.getItem(SESSION_KEY), null)
  assert.ok(session.getItem(SESSION_KEY))
  assert.equal(getAuthToken(), 'signed-token')
})

test('an expired token is dropped instead of falling back to the demo session', () => {
  local.setItem(LEGACY_KEY, JSON.stringify({ authenticated: true, displayName: '旧演示' }))
  saveAuthSession(payload({ expiresAt: new Date(Date.now() - 1_000).toISOString() }), true)

  assert.equal(getAuthSession(), null)
  assert.equal(getAuthToken(), '')
  assert.equal(local.getItem(SESSION_KEY), null)
})

test('the old demo session can still open the prototype but has no token', () => {
  local.setItem(LEGACY_KEY, JSON.stringify({
    authenticated: true,
    email: 'demo@resume-helper.cn',
    displayName: '林同学'
  }))

  assert.equal(getAuthToken(), '')
  assert.deepEqual(getAuthUser(), {
    id: 'demo-user',
    email: 'demo@resume-helper.cn',
    displayName: '林同学'
  })
})

test('clearing removes the real session and the legacy key', () => {
  saveAuthSession(payload(), true)
  local.setItem(LEGACY_KEY, JSON.stringify({ authenticated: true }))

  clearAuthSession()

  assert.equal(local.getItem(SESSION_KEY), null)
  assert.equal(session.getItem(SESSION_KEY), null)
  assert.equal(local.getItem(LEGACY_KEY), null)
  assert.equal(getAuthSession(), null)
})
