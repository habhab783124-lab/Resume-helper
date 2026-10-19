<template>
  <IconLibrary />
  <RouterView />
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import IconLibrary from './components/IconLibrary.vue'
import { clearAuthState } from './utils/auth'

const router = useRouter()

function handleAuthExpired() {
  const redirect = router.currentRoute.value.fullPath
  clearAuthState()
  router.replace({ name: 'Login', query: redirect && redirect !== '/login' ? { redirect } : {} })
}

onMounted(() => window.addEventListener('auth-expired', handleAuthExpired))
onUnmounted(() => window.removeEventListener('auth-expired', handleAuthExpired))
</script>
