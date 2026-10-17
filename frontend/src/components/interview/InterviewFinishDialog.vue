<script setup>
import { nextTick, onUnmounted, ref, watch } from 'vue'

const props = defineProps({ open: Boolean, message: { type: String, default: '' }, alternativeLabel: { type: String, default: '' }, pending: { default: null }, answer: { type: String, default: '' }, voice: { type: Object, required: true }, locked: Boolean, finishing: Boolean })
const emit = defineEmits(['confirm', 'continue'])
const closeButton = ref(null)
const dialog = ref(null)
function onKeydown(event) {
  if (event.key === 'Escape' && !props.finishing) { emit('continue'); return }
  if (event.key !== 'Tab') return
  const controls = [...dialog.value?.querySelectorAll('button:not([disabled])') || []]
  if (!controls.length) return
  const first = controls[0], last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
watch(() => props.open, async open => {
  if (open) { globalThis.addEventListener?.('keydown', onKeydown); await nextTick(); closeButton.value?.focus() }
  else globalThis.removeEventListener?.('keydown', onKeydown)
}, { immediate: true })
onUnmounted(() => globalThis.removeEventListener?.('keydown', onKeydown))
</script>

<template>
  <div v-if="open" class="interview-finish-overlay">
    <section ref="dialog" class="interview-finish-dialog" role="dialog" aria-modal="true" aria-labelledby="finish-dialog-title" tabindex="-1">
      <header><div><span class="session-overline">END THIS SESSION</span><h2 id="finish-dialog-title">结束本次面试</h2></div><button ref="closeButton" type="button" class="text-button" :disabled="finishing" @click="emit('continue')">继续面试</button></header>
      <p>{{ message }}</p>
      <p v-if="voice.recorderState === 'recorded' || voice.blob" class="voice-note">录音保留在本机，可先转写并检查；直接结束时录音不会计入面试记录。</p>
      <p v-if="voice.transcribing" role="status" class="voice-note">正在结束语音识别并保留录音。</p>
      <div v-if="voice.preview" class="finish-transcript"><strong>待确认转写预览</strong><pre>{{ voice.preview.text }}</pre><div class="voice-actions"><button class="button button-ghost" :disabled="locked" @click="voice.acceptPreview('replace')">替换草稿</button><button class="button button-ghost" :disabled="locked" @click="voice.acceptPreview('append')">追加到草稿</button><button class="text-button" :disabled="locked" @click="voice.acceptPreview('keep')">保留原草稿</button></div></div>
      <div class="voice-actions finish-dialog-actions">
        <button v-if="voice.blob && !voice.preview" data-testid="finish-transcribe" class="button button-ghost" :disabled="locked || !voice.asrEnabled" @click="voice.transcribe">转写并检查</button>
        <button v-if="pending == null" data-testid="finish-submit" class="button button-primary" :disabled="locked || !!voice.preview || !answer.trim() || answer.length > 5000" @click="emit('confirm', 'submit')">提交当前回答并结束</button>
        <button v-if="pending != null" data-testid="finish-preserve" class="button button-primary" :disabled="locked" @click="emit('confirm', 'preserve')">{{ alternativeLabel }}</button>
        <button v-else data-testid="finish-discard" class="button button-ghost" :disabled="locked" @click="emit('confirm', 'discard')">{{ alternativeLabel || '放弃未提交内容并结束' }}</button>
      </div>
    </section>
  </div>
</template>
