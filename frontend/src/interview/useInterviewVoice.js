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
  let generation = 0, capabilityGeneration = 0, contextKey = '', request = null, disposed = false

  function clearRecordingUrl() {
    pauseReplay()
    if (recordingUrl.value) revokeUrl(recordingUrl.value)
    recordingUrl.value = ''
  }
  const stopBlobWatch = watch(blob, value => {
    clearRecordingUrl()
    if (value?.size) recordingUrl.value = createUrl(value)
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
  async function loadCapabilities() {
    if (disposed || capabilityState.value === 'loading' || busy.value) return
    const my = ++capabilityGeneration
    capabilityState.value = 'loading'
    capabilityError.value = ''
    try {
      const value = await api.getSpeechCapabilities()
      if (disposed || my !== capabilityGeneration) return
      if (typeof value?.asrEnabled !== 'boolean' || typeof value?.ttsEnabled !== 'boolean') throw new Error('语音能力返回格式异常。')
      capabilities.value = { ...value,
        maxRecordingSeconds: Number.isFinite(value.maxRecordingSeconds) && value.maxRecordingSeconds > 0 ? Math.min(value.maxRecordingSeconds, 120) : 120,
        maxAudioBytes: Number.isFinite(value.maxAudioBytes) && value.maxAudioBytes > 0 ? Math.min(value.maxAudioBytes, 5 * 1024 * 1024) : 5 * 1024 * 1024 }
      // Retry may change the server limits. No recording is active here.
      recorder.value?.dispose()
      recorder.value = createRecorder({ maxSeconds: maxSeconds.value, maxBytes: maxBytes.value })
      capabilityState.value = 'ready'
    } catch {
      if (disposed || my !== capabilityGeneration) return
      capabilityState.value = 'unavailable'
      capabilityError.value = '暂时无法确认语音能力，可以继续文字作答或重试。'
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
    recorder.value.cancel()
    await recorder.value.start()
  }
  function stopRecording() {
    if (recorderState.value === 'recording') recorder.value.stop()
  }
  function cancelRecording() {
    cancelTranscription()
    recorder.value?.cancel()
    clearRecordingUrl()
  }
  function playQuestion() {
    const context = getContext()
    if (!ttsEnabled.value || busy.value || disposed || context?.blocked || context?.status !== 'ACTIVE' || !context.questionId) return
    pauseReplay()
    speech.play(context.sessionId, context.questionId)
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
    try {
      const timedOut = new Promise((_, reject) => {
        operation.timer = setTimeout(() => { controller.abort(); reject(new Error('语音识别超时，请重试或改用文字回答。')) }, timeoutMs)
      })
      const extension = blob.value.type.includes('mp4') ? 'mp4' : blob.value.type.includes('ogg') ? 'ogg' : 'webm'
      // Give FormData a truthful container filename; the recorder already probes its format.
      const file = blob.value
      const namedFile = typeof File === 'function' ? new File([file], `answer.${extension}`, { type: file.type }) : file
      const result = await Promise.race([api.transcribe(context.sessionId, { questionId: context.questionId, requestId: id, file: namedFile }, controller.signal), timedOut])
      if (!matches(context, my)) return
      if (result?.sessionId !== context.sessionId || result?.questionId !== context.questionId || result?.requestId !== id || typeof result?.text !== 'string') throw new Error('语音识别结果格式异常，请重试。')
      if (!result.text.trim()) throw new Error('未识别到有效内容，请重试或改用文字回答。')
      preview.value = { ...context, requestId: id, generation: my, text: result.text }
    } catch (cause) {
      if (!matches(context, my)) return
      if (cause?.name !== 'AbortError') error.value = cause?.message || '语音识别失败，请重试或改用文字回答。'
      if (cause?.status === 409) await syncSession(context, () => matches(context, my))
    } finally {
      clearTimeout(operation.timer)
      if (request === operation) { request = null; transcribing.value = false }
    }
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
  }
  return { capabilities, capabilityState, capabilityError, asrEnabled, ttsEnabled, maxSeconds, maxBytes,
    recorderState, blob, durationSeconds, recorderError, speechState: speech.state, speechError: speech.error,
    preview, error, transcribing, busy, recordingUrl, loadCapabilities, syncContext, startRecording, stopRecording,
    cancelRecording, cancelTranscription, transcribe, acceptPreview, playQuestion, stopQuestion: speech.stop, replayRecording, dispose }
}
