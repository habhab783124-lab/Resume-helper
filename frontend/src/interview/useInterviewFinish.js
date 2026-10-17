import { ref } from 'vue'

export function useInterviewFinish({ isActive = () => false, voice = {}, open = ref(false) } = {}) {
  const finishing = ref(false)
  async function requestFinish() {
    if (!isActive() || finishing.value) return false
    if (voice.recorderState?.value === 'recording') await voice.stopRecording?.({ transcribe: false })
    else if (voice.recorderState?.value === 'requesting-mic') voice.cancelRecording?.()
    if (voice.transcribing?.value) voice.cancelTranscription?.()
    open.value = true
    return true
  }
  function continueInterview() { if (!finishing.value) open.value = false }
  async function confirmFinish(choice, handler) {
    if (!open.value || finishing.value || typeof handler !== 'function') return false
    finishing.value = true
    try {
      const result = await handler(choice)
      if (result !== false) open.value = false
      return result
    } finally { finishing.value = false }
  }
  return { open, finishing, requestFinish, confirmFinish, continueInterview }
}
