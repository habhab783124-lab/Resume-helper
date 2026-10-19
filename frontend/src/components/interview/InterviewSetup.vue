<script setup>
const props = defineProps({ material: { type: Object, default: null }, paste: { type: String, default: '' },
  targetRole: { type: String, default: '' }, jobDescription: { type: String, default: '' },
  preferences: { type: Object, required: true }, planError: { type: String, default: '' }, starting: Boolean,
  allowAlgorithmRepeat: Boolean, repeatConsentRequired: Boolean, locked: Boolean })
const emit = defineEmits(['update:paste', 'update:targetRole', 'update:jobDescription', 'update:preferences', 'update:allowAlgorithmRepeat', 'upload', 'use-paste', 'start', 'dismiss-consent'])
function setPreference(key, value) { emit('update:preferences', { ...props.preferences, [key]: value }) }
</script>

<template>
  <section class="interview-setup" aria-labelledby="interview-setup-title">
    <div class="interview-session-top"><div><h2 id="interview-setup-title">开始面试</h2></div></div>
    <label class="field">选择简历 <span>PDF / DOCX / TXT</span>
      <input type="file" accept=".pdf,.docx,.txt" :disabled="locked" @change="emit('upload', $event.target.files[0]); $event.target.value = ''" />
    </label>
    <details class="paste-panel"><summary>或粘贴简历文本</summary><label class="field" for="resume-paste">简历内容</label>
      <textarea id="resume-paste" :value="paste" rows="6" maxlength="20000" :disabled="locked" placeholder="粘贴简历内容（最多 20,000 字）" @input="emit('update:paste', $event.target.value)"></textarea>
      <button class="button button-ghost" :disabled="locked || !paste.trim()" @click="emit('use-paste')">使用这份文本</button>
    </details>
    <label class="field">目标岗位（可选）<input :value="targetRole" maxlength="200" :disabled="locked" placeholder="例如：产品经理" @input="emit('update:targetRole', $event.target.value)" /></label>
    <label class="field">面试模式<select :value="preferences.mode" :disabled="locked" @change="setPreference('mode', $event.target.value)"><option value="QUICK">快速练习</option><option value="FULL">完整面试</option></select></label>
    <details class="interview-advanced"><summary>更多选项</summary>
      <label class="field">岗位描述<textarea :value="jobDescription" rows="4" maxlength="20000" :disabled="locked" placeholder="粘贴岗位描述" @input="emit('update:jobDescription', $event.target.value)"></textarea></label>
      <div class="setup-grid">
        <label v-if="preferences.mode === 'QUICK'" class="field">题目数<select :value="preferences.mainCount" :disabled="locked" @change="setPreference('mainCount', Number($event.target.value))"><option :value="3">3 题</option><option :value="5">5 题</option></select></label>
        <label class="field">难度<select :value="preferences.level" :disabled="locked" @change="setPreference('level', $event.target.value)"><option value="FOUNDATION">基础</option><option value="STANDARD">标准</option><option value="ADVANCED">进阶</option></select></label>
        <label class="field">职业阶段<select :value="preferences.careerStage" :disabled="locked" @change="setPreference('careerStage', $event.target.value)"><option value="INTERNSHIP">实习</option><option value="CAMPUS">校招</option><option value="EXPERIENCED">有经验</option></select></label>
        <label class="field">专项题<select :value="preferences.specialtyPreference" :disabled="locked" @change="setPreference('specialtyPreference', $event.target.value)"><option value="AUTO">自动匹配</option><option v-if="preferences.mode === 'QUICK'" value="NONE">不出专项题</option><option value="ALGORITHM">算法题</option><option value="CASE_STUDY">专业案例题</option></select></label>
      </div>
    </details>
    <p v-if="planError" class="notice error" role="alert">{{ planError }}</p>
    <p class="consent">简历和回答将用于生成面试内容</p>
    <button class="button button-primary" :disabled="locked || !material || starting || (repeatConsentRequired && !allowAlgorithmRepeat)" @click="emit('start')">{{ starting ? '正在准备…' : '开始面试' }} <span aria-hidden="true">→</span></button>
    <div v-if="repeatConsentRequired" class="repeat-consent-backdrop">
      <section class="repeat-consent-dialog" role="dialog" aria-modal="true" aria-labelledby="repeat-consent-title">
        <h3 id="repeat-consent-title">这类算法题已练习完</h3>
        <label><input type="checkbox" :checked="allowAlgorithmRepeat" :disabled="locked" @change="emit('update:allowAlgorithmRepeat', $event.target.checked)" /> 我同意重新抽取一道</label>
        <div><button class="button button-primary" :disabled="locked || !allowAlgorithmRepeat" @click="emit('start')">继续</button><button class="text-button" :disabled="locked" @click="emit('dismiss-consent')">返回修改设置</button></div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.interview-advanced { margin: 16px 0; }.interview-advanced summary { padding: 7px 0; color: #66746a; }
.repeat-consent-backdrop { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 18px; background: rgba(20,31,25,.38); }
.repeat-consent-dialog { width: min(100%, 420px); padding: 24px; border: 1px solid #e4eae5; border-radius: 18px; background: white; box-shadow: 0 18px 60px rgba(0,0,0,.18); }
.repeat-consent-dialog h3 { margin: 0 0 16px; }.repeat-consent-dialog label { display: flex; align-items: center; gap: 9px; margin-bottom: 18px; }.repeat-consent-dialog label input { width: auto; }.repeat-consent-dialog > div { display: flex; gap: 12px; align-items: center; }
</style>
