<script setup>
const props = defineProps({ material: { type: Object, default: null }, paste: { type: String, default: '' },
  targetRole: { type: String, default: '' }, jobDescription: { type: String, default: '' },
  preferences: { type: Object, required: true }, plan: { type: Object, default: null }, planCurrent: Boolean,
  planError: { type: String, default: '' }, sourceSessionId: { type: String, default: '' }, preparingPlan: Boolean, confirmingPlan: Boolean, allowAlgorithmRepeat: Boolean, repeatConsentRequired: Boolean, locked: Boolean })
const emit = defineEmits(['update:paste', 'update:targetRole', 'update:jobDescription', 'update:preferences', 'update:allowAlgorithmRepeat', 'upload', 'use-paste', 'prepare', 'start'])
function setPreference(key, value) { emit('update:preferences', { ...props.preferences, [key]: value }) }
function levelLabel(level) { return ({ FOUNDATION: '基础', STANDARD: '标准', ADVANCED: '进阶' })[level] || level || '标准' }
</script>

<template>
  <section class="interview-setup" aria-labelledby="interview-setup-title">
    <div class="interview-session-top"><div><span class="session-overline">BEFORE WE BEGIN</span><h2 id="interview-setup-title">准备你的简历</h2><span class="session-company">选择面试方式，预览计划后开始</span></div></div>
    <label class="field">上传简历 <span>PDF / DOCX / TXT · 最大 10 MiB</span>
      <input type="file" accept=".pdf,.docx,.txt" :disabled="locked" @change="emit('upload', $event.target.files[0]); $event.target.value = ''" />
    </label>
    <details class="paste-panel"><summary>或粘贴简历文本</summary><label class="field" for="resume-paste">简历内容</label>
      <textarea id="resume-paste" :value="paste" rows="6" maxlength="20000" :disabled="locked" placeholder="粘贴简历内容（最多 20,000 字）" @input="emit('update:paste', $event.target.value)"></textarea>
      <button class="button button-ghost" :disabled="locked || !paste.trim()" @click="emit('use-paste')">使用这份文本</button>
    </details>
    <div v-if="material" class="resume-preview"><h3>已解析 · {{ material.fileName }}</h3><p>请确认文字完整；扫描版 PDF 可改用粘贴文本。</p><pre>{{ material.text }}</pre></div>
    <div class="setup-grid">
      <label class="field">目标岗位（可选）<input :value="targetRole" maxlength="200" :disabled="locked" placeholder="例如：运营、产品经理、Java 开发工程师" @input="emit('update:targetRole', $event.target.value)" /></label>
      <label class="field jd-field">岗位描述（可选）<textarea :value="jobDescription" rows="4" maxlength="20000" :disabled="locked" placeholder="从招聘信息粘贴完整岗位描述" @input="emit('update:jobDescription', $event.target.value)"></textarea></label>
      <label class="field">面试模式<select :value="preferences.mode" :disabled="locked" @change="setPreference('mode', $event.target.value)"><option value="QUICK">快速练习</option><option value="FULL">完整面试</option></select></label>
      <label v-if="preferences.mode === 'QUICK'" class="field">主问题数<select :value="preferences.mainCount" :disabled="locked" @change="setPreference('mainCount', Number($event.target.value))"><option :value="3">3 题</option><option :value="5">5 题</option></select></label>
      <label class="field">难度<select :value="preferences.level" :disabled="locked" @change="setPreference('level', $event.target.value)"><option value="FOUNDATION">基础</option><option value="STANDARD">标准</option><option value="ADVANCED">进阶</option></select></label>
      <label class="field">职业阶段<select :value="preferences.careerStage" :disabled="locked" @change="setPreference('careerStage', $event.target.value)"><option value="INTERNSHIP">实习</option><option value="CAMPUS">校招</option><option value="EXPERIENCED">有经验</option></select></label>
      <label class="field">岗位专项题<select :value="preferences.specialtyPreference" :disabled="locked" @change="setPreference('specialtyPreference', $event.target.value)"><option value="AUTO">自动匹配</option><option v-if="preferences.mode === 'QUICK'" value="NONE">不出专项题</option><option value="ALGORITHM">算法题</option><option value="CASE_STUDY">专业案例题</option></select></label>
    </div>
    <button class="button button-ghost" :disabled="locked || preparingPlan || !material" @click="emit('prepare')">{{ preparingPlan ? '正在生成计划…' : '生成计划预览' }}</button>
    <p v-if="planError" class="notice error" role="alert">{{ planError }}</p>
    <label v-if="repeatConsentRequired" class="field"><input type="checkbox" :checked="allowAlgorithmRepeat" :disabled="locked" @change="emit('update:allowAlgorithmRepeat', $event.target.checked)" /> 我已了解该难度题目已练习完，允许重新抽取</label>
    <div v-if="plan && planCurrent" class="interview-plan" role="status"><strong>计划已就绪</strong>
      <p>模式：{{ plan.plan?.mode || preferences.mode }} · 难度：{{ levelLabel(plan.plan?.level || preferences.level) }} · 主问题：{{ plan.plan?.mainCount ?? (preferences.mode === 'QUICK' ? preferences.mainCount : 12) }} 道 · 追问上限：{{ plan.plan?.followUpBudget ?? '按计划' }}</p>
      <p>覆盖方向：{{ (plan.plan?.coverage || []).map(x => typeof x === 'string' ? x : x.label).join('、') || '已按简历和岗位要求生成' }}</p>
      <p v-if="plan.plan?.specialty">专项：{{ plan.plan.specialty.label || plan.plan.specialty.kind }}<span v-if="plan.plan.specialty.reason"> · {{ plan.plan.specialty.reason }}</span></p>
      <div v-if="plan.plan?.practiceTargets?.length" class="focused-plan-targets"><strong>本轮针对以下报告重点</strong><ul><li v-for="target in plan.plan.practiceTargets" :key="target.issueId">{{ target.title }} · {{ target.action }}</li></ul><p v-if="sourceSessionId">来源报告：{{ sourceSessionId }}。回答和评分将形成独立新一轮。</p></div>
      <p v-if="plan.expiresAt">预览有效期至 {{ plan.expiresAt }}</p>
    </div>
    <p class="consent">开始面试后，简历文本和回答将发送至配置的大模型服务</p>
    <button class="button button-primary" :disabled="locked || !material || !planCurrent || confirmingPlan || (repeatConsentRequired && !allowAlgorithmRepeat)" @click="emit('start')">{{ confirmingPlan ? '正在确认计划…' : '确认计划并开始' }} <span aria-hidden="true">→</span></button>
  </section>
</template>
