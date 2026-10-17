/**
 * Local Chrome acceptance: native MediaRecorder + fake device, simulated HTTP APIs.
 * No real microphone, credentials, model or speech provider is used.
 * Node 24+, Chrome, ffmpeg required. Run: node tools/check-interview.mjs
 * Optional: CHROME_PATH, FFMPEG_PATH, CHECK_ARTIFACT_DIR (default temp directory).
 * Own random ports/profile and all subprocesses are cleaned even after failure.
 */
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdtemp, readFile, writeFile, mkdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { inflateSync } from 'node:zlib'

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const work = await mkdtemp(join(tmpdir(), 'interview-browser-'))
const artifacts = process.env.CHECK_ARTIFACT_DIR || join(work, 'artifacts')
await mkdir(artifacts, { recursive: true })
const chromePath = process.env.CHROME_PATH || (process.platform === 'darwin'
  ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome')
const children = [], sockets = new Set(), checks = [], calls = [], errors = [], dialogs = [], browserRequests = []
// The test servers must not inherit deployment credentials or API overrides.
const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(AI_|VOLC_|VITE_|OPENAI_)/.test(key)))
let ws, server, send, evaluate, snapshot, mainFrameNavigations = 0, acceptNextDialog = false, dialogHandling = Promise.resolve()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
let caps = { asrEnabled: true, ttsEnabled: true, maxRecordingSeconds: 120, maxAudioBytes: 5242880 }
let asrDelay = 60, asrText = '我使用 Redis 缓存热点数据，并用 Spring Boot 完成接口。'
let failReport = 0, failAnswer = false, failSave = false, loseSaveResponse = false, failFinishResponse = false, sessionCounter = 0
const sessions = new Map(), plans = new Map(), focusedPlans = new Map(), cachedReports = new Map(), reportStates = new Map(), reportGenerations = new Map(), generationBehaviors = new Map()
const algorithm = { id: '1', title: '两数之和', url: 'https://leetcode.cn/problems/two-sum/', difficulty: 'EASY' }
const rootTurns = value => value.turns.filter(turn => value.questionMetadata[turn.questionId].kind !== 'FOLLOW_UP')
function metadata(index, kind = index === 13 ? 'ALGORITHM' : 'MAIN', parentQuestionId) {
  return { kind, mainQuestionIndex: index, parentQuestionId, ...(kind === 'ALGORITHM' ? { algorithm } : {}) }
}
function weightedReport(value, state = {}) {
  const roots = rootTurns(value), answered = roots.filter(turn => turn.answer != null)
  const mains = roots.filter(turn => value.questionMetadata[turn.questionId].kind === 'MAIN')
  const weights = mains.map((_turn, i) => [8, 12, 12][i % 3]), total = weights.reduce((sum, weight) => sum + weight, 0)
  const basis = weights.map(weight => Math.floor(weight * 8000 / total))
  const order = weights.map((weight, i) => ({ i, remainder: weight * 8000 % total })).sort((a, b) => b.remainder - a.remainder || a.i - b.i)
  for (let i = 0, left = weights.length ? 8000 - basis.reduce((sum, n) => sum + n, 0) : 0; i < left; i++) basis[order[i].i]++
  const turnFeedbacks = roots.map((turn, index) => {
    const info = value.questionMetadata[turn.questionId], isAlgorithm = info.kind === 'ALGORITHM', isSpecialty = index === 12
    const status = turn.answer == null ? 'UNANSWERED' : state.missing?.has(turn.questionId) ? 'PENDING' : 'SCORED'
    const score = status === 'SCORED' ? state.zero ? 0 : isSpecialty ? 90 : [80, 90, 70][index % 3] : null
    const share = isSpecialty ? 20 : basis[index] / 100
    let remaining = score
    const dimensionScores = status === 'SCORED' ? Object.fromEntries(['CORRECTNESS', 'COVERAGE', 'REASONING', 'EVIDENCE', 'CLARITY'].map(key => { const points = Math.min(20, remaining); remaining -= points; return [key, points] })) : {}
    return { ...turn, ...info, followUps: value.turns.filter(follow => value.questionMetadata[follow.questionId].parentQuestionId === turn.questionId),
      index: index + 1, score, status, competency: isSpecialty ? value.specialty.focus : '专业实践与结果验证', difficulty: index === 1 ? 'ADVANCED' : 'BASIC', importance: index === 2 ? 'CORE' : 'RELATED',
      weightUnits: isSpecialty ? 0 : weights[index], weightPercent: share,
      weightedContribution: score == null ? null : Number((score * share / 100).toFixed(4)), weightReason: isSpecialty ? '岗位专项题固定占20%' : '岗位核心能力与真实项目验证', dimensionScores,
      evidence: status === 'SCORED' ? [turn.answer] : [], missingPoints: status === 'SCORED' ? ['未说明统计周期和样本范围'] : [],
      highlight: status === 'SCORED' ? '说明了实际采用的验证方法' : null, improvement: status === 'SCORED' ? '补充真实统计周期' : null,
      suggestedOutline: status === 'SCORED' ? '指标口径、测量方法、待补充真实样本范围' : null, aiModel: status === 'SCORED' ? 'local-model' : null }
  })
  const scored = turnFeedbacks.filter(turn => turn.status === 'SCORED')
  const reportStatus = !answered.length ? 'EMPTY' : scored.length === answered.length ? 'COMPLETE' : scored.length ? 'PARTIAL' : 'UNAVAILABLE'
  return { overallScore: reportStatus === 'COMPLETE' && answered.length === 13 ? Number(scored.reduce((sum, turn) => sum + turn.weightedContribution, 0).toFixed(1)) : null,
    overallSummary: '本地模拟报告：12 道主问题占80%，专项题固定20%；仅在13题全部完成后显示综合分。', strengths: scored.length ? ['说明实际验证方法'] : [], gaps: scored.length ? ['未说明统计周期'] : [], suggestions: scored.length ? ['补充真实统计周期'] : [],
    turnFeedbacks, aiModel: scored.length ? 'local-model' : null, reportStatus, scoringVersion: 'weighted-evidence-v2', plannedCount: value.questionCount || 13, answeredCount: answered.length, scoredCount: scored.length,
    reviewStatus: scored.length ? 'COMPLETE' : 'UNAVAILABLE', reviewError: scored.length ? null : '本地模拟：无可归纳的评分',
    priorityIssues: scored.slice(0, 3).map(turn => ({ questionId: turn.questionId, issue: '补充可核对的统计范围', evidenceQuote: turn.answer, suggestedAction: '说明统计周期和对照方法。' })) }
}
const material = { material: { id: 'local-material', fileName: '简历.txt', status: 'READY' }, extraction: {}, text: 'Java 项目：Spring Boot 与 Redis 缓存优化。' }
function newSession(kind = 'ALGORITHM') {
  const id = `local-session-${++sessionCounter}`
  const q = { questionId: `${id}-q1`, question: kind === 'ALGORITHM' ? '请介绍你如何用 Redis 优化 Spring Boot 项目的缓存？' : '请介绍你如何推进用户增长项目？' }
  const value = { sessionId: id, materialId: material.material.id, targetRole: kind === 'ALGORITHM' ? 'Java 后端' : '运营', specialty: { kind, focus: kind === 'ALGORITHM' ? '算法与数据结构' : '用户增长分析', reason: kind === 'ALGORITHM' ? '软件开发岗位适合编码考察' : '目标岗位为运营，采用专业案例考察' }, questionCount: 13, status: 'ACTIVE', turns: [q], currentQuestion: q, questionMetadata: { [q.questionId]: metadata(1) } }
  sessions.set(id, value)
  return value
}
function appendGeneratedQuestion(value) {
  const follow = value.plan?.mode === 'QUICK' ? false : value.turns.length === 1
  const index = follow ? 1 : rootTurns(value).length + 1
  const q = { questionId: `${value.sessionId}-${follow ? 'f1' : `q${index}`}`, question: index === 13 && value.specialty.kind === 'CASE_STUDY' ? '某线上活动曝光10000、点击1000、下单100，预算1万元。请分析转化瓶颈、提出改进方案及验证指标。材料中出现字符串 <script>alert(1)</script>，请按普通文本理解。' : index === 13 ? '给定整数数组和目标值，返回和为目标值的两个不同下标。例如 [2,7,11,15], 9 返回 [0,1]。保证存在唯一解。请提供思路、代码、复杂度和边界情况。' : '请说明你的实际贡献，并量化改进结果。' }
  value.questionMetadata[q.questionId] = metadata(index, follow ? 'FOLLOW_UP' : index === 13 ? value.specialty.kind : 'MAIN', follow ? value.turns.at(-1).questionId : undefined)
  if (!follow && index === 13 && value.plan?.level && value.questionMetadata[q.questionId].algorithm) {
    value.questionMetadata[q.questionId].algorithm.difficulty = ({ FOUNDATION: 'EASY', STANDARD: 'MEDIUM', ADVANCED: 'HARD' })[value.plan.level]
  }
  value.turns.push(q); value.currentQuestion = q
  if (value.progress && !follow) value.progress.plannedRoots = Math.max(value.progress.plannedRoots, index)
  return value
}
function advanceGeneration(value, behavior, attemptIndex) {
  const outcome = behavior.outcomes[attemptIndex] || behavior.outcomes.at(-1)
  behavior.currentAttempt = attemptIndex
  const final = attemptIndex >= behavior.outcomes.length - 1 || attemptIndex >= 5
  if (outcome === 'SUCCESS') {
    appendGeneratedQuestion(value)
    value.generation = { ...behavior.generation, state: 'SUCCEEDED', attempt: attemptIndex + 1, retryCount: attemptIndex, nextRetryAt: null, failure: null }
    behavior.terminal = true
    return value
  }
  const config = outcome === 'CONFIG'
  const budgetExpired = behavior.budgetMs != null && Date.now() - behavior.startedAt >= behavior.budgetMs
  const failed = config || final || budgetExpired
  value.generation = { ...behavior.generation, state: failed ? 'FAILED' : 'RETRY_WAIT', attempt: attemptIndex + 1,
    retryCount: attemptIndex, nextRetryAt: failed ? null : new Date(Date.now() + 100).toISOString(),
    failure: { code: config ? 'CONFIG' : budgetExpired ? 'TIMEOUT' : 'NETWORK', retryable: !config, message: config ? '模型配置不可用' : budgetExpired ? '已达到本次生成时间预算' : '临时网络失败' } }
  behavior.terminal = failed
  return value
}
const json = (res, data, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(status === 200 ? { success: true, data } : { success: false, message: data }))
}
function child(command, args, options = {}) {
  const proc = spawn(command, args, { env, stdio: ['ignore', 'pipe', 'pipe'], ...options })
  children.push(proc)
  let output = ''
  proc.stdout.on('data', data => { output = (output + data).slice(-8000) })
  proc.stderr.on('data', data => { output = (output + data).slice(-8000) })
  proc.on('error', cause => { output += cause.message })
  proc.output = () => output
  return proc
}
async function run(command, args) {
  const proc = child(command, args)
  await new Promise((resolve, reject) => {
    proc.once('error', reject)
    proc.once('exit', code => code === 0 ? resolve() : reject(new Error(`${command}: ${code} ${proc.output()}`)))
  })
}
async function waitFor(fn, description, timeout = 20000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { if (await fn()) return; await sleep(80) }
  throw new Error(`Timeout: ${description}`)
}
async function check(description, fn) {
  await fn()
  checks.push(description)
  console.log(`PASS ${description}`)
}
async function freePort() {
  const probe = createServer()
  await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve))
  const port = probe.address().port
  await new Promise(resolve => probe.close(resolve))
  return port
}
async function main() {
  const audioFile = join(work, 'question.mp3')
  await run(process.env.FFMPEG_PATH || 'ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=5', '-c:a', 'libmp3lame', audioFile])
  const audio = await readFile(audioFile)
  server = createServer(async (req, res) => {
    try {
      res.setHeader('Access-Control-Allow-Origin', req.headers.origin || '*')
      res.setHeader('Access-Control-Allow-Credentials', 'true')
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-User-Id, X-AI-Api-Key, X-AI-Model, X-AI-Base-Url, X-Speech-Api-Key')
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS')
      if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return }
      const chunks = []
      for await (const chunk of req) chunks.push(chunk)
      const buffer = Buffer.concat(chunks), path = req.url.split('?')[0]
      const call = { method: req.method, path, headers: req.headers, bytes: buffer.length }
      calls.push(call)
      if (path === '/api/interview-speech/capabilities') return json(res, caps)
      if (path === '/api/interviews/plans' && req.method === 'POST') {
        call.json = JSON.parse(buffer)
        const id = `local-plan-${plans.size + 1}`, input = call.json
        const plan = { planId: id, planVersion: 1, status: 'READY', expiresAt: new Date(Date.now() + 600000).toISOString(),
          plan: { mode: input.mode, level: input.level, mainCount: input.mainCount, followUpBudget: input.mode === 'QUICK' ? 2 : 12,
            specialty: { kind: input.specialtyPreference === 'CASE_STUDY' ? 'CASE_STUDY' : 'ALGORITHM', label: '岗位专项', reason: '按岗位要求安排' }, coverage: [{ id: 'project-impact', label: '项目成果' }] } }
        plans.set(id, input); return json(res, plan)
      }
      const planMatch = path.match(/^\/api\/interviews\/plans\/([^/]+)$/)
      if (planMatch && req.method === 'GET') { const focused = focusedPlans.get(planMatch[1]); if (focused) return json(res, focused); const input = plans.get(planMatch[1]); if (!input) return json(res, '计划不存在', 404); return json(res, { planId: planMatch[1], planVersion: 1, status: 'READY', plan: { mode: input.mode, level: input.level, mainCount: input.mainCount, followUpBudget: input.mode === 'QUICK' ? 2 : 12, specialty: { kind: 'ALGORITHM', label: '岗位专项', reason: '按岗位要求安排' }, coverage: [{ id: 'project-impact', label: '项目成果' }] } }) }
      if (path === '/api/interviews/source/preview' && req.method === 'POST') {
        call.json = JSON.parse(buffer)
        const source = call.json.source
        const resumeText = source.kind === 'RESUME_VERSION' ? '版本简历\n经历\n- 确认过的成果' : material.text
        return json(res, { source, sourceLabel: source.kind === 'RESUME_VERSION' ? '已保存版本' : material.material.fileName,
          resumeText, sourceFingerprint: `${source.kind}:${source.id}:${resumeText.length}` })
      }
      if (path === '/api/jobs/sources') return json(res, [])
      if (path === '/api/jobs/search' && req.method === 'POST') return json(res, { jobs: [{ id: 'job-1', title: '数据分析师', company: '本地公司', mark: '本', style: '', salary: '20K', place: '上海', experience: '应届', tags: [], score: 80 }], notes: [], aiUsed: false })
      if (path === '/api/jobs/job-1') return json(res, { id: 'job-1', title: '数据分析师', description: '完整岗位描述：需要负责数据分析、指标设计、实验评估与跨团队协作。'.repeat(8) })
      if (path === '/api/resume' && req.method === 'GET') return json(res, { draft: { headline: '求职者', summary: '', contacts: [], educations: [], experiences: [], projects: [], skills: [], keywords: [] }, empty: false, message: '' })
      if (path === '/api/resume/versions' && req.method === 'GET') return json(res, [{ id: 'version-1', name: '后端版', targetTitle: 'Java 后端', summaryText: '教育 1 · 经历 1', createdAt: '2026-10-17', hasJd: false }])
      if (path === '/api/resume/versions/version-1' && req.method === 'GET') return json(res, { version: { id: 'version-1', name: '后端版' }, draft: { headline: 'Java 后端', summary: '', contacts: [], educations: [], experiences: [], projects: [], skills: [], keywords: [] } })
      if (path === '/api/interview-speech/check') return json(res, { audioBytes: audio.length, speaker: 'local-speaker' })
      if (path === '/api/ai/check') return json(res, { model: 'local-model' })
      if (path === '/api/materials' && req.method === 'GET') return json(res, [material.material])
      if (path === '/api/materials' && req.method === 'POST') return json(res, material)
      if (path === '/api/materials/text' && req.method === 'POST') return json(res, material)
      if (path === '/api/interviews' && req.method === 'GET') {
        const page = [...sessions.values()].map(value => ({ id: value.sessionId, role: value.targetRole || 'Java 后端',
          mode: value.plan?.mode || 'FULL', status: value.status, createdAt: value.createdAt || '2026-10-17T08:00:00Z',
          expiresAt: value.expiresAt || '2026-11-16T08:00:00Z', progress: `${value.progress?.answeredRoots || 0}/${value.progress?.plannedRoots || 13}`,
          reportStatus: cachedReports.has(value.sessionId) ? 'READY' : 'NOT_READY' })).reverse()
        return json(res, { items: page, nextCursor: null })
      }
      if (/^\/api\/materials\/[^/]+$/.test(path) && req.method === 'GET') return json(res, material)
      if (path === '/api/interviews' && req.method === 'POST') {
        call.json = JSON.parse(buffer)
        if (call.json.planId) {
          const input = plans.get(call.json.planId)
          if (!input) return json(res, '计划不存在', 404)
          const value = newSession(input.specialtyPreference === 'CASE_STUDY' ? 'CASE_STUDY' : 'ALGORITHM')
          value.targetRole = input.targetRole || 'Java 后端'; value.questionCount = input.mode === 'QUICK' ? input.mainCount + (input.specialtyPreference === 'NONE' ? 0 : 1) : 13
          value.plan = { mode: input.mode, level: input.level, mainCount: input.mainCount, followUpBudget: input.mode === 'QUICK' ? 2 : 12, specialty: input.specialtyPreference === 'NONE' ? null : value.specialty, coverage: [{ id: 'project-impact', label: '项目成果' }], practiceTargets: input.practiceTargets || [], sourceSessionId: input.sourceSessionId || '' }
          value.progress = { plannedRoots: input.mainCount, resolvedRoots: 0, answeredRoots: 0, skippedRoots: 0, followUpsUsed: 0, followUpBudget: value.plan.followUpBudget }
          return json(res, value)
        }
        return json(res, newSession(call.json.targetRole?.includes('运营') ? 'CASE_STUDY' : 'ALGORITHM'))
      }
      const match = path.match(/^\/api\/interviews\/([^/]+)(.*)$/)
      const value = match && sessions.get(match[1])
      if (match && match[2] === '' && req.method === 'DELETE') {
        sessions.delete(match[1]); return json(res, { deleted: true })
      }
      if (!value) return json(res, '本地模拟接口不存在', 404)
      const suffix = match[2]
      if (suffix === '/practice-plans' && req.method === 'POST') {
        call.json = JSON.parse(buffer)
        const input = call.json, planId = `focused-${focusedPlans.size + 1}`
        const source = { kind: 'MATERIAL', id: material.material.id }
        const practiceTargets = input.issueIds.map((issueId, index) => ({ issueId, title: `报告重点 ${index + 1}`, action: '补充可验证的结果', sourceQuestionIds: [issueId] }))
        const focused = { planId, version: 1, status: 'READY', expiresAt: new Date(Date.now() + 900000).toISOString(),
          plan: { mode: 'QUICK', mainCount: 3, plannedCount: 3, followUpBudget: 2, level: 'STANDARD', careerStage: 'CAMPUS',
            targetRole: value.targetRole, jobDescription: '', specialty: { kind: 'NONE', label: '不附加专项题' },
            source, sourceLabel: material.material.fileName, sourceFingerprint: `MATERIAL:${source.id}:${material.text.length}`,
            practiceTargets, sourceSessionId: value.sessionId, coverage: [{ id: 'evidence', label: '结果证据' }] } }
        focusedPlans.set(planId, focused)
        plans.set(planId, { mode: 'QUICK', level: 'STANDARD', mainCount: 3, specialtyPreference: 'NONE', targetRole: value.targetRole,
          practiceTargets, sourceSessionId: value.sessionId, plan: focused.plan })
        return json(res, focused)
      }
      if (suffix === '/attempts' && req.method === 'GET') {
        const questionId = new URL(req.url, 'http://localhost').searchParams.get('questionId')
        return json(res, (value.attempts || []).filter(item => item.questionId === questionId))
      }
      if (suffix === '/attempts' && req.method === 'POST') {
        call.json = JSON.parse(buffer)
        const input = call.json
        value.attempts ||= []
        let attempt = value.attempts.find(item => item.requestId === input.requestId)
        if (!attempt) {
          attempt = { attemptId: `attempt-${value.attempts.length + 1}`, requestId: input.requestId, questionId: input.questionId,
            version: value.attempts.filter(item => item.questionId === input.questionId).length + 1, answer: input.answer, status: 'COMPLETE',
            feedback: { highlight: '重答补充了测量方式。', improvement: '继续报告结果。', suggestedOutline: '背景、行动、结果', evidence: [input.answer], missingPoints: ['报告结果'] },
            comparison: { scoreChange: null, fixedPoints: ['补充测量方式'], remainingPoints: ['报告结果'], newIssues: [], trainingReference: true,
              comparisonNote: '仅作训练参考。' } }
          value.attempts.push(attempt)
        }
        return json(res, attempt)
      }
      if (suffix === '') {
        const behavior = generationBehaviors.get(value.sessionId)
        if (behavior && !behavior.holdRetry && !behavior.terminal && value.generation?.state === 'RETRY_WAIT'
            && Date.parse(value.generation.nextRetryAt) <= Date.now())
          advanceGeneration(value, behavior, behavior.currentAttempt + 1)
        return json(res, value)
      }
      if (suffix === '/transcriptions') {
        const form = await new Response(buffer, { headers: { 'Content-Type': req.headers['content-type'] } }).formData()
        const file = form.get('file')
        call.upload = { type: file.type, name: file.name, size: file.size, questionId: form.get('questionId'), requestId: form.get('requestId') }
        const response = { sessionId: value.sessionId, questionId: form.get('questionId'), requestId: form.get('requestId'), text: asrText }
        await sleep(asrDelay)
        call.responded = true
        if (!res.destroyed) json(res, response)
        return
      }
      if (/^\/questions\/[^/]+\/speech$/.test(suffix)) {
        res.writeHead(200, { 'Content-Type': 'audio/mpeg' }); res.end(audio); return
      }
      if (suffix === '/turns' || suffix === '/answers') {
        const input = JSON.parse(buffer)
        call.answer = input
        const savedTurn = value.turns.find(turn => turn.questionId === input.questionId)
        value.submissionIds ||= {}
        if (savedTurn?.answer === input.answer && value.submissionIds[input.questionId] === input.submissionId)
          return json(res, value)
        assert.equal(input.questionId, value.currentQuestion.questionId)
        if (failSave) return json(res, '本地模拟：回答保存失败', 502)

        savedTurn.answer = input.answer
        if (value.progress && value.questionMetadata[input.questionId].kind !== 'FOLLOW_UP') { value.progress.answeredRoots++; value.progress.resolvedRoots++ }
        value.submissionIds[input.questionId] = input.submissionId
        delete value.pendingAnswer
        delete value.currentQuestion
        if (['ALGORITHM', 'CASE_STUDY'].includes(value.questionMetadata[input.questionId].kind)) value.status = 'FINISHED'
        if (loseSaveResponse) { loseSaveResponse = false; res.destroy(); return }
        return json(res, value)
      }
      if (suffix === '/generations') {
        const input = JSON.parse(buffer)
        if (value.plan?.mode === 'QUICK' && rootTurns(value).filter(turn => value.questionMetadata[turn.questionId].kind === 'MAIN').length >= value.plan.mainCount) {
          value.status = 'FINISHED'; delete value.currentQuestion; return json(res, value)
        }
        if (failAnswer) return json(res, '本地模拟：下一题生成失败，回答已保存', 502)
        const behavior = generationBehaviors.get(value.sessionId)
        if (behavior) {
          behavior.startedAt = Date.now()
          behavior.generation = { generationId: `browser-${value.sessionId}`, requestId: input.requestId, kind: 'NEXT', stage: 'NEXT_DECISION',
            state: 'RUNNING', attempt: 0, retryCount: 0, maxRetries: 5, startedAt: new Date().toISOString(), deadlineAt: new Date(Date.now() + 120000).toISOString() }
          advanceGeneration(value, behavior, 0)
          return json(res, value)
        }
        appendGeneratedQuestion(value)
        return json(res, value)
      }
      if (suffix === '/skip') {
        const input = JSON.parse(buffer)
        const turn = value.turns.find(item => item.questionId === input.questionId)
        assert.ok(turn && turn.answer == null, 'only unanswered question groups can be skipped')
        value.closedRootIds ||= []
        if (!value.closedRootIds.includes(input.questionId)) {
          value.closedRootIds.push(input.questionId)
          if (value.progress && value.questionMetadata[input.questionId].kind !== 'FOLLOW_UP') {
            value.progress.skippedRoots++; value.progress.resolvedRoots++
          }
        }
        delete value.currentQuestion
        value.revision = (value.revision || 0) + 1
        value.stateVersion = (value.stateVersion || 0) + 1
        return json(res, value)
      }
      if (suffix === '/feedback') {
        assert.equal(req.method, 'POST')
        const input = JSON.parse(buffer)
        assert.equal(value.plan?.mode, 'QUICK', 'teaching feedback is unavailable in an active FULL session')
        const metadata = value.questionMetadata[input.questionId]
        const rootId = metadata?.kind === 'FOLLOW_UP' ? metadata.parentQuestionId : input.questionId
        const root = value.turns.find(turn => turn.questionId === rootId)
        assert.ok(root?.answer, 'feedback must use a saved root answer')
        value.closedRootIds ||= []
        if (!value.closedRootIds.includes(rootId)) value.closedRootIds.push(rootId)
        value.practiceFeedbacks ||= {}
        value.practiceFeedbacks[rootId] = { requestId: input.requestId, status: 'COMPLETE', error: '', assessment: {
          questionId: rootId, dimensionScores: { CORRECTNESS: 10, COVERAGE: 10, REASONING: 10, EVIDENCE: 10, CLARITY: 10 },
          evidence: [root.answer], missingPoints: ['补充真实统计周期'], highlight: '回答里说明了验证方法。',
          improvement: '补充真实统计周期和对照范围。', suggestedOutline: '目标、对照、测量周期、结果', aiModel: 'local-model' } }
        value.revision = (value.revision || 0) + 1
        value.stateVersion = (value.stateVersion || 0) + 1
        return json(res, value)
      }
      if (suffix === '/clarifications') {
        const input = JSON.parse(buffer)
        assert.equal(input.questionId, value.currentQuestion?.questionId)
        const metadata = value.questionMetadata[input.questionId]
        const rootId = metadata.kind === 'FOLLOW_UP' ? metadata.parentQuestionId : input.questionId
        value.clarifications ||= {}
        value.clarifications[rootId] = { requestId: input.requestId, status: 'COMPLETE',
          text: '这道题要求你说明验证步骤，不提供示例答案。', error: null }
        return json(res, value)
      }
      if (suffix === '/replacements') {
        const input = JSON.parse(buffer)
        const original = value.turns.find(item => item.questionId === input.questionId)
        assert.ok(original && original.answer == null, 'replacement source must be unanswered')
        assert.ok(input.reason.length >= 1 && input.reason.length <= 300)
        value.replacements ||= {}
        assert.equal(value.replacements[input.questionId], undefined, 'one replacement per source question')
        const info = value.questionMetadata[input.questionId]
        const replacement = { questionId: `${input.questionId}-replacement`, question: '请说明你亲自负责的另一项工作及验证结果。' }
        value.withdrawnQuestionIds ||= []
        value.withdrawnQuestionIds.push(input.questionId)
        value.replacements[input.questionId] = { requestId: input.requestId, reason: input.reason, status: 'COMPLETE', replacementQuestionId: replacement.questionId, error: null }
        value.turns.push(replacement)
        value.questionMetadata[replacement.questionId] = { ...info }
        value.currentQuestion = replacement
        value.revision = (value.revision || 0) + 1
        value.stateVersion = (value.stateVersion || 0) + 1
        return json(res, value)
      }
      if (suffix === '/finish') {
        const input = buffer.length ? JSON.parse(buffer) : {}
        if (input.submission) {
          const turn = value.turns.find(item => item.questionId === input.submission.questionId)
          if (turn) turn.answer = input.submission.answer
        }
        if (value.pendingAnswer != null) value.turns.at(-1).answer = value.pendingAnswer
        delete value.pendingAnswer; delete value.currentQuestion; value.status = 'FINISHED'
        if (value.generation && ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(value.generation.state)) {
          value.generation = { ...value.generation, state: 'CANCELLED', nextRetryAt: null,
            failure: { code: 'INTERRUPTED', retryable: false, message: '面试已结束' } }
          const behavior = generationBehaviors.get(value.sessionId)
          if (behavior) behavior.terminal = true
        }
        if (failFinishResponse) { failFinishResponse = false; res.destroy(); return }
        return json(res, value)
      }
      if (suffix === '/report' || suffix === '/report/retry') {
        if (failReport-- > 0) return json(res, '本地模拟：报告暂时不可用', 503)
        const state = reportStates.get(value.sessionId) || {}
        if (suffix === '/report/retry') {
          assert.equal(req.method, 'POST')
          call.retryQuestions = [...(state.missing || [])]
          await sleep(400)
          if (state.retryFails) { state.retryFails--; return json(res, '本地模拟：评分重试失败，原报告已保留', 503) }
          state.missing?.clear()
        } else assert.equal(req.method, 'GET')
        if (!cachedReports.has(value.sessionId) || suffix === '/report/retry' && call.retryQuestions.length) {
          reportGenerations.set(value.sessionId, (reportGenerations.get(value.sessionId) || 0) + 1)
          cachedReports.set(value.sessionId, weightedReport(value, state))
        }
        return json(res, cachedReports.get(value.sessionId))
      }
      json(res, '本地模拟接口不存在', 404)
    } catch (cause) { errors.push(cause.message); if (!res.headersSent) json(res, cause.message, 500) }
  })
  server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)) })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const mockPort = server.address().port, vitePort = await freePort(), cdpPort = await freePort()
  const base = `http://127.0.0.1:${vitePort}`
  const vite = child(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(vitePort), '--strictPort'], { cwd: frontend, env: { ...env, VITE_PROXY_TARGET: `http://127.0.0.1:${mockPort}` } })
  await waitFor(async () => { try { return (await fetch(base)).ok } catch { if (vite.exitCode != null) throw new Error(vite.output()); return false } }, 'Vite')
  const chrome = child(chromePath, ['--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required', `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${join(work, 'profile')}`, 'about:blank'])
  let target
  await waitFor(async () => { try { target = (await fetch(`http://127.0.0.1:${cdpPort}/json/list`).then(r => r.json())).find(t => t.type === 'page'); return !!target } catch { if (chrome.exitCode != null) throw new Error(chrome.output()); return false } }, 'Chrome CDP')
  ws = new WebSocket(target.webSocketDebuggerUrl)
  const pending = new Map(); let nextId = 0
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data)
    if (message.id) { pending.get(message.id)?.(message); pending.delete(message.id) }
    if (message.method === 'Page.frameNavigated' && !message.params.frame.parentId) mainFrameNavigations += 1
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text)
    if (message.method === 'Network.requestWillBeSent' && message.params.request.url.includes('/api/interviews/'))
      browserRequests.push({ method: message.params.request.method, url: message.params.request.url })
    if (message.method === 'Network.loadingFailed') browserRequests.push({ failed: message.params.errorText, requestId: message.params.requestId })
    if (message.method === 'Page.javascriptDialogOpening') {
      dialogs.push({ type: message.params.type, message: message.params.message })
      if (acceptNextDialog) {
        acceptNextDialog = false
        dialogHandling = send('Page.handleJavaScriptDialog', { accept: true }).catch(error => { errors.push(error.message); throw error })
      }
    }
  })
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }) })
  send = async (method, params = {}) => {
    const reply = await new Promise(resolve => { const id = ++nextId; pending.set(id, resolve); ws.send(JSON.stringify({ id, method, params })) })
    if (reply.error) throw new Error(`${method}: ${reply.error.message}`)
    return reply.result
  }
  evaluate = async expression => {
    const reply = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture: true })
    if (reply.exceptionDetails) throw new Error(reply.exceptionDetails.exception?.description || reply.exceptionDetails.text)
    return reply.result?.value
  }
  snapshot = async () => ({ ...(await evaluate(`({path:location.pathname,href:location.href,text:document.body.innerText,stats:window.__media,buttons:[...document.querySelectorAll('button')].map(b=>({text:b.textContent.trim(),disabled:b.disabled,testid:b.dataset.testid})),interviewKeys:Object.keys(sessionStorage).filter(k=>k.includes('interview-id')),interviewValues:Object.keys(sessionStorage).filter(k=>k.includes('interview-id')).map(k=>[k,sessionStorage.getItem(k)]),apiBase:localStorage.getItem('resume-helper-local-api-base-url'),overflow:document.documentElement.scrollWidth>innerWidth})`)), errors, requests: calls.slice(-12).map(({ method, path, json }) => ({ method, path, json })) })
  await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable')
  await send('Browser.grantPermissions', { origin: base, permissions: ['audioCapture'] })
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  // Observe native media operations; every call still goes through Chrome's implementation.
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `
    window.__media={mic:0,recorders:0,mimes:[],tracks:[],urls:[],revoked:[],plays:0,prints:0};
    const gum=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia=async (...args)=>{__media.mic++;const stream=await gum(...args);__media.tracks.push(...stream.getTracks());return stream;};
    const NativeRecorder=window.MediaRecorder;
    window.MediaRecorder=class extends NativeRecorder{constructor(...args){super(...args);__media.recorders++;__media.mimes.push(this.mimeType);}};
    const create=URL.createObjectURL.bind(URL),revoke=URL.revokeObjectURL.bind(URL);
    URL.createObjectURL=(blob)=>{const url=create(blob);__media.urls.push(url);return url;};
    URL.revokeObjectURL=(url)=>{__media.revoked.push(url);return revoke(url);};
    const play=HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play=function(...args){__media.plays++;return play.apply(this,args);};
    window.print=()=>{__media.prints++;};
  ` })
  const click = async selector => {
    await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});if(!e||e.disabled)throw new Error('Missing/disabled ${selector}');e.scrollIntoView({block:'center',behavior:'instant'});})()`)
    await sleep(100)
    const point = await evaluate(`(()=>{const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`)
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, ...point })
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, ...point })
  }
  const button = async text => {
    await evaluate(`(()=>{const b=[...document.querySelectorAll('button')].find(e=>e.textContent.trim().startsWith(${JSON.stringify(text)}));if(!b)throw new Error('Missing button');b.dataset.browserCheck='target'})()`)
    await click('[data-browser-check="target"]')
    await evaluate(`document.querySelector('[data-browser-check="target"]')?.removeAttribute('data-browser-check')`)
  }
  const testid = id => click(`[data-testid="${id}"]`)
  const fill = (selector, value) => evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});if(!e||e.disabled)throw new Error('Missing/disabled input');e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));})()`)
  const visible = selector => evaluate(`!!document.querySelector(${JSON.stringify(selector)})`)
  const disabled = selector => evaluate(`document.querySelector(${JSON.stringify(selector)})?.disabled`)
  const text = () => evaluate('document.body.innerText')
  const turns = () => calls.filter(c => c.path.endsWith('/turns') || c.path.endsWith('/answers')).length
  const screenshot = async name => {
    await evaluate(`document.querySelector('.voice-panel')?.scrollIntoView({block:'center'})`)
    const result = await send('Page.captureScreenshot', { format: 'png' })
    await writeFile(join(artifacts, name), Buffer.from(result.data, 'base64'))
  }
  const navigate = async url => {
    const expectedUrl = new URL(url)
    const expectedPath = expectedUrl.pathname
    await send('Page.navigate', { url })
    await waitFor(() => evaluate(`location.pathname === ${JSON.stringify(expectedPath)} && location.search === ${JSON.stringify(expectedUrl.search)}`), `navigation to ${expectedPath}${expectedUrl.search}`)
    await waitFor(() => visible('#page-interview, #page-report, #page-settings, #page-materials, #page-resume, #page-jobs, #login-email'), 'page render')
  }
  const loadSession = async value => {
    await navigate(`${base}/settings`)
    await evaluate(`sessionStorage.setItem(${JSON.stringify(`career-studio-interview-id-v2|http://127.0.0.1:${mockPort}|demo-user` )},${JSON.stringify(value.sessionId)})`)
    await navigate(`${base}/interview?restoreCheck=${encodeURIComponent(value.sessionId)}`)
    await waitFor(() => calls.some(call => call.method === 'GET' && call.path === `/api/interviews/${value.sessionId}`), 'requested session snapshot')
    if (value.status === 'FINISHED') await waitFor(() => visible('[data-testid="view-report"]'), 'finished session restore')
    else {
      const expectedRoots = rootTurns(value).filter(turn => turn.answer != null).length
      await waitFor(async () => {
        const progress = await evaluate(`document.querySelector('.interview-progress b')?.textContent.replace(/\\s/g,'')`)
        return progress === `${expectedRoots}/${value.questionCount}`
          && (value.currentQuestion ? await visible('#interview-answer') : !await visible('#interview-answer'))
      }, 'active session restore')
    }
    await waitFor(async () => !(await text()).includes('正在确认语音能力'), 'capabilities')
  }
  const record = async ({ autoTranscribe = true } = {}) => {
    await waitFor(async () => visible('[data-testid="recording-start"]') && !await disabled('[data-testid="recording-start"]'), 'recording capability ready')
    await testid('recording-start')
    await waitFor(async () => (await text()).includes('录音中'), 'native recording', 30000)
    await sleep(1200)
    await testid('recording-stop')
    await waitFor(() => visible('audio[aria-label="回放自己的录音"]'), 'native recording blob')
    if (autoTranscribe) await waitFor(() => visible('.transcription-preview'), 'automatic transcription preview')
  }
  const transcribe = async () => { await testid('transcription-start'); await waitFor(() => visible('.transcription-preview'), 'ASR preview') }

  await navigate(`${base}/login`)
  await evaluate(`localStorage.setItem('resume-helper-demo-session',JSON.stringify({authenticated:true,email:'browser@local.test',displayName:'本地验证',createdAt:new Date().toISOString()}))`)
  await navigate(`${base}/interview`)
  const inlineModelSettings = await visible('.model-panel')
  if (inlineModelSettings) {
    await evaluate(`document.querySelector('.model-panel').open=true`)
    await fill('.model-panel input[type="password"]', 'local-browser-test')
    await fill('input[placeholder="留空使用服务端默认模型"]', 'local-model')
    await fill('input[placeholder="例如 https://api.deepseek.com/v1"]', 'http://local.invalid/v1')
  } else {
    await navigate(`${base}/settings`)
    await fill('[data-testid="model-key"]', 'local-browser-test')
    await fill('input[placeholder="例如 deepseek-chat"]', 'local-model')
    await fill('input[placeholder="例如 https://api.deepseek.com/v1"]', 'http://local.invalid/v1')
    await fill('[data-testid="speech-key"]', 'local-speech-test')
    await check('unified settings saves and detects backend independently from model and speech', async () => {
      await fill('#backend-url', `http://127.0.0.1:${mockPort}`)
      await testid('backend-save')
      await waitFor(async () => (await text()).includes('Java 服务已连接'), 'backend probe')
      assert.equal(await evaluate(`localStorage.getItem('resume-helper-local-api-base-url')`), `http://127.0.0.1:${mockPort}`)
      const probe = calls.find(c => c.path === '/api/jobs/sources')
      assert.ok(probe && !probe.headers['x-ai-api-key'] && !probe.headers['x-speech-api-key'])
    })
    await check('model and speech checks use separate credentials at the selected backend', async () => {
      await testid('model-check')
      await waitFor(async () => (await text()).includes('连接成功，模型：local-model'), 'model check')
      await testid('speech-check')
      await waitFor(async () => (await text()).includes('已合成一段测试音频'), 'speech check')
      const model = calls.find(c => c.path === '/api/ai/check'), speech = calls.find(c => c.path === '/api/interview-speech/check')
      assert.equal(model.headers['x-ai-api-key'], 'local-browser-test'); assert.equal(model.headers['x-speech-api-key'], undefined)
      assert.equal(speech.headers['x-speech-api-key'], 'local-speech-test'); assert.equal(speech.headers['x-ai-api-key'], undefined)
    })
    await check('settings survives reload and remains usable at390px without horizontal overflow', async () => {
      await navigate(`${base}/settings`)
      assert.equal(await evaluate(`document.querySelector('#backend-url').value`), `http://127.0.0.1:${mockPort}`)
      assert.equal(await evaluate(`document.querySelector('[data-testid="model-key"]').value`), 'local-browser-test')
      assert.equal(await evaluate(`document.querySelector('[data-testid="speech-key"]').value`), 'local-speech-test')
      await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
      assert.equal((await snapshot()).overflow, false)
      await send('Emulation.clearDeviceMetricsOverride')
    })
    await navigate(`${base}/interview`)
  }
  await check('no microphone or TTS request before explicit click', async () => { assert.equal(await evaluate('__media.mic'), 0); assert.equal(calls.filter(c => c.path.endsWith('/speech')).length, 0) })
  await evaluate(`document.querySelector('.paste-panel').open=true`)
  await fill('#resume-paste', material.text)
  await check('new setup defaults to QUICK and exposes explicit FULL selection', async () => { assert.equal(await evaluate(`document.querySelector('select').value`), 'QUICK'); assert.ok((await text()).includes('快速练习')); assert.ok((await text()).includes('完整面试')) })
  await button('使用这份文本')
  await waitFor(() => visible('.resume-preview'), 'material preview')
  await check('unified material response renders original resume text', async () => assert.ok((await text()).includes(material.text)))
  await evaluate(`document.querySelectorAll('select')[0].value='FULL'; document.querySelectorAll('select')[0].dispatchEvent(new Event('change',{bubbles:true}))`)
  await button('生成计划预览')
  await waitFor(() => visible('.interview-plan'), 'ready plan preview')
  await check('FULL plan preview shows coverage, specialty and follow-up budget before confirmation', async () => { const page = await text(); assert.ok(page.includes('岗位专项')); assert.ok(page.includes('项目成果')); assert.ok(page.includes('12')) })
  await button('确认计划并开始')
  await waitFor(() => visible('#interview-answer'), 'first question')
  await check('start renders generated question and voice controls without scoring criteria', async () => { assert.ok((await text()).includes('Redis 优化 Spring Boot')); assert.ok(!(await text()).includes('评分标准')); assert.equal(await visible('.dimension-scores'), false); assert.ok(!JSON.stringify(sessions.get('local-session-1')).includes('criteria')) })
  await check('clarification explains task intent while preserving the typed draft and question', async () => {
    await fill('#interview-answer', '澄清前已有回答草稿')
    const qid = sessions.get('local-session-1').currentQuestion.questionId
    await evaluate(`document.querySelector('[data-testid="clarify-question"]').click()`)
    await sleep(250)
    assert.ok(calls.some(call => call.path.endsWith('/clarifications')), `clarification request missing; page text: ${await text()}; browser errors: ${JSON.stringify(errors)}`)
    await waitFor(async () => (await text()).includes('题意说明'), 'clarification result')
    assert.equal(await evaluate(`document.querySelector('#interview-answer').value`), '澄清前已有回答草稿')
    assert.equal(sessions.get('local-session-1').currentQuestion.questionId, qid)
    assert.equal((await text()).includes('不提供示例答案'), true)
  })
  await evaluate(`document.querySelector('[data-testid="question-play"]')?.click()` )
  await waitFor(async () => calls.some(call => call.path.endsWith('/speech')) && (await text()).includes('停止朗读'), 'native MP3 playback')
  await evaluate(`document.querySelector('[data-testid="question-play"]')?.click()` )
  const ttsCalls = calls.filter(c => c.path.endsWith('/speech')).length
  await evaluate(`document.querySelector('[data-testid="question-play"]')?.click()` )
  await check('stop and replay use cached native MP3 without second synthesis', async () => { await sleep(180); assert.equal(calls.filter(c => c.path.endsWith('/speech')).length, ttsCalls); assert.ok(await evaluate('__media.plays>=2')) })
  await testid('recording-start')
  await waitFor(async () => (await text()).includes('录音中'), 'recording locks', 30000)
  await check('recording locks answer and submit while keeping finish available', async () => {
    for (const selector of ['#interview-answer', '.answer-tools button']) assert.equal(await disabled(selector), true)
    assert.equal(await disabled('[data-testid="finish-interview"]'), false)
    if (inlineModelSettings) assert.equal(await disabled('.model-panel input'), true)
    assert.equal(await disabled('[data-testid="question-play"]'), true)
  })
  await testid('recording-cancel')
  await check('cancel releases native tracks and allows re-record', async () => { await sleep(100); assert.equal(await disabled('[data-testid="recording-start"]'), false); assert.equal(await evaluate('__media.tracks.filter(t=>t.readyState!=="ended").length'), 0) })
  await fill('#interview-answer', '原有草稿')
  await record()
  await check('native fake-device MediaRecorder produces replayable WebM Blob', async () => {
    assert.ok(await evaluate('__media.recorders>=2 && __media.mimes.some(m=>m.includes("webm"))'))
    assert.ok(await evaluate(`document.querySelector('audio')?.src.startsWith('blob:')`), 'recording preview must have a local replay URL')
  })
  const before = turns()
  await check('ASR upload has real audio + matching IDs and only creates preview', async () => {
    const call = calls.findLast(c => c.upload)
    assert.ok(call.upload.size > 0 && call.upload.type.includes('webm') && call.upload.name.endsWith('.webm'))
    assert.ok(call.upload.questionId && /^[0-9a-f-]{36}$/.test(call.upload.requestId))
    assert.equal(turns(), before); assert.equal(await evaluate('document.querySelector("#interview-answer").value'), '原有草稿')
    assert.ok((await text()).includes(asrText))
  })
  await screenshot('interview-desktop.png')
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await check('390px mobile interview has no horizontal overflow', async () => { await sleep(120); assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'), false) })
  await screenshot('interview-mobile.png')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  await testid('transcript-append')
  await check('explicit append preserves editable original draft without submitting', async () => { assert.equal(await evaluate('document.querySelector("#interview-answer").value'), `原有草稿\n${asrText}`); assert.equal(turns(), before) })
  await transcribe(); await testid('transcript-keep')
  await check('keep original draft drops preview without altering answer', async () => { assert.equal(await visible('.transcription-preview'), false); assert.equal(await evaluate('document.querySelector("#interview-answer").value'), `原有草稿\n${asrText}`) })
  await transcribe(); await testid('transcript-replace'); await fill('#interview-answer', '编辑后确认的回答')
  await button('提交回答')
  await waitFor(async () => (await text()).includes('量化改进结果'), 'next question')
  await check('follow-up retains 1/13 root progress and saves edited answer', async () => { assert.equal(turns(), before + 1); assert.ok((await text()).includes('追问（不计题数）')); assert.equal(await evaluate(`document.querySelector('.interview-progress b').textContent.replace(/\\s/g,'')`), '1/13'); assert.equal(calls.findLast(c => c.answer).answer.answer, '编辑后确认的回答'); assert.equal(await visible('audio[aria-label="回放自己的录音"]'), false) })
  asrDelay = 900; await record({ autoTranscribe: false })
  await waitFor(() => visible('[data-testid="transcription-cancel"]'), 'automatic ASR pending')
  await check('transcription locks submission while finish and cancel remain available', async () => { assert.equal(await disabled('.answer-tools button'), true); assert.equal(await disabled('[data-testid="finish-interview"]'), false); assert.equal(await disabled('[data-testid="transcription-cancel"]'), false) })
  await testid('transcription-cancel')
  await fill('#interview-answer', '取消后保留的文字')
  await sleep(1100)
  await check('late canceled ASR cannot overwrite text and retained recording can retry', async () => { assert.equal(await visible('.transcription-preview'), false); assert.equal(await evaluate('document.querySelector("#interview-answer").value'), '取消后保留的文字'); assert.equal(await disabled('[data-testid="transcription-start"]'), false); assert.equal(calls.findLast(c => c.upload).responded, true) })
  asrDelay = 60
  await transcribe(); await testid('transcript-keep'); await testid('finish-interview')
  await check('kept transcription offers finish choices and preserves submit/discard actions', async () => { assert.equal(await visible('[data-testid="finish-discard"]'), true); assert.equal(await disabled('[data-testid="finish-submit"]'), false) })
  await button('继续面试'); await button('提交回答')
  for (let index = 2; index <= 13; index++) {
    await waitFor(async () => await visible('#interview-answer') && !await disabled('#interview-answer'), `root question ${index}`)
    if (index === 13) await check('final algorithm exposes Hot100 source, fixed 20% and code answer guidance', async () => {
      assert.ok((await text()).includes('算法题 · Hot 100 · 固定权重 20%'))
      assert.ok((await text()).includes('算法难度 MEDIUM'), 'actual algorithm metadata must match the confirmed STANDARD level')
      assert.equal(await evaluate(`document.querySelector('.algorithm-source a').href`), algorithm.url)
      assert.ok((await text()).includes('代码或伪代码'))
      await button('展开编辑')
      assert.equal(await evaluate(`document.querySelector('.answer-editor').classList.contains('is-expanded')`), true)
      assert.equal(await evaluate('document.activeElement.id'), 'interview-answer')
      await evaluate(`document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))`)
      assert.equal(await evaluate(`document.querySelector('.answer-editor').classList.contains('is-expanded')`), false)
      assert.ok((await evaluate('document.activeElement.textContent')).includes('展开编辑'))
      await screenshot('algorithm-question.png')
    })
    await fill('#interview-answer', index === 13 ? '使用哈希表，逐个查找差值。\nfunction twoSum(nums, target) { /* O(n) time, O(n) space */ }' : `主问题 ${index} 的回答`)
    await button('提交回答')
    await waitFor(async () => index === 13 ? visible('[data-testid="view-report"]') : await evaluate('document.querySelector("#interview-answer")?.value === ""'), `saved root ${index}`)
  }
  await waitFor(() => visible('[data-testid="view-report"]'), 'automatic last answer completion')
  await check('last answer completes, retains algorithm source and offers report entry', async () => {
    assert.equal(await visible('#interview-answer'), false)
    assert.equal(await disabled('[data-testid="view-report"]'), false)
    assert.ok((await evaluate(`document.querySelectorAll('.conversation-history .algorithm-answer-text')[1]?.textContent`)).includes('function twoSum'))
  })
  await loadSession(sessions.get('local-session-1'))
  await check('restored FINISHED session exposes report entry', async () => assert.equal(await visible('[data-testid="view-report"]'), true))
  await check('restored FINISHED history retains canonical algorithm title and official source link', async () => {
    const source = await evaluate(`document.querySelectorAll('.conversation-history .algorithm-answer-text')[1]?.textContent || ''`)
    assert.ok(source.includes('function twoSum'), 'completed algorithm history must preserve the submitted code')
  })
  failReport = 1
  await testid('view-report')
  await waitFor(async () => (await text()).includes('报告暂时不可用'), 'report error')
  await check('report failure retains saved question-answer history', async () => { assert.equal(await evaluate('location.pathname'), '/report'); assert.ok((await text()).includes('已保存的问答记录')); await evaluate('document.querySelector(".review-detail").open=true'); assert.ok((await text()).includes('编辑后确认的回答')) })
  await button('重试加载报告')
  await waitFor(() => visible('.report-meta'), 'report retry')
  await check('GET retry renders weighted report and recovered target role', async () => assert.ok((await text()).includes('本轮训练参考分') && (await text()).includes('Java 后端')))
  await check('report content order and evidence-backed priority are visible', async () => {
    const order = await evaluate(`Array.from(document.querySelectorAll('[data-testid="report-coverage"], [data-testid="priority-review"], [data-testid="next-actions"], [data-testid="positive-feedback"], [data-testid="question-review"], [data-testid="scoring-details"]')).map(e=>e.dataset.testid)`)
    assert.deepEqual(order, ['report-coverage', 'priority-review', 'next-actions', 'positive-feedback', 'question-review', 'scoring-details'])
    assert.ok((await text()).includes('补充可核对的统计范围'))
  })
  const voiceUrlCount = await evaluate('__media.urls.length')
  await click('.export-menu summary')
  await check('report export menu exposes Markdown and PDF choices', async () => { assert.equal(await visible('.export-menu-list'), true); assert.ok((await text()).includes('导出 Markdown (.md)') && (await text()).includes('导出 PDF (.pdf)')) })
  await button('导出 PDF (.pdf)')
  await waitFor(() => visible('.export-menu-list').then(value => !value), 'export menu closes')
  await check('PDF export completes without a report error', async () => { assert.ok(!(await text()).includes('导出 PDF 失败')) })
  await evaluate(`document.querySelectorAll('.review-detail').forEach(e=>e.open=true)`)
  await evaluate(`window.dispatchEvent(new Event('beforeprint'))`)
  await send('Emulation.setEmulatedMedia', { media: 'print' })
  try {
    await check('report score and feedback remain visible in print media', async () => {
      for (const selector of ['#page-report', '.score-main', '.question-review']) {
        assert.equal(await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});return !!e && getComputedStyle(e).display!=='none' && e.getBoundingClientRect().height>0})()`), true, `${selector} hidden in print`)
      }
      assert.ok((await text()).includes('本地模拟报告')); assert.ok((await evaluate(`[...document.querySelectorAll('.review-feedback-label')].map(e=>e.textContent).join('|')`)).includes('原文证据')); assert.ok((await evaluate(`[...document.querySelectorAll('.review-feedback-label')].map(e=>e.textContent).join('|')`)).includes('五维得分')); assert.ok((await text()).includes('权重'))
      assert.equal(await evaluate(`[...document.querySelectorAll('#page-report button')].some(button=>getComputedStyle(button).display!=='none')`), false)
    })
  } finally { await send('Emulation.setEmulatedMedia', { media: '' }); await evaluate(`window.dispatchEvent(new Event('afterprint'))`) }
  const pdf = await send('Page.printToPDF', { printBackground: true })
  await writeFile(join(artifacts, 'interview-report.pdf'), Buffer.from(pdf.data, 'base64'))
  await check('native Chrome prints report PDF with text content', async () => {
    const bytes = Buffer.from(pdf.data, 'base64')
    assert.ok(bytes.length > 1000)
    const streams = [...bytes.toString('latin1').matchAll(/stream\r?\n([\s\S]*?)\r?\nendstream/g)]
    const containsText = streams.some(([, data]) => {
      try { return /\bBT\b[\s\S]*?\b(?:Tj|TJ)\b/.test(inflateSync(Buffer.from(data, 'latin1')).toString('latin1')) }
      catch { return false }
    })
    assert.ok(containsText, 'PDF must contain rendered text, not only an empty page or rectangles')
  })
  await check('report navigation cleans media tracks and Blob URLs', async () => { assert.equal(await evaluate('__media.tracks.filter(t=>t.readyState!=="ended").length'), 0); assert.equal(await evaluate(`__media.urls.slice(0,${voiceUrlCount}).every(u=>__media.revoked.includes(u))`), true) })
  const openedRounds = calls.filter(c => c.path === '/api/interviews' && c.method === 'POST').length
  await check('practice again opens the setup page instead of the finished session', async () => {
    await evaluate(`document.querySelector('[data-testid="practice-again"]')?.click()`)
    await waitFor(() => evaluate('location.pathname === "/interview" && location.search.includes("intent=new")'), 'practice again navigation')
    await waitFor(() => visible('.setup-grid'), 'practice again setup')
    assert.equal(await evaluate('location.pathname'), '/interview')
    assert.equal(await evaluate('location.search'), '?intent=new&sourceSessionId=local-session-1')
    assert.ok((await text()).includes('准备你的简历'))
    assert.ok(!(await text()).includes('SESSION COMPLETE'))
    assert.equal(await visible('#interview-answer'), false)
    assert.equal(await visible('[data-testid="view-report"]'), false)
  })
  await check('practice again reuses the previous resume and role without opening a new session', async () => {
    await waitFor(() => visible('.resume-preview'), 'source resume preview')
    assert.equal(await evaluate('document.querySelector(".setup-grid input").value'), 'Java 后端')
    assert.ok((await text()).includes(material.text))
    assert.equal(await disabled('.interview-main .button-primary'), true)
    assert.equal(calls.filter(c => c.path === '/api/interviews' && c.method === 'POST').length, openedRounds)
    assert.equal(await evaluate(`sessionStorage.getItem(${JSON.stringify(`career-studio-interview-id-v2|http://127.0.0.1:${mockPort}|demo-user`)})`), null)
    assert.ok(calls.some(c => c.method === 'GET' && c.path === '/api/interviews/local-session-1'), 'source session was read')
    assert.ok(calls.some(c => c.path === '/api/materials/local-material'), 'source resume was read')
  })
  await check('the previous round and its answers can still be viewed from the setup page', async () => {
    await evaluate('history.back()')
    await waitFor(() => visible('.report-meta'), 'previous round report')
    assert.equal(await evaluate('location.search'), '?sessionId=local-session-1')
    assert.equal(await evaluate('document.querySelectorAll(".review-detail").length'), 13)
    await evaluate('document.querySelector(".review-detail").open=true')
    assert.ok((await text()).includes('编辑后确认的回答'))
  })
  await loadSession(sessions.get('local-session-1'))
  await check('intent=new without a source session clears the restore pointer and shows setup', async () => {
    await navigate(`${base}/interview?intent=new`)
    await waitFor(() => visible('.setup-grid'), 'intent new setup')
    assert.ok(!(await text()).includes('SESSION COMPLETE'))
    assert.equal(await visible('[data-testid="view-report"]'), false)
    assert.equal(await evaluate(`sessionStorage.getItem(${JSON.stringify(`career-studio-interview-id-v2|http://127.0.0.1:${mockPort}|demo-user`)})`), null)
  })

  caps = { ...caps, ttsEnabled: false }
  await loadSession(newSession())
  await record(); await transcribe()
  const retained = await evaluate('document.querySelector("audio").src')
  await button('重试语音能力')
  await waitFor(async () => !(await text()).includes('正在确认语音能力'), 'capability retry')
  await check('capability retry with TTS unavailable preserves recording/URL/preview', async () => { assert.equal(await evaluate('document.querySelector("audio").src'), retained); assert.equal(await visible('.transcription-preview'), true); assert.equal(await disabled('[data-testid="question-play"]'), true); assert.equal(await disabled('[data-testid="transcription-start"]'), false) })
  await testid('transcript-keep'); await fill('#interview-answer', '未提交草稿')
  await testid('finish-interview')
  await check('finish dialog focuses its close action and Escape restores opener focus', async () => {
    assert.equal(await evaluate('document.activeElement.textContent.trim()'), '继续面试')
    await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
    await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
    await waitFor(async () => !(await visible('[data-testid="finish-submit"]')), 'finish dialog closes on Escape')
    assert.equal(await evaluate('document.activeElement.dataset.testid'), 'finish-interview')
  })
  await testid('finish-interview')
  await check('local draft finish offers submit/discard/continue', async () => { assert.equal(await disabled('[data-testid="finish-submit"]'), false); assert.equal(await visible('[data-testid="finish-discard"]'), true); assert.equal(await visible('[data-testid="finish-preserve"]'), false) })
  await button('继续面试'); failAnswer = true
  await button('提交回答')
  await waitFor(async () => (await text()).includes('下一题生成失败'), 'generation failure with saved answer')
  await check('generation failure keeps the saved answer in history and offers generation-only retry', async () => { assert.equal(await evaluate('location.pathname'), '/interview'); assert.equal(await visible('#interview-answer'), false); assert.ok((await text()).includes('未提交草稿')); assert.equal(await visible('[data-testid="recording-start"]'), false); assert.equal(await visible('button:not([disabled])'), true) })
  await testid('finish-interview')
  await check('finish after generation failure explicitly preserves the accepted answer', async () => { assert.equal(await visible('[data-testid="finish-discard"]'), false); assert.equal(await visible('[data-testid="finish-preserve"]'), true); assert.ok((await text()).includes('保留已提交回答并结束')) })
  await testid('finish-preserve'); await waitFor(() => visible('.report-meta'), 'preserve report')
  await check('preserve finish includes the accepted answer in report session', async () => assert.equal(sessions.get('local-session-2').turns[0].answer, '未提交草稿'))
  failAnswer = false
  const manualRetrySession = newSession()
  await loadSession(manualRetrySession)
  failAnswer = true
  await fill('#interview-answer', '等待手动继续的回答'); await button('提交回答')
  await waitFor(async () => (await text()).includes('下一题生成失败'), 'manual retry state')
  failAnswer = false
  await button('重试生成')
  await waitFor(() => visible('#interview-answer') && manualRetrySession.turns.length === 2, 'manual generation retry succeeds', 15000)
  await check('manual generation retry resumes the saved answer without saving it again', async () => {
    assert.equal(manualRetrySession.turns[0].answer, '等待手动继续的回答')
    assert.equal(calls.filter(call => call.path === `/api/interviews/${manualRetrySession.sessionId}/answers`).length, 1)
    assert.equal(calls.filter(call => call.path === `/api/interviews/${manualRetrySession.sessionId}/generations`).length, 2)
  })
  const retrySuccess = newSession()
  await loadSession(retrySuccess)
  const retryBehavior = { outcomes: ['NETWORK', 'NETWORK', 'SUCCESS'], terminal: false }
  generationBehaviors.set(retrySuccess.sessionId, retryBehavior)
  await fill('#interview-answer', '两次暂时失败后恢复')
  await button('提交回答')
  await waitFor(() => retrySuccess.generation?.state === 'SUCCEEDED' && !!retrySuccess.currentQuestion, 'automatic generation retry succeeds', 15000)
  await waitFor(async () => await visible('#interview-answer') && (await text()).includes('请说明你的实际贡献'), 'automatic retry question appears', 5000)
  await check('two transient generation failures recover automatically without duplicate submit or generation requests', async () => {
    assert.equal(retryBehavior.currentAttempt, 2)
    assert.equal(sessions.get(retrySuccess.sessionId).generation.state, 'SUCCEEDED')
    assert.equal(calls.filter(call => call.path === `/api/interviews/${retrySuccess.sessionId}/generations`).length, 1)
    assert.equal(sessions.get(retrySuccess.sessionId).turns[0].answer, '两次暂时失败后恢复')
  })
  const exhausted = newSession()
  await loadSession(exhausted)
  const exhaustedBehavior = { outcomes: Array(6).fill('NETWORK'), terminal: false }
  generationBehaviors.set(exhausted.sessionId, exhaustedBehavior)
  await fill('#interview-answer', '六次失败后停止')
  await button('提交回答')
  await waitFor(() => sessions.get(exhausted.sessionId).generation?.state === 'FAILED', 'generation stops at five retries', 30000)
  await check('five automatic retries stop after six total failed attempts and preserve the accepted answer', async () => {
    assert.equal(exhaustedBehavior.currentAttempt, 5)
    assert.equal(sessions.get(exhausted.sessionId).generation.retryCount, 5)
    assert.equal(sessions.get(exhausted.sessionId).turns[0].answer, '六次失败后停止')
  })
  const budgetLimited = newSession()
  await loadSession(budgetLimited)
  const budgetBehavior = { outcomes: ['NETWORK', 'SUCCESS'], budgetMs: 0, terminal: false }
  generationBehaviors.set(budgetLimited.sessionId, budgetBehavior)
  await fill('#interview-answer', '预算到期停止')
  await button('提交回答')
  await waitFor(() => sessions.get(budgetLimited.sessionId).generation?.state === 'FAILED', 'generation deadline stops retries')
  await check('expired generation budget stops before another automatic retry', async () => {
    assert.equal(budgetBehavior.currentAttempt, 0)
    assert.equal(sessions.get(budgetLimited.sessionId).generation.retryCount, 0)
  })
  const invalidConfig = newSession()
  await loadSession(invalidConfig)
  const configBehavior = { outcomes: ['CONFIG', 'SUCCESS'], terminal: false }
  generationBehaviors.set(invalidConfig.sessionId, configBehavior)
  await fill('#interview-answer', '配置失败不重试')
  await button('提交回答')
  await waitFor(() => sessions.get(invalidConfig.sessionId).generation?.state === 'FAILED', 'configuration error stops retries')
  await check('non-retryable configuration failure makes exactly one attempt', async () => {
    assert.equal(configBehavior.currentAttempt, 0)
    assert.equal(sessions.get(invalidConfig.sessionId).generation.failure.code, 'CONFIG')
    assert.equal(sessions.get(invalidConfig.sessionId).generation.retryCount, 0)
  })
  const finishDuringRetry = newSession()
  await loadSession(finishDuringRetry)
  const heldRetry = { outcomes: ['NETWORK', 'SUCCESS'], terminal: false, holdRetry: true }
  generationBehaviors.set(finishDuringRetry.sessionId, heldRetry)
  await fill('#interview-answer', '重试等待时结束')
  await button('提交回答')
  await waitFor(() => sessions.get(finishDuringRetry.sessionId).generation?.state === 'RETRY_WAIT', 'retry wait before finish')
  await waitFor(async () => visible('[data-testid="finish-interview"]') && !await disabled('[data-testid="finish-interview"]'), 'finish action enabled during retry wait')
  await testid('finish-interview'); await testid('finish-discard')
  await waitFor(() => visible('.report-meta'), 'finish while retry is queued')
  await check('finishing during automatic retry cancels it and preserves the accepted answer', async () => {
    assert.equal(finishDuringRetry.status, 'FINISHED')
    assert.equal(finishDuringRetry.generation.state, 'CANCELLED')
    assert.equal(finishDuringRetry.turns[0].answer, '重试等待时结束')
    assert.equal(finishDuringRetry.turns.length, 1)
  })
  const submitAndFinish = newSession()
  await loadSession(submitAndFinish); await fill('#interview-answer', '提交后结束的回答'); await testid('finish-interview'); await testid('finish-submit')
  await waitFor(() => visible('.report-meta'), 'submit then finish')
  await check('submit-and-finish saves answer before ending and routing', async () => { assert.equal(submitAndFinish.status, 'FINISHED'); assert.equal(submitAndFinish.turns[0].answer, '提交后结束的回答') })
  const discardFinish = newSession()
  await loadSession(discardFinish); await fill('#interview-answer', '放弃的本地草稿'); await testid('finish-interview'); await testid('finish-discard')
  await waitFor(() => visible('.report-meta'), 'discard report')
  await check('discard finish does not submit unsent local draft', async () => assert.equal(discardFinish.turns[0].answer, undefined))
  const lostFinishResponse = newSession()
  await loadSession(lostFinishResponse); await fill('#interview-answer', '结束响应丢失后仍保存')
  failFinishResponse = true
  await testid('finish-interview'); await testid('finish-submit')
  await waitFor(() => visible('.report-meta'), 'finish response loss recovered from session read', 15000)
  await check('lost finish response reads back FINISHED state and routes with the accepted answer', async () => {
    assert.equal(lostFinishResponse.status, 'FINISHED')
    assert.equal(lostFinishResponse.turns[0].answer, '结束响应丢失后仍保存')
    assert.ok(calls.some(call => call.method === 'GET' && call.path === `/api/interviews/${lostFinishResponse.sessionId}`))
  })
  const openingPending = newSession()
  openingPending.turns = []
  openingPending.questionMetadata = {}
  delete openingPending.currentQuestion
  openingPending.generation = { generationId: `opening-${openingPending.sessionId}`, requestId: 'opening-pending', kind: 'OPENING',
    stage: 'OPENING', state: 'RUNNING', attempt: 1, retryCount: 0, maxRetries: 5,
    startedAt: new Date().toISOString(), deadlineAt: new Date(Date.now() + 120000).toISOString() }
  await evaluate(`sessionStorage.setItem(${JSON.stringify(`career-studio-interview-id-v2|http://127.0.0.1:${mockPort}|demo-user`)},${JSON.stringify(openingPending.sessionId)})`)
  await navigate(`${base}/interview`)
  await waitFor(() => visible('[data-testid="finish-interview"]'), 'opening generation without a first question')
  await testid('finish-interview'); await testid('finish-discard')
  await waitFor(() => visible('.report-meta'), 'finish during opening generation')
  await check('ending during first-question generation cancels opening and keeps the empty session ended', async () => {
    assert.equal(openingPending.status, 'FINISHED')
    assert.equal(openingPending.generation.state, 'CANCELLED')
    assert.equal(openingPending.turns.length, 0)
  })
  const finishDuringRecording = newSession()
  await loadSession(finishDuringRecording)
  await fill('#interview-answer', '录音中结束时保留的草稿')
  await testid('recording-start')
  await waitFor(async () => (await text()).includes('录音中'), 'recording active before finish')
  const transcriptionsBeforeRecordingFinish = calls.filter(call => call.path.endsWith('/transcriptions')).length
  await testid('finish-interview')
  await waitFor(() => visible('[data-testid="finish-transcribe"]'), 'finish dialog keeps recording for review')
  await check('finishing during recording keeps a local replay and does not auto-upload audio', async () => {
    assert.ok(await evaluate(`document.querySelector('audio[aria-label="回放自己的录音"]')?.src.startsWith('blob:')`))
    assert.equal(calls.filter(call => call.path.endsWith('/transcriptions')).length, transcriptionsBeforeRecordingFinish)
    assert.equal(await evaluate('__media.tracks.filter(track=>track.readyState!=="ended").length'), 0)
  })
  await testid('finish-discard')
  await waitFor(() => visible('.report-meta'), 'finish after recording')
  await check('discarding a local draft after recording ends without submitting it', async () => assert.equal(finishDuringRecording.turns[0].answer, undefined))
  const finishDuringTranscription = newSession()
  await loadSession(finishDuringTranscription)
  await fill('#interview-answer', '转写中要保留的原文')
  asrDelay = 900
  await record({ autoTranscribe: false })
  await waitFor(() => visible('[data-testid="transcription-cancel"]'), 'transcription active before finish')
  await testid('finish-interview')
  await waitFor(() => visible('[data-testid="finish-transcribe"]'), 'finish dialog offers review retry')
  asrDelay = 60
  await testid('finish-transcribe')
  await waitFor(() => visible('.finish-transcript'), 'finish transcription preview')
  await check('finish requested during ASR retains source text and permits a review preview', async () => {
    assert.equal(await evaluate('document.querySelector("#interview-answer").value'), '转写中要保留的原文')
    assert.ok((await text()).includes(asrText))
    assert.equal(finishDuringTranscription.turns[0].answer, undefined)
  })
  await evaluate(`document.querySelector('.finish-transcript button:last-child').click()`)
  await testid('finish-discard')
  await waitFor(() => visible('.report-meta'), 'finish after transcription review')
  asrDelay = 60
  caps = { ...caps, asrEnabled: false }
  await loadSession(newSession()); await fill('#interview-answer', '纯文字回答')
  await check('disabled ASR/TTS leaves ordinary text submission available', async () => { assert.equal(await disabled('[data-testid="recording-start"]'), true); assert.equal(await disabled('[data-testid="question-play"]'), true); assert.equal(await disabled('.answer-tools button'), false); assert.ok((await text()).includes('请用文字回答')) })
  await button('提交回答'); await waitFor(async () => (await text()).includes('量化改进结果'), 'text fallback next question')
  const fixture = (mode = 'complete', kind = 'ALGORITHM') => {
    const value = newSession(kind)
    value.status = 'FINISHED'; delete value.currentQuestion
    value.turns = Array.from({ length: mode === 'empty' ? 1 : mode === 'early' ? 2 : 13 }, (_unused, offset) => {
      const index = offset + 1, questionId = `${value.sessionId}-q${index}`
      value.questionMetadata[questionId] = metadata(index, index === 13 ? kind : 'MAIN')
      return { questionId, question: index === 13 && kind === 'CASE_STUDY' ? '某活动转化率下降，请分析问题并提出验证方案。' : index === 13 ? '两数之和：请提供解法和复杂度。' : `加权题目 ${index}`, ...(mode === 'empty' || mode === 'early' && index > 1 ? {} : { answer: `原始回答 ${index}：用同一批接口压测验证缓存效果。` }) }
    })
    const follow = { questionId: `${value.sessionId}-f1`, question: '追问：如何确保统计结果可信？', answer: '补充回答：用对照组和相同样本验证。' }
    if (mode !== 'empty') { value.questionMetadata[follow.questionId] = metadata(1, 'FOLLOW_UP', value.turns[0].questionId); value.turns.splice(1, 0, follow) }
    const missing = new Set(mode === 'partial' ? [rootTurns(value)[1].questionId] : mode === 'unavailable' ? rootTurns(value).map(turn => turn.questionId) : [])
    reportStates.set(value.sessionId, { missing, zero: mode === 'zero', retryFails: mode === 'partial' ? 1 : 0 })
    return value
  }
  const openReport = async value => { await navigate(`${base}/report?sessionId=${value.sessionId}`); await waitFor(() => visible('.report-meta'), 'weighted report fixture') }
  const normal = fixture(); await openReport(normal)
  await check('complete review summary quotes original answer and lists a concrete next action', async () => {
    assert.ok((await text()).includes('原始回答 1：用同一批接口压测验证缓存效果。'))
    assert.ok((await text()).includes('说明统计周期和对照方法。'))
  })
  await check('13 root weights sum to100 with algorithm20 and exact weighted total82', async () => {
    assert.equal(await evaluate(`document.querySelector('[data-testid="overall-score"] strong').textContent.trim()`), '82')
    const shares = await evaluate(`[...document.querySelectorAll('.review-weight')].map(e=>Number(e.textContent.match(/权重 ([0-9.]+)%/)[1]))`)
    assert.equal(shares.length, 13); assert.equal(shares.at(-1), 20); assert.equal(shares.slice(0,12).reduce((sum, n) => sum + Math.round(n*100), 0), 8000)
    await evaluate(`document.querySelector('.review-detail').open=true`)
    assert.ok((await text()).includes('补充回答：用对照组和相同样本验证。'))
    assert.equal(await evaluate(`document.querySelectorAll('.review-detail').length`), 13)
    await evaluate(`document.querySelector('.review-detail').open=true`)
    for (const textValue of ['原始回答 1', '原文证据', '统计周期', '复练回答框架', '切题与正确性', '关键要点覆盖', '推理与取舍', '证据与具体性', '表达结构']) assert.ok((await text()).includes(textValue), textValue)
  })
  await check('report offers independent re-answer versions without changing the original total score', async () => {
    const scoreBefore = await evaluate(`document.querySelector('[data-testid="overall-score"] strong').textContent.trim()`)
    await evaluate(`document.querySelector('.review-detail').open=true`)
    await button('重答此题')
    await waitFor(async () => (await text()).includes('首答（保留作比较）'), 'report re-answer entry')
    await fill('[data-testid="interview-attempt"] textarea', '报告页重答：加入具体结果和验证证据。')
    await button('保存并评估重答')
    await waitFor(async () => (await text()).includes('重答补充了测量方式。'), 'report re-answer feedback')
    assert.equal(await evaluate(`document.querySelector('[data-testid="overall-score"] strong').textContent.trim()`), scoreBefore)
    assert.ok((await text()).includes('原始回答 1：用同一批接口压测验证缓存效果。'))
  })
  await check('report selection prepares a three-question focused plan and starts an independent round', async () => {
    const beforeCount = sessions.size
    assert.equal(await evaluate(`document.querySelectorAll('.priority-select input:checked').length`), 3)
    await evaluate(`(() => { const boxes=[...document.querySelectorAll('.priority-select input')]; boxes[1].checked=false; boxes[1].dispatchEvent(new Event('change',{bubbles:true})); boxes[2].checked=false; boxes[2].dispatchEvent(new Event('change',{bubbles:true})) })()`)
    await testid('prepare-focused-practice')
    await waitFor(() => evaluate(`location.pathname === '/interview' && location.search.includes('planId=focused-')`), 'focused plan setup navigation')
    const request = calls.findLast(call => call.path.endsWith('/practice-plans'))
    assert.equal(request.json.issueIds.length, 1)
    assert.ok(request.json.issueIds[0])
    await waitFor(async () => (await text()).includes('报告重点 1 · 补充可验证的结果'), 'focused targets shown in setup preview')
    assert.ok((await text()).includes('来源报告：'))
    await button('确认计划并开始')
    await waitFor(() => visible('#interview-answer'), 'focused practice opening')
    const focused = [...sessions.values()].at(-1)
    assert.equal(focused.sessionId === normal.sessionId, false)
    assert.equal(focused.plan.mode, 'QUICK')
    assert.equal(focused.plan.mainCount, 3)
    assert.equal(focused.plan.practiceTargets.length, 1)
    for (let i = 0; i < 3; i++) {
      await fill('#interview-answer', `短练新回答 ${i + 1}：验证结果并说明依据。`)
      await button('提交回答')
      await waitFor(() => visible('[data-testid="quick-next-question"]'), 'focused explicit next action')
      await button('继续生成下一题')
      if (i < 2) await waitFor(() => visible('#interview-answer'), 'focused next question')
    }
    await waitFor(() => visible('[data-testid="view-report"]'), 'focused round completion')
    await testid('view-report')
    await waitFor(() => visible('.report-meta'), 'focused independent report')
    assert.ok((await text()).includes('短练新回答 1：验证结果并说明依据。'))
    assert.equal(await evaluate(`document.querySelectorAll('.review-detail').length`), 3)
    assert.equal(sessions.size, beforeCount + 1)
    await openReport(normal)
    assert.equal(await evaluate(`document.querySelector('[data-testid="overall-score"] strong').textContent.trim()`), '82')
  })
  await openReport(normal)
  await check('refresh reuses cached scoring via GET', async () => { assert.equal(reportGenerations.get(normal.sessionId), 1); assert.equal(calls.filter(call=>call.path.includes(normal.sessionId) && call.path.endsWith('/report/retry')).length, 0) })
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await check('weighted report has no mobile horizontal overflow', async () => { await sleep(100); assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'), false) })
  await screenshot('weighted-report-mobile.png')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  await openReport(fixture('early'))
  await check('early finish labels answered scope and unanswered rows without zero scores', async () => {
    assert.ok((await text()).includes('已答 1/13 题，提前结束')); assert.equal(await evaluate(`document.querySelectorAll('[data-status="UNANSWERED"]').length`), 1)
    assert.equal(await evaluate(`document.querySelectorAll('[data-status="UNANSWERED"] small').length`), 0)
  })
  const partial = fixture('partial'); await openReport(partial)
  const stable = JSON.stringify(cachedReports.get(partial.sessionId).turnFeedbacks[0])
  await check('partial report shows successful question scores but no total or level', async () => { assert.equal(await visible('[data-testid="overall-score"]'), false); assert.equal(await evaluate(`document.querySelectorAll('[data-status="SCORED"]').length`), 12); assert.equal(await visible('[data-testid="retry-scoring"]'), true); assert.ok(!(await text()).includes('合格')) })
  await testid('retry-scoring')
  await check('retry keeps prior report visible and disables duplicate scoring', async () => { assert.equal(await visible('.question-review'), true); assert.equal(await disabled('[data-testid="retry-scoring"]'), true) })
  await waitFor(async () => (await text()).includes('评分重试失败'), 'retry failure')
  await check('failed scoring retry retains question answers and successful scores', async () => { assert.equal(await evaluate(`document.querySelectorAll('[data-status="SCORED"]').length`), 12); await evaluate(`document.querySelector('.review-detail').open=true`); assert.ok((await text()).includes('原始回答 1')); assert.equal(await visible('[data-testid="overall-score"]'), false) })
  await testid('retry-scoring'); await waitFor(() => visible('[data-testid="overall-score"]'), 'retry success')
  await check('explicit POST retries missing question only and preserves successful assessment', async () => {
    const retry = calls.findLast(call=>call.path.endsWith('/report/retry'))
    assert.equal(retry.method, 'POST'); assert.deepEqual(retry.retryQuestions, [rootTurns(partial)[1].questionId])
    assert.equal(JSON.stringify(cachedReports.get(partial.sessionId).turnFeedbacks[0]), stable)
  })
  await openReport(fixture('unavailable'))
  await check('all scoring unavailable preserves question history with no invented score or praise', async () => {
    assert.equal(await visible('[data-testid="overall-score"]'), false); assert.ok((await text()).includes('评分暂不可用')); assert.ok((await text()).includes('暂无有效反馈'))
    assert.equal(await evaluate(`document.querySelectorAll('[data-status="PENDING"]').length`), 13); assert.ok(!(await text()).includes('合格'))
  })
  await openReport(fixture('empty'))
  await check('empty report has no numeric score or scoring retry', async () => { assert.equal(await visible('[data-testid="overall-score"]'), false); assert.equal(await visible('[data-testid="retry-scoring"]'), false); assert.ok((await text()).includes('尚无可评分内容')); assert.equal(await evaluate(`document.querySelectorAll('[data-status="UNANSWERED"]').length`), 1) })
  await openReport(fixture('zero'))
  await check('legitimate zero remains a scored total and per-question score', async () => { assert.equal(await evaluate(`document.querySelector('[data-testid="overall-score"] strong').textContent.trim()`), '0'); assert.equal(await evaluate(`document.querySelector('[data-status="SCORED"]').textContent.trim()`), '0 /100') })
  const caseSession = newSession('CASE_STUDY')
  await loadSession(caseSession)
  await button('本场信息')
  await check('information drawer exposes the frozen professional plan and rationale', async () => {
    const plan = await evaluate(`document.querySelector('.interview-info-drawer').textContent`)
    assert.ok(plan.includes('专业案例题') && plan.includes('用户增长分析') && plan.includes('目标岗位为运营'))
  })
  await evaluate(`document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))`)
  await check('Escape closes the drawer and returns focus to its opener', async () => {
    assert.equal(await visible('.interview-info-drawer'), false)
    assert.ok((await evaluate('document.activeElement.textContent')).includes('本场信息'))
  })
  while (caseSession.status === 'ACTIVE') {
    await waitFor(async () => await visible('#interview-answer') && !await disabled('#interview-answer'), 'case session answer ready')
    if (caseSession.questionMetadata[caseSession.currentQuestion.questionId].kind === 'CASE_STUDY') {
      await check('question13 is a20 percent professional case with job analysis guidance and safe plain text', async () => {
        assert.ok((await evaluate(`document.querySelector('.question-number').textContent`)).includes('专业案例题'))
        assert.ok((await evaluate(`document.querySelector('.question-number').textContent`)).includes('20%'))
        assert.equal(await evaluate(`document.querySelectorAll('.question-card .algorithm-source').length`), 0)
        assert.ok((await evaluate(`document.querySelector('label[for="interview-answer"]').textContent`)).includes('专业方法'))
        assert.equal(await evaluate(`document.querySelector('#interview-answer').placeholder.includes('代码')`), false)
        assert.ok((await evaluate(`document.querySelector('.question-card h3').textContent`)).includes('<script>alert(1)</script>'))
        assert.equal(await evaluate(`document.querySelectorAll('.question-card script').length`), 0)
        await screenshot('professional-case-question.png')
      })
    }
    await fill('#interview-answer', '基于案例数据核对转化漏斗，优先检验下单环节，以对照组验证改进。')
    await button('提交回答')
    await waitFor(async () => await visible('[data-testid="view-report"]') || await evaluate('document.querySelector("#interview-answer")?.value === ""'), 'case answer saved')
  }
  await check('case answer completes exactly13 roots with follow-up excluded and no Hot100 source', async () => {
    assert.equal(rootTurns(caseSession).length, 13, JSON.stringify(caseSession.turns.map(turn => [turn.questionId, caseSession.questionMetadata[turn.questionId]?.kind, caseSession.questionMetadata[turn.questionId]?.parentQuestionId]))); assert.equal(caseSession.turns.length, 14)
    assert.equal(await evaluate(`document.querySelectorAll('.history .algorithm-source').length`), 0)
    assert.equal(caseSession.questionMetadata[caseSession.turns.at(-1).questionId].kind, 'CASE_STUDY')
    assert.equal(await visible('[data-testid="view-report"]'), true)
  })
  await loadSession(caseSession)
  await check('restored completed case retains specialty metadata and report entry', async () => {
    assert.equal(caseSession.questionMetadata[caseSession.turns.at(-1).questionId].kind, 'CASE_STUDY')
    assert.equal(await visible('[data-testid="view-report"]'), true)
    assert.equal(await evaluate(`document.querySelectorAll('.history .algorithm-source').length`), 0)
  })
  await openReport(caseSession)
  await check('case report has13 roots, fixed20 percent and professional feedback without an algorithm link', async () => {
    assert.equal(await evaluate(`document.querySelectorAll('.review-detail').length`), 13)
    await evaluate(`document.querySelectorAll('.review-detail')[12].open=true`)
    assert.ok((await evaluate(`document.querySelectorAll('.review-detail .review-kind')[12].textContent`)).includes('专业案例题'))
    assert.ok((await evaluate(`document.querySelectorAll('.review-weight')[12].textContent`)).includes('20%'))
    assert.equal(await evaluate(`document.querySelectorAll('.algorithm-source').length`), 0)
    assert.equal(await visible('[data-testid="overall-score"]'), true)
    await screenshot('professional-case-report.png')
  })
  const pendingCase = fixture('partial', 'CASE_STUDY')
  reportStates.set(pendingCase.sessionId, { missing: new Set([rootTurns(pendingCase).at(-1).questionId]) })
  await openReport(pendingCase); await testid('retry-scoring')
  await waitFor(() => visible('[data-testid="overall-score"]'), 'professional case retry')
  await check('case retry targets only its missing assessment and retains fixed20 percent', async () => {
    assert.deepEqual(calls.findLast(call => call.path.endsWith('/report/retry')).retryQuestions, [rootTurns(pendingCase).at(-1).questionId])
    assert.ok((await evaluate(`document.querySelectorAll('.review-weight')[12].textContent`)).includes('20%'))
  })
  await openReport(fixture('early', 'CASE_STUDY'))
  await check('early non-coding report reserves specialty20 percent and has no complete total', async () => {
    assert.equal(await visible('[data-testid="overall-score"]'), false)
    assert.ok((await text()).includes('已答 1/13 题，提前结束'))
    assert.ok((await text()).includes('岗位专项题固定 20%'))
  })
  await check('a started round keeps its session across a reload instead of being lost to intent=new', async () => {
    await navigate(`${base}/interview?intent=new&sourceSessionId=${normal.sessionId}`)
    await waitFor(() => visible('.resume-preview'), 'prefilled source round')
    assert.equal(await evaluate('location.search'), `?intent=new&sourceSessionId=${normal.sessionId}`)
    await evaluate(`document.querySelectorAll('select')[0].value='FULL'; document.querySelectorAll('select')[0].dispatchEvent(new Event('change',{bubbles:true}))`)
  await button('生成计划预览')
  await waitFor(() => visible('.interview-plan'), 'ready plan preview')
  await check('FULL plan preview shows coverage, specialty and follow-up budget before confirmation', async () => { const page = await text(); assert.ok(page.includes('岗位专项')); assert.ok(page.includes('项目成果')); assert.ok(page.includes('12')) })
  await button('确认计划并开始')
    await waitFor(() => visible('#interview-answer'), 'new round started')
    await waitFor(async () => !(await evaluate('location.search')), 'intent cleared once the round exists')
    await navigate(`${base}/interview`)
    await waitFor(() => visible('#interview-answer'), 'reload restores the new round')
    assert.equal(await disabled('#interview-answer'), false)
  })
  const draftKeyOf = value => `career-studio-draft-v1|http://127.0.0.1:${mockPort}|demo-user|${value.sessionId}|${value.turns[0].questionId}`
  const draftSession = newSession()
  await loadSession(draftSession)
  await fill('#interview-answer', '草稿应跨页面保留')
  await sleep(400)
  await check('typed draft survives a settings round trip and a hard reload', async () => {
    await navigate(`${base}/settings`)
    await navigate(`${base}/interview`)
    await waitFor(async () => (await evaluate('document.querySelector("#interview-answer")?.value')) === '草稿应跨页面保留', 'draft restored after settings round trip')
    await navigate(`${base}/interview`)
    await waitFor(async () => (await evaluate('document.querySelector("#interview-answer")?.value')) === '草稿应跨页面保留', 'draft restored after reload')
    const note = await evaluate('document.querySelector(".interview-footnote").textContent')
    assert.ok(note.includes('7 天') && note.includes('回答文字') && note.includes('本地录音'), 'footnote must distinguish saved text from local recording')
  })
  await check('a failed submit keeps the question draft on disk and reports no storage problem', async () => {
    failSave = true
    await fill('#interview-answer', '提交失败后应保留')
    await sleep(400)
    await button('提交回答')
    await waitFor(async () => (await text()).includes('失败'), 'submit failure shown')
    const stored = await evaluate(`localStorage.getItem(${JSON.stringify(draftKeyOf(draftSession))})`)
    assert.ok(stored && stored.includes('提交失败后应保留'), 'a failed submit must not clear the local draft')
    assert.ok(!(await text()).includes('本机自动保存不可用'), 'storage is working, so no availability warning')
    failSave = false
  })
  const committedSaveResponseLost = newSession()
  await loadSession(committedSaveResponseLost)
  loseSaveResponse = true
  await fill('#interview-answer', '保存已提交但响应丢失')
  await button('提交回答')
  await waitFor(() => visible('#interview-answer') && committedSaveResponseLost.turns.length === 2, 'save response loss recovered and generation resumed', 15000)
  await check('lost save response reuses the idempotency key and retains exactly one committed answer', async () => {
    assert.equal(committedSaveResponseLost.turns.filter(turn => turn.answer === '保存已提交但响应丢失').length, 1)
    const submits = calls.filter(call => call.path === `/api/interviews/${committedSaveResponseLost.sessionId}/answers`)
    assert.ok(submits.length >= 1)
    assert.equal(new Set(submits.map(call => call.answer.submissionId)).size, 1)
    assert.ok(calls.some(call => call.method === 'GET' && call.path === `/api/interviews/${committedSaveResponseLost.sessionId}`))
  })
  const unavailableStorageSession = newSession()
  await loadSession(unavailableStorageSession)
  await evaluate('window.__originalStorageSetItem=Storage.prototype.setItem; Storage.prototype.setItem=function(key,value){if(this===localStorage)throw new Error("storage unavailable");return window.__originalStorageSetItem.call(this,key,value)}')
  await fill('#interview-answer', '存储不可用时仍可继续文字面试')
  await sleep(450)
  await check('unavailable local draft storage is disclosed without disabling text answering', async () => {
    assert.ok((await text()).includes('本机自动保存不可用'))
    assert.equal(await disabled('#interview-answer'), false)
    assert.equal(await disabled('.answer-tools button'), false)
  })
  await evaluate('Storage.prototype.setItem=window.__originalStorageSetItem; delete window.__originalStorageSetItem')
  const conflictSession = newSession()
  await loadSession(conflictSession)
  await check('a newer draft from another tab prompts instead of overwriting the current input', async () => {
    await fill('#interview-answer', '本标签页的版本')
    await sleep(400)
    const key = await evaluate(`Object.keys(localStorage).find(item => item.includes(${JSON.stringify(conflictSession.sessionId)}))`)
    assert.ok(key, 'current question draft key exists')
    await evaluate(`(() => { const key=${JSON.stringify(key)}; const value=JSON.parse(localStorage.getItem(key)); value.text='另一标签页的版本'; value.updatedAt=Date.now()+1000; window.dispatchEvent(new StorageEvent('storage',{key,newValue:JSON.stringify(value)})) })()`)
    await waitFor(() => visible('[data-testid="draft-load-other"]'), 'conflict prompt')
    await testid('draft-load-other')
    assert.equal(await evaluate('document.querySelector("#interview-answer").value'), '另一标签页的版本')
  })
  await check('question speech sends only question ID route without answers or contracts', async () => { const speech = calls.filter(call=>call.path.includes('/questions/') && call.path.endsWith('/speech')); assert.ok(speech.length); assert.ok(speech.every(call=>call.bytes === 0 && /^\/api\/interviews\/[^/]+\/questions\/[^/]+\/speech$/.test(call.path))) })
  await check('material library entry reuses the stored source without uploading it again', async () => {
    const before = calls.length
    await navigate(`${base}/materials`)
    await waitFor(() => visible('.material-item'), 'material library entry')
    await button('用这份简历练习')
    await waitFor(() => visible('.resume-preview'), 'selected material preview')
    assert.ok(calls.slice(before).some(call => call.method === 'GET' && call.path === '/api/materials/local-material'))
    assert.ok(calls.slice(before).some(call => call.path === '/api/interviews/source/preview'))
    assert.equal(calls.slice(before).filter(call => call.path === '/api/materials' && call.method === 'POST').length, 0)
    await evaluate(`document.querySelectorAll('select')[0].value='FULL'; document.querySelectorAll('select')[0].dispatchEvent(new Event('change',{bubbles:true}))`)
  await button('生成计划预览')
  await waitFor(() => visible('.interview-plan'), 'ready plan preview')
  await check('FULL plan preview shows coverage, specialty and follow-up budget before confirmation', async () => { const page = await text(); assert.ok(page.includes('岗位专项')); assert.ok(page.includes('项目成果')); assert.ok(page.includes('12')) })
  await button('确认计划并开始')
    await waitFor(() => visible('#interview-answer'), 'material based interview start')
    assert.deepEqual(calls.findLast(call => call.path === '/api/interviews/plans' && call.method === 'POST').json.source,
      { kind: 'MATERIAL', id: 'local-material' })
  })
  await check('saved resume version entry previews the version without running resume extraction', async () => {
    const before = calls.length
    await navigate(`${base}/resume`)
    await waitFor(() => visible('.version-item'), 'saved resume version')
    await button('用此版本练习')
    await waitFor(() => visible('.resume-preview'), 'saved version preview')
    assert.ok(calls.slice(before).some(call => call.path === '/api/resume/versions/version-1' && call.method === 'GET'))
    assert.ok(calls.slice(before).some(call => call.path === '/api/interviews/source/preview'))
    assert.equal(calls.slice(before).filter(call => call.path === '/api/materials' && call.method === 'POST').length, 0)
    await evaluate(`document.querySelectorAll('select')[0].value='FULL'; document.querySelectorAll('select')[0].dispatchEvent(new Event('change',{bubbles:true}))`)
  await button('生成计划预览')
  await waitFor(() => visible('.interview-plan'), 'ready plan preview')
  await check('FULL plan preview shows coverage, specialty and follow-up budget before confirmation', async () => { const page = await text(); assert.ok(page.includes('岗位专项')); assert.ok(page.includes('项目成果')); assert.ok(page.includes('12')) })
  await button('确认计划并开始')
    await waitFor(() => visible('#interview-answer'), 'version based interview start')
    assert.deepEqual(calls.findLast(call => call.path === '/api/interviews/plans' && call.method === 'POST').json.source,
      { kind: 'RESUME_VERSION', id: 'version-1' })
  })
  await check('job entry loads full JD into editable setup while direct entry remains available', async () => {
    await navigate(`${base}/jobs`)
    await evaluate(`Object.keys(sessionStorage).filter(key=>key.startsWith('career-studio-interview-setup-v1|')).forEach(key=>sessionStorage.removeItem(key))`)
    await waitFor(() => visible('.job-card'), 'job results')
    await button('用这个岗位练习面试')
    await waitFor(() => visible('.setup-grid'), 'job based setup')
    await waitFor(async () => (await evaluate('document.querySelector(".jd-field textarea")?.value'))?.startsWith('完整岗位描述：'), 'full job description loaded')
    const jd = await evaluate('document.querySelector(".jd-field textarea").value')
    assert.ok(jd.startsWith('完整岗位描述：') && jd.length > 160, 'full JD should be kept, not the card summary')
    assert.ok(calls.some(call => call.method === 'GET' && call.path === '/api/jobs/job-1'))
    assert.equal(await disabled('.interview-main .button-primary'), true, 'job alone must not select an old resume version')
    await evaluate(`Object.keys(sessionStorage).filter(key=>key.startsWith('career-studio-interview-setup-v1|')).forEach(key=>sessionStorage.removeItem(key))`)
    await navigate(`${base}/interview?intent=new`)
    await waitFor(() => visible('.setup-grid'), 'direct interview setup')
    assert.equal(await visible('.resume-preview'), false)
    assert.equal(await disabled('.interview-main .button-primary'), true)
  })
  await check('QUICK saves each answer and waits for explicit next-question choice through completion', async () => {
    await evaluate(`document.querySelector('.paste-panel').open=true`)
    await fill('#resume-paste', material.text)
    await button('使用这份文本')
    await waitFor(() => visible('.resume-preview'), 'quick material preview')
    const selects = await evaluate(`Array.from(document.querySelectorAll('.setup-grid select')).map(s=>s.options.length)`)
    await evaluate(`(() => { const selects=document.querySelectorAll('.setup-grid select'); selects[0].value='QUICK'; selects[0].dispatchEvent(new Event('change',{bubbles:true})); const specialty=document.querySelectorAll('.setup-grid select')[4]; specialty.value='NONE'; specialty.dispatchEvent(new Event('change',{bubbles:true})) })()`)
    await button('生成计划预览'); await waitFor(() => visible('.interview-plan'), 'quick plan preview')
    assert.match(await text(), /主问题：\s*5 道/)
    await button('确认计划并开始'); await waitFor(() => visible('#interview-answer'), 'quick opening question')
    assert.ok(await evaluate(`document.querySelector('[data-testid="finish-interview"]') && document.querySelector('.interview-progress b').textContent`))
    const generationBaseline = calls.filter(c => c.path.endsWith('/generations')).length
    for (let i = 0; i < 5; i++) {
      if (i === 0) {
        const sessionId = [...sessions.keys()].at(-1)
        const questionId = sessions.get(sessionId).currentQuestion.questionId
        await fill('#interview-answer', '跳过后保留的本题草稿')
        await sleep(350)
        await testid('skip-question')
        await testid('skip-keep-draft')
        await waitFor(() => visible('[data-testid="quick-next-question"]'), 'QUICK skip waits for explicit next action')
        const keptDraft = await evaluate(`(() => { const key=Object.keys(localStorage).find(k=>k.startsWith('career-studio-draft-v1|')&&k.endsWith(${JSON.stringify(`|${sessionId}|${questionId}`)})); return key ? localStorage.getItem(key) : null })()`)
        assert.equal(JSON.parse(keptDraft).text, '跳过后保留的本题草稿')
        assert.equal(calls.filter(c => c.path.endsWith('/generations')).length, generationBaseline, 'QUICK skip must wait before generating')
        await button('继续生成下一题')
        await waitFor(() => visible('#interview-answer'), 'question after QUICK skip')
        continue
      }
      await fill('#interview-answer', `快速练习回答 ${i + 1}`)
      await button('提交回答')
      await waitFor(() => visible('[data-testid="quick-next-question"]'), 'explicit quick next action')
      assert.equal(calls.filter(c => c.path.endsWith('/generations')).length, generationBaseline + i, 'saving an answer must not auto-generate')
      if (i === 1 || i === 4) {
        await testid('quick-view-feedback')
        await waitFor(async () => (await text()).includes('补充真实统计周期和对照范围。'), 'QUICK evidence-backed teaching feedback')
        assert.ok((await text()).includes(`快速练习回答 ${i + 1}`))
        if (i === 1) {
          await button('重答此题')
          await waitFor(async () => (await text()).includes('首答（保留作比较）'), 'QUICK root re-answer entry')
          await fill('[data-testid="interview-attempt"] textarea', '快速重答版本：补充测量周期和对照范围。')
          await button('保存并评估重答')
          await waitFor(async () => (await text()).includes('重答补充了测量方式。'), 'QUICK re-answer assessment')
          assert.ok((await text()).includes('快速练习回答 2'))
        }
      }
      await button('继续生成下一题')
      if (i < 4) await waitFor(() => visible('#interview-answer'), 'next quick question')
    }
    await waitFor(() => visible('[data-testid="view-report"]'), 'quick completion')
    assert.ok((await text()).includes('查看报告'))
  })
  await check('FULL skip immediately generates the next question without counting a completed answer', async () => {
    await navigate(`${base}/interview?intent=new`)
    await evaluate(`document.querySelector('.paste-panel').open=true`)
    await fill('#resume-paste', material.text)
    await button('使用这份文本')
    await waitFor(() => visible('.resume-preview'), 'FULL skip material preview')
    await evaluate(`(() => { const mode=document.querySelectorAll('.setup-grid select')[0]; mode.value='FULL'; mode.dispatchEvent(new Event('change',{bubbles:true})) })()`)
    await waitFor(async () => !(await disabled('.interview-setup > button.button-ghost')), 'FULL plan preparation unlocked')
    await button('生成计划预览'); await waitFor(() => visible('.interview-plan'), 'FULL skip plan preview')
    await waitFor(async () => !(await disabled('.interview-setup > button.button-primary')), 'FULL plan confirmation unlocked')
    await button('确认计划并开始'); await waitFor(() => visible('#interview-answer'), 'FULL skip opening question')
    const sessionId = [...sessions.keys()].at(-1)
    const questionId = sessions.get(sessionId).currentQuestion.questionId
    const before = calls.filter(c => c.path.endsWith('/generations')).length
    const answersBefore = turns()
    await fill('#interview-answer', '选择删除的本题草稿')
    await sleep(350)
    await testid('skip-question'); await testid('skip-discard-draft')
    await waitFor(() => calls.some(call => call.path.endsWith('/skip')), 'FULL skip action request')
    await waitFor(() => calls.filter(c => c.path.endsWith('/generations')).length === before + 1, 'FULL skip NEXT generation request')
    await waitFor(() => visible('#interview-answer'), 'FULL skip generated next question')
    assert.equal(calls.filter(c => c.path.endsWith('/generations')).length, before + 1)
    assert.equal(turns(), answersBefore, 'skipping must not save a fabricated answer')
    assert.equal(await evaluate(`Object.keys(localStorage).some(k=>k.startsWith('career-studio-draft-v1|')&&k.endsWith(${JSON.stringify(`|${sessionId}|${questionId}`)}))`), false)
    const state = sessions.get(sessionId)
    assert.equal(state.progress.answeredRoots, 0)
    assert.equal(state.progress.skippedRoots, 1)
    if (state.questionMetadata[state.currentQuestion.questionId].kind === 'FOLLOW_UP') {
      await fill('#interview-answer', '追问回答')
      await button('提交回答')
      await waitFor(() => state.currentQuestion && state.questionMetadata[state.currentQuestion.questionId].kind === 'MAIN', 'next root question after follow-up')
    }
    const sourceQuestion = state.currentQuestion.questionId
    const sourceIndex = state.questionMetadata[sourceQuestion].mainQuestionIndex
    await waitFor(() => visible('#interview-answer'), 'replacement source answer input')
    await fill('#interview-answer', '我的草稿只针对原来的经历')
    await sleep(350)
    await testid('replace-question')
    await fill('textarea[aria-label="不适用原因"]', '这段经历由其他团队负责，我没有参与。')
    await testid('confirm-replacement')
    await waitFor(() => state.currentQuestion?.questionId !== sourceQuestion, 'replacement question response')
    assert.ok(state.withdrawnQuestionIds.includes(sourceQuestion))
    assert.equal(state.questionMetadata[state.currentQuestion.questionId].mainQuestionIndex, sourceIndex)
    assert.equal(state.turns.find(turn => turn.questionId === sourceQuestion).answer, undefined)
    assert.ok(state.replacements[sourceQuestion].reason.includes('没有参与'))
    assert.ok((await text()).includes('原题草稿仍按原题编号保存'))
    const archivedDraft = await evaluate(`(() => { const key=Object.keys(localStorage).find(k=>k.startsWith('career-studio-draft-v1|')&&k.endsWith(${JSON.stringify(`|${sessionId}|${sourceQuestion}`)})); return key ? JSON.parse(localStorage.getItem(key)).text : null })()`)
    assert.equal(archivedDraft, '我的草稿只针对原来的经历')
    await waitFor(async () => (await text()).includes('已按你的说明撤下，不计入评分'), 'withdrawn source message label')
  })
  await check('history lists, delete confirmation, refresh and logout draft cleanup', async () => {
    await send('Page.navigate', { url: `${base}/interview/history` })
    await waitFor(() => evaluate(`document.querySelectorAll('.history-card').length > 0`), 'interview history list')
    const info = JSON.parse(await evaluate(`JSON.stringify({ cards:document.querySelectorAll('.history-card').length,
      hasExpiry:document.body.innerText.includes('保留至'), hasContinue:[...document.querySelectorAll('.history-card')].some(c=>c.innerText.includes('进行中')&&c.innerText.includes('继续面试')),
      hasReport:[...document.querySelectorAll('.history-card')].some(c=>c.innerText.includes('查看报告')||c.innerText.includes('查看记录')),
      hasDelete:[...document.querySelectorAll('.history-card')].some(c=>c.innerText.includes('删除')) })`))
    assert.ok(info.cards > 0); assert.ok(info.hasExpiry); assert.ok(info.hasContinue); assert.ok(info.hasReport); assert.ok(info.hasDelete)
    assert.ok(calls.some(call => call.method === 'GET' && call.path === '/api/interviews'))
    const before = info.cards, previousDialogCount = dialogs.length
    acceptNextDialog = true
    await click('.history-card button.danger')
    await waitFor(() => dialogs.length === previousDialogCount + 1, 'history delete confirmation dialog')
    assert.equal(dialogs.at(-1).type, 'confirm')
    assert.ok(dialogs.at(-1).message.includes('删除'))
    await dialogHandling
    await waitFor(() => calls.some(call => call.method === 'DELETE' && /^\/api\/interviews\/[^/]+$/.test(call.path)), 'history delete request')
    await waitFor(() => evaluate(`document.querySelectorAll('.history-card').length === ${before - 1}`), 'deleted history card disappears')
    await send('Page.reload')
    await waitFor(() => evaluate(`document.querySelectorAll('.history-card').length === ${before - 1}`), 'history remains consistent after refresh')
    const activeDraftKey = await evaluate(`(() => {
      const base = localStorage.getItem('resume-helper-local-api-base-url') || ''
      return 'career-studio-draft-v1|' + base + '|demo-user|logout-session|q1'
    })()`)
    const otherScopeDraftKey = activeDraftKey.replace('|demo-user|', '|another-demo-user|')
    await evaluate(`localStorage.setItem(${JSON.stringify(activeDraftKey)}, JSON.stringify({sessionId:'logout-session',questionId:'q1',text:'当前用户草稿',previewText:'',updatedAt:Date.now()})); localStorage.setItem(${JSON.stringify(otherScopeDraftKey)}, JSON.stringify({sessionId:'other-session',questionId:'q1',text:'其他范围草稿',previewText:'',updatedAt:Date.now()}))`)
    await button('退出')
    await waitFor(() => evaluate(`location.pathname === '/login'`), 'logout returns to login')
    assert.equal(await evaluate(`localStorage.getItem(${JSON.stringify(activeDraftKey)})`), null)
    assert.ok(await evaluate(`localStorage.getItem(${JSON.stringify(otherScopeDraftKey)})`), 'logout must not clear another user/API scope')
  })
  await check('application API calls carry common demo identity; backend probe sends no credentials', async () => assert.ok(calls.length > 20 && calls.filter(c => c.path !== '/api/jobs/sources').every(c => c.headers['x-user-id'] === 'demo-user')))
  await check('text settings shared into report; speech carries no model/provider credentials', async () => {
    const reports = calls.filter(c => /\/report(?:\/retry)?$/.test(c.path))
    assert.ok(reports.length > 0 && reports.every(c => c.headers['x-ai-api-key'] === 'local-browser-test' && c.headers['x-ai-model'] === 'local-model'))
    const speech = calls.filter(c => c.path.includes('/interview-speech/') || c.path.endsWith('/speech') || c.path.endsWith('/transcriptions'))
    assert.ok(speech.length > 0 && speech.every(c => !Object.keys(c.headers).some(k => k.startsWith('x-ai-') || k.startsWith('x-api-key'))))
    assert.ok(calls.filter(c => !c.path.includes('/interview-speech/') && !c.path.endsWith('/speech') && !c.path.endsWith('/transcriptions')).every(c => !c.headers['x-speech-api-key']))
    assert.ok(!await evaluate('location.href.includes("local-browser-test")'))
  })
  await check('no uncaught browser or mock-server errors', async () => assert.deepEqual(errors, []))
  for (const name of ['failure.json', 'failure.png', 'process.log']) await rm(join(artifacts, name), { force: true })
  console.log(`Browser acceptance passed: ${checks.length} checks. Artifacts: ${artifacts}`)
}
try { await main() }
catch (cause) {
  console.error(cause.stack)
  await writeFile(join(artifacts, 'process.log'), children.map(proc => proc.output()).join('\n'))
  if (snapshot) { try { await writeFile(join(artifacts, 'failure.json'), JSON.stringify(await snapshot(), null, 2)); const shot = await send('Page.captureScreenshot', { format: 'png' }); await writeFile(join(artifacts, 'failure.png'), Buffer.from(shot.data, 'base64')) } catch {} }
  process.exitCode = 1
} finally {
  await writeFile(join(artifacts, 'validation.json'), JSON.stringify({ checks, errors, dialogs, browserRequests, calls: calls.map(({ headers, ...call }) => ({ ...call, identity: headers['x-user-id'], hasTextSettings: Boolean(headers['x-ai-api-key']) })) }, null, 2))
  ws?.close()
  const alive = proc => proc.exitCode == null && proc.signalCode == null
  for (const proc of children) if (alive(proc)) proc.kill('SIGTERM')
  for (const proc of children) if (alive(proc)) {
    await Promise.race([new Promise(resolve => proc.once('exit', resolve)), sleep(2000)])
    if (alive(proc)) { proc.kill('SIGKILL'); await Promise.race([new Promise(resolve => proc.once('exit', resolve)), sleep(1000)]) }
  }
  for (const socket of sockets) socket.destroy()
  if (server?.listening) await new Promise(resolve => server.close(resolve))
  // Retain default artifacts but always remove the temporary Chrome profile/audio.
  if (process.env.CHECK_ARTIFACT_DIR) await rm(work, { recursive: true, force: true })
  else { await rm(join(work, 'profile'), { recursive: true, force: true }); await rm(join(work, 'question.mp3'), { force: true }) }
}
