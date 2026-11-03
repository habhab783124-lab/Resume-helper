<template>
  <button class="job-card-action compare-toggle" type="button" :aria-pressed="included" @click="toggle(job)">{{ included?'移出对比':'加入对比' }}</button>
</template>
<script setup>
import {computed} from 'vue'
import {useJobComparison} from '../composables/useJobComparison'
const props=defineProps({job:{type:Object,required:true}})
const {selected,toggle}=useJobComparison()
const included=computed(()=>selected.value.some(v=>v.id===props.job.id))
</script>
<style scoped>
.compare-toggle[aria-pressed=true]{color:var(--green-dark);font-weight:700;text-decoration:underline;text-underline-offset:4px}
</style>
