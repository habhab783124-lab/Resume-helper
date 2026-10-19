<script setup>
import { nextTick, onUnmounted, ref, watch } from 'vue'
import { insertIndent, shouldSend } from '../../interview/composerActions.js'
import { voiceComposerPresentation } from '../../interview/voice/voiceComposerPresentation.js'

const props = defineProps({ modelValue: { type: String, default: '' }, locked: Boolean, pending: { default: null }, kind: { type: String, default: 'MAIN' }, voice: { type: Object, required: true }, continuousActive: Boolean, realtimeState: { type: Object, default: null }, speechAvailable: { type: Boolean, default: true } })
const emit = defineEmits(['update:modelValue', 'send', 'audio', 'voice-start', 'voice-pause', 'voice-stop', 'voice-submit-now', 'voice-retry', 'voice-discard'])
const textarea = ref(null), audio = ref(null), manualOpen = ref(false), failureOpen = ref(false), expanded = ref(false), indentCapture = ref(false), language = ref('auto'), now = ref(performance.now())
let countdownTimer
watch(() => props.realtimeState?.countdown?.deadlineAt, deadline => {
  globalThis.clearInterval(countdownTimer)
  countdownTimer = undefined
  if (deadline) countdownTimer = globalThis.setInterval(() => { now.value = performance.now() }, 120)
}, { immediate: true })
onUnmounted(() => globalThis.clearInterval(countdownTimer))
const voiceAction = () => voiceComposerPresentation(props.realtimeState, props.speechAvailable)
const statusText = () => {
  const state = props.realtimeState
  if (!state || state.phase === 'OFF') return ''
  if (state.phase === 'REVIEW' && state.countdown) return `${Math.max(0, Math.ceil((state.countdown.deadlineAt - now.value) / 1000))} 秒后发送 · 继续说话可取消`
  return ({ STARTING: '正在连接', SPEAKING: '正在朗读题目', LISTENING: '正在聆听', TRANSCRIBING: '正在转写', REVIEW: '回答已就绪', SUBMITTING: '正在发送', WAITING_QUESTION: '正在准备下一题', PAUSED: '语音已暂停', ERROR: state.failure?.message || '语音遇到问题' })[state.phase] || ''
}
function onInput(event) { emit('update:modelValue', event.target.value) }
function submitFromComposer() {
  if (props.realtimeState?.phase === 'REVIEW') emit('voice-submit-now')
  else emit('send')
}
function onKeydown(event) {
  if (props.kind === 'ALGORITHM' && indentCapture.value && event.key === 'Tab' && !event.shiftKey) {
    event.preventDefault()
    const result = insertIndent(event.target.value, event.target.selectionStart, event.target.selectionEnd)
    emit('update:modelValue', result.text)
    nextTick(() => event.target.setSelectionRange(result.start, result.end))
    return
  }
  if (shouldSend(event)) { event.preventDefault(); submitFromComposer() }
}
function activateVoice() {
  const { action } = voiceAction()
  if (action === 'manual') { manualOpen.value = true; return }
  if (action === 'start') emit('voice-start')
  else if (action === 'pause') emit('voice-pause')
  else if (action === 'submitNow') emit('voice-submit-now')
}
async function toggleExpanded() { expanded.value = !expanded.value; await nextTick(); textarea.value?.focus() }
function onAudio(element) { audio.value = element; emit('audio', element) }
</script>

<template>
  <div class="interview-composer-wrap">
    <form class="interview-composer" :class="{ 'voice-active': voiceAction().active }" @submit.prevent="submitFromComposer">
      <button v-if="kind === 'ALGORITHM'" type="button" class="composer-expand" aria-label="展开编辑器" @click="toggleExpanded">↗</button>
      <textarea ref="textarea" :value="modelValue" :disabled="locked || pending != null" :class="{ 'algorithm-answer': kind === 'ALGORITHM' }" rows="1" maxlength="5000" :placeholder="kind === 'ALGORITHM' ? '写下你的思路…' : '输入回答…'" aria-label="输入回答" @input="onInput" @keydown="onKeydown"></textarea>
      <button type="button" class="composer-icon-button" :disabled="locked || voiceAction().disabled || pending != null" :aria-label="voiceAction().label" :title="voiceAction().label" @click="activateVoice">
        <span v-if="voiceAction().active" class="composer-voice-wave" aria-hidden="true"><i></i><i></i><i></i></span>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M12 14a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v5a3 3 0 0 0 3 3Zm5-3a5 5 0 0 1-10 0M12 16v4m-4 0h8" /></svg>
      </button>
      <button type="submit" class="composer-send-button" :disabled="locked || (continuousActive && realtimeState?.phase !== 'REVIEW') || !(pending ?? modelValue.trim()) || (pending ?? modelValue).length > 5000" aria-label="发送回答">
        <span v-if="pending != null" aria-hidden="true">↻</span><span v-else aria-hidden="true">↑</span>
      </button>
      <div v-if="statusText() || realtimeState?.segments?.some(segment => segment.state === 'FAILED')" class="composer-inline-status" :class="{ 'has-failed-segment': realtimeState?.segments?.some(segment => segment.state === 'FAILED') }" :role="realtimeState?.segments?.some(segment => segment.state === 'FAILED') ? 'alert' : 'status'" aria-live="polite">
        <span v-if="statusText()">{{ statusText() }}</span><button v-if="realtimeState?.phase === 'REVIEW'" type="button" @click="emit('voice-submit-now')">现在发送</button><button v-if="realtimeState?.phase && realtimeState.phase !== 'OFF'" type="button" @click="emit('voice-stop')">关闭语音</button>
        <span v-if="realtimeState?.segments?.some(segment => segment.state === 'FAILED')">有语音片段未能转写</span><button v-if="realtimeState?.segments?.some(segment => segment.state === 'FAILED')" type="button" @click="failureOpen = true">处理</button>
      </div>
    </form>
    <div v-if="failureOpen" class="manual-voice-backdrop" @click.self="failureOpen = false">
      <section class="manual-voice-dialog" role="dialog" aria-modal="true" aria-label="处理未转写语音">
        <button class="manual-close" type="button" aria-label="关闭" @click="failureOpen = false">×</button>
        <h3>语音未能转写</h3>
        <p>录音只保留在当前页面。可以重试转写或丢弃片段。</p>
        <div v-for="segment in realtimeState.segments.filter(item => item.state === 'FAILED')" :key="segment.segmentId" class="voice-actions">
          <button type="button" @click="emit('voice-retry', segment.segmentId); failureOpen = false">重试</button>
          <button type="button" @click="emit('voice-discard', segment.segmentId)">丢弃</button>
        </div>
      </section>
    </div>
    <div v-if="manualOpen" class="manual-voice-backdrop" @click.self="manualOpen = false">
      <section class="manual-voice-dialog" role="dialog" aria-modal="true" aria-label="手动语音输入">
        <button class="manual-close" type="button" aria-label="关闭" @click="manualOpen = false">×</button>
        <h3>语音输入</h3>
        <p v-if="voice.capabilityState === 'unavailable' || !voice.asrEnabled" class="manual-error">语音识别暂不可用</p>
        <div class="voice-actions">
          <button data-testid="recording-start" type="button" :disabled="locked || continuousActive || pending != null || !voice.asrEnabled" @click="voice.startRecording">{{ voice.blob ? '重新录音' : '开始录音' }}</button>
          <button data-testid="recording-stop" type="button" :disabled="voice.recorderState !== 'recording'" @click="voice.stopRecording">停止</button>
          <button data-testid="recording-cancel" type="button" :disabled="!['requesting-mic', 'recording', 'recorded'].includes(voice.recorderState) && !voice.blob" @click="voice.cancelRecording">取消</button>
        </div>
        <p v-if="voice.recorderState === 'recording'" role="status">录音中 · {{ voice.durationSeconds }} 秒</p>
        <audio v-if="voice.recordingUrl" :ref="onAudio" :src="voice.recordingUrl" controls aria-label="回放录音" @play="voice.replayRecording"></audio>
        <button v-if="voice.blob" data-testid="transcription-start" type="button" :disabled="voice.transcribing || !voice.asrEnabled" @click="voice.transcribe">{{ voice.transcribing ? '正在识别…' : '识别语音' }}</button>
        <button v-if="voice.transcribing" data-testid="transcription-cancel" type="button" @click="voice.cancelTranscription">取消识别</button>
        <p v-if="voice.error || voice.recorderError" class="manual-error" role="alert">{{ voice.error || voice.recorderError }}</p>
        <div v-if="voice.preview" class="transcription-preview">
          <pre>{{ voice.preview.text }}</pre>
          <div class="voice-actions">
            <button data-testid="transcript-replace" type="button" :disabled="locked || pending != null" @click="voice.acceptPreview('replace'); manualOpen = false">替换草稿</button>
            <button data-testid="transcript-append" type="button" :disabled="locked || pending != null" @click="voice.acceptPreview('append'); manualOpen = false">追加草稿</button>
            <button data-testid="transcript-keep" type="button" @click="voice.acceptPreview('keep'); manualOpen = false">保留原草稿</button>
          </div>
        </div>
      </section>
    </div>
    <div v-if="expanded" class="manual-voice-backdrop" @click.self="expanded = false">
      <section class="manual-voice-dialog expanded-answer" role="dialog" aria-modal="true" aria-label="展开回答编辑器">
        <button class="manual-close" type="button" aria-label="关闭" @click="expanded = false">×</button>
        <select v-if="kind === 'ALGORITHM'" v-model="language" aria-label="代码语言"><option value="auto">按习惯输入</option><option>Java</option><option>Python</option><option>JavaScript</option><option>C++</option></select>
        <button v-if="kind === 'ALGORITHM'" type="button" @click="indentCapture = !indentCapture">{{ indentCapture ? '关闭 Tab 缩进' : '开启 Tab 缩进' }}</button>
        <textarea :value="modelValue" :disabled="locked || pending != null" rows="12" maxlength="5000" aria-label="完整回答" @input="emit('update:modelValue', $event.target.value)" @keydown="onKeydown"></textarea>
        <button class="button button-primary" :disabled="locked || !(pending ?? modelValue.trim())" @click="emit('send'); expanded = false">发送回答</button>
      </section>
    </div>
  </div>
</template>

<style scoped>
.interview-composer-wrap { position: sticky; bottom: 0; z-index: 4; width: 100%; margin: auto 0 0; padding-top: 14px; background: linear-gradient(180deg, rgba(255,255,255,0), #fff 16px); }
.interview-composer { display: flex; flex-wrap: wrap; align-items: end; gap: 8px; padding: 8px 9px 8px 16px; border: 1px solid #dce2dd; border-radius: 24px; background: rgba(255,255,255,.97); box-shadow: 0 8px 30px rgba(31,51,40,.10); }
.interview-composer textarea { flex: 1; min-width: 0; min-height: 32px; max-height: 132px; resize: none; border: 0; padding: 6px 0; outline: 0; background: transparent; box-shadow: none; line-height: 1.5; }
.composer-inline-status { order: 4; flex: 0 0 100%; display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 3px 10px; padding: 0 0 2px; color: #718078; font-size: 11px; line-height: 1.4; }
.composer-inline-status.has-failed-segment { color: #995444; }
.composer-inline-status button { padding: 0; border: 0; background: transparent; color: #315e49; font: inherit; cursor: pointer; }
.interview-composer textarea:focus { outline: 0; }
.composer-icon-button, .composer-send-button, .composer-expand { display: grid; place-items: center; flex: none; width: 40px; height: 40px; padding: 0; border: 0; border-radius: 50%; cursor: pointer; }
.composer-icon-button { color: #315e49; background: #edf3ee; }
.composer-icon-button svg { width: 21px; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.composer-send-button { color: white; background: #315e49; font-size: 22px; }
.composer-send-button:disabled, .composer-icon-button:disabled { opacity: .42; cursor: default; }
.composer-voice-wave { display: flex; align-items: center; gap: 2px; height: 20px; }
.composer-voice-wave i { width: 2px; height: 7px; border-radius: 2px; background: currentColor; animation: voice-wave .7s ease-in-out infinite alternate; }
.composer-voice-wave i:nth-child(2) { height: 15px; animation-delay: .15s; }.composer-voice-wave i:nth-child(3) { animation-delay: .3s; }
.manual-voice-backdrop { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; padding: 18px; background: rgba(20,31,25,.38); }
.manual-voice-dialog { position: relative; width: min(100%, 480px); max-height: 85vh; overflow: auto; padding: 24px; border-radius: 18px; background: #fff; box-shadow: 0 18px 60px rgba(0,0,0,.18); }
.manual-voice-dialog h3 { margin: 0 0 18px; }.manual-close { position: absolute; top: 12px; right: 14px; border: 0; background: none; font-size: 25px; cursor: pointer; }
.manual-voice-dialog audio { width: 100%; margin: 12px 0; }.manual-voice-dialog button:not(.manual-close) { min-height: 36px; padding: 7px 12px; border: 1px solid #dce2dd; border-radius: 10px; background: white; cursor: pointer; }
.manual-voice-dialog .voice-actions { display: flex; flex-wrap: wrap; gap: 8px; }.manual-error { color: #a24f47; }
.transcription-preview { margin-top: 15px; }.transcription-preview pre { max-height: 220px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
.expanded-answer { display: flex; flex-direction: column; gap: 12px; }.expanded-answer textarea { min-height: 55vh; }
.algorithm-answer { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; tab-size: 4; }
@keyframes voice-wave { to { transform: scaleY(.5); } }
@media (prefers-reduced-motion: reduce) { .composer-voice-wave i { animation: none; } }
</style>
