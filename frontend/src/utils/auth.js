import { clearAuthSession } from './authSession.js'
import { getLocalApiBaseUrl, getUserId } from '../api/requestContext.js'
import { createDraftStore } from '../interview/draftStore.js'

/**
 * 退出登录：清掉令牌，同时清掉当前用户在本机的答题草稿。
 *
 * 先读 scope 再清会话——顺序反了就会拿不到用户 id，草稿会留在本机。
 */
export function clearAuthState() {
  try {
    createDraftStore({
      storage: window.localStorage,
      scope: { apiBaseUrl: getLocalApiBaseUrl(), userId: getUserId() }
    }).clearScope()
  } catch {
    // 存储不可用时仍然要退出登录。
  }
  clearAuthSession()
}
