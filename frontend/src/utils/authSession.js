/**
 * 真实登录会话：保存登录令牌与当前用户。
 *
 * 令牌由后端签发并带有效期；勾选「记住登录状态」时放 localStorage，
 * 否则放 sessionStorage（关闭标签页即退出）。
 *
 * 同时识别旧的演示会话键，保证已有的本地验证脚本还能打开页面；
 * 旧会话没有令牌，真实后端会要求重新登录。
 */
const SESSION_KEY = 'resume-helper-auth-session'
const LEGACY_DEMO_KEY = 'resume-helper-demo-session'

let transientSession = null

function readSession(storage) {
  try {
    const value = storage.getItem(SESSION_KEY)
    if (!value) return null

    const session = JSON.parse(value)
    if (!session?.token || !session?.user?.id) return null
    if (session.expiresAt && Date.parse(session.expiresAt) <= Date.now()) return null
    return session
  } catch {
    return null
  }
}

function readLegacyDemoSession() {
  try {
    const value = window.localStorage.getItem(LEGACY_DEMO_KEY)
      || window.sessionStorage.getItem(LEGACY_DEMO_KEY)
    if (!value) return null

    const session = JSON.parse(value)
    if (session?.authenticated !== true) return null
    return {
      token: '',
      expiresAt: '',
      legacyDemo: true,
      user: {
        id: 'demo-user',
        email: session.email || 'demo@resume-helper.cn',
        displayName: session.displayName || '林同学'
      }
    }
  } catch {
    return null
  }
}

export function getAuthSession() {
  try {
    const hasNewSession = Boolean(
      window.localStorage.getItem(SESSION_KEY) || window.sessionStorage.getItem(SESSION_KEY)
    )
    if (hasNewSession) {
      const session = readSession(window.localStorage) || readSession(window.sessionStorage)
      if (session) return session
      // 新会话存在但已过期/损坏时，不要退回旧的演示会话。
      clearAuthSession()
      return null
    }
    return transientSession || readLegacyDemoSession()
  } catch {
    return transientSession || readLegacyDemoSession()
  }
}

export function getAuthToken() {
  return getAuthSession()?.token || ''
}

export function getAuthUser() {
  return getAuthSession()?.user || null
}

export function isAuthenticated() {
  return Boolean(getAuthSession())
}

/**
 * 登录/注册成功后保存会话。
 *
 * @param {{token:string, expiresAt:string, user:object}} payload 后端返回的数据
 * @param {boolean} remember true 写 localStorage，false 只写 sessionStorage
 */
export function saveAuthSession(payload, remember = true) {
  const session = {
    token: payload.token,
    expiresAt: payload.expiresAt || '',
    user: payload.user,
    createdAt: new Date().toISOString()
  }
  const serialized = JSON.stringify(session)

  try {
    window.localStorage.removeItem(SESSION_KEY)
    window.sessionStorage.removeItem(SESSION_KEY)
    window.localStorage.removeItem(LEGACY_DEMO_KEY)
    window.sessionStorage.removeItem(LEGACY_DEMO_KEY)
    const storage = remember ? window.localStorage : window.sessionStorage
    storage.setItem(SESSION_KEY, serialized)
    transientSession = session
    return { ok: true }
  } catch {
    // 浏览器禁用存储时，当前标签页仍然可用。
    transientSession = session
    return { ok: true, storageUnavailable: true }
  }
}

export function clearAuthSession() {
  transientSession = null
  try {
    window.localStorage.removeItem(SESSION_KEY)
    window.sessionStorage.removeItem(SESSION_KEY)
    window.localStorage.removeItem(LEGACY_DEMO_KEY)
    window.sessionStorage.removeItem(LEGACY_DEMO_KEY)
  } catch {
    // 清理失败不能阻止退出登录后的跳转。
  }
}
