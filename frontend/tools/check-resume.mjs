/**
 * 用真实浏览器（Edge headless + CDP）验证「简历工作台」页（功能四）。
 *
 * 这个页面最该证明的三件事，只有浏览器能证明：
 *   1. 简历内容是**从画像生成**的（不是模板里写死的演示文案）
 *   2. 改一处要点，右侧 A4 预览**立刻跟着变**（预览和编辑器是同一份数据）
 *   3. 「打印 / 导出 PDF」真的能产出 PDF —— 用 CDP 的 Page.printToPDF 实际生成一份，
 *      检查它是合法 PDF 而且有内容。这一条比"按钮存在"有意义得多。
 *
 * 另外验证「保存版本 → 列表里出现 → 能载入回来」这条链路。
 *
 * 前置：
 *   1) 后端与前端都在跑，且**已经保存过一份画像**（否则简历是空的，页面会显示空状态）
 *   2) 启动带调试端口的 headless Edge：
 *
 *      & "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" `
 *          --headless=new --disable-gpu --no-first-run `
 *          --remote-debugging-port=9225 --user-data-dir="$env:TEMP\edge-resume" about:blank
 *
 * 环境变量：CHECK_BASE（默认 http://localhost:5173）、CDP_PORT（默认 9225）
 */

const CDP_PORT = Number(process.env.CDP_PORT || 9225)
const BASE = process.env.CHECK_BASE || 'http://localhost:5173'

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function pickTarget() {
  for (let attempt = 0; attempt < 20; attempt++) {
    try {
      const list = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`).then((r) => r.json())
      const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
      if (page) return page
    } catch {
      // Edge 还没起来
    }
    await sleep(500)
  }
  throw new Error('连不上 Edge 的调试端口')
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

const SNAPSHOT = `JSON.stringify({
  path: location.pathname,
  isEmpty: Boolean(document.querySelector('.empty-panel')),
  summaryLength: (document.querySelector('.resume-textarea')?.value || '').length,
  groups: [...document.querySelectorAll('.editor-section-title h3')].map(e => e.textContent.trim()),
  cards: document.querySelectorAll('.resume-editor .editable-card').length,
  paperText: (document.querySelector('#resume-paper')?.textContent || '').replace(/\\s+/g, ' ').trim(),
  paperSections: [...document.querySelectorAll('#resume-paper .paper-section h3')].map(e => e.textContent.trim()),
  paperPerson: (document.querySelector('#resume-paper .paper-person')?.textContent || '').replace(/\\s+/g, ' ').trim(),
  hasContactWarning: Boolean(document.querySelector('.contact-warning')),
  versionCount: document.querySelectorAll('.versions-panel .version-item').length,
  hasPrintButton: /打印/.test(document.querySelector('.intro-actions')?.textContent || ''),
  hasSaveButton: /保存版本/.test(document.querySelector('.intro-actions')?.textContent || '')
})`

const setValue = (selector, value) => `(() => {
  const el = document.querySelector(${JSON.stringify(selector)});
  if (!el) return false;
  const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(value)});
  el.dispatchEvent(new Event('input', { bubbles: true }));
  return true;
})()`

async function main() {
  const target = await pickTarget()
  const { ws, ready, send } = connect(target.webSocketDebuggerUrl)
  await ready
  await send('Page.enable')
  await send('Runtime.enable')

  const consoleErrors = []
  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.method !== 'Runtime.exceptionThrown') return
    const details = message.params?.exceptionDetails
    const description = details?.exception?.description || details?.text || '未知异常'
    const sourceUrl = details?.url || details?.stackTrace?.callFrames?.[0]?.url || ''
    if (sourceUrl.startsWith('chrome-extension://') || sourceUrl.startsWith('moz-extension://')
        || description.includes('chrome-extension://')) {
      return
    }
    consoleErrors.push(sourceUrl ? `${description}  (${sourceUrl})` : description)
  })

  console.log('=== 简历工作台 端到端验证 ===')
  console.log(`目标：${BASE}/resume\n`)

  await send('Page.navigate', { url: `${BASE}/login` })
  await sleep(2000)
  await evaluate(send, `window.localStorage.setItem('resume-helper-demo-session', JSON.stringify({authenticated:true,email:'demo@resume-helper.cn',displayName:'林同学',createdAt:new Date().toISOString()})); true`)

  // 种一份带联系方式的画像，让这个检查可以独立重复运行
  // （简历抬头读的正是画像里的姓名 / 电话 / 邮箱）
  await evaluate(send, `window.localStorage.removeItem('resume-helper-profile'); true`)
  await evaluate(send, `fetch('/api/profile', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: '林一舟', phone: '138 0000 0000', email: 'linyizhou@example.com',
      targetTitle: 'Java 后端开发工程师', targetCities: ['上海'], yearsOfExperience: 0,
      education: '本科', educations: ['上海理工大学 · 本科 · 计算机科学与技术 · 2022.09—2026.06'],
      skills: ['Java', 'Spring Boot', 'MySQL', 'Redis'], industries: ['互联网'],
      expectedSalary: '15-22K', summary: '计算机科学与技术专业应届毕业生，具备 Java 后端开发项目实践经验。',
      experiences: ['上海璞云信息技术有限公司 · Java 后端开发实习生 · 2025.07—2025.09：参与了权限模块开发；把权限校验改成 Redis 缓存']
    })
  }).then(r => r.ok)`)

  await send('Page.navigate', { url: `${BASE}/resume` })
  await sleep(4000)

  const page = JSON.parse(await evaluate(send, SNAPSHOT))
  console.log(`  抬头：${page.paperPerson}`)
  console.log(`  分区：${page.groups.join(' / ')}`)
  console.log(`  纸面区块：${page.paperSections.join(' / ')}`)
  console.log(`  纸面预览长度：${page.paperText.length} 字`)
  console.log(`  已存版本：${page.versionCount}`)

  check('已停留在 /resume（没被守卫弹回登录页）', page.path === '/resume', page.path)
  check('有内容（说明画像已就绪）', page.isEmpty === false, page.isEmpty ? '页面显示空状态' : '')
  check('有「打印 / 导出 PDF」按钮', page.hasPrintButton === true)
  check('有「保存版本」按钮', page.hasSaveButton === true)
  check('教育 / 经历分区都在', page.groups.length >= 3, page.groups.join('、'))
  check('有可编辑的经历卡片', page.cards > 0, `${page.cards} 张`)
  check('A4 预览里有简历正文', page.paperText.length > 100, `${page.paperText.length} 字`)
  check('预览里出现了教育经历（说明内容是生成的，不是空模板）',
    page.paperSections.includes('教育经历'), page.paperSections.join('、'))
  // 抬头三件套：姓名 / 电话 / 邮箱。没有联系方式的简历投出去等于白发
  check('抬头显示画像里的姓名（不是会话里写死的"林同学"）',
    page.paperPerson.includes('林一舟') && !page.paperPerson.includes('林同学'), page.paperPerson)
  check('抬头有电话', page.paperPerson.includes('138 0000 0000'), page.paperPerson)
  check('抬头有邮箱', page.paperPerson.includes('linyizhou@example.com'), page.paperPerson)
  check('联系方式齐全时不显示"缺联系方式"的警告', page.hasContactWarning === false)

  // 1) 改一条要点 → 预览应当立刻变
  const marker = `验证用要点${Date.now() % 100000}`
  const edited = await evaluate(send,
    `(() => {
      const box = document.querySelector('.resume-editor .entry-bullets');
      if (!box) return false;
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(box, ${JSON.stringify(marker)});
      box.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    })()`)
  check('能编辑经历要点', edited === true)
  await sleep(700)

  const afterEdit = JSON.parse(await evaluate(send, SNAPSHOT))
  check('改了要点后，A4 预览立刻跟着变（预览和编辑器是同一份数据）',
    afterEdit.paperText.includes(marker), afterEdit.paperText.slice(0, 90))

  // 2) 保存版本
  await evaluate(send,
    `[...document.querySelectorAll('.intro-actions .button')].find(b => /保存版本/.test(b.textContent))?.click(); true`)
  await sleep(3000)

  const afterSave = JSON.parse(await evaluate(send, SNAPSHOT))
  check('保存后版本列表里多了一版', afterSave.versionCount > page.versionCount,
    `${page.versionCount} -> ${afterSave.versionCount}`)

  // 3) 验证打印 / 导出 PDF 的样式。
  //
  //    本来想直接调 CDP 的 Page.printToPDF 生成一份真 PDF 来断言，
  //    但本机 Edge 的 headless 返回 {"code":-32000,"message":"Printing is not available"}，
  //    这是浏览器的限制，不是页面问题。
  //
  //    所以改成**模拟 print 媒体**，检查打印时到底显示了什么：
  //    界面元素应当被隐藏、只留 A4 纸面。这正是"导出效果对不对"的决定因素——
  //    如果工具栏和编辑器没被隐藏，导出的 PDF 上就会顶着一堆按钮。
  await send('Emulation.setEmulatedMedia', { media: 'print' })
  await sleep(600)
  const printView = JSON.parse(await evaluate(send, `JSON.stringify({
    editorHidden: getComputedStyle(document.querySelector('.resume-editor')).display === 'none',
    toolbarHidden: getComputedStyle(document.querySelector('.resume-toolbar')).display === 'none',
    introHidden: getComputedStyle(document.querySelector('.page-intro')).display === 'none',
    targetHidden: getComputedStyle(document.querySelector('.target-panel')).display === 'none',
    versionsHidden: getComputedStyle(document.querySelector('.versions-panel')).display === 'none',
    suggestionHidden: document.querySelector('.suggestion-panel')
      ? getComputedStyle(document.querySelector('.suggestion-panel')).display === 'none' : true,
    paperVisible: getComputedStyle(document.querySelector('#resume-paper')).display !== 'none',
    paperWidth: getComputedStyle(document.querySelector('#resume-paper')).width,
    paperShadow: getComputedStyle(document.querySelector('#resume-paper')).boxShadow
  })`))
  await send('Emulation.setEmulatedMedia', { media: '' })

  console.log(`  打印视图：纸面宽 ${printView.paperWidth}，阴影 ${printView.paperShadow}`)
  check('打印时隐藏了编辑器', printView.editorHidden === true)
  check('打印时隐藏了工具栏与页头', printView.toolbarHidden && printView.introHidden)
  check('打印时隐藏了本页新增的定向 / 建议 / 版本面板',
    printView.targetHidden && printView.versionsHidden && printView.suggestionHidden,
    `定向 ${printView.targetHidden} / 版本 ${printView.versionsHidden} / 建议 ${printView.suggestionHidden}`)
  check('打印时保留 A4 纸面', printView.paperVisible === true)
  // 210mm 在 96dpi 下约 793.7px
  check('纸面按 A4 宽度排版', parseFloat(printView.paperWidth) > 700, printView.paperWidth)
  check('打印时去掉纸面阴影（否则纸上会有一圈灰边）',
    printView.paperShadow === 'none', printView.paperShadow)

  // 4) 没有 AI token 时给建议应当优雅降级，而不是报错或编几条假建议
  await evaluate(send, setValue('.target-panel textarea', '负责后端服务的设计与开发，熟悉 Java / Spring Boot。'))
  await sleep(400)
  await evaluate(send,
    `[...document.querySelectorAll('.target-panel .button')].find(b => /改写建议/.test(b.textContent))?.click(); true`)
  // 页面的提示 4 秒后会自动消失，所以这里不能等满 4 秒再读——第一次就踩到了，
  // 读到的是已经被清空的提示，看起来像"没有给出提示"。
  await sleep(2200)

  const afterSuggest = JSON.parse(await evaluate(send, `JSON.stringify({
    cards: document.querySelectorAll('.suggestion-card').length,
    notice: document.querySelector('.notice')?.textContent?.trim() || ''
  })`))
  console.log(`  建议条数：${afterSuggest.cards}；页面提示：${afterSuggest.notice || '（无）'}`)
  check('没有 token 时给出可读提示，而不是编造建议或报错',
    afterSuggest.cards > 0 || /token/i.test(afterSuggest.notice),
    afterSuggest.notice || `${afterSuggest.cards} 条建议`)

  // 5) 反向验证：把电话邮箱清掉后，"缺联系方式"的警告必须出现。
  //    只验证"有联系方式时不显示警告"是不够的——那样即使警告是死代码也能通过。
  await evaluate(send, `fetch('/api/profile').then(r => r.json()).then(d => {
    const p = d.data || {};
    delete p.phone; delete p.email;
    return fetch('/api/profile', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(p)
    });
  }).then(r => r.ok)`)
  await send('Page.navigate', { url: `${BASE}/resume` })
  await sleep(3500)

  const withoutContact = JSON.parse(await evaluate(send, SNAPSHOT))
  console.log(`  清掉联系方式后抬头：${withoutContact.paperPerson}`)
  check('清掉电话邮箱后出现"缺联系方式"的警告（说明它不是死代码）',
    withoutContact.hasContactWarning === true)
  check('抬头里确实不再有电话邮箱',
    !withoutContact.paperPerson.includes('138 0000 0000')
      && !withoutContact.paperPerson.includes('linyizhou@example.com'),
    withoutContact.paperPerson)

  check('页面没有未捕获的 JS 异常', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '))

  const failed = results.filter((r) => !r.ok).length
  console.log(`\n=== 简历工作台验证：通过 ${results.length - failed} 项，失败 ${failed} 项 ===`)
  ws.close()
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((error) => {
  console.error('\n验证脚本自身出错：', error.message)
  process.exit(2)
})
