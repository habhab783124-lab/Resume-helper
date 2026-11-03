<template>
<section class="page active" id="page-jobs" data-title="岗位匹配">
          <div class="page-intro"><div><div class="eyebrow"><span class="eyebrow-line"></span>OPPORTUNITIES / 04</div><h1>找到适合你的机会<span class="greeting-dot">。</span></h1><p>根据你的技能、经历和求职意向，整理值得进一步了解的岗位。</p></div><button class="button button-outline" type="button" @click="toggleImport">＋ 粘贴岗位描述</button></div>

          <!-- 粘贴 JD 导入：外部岗位来源不可用时的兜底入口 -->
          <div v-if="importOpen" class="job-panel">
            <div class="job-panel-head"><strong>粘贴岗位描述</strong><span>从招聘网站复制整段 JD 贴进来，会自动抽取公司、城市、薪资、技能并计算匹配度</span></div>
            <textarea v-model="importText" class="job-panel-input" rows="6" placeholder="岗位名称：Java 后端开发工程师&#10;公司名称：某某科技&#10;薪资：15-22K&#10;工作地点：上海徐汇区&#10;任职要求：本科，应届生亦可；熟悉 Java、Spring Boot、MySQL……"></textarea>
            <div class="job-panel-actions">
              <button class="button button-primary" type="button" :disabled="importing || !importText.trim()" @click="submitImport">{{ importing ? '导入中…' : '导入并匹配' }}</button>
              <button class="text-button" type="button" @click="importOpen = false">取消</button>
            </div>
          </div>

          <!-- 后端连通状态 + 用户自带 AI token -->
          <div class="job-status">
            <div class="job-status-left">
              <span class="job-dot" :class="statusClass"></span>
              <span v-if="loading">正在检索岗位…</span>
              <span v-else-if="error">{{ error }}</span>
              <span v-else>返回 {{ jobs.length }} 个岗位（本次评分候选 {{ candidateCount }} 个）<i class="job-sep">·</i>来源：{{ sourceLabel }}</span>
            </div>
            <div class="job-status-right">
              <span class="job-ai-tag" :class="{ 'is-on': hasKey }">{{ hasKey ? '已配置 AI token' : '未配置 token，使用规则检索' }}</span>
              <input v-model="aiKeyInput" class="job-ai-input" type="password" autocomplete="off" placeholder="填入你的 AI token（可选）" />
              <button class="text-button" type="button" @click="applyAiKey">保存</button>
            </div>
          </div>

          <div class="jobs-summary"><div class="jobs-summary-left"><span class="summary-pin"><svg><use href="#i-pin"/></svg></span><div><strong>{{ summaryTitle }}</strong><span>基于你的个人资料推荐 <b>{{ jobs.length }}</b> 个岗位</span></div></div><div class="jobs-summary-right"><span>本次推荐更新</span><b>{{ updatedAt }}</b></div></div>

          <div class="jobs-toolbar"><label class="jobs-search"><svg><use href="#i-search"/></svg><input v-model="keyword" type="search" placeholder="搜索职位、公司或技能…"></label><div class="filter-group"><button class="filter-select" type="button" @click="cycleCity">城市：{{ cityLabel }} <svg><use href="#i-chevron"/></svg></button><button class="filter-select" type="button" @click="cycleExperience">经验：{{ experienceLabel }} <svg><use href="#i-chevron"/></svg></button><button class="filter-select" type="button" @click="cycleSort">排序：{{ sortLabel }} <svg><use href="#i-chevron"/></svg></button><label class="job-limit">数量 <select v-model.number="resultLimit" @change="reload"><option v-for="limit in JOB_LIMITS" :key="limit" :value="limit">最多 {{ limit }} 个</option></select></label><button class="filter-select filter-select-action" type="button" :disabled="loading" @click="runAgent">{{ mode === 'agent' && loading ? '智能体检索中…' : '让智能体去找' }} <svg><use href="#i-spark"/></svg></button></div></div>

          <div class="job-results-header"><span>为你挑选 <b>{{ paddedCount }}</b> 个机会</span><span><i class="match-legend"></i>{{ scoreNotice }}</span></div>

          <!-- 智能体执行轨迹：让用户看见「它是怎么找的」 -->
          <div v-if="trace.length" class="job-trace">
            <div class="job-trace-head"><strong>智能体执行轨迹</strong><span>{{ aiUsed ? '已调用大模型 · ' + aiModel : '未调用大模型，按规则策略检索' }}</span></div>
            <ol class="job-trace-list"><li v-for="step in trace" :key="step.round + '-' + step.action"><span class="job-trace-round">第 {{ step.round }} 轮</span><span class="job-trace-action">{{ step.action }}</span><span class="job-trace-obs">{{ step.observation }}</span></li></ol>
          </div>

          <!-- 降级提示：后端如实标注的原因，必须原样展示，不能让用户以为拿到的是 AI 结果 -->
          <ul v-if="notes.length" class="job-notes"><li v-for="(note, index) in notes" :key="index">{{ note }}</li></ul>

          <div class="job-grid" id="job-grid">
            <article v-for="job in visibleJobs" :key="job.id" class="job-card" :data-job-id="job.id">
              <div class="job-card-top">
                <span class="company-mark" :class="job.style">{{ job.mark }}</span>
                <div class="job-title-wrap"><h3>{{ job.title }}</h3><p>{{ job.company }}</p></div>
                <span v-if="hasScore(job)" class="match-score">{{ job.score }}<small>匹配度</small></span>
              </div>
              <div class="job-meta"><span><svg><use href="#i-pin"/></svg>{{ job.place }}</span><span><svg><use href="#i-user"/></svg>{{ job.experience }}</span></div>
              <div v-if="job.tags && job.tags.length" class="job-tags"><span v-for="tag in job.tags" :key="tag">{{ tag }}</span></div>
              <div v-if="job.reason" class="job-reason"><svg><use href="#i-spark"/></svg><span>{{ job.reason }}</span></div>
              <div v-if="job.gaps && job.gaps.length" class="job-gap">待补强：{{ job.gaps[0] }}</div>
              <JobComparisonButton :job="job" />
              <div class="job-card-bottom">
                <span class="job-salary">{{ job.salary }}</span>
                <button class="job-card-action" :class="{ 'is-saved': job.saved }" type="button" :disabled="favoriteBusy.has(job.id)" @click="toggleFavorite(job)">{{ job.saved ? '已收藏' : '收藏岗位' }} <svg><use href="#i-arrow"/></svg></button>
                <button class="job-card-action" type="button" :disabled="creatingApplication" @click="trackApplication(job)">加入投递管理 <svg><use href="#i-arrow"/></svg></button>
                <button class="job-card-action" type="button" @click="startInterviewForJob(job)">用这个岗位练习面试 <svg><use href="#i-arrow"/></svg></button>
              </div>
            </article>

            <div v-if="!sortedJobs.length" class="empty-jobs">{{ emptyText }}</div>
          </div>
          <div v-if="visibleCount < sortedJobs.length" class="job-more"><button class="button button-outline" @click="visibleCount += 24">显示更多（已显示 {{ visibleJobs.length }} / {{ sortedJobs.length }}）</button></div>
          <p v-if="candidateCount > jobs.length" class="job-result-hint">已按匹配度选取前 {{ jobs.length }} 个；可增加数量查看本次候选中的更多岗位。候选数不代表全网在招总数。</p>

          <div class="jobs-bottom-note"><span>—</span>岗位来自招聘官网、已配置来源或本人导入；在招状态与具体要求请以招聘原文为准<span>—</span></div>
          <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>产品预览 2026</span></footer>
        </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import JobComparisonButton from '../components/JobComparisonButton.vue'
import { createApplication } from '../api/applications'
import { addFavorite, importJob, matchJob, removeFavorite, runJobAgent, searchJobs } from '../api/jobs'
import { fetchProfile } from '../api/materials'
import { newInterviewRoute } from '../interview/interviewNavigation.js'
import { getAiKey, setAiKey } from '../api/client'
import { emptyProfile } from '../utils/demoProfile'
import { getUserId, getLocalApiBaseUrl } from '../api/requestContext'
import { DEFAULT_JOB_LIMIT, JOB_LIMITS, EXPERIENCE_OPTIONS, jobSearchPayload, sortJobCards, jobSourceLabel } from '../utils/jobSearch'

/** 后端没给 scoreNotice 时的兜底文案。这句话必须展示，避免用户把匹配度当成录取概率。 */
const SCORE_NOTICE_FALLBACK = '匹配度是资料贴合参考，不代表录取概率'

const profile = ref(emptyProfile())
const router = useRouter()
const creatingApplication = ref(false)

async function trackApplication(job) {
  if (creatingApplication.value) return
  creatingApplication.value = true
  const owner = scope()
  try {
    const value = await createApplication({ jobId: job.id })
    if (alive && owner === scope()) router.push({ name: 'Applications', query: { id: value.id } })
  } catch (cause) {
    if (alive && owner === scope()) error.value = cause.message || '创建投递记录失败'
  } finally {
    if (alive && owner === scope()) creatingApplication.value = false
  }
}

function startInterviewForJob(job) {
  if (job?.id) router.push(newInterviewRoute({ jobId: String(job.id) }))
}

const jobs = ref([])
const loading = ref(false)
const error = ref('')
const notes = ref([])
const scoreNotice = ref(SCORE_NOTICE_FALLBACK)
const mode = ref('search')
const trace = ref([])
const aiUsed = ref(false)
const aiModel = ref('')
const sources = ref([])
const updatedAt = ref('—')
const candidateCount = ref(0)
const resultLimit = ref(DEFAULT_JOB_LIMIT)
const visibleCount = ref(24)
const favoriteBusy = ref(new Set())
const scope = () => getUserId() + '|' + getLocalApiBaseUrl()
let generation = 0, contextGeneration = 0, alive = true, controller = null

const keyword = ref('')
const cityIndex = ref(0)
const experienceIndex = ref(0)
const sortIndex = ref(0)

const importOpen = ref(false)
const importText = ref('')
const importing = ref(false)

const hasKey = ref(Boolean(getAiKey()))
const aiKeyInput = ref('')

/* ------------------------------------------------------------------ 筛选选项
 * 沿用原型里三个按钮的外观，点一下循环切换下一个取值。
 * 这样做不需要新增任何 CSS，也不会和现有设计冲突。
 * ------------------------------------------------------------------------ */
const cityOptions = computed(() => {
  const cities = Array.isArray(profile.value.targetCities) ? profile.value.targetCities : []
  const options = cities.map((city) => ({ label: city, value: city }))
  options.unshift({ label: '不限', value: '' })
  return options
})

const experienceOptions = EXPERIENCE_OPTIONS

const sortOptions = [
  { label: '匹配度优先', value: 'score' },
  { label: '薪资优先', value: 'salary' }
]

const cityLabel = computed(() => cityOptions.value[cityIndex.value % cityOptions.value.length]?.label || '不限')
const experienceLabel = computed(() => experienceOptions[experienceIndex.value % experienceOptions.length].label)
const sortLabel = computed(() => sortOptions[sortIndex.value % sortOptions.length].label)
const summaryTitle = computed(() => [cityLabel.value, profile.value.targetTitle].filter(Boolean).join(' · '))
const paddedCount = computed(() => String(jobs.value.length).padStart(2, '0'))
const sourceLabel = computed(() => jobSourceLabel(sources.value))
const statusClass = computed(() => (error.value ? 'is-error' : loading.value ? 'is-loading' : 'is-ok'))
const emptyText = computed(() => {
  if (loading.value) return '正在检索岗位…'
  if (error.value) return '岗位没能加载出来，请确认后端已启动后重试。'
  return '暂时没有找到相关岗位，试试职位名称、公司或技能关键词。'
})

/** 本地排序。后端已经按匹配度返回，这里让用户能切成薪资优先。 */
const sortedJobs = computed(() => sortJobCards(jobs.value, sortOptions[sortIndex.value % sortOptions.length].value))
const visibleJobs = computed(() => sortedJobs.value.slice(0, visibleCount.value))

function hasScore(job) {
  return job.score !== null && job.score !== undefined
}

function formatNow() {
  const now = new Date()
  return `今天 ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
}

/* ------------------------------------------------------------------ 请求 */

function buildPayload() {
  return jobSearchPayload({
    keyword: keyword.value.trim(),
    city: cityOptions.value[cityIndex.value % cityOptions.value.length]?.value || '',
    maxYears: experienceOptions[experienceIndex.value % experienceOptions.length].maxYears,
    limit: resultLimit.value,
    // 列表默认不调用大模型，保证筛选时够快；要看 AI 解释就点「让智能体去找」。
    explainTopN: 0,
    profile: profile.value
  })
}

function applyResult(data) {
  jobs.value = Array.isArray(data?.jobs) ? data.jobs : []
  candidateCount.value = data?.candidateCount ?? jobs.value.length
  visibleCount.value = 24
  sources.value = Array.isArray(data?.sources) ? data.sources : []
  notes.value = Array.isArray(data?.notes) ? data.notes : []
  scoreNotice.value = data?.scoreNotice || SCORE_NOTICE_FALLBACK
  aiUsed.value = Boolean(data?.aiUsed)
  aiModel.value = data?.aiModel || ''
  updatedAt.value = formatNow()
}

function invalidateSearch() {
  generation++
  controller?.abort()
  if (debounceTimer) window.clearTimeout(debounceTimer)
}

async function load(agentMode = false) {
  invalidateSearch()
  const request = generation, owner = scope()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  mode.value = agentMode ? 'agent' : 'search'
  trace.value = []
  try {
    const payload = buildPayload()
    const { profile: currentProfile, ...options } = payload
    const data = agentMode
      ? await runJobAgent({ profile: currentProfile, options: { ...options, explainTopN: 4 } }, { signal: controller.signal })
      : await searchJobs(payload, { signal: controller.signal })
    if (!alive || request !== generation || owner !== scope()) return
    applyResult(data)
    if (agentMode) trace.value = Array.isArray(data?.trace) ? data.trace : []
  } catch (caught) {
    if (alive && request === generation && owner === scope()) {
      error.value = caught.message || '加载失败'
      jobs.value = []
      candidateCount.value = 0
      sources.value = []
    }
  } finally {
    if (alive && request === generation && owner === scope()) loading.value = false
  }
}

/** 用户连续改条件时不要每个字符都发一次请求。 */
let debounceTimer = null
function reload() {
  invalidateSearch()
  loading.value = true
  debounceTimer = window.setTimeout(() => load(), 260)
}

const runAgent = () => load(true)

/* ------------------------------------------------------------------ 交互 */

function cycleCity() {
  cityIndex.value = (cityIndex.value + 1) % cityOptions.value.length
  reload()
}

function cycleExperience() {
  experienceIndex.value = (experienceIndex.value + 1) % experienceOptions.length
  reload()
}

function cycleSort() {
  // 排序是本地做的，不需要重新请求
  sortIndex.value = (sortIndex.value + 1) % sortOptions.length
}

function toggleImport() {
  importOpen.value = !importOpen.value
}

async function submitImport() {
  if (importing.value || !importText.value.trim()) return
  importing.value = true
  const owner = scope()
  error.value = ''
  try {
    const imported = await importJob({ text: importText.value })
    if (!alive || owner !== scope()) return
    // 导入已持久化，即便后续匹配失败也先保留可收藏、对比、投递的卡片。
    invalidateSearch()
    loading.value = false
    jobs.value = [imported, ...jobs.value.filter(job => job.id !== imported.id)]
    candidateCount.value = Math.max(candidateCount.value, jobs.value.length)
    importOpen.value = false
    importText.value = ''
    const request = generation
    const detail = await matchJob(imported.id, { profile: profile.value, explainAi: false }).catch(() => null)
    if (!alive || owner !== scope() || request !== generation) return
    if (!detail) {
      notes.value = ['岗位已导入并保留，可收藏、对比或加入投递管理；本次匹配未完成，请稍后重试。']
      return
    }
    const card = {
      ...detail.job,
      score: detail.score,
      reason: detail.aiExplanation || (detail.reasons && detail.reasons[0]) || '',
      matchedSkills: detail.matchedSkills || [],
      missingSkills: detail.missingSkills || [],
      gaps: detail.gaps || [],
      dimensions: detail.dimensions || {}
    }
    jobs.value = jobs.value.map(job => job.id === card.id ? card : job)
    notes.value = ['已导入你粘贴的岗位并计算了匹配度']
    updatedAt.value = formatNow()
  } catch (caught) {
    if (alive && owner === scope()) error.value = caught.message || '导入失败'
  } finally {
    if (alive && owner === scope()) importing.value = false
  }
}

async function toggleFavorite(job) {
  if (favoriteBusy.value.has(job.id)) return
  favoriteBusy.value.add(job.id)
  const owner = scope()
  const userId = getUserId()
  try {
    if (job.saved) {
      await removeFavorite(job.id, userId)
      if (alive && owner === scope()) job.saved = false
    } else {
      await addFavorite(job.id, userId)
      if (alive && owner === scope()) job.saved = true
    }
  } catch (caught) {
    if (alive && owner === scope()) error.value = caught.message || '收藏失败'
  } finally {
    if (alive && owner === scope()) favoriteBusy.value.delete(job.id)
  }
}

function applyAiKey() {
  setAiKey(aiKeyInput.value)
  hasKey.value = Boolean(getAiKey())
  aiKeyInput.value = ''
  notes.value = [
    hasKey.value
      ? '已保存 AI token（只存在当前标签页，关闭浏览器即失效）'
      : '已清除 AI token，将使用规则检索'
  ]
}

/* ------------------------------------------------------------------ 生命周期 */

async function refreshContext() {
  invalidateSearch()
  const request = ++contextGeneration, owner = scope()
  profile.value = emptyProfile()
  jobs.value = []; sources.value = []; notes.value = []; trace.value = []
  candidateCount.value = 0; error.value = ''; updatedAt.value = '—'
  importText.value = ''; importOpen.value = false; importing.value = false
  favoriteBusy.value = new Set(); creatingApplication.value = false
  cityIndex.value = 0; loading.value = true
  try {
    const confirmed = await fetchProfile()
    if (!alive || request !== contextGeneration || owner !== scope()) return
    profile.value = { ...emptyProfile(), ...confirmed }
  } catch {
    // 尚未确认资料时照常检索，后端不生成个人匹配分。
  }
  if (!alive || request !== contextGeneration || owner !== scope()) return
  await load()
}

onMounted(() => {
  globalThis.addEventListener?.('profile-updated', refreshContext)
  globalThis.addEventListener?.('request-context-change', refreshContext)
  refreshContext()
})

onBeforeUnmount(() => {
  alive = false; contextGeneration++; invalidateSearch()
  globalThis.removeEventListener?.('profile-updated', refreshContext)
  globalThis.removeEventListener?.('request-context-change', refreshContext)
})

watch(keyword, reload)
</script>

<style scoped>
/* 新增的样式都跟着 career-studio.css 的调色板和字号走，避免视觉突兀 */
.job-status { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; padding: 8px 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface); font-size: 9px; color: var(--ink-soft); }
.job-status-left, .job-status-right { display: flex; align-items: center; gap: 8px; min-width: 0; }
.job-sep { margin: 0 5px; color: var(--line-strong); font-style: normal; }
.job-dot { width: 6px; height: 6px; flex: 0 0 6px; border-radius: 50%; background: var(--muted); }
.job-dot.is-ok { background: var(--green); }
.job-dot.is-loading { background: var(--warm); }
.job-dot.is-error { background: var(--danger); }
.job-ai-tag { padding: 2px 7px; border-radius: 3px; background: var(--surface-alt); color: var(--muted); white-space: nowrap; }
.job-ai-tag.is-on { background: var(--green-light); color: var(--green-dark); }
.job-ai-input { width: 170px; padding: 4px 7px; border: 1px solid var(--line-strong); border-radius: 3px; background: var(--surface); font-family: var(--mono); font-size: 9px; color: var(--ink); }
.job-panel { margin-bottom: 12px; padding: 12px 14px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface); }
.job-panel-head { display: flex; flex-direction: column; gap: 3px; margin-bottom: 8px; }
.job-panel-head strong { font-size: 10px; color: var(--ink); }
.job-panel-head span { font-size: 9px; color: var(--muted); }
.job-panel-input { width: 100%; padding: 8px; border: 1px solid var(--line-strong); border-radius: 4px; font-family: var(--mono); font-size: 9px; line-height: 1.6; color: var(--ink); resize: vertical; }
.job-panel-actions { display: flex; align-items: center; gap: 12px; margin-top: 9px; }
.job-trace { margin-bottom: 12px; padding: 10px 14px; border: 1px solid var(--line); border-left: 3px solid var(--green); border-radius: 5px; background: var(--surface-alt); }
.job-trace-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: 7px; }
.job-trace-head strong { font-size: 10px; color: var(--ink); }
.job-trace-head span { font-size: 9px; color: var(--muted); }
.job-trace-list { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 5px; }
.job-trace-list li { display: flex; align-items: baseline; gap: 8px; font-size: 9px; color: var(--ink-soft); }
.job-trace-round { flex: 0 0 48px; color: var(--green); font-weight: 600; }
.job-trace-action { flex: 0 0 86px; font-family: var(--mono); color: var(--muted); }
.job-trace-obs { flex: 1; min-width: 0; }
.job-notes { margin: 0 0 12px; padding: 8px 12px 8px 26px; border: 1px solid #f0e2cc; border-radius: 5px; background: #fdf9f1; font-size: 9px; line-height: 1.7; color: #8a6a3a; }
.job-gap { margin: 7px 0 0; padding: 5px 8px; border-radius: 3px; background: #fdf6ef; font-size: 9px; line-height: 1.6; color: #a5703d; }
.filter-select-action { color: var(--green); border-color: var(--green-light); }
.filter-select-action:disabled { opacity: .55; cursor: not-allowed; }
.job-status, .job-status-left, .job-status-right, .filter-group { flex-wrap: wrap; }
.job-limit { display: flex; align-items: center; gap: 5px; font-size: 11px; color: var(--ink-soft); }
.job-limit select { max-width: 120px; padding: 6px; border: 1px solid var(--line); border-radius: 4px; background: var(--surface); color: inherit; }
.job-more { margin: 18px 0; text-align: center; }
.job-result-hint { font-size: 12px; line-height: 1.8; color: var(--muted); }
</style>
