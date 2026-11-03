<template>
  <aside v-if="selected.length||message" class="comparison-tray" aria-label="待对比岗位">
    <div class="comparison-tray-top"><strong>岗位对比 <span>{{ selected.length }} / 4</span></strong><span>选择 2～4 个机会，一起看清取舍。</span></div>
    <div class="comparison-tray-items"><button v-for="job in selected" :key="job.id" type="button" :aria-label="'移除对比：'+job.title" @click="remove(job.id)">{{ job.title }} <span aria-hidden="true">×</span></button></div>
    <p v-if="message" role="status">{{ message }}</p>
    <div class="comparison-tray-actions"><button class="text-button" @click="clear">清空选择</button><button class="button button-primary" :disabled="selected.length<2" @click="router.push({name:'JobComparison'})">查看对比</button></div>
  </aside>
</template>
<script setup>
import {useRouter} from 'vue-router'
import {useJobComparison} from '../composables/useJobComparison'
const router=useRouter(),{selected,message,remove,clear}=useJobComparison()
</script>
<style scoped>
.comparison-tray{position:sticky;bottom:16px;z-index:20;background:#fdfef9;border:1px solid #9aae8d;border-radius:10px;padding:16px 20px;margin:20px 34px;box-shadow:0 6px 26px #24452b18;display:flex;align-items:center;gap:16px;flex-wrap:wrap}.comparison-tray-top{display:flex;flex-direction:column;gap:7px;font-size:12px;color:#6b7967}.comparison-tray-top strong{font-size:15px;color:#315c48}.comparison-tray-top strong span{margin-left:8px;font-size:12px}.comparison-tray-items{display:flex;flex:1;gap:8px;flex-wrap:wrap;min-width:0}.comparison-tray-items button{border:1px solid #dce5d4;border-radius:5px;background:#f0f5eb;padding:7px 10px;color:#405940;max-width:240px;overflow-wrap:anywhere;text-align:left;cursor:pointer}.comparison-tray-actions{display:flex;align-items:center;gap:15px}.comparison-tray p{color:#9c5d2f;margin:0;width:100%;font-size:13px}@media(max-width:700px){.comparison-tray{margin:12px;bottom:8px;padding:13px;gap:10px}.comparison-tray-items{flex-basis:100%}.comparison-tray-actions{width:100%;justify-content:space-between}}
</style>
