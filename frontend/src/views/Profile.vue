<template>
<section class="page active" id="page-profile" data-title="个人资料">
          <div class="page-intro"><div><div class="eyebrow"><span class="eyebrow-line"></span>PROFILE / 01</div><h1>个人资料</h1><p>把你的经历整理好，让每一次申请都有清晰的起点。</p></div><button class="button button-primary" @click="toggleEdit"><svg><use href="#i-edit"/></svg>{{ editing ? '取消编辑' : '编辑资料' }}</button></div>

          <p v-if="error" class="notice is-error" role="alert">{{ error }}</p>
          <p v-else-if="notice" class="notice" role="status">{{ notice }}</p>

          <div class="profile-banner">
            <div class="profile-avatar">{{ avatarChar }}</div>
            <div class="profile-banner-info">
              <span class="profile-kicker">求职者档案 · {{ sourceLabel }}</span>
              <h2>{{ profile.name || displayName }}</h2>
              <p><template v-for="(part, index) in kickerParts" :key="part"><i v-if="index">·</i>{{ part }}</template></p>
            </div>
            <div class="profile-completion"><span>档案完整度</span><strong>{{ completeness }}%</strong><div class="tiny-progress"><i :style="{ width: completeness + '%' }"></i></div></div>
          </div>

          <div class="profile-layout">
            <div class="profile-main">
              <!-- 基本信息 -->
              <section class="panel detail-panel">
                <div class="panel-heading"><div><div class="eyebrow">BASIC INFORMATION</div><h3>基本信息</h3></div><button class="icon-button" type="button" :aria-label="editing ? '取消编辑' : '编辑基本信息'" @click="toggleEdit"><svg><use href="#i-edit"/></svg></button></div>

                <div v-if="!editing" class="detail-grid">
                  <div><span>姓名</span><strong>{{ filled(profile.name) }}</strong></div>
                  <div><span>联系电话</span><strong>{{ filled(profile.phone) }}</strong></div>
                  <div><span>电子邮箱</span><strong>{{ filled(profile.email) }}</strong></div>
                  <div><span>目标职位</span><strong>{{ filled(profile.targetTitle) }}</strong></div>
                  <div><span>求职城市</span><strong>{{ filled(cityText) }}</strong></div>
                  <div><span>学历</span><strong>{{ filled(profile.education) }}</strong></div>
                  <div><span>相关经验</span><strong>{{ experienceText }}</strong></div>
                  <div><span>期望薪资</span><strong>{{ filled(profile.expectedSalary) }}</strong></div>
                  <div><span>目标行业</span><strong>{{ filled(industryText) }}</strong></div>
                </div>

                <div v-else class="edit-grid">
                  <label><span>姓名</span><input v-model.trim="form.name" type="text" placeholder="你的名字"></label>
                  <label><span>联系电话</span><input v-model.trim="form.phone" type="text" placeholder="138 0000 0000"></label>
                  <label class="edit-wide"><span>电子邮箱</span><input v-model.trim="form.email" type="text" placeholder="you@example.com"></label>
                  <label><span>目标职位</span><input v-model.trim="form.targetTitle" type="text" placeholder="Java 后端开发工程师"></label>
                  <label><span>求职城市（多个用逗号分隔）</span><input v-model.trim="form.cities" type="text" placeholder="上海"></label>
                  <label><span>学历</span><input v-model.trim="form.education" type="text" placeholder="本科"></label>
                  <label><span>相关经验（年，应届填 0）</span><input v-model.number="form.yearsOfExperience" type="number" min="0" max="50"></label>
                  <label><span>期望薪资</span><input v-model.trim="form.expectedSalary" type="text" placeholder="15-22K"></label>
                  <label><span>目标行业（多个用逗号分隔）</span><input v-model.trim="form.industries" type="text" placeholder="互联网"></label>
                  <label class="edit-wide"><span>技能（逗号分隔）</span><input v-model.trim="form.skills" type="text" placeholder="Java, Spring Boot, MySQL, Redis"></label>
                  <label class="edit-wide"><span>一句话简介</span><input v-model.trim="form.summary" type="text" placeholder="计算机专业应届生，有后端实习经历"></label>
                </div>
              </section>

              <!-- 教育经历 -->
              <section class="panel detail-panel">
                <div class="panel-heading"><div><div class="eyebrow">EDUCATION</div><h3>教育经历</h3></div><span class="panel-count">{{ educations.length }} 条</span></div>
                <template v-if="!editing">
                  <div v-for="(line, index) in educations" :key="`edu-${index}`" class="timeline-entry">
                    <span class="timeline-dot"></span>
                    <div class="timeline-entry-main"><div class="timeline-title"><strong>{{ line }}</strong></div></div>
                  </div>
                  <p v-if="!educations.length" class="panel-empty">还没有教育经历。去 <RouterLink to="/materials">材料整理</RouterLink> 上传一份简历，确认后就会出现在这里。</p>
                </template>
                <label v-else class="edit-block"><span>每行一条，例如：上海理工大学 · 本科 · 计算机科学与技术 · 2022.09—2026.06</span><textarea v-model="form.educations" rows="3" placeholder="上海理工大学 · 本科 · 计算机科学与技术 · 2022.09—2026.06"></textarea></label>
              </section>

              <!-- 经历 -->
              <section class="panel detail-panel">
                <div class="panel-heading"><div><div class="eyebrow">EXPERIENCE</div><h3>项目与实习经历</h3></div><span class="panel-count">{{ experiences.length }} 条</span></div>
                <template v-if="!editing">
                  <div v-for="(line, index) in experiences" :key="`exp-${index}`" class="project-entry">
                    <p class="project-description">{{ line }}</p>
                  </div>
                  <p v-if="!experiences.length" class="panel-empty">还没有经历。去 <RouterLink to="/materials">材料整理</RouterLink> 上传简历并确认，或点右上角「编辑资料」手动填写。</p>
                </template>
                <label v-else class="edit-block"><span>每行一条，尽量带上规模、数据或结果</span><textarea v-model="form.experiences" rows="6" placeholder="校园活动管理平台（后端开发 · 个人项目，2025.03—2025.07）：基于 Spring Boot 构建……"></textarea></label>
              </section>

              <div v-if="editing" class="edit-actions">
                <span>保存后会同时写入本机与后端；<b>岗位匹配</b>页会立刻用上新的资料。</span>
                <div>
                  <button class="button button-outline" type="button" :disabled="saving" @click="toggleEdit">取消</button>
                  <button class="button button-dark" type="button" :disabled="saving" @click="submit">{{ saving ? '保存中…' : '保存资料' }} <svg><use href="#i-arrow"/></svg></button>
                </div>
              </div>
            </div>

            <aside class="profile-side">
              <section class="panel skills-panel"><div class="eyebrow">SKILL SET</div><h3>专业技能</h3><p class="side-muted">来自你确认过的材料</p>
                <div v-if="skills.length" class="tag-row"><span v-for="skill in skills" :key="skill" class="skill-tag">{{ skill }}</span></div>
                <p v-else class="side-muted">还没有技能。去材料整理上传一份简历，或点「编辑资料」手动填写。</p>
                <!-- 刻意不显示"熟练 / 熟悉 / 了解"的等级和进度条：画像里没有这个信息，
                     编一个出来只会让用户以为系统真的评估过。 -->
                <p class="side-footnote">技能熟练度要自己判断，这里不替你打分。</p>
              </section>
              <section class="side-callout"><span class="callout-icon"><svg><use href="#i-spark"/></svg></span><div class="eyebrow">NEXT STEP</div><h3>{{ callout.title }}</h3><p>{{ callout.body }}</p><RouterLink class="text-button" :to="callout.to">{{ callout.action }} <svg><use href="#i-arrow"/></svg></RouterLink></section>
            </aside>
          </div>
          <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>产品预览 2026</span></footer>
        </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchProfile, saveProfile as putProfile } from '../api/materials'
import { getProfile, saveProfile as cacheProfile } from '../utils/demoProfile'
import { getDemoSession } from '../utils/demoAuth'

const profile = ref(getProfile())
const loading = ref(true)
const saving = ref(false)
const editing = ref(false)
const error = ref('')
const notice = ref('')
/** 资料是从后端读到的，还是本地/演示的。页面上如实标注，不假装。 */
const fromServer = ref(false)

const form = reactive({
  name: '',
  phone: '',
  email: '',
  targetTitle: '',
  cities: '',
  education: '',
  yearsOfExperience: 0,
  expectedSalary: '',
  industries: '',
  skills: '',
  summary: '',
  educations: '',
  experiences: ''
})

const displayName = computed(() => getDemoSession()?.displayName || '求职者')
/** 头像用姓名首字。姓名改成自己的之后，这里也跟着变。 */
const avatarChar = computed(() => ((profile.value.name || displayName.value || '求')).slice(0, 1))

const cityText = computed(() => (profile.value.targetCities || []).join('、'))
const industryText = computed(() => (profile.value.industries || []).join('、'))
const skills = computed(() => profile.value.skills || [])
const educations = computed(() => profile.value.educations || [])
const experiences = computed(() => profile.value.experiences || [])

const experienceText = computed(() => {
  const years = Number(profile.value.yearsOfExperience) || 0
  return years > 0 ? `${years} 年` : '应届 / 在校'
})

const kickerParts = computed(() => {
  const years = Number(profile.value.yearsOfExperience) || 0
  const age = years > 0 ? `${years} 年经验` : '应届毕业生'
  return [age, cityText.value, profile.value.education].filter(Boolean)
})

const sourceLabel = computed(() => (fromServer.value ? '已保存到后端' : '本机演示数据 · 尚未确认'))

/**
 * 档案完整度：按画像里**实际有值的字段**算，不写死一个百分比。
 *
 * 原来页面上是硬编码的 82%——用户删掉经历、清空技能它也纹丝不动，
 * 那就不是"完整度"，只是个装饰。这里每个字段都真的影响结果。
 */
const completeness = computed(() => {
  const p = profile.value
  const checks = [
    Boolean(p.targetTitle),
    (p.targetCities || []).length > 0,
    Boolean(p.education),
    (p.educations || []).length > 0,
    (p.skills || []).length >= 3,
    (p.experiences || []).length > 0,
    Boolean(p.summary),
    Boolean(p.expectedSalary)
  ]
  return Math.round((checks.filter(Boolean).length / checks.length) * 100)
})

/** 下一步该做什么：按资料缺什么来给，而不是一句固定的鼓励。 */
const callout = computed(() => {
  if (!experiences.value.length && !educations.value.length) {
    return {
      title: '先把材料整理起来。',
      body: '上传一份简历或粘贴一段经历，系统会抽出教育、项目和技能，你确认后就会出现在这里。',
      action: '去材料整理',
      to: '/materials'
    }
  }
  if (completeness.value < 100) {
    const missing = []
    if (!profile.value.targetTitle) missing.push('目标职位')
    if (!(profile.value.targetCities || []).length) missing.push('求职城市')
    if (!skills.value.length) missing.push('技能')
    if (!profile.value.summary) missing.push('一句话简介')
    return {
      title: '再补几个字段就够了。',
      body: `还缺 ${missing.slice(0, 3).join('、') || '一些信息'}。补全之后岗位匹配会更准。`,
      action: '编辑资料',
      to: '/profile'
    }
  }
  return {
    title: '资料齐了，去看看岗位。',
    body: '岗位匹配会按你的技能、方向和城市算契合度，并指出还差什么。',
    action: '去岗位匹配',
    to: '/jobs'
  }
})

function filled(value) {
  return value && String(value).trim() ? value : '未填写'
}

function flash(message) {
  notice.value = message
  error.value = ''
  window.setTimeout(() => {
    if (notice.value === message) notice.value = ''
  }, 4000)
}

/* ------------------------------------------------------------------ 读写 */

function toForm(p) {
  form.name = p.name || ''
  form.phone = p.phone || ''
  form.email = p.email || ''
  form.targetTitle = p.targetTitle || ''
  form.cities = (p.targetCities || []).join('、')
  form.education = p.education || ''
  form.yearsOfExperience = Number(p.yearsOfExperience) || 0
  form.expectedSalary = p.expectedSalary || ''
  form.industries = (p.industries || []).join('、')
  form.skills = (p.skills || []).join(', ')
  form.summary = p.summary || ''
  form.educations = (p.educations || []).join('\n')
  form.experiences = (p.experiences || []).join('\n')
}

const splitList = (text) => String(text || '').split(/[、,，\n]+/).map((s) => s.trim()).filter(Boolean)

onMounted(async () => {
  try {
    // 后端那一份是「用户确认过的」，优先用它；读不到（404 = 从没保存过）就退回本机
    const server = await fetchProfile()
    profile.value = { ...getProfile(), ...server }
    fromServer.value = true
    // 回写本机，保证岗位页和服务端看到的是同一份
    cacheProfile(profile.value)
  } catch {
    profile.value = getProfile()
    fromServer.value = false
  } finally {
    toForm(profile.value)
    loading.value = false
  }
})

function toggleEdit() {
  editing.value = !editing.value
  error.value = ''
  if (editing.value) {
    toForm(profile.value)
  }
}

async function submit() {
  const base = getProfile()
  const payload = {
    userId: profile.value.userId || base.userId || 'demo-user',
    // 联系方式：只出现在简历抬头，不参与匹配打分
    name: form.name,
    phone: form.phone,
    email: form.email,
    targetTitle: form.targetTitle,
    targetCities: splitList(form.cities),
    yearsOfExperience: Number(form.yearsOfExperience) || 0,
    education: form.education,
    educations: splitList(form.educations),
    skills: splitList(form.skills),
    industries: splitList(form.industries),
    expectedSalary: form.expectedSalary,
    summary: form.summary,
    experiences: splitList(form.experiences)
  }

  saving.value = true
  error.value = ''
  try {
    cacheProfile(payload)
    const saved = await putProfile(payload)
    profile.value = { ...payload, ...(saved || {}) }
    fromServer.value = true
    editing.value = false
    flash('资料已保存，岗位匹配页会立刻用上它。')
  } catch (cause) {
    // 本机已经写成功了，说清楚"哪一半失败"，别让用户以为全丢了
    error.value = `已保存到本机，但同步到后端失败：${cause?.message || '未知错误'}。岗位匹配页仍会使用本机的资料。`
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
/* 画像页的编辑表单。简历页那套 .editable-card 是给它自己用的，这里另起一套，
   避免改到队友正在动的共享样式。 */
.edit-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px; }
.edit-grid label, .edit-block { display: flex; flex-direction: column; gap: 5px; }
.edit-grid span, .edit-block span { color: #98a296; font-size: 8px; }
.edit-wide { grid-column: 1 / -1; }
.edit-grid input, .edit-block textarea {
  width: 100%; padding: 8px 10px; border: 1px solid #e8ebe4; border-radius: 4px;
  color: #55645a; background: #fdfdfa; font-size: 9px; line-height: 1.8; outline: none;
}
.edit-grid input:focus, .edit-block textarea:focus { border-color: #a4b79c; box-shadow: 0 0 0 3px rgba(115, 151, 112, .08); }
.edit-block textarea { resize: vertical; }
.edit-block { margin-top: 4px; }

.edit-actions { margin-top: 2px; padding: 14px 16px; border: 1px solid #e6ebdf; border-radius: 5px; background: #f7f9f2; display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
.edit-actions > span { color: #8d988a; font-size: 8px; }
.edit-actions > div { display: flex; gap: 8px; }
.edit-actions .button { min-height: 31px; font-size: 8px; }

.panel-count { color: #a8b0a6; font: 8px var(--mono, monospace); }
.panel-empty { margin: 0; color: #a4ada3; font-size: 9px; line-height: 1.9; }
.panel-empty a { color: #6f8a5f; }
.timeline-entry + .timeline-entry { margin-top: 10px; }
.side-footnote { margin: 12px 0 0; padding-top: 10px; border-top: 1px solid #eceee7; color: #a6aea4; font-size: 7px; line-height: 1.7; }

@media (max-width: 900px) {
  .edit-grid { grid-template-columns: 1fr; }
}
</style>
