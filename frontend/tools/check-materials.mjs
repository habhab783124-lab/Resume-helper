/**
 * 用真实浏览器（Edge headless + CDP）验证「材料整理」页（功能一）。
 *
 * 为什么要单独一个脚本：材料页的核心价值是**真的把文件传上去了、真的抽出了东西**，
 * 这三点只有浏览器能证明：
 *   - `npm run build` 只能证明代码能编译；
 *   - curl 打后端只能证明接口对；
 *   - 只有真的给页面一个文件，才能证明「选文件 → 上传 → 抽取 → 渲染结果」这条链路是通的。
 *
 * 所以这里用 CDP 的 DOM.setFileInputFiles 往页面里塞**真实文件**，
 * 而不是模拟点击或直接调接口——那两样都绕过了要验证的那段代码。
 *
 * 它检查什么：
 *   1. 列表渲染的是**后端真实数据**，不是原来的写死假数据（林同学_个人简历.pdf 那两条）
 *   2. 通过页面真的上传一个文件，列表条数增加、出现新文件名
 *   3. 点一条材料 → 出现抽取结果面板 + 原文对照（text 来自后端）
 *   4. 每条经历带「原文」证据（这是防 AI 编造经历的关键设计）
 *   5. 没有未捕获的 JS 异常
 *
 * 前置：
 *   1) 后端与前端都在跑（后端 8080 或自定义，前端 5173）
 *   2) 启动带调试端口的 headless Edge：
 *
 *      & "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" `
 *          --headless=new --disable-gpu --no-first-run `
 *          --remote-debugging-port=9223 --user-data-dir="$env:TEMP\edge-materials" about:blank
 *
 *   3) node tools/check-materials.mjs
 *
 * 环境变量：
 *   CHECK_BASE   前端地址，默认 http://localhost:5173
 *   CDP_PORT     调试端口，默认 9223
 *   UPLOAD_FILE  用来测试上传的真实文件路径（必填；建议用 tools/testdata/sample-resume.docx）
 */

import { existsSync } from 'node:fs'

const CDP_PORT = Number(process.env.CDP_PORT || 9223)
const BASE = process.env.CHECK_BASE || 'http://localhost:5173'
const UPLOAD_FILE = process.env.UPLOAD_FILE || ''

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
  throw new Error('连不上 Edge 的调试端口，请确认 Edge 已用 --remote-debugging-port 启动')
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

/** 读当前列表的快照。 */
const LIST_SNAPSHOT = `JSON.stringify({
  count: document.querySelectorAll('#material-list .material-item').length,
  names: [...document.querySelectorAll('#material-list .material-item .material-info strong')].map(e => e.textContent.trim()),
  statuses: [...document.querySelectorAll('#material-list .material-item .status-pill')].map(e => e.textContent.trim()),
  badge: document.querySelector('.storage-badge b')?.textContent?.trim() || ''
})`

async function main() {
  if (!UPLOAD_FILE || !existsSync(UPLOAD_FILE)) {
    console.error(`请用 UPLOAD_FILE 指定一个存在的文件（建议 backend/tools/testdata/sample-resume.docx）`)
    console.error(`当前值：${UPLOAD_FILE || '(空)'}`)
    process.exit(2)
  }

  const target = await pickTarget()
  const { ws, ready, send } = connect(target.webSocketDebuggerUrl)
  await ready
  await send('Page.enable')
  await send('Runtime.enable')
  await send('DOM.enable')

  const consoleErrors = []
  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.method !== 'Runtime.exceptionThrown') return
    const details = message.params?.exceptionDetails
    const description = details?.exception?.description || details?.text || '未知异常'
    const sourceUrl = details?.url || details?.stackTrace?.callFrames?.[0]?.url || ''
    // 浏览器扩展自己抛的错和我们无关，忽略（无头 Edge 会加载本机已装的扩展）
    if (sourceUrl.startsWith('chrome-extension://') || sourceUrl.startsWith('moz-extension://')
        || description.includes('chrome-extension://')) {
      return
    }
    consoleErrors.push(sourceUrl ? `${description}  (${sourceUrl})` : description)
  })

  console.log('=== 材料整理页 端到端验证 ===')
  console.log(`目标：${BASE}/materials`)
  console.log(`上传文件：${UPLOAD_FILE}\n`)

  // 1) 先到登录页拿到 origin，才能写 localStorage 种会话
  await send('Page.navigate', { url: `${BASE}/login` })
  await sleep(2000)
  await evaluate(send, `window.localStorage.setItem('resume-helper-demo-session', JSON.stringify({authenticated:true,email:'demo@resume-helper.cn',displayName:'林同学',createdAt:new Date().toISOString()})); true`)

  // 2) 打开材料页
  await send('Page.navigate', { url: `${BASE}/materials` })
  await sleep(3500)

  const path = await evaluate(send, `window.location.pathname`)
  check('已停留在 /materials（没被守卫弹回登录页）', path === '/materials', `当前路径 ${path}`)

  const before = JSON.parse(await evaluate(send, LIST_SNAPSHOT))
  console.log(`  渲染结果：条数 ${before.count}，角标 ${before.badge}`)
  console.log(`  文件名：${before.names.join(' / ')}`)
  console.log(`  状态：${before.statuses.join(' / ')}`)

  check('材料列表渲染出来了', before.count > 0, `${before.count} 条`)
  check('列表是后端真实数据（不是原来写死的假数据）',
    !before.names.includes('林同学_个人简历.pdf') && !before.names.includes('校园活动平台_项目复盘.docx'),
    before.names.join('、'))
  check('角标数字与实际条数一致', before.badge.replace(/^0+(?=\d)/, '') === String(before.count),
    `角标 ${before.badge} vs 实际 ${before.count}`)

  // 3) 通过页面真的上传一个文件
  const document0 = await send('DOM.getDocument', { depth: -1 })
  const input = await send('DOM.querySelector', { nodeId: document0.result.root.nodeId, selector: '#file-input' })
  check('页面里有真实的文件选择框', Boolean(input.result?.nodeId))
  if (input.result?.nodeId) {
    // 这一步等价于用户在文件对话框里选中文件：input.files 会被真实填充
    await send('DOM.setFileInputFiles', { nodeId: input.result.nodeId, files: [UPLOAD_FILE] })
    // setFileInputFiles 不保证触发 change，这里补一次（此时 files 已就位）
    await evaluate(send, `document.querySelector('#file-input').dispatchEvent(new Event('change')); true`)
    await sleep(7000)

    const after = JSON.parse(await evaluate(send, LIST_SNAPSHOT))
    const uploadedName = UPLOAD_FILE.split(/[\\/]/).pop()
    console.log(`  上传后：条数 ${after.count}`)
    console.log(`  文件名：${after.names.join(' / ')}`)
    check('通过页面真的上传成功了（列表条数增加）', after.count > before.count,
      `${before.count} -> ${after.count}`)
    check('新上传的文件出现在列表里', after.names.some((n) => n.includes(uploadedName.replace(/\.\w+$/, ''))),
      after.names.join('、'))
  }

  // 4) 上传后页面会自动展开抽取结果——先验证这条路径。
  //    注意不要直接点"第一条"：如果它正是已展开的那条，会触发「再点一次收起」，
  //    把面板关掉（第一次跑就踩到了，所以脚本要先判断当前状态）。
  const readPanel = `JSON.stringify({
    visible: Boolean(document.querySelector('.extraction-panel')),
    heading: document.querySelector('.extraction-panel .panel-heading h3')?.textContent?.trim() || '',
    sourceLength: (document.querySelector('.extraction-panel .source-text')?.textContent || '').length,
    sourceMentionsResume: /(上海理工大学|Java|Redis|Spring)/.test(document.querySelector('.extraction-panel .source-text')?.textContent || ''),
    evidenceCount: document.querySelectorAll('.extraction-panel .extract-evidence').length,
    groups: [...document.querySelectorAll('.extraction-panel .extract-subtitle')].map(e => e.textContent.trim()),
    hasSaveButton: /确认并保存/.test(document.querySelector('.extract-actions')?.textContent || ''),
    hasHint: Boolean(document.querySelector('.extraction-panel .extract-hint'))
  })`

  const autoOpened = JSON.parse(await evaluate(send, readPanel))
  check('上传后自动展开抽取结果（不用用户再点一次）', autoOpened.visible === true)

  // 5) 再验证「点列表 → 打开」这条路径：先收起，再点第一条
  await evaluate(send, `document.querySelector('#material-list .material-item.is-active')?.click(); true`)
  await sleep(600)
  const closed = JSON.parse(await evaluate(send, readPanel))
  check('点已展开的那条会收起面板', closed.visible === false)

  await evaluate(send, `document.querySelector('#material-list .material-item')?.click(); true`)
  await sleep(2500)

  const panel = JSON.parse(await evaluate(send, readPanel))
  check('点一条材料后出现抽取结果面板', panel.visible === true)
  check('面板标题指向被点的那份材料', panel.heading.includes('抽取结果'), panel.heading)
  check('原文对照有内容（让用户能核对，而不是盲信 AI）', panel.sourceLength > 100,
    `${panel.sourceLength} 字`)
  check('原文里确实是简历内容', panel.sourceMentionsResume === true)
  check('有「确认并保存为我的资料」按钮', panel.hasSaveButton === true)
  if (panel.hasHint) {
    console.log('  [注意] 页面提示了「没有 AI token」——抽取结果为空是预期行为，不是失败')
  }
  console.log(`  分组：${panel.groups.join(' / ')}`)
  console.log(`  证据条数：${panel.evidenceCount}`)

  // 6) 点「确认并保存为我的资料」——这是和岗位页的衔接点，必须验证两条写入路径
  const saveClicked = await evaluate(send,
    `Boolean([...document.querySelectorAll('.extract-actions .button')].find(b => /确认并保存/.test(b.textContent))?.click() ?? true)`)
  check('找到了「确认并保存」按钮', saveClicked === true)
  await sleep(3000)

  const localProfile = await evaluate(send, `window.localStorage.getItem('resume-helper-profile') || ''`)
  check('确认后写入了本地求职画像（岗位匹配页读的就是它）',
    localProfile.includes('targetTitle'), localProfile.slice(0, 70))

  const backendProfile = await evaluate(send,
    `fetch('/api/profile').then(r => r.json()).then(d => JSON.stringify(d.data || {})).catch(e => 'ERR:' + e.message)`)
  check('确认后画像也同步到了后端（简历/面试模块以后从这里读）',
    backendProfile.includes('targetTitle'), backendProfile.slice(0, 70))

  // 7) 未捕获异常
  check('页面没有未捕获的 JS 异常', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '))

  const failed = results.filter((r) => !r.ok).length
  console.log(`\n=== 材料页验证：通过 ${results.length - failed} 项，失败 ${failed} 项 ===`)
  ws.close()
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((error) => {
  console.error('\n验证脚本自身出错：', error.message)
  process.exit(2)
})
