import { getAuthUser } from './authSession.js'

/**
 * 求职画像的本地缓存。
 *
 * 这里只缓存「当前登录用户」的资料，不再提供演示画像兜底：
 * 新账号在后端没有资料时，页面应该显示空状态，而不是别人的学校/技能/经历。
 */

const PROFILE_KEY_PREFIX = 'resume-helper-profile:'
const LEGACY_PROFILE_KEY = 'resume-helper-profile'

export function emptyProfile() {
  return {
    userId: '',
    targetTitle: '',
    targetCities: [],
    yearsOfExperience: 0,
    education: '',
    educations: [],
    skills: [],
    industries: [],
    expectedSalary: '',
    summary: '',
    experiences: []
  }
}

function currentUserId() {
  return getAuthUser()?.id || ''
}

function profileKey(userId) {
  return `${PROFILE_KEY_PREFIX}${userId || 'anonymous'}`
}

function normalize(profile = {}) {
  const base = emptyProfile()
  return {
    ...base,
    ...profile,
    userId: profile.userId || '',
    targetCities: Array.isArray(profile.targetCities) ? profile.targetCities : base.targetCities,
    educations: Array.isArray(profile.educations) ? profile.educations : base.educations,
    skills: Array.isArray(profile.skills) ? profile.skills : base.skills,
    industries: Array.isArray(profile.industries) ? profile.industries : base.industries,
    experiences: Array.isArray(profile.experiences) ? profile.experiences : base.experiences,
    yearsOfExperience: Number(profile.yearsOfExperience) || 0
  }
}

/**
 * 取当前用户的求职画像。
 *
 * 只读当前用户自己的 key；旧的全局 key 会直接清掉，避免 A 账号的资料
 * 被 B 账号读出来。后端才是资料的权威来源，本地缓存只用于岗位页快速读取。
 */
export function getProfile() {
  const userId = currentUserId()
  try {
    window.localStorage.removeItem(LEGACY_PROFILE_KEY)
  } catch {
    // 存储不可用时仍然返回空画像，不阻塞页面。
  }

  try {
    const saved = window.localStorage.getItem(profileKey(userId))
    if (saved) {
      const parsed = JSON.parse(saved)
      if (parsed && typeof parsed === 'object') {
        return normalize({ ...parsed, userId: parsed.userId || userId })
      }
    }
  } catch {
    // 坏数据按空画像处理。
  }
  return emptyProfile()
}

/** 粗略的画像完整度，只用于工作台/侧栏的进度展示。 */
export function profileCompleteness(profile = getProfile()) {
  const value = profile || {}
  const checks = [
    Boolean(value.targetTitle),
    Array.isArray(value.targetCities) && value.targetCities.length > 0,
    Boolean(value.education),
    Array.isArray(value.skills) && value.skills.length > 0,
    Boolean(value.summary),
    Array.isArray(value.experiences) && value.experiences.length > 0,
    Array.isArray(value.educations) && value.educations.length > 0
  ]
  return Math.round(checks.filter(Boolean).length / checks.length * 100)
}

/** 保存当前用户的画像缓存；实际资料仍由后端 /api/profile 保存。 */
export function saveProfile(profile) {
  const userId = currentUserId()
  const payload = normalize({ ...profile, userId: userId || profile?.userId || '' })
  try {
    window.localStorage.removeItem(LEGACY_PROFILE_KEY)
    window.localStorage.setItem(profileKey(userId), JSON.stringify(payload))
    globalThis.dispatchEvent?.(new Event('profile-updated'))
    return true
  } catch {
    return false
  }
}

/** 清掉当前用户的本地画像缓存。 */
export function clearProfile() {
  const userId = currentUserId()
  try {
    window.localStorage.removeItem(profileKey(userId))
    window.localStorage.removeItem(LEGACY_PROFILE_KEY)
    globalThis.dispatchEvent?.(new Event('profile-updated'))
  } catch {
    // 忽略
  }
}
