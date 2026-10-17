<template>
  <section class="history-page">
    <header><div><p class="eyebrow">模拟面试</p><h1>面试历史</h1><p>每轮独立保存，保留期限以服务记录为准；达到记录上限时可删除旧轮释放空间。</p></div><RouterLink to="/interview" class="primary">开始新面试</RouterLink></header>
    <p v-if="history.error.value" class="error">{{ history.error.value }} <button @click="history.reload">重试</button></p>
    <div v-else-if="!history.items.value.length && !history.loading.value" class="empty">暂无面试记录</div>
    <article v-for="item in history.items.value" :key="item.id" class="history-card">
      <div class="details"><h2>{{ item.role || '未设置目标岗位' }}</h2><p>{{ modeLabel(item.mode) }} · {{ statusLabel(item.status) }} · {{ item.progress }} 题</p>
        <small>开始于 {{ format(item.createdAt) }} · 保留至 {{ format(item.expiresAt) }}</small></div>
      <div class="actions">
        <button v-if="item.status === 'ACTIVE'" @click="resume(item)">继续面试</button>
        <button v-else @click="openReport(item)">{{ item.reportStatus === 'READY' ? '查看报告' : '查看记录' }}</button>
        <button class="danger" @click="remove(item)">删除</button>
      </div>
    </article>
    <button v-if="history.cursor.value" class="load-more" :disabled="history.loading.value" @click="history.loadMore">{{ history.loading.value ? '加载中…' : '加载更多' }}</button>
    <p v-else-if="history.loading.value" class="empty">正在加载…</p>
  </section>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { createInterviewApi } from '../api/interview.js'
import { getLocalApiBaseUrl, getUserId } from '../api/requestContext.js'
import { createDraftStore } from '../interview/draftStore.js'
import { useInterviewHistory } from '../interview/useInterviewHistory.js'

const router = useRouter()
const scope = () => ({ apiBaseUrl: getLocalApiBaseUrl(), userId: getUserId() })
const api = createInterviewApi({ baseUrl: import.meta.env.VITE_API_BASE_URL || (import.meta.env.PROD ? 'http://localhost:8080' : '') })
const history = useInterviewHistory({ api, scope })
const drafts = () => createDraftStore({ storage: globalThis.localStorage, scope: scope() })
function onScopeChange() { history.reload() }
onMounted(() => {
  history.reload()
  globalThis.addEventListener?.('request-context-change', onScopeChange)
})
onUnmounted(() => {
  globalThis.removeEventListener?.('request-context-change', onScopeChange)
  history.dispose()
})
function modeLabel(mode) { return ({ QUICK: '短练', FULL: '完整面试' })[mode] || '面试' }
function statusLabel(status) { return status === 'ACTIVE' ? '进行中' : status === 'FINISHED' ? '已结束' : status }
function format(value) { const date = new Date(value); return Number.isNaN(date.valueOf()) ? '时间未知' : date.toLocaleString() }
function resume(item) { router.push({ name: 'Interview', query: { sessionId: item.id } }) }
function openReport(item) { router.push({ name: 'Report', query: { sessionId: item.id } }) }
async function remove(item) {
  if (!globalThis.window?.confirm(`删除「${item.role || '未设置岗位'}」这轮面试？`)) return
  const removed = await history.remove(item.id)
  if (!removed) return
  drafts().removeSession(item.id)
  try {
    const key = `career-studio-interview-id-v2|${scope().apiBaseUrl}|${scope().userId}`
    if (sessionStorage.getItem(key) === item.id) sessionStorage.removeItem(key)
    if (sessionStorage.getItem('career-studio-interview-id') === item.id) sessionStorage.removeItem('career-studio-interview-id')
  } catch { /* Session storage is optional. */ }
}
</script>

<style scoped>
.history-page{max-width:1080px;margin:0 auto;padding:32px;color:#25362d}.history-page header{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px}.eyebrow{color:#59856b;font-size:12px;text-transform:uppercase;letter-spacing:.12em}h1{margin:4px 0;font-size:30px}.history-card{display:flex;justify-content:space-between;align-items:center;padding:20px 22px;margin:12px 0;border:1px solid #e4ebe5;border-radius:16px;background:white}.history-card h2{margin:0 0 8px;font-size:17px}.history-card p,.history-page header p{color:#718078}.history-card small{color:#849188}.actions{display:flex;gap:8px}.actions button,.primary,.load-more{border:1px solid #d9e4dc;border-radius:10px;padding:9px 13px;background:#fff;color:#315c48;cursor:pointer;text-decoration:none}.primary{background:#315c48;color:white}.actions .danger{color:#a24f47}.empty{padding:48px;text-align:center;color:#758179}.error{color:#a24f47}.load-more{display:block;margin:22px auto}.load-more:disabled{opacity:.6}@media(max-width:680px){.history-page{padding:20px}.history-card{align-items:flex-start;gap:14px;flex-direction:column}.history-page header{align-items:flex-start}}
</style>
