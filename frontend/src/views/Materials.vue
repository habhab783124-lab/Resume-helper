<template>
<section class="page active" id="page-materials" data-title="材料整理">
          <div class="page-intro"><div><div class="eyebrow"><span class="eyebrow-line"></span>MATERIALS / 02</div><h1>材料整理</h1><p>简历、项目记录、实习复盘，都可以成为你的职业素材。</p></div><span class="storage-badge">已整理 <b>{{ materials.length }}</b> 份材料</span></div>

          <p v-if="error" class="notice is-error" role="alert">{{ error }}</p>
          <p v-else-if="notice" class="notice" role="status">{{ notice }}</p>

          <div class="materials-layout">
            <div class="materials-main">
              <section
                class="upload-zone"
                :class="{ 'is-dragging': dragging, 'is-busy': busy }"
                id="upload-zone"
                tabindex="0"
                role="button"
                aria-label="上传材料"
                @click="pickFiles"
                @keydown.enter.prevent="pickFiles"
                @keydown.space.prevent="pickFiles"
                @dragover.prevent="dragging = true"
                @dragleave.prevent="dragging = false"
                @drop.prevent="onDrop"
              >
                <input ref="fileInput" id="file-input" type="file" accept=".pdf,.docx,.txt,.md" multiple hidden @change="onPick">
                <span class="upload-illustration"><svg><use href="#i-upload"/></svg><span class="upload-spark">✳</span></span>
                <h2>{{ busy ? busyText : '把材料放在这里' }}</h2>
                <p v-if="!busy">拖拽文件至此，或 <button type="button" class="inline-action" id="choose-files" @click.stop="pickFiles">浏览文件</button></p>
                <p v-else>抽取中，请稍候…</p>
                <div class="upload-formats"><span>PDF</span><span>DOCX</span><span>TXT</span><i>{{ uploadHint }}</i></div>
              </section>

              <section class="paste-panel panel"><div class="panel-heading"><div><div class="eyebrow">QUICK CAPTURE</div><h3>粘贴一段经历</h3></div><span class="capture-icon">⌘ V</span></div><textarea id="paste-text" v-model="pasteBody" rows="4" placeholder="可以粘贴项目经历、实习记录或一段尚未整理的想法…"></textarea><div class="paste-actions"><span>扫描件（图片型 PDF）请走这里——直接粘贴文字最可靠</span><button class="button button-dark" id="save-paste" :disabled="busy || !pasteBody.trim()" @click="submitPaste">保存这段材料 <svg><use href="#i-arrow"/></svg></button></div></section>

              <section class="materials-list-panel"><div class="panel-heading"><div><div class="eyebrow">YOUR LIBRARY</div><h3>已整理的材料 <span class="heading-count">{{ pad(materials.length) }}</span></h3></div><span class="list-hint">点一条查看抽取结果</span></div><div class="material-list" id="material-list">
                <p v-if="loading" class="list-empty">正在读取材料…</p>
                <p v-else-if="!materials.length" class="list-empty">还没有材料。上传一份简历，或粘贴一段经历。</p>
                <article
                  v-for="item in materials"
                  :key="item.id"
                  class="material-item"
                  :class="{ 'is-active': detail && detail.material.id === item.id }"
                  role="button"
                  tabindex="0"
                  @click="select(item.id)"
                  @keydown.enter.prevent="select(item.id)"
                >
                  <span class="file-type" :class="fileTypeClass(item)">{{ item.fileTypeLabel }}</span>
                  <div class="material-info">
                    <strong>{{ item.fileName }}</strong>
                    <span>{{ item.sizeText }} · {{ item.createdAt }}<template v-if="item.itemCount"> · 识别出 {{ item.itemCount }} 条</template></span>
                  </div>
                  <span class="status-pill" :class="`status-${item.statusStyle}`"><i></i>{{ item.statusLabel }}</span>
                  <button class="icon-button" type="button" :aria-label="`删除 ${item.fileName}`" title="删除这份材料" @click.stop="remove(item)">✕</button>
                </article>
              </div></section>
            </div>

            <aside class="materials-side"><div class="side-illustration"><span class="paper paper-one"></span><span class="paper paper-two"></span><span class="paper paper-three"><i></i><i></i><i></i></span><span class="paper-spark">✳</span></div><div class="eyebrow">A LITTLE NOTE</div><h3>先收集，再整理。<br>好经历值得被记下来。</h3><p>上传材料后，我们会尝试提取其中的教育、项目、实习和技能信息，供你检查确认。</p><div class="privacy-note"><span>◎</span><span>课程演示数据只保存在本机后端，<b>不会</b>上传到任何第三方</span></div></aside>
          </div>

          <!-- ---------------------------------------------------------------- 抽取结果 -->
          <section v-if="detail" class="extraction-panel panel" aria-label="抽取结果">
            <div class="panel-heading">
              <div>
                <div class="eyebrow">EXTRACTION REVIEW</div>
                <h3>{{ detail.material.fileName }} · 抽取结果</h3>
              </div>
              <button class="text-button" type="button" @click="closeDetail">收起 <svg><use href="#i-chevron"/></svg></button>
            </div>

            <!-- 解析失败 / 扫描件：直接给下一步该做什么，不展示空的抽取结果 -->
            <template v-if="!draft">
              <p class="extract-blocked">{{ detail.material.message || '这份材料没有可用的文字内容。' }}</p>
            </template>

            <template v-else>
              <p v-if="!hasToken" class="extract-hint">
                没有检测到 AI token，<b>无法自动识别简历内容</b>。
                可以到<a href="/jobs">岗位匹配</a>页顶部填入自己的 token 后重新上传，
                也可以直接在下面手动整理——下列字段都能改。
              </p>

              <p v-for="(warning, index) in draft.warnings" :key="`warn-${index}`" class="extract-warning">{{ warning }}</p>

              <div class="extract-split">
                <!-- 左：原文。让用户能核对，而不是盲信 AI -->
                <div class="extract-source">
                  <div class="extract-subtitle">原文（从你上传的文件里抽出来的）</div>
                  <pre class="source-text">{{ detail.text || '（没有可显示的原文）' }}</pre>
                </div>

                <!-- 右：结构化结果，每条都带原文证据 -->
                <div class="extract-result">
                  <div class="extract-subtitle">识别结果（可修改）</div>

                  <div class="field-grid">
                    <label><span>一句话简介</span><input v-model.trim="draft.summary" type="text" placeholder="例如：计算机专业应届生，有后端实习经历"></label>
                    <label><span>最高学历</span><input v-model.trim="draft.highestEducation" type="text" placeholder="本科 / 硕士"></label>
                    <label><span>相关经验（年）</span><input v-model.number="draft.yearsOfExperience" type="number" min="0" max="50"></label>
                    <label><span>目标岗位</span><input v-model.trim="targetTitle" type="text" placeholder="Java 后端开发工程师"></label>
                    <label><span>目标城市</span><input v-model.trim="targetCities" type="text" placeholder="上海（多个用逗号分隔）"></label>
                    <label><span>期望薪资</span><input v-model.trim="expectedSalary" type="text" placeholder="15-22K"></label>
                  </div>

                  <label class="field-block"><span>技能（逗号分隔）</span><input v-model.trim="skillsText" type="text" placeholder="Java, Spring Boot, MySQL"></label>

                  <div v-for="group in groups" :key="group.key" class="extract-group">
                    <div class="extract-subtitle">{{ group.title }} <i>{{ group.items.length }}</i></div>
                    <p v-if="!group.items.length" class="group-empty">没有识别到{{ group.title }}。</p>
                    <article v-for="(item, index) in group.items" :key="`${group.key}-${index}`" class="extract-item">
                      <div class="extract-item-head">
                        <strong>{{ group.headline(item) }}</strong>
                        <button type="button" class="icon-button" title="这条不对，删掉" @click="removeItem(group.key, index)">✕</button>
                      </div>
                      <p v-if="group.detail(item)" class="extract-item-body">{{ group.detail(item) }}</p>
                      <!-- 证据：这是让用户敢相信抽取结果的关键。模型编造的经历过不了证据回验，根本不会出现在这里 -->
                      <p v-if="item.evidence" class="extract-evidence">原文：{{ item.evidence }}</p>
                    </article>
                  </div>

                  <div v-if="draft.highlights.length" class="extract-group">
                    <div class="extract-subtitle">获奖 / 证书 <i>{{ draft.highlights.length }}</i></div>
                    <ul class="highlight-list"><li v-for="(item, index) in draft.highlights" :key="`h-${index}`">{{ item }}</li></ul>
                  </div>
                </div>
              </div>

              <div class="extract-actions">
                <span>确认后会保存成你的求职画像，<b>岗位匹配</b>页会立刻用上它。</span>
                <div>
                  <button class="button button-outline" type="button" :disabled="saving" @click="closeDetail">稍后再说</button>
                  <button class="button button-dark" type="button" :disabled="saving" @click="confirmProfile">
                    {{ saving ? '保存中…' : '确认并保存为我的资料' }} <svg><use href="#i-arrow"/></svg>
                  </button>
                </div>
              </div>
            </template>
          </section>

          <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>产品预览 2026</span></footer>
        </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  deleteMaterial,
  fetchMaterial,
  listMaterials,
  pasteMaterial,
  saveProfile as putProfile,
  uploadMaterial
} from '../api/materials'
import { getProfile, saveProfile as cacheProfile } from '../utils/demoProfile'
import { hasAiKey } from '../api/client'

/* ---------------------------------------------------------------------------
 * 为什么这里保存两次画像
 *
 *   localStorage（utils/demoProfile.js）：岗位页读的是它，页面刷新也在。
 *   后端 PUT /api/profile：服务端持久化，将来简历/面试模块从服务端读画像时用得上。
 *
 * 两处都写，是为了让「材料页确认完 → 岗位页立刻生效」这条链路在
 * 前后端各自演进时都不会断。
 * ------------------------------------------------------------------------- */

const MAX_FILE_MB = 10
const ACCEPTED = ['pdf', 'docx', 'txt', 'md']

const materials = ref([])
const loading = ref(true)
const busy = ref(false)
const busyText = ref('')
const saving = ref(false)
const dragging = ref(false)
const error = ref('')
const notice = ref('')
const pasteBody = ref('')
const fileInput = ref(null)

/** 当前查看的材料：后端返回的原始 { material, extraction, text }。 */
const detail = ref(null)
/** 抽取结果的可编辑副本。用户删掉的条目会从这里移除。 */
const draft = ref(null)

/** 求职偏好：不在简历里，由用户自己填。默认沿用已有画像。 */
const targetTitle = ref('')
const targetCities = ref('')
const expectedSalary = ref('')
const skillsText = ref('')

const hasToken = ref(hasAiKey())

const uploadHint = computed(() => `单个文件不超过 ${MAX_FILE_MB}MB · 旧版 .doc 请先另存为 .docx`)

/** 三个经历分组的展示规则，模板里循环用，避免三份几乎一样的 HTML。 */
const groups = computed(() => {
  if (!draft.value) return []
  return [
    {
      key: 'educations',
      title: '教育经历',
      items: draft.value.educations,
      headline: (item) => [item.school, item.degree, item.major].filter(Boolean).join(' · ') || '（未命名）',
      detail: (item) => item.period
    },
    {
      key: 'experiences',
      title: '实习 / 工作经历',
      items: draft.value.experiences,
      headline: (item) => [item.company, item.role].filter(Boolean).join(' · ') || '（未命名）',
      detail: (item) => [item.period, item.description].filter(Boolean).join('　')
    },
    {
      key: 'projects',
      title: '项目经历',
      items: draft.value.projects,
      headline: (item) => [item.name, item.role].filter(Boolean).join(' · ') || '（未命名）',
      detail: (item) => [item.period, item.description].filter(Boolean).join('　')
    }
  ]
})

function pad(count) {
  return String(count).padStart(2, '0')
}

function fileTypeClass(item) {
  const extension = (item.extension || '').toLowerCase()
  if (extension === 'docx' || extension === 'doc') return 'file-doc'
  if (extension === 'txt' || extension === 'md') return 'file-txt'
  return ''
}

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

/* ------------------------------------------------------------------ 列表 */

async function refresh() {
  loading.value = true
  try {
    materials.value = await listMaterials()
  } catch (cause) {
    fail(cause)
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

/* ------------------------------------------------------------------ 上传 */

function pickFiles() {
  if (!busy.value) fileInput.value?.click()
}

function onPick(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (files.length) handleFiles(files)
}

function onDrop(event) {
  dragging.value = false
  const files = Array.from(event.dataTransfer?.files || [])
  if (files.length) handleFiles(files)
}

/** 上传前的本地校验：先在浏览器挡一道，用户不用等一次往返才知道格式不对。 */
function validate(file) {
  const extension = (file.name.split('.').pop() || '').toLowerCase()
  if (!ACCEPTED.includes(extension)) {
    return `不支持 .${extension || '未知'} 格式。目前支持 PDF、DOCX、TXT、Markdown；旧版 .doc 请先另存为 .docx。`
  }
  if (file.size > MAX_FILE_MB * 1024 * 1024) {
    return `「${file.name}」超过 ${MAX_FILE_MB}MB，请压缩后再上传，或者用下面的「粘贴一段经历」。`
  }
  return ''
}

async function handleFiles(files) {
  error.value = ''
  const pending = []
  for (const file of files) {
    const problem = validate(file)
    if (problem) {
      fail(new Error(problem))
      return
    }
    pending.push(file)
  }

  let last = null
  for (const [index, file] of pending.entries()) {
    busy.value = true
    busyText.value = pending.length > 1
      ? `正在上传并抽取（${index + 1}/${pending.length}）：${file.name}`
      : '正在上传并抽取…'
    try {
      last = await uploadMaterial(file)
      materials.value = await listMaterials()
      // 扫描件 / 解析失败要单独说清楚，否则用户以为"传上去了就没事"
      const status = last?.material?.status
      if (status === 'SCANNED') {
        fail(new Error(last.material.message))
      } else if (status === 'FAILED') {
        fail(new Error(last.material.message || '这份材料解析失败。'))
      } else {
        flash(`「${file.name}」已抽取，请核对识别结果。`)
      }
    } catch (cause) {
      fail(cause)
    } finally {
      busy.value = false
      busyText.value = ''
    }
  }
  if (last?.material?.id) showDetail(last)
}

/* ------------------------------------------------------------------ 粘贴 */

async function submitPaste() {
  const text = pasteBody.value.trim()
  if (!text) return
  busy.value = true
  busyText.value = '正在抽取粘贴的内容…'
  error.value = ''
  try {
    const result = await pasteMaterial({ title: '粘贴的经历', text })
    pasteBody.value = ''
    materials.value = await listMaterials()
    flash('已保存这段材料，请核对识别结果。')
    showDetail(result)
  } catch (cause) {
    fail(cause)
  } finally {
    busy.value = false
    busyText.value = ''
  }
}

/* ------------------------------------------------------------------ 详情 */

async function select(id) {
  if (detail.value?.material?.id === id && draft.value) {
    closeDetail()
    return
  }
  try {
    const fetched = await fetchMaterial(id)
    showDetail(fetched)
  } catch (cause) {
    fail(cause)
  }
}

function showDetail(payload) {
  detail.value = payload
  const profile = payload?.extraction?.profile
  // 没有抽取结果（扫描件、解析失败、没 token）时不展示可编辑表单，
  // 页面会直接显示材料自带的 message，告诉用户下一步做什么
  draft.value = profile
    ? {
        summary: profile.summary || '',
        highestEducation: profile.highestEducation || '',
        yearsOfExperience: profile.yearsOfExperience ?? 0,
        educations: [...(profile.educations || [])],
        experiences: [...(profile.experiences || [])],
        projects: [...(profile.projects || [])],
        skills: [...(profile.skills || [])],
        highlights: [...(profile.highlights || [])],
        warnings: [...(profile.warnings || [])]
      }
    : null

  const base = getProfile()
  targetTitle.value = base.targetTitle || ''
  targetCities.value = (base.targetCities || []).join('、')
  expectedSalary.value = base.expectedSalary || ''
  skillsText.value = (draft.value?.skills || []).join(', ')
  hasToken.value = hasAiKey()
  if (typeof document !== 'undefined') {
    window.setTimeout(() => {
      document.querySelector('.extraction-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 60)
  }
}

function closeDetail() {
  detail.value = null
  draft.value = null
}

function removeItem(key, index) {
  draft.value?.[key]?.splice(index, 1)
}

/* ------------------------------------------------------------------ 删除 */

async function remove(item) {
  if (!window.confirm(`删除「${item.fileName}」？原文件、抽出的原文和识别结果都会一起删掉。`)) {
    return
  }
  try {
    await deleteMaterial(item.id)
    if (detail.value?.material?.id === item.id) closeDetail()
    materials.value = await listMaterials()
    flash('已删除。')
  } catch (cause) {
    fail(cause)
  }
}

/* ------------------------------------------------------------------ 确认 */

/** 把「识别的经历」拼成人能读的一行，喂给岗位模块的 profile.experiences。 */
function experienceLines() {
  return (draft.value?.experiences || []).map((item) => {
    const head = [item.company, item.role, item.period].filter(Boolean).join(' · ')
    return item.description ? `${head}：${item.description}` : head
  }).filter(Boolean)
}

/**
 * 教育经历同样拼成一行。
 *
 * 为什么要单独一个字段：画像里的 {@code education} 只有学历层次（"本科"），
 * 那是岗位匹配的打分维度；而个人资料页要按「教育经历」展示，
 * 需要学校 / 专业 / 时间，所以后端追加了 {@code educations}。
 */
function educationLines() {
  return (draft.value?.educations || []).map((item) => {
    return [item.school, item.degree, item.major, item.period].filter(Boolean).join(' · ')
  }).filter(Boolean)
}

async function confirmProfile() {
  if (!draft.value) return
  const base = getProfile()
  // 与后端 ProfileDto / UserProfileSnapshot 字段一一对应，两侧可以直接互相透传
  const profile = {
    userId: base.userId || 'demo-user',
    targetTitle: targetTitle.value || base.targetTitle || '',
    targetCities: targetCities.value
      ? targetCities.value.split(/[、,，\s]+/).filter(Boolean)
      : (base.targetCities || []),
    yearsOfExperience: Number(draft.value.yearsOfExperience) || 0,
    education: draft.value.highestEducation || base.education || '',
    educations: educationLines().length ? educationLines() : (base.educations || []),
    skills: (skillsText.value ? skillsText.value.split(/[,，、\s]+/).filter(Boolean) : draft.value.skills),
    industries: base.industries || [],
    expectedSalary: expectedSalary.value || base.expectedSalary || '',
    summary: draft.value.summary || base.summary || '',
    experiences: experienceLines().length ? experienceLines() : (base.experiences || [])
  }

  saving.value = true
  error.value = ''
  try {
    // 本地先写：岗位页读的是 localStorage，写完立刻生效
    cacheProfile(profile)
    // 再写服务端：将来简历 / 面试模块从服务端读画像
    await putProfile(profile)
    flash('已保存为你的求职画像，岗位匹配页会立刻用上它。')
    closeDetail()
  } catch (cause) {
    // 本地已经写成功了，所以这里说清楚"哪一半失败了"，不要让用户以为全没保存
    fail(new Error(`画像已存到本机，但同步到后端失败：${cause?.message || '未知错误'}。岗位匹配页仍会使用本机的画像。`))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
/* 原型 CSS 里只有 status-ready，这里补上另外两种状态与抽取面板的样式。
   放在组件内而不是共享 CSS，是为了不和队友正在改的全局样式冲突。 */
.status-warn { color: #9a7b3f; background: #f8f1e2; }
.status-warn i { background: #c9a24a; }
.status-error { color: #a05a52; background: #f9ecea; }
.status-error i { background: #c07a70; }
.file-txt { color: #6f7d6b; background: #f1f4ee; border-color: #e2e8dc; }

.list-hint { color: #a8b0a6; font-size: 8px; }
.list-empty { margin: 0; padding: 18px 2px; color: #a4ada3; font-size: 9px; }
.material-item { cursor: pointer; padding: 0 6px; border-radius: 3px; transition: background .15s; }
.material-item:hover { background: #f7f9f3; }
.material-item.is-active { background: #f1f5ea; box-shadow: inset 2px 0 0 #7d9a70; }
.material-item > .icon-button { color: #b3bbb2; font-size: 10px; }
.material-item > .icon-button:hover { color: #b06a60; }

.extraction-panel { margin-top: 16px; padding: 20px 20px 18px; }
.extract-split { margin-top: 14px; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.25fr); gap: 16px; align-items: start; }
.extract-subtitle { margin-bottom: 7px; color: #7d8a7c; font-size: 8px; letter-spacing: .1em; text-transform: uppercase; }
.extract-subtitle i { margin-left: 4px; color: #a8b2a4; font-style: normal; }
.source-text { max-height: 520px; margin: 0; padding: 12px; overflow: auto; border: 1px solid #e8ebe4; border-radius: 4px; color: #5c6a5e; background: #fdfdfa; font: 8px/1.9 var(--mono, monospace); white-space: pre-wrap; word-break: break-word; }

.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }
.field-grid label, .field-block { display: flex; flex-direction: column; gap: 4px; }
.field-grid span, .field-block span { color: #98a296; font-size: 8px; }
.field-grid input, .field-block input { padding: 7px 9px; border: 1px solid #e8ebe4; border-radius: 4px; color: #55645a; background: #fdfdfa; font-size: 9px; outline: none; }
.field-grid input:focus, .field-block input:focus { border-color: #a4b79c; box-shadow: 0 0 0 3px rgba(115, 151, 112, .08); }
.field-block { margin-top: 10px; }

.extract-group { margin-top: 14px; }
.group-empty { margin: 0; color: #aab3a8; font-size: 8px; }
.extract-item { margin-bottom: 7px; padding: 9px 10px; border: 1px solid #ecefe7; border-radius: 4px; background: #fcfdfa; }
.extract-item-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.extract-item-head strong { color: #536257; font-size: 9px; font-weight: 600; }
.extract-item-head .icon-button { color: #bcc3ba; font-size: 9px; flex: 0 0 auto; }
.extract-item-head .icon-button:hover { color: #b06a60; }
.extract-item-body { margin: 5px 0 0; color: #7f8b7e; font-size: 8px; line-height: 1.8; }
.extract-evidence { margin: 6px 0 0; padding-left: 7px; border-left: 2px solid #dfe6d6; color: #9aa496; font-size: 8px; line-height: 1.7; }
.highlight-list { margin: 0; padding-left: 15px; color: #7f8b7e; font-size: 8px; line-height: 1.9; }

.extract-hint, .extract-warning, .extract-blocked { margin: 10px 0 0; padding: 10px 12px; border-radius: 4px; font-size: 9px; line-height: 1.8; }
.extract-hint { color: #8a7b52; background: #fbf6e9; border: 1px solid #f0e6cd; }
.extract-hint a { color: #6f8a5f; }
.extract-warning { color: #8a7852; background: #fdf9f0; border: 1px solid #f2ead8; }
.extract-blocked { color: #8a6a62; background: #fbf1ef; border: 1px solid #f0ded9; }

.extract-actions { margin-top: 16px; padding-top: 13px; border-top: 1px solid #ecefe7; display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
.extract-actions > span { color: #98a296; font-size: 8px; }
.extract-actions .button { min-height: 31px; font-size: 8px; }
.extract-actions > div { display: flex; gap: 8px; }

@media (max-width: 900px) {
  .extract-split { grid-template-columns: 1fr; }
  .field-grid { grid-template-columns: 1fr; }
}
</style>
