<template>
<section class="page active" id="page-resume" data-title="简历工作台">
          <div class="page-intro resume-intro"><div><div class="eyebrow"><span class="eyebrow-line"></span>RESUME STUDIO / 03</div><h1>简历工作台</h1><p>把经历写得准确、有重点，也更容易被读懂。</p></div><div class="intro-actions"><button class="button button-outline" @click="printResume"><svg><use href="#i-download"/></svg>打印 / 导出 PDF</button><button class="button button-primary" :disabled="saving || isEmpty" @click="saveVersion">{{ saving ? '保存中…' : '保存版本' }}</button></div></div>

          <p v-if="error" class="notice is-error" role="alert">{{ error }}</p>
          <p v-else-if="notice" class="notice" role="status">{{ notice }}</p>

          <div class="resume-toolbar">
            <div class="resume-template-label"><span class="template-swatch"></span><div><b>经典简约</b><span>当前模板 · 单栏清晰版</span></div><span class="template-note">首期只提供这一个模板</span></div>
            <div class="resume-target"><span>目标职位</span><input v-model.trim="targetTitle" type="text" placeholder="Java 后端开发工程师"></div>
            <span class="autosave"><i></i>{{ draft ? '内容来自你确认过的资料' : '正在读取资料…' }}</span>
          </div>

          <!-- 空简历：直接说清楚该去哪，不给一个空白编辑器 -->
          <section v-if="isEmpty" class="panel empty-panel">
            <div class="eyebrow">NOTHING YET</div>
            <h3>还没有可以生成简历的资料。</h3>
            <p>{{ emptyMessage }}</p>
            <RouterLink class="button button-dark" to="/materials">去材料整理 <svg><use href="#i-arrow"/></svg></RouterLink>
          </section>

          <template v-else>
            <!-- 定向 -->
            <section class="panel target-panel">
              <div class="panel-heading"><div><div class="eyebrow">TAILOR</div><h3>定向到具体岗位</h3></div><span class="panel-count">{{ jd.length }} 字</span></div>
              <p class="target-hint">把招聘网站上的岗位描述粘在这里，AI 会针对它给出<b>逐条</b>改写建议。不填也能用，只是建议会泛一些。</p>
              <textarea v-model="jd" rows="4" placeholder="把岗位描述粘在这里，例如：负责后端服务的设计与开发，要求熟悉 Java / Spring Boot，有高并发经验优先…"></textarea>
              <div class="target-actions">
                <span>AI 只改表达，<b>不会替你编造数字和成果</b>；建议需要你逐条采纳，不会自动覆盖。</span>
                <button class="button button-dark" :disabled="suggesting" @click="askSuggestions">{{ suggesting ? '生成中…' : '让 AI 给改写建议' }}</button>
              </div>
            </section>

            <!-- 建议 -->
            <section v-if="suggestions.length" class="panel suggestion-panel">
              <div class="panel-heading"><div><div class="eyebrow">SUGGESTIONS</div><h3>改写建议 <span class="panel-count">{{ suggestions.length }} 条</span></h3></div><button class="text-button" type="button" @click="suggestions = []">全部忽略</button></div>
              <article v-for="(item, index) in suggestions" :key="`sug-${index}`" class="suggestion-card">
                <p class="suggestion-reason"><span>为什么改</span>{{ item.reason }}</p>
                <div class="suggestion-diff">
                  <div><span class="diff-label">原文</span><p>{{ item.original }}</p></div>
                  <div><span class="diff-label is-new">建议稿</span><p>{{ item.suggested }}</p></div>
                </div>
                <div class="suggestion-actions">
                  <button class="button button-outline" type="button" @click="dismissSuggestion(index)">忽略</button>
                  <button class="button button-dark" type="button" @click="applySuggestion(index)">采纳这条 <svg><use href="#i-arrow"/></svg></button>
                </div>
              </article>
            </section>

            <div class="resume-workspace">
              <div class="resume-editor">
                <section class="editor-section"><div class="editor-section-title"><span class="editor-index">01</span><div><h3>个人简介</h3><p>用 2–3 句话建立清晰的职业印象</p></div></div><textarea v-model="draft.summary" class="resume-textarea" rows="4" placeholder="计算机科学与技术专业应届毕业生，具备 Java 后端开发项目实践经验……"></textarea><div class="field-hint"><span>建议突出与你目标岗位相关的能力</span><span>{{ (draft.summary || '').length }} 字</span></div></section>

                <section v-for="(group, groupIndex) in editableGroups" :key="group.key" class="editor-section">
                  <div class="editor-section-title"><span class="editor-index">{{ String(groupIndex + 2).padStart(2, '0') }}</span><div><h3>{{ group.title }}</h3><p>{{ group.hint }}</p></div><button class="icon-button" type="button" :aria-label="`给${group.title}加一条`" @click="addEntry(group.key)">＋</button></div>
                  <p v-if="!group.entries.length" class="section-empty">还没有{{ group.title }}。去 <RouterLink to="/materials">材料整理</RouterLink> 补一份，或点右上角 ＋ 手动加一条。</p>
                  <article v-for="(entry, entryIndex) in group.entries" :key="`${group.key}-${entryIndex}`" class="editable-card">
                    <div class="editable-head">
                      <div class="editable-fields">
                        <input v-model.trim="entry.title" type="text" :placeholder="group.titlePlaceholder">
                        <input v-model.trim="entry.meta" type="text" placeholder="时间 / 角色（可选）">
                      </div>
                      <button class="icon-button" type="button" :aria-label="`删掉这一条${group.title}`" title="删掉这一条" @click="removeEntry(group.key, entryIndex)">✕</button>
                    </div>
                    <textarea class="entry-bullets" rows="3" :value="bulletsText(entry)" :placeholder="group.bulletPlaceholder" @input="setBullets(entry, $event.target.value)"></textarea>
                    <p class="field-hint"><span>每条要点单独一行</span><span>{{ entry.bullets.length }} 条</span></p>
                  </article>
                </section>

                <section class="editor-section compact-editor"><div class="editor-section-title"><span class="editor-index">{{ String(editableGroups.length + 2).padStart(2, '0') }}</span><div><h3>专业技能</h3><p>逗号分隔，会按顺序显示在简历上</p></div></div><input v-model.trim="skillsText" class="skills-input" type="text" placeholder="Java, Spring Boot, MySQL, Redis"></section>

                <div class="editor-bottom-note"><span>✳</span>内容由你确认后再使用，AI 建议不会自动覆盖原文。</div>
              </div>

              <aside class="resume-preview-panel">
                <div class="preview-header"><span>简历预览</span><span class="preview-page">A4 <i>·</i> 单栏</span></div>
                <div class="resume-paper" id="resume-paper">
                  <div class="paper-topline"></div>
                  <div class="paper-person"><h2>{{ personName }}</h2><p>{{ draft.headline || '（未填目标职位）' }}</p><div v-if="draft.contacts && draft.contacts.length"><template v-for="(contact, index) in draft.contacts" :key="contact"><i v-if="index">·</i><span>{{ contact }}</span></template></div></div>
                  <div v-if="draft.summary" class="paper-section"><h3>个人简介</h3><p>{{ draft.summary }}</p></div>
                  <div v-if="draft.educations.length" class="paper-section"><h3>教育经历</h3><template v-for="(entry, index) in draft.educations" :key="`pe-${index}`"><div class="paper-row"><b>{{ entry.title }}</b><span>{{ entry.meta }}</span></div><ul v-if="entry.bullets.length"><li v-for="(bullet, bi) in entry.bullets" :key="`pe-${index}-${bi}`">{{ bullet }}</li></ul></template></div>
                  <div v-if="draft.experiences.length" class="paper-section"><h3>实习 / 工作经历</h3><template v-for="(entry, index) in draft.experiences" :key="`px-${index}`"><div class="paper-row"><b>{{ entry.title }}</b><span>{{ entry.meta }}</span></div><ul v-if="entry.bullets.length"><li v-for="(bullet, bi) in entry.bullets" :key="`px-${index}-${bi}`">{{ bullet }}</li></ul></template></div>
                  <div v-if="draft.projects.length" class="paper-section"><h3>项目经历</h3><template v-for="(entry, index) in draft.projects" :key="`pp-${index}`"><div class="paper-row"><b>{{ entry.title }}</b><span>{{ entry.meta }}</span></div><ul v-if="entry.bullets.length"><li v-for="(bullet, bi) in entry.bullets" :key="`pp-${index}-${bi}`">{{ bullet }}</li></ul></template></div>
                  <div v-if="draft.skills.length" class="paper-section"><h3>专业技能</h3><p>{{ draft.skills.join(' · ') }}</p></div>
                  <div v-if="draft.keywords.length" class="paper-keywords">{{ draft.keywords.join('　') }}</div>
                  <div class="paper-end">拾阶简历工作台 · 个人预览</div>
                </div>
                <div class="preview-caption"><span class="preview-dot"></span>预览内容来自你确认过的资料</div>
              </aside>
            </div>

            <!-- 版本 -->
            <section class="panel versions-panel">
              <div class="panel-heading"><div><div class="eyebrow">VERSIONS</div><h3>已保存的版本 <span class="panel-count">{{ versions.length }}</span></h3></div><span class="panel-count">投不同岗位可以各存一版</span></div>
              <p v-if="!versions.length" class="section-empty">还没有保存过版本。改好之后点右上角「保存版本」。</p>
              <article v-for="version in versions" :key="version.id" class="version-item">
                <div class="version-info"><strong>{{ version.name }}</strong><span>{{ version.targetTitle || '未填目标职位' }} · {{ version.summaryText }} · {{ version.createdAt }}<template v-if="version.hasJd"> · 定向版</template></span></div>
                <div class="version-actions">
                  <button class="text-button" type="button" @click="loadVersion(version.id)">载入</button>
                  <button class="text-button is-danger" type="button" @click="removeVersion(version)">删除</button>
                </div>
              </article>
            </section>
          </template>

          <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>产品预览 2026</span></footer>
        </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  deleteResumeVersion,
  fetchResumeDraft,
  fetchResumeVersion,
  listResumeVersions,
  saveResumeVersion,
  suggestResume
} from '../api/resume'
import { getDemoSession } from '../utils/demoAuth'

const draft = ref(null)
const emptyMessage = ref('')
const versions = ref([])
const suggestions = ref([])
const targetTitle = ref('')
const jd = ref('')
const skillsText = ref('')
const loading = ref(true)
const saving = ref(false)
const suggesting = ref(false)
const error = ref('')
const notice = ref('')

const personName = computed(() => getDemoSession()?.displayName || '求职者')
const isEmpty = computed(() => !draft.value || loading.value)

/**
 * 三块可编辑内容。
 *
 * 用一份配置驱动，避免在模板里写三遍几乎一样的 HTML——
 * 三份重复的模板最难维护：改了一处忘了另外两处。
 */
const editableGroups = computed(() => {
  if (!draft.value) return []
  return [
    {
      key: 'educations',
      title: '教育经历',
      hint: '学校、专业与时间',
      titlePlaceholder: '上海理工大学',
      bulletPlaceholder: '主修课程、GPA、获奖…（每条一行，可留空）',
      entries: draft.value.educations
    },
    {
      key: 'experiences',
      title: '实习 / 工作经历',
      hint: '描述行动与结果，让贡献更具体',
      titlePlaceholder: '上海璞云信息技术有限公司 · Java 后端开发实习生',
      bulletPlaceholder: '参与了权限模块开发\n把权限校验改成 Redis 缓存，响应时间从 180ms 降到 45ms',
      entries: draft.value.experiences
    },
    {
      key: 'projects',
      title: '项目经历',
      hint: '课程项目、个人项目、比赛作品都算',
      titlePlaceholder: '校园二手交易平台 · 后端负责人',
      bulletPlaceholder: '用分布式锁解决了同一商品被并发下单的超卖问题',
      entries: draft.value.projects
    }
  ]
})

function flash(message) {
  notice.value = message
  error.value = ''
  window.setTimeout(() => {
    if (notice.value === message) notice.value = ''
  }, 4000)
}

function fail(cause) {
  error.value = cause?.message || '操作失败，请重试'
  notice.value = ''
}

/* ------------------------------------------------------------------ 载入 */

async function load() {
  loading.value = true
  try {
    const result = await fetchResumeDraft()
    draft.value = result.draft
    emptyMessage.value = result.message || ''
    targetTitle.value = result.draft?.headline || ''
    skillsText.value = (result.draft?.skills || []).join(', ')
    if (result.message && !result.empty) {
      flash(result.message)
    }
    await refreshVersions()
  } catch (cause) {
    fail(cause)
  } finally {
    loading.value = false
  }
}

async function refreshVersions() {
  try {
    versions.value = await listResumeVersions()
  } catch (cause) {
    // 版本列表挂了不该挡住主流程（编辑和打印才是主功能）
    versions.value = []
  }
}

onMounted(load)

/* ------------------------------------------------------------------ 编辑 */

function bulletsText(entry) {
  return (entry.bullets || []).join('\n')
}

function setBullets(entry, text) {
  entry.bullets = String(text || '').split('\n').map((line) => line.trim()).filter(Boolean)
}

function addEntry(key) {
  draft.value?.[key]?.push({ title: '', meta: '', bullets: [] })
}

function removeEntry(key, index) {
  draft.value?.[key]?.splice(index, 1)
}

/**
 * 技能输入框实时同步回 draft。
 *
 * 用 watch 而不是只在保存时同步：右侧预览读的是 draft.skills，
 * 如果等保存才更新，用户改了技能会看到预览"没反应"，以为没生效。
 */
watch(skillsText, (value) => {
  if (draft.value) {
    draft.value.skills = value.split(/[,，、\s]+/).filter(Boolean)
  }
})

/** 技能输入框变化时同步回 draft（保存和预览都读 draft.skills）。 */
function syncSkills() {
  if (!draft.value) return
  draft.value.skills = skillsText.value.split(/[,，、\s]+/).filter(Boolean)
}

/* ------------------------------------------------------------------ 建议 */

async function askSuggestions() {
  syncSkills()
  suggesting.value = true
  error.value = ''
  try {
    const result = await suggestResume({
      targetTitle: targetTitle.value,
      jd: jd.value,
      draft: draft.value
    })
    suggestions.value = result.suggestions || []
    if (result.message) {
      flash(result.message)
    } else if (!suggestions.value.length) {
      flash('模型这次没有给出可用的建议。')
    }
  } catch (cause) {
    fail(cause)
  } finally {
    suggesting.value = false
  }
}

function dismissSuggestion(index) {
  suggestions.value.splice(index, 1)
}

/**
 * 采纳一条建议：把原文替换成建议稿。
 *
 * 后端已经校验过 original 确实在简历里（编造的建议根本不会返回），
 * 但这里仍然要处理"找不到"的情况——用户可能在拿到建议后又改了原文。
 * 找不到就明确告诉他，而不是悄悄什么都不做。
 */
function applySuggestion(index) {
  const item = suggestions.value[index]
  if (!item || !draft.value) return

  const target = item.original
  for (const key of ['educations', 'experiences', 'projects']) {
    for (const entry of draft.value[key] || []) {
      if (entry.title === target) {
        entry.title = item.suggested
        suggestions.value.splice(index, 1)
        return
      }
      const bulletIndex = (entry.bullets || []).indexOf(target)
      if (bulletIndex >= 0) {
        entry.bullets[bulletIndex] = item.suggested
        suggestions.value.splice(index, 1)
        return
      }
    }
  }
  fail(new Error('这句话在当前简历里找不到了（可能你刚改过它）。请重新生成建议。'))
  suggestions.value.splice(index, 1)
}

/* ------------------------------------------------------------------ 版本 */

async function saveVersion() {
  syncSkills()
  saving.value = true
  error.value = ''
  try {
    const saved = await saveResumeVersion({
      name: '',
      targetTitle: targetTitle.value,
      jd: jd.value,
      draft: draft.value
    })
    await refreshVersions()
    flash(`已保存为「${saved?.name || '新版本'}」。`)
  } catch (cause) {
    fail(cause)
  } finally {
    saving.value = false
  }
}

async function loadVersion(id) {
  try {
    const detail = await fetchResumeVersion(id)
    draft.value = detail.draft
    targetTitle.value = detail.draft?.headline || targetTitle.value
    skillsText.value = (detail.draft?.skills || []).join(', ')
    suggestions.value = []
    flash(`已载入「${detail.version?.name || id}」。`)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  } catch (cause) {
    fail(cause)
  }
}

async function removeVersion(version) {
  if (!window.confirm(`删除版本「${version.name}」？`)) return
  try {
    await deleteResumeVersion(version.id)
    await refreshVersions()
    flash('版本已删除。')
  } catch (cause) {
    fail(cause)
  }
}

/* ------------------------------------------------------------------ 打印 */

/**
 * 导出 PDF 走浏览器自带的打印（在打印对话框里选「另存为 PDF」）。
 *
 * 为什么不做服务端渲染 PDF：那要引入一整套排版引擎（或 headless 浏览器），
 * 对一个课程项目来说是过重的依赖；而共享样式里已经为打印写好了 A4 纸面规则
 * （`@media print` 只留 #page-resume 和 .resume-paper），浏览器打印的效果是可控的。
 */
function printResume() {
  syncSkills()
  window.print()
}
</script>

<style scoped>
.empty-panel { padding: 26px 24px; text-align: center; }
.empty-panel h3 { margin: 10px 0 8px; color: #46584a; font-size: 15px; }
.empty-panel p { margin: 0 0 16px; color: #8d988f; font-size: 9px; line-height: 1.9; }
.empty-panel .button { display: inline-flex; }

.template-note { color: #a8b0a6; font-size: 7px; }
.resume-target input { padding: 7px 9px; border: 1px solid #e8ebe4; border-radius: 4px; color: #55645a; background: #fdfdfa; font-size: 9px; outline: none; min-width: 180px; }
.resume-target input:focus { border-color: #a4b79c; box-shadow: 0 0 0 3px rgba(115, 151, 112, .08); }

.panel-count { color: #a8b0a6; font: 8px var(--mono, monospace); }
.target-panel { margin-bottom: 14px; padding: 19px 21px; }
.target-hint { margin: 0 0 11px; color: #939d94; font-size: 8px; line-height: 1.85; }
.target-panel textarea { width: 100%; resize: vertical; border: 1px solid #e8ebe4; border-radius: 4px; padding: 10px 11px; color: #55645a; background: #fdfdfa; font-size: 9px; line-height: 1.8; outline: none; }
.target-panel textarea:focus { border-color: #a4b79c; box-shadow: 0 0 0 3px rgba(115, 151, 112, .08); }
.target-actions { margin-top: 10px; display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.target-actions > span { color: #98a296; font-size: 8px; line-height: 1.8; }
.target-actions .button { min-height: 31px; font-size: 8px; }

.suggestion-panel { margin-bottom: 14px; padding: 19px 21px; }
.suggestion-card { margin-bottom: 12px; padding: 13px 14px; border: 1px solid #e6ecdd; border-radius: 5px; background: #fbfcf7; }
.suggestion-reason { margin: 0 0 10px; color: #7d8a72; font-size: 8px; line-height: 1.8; }
.suggestion-reason span { margin-right: 6px; padding: 2px 6px; border-radius: 3px; color: #6f8a5f; background: #eef3e6; font-size: 7px; }
.suggestion-diff { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.suggestion-diff > div { padding: 9px 10px; border-radius: 4px; background: #fdf9f6; border: 1px solid #f0e6dd; }
.suggestion-diff > div:last-child { background: #f5f9ef; border-color: #e3ecd7; }
.diff-label { display: inline-block; margin-bottom: 5px; color: #b08b74; font-size: 7px; }
.diff-label.is-new { color: #6f8a5f; }
.suggestion-diff p { margin: 0; color: #5f6d61; font-size: 8px; line-height: 1.85; }
.suggestion-actions { margin-top: 11px; display: flex; justify-content: flex-end; gap: 8px; }
.suggestion-actions .button { min-height: 29px; font-size: 8px; }

.editable-fields { flex: 1; display: flex; flex-direction: column; gap: 5px; }
.editable-fields input { width: 100%; padding: 6px 8px; border: 1px solid #e8ebe4; border-radius: 4px; color: #55645a; background: #fff; font-size: 9px; outline: none; }
.editable-fields input:focus { border-color: #a4b79c; box-shadow: 0 0 0 3px rgba(115, 151, 112, .08); }
.entry-bullets { width: 100%; margin-top: 9px; resize: vertical; border: 1px solid #e8ebe4; border-radius: 4px; padding: 8px 9px; color: #627065; background: #fff; font-size: 8px; line-height: 1.9; outline: none; }
.entry-bullets:focus { border-color: #a4b79c; box-shadow: 0 0 0 3px rgba(115, 151, 112, .08); }
.section-empty { margin: 0 0 10px; color: #a4ada3; font-size: 8px; line-height: 1.9; }
.section-empty a { color: #6f8a5f; }
.skills-input { width: 100%; padding: 8px 10px; border: 1px solid #e8ebe4; border-radius: 4px; color: #55645a; background: #fdfdfa; font-size: 9px; outline: none; }
.skills-input:focus { border-color: #a4b79c; box-shadow: 0 0 0 3px rgba(115, 151, 112, .08); }

.versions-panel { margin-top: 14px; padding: 19px 21px; }
.version-item { padding: 11px 0; border-bottom: 1px solid #ecefe7; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.version-item:last-child { border-bottom: 0; }
.version-info { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.version-info strong { color: #536257; font-size: 9px; font-weight: 600; }
.version-info span { color: #a0a9a0; font-size: 8px; }
.version-actions { display: flex; gap: 10px; flex-shrink: 0; }
.text-button.is-danger { color: #b06a60; }

/* 打印时只留纸面：共享样式已经隐藏了工具栏和编辑器，
   这里补上本页新增的面板，否则建议和版本会一起被打出来。 */
@media print {
  .target-panel, .suggestion-panel, .versions-panel, .notice, .empty-panel { display: none !important; }
}

@media (max-width: 900px) {
  .suggestion-diff { grid-template-columns: 1fr; }
}
</style>
