import test from 'node:test'
import assert from 'node:assert/strict'
import { useInterviewHistory } from './useInterviewHistory.js'

test('history appends cursor pages without duplicate sessions and removes only after server success', async () => {
  const pages = [
    { items: [{ id: 'a' }, { id: 'b' }], nextCursor: 'next' },
    { items: [{ id: 'b' }, { id: 'c' }], nextCursor: null }
  ]
  const deleted = []
  const history = useInterviewHistory({ api: {
    async listHistory() { return pages.shift() },
    async deleteSession(id) { deleted.push(id) }
  }, scope: { userId: 'alice' } })
  await history.loadMore(); await history.loadMore()
  assert.deepEqual(history.items.value.map(item => item.id), ['a', 'b', 'c'])
  assert.equal(await history.remove('b'), true)
  assert.deepEqual(deleted, ['b'])
  assert.deepEqual(history.items.value.map(item => item.id), ['a', 'c'])
  history.dispose()
})

test('an empty history page settles with no cursor and no error', async () => {
  const history = useInterviewHistory({ api: { async listHistory() { return { items: [], nextCursor: null } } }, scope: {} })
  await history.loadMore()
  assert.deepEqual(history.items.value, [])
  assert.equal(history.cursor.value, null)
  assert.equal(history.error.value, '')
  history.dispose()
})

test('history discards a response after scope changes and reports deletion failure', async () => {
  let userId = 'alice'
  let release
  const history = useInterviewHistory({ api: {
    listHistory: () => new Promise(resolve => { release = resolve }),
    async deleteSession() { throw new Error('offline') }
  }, scope: () => ({ userId }) })
  const pending = history.loadMore()
  userId = 'bob'
  release({ items: [{ id: 'private-a' }], nextCursor: null })
  await pending
  assert.deepEqual(history.items.value, [])
  assert.equal(await history.remove('private-a'), false)
  assert.equal(history.error.value, 'offline')
  history.dispose()
})

test('a pending history page cannot restore a session after deletion succeeds', async () => {
  let releasePage
  const history = useInterviewHistory({ api: {
    listHistory: () => new Promise(resolve => { releasePage = resolve }),
    async deleteSession() {}
  }, scope: { userId: 'alice' } })
  const pending = history.loadMore()
  assert.equal(await history.remove('deleted-session'), true)
  releasePage({ items: [{ id: 'deleted-session' }, { id: 'still-active' }], nextCursor: null })
  await pending
  assert.deepEqual(history.items.value.map(item => item.id), ['still-active'])
  history.dispose()
})
