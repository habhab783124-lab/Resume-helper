import { computed, ref, shallowRef, watch } from 'vue'
import { useVoiceRecorder } from './useVoiceRecorder.js'
import { useQuestionSpeech } from './useQuestionSpeech.js'

/** Voice produces a separate, explicitly accepted draft. Interview turns remain owned by the page. */
export function useInterviewVoice({
  api, getContext, getDraft, setDraft, syncSession = async () => {},
  createRecorder = useVoiceRecorder, createSpeech = useQuestionSpeech,
  createUrl = blob => URL.createObjectURL(blob), revokeUrl = url => URL.revokeObjectURL(url),
  pauseReplay = () => {}, requestId = () => crypto.randomUUID(), timeoutMs = 90000,
} = {}) {
  const capabilities = ref(null)
  const capabilityState = ref('idle')
  const capabilityError = ref('')
  const recorder = shallowRef(null)
  const speech = createSpeech({ api })
  const preview = ref(null)
  const error = ref('')
  const speechQuestionId = ref('')
  const transcribing = ref(false)
  const recordingUrl = ref('')
  const recorderState = computed(() => recorder.value?.state.value || 'idle')
  const blob = computed(() => recorder.value?.blob.value || null)
  const durationSeconds = computed(() => recorder.value?.durationSeconds.value || 0)
  const recorderError = computed(() => recorder.value?.error.value || '')
  const busy = computed(() => transcribing.value || ['requesting-mic', 'recording'].includes(recorderState.value))
  const asrEnabled = computed(() => capabilityState.value === 'ready' && capabilities.value.asrEnabled === true)
  const ttsEnabled = computed(() => capabilityState.value === 'ready' && capabilities.value.ttsEnabled === true)
  const maxSeconds = computed(() => capabilities.value?.maxRecordingSeconds || 120)
  const maxBytes = computed(() => capabilities.value?.maxAudioBytes || 5 * 1024 * 1024)
  let generation = 0, capabilityGeneration = 0, contextKey = '', request = null, disposed = false, recorderLimits = '', suppressStopTranscription = false

  function clearRecordingUrl() {
    pauseReplay()
    if (recordingUrl.value) revokeUrl(recordingUrl.value)
    recordingUrl.value = ''
  }
  const stopBlobWatch = watch(blob, value => {
    clearRecordingUrl()
    if (value?.size) recordingUrl.value = createUrl(value)
  }, { flush: 'sync' })
  let previousRecorderState = recorderState.value
  const stopStateWatch = watch(recorderState, value => {
    const stoppedWithAudio = previousRecorderState === 'recording' && value === 'recorded'
    previousRecorderState = value
    if (stoppedWithAudio && !suppressStopTranscription) queueMicrotask(() => { if (blob.value?.size) transcribe() })
  }, { flush: 'sync' })
  function contextSignature(context) {
    return JSON.stringify([context?.sessionId, context?.questionId, context?.status, context?.pending ?? null])
  }
  function answerable(context = getContext()) {
    return !disposed && context?.status === 'ACTIVE' && !!context.sessionId && !!context.questionId && context.pending == null && !context.blocked
  }
  function matches(context, my) {
    return generation === my && answerable() && contextSignature(context) === contextSignature(getContext())
  }
  function configureRecorder() {
    const limits = { maxSeconds: maxSeconds.value, maxBytes: maxBytes.value }
    const key = JSON.stringify(limits)
    if (recorder.value && recorderLimits === key) return
    recorder.value?.dispose()
    recorder.value = createRecorder(limits)
    recorderLimits = key
  }
  async function loadCapabilities(options) {
    if (disposed || busy.value) return
    if (capabilityState.value === 'loading') {
      if (!options?.signal) return
      capabilityGeneration++
      capabilityState.value = 'idle'
    }
    const my = ++capabilityGeneration
    capabilityState.value = 'loading'
    capabilityError.value = ''
    try {
      const value = await api.getSpeechCapabilities(options)
      if (disposed || my !== capabilityGeneration) return
      if (typeof value?.asrEnabled !== 'boolean' || typeof value?.ttsEnabled !== 'boolean') throw new Error('语音能力返回格式异常。')
      capabilities.value = { ...value,
        maxRecordingSeconds: Number.isFinite(value.maxRecordingSeconds) && value.maxRecordingSeconds > 0 ? Math.min(value.maxRecordingSeconds, 120) : 120,
        maxAudioBytes: Number.isFinite(value.maxAudioBytes) && value.maxAudioBytes > 0 ? Math.min(value.maxAudioBytes, 5 * 1024 * 1024) : 5 * 1024 * 1024 }
      // A capability retry must not discard an unsent recording or its preview.
      // New limits validate retained audio below and configure the next recording.
      if (!blob.value) configureRecorder()
      capabilityState.value = 'ready'
      return capabilities.value
    } catch {
      if (disposed || my !== capabilityGeneration) return
      capabilityState.value = 'unavailable'
      capabilityError.value = '暂时无法确认语音能力，可以继续文字作答或重试。'
      return null
    }
  }
  function cancelTranscription() {
    generation += 1
    if (request) { clearTimeout(request.timer); request.controller.abort(); request = null }
    transcribing.value = false
    preview.value = null
  }
  function clearContext() {
    cancelTranscription()
    recorder.value?.dispose()
    speech.dispose()
    speechQuestionId.value = ''
    clearRecordingUrl()
    error.value = ''
  }
  function syncContext() {
    const next = contextSignature(getContext())
    if (contextKey !== next) { clearContext(); contextKey = next }
  }
  async function startRecording() {
    if (!asrEnabled.value || !answerable() || busy.value) return
    cancelTranscription()
    error.value = ''
    speech.stop()
    pauseReplay()
    configureRecorder()
    recorder.value.cancel()
    await recorder.value.start()
  }
  async function stopRecording({ transcribe: shouldTranscribe = true } = {}) {
    if (recorderState.value !== 'recording') return false
    suppressStopTranscription = true
    try {
      const stopped = recorder.value.stop()
      if (stopped?.then) await stopped
      if (shouldTranscribe && blob.value?.size) await transcribe()
      return true
    } finally { suppressStopTranscription = false }
  }
  function cancelRecording() {
    cancelTranscription()
    recorder.value?.cancel()
    clearRecordingUrl()
  }
  function playQuestion(question = {}) {
    const context = getContext()
    const sessionId = question.sessionId || context?.sessionId
    const questionId = question.questionId || context?.questionId
    if (!ttsEnabled.value || busy.value || disposed || context?.blocked || !sessionId || !questionId || sessionId !== context?.sessionId) return
    pauseReplay()
    speechQuestionId.value = questionId
    speech.play(sessionId, questionId)
  }
  function replayRecording() {
    if (busy.value || getContext()?.blocked) { pauseReplay(); return }
    speech.stop()
  }
  async function transcribe() {
    if (!asrEnabled.value || !answerable() || busy.value) return
    error.value = ''
    if (!blob.value?.size) { error.value = '录音为空，请重新录音后识别。'; return }
    if (blob.value.size > maxBytes.value) { error.value = '录音超过大小限制，请缩短后重新录音。'; return }
    if (durationSeconds.value > maxSeconds.value) { error.value = '录音超过当前时长限制，请缩短后重新录音。'; return }
    cancelTranscription()
    speech.stop()
    pauseReplay()
    const my = generation
    const context = { ...getContext() }
    const id = requestId()
    const controller = new AbortController()
    const operation = { controller, timer: null }
    request = operation
    transcribing.value = true
    let onAbort
    try {
      // Both ASR and conflict recovery share this deadline. Abort must also settle
      // the wait when a transport ignores its signal.
      const stopped = new Promise((_, reject) => {
        onAbort = () => reject(Object.assign(new Error('语音识别已取消。'), { name: 'AbortError' }))
        controller.signal.addEventListener('abort', onAbort, { once: true })
        operation.timer = setTimeout(() => {
          reject(new Error('语音识别超时，请重试或改用文字回答。'))
          controller.abort()
        }, timeoutMs)
      })
      const extension = blob.value.type.includes('mp4') ? 'mp4' : blob.value.type.includes('ogg') ? 'ogg' : 'webm'
      // Give FormData a truthful container filename; the recorder already probes its format.
      const file = blob.value
      const namedFile = typeof File === 'function' ? new File([file], `answer.${extension}`, { type: file.type }) : file
      let result
      try {
        result = await Promise.race([api.transcribe(context.sessionId, { questionId: context.questionId, requestId: id, file: namedFile }, controller.signal), stopped])
      } catch (cause) {
        if (cause?.status !== 409 || !matches(context, my) || controller.signal.aborted) throw cause
        error.value = cause?.message || '语音识别失败，请重试或改用文字回答。'
        await Promise.race([syncSession(context, () => !controller.signal.aborted && matches(context, my), controller.signal), stopped])
        return
      }
      if (!matches(context, my)) return
      if (result?.sessionId !== context.sessionId || result?.questionId !== context.questionId || result?.requestId !== id || typeof result?.text !== 'string') throw new Error('语音识别结果格式异常，请重试。')
      if (!result.text.trim()) throw new Error('未识别到有效内容，请重试或改用文字回答。')
      preview.value = { ...context, requestId: id, generation: my, text: result.text }
    } catch (cause) {
      if (!matches(context, my)) return
      if (cause?.name !== 'AbortError') error.value = cause?.message || '语音识别失败，请重试或改用文字回答。'
    } finally {
      clearTimeout(operation.timer)
      controller.signal.removeEventListener('abort', onAbort)
      if (request === operation) { request = null; transcribing.value = false }
    }
  }
  function restorePreview(text) {
    if (typeof text !== 'string' || !text.trim() || disposed) return false
    const context = getContext()
    // A restored preview is inert until explicitly accepted, so it only needs a
    // live question — not an idle page. acceptPreview still enforces the busy lock.
    if (!context || context.status !== 'ACTIVE' || !context.sessionId || !context.questionId || context.pending != null) return false
    // Re-enters at the current generation so acceptPreview still matches.
    preview.value = { ...context, requestId: '', generation, text }
    return true
  }
  function acceptPreview(mode) {
    const value = preview.value
    if (!value || busy.value || !matches(value, value.generation)) return false
    if (mode === 'replace' || mode === 'append') {
      const existing = getDraft()
      const text = mode === 'replace' ? value.text : `${existing}${existing ? '\n' : ''}${value.text}`
      setDraft(text)
      if (text.length > 5000) error.value = '回答超过 5000 字，已保留完整内容，请精简后提交。'
    } else if (mode !== 'keep') return false
    preview.value = null
    return true
  }
  function dispose() {
    disposed = true
    capabilityGeneration += 1
    clearContext()
    stopBlobWatch()
    stopStateWatch()
  }
  return { capabilities, capabilityState, capabilityError, asrEnabled, ttsEnabled, maxSeconds, maxBytes,
    recorderState, blob, durationSeconds, recorderError, speechState: speech.state, speechError: speech.error,
    preview, error, transcribing, busy, recordingUrl, speechQuestionId, loadCapabilities, syncContext, startRecording, stopRecording,
    cancelRecording, cancelTranscription, transcribe, acceptPreview, restorePreview, playQuestion, stopQuestion: speech.stop, replayRecording, dispose }
}
