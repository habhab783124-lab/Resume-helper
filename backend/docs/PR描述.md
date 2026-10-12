## 做了什么

实现产品方案 **3.C 岗位搜索和匹配**，对应功能二需求「有一个智能体去网上找适合用户的岗位」。

### 1. 岗位数据源（可插拔）
`JobSource` SPI + 注册中心，负责聚合、**单来源失败隔离**、跨来源去重合并。

内置三个来源：
- **演示岗位库**（13 条人工编造的演示数据，兜底保证岗位页永不空白）
- **CSV 岗位库**（用 Excel 人工维护，可换成绝对路径）
- **粘贴岗位描述导入**（用户复制 JD 即可完成匹配）

新增来源只要实现接口 + 注册 Bean，智能体和检索逻辑一行都不用改。

### 2. 岗位检索智能体
ReAct 循环：`规划关键词 → 聚合检索 → 观察命中 → 换词扩检 → 排序解释`。
执行轨迹（`trace`）随响应返回，前端可以渲染成「它是怎么找的」时间线。

用**文本行协议**而不是各厂商的 function calling，因为「用户自带 token」意味着模型不固定，
DeepSeek / 通义 / 豆包 / 自建网关的工具调用字段互有差异。

### 3. 匹配打分与技能差距报告
六维加权：技能 45 / 岗位方向 20 / 城市 15 / 经验 10 / 学历 5 / 薪资 5。

- 规则分**保证大模型不可用时功能依然完整**，AI 只负责补充「为什么匹配、差在哪里」的自然语言
- 城市默认是**软条件**（参与打分、给出差距说明），用户在页面上开筛选才硬过滤
- 经验不做「差一年也算友好」的宽松处理——应届生投「1-3 年」是真实差距，给 0.7 分并提示用项目经历弥补
- 响应固定带 `scoreNotice`：**匹配度是贴合程度，不是录取概率**

### 4. AI 接入（用户自带 token）
优先级：请求头 `X-AI-Api-Key` > 服务端 `AI_API_KEY` > 离线替身。

- **按请求构造客户端，不注册 Spring 单例**——否则并发时会出现「A 用户的请求用了 B 用户的 key」
- 密钥不落库、不打日志（`maskedKey()` 是唯一允许打日志的形式）
- 用 JDK 自带的 `HttpClient`，不引第三方 SDK

### 5. 三层降级保证（方案 11 风险兜底）
| 故障 | 行为 |
|---|---|
| 无 token / 鉴权失败 / 超时 | 离线替身按规则继续跑，`notes` 里说明原因 |
| 关键词过窄没命中 | 注册中心放宽为按硬条件返回 |
| 所有来源都失败 | 演示岗位库兜底 |

**降级不是静默的**：全部写进 `notes`，前端应原样展示。

### 6. 架构
core 层（`domain` / `source` / `match` / `agent` / `store`）**零框架依赖**，只依赖 JDK，
Spring 只出现在 `api` / `service` / `config`。好处是业务逻辑能脱离容器秒级验证。

岗位模块**零数据库依赖**（内存存储），不会被队友的 MySQL 进度阻塞；表结构已写好放在 `db/schema.sql`。

---

## 怎么验证

```bash
cd backend

# 1) 离线自检：不需要 Maven / 数据库 / 网络 / API Key，102 项断言
powershell -ExecutionPolicy Bypass -File tools\verify.ps1

# 2) 启动后端（这台机器 8080 被 NI Application Web Server 占用，用 8081）
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 3) HTTP 冒烟：10 组接口，51 项断言
powershell -ExecutionPolicy Bypass -File tools\smoke-api.ps1 -BaseUrl http://localhost:8081
```

两套验证本机都跑过，全绿。

---

## 接口清单

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/jobs` | 搜索岗位，可带扁平画像参数返回匹配度 |
| POST | `/api/jobs/search` | 同上，画像放 body（推荐前端用这个） |
| POST | `/api/jobs/agent/run` | 让智能体自己决定怎么找，返回执行轨迹 |
| POST | `/api/jobs/import` | 粘贴 JD 导入 |
| GET | `/api/jobs/{id}` | 岗位详情 |
| POST | `/api/jobs/{id}/match` | 匹配度 + 技能差距报告 |
| GET | `/api/jobs/sources` | 数据源清单与可用性 |
| POST/DELETE | `/api/jobs/{id}/favorite` | 收藏 / 取消收藏 |
| GET | `/api/jobs/favorites` | 收藏列表 |

`JobCard` 的字段（`id/title/company/mark/style/salary/place/experience/tags/score/reason`）
**已对齐 `prototype-static/assets/app.js` 里的岗位卡片对象**，前端把 mock 数组换成接口返回即可，
渲染逻辑不用改。详见 `backend/README.md`。

---

## 没做什么（及原因）

- **没写招聘网站爬虫**。方案 3.C 明确要求「不绕过登录」「公开页面可访问不等于自动采集已经获准」，
  且反爬（登录态、风控、验证码、动态渲染）的成本会吃掉 20 天预算的大半，
  一旦演示当天被封 IP 就直接翻车。改用「合规来源 + 粘贴 JD 兜底」。
- **没做 SSE 流式推送**。轨迹已在 `trace` 字段同步返回，够前端渲染时间线；流式留作后续优化。
- **没动 `frontend/`**。岗位页的 Vite 代理需要前端同学加 3 行（见下）。

---

## 需要前端配合的一处改动

`frontend/vite.config.js` 目前没有代理，前端开发服务器连不上后端。加 3 行即可：

```js
server: {
  port: 5173,
  host: '0.0.0.0',
  proxy: {
    '/api': { target: 'http://localhost:8081', changeOrigin: true }
  }
}
```

（后端也开好了 CORS，不加代理直连 `http://localhost:8081/api/...` 也能通，但代理更干净。
我没有直接改这个文件，避免和前端同学产生冲突。）

另外 `frontend/src/views/Jobs.vue` 目前还是静态结构（`job-grid` 是空的），
接数据时把 `jobs` 数组换成 `/api/jobs/search` 的返回即可，字段名一致。

---

## 需要注意的地方

- `backend/tools/verify-stubs/` 是**编译期打桩**（Spring/Jackson 的最小类型声明），
  只用于没有 Maven 时用 javac 校验，**不参与 Maven 构建**，请不要删。
- `backend/src/main/resources/application-local.yml` 是**本机专用**（8081 端口），
  已在 `.gitignore` 里，不会提交。仓库默认端口仍是 8080。
- PowerShell 脚本必须是 **UTF-8 with BOM**（宿主是 PS 5.1，无 BOM 会把中文按 ANSI 解码导致解析失败）；
  `.sh` 必须**无 BOM**（否则 shebang 失效）。已在 `backend/.gitattributes` 里注明。
