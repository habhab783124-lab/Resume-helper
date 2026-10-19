# 拾阶 · 求职工作台前端

本目录包含 Vue 3 前端页面。登录 / 注册入口调用真实的后端账号接口，
密码与令牌说明见根目录 README 的「注册与登录」。

**后端连接状态**：**岗位匹配页（`/jobs`）、工作台推荐位（`/dashboard`）和模拟面试（`/interview`）已经接上后端**。
岗位页通过 `src/api/client.js` 的 axios 封装调用，能显示真实的匹配度、技能标签、匹配理由与差距；
面试页通过 `src/api/interview.js` 支持简历上传、AI 文字问答与刷新恢复，
运行需要同时启动 Java 后端并配置模型，详见 [文字面试接口与接入](../backend/docs/文字面试接口与接入.md)。
个人资料、材料整理、简历与报告页也都已接上后端。

## 本地运行

需要 Node.js 18 或更新版本。

```bash
npm ci
npm run dev
```

> **两个环境坑**（换机器时容易踩）：
>
> 1. **`EALLOWREMOTE`**：npm 11/12 出于安全会拦截「非默认 registry 的远程包」，
>    而 `package-lock.json` 里的依赖指向 npmmirror 镜像。用镜像源装即可：
>    ```bash
>    npm ci --registry=https://registry.npmmirror.com
>    ```
> 2. **Windows 报「禁止运行脚本」**：PowerShell 执行策略拦住了 `npm.ps1`。
>    把命令换成 `npm.cmd`，或执行一次
>    `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`。

## 连后端一起跑（岗位页需要它）

**开两个终端：**

```bash
# 终端 1：后端（默认 8080）
cd backend
mvn spring-boot:run

# 终端 2：前端（默认把 /api 代理到 http://localhost:8080）
cd frontend
npm run dev
```

打开 <http://localhost:5173/jobs>，应该能看到带匹配度的岗位卡片、
下拉式的筛选按钮，以及「让智能体去找」的执行轨迹。

**后端不在 8080**（例如本机 8080 被别的程序占了）时，不用改代码：

```powershell
$env:VITE_PROXY_TARGET = 'http://localhost:8081'
npm run dev
```

**后端没启动会怎样**：岗位页不会白屏，会在状态条里显示
「连不上后端服务。请确认后端已启动……」并把空列表的说明换成「岗位没能加载出来」。
这是刻意设计的——比起报错页，静态骨架至少还能看出产品的样子。

## 验证「真的打通了」

改完前后端代码后，想知道渲染有没有坏，可以跑一遍浏览器端到端检查：

```powershell
# 1) 起一个带调试端口的 headless Edge
& "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" `
    --headless=new --disable-gpu --no-first-run `
    --remote-debugging-port=9222 --user-data-dir="$env:TEMP\edge-check" about:blank

# 2) 另开一个终端（前端和后端都要在跑）
node tools/check-render.mjs
```

它会真实渲染 `/jobs` 和 `/dashboard`，断言卡片数量、标题、匹配度、公司首字、配色类、
技能标签、匹配理由、薪资、数据源状态条，并检查有没有未捕获的 JS 异常。

**为什么值得跑**：`npm run build` 只证明代码能编译，curl 只证明网络通和 JSON 对，
**只有浏览器渲染才能证明 Vue 真的把数据变成了卡片**。这个脚本不依赖任何 npm 包
（Node 22+ 自带 WebSocket，CDP 就是 WebSocket + JSON）。

## 目录约定

```
src/
├── api/
│   ├── client.js        axios 实例：统一解包 {success,message,data}、错误翻译、AI token 请求头
│   └── jobs.js          岗位接口封装（搜索 / 智能体 / 导入 / 匹配 / 收藏）
├── utils/
│   ├── demoAuth.js      演示登录会话
│   └── demoProfile.js   求职画像（岗位匹配用）
└── views/               页面
```

**关于 `demoProfile.js`**：岗位匹配需要用户画像，而「材料解析」还没做。
所以它现在提供一份与 `Profile.vue` 页面一致的**演示画像**，让匹配度是真实算出来的、不是假的。
等功能一完成后，它只要调用 `saveProfile(解析结果)`，岗位页就会自动用上真实画像，
`demoProfile.js` 本身不需要改——这是刻意留的接口缝。

**关于 AI token**：需求是「让用户填自己的 token」。岗位页顶部有个输入框，
填了会存在 `sessionStorage`（关闭标签页即失效），每次请求作为 `X-AI-Api-Key` 请求头带给后端。
不填也能用，后端会自动降级为规则检索，并在页面上如实说明。

## 发布到 Vercel

在 Vercel 选择 **Add New → Project**，导入 GitHub 仓库 `Resume-helper`，并将 **Root Directory** 设为 `frontend`。构建设置使用：

- Framework Preset：Vite
- Install Command：`npm ci`
- Build Command：`npm run build`
- Output Directory：`dist`

成功部署后，Vercel 会分配一个以项目名为基础的 `*.vercel.app` 网址。连接 GitHub 后，后续推送到生产分支会自动重新部署。

> 部署版默认尝试连接访问者当前设备的 `http://localhost:8080`。浏览器中的 `localhost` 指向打开网页的那台设备；访问者需要在该设备运行 Java 后端。可在网页的「本地服务设置」中修改地址并检测连接。
>
> 本地服务地址保存在当前浏览器中，不会自动启动 Java 进程。用户要先在项目的 `backend` 目录运行 `mvn spring-boot:run`，并保持服务运行；网页的连接设置会负责检测服务状态。Java 端需要允许正式 Vercel 域名进行 CORS 跨域访问。
>
> 需要多人共用同一个后端时，可设置 `VITE_API_BASE_URL=https://你的后端域名`，并将这个域名加入 `backend/src/main/resources/application.yml` 的 CORS 白名单。
