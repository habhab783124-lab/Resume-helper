# 岗位匹配、对比、收藏与投递管理

## 检索数量与筛选

岗位页默认请求 60 个，可切换 30 / 60 / 100 / 200 个；先显示 24 个，点击「显示更多」浏览剩余结果。默认城市和经验均不限；明确选城市后执行硬筛选，选择「应届 / 1 年以内」才排除更高经验要求。智能体沿用搜索框关键词和同一组筛选，不再固定只返回 8 个。

后端先召回最多 `min(500, max(100, limit × 3))` 个公开候选，再按匹配度取前 `limit` 个，避免先截断后评分。普通搜索响应新增 `candidateCount`，表示本次参与评分的候选数；`total` 仍为实际返回数，不是官网总数或全网在招总数。页面展示本次来源的返回数和失败状态；来源数量在去重和硬筛选之前统计。

腾讯和网易来自官方公开招聘接口，支持分页和五分钟缓存；来源失败、城市/经验限制、官网暂无相关岗位都可能减少结果。真实来源有结果时优先展示真实岗位；无关演示数据不会为了凑数混入。未公开薪资显示面议，列表薪资排序使用后端 `salaryMin/salaryMax` 的千元/月单位，并保留原文金额。

## 主要功能

- **岗位对比**：从岗位卡片、收藏或投递详情选择 2～4 个岗位，横向查看地点、薪资、经验、学历、技能证据和 JD 原文；可切换综合、城市、薪资、技能偏好。
- **收藏持久化**：收藏关联当前登录账号，公开岗位与自己粘贴的 JD 都能收藏，后端重启后仍可查看。
- **投递管理**：从岗位或收藏创建准备记录，管理准备、已投递、笔试、面试、Offer、结束六种状态，记录备注、下一步行动时间和进展时间线。
- **投递快照**：保留当时的岗位描述，可绑定本账号的简历版本和面试记录。删除简历原版本后仍可查看已绑定快照；删除投递记录不会取消收藏。

## 实现与边界

对比只读取服务端已确认的个人资料和已保存岗位快照，不访问招聘网站或大模型。技能证据来自技能、简介、经历及项目，目标职位名称不算已掌握技能。缺少个人资料时不生成个人分数；未知维度标为待核对，按权重的 50% 中性计入，并显示有效信息覆盖权重。分数是资料贴合参考，不是录取概率。

薪资统一折算为千元/月供比较：日薪按 21.75 天、年薪按 12 个月换算，不把额外薪数当作区间上限；奖金、补贴和税前税后需要核对招聘原文。粘贴 JD 的明确工作地点优先于公司总部地址。

收藏和投递的归属以认证令牌为准，忽略客户端传入的 `userId`。手动导入 ID 由账号和 JD 内容生成，按账号保存，不进入公共岗位池。对比选择按账号与后端地址隔离，退出登录清除当前选择。

投递创建按账号和岗位幂等；修改与删除要求当前 `version`，旧版本返回 HTTP 409，避免覆盖其他标签页已保存的进度。文件仓库采用临时文件及替换写入，适用于单机单实例；多副本部署需要迁移共享存储并实现并发协调。

投递与收藏列表独立处理加载失败；刷新列表保留未保存草稿。发生版本冲突后，可读取最新版本并保留备注、简历选择和行动时间，核对状态后重新保存。导入 JD 成功而后续匹配失败时，已导入卡片仍可用于收藏、对比和投递；搜索条件变化时会取消旧请求，防止慢响应覆盖新结果。

## 接口与配置

| 接口 | 用途 |
| --- | --- |
| `POST /api/jobs/compare` | `jobIds` 传 2～4 个不同 ID，`preference` 选 `balanced/city/salary/skills` |
| `POST/DELETE /api/jobs/{id}/favorite` | 收藏、取消收藏 |
| `GET /api/jobs/favorites` | 本账号收藏 |
| `GET/POST /api/applications` | 筛选投递、创建记录；支持 `status/due/offset/limit` |
| `GET /api/applications/{id}` | 投递详情与快照 |
| `PATCH /api/applications/{id}/status` | 带 `version` 更新状态、简历绑定、行动时间和备注 |
| `GET/POST /api/applications/{id}/events` | 读取时间线，或带 `version` 添加备注及本账号面试关联 |
| `DELETE /api/applications/{id}?version=…` | 删除当前版本的投递记录 |

前端入口为 `/jobs/compare` 和 `/applications`，到期待办入口为 `/applications?due=1`，收藏入口为 `/applications?tab=favorites`。这些路由支持直接访问和刷新。

| 环境变量 | 默认值 | 保存内容 |
| --- | --- | --- |
| `JOB_STORAGE_DIR` | `./data/jobs` | 公开岗位快照、按账号隔离的导入 JD 与收藏 |
| `APPLICATION_STORAGE_DIR` | `./data/applications` | 按账号隔离的投递快照和时间线 |

目录相对于后端工作目录，部署时应保留并备份，不要提交运行数据。前后端同源部署无需设置 API 地址；前端独立部署时配置 `VITE_API_BASE_URL`，或通过现有本地服务设置指定后端。

## 验证

后端相关回归及打包：

```powershell
cd backend
.\mvnw.cmd '-Dtest=ApplicationWorkflowTest,Job*Test,NetEaseJobSourceTest,PublicJobSourceTest,Auth*Test,TextExtractionTest,ProfileExtractorTest,ResumeServiceTest,SafePathsTest' package
```

前端单元测试、类型检查、生产构建及浏览器验收：

```powershell
cd frontend
npm ci --no-audit --no-fund --replace-registry-host=always
npm test
npm run build
node tools/check-job-comparison.mjs
node tools/check-application-workflow.mjs
```

浏览器脚本需要 Node 24、Java 17 和 Edge/Chrome，可用 `JAVA_PATH`、`CHROME_PATH` 指定路径。脚本会启动隔离后端、使用随机端口和测试账号，覆盖账号隔离、重启恢复、冲突处理、失败重试及 390px 布局，结果写到已忽略的 `verification/`。不使用真实模型、语音密钥或用户存储。

2026-11-03 数量与流程回归：相关后端 98 项及打包通过，前端 315 项测试、类型检查和生产构建通过。浏览器验收共 48 项（岗位匹配与对比 26 项、投递流程 22 项），覆盖超过 12 个岗位的浏览、筛选透传、旧响应隔离、导入后匹配失败、投递冲突草稿恢复和两个列表的独立失败。

全量后端回归还存在面试模块的 MockMvc WebSocket 容器初始化错误，以及本机 PATH 导致的 ffmpeg 路径错误；同类失败已在未修改的原始基线 `1c0505d` 上复现，新远程提交没有改动这些面试代码。本次提交不包含这些独立问题的修复。
