import test from 'node:test'
import assert from 'node:assert/strict'
import { shouldSend, insertIndent } from './composerActions.js'

test('sends only Ctrl or Command Enter outside IME composition', () => {
  assert.equal(shouldSend({ key: 'Enter', ctrlKey: false, metaKey: false, isComposing: false }), false)
  assert.equal(shouldSend({ key: 'Enter', ctrlKey: true, isComposing: false }), true)
  assert.equal(shouldSend({ key: 'Enter', metaKey: true, isComposing: false }), true)
  assert.equal(shouldSend({ key: 'Enter', ctrlKey: true, isComposing: true }), false)
})

test('inserts four spaces at the cursor and keeps the remaining answer intact', () => {
  assert.deepEqual(insertIndent('abc\ndef', 2, 2), { text: 'ab    c\ndef', start: 6, end: 6 })
  assert.deepEqual(insertIndent('abc', 1, 2), { text: 'a    c', start: 5, end: 5 })
})
