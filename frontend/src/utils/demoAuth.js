import { createDraftStore } from '../interview/draftStore.js'
import { getLocalApiBaseUrl, getUserId } from '../api/requestContext.js'

export const DEMO_CREDENTIALS = Object.freeze({
  email: 'demo@resume-helper.cn',
  password: 'ResumeDemo2026!'
})

const SESSION_KEY = 'resume-helper-demo-session'
let transientSession = null

function readSession(storage) {
  try {
    const value = storage.getItem(SESSION_KEY)
    if (!value) return null

    const session = JSON.parse(value)
    return session?.authenticated === true ? session : null
  } catch {
    return null
  }
}

export function getDemoSession() {
  try {
    return readSession(window.localStorage) || readSession(window.sessionStorage) || transientSession
  } catch {
    return transientSession
  }
}

export function createDemoSession(email, password, remember = true) {
  const normalizedEmail = String(email || '').trim().toLowerCase()
  const matchesDemoAccount = normalizedEmail === DEMO_CREDENTIALS.email && password === DEMO_CREDENTIALS.password

  if (!matchesDemoAccount) return { ok: false }

  const sessionData = {
    authenticated: true,
    email: DEMO_CREDENTIALS.email,
    displayName: '林同学',
    createdAt: new Date().toISOString()
  }
  const session = JSON.stringify(sessionData)

  try {
    window.localStorage.removeItem(SESSION_KEY)
    window.sessionStorage.removeItem(SESSION_KEY)
    const storage = remember ? window.localStorage : window.sessionStorage
    storage.setItem(SESSION_KEY, session)
    transientSession = sessionData
    return { ok: true }
  } catch {
    transientSession = sessionData
    return { ok: true, storageUnavailable: true }
  }
}

export function clearDemoSession() {
  transientSession = null
  try {
    window.localStorage.removeItem(SESSION_KEY)
    window.sessionStorage.removeItem(SESSION_KEY)
    // 退出登录时清掉当前服务地址与用户的本机草稿，避免换账号看到别人草稿。
    createDraftStore({ storage: window.localStorage, scope: { apiBaseUrl: getLocalApiBaseUrl(), userId: getUserId() } }).clearScope()
  } catch {
    // The browser may block storage; clearing it must not prevent navigation.
  }
}
