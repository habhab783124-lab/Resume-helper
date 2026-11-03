<template>
  <section class="applications-page page active">
    <div class="page-intro">
      <div><div class="eyebrow">APPLICATIONS / 求职进度</div><h1>把机会推进到下一步。</h1><p>收藏岗位，保留投递时的 JD 和简历，记录每一次进展。</p></div>
      <RouterLink class="button button-outline" to="/jobs">继续寻找岗位</RouterLink>
    </div>
    <div class="workflow-toolbar">
      <button class="button" :class="tab==='applications'?'button-primary':'button-outline'" @click="tab='applications'">投递记录</button>
      <button class="button" :class="tab==='favorites'?'button-primary':'button-outline'" @click="tab='favorites'">我的收藏</button>
      <template v-if="tab==='applications'">
        <label>进度 <select v-model="status" @change="offset=0;loadList()"><option value="">全部</option><option v-for="s in APPLICATION_STATUSES" :key="s.value" :value="s.value">{{ s.label }}</option></select></label>
        <label class="due-filter"><input type="checkbox" v-model="due" @change="offset=0;loadList()" />仅看到期待办</label>
      </template>
      <button class="text-button" :disabled="busy" @click="reload">刷新</button>
    </div>
    <p v-if="error" role="alert" class="workflow-error">{{ error }}</p>
    <p v-if="notice" role="status" class="workflow-notice">{{ notice }}</p>
    <p v-if="loading" role="status">正在加载求职记录…</p>
    <template v-else-if="tab==='favorites'">
      <p v-if="!favorites.length" class="workflow-empty">还没有收藏岗位。到岗位匹配页收藏，重启后也会保留。</p>
      <div class="favorites-grid">
        <article v-for="job in favorites" :key="job.id" class="workflow-card">
          <p class="eyebrow">{{ job.company }}</p><h2>{{ job.title }}</h2><p>{{ job.place }} · {{ job.salary }}</p>
          <p>{{ job.sourceName }}</p>
          <JobComparisonButton :job="job" />
          <div class="workflow-actions"><button class="button button-primary" :disabled="busy" @click="add(job.id)">加入投递管理</button><button class="text-button" :disabled="busy" @click="unfavorite(job.id)">取消收藏</button></div>
        </article>
      </div>
    </template>
    <template v-else>
      <p class="workflow-count">共 {{ total }} 条记录<span v-if="due"> · 包含已到行动时间且尚未结束的记录</span></p>
      <div class="workflow-layout">
        <div>
          <p v-if="!items.length" class="workflow-empty">{{ due ? '当前没有到期待办。' : '暂无符合条件的投递记录。可以从收藏或岗位卡片加入。' }}</p>
          <button v-for="app in items" :key="app.id" class="application-row" :class="{selected:selected?.id===app.id}" :disabled="busy" @click="open(app.id)">
            <span class="row-head"><strong>{{ app.jobSnapshot.title }}</strong><span class="status-pill">{{ statusLabel(app.status) }}</span></span>
            <span>{{ app.jobSnapshot.company }} · {{ app.jobSnapshot.city || '地点未披露' }}</span>
            <small v-if="app.nextActionAt" :class="{'is-due':dueNow(app)}">下一步行动：{{ format(app.nextActionAt) }}{{ dueNow(app)?' · 已到期':'' }}</small>
            <small v-else>尚未设置下一步行动时间</small>
          </button>
          <div class="workflow-actions" v-if="total>limit"><button class="button button-outline" :disabled="offset===0||busy" @click="offset-=limit;loadList()">上一页</button><span>{{ Math.floor(offset/limit)+1 }} / {{ Math.ceil(total/limit) }}</span><button class="button button-outline" :disabled="offset+limit>=total||busy" @click="offset+=limit;loadList()">下一页</button></div>
        </div>
        <article v-if="selected" class="workflow-card application-detail" :aria-busy="busy">
          <div class="row-head"><h2>{{ selected.jobSnapshot.title }}</h2><button class="text-button" :disabled="busy" @click="closeDetail">关闭详情</button></div>
          <p>{{ selected.jobSnapshot.company }} · {{ selected.jobSnapshot.city || '地点未披露' }} · {{ selected.jobSnapshot.salary }}</p>
          <JobComparisonButton :job="selected.jobSnapshot" />
          <p class="snapshot-note">岗位快照采集于 {{ format(selected.createdAt) }}。原岗位失效后，本记录仍保留当时内容。</p>
          <a v-if="safeSourceUrl(selected.jobSnapshot.sourceUrl)" :href="safeSourceUrl(selected.jobSnapshot.sourceUrl)" target="_blank" rel="noopener noreferrer">查看招聘原文</a>
          <form class="application-form" @submit.prevent="save">
            <label>当前进度<select v-model="form.status" :disabled="busy"><option v-for="s in allowedStatuses(selected.status)" :key="s.value" :value="s.value">{{ s.label }}</option></select></label>
            <label>本次使用的简历<select v-model="form.resumeVersionId" :disabled="busy"><option value="">暂不绑定</option><option v-if="selected.resumeVersionId&&!versions.some(v=>v.id===selected.resumeVersionId)" :value="selected.resumeVersionId">{{ selected.resumeSnapshot?.name || '已删除版本的投递快照' }}</option><option v-for="v in versions" :key="v.id" :value="v.id">{{ v.name }}</option></select></label>
            <label>下一步行动时间<input type="datetime-local" v-model="form.nextActionAt" :disabled="busy" /></label>
            <label class="form-wide">本次进展 / 下一步行动<textarea v-model="form.note" maxlength="4000" rows="3" placeholder="例如：已投递后端实习岗位，周五跟进；准备 Redis 项目问题。" :disabled="busy"></textarea></label>
            <button class="button button-primary" :disabled="busy">{{ busy?'保存中…':'保存进度' }}</button>
          </form>
          <details><summary>查看投递时的岗位描述</summary><p class="snapshot-text">{{ selected.jobSnapshot.description || '来源未提供完整描述。' }}</p><p>技能要求：{{ selected.jobSnapshot.skills?.join('、') || '未披露' }}</p></details>
          <details v-if="selected.resumeSnapshot"><summary>查看绑定的简历快照：{{ selected.resumeSnapshot.name }}</summary>
            <p>{{ selected.resumeSnapshot.draft?.fullName }} · {{ selected.resumeSnapshot.draft?.headline }}</p><p>{{ selected.resumeSnapshot.draft?.summary }}</p>
            <p>技能：{{ selected.resumeSnapshot.draft?.skills?.join('、') }}</p>
            <div v-for="group in ['educations','experiences','projects']" :key="group"><div v-for="(entry,i) in selected.resumeSnapshot.draft?.[group] || []" :key="i"><strong>{{ entry.title }}</strong><p>{{ entry.meta }}</p><ul><li v-for="(bullet,j) in entry.bullets" :key="j">{{ bullet }}</li></ul></div></div>
          </details>
          <h3>进展时间线</h3>
          <ol class="application-timeline"><li v-for="event in [...selected.events].reverse()" :key="event.id"><strong>{{ event.fromStatus!==event.toStatus?statusLabel(event.toStatus):event.type==='CREATED'?'创建记录':'补充记录' }}</strong><time>{{ format(event.occurredAt) }}</time><p v-if="event.note">{{ event.note }}</p><RouterLink v-if="event.interviewSessionId" :to="{name:'InterviewHistory',query:{sessionId:event.interviewSessionId}}">查看关联面试与报告</RouterLink></li></ol>
          <form @submit.prevent="linkInterview" class="workflow-actions"><label>关联本账号面试<select v-model="interviewId" :disabled="busy"><option value="">选择一轮面试</option><option v-for="v in interviews" :key="v.id" :value="v.id">{{ v.role || '模拟面试' }} · {{ format(v.createdAt) }}</option></select></label><button class="button button-outline" :disabled="busy||!interviewId">加入时间线</button></form>
          <p v-if="auxiliaryError" class="snapshot-note">{{ auxiliaryError }} <button class="text-button" @click="loadOptions">重试载入选项</button></p>
          <div class="workflow-delete"><button v-if="!confirmDelete" class="text-button" :disabled="busy" @click="confirmDelete=true">删除这条投递记录</button><template v-else><span>将删除本条记录及时间线，收藏与简历原版本保留。</span><button class="button button-outline" :disabled="busy" @click="confirmDelete=false">取消</button><button class="button button-outline" :disabled="busy" @click="remove">确认删除</button></template></div>
        </article>
        <div v-else class="workflow-empty">选择一条记录，查看岗位快照、简历与进展。</div>
      </div>
    </template>
  </section>
</template>

<script setup>
import {ref,reactive,onMounted,onBeforeUnmount,watch} from 'vue'
import {RouterLink,useRoute,useRouter} from 'vue-router'
import {listApplications,createApplication,fetchApplication,changeApplication,addApplicationEvent,deleteApplication,listApplicationInterviews} from '../api/applications'
import {fetchFavorites,removeFavorite} from '../api/jobs'
import JobComparisonButton from '../components/JobComparisonButton.vue'
import {listResumeVersions} from '../api/resume'
import {getUserId,getLocalApiBaseUrl} from '../api/requestContext'
import {APPLICATION_STATUSES,statusLabel,allowedStatuses,localDateTime,dueNow,safeSourceUrl} from '../utils/applicationWorkflow'
const route=useRoute(),router=useRouter()
const tab=ref(route.query.tab==='favorites'?'favorites':'applications'),status=ref(''),due=ref(route.query.due==='1'),offset=ref(0),limit=30,total=ref(0)
const items=ref([]),favorites=ref([]),selected=ref(null),versions=ref([]),interviews=ref([])
const loading=ref(false),busy=ref(false),error=ref(''),notice=ref(''),auxiliaryError=ref(''),interviewId=ref(''),confirmDelete=ref(false)
const form=reactive({status:'PREPARING',resumeVersionId:'',nextActionAt:'',note:''})
let generation=0,detailGeneration=0,optionsGeneration=0,alive=true
const scope=()=>getUserId()+'|'+getLocalApiBaseUrl()
const format=value=>value?new Date(value).toLocaleString('zh-CN'):'—'
async function loadList(){
  const request=++generation,currentScope=scope();loading.value=true;error.value=''
  try{
    const [page,saved]=await Promise.all([listApplications({status:status.value||undefined,due:due.value,offset:offset.value,limit}),fetchFavorites()])
    if(!alive||request!==generation||currentScope!==scope())return
    items.value=page.items||[];total.value=page.total||0;favorites.value=saved.jobs||[]
  }catch(e){if(alive&&request===generation&&currentScope===scope())error.value=e.message||'加载失败，请重试'}
  finally{if(alive&&request===generation)loading.value=false}
}
async function loadOptions(){
  const request=++optionsGeneration,currentScope=scope()
  const results=await Promise.allSettled([listResumeVersions(),listApplicationInterviews()])
  if(!alive||request!==optionsGeneration||currentScope!==scope())return
  versions.value=results[0].status==='fulfilled'?results[0].value:[]
  interviews.value=results[1].status==='fulfilled'?results[1].value.items||[]:[]
  auxiliaryError.value=results.some(r=>r.status==='rejected')?'部分简历或面试选项未载入；已保存快照不受影响。':''
}
function select(app){
  selected.value=app;confirmDelete.value=false;interviewId.value=''
  Object.assign(form,{status:app.status,resumeVersionId:app.resumeVersionId||'',nextActionAt:localDateTime(app.nextActionAt),note:''})
}
async function open(id){
  const request=++detailGeneration,currentScope=scope()
  selected.value=null
  error.value=''
  try{const app=await fetchApplication(id);if(alive&&request===detailGeneration&&currentScope===scope())select(app)}
  catch(e){if(alive&&request===detailGeneration&&currentScope===scope())error.value=e.message}
}
function closeDetail(){detailGeneration++;selected.value=null;confirmDelete.value=false;router.replace({name:'Applications',query:due.value?{due:'1'}:{}})}
async function mutate(action){
  if(busy.value)return
  const currentScope=scope();busy.value=true;error.value='';notice.value=''
  try{await action(currentScope)}
  catch(e){if(alive&&currentScope===scope())error.value=e.status===409?'记录已被更新，请刷新后重新修改。当前表单尚未提交。':e.message||'保存失败，请重试'}
  finally{if(alive&&currentScope===scope())busy.value=false}
}
async function save(){const app=selected.value;if(!app)return
  const payload={version:app.version,status:form.status,resumeVersionId:form.resumeVersionId,nextActionAt:form.nextActionAt?new Date(form.nextActionAt).toISOString():null,note:form.note}
  await mutate(async owner=>{const value=await changeApplication(app.id,payload);if(!alive||owner!==scope())return;select(value);notice.value='进度已保存';await loadList()})
}
async function linkInterview(){const app=selected.value;if(!app||!interviewId.value)return
  const payload={version:app.version,note:'关联面试记录',interviewSessionId:interviewId.value}
  await mutate(async owner=>{const value=await addApplicationEvent(app.id,payload);if(!alive||owner!==scope())return;select(value);notice.value='面试已加入时间线';await loadList()})
}
async function add(jobId){await mutate(async owner=>{const app=await createApplication({jobId});if(!alive||owner!==scope())return;tab.value='applications';status.value='';due.value=false;offset.value=0;select(app);await loadList()})}
async function unfavorite(id){await mutate(async owner=>{await removeFavorite(id);if(alive&&owner===scope()){favorites.value=favorites.value.filter(v=>v.id!==id);notice.value='已取消收藏'}})}
async function remove(){const app=selected.value;if(!app)return
  await mutate(async owner=>{await deleteApplication(app.id,app.version);if(!alive||owner!==scope())return;closeDetail();offset.value=0;notice.value='投递记录已删除';await loadList()})
}
async function reload(){await loadList();if(selected.value)await open(selected.value.id)}
function resetScope(){generation++;detailGeneration++;optionsGeneration++;selected.value=null;items.value=[];favorites.value=[];versions.value=[];interviews.value=[];busy.value=false;offset.value=0;notice.value='';loadList();loadOptions()}
watch(()=>route.query.id,id=>{if(typeof id==='string')open(id)})
watch(()=>route.query.due,value=>{due.value=value==='1';offset.value=0;loadList()})
onMounted(()=>{loadList();loadOptions();if(typeof route.query.id==='string')open(route.query.id);globalThis.addEventListener?.('request-context-change',resetScope)})
onBeforeUnmount(()=>{alive=false;generation++;detailGeneration++;optionsGeneration++;globalThis.removeEventListener?.('request-context-change',resetScope)})
</script>

<style scoped>
.applications-page{padding-bottom:36px}.workflow-toolbar,.workflow-actions,.row-head{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.workflow-toolbar{margin:22px 0}.workflow-toolbar label,.application-form label{display:flex;gap:8px;align-items:center}.workflow-toolbar select,.application-form select,.application-form input,.workflow-actions select{min-height:36px;border:1px solid #dce3d8;border-radius:5px;background:#fff;padding:6px 9px;max-width:100%;color:#405545}
.workflow-error{padding:12px;color:#933d35;background:#fff2ee;border-radius:6px}.workflow-notice{padding:12px;background:#eef5e9;color:#3d6544}.workflow-count,.snapshot-note,time{font-size:12px;color:#6c7a6b}.workflow-layout{display:grid;grid-template-columns:minmax(240px,1fr) minmax(0,1.6fr);gap:22px;align-items:start}.workflow-card{background:#fff;border:1px solid #e2e8dc;border-radius:8px;padding:22px;min-width:0}.workflow-card h2{font-size:20px;margin:7px 0 12px}.workflow-card h3{font-size:16px;margin-top:26px}.workflow-card p{line-height:1.8;overflow-wrap:anywhere}.application-row{width:100%;text-align:left;padding:17px;margin:0 0 10px;border:1px solid #e1e6dc;border-radius:7px;background:#fff;color:#475e4c;cursor:pointer;display:flex;flex-direction:column;gap:10px}.application-row.selected{border-color:#709564;background:#f4f8f0}.row-head{justify-content:space-between;width:100%;gap:8px}.status-pill{background:#edf3e7;padding:3px 8px;border-radius:4px;font-size:12px;white-space:nowrap}.is-due{color:#a2602e}.application-form{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin:22px 0}.application-form label{flex-direction:column;align-items:stretch;font-size:13px}.application-form textarea{padding:10px;border:1px solid #dce3d8;border-radius:5px;resize:vertical;font:inherit;line-height:1.6}.form-wide{grid-column:1/-1}.snapshot-text{white-space:pre-wrap;max-height:360px;overflow:auto}.application-detail details{border-top:1px solid #e7ebdf;padding:15px 0}.application-detail summary{cursor:pointer;font-weight:600}.application-timeline{list-style:none;padding:0}.application-timeline li{border-left:2px solid #dbe6d0;padding:0 0 18px 15px}.application-timeline time{display:block;margin-top:4px}.application-timeline p{white-space:pre-wrap;margin:7px 0}.workflow-delete{border-top:1px solid #e7ebdf;margin-top:22px;padding-top:16px;display:flex;gap:10px;flex-wrap:wrap;font-size:13px}.favorites-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.workflow-empty{padding:26px;color:#74816f;background:#f7f9f3;border-radius:6px;line-height:1.8}.application-row:focus-visible,summary:focus-visible{outline:2px solid #709564;outline-offset:3px}
@media(max-width:1050px){.workflow-layout{grid-template-columns:1fr}.favorites-grid{grid-template-columns:1fr}}@media(max-width:580px){.application-form{grid-template-columns:1fr}.form-wide{grid-column:auto}.workflow-card{padding:16px}.workflow-toolbar{gap:8px}.workflow-toolbar label{width:100%}}
</style>
