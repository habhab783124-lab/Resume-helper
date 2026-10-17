import test from 'node:test'
import assert from 'node:assert/strict'
import { useInterviewPolling } from './useInterviewPolling.js'

function fakeClock() {
  const timers = new Map(); let next = 1
  return {
    timers,
    setTimeout(fn, ms) { const id = next++; timers.set(id, { fn, ms }); return id },
    clearTimeout(id) { timers.delete(id) },
    tick() { const [id, timer] = timers.entries().next().value ?? []; if (id == null) return false; timers.delete(id); timer.fn(); return true }
  }
}

test('polling is sequential, backs off to five seconds, and stops on terminal state', async () => {
  const clock = fakeClock(); const calls = []; const sessions = []
  const api = { async getSession(id, { signal }) { calls.push({ id, signal }); return { status: 'ACTIVE', generation: { state: calls.length < 6 ? 'RUNNING' : 'SUCCEEDED' } } } }
  const poll = useInterviewPolling({ api, clock, onSession: session => sessions.push(session) })
  const work = poll.start('s1')
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(calls.length, 1)
  assert.equal(clock.timers.values().next().value.ms, 1000)
  const delays = [1000]
  for (let i = 0; i < 5; i++) {
    clock.tick(); await new Promise(resolve => setImmediate(resolve))
    if (clock.timers.size) delays.push(clock.timers.values().next().value.ms)
  }
  await work
  assert.equal(calls.length, 6)
  assert.equal(sessions.length, 6)
  assert.deepEqual(delays, [1000, 2000, 3000, 4000, 5000])
  assert.equal(poll.running.value, false)
})

test('stop aborts in-flight reads and discards late results', async () => {
  const clock = fakeClock(); let signal; let resolveRead; const seen = []; const errors = []
  const api = { getSession(_id, options) { signal = options.signal; return new Promise(resolve => { resolveRead = resolve }) } }
  const poll = useInterviewPolling({ api, clock, onSession: value => seen.push(value), onError: error => errors.push(error) })
  const work = poll.start('s1')
  await new Promise(resolve => setImmediate(resolve))
  poll.stop()
  resolveRead({ status: 'ACTIVE' })
  await work
  assert.equal(signal.aborted, true)
  assert.deepEqual(seen, [])
  assert.deepEqual(errors, [])
  assert.equal(clock.timers.size, 0)
})

test('read failures update connection state and never enqueue generation requests', async () => {
  const clock = fakeClock(); const errors = []; let calls = 0
  const poll = useInterviewPolling({ api: { async getSession() { calls++; throw new Error('offline') } }, clock, onError: error => errors.push(error) })
  const work = poll.start('s1')
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(errors[0].message, 'offline')
  poll.stop(); await work
  assert.equal(calls, 1)
})

test('getMaxDelayMs keeps the existing poll loop at one second during voice and restores backoff after voice stops', async () => {
  const clock = fakeClock(); let voiceActive = false; let calls = 0
  const api = { async getSession() { calls++; return { status: 'ACTIVE', generation: { state: 'RUNNING' } } } }
  const poll = useInterviewPolling({ api, clock, getMaxDelayMs: () => voiceActive ? 1000 : 5000 })
  const work = poll.start('s1')
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(clock.timers.values().next().value.ms, 1000)
  clock.tick(); await new Promise(resolve => setImmediate(resolve))
  assert.equal(clock.timers.values().next().value.ms, 2000)
  voiceActive = true
  clock.tick(); await new Promise(resolve => setImmediate(resolve))
  assert.equal(clock.timers.values().next().value.ms, 1000)
  voiceActive = false
  clock.tick(); await new Promise(resolve => setImmediate(resolve))
  assert.equal(clock.timers.values().next().value.ms, 1000)
  clock.tick(); await new Promise(resolve => setImmediate(resolve))
  assert.equal(clock.timers.values().next().value.ms, 2000)
  assert.equal(calls, 5)
  poll.stop(); await work
})
