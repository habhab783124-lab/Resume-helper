/**
 * 岗位匹配用的用户画像。
 *
 * ## 这些数据是从哪来的
 * 原本写死在 `views/Profile.vue` 的模板里（演示数据）。这里把它抽成结构化对象，
 * 目的是让岗位模块在「材料解析」（功能一）完成之前就能跑出**真实的匹配度**，
 * 而不是给一个假分数。
 *
 * ## 和功能一的衔接方式（重要）
 * `getProfile()` 读取时会先看 `localStorage`。等功能一做完，它只需要调用
 * `saveProfile(解析并确认后的资料)`，岗位页就会自动用上真实画像，**本文件不需要改**。
 * 字段名就是后端 `ProfileDto` 的字段名，一一对应。
 */

/** 字段名与 backend 的 ProfileDto 完全一致，不要随意改名。 */
const PROFILE_KEY = 'resume-helper-profile'

/** 演示画像：与 Profile.vue 页面上展示的内容保持一致。 */
export const DEMO_PROFILE = Object.freeze({
  userId: 'demo-user',
  targetTitle: 'Java 后端开发工程师',
  targetCities: ['上海'],
  // Profile.vue 上写的是「全职 · 应届校招」，2026.06 毕业 ⇒ 0 年经验
  yearsOfExperience: 0,
  education: '本科',
  // 教育经历是给人看的（学校 / 专业 / 时间），和上面的 education（学历层次）分开：
  // 后者是岗位匹配的打分维度，前者用于个人资料页展示。
  educations: ['上海理工大学 · 本科 · 计算机科学与技术 · 2022.09—2026.06'],
  skills: ['Java', 'Spring Boot', 'MySQL', 'Redis', 'Git'],
  industries: ['互联网'],
  // Profile.vue 上没有展示期望薪资，这里给一个符合上海 Java 应届行情的演示值。
  // 它只影响薪资维度（满分 5 分），对排序影响很小。
  expectedSalary: '15-22K',
  summary: '上海理工大学计算机科学与技术专业 2026 届本科毕业生，目标方向 Java 后端开发。',
  experiences: [
    '校园活动管理平台（后端开发 · 个人项目，2025.03—2025.07）：' +
      '基于 Spring Boot 构建校园活动管理服务，完成用户认证、活动发布、报名管理等核心模块。' +
      '使用 Redis 优化热门活动查询，通过分页与索引调整提升列表接口响应效率。'
  ]
})

/**
 * 取当前用户的求职画像。
 * 优先用功能一保存下来的真实资料，没有就退回演示画像。
 */
export function getProfile() {
  try {
    const saved = window.localStorage.getItem(PROFILE_KEY)
    if (saved) {
      const parsed = JSON.parse(saved)
      if (parsed && typeof parsed === 'object') {
        // 与演示画像合并，避免功能一漏字段时岗位页因为 undefined 报错
        return { ...DEMO_PROFILE, ...parsed }
      }
    }
  } catch {
    // 存储不可用或 JSON 坏了，直接用演示画像，不要因此让页面白屏
  }
  return { ...DEMO_PROFILE }
}

/** 功能一（材料解析）确认完资料后调用它，岗位页就会自动用上真实画像。 */
export function saveProfile(profile) {
  try {
    window.localStorage.setItem(PROFILE_KEY, JSON.stringify(profile))
    return true
  } catch {
    return false
  }
}

/** 清掉真实画像，退回演示数据。调试用。 */
export function clearProfile() {
  try {
    window.localStorage.removeItem(PROFILE_KEY)
  } catch {
    // 忽略
  }
}
