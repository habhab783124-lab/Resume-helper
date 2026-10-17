import { computed, ref, watch } from 'vue'

const DEFAULT_PREFERENCES = Object.freeze({ mode: 'QUICK', mainCount: 5, level: 'STANDARD', careerStage: 'CAMPUS', specialtyPreference: 'AUTO' })
const STORAGE_PREFIX = 'career-studio-interview-setup-v1'

function validSource(value) {
  return value && ['MATERIAL', 'RESUME_VERSION'].includes(value.kind)
    && typeof value.id === 'string' && value.id.trim()
    ? { kind: value.kind, id: value.id.trim() } : null
}

function storageKey(scope) {
  const value = typeof scope === 'function' ? scope() : scope
  return `${STORAGE_PREFIX}|${value?.apiBaseUrl || ''}|${value?.userId || 'demo-user'}`
}

export function useInterviewSetup({ materialsApi = {}, resumeApi = {}, jobsApi = {}, interviewApi = {},
  storage = globalThis.sessionStorage, scope = {} } = {}) {
  const source = ref(null)
  const resumePreview = ref(null)
  const targetRole = ref('')
  const jobDescription = ref('')
  const preferences = ref({ ...DEFAULT_PREFERENCES })
  const planPreview = ref(null)
  const planError = ref('')
  const preparingPlan = ref(false)
  const confirmingPlan = ref(false)
  const allowAlgorithmRepeat = ref(false)
  const repeatConsentRequired = ref(false)
  const sourceSessionId = ref('')
  let planFingerprint = ''
  let planRequest = 0
  let restoring = false
  const planCurrent = computed(() => !!planPreview.value && planFingerprint === fingerprint())
  function fingerprint() { return JSON.stringify([source.value, targetRole.value.trim(), jobDescription.value, preferences.value, sourceSessionId.value, (typeof scope === 'function' ? scope() : scope)]) }
  function invalidatePlan() { planRequest++; planPreview.value = null; planError.value = ''; planFingerprint = ''; preparingPlan.value = false; allowAlgorithmRepeat.value = false; repeatConsentRequired.value = false; if (!restoring) sourceSessionId.value = '' }

  function savePreparation() {
    if (restoring || !storage?.setItem) return false
    const value = {
      version: 1,
      source: validSource(source.value),
      targetRole: String(targetRole.value || '').slice(0, 200),
      jobDescription: String(jobDescription.value || '').slice(0, 20000),
      preferences: {
        mode: preferences.value?.mode === 'FULL' ? 'FULL' : 'QUICK',
        mainCount: preferences.value?.mainCount === 3 ? 3 : 5, level: preferences.value?.level || 'STANDARD',
        careerStage: preferences.value?.careerStage || 'CAMPUS', specialtyPreference: preferences.value?.specialtyPreference || 'AUTO'
      }
    }
    try { storage.setItem(storageKey(scope), JSON.stringify(value)); return true } catch { return false }
  }

  function restorePreparation() {
    if (!storage?.getItem) return false
    let value
    try { value = JSON.parse(storage.getItem(storageKey(scope)) || 'null') } catch { return false }
    if (!value || value.version !== 1) return false
    restoring = true
    source.value = validSource(value.source)
    targetRole.value = typeof value.targetRole === 'string' ? value.targetRole.slice(0, 200) : ''
    jobDescription.value = typeof value.jobDescription === 'string' ? value.jobDescription.slice(0, 20000) : ''
    preferences.value = {
      mode: value.preferences?.mode === 'FULL' ? 'FULL' : 'QUICK',
      mainCount: value.preferences?.mainCount === 3 ? 3 : 5, level: value.preferences?.level || 'STANDARD',
      careerStage: value.preferences?.careerStage || 'CAMPUS', specialtyPreference: value.preferences?.specialtyPreference || 'AUTO'
    }
    resumePreview.value = null
    invalidatePlan()
    restoring = false
    return true
  }

  function clearPreparation() {
    sourceSessionId.value = ''
    source.value = null
    resumePreview.value = null
    targetRole.value = ''
    jobDescription.value = ''
    preferences.value = { ...DEFAULT_PREFERENCES }
    invalidatePlan()
    try { storage?.removeItem?.(storageKey(scope)) } catch { /* Storage is optional. */ }
  }

  function switchScope() {
    restoring = true
    source.value = null
    resumePreview.value = null
    targetRole.value = ''
    jobDescription.value = ''
    preferences.value = { ...DEFAULT_PREFERENCES }
    sourceSessionId.value = ''
    invalidatePlan()
    restoring = false
    return restorePreparation()
  }

  async function loadSource(value) {
    const next = validSource(value)
    if (!next) throw new Error('简历来源无效，请重新选择。')
    let fallback
    if (next.kind === 'MATERIAL') {
      const detail = await materialsApi.fetchMaterial(next.id)
      fallback = { source: next, sourceLabel: detail?.material?.fileName || '已整理简历', resumeText: detail?.text || '' }
    } else {
      const detail = await resumeApi.fetchResumeVersion(next.id)
      fallback = { source: next, sourceLabel: detail?.version?.name || detail?.name || '已保存版本', resumeText: '' }
    }
    const preview = interviewApi.previewSource
      ? await interviewApi.previewSource(next)
      : fallback
    if (!preview || typeof preview.resumeText !== 'string' || !preview.resumeText.trim()) {
      throw new Error('这份简历没有可用于面试的文字内容。')
    }
    invalidatePlan()
    source.value = next
    resumePreview.value = { ...preview, source: next }
    savePreparation()
    return resumePreview.value
  }

  async function loadJob(id) {
    if (typeof id !== 'string' || !id.trim()) throw new Error('岗位信息无效。')
    const detail = await jobsApi.fetchJobDetail(id, (typeof scope === 'function' ? scope() : scope)?.userId)
    const job = detail?.job || detail
    const title = job?.title || job?.jobTitle || ''
    const description = job?.description || job?.jobDescription || job?.jd || ''
    if (title) targetRole.value = String(title).slice(0, 200)
    if (typeof description === 'string') jobDescription.value = description.slice(0, 20000)
    savePreparation()
    return job
  }

  async function preparePlan() {
    invalidatePlan(); const request = planRequest; planError.value = '';
    if (!source.value || !resumePreview.value?.resumeText?.trim()) { planError.value = '请先选择并确认简历。'; return null }
    if (!interviewApi.preparePlan) { planError.value = '当前服务暂不支持计划预览。'; return null }
    preparingPlan.value = true
    try {
      const p = preferences.value
      let value = await interviewApi.preparePlan({ source: source.value, targetRole: targetRole.value.trim(), jobDescription: jobDescription.value, careerStage: p.careerStage, level: p.level, mode: p.mode, mainCount: p.mode === 'QUICK' ? p.mainCount : 12, specialtyPreference: p.specialtyPreference, requestId: globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}` })
      while (value?.status === 'GENERATING' && value.planId && request === planRequest) { await new Promise(resolve => setTimeout(resolve, 700)); value = await interviewApi.getPlan(value.planId) }
      if (request !== planRequest) return null
      if (value?.status !== 'READY') throw new Error(value?.failure || '计划生成失败，请重试。')
      planPreview.value = value; planFingerprint = fingerprint(); return value
    } catch (cause) { if (request === planRequest) { planError.value = cause.message || '计划生成失败，请重试。'; planPreview.value = null } return null }
    finally { if (request === planRequest) preparingPlan.value = false }
  }
  async function loadPreparedPlan(planId, originatingSessionId = '') {
    invalidatePlan()
    const request = planRequest
    if (!planId || !interviewApi.getPlan || !interviewApi.previewSource) {
      planError.value = '练习计划链接无效，请从报告重新选择重点。'
      return false
    }
    preparingPlan.value = true
    try {
      let value = await interviewApi.getPlan(planId)
      while (value?.status === 'GENERATING' && request === planRequest) {
        await new Promise(resolve => setTimeout(resolve, 700))
        value = await interviewApi.getPlan(planId)
      }
      if (request !== planRequest) return false
      if (value?.status !== 'READY' || !value.plan) throw new Error(value?.failure?.message || value?.failure || '练习计划已失效，请回到报告重新选择。')
      const selectedSource = validSource(value.plan.source)
      if (!selectedSource) throw new Error('计划中的简历来源无效，请回到报告重新选择重点。')
      const preview = await interviewApi.previewSource(selectedSource)
      if (request !== planRequest) return false
      if (value.plan.sourceFingerprint && preview.sourceFingerprint !== value.plan.sourceFingerprint)
        throw new Error('简历来源已变化，请回到报告重新选择练习重点。')
      restoring = true
      source.value = selectedSource
      resumePreview.value = { ...preview, source: selectedSource }
      targetRole.value = value.plan.targetRole || ''
      jobDescription.value = value.plan.jobDescription || ''
      preferences.value = { mode: value.plan.mode || 'QUICK', mainCount: value.plan.mainCount || 3,
        level: value.plan.level || 'STANDARD', careerStage: value.plan.careerStage || 'CAMPUS',
        specialtyPreference: value.plan.specialty?.kind === 'NONE' ? 'NONE' : 'AUTO' }
      sourceSessionId.value = value.plan.sourceSessionId || originatingSessionId
      restoring = false
      planPreview.value = value
      planFingerprint = fingerprint()
      savePreparation()
      return true
    } catch (cause) {
      restoring = false
      if (request === planRequest) planError.value = cause.message || '练习计划暂时无法读取。'
      return false
    } finally { if (request === planRequest) preparingPlan.value = false }
  }
  async function confirmPlan() {
    if (confirmingPlan.value || !planCurrent.value || planPreview.value?.status !== 'READY') return null
    confirmingPlan.value = true
    try { const value = await interviewApi.startPlanned({ planId: planPreview.value.planId, planVersion: planPreview.value.version ?? planPreview.value.planVersion, allowAlgorithmRepeat: allowAlgorithmRepeat.value, requestId: globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}` }); invalidatePlan(); return value }
    catch (cause) { planError.value = cause.message || '确认计划失败，请重试。'; repeatConsentRequired.value = /已全部练习/.test(planError.value); return null }
    finally { confirmingPlan.value = false }
  }
  watch([source, targetRole, jobDescription, preferences], () => { if (restoring) return; invalidatePlan(); savePreparation() }, { deep: true, flush: 'sync' })

  return { source, sourceSessionId, resumePreview, targetRole, jobDescription, preferences, planPreview, planError, preparingPlan, confirmingPlan, allowAlgorithmRepeat, repeatConsentRequired, planCurrent, preparePlan, loadPreparedPlan, confirmPlan,
    loadSource, loadJob, savePreparation, restorePreparation, clearPreparation, switchScope }
}
