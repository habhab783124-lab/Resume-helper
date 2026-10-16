export function algorithmUrl(algorithm) {
  try {
    const url = new URL(algorithm?.url)
    return url.protocol === 'https:' && ['leetcode.cn', 'leetcode.com'].includes(url.hostname) && /^\/problems\/[a-z0-9-]+\/$/.test(url.pathname) ? url.href : ''
  } catch { return '' }
}
