<script setup>
defineProps({ role: { type: String, default: '' }, active: Boolean, progress: { type: Number, default: 0 }, total: { type: Number, default: 13 }, canFinish: Boolean })
const emit = defineEmits(['finish', 'open-info'])
</script>

<template>
  <header class="interview-header">
    <div class="interview-chat-title"><h2>{{ role || '模拟面试' }}</h2></div>
    <div class="interview-header-actions"><button v-if="active" data-testid="finish-interview" type="button" :disabled="!canFinish" aria-label="结束面试" title="结束面试" @click="emit('finish', $event)">结束</button><button type="button" aria-label="本场信息" title="本场信息" @click="emit('open-info', $event)">···</button></div>
  </header>
</template>

<style scoped>
.interview-header { position: sticky; top: 0; z-index: 3; display: grid; grid-template-columns: 1fr auto; gap: 16px; align-items: center; margin: -20px -22px 14px; padding: 14px 20px; border-bottom: 1px solid #edf0ed; background: rgba(255,255,255,.94); backdrop-filter: blur(12px); }
.interview-chat-title { min-width: 0; }.interview-chat-title h2 { overflow: hidden; margin: 0; color: #26372e; font-size: 15px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.interview-header-actions { display: flex; gap: 7px; }.interview-header-actions button { min-width: 40px; min-height: 36px; padding: 0 9px; border: 1px solid #e1e7e2; border-radius: 10px; background: white; color: #58685e; cursor: pointer; }.interview-header-actions button:disabled { opacity: .45; cursor: default; }
@media (max-width: 640px) { .interview-header { margin: -16px -13px 12px; padding: 12px 13px 9px; } }
</style>
