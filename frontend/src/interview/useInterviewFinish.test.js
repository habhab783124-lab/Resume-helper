import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive, ref } from 'vue'
import { useInterviewFinish } from './useInterviewFinish.js'

test('requestFinish preserves a recording, cancels in-flight transcription, and opens choices', async () => {
  const events = []
  const finish = useInterviewFinish({ isActive: () => true, voice: {
    recorderState: ref('recording'), transcribing: ref(true),
    async stopRecording(options) { events.push(['stop', options]) }, cancelTranscription() { events.push(['cancel-transcription']) },
  } })
  assert.equal(await finish.requestFinish(), true)
  assert.deepEqual(events, [['stop', { transcribe: false }], ['cancel-transcription']])
  assert.equal(finish.open.value, true)
})

test('continue closes the choice UI without clearing voice or conversation state', () => {
  const finish = useInterviewFinish({ isActive: () => true, voice: {} })
  finish.open.value = true
  finish.continueInterview()
  assert.equal(finish.open.value, false)
})

test('requestFinish reads reactive-unwrapped recorder and ASR state from the page', async () => {
  const events = []
  const voice = reactive({
    recorderState: ref('recording'), transcribing: ref(true),
    async stopRecording(options) { events.push(['stop', options]) },
    cancelTranscription() { events.push(['cancel-transcription']) },
  })
  const finish = useInterviewFinish({ isActive: () => true, voice })
  await finish.requestFinish()
  assert.deepEqual(events, [['stop', { transcribe: false }], ['cancel-transcription']])
})
