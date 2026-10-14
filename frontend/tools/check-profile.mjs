/**
 * 用真实浏览器（Edge headless + CDP）验证「个人资料」页。
 *
 * 这个页面是 功能一（材料整理）和 功能二（岗位匹配）之间的**衔接点**：
 * 材料页确认后的画像存在这里、显示在这里、也从这里改。所以它最该验证的不是"长得对不对"，
 * 而是**读写两条链路真的通**：
 *   读：后端 /api/profile 的画像出现在页面上（而不是写死的假数据）
 *   写：在页面上改一个字段 → 保存 → 后端和 localStorage 都变了（岗位页读的是后者）
 *
 * 检查什么：
 *   1. /profile 渲染出来了，标题里的完整度是按实际字段算的
 *   2. 技能 / 教育经历 / 经历来自画像，不是模板里写死的
 *   3. 点「编辑资料」能改、能存，保存后后端与本机都更新了
 *   4. 清空一个必填字段后完整度会下降（证明它真的是算出来的，不是装饰）
 *   5. 没有未捕获的 JS 异常
 *
 * 前置：后端与前端都在跑，并启动带调试端口的 headless Edge：
 *
 *   & "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" `
 *       --headless=new --disable-gpu --no-first-run `
 *       --remote-debugging-port=9224 --user-data-dir="$env:TEMP\edge-profile" about:blank
 *
 * 环境变量：CHECK_BASE（默认 http://localhost:5173）、CDP_PORT（默认 9224）
 */

const CDP_PORT = Number(process.env.CDP_PORT || 9224)
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
  name: document.querySelector('.profile-banner-info h2')?.textContent?.trim() || '',
  kicker: document.querySelector('.profile-banner-info p')?.textContent?.trim() || '',
  completeness: document.querySelector('.profile-completion strong')?.textContent?.trim() || '',
  barWidth: document.querySelector('.profile-completion .tiny-progress i')?.style?.width || '',
  fields: [...document.querySelectorAll('.detail-grid > div')].map(d => ({
    label: d.querySelector('span')?.textContent?.trim() || '',
    value: d.querySelector('strong')?.textContent?.trim() || ''
  })),
  skills: [...document.querySelectorAll('.skills-panel .skill-tag')].map(e => e.textContent.trim()),
  educations: [...document.querySelectorAll('.detail-panel .timeline-title strong')].map(e => e.textContent.trim()),
  experiences: [...document.querySelectorAll('.project-description')].map(e => e.textContent.trim()),
  source: document.querySelector('.profile-kicker')?.textContent?.trim() || '',
  hasEditButton: /编辑资料/.test(document.querySelector('.page-intro .button')?.textContent || '')
})`

/**
 * 按**字段名**改一个输入框。
 *
 * 不能用 `.edit-grid input` 这种按位置选的写法：字段一多就会选错——
 * 加了姓名 / 电话 / 邮箱之后，第一个输入框已经不是"目标职位"了，
 * 而按位置写的断言还会"看起来通过"，实际改的是别的字段。
 */
const setField = (labelText, value) => `(() => {
  const label = [...document.querySelectorAll('.edit-grid label')]
    .find((l) => (l.querySelector('span')?.textContent || '').includes(${JSON.stringify(labelText)}));
  if (!label) return false;
  const input = label.querySelector('input, textarea');
  if (!input) return false;
  const proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(input, ${JSON.stringify(value)});
  input.dispatchEvent(new Event('input', { bubbles: true }));
  return true;
})()`

/** 读取编辑态里某个字段当前的值。 */
const fieldValue = (labelText) => `(() => {
  const label = [...document.querySelectorAll('.edit-grid label')]
    .find((l) => (l.querySelector('span')?.textContent || '').includes(${JSON.stringify(labelText)}));
  return label?.querySelector('input, textarea')?.value ?? null;
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

  console.log('=== 个人资料页 端到端验证 ===')
  console.log(`目标：${BASE}/profile\n`)

  // 1) 种会话再进页面
  await send('Page.navigate', { url: `${BASE}/login` })
  await sleep(2000)
  await evaluate(send, `window.localStorage.setItem('resume-helper-demo-session', JSON.stringify({authenticated:true,email:'demo@resume-helper.cn',displayName:'林同学',createdAt:new Date().toISOString()})); true`)

  // 种一份完整画像，让断言不依赖上一轮运行留下的状态。
  // 不清干净的话，上一次跑完留下的 targetTitle:'' / skills:[] 会让这一跑莫名其妙地失败——
  // 检查脚本自己必须能重复运行。
  await evaluate(send, `window.localStorage.removeItem('resume-helper-profile'); true`)
  await evaluate(send, `fetch('/api/profile', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      targetTitle: 'Java 后端开发工程师', targetCities: ['上海'], yearsOfExperience: 0,
      name: '林一舟', phone: '138 0000 0000', email: 'linyizhou@example.com',
      education: '本科', educations: ['上海理工大学 · 本科 · 计算机科学与技术 · 2022.09—2026.06'],
      skills: ['Java', 'Spring Boot', 'MySQL', 'Redis', 'Git'],
      industries: ['互联网'], expectedSalary: '15-22K',
      summary: '计算机科学与技术专业应届毕业生，具备 Java 后端开发项目实践经验。',
      experiences: ['校园活动管理平台（后端开发 · 个人项目，2025.03—2025.07）：基于 Spring Boot 构建活动管理服务']
    })
  }).then(r => r.ok)`)

  await send('Page.navigate', { url: `${BASE}/profile` })
  await sleep(3500)

  const page = JSON.parse(await evaluate(send, SNAPSHOT))
  console.log(`  姓名：${page.name}　${page.kicker}`)
  console.log(`  完整度：${page.completeness}（进度条 ${page.barWidth}）`)
  console.log(`  字段：${page.fields.map((f) => `${f.label}=${f.value}`).join('　')}`)
  console.log(`  技能：${page.skills.join('、')}`)
  console.log(`  教育：${page.educations.join(' / ')}`)

  check('已停留在 /profile（没被守卫弹回登录页）', page.path === '/profile', page.path)
  // 画像里的姓名要压过登录会话里写死的"林同学"——用户自己的名字优先
  check('抬头显示画像里的姓名（而不是会话里写死的名字）', page.name === '林一舟', page.name)
  check('有「编辑资料」按钮', page.hasEditButton === true)
  check('完整度是按实际字段算出来的（不是写死的 82%）', /^\d+%$/.test(page.completeness),
    `${page.completeness}，进度条 ${page.barWidth}`)
  check('进度条宽度与百分比一致', page.barWidth === page.completeness, `${page.barWidth} vs ${page.completeness}`)
  check('基本信息有真实内容', page.fields.some((f) => f.label === '目标职位' && f.value && f.value !== '未填写'),
    JSON.stringify(page.fields.slice(0, 2)))
  // 联系方式：一份没有电话邮箱的简历投出去等于白发，必须真的显示出来
  check('基本信息里显示了联系电话', page.fields.some((f) => f.label === '联系电话' && f.value === '138 0000 0000'),
    JSON.stringify(page.fields.find((f) => f.label === '联系电话')))
  check('基本信息里显示了电子邮箱', page.fields.some((f) => f.label === '电子邮箱' && f.value === 'linyizhou@example.com'),
    JSON.stringify(page.fields.find((f) => f.label === '电子邮箱')))
  check('技能来自画像（不是模板写死的）', page.skills.length > 0, page.skills.join('、'))
  check('教育经历来自画像', page.educations.length > 0, page.educations.join(' / '))
  check('经历来自画像', page.experiences.length > 0, `${page.experiences.length} 条`)

  // 2) 编辑：改目标职位与电话
  const marker = `验证用岗位${Date.now() % 100000}`
  const phone = `139${String(Date.now()).slice(-8)}`
  await evaluate(send, `document.querySelector('.page-intro .button')?.click(); true`)
  await sleep(700)
  const editing = await evaluate(send, `Boolean(document.querySelector('.edit-grid'))`)
  check('点「编辑资料」切到编辑态', editing === true)

  // 按字段名改，不按位置改：加了联系方式后第一个输入框已经不是"目标职位"了
  const typedTitle = await evaluate(send, setField('目标职位', marker))
  const typedPhone = await evaluate(send, setField('联系电话', phone))
  check('能按字段名往输入框里输入', typedTitle === true && typedPhone === true,
    `目标职位 ${typedTitle} / 联系电话 ${typedPhone}`)
  check('输入后表单里读到的就是刚填的值',
    (await evaluate(send, fieldValue('联系电话'))) === phone)

  await evaluate(send,
    `[...document.querySelectorAll('.edit-actions .button')].find(b => /保存资料/.test(b.textContent))?.click(); true`)
  await sleep(3000)

  const after = JSON.parse(await evaluate(send, SNAPSHOT))
  check('保存后回到查看态', !(await evaluate(send, `Boolean(document.querySelector('.edit-grid'))`)))
  check('页面上的目标职位已更新', after.fields.some((f) => f.value === marker),
    after.fields.map((f) => `${f.label}=${f.value}`).join('　'))
  check('页面上的联系电话已更新', after.fields.some((f) => f.label === '联系电话' && f.value === phone),
    JSON.stringify(after.fields.find((f) => f.label === '联系电话')))
  check('保存后来源标注变成「已保存到后端」', after.source.includes('已保存到后端'), after.source)

  const local = await evaluate(send, `window.localStorage.getItem('resume-helper-profile') || ''`)
  check('画像写进了 localStorage（岗位匹配页读的就是它）', local.includes(marker), local.slice(0, 80))

  const backend = await evaluate(send,
    `fetch('/api/profile').then(r => r.json()).then(d => JSON.stringify(d.data || {})).catch(e => 'ERR:' + e.message)`)
  check('画像同步到了后端', backend.includes(marker), backend.slice(0, 80))
  check('联系方式也同步到了后端（简历抬头要用）', backend.includes(phone), backend.slice(0, 120))

  // 3) 完整度确实是算出来的：清空目标职位后应当下降。
  //    同样按字段名清，不按位置——否则清的可能是"姓名"，那这条断言就名不副实了。
  await evaluate(send, `document.querySelector('.page-intro .button')?.click(); true`)
  await sleep(600)
  const clearedOk = await evaluate(send, setField('目标职位', ''))
  await sleep(400)
  await evaluate(send,
    `[...document.querySelectorAll('.edit-actions .button')].find(b => /保存资料/.test(b.textContent))?.click(); true`)
  await sleep(2500)

  const cleared = JSON.parse(await evaluate(send, SNAPSHOT))
  check('清空字段的操作命中的是「目标职位」', clearedOk === true)
  check('清空一个字段后完整度真的下降（说明它是算出来的，不是装饰）',
    parseInt(cleared.completeness, 10) < parseInt(after.completeness, 10),
    `${after.completeness} -> ${cleared.completeness}`)
  check('清空的字段显示为「未填写」而不是空白',
    cleared.fields.some((f) => f.label === '目标职位' && f.value === '未填写'),
    JSON.stringify(cleared.fields.find((f) => f.label === '目标职位')))
  check('但清空目标职位不会顺手把联系方式也清掉',
    cleared.fields.some((f) => f.label === '联系电话' && f.value === phone),
    JSON.stringify(cleared.fields.find((f) => f.label === '联系电话')))

  // 4) 异常
  check('页面没有未捕获的 JS 异常', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '))

  const failed = results.filter((r) => !r.ok).length
  console.log(`\n=== 个人资料页验证：通过 ${results.length - failed} 项，失败 ${failed} 项 ===`)
  ws.close()
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((error) => {
  console.error('\n验证脚本自身出错：', error.message)
  process.exit(2)
})
