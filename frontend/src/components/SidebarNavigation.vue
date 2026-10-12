<template>
  <aside class="sidebar">
    <RouterLink to="/dashboard" class="brand" aria-label="拾阶工作台">
      <span class="brand-mark"><span></span><span></span><span></span></span>
      <span class="brand-word">拾阶<span class="brand-en">CAREER STUDIO</span></span>
    </RouterLink>

    <div class="nav-caption">工作空间</div>
    <nav class="main-nav" aria-label="主导航">
      <RouterLink v-for="item in workspaceItems" :key="item.path" :to="item.path" class="nav-item" :class="{ active: route.path === item.path }">
        <svg><use :href="`#${item.icon}`" /></svg><span>{{ item.label }}</span><kbd v-if="item.hint">{{ item.hint }}</kbd><b v-if="item.dot" class="nav-dot"></b>
      </RouterLink>

      <div class="nav-caption nav-caption-spaced">求职工具</div>
      <RouterLink v-for="item in toolItems" :key="item.path" :to="item.path" class="nav-item" :class="{ active: route.path === item.path }">
        <svg><use :href="`#${item.icon}`" /></svg><span>{{ item.label }}</span><em v-if="item.count">{{ item.count }}</em>
      </RouterLink>
    </nav>

    <div class="sidebar-bottom">
      <div class="sidebar-note">
        <span class="note-kicker">本周目标</span>
        <strong>让准备更有方向</strong>
        <div class="note-progress"><i></i></div>
        <div class="note-meta"><span>完成度</span><b>68%</b></div>
      </div>
      <div class="help-link"><span class="help-symbol">?</span>帮助与反馈<span class="help-version">v0.1</span></div>
    </div>
  </aside>
</template>

<script setup>
import { useRoute } from 'vue-router'

const route = useRoute()
const workspaceItems = [
  { path: '/dashboard', label: '工作台', icon: 'i-grid', hint: '01' },
  { path: '/profile', label: '个人资料', icon: 'i-user' },
  { path: '/materials', label: '材料整理', icon: 'i-folder', dot: true }
]
const toolItems = [
  { path: '/resume', label: '简历工作台', icon: 'i-file' },
  { path: '/jobs', label: '岗位匹配', icon: 'i-briefcase', count: '8' },
  { path: '/interview', label: '模拟面试', icon: 'i-mic' },
  { path: '/report', label: '面试报告', icon: 'i-chart' }
]
</script>
