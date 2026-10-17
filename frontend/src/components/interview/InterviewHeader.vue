<script setup>
defineProps({ role: { type: String, default: '' }, active: Boolean, progress: { type: Number, default: 0 }, total: { type: Number, default: 13 }, canFinish: Boolean })
const emit = defineEmits(['finish', 'open-info'])
</script>

<template>
  <header class="interview-header">
    <div class="interview-session-top"><div><span class="session-overline">{{ active ? 'IN CONVERSATION' : 'SESSION COMPLETE' }}</span><h2>{{ role || '简历模拟面试' }}</h2><span class="session-company">{{ active ? '结合简历与回答进行提问' : '本次面试已结束，可回顾下方问答' }}</span></div></div>
    <div class="interview-progress"><div><span>已答题目 · 追问不计入</span><b>{{ progress }} <i>/</i> {{ total }}</b></div><div class="interview-progress-track"><i :style="{ width: `${total ? progress / total * 100 : 0}%` }"></i></div></div>
    <div class="interview-header-actions"><button v-if="active" data-testid="finish-interview" class="button button-ghost" :disabled="!canFinish" @click="emit('finish', $event)">结束本次面试</button><button class="text-button" @click="emit('open-info', $event)">本场信息</button></div>
  </header>
</template>
