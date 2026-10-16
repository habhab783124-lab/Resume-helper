// 按「服务地址 + 用户 + 场次 + 题目」分区的本机回答草稿。
//
// localStorage 是浏览器全局的，不同后端指向同一地址时草稿会互相串扰，
// 所以服务地址进 key 而不是仅靠 JSON 内容校验。存储注入以便无 DOM 测试；
// 默认走浏览器 localStorage，注入的时钟让过期行为可复现。
const VERSION = 1
const DEFAULT_TTL_MS = 7 * 24 * 60 * 60 * 1000
const keyPrefix = `career-studio-draft-v${VERSION}`

function isQuotaError(cause) {
  return cause?.name === 'QuotaExceededError' || cause?.code === 22
}

function toSafeScope(scope = {}) {
  return {
    apiBaseUrl: typeof scope.apiBaseUrl === 'string' ? scope.apiBaseUrl : '',
    userId: typeof scope.userId === 'string' ? scope.userId : '',
  }
}

function storageKey(scope, sessionId, questionId) {
  return `${keyPrefix}|${scope.apiBaseUrl}|${scope.userId}|${sessionId}|${questionId}`
}

export function createDraftStore(options = {}) {
  const {
    storage = globalThis.localStorage,
    now = () => Date.now(),
    ttlMs = DEFAULT_TTL_MS,
  } = options
  const scope = toSafeScope(options.scope)

  function read(sessionId, questionId) {
    if (!sessionId || !questionId) return null
    try {
      const raw = storage.getItem(storageKey(scope, sessionId, questionId))
      if (!raw) return null
      const value = JSON.parse(raw)
      if (!value || typeof value !== 'object' || value.version !== VERSION) return null
      if (value.sessionId !== sessionId || value.questionId !== questionId) return null
      if (typeof value.updatedAt !== 'number' || value.updatedAt + ttlMs < now()) return null
      if (typeof value.text !== 'string') return null
      return value
    } catch {
      return null
    }
  }

  function pruneExpired(keepKey) {
    try {
      for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index)
        if (!key || key === keepKey || !key.startsWith(`${keyPrefix}|${scope.apiBaseUrl}|${scope.userId}|`)) continue
        try {
          const value = JSON.parse(storage.getItem(key))
          if (value && typeof value.updatedAt === 'number' && value.updatedAt + ttlMs < now()) storage.removeItem(key)
        } catch {
          storage.removeItem(key)
        }
      }
    } catch {
      /* 过期清理失败不影响保存。 */
    }
  }

  function save(sessionId, questionId, payload = {}) {
    if (!sessionId || !questionId) return { saved: false, reason: 'UNAVAILABLE' }
    const text = typeof payload.text === 'string' ? payload.text : ''
    const previewText = typeof payload.previewText === 'string' ? payload.previewText : ''
    const key = storageKey(scope, sessionId, questionId)
    try {
      // 空草稿说明这道题又回到空白，删掉记录而不是留下墓碑。
      if (!text && !previewText) {
        storage.removeItem(key)
        pruneExpired(key)
        return { saved: true }
      }
      const existing = read(sessionId, questionId)
      if (!existing || existing.text !== text || existing.previewText !== previewText) {
        storage.setItem(key, JSON.stringify({
          version: VERSION,
          sessionId,
          questionId,
          text,
          previewText,
          updatedAt: now(),
        }))
      }
      if (!text) pruneExpired(key)
      return { saved: true }
    } catch (cause) {
      return { saved: false, reason: isQuotaError(cause) ? 'QUOTA' : 'UNAVAILABLE' }
    }
  }

  function load(sessionId, questionId) {
    return read(sessionId, questionId)
  }

  function remove(sessionId, questionId) {
    try {
      storage.removeItem(storageKey(scope, sessionId, questionId))
      return true
    } catch {
      return false
    }
  }

  function clearScope() {
    let removed = 0
    try {
      const doomed = []
      for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index)
        if (key && key.startsWith(`${keyPrefix}|${scope.apiBaseUrl}|${scope.userId}|`)) doomed.push(key)
      }
      doomed.forEach(key => storage.removeItem(key))
      removed = doomed.length
    } catch {
      /* 存储不可用时无可清理。 */
    }
    return removed
  }

  return { save, load, remove, clearScope, keyFor: (sessionId, questionId) => storageKey(scope, sessionId, questionId) }
}
