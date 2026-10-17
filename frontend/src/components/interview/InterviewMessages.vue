<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import InterviewQuestionMessage from './InterviewQuestionMessage.vue'
import { useConversationScroll } from '../../interview/useConversationScroll.js'

const props = defineProps({ messages: { type: Array, default: () => [] }, voice: { type: Object, default: null }, sessionId: { type: String, default: '' } })
const scroller = ref(null)
const hasNewMessages = ref(false)
const scroll = useConversationScroll({ threshold: 80, getElement: () => scroller.value, onChange: value => { hasNewMessages.value = value } })
const messageKey = computed(() => props.messages.map(message => `${message.id}:${message.status}`).join('|'))
watch(messageKey, async () => { await nextTick(); scroll.onMessagesChanged(props.messages) }, { immediate: true })
</script>

<template>
  <section class="conversation-history" aria-label="面试问答记录" ref="scroller" @scroll.passive="scroll.onScroll">
    <ol><li v-for="message in messages" :key="message.id"><InterviewQuestionMessage :message="message" :voice="voice" :session-id="sessionId" /></li></ol>
    <button v-if="hasNewMessages" class="conversation-latest" @click="scroll.scrollToLatest(true)">有新消息 · 回到最新</button>
  </section>
</template>
