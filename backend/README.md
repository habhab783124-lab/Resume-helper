# 拾阶求职工作台 · Java 后端

对应产品方案《AI求职与面试平台_20天课程项目方案.md》。当前已实现：

- **功能一：材料整理** —— 上传 PDF / DOCX / 粘贴文本 → 抽取正文 → 大模型结构化抽取 → 用户确认成求职画像
- **功能二：岗位来源与匹配** —— 岗位检索智能体、六维可解释匹配、差距报告

其余模块（简历生成 / 模拟面试）由队友按同一套约定补齐。

---

## 1. 这个模块做了什么

| 能力 | 实现位置 | 说明 |
|---|---|---|
| 多来源岗位聚合 | `job/source` | 可插拔 `JobSource` SPI；内置演示岗位库 + CSV 岗位库，新增来源不用改检索逻辑 |
| 跨来源去重 | `job/source/JobDeduplicator` | 按「公司+岗位+城市」指纹合并，技能取并集，真实来源优先于演示数据 |
| 粘贴 JD 导入 | `job/source/JobImporter` | 用户没有爬虫数据也能完成匹配（产品方案 3.C 的兜底要求） |
| 规则匹配打分 | `job/match/MatchScorer` | 技能 / 岗位方向 / 城市 / 经验 / 学历 / 薪资 六维加权，**完全可解释** |
| 技能归一与识别 | `job/match/SkillLexicon` | 120+ 条技能词典，`spring-boot` / `SpringBoot` / `Spring Boot` 归一；`Java` 不会误命中 `JavaScript` |
| 匹配解释 + 差距报告 | `job/match/JobMatchService` | 规则结果 + 大模型自然语言解释，AI 挂了自动退回规则文案 |
| **岗位检索智能体** | `job/agent/JobSearchAgent` | ReAct 循环：规划关键词 → 检索 → 观察 → 换词扩检 → 排序，全过程可回放 |
| 用户自带 token | `common/ai` | 前端传 `X-AI-Api-Key` 等请求头，按请求构造客户端，不缓存、不落库、不打日志 |
| **材料上传与文本抽取** | `material/parse` | PDF（PDFBox）/ DOCX（POI）/ TXT / Markdown，可插拔 `DocumentTextExtractor` SPI |
| **扫描件检测** | `material/parse/TextExtraction` | 没有文字层的 PDF 识别为「需粘贴文本」而不是报解析失败——那是文件的问题，不是产品坏了 |
| **简历结构化抽取** | `material/extract/ProfileExtractor` | 中文简历 → 教育/实习/项目/技能/获奖；**每条都带原文证据并回验，编造的经历会被丢弃** |
| 材料持久化 | `material/store` | 文件系统实现，目录结构人类可读；路径经过防目录穿越清洗 |
| 求职画像 | `common/profile` | 材料模块产出、岗位模块消费；两个模块共用同一组字段名 |

**零数据库依赖**：克隆下来 `mvn spring-boot:run` 就能跑，不会被队友的 MySQL 进度阻塞。
MySQL 表结构已经写好，见 `src/main/resources/db/schema.sql`。

---

## 2. 快速开始

环境要求：**JDK 17** + Maven。

> ⚠️ **如果 8080 被占用**，直接启动会报 `Port 8080 was already in use`。
> 用环境变量换端口即可（**不需要改代码**）：
>
> ```powershell
> $env:SERVER_PORT = 8081
> mvn spring-boot:run
> ```
>
> 常见的占用者：装了 NI（National Instruments）软件时，它的 `NIApplicationWebServer`
> 服务会占着 8080。**不要杀掉它**——它是自动启动的服务，杀了会回来，而且可能被其他软件依赖。
> 换端口就行，前端的代理目标也要跟着改（见 3.1 节的 `VITE_PROXY_TARGET`）。

```bash
cd backend
mvn spring-boot:run
```

启动后访问 <http://localhost:8080/api/jobs/sources> 应能看到两个数据源。
**不需要配置任何 API Key**——没有 token 时智能体会自动降级为规则检索，功能完整可用。

### 不装 Maven 也能验证逻辑

`core` 层（领域模型 / 数据源 / 技能词典 / 匹配 / 智能体）**不依赖 Spring 和 Jackson**，
`JobService` 和 `JobViews` 的构造参数也都是普通对象，所以整条业务链路可以脱离容器直接跑：

```powershell
# 方式一：不需要 Maven / 数据库 / 网络 / API Key（推荐，用 JDK 自带 javac）
powershell -ExecutionPolicy Bypass -File tools\verify.ps1

# 方式二：装了 Maven 的话
mvn -q test-compile exec:java
```

它会用 JDK 自带的 `javac` 编译全部 75 个源文件（Spring/Jackson 由 `tools/verify-stubs` 打桩替代），
然后跑 **102 项断言**：薪资解析、技能误命中防护、CSV 引号、粘贴 JD 抽取、智能体全链路、
匹配打分与差距报告、存储与收藏，以及**服务层响应契约**（控制器真正走的那条路）。

Linux / macOS 用 `bash tools/verify.sh`。

**改完匹配逻辑先跑这个**，几秒钟出结果，比启动整个服务快得多。

### 启动之后跑 HTTP 冒烟

离线自检覆盖不到 Spring MVC 的接线（路径映射、Jackson 序列化、CORS、异常处理）。
后端起来之后，用冒烟脚本补齐：

```powershell
powershell -ExecutionPolicy Bypass -File tools\smoke-api.ps1
# 后端在 8081 时： -BaseUrl http://localhost:8081
# 验证真实大模型链路： -ApiKey sk-xxxx
```

它会按真实 HTTP 把 10 组接口全打一遍，并打印智能体的执行轨迹与降级提示。

完整的四级验证流程（含排查表）见 [docs/团队协作与验证指南.md](docs/团队协作与验证指南.md)。

### 单进程演示模式（演示不在开发机上时用这个）

课程演示常常在**别人的电脑**上做，那台机器可能没有 Node、没有 Maven、不能联网装依赖。
所以后端支持**顺便把前端静态文件一起提供**，做成"一个进程、一个端口"：

```powershell
# 1) 打包（只需在开发机做一次）
cd backend
mvn -B clean package -DskipTests
cd ..\frontend
npm run build

# 2) 组装演示目录
#    demo\app\resume-helper-backend-*.jar
#    demo\app\webapp\           <- frontend\dist 的内容
#    demo\jre\                  <- 可选，便携版 JRE 17

# 3) 在演示机上运行（只需要 JDK 17）
cd demo\app
java -jar resume-helper-backend-0.1.0-SNAPSHOT.jar
# 浏览器打开 http://localhost:8080/
```

原理：
- `spring.web.resources.static-locations` 里加了 `file:./webapp/`（jar 同级目录）
- `SpaForwardController` 把 `/jobs`、`/dashboard` 这些**前端路由**转发到 `index.html`
  （磁盘上并不存在这些路径，不回退就会 404）
- 因为**同源**，所以不需要 vite 代理，也不需要配 CORS

`SpaForwardController` 只列了前端真实存在的路由，**刻意不用通配 `/**` 兜底**：
通配会把 `/api` 也吞掉，写错接口路径时会返回一份 HTML 而不是 404，排查起来极其痛苦。


---

## 3. 前端对接

### 3.1 跨域与代理（已配好）

前端已在 `frontend/vite.config.js` 里配好代理，**前端代码统一写相对路径 `/api/xxx`**，
由 Vite 转发到后端。开发时没有跨域问题，生产构建里 `/api` 也不会被当成前端路由。

```js
// frontend/vite.config.js
const proxyTarget = process.env.VITE_PROXY_TARGET || 'http://localhost:8080'
// ...
proxy: { '/api': { target: proxyTarget, changeOrigin: true } }
```

**后端不在 8080 时不用改代码**，用环境变量指定即可：

```powershell
$env:VITE_PROXY_TARGET = 'http://localhost:8081'   # 后端跑在 8081
npm run dev
```

#### 部署到 Vercel 后代理不生效（重要）

Vercel 上的代理不会转发到你本机，而且它是 **HTTPS**，浏览器不允许 HTTPS 页面请求
`http://` 接口（混合内容拦截）。所以公网演示必须：

1. **前端**配 `VITE_API_BASE_URL=https://你的后端域名`（见 `frontend/.env.example`）；
2. **后端**把前端域名加进 CORS 白名单——改配置即可，不用改代码：

```yaml
app:
  job:
    cors:
      allowed-origin-patterns:
        - http://localhost:*
        - https://resume-helper-two.vercel.app
        # 或者用通配符一次放行所有 Vercel 预览域名：
        # - https://*.vercel.app
```

公网后端也必须是 HTTPS。如果暂时没有公网后端，就本地演示：
前端 `npm run dev` + 后端本机跑，走代理，最省事也最不容易翻车。

### 3.2 接口清单

全部以 `/api` 开头。响应统一包一层：

```json
{ "success": true, "message": null, "data": { } }
```

| 方法 | 路径 | 作用 |
|---|---|---|
| `GET` | `/api/jobs` | 搜索岗位。可带画像字段，带则返回匹配度 |
| `POST` | `/api/jobs/search` | 同上，画像放 body（字段多时用这个） |
| `POST` | `/api/jobs/agent/run` | **让智能体自己决定怎么找**，返回执行轨迹 |
| `POST` | `/api/jobs/import` | 粘贴 JD 导入岗位 |
| `GET` | `/api/jobs/{id}` | 岗位详情（完整描述） |
| `POST` | `/api/jobs/{id}/match` | 计算匹配度 + 技能差距报告 |
| `GET` | `/api/jobs/sources` | 数据源清单与说明 |
| `POST` / `DELETE` | `/api/jobs/{id}/favorite` | 收藏 / 取消收藏 |
| `GET` | `/api/jobs/favorites` | 收藏列表 |

### 3.3 为什么搜索也有 POST

`GET /api/jobs` 能带上画像的关键字段（`targetTitle`、`skills`、`targetCities`…），够用：

```
GET /api/jobs?keyword=Java&targetTitle=Java 后端开发工程师&skills=Java,Spring Boot,MySQL&targetCities=上海&yearsOfExperience=0&limit=12
```

但画像字段一多，query string 既难看又容易超长，所以同时提供语义等价的 `POST /api/jobs/search`。
**推荐前端统一用 POST。**

### 3.4 岗位卡片字段已对齐原型

`JobCard` 的字段是照着 `prototype-static/assets/app.js` 里的 mock 对象设计的，
前端把 `jobs` 数组换成接口返回、渲染代码基本不用改：

| 接口字段 | 原型 mock 字段 | 说明 |
|---|---|---|
| `id` | `id` | |
| `title` | `title` | 职位名称 |
| `company` | `company` | 公司名称 |
| `mark` | `mark` | 公司首字 |
| `style` | `style` | 配色类：`""` / `mark-blue` / `mark-amber` / `mark-rose` |
| `salary` | `salary` | 例如 `15-22K` |
| `place` | `place` | 例如 `上海 · 徐汇` |
| `experience` | `experience` | 经验要求 |
| `tags` | `tags` | 技能标签，最多 4 个，命中的技能优先 |
| `score` | `score` | 匹配度 0-100；**没有画像时为 `null`** |
| `reason` | `reason` | 一句话匹配理由，对应卡片上的 `job-reason` |

额外多出的字段：`matchedSkills` / `missingSkills` / `gaps` / `dimensions`（差距分析用）、
`sourceName` / `sourceUrl` / `collectedAt`（来源可回溯）、`campus`、`description`。

### 3.5 调用示例

**搜索（带匹配度）**

```js
const res = await fetch('/api/jobs/search', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-AI-Api-Key': userApiKey,        // 用户自己填的 token，没填就不传
    'X-AI-Model': 'deepseek-chat'      // 可选
  },
  body: JSON.stringify({
    keyword: 'Java',
    limit: 12,
    profile: {
      userId: 'u-1001',
      targetTitle: 'Java 后端开发工程师',
      targetCities: ['上海'],
      yearsOfExperience: 0,
      education: '本科',
      skills: ['Java', 'Spring Boot', 'MySQL', 'Redis'],
      expectedSalary: '15-22K',
      summary: '计算机专业应届生，做过校园活动管理平台',
      experiences: ['校园活动管理平台：负责报名与签到接口，用 Redis 缓存热门活动，响应从 800ms 降到 120ms']
    }
  })
});
const { data } = await res.json();
// data.jobs[i].score / .reason / .gaps 就可以直接渲染
// data.notes 里可能有降级提示，例如「大模型调用失败，已降级为规则检索」，建议原样展示给用户
// data.scoreNotice 是必须展示的说明：匹配度不是录取概率
```

**让智能体去找**

```js
const res = await fetch('/api/jobs/agent/run', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-AI-Api-Key': userApiKey },
  body: JSON.stringify({
    profile: { /* 同上 */ },
    options: { limit: 8, campusOnly: true, city: '上海' }
  })
});
const { data } = await res.json();
// data.trace 是执行轨迹，可以渲染成时间线：
//   第 1 轮 [search_jobs] 关键词「后端开发」命中 7 个岗位
//   第 2 轮 [search_jobs] 关键词「Java」命中 8 个岗位
//   第 3 轮 [finish] 候选岗位已足够，进入排序与差距分析
// data.aiUsed 为 false 表示这次没用到真实大模型（没配 token 或调用失败）
```

**粘贴 JD 导入**

```js
await fetch('/api/jobs/import', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ text: 粘贴的整段岗位描述, sourceUrl: 'https://...' })
});
```

**差距报告**

```js
const res = await fetch(`/api/jobs/${id}/match`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-AI-Api-Key': userApiKey },
  body: JSON.stringify({ profile, explainAi: true })
});
// data.gapReport.strengths / .gaps / .suggestions 对应「岗位匹配与差距分析」页面
```

### 3.6 用户自带 token 怎么传

| 请求头 | 作用 |
|---|---|
| `X-AI-Api-Key` | 用户的 API Key |
| `X-AI-Base-Url` | 自定义 OpenAI 兼容端点（默认 `https://api.deepseek.com/v1`） |
| `X-AI-Model` | 模型名（默认 `deepseek-chat`） |

优先级：**请求头 > 服务端配置**。

> 产品方案 5 写的是「密钥放在后端配置中，不能写进前端代码」，
> 而实际需求是「让用户填自己的 token」。两者靠上面的优先级共存：
> 课程演示时用服务端兜底 key（`AI_API_KEY` 环境变量），
> 用户想用自己额度时就把 token 放请求头，覆盖掉服务端的。

⚠️ **安全提醒（演示项目可以接受，但要写进报告里）**：

- 前端存的 token 建议放 `sessionStorage` 而不是 `localStorage`，关闭标签页即失效；
- 一定要走 HTTPS，否则请求头里的 token 会被中间人截获；
- 后端**只在该请求内**使用这个 key，不写日志、不落库。`AiCredentials.maskedKey()` 是唯一允许打日志的形式；
- 生产环境的正确做法是「用户 token 加密存服务端 + 会话态引用」，不要每请求都在前端带到后端。

---

## 4. 岗位数据从哪来（合规优先）

产品方案 3.C 明确要求：**不绕过登录、不绕过反爬**，「公开页面可访问不等于自动采集已经获准」。
所以本模块的默认数据策略是：

| 来源 | 实现 | 状态 |
|---|---|---|
| 演示岗位库 | `MockJobSource` | ✅ 内置 13 条人工编造的演示数据（**非真实在招岗位**），保证演示永不空白 |
| CSV 岗位库 | `CsvJobSource` | ✅ 读 `classpath:jobs/sample-jobs.csv`，用 Excel 维护即可，可换成绝对路径 |
| 粘贴 JD | `JobImporter` | ✅ 用户手动复制岗位描述，零合规风险 |
| 公开 API | 预留 `JobSource` 接口 | ⬜ 建议先确认目标站点的 robots 与使用条款，再实现 |
| 搜索引擎 API | 预留 | ⬜ 需要 API Key，结果需再用大模型抽成结构化岗位 |
| 国内招聘站爬虫 | **不做** | ❌ 反爬强、易封 IP、有法律风险，不在课程项目范围内 |

**新增一个来源只要三步**：实现 `JobSource` → 加 `@Bean` 注册 → 完成。
`JobSourceRegistry` 会自动发现它，智能体和检索逻辑都不用改。
单个来源抛异常会被隔离（记进 `sourceReports`），不会拖垮整次检索。

CSV 表头（顺序可调）：

```csv
title,company,city,district,salary,education,experience,jobType,skills,description,sourceUrl
Java 后端开发工程师,示例企业A,上海,浦东,15-22K,本科,应届 / 1 年以内,校招,Java|Spring Boot|MySQL,负责后端接口开发与维护。,
```

`skills` 用 `|` 分隔；描述里有逗号就用双引号包起来。

---

## 5. 匹配度怎么算

六个维度加权，总和 100：

| 维度 | 权重 | 依据 |
|---|---|---|
| 技能匹配 | 45 | 岗位技能标签 + 岗位描述里识别出的技能，对比用户技能（含从经历文本里抽取的） |
| 岗位方向 | 20 | 目标职位与岗位名称的字符二元组相似度 |
| 城市 | 15 | 命中期望城市得满分；**默认是软条件**，不匹配只扣分并给出差距说明 |
| 经验 | 10 | 岗位最低年限 vs 用户年限 |
| 学历 | 5 | 学历等级对比 |
| 薪资 | 5 | 岗位薪资上限 vs 期望薪资下限 |

设计取舍：

- **城市默认不做硬筛选。** 用户明确开了「城市」筛选（`strictCity: true`）才硬过滤。
  理由是让用户看到「杭州有个很匹配的岗位，但地点不符」这种信息，比直接隐藏更有用。
- **经验不做「差一年也算友好」的宽松处理。** 应届生投「1-3 年」的岗位是真实差距，
  给 0.7 分并提示「用项目经历弥补」，而不是说成「经验要求友好」。
- **任一侧薪资是「面议」时给中性分**，不让缺失信息影响排序。
- 岗位没标注技能时给中性分，避免因为来源信息不全而误杀。

⚠️ **匹配度表示资料与岗位的贴合程度，不是录取概率。** 响应里的 `scoreNotice` 字段就是这句话，
前端必须展示，避免误导用户。

---

## 6. 智能体是怎么工作的

```text
用户画像
   │
   ├─ KeywordPlanner 规则兜底，先从目标职位和技能推出候选关键词
   │     「Java 后端开发工程师」→ 后端开发 / Java / Spring Boot / MySQL / 软件开发
   │
   └─ ReAct 循环（最多 maxRounds 轮）
        第 1 轮：大模型选「后端开发」→ 聚合所有来源 → 命中 7 个 → 作为观察结果拼回提示词
        第 2 轮：大模型看到已有结果，换「Java」扩检 → 命中 8 个
        第 3 轮：大模型判断够了 → finish
   │
   ├─ 去重合并（公司+岗位+城市）
   ├─ MatchScorer 六维打分排序
   └─ JobMatchService 对前 N 个岗位生成 AI 解释
```

**为什么用文本行协议（`THOUGHT/ACTION/ARGS`）而不是各家厂商的 function calling？**
因为「用户自带 token」意味着模型不固定，DeepSeek / 通义 / 豆包 / 自建网关的工具调用字段互有差异。
文本协议在任何 OpenAI 兼容端点上都能工作，解析失败还能干净地降级到规则检索，
不会因为模型不听话就让整个功能挂掉。

**三层降级保证**（对应产品方案 11 的风险兜底）：

1. 没有 token / 大模型超时 / 鉴权失败 → 用 `FakeAiClient` 按规则继续跑，流程不中断，`notes` 里说明原因；
2. 关键词过窄 → `JobSourceRegistry` 放宽为只按硬条件返回；
3. 所有来源都失败 → 演示岗位库兜底，岗位页不会是空白。

---

## 7. 接入 MySQL

当前用 `InMemoryJobStore`（进程内，重启即失），够联调用。

要持久化：

1. `pom.xml` 打开被注释的 `spring-boot-starter-data-jpa` 和 `mysql-connector-j`；
2. `application.yml` 加 `spring.datasource.*`；
3. 执行 `src/main/resources/db/schema.sql`；
4. 写一个 `JpaJobStore` 实现 `JobStore` 接口（`put` / `find` / `recent` / `save` / `unsave` / `isSaved` / `saved`），
   在 `JobModuleConfiguration` 里把 `jobStore()` 的返回换成它。

`JobMatch` 缓存（`job_match` 表）可以一起做，避免同一用户反复看同一岗位时重复消耗大模型额度。

---

## 8. 目录结构

```
backend/
├── pom.xml
├── README.md                                  ← 你正在看的这份
├── docs/
│   ├── 岗位检索智能体-设计说明.md              ← 架构、算法取舍、开发中修掉的缺陷
│   └── 团队协作与验证指南.md                    ← 怎么合并进 git 仓库 + 四级验证流程
├── tools/
│   ├── verify-stubs/                          ← 仅本地 javac 校验用的 Spring/Jackson 打桩，不参与 Maven 构建
│   ├── verify.ps1 / verify.sh                 ← 离线自检：编译全部源码 + 跑 102 项断言，不需要 Maven
│   └── smoke-api.ps1                          ← HTTP 冒烟：后端启动后把 10 组接口全打一遍
└── src/
    ├── main/java/com/shijie/resumehelper/
    │   ├── ResumeHelperApplication.java        Spring Boot 入口（模块化单体）
    │   ├── common/                             ApiResponse、全局异常处理
    │   └── job/                                功能二：岗位来源与匹配
    │       ├── domain/                         Job、JobQuery、UserProfileSnapshot、JobMatchResult（纯 JDK）
    │       ├── source/                         JobSource SPI、演示库、CSV 库、去重、JD 导入（纯 JDK）
    │       ├── match/                          SkillLexicon、MatchScorer、JobMatchService（纯 JDK）
    │       ├── agent/                          ReAct 循环、提示词协议、执行轨迹（纯 JDK）
    │       ├── ai/                             AiClient 接口、离线替身、OpenAI 兼容客户端（用户自带 token）
    │       ├── store/                          JobStore 与内存实现（纯 JDK）
    │       ├── service/                        JobService：对外服务入口，队友模块可直接注入
    │       ├── api/                            REST 控制器 + DTO
    │       └── config/                         配置类、CORS
    ├── main/resources/
    │   ├── application.yml
    │   ├── jobs/sample-jobs.csv                CSV 岗位库样例
    │   └── db/schema.sql                       MySQL 表结构
    └── test/java/.../JobAgentOfflineDemo.java  离线端到端自检 + 演示
```

**分层约定（重要）**：`domain` / `source` / `match` / `agent` / `store` 是 core 层，**不依赖 Spring**。
Spring 只出现在 `config` / `service` / `api` / `ai` 的实现类里。
这样业务逻辑可以脱离容器快速验证，也方便队友替换 Web 框架或存储实现。

---

## 9. 已知限制 / 刻意没做的事

| 项 | 说明 |
|---|---|
| 没有真实招聘网站来源 | 合规原因，见第 4 节。默认靠演示库 + CSV + 粘贴 JD |
| 智能体轨迹是同步返回的 | 没用 SSE 流式推送。轨迹在 `trace` 字段里，前端跑完再渲染时间线即可；要边跑边看可加 SSE |
| 收藏是内存态 | 重启丢失，接 MySQL 即可解决 |
| 没有做分页 | 单次最多返回 50 条（`app.job.max-limit`），课程演示够用 |
| 技能词典是静态的 | 120+ 条常见技能，覆盖 Java 后端校招场景；新增技能改 `SkillLexicon` 的 `register` 一行即可 |
| 没有防刷和限流 | 用户自带 token 消耗的是自己的额度，暂未做服务端限流 |

---

## 10. 功能一：材料整理

### 10.1 做什么

用户上传简历（PDF / DOCX）或直接粘贴文本，系统抽出正文、调大模型抽成结构化资料，
用户核对确认后存成**求职画像**，供岗位模块消费。

```
上传 → 抽文本 → 是扫描件？ ─是→ 提示改用「粘贴文本」（不报错）
                         └否→ 大模型结构化抽取 → 证据回验 → 存盘 → 展示（附原文对照）
```

### 10.2 三道防线：不让 AI 编造经历

简历是要拿去投递的。模型编一条不存在的实习经历，用户没细看就投出去，面试时答不上来——
这是**害用户**，不是"AI 有点不稳定"。所以设了三道防线：

1. **提示词硬约束**：明确要求「原文没写的留空，不得推测」，并要求每条记录附上原文片段
2. **证据回验**：把模型给的 evidence 拿回原文里找，找不到就**丢掉这条记录**并给出提示。
   模型想造假，就得同时造出一段在原文里真实存在的"原文"——基本做不到
3. **没有 token 就不猜**：返回空结果 + 明确提示，而不是用规则硬凑一份
   看起来完整、实际不可信的档案

**宁可少抽几条，也不能多抽一条假的。** 这条原则由 `ProfileExtractorTest` 的
「模型编造的经历会被证据回验丢掉」等 8 个用例锁住。

### 10.3 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/materials` | 已整理的材料列表 |
| POST | `/api/materials` | 上传文件（multipart，字段名 `file`） |
| POST | `/api/materials/text` | 粘贴文本（扫描件的兜底路径，任何情况下都能用） |
| GET | `/api/materials/{id}` | 材料详情 + 抽取结果 |
| GET | `/api/materials/{id}/text` | 抽出的原文（**给「原文对照」面板用**） |
| DELETE | `/api/materials/{id}` | 删除材料（原文件、原文、抽取结果一并删除） |
| GET / PUT / DELETE | `/api/profile` | 读取 / 保存 / 清空求职画像 |

身份来自 `X-User-Id` 请求头，缺省 `demo-user`。接入真实认证后只需改 `UserContext`，
所有调用方不用动。**注意请求头是客户端可伪造的，现在只能用于数据分区，不能当权限控制。**

### 10.4 怎么验证

```powershell
# 纯逻辑（只要有 JDK + Maven）
mvn -B test                        # 26 项：反幻觉回验、路径安全、真实文件抽取

# 接口验收（需要先启动后端）
pwsh -File tools/material-check.ps1 -BaseUrl http://localhost:8080
#   39 项检查，用真实简历样本跑 上传 → 抽取 → 确认 → 删除 全流程

# 带真实 token 验证抽取质量
pwsh -File tools/material-check.ps1 -BaseUrl http://localhost:8080 -ApiKey "sk-xxx"
```

测试样本在 `tools/testdata/`：一份带**表格排版**的中文简历 DOCX、由它转出的文本 PDF、
以及一个**整页只有图片**的扫描件 PDF（负样本）。生成方式见 `tools/testdata/make-samples.py`。

> DOCX 抽取刻意用 `getBodyElements()` 按文档顺序遍历段落与表格。
> 如果分别遍历（先所有段落、再所有表格），"教育经历"标题会被甩到文档末尾，
> 表格里的学校信息就和它对不上了——`TextExtractionTest` 里有专门一个用例锁住这个行为。

### 10.5 首期不做的

| 项 | 说明 |
|---|---|
| **不做 OCR** | 扫描件/拍照件识别为「需粘贴文本」并引导粘贴。产品方案首期也是这么定的 |
| 不支持旧版 `.doc` | 二进制格式，需要额外的 `poi-scratchpad`。明确提示"另存为 .docx"，而不是多引一个依赖 |
| 复杂版式不保证 100% 还原 | 双栏/表格的文本顺序可能乱，但内容都在。界面上用「原文对照」让用户自行核对 |
| 抽取是同步的 | 一份简历几百毫秒就抽完，没必要上任务队列。状态字段已预留，将来改异步不用动接口 |
| 存储是文件系统 | 零依赖，克隆下来就能跑。`MaterialStore` 是接口，换 MySQL 只需加一个实现类 |
