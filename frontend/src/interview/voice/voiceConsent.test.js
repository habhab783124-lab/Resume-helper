import test from 'node:test'
import assert from 'node:assert/strict'
import { createVoiceConsent } from './voiceConsent.js'

test('voice consent is remembered for the browser session and unavailable storage is safe', () => {
  const values = new Map()
  const storage = { getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, value) }
  const consent = createVoiceConsent({ storage })
  assert.equal(consent.accepted(), false)
  consent.accept()
  assert.equal(createVoiceConsent({ storage }).accepted(), true)
  assert.equal(createVoiceConsent({ storage: null }).accepted(), false)
})
