import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildReportMarkdown } from './reportExport.js'

function baseReport(overrides = {}) {
  return {
    reportStatus: 'COMPLETE',
    overallScore: 82, overallSummary: '整体表现良好。',
    strengths: ['结构清晰', '举例到位'], gaps: ['深度不足'], suggestions: ['多练复杂度'],
    turnFeedbacks: [], aiModel: 'demo-model', scoringVersion: 'v1',
    plannedCount: 13, answeredCount: 13, scoredCount: 13,
    ...overrides,
  }
}

function scoredFeedback(overrides = {}) {
  return {
    index: 1, questionId: 'q1', question: '讲讲你的项目。', answer: '我做过一个系统。',
    status: 'SCORED', score: 80, competency: '表达', importance: 'CORE', difficulty: 'BASIC',
    weightUnits: 100, weightPercent: 10, weightedContribution: 8, weightReason: '核心能力',
    dimensionScores: { CORRECTNESS: 15, COVERAGE: 20, REASONING: 15, EVIDENCE: 10, CLARITY: 20 },
    evidence: ['"我做过一个系统"'], missingPoints: ['缺少量化结果'],
    highlight: '结构清楚', improvement: '补充数据', suggestedOutline: '背景-行动-结果',
    kind: 'MAIN', mainQuestionIndex: 1, algorithm: null, followUps: [],
    ...overrides,
  }
}

test('COMPLETE 报告：含综合分、五维、追问、算法题链接', () => {
  const fb = scoredFeedback({
    followUps: [{ questionId: 'q2', question: '追问：复杂度？', answer: 'O(n)' }],
  })
  const algo = scoredFeedback({
    index: 13, questionId: 'q3', question: '两数之和。', answer: '哈希表。',
    kind: 'ALGORITHM', mainQuestionIndex: 0,
    algorithm: { id: 'two-sum', title: '两数之和', url: 'https://leetcode.cn/problems/two-sum/', difficulty: 'Easy' },
  })
  const md = buildReportMarkdown(
    baseReport({ turnFeedbacks: [fb, algo] }),
    { role: '后端工程师', session: { turns: [] } },
  )
  assert.match(md, /# 面试报告 · 后端工程师/)
  assert.match(md, /状态：评分已完成/)
  assert.match(md, /已答 13\/13 题/)
  assert.match(md, /评分模型：demo-model/)
  assert.match(md, /82 \/ 100/)
  assert.match(md, /良好 · 有清晰的基础/)
  assert.match(md, /整体表现良好。/)
  assert.match(md, /讲讲你的项目。/)
  assert.match(md, /状态：已评分  ·  得分：80 \/ 100  ·  加权贡献：8 分/)
  assert.match(md, /权重：10%  ·  权重依据：核心能力  ·  相对权重点数：100/)
  assert.match(md, /切题与正确性 15 \/ 20  ·  关键要点覆盖 20 \/ 20/)
  assert.match(md, /✦ 回答亮点.*结构清楚/)
  assert.match(md, /↗ 改进动作.*补充数据/)
  assert.match(md, /追问与补充回答/)
  assert.match(md, /追问 1：追问：复杂度？/)
  assert.match(md, /Hot 100 原题/)
  assert.match(md, /两数之和/)
  assert.match(md, /leetcode\.cn\/problems\/two-sum/)
})

test('PARTIAL 报告：有 PENDING 题，无综合分', () => {
  const scored = scoredFeedback()
  const pending = scoredFeedback({
    index: 2, questionId: 'q2', status: 'PENDING', score: null,
    dimensionScores: {}, evidence: null, missingPoints: null,
    highlight: null, improvement: null, suggestedOutline: null,
  })
  const r = baseReport({
    reportStatus: 'PARTIAL', overallScore: null, answeredCount: 13, plannedCount: 13, scoredCount: 1,
    turnFeedbacks: [scored, pending],
  })
  const md = buildReportMarkdown(r, { role: '', session: { turns: [] } })
  assert.match(md, /状态：待评分/)
  assert.doesNotMatch(md, /综合训练参考分/)
  // PENDING 题不应出现得分行
  const pendingBlock = md.split('### 02').pop()
  assert.doesNotMatch(pendingBlock, /得分：/)
  assert.match(pendingBlock, /本题待评分/)
})

test('EMPTY 报告：无评分内容，不崩', () => {
  const r = {
    reportStatus: 'EMPTY', overallScore: null, overallSummary: null,
    strengths: [], gaps: [], suggestions: [], turnFeedbacks: [],
    aiModel: null, scoringVersion: null, plannedCount: 13, answeredCount: 0, scoredCount: 0,
  }
  const md = buildReportMarkdown(r, { role: '前端', session: null })
  assert.match(md, /# 面试报告 · 前端/)
  assert.match(md, /状态：暂无评分/)
  assert.match(md, /已答 0\/13 题/)
  assert.doesNotMatch(md, /综合训练参考分/)
  assert.doesNotMatch(md, /逐题反馈/)
})

test('question/answer 缺失时从 session.turns 回填', () => {
  const fb = scoredFeedback({ question: undefined, answer: undefined })
  const md = buildReportMarkdown(
    baseReport({ turnFeedbacks: [fb] }),
    { role: 'x', session: { turns: [{ questionId: 'q1', question: '回填题目', answer: '回填回答' }] } },
  )
  assert.match(md, /回填题目/)
  assert.match(md, /回填回答/)
})
