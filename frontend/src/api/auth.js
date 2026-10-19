import http from './client.js'

/** 注册：邮箱 + 密码 + 可选昵称。成功后直接返回登录令牌。 */
export function registerAccount({ email, password, displayName }) {
  return http.post('/api/auth/register', { email, password, displayName })
}

/** 邮箱密码登录。 */
export function loginAccount({ email, password }) {
  return http.post('/api/auth/login', { email, password })
}

/** 用当前令牌换取用户信息，可用于校验令牌是否还有效。 */
export function fetchCurrentUser() {
  return http.get('/api/auth/me')
}

/** 后端令牌是无状态的，这个调用只是语义上的退出；前端随后清除本地会话。 */
export function logoutAccount() {
  return http.post('/api/auth/logout').catch(() => null)
}
