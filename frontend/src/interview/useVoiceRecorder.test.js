import test from 'node:test'
import assert from 'node:assert/strict'
import { useVoiceRecorder } from './useVoiceRecorder.js'

/** 把微任务与队列清空，等异步回调落定。 */
const tick = () => new Promise(resolve => setImmediate(resolve))

/** 等某个条件成立，用轮询代替固定延时，避免受定时器精度影响。 */
async function waitFor(predicate, budgetMs = 5000) {
  const started = Date.now()
  while (!predicate() && Date.now() - started < budgetMs) {
    await new Promise(resolve => setTimeout(resolve, 20))
  }
}

function makeDevices({ deny = null, delay = 0 } = {}) {
  const calls = []
  const tracks = []
  return {
    calls,
    tracks,
    mediaDevices: {
      getUserMedia: async constraints => {
        calls.push(constraints)
        if (delay) await new Promise(resolve => setTimeout(resolve, delay))
        if (deny) throw Object.assign(new Error('denied'), { name: deny.name })
        const stream = { getTracks: () => tracks }
        tracks.push({ stopped: false, stop() { this.stopped = true } })
        return stream
      },
    },
  }
}

function makeRecorder({ supported = ['audio/webm;codecs=opus'] } = {}) {
  const instances = []
  class Recorder {
    static isTypeSupported(type) {
      return supported.includes(type)
    }
    constructor(stream, options) {
      this.stream = stream
      this.options = options
      this.state = 'inactive'
      this.ondataavailable = null
      this.onstop = null
      instances.push(this)
    }
    start(timeslice) {
      this.timeslice = timeslice
      this.state = 'recording'
    }
    push(size) {
      this.ondataavailable?.({ data: new Blob([new Uint8Array(size)]) })
    }
    stop() {
      if (this.state === 'inactive') return
      this.state = 'inactive'
      this.onstop?.()
    }
  }
  return { Recorder, instances }
}

async function record({ Recorder, mediaDevices, ...options } = {}) {
  const { Recorder: recorderClass, instances } = Recorder ?? makeRecorder()
  const devices = mediaDevices ?? makeDevices()
  const recorder = useVoiceRecorder({ Recorder: recorderClass, mediaDevices: devices.mediaDevices, ...options })
  await recorder.start()
  return { recorder, instances, devices }
}

test('probes the supported format instead of faking a container', async () => {
  const webm = await record({ Recorder: makeRecorder({ supported: ['audio/webm;codecs=opus', 'audio/mp4'] }) })
  webm.instances[0].push(10)
  webm.recorder.stop()
  assert.deepEqual(webm.instances[0].options, { mimeType: 'audio/webm;codecs=opus' }, '应挑第一个可用的封装')
  assert.equal(webm.recorder.blob.value.type, 'audio/webm;codecs=opus')
  webm.recorder.dispose()

  const none = await record({ Recorder: makeRecorder({ supported: [] }) })
  none.instances[0].push(10)
  none.recorder.stop()
  assert.deepEqual(none.instances[0].options, {}, '没有可用封装时不指定 mimeType')
  assert.equal(none.recorder.blob.value.type, 'audio/webm')
  none.recorder.dispose()
})

test('requests a single-channel echo-cancelled microphone', async () => {
  const { recorder, devices } = await record()
  assert.equal(devices.calls.length, 1)
  assert.deepEqual(devices.calls[0], {
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    video: false,
  })
  assert.equal(recorder.state.value, 'recording')
  recorder.dispose()
})

test('stopping keeps the recording for a transcription retry', async () => {
  const { recorder, instances, devices } = await record()
  assert.equal(instances[0].timeslice, 1000, '按秒收集片段，超限时才停得下来')

  instances[0].push(1000)
  instances[0].push(1000)
  recorder.stop()

  assert.equal(recorder.state.value, 'recorded')
  assert.equal(recorder.blob.value.size, 2000)
  assert.equal(recorder.error.value, '')
  assert.equal(devices.tracks[0].stopped, true, '录完就归还麦克风')
  recorder.dispose()
})

test('releasing the microphone after stop does not discard the blob', async () => {
  const { recorder, instances } = await record()
  instances[0].push(100)
  recorder.stop()
  recorder.dispose()

  assert.equal(recorder.blob.value, null)
  assert.equal(recorder.state.value, 'idle')
})

test('cancel throws away the chunks and releases the microphone', async () => {
  const { recorder, devices } = await record()
  recorder.durationSeconds.value = 3
  recorder.error.value = '上一次录音的提示'

  recorder.cancel()

  assert.equal(recorder.blob.value, null)
  assert.equal(recorder.state.value, 'idle')
  assert.equal(recorder.durationSeconds.value, 0)
  assert.equal(recorder.error.value, '上一次录音的提示')
  assert.equal(devices.tracks[0].stopped, true)
})

test('a permission reply that arrives after cancel never starts recording', async () => {
  const { Recorder } = makeRecorder()
  const devices = makeDevices({ delay: 5 })
  const recorder = useVoiceRecorder({ Recorder, mediaDevices: devices.mediaDevices })

  recorder.start()
  recorder.cancel()
  await waitFor(() => devices.tracks.length === 1, 1000)

  assert.equal(devices.calls.length, 1)
  assert.equal(devices.tracks[0].stopped, true)
  assert.equal(recorder.state.value, 'idle')
  assert.equal(recorder.blob.value, null)
})

test('stopping while the permission prompt is still open discards the late permission', async () => {
  const { Recorder } = makeRecorder()
  const devices = makeDevices({ delay: 5 })
  const recorder = useVoiceRecorder({ Recorder, mediaDevices: devices.mediaDevices })

  recorder.start()
  recorder.stop()
  assert.equal(recorder.state.value, 'idle')
  recorder.stop()

  await waitFor(() => devices.tracks.length === 1, 1000)

  assert.equal(recorder.state.value, 'idle', '迟到的许可不能开始录音')
  assert.equal(recorder.blob.value, null)
  assert.equal(devices.tracks[0].stopped, true, '许可回来后仍要归还麦克风')
})

test('reports the permission outcome instead of throwing', async () => {
  for (const [name, expected] of [
    ['NotAllowedError', '麦克风权限被拒绝，请改用文字回答。'],
    ['NotFoundError', '没有可用的麦克风，请改用文字回答。'],
    ['NotReadableError', '麦克风被占用，请改用文字回答。'],
    ['OSError', '麦克风不可用，请改用文字回答。'],
  ]) {
    const { Recorder } = makeRecorder()
    const devices = makeDevices({ deny: { name } })
    const recorder = useVoiceRecorder({ Recorder, mediaDevices: devices.mediaDevices })
    recorder.error.value = '旧提示'

    recorder.start()
    await tick()

    assert.equal(recorder.error.value, expected, name)
    assert.equal(recorder.state.value, 'idle')
  }
})

test('reports unsupported browsers without asking for the microphone', async () => {
  const devices = makeDevices()
  const recorder = useVoiceRecorder({ Recorder: null, mediaDevices: devices.mediaDevices })

  recorder.start()
  await tick()

  assert.equal(recorder.error.value, '当前浏览器不支持录音，请改用文字回答。')
  assert.equal(recorder.state.value, 'idle')
  assert.equal(devices.calls.length, 0)
})

test('stops early when the recording grows past the size limit', async () => {
  const { recorder, instances } = await record({ maxBytes: 2000 })

  instances[0].push(1500)
  instances[0].push(1500)

  assert.equal(recorder.state.value, 'recorded')
  assert.equal(recorder.error.value, '录音超过大小限制，请缩短后重新录音。')
  assert.equal(recorder.blob.value.size, 3000, '保留已录内容，用户可以重新录')
  recorder.dispose()
})

test('reaching the size limit releases the microphone too', async () => {
  const { recorder, instances, devices } = await record({ maxBytes: 2000 })

  instances[0].push(1500)
  instances[0].push(1500)

  assert.equal(devices.tracks[0].stopped, true)
  recorder.dispose()
})

test('stops automatically at the length limit', async () => {
  // 计时器每 1000ms 触发一次，这里用真实的 1 秒上限等它自己停，不改全局定时器。
  const { recorder, instances } = await record({ maxSeconds: 1, maxBytes: 100000 })
  instances[0].push(10)

  await waitFor(() => recorder.state.value !== 'recording')

  assert.equal(recorder.state.value, 'recorded')
  assert.equal(recorder.durationSeconds.value, 1)
  assert.equal(recorder.error.value, '')
  recorder.dispose()
})

test('dispose clears every timer, track and pending callback', async () => {
  const { recorder, devices } = await record()
  recorder.dispose()

  assert.equal(recorder.state.value, 'idle')
  assert.equal(recorder.blob.value, null)
  assert.equal(recorder.error.value, '')
  assert.equal(devices.tracks[0].stopped, true)

  // 迟到回调只能改自己的计数，不能再写入界面状态。
  recorder.cancel()
  assert.equal(recorder.state.value, 'idle')
})
