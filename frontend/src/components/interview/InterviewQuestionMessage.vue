<script setup>
defineProps({ message: { type: Object, required: true }, voice: { type: Object, default: null }, sessionId: { type: String, default: '' } })
</script>

<template>
  <article class="conversation-message" :class="[`is-${message.role}`, { 'is-pending': message.status !== 'complete' }]" :data-message-id="message.id">
    <span class="conversation-role">{{ message.role === 'user' ? '你' : message.kind === 'STATUS' ? '面试官' : '面试官' }}</span>
    <p v-if="message.kind === 'ALGORITHM'" class="algorithm-answer-text">{{ message.text }}</p>
    <p v-else>{{ message.text }}</p>
    <button v-if="message.role === 'assistant' && message.kind !== 'STATUS' && voice" class="text-button message-speech" :disabled="!voice.ttsEnabled || voice.busy" @click="voice.speechQuestionId === message.questionId && ['loading', 'playing'].includes(voice.speechState) ? voice.stopQuestion() : voice.playQuestion({ sessionId, questionId: message.questionId })">{{ voice.speechQuestionId === message.questionId && voice.speechState === 'loading' ? '正在生成语音 · 停止' : voice.speechQuestionId === message.questionId && voice.speechState === 'playing' ? '停止朗读' : '朗读此题' }}</button>
    <small v-if="message.status === 'unconfirmed'">提交结果尚未确认</small>
  </article>
</template>
