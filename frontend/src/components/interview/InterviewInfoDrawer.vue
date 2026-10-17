<script setup>
import { nextTick, onUnmounted, ref, watch } from 'vue'
import { specialtyLabel } from '../../interview/specialtyPresentation.js'

const props = defineProps({ open: Boolean, session: { type: Object, default: null }, material: { type: Object, default: null }, voiceMaxSeconds: { type: Number, default: 0 }, voiceMaxBytes: { type: Number, default: 0 } })
const emit = defineEmits(['close'])
const closeButton = ref(null)
function onKeydown(event) { if (event.key === 'Escape') emit('close') }
watch(() => props.open, async open => {
  if (open) { globalThis.addEventListener?.('keydown', onKeydown); await nextTick(); closeButton.value?.focus() }
  else globalThis.removeEventListener?.('keydown', onKeydown)
}, { immediate: true })
onUnmounted(() => globalThis.removeEventListener?.('keydown', onKeydown))
</script>

<template>
  <div v-if="open" class="interview-info-overlay" @click.self="emit('close')">
    <section class="interview-info-drawer" role="dialog" aria-modal="true" aria-labelledby="interview-info-title" tabindex="-1">
      <header><div><span class="session-overline">SESSION DETAILS</span><h2 id="interview-info-title">本场信息</h2></div><button ref="closeButton" class="text-button" aria-label="关闭本场信息" @click="emit('close')">关闭</button></header>
      <div class="interview-info-section"><h3>材料与进度</h3><p>简历：{{ material?.fileName || session?.resumeFileName || '本场简历' }}</p><p>岗位：{{ session?.targetRole || '按简历职业方向' }}</p><p>已答主问题 {{ session?.progress?.answeredRoots ?? session?.turns?.filter(turn => turn.answer != null).length ?? 0 }} / {{ session?.progress?.plannedRoots || session?.questionCount || '—' }}；已跳过 {{ session?.progress?.skippedRoots || 0 }} 道。</p><p v-if="session?.progress?.followUpBudget != null">已用追问 {{ session.progress.followUpsUsed }} / {{ session.progress.followUpBudget }}。</p></div>
      <div v-if="session?.plan" class="interview-info-section"><h3>面试计划</h3><p>{{ session.plan.mode }} · 计划难度 {{ session.plan.level }} · {{ session.plan.careerStage }}</p><p v-if="session?.specialty?.kind === 'ALGORITHM'">算法题难度：{{ session?.turns?.map(turn => session.questionMetadata?.[turn.questionId]?.algorithm?.difficulty).find(Boolean) || session.plan.level }}</p><p v-if="session.plan.specialty">专项：{{ session.plan.specialty.label || session.plan.specialty.kind }}</p><p v-if="session.plan.coverage?.length">覆盖方向：{{ session.plan.coverage.map(x => x.label).join('、') }}</p></div><div v-else class="interview-info-section"><h3>题目与评分规则</h3><p>主问题数遵循本场规则；追问并入对应主问题评分。</p><p v-if="session?.specialty">专项方向：{{ session.specialty.focus }}。{{ session.specialty.reason }}</p></div>
      <div v-if="session?.specialty" class="interview-info-section"><h3>本轮专项 · {{ specialtyLabel(session.specialty.kind) }}</h3><p>{{ session.specialty.focus }}</p><p>{{ session.specialty.reason }}</p></div>
      <div class="interview-info-section"><h3>保存与语音</h3><p>回答文字与转写预览按题保存在本机 7 天；已提交面试记录可在一天内恢复，后端重启后失效。本机录音刷新后无法恢复。</p><p>录音上限 {{ voiceMaxSeconds }} 秒 / {{ (voiceMaxBytes / 1024 / 1024).toFixed(1) }} MiB。主动识别时录音发送至火山引擎；题目朗读按需生成。</p></div>
    </section>
  </div>
</template>
