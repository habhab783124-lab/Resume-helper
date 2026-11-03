import test from 'node:test'
import assert from 'node:assert/strict'
import { DEFAULT_JOB_LIMIT, EXPERIENCE_OPTIONS, jobSearchPayload, sortJobCards, jobSourceLabel } from './jobSearch.js'

test('default search requests sixty jobs without a hidden experience restriction', () => {
  const payload = jobSearchPayload({ maxYears: EXPERIENCE_OPTIONS[0].maxYears })
  assert.equal(payload.limit, 60)
  assert.equal(DEFAULT_JOB_LIMIT, 60)
  assert.equal(payload.maxYears, null)
  assert.equal(payload.strictCity, false)
  assert.equal(payload.explainTopN, 0)
})
test('explicit keyword, city, experience and count are preserved for both modes', () => {
  const payload = jobSearchPayload({ keyword: ' Java 上海 ', city: '上海', maxYears: 1, limit: '100', profile: { skills: ['Java'] } })
  assert.equal(payload.keyword, 'Java 上海')
  assert.equal(payload.city, '上海')
  assert.equal(payload.strictCity, true)
  assert.equal(payload.maxYears, 1)
  assert.equal(payload.limit, 100)
  assert.equal(jobSearchPayload({ limit: 100000 }).limit, 60)
})
test('salary sort uses normalized monthly amounts and leaves unknown salaries last', () => {
  const jobs = [
    { id: 'daily', salary: '300元/天', salaryMin: 7, salaryMax: 7 },
    { id: 'unknown', salary: '面议', salaryMin: 0, salaryMax: 0 },
    { id: 'monthly', salary: '15-22K', salaryMin: 15, salaryMax: 22 },
    { id: 'yearly', salary: '24万/年', salaryMin: 20, salaryMax: 20 }
  ]
  assert.deepEqual(sortJobCards(jobs, 'salary').map(j => j.id), ['yearly', 'monthly', 'daily', 'unknown'])
  assert.equal(jobs[0].id, 'daily')
  assert.deepEqual(sortJobCards([{ id: 'none' }, { id: 'zero', score: 0 }, { id: 'high', score: 80 }]).map(j => j.id), ['high', 'zero', 'none'])
})
test('source status reflects this search and does not list disabled sources as available', () => {
  assert.equal(jobSourceLabel([{ name: '官网一', ok: true, count: 80 }, { name: '官网二', ok: false, message: '连接失败' },
    { name: '演示库', ok: false, message: '已通过配置关闭' }]), '官网一（返回 80 条） + 官网二（暂不可用）')
})
