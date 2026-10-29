<template>
  <header class="topbar">
    <div class="breadcrumb"><span>拾阶</span><i>/</i><strong>{{ title }}</strong></div>
    <div class="topbar-actions">
      <span class="prototype-badge"><i></i>前端页面预览</span>
      <label class="global-search">
        <svg><use href="#i-search" /></svg>
        <input type="search" placeholder="搜索岗位、简历…" aria-label="搜索岗位或简历" />
        <kbd>Ctrl K</kbd>
      </label>
      <button class="icon-button notification-button" type="button" aria-label="通知"><svg><use href="#i-bell" /></svg></button>
      <RouterLink to="/profile" class="user-chip"><span class="avatar">{{ avatarChar }}</span><span class="user-chip-name">{{ displayName }}</span><svg><use href="#i-chevron" /></svg></RouterLink>
      <button class="topbar-logout" type="button" @click="logout">退出</button>
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { getAuthUser } from '../utils/authSession'
import { clearAuthState } from '../utils/auth'

const router = useRouter()
const user = getAuthUser()
const displayName = computed(() => user?.displayName || user?.email?.split('@')[0] || '求职者')
const avatarChar = computed(() => displayName.value.slice(0, 1))

defineProps({
  title: { type: String, default: '工作台' }
})

function logout() {
  clearAuthState()
  router.replace('/login')
}
</script>

<style scoped>
.topbar-logout {
  padding: 6px 0;
  border: 0;
  background: transparent;
  color: #8a958c;
  font-size: 10px;
  cursor: pointer;
  transition: color .18s;
}
.topbar-logout:hover { color: #315c48; }
@media (max-width: 900px) {
  .topbar-logout { display: inline-flex; }
}
</style>
