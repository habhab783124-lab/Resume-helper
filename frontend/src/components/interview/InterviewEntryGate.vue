<script setup>
defineProps({ status: { type: String, default: 'checking' }, error: { type: String, default: '' } })
const emit = defineEmits(['retry'])
</script>

<template>
  <section class="interview-entry-gate" aria-live="polite" :aria-busy="status === 'checking'">
    <div v-if="status === 'checking'" class="entry-checking">
      <span class="entry-spinner" aria-hidden="true"></span>
      <span>正在检查服务</span>
    </div>
    <div v-else class="entry-unavailable">
      <span class="entry-status-icon" aria-hidden="true">!</span>
      <p>{{ status === 'missing-model' ? '尚未配置模型' : status === 'offline' ? '服务暂时无法连接' : status === 'timeout' ? '服务响应超时' : '模型服务暂不可用' }}</p>
      <small>{{ status === 'missing-model' ? '配置模型后即可开始面试' : error || '请稍后重试' }}</small>
      <div class="entry-actions">
        <RouterLink v-if="status === 'missing-model'" class="button button-primary" to="/settings">去设置</RouterLink>
        <button class="button button-ghost" type="button" @click="emit('retry')">重新检查</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.interview-entry-gate { min-height: 230px; display: grid; place-items: center; border: 1px solid #e8ebe8; border-radius: 20px; background: #fff; color: #26372e; }
.entry-checking, .entry-unavailable { display: flex; flex-direction: column; align-items: center; gap: 12px; text-align: center; }
.entry-spinner { width: 42px; height: 42px; border: 3px solid #e3e9e4; border-top-color: #315e49; border-radius: 50%; animation: entry-spin .85s linear infinite; }
.entry-status-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 50%; background: #fff1d8; color: #aa741d; font-weight: 700; }
.entry-unavailable p, .entry-unavailable small { margin: 0; }
.entry-unavailable p { font-weight: 650; }
.entry-unavailable small { color: #738078; }
.entry-actions { display: flex; gap: 10px; margin-top: 4px; }
@keyframes entry-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .entry-spinner { animation-duration: 2s; } }
</style>
