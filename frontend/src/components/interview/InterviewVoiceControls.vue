<script setup lang="ts">
import type { VoiceState } from '../../interview/voice/voiceContracts.ts'
import { onMounted, onUnmounted, ref } from 'vue'

defineProps<{ state: Readonly<VoiceState>; error?: string }>()
const emit = defineEmits<{
  start: []
  pause: []
  stop: []
  stopPlayback: []
  submitNow: []
  retrySegment: [segmentId: string]
  discardSegment: [segmentId: string]
}>()

const labels: Record<VoiceState['phase'], string> = {
  OFF: '连续语音已关闭', STARTING: '正在连接语音并开启麦克风', SPEAKING: '正在朗读题目', LISTENING: '正在收音',
  TRANSCRIBING: '正在转写，可继续说话', REVIEW: '转写已加入草稿，即将自动提交', SUBMITTING: '正在保存回答',
  WAITING_QUESTION: '回答已保存，正在等待下一步', PAUSED: '连续语音已暂停', ERROR: '连续语音遇到问题',
}
const now = ref(0)
let timer: ReturnType<typeof setInterval> | null = null
onMounted(() => { now.value = performance.now(); timer = globalThis.setInterval(() => { now.value = performance.now() }, 250) })
onUnmounted(() => { if (timer !== null) globalThis.clearInterval(timer) })
</script>

<template>
  <section class="interview-voice-controls" aria-label="连续语音控制">
    <p>连续模式会按停顿分段转写；停顿后展示转写，倒计时结束自动提交，继续说话可取消倒计时。</p>
    <p role="status" aria-live="polite">{{ labels[state.phase] }}<template v-if="state.countdown"> · {{ Math.max(0, Math.ceil((state.countdown.deadlineAt - now) / 1000)) }} 秒后提交</template></p>
    <p v-if="state.phase === 'PAUSED'">已暂停收音，回答仍保留。</p>
    <p v-if="error || state.failure" class="voice-control-error" role="alert">{{ error || state.failure?.message }}</p>
    <div class="voice-control-actions">
      <button v-if="state.phase === 'OFF' || state.phase === 'PAUSED' || state.phase === 'ERROR'" type="button" class="button button-primary" @click="emit('start')">{{ state.phase === 'OFF' ? '开启连续语音' : '继续语音' }}</button>
      <button v-else-if="state.phase !== 'SUBMITTING' && state.phase !== 'WAITING_QUESTION'" type="button" class="button button-ghost" @click="emit('pause')">暂停收音</button>
      <button v-if="state.phase === 'SPEAKING'" type="button" class="button button-ghost" @click="emit('stopPlayback')">停止朗读</button>
      <button v-if="state.phase === 'REVIEW'" type="button" class="button button-ghost" @click="emit('submitNow')">立即提交</button>
      <button v-if="state.phase !== 'OFF'" type="button" class="text-button" @click="emit('stop')">关闭连续语音</button>
    </div>
    <ul v-if="state.segments.some(segment => segment.state === 'FAILED')" class="voice-segment-errors">
      <li v-for="segment in state.segments.filter(item => item.state === 'FAILED')" :key="segment.segmentId">
        <span>有一段语音没有转写成功。录音只保存在当前页面，刷新后无法恢复。</span>
        <button type="button" class="text-button" @click="emit('retrySegment', segment.segmentId)">重试转写</button>
        <button type="button" class="text-button" @click="emit('discardSegment', segment.segmentId)">丢弃录音</button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.interview-voice-controls { display: flex; flex-direction: column; gap: 8px; margin: 12px 0; padding: 12px 14px; border: 1px solid #dfe8e1; border-radius: 12px; background: #f8faf7; color: #334d3b; }
.interview-voice-controls p { margin: 0; line-height: 1.6; }
.voice-control-actions, .voice-segment-errors li { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.voice-segment-errors { margin: 0; padding-left: 20px; }
.voice-control-error { color: #a24f47; }
</style>
