import test from 'node:test'
import assert from 'node:assert/strict'
import { toConversationMessages } from './conversationMessages.js'

const session = { sessionId: 's1', status: 'ACTIVE', currentQuestion: { questionId: 'q2', question: '下一题' },
  turns: [{ questionId: 'q1', question: '主问题', answer: '我的回答' }, { questionId: 'f1', question: '追问', answer: null }],
  questionMetadata: { q1: { kind: 'MAIN' }, f1: { kind: 'FOLLOW_UP' } } }

test('maps stable question and answer IDs, and does not duplicate the current snapshot question', () => {
  const messages = toConversationMessages(session)
  assert.deepEqual(messages.map(({ id, role, questionId }) => [id, role, questionId]), [
    ['q1:question', 'assistant', 'q1'], ['q1:answer', 'user', 'q1'], ['f1:question', 'assistant', 'f1'], ['q2:question', 'assistant', 'q2'],
  ])
  assert.deepEqual(toConversationMessages(session), messages)
})

test('represents an unconfirmed submission and generation status without fabricating an answer', () => {
  const snapshot = { ...session, currentQuestion: null, turns: [] }
  assert.equal(toConversationMessages({ ...snapshot, turns: [{ questionId: 'q1', question: '问题' }] }, { questionId: 'q1', answer: '未确认' })[1].status, 'unconfirmed')
  assert.deepEqual(toConversationMessages(snapshot, null, 'GENERATING').map(item => item.status), ['pending'])
})

test('finished sessions ignore a late active generation state', () => {
  assert.deepEqual(toConversationMessages({ ...session, status: 'FINISHED', currentQuestion: null }, null, 'GENERATING').map(item => item.id), ['q1:question', 'q1:answer', 'f1:question'])
})
