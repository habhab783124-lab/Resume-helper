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
      <button class="icon-button notification-button" type="button" aria-label="通知"><svg><use href="#i-bell" /></svg><i></i></button>
      <RouterLink to="/profile" class="user-chip"><span class="avatar">林</span><span class="user-chip-name">林同学</span><svg><use href="#i-chevron" /></svg></RouterLink>
      <RouterLink to="/login" class="topbar-logout">退出</RouterLink>
    </div>
  </header>
</template>

<script setup>
defineProps({
  title: { type: String, default: '工作台' }
})
</script>

<style scoped>
.topbar-logout {
  color: #8a958c;
  font-size: 10px;
  transition: color .18s;
}
.topbar-logout:hover { color: #315c48; }
@media (max-width: 900px) {
  .topbar-logout { display: none; }
}
</style>
