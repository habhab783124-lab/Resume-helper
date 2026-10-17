<script setup>
import InterviewAttempt from './InterviewAttempt.vue'
defineProps({ message: { type: Object, required: true }, voice: { type: Object, default: null }, sessionId: { type: String, default: '' } })
</script>

<template>
  <article class="conversation-message" :class="[`is-${message.role}`, { 'is-pending': message.status === 'pending' || message.status === 'unconfirmed', 'is-withdrawn': message.status === 'withdrawn' }]" :data-message-id="message.id">
    <span class="conversation-role">{{ message.role === 'user' ? '你' : message.kind === 'STATUS' ? '面试官' : '面试官' }}</span>
    <p v-if="message.kind === 'ALGORITHM'" class="algorithm-answer-text">{{ message.text }}</p>
    <p v-else>{{ message.text }}</p>
    <button v-if="message.role === 'assistant' && message.kind !== 'STATUS' && voice" class="text-button message-speech" :disabled="!voice.ttsEnabled || voice.busy" @click="voice.speechQuestionId === message.questionId && ['loading', 'playing'].includes(voice.speechState) ? voice.stopQuestion() : voice.playQuestion({ sessionId, questionId: message.questionId })">{{ voice.speechQuestionId === message.questionId && voice.speechState === 'loading' ? '正在生成语音 · 停止' : voice.speechQuestionId === message.questionId && voice.speechState === 'playing' ? '停止朗读' : '朗读此题' }}</button>
    <small v-if="message.status === 'unconfirmed'">提交结果尚未确认</small>
    <small v-if="message.status === 'withdrawn'">已按你的说明撤下，不计入评分</small>
    <InterviewAttempt v-if="message.role === 'user' && message.attemptEnabled" :session-id="sessionId" :question-id="message.questionId" :question-text="message.questionText" :original-answer="message.text" />
  </article>
</template>
