<script setup>
import { onUnmounted, ref } from 'vue'
import { createInterviewApi } from '../../api/interview.js'
import { getLocalApiBaseUrl, getUserId } from '../../api/requestContext.js'
import { useInterviewAttempt } from '../../interview/useInterviewAttempt.js'

const props = defineProps({ sessionId: { type: String, required: true }, questionId: { type: String, required: true }, questionText: { type: String, default: '' }, originalAnswer: { type: String, default: '' } })
const api = createInterviewApi({ baseUrl: import.meta.env.VITE_API_BASE_URL || (import.meta.env.PROD ? 'http://localhost:8080' : '') })
const practice = useInterviewAttempt({ api, sessionId: props.sessionId, questionId: props.questionId, storage: globalThis.sessionStorage,
  scope: { apiBaseUrl: getLocalApiBaseUrl(), userId: getUserId() } })
const { answer, versions, latest, status, result, error, busy, canSubmit } = practice
const open = ref(false)
let loaded = false
let timer = null
async function toggle() {
  open.value = !open.value
  if (!open.value || loaded) return
  loaded = true
  await practice.load()
  schedule()
}
function schedule() {
  clearTimeout(timer)
  if (['QUEUED', 'PENDING', 'RUNNING'].includes(status.value)) timer = setTimeout(async () => { await practice.refresh(); schedule() }, 1500)
}
async function submit() { await practice.submit(); schedule() }
async function retry() { await practice.retry(); schedule() }
onUnmounted(() => { clearTimeout(timer); practice.dispose() })
</script>

<template>
  <section class="attempt-root" data-testid="interview-attempt">
    <button class="text-button attempt-toggle" type="button" @click="toggle">{{ open ? '收起重答' : '重答此题' }}</button>
    <div v-if="open" class="attempt-panel">
      <h4>重新练习这道题</h4>
      <p class="attempt-question">{{ questionText }}</p>
      <div class="attempt-answer"><strong>首答（保留作比较）</strong><p>{{ originalAnswer }}</p></div>
      <label class="attempt-label" :for="`attempt-${questionId}`">重答版本 {{ versions.length + (versions.length < 5 ? 1 : 0) }} / 5</label>
      <textarea :id="`attempt-${questionId}`" v-model="answer" maxlength="5000" rows="5" placeholder="请基于真实经历重新作答。" :disabled="busy" @input="practice.saveDraft()" />
      <div class="attempt-controls">
        <span>{{ answer.length }} / 5000 字</span>
        <button type="button" class="button button-primary" :disabled="!canSubmit" @click="submit">{{ busy ? '正在保存…' : '保存并评估重答' }}</button>
        <button v-if="latest?.status === 'FAILED'" type="button" class="text-button" :disabled="busy" @click="retry">重试评估</button>
      </div>
      <p v-if="error" class="attempt-error" role="alert">{{ error }}</p>
      <p v-if="['QUEUED','PENDING','RUNNING'].includes(status)" class="attempt-status" aria-live="polite">重答已保存，正在评估。</p>
      <p v-if="status === 'FAILED'" class="attempt-status">重答已保留，评估暂时失败；可重试评估。</p>
      <article v-for="version in versions" :key="version.attemptId" class="attempt-version">
        <h5>重答版本 {{ version.version }} · {{ version.status === 'COMPLETE' ? '评估完成' : version.status === 'FAILED' ? '评估失败' : '评估中' }}</h5>
        <p class="attempt-version-answer">{{ version.answer }}</p>
        <template v-if="version.feedback">
          <p><strong>亮点：</strong>{{ version.feedback.highlight }}</p>
          <p><strong>改进动作：</strong>{{ version.feedback.improvement }}</p>
          <p><strong>建议结构：</strong>{{ version.feedback.suggestedOutline }}</p>
          <div v-if="version.feedback.evidence?.length"><strong>回答证据</strong><ul><li v-for="(item,index) in version.feedback.evidence" :key="index">{{ item }}</li></ul></div>
          <div v-if="version.feedback.missingPoints?.length"><strong>待补充要点</strong><ul><li v-for="(item,index) in version.feedback.missingPoints" :key="index">{{ item }}</li></ul></div>
        </template>
        <div v-if="version.comparison" class="attempt-comparison">
          <strong>与首答比较</strong>
          <p v-if="version.comparison.scoreChange != null">原题练习分变化：{{ version.comparison.scoreChange > 0 ? '+' : '' }}{{ version.comparison.scoreChange }}</p>
          <p v-else>此版本仅作训练参考，暂不提供可比的分数变化。</p>
          <p v-if="version.comparison.fixedPoints?.length">已补足：{{ version.comparison.fixedPoints.join('、') }}</p>
          <p v-if="version.comparison.remainingPoints?.length">仍可补充：{{ version.comparison.remainingPoints.join('、') }}</p>
          <p v-if="version.comparison.newIssues?.length">新出现的风险：{{ version.comparison.newIssues.join('、') }}</p>
          <p>此练习不会更改原报告或综合分。</p>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.attempt-root { margin-top: 12px; }
.attempt-toggle { padding: 0; }
.attempt-panel { margin-top: 12px; padding: 16px; border: 1px solid var(--line, #dedbd3); border-radius: 14px; background: var(--surface, #fff); }
.attempt-panel h4, .attempt-version h5 { margin: 0 0 8px; }
.attempt-question, .attempt-answer, .attempt-version { overflow-wrap: anywhere; }
.attempt-answer, .attempt-version { margin: 12px 0; padding: 12px; border-radius: 10px; background: var(--surface-soft, #f5f3ef); }
.attempt-answer p, .attempt-version-answer { white-space: pre-wrap; margin-bottom: 0; }
.attempt-label { display: block; margin: 12px 0 6px; font-weight: 600; }
textarea { box-sizing: border-box; width: 100%; resize: vertical; padding: 10px; border: 1px solid var(--line, #dedbd3); border-radius: 8px; font: inherit; }
.attempt-controls { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-top: 8px; }
.attempt-controls span { margin-right: auto; color: var(--muted, #69675f); }
.attempt-error { color: #a33225; }
.attempt-comparison { border-top: 1px solid var(--line, #dedbd3); padding-top: 8px; }
@media print { .attempt-toggle, .attempt-label, textarea, .attempt-controls, .attempt-error { display: none !important; } .attempt-panel { break-inside: avoid; } }
</style>
