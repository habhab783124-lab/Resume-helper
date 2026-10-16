import test from 'node:test'
import assert from 'node:assert/strict'
import { algorithmUrl } from './algorithmSource.js'

test('algorithm source accepts both official HTTPS problem hosts', () => {
  for (const host of ['leetcode.cn', 'leetcode.com']) {
    const url = `https://${host}/problems/two-sum/`
    assert.equal(algorithmUrl({ url }), url)
  }
})

test('algorithm source rejects missing, malformed, unsafe and non-problem URLs', () => {
  for (const algorithm of [undefined, null, {}, { url: null }]) assert.equal(algorithmUrl(algorithm), '')
  for (const url of ['not a URL', '//leetcode.cn/problems/two-sum/', 'https://[bad/problems/two-sum/',
    'javascript:alert(1)', 'http://leetcode.cn/problems/two-sum/', 'https://leetcode.cn.evil.test/problems/two-sum/',
    'https://leetcode.cn@evil.test/problems/two-sum/', 'https://evil.test/problems/two-sum/',
    'https://leetcode.cn/settings/', 'https://leetcode.cn/problems/two-sum', 'https://leetcode.cn/problems/two-sum/solution/']) {
    assert.equal(algorithmUrl({ url }), '', url)
  }
})
