# Resume-helper · 拾阶求职工作台

![Java 17](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot 3.2.5](https://img.shields.io/badge/Spring%20Boot-3.2.5-6DB33F?logo=springboot&logoColor=white)
![Vue 3.5](https://img.shields.io/badge/Vue-3.5-42B883?logo=vuedotjs&logoColor=white)
![Vite 5](https://img.shields.io/badge/Vite-5-646CFF?logo=vite&logoColor=white)
![Maven](https://img.shields.io/badge/build-Maven-C71A36?logo=apachemaven&logoColor=white)
![Node 24+](https://img.shields.io/badge/Node-24%2B-5FA04E?logo=nodedotjs&logoColor=white)

面向应届毕业生的求职准备课程项目：从一份简历材料出发，整理成个人资料，生成定向简历，
找到匹配的岗位，再练一遍模拟面试。

## 仓库结构

- `frontend/`：Vue 3 + JavaScript 前端，含登录入口、工作台与六个求职功能页面。
- `backend/`：Spring Boot 后端（模块化单体），实现四个功能模块，见
  [后端说明](backend/README.md)。
- `prototype-static/`：独立 HTML / CSS / JavaScript 原型，展示完整的七个产品页面与交互流程。
- `preview-server.cjs`：本地预览静态原型的小型 Node 服务器。
- `AI求职与面试平台_20天课程项目方案.md`：产品与实现方案。

## 功能与实现状态

| 功能 | 后端 | 前端 | 说明 |
|---|---|---|---|
| 一、材料整理 | ✅ | ✅ | 上传 PDF / DOCX / 粘贴文本 → 抽取正文 → 大模型结构化抽取 → 用户核对确认成求职画像 |
| 二、岗位来源与匹配 | ✅ | ✅ | 岗位检索智能体、六维可解释匹配、技能差距报告 |
| 三、模拟面试 | ✅ | ✅ | 文字作答 / 可选听题与录音转写，预览确认提交，再生成报告；当前厂商与真人验收待完成 |
| 四、简历生成 | ✅ | ✅ | 按画像生成内容、针对岗位描述给逐条改写建议、保存版本、打印导出 PDF |

四个页面都已接上后端，不再是静态展示。**没有做招聘网站爬虫**——合规原因，见
[后端说明第 4 节](backend/README.md)；岗位数据用「人工整理的 CSV + 演示岗位库 + 粘贴 JD」兜底。

## 运行

```bash
# 后端（JDK 17；仓库自带 Maven Wrapper，首次运行会自动下载 Maven）
cd backend
.\mvnw.cmd spring-boot:run     # macOS / Linux 用 ./mvnw spring-boot:run
# 本机 8080 常被其他程序占用，可换端口：$env:SERVER_PORT=8081

# 前端
cd frontend
npm install
npm run dev            # 打开 http://localhost:5173
```

Vite 已把 `/api` 代理到后端 8080。后端地址不同时用 `VITE_PROXY_TARGET` 覆盖，不必改代码。

### 注册与登录

打开 http://localhost:5173/login ，在「登录 / 注册」之间切换：

- 注册只需要邮箱 + 密码（至少 8 位）+ 可选昵称，不发送验证邮件，账号直接保存在本机后端。
- 密码使用 PBKDF2-HMAC-SHA256 加盐哈希保存，账号文件在 `backend/data/auth/users.json`。
- 登录成功后后端签发 7 天有效的 HS256 令牌，前端放在 `Authorization: Bearer` 里；
  后端用令牌里的用户 id 覆盖客户端传来的 `X-User-Id`，各业务模块仍按原方式分区数据。
- 正式部署请通过环境变量 `AUTH_TOKEN_SECRET` 配置固定的令牌密钥；留空时每次重启会生成临时密钥，
  已登录用户需要重新登录。
- 只有运行旧版演示脚本时才建议 `$env:AUTH_ENFORCE="false"`，保留可伪造的 `X-User-Id` 兼容模式；
  默认 `true` 会要求所有 `/api` 业务接口都带有效令牌。

### 关于 AI token

四个模块里涉及大模型的地方（材料抽取、匹配解释、面试出题与报告、简历改写建议）都支持
**用户自带 token**：页面上填入即可，三项文本模型设置共用当前标签页的内存与 sessionStorage，
支持跨路由及同标签页刷新；按文本模型请求传给后端，不进入 URL、不落库、不打日志。
不填也能用——材料抽取会明确提示无法自动识别并允许手动整理，岗位匹配退回规则打分，
简历页仍可手动编辑和打印。**不会用规则硬凑一份看起来完整的结果。**

面试出题与评分需要服务端或个人模型密钥；评分不可用时保留原问答和待评分状态，支持重试。语音请求不携带文本模型设置。

### QUICK / FULL 模拟面试

新计划可选择 QUICK 或 FULL，先预览并确认简历/JD、问题数量、难度和岗位专项；FULL 默认沿用12道主问题加1道专项题的结构，每道主问题最多两次追问。旧版会话保留原 12+1 规则。软件开发等编码岗位可安排官方 Hot100 算法题，运营、产品、市场、财务等岗位安排专业案例；优先依据目标岗位，方向不明确时采用案例。

问题标准、评分标准和题目权重在提问前冻结。FULL 报告在范围完整且评分成功后显示综合训练参考分；提前结束仍可查看已答题反馈，未完成内容不记零分。QUICK 可在关闭题组后查看教学反馈、创建独立重答或三题短练；完整操作与报告范围见[文字面试接口与接入](backend/docs/文字面试接口与接入.md)。

### 语音面试（可选）

需要三个额外条件，**不配也不影响文字面试**：

1. 部署机装有 `ffmpeg` 与 `ffprobe` 且在 PATH 上（后端转码用）
2. 火山引擎「语音技术控制台」开通豆包语音，账户对 ASR / TTS 资源有权限与额度
3. 环境变量 `VOLC_SPEECH_API_KEY`（**必须是语音控制台的 key**，火山方舟的 `ark-` key 调语音接口会 401）

ASR/TTS 资源和音色按账号实际权限配置。能力接口只检查本机配置/工具，不证明额度可用。
录音最多 5 MiB / 120 秒；材料上传最多 10 MiB，全局 multipart 为 10MB / 12MB。
录音仅主动识别时发送，转写先预览，用户确认并显式提交才进入下一题。取消不保证厂商已执行请求免费。
入口须 HTTPS 或 localhost；转写客户端默认 90 秒，部署反向代理超时至少 100 秒。

配置与排查见 [语音面试接口与接入](backend/docs/语音面试接口与接入.md)。

## 已知限制

- 账号认证已接入，但仍是**课程项目的单机实现**：用户保存在本机 `data/auth/users.json`，
  没有邮箱验证、找回密码、登录限流和跨实例共享；正式上线前应换成数据库并补上这些能力。
  令牌密钥通过 `AUTH_TOKEN_SECRET` 配置，未配置时重启会让已登录用户重新登录。
- 材料、简历版本与面试快照使用**本机文件系统**。面试快照默认写入 `./data/interviews`，按演示用户分区、原子替换；新会话默认保留 30 天（`app.interview.retention-days` 可配置，`expiresAt` 创建时固定），旧会话期限不会追溯延长。服务重启后可恢复未过期的已提交记录，运行中的模型任务会作为可重试失败状态恢复。
  当前是单机单实例存储，没有跨主机共享、分布式锁或备份能力；部署多副本前需迁移到共享持久化存储并实现协调。
- 语音面试依赖外部服务；没有密钥时相关能力自动关闭。
- 原始录音刷新后无法恢复；回答草稿与转写预览按题保存在本机，保留 7 天。后端重启可恢复尚未到期的已提交内容；本机文件损坏、磁盘不可用或超过保留期后无法恢复。当前真人权限/设备、Safari/Edge、
  实际 Windows 部署和真实厂商音质/用量验收仍见[剩余计划](docs/superpowers/plans/2026-10-17-interview-unified-plan.md)。

## 验证方式

2026-10-19 登录改造回归：新增 `AuthApiTest` **3 项**通过（注册、登录、令牌身份覆盖伪造请求头、密码哈希落盘）；
前端 `npm run build` 通过，238 项 JavaScript 测试通过（含 5 项登录会话测试）。旧的浏览器检查脚本种的是不带令牌的演示会话，
需要用 `AUTH_ENFORCE=false` 启动后端，或按脚本里的说明换成真实登录会话。

新增面试浏览器检查（Node 24+、Chrome、ffmpeg）：

```bash
cd frontend
node tools/check-interview.mjs
```

脚本自启隔离的本地模拟 API、Vite 和 headless Chrome，原生 MediaRecorder 使用假设备，结束清理进程/临时 profile。
当前 98 项检查覆盖正常对话、语音模拟、报告、来源/JD、QUICK/FULL、跳题、保存/结束响应丢失、自动重试（最多 5 次、总预算 120 秒）、草稿存储不可用、录音/转写/首题/重试中结束、历史删除确认/刷新和 390px 布局。浏览器服务是本地模拟，不代表真实厂商、真人设备或目标系统验收。
输出截图、验证 JSON 与 Chrome 打印 PDF；`CHECK_ARTIFACT_DIR` 可指定目录，
`CHROME_PATH` / `FFMPEG_PATH` 可指定工具。它不使用真实麦克风、真实模型或火山服务，不能替代真人与厂商验收。
