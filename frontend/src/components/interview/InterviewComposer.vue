<script setup>
import { nextTick, ref, watch } from 'vue'
import { insertIndent, shouldSend } from '../../interview/composerActions.js'
const props = defineProps({ modelValue: { type: String, default: '' }, locked: Boolean, pending: { default: null }, kind: { type: String, default: 'MAIN' }, guidance: { type: String, default: '' }, voice: { type: Object, required: true } })
const emit = defineEmits(['update:modelValue', 'send', 'audio'])
const textarea = ref(null), expandButton = ref(null), expanded = ref(false), indentCapture = ref(false)
function onInput(event) {
  emit('update:modelValue', event.target.value)
  event.target.style.height = 'auto'
  event.target.style.height = `${Math.min(event.target.scrollHeight, 6 * 24)}px`
}
async function onKeydown(event) {
  if (shouldSend(event)) { event.preventDefault(); emit('send'); return }
  if (event.key === 'Escape') {
    if (expanded.value) { expanded.value = false; await nextTick(); expandButton.value?.focus() }
    else if (indentCapture.value) indentCapture.value = false
    return
  }
  if (props.kind === 'ALGORITHM' && indentCapture.value && event.key === 'Tab' && !event.shiftKey) {
    event.preventDefault()
    const el = event.target
    const result = insertIndent(el.value, el.selectionStart, el.selectionEnd)
    emit('update:modelValue', result.text)
    await nextTick(); el.setSelectionRange(result.start, result.end)
  }
}
async function toggleExpanded() {
  expanded.value = !expanded.value
  if (expanded.value) { await nextTick(); textarea.value?.focus() }
  else { await nextTick(); expandButton.value?.focus() }
}
function onAudio(element) { emit('audio', element) }
watch(() => props.modelValue, async () => { await nextTick(); const el = textarea.value; if (el && !expanded.value) { el.style.height = 'auto'; el.style.height = `${Math.min(el.scrollHeight, 6 * 24)}px` } })
</script>

<template>
<div class="answer-editor" :class="{ 'is-expanded': expanded }" @keydown.capture="onKeydown" :role="expanded ? 'dialog' : undefined" :aria-modal="expanded ? 'true' : undefined" :aria-label="expanded ? '展开回答编辑器' : undefined">
  <div class="composer-heading"><label for="interview-answer">你的回答 <span>{{ guidance }}</span></label><button ref="expandButton" type="button" class="text-button" @click="toggleExpanded">{{ expanded ? '收起编辑' : '展开编辑' }}</button></div>
  <label v-if="kind === 'ALGORITHM'" class="composer-language">代码语言 <select aria-label="代码语言"><option>按你的习惯输入</option><option>Java</option><option>Python</option><option>JavaScript</option><option>C++</option></select></label>
  <button v-if="kind === 'ALGORITHM'" type="button" class="text-button" @click="indentCapture = !indentCapture">{{ indentCapture ? 'Tab 缩进已开启 · Esc 关闭' : '开启 Tab 缩进' }}</button>

              <p v-if="pending != null" class="notice">回答已保留。重试会提交同一份回答；结束时会保留服务端已收到的回答。录音和转写不能覆盖待提交回答。</p>
              <div class="voice-panel">
                <p class="voice-note">可选语音作答：点击后才会申请麦克风。录音由你主动识别时发送至火山引擎；转写先预览，确认后再写入草稿，不会自动提交回答。</p>
                <p class="voice-note">录音上限 {{ voice.maxSeconds }} 秒 / {{ (voice.maxBytes / 1024 / 1024).toFixed(1) }} MiB。</p>
                <p v-if="voice.capabilityState === 'loading'" role="status" class="voice-note">正在确认语音能力，可继续文字作答。</p>
                <p v-else-if="voice.capabilityError" class="voice-note">{{ voice.capabilityError }}</p>
                <p v-else-if="!voice.asrEnabled" class="voice-note">语音识别暂不可用，请用文字回答。</p>
                <button v-if="voice.capabilityState !== 'loading' && (!voice.asrEnabled || !voice.ttsEnabled)" class="text-button" :disabled="locked" @click="voice.loadCapabilities">重试语音能力</button>
              <RouterLink to="/settings" class="text-button config-link" aria-label="去设置页配置大模型与语音">去配置 AI →</RouterLink>
                <div class="voice-actions">
                  <button data-testid="recording-start" class="button button-ghost" :disabled="locked || pending != null || !voice.asrEnabled" @click="voice.startRecording">{{ voice.blob ? '重新录音' : '开始录音' }}</button>
                  <button data-testid="recording-stop" class="button button-ghost" :disabled="voice.recorderState !== 'recording'" @click="voice.stopRecording">停止录音</button>
                  <button data-testid="recording-cancel" class="text-button" :disabled="!['requesting-mic', 'recording', 'recorded'].includes(voice.recorderState) && !voice.blob" @click="voice.cancelRecording">{{ voice.recorderState === 'recorded' ? '丢弃录音' : '取消录音' }}</button>
                </div>
                <p v-if="voice.recorderState === 'requesting-mic'" class="voice-note" role="status">等待麦克风授权，可随时取消。</p>
                <p v-if="voice.recorderState === 'recording'" class="voice-note" role="status">录音中 · {{ voice.durationSeconds }} / {{ voice.maxSeconds }} 秒</p>
                <audio v-if="voice.recordingUrl" :ref="onAudio" :src="voice.recordingUrl" controls :class="{ 'replay-locked': locked }" :inert="locked" aria-label="回放自己的录音" @play="voice.replayRecording"></audio>
                <div v-if="voice.blob" class="voice-actions">
                  <button data-testid="transcription-start" class="button button-ghost" :disabled="locked || pending != null || !voice.asrEnabled" @click="voice.transcribe">{{ voice.transcribing ? '正在识别…' : '识别 / 重试录音' }}</button>
                  <button v-if="voice.transcribing" data-testid="transcription-cancel" class="text-button" @click="voice.cancelTranscription">取消识别，保留录音</button>
                </div>
                <p v-if="voice.transcribing" class="voice-note" role="status">正在转写录音，最长等待 90 秒；可取消后重试。</p>
                <p v-if="voice.error || voice.recorderError" class="notice error" role="alert">{{ voice.error || voice.recorderError }}</p>
                <div v-if="voice.preview" class="transcription-preview">
                  <h4>转写预览 · 尚未写入草稿</h4>
                  <pre>{{ voice.preview.text }}</pre>
                  <p v-if="voice.preview.text.length > 5000" class="notice error">转写超过 5000 字，已完整保留。确认后请在回答框精简再提交。</p>
                  <div class="voice-actions">
                    <button data-testid="transcript-replace" class="button button-ghost" :disabled="locked || pending != null" @click="voice.acceptPreview('replace')">替换草稿</button>
                    <button data-testid="transcript-append" class="button button-ghost" :disabled="locked || pending != null" @click="voice.acceptPreview('append')">追加到草稿</button>
                    <button data-testid="transcript-keep" class="text-button" :disabled="locked || pending != null" @click="voice.acceptPreview('keep')">保留原草稿</button>
                  </div>
                </div>
              </div>
              <textarea id="interview-answer" :value="modelValue" ref="textarea" @input="onInput" :class="{ 'algorithm-answer': kind === 'ALGORITHM' }" rows="1" maxlength="5000" :disabled="locked || pending != null" :placeholder="guidance"></textarea>
              <p v-if="modelValue.length > 5000" class="notice error">回答超过 5000 字，请精简后提交；完整内容已保留。</p>
              <div class="answer-tools"><span>{{ modelValue.length }} / 5000 字</span><button class="button button-primary" :disabled="locked || !(pending ?? modelValue.trim()) || (pending ?? modelValue).length > 5000" @click="emit('send')">{{ pending != null ? '重试提交' : '提交回答' }} →</button></div>
            
</div>
</template>
