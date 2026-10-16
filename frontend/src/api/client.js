import axios from 'axios'
import { getAiSettings, setAiSettings, getLocalApiBaseUrl, getUserId } from './requestContext.js'

/**
 * 岗位模块的 HTTP 客户端。
 *
 * 后端地址怎么定：
 *  - **本地开发**：不配 `VITE_API_BASE_URL` 时，请求走相对路径 `/api/xxx`，由 Vite proxy 转发。
 *  - **部署到 Vercel**：用户可在「本地服务设置」里配置自己电脑上的 Java 服务；浏览器中的
 *    `localhost` 指向当前访问者的电脑。需要多人共用时，再通过 `VITE_API_BASE_URL` 配公网后端。
 */
const configuredBaseURL = import.meta.env.VITE_API_BASE_URL || (import.meta.env.PROD ? 'http://localhost:8080' : '')
function getEffectiveBaseURL() {
  return getLocalApiBaseUrl() || configuredBaseURL
}

/** 统一的接口错误：带上后端返回的 message 和 HTTP 状态码，方便页面直接展示。 */
export class ApiError extends Error {
  constructor(message, status = 0, cause = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.cause = cause
  }
}

const http = axios.create({
  baseURL: getEffectiveBaseURL(),
  // 智能体多轮检索 + 大模型生成解释可能要几十秒，超时给宽一点
  timeout: 90000,
  headers: { 'Content-Type': 'application/json' }
})

/* ---------------------------------------------------------------------------
 * 用户的 AI token
 *
 * 需求是「让用户填自己的 token」，所以它只存在浏览器里，每次请求作为请求头传给后端。
 * 刻意用 sessionStorage 而不是 localStorage：关闭标签页即失效，泄露面更小。
 * 后端只在单次请求内使用它，不落库、不打日志。
 * ------------------------------------------------------------------------- */
export function getAiKey() { return getAiSettings().apiKey }

export function setAiKey(key) {
  setAiSettings({ ...getAiSettings(), apiKey: key })
}

export function hasAiKey() { return getAiKey().length > 0 }

http.interceptors.request.use((config) => {
  config.baseURL = getEffectiveBaseURL()
  config.headers['X-User-Id'] = getUserId()
  const settings = getAiSettings()
  for (const [field, header] of Object.entries({ apiKey: 'X-AI-Api-Key', model: 'X-AI-Model', baseUrl: 'X-AI-Base-Url' })) {
    if (settings[field]) config.headers[header] = settings[field]
    else delete config.headers[header]
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const payload = response.data
    // 后端统一返回 { success, message, data }
    if (payload && typeof payload === 'object' && 'success' in payload) {
      if (payload.success) {
        return payload.data
      }
      // success=false 但 HTTP 是 200（例如「没有找到该岗位」）
      throw new ApiError(payload.message || '请求失败', response.status)
    }
    return payload
  },
  (error) => {
    throw toApiError(error)
  }
)

/** 把 axios 的各种失败形态翻译成用户看得懂的中文。 */
function toApiError(error) {
  if (error instanceof ApiError) {
    return error
  }

  const response = error?.response
  if (response) {
    const backendMessage = response.data?.message
    if (backendMessage) {
      return new ApiError(backendMessage, response.status, error)
    }
    if (response.status === 404) {
      return new ApiError('接口不存在（404）。请确认后端已启动，且 vite 代理指向了正确的端口', 404, error)
    }
    if (response.status >= 500) {
      return new ApiError(`后端出错了（HTTP ${response.status}），请看后端日志`, response.status, error)
    }
    return new ApiError(`请求失败（HTTP ${response.status}）`, response.status, error)
  }

  if (error?.code === 'ECONNABORTED') {
    return new ApiError('请求超时。智能体多轮检索接大模型时会比较慢，请重试', 0, error)
  }

  return new ApiError('连接不到后端服务。请确认本地 Java 服务已启动，或前往“本地服务设置”检查地址和连接。', 0, error)
}

export default http
