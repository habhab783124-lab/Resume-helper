export type RequestDeadlineOptions = {
  signal?: AbortSignal
  timeoutMs?: number
}

export function withRequestDeadline<T>(
  operation: (signal: AbortSignal) => Promise<T>,
  options: RequestDeadlineOptions = {},
): Promise<T> {
  const controller = new AbortController()
  const { signal, timeoutMs } = options
  let timer: ReturnType<typeof setTimeout> | undefined
  let timedOut = false
  let rejectAbort: ((reason: unknown) => void) | undefined
  const abortPromise = new Promise<never>((_, reject) => { rejectAbort = reject })
  const abortFromCaller = () => {
    const reason = signal?.reason ?? new DOMException('The operation was aborted', 'AbortError')
    controller.abort(reason)
    rejectAbort?.(reason)
  }

  if (signal?.aborted) abortFromCaller()
  else signal?.addEventListener('abort', abortFromCaller, { once: true })

  const timeoutPromise = new Promise<never>((_, reject) => {
    if (timeoutMs !== undefined && timeoutMs > 0) {
      timer = setTimeout(() => {
        timedOut = true
        const error = Object.assign(
          new Error('浏览器请求未确认，请读取会话状态后再决定是否重试'),
          { code: 'BROWSER_UNCONFIRMED', retryable: true },
        )
        controller.abort(error)
        reject(error)
      }, timeoutMs)
    }
  })

  const operationSignal = timeoutMs === undefined && signal ? signal : controller.signal
  const operationPromise = Promise.resolve().then(() => operation(operationSignal))
  return Promise.race([operationPromise, abortPromise, timeoutPromise]).catch(error => {
    if (timedOut) throw error
    throw error
  }).finally(() => {
    if (timer !== undefined) clearTimeout(timer)
    signal?.removeEventListener('abort', abortFromCaller)
  })
}
