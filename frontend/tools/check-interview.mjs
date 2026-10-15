/**
 * Local Chrome acceptance: native MediaRecorder + fake device, simulated HTTP APIs.
 * No real microphone, credentials, model or speech provider is used.
 * Node 24+, Chrome, ffmpeg required. Run: node tools/check-interview.mjs
 * Optional: CHROME_PATH, FFMPEG_PATH, CHECK_ARTIFACT_DIR (default temp directory).
 * Own random ports/profile and all subprocesses are cleaned even after failure.
 */
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdtemp, readFile, writeFile, mkdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { inflateSync } from 'node:zlib'

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const work = await mkdtemp(join(tmpdir(), 'interview-browser-'))
const artifacts = process.env.CHECK_ARTIFACT_DIR || join(work, 'artifacts')
await mkdir(artifacts, { recursive: true })
const chromePath = process.env.CHROME_PATH || (process.platform === 'darwin'
  ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome')
const children = [], sockets = new Set(), checks = [], calls = [], errors = []
// The test servers must not inherit deployment credentials or API overrides.
const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(AI_|VOLC_|VITE_|OPENAI_)/.test(key)))
let ws, server, send, evaluate, snapshot
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
let caps = { asrEnabled: true, ttsEnabled: true, maxRecordingSeconds: 120, maxAudioBytes: 5242880 }
let asrDelay = 60, asrText = '我使用 Redis 缓存热点数据，并用 Spring Boot 完成接口。'
let failReport = 0, failAnswer = false, sessionCounter = 0
const sessions = new Map(), cachedReports = new Map(), reportStates = new Map(), reportGenerations = new Map()
function weightedReport(value, state = {}) {
  const answered = value.turns.filter(turn => turn.answer != null)
  const weights = answered.map((_turn, i) => [8, 12, 12][i % 3]), total = weights.reduce((sum, weight) => sum + weight, 0)
  const basis = weights.map(weight => Math.floor(weight * 10000 / total))
  const order = weights.map((weight, i) => ({ i, remainder: weight * 10000 % total })).sort((a, b) => b.remainder - a.remainder || a.i - b.i)
  for (let i = 0, left = weights.length ? 10000 - basis.reduce((sum, n) => sum + n, 0) : 0; i < left; i++) basis[order[i].i]++
  const turnFeedbacks = value.turns.map((turn, index) => {
    const answeredIndex = answered.findIndex(item => item.questionId === turn.questionId)
    const status = answeredIndex < 0 ? 'UNANSWERED' : state.missing?.has(turn.questionId) ? 'PENDING' : 'SCORED'
    const score = status === 'SCORED' ? state.zero ? 0 : [80, 90, 70][index % 3] : null
    let remaining = score
    const dimensionScores = status === 'SCORED' ? Object.fromEntries(['CORRECTNESS', 'COVERAGE', 'REASONING', 'EVIDENCE', 'CLARITY'].map(key => { const points = Math.min(20, remaining); remaining -= points; return [key, points] })) : {}
    return { ...turn, index: index + 1, score, status, competency: '缓存优化与指标验证', difficulty: index === 1 ? 'ADVANCED' : 'BASIC', importance: index === 2 ? 'CORE' : 'RELATED',
      weightUnits: [8, 12, 12][index % 3], weightPercent: answeredIndex < 0 ? null : basis[answeredIndex] / 100,
      weightedContribution: score == null ? null : Number((score * weights[answeredIndex] / total).toFixed(2)), weightReason: '岗位核心能力与真实项目验证', dimensionScores,
      evidence: status === 'SCORED' ? [turn.answer] : [], missingPoints: status === 'SCORED' ? ['未说明统计周期和样本范围'] : [],
      highlight: status === 'SCORED' ? '说明了实际采用的验证方法' : null, improvement: status === 'SCORED' ? '补充真实统计周期' : null,
      suggestedOutline: status === 'SCORED' ? '指标口径、测量方法、待补充真实样本范围' : null, aiModel: status === 'SCORED' ? 'local-model' : null }
  })
  const scored = turnFeedbacks.filter(turn => turn.status === 'SCORED')
  const reportStatus = !answered.length ? 'EMPTY' : scored.length === answered.length ? 'COMPLETE' : scored.length ? 'PARTIAL' : 'UNAVAILABLE'
  return { overallScore: reportStatus === 'COMPLETE' ? Number((scored.reduce((sum, turn) => sum + turn.score * turn.weightUnits, 0) / total).toFixed(1)) : null,
    overallSummary: '本地模拟报告：已保存问答，仅对已回答内容提供训练参考。', strengths: scored.length ? ['说明实际验证方法'] : [], gaps: scored.length ? ['未说明统计周期'] : [], suggestions: scored.length ? ['补充真实统计周期'] : [],
    turnFeedbacks, aiModel: scored.length ? 'local-model' : null, reportStatus, scoringVersion: 'weighted-evidence-v1', plannedCount: value.questionCount, answeredCount: answered.length, scoredCount: scored.length }
}
const material = { material: { id: 'local-material', fileName: '简历.txt', status: 'READY' }, extraction: {}, text: 'Java 项目：Spring Boot 与 Redis 缓存优化。' }
function newSession(questionCount = 3) {
  const id = `local-session-${++sessionCounter}`
  const q = { questionId: `${id}-q1`, question: '请介绍你如何用 Redis 优化 Spring Boot 项目的缓存？' }
  const value = { sessionId: id, materialId: material.material.id, targetRole: 'Java 后端', questionCount, status: 'ACTIVE', turns: [q], currentQuestion: q }
  sessions.set(id, value)
  return value
}
const json = (res, data, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(status === 200 ? { success: true, data } : { success: false, message: data }))
}
function child(command, args, options = {}) {
  const proc = spawn(command, args, { env, stdio: ['ignore', 'pipe', 'pipe'], ...options })
  children.push(proc)
  let output = ''
  proc.stdout.on('data', data => { output = (output + data).slice(-8000) })
  proc.stderr.on('data', data => { output = (output + data).slice(-8000) })
  proc.on('error', cause => { output += cause.message })
  proc.output = () => output
  return proc
}
async function run(command, args) {
  const proc = child(command, args)
  await new Promise((resolve, reject) => {
    proc.once('error', reject)
    proc.once('exit', code => code === 0 ? resolve() : reject(new Error(`${command}: ${code} ${proc.output()}`)))
  })
}
async function waitFor(fn, description, timeout = 10000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { if (await fn()) return; await sleep(80) }
  throw new Error(`Timeout: ${description}`)
}
async function check(description, fn) {
  await fn()
  checks.push(description)
  console.log(`PASS ${description}`)
}
async function freePort() {
  const probe = createServer()
  await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve))
  const port = probe.address().port
  await new Promise(resolve => probe.close(resolve))
  return port
}
async function main() {
  const audioFile = join(work, 'question.mp3')
  await run(process.env.FFMPEG_PATH || 'ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=5', '-c:a', 'libmp3lame', audioFile])
  const audio = await readFile(audioFile)
  server = createServer(async (req, res) => {
    try {
      const chunks = []
      for await (const chunk of req) chunks.push(chunk)
      const buffer = Buffer.concat(chunks), path = req.url.split('?')[0]
      const call = { method: req.method, path, headers: req.headers, bytes: buffer.length }
      calls.push(call)
      if (path === '/api/interview-speech/capabilities') return json(res, caps)
      if (path === '/api/ai/check') return json(res, { model: 'local-model' })
      if (/^\/api\/materials(?:\/[^/]+)?$/.test(path)) return json(res, material)
      if (path === '/api/interviews' && req.method === 'POST') return json(res, newSession(JSON.parse(buffer).questionCount))
      const match = path.match(/^\/api\/interviews\/([^/]+)(.*)$/)
      const value = match && sessions.get(match[1])
      if (!value) return json(res, '本地模拟接口不存在', 404)
      const suffix = match[2]
      if (suffix === '') return json(res, value)
      if (suffix === '/transcriptions') {
        const form = await new Response(buffer, { headers: { 'Content-Type': req.headers['content-type'] } }).formData()
        const file = form.get('file')
        call.upload = { type: file.type, name: file.name, size: file.size, questionId: form.get('questionId'), requestId: form.get('requestId') }
        const response = { sessionId: value.sessionId, questionId: form.get('questionId'), requestId: form.get('requestId'), text: asrText }
        await sleep(asrDelay)
        call.responded = true
        if (!res.destroyed) json(res, response)
        return
      }
      if (/^\/questions\/[^/]+\/speech$/.test(suffix)) {
        res.writeHead(200, { 'Content-Type': 'audio/mpeg' }); res.end(audio); return
      }
      if (suffix === '/turns') {
        const input = JSON.parse(buffer)
        call.answer = input
        assert.equal(input.questionId, value.currentQuestion.questionId)
        if (failAnswer) { value.pendingAnswer = input.answer; return json(res, '本地模拟：下一题生成失败，回答已保留', 502) }
        value.turns.at(-1).answer = input.answer
        delete value.pendingAnswer
        if (value.turns.length >= value.questionCount) { value.status = 'FINISHED'; delete value.currentQuestion }
        else {
          const q = { questionId: `${value.sessionId}-q${value.turns.length + 1}`, question: '请说明你的实际贡献，并量化改进结果。' }
          value.turns.push(q); value.currentQuestion = q
        }
        return json(res, value)
      }
      if (suffix === '/finish') {
        if (value.pendingAnswer != null) value.turns.at(-1).answer = value.pendingAnswer
        delete value.pendingAnswer; delete value.currentQuestion; value.status = 'FINISHED'
        return json(res, value)
      }
      if (suffix === '/report' || suffix === '/report/retry') {
        if (failReport-- > 0) return json(res, '本地模拟：报告暂时不可用', 503)
        const state = reportStates.get(value.sessionId) || {}
        if (suffix === '/report/retry') {
          assert.equal(req.method, 'POST')
          call.retryQuestions = [...(state.missing || [])]
          await sleep(400)
          if (state.retryFails) { state.retryFails--; return json(res, '本地模拟：评分重试失败，原报告已保留', 503) }
          state.missing?.clear()
        } else assert.equal(req.method, 'GET')
        if (!cachedReports.has(value.sessionId) || suffix === '/report/retry' && call.retryQuestions.length) {
          reportGenerations.set(value.sessionId, (reportGenerations.get(value.sessionId) || 0) + 1)
          cachedReports.set(value.sessionId, weightedReport(value, state))
        }
        return json(res, cachedReports.get(value.sessionId))
      }
      json(res, '本地模拟接口不存在', 404)
    } catch (cause) { errors.push(cause.message); if (!res.headersSent) json(res, cause.message, 500) }
  })
  server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)) })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const mockPort = server.address().port, vitePort = await freePort(), cdpPort = await freePort()
  const base = `http://127.0.0.1:${vitePort}`
  const vite = child(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(vitePort), '--strictPort'], { cwd: frontend, env: { ...env, VITE_PROXY_TARGET: `http://127.0.0.1:${mockPort}` } })
  await waitFor(async () => { try { return (await fetch(base)).ok } catch { if (vite.exitCode != null) throw new Error(vite.output()); return false } }, 'Vite')
  const chrome = child(chromePath, ['--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required', `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${join(work, 'profile')}`, 'about:blank'])
  let target
  await waitFor(async () => { try { target = (await fetch(`http://127.0.0.1:${cdpPort}/json/list`).then(r => r.json())).find(t => t.type === 'page'); return !!target } catch { if (chrome.exitCode != null) throw new Error(chrome.output()); return false } }, 'Chrome CDP')
  ws = new WebSocket(target.webSocketDebuggerUrl)
  const pending = new Map(); let nextId = 0
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data)
    if (message.id) { pending.get(message.id)?.(message); pending.delete(message.id) }
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text)
  })
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }) })
  send = async (method, params = {}) => {
    const reply = await new Promise(resolve => { const id = ++nextId; pending.set(id, resolve); ws.send(JSON.stringify({ id, method, params })) })
    if (reply.error) throw new Error(`${method}: ${reply.error.message}`)
    return reply.result
  }
  evaluate = async expression => {
    const reply = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture: true })
    if (reply.exceptionDetails) throw new Error(reply.exceptionDetails.exception?.description || reply.exceptionDetails.text)
    return reply.result?.value
  }
  snapshot = () => evaluate(`({path:location.pathname,text:document.body.innerText,stats:window.__media,buttons:[...document.querySelectorAll('button')].map(b=>({text:b.textContent.trim(),disabled:b.disabled,testid:b.dataset.testid})),overflow:document.documentElement.scrollWidth>innerWidth})`)
  await send('Page.enable'); await send('Runtime.enable')
  await send('Browser.grantPermissions', { origin: base, permissions: ['audioCapture'] })
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false })
  // Observe native media operations; every call still goes through Chrome's implementation.
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `
    window.__media={mic:0,recorders:0,mimes:[],tracks:[],urls:[],revoked:[],plays:0,prints:0};
    const gum=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia=async (...args)=>{__media.mic++;const stream=await gum(...args);__media.tracks.push(...stream.getTracks());return stream;};
    const NativeRecorder=window.MediaRecorder;
    window.MediaRecorder=class extends NativeRecorder{constructor(...args){super(...args);__media.recorders++;__media.mimes.push(this.mimeType);}};
    const create=URL.createObjectURL.bind(URL),revoke=URL.revokeObjectURL.bind(URL);
    URL.createObjectURL=(blob)=>{const url=create(blob);__media.urls.push(url);return url;};
    URL.revokeObjectURL=(url)=>{__media.revoked.push(url);return revoke(url);};
    const play=HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play=function(...args){__media.plays++;return play.apply(this,args);};
    window.print=()=>{__media.prints++;};
  ` })
  const click = async selector => {
    await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});if(!e||e.disabled)throw new Error('Missing/disabled ${selector}');e.scrollIntoView({block:'center',behavior:'instant'});})()`)
    await sleep(100)
    const point = await evaluate(`(()=>{const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`)
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, ...point })
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, ...point })
  }
  const button = async text => {
    await evaluate(`(()=>{const b=[...document.querySelectorAll('button')].find(e=>e.textContent.trim().startsWith(${JSON.stringify(text)}));if(!b)throw new Error('Missing button');b.dataset.browserCheck='target'})()`)
    await click('[data-browser-check="target"]')
    await evaluate(`document.querySelector('[data-browser-check="target"]')?.removeAttribute('data-browser-check')`)
  }
  const testid = id => click(`[data-testid="${id}"]`)
  const fill = (selector, value) => evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});if(!e||e.disabled)throw new Error('Missing/disabled input');e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));})()`)
  const visible = selector => evaluate(`!!document.querySelector(${JSON.stringify(selector)})`)
  const disabled = selector => evaluate(`document.querySelector(${JSON.stringify(selector)})?.disabled`)
  const text = () => evaluate('document.body.innerText')
  const turns = () => calls.filter(c => c.path.endsWith('/turns')).length
  const screenshot = async name => {
    await evaluate(`document.querySelector('.voice-panel')?.scrollIntoView({block:'center'})`)
    const result = await send('Page.captureScreenshot', { format: 'png' })
    await writeFile(join(artifacts, name), Buffer.from(result.data, 'base64'))
  }
  const navigate = async url => { await send('Page.navigate', { url }); await waitFor(() => visible('#page-interview, #page-report, #page-settings, #login-email'), 'page render') }
  const loadSession = async value => {
    await evaluate(`sessionStorage.setItem('career-studio-interview-id',${JSON.stringify(value.sessionId)})`)
    await navigate(`${base}/interview`)
    await waitFor(() => visible(value.status === 'FINISHED' ? '[data-testid="view-report"]' : '#interview-answer'), 'session restore')
    await waitFor(async () => !(await text()).includes('正在确认语音能力'), 'capabilities')
  }
  const record = async () => {
    await testid('recording-start')
    await waitFor(async () => (await text()).includes('录音中'), 'native recording', 30000)
    await sleep(1200)
    await testid('recording-stop')
    await waitFor(() => visible('audio[aria-label="回放自己的录音"]'), 'native recording blob')
  }
  const transcribe = async () => { await testid('transcription-start'); await waitFor(() => visible('.transcription-preview'), 'ASR preview') }

  await navigate(`${base}/login`)
  await evaluate(`localStorage.setItem('resume-helper-demo-session',JSON.stringify({authenticated:true,email:'browser@local.test',displayName:'本地验证',createdAt:new Date().toISOString()}))`)
  await navigate(`${base}/interview`)
  const inlineModelSettings = await visible('.model-panel')
  if (inlineModelSettings) {
    await evaluate(`document.querySelector('.model-panel').open=true`)
    await fill('.model-panel input[type="password"]', 'local-browser-test')
    await fill('input[placeholder="留空使用服务端默认模型"]', 'local-model')
    await fill('input[placeholder="例如 https://api.deepseek.com/v1"]', 'http://local.invalid/v1')
  } else {
    await navigate(`${base}/settings`)
    await fill('.settings-panel input[type="password"]', 'local-browser-test')
    await fill('input[placeholder="例如 deepseek-chat"]', 'local-model')
    await fill('input[placeholder="例如 https://api.deepseek.com/v1"]', 'http://local.invalid/v1')
    await navigate(`${base}/interview`)
  }
  await check('no microphone or TTS request before explicit click', async () => { assert.equal(await evaluate('__media.mic'), 0); assert.equal(calls.filter(c => c.path.endsWith('/speech')).length, 0) })
  await evaluate(`document.querySelector('.paste-panel').open=true`)
  await fill('#resume-paste', material.text)
  await fill('input[type="number"]', '2')
  await button('使用这份文本')
  await waitFor(() => visible('.resume-preview'), 'material preview')
  await check('unified material response renders original resume text', async () => assert.ok((await text()).includes(material.text)))
  await button('开始面试')
  await waitFor(() => visible('#interview-answer'), 'first question')
  await check('start renders generated question and voice controls without scoring criteria', async () => { assert.ok((await text()).includes('Redis 优化 Spring Boot')); assert.ok(!(await text()).includes('评分标准')); assert.equal(await visible('.dimension-scores'), false); assert.ok(!JSON.stringify(sessions.get('local-session-1')).includes('criteria')) })
  await testid('question-play')
  await waitFor(async () => await evaluate('__media.plays>0') && !(await disabled('[data-testid="question-stop"]')), 'native MP3 playback')
  await testid('question-stop')
  const ttsCalls = calls.filter(c => c.path.endsWith('/speech')).length
  await testid('question-play')
  await check('stop and replay use cached native MP3 without second synthesis', async () => { await sleep(180); assert.equal(calls.filter(c => c.path.endsWith('/speech')).length, ttsCalls); assert.ok(await evaluate('__media.plays>=2')) })
  await testid('recording-start')
  await waitFor(async () => (await text()).includes('录音中'), 'recording locks', 30000)
  await check('recording locks answer, submit and finish', async () => {
    for (const selector of ['#interview-answer', '.answer-tools button', '[data-testid="finish-interview"]']) assert.equal(await disabled(selector), true)
    if (inlineModelSettings) assert.equal(await disabled('.model-panel input'), true)
    assert.equal(await disabled('[data-testid="question-stop"]'), true)
  })
  await testid('recording-cancel')
  await check('cancel releases native tracks and allows re-record', async () => { await sleep(100); assert.equal(await disabled('[data-testid="recording-start"]'), false); assert.equal(await evaluate('__media.tracks.filter(t=>t.readyState!=="ended").length'), 0) })
  await record()
  await check('native fake-device MediaRecorder produces replayable WebM Blob', async () => {
    assert.ok(await evaluate('__media.recorders>=2 && __media.mimes.some(m=>m.includes("webm"))'))
    await evaluate(`document.querySelector('audio').play()`)
    await waitFor(() => evaluate('document.querySelector("audio").currentTime>0'), 'recording playback clock')
    await evaluate('document.querySelector("audio").pause()')
  })
  await fill('#interview-answer', '原有草稿')
  const before = turns()
  await transcribe()
  await check('ASR upload has real audio + matching IDs and only creates preview', async () => {
    const call = calls.findLast(c => c.upload)
    assert.ok(call.upload.size > 0 && call.upload.type.includes('webm') && call.upload.name.endsWith('.webm'))
    assert.ok(call.upload.questionId && /^[0-9a-f-]{36}$/.test(call.upload.requestId))
    assert.equal(turns(), before); assert.equal(await evaluate('document.querySelector("#interview-answer").value'), '原有草稿')
    assert.ok((await text()).includes(asrText))
  })
  await screenshot('interview-desktop.png')
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await check('390px mobile interview has no horizontal overflow', async () => { await sleep(120); assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'), false) })
  await screenshot('interview-mobile.png')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false })
  await testid('transcript-append')
  await check('explicit append preserves editable original draft without submitting', async () => { assert.equal(await evaluate('document.querySelector("#interview-answer").value'), `原有草稿\n${asrText}`); assert.equal(turns(), before) })
  await transcribe(); await testid('transcript-keep')
  await check('keep original draft drops preview without altering answer', async () => { assert.equal(await visible('.transcription-preview'), false); assert.equal(await evaluate('document.querySelector("#interview-answer").value'), `原有草稿\n${asrText}`) })
  await transcribe(); await testid('transcript-replace'); await fill('#interview-answer', '编辑后确认的回答')
  await button('提交回答')
  await waitFor(async () => (await text()).includes('量化改进结果'), 'next question')
  await check('only explicit submit advances question and saves edited answer', async () => { assert.equal(turns(), before + 1); assert.equal(calls.findLast(c => c.answer).answer.answer, '编辑后确认的回答'); assert.equal(await visible('audio[aria-label="回放自己的录音"]'), false) })
  await record(); asrDelay = 900
  await testid('transcription-start')
  await waitFor(() => visible('[data-testid="transcription-cancel"]'), 'ASR pending')
  await check('transcription locks submission/finish while cancel remains available', async () => { assert.equal(await disabled('.answer-tools button'), true); assert.equal(await disabled('[data-testid="finish-interview"]'), true); assert.equal(await disabled('[data-testid="transcription-cancel"]'), false) })
  await testid('transcription-cancel')
  await fill('#interview-answer', '取消后保留的文字')
  await sleep(1100)
  await check('late canceled ASR cannot overwrite text and retained recording can retry', async () => { assert.equal(await visible('.transcription-preview'), false); assert.equal(await evaluate('document.querySelector("#interview-answer").value'), '取消后保留的文字'); assert.equal(await disabled('[data-testid="transcription-start"]'), false); assert.equal(calls.findLast(c => c.upload).responded, true) })
  asrDelay = 60
  await transcribe(); await testid('finish-interview')
  await check('unaccepted preview offers finish choices and blocks submit-and-finish', async () => { assert.equal(await visible('[data-testid="finish-discard"]'), true); assert.equal(await disabled('[data-testid="finish-submit"]'), true) })
  await button('继续作答'); await testid('transcript-keep'); await button('提交回答')
  await waitFor(() => visible('[data-testid="view-report"]'), 'automatic last answer completion')
  await check('last answer completes and offers report entry', async () => { assert.equal(await visible('#interview-answer'), false); assert.equal(await disabled('[data-testid="view-report"]'), false) })
  await loadSession(sessions.get('local-session-1'))
  await check('restored FINISHED session exposes report entry', async () => assert.equal(await visible('[data-testid="view-report"]'), true))
  failReport = 1
  await testid('view-report')
  await waitFor(async () => (await text()).includes('报告暂时不可用'), 'report error')
  await check('report failure retains saved question-answer history', async () => { assert.equal(await evaluate('location.pathname'), '/report'); assert.ok((await text()).includes('已保存的问答记录')); await evaluate('document.querySelector(".review-detail").open=true'); assert.ok((await text()).includes('编辑后确认的回答')) })
  await button('重试加载报告')
  await waitFor(() => visible('.report-meta'), 'report retry')
  await check('GET retry renders weighted report and recovered target role', async () => assert.ok((await text()).includes('加权训练参考分') && (await text()).includes('Java 后端')))
  await button('导出练习报告')
  await check('report export click invokes guarded print method', async () => assert.equal(await evaluate('__media.prints'), 1))
  await evaluate(`window.dispatchEvent(new Event('beforeprint'))`)
  await send('Emulation.setEmulatedMedia', { media: 'print' })
  try {
    await check('report score and feedback remain visible in print media', async () => {
      for (const selector of ['#page-report', '.score-main', '.question-review']) {
        assert.equal(await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});return !!e && getComputedStyle(e).display!=='none' && e.getBoundingClientRect().height>0})()`), true, `${selector} hidden in print`)
      }
      assert.ok((await text()).includes('本地模拟报告')); assert.ok((await text()).includes('原文证据')); assert.ok((await text()).includes('五维得分')); assert.ok((await text()).includes('权重'))
      assert.equal(await evaluate(`[...document.querySelectorAll('#page-report button')].some(button=>getComputedStyle(button).display!=='none')`), false)
    })
  } finally { await send('Emulation.setEmulatedMedia', { media: '' }); await evaluate(`window.dispatchEvent(new Event('afterprint'))`) }
  const pdf = await send('Page.printToPDF', { printBackground: true })
  await writeFile(join(artifacts, 'interview-report.pdf'), Buffer.from(pdf.data, 'base64'))
  await check('native Chrome prints report PDF with text content', async () => {
    const bytes = Buffer.from(pdf.data, 'base64')
    assert.ok(bytes.length > 1000)
    const streams = [...bytes.toString('latin1').matchAll(/stream\r?\n([\s\S]*?)\r?\nendstream/g)]
    const containsText = streams.some(([, data]) => {
      try { return /\bBT\b[\s\S]*?\b(?:Tj|TJ)\b/.test(inflateSync(Buffer.from(data, 'latin1')).toString('latin1')) }
      catch { return false }
    })
    assert.ok(containsText, 'PDF must contain rendered text, not only an empty page or rectangles')
  })
  await check('report navigation cleans media tracks and Blob URLs', async () => { assert.equal(await evaluate('__media.tracks.filter(t=>t.readyState!=="ended").length'), 0); assert.equal(await evaluate('__media.urls.filter(u=>!__media.revoked.includes(u)).length'), 0) })

  caps = { ...caps, ttsEnabled: false }
  await loadSession(newSession())
  await record(); await transcribe()
  const retained = await evaluate('document.querySelector("audio").src')
  await button('重试语音能力')
  await waitFor(async () => !(await text()).includes('正在确认语音能力'), 'capability retry')
  await check('capability retry with TTS unavailable preserves recording/URL/preview', async () => { assert.equal(await evaluate('document.querySelector("audio").src'), retained); assert.equal(await visible('.transcription-preview'), true); assert.equal(await disabled('[data-testid="question-play"]'), true); assert.equal(await disabled('[data-testid="transcription-start"]'), false) })
  await testid('transcript-keep'); await fill('#interview-answer', '未提交草稿')
  await testid('finish-interview')
  await check('local draft finish offers submit/discard/continue', async () => { assert.equal(await disabled('[data-testid="finish-submit"]'), false); assert.equal(await visible('[data-testid="finish-discard"]'), true); assert.equal(await visible('[data-testid="finish-preserve"]'), false) })
  await button('继续作答'); failAnswer = true
  await button('提交回答')
  await waitFor(async () => (await text()).includes('下一题生成失败'), 'pending answer recovery')
  await check('server-pending answer locks recording and draft; failed submit stays on interview', async () => { assert.equal(await evaluate('location.pathname'), '/interview'); assert.equal(await disabled('[data-testid="recording-start"]'), true); assert.equal(await disabled('#interview-answer'), true) })
  await testid('finish-interview')
  await check('pending answer finish explicitly preserves server answer', async () => { assert.equal(await visible('[data-testid="finish-discard"]'), false); assert.equal(await visible('[data-testid="finish-preserve"]'), true); assert.ok((await text()).includes('结束时会计入面试报告')) })
  await testid('finish-preserve'); await waitFor(() => visible('.report-meta'), 'preserve report')
  await check('preserve finish includes pending answer in report session', async () => assert.equal(sessions.get('local-session-2').turns[0].answer, '未提交草稿'))
  failAnswer = false
  await loadSession(newSession()); await fill('#interview-answer', '提交后结束的回答'); await testid('finish-interview'); await testid('finish-submit')
  await waitFor(() => visible('.report-meta'), 'submit then finish')
  await check('submit-and-finish saves answer before ending and routing', async () => { const value = sessions.get('local-session-3'); assert.equal(value.status, 'FINISHED'); assert.equal(value.turns[0].answer, '提交后结束的回答') })
  await loadSession(newSession()); await fill('#interview-answer', '放弃的本地草稿'); await testid('finish-interview'); await testid('finish-discard')
  await waitFor(() => visible('.report-meta'), 'discard report')
  await check('discard finish does not submit unsent local draft', async () => assert.equal(sessions.get('local-session-4').turns[0].answer, undefined))
  caps = { ...caps, asrEnabled: false }
  await loadSession(newSession()); await fill('#interview-answer', '纯文字回答')
  await check('disabled ASR/TTS leaves ordinary text submission available', async () => { assert.equal(await disabled('[data-testid="recording-start"]'), true); assert.equal(await disabled('[data-testid="question-play"]'), true); assert.equal(await disabled('.answer-tools button'), false); assert.ok((await text()).includes('请用文字回答')) })
  await button('提交回答'); await waitFor(async () => (await text()).includes('量化改进结果'), 'text fallback next question')
  const fixture = (mode = 'complete') => {
    const value = newSession(3)
    value.status = 'FINISHED'; delete value.currentQuestion
    value.turns = [1, 2, 3].map(index => ({ questionId: `${value.sessionId}-q${index}`, question: `加权题目 ${index}`, ...(mode === 'empty' || mode === 'early' && index > 1 ? {} : { answer: `原始回答 ${index}：用同一批接口压测验证缓存效果。` }) }))
    const missing = new Set(mode === 'partial' ? [value.turns[1].questionId] : mode === 'unavailable' ? value.turns.map(turn => turn.questionId) : [])
    reportStates.set(value.sessionId, { missing, zero: mode === 'zero', retryFails: mode === 'partial' ? 1 : 0 })
    return value
  }
  const openReport = async value => { await navigate(`${base}/report?sessionId=${value.sessionId}`); await waitFor(() => visible('.report-meta'), 'weighted report fixture') }
  const normal = fixture(); await openReport(normal)
  await check('three answered weights show 25/37.5/37.5 and exact weighted total 80', async () => {
    assert.equal(await evaluate(`document.querySelector('[data-testid="overall-score"] strong').textContent.trim()`), '80')
    assert.deepEqual(await evaluate(`[...document.querySelectorAll('.review-weight')].map(e=>e.textContent.match(/权重 ([0-9.]+)%/)[1])`), ['25', '37.5', '37.5'])
    await evaluate(`document.querySelector('.review-detail').open=true`)
    for (const textValue of ['原始回答 1', '原文证据', '统计周期', '复练回答框架', '切题与正确性', '关键要点覆盖', '推理与取舍', '证据与具体性', '表达结构']) assert.ok((await text()).includes(textValue), textValue)
  })
  await openReport(normal)
  await check('refresh reuses cached scoring via GET', async () => { assert.equal(reportGenerations.get(normal.sessionId), 1); assert.equal(calls.filter(call=>call.path.includes(normal.sessionId) && call.path.endsWith('/report/retry')).length, 0) })
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await check('weighted report has no mobile horizontal overflow', async () => { await sleep(100); assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'), false) })
  await screenshot('weighted-report-mobile.png')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false })
  await openReport(fixture('early'))
  await check('early finish labels answered scope and unanswered rows without zero scores', async () => {
    assert.ok((await text()).includes('已答 1/3 题，提前结束')); assert.equal(await evaluate(`document.querySelectorAll('[data-status="UNANSWERED"]').length`), 2)
    assert.equal(await evaluate(`document.querySelectorAll('[data-status="UNANSWERED"] small').length`), 0)
  })
  const partial = fixture('partial'); await openReport(partial)
  const stable = JSON.stringify(cachedReports.get(partial.sessionId).turnFeedbacks[0])
  await check('partial report shows successful question scores but no total or level', async () => { assert.equal(await visible('[data-testid="overall-score"]'), false); assert.equal(await evaluate(`document.querySelectorAll('[data-status="SCORED"]').length`), 2); assert.equal(await visible('[data-testid="retry-scoring"]'), true); assert.ok(!(await text()).includes('合格')) })
  await testid('retry-scoring')
  await check('retry keeps prior report visible and disables duplicate scoring', async () => { assert.equal(await visible('.question-review'), true); assert.equal(await disabled('[data-testid="retry-scoring"]'), true) })
  await waitFor(async () => (await text()).includes('评分重试失败'), 'retry failure')
  await check('failed scoring retry retains question answers and successful scores', async () => { assert.equal(await evaluate(`document.querySelectorAll('[data-status="SCORED"]').length`), 2); await evaluate(`document.querySelector('.review-detail').open=true`); assert.ok((await text()).includes('原始回答 1')); assert.equal(await visible('[data-testid="overall-score"]'), false) })
  await testid('retry-scoring'); await waitFor(() => visible('[data-testid="overall-score"]'), 'retry success')
  await check('explicit POST retries missing question only and preserves successful assessment', async () => {
    const retry = calls.findLast(call=>call.path.endsWith('/report/retry'))
    assert.equal(retry.method, 'POST'); assert.deepEqual(retry.retryQuestions, [partial.turns[1].questionId])
    assert.equal(JSON.stringify(cachedReports.get(partial.sessionId).turnFeedbacks[0]), stable)
  })
  await openReport(fixture('unavailable'))
  await check('all scoring unavailable preserves question history with no invented score or praise', async () => {
    assert.equal(await visible('[data-testid="overall-score"]'), false); assert.ok((await text()).includes('评分暂不可用')); assert.ok((await text()).includes('暂无有效反馈'))
    assert.equal(await evaluate(`document.querySelectorAll('[data-status="PENDING"]').length`), 3); assert.ok(!(await text()).includes('合格'))
  })
  await openReport(fixture('empty'))
  await check('empty report has no numeric score or scoring retry', async () => { assert.equal(await visible('[data-testid="overall-score"]'), false); assert.equal(await visible('[data-testid="retry-scoring"]'), false); assert.ok((await text()).includes('尚无可评分内容')); assert.equal(await evaluate(`document.querySelectorAll('[data-status="UNANSWERED"]').length`), 3) })
  await openReport(fixture('zero'))
  await check('legitimate zero remains a scored total and per-question score', async () => { assert.equal(await evaluate(`document.querySelector('[data-testid="overall-score"] strong').textContent.trim()`), '0'); assert.equal(await evaluate(`document.querySelector('[data-status="SCORED"]').textContent.trim()`), '0 /100') })
  await check('question speech sends only question ID route without answers or contracts', async () => { const speech = calls.filter(call=>call.path.includes('/questions/') && call.path.endsWith('/speech')); assert.ok(speech.length); assert.ok(speech.every(call=>call.bytes === 0 && /^\/api\/interviews\/[^/]+\/questions\/[^/]+\/speech$/.test(call.path))) })
  await check('every local API call carries common demo identity', async () => assert.ok(calls.length > 20 && calls.every(c => c.headers['x-user-id'] === 'demo-user')))
  await check('text settings shared into report; speech carries no model/provider credentials', async () => {
    const reports = calls.filter(c => /\/report(?:\/retry)?$/.test(c.path))
    assert.ok(reports.length > 0 && reports.every(c => c.headers['x-ai-api-key'] === 'local-browser-test' && c.headers['x-ai-model'] === 'local-model'))
    const speech = calls.filter(c => c.path.includes('/interview-speech/') || c.path.endsWith('/speech') || c.path.endsWith('/transcriptions'))
    assert.ok(speech.length > 0 && speech.every(c => !Object.keys(c.headers).some(k => k.startsWith('x-ai-') || k.startsWith('x-api-key'))))
    assert.ok(!await evaluate('location.href.includes("local-browser-test")'))
  })
  await check('no uncaught browser or mock-server errors', async () => assert.deepEqual(errors, []))
  for (const name of ['failure.json', 'failure.png', 'process.log']) await rm(join(artifacts, name), { force: true })
  console.log(`Browser acceptance passed: ${checks.length} checks. Artifacts: ${artifacts}`)
}
try { await main() }
catch (cause) {
  console.error(cause.stack)
  await writeFile(join(artifacts, 'process.log'), children.map(proc => proc.output()).join('\n'))
  if (snapshot) { try { await writeFile(join(artifacts, 'failure.json'), JSON.stringify(await snapshot(), null, 2)); const shot = await send('Page.captureScreenshot', { format: 'png' }); await writeFile(join(artifacts, 'failure.png'), Buffer.from(shot.data, 'base64')) } catch {} }
  process.exitCode = 1
} finally {
  await writeFile(join(artifacts, 'validation.json'), JSON.stringify({ checks, errors, calls: calls.map(({ headers, ...call }) => ({ ...call, identity: headers['x-user-id'], hasTextSettings: Boolean(headers['x-ai-api-key']) })) }, null, 2))
  ws?.close()
  const alive = proc => proc.exitCode == null && proc.signalCode == null
  for (const proc of children) if (alive(proc)) proc.kill('SIGTERM')
  for (const proc of children) if (alive(proc)) {
    await Promise.race([new Promise(resolve => proc.once('exit', resolve)), sleep(2000)])
    if (alive(proc)) { proc.kill('SIGKILL'); await Promise.race([new Promise(resolve => proc.once('exit', resolve)), sleep(1000)]) }
  }
  for (const socket of sockets) socket.destroy()
  if (server?.listening) await new Promise(resolve => server.close(resolve))
  // Retain default artifacts but always remove the temporary Chrome profile/audio.
  if (process.env.CHECK_ARTIFACT_DIR) await rm(work, { recursive: true, force: true })
  else { await rm(join(work, 'profile'), { recursive: true, force: true }); await rm(join(work, 'question.mp3'), { force: true }) }
}
