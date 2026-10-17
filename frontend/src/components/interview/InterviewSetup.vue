<script setup>
defineProps({ material: { type: Object, default: null }, paste: { type: String, default: '' },
  targetRole: { type: String, default: '' }, locked: { type: Boolean, default: false } })
const emit = defineEmits(['update:paste', 'update:targetRole', 'upload', 'use-paste', 'start'])
</script>

<template>
  <section class="interview-setup" aria-labelledby="interview-setup-title">
    <div class="interview-session-top"><div><span class="session-overline">BEFORE WE BEGIN</span><h2 id="interview-setup-title">准备你的简历</h2><span class="session-company">上传、确认内容，再开始面试</span></div></div>
    <label class="field">上传简历 <span>PDF / DOCX / TXT · 最大 10 MiB</span>
      <input type="file" accept=".pdf,.docx,.txt" :disabled="locked" @change="emit('upload', $event.target.files[0]); $event.target.value = ''" />
    </label>
    <details class="paste-panel">
      <summary>或粘贴简历文本</summary>
      <label class="field" for="resume-paste">简历内容</label>
      <textarea id="resume-paste" :value="paste" rows="6" maxlength="20000" :disabled="locked"
        placeholder="粘贴简历内容（最多 20,000 字）" @input="emit('update:paste', $event.target.value)"></textarea>
      <button class="button button-ghost" :disabled="locked || !paste.trim()" @click="emit('use-paste')">使用这份文本</button>
    </details>
    <div v-if="material" class="resume-preview">
      <h3>已解析 · {{ material.fileName }}</h3><p>请确认文字完整；扫描版 PDF 可改用粘贴文本。</p><pre>{{ material.text }}</pre>
    </div>
    <div class="setup-grid">
      <label class="field">目标岗位（可选）
        <input :value="targetRole" maxlength="200" :disabled="locked" placeholder="例如：运营、产品经理、Java 开发工程师"
          @input="emit('update:targetRole', $event.target.value)" />
      </label>
      <div class="interview-plan"><strong>12 道主问题 + 1 道岗位专项题</strong>
        <p>主问题合计 80%，专项题固定 20%。软件开发等编码岗位出 Hot 100 算法题，其他岗位出专业案例题。追问不计入总题数，并入对应主问题评分。</p>
        <p>优先按目标岗位匹配；未填写时参考简历职业方向。</p>
      </div>
    </div>
    <p class="consent">开始面试后，简历文本和回答将发送至配置的大模型服务</p>
    <button class="button button-primary" :disabled="locked || !material" @click="emit('start')">开始面试 <span aria-hidden="true">→</span></button>
  </section>
</template>
