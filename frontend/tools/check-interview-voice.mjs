/**
 * Browser smoke for the real capture worklet in Vite dev and production preview.
 * Uses Chrome's fake microphone, a deterministic WAV source, no provider credentials,
 * and always tears down Chrome/Vite/temp state. Run: node tools/check-interview-voice.mjs
 */
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdtemp, mkdir, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repo = resolve(frontend, '..')
const temp = await mkdtemp(join(tmpdir(), 'interview-voice-browser-'))
const artifacts = process.env.CHECK_ARTIFACT_DIR || join(repo, 'docs', 'interview-voice-artifacts')
const chromePath = process.env.CHROME_PATH || (process.platform === 'darwin'
  ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome')
const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(AI_|VOLC_|VITE_|OPENAI_)/.test(key)))
const children = []
let chrome, cdp, dev, preview, endpoint
const checks = []
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function child(command, args, options = {}) {
  const proc = spawn(command, args, { env, stdio: ['ignore', 'pipe', 'pipe'], ...options })
  children.push(proc)
  let output = ''
  proc.stdout.on('data', data => { output = (output + data).slice(-6000) })
  proc.stderr.on('data', data => { output = (output + data).slice(-6000) })
  proc.on('error', cause => { output += cause.message })
  proc.output = () => output
  return proc
}
async function port() {
  const server = createServer()
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const value = server.address().port
  await new Promise(resolve => server.close(resolve))
  return value
}
async function waitFor(fn, label, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await fn()) return
    await sleep(80)
  }
  throw new Error(`Timed out waiting for ${label}`)
}
async function startServer(args) {
  const listenPort = await port()
  const proc = child(process.execPath, ['node_modules/vite/bin/vite.js', ...args, '--host', '127.0.0.1', '--port', String(listenPort), '--strictPort'], { cwd: frontend })
  const base = `http://127.0.0.1:${listenPort}`
  await waitFor(async () => {
    try { return (await fetch(base)).ok }
    catch { if (proc.exitCode != null) throw new Error(proc.output()); return false }
  }, `Vite at ${base}`)
  return { proc, base }
}
function wavFixture() {
  const rate = 48_000, samples = 4_800, bytes = Buffer.alloc(44 + samples * 2)
  bytes.write('RIFF', 0); bytes.writeUInt32LE(bytes.length - 8, 4); bytes.write('WAVE', 8)
  bytes.write('fmt ', 12); bytes.writeUInt32LE(16, 16); bytes.writeUInt16LE(1, 20); bytes.writeUInt16LE(1, 22)
  bytes.writeUInt32LE(rate, 24); bytes.writeUInt32LE(rate * 2, 28); bytes.writeUInt16LE(2, 32); bytes.writeUInt16LE(16, 34)
  bytes.write('data', 36); bytes.writeUInt32LE(samples * 2, 40)
  for (let i = 0; i < samples; i++) bytes.writeInt16LE(Math.round(Math.sin(2 * Math.PI * 440 * i / rate) * 12_000), 44 + i * 2)
  return bytes
}
async function connectCdp(debugPort) {
  await waitFor(async () => {
    try { endpoint = (await fetch(`http://127.0.0.1:${debugPort}/json/list`).then(r => r.json())).find(item => item.type === 'page'); return !!endpoint }
    catch { if (chrome.exitCode != null) throw new Error(chrome.output()); return false }
  }, 'Chrome DevTools')
  cdp = new WebSocket(endpoint.webSocketDebuggerUrl)
  const pending = new Map(); let nextId = 0
  cdp.addEventListener('message', event => {
    const message = JSON.parse(event.data)
    if (message.id) { pending.get(message.id)?.(message); pending.delete(message.id) }
  })
  await new Promise((resolve, reject) => { cdp.addEventListener('open', resolve, { once: true }); cdp.addEventListener('error', reject, { once: true }) })
  const send = (method, params = {}) => new Promise(resolve => {
    const id = ++nextId; pending.set(id, resolve); cdp.send(JSON.stringify({ id, method, params }))
  }).then(reply => { if (reply.error) throw new Error(`${method}: ${reply.error.message}`); return reply.result })
  const evaluate = async expression => {
    const reply = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture: true })
    if (reply.exceptionDetails) throw new Error(reply.exceptionDetails.exception?.description || reply.exceptionDetails.text)
    return reply.result?.value
  }
  await send('Page.enable'); await send('Runtime.enable')
  return { send, evaluate }
}
async function checkWorklet(base, moduleUrl, label, wavBase64) {
  await cdpTools.send('Browser.grantPermissions', { origin: base, permissions: ['audioCapture'] })
  await cdpTools.send('Page.navigate', { url: base })
  await waitFor(() => cdpTools.evaluate('document.readyState === "complete"'), `${label} page load`)
  const expression = `(async () => {
    const wav = Uint8Array.from(atob(${JSON.stringify(wavBase64)}), c => c.charCodeAt(0)).buffer;
    const context = new AudioContext(); await context.resume();
    const stream = await navigator.mediaDevices.getUserMedia({audio:true, video:false});
    const decoded = await context.decodeAudioData(wav);
    await context.audioWorklet.addModule(${JSON.stringify(moduleUrl)});
    const worklet = new AudioWorkletNode(context, 'resume-helper-voice-capture', {numberOfInputs:1,numberOfOutputs:1,outputChannelCount:[1]});
    const gain = context.createGain(); gain.gain.value = 0;
    const source = context.createBufferSource(); source.buffer = decoded; source.connect(worklet); worklet.connect(gain); gain.connect(context.destination);
    const audioSource = context.createMediaStreamSource(stream); audioSource.connect(worklet);
    const received = []; let sealed = false; let failure = '';
    const result = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('worklet frame/seal timeout')), 8000);
      worklet.port.onmessage = event => {
        const value = event.data;
        if (value.type === 'capture.overrun') { failure = 'capture.overrun'; reject(new Error(failure)); return; }
        if (value.type === 'frame') {
          const pcm = new Int16Array(value.pcm);
          received.push({sequence:value.sequence,firstSample:value.firstSample,sampleCount:value.sampleCount,sampleRate:value.sampleRate,nonZero:pcm.some(sample => sample !== 0)});
          worklet.port.postMessage({type:'frame.ack',sequence:value.sequence});
        }
        if (value.type === 'sealed') { sealed = true; clearTimeout(timeout); resolve({frames:received,sealed,lastSequence:value.lastSequence}); }
      };
      source.onended = () => worklet.port.postMessage({type:'seal',barrierId:1});
      source.start();
    });
    const tracks = stream.getTracks(); tracks.forEach(track => track.stop());
    source.disconnect(); audioSource.disconnect(); worklet.disconnect(); gain.disconnect(); await context.close();
    if (!result.sealed || !result.frames.length || !result.frames.some(frame => frame.nonZero)) throw new Error('worklet did not emit deterministic audio');
    if (result.frames.some((frame,index) => frame.sequence !== index || frame.firstSample !== result.frames.slice(0,index).reduce((sum,item)=>sum+item.sampleCount,0))) throw new Error('worklet frame sequence is discontinuous');
    if (tracks.some(track => track.readyState !== 'ended')) throw new Error('fake microphone tracks were not released');
    document.body.innerHTML = '<main style="font:16px system-ui;padding:40px"><h1>实时语音 AudioWorklet 验收</h1><p>${label}: PASS</p><p>已加载模块并处理确定性 WAV 与 Chrome 假麦克风帧。</p><p>帧数：' + result.frames.length + '；帧序列连续；麦克风轨道已释放。</p></main>';
    return {frames:result.frames.length, totalSamples:result.frames.reduce((sum,item)=>sum+item.sampleCount,0), sealed:result.sealed, micTracksStopped:tracks.every(track=>track.readyState==='ended')};
  })()`
  const result = await cdpTools.evaluate(expression)
  assert.ok(result.frames > 0 && result.totalSamples > 0 && result.sealed && result.micTracksStopped, `${label} worklet result is invalid`)
  const shot = await cdpTools.send('Page.captureScreenshot', { format: 'png' })
  await writeFile(join(artifacts, `audio-worklet-${label}.png`), Buffer.from(shot.data, 'base64'))
  checks.push({ check: `${label} AudioWorklet module, fake capture and seal barrier`, ...result })
}

let cdpTools
try {
  await mkdir(artifacts, { recursive: true })
  const distFiles = await readdir(join(frontend, 'dist', 'assets'))
  const builtWorklet = distFiles.find(name => /^voiceCapture\.worklet-.*\.js$/.test(name))
  assert.ok(builtWorklet, 'production build must contain the capture worklet JS asset')
  const wav = wavFixture()
  assert.equal(wav.readUInt32LE(40), 9_600)
  const encodedWav = wav.toString('base64')
  const devUrl = '/src/interview/voice/worklet/voiceCapture.worklet.ts'
  const prodUrl = `/assets/${builtWorklet}`
  const devStarted = await startServer(['--mode', 'development'])
  dev = devStarted.proc
  const previewStarted = await startServer(['preview'])
  preview = previewStarted.proc
  const cdpPort = await port()
  chrome = child(chromePath, ['--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking',
    '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required',
    `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${join(temp, 'profile')}`, 'about:blank'])
  cdpTools = await connectCdp(cdpPort)
  await checkWorklet(devStarted.base, devUrl, 'development', encodedWav)
  await checkWorklet(previewStarted.base, prodUrl, 'production-preview', encodedWav)
  await rm(join(artifacts, 'failure.json'), { force: true })
  await writeFile(join(artifacts, 'validation.json'), JSON.stringify({ date: new Date().toISOString(), checks, limitations: ['真实供应商 TTS/ASR 和真人耳机/扬声器未在此假设备 smoke 中验证'] }, null, 2))
  console.log(`Voice browser smoke passed: ${checks.length} checks. Artifacts: ${artifacts}`)
} catch (cause) {
  console.error(cause.stack || cause)
  await writeFile(join(artifacts, 'failure.json'), JSON.stringify({ checks, error: cause.message || String(cause) }, null, 2)).catch(() => {})
  process.exitCode = 1
} finally {
  cdp?.close()
  for (const proc of children) if (proc.exitCode == null && proc.signalCode == null) proc.kill('SIGTERM')
  for (const proc of children) if (proc.exitCode == null && proc.signalCode == null) {
    await Promise.race([new Promise(resolve => proc.once('exit', resolve)), sleep(2000)])
    if (proc.exitCode == null && proc.signalCode == null) proc.kill('SIGKILL')
  }
  await rm(temp, { recursive: true, force: true })
}
