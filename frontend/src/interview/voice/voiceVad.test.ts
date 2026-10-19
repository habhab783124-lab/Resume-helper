import assert from 'node:assert/strict'
import test from 'node:test'
import { createVoiceVad } from './voiceVad.ts'

test('one loud frame is only a candidate, not confirmed speech', () => {
  const events: string[] = []
  const vad = createVoiceVad({ now: () => 0, onCandidate: () => events.push('candidate'),
    onSpeechStart: () => events.push('start'), onSpeechEnd: () => events.push('end') })
  vad.feed(0.25, 20)
  for (let i = 0; i < 20; i++) vad.feed(0, 20)
  assert.deepEqual(events, ['candidate'])
})

test('300 ms window confirms when at least 80 percent exceeds threshold and ends after two seconds silence', () => {
  let now = 0
  const events: string[] = []
  const vad = createVoiceVad({ now: () => now, onCandidate: () => events.push('candidate'),
    onSpeechStart: () => events.push('start'), onSpeechEnd: () => events.push('end') })
  for (let i = 0; i < 15; i++) { now += 20; vad.feed(0.08, 20) }
  assert.deepEqual(events, ['candidate', 'start'])
  for (let i = 0; i < 99; i++) { now += 20; vad.feed(0, 20) }
  assert.deepEqual(events, ['candidate', 'start'])
  now += 20; vad.feed(0, 20)
  assert.deepEqual(events, ['candidate', 'start', 'end'])
})

test('a quiet headset voice above its ambient floor is detected and ends after silence', () => {
  let now = 0
  const events: string[] = []
  const vad = createVoiceVad({ now: () => now, onCandidate: () => events.push('candidate'),
    onSpeechStart: () => events.push('start'), onSpeechEnd: () => events.push('end') })
  for (let i = 0; i < 25; i++) { now += 20; vad.feed(0.0007, 20) }
  assert.deepEqual(events, [])
  for (let i = 0; i < 50; i++) { now += 20; vad.feed(0.0025, 20) }
  assert.deepEqual(events, ['candidate', 'start'])
  for (let i = 0; i < 100; i++) { now += 20; vad.feed(0.0007, 20) }
  assert.deepEqual(events, ['candidate', 'start', 'end'])
})

test('quiet speech immediately after capture starts is not learned as background noise', () => {
  let now = 0
  const events: string[] = []
  const vad = createVoiceVad({ now: () => now, onSpeechStart: () => events.push('start') })
  for (let i = 0; i < 35; i++) { now += 20; vad.feed(0.0025, 20) }
  assert.deepEqual(events, ['start'])
})

test('playback grace and echo tail use the stricter playback threshold', () => {
  let now = 0
  const events: string[] = []
  const vad = createVoiceVad({ now: () => now, onCandidate: () => events.push('candidate'),
    onSpeechStart: () => events.push('start'), onSpeechEnd: () => events.push('end') })
  vad.setPlayback(true)
  for (let i = 0; i < 24; i++) { now += 20; vad.feed(0.25, 20) }
  assert.deepEqual(events, [])
  for (let i = 0; i < 15; i++) { now += 20; vad.feed(0.08, 20) }
  assert.deepEqual(events, ['candidate', 'start'])
  vad.setPlayback(false)
  for (let i = 0; i < 12; i++) { now += 20; vad.feed(0, 20) }
  for (let i = 0; i < 15; i++) { now += 20; vad.feed(0.08, 20) }
  assert.equal(events.filter(event => event === 'start').length, 1)
})

test('reset clears a partial window and playback state', () => {
  let now = 0
  const events: string[] = []
  const vad = createVoiceVad({ now: () => now, onCandidate: () => events.push('candidate'),
    onSpeechStart: () => events.push('start'), onSpeechEnd: () => events.push('end') })
  vad.feed(0.08, 20)
  vad.reset()
  events.length = 0
  for (let i = 0; i < 15; i++) { now += 20; vad.feed(0, 20) }
  assert.deepEqual(events, [])
})
