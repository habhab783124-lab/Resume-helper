<template>
<section class="page active" id="page-dashboard" data-title="工作台">
          <div class="page-intro dashboard-intro">
            <div>
              <div class="eyebrow"><span class="eyebrow-line"></span>MONDAY, OCTOBER 12</div>
              <h1>早上好，林同学<span class="greeting-dot">。</span></h1>
              <p>每一次认真准备，都会让下一步更清晰。</p>
            </div>
            <button class="button button-outline" data-page="materials"><svg><use href="#i-upload"/></svg>整理求职材料</button>
          </div>

          <section class="feature-card">
            <div class="feature-copy">
              <div class="feature-label"><span class="live-dot"></span>你的求职进度 <span class="feature-divider"></span><span>第 03 周</span></div>
              <h2>把准备做好，<br>机会自然会<span>靠近。</span></h2>
              <p>已完成基础资料整理，下一步完善简历并开始岗位匹配。</p>
              <button class="button button-light" data-page="resume">继续完善简历<svg><use href="#i-arrow"/></svg></button>
            </div>
            <div class="feature-visual" aria-label="求职准备完成度 68%">
              <div class="orbit orbit-a"></div><div class="orbit orbit-b"></div>
              <div class="orbit-center"><span class="orbit-number">68<span>%</span></span><span class="orbit-caption">准备度</span></div>
              <span class="orbit-node node-a"><svg><use href="#i-check"/></svg></span>
              <span class="orbit-node node-b"><svg><use href="#i-file"/></svg></span>
              <span class="orbit-node node-c"><svg><use href="#i-briefcase"/></svg></span>
              <span class="visual-label visual-label-top">YOUR NEXT CHAPTER</span>
              <span class="visual-label visual-label-bottom">准备 · 探索 · 前进</span>
            </div>
            <div class="feature-index">01 <span>—</span> 03</div>
          </section>

          <div class="metric-grid">
            <article class="metric-card"><div class="metric-top"><span>简历完整度</span><span class="metric-icon"><svg><use href="#i-file"/></svg></span></div><div class="metric-value">82<span>%</span><span class="metric-trend">↑ 12%</span></div><div class="metric-foot"><span class="tiny-progress"><i style="width:82%"></i></span><span>较上周</span></div></article>
            <article class="metric-card"><div class="metric-top"><span>本周匹配岗位</span><span class="metric-icon"><svg><use href="#i-briefcase"/></svg></span></div><div class="metric-value">{{ paddedMatched }}<span class="metric-unit">个</span><span class="metric-trend">+ 3</span></div><div class="metric-foot"><span>{{ matchedFootLabel }}</span><span class="metric-arrow">↗</span></div></article>
            <article class="metric-card"><div class="metric-top"><span>面试练习时长</span><span class="metric-icon"><svg><use href="#i-mic"/></svg></span></div><div class="metric-value">42<span class="metric-unit">分钟</span></div><div class="metric-foot"><span>本周已完成 2 次练习</span><span class="metric-arrow">↗</span></div></article>
            <article class="metric-card metric-card-quote"><span class="quote-mark">“</span><p>让经历被看见，<br>从讲清楚一件事开始。</p><span class="quote-byline">拾阶 · 求职札记 04</span></article>
          </div>

          <div class="dashboard-columns">
            <section class="panel journey-panel">
              <div class="panel-heading"><div><div class="eyebrow">YOUR JOURNEY</div><h3>求职旅程</h3></div><button class="text-button" data-page="profile">查看资料 <svg><use href="#i-arrow"/></svg></button></div>
              <div class="journey-list">
                <div class="journey-step is-done"><span class="journey-marker"><svg><use href="#i-check"/></svg></span><div class="journey-info"><strong>建立个人档案</strong><span>教育经历、技能与求职意向已整理</span></div><span class="step-state">已完成</span></div>
                <div class="journey-step is-done"><span class="journey-marker"><svg><use href="#i-check"/></svg></span><div class="journey-info"><strong>整理项目材料</strong><span>2 份材料已归档</span></div><span class="step-state">已完成</span></div>
                <div class="journey-step is-current"><span class="journey-marker">03</span><div class="journey-info"><strong>打磨目标简历</strong><span>强化项目成果与技术关键词</span></div><button class="step-action" data-page="resume">继续 <svg><use href="#i-arrow"/></svg></button></div>
                <div class="journey-step is-upcoming"><span class="journey-marker">04</span><div class="journey-info"><strong>探索合适岗位</strong><span>根据目标方向查看精选机会</span></div><span class="step-state">待开始</span></div>
              </div>
            </section>
            <section class="panel focus-panel">
              <div class="panel-heading"><div><div class="eyebrow">TODAY'S FOCUS</div><h3>今天的重点</h3></div><span class="focus-date">10.12 <span>周一</span></span></div>
              <div class="focus-card focus-card-main"><span class="focus-number">01</span><div><span class="focus-tag">建议 · 15 分钟</span><strong>补充一个项目成果</strong><p>试着用数据描述你在项目中的贡献。</p></div><button class="round-arrow" data-page="resume" aria-label="前往简历"><svg><use href="#i-arrow"/></svg></button></div>
              <div class="focus-card"><span class="focus-number">02</span><div><span class="focus-tag">岗位 · {{ matchedCount }} 个新匹配</span><strong>看看本周的岗位推荐</strong></div><button class="round-arrow" data-page="jobs" aria-label="查看岗位"><svg><use href="#i-arrow"/></svg></button></div>
              <div class="focus-footer"><span class="focus-check"><svg><use href="#i-check"/></svg></span>完成一项准备，离目标更近一点</div>
            </section>
          </div>

          <section class="panel recommended-panel">
            <div class="panel-heading"><div><div class="eyebrow">CURATED FOR YOU</div><h3>与你经历相近的岗位</h3></div><button class="text-button" data-page="jobs">查看全部岗位 <svg><use href="#i-arrow"/></svg></button></div>
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
import { getProfile } from '../utils/demoProfile'

/** 推荐位只展示 3 个，但指标卡要显示「本周匹配到多少个」，所以多取一些。 */
const DISPLAY_LIMIT = 3
const FETCH_LIMIT = 8

const recommended = ref([])
const matchedCount = ref(0)
const error = ref('')

const paddedMatched = computed(() => String(matchedCount.value).padStart(2, '0'))
const matchedFootLabel = computed(() => {
  const profile = getProfile()
  return [profile.targetCities?.[0], profile.targetTitle].filter(Boolean).join(' · ') || '最近匹配'
})
const emptyText = computed(() =>
  error.value ? '岗位推荐暂时加载不出来，请确认后端已启动。' : '正在为你匹配岗位…'
)

function hasScore(job) {
  return job.score !== null && job.score !== undefined
}

onMounted(async () => {
  try {
    // explainTopN: 0 —— 工作台要秒开，不在这里调用大模型
    const data = await searchJobs({ limit: FETCH_LIMIT, explainTopN: 0, profile: getProfile() })
    const jobs = Array.isArray(data?.jobs) ? data.jobs : []
    recommended.value = jobs.slice(0, DISPLAY_LIMIT)
    matchedCount.value = data?.total ?? jobs.length
  } catch (caught) {
    error.value = caught.message || '加载失败'
  }
})
</script>
