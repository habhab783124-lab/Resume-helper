<template>
<section class="page active" id="page-dashboard" data-title="工作台">
          <div class="page-intro dashboard-intro">
            <div>
              <div class="eyebrow"><span class="eyebrow-line"></span>{{ todayLabel }}</div>
              <h1>早上好，{{ displayName }}<span class="greeting-dot">。</span></h1>
              <p>{{ profileReady ? '继续完善你的求职准备。' : '从一份简历材料开始，建立你的求职画像。' }}</p>
            </div>
            <button class="button button-outline" data-page="materials"><svg><use href="#i-upload"/></svg>整理求职材料</button>
          </div>

          <section class="feature-card">
            <div class="feature-copy">
              <div class="feature-label"><span class="live-dot"></span>你的求职进度 <span class="feature-divider"></span><span>{{ profileReady ? '已建立画像' : '尚未开始' }}</span></div>
              <h2>把准备做好，<br>机会自然会<span>靠近。</span></h2>
              <p>{{ profileReady ? '资料已建立，下一步完善简历并开始岗位匹配。' : '还没有求职资料。先上传一份简历，或在个人资料页手动填写。' }}</p>
              <button class="button button-light" :data-page="profileReady ? 'resume' : 'materials'">{{ profileReady ? '继续完善简历' : '去整理材料' }}<svg><use href="#i-arrow"/></svg></button>
            </div>
            <div class="feature-visual" :aria-label="`求职准备完成度 ${readiness}%`">
              <div class="orbit orbit-a"></div><div class="orbit orbit-b"></div>
              <div class="orbit-center"><span class="orbit-number">{{ readiness }}<span>%</span></span><span class="orbit-caption">准备度</span></div>
              <span class="orbit-node node-a"><svg><use href="#i-check"/></svg></span>
              <span class="orbit-node node-b"><svg><use href="#i-file"/></svg></span>
              <span class="orbit-node node-c"><svg><use href="#i-briefcase"/></svg></span>
              <span class="visual-label visual-label-top">YOUR NEXT CHAPTER</span>
              <span class="visual-label visual-label-bottom">准备 · 探索 · 前进</span>
            </div>
            <div class="feature-index">01 <span>—</span> 03</div>
          </section>

          <div class="metric-grid">
            <article class="metric-card"><div class="metric-top"><span>简历完整度</span><span class="metric-icon"><svg><use href="#i-file"/></svg></span></div><div class="metric-value">{{ readiness }}<span>%</span></div><div class="metric-foot"><span class="tiny-progress"><i :style="{ width: readiness + '%' }"></i></span><span>{{ profileReady ? '根据画像计算' : '还没有简历' }}</span></div></article>
            <article class="metric-card"><div class="metric-top"><span>本周匹配岗位</span><span class="metric-icon"><svg><use href="#i-briefcase"/></svg></span></div><div class="metric-value">{{ paddedMatched }}<span class="metric-unit">个</span></div><div class="metric-foot"><span>{{ matchedFootLabel }}</span><span class="metric-arrow">↗</span></div></article>
            <article class="metric-card"><div class="metric-top"><span>面试练习时长</span><span class="metric-icon"><svg><use href="#i-mic"/></svg></span></div><div class="metric-value">0<span class="metric-unit">分钟</span></div><div class="metric-foot"><span>还没有练习</span><span class="metric-arrow">↗</span></div></article>
            <article class="metric-card metric-card-quote"><span class="quote-mark">“</span><p>让经历被看见，<br>从讲清楚一件事开始。</p><span class="quote-byline">拾阶 · 求职札记 04</span></article>
          </div>

          <div class="dashboard-columns">
            <section class="panel journey-panel">
              <div class="panel-heading"><div><div class="eyebrow">YOUR JOURNEY</div><h3>求职旅程</h3></div><button class="text-button" data-page="profile">查看资料 <svg><use href="#i-arrow"/></svg></button></div>
              <div class="journey-list">
                <div class="journey-step" :class="profileReady ? 'is-done' : 'is-current'"><span class="journey-marker"><svg v-if="profileReady"><use href="#i-check"/></svg><template v-else>01</template></span><div class="journey-info"><strong>建立个人档案</strong><span>{{ profileReady ? '教育经历、技能与求职意向已整理' : '还没有填写个人资料' }}</span></div><span class="step-state">{{ profileReady ? '已完成' : '待开始' }}</span></div>
                <div class="journey-step" :class="profileReady ? 'is-current' : 'is-upcoming'"><span class="journey-marker">02</span><div class="journey-info"><strong>整理项目材料</strong><span>{{ profileReady ? '上传简历或项目记录，补充你的经历' : '完成个人档案后开始' }}</span></div><span class="step-state">{{ profileReady ? '进行中' : '待开始' }}</span></div>
                <div class="journey-step is-upcoming"><span class="journey-marker">03</span><div class="journey-info"><strong>打磨目标简历</strong><span>根据目标岗位完善表达</span></div><span class="step-state">待开始</span></div>
                <div class="journey-step is-upcoming"><span class="journey-marker">04</span><div class="journey-info"><strong>探索合适岗位</strong><span>根据目标方向查看精选机会</span></div><span class="step-state">待开始</span></div>
              </div>
            </section>
            <section class="panel focus-panel">
              <div class="panel-heading"><div><div class="eyebrow">TODAY'S FOCUS</div><h3>今天的重点</h3></div><span class="focus-date">{{ todayShort }}</span></div>
              <div class="focus-card focus-card-main"><span class="focus-number">01</span><div><span class="focus-tag">建议 · 15 分钟</span><strong>{{ profileReady ? '补充一个项目成果' : '先整理一份简历材料' }}</strong><p>{{ profileReady ? '试着用数据描述你在项目中的贡献。' : '上传简历或粘贴项目经历，系统会帮你整理成求职画像。' }}</p></div><button class="round-arrow" :data-page="profileReady ? 'resume' : 'materials'" aria-label="前往下一步"><svg><use href="#i-arrow"/></svg></button></div>
              <div class="focus-card"><span class="focus-number">02</span><div><span class="focus-tag">岗位 · {{ matchedCount ? matchedCount + ' 个新匹配' : '暂无匹配' }}</span><strong>{{ matchedCount ? '看看本周的岗位推荐' : '完善资料后开始岗位匹配' }}</strong></div><button class="round-arrow" data-page="jobs" aria-label="查看岗位"><svg><use href="#i-arrow"/></svg></button></div>
              <div class="focus-footer"><span class="focus-check"><svg><use href="#i-check"/></svg></span>完成一项准备，离目标更近一点</div>
            </section>
          </div>

          <section class="panel recommended-panel">
            <div class="panel-heading"><div><div class="eyebrow">CURATED FOR YOU</div><h3>{{ profileReady ? '与你经历相近的岗位' : '岗位推荐' }}</h3></div><button class="text-button" data-page="jobs">查看全部岗位 <svg><use href="#i-arrow"/></svg></button></div>
            <div class="recommended-jobs" id="dashboard-job-list">
              <article v-for="job in recommended" :key="job.id" class="recommended-job" data-page="jobs">
                <div class="rec-job-head">
                  <span class="rec-company-mark">{{ job.mark }}</span>
                  <span v-if="hasScore(job)" class="rec-match">{{ job.score }}%</span>
                </div>
                <strong>{{ job.title }}</strong>
                <p>{{ job.company }} · {{ job.city }}</p>
                <div class="rec-salary"><span>{{ job.tags && job.tags.length ? job.tags[0] : job.jobType }}</span><b>{{ job.salary }}</b></div>
              </article>
              <div v-if="!recommended.length" class="empty-jobs">{{ emptyText }}</div>
            </div>
          </section>
          <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>产品预览 2026</span></footer>
        </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { searchJobs } from '../api/jobs'
import { getProfile, profileCompleteness } from '../utils/demoProfile'
import { getAuthUser } from '../utils/authSession'

const DISPLAY_LIMIT = 3
const FETCH_LIMIT = 8

const now = new Date()
const todayLabel = now.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' }).toUpperCase()
const todayShort = `${now.getMonth() + 1}.${now.getDate()} 周${'日一二三四五六'[now.getDay()]}`

const displayName = computed(() => getAuthUser()?.displayName || '求职者')
const profile = getProfile()
const profileReady = computed(() => Boolean(
  profile.targetTitle ||
  profile.summary ||
  profile.education ||
  profile.skills?.length ||
  profile.experiences?.length ||
  profile.educations?.length
))
const readiness = computed(() => profileCompleteness(profile))

const recommended = ref([])
const matchedCount = ref(0)
const error = ref('')

const paddedMatched = computed(() => String(matchedCount.value).padStart(2, '0'))
const matchedFootLabel = computed(() => {
  if (!profileReady.value) return '完善资料后开始匹配'
  return [profile.targetCities?.[0], profile.targetTitle].filter(Boolean).join(' · ') || '最近匹配'
})
const emptyText = computed(() => {
  if (!profileReady.value) return '先完善个人资料，再查看与你经历相近的岗位。'
  return error.value ? '岗位推荐暂时加载不出来，请确认后端已启动。' : '正在为你匹配岗位…'
})

function hasScore(job) {
  return job.score !== null && job.score !== undefined
}

onMounted(async () => {
  if (!profileReady.value) return
  try {
    const data = await searchJobs({ limit: FETCH_LIMIT, explainTopN: 0, profile })
    const jobs = Array.isArray(data?.jobs) ? data.jobs : []
    recommended.value = jobs.slice(0, DISPLAY_LIMIT)
    matchedCount.value = data?.total ?? jobs.length
  } catch (caught) {
    error.value = caught.message || '加载失败'
  }
})
</script>
