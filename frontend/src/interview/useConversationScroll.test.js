import test from 'node:test'
import assert from 'node:assert/strict'
import { useConversationScroll } from './useConversationScroll.js'

test('tracks latest position, follows within threshold and preserves history reading position', () => {
  const el = { scrollHeight: 1000, scrollTop: 850, clientHeight: 100, scrollTo({ top }) { this.scrollTop = top } }
  const changes = []
  const scroll = useConversationScroll({ threshold: 80, getElement: () => el, onChange: value => changes.push(value) })
  scroll.onMessagesChanged([{ id: 'first', status: 'complete' }])
  assert.equal(el.scrollTop, 1000)
  el.scrollTop = 830; scroll.onScroll()
  scroll.onMessagesChanged([{ id: 'first', status: 'complete' }, { id: 'second', status: 'complete' }])
  assert.equal(el.scrollTop, 1000)
  el.scrollTop = 200; scroll.onScroll()
  scroll.onMessagesChanged([{ id: 'first', status: 'complete' }, { id: 'second', status: 'complete' }, { id: 'third', status: 'complete' }])
  assert.equal(el.scrollTop, 200)
  assert.equal(scroll.hasNewMessages, true)
  assert.deepEqual(changes.slice(-1), [true])
  scroll.scrollToLatest()
  assert.equal(scroll.hasNewMessages, false)
})

test('repeated snapshots with unchanged IDs and status do not trigger duplicate changes', () => {
  let calls = 0
  const scroll = useConversationScroll({ getElement: () => ({ scrollHeight: 1, scrollTop: 0, clientHeight: 1, scrollTo() {} }), onChange: () => calls++ })
  const messages = [{ id: 'q1:question', status: 'complete' }]
  scroll.onMessagesChanged(messages); const first = calls
  scroll.onMessagesChanged(messages)
  assert.equal(calls, first)
})
