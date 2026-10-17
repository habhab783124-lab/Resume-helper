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
              <span v-else>共 {{ jobs.length }} 个岗位<i class="job-sep">·</i>数据源：{{ sourceLabel }}</span>
            </div>
            <div class="job-status-right">
              <span class="job-ai-tag" :class="{ 'is-on': hasKey }">{{ hasKey ? '已配置 AI token' : '未配置 token，使用规则检索' }}</span>
              <input v-model="aiKeyInput" class="job-ai-input" type="password" autocomplete="off" placeholder="填入你的 AI token（可选）" />
              <button class="text-button" type="button" @click="applyAiKey">保存</button>
            </div>
          </div>

          <div class="jobs-summary"><div class="jobs-summary-left"><span class="summary-pin"><svg><use href="#i-pin"/></svg></span><div><strong>{{ summaryTitle }}</strong><span>基于你的个人资料推荐 <b>{{ jobs.length }}</b> 个岗位</span></div></div><div class="jobs-summary-right"><span>本次推荐更新</span><b>{{ updatedAt }}</b></div></div>

          <div class="jobs-toolbar"><label class="jobs-search"><svg><use href="#i-search"/></svg><input v-model="keyword" type="search" placeholder="搜索职位、公司或技能…"></label><div class="filter-group"><button class="filter-select" type="button" @click="cycleCity">城市：{{ cityLabel }} <svg><use href="#i-chevron"/></svg></button><button class="filter-select" type="button" @click="cycleExperience">经验：{{ experienceLabel }} <svg><use href="#i-chevron"/></svg></button><button class="filter-select" type="button" @click="cycleSort">排序：{{ sortLabel }} <svg><use href="#i-chevron"/></svg></button><button class="filter-select filter-select-action" type="button" :disabled="loading" @click="runAgent">{{ mode === 'agent' && loading ? '智能体检索中…' : '让智能体去找' }} <svg><use href="#i-spark"/></svg></button></div></div>

          <div class="job-results-header"><span>为你挑选 <b>{{ paddedCount }}</b> 个机会</span><span><i class="match-legend"></i>{{ scoreNotice }}</span></div>

          <!-- 智能体执行轨迹：让用户看见「它是怎么找的」 -->
          <div v-if="trace.length" class="job-trace">
            <div class="job-trace-head"><strong>智能体执行轨迹</strong><span>{{ aiUsed ? '已调用大模型 · ' + aiModel : '未调用大模型，按规则策略检索' }}</span></div>
            <ol class="job-trace-list"><li v-for="step in trace" :key="step.round + '-' + step.action"><span class="job-trace-round">第 {{ step.round }} 轮</span><span class="job-trace-action">{{ step.action }}</span><span class="job-trace-obs">{{ step.observation }}</span></li></ol>
          </div>

          <!-- 降级提示：后端如实标注的原因，必须原样展示，不能让用户以为拿到的是 AI 结果 -->
          <ul v-if="notes.length" class="job-notes"><li v-for="(note, index) in notes" :key="index">{{ note }}</li></ul>

          <div class="job-grid" id="job-grid">
            <article v-for="job in sortedJobs" :key="job.id" class="job-card" :data-job-id="job.id">
              <div class="job-card-top">
                <span class="company-mark" :class="job.style">{{ job.mark }}</span>
                <div class="job-title-wrap"><h3>{{ job.title }}</h3><p>{{ job.company }}</p></div>
                <span v-if="hasScore(job)" class="match-score">{{ job.score }}<small>匹配度</small></span>
              </div>
              <div class="job-meta"><span><svg><use href="#i-pin"/></svg>{{ job.place }}</span><span><svg><use href="#i-user"/></svg>{{ job.experience }}</span></div>
              <div v-if="job.tags && job.tags.length" class="job-tags"><span v-for="tag in job.tags" :key="tag">{{ tag }}</span></div>
              <div v-if="job.reason" class="job-reason"><svg><use href="#i-spark"/></svg><span>{{ job.reason }}</span></div>
              <div v-if="job.gaps && job.gaps.length" class="job-gap">待补强：{{ job.gaps[0] }}</div>
              <div class="job-card-bottom">
                <span class="job-salary">{{ job.salary }} <small>· 月薪</small></span>
                <button class="job-card-action" :class="{ 'is-saved': job.saved }" type="button" @click="toggleFavorite(job)">{{ job.saved ? '已收藏' : '收藏岗位' }} <svg><use href="#i-arrow"/></svg></button>
                <button class="job-card-action" type="button" @click="startInterviewForJob(job)">用这个岗位练习面试 <svg><use href="#i-arrow"/></svg></button>
              </div>
            </article>

            <div v-if="!sortedJobs.length" class="empty-jobs">{{ emptyText }}</div>
          </div>

          <div class="jobs-bottom-note"><span>—</span>岗位信息为课程原型演示数据，请以招聘方发布内容为准<span>—</span></div>
          <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>产品预览 2026</span></footer>
        </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { addFavorite, fetchSources, importJob, matchJob, removeFavorite, runJobAgent, searchJobs } from '../api/jobs'
import { newInterviewRoute } from '../interview/interviewNavigation.js'
import { getAiKey, setAiKey } from '../api/client'
import { getProfile } from '../utils/demoProfile'

/** 后端没给 scoreNotice 时的兜底文案。这句话必须展示，避免用户把匹配度当成录取概率。 */
const SCORE_NOTICE_FALLBACK = '匹配度是资料贴合参考，不代表录取概率'

const profile = ref(getProfile())
const router = useRouter()

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
  options.push({ label: '不限', value: '' })
  return options
})

const experienceOptions = [
  { label: '应届 / 不限', maxYears: 1 },
  { label: '经验不限', maxYears: null },
  { label: '3 年以内', maxYears: 3 }
]

const sortOptions = [
  { label: '匹配度优先', value: 'score' },
  { label: '薪资优先', value: 'salary' }
]

const cityLabel = computed(() => cityOptions.value[cityIndex.value % cityOptions.value.length]?.label || '不限')
const experienceLabel = computed(() => experienceOptions[experienceIndex.value % experienceOptions.length].label)
const sortLabel = computed(() => sortOptions[sortIndex.value % sortOptions.length].label)
const summaryTitle = computed(() => [cityOptions.value[0]?.value, profile.value.targetTitle].filter(Boolean).join(' · '))
const paddedCount = computed(() => String(jobs.value.length).padStart(2, '0'))
const sourceLabel = computed(() => {
  const names = sources.value.map((source) => source.name).filter(Boolean)
  return names.length ? names.join(' + ') : '—'
})
const statusClass = computed(() => (error.value ? 'is-error' : loading.value ? 'is-loading' : 'is-ok'))
const emptyText = computed(() => {
  if (loading.value) return '正在检索岗位…'
  if (error.value) return '岗位没能加载出来，请确认后端已启动后重试。'
  return '暂时没有找到相关岗位，试试职位名称、公司或技能关键词。'
})

/** 本地排序。后端已经按匹配度返回，这里让用户能切成薪资优先。 */
const sortedJobs = computed(() => {
  const list = [...jobs.value]
  if (sortOptions[sortIndex.value % sortOptions.length].value === 'salary') {
    list.sort((a, b) => firstNumber(b.salary) - firstNumber(a.salary))
  } else {
    list.sort((a, b) => (b.score ?? -1) - (a.score ?? -1))
  }
  return list
})

function firstNumber(text) {
  const matched = String(text || '').match(/(\d+)/)
  return matched ? Number(matched[1]) : 0
}

function hasScore(job) {
  return job.score !== null && job.score !== undefined
}

function formatNow() {
  const now = new Date()
  return `今天 ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
}

/* ------------------------------------------------------------------ 请求 */

function buildPayload() {
  return {
    keyword: keyword.value.trim(),
    city: cityOptions.value[cityIndex.value % cityOptions.value.length]?.value || '',
    maxYears: experienceOptions[experienceIndex.value % experienceOptions.length].maxYears,
    limit: 12,
    // 列表默认不调用大模型，保证筛选时够快；要看 AI 解释就点「让智能体去找」。
    explainTopN: 0,
    profile: profile.value
  }
}

function applyResult(data) {
  jobs.value = Array.isArray(data?.jobs) ? data.jobs : []
  notes.value = Array.isArray(data?.notes) ? data.notes : []
  scoreNotice.value = data?.scoreNotice || SCORE_NOTICE_FALLBACK
  aiUsed.value = Boolean(data?.aiUsed)
  aiModel.value = data?.aiModel || ''
  updatedAt.value = formatNow()
}

async function load() {
  loading.value = true
  error.value = ''
  mode.value = 'search'
  trace.value = []
  try {
    applyResult(await searchJobs(buildPayload()))
  } catch (caught) {
    error.value = caught.message || '加载失败'
    jobs.value = []
  } finally {
    loading.value = false
  }
}

/** 用户连续改条件时不要每个字符都发一次请求。 */
let debounceTimer = null
function reload() {
  if (debounceTimer) window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(load, 260)
}

async function runAgent() {
  loading.value = true
  error.value = ''
  mode.value = 'agent'
  try {
    const data = await runJobAgent({
      profile: profile.value,
      options: {
        limit: 8,
        city: cityOptions.value[cityIndex.value % cityOptions.value.length]?.value || '',
        maxYears: experienceOptions[experienceIndex.value % experienceOptions.length].maxYears,
        explainTopN: 4
      }
    })
    applyResult(data)
    trace.value = Array.isArray(data?.trace) ? data.trace : []
  } catch (caught) {
    error.value = caught.message || '智能体检索失败'
    trace.value = []
  } finally {
    loading.value = false
  }
}

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
  importing.value = true
  error.value = ''
  try {
    const imported = await importJob({ text: importText.value })
    importOpen.value = false
    importText.value = ''
    // 导入后立刻算一次匹配，这样用户马上能看到分数和差距
    const detail = await matchJob(imported.id, { profile: profile.value, explainAi: hasKey.value })
    const card = {
      ...detail.job,
      score: detail.score,
      reason: detail.aiExplanation || (detail.reasons && detail.reasons[0]) || '',
      matchedSkills: detail.matchedSkills || [],
      missingSkills: detail.missingSkills || [],
      gaps: detail.gaps || [],
      dimensions: detail.dimensions || {}
    }
    jobs.value = [card, ...jobs.value.filter((job) => job.id !== card.id)]
    notes.value = ['已导入你粘贴的岗位并计算了匹配度']
    updatedAt.value = formatNow()
  } catch (caught) {
    error.value = caught.message || '导入失败'
  } finally {
    importing.value = false
  }
}

async function toggleFavorite(job) {
  const userId = profile.value.userId || 'demo-user'
  try {
    if (job.saved) {
      await removeFavorite(job.id, userId)
      job.saved = false
    } else {
      await addFavorite(job.id, userId)
      job.saved = true
    }
  } catch (caught) {
    error.value = caught.message || '收藏失败'
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

onMounted(async () => {
  try {
    sources.value = await fetchSources()
  } catch {
    // 数据源信息拿不到不影响主流程，页面上会显示 “—”
  }
  await load()
})

onBeforeUnmount(() => {
  if (debounceTimer) window.clearTimeout(debounceTimer)
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
</style>
