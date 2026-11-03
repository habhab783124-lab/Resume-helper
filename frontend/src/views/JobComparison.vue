<template>
  <section class="page active comparison-page">
    <div class="page-intro"><div><div class="eyebrow"><span class="eyebrow-line"></span>OPPORTUNITIES / COMPARE</div><h1>把机会放在一起看<span class="greeting-dot">。</span></h1><p>比较条件、核对依据，再决定下一步把精力放在哪里。</p></div><RouterLink class="button button-outline" to="/jobs">继续选岗位</RouterLink></div>
    <div class="comparison-controls">
      <label>这次更看重什么 <select v-model="preference" aria-label="对比偏好"><option v-for="p in COMPARISON_PREFERENCES" :key="p.value" :value="p.value">{{ p.label }}</option></select></label>
      <button class="text-button" :disabled="loading||selected.length<2" @click="load">刷新确认资料与岗位快照</button>
      <button v-if="selected.length" class="text-button" @click="clear">清空选择</button>
    </div>
    <div v-if="selected.length" class="comparison-selection" aria-label="已选岗位"><button v-for="job in selected" :key="job.id" @click="remove(job.id)" :aria-label="'移除对比：'+job.title">{{ job.title }} <span aria-hidden="true">×</span></button></div>
    <p v-if="error" role="alert" class="comparison-error">{{ error }} <button class="text-button" @click="load">重试</button></p>
    <p v-if="notice" role="status" class="comparison-notice">{{ notice }}</p>
    <div v-if="selected.length<2" class="comparison-empty"><h2>先挑选至少两个岗位</h2><p>在岗位卡片、我的收藏或投递详情中点击“加入对比”。当前标签页的选择可跨页面保留，最多 4 个。</p><div class="comparison-links"><RouterLink class="button button-primary" to="/jobs">去岗位匹配</RouterLink><RouterLink class="button button-outline" to="/applications?tab=favorites">从收藏中选择</RouterLink></div></div>
    <p v-else-if="loading" role="status">正在读取本人确认资料与岗位快照…</p>
    <template v-else-if="result">
      <div class="comparison-context"><strong>本次依据：{{ result.profileSummary }}</strong><p>{{ result.scoreNotice }}</p><p v-if="!result.profileAvailable">暂不计算个人分数。你仍可比较岗位条件，<RouterLink to="/profile">完善并确认个人资料</RouterLink>后点击刷新。</p></div>
      <div v-if="result.unavailableIds.length" class="comparison-warning" role="status"><p>{{ result.unavailableIds.length }} 个岗位快照不可用或不属于当前账号；其余可用岗位仍可查看。</p><button class="text-button" @click="removeUnavailable">移除不可用岗位</button></div>
      <p v-if="result.items.length<2" class="comparison-warning">当前可用岗位不足两个，请补选岗位后再做比较。</p>
      <p v-if="result.items.length" class="comparison-hint">按本次偏好参考分排序；同分保留选择顺序。窄屏可在表格内左右滑动查看。</p>
      <div v-if="result.items.length" class="comparison-scroll" tabindex="0" role="region" aria-label="岗位横向对比表">
        <table class="comparison-table"><caption class="visually-hidden">{{ COMPARISON_PREFERENCES.find(p=>p.value===result.preference)?.label }} · 岗位比较</caption>
          <thead><tr><th scope="col">比较项目</th><th v-for="(item,index) in result.items" :key="item.job.id" scope="col"><span class="comparison-order">机会 {{ String(index+1).padStart(2,'0') }}</span><h2>{{ item.job.title }}</h2><p>{{ item.job.company }}</p><button class="text-button" @click="remove(item.job.id)">移出对比</button></th></tr></thead>
          <tbody>
            <tr v-if="rowVisibility.score" class="comparison-score-row"><th scope="row">偏好参考分<small>不同偏好不可直接比较</small></th><td v-for="item in result.items" :key="item.job.id"><strong class="comparison-score">{{ item.score ?? '—' }}<small v-if="item.score!=null"> / 100</small></strong><p>信息覆盖权重 {{ item.knownWeight }} / 100</p></td></tr>
            <tr v-for="field in visibleFields" :key="field.key"><th scope="row">{{ field.label }}</th><td v-for="item in result.items" :key="item.job.id">{{ field.value(item.job) }}<small v-if="field.key==='salary'&&item.salaryMin!=null">统一月薪参考：{{ item.salaryMin }}～{{ item.salaryMax }} 千元/月</small></td></tr>
            <tr v-if="rowVisibility.requiredSkills"><th scope="row">岗位技能信息</th><td v-for="item in result.items" :key="item.job.id"><span v-for="skill in item.requiredSkills" :key="skill" class="comparison-tag">{{ skill }}</span><span v-if="!item.requiredSkills.length">未识别到明确技能，请核对原文</span></td></tr>
            <tr v-if="rowVisibility.evidencedSkills"><th scope="row">确认资料已体现</th><td v-for="item in result.items" :key="item.job.id"><span v-for="skill in item.evidencedSkills" :key="skill" class="comparison-tag positive">{{ skill }}</span><span v-if="!item.evidencedSkills.length">{{ result.profileAvailable?'尚无对应技能证据':'尚无已确认资料' }}</span></td></tr>
            <tr v-if="rowVisibility.unrepresentedSkills"><th scope="row">待补充证据<small>未体现不代表不会</small></th><td v-for="item in result.items" :key="item.job.id"><span v-for="skill in item.unrepresentedSkills" :key="skill" class="comparison-tag uncertain">{{ skill }}</span><span v-if="!item.unrepresentedSkills.length">{{ result.profileAvailable?'暂无待补充项；仍需核对原文':'确认个人资料后再比较' }}</span></td></tr>
            <tr v-for="index in rowVisibility.dimensions" :key="result.items[0].dimensions[index].label"><th scope="row">{{ result.items[0].dimensions[index].label }}<small>该项权重 {{ result.items[0].dimensions[index].weight }} 分</small></th><td v-for="item in result.items" :key="item.job.id"><strong>{{ item.dimensions[index].points==null?'信息待确认':item.dimensions[index].points+' / '+item.dimensions[index].weight }}</strong><p>{{ item.dimensions[index].note }}</p></td></tr>
            <tr v-if="rowVisibility.source"><th scope="row">来源与采集时间</th><td v-for="item in result.items" :key="item.job.id"><p>{{ item.job.sourceName }}</p><p>{{ formatDate(item.job.collectedAt) }}</p><a v-if="safeSourceUrl(item.job.sourceUrl)" :href="safeSourceUrl(item.job.sourceUrl)" target="_blank" rel="noopener noreferrer">查看招聘原文 ↗</a><span v-else>未提供可访问的招聘链接</span></td></tr>
            <tr v-if="rowVisibility.details"><th scope="row">岗位原文与注意事项</th><td v-for="item in result.items" :key="item.job.id"><details><summary>查看 JD 原文</summary><p class="comparison-jd">{{ item.job.description||'来源未提供完整描述。' }}</p></details><ul><li v-for="note in item.notes" :key="note">{{ note }}</li></ul></td></tr>
            <tr><th scope="row">下一步</th><td v-for="item in result.items" :key="item.job.id"><div class="comparison-next"><button class="button button-primary" :disabled="!!busyId" @click="track(item)">加入投递管理</button><button class="button button-outline" :disabled="!!busyId" @click="favorite(item)">{{ item.job.saved?'取消收藏':'收藏岗位' }}</button><button class="text-button" :disabled="!!busyId" @click="practice(item)">用此岗位练习面试</button></div></td></tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>
<script setup>
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {useJobComparison} from '../composables/useJobComparison'
import {COMPARISON_PREFERENCES,getComparisonRowVisibility} from '../utils/jobComparison'
import {compareJobs} from '../api/jobComparison'
import {addFavorite,removeFavorite} from '../api/jobs'
import {createApplication} from '../api/applications'
import {safeSourceUrl} from '../utils/applicationWorkflow'
import {getUserId,getLocalApiBaseUrl} from '../api/requestContext'
import {newInterviewRoute} from '../interview/interviewNavigation'
const router=useRouter(),{selected,remove,clear}=useJobComparison()
const preference=ref('balanced'),result=ref(null),loading=ref(false),error=ref(''),notice=ref(''),busyId=ref('')
const ids=computed(()=>JSON.stringify(selected.value.map(v=>v.id)))
const rowVisibility=computed(()=>getComparisonRowVisibility(result.value?.items||[]))
const scope=()=>getUserId()+'|'+getLocalApiBaseUrl()
let generation=0,alive=true
const fields=[{key:'place',label:'工作地点',value:j=>j.place||'未披露'},
  {key:'salary',label:'薪资原文',value:j=>j.salary||'未披露'},
  {key:'experience',label:'经验要求',value:j=>/不限/.test(j.experience||'')?'不限 / 来源未明确，请核对原文':j.experience||'未披露'},
  {key:'education',label:'学历要求',value:j=>j.education==='不限'?'不限 / 来源未明确，请核对原文':j.education||'未披露'}]
const visibleFields=computed(()=>fields.filter(field=>rowVisibility.value.fields.includes(field.key)))
const formatDate=value=>value?new Date(value).toLocaleString('zh-CN'):'未记录'
async function load(){
  const request=++generation,currentScope=scope();result.value=null;error.value='';notice.value=''
  if(selected.value.length<2){loading.value=false;return}
  loading.value=true
  try{const value=await compareJobs(selected.value.map(v=>v.id),preference.value)
    if(alive&&request===generation&&currentScope===scope())result.value=value
  }catch(e){if(alive&&request===generation&&currentScope===scope())error.value=e.message||'对比暂未加载，请重试。'}
  finally{if(alive&&request===generation)loading.value=false}
}
function removeUnavailable(){for(const id of result.value?.unavailableIds||[])remove(id)}
async function act(item,action){
  if(busyId.value)return
  const currentScope=scope();busyId.value=item.job.id;error.value='';notice.value=''
  try{await action(()=>alive&&currentScope===scope())}
  catch(e){if(alive&&currentScope===scope())error.value=e.message||'操作失败，请重试。'}
  finally{if(alive&&currentScope===scope())busyId.value=''}
}
async function track(item){await act(item,async valid=>{const app=await createApplication({jobId:item.job.id});if(valid())router.push({name:'Applications',query:{id:app.id}})})}
async function favorite(item){await act(item,async valid=>{const saved=item.job.saved;if(saved)await removeFavorite(item.job.id);else await addFavorite(item.job.id);if(valid()){item.job.saved=!saved;notice.value=saved?'已取消收藏':'已收藏岗位'}})}
function practice(item){router.push(newInterviewRoute({jobId:item.job.id}))}
function contextChanged(){busyId.value='';load()}
watch([ids,preference],load,{immediate:true})
onMounted(()=>{globalThis.addEventListener?.('request-context-change',contextChanged);globalThis.addEventListener?.('profile-updated',load)})
onBeforeUnmount(()=>{alive=false;generation++;globalThis.removeEventListener?.('request-context-change',contextChanged);globalThis.removeEventListener?.('profile-updated',load)})
</script>
<style scoped>
.comparison-page{min-width:0;padding-bottom:36px}.comparison-controls,.comparison-selection,.comparison-links{display:flex;align-items:center;gap:16px;flex-wrap:wrap}.comparison-controls{margin:24px 0 16px}.comparison-controls label{display:flex;align-items:center;gap:10px;font-size:13px}.comparison-controls select{padding:9px;border:1px solid var(--line-strong);border-radius:5px;background:var(--surface);color:var(--ink)}.comparison-selection{gap:8px;margin-bottom:20px}.comparison-selection button{padding:8px 12px;border:1px solid var(--line);border-radius:5px;background:var(--surface);color:var(--green-dark);cursor:pointer;text-align:left;overflow-wrap:anywhere}.comparison-selection span{margin-left:10px}.comparison-context{padding:18px 20px;border-left:3px solid var(--green);background:var(--surface-alt);border-radius:5px;margin:18px 0;font-size:13px;line-height:1.8}.comparison-context p{margin:8px 0 0}.comparison-warning,.comparison-error,.comparison-notice{padding:12px 15px;border-radius:5px;line-height:1.8;font-size:13px;margin:12px 0}.comparison-warning{background:#fff7eb;color:#8d663a}.comparison-error{background:#fff0eb;color:#943f34}.comparison-notice{background:#edf4e7;color:#446340}.comparison-empty{padding:38px;border:1px solid var(--line);background:var(--surface);border-radius:8px;line-height:1.9}.comparison-empty h2{font-size:22px}.comparison-links{margin-top:20px}.comparison-hint{font-size:12px;color:var(--muted);line-height:1.8}.comparison-scroll{max-width:100%;overflow:auto;border:1px solid var(--line);border-radius:8px;background:var(--surface)}.comparison-table{border-collapse:separate;border-spacing:0;width:100%;table-layout:fixed;font-size:13px;line-height:1.8}.comparison-table th,.comparison-table td{min-width:230px;width:260px;padding:20px;text-align:left;vertical-align:top;border-right:1px solid var(--line);border-bottom:1px solid var(--line);overflow-wrap:anywhere}.comparison-table tr>:first-child{position:sticky;left:0;z-index:1;background:#f5f6ee;width:150px;min-width:150px;font-weight:600}.comparison-table tr>:last-child{border-right:0}.comparison-table tbody tr:last-child>*{border-bottom:0}.comparison-table thead th{background:#f9faf5}.comparison-table h2{font-size:19px;line-height:1.5;margin:8px 0}.comparison-table p{margin:6px 0;color:var(--ink-soft);font-weight:400}.comparison-table small{display:block;color:var(--muted);font-size:11px;font-weight:400;margin-top:6px}.comparison-order{color:var(--green);font-family:var(--mono);font-size:11px;letter-spacing:1px}.comparison-score-row td{background:#f4f8ee}.comparison-score{font-size:32px;color:var(--green-dark);font-family:var(--mono)}.comparison-score small{display:inline;font-size:12px}.comparison-tag{display:inline-block;border-radius:4px;padding:3px 7px;margin:3px;background:var(--surface-alt);font-size:12px}.comparison-tag.positive{background:#eaf2e3;color:#426338}.comparison-tag.uncertain{background:#fff1df;color:#92632f}.comparison-table details{margin-bottom:12px}.comparison-table summary{cursor:pointer;color:var(--green);font-weight:600}.comparison-jd{white-space:pre-wrap;max-height:320px;overflow:auto}.comparison-table ul{padding-left:17px;margin-bottom:0;font-size:12px;color:var(--muted)}.comparison-table li{margin-bottom:9px}.comparison-next{display:flex;gap:10px;flex-direction:column;align-items:flex-start}.comparison-scroll:focus-visible{outline:2px solid var(--green);outline-offset:3px}.visually-hidden{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}@media(max-width:700px){.comparison-table th,.comparison-table td{width:230px;padding:14px}.comparison-table tr>:first-child{width:110px;min-width:110px}.comparison-empty{padding:22px}.comparison-controls{gap:12px}.comparison-controls label{width:100%}}
</style>
