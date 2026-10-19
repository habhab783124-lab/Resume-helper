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

<style scoped>
.conversation-history { flex: 1 1 auto; min-height: 0; max-height: none; overflow: auto; padding: 18px clamp(4px, 5vw, 42px) 22px; scroll-padding-bottom: 24px; }
.conversation-history ol { display: flex; flex-direction: column; gap: 18px; margin: 0; padding: 0; list-style: none; }
.conversation-history li { display: flex; }
.conversation-history :deep(.conversation-message) { max-width: min(88%, 900px); padding: 13px 17px; border: 0; border-radius: 18px 18px 18px 5px; background: #f2f5f2; }
.conversation-history :deep(.conversation-message.is-user) { margin-left: auto; border-radius: 18px 18px 5px 18px; background: #eaf2ec; }
.conversation-history :deep(.conversation-message p) { margin: 0; color: #293a30; font-size: 14px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.conversation-history :deep(.conversation-role) { display: block; margin-bottom: 5px; color: #859187; font-size: 11px; }
.conversation-history :deep(.message-speech) { margin-top: 8px; font-size: 12px; }
@media (max-width: 640px) { .conversation-history { padding: 13px 1px 18px; }.conversation-history :deep(.conversation-message) { max-width: 90%; padding: 11px 14px; }.conversation-history :deep(.conversation-message p) { font-size: 14px; } }
</style>
