import test from 'node:test'
import assert from 'node:assert/strict'
import { useInterviewSetup } from './useInterviewSetup.js'

function memoryStorage() {
  const values = new Map()
  return { values, getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key) }
}

test('loads a saved material and previews it without uploading again', async () => {
  const storage = memoryStorage()
  let uploads = 0
  let previews = 0
  const setup = useInterviewSetup({
    materialsApi: { fetchMaterial: async id => ({ material: { id, fileName: 'cv.txt' }, text: '简历原文' }),
      uploadMaterial: async () => { uploads += 1 } },
    resumeApi: {}, jobsApi: {},
    interviewApi: { previewSource: async source => { previews += 1; return { source, sourceLabel: 'cv.txt', resumeText: '简历原文', sourceFingerprint: 'fp' } } },
    storage, scope: { apiBaseUrl: '', userId: 'alice' }
  })
  await setup.loadSource({ kind: 'MATERIAL', id: 'm1' })
  assert.equal(setup.resumePreview.value.resumeText, '简历原文')
  assert.equal(uploads, 0)
  assert.equal(previews, 1)
})

test('loads saved version text via the shared server preview', async () => {
  let fetched = ''
  const setup = useInterviewSetup({ materialsApi: {},
    resumeApi: { fetchResumeVersion: async id => { fetched = id; return { version: { name: 'v1' }, draft: {} } } },
    jobsApi: {}, interviewApi: { previewSource: async source => ({ source, sourceLabel: 'v1', resumeText: '标题\n经历\n- 结果', sourceFingerprint: 'vfp' }) },
    storage: memoryStorage(), scope: { userId: 'alice' } })
  await setup.loadSource({ kind: 'RESUME_VERSION', id: 'v1' })
  assert.equal(fetched, 'v1')
  assert.match(setup.resumePreview.value.resumeText, /经历\n- 结果/)
})

test('loads full job description and allows manual role after job disappears', async () => {
  let available = true
  const setup = useInterviewSetup({ materialsApi: {}, resumeApi: {}, storage: memoryStorage(), scope: { userId: 'alice' },
    jobsApi: { fetchJobDetail: async () => { if (!available) throw Object.assign(new Error('missing'), { status: 404 });
      return { title: 'Data Analyst', description: 'Complete job description\nwith requirements' } } } })
  await setup.loadJob('j1')
  assert.equal(setup.targetRole.value, 'Data Analyst')
  assert.equal(setup.jobDescription.value, 'Complete job description\nwith requirements')
  available = false
  await assert.rejects(setup.loadJob('j1'))
  assert.equal(setup.targetRole.value, 'Data Analyst')
  setup.targetRole.value = '手动填写的岗位'
  assert.equal(setup.targetRole.value, '手动填写的岗位')
})

test('restores preparation after refresh and isolates it by user and API base', async () => {
  const storage = memoryStorage()
  const first = useInterviewSetup({ materialsApi: {}, resumeApi: {}, jobsApi: {}, storage,
    scope: { apiBaseUrl: 'https://a.example', userId: 'alice' } })
  first.source.value = { kind: 'RESUME_VERSION', id: 'v7' }
  first.targetRole.value = 'Backend'
  first.jobDescription.value = 'Full JD'
  first.preferences.value = { mode: 'QUICK', mainCount: 3 }
  first.savePreparation()
  const sameScope = useInterviewSetup({ materialsApi: {}, resumeApi: {}, jobsApi: {}, storage,
    scope: { apiBaseUrl: 'https://a.example', userId: 'alice' } })
  assert.equal(sameScope.restorePreparation(), true)
  assert.equal(sameScope.jobDescription.value, 'Full JD')
  assert.deepEqual(sameScope.preferences.value, { mode: 'QUICK', mainCount: 3, level: 'STANDARD', careerStage: 'CAMPUS', specialtyPreference: 'AUTO' })
  const otherUser = useInterviewSetup({ materialsApi: {}, resumeApi: {}, jobsApi: {}, storage,
    scope: { apiBaseUrl: 'https://a.example', userId: 'bob' } })
  assert.equal(otherUser.restorePreparation(), false)
  const otherApi = useInterviewSetup({ materialsApi: {}, resumeApi: {}, jobsApi: {}, storage,
    scope: { apiBaseUrl: 'https://b.example', userId: 'alice' } })
  assert.equal(otherApi.restorePreparation(), false)
})

test('saved preparation contains no file, API key, recording, or resume text', () => {
  const storage = memoryStorage()
  const setup = useInterviewSetup({ materialsApi: {}, resumeApi: {}, jobsApi: {}, storage,
    scope: { apiBaseUrl: '', userId: 'alice' } })
  setup.source.value = { kind: 'MATERIAL', id: 'm1' }
  setup.resumePreview.value = { resumeText: 'secret resume body', sourceFingerprint: 'fp' }
  setup.savePreparation()
  const saved = [...storage.values.values()][0]
  assert.doesNotMatch(saved, /secret resume body|sourceFingerprint|File|apiKey|recording/i)
})

test('plan preview waits for generation and becomes unusable when preferences change', async () => {
  let release
  let polls = 0
  const setup = useInterviewSetup({ storage: memoryStorage(), scope: { userId: 'alice' },
    materialsApi: { fetchMaterial: async id => ({ material: { id }, text: '简历' }) },
    interviewApi: { previewSource: async source => ({ source, resumeText: '简历', sourceFingerprint: 'fp' }),
      preparePlan: async () => ({ status: 'GENERATING', planId: 'p1' }),
      getPlan: async () => { polls++; if (polls === 1) await new Promise(resolve => { release = resolve }); return { planId: 'p1', planVersion: 1, status: 'READY', plan: { coverage: ['技能'] } } } }
  })
  await setup.loadSource({ kind: 'MATERIAL', id: 'm1' })
  const pending = setup.preparePlan()
  while (!release) await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(setup.planPreview.value, null)
  release()
  await pending
  assert.equal(setup.planCurrent.value, true)
  setup.preferences.value = { ...setup.preferences.value, mode: 'QUICK', mainCount: 3 }
  assert.equal(setup.planCurrent.value, false)
})

test('failed preview is surfaced and duplicate confirmation starts only once', async () => {
  let starts = 0
  let release
  const interviewApi = { previewSource: async source => ({ source, resumeText: '简历' }),
    preparePlan: async () => ({ planId: 'p2', planVersion: 2, status: 'FAILED', failure: '生成失败' }),
    startPlanned: async () => { starts++; await new Promise(resolve => { release = resolve }); return { sessionId: 's1' } } }
  /*
  */
  const setup = useInterviewSetup({ storage: memoryStorage(), scope: { userId: 'alice' },
    materialsApi: { fetchMaterial: async id => ({ material: { id }, text: '简历' }) }, interviewApi })
  await setup.loadSource({ kind: 'MATERIAL', id: 'm1' })
  await setup.preparePlan()
  assert.equal(setup.planError.value, '生成失败')
  interviewApi.startPlanned = async () => { starts++; await new Promise(resolve => { release = resolve }); return { sessionId: 's1' } }
  interviewApi.preparePlan = async () => ({ planId: 'p2', planVersion: 2, status: 'READY', plan: {} })
  await setup.preparePlan()
  const first = setup.confirmPlan()
  const second = await setup.confirmPlan()
  assert.equal(second, null)
  release()
  assert.equal((await first).sessionId, 's1')
  assert.equal(starts, 1)
})

test('switching API scope clears an old preview and prevents confirmation', async () => {
  const storage = memoryStorage()
  let scope = { apiBaseUrl: 'https://a.example', userId: 'alice' }
  const setup = useInterviewSetup({ storage, scope: () => scope,
    materialsApi: { fetchMaterial: async id => ({ material: { id }, text: '简历' }) },
    interviewApi: { previewSource: async source => ({ source, resumeText: '简历' }), preparePlan: async () => ({ planId: 'p', planVersion: 1, status: 'READY', plan: {} }), startPlanned: async () => ({ sessionId: 's' }) } })
  await setup.loadSource({ kind: 'MATERIAL', id: 'm1' }); await setup.preparePlan()
  scope = { apiBaseUrl: 'https://b.example', userId: 'alice' }; setup.switchScope()
  assert.equal(setup.planPreview.value, null)
  assert.equal(await setup.confirmPlan(), null)
})

test('loads a focused practice preview from its plan ID, preserves targets and confirms the same plan', async () => {
  const requests = []
  const plan = { planId: 'focused-plan', planVersion: 1, status: 'READY', expiresAt: '2026-10-17T11:00:00Z',
    plan: { mode: 'QUICK', mainCount: 3, plannedCount: 3, level: 'STANDARD', careerStage: 'CAMPUS',
      specialty: { kind: 'NONE' }, source: { kind: 'MATERIAL', id: 'm1' },
      practiceTargets: [{ issueId: 'q1', title: '补充证据', action: '报告量化结果', sourceQuestionIds: ['q1'] }], sourceSessionId: 'source-1' } }
  const setup = useInterviewSetup({ storage: memoryStorage(), scope: { userId: 'alice' },
    materialsApi: { fetchMaterial: async id => ({ material: { id }, text: '简历' }) },
    interviewApi: { getPlan: async id => { requests.push(['getPlan', id]); return plan },
      previewSource: async source => ({ source, sourceLabel: '简历.txt', resumeText: '简历原文', sourceFingerprint: 'fp' }),
      startPlanned: async body => { requests.push(['start', body]); return { sessionId: 'practice-1' } } } })

  assert.equal(await setup.loadPreparedPlan('focused-plan'), true)
  assert.equal(setup.sourceSessionId.value, 'source-1')
  assert.equal(setup.planPreview.value.plan.practiceTargets[0].title, '补充证据')
  assert.equal(setup.planCurrent.value, true)
  assert.equal((await setup.confirmPlan()).sessionId, 'practice-1')
  assert.equal(requests[1][0], 'start')
  assert.equal(requests[1][1].planId, 'focused-plan')
})

test('startInterview prepares and starts once without exposing a separate plan step', async () => {
  const calls = []
  let release
  const setup = useInterviewSetup({ storage: memoryStorage(), scope: { userId: 'alice' },
    materialsApi: { fetchMaterial: async id => ({ material: { id }, text: '简历' }) },
    interviewApi: { previewSource: async source => ({ source, resumeText: '简历' }),
      preparePlan: async () => { calls.push('prepare'); return { planId: 'p1', planVersion: 1, status: 'READY', plan: {} } },
      startPlanned: async () => { calls.push('start'); await new Promise(resolve => { release = resolve }); return { sessionId: 's1' } } } })
  await setup.loadSource({ kind: 'MATERIAL', id: 'm1' })
  const first = setup.startInterview()
  while (!release) await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(await setup.startInterview(), null)
  release()
  assert.equal((await first).sessionId, 's1')
  assert.deepEqual(calls, ['prepare', 'start'])
  assert.equal(setup.starting.value, false)
})

test('startInterview does not start when automatic plan preparation fails and keeps the chosen source', async () => {
  let starts = 0
  const setup = useInterviewSetup({ storage: memoryStorage(), scope: { userId: 'alice' },
    materialsApi: { fetchMaterial: async id => ({ material: { id }, text: '简历' }) },
    interviewApi: { previewSource: async source => ({ source, resumeText: '简历' }),
      preparePlan: async () => { throw new Error('服务暂不可用') }, startPlanned: async () => { starts++ } } })
  await setup.loadSource({ kind: 'MATERIAL', id: 'm1' })
  assert.equal(await setup.startInterview(), null)
  assert.equal(starts, 0)
  assert.equal(setup.source.value.id, 'm1')
  assert.equal(setup.planError.value, '服务暂不可用')
})


test('an exhausted algorithm pool asks for consent before retrying planned start', async () => {
  const requests = []
  let attempts = 0
  const interviewApi = { previewSource: async source => ({ source, resumeText: '简历' }),
    preparePlan: async () => ({ planId: 'p-repeat', planVersion: 1, status: 'READY', plan: {} }),
    startPlanned: async body => { requests.push(body); if (++attempts === 1) throw new Error('该难度的算法题已全部练习，请勾选确认后允许重新抽题'); return { sessionId: 'repeat-session' } } }
  const setup = useInterviewSetup({ storage: memoryStorage(), scope: { userId: 'alice' },
    materialsApi: { fetchMaterial: async id => ({ material: { id }, text: '简历' }) }, interviewApi })
  await setup.loadSource({ kind: 'MATERIAL', id: 'm1' }); await setup.preparePlan()
  assert.equal(await setup.confirmPlan(), null)
  assert.equal(setup.repeatConsentRequired.value, true)
  assert.equal(requests[0].allowAlgorithmRepeat, false)
  setup.allowAlgorithmRepeat.value = true
  assert.equal((await setup.confirmPlan()).sessionId, 'repeat-session')
  assert.equal(requests[1].allowAlgorithmRepeat, true)
})

test('starting an interview displays a structured plan failure message', async () => {
  let starts = 0
  const setup = useInterviewSetup({ storage: memoryStorage(),
    materialsApi: { fetchMaterial: async id => ({ material: { id }, text: '简历' }) },
    interviewApi: {
      previewSource: async source => ({ source, resumeText: '简历' }),
      preparePlan: async () => ({ status: 'FAILED', failure: { code: 'RATE_LIMITED', message: '大模型调用频率受限，请稍后重试', retryable: true } }),
      startPlanned: async () => { starts++ }
    }
  })
  await setup.loadSource({ kind: 'MATERIAL', id: 'm1' })
  assert.equal(await setup.startInterview(), null)
  assert.equal(setup.planError.value, '大模型调用频率受限，请稍后重试')
  assert.equal(starts, 0)
  assert.equal(setup.source.value.id, 'm1')
})
