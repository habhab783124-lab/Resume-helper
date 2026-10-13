<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createInterviewApi } from '../api/interview.js'

const route = useRoute()
const router = useRouter()
const api = createInterviewApi({ baseUrl: import.meta.env.VITE_API_BASE_URL || '' })

const loading = ref(true)
const error = ref('')
const report = ref(null)
const sessionRole = ref('')

const sessionId = computed(() => route.query.sessionId || '')

const scoreLevel = computed(() => {
  const s = report.value?.overallScore ?? 0
  if (s >= 85) return '优秀 · 可以自信展现'
  if (s >= 75) return '良好 · 有清晰的基础'
  if (s >= 60) return '合格 · 方向对了，继续打磨'
  return '需要加强 · 把建议落到实处'
})

const scoreColor = computed(() => {
  const s = report.value?.overallScore ?? 0
  if (s >= 85) return '#7b9a5a'
  if (s >= 75) return '#8fa86b'
  if (s >= 60) return '#c4a15b'
  return '#b06a5a'
})

async function loadReport() {
  if (!sessionId.value) {
    loading.value = false
    error.value = '没有找到面试记录。请先完成一次模拟面试。'
    return
  }
  loading.value = true
  error.value = ''
  try {
    report.value = await api.report(sessionId.value)
    const session = await api.getSession(sessionId.value)
    sessionRole.value = session.targetRole || '综合模拟'
  } catch (e) {
    error.value = e.message || '报告加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(loadReport)
</script>

<template>
<section class="page active" id="page-report" data-title="面试报告">
  <div v-if="loading" class="loading-wrap">
    <p>正在生成面试报告…</p>
  </div>

  <div v-else-if="error" class="error-wrap">
    <p class="notice error" role="alert">{{ error }}</p>
    <button class="button button-primary" @click="router.push({ name: 'Interview' })">返回面试室</button>
  </div>

  <template v-else-if="report">
    <div class="page-intro">
      <div>
        <div class="eyebrow"><span class="eyebrow-line"></span>INTERVIEW NOTES / 06</div>
        <h1>一次练习，一点进步<span class="greeting-dot">。</span></h1>
        <p>把反馈变成下一次表达的起点。</p>
      </div>
      <button class="button button-outline" @click="window.print()">
        <svg><use href="#i-download"/></svg>导出练习报告
      </button>
    </div>

    <div class="report-meta">
      <span class="report-meta-mark">01</span>
      <div>
        <strong>{{ sessionRole }}</strong>
        <span>{{ report.turnFeedbacks.length }} 道回答 <i>·</i> 综合评分 <i>·</i> {{ report.aiModel === 'rule' ? '规则模式' : 'AI 点评' }}</span>
      </div>
      <span class="report-complete"><i></i>练习已完成</span>
    </div>

    <div class="report-overview">
      <section class="panel score-panel">
        <div class="panel-heading">
          <div>
            <div class="eyebrow">OVERALL IMPRESSION</div>
            <h3>综合表现</h3>
          </div>
          <span class="score-period">本次练习</span>
        </div>
        <div class="score-main">
          <div class="score-ring">
            <div>
              <strong>{{ report.overallScore }}</strong>
              <span>/ 100</span>
            </div>
          </div>
          <div class="score-summary">
            <span class="score-level">{{ scoreLevel }}</span>
            <h2>{{ report.overallSummary }}</h2>
          </div>
        </div>
      </section>

      <section class="report-side-panel">
        <div class="eyebrow">A NOTE FOR YOU</div>
        <span class="report-side-icon"><svg><use href="#i-spark"/></svg></span>
        <h3>下一步可以<br>这样练习。</h3>
        <ul class="suggestion-list">
          <li v-for="(s, i) in report.suggestions.slice(0, 3)" :key="i">{{ s }}</li>
          <li v-if="!report.suggestions?.length">继续打磨回答结构和结果量化。</li>
        </ul>
        <button class="button button-dark" @click="router.push({ name: 'Interview' })">
          再练习一次 <svg><use href="#i-arrow"/></svg>
        </button>
      </section>
    </div>

    <div class="report-detail-grid">
      <section class="panel report-detail">
        <div class="panel-heading">
          <div>
            <div class="eyebrow">WHAT WENT WELL</div>
            <h3>做得不错的地方</h3>
          </div>
          <span class="detail-symbol positive">＋</span>
        </div>
        <div v-if="report.strengths?.length" class="feedback-list">
          <div v-for="(s, i) in report.strengths" :key="i" class="feedback-item">
            <span class="feedback-number">{{ String(i + 1).padStart(2, '0') }}</span>
            <div><strong>{{ s }}</strong></div>
          </div>
        </div>
        <p v-else class="empty-hint">继续加油，你能找到属于自己的亮点。</p>
      </section>

      <section class="panel report-detail">
        <div class="panel-heading">
          <div>
            <div class="eyebrow">ROOM TO GROW</div>
            <h3>下一次可以加强</h3>
          </div>
          <span class="detail-symbol improve">↗</span>
        </div>
        <div v-if="report.gaps?.length" class="feedback-list">
          <div v-for="(g, i) in report.gaps" :key="i" class="feedback-item">
            <span class="feedback-number">{{ String(i + 1).padStart(2, '0') }}</span>
            <div><strong>{{ g }}</strong></div>
          </div>
        </div>
        <p v-else class="empty-hint">目前回答比较完整，继续保持。</p>
      </section>
    </div>

    <section class="panel question-review">
      <div class="panel-heading">
        <div>
          <div class="eyebrow">QUESTION BY QUESTION</div>
          <h3>逐题回顾</h3>
        </div>
        <span class="text-button">共 {{ report.turnFeedbacks.length }} 题</span>
      </div>
      <template v-if="report.turnFeedbacks?.length">
        <details v-for="fb in report.turnFeedbacks" :key="fb.index" class="review-detail">
          <summary class="review-row">
            <span class="review-index">{{ String(fb.index).padStart(2, '0') }}</span>
            <div>
              <strong>{{ fb.question }}</strong>
              <span>亮点：{{ fb.highlight || '—' }}</span>
            </div>
            <span class="review-score">{{ fb.score }} <small>/100</small></span>
            <span class="review-expand">⌄</span>
          </summary>
          <div class="review-expand-body">
            <div class="review-feedback-block">
              <div class="review-feedback-label highlight">✦ 回答亮点</div>
              <p>{{ fb.highlight || '继续保持稳定的表达。' }}</p>
            </div>
            <div class="review-feedback-block">
              <div class="review-feedback-label improve">↗ 改进方向</div>
              <p>{{ fb.improvement || '下次回答时可以更具体一些。' }}</p>
            </div>
          </div>
        </details>
      </template>
      <p v-else class="empty-hint">还没有回答记录。</p>
    </section>

    <footer class="page-footer">
      <span>拾阶 · CAREER STUDIO</span>
      <span>为认真准备的人，整理下一步。</span>
      <span>{{ report.aiModel === 'rule' ? '规则模式报告' : `AI: ${report.aiModel}` }}</span>
    </footer>
  </template>
</section>

<style scoped>
.loading-wrap, .error-wrap {
  padding: 80px 24px;
  text-align: center;
}
.notice.error {
  max-width: 480px;
  margin: 0 auto 24px;
  padding: 16px 20px;
  background: #fff1e9;
  color: #95452c;
  border-radius: 8px;
}
.suggestion-list {
  list-style: none;
  padding: 0;
  margin: 16px 0 20px;
}
.suggestion-list li {
  padding: 8px 0;
  font-size: 13px;
  line-height: 1.7;
  color: #4a4d44;
  border-bottom: 1px dashed #e2e4dc;
}
.suggestion-list li:last-child {
  border-bottom: none;
}
.feedback-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.empty-hint {
  color: #9a9a90;
  font-size: 13px;
  line-height: 1.7;
}
.review-detail {
  border-bottom: 1px solid #e7e8e1;
}
.review-detail summary {
  list-style: none;
  cursor: pointer;
}
.review-detail summary::-webkit-details-marker {
  display: none;
}
.review-row {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 18px 0;
}
.review-row > div {
  flex: 1;
  min-width: 0;
}
.review-row strong {
  display: block;
  font-size: 14px;
  line-height: 1.6;
  margin-bottom: 4px;
  color: #353831;
}
.review-row span {
  font-size: 12px;
  color: #79766e;
}
.review-score {
  font-size: 20px;
  font-weight: 700;
  color: #4a5f3a;
  white-space: nowrap;
  text-align: right;
}
.review-score small {
  font-size: 12px;
  font-weight: 500;
  color: #9a9a90;
}
.review-expand {
  color: #9a9a90;
  font-size: 14px;
}
.review-expand-body {
  padding: 0 0 20px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.review-feedback-block {
  padding: 12px 16px;
  background: #f7f7f2;
  border-radius: 6px;
}
.review-feedback-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 6px;
}
.review-feedback-label.highlight {
  color: #7b9a5a;
}
.review-feedback-label.improve {
  color: #c48a3a;
}
.review-feedback-block p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: #353831;
}
.score-ring strong {
  color: v-bind(scoreColor);
}
@media print {
  .report-side-panel, .page-footer button, .text-button {
    display: none;
  }
}
</style>