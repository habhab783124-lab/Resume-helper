import test from 'node:test'
import assert from 'node:assert/strict'
import { useQuestionSpeech } from './useQuestionSpeech.js'

/** 把微任务与队列清空，等异步回调落定。 */
const tick = () => new Promise(resolve => setImmediate(resolve))

/** 可以手动 resolve / reject 的朗读请求，用来模拟「用户已经切题，旧题的音频才回来」。 */
function makeApi() {
  const requests = []
  return {
    requests,
    api: {
      questionSpeech: (sessionId, questionId, signal) => {
        const request = { key: `${sessionId}:${questionId}`, signal, resolve: null, reject: null }
        requests.push(request)
        return new Promise((resolve, reject) => {
          request.resolve = resolve
          request.reject = reject
        })
      },
    },
  }
}

function makeAudioFactory() {
  const audio = []
  const createAudio = src => {
    const element = {
      src,
      plays: 0,
      paused: false,
      currentTime: 0,
      onended: null,
      onerror: null,
      play() {
        this.plays += 1
        this.paused = false
        return Promise.resolve()
      },
      pause() { this.paused = true },
      removeAttribute(name) { if (name === 'src') this.src = '' },
      load() {},
    }
    audio.push(element)
    return element
  }
  return { audio, createAudio }
}

function harness() {
  const { requests, api } = makeApi()
  const { audio, createAudio } = makeAudioFactory()
  const urls = new Map()
  const revoked = []
  let next = 0
  const speech = useQuestionSpeech({
    api,
    createAudio,
    createUrl: blob => {
      const url = `url-${++next}`
      urls.set(url, blob)
      return url
    },
    revokeUrl: url => revoked.push(url),
  })
  return { speech, audio, urls, revoked, requests }
}

test('plays the question audio and reports progress', async () => {
  const { speech, audio, urls, revoked, requests } = harness()

  speech.play('s1', 'q1')
  assert.equal(speech.state.value, 'loading')
  assert.equal(speech.error.value, '')

  requests[0].resolve(new Blob(['audio bytes']))
  await tick()

  assert.equal(speech.state.value, 'playing')
  assert.equal(audio.length, 1)
  assert.equal(audio[0].plays, 1)
  assert.equal(urls.get(audio[0].src).size, 11)
  assert.equal(revoked.length, 0, '播完之前不撤销 URL，还要能重播')
  speech.dispose()
  assert.deepEqual(revoked, ['url-1'])
})

test('a late response for a previous question is dropped', async () => {
  const { speech, audio, urls, requests } = harness()

  speech.play('s1', 'q1')
  speech.play('s1', 'q2')
  requests[0].resolve(new Blob(['q1 audio']))
  await tick()

  assert.deepEqual(requests.map(request => request.key), ['s1:q1', 's1:q2'])
  assert.equal(requests[0].signal.aborted, true, '旧题的下载已取消')
  assert.equal(urls.size, 0, '旧题的音频既不能建 URL 也不能播放')
  assert.equal(audio.length, 0)
  assert.equal(speech.state.value, 'loading')

  requests[1].resolve(new Blob(['q2 audio']))
  await tick()

  assert.equal(speech.state.value, 'playing')
  assert.equal(audio.length, 1)
  assert.equal(urls.size, 1)
  assert.equal(await urls.get(audio[0].src).text(), 'q2 audio')
  speech.dispose()
})

test('switching questions cancels the download for the previous one', async () => {
  const { speech, requests } = harness()

  speech.play('s1', 'q1')
  speech.play('s1', 'q2')

  assert.equal(requests.length, 2)
  assert.equal(requests[0].signal.aborted, true)
  assert.equal(requests[1].signal.aborted, false)
  speech.dispose()
})

test('an error for a previous question does not overwrite the newer state', async () => {
  const { speech, requests } = harness()

  speech.play('s1', 'q1')
  speech.play('s1', 'q2')
  requests[0].reject(new Error('语音服务繁忙，请稍后再试'))
  await tick()

  assert.equal(speech.state.value, 'loading')
  assert.equal(speech.error.value, '')

  requests[1].resolve(new Blob(['q2 audio']))
  await tick()

  assert.equal(speech.state.value, 'playing')
  assert.equal(speech.error.value, '')
  speech.dispose()
})

test('replaying the same question reuses the cached audio', async () => {
  const { speech, audio, urls, requests } = harness()

  speech.play('s1', 'q1')
  requests[0].resolve(new Blob(['audio']))
  await tick()

  speech.play('s1', 'q1')
  await tick()

  assert.equal(requests.length, 1, '同题重播不应再花钱合成一次')
  assert.equal(urls.size, 1)
  assert.equal(audio.length, 1)
  assert.equal(audio[0].plays, 2)
  speech.dispose()
})

test('repeated plays while one request is in flight do not duplicate it', async () => {
  const { speech, requests } = harness()

  speech.play('s1', 'q1')
  speech.play('s1', 'q1')
  speech.play('s1', 'q1')

  assert.equal(requests.length, 1)
  requests[0].resolve(new Blob(['audio']))
  await tick()
  assert.equal(speech.state.value, 'playing')
  speech.dispose()
})

test('an ended playback can be replayed without a new request', async () => {
  const { speech, audio, requests } = harness()

  speech.play('s1', 'q1')
  requests[0].resolve(new Blob(['audio']))
  await tick()
  audio[0].onended()

  assert.equal(speech.state.value, 'stopped')
  assert.equal(audio[0].currentTime, 0)

  speech.play('s1', 'q1')
  await tick()
  assert.equal(audio[0].plays, 2)
  assert.equal(requests.length, 1)
  speech.dispose()
})

test('stop cancels an in-flight download and ignores its late response', async () => {
  const { speech, requests } = harness()

  speech.play('s1', 'q1')
  assert.equal(speech.state.value, 'loading')
  speech.stop()

  assert.equal(speech.state.value, 'idle')
  assert.equal(requests[0].signal.aborted, true)

  requests[0].resolve(new Blob(['audio']))
  await tick()
  assert.equal(speech.state.value, 'idle')
  assert.equal(speech.error.value, '')
})

test('stop pauses the playback but keeps the audio cached', async () => {
  const { speech, audio, revoked, requests } = harness()

  speech.play('s1', 'q1')
  requests[0].resolve(new Blob(['audio']))
  await tick()
  audio[0].currentTime = 12

  speech.stop()

  assert.equal(speech.state.value, 'stopped')
  assert.equal(audio[0].paused, true)
  assert.equal(audio[0].currentTime, 0)
  assert.equal(revoked.length, 0, '停播不撤销 URL，还要能重播')
  speech.dispose()
  assert.equal(revoked.length, 1)
})

test('dispose releases the audio and clears every callback', async () => {
  const { speech, audio, revoked, requests } = harness()

  speech.play('s1', 'q1')
  requests[0].resolve(new Blob(['audio']))
  await tick()

  speech.dispose()

  assert.equal(speech.state.value, 'idle')
  assert.equal(speech.error.value, '')
  assert.equal(audio[0].paused, true)
  assert.equal(audio[0].currentTime, 0)
  assert.equal(audio[0].src, '')
  assert.equal(audio[0].onended, null)
  assert.equal(audio[0].onerror, null)
  assert.deepEqual(revoked, ['url-1'])
})

test('replaying after stop retains the source and playback callbacks', async () => {
  const { speech, audio, requests } = harness()
  speech.play('s1', 'q1')
  requests[0].resolve(new Blob(['audio']))
  await tick()
  const source = audio[0].src
  speech.stop()
  speech.play('s1', 'q1')
  await tick()

  assert.equal(audio[0].src, source, '停止后重播仍须有音频源')
  assert.equal(typeof audio[0].onended, 'function', '重播结束须能退出 playing 状态')
  audio[0].onended()
  assert.equal(speech.state.value, 'stopped')
  assert.equal(requests.length, 1)
  speech.dispose()
})

test('dispose before the response lands leaves no audio behind', async () => {
  const { speech, audio, revoked, requests } = harness()

  speech.play('s1', 'q1')
  speech.dispose()
  assert.equal(speech.state.value, 'idle')

  requests[0].resolve(new Blob(['audio']))
  await tick()

  assert.equal(audio.length, 0)
  assert.equal(revoked.length, 0)
  assert.equal(speech.state.value, 'idle')
})

test('a server error becomes a message instead of audio', async () => {
  const { audio, createAudio } = makeAudioFactory()
  const api = {
    questionSpeech: async () => {
      throw Object.assign(new Error('语音服务未配置，文字面试仍可继续使用'), { status: 503 })
    },
  }
  const speech = useQuestionSpeech({ api, createAudio })

  speech.play('s1', 'q1')
  await tick()

  assert.equal(speech.error.value, '语音服务未配置，文字面试仍可继续使用')
  assert.equal(speech.state.value, 'idle')
  assert.equal(audio.length, 0)
})

test('an uncaught rejection falls back to a readable hint', async () => {
  const { audio, createAudio } = makeAudioFactory()
  const speech = useQuestionSpeech({ api: { questionSpeech: () => Promise.reject(new TypeError('网络中断')) }, createAudio })

  speech.play('s1', 'q1')
  await tick()

  assert.equal(speech.error.value, '题目语音生成失败，请稍后重试。')
  assert.equal(speech.state.value, 'idle')
})

test('an aborted request stays silent', async () => {
  const { audio, createAudio } = makeAudioFactory()
  const speech = useQuestionSpeech({
    api: { questionSpeech: () => Promise.reject(Object.assign(new Error('aborted'), { name: 'AbortError' })) },
    createAudio,
  })

  speech.play('s1', 'q1')
  await tick()

  assert.equal(speech.error.value, '')
  assert.equal(speech.state.value, 'idle')
  assert.equal(audio.length, 0)
})

for (const [name, expected] of [
  ['NotAllowedError', '浏览器阻止了自动播放，请再点一次播放。'],
  ['NotSupportedError', '题目音频无法播放，请重试。'],
]) {
  test(`a rejected ${name} autoplay surfaces a manual replay hint`, async () => {
    const audio = []
    const createAudio = () => {
      const element = {
        plays: 0,
        // 第一次被浏览器拦住，用户手动点第二次才放行——这正是这条文案要提示的场景。
        play() {
          this.plays += 1
          return this.plays === 1
            ? Promise.reject(Object.assign(new Error('rejected'), { name }))
            : Promise.resolve()
        },
        pause() {},
        removeAttribute() {},
        load() {},
      }
      audio.push(element)
      return element
    }
    const speech = useQuestionSpeech({
      api: { questionSpeech: async () => new Blob(['audio']) },
      createAudio,
    })

    speech.play('s1', 'q1')
    await tick()

    assert.equal(speech.error.value, expected)
    assert.equal(speech.state.value, 'stopped')

    speech.play('s1', 'q1')
    await tick()
    assert.equal(audio[0].plays, 2)
    assert.equal(speech.error.value, '')
    assert.equal(speech.state.value, 'playing')
    speech.dispose()
  })
}
