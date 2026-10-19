import test from 'node:test'
import assert from 'node:assert/strict'
import { voiceComposerPresentation } from './voiceComposerPresentation.js'

test('voice button starts, pauses, and submits immediately from the corresponding controller phases', () => {
  assert.deepEqual(voiceComposerPresentation({ phase: 'OFF' }, true).action, 'start')
  assert.deepEqual(voiceComposerPresentation({ phase: 'LISTENING' }, true).action, 'pause')
  assert.deepEqual(voiceComposerPresentation({ phase: 'REVIEW' }, true).action, 'pause')
  assert.equal(voiceComposerPresentation({ phase: 'STARTING' }, true).disabled, false)
  assert.equal(voiceComposerPresentation({ phase: 'SUBMITTING' }, true).disabled, true)
})

test('speech capability fallback retains the manual recording entry when realtime is unavailable', () => {
  const result = voiceComposerPresentation({ phase: 'OFF' }, false)
  assert.equal(result.action, 'manual')
  assert.equal(result.label, '语音输入')
})
