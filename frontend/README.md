# 拾阶 · 求职工作台前端

本目录包含 Vue 3 前端页面。登录入口用于进入演示工作台。

**后端连接状态**：**岗位匹配页（`/jobs`）和工作台推荐位（`/dashboard`）已经接上后端**，
通过 `src/api/` 下的 axios 封装调用，能显示真实的匹配度、技能标签、匹配理由与差距。
其余页面（个人资料 / 材料整理 / 简历 / 面试 / 报告）仍是静态原型。

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

> ⚠️ **部署版本连不上本地后端，这是必然的**，不是 bug：
> Vercel 的服务器和访问者的浏览器都到不了你电脑的 `localhost`，
> 而且 Vercel 是 HTTPS，浏览器不允许它请求 `http://` 接口（混合内容拦截）。
>
> 要在 Vercel 上用真实数据，需要：
> 1. 后端也部署到公网（且必须是 HTTPS）；
> 2. 前端配环境变量 `VITE_API_BASE_URL=https://你的后端域名`；
> 3. 后端把前端域名加进 CORS 白名单（见 `backend/README.md` 3.1 节）。
>
> 课程演示用本地运行（`npm run dev`）最省事，也最不容易在演示当天翻车。
