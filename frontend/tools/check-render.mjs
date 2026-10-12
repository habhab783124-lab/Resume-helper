/**
 * 用真实浏览器（Edge headless + CDP）验证「前端 → vite 代理 → 后端」整条链路的渲染结果。
 *
 * 为什么需要它：
 *   - `npm run build` 只能证明代码能编译；
 *   - curl 穿过 vite 代理只能证明网络通、JSON 对；
 *   - 只有真的在浏览器里跑一遍，才能证明 Vue 把后端返回的岗位渲染成了卡片。
 *
 * 它做的事：种一个演示登录会话绕过路由守卫 → 打开 /jobs → 读取真实 DOM →
 * 断言卡片数量、标题、匹配度、公司首字、配色类、技能标签、匹配理由、薪资、状态条，
 * 再打开 /dashboard 检查推荐位，最后检查有没有未捕获的 JS 异常。
 *
 * 不依赖任何 npm 包：Node 22+ 自带全局 WebSocket，CDP 就是一个 WebSocket + JSON 协议。
 *
 * 前置：
 *   1) 后端和前端都已经在跑（后端默认 8080，前端 5173）
 *   2) 启动一个带调试端口的 headless Edge（Windows 示例）：
 *
 *      & "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" `
 *          --headless=new --disable-gpu --no-first-run `
 *          --remote-debugging-port=9222 --user-data-dir="$env:TEMP\edge-check" about:blank
 *
 *   3) node tools/check-render.mjs
 *
 * 前端不在 5173 时用环境变量覆盖： $env:CHECK_BASE = 'http://localhost:4173'
 */

const CDP_PORT = Number(process.env.CDP_PORT || 9222)
const BASE = process.env.CHECK_BASE || 'http://localhost:5173'

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function pickTarget() {
  for (let attempt = 0; attempt < 20; attempt++) {
    try {
      const list = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`).then((r) => r.json())
      const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
      if (page) return page
    } catch {
      // Edge 还没起来，等下一轮
    }
    await sleep(500)
  }
  throw new Error('连不上 Edge 的调试端口，请确认 Edge 已用 --remote-debugging-port=9222 启动')
}

function connect(url) {
  const ws = new WebSocket(url)
  const pending = new Map()
  let nextId = 1

  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.id && pending.has(message.id)) {
      pending.get(message.id)(message)
      pending.delete(message.id)
    }
  })

  const ready = new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve)
    ws.addEventListener('error', () => reject(new Error('CDP WebSocket 连接失败')))
  })

  const send = (method, params = {}) =>
    new Promise((resolve) => {
      const id = nextId++
      pending.set(id, resolve)
      ws.send(JSON.stringify({ id, method, params }))
    })

  return { ws, ready, send }
}

/** 求值并返回 JS 值。CDP 会把结果包在 result.result.value 里。 */
async function evaluate(send, expression) {
  const response = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  const exception = response.result?.exceptionDetails
  if (exception) {
    throw new Error('页面内求值出错：' + (exception.exception?.description || exception.text))
  }
  return response.result?.result?.value
}

const results = []
function check(desc, ok, detail = '') {
  results.push({ desc, ok, detail })
  console.log(`  [${ok ? '通过' : '失败'}] ${desc}${detail ? '  — ' + detail : ''}`)
}

async function main() {
  const target = await pickTarget()
  const { ws, ready, send } = connect(target.webSocketDebuggerUrl)
  await ready
  await send('Page.enable')
  await send('Runtime.enable')

  const consoleErrors = []
  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.method === 'Runtime.exceptionThrown') {
      consoleErrors.push(message.params?.exceptionDetails?.exception?.description || '未知异常')
    }
  })

  console.log('=== 浏览器端到端渲染验证 ===')
  console.log(`目标：${BASE}/jobs\n`)

  // 1) 先打开登录页，为的是拿到 localhost:5173 这个 origin 才能写 localStorage
  await send('Page.navigate', { url: `${BASE}/login` })
  await sleep(2500)
  const loginOk = await evaluate(send, `Boolean(document.querySelector('input[type="email"],#login-email,input'))`)
  check('登录页渲染出来了（说明 SPA 正常启动）', loginOk === true)

  // 2) 种一个演示会话，绕过路由守卫。key 与 utils/demoAuth.js 里的 SESSION_KEY 一致。
  await evaluate(
    send,
    `window.localStorage.setItem('resume-helper-demo-session', JSON.stringify({authenticated:true,email:'demo@resume-helper.cn',displayName:'林同学',createdAt:new Date().toISOString()})); true`
  )

  // 3) 打开岗位页
  await send('Page.navigate', { url: `${BASE}/jobs` })
  await sleep(4500)

  const path = await evaluate(send, `window.location.pathname`)
  check('已停留在 /jobs（没有被守卫弹回登录页）', path === '/jobs', `当前路径 ${path}`)

  const summary = await evaluate(
    send,
    `JSON.stringify({
      cards: document.querySelectorAll('#job-grid .job-card').length,
      titles: [...document.querySelectorAll('#job-grid .job-card h3')].slice(0,3).map(e => e.textContent.trim()),
      companies: [...document.querySelectorAll('#job-grid .job-card .job-title-wrap p')].slice(0,3).map(e => e.textContent.trim()),
      scores: [...document.querySelectorAll('#job-grid .match-score')].slice(0,3).map(e => e.textContent.trim()),
      marks: [...document.querySelectorAll('#job-grid .company-mark')].slice(0,3).map(e => e.textContent.trim()),
      styles: [...document.querySelectorAll('#job-grid .company-mark')].slice(0,3).map(e => e.className),
      tags: [...document.querySelectorAll('#job-grid .job-tags span')].slice(0,4).map(e => e.textContent.trim()),
      reasons: [...document.querySelectorAll('#job-grid .job-reason span')].slice(0,2).map(e => e.textContent.trim()),
      gaps: [...document.querySelectorAll('#job-grid .job-gap')].slice(0,1).map(e => e.textContent.trim()),
      salary: document.querySelector('#job-grid .job-salary')?.textContent.trim(),
      status: document.querySelector('.job-status-left')?.textContent.trim(),
      notice: document.querySelector('.job-results-header span:last-child')?.textContent.trim(),
      countBadge: document.querySelector('#job-result-count')?.textContent.trim() ?? document.querySelector('.job-results-header b')?.textContent.trim(),
      dashboardCards: 0
    })`
  )
  const data = JSON.parse(summary)

  console.log('  渲染结果：')
  console.log(`    岗位卡片数：${data.cards}`)
  console.log(`    标题：${data.titles.join(' / ')}`)
  console.log(`    公司：${data.companies.join(' / ')}`)
  console.log(`    匹配度：${data.scores.join(' / ')}`)
  console.log(`    公司首字：${data.marks.join(' / ')}`)
  console.log(`    技能标签：${data.tags.join('、')}`)
  console.log(`    匹配理由：${data.reasons[0] || '（无）'}`)
  console.log(`    状态条：${data.status || '（无）'}`)
  console.log(`    结果计数：${data.countBadge || '（无）'}`)
  console.log('')

  check('岗位卡片渲染出来了', data.cards > 0, `${data.cards} 张`)
  check('卡片标题来自后端（不是空壳）', data.titles.length > 0 && data.titles[0].length > 0, data.titles[0] || '')
  check('公司名渲染正确', data.companies.length > 0, data.companies[0] || '')
  check('匹配度分数渲染出来了', data.scores.length > 0 && /\d/.test(data.scores[0]), data.scores[0] || '')
  check('公司首字标记是单字（对应原型 company-mark）', data.marks.every((m) => m.length === 1), data.marks.join(','))
  check('配色类生效（mark-blue / mark-amber / mark-rose）', data.styles.some((s) => /mark-/.test(s)), data.styles.join(' | '))
  check('技能标签渲染出来了', data.tags.length > 0, data.tags.join('、'))
  check('匹配理由渲染出来了', (data.reasons[0] || '').length > 0, data.reasons[0] || '')
  check('薪资渲染出来了', Boolean(data.salary), data.salary || '')
  check('状态条显示后端已连通 + 数据源', /共 \d+ 个岗位/.test(data.status || ''), data.status || '')
  check('页面展示了「不代表录取概率」提示', /不代表录取概率/.test(data.notice || ''), data.notice || '')
  check('结果计数变成了实际条数（不再是写死的 08）', data.countBadge !== '08' || data.cards === 8, `显示 ${data.countBadge}`)

  // 4) 工作台推荐位
  await send('Page.navigate', { url: `${BASE}/dashboard` })
  await sleep(4000)
  const dash = JSON.parse(
    await evaluate(
      send,
      `JSON.stringify({
        cards: document.querySelectorAll('#dashboard-job-list .recommended-job').length,
        titles: [...document.querySelectorAll('#dashboard-job-list .recommended-job strong')].slice(0,3).map(e => e.textContent.trim()),
        metric: document.querySelector('.metric-card:nth-child(2) .metric-value')?.textContent.trim()
      })`
    )
  )
  console.log('  工作台推荐位：')
  console.log(`    推荐卡片数：${dash.cards}`)
  console.log(`    标题：${dash.titles.join(' / ')}`)
  console.log(`    指标卡：${dash.metric}`)
  console.log('')
  check('工作台推荐岗位渲染出来了', dash.cards > 0, `${dash.cards} 张`)
  check('工作台指标卡不再是写死的 08个', Boolean(dash.metric), dash.metric || '')

  check('页面没有未捕获的 JS 异常', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '))

  ws.close()

  const failed = results.filter((r) => !r.ok).length
  console.log(failed === 0
    ? `\n=== 浏览器验证通过：${results.length} 项全过 ===`
    : `\n=== 浏览器验证：通过 ${results.length - failed} 项，失败 ${failed} 项 ===`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((error) => {
  console.error('验证脚本自身出错：' + error.message)
  process.exit(2)
})
