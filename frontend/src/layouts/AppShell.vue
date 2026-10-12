<template>
  <div class="app-shell is-visible">
    <SidebarNavigation />
    <main class="main-shell">
      <TopBar :title="route.meta.title || '工作台'" />
      <div class="content-wrap" @click="navigateFromContent">
        <RouterView />
      </div>
    </main>
  </div>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import SidebarNavigation from '../components/SidebarNavigation.vue'
import TopBar from '../components/TopBar.vue'

const route = useRoute()
const router = useRouter()

function navigateFromContent(event) {
  const control = event.target.closest('[data-page]')
  if (!control) return

  event.preventDefault()
  router.push(`/${control.dataset.page}`)
}
</script>
