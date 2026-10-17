import { decodePcmFrame, parseServerMessage } from './voiceProtocol.ts'
import type { VoiceSessionTicket } from './voiceContracts.ts'
import type { VoiceClientMessage, VoiceSocketEvent } from './voiceProtocol.ts'

type Clock = {
  now(): number
  setTimeout(callback: () => void, delay: number): unknown
  clearTimeout(id: unknown): void
  setInterval(callback: () => void, delay: number): unknown
  clearInterval(id: unknown): void
}

type WebSocketConstructor = new (url: string) => SocketLike

type SocketLike = {
  readyState: number
  binaryType: string
  onopen: ((event?: Event) => void) | null
  onmessage: ((event: MessageEvent) => void) | null
  onclose: ((event: CloseEvent) => void) | null
  onerror: ((event?: Event) => void) | null
  send(data: string | ArrayBuffer): void
  close(code?: number, reason?: string): void
}

const defaultClock: Clock = {
  now: () => Date.now(),
  setTimeout: (callback, delay) => globalThis.setTimeout(callback, delay),
  clearTimeout: id => globalThis.clearTimeout(id as ReturnType<typeof setTimeout>),
  setInterval: (callback, delay) => globalThis.setInterval(callback, delay),
  clearInterval: id => globalThis.clearInterval(id as ReturnType<typeof setInterval>),
}

function error(message: string) { return new Error(message) }

export function createVoiceSocket({
  url,
  WebSocketImpl = globalThis.WebSocket as unknown as WebSocketConstructor,
  clock = defaultClock,
  onEvent = () => {},
  handshakeTimeoutMs = 5000,
  heartbeatIntervalMs = 15000,
  heartbeatTimeoutMs = 30000,
}: {
  url: string
  WebSocketImpl?: WebSocketConstructor
  clock?: Clock
  onEvent?: (event: VoiceSocketEvent) => void
  handshakeTimeoutMs?: number
  heartbeatIntervalMs?: number
  heartbeatTimeoutMs?: number
}) {
  let ws: SocketLike | null = null
  let ready = false
  let closed = false
  let lastActivity = 0
  let heartbeatTimer: unknown = null
  let handshakeTimer: unknown = null
  let resolveReady: (() => void) | null = null
  let rejectReady: ((cause: Error) => void) | null = null

  function cleanupTimers() {
    if (handshakeTimer !== null) clock.clearTimeout(handshakeTimer)
    if (heartbeatTimer !== null) clock.clearInterval(heartbeatTimer)
    handshakeTimer = null
    heartbeatTimer = null
  }
  function finishClose(code: number, reason: string) {
    if (closed) return
    closed = true
    ready = false
    cleanupTimers()
    rejectReady?.(error(reason || '语音连接已关闭'))
    resolveReady = null
    rejectReady = null
    onEvent({ type: 'closed', code, reason })
  }
  function fail(cause: unknown) {
    const message = cause instanceof Error ? cause.message : '语音连接协议错误'
    if (!closed) {
      try { ws?.close(1002, 'protocol error') } catch { /* close event may not arrive */ }
      finishClose(1002, message)
    }
  }
  function send(message: VoiceClientMessage) {
    if (!ws || closed || ws.readyState !== 1) throw error('语音连接尚未就绪')
    ws.send(JSON.stringify(message))
  }
  function connect(ticket: VoiceSessionTicket): Promise<void> {
    if (ws || closed) return Promise.reject(error('语音连接已创建或已关闭'))
    let parsed: URL
    try { parsed = new URL(url) } catch { return Promise.reject(error('语音 WebSocket 地址无效')) }
    if (!['ws:', 'wss:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.hash
        || !parsed.pathname.endsWith('/api/interview-voice/ws') || parsed.search) {
      return Promise.reject(error('语音 WebSocket 地址或路径无效'))
    }
    if (ticket.websocketPath !== '/api/interview-voice/ws' || ticket.protocolVersion !== 1 || !ticket.ticket || !ticket.voiceSessionId) {
      return Promise.reject(error('语音会话凭据无效'))
    }
    try { ws = new WebSocketImpl(url) as unknown as SocketLike }
    catch (cause) { return Promise.reject(cause instanceof Error ? cause : error('无法创建语音连接')) }
    ws.binaryType = 'arraybuffer'
    const pending = new Promise<void>((resolve, reject) => { resolveReady = resolve; rejectReady = reject })
    handshakeTimer = clock.setTimeout(() => {
      if (!ready && !closed) {
        try { ws?.close(1000, 'handshake timeout') } catch { /* no-op */ }
        finishClose(1000, '语音连接握手超时')
      }
    }, handshakeTimeoutMs)
    ws.onopen = () => {
      if (closed) return
      lastActivity = clock.now()
      try { send({ v: 1, type: 'session.hello', voiceSessionId: ticket.voiceSessionId, ticket: ticket.ticket }) }
      catch (cause) { fail(cause) }
    }
    ws.onmessage = event => {
      if (closed) return
      lastActivity = clock.now()
      try {
        if (event.data instanceof ArrayBuffer) {
          onEvent({ type: 'audio', frame: decodePcmFrame(event.data) })
          return
        }
        if (typeof event.data !== 'string') throw error('语音服务返回了不支持的数据')
        const message = parseServerMessage(JSON.parse(event.data))
        if (message.type === 'session.ready') {
          if (ready || message.voiceSessionId !== ticket.voiceSessionId) throw error('语音会话身份不匹配')
          ready = true
          if (handshakeTimer !== null) clock.clearTimeout(handshakeTimer)
          handshakeTimer = null
          resolveReady?.()
          resolveReady = null
          rejectReady = null
          heartbeatTimer = clock.setInterval(() => {
            if (clock.now() - lastActivity >= heartbeatTimeoutMs) {
              try { ws?.close(1000, 'heartbeat timeout') } catch { /* no-op */ }
              finishClose(1000, '语音连接心跳超时')
            } else if (ready) {
              try { send({ v: 1, type: 'session.ping', nonce: String(clock.now()) }) }
              catch (cause) { fail(cause) }
            }
          }, heartbeatIntervalMs)
        } else if (message.type === 'session.failed') {
          onEvent(message)
          throw error(message.message)
        } else if (!ready) throw error('语音服务在握手完成前发送了消息')
        onEvent(message)
      } catch (cause) { fail(cause) }
    }
    ws.onerror = () => {
      if (!closed) fail(error('语音网络连接失败'))
    }
    ws.onclose = event => finishClose(event.code, event.reason || '')
    return pending
  }

  return {
    connect,
    start(questionId: string, requestId: string) {
      if (!ready) throw error('语音连接尚未就绪')
      send({ v: 1, type: 'speech.start', questionId, requestId })
    },
    cancel(requestId: string, streamId?: number) {
      if (!ready) return
      send({ v: 1, type: 'speech.cancel', requestId, ...(streamId === undefined ? {} : { streamId }) })
    },
    ack(streamId: number, receivedSeq: number, playedSamples: number) {
      if (!ready) return
      send({ v: 1, type: 'audio.ack', streamId, receivedSeq, playedSamples })
    },
    close() {
      if (closed) return
      try { if (ws?.readyState === 1) send({ v: 1, type: 'session.close' }) } catch { /* close below */ }
      try { ws?.close(1000, 'client close') } catch { /* no-op */ }
      finishClose(1000, '客户端关闭语音连接')
    },
    isReady: () => ready && !closed,
  }
}
