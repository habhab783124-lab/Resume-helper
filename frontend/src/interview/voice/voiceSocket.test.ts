import assert from 'node:assert/strict'
import test from 'node:test'
import { createVoiceSocket } from './voiceSocket.ts'
import type { VoiceSessionTicket } from './voiceContracts.ts'

class FakeClock {
  nowValue = 0
  nextId = 0
  jobs = new Map<number, { at: number; callback: () => void; interval: number }>()
  now = () => this.nowValue
  setTimeout = (callback: () => void, delay: number) => this.add(callback, delay, 0)
  clearTimeout = (id: number) => { this.jobs.delete(id) }
  setInterval = (callback: () => void, delay: number) => this.add(callback, delay, delay)
  clearInterval = (id: number) => { this.jobs.delete(id) }
  private add(callback: () => void, delay: number, interval: number) {
    const id = ++this.nextId; this.jobs.set(id, { at: this.nowValue + delay, callback, interval }); return id
  }
  advance(ms: number) {
    this.nowValue += ms
    for (const [id, job] of [...this.jobs]) if (job.at <= this.nowValue) {
      if (job.interval) job.at = this.nowValue + job.interval
      else this.jobs.delete(id)
      job.callback()
    }
  }
}

class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  readyState = FakeWebSocket.CONNECTING
  binaryType = ''
  sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  onerror: (() => void) | null = null
  constructor(readonly url: string) { FakeWebSocket.instances.push(this) }
  send(value: string | ArrayBuffer) { assert.equal(typeof value, 'string'); this.sent.push(value as string) }
  close(code = 1000, reason = '') { this.readyState = FakeWebSocket.CLOSED; this.onclose?.({ code, reason } as CloseEvent) }
  open() { this.readyState = FakeWebSocket.OPEN; this.onopen?.() }
  message(value: unknown) { this.onmessage?.({ data: JSON.stringify(value) } as MessageEvent) }
  binary(value: ArrayBuffer) { this.onmessage?.({ data: value } as MessageEvent) }
}

const ticket: VoiceSessionTicket = { voiceSessionId: 'voice-1', ticket: 'secret-ticket',
  ticketExpiresAt: '2026-10-18T00:01:00Z', websocketPath: '/api/interview-voice/ws', protocolVersion: 1 }
const ready = { v: 1, type: 'session.ready', voiceSessionId: 'voice-1', connectionId: 'connection-1', protocolVersion: 1,
  expiresAt: '2026-10-18T00:30:00Z', sampleRate: 24000, channels: 1, maxBufferedSamples: 48000, maxPcmBytes: 4096 }

test('sends hello only after open, starts only after ready, and keeps secrets out of the URL', async () => {
  FakeWebSocket.instances = []
  const events: unknown[] = []
  const socket = createVoiceSocket({ url: 'wss://example.test/prefix/api/interview-voice/ws', WebSocketImpl: FakeWebSocket,
    clock: new FakeClock(), onEvent: event => events.push(event) })
  const connected = socket.connect(ticket)
  const ws = FakeWebSocket.instances[0]!
  assert.equal(ws.url, 'wss://example.test/prefix/api/interview-voice/ws')
  assert.equal(new URL(ws.url).search, '')
  assert.equal(ws.binaryType, 'arraybuffer')
  assert.throws(() => socket.start('question', 'request'), /尚未就绪/)
  ws.open()
  assert.deepEqual(JSON.parse(ws.sent[0]!), { v: 1, type: 'session.hello', voiceSessionId: 'voice-1', ticket: 'secret-ticket' })
  ws.message(ready)
  await connected
  socket.start('question-1', 'request-1')
  socket.cancel('request-1', 1)
  socket.ack(1, 0, 10)
  assert.equal(JSON.parse(ws.sent[1]!).type, 'speech.start')
  assert.equal(JSON.parse(ws.sent[2]!).type, 'speech.cancel')
  assert.equal(JSON.parse(ws.sent[3]!).type, 'audio.ack')
  assert.equal(events[0] && (events[0] as { type: string }).type, 'session.ready')
  socket.close()
})

test('rejects an unexpected websocket path and reports handshake timeout as closed', async () => {
  FakeWebSocket.instances = []
  const clock = new FakeClock(); const events: any[] = []
  const first = createVoiceSocket({ url: 'ws://localhost/api/elsewhere', WebSocketImpl: FakeWebSocket, clock, onEvent: event => events.push(event) })
  await assert.rejects(first.connect(ticket), /路径无效/)

  const second = createVoiceSocket({ url: 'ws://localhost/api/interview-voice/ws', WebSocketImpl: FakeWebSocket, clock, onEvent: event => events.push(event) })
  const pending = second.connect(ticket)
  clock.advance(6000)
  await assert.rejects(pending, /timeout|超时/i)
  assert.ok(events.some(event => event.type === 'closed'))
})

test('heartbeat silence closes the socket and clears its timers', async () => {
  FakeWebSocket.instances = []
  const clock = new FakeClock(); const events: any[] = []
  const socket = createVoiceSocket({ url: 'ws://localhost/api/interview-voice/ws', WebSocketImpl: FakeWebSocket,
    clock, onEvent: event => events.push(event) })
  const pending = socket.connect(ticket); const ws = FakeWebSocket.instances[0]!
  ws.open(); ws.message(ready); await pending
  clock.advance(31_000)
  assert.ok(events.some(event => event.type === 'closed'))
  assert.equal(ws.readyState, FakeWebSocket.CLOSED)
})

test('decodes binary PCM frames and rejects malformed server messages', async () => {
  FakeWebSocket.instances = []
  const events: any[] = []
  const socket = createVoiceSocket({ url: 'ws://localhost/api/interview-voice/ws', WebSocketImpl: FakeWebSocket,
    clock: new FakeClock(), onEvent: event => events.push(event) })
  const pending = socket.connect(ticket); const ws = FakeWebSocket.instances[0]!
  ws.open(); ws.message(ready); await pending
  const frame = new ArrayBuffer(36); const bytes = new Uint8Array(frame); bytes.set([0x52, 0x56, 0x56, 0x31])
  const view = new DataView(frame); view.setUint8(4, 1); view.setUint8(5, 1); view.setUint32(8, 1, true)
  view.setUint32(20, 2, true); view.setUint32(24, 4, true); view.setInt16(32, -2, true); view.setInt16(34, 3, true)
  ws.binary(frame)
  assert.deepEqual(Array.from(events.at(-1).frame.pcm), [-2, 3])
  ws.message({ v: 3, type: 'session.ready' })
  assert.equal(ws.readyState, FakeWebSocket.CLOSED)
})
