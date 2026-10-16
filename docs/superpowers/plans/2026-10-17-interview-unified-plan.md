# 模拟面试统一剩余实施计划 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Use superpowers:subagent-driven-development only when the user explicitly chooses delegated execution. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 先交付可靠、简洁、可随时结束的聊天面试，再接通材料/JD、可确认的练习模式、证据反馈、重答与历史，所有剩余工作只以本文件为准。

**Architecture:** 保留 Vue 3、Spring Boot 和现有模型/语音/评分实现。先分离回答保存、后台出题与结束，建立持久化快照、单次生成操作和有上限的重试；前端用短请求与状态读取组织聊天。随后在同一会话与计划模型上增加材料/JD 复用、练习模式、题目操作和反馈复练，不再建设第二套答题流程。每阶段均能单独使用和验收。

**Tech Stack:** Java 17、Spring Boot 3.2.5、Jackson、JDK HttpClient、JUnit 5 / Spring Boot Test、Vue 3.5、Vue Router、Node 原生测试、现有 Chrome 浏览器验收脚本。

## Global Constraints

- 本次仅整理计划和修正计划链接，不修改业务代码，不提交、不合并、不部署。待办只保留未完成部分。
- 不写任务工时或完成日期；请求超时、录音限制、TTL 属于产品参数。
- 本文件合并原聊天可靠性计划、体验改造计划及旧语音进度中的剩余验收。设置、岗位专项、导航、草稿等已有实现仅作为保留基线，不重复列为开发任务。
- 阶段 A 不改变现有 12 道主问题 + 1 道专项、主问题 80% / 专项 20%、单主问题最多两次追问。阶段 B 才为新会话引入 QUICK/FULL 与整场追问预算；旧会话冻结 legacy-v3，不重新解释历史。
- 首题与后续出题最多额外自动重试 5 次，总计最多 6 次模型请求，共享默认 120 秒总预算；不可重试错误或预算用尽可提前终止。
- 语音和报告评分不叠加这套 6 次循环。后续计划/澄清/换题/归纳等功能也使用独立可查询状态、取消与受控补试，禁止多层循环放大请求。
- 五维合法档位保持 0、5、10、15、20。题分、权重、综合分由后端计算；未答、跳过、未评分、合法零分必须区分。原标准、首答和成功评分不被重答或重试覆盖。
- 问题、答案、案例和模型错误保持纯文本安全渲染，不用 v-html 执行内容。
- 简历、JD 和回答是候选人数据，不是系统指令。不编造经历、指标和证据；归纳引用必须回验原答。
- 保留现有 7 天本机草稿、转写预览、新一轮导航、请求级身份/模型设置、凭据隔离、语音取消及迟到结果隔离；不覆盖工作区已有代码改动。
- 阶段 A 新快照保留期为创建起 24 小时；历史功能交付后，新会话默认保留 30 天，可配置。expiresAt 在创建时冻结，不因读取、升级或改配置追溯延长旧会话。
- schemaVersion 统一为 2；它只表示文件结构。plan.policyVersion 与 rubricVersion 分别表示模式规则和评分标准，三者不能混用。
- 文件存储只承诺单后端进程重启恢复；不引入数据库、队列平台、多实例共享写入或正式账号系统。
- 文字路径始终可用。录音最多 120 秒、5 MiB；ASR 前端最长等待 90 秒。原始录音不保存至服务端，刷新恢复只承诺文字与预览。
- Key 不进 URL、日志、快照或本机草稿；后台任务只在执行期持有请求解析的客户端，终态释放，重启不自动恢复带凭据调用。
- 每个新接口校验所属用户；当前演示身份只用于数据分区，不声称是正式认证。
- 服务端确认落盘后才显示回答已保存、清草稿；未知结果读回确认。结束成功不依赖出题/评分成功。
- 尽力取消在途厂商请求，同时保证不再重试、不应用迟到结果；不能承诺取消一定停止厂商计费。
- 使用现有材料、简历版本、岗位详情接口。组件提取随行为改造进行，不引入 Pinia、新 UI 框架或编辑器依赖。
- 项目本地技能扩展随新模式/反馈任务更新；skills/interview/UPSTREAM.md 标识的六份上游原始文件不改。
- 后续任务先写真实行为用例并确认失败，再做最小实现及定向验证；布局用浏览器验证，不添加只匹配实现字符串的测试。
- 一个任务只完成其独立交付物。默认顺序执行，同一服务/页面不要并发编辑；本文件不沿用旧计划中的分支、自动提交、推送或合并授权。

---

## 1. 已记录的需求与当前事实

### 1.1 已确认的目标体验

1. 开始前准备简历和目标岗位；开始后进入专注的聊天界面。
2. 面试官问题在左、用户回答在右，保留完整对话记录。
3. 底部固定多行输入框，麦克风和发送按钮放在输入区内部。
4. 问题消息内提供小喇叭：生成中、播放中、停止/重播状态清楚，不另设大块语音面板。
5. 录音结束后自动转写到可编辑输入框，已有文字时默认追加，确认后由用户发送；失败保留录音可重试。
6. 顶部保留岗位、简洁进度和“结束面试”；简历、规则、专项信息收进抽屉，去掉答题时的装饰侧栏和重复说明。
7. 暂时性出题故障由系统自动恢复，显示当前状态与重试次数；最终失败仍可继续尝试或结束。
8. 用户能提前结束，包括录音、转写、生成下一题和重试等待期间。
9. 有草稿时可“提交当前回答并结束”“直接结束”“继续面试”；已收到的回答保留。
10. 提交并结束不生成下一题；结束后查看已答题报告。未答题不记零分，未完整覆盖不出完整综合分，零回答时显示暂无评价内容。

### 1.2 清理依据：已有能力不再作为开发待办

| 已有能力 | 本轮核对依据 | 剩余处理 |
| --- | --- | --- |
| 后端/模型/语音统一设置、请求时地址、凭据分流 | Settings.vue、requestContext、统一设置与 API 测试 | 保留回归，不重新建设 |
| 12+1 岗位专项、非编码案例、80/20、证据与成功评分缓存 | InterviewService / InterviewAgent / ReportAssembler；前端专项测试 | 保留旧规则，只新增模式边界 |
| 新一轮不恢复旧场次，复用上次材料和岗位 | interviewNavigation、Interview.vue.prepareNewRound、Report.vue | 删除原实现任务，后续新准备页继续复用 |
| 按题/用户/服务隔离的草稿、7 天 TTL、预览恢复、退出清理 | draftStore、useInterviewVoice、demoAuth、相关测试 | 删除重写任务，仅保留未完成浏览器验收与新流程适配 |
| 基础录音/朗读/ASR、取消、90 秒恢复截止时间、打印 | 现有 voice 模块与相关测试；旧进度记录最终复核已通过 | 删除已完成语音开发任务；真人/真实厂商/目标系统验收仍待完成 |

本次实际重新运行 frontend/npm test：133 项通过、0 失败。该结果证明当前前端单元回归，不代表浏览器、真实模型、真人麦克风或 Windows/Safari 已验收；本次未重跑后端全套。

仍需改造的事实：回答与下一题生成耦合；模型调用持有会话锁；缺少自动重试；提交后结束会多生成一题；会话只在内存；聊天布局、材料/JD 接入、新模式、题目操作、归纳/复练和历史接口尚不存在。

### 1.3 原计划冲突的统一结果

| 冲突项 | 统一规则 |
| --- | --- |
| 当前题置顶并折叠历史 / 聊天时间线 | 采用最新确认的聊天时间线、底部固定输入；历史完整且可回看，不再另做一套当前题置顶页面 |
| /next-question / /generations | 只新增 POST /generations；旧 /turns 兼容适配，不同时维护两套新出题接口 |
| schemaVersion 1 / 2 | 从第一版文件持久化统一使用 2；未部署的草案不需要制造文件迁移任务 |
| useInterviewSession / useInterviewConversation | 统一为 useInterviewConversation；旧草稿/导航工具复用 |
| 转写预览按钮 / 自动写输入框 | 新流程默认追加到结果到达时的草稿，先经 preview 缓冲和保存；不覆盖用户已有文字，不自动提交 |
| 24 小时 / 30 天保留 | 分阶段改变新建默认值，旧 expiresAt 冻结；页面读取服务端期限，不写死两套文案 |
| 固定 12+1 / QUICK 与新 FULL | 阶段 A 和 legacy 保持现有规则；阶段 B 新计划显式选模式，旧会话不迁移预算 |
| 并发响应和模型取消 | logical revision 保护生成输入；stateVersion 保护所有快照应用顺序；取消不能只依赖浏览器 abort |
| 旧语音待办中的“重启失效提示” | 阶段 A 后应验收重启恢复；已过期/损坏/旧实现已丢失分别提示，不再笼统宣称重启必丢 |

## 2. 交付拆分和执行约定

| 阶段 | 任务 | 独立交付 |
| --- | --- | --- |
| A：可靠聊天面试 | T01–T21 | 持久化、最多五次重试、聊天输入、录音朗读、随时结束与部分报告；沿用当前题数 |
| B：准备与练习选择 | T22–T30 | 材料/JD 复用、计划确认、快速/完整模式、难度、跳题/澄清/换题 |
| C：反馈、复练与历史 | T31–T39 | 重点归纳、逐题教学反馈、独立重答、薄弱项三题练习、历史与删除 |
| D：真实环境与最终验证 | T40–T42 | 真实配置、真人/目标浏览器与系统验收、最终一致性记录 |

- 共 42 个待办，每项四个步骤，带文件、接口、依赖和验收；不把旧计划的十余文件大任务原样拼接。
- 先完成 A 并验收，再实施 B/C；B/C 的准备和反馈使用同一个会话服务与聊天界面。
- 一个任务中的测试、配置和必要说明随交付一起完成；T21/T42 负责阶段/全局核对，不代替任务内测试。
- 本机草稿已实现但浏览器验收未完成的路径纳入 T20/T21；不把“未提交”当成“未实现”，也不以单测通过代替真人/浏览器验收。
- 不同阶段的规则通过 plan.policyVersion 冻结；变更部署顺序不得让旧客户端解释新状态或新会话按旧题数评分。
- 开始实施前读取适用 AGENTS.md、先记录现有改动，定位代码先用 CodeGraph。提交策略按实施时用户明确指示执行。

## 3. 文件职责

以下路径均相对仓库根目录 /Users/bohao/Desktop/Resume-helper；“新增”为本轮实施时创建，本轮计划阶段不创建这些实现。

| 文件/文件组 | 操作 | 职责 |
| --- | --- | --- |
| backend/src/main/java/com/shijie/resumehelper/common/ai/AiClient.java、OpenAiCompatibleAiClient.java | 修改 | 保留上游错误类型、可重试性、Retry-After；不把面试重试变成所有模块的隐式重试 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewException.java、InterviewErrorHandler.java | 修改 | 面试错误类型与安全提示；兼容既有响应包装 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewFailure.java | 新增 | 错误码、是否可重试、建议等待；不含密钥或原始上游正文 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewSnapshot.java、InterviewPlan.java、InterviewStore.java、FileSystemInterviewStore.java | 新增 | 完整可序列化快照、按用户存取、原子替换 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewProperties.java、InterviewConfiguration.java | 新增 | 存储目录、重试参数、可关闭的有界工作线程池 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewService.java | 修改 | 保存回答、状态迁移、幂等、快照提交、结束；不负责等待重试间隔 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewGenerationCoordinator.java | 新增 | 认领生成操作、单次在途约束、提交/拒绝迟到结果 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewGenerationRunner.java | 新增 | 锁外执行模型任务，接受取消，更新生成状态 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewRetryPolicy.java | 新增 | 次数、间隔、总截止时间、输出错误特殊上限 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewController.java、InterviewAgent.java | 修改 | 新接口接入、首题/后续题生成、兼容旧接口 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewReportAssembler.java | 修改 | 结束原因和部分报告语义，评分算法保持现状 |
| backend/src/main/resources/application.yml | 修改 | 新增 app.interview 配置，保留 app.ai 默认设置 |
| frontend/src/api/interview.js | 修改 | 保存/生成/结束接口、请求截止时间、取消信号 |
| frontend/src/interview/useInterviewPolling.js | 新增 | 一次一个状态读取、轮询节奏、离页/换轮取消 |
| frontend/src/interview/useInterviewConversation.js | 新增 | 发送、恢复、生成状态和幂等 ID；供页面和结束流程共享 |
| frontend/src/interview/conversationMessages.js | 新增 | 服务端问答转换为稳定消息序列 |
| frontend/src/interview/useConversationScroll.js | 新增 | 靠近底部时跟随、阅读历史时提示新消息 |
| frontend/src/interview/useInterviewFinish.js | 新增 | 结束选择、草稿/录音处理、迟到回调隔离 |
| frontend/src/components/interview/InterviewSetup.vue、InterviewHeader.vue、InterviewInfoDrawer.vue | 新增 | 准备表单、精简头部、信息抽屉 |
| frontend/src/components/interview/InterviewMessages.vue、InterviewQuestionMessage.vue、InterviewComposer.vue、InterviewFinishDialog.vue | 新增 | 聊天记录、问题内朗读、输入区、结束确认 |
| frontend/src/interview/useInterviewVoice.js、useVoiceRecorder.js、useQuestionSpeech.js | 以复用为主修改 | 录音结束后转写、明确题目播放对象、取消和资源释放 |
| frontend/src/views/Interview.vue、Report.vue | 修改 | 页面组装、报告展示；尽量不再塞入新的底层流程 |
| frontend/src/interview/draftStore.js、interviewNavigation.js | 保留并按需适配 | 草稿隔离、新建/恢复语义 |
| backend/src/test/java/com/shijie/resumehelper/interview/、frontend/src/interview/、frontend/src/api/ | 新增/修改测试 | 每项任务下列出具体测试文件 |
| frontend/tools/check-interview.mjs | 修改 | 使用真实页面事件验收；故障模拟服务同步新协议 |
| docs/interview-chat-acceptance.md、backend/README.md | 新增/修改 | 实际验收记录、配置与恢复边界 |

### 3.1 后续功能的文件职责（同样只在实施时新增）

| 文件/文件组 | 职责 |
| --- | --- |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewSourceResolver.java | MATERIAL / RESUME_VERSION 校验与纯文本快照 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewPlan.java、InterviewPlanService.java | 计划预览、版本、模式/预算、来源指纹、异步准备 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewQuestionActionService.java | skip / clarification / replacement，限额与失败回滚 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewReviewSummary.java | 证据归纳结构、引用回验、独立版本和缓存 |
| backend/src/main/java/com/shijie/resumehelper/interview/InterviewPracticeService.java | 关闭题组反馈、独立重答、证据对比、薄弱项计划 |
| backend/src/main/java/com/shijie/resumehelper/interview/Hot100Catalog.java | 难度过滤、去重与冻结选题 |
| frontend/src/interview/useInterviewSetup.js、InterviewSetup.vue | 来源、JD、模式偏好、计划确认；替代重复准备表单 |
| frontend/src/interview/useInterviewAttempt.js、components/interview/InterviewAttempt.vue | 重答输入、结果与对比 |
| frontend/src/interview/useInterviewHistory.js、views/InterviewHistory.vue | 摘要列表、继续/报告、删除 |
| frontend/src/views/Jobs.vue、Materials.vue、Resume.vue | 只传来源 ID 的新面试入口 |
| frontend/src/router/index.js、components/SidebarNavigation.vue | 历史路由与入口 |
| skills/interview/README.md、references/project-question-contract.md、references/project-scoring-and-feedback.md | 项目本地模式、提问与评分阶段规则；不改上游原件 |

任务中同名后端类均位于 backend/src/main/java/com/shijie/resumehelper/interview；配置和测试路径另行列明，不代表其他模块也需重写。

## 4. 接口和状态约定

### 4.1 会话和生成操作

以下是未来接口的约定，当前代码尚未支持。既有 Session 字段继续保留，新增字段不放进问题正文。

~~~ts
type GenerationState =
  | 'QUEUED' | 'RUNNING' | 'RETRY_WAIT'
  | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
type InterviewFailure = {
  code: 'TIMEOUT' | 'NETWORK' | 'RATE_LIMITED' | 'UPSTREAM_UNAVAILABLE'
    | 'INVALID_OUTPUT' | 'AUTH' | 'CONFIG' | 'QUOTA'
    | 'INPUT' | 'STORAGE' | 'INTERRUPTED' | 'UNKNOWN'
  retryable: boolean
  message: string
  retryAfterMs: number | null
}
type Generation = {
  generationId: string
  requestId: string
  kind: 'OPENING' | 'NEXT'
  stage: 'OPENING' | 'NEXT_DECISION' | 'SPECIALTY'
  sourceQuestionId: string | null
  state: GenerationState
  attempt: number             // 排队为 0，本操作累计实际模型请求数为 1..6
  retryCount: number          // 实际发生的额外重试数；UI 显示为 n/5
  maxRetries: 5
  startedAt: string
  deadlineAt: string
  nextRetryAt: string | null
  failure: InterviewFailure | null
}
type Submission = {
  questionId: string
  submissionId: string
  answer: string
}
// Session 新增字段；不替换既有 turns/questionMetadata/specialty 等字段。
type SessionAdditions = {
  schemaVersion: 2
  revision: number
  stateVersion: number
  generation: Generation | null
  finishReason: 'COMPLETED' | 'USER_ENDED' | null
  createdAt: string
  expiresAt: string
}
~~~

- status 仍为 ACTIVE / FINISHED；ACTIVE 允许没有 currentQuestion，例如首题准备中、回答保存后等待下一题。
- currentQuestion 只指向未回答且仍可作答的问题。保存回答后立即置空，旧题保留在消息列表中。
- revision 仅在回答、题目、关闭题组、结束等逻辑数据提交时增加；轮询和 attempt 状态更新不增加 revision，避免后台自身更新使生成校验失效。stateVersion 在每次持久化状态变化时递增，包括 attempt、评分和报告缓存；前端只接受不倒退的 stateVersion，读取不增加版本。
- 每个会话最多一个有效生成操作；识别同一操作依靠 generationId，不依靠页面按钮是否禁用。
- 结束使生成操作 CANCELLED，并递增逻辑 revision；即使上游不接受取消，也不再应用结果。
- 报告评分可以更新 FINISHED 会话的报告缓存，但不能修改原回答、追加问题或重开会话。
- 消息中“发送中”与“已保存、等待下一题”分开；只有落盘确认才显示后者。

### 4.2 API

保留 ApiResponse 的 success / message / data 包装。错误响应用现有可空 data 承载 InterviewFailure；前端只在 success=true 时将 data 当作业务结果。

| 接口 | 请求 | 行为 |
| --- | --- | --- |
| POST /api/interviews | {materialId,targetRole,requestId,async:true} | 校验材料和凭据、保存空会话并排队生成首题，立即返回 Session；同用户同 requestId 不新建第二场 |
| POST /api/interviews/{id}/answers | Submission | 仅验证和保存回答，不调用模型，返回 Session |
| POST /api/interviews/{id}/generations | {requestId,kind:'OPENING'或'NEXT',expectedRevision} | 开始/加入一次生成操作，返回 Session；失败后手动继续用新 requestId 开新操作，成功题目不重复生成 |
| GET /api/interviews/{id} | 无 | 返回当前 Session，生成或重试等待不阻塞读取 |
| POST /api/interviews/{id}/finish | {requestId,submission?:Submission} | 原子保存可选最后回答并结束；无模型出题调用；无 body 的旧请求仍有效 |

幂等和兼容规则：

1. 同用户同 start requestId 且相同材料/岗位返回同一场会话；同 ID 不同输入返回 409。落盘时保存 start requestId，重启后仍能查回。
2. 同题、同 submissionId、同内容只保存一次；同 ID 不同内容 409；换 ID 也不能覆盖已提交答案。同题同内容的旧重提保留既有兼容行为。
3. 已提交回答后，即使生成失败或请求响应丢失，也不再次要求回答；先读回，再发生成请求。
4. 同 generation requestId 返回同一操作；同会话已有活跃操作时返回其状态，不排第二个任务。expectedRevision 过期且未命中同 requestId 时返回 409，前端读回再决策。
5. 一次 NEXT 操作最多追加一道问题；重试只在当前操作内部增加 attempt，不新增问题或用户消息。
6. 生成输入在短锁内冻结：会话 ID、generationId、逻辑 revision、源题、简历及历史。回写前校验仍 ACTIVE、操作匹配、逻辑 revision 未变。
7. 工作线程与等待计时在锁外。拒绝排队时返回已保存会话的 FAILED 状态和可操作提示，不能遗留永远 QUEUED。
8. 新界面只用分离接口；旧 /turns 作为适配调用“保存一次、开始/加入生成、等待结果”。不能在发现同题已回答后直接返回一个永远不继续出题的快照。
9. 旧 start 不带 async 时保留同步返回首题；start/turns 的兼容等待均不持有会话锁，且加入同一个生成操作，不能再额外套一层重试。
10. 旧 pending 在保存、结束或迁移快照时归入真实已保存回答；不可丢弃服务端已经收到的内容。
11. 最后一道专项回答保存成功后直接 FINISHED / COMPLETED，不排 NEXT。首题未成功时结束也是合法操作，报告为零回答。

### 4.3 重试和截止时间

建议作为本轮初始可配置默认值，实施验收后根据真实耗时调整；不声称已经证明它们能解决所有故障。

~~~yaml
app:
  interview:
    storage-dir: ./data/interviews
    retention-days: 1
    max-retries: 5
    generation-deadline-seconds: 120
    attempt-timeout-seconds: 60
    retry-delays-ms: [1000, 2000, 4000, 8000, 16000]
    generation-workers: 4
    generation-queue-capacity: 32
~~~

- 总预算从生成操作被接受时开始，包含排队、每次调用与重试间隔。实际尝试次数可能小于 6。
- 常规首题/下一题是一个模型阶段，此时 retryCount=max(0,attempt-1)。转入专项可能先决策是否追问，再生成算法/案例正文；这两个阶段共享总计 6 次请求和 120 秒预算，retryCount 仅在失败后重试时增加，成功转入专项本身不算重试。已成功的阶段结果保留在操作内部，专项失败不能重跑已成功的追问决策。不得把“每个阶段 6 次”叠成一次生成 12 次。
- 每次调用超时不超过单次配置和剩余总预算。现有客户端最低 5 秒：剩余不足 5 秒不再启动一次调用，直接给出总预算用尽状态。
- TIMEOUT / NETWORK / UPSTREAM_UNAVAILABLE 可重试；RATE_LIMITED 先区分临时限流和明确额度耗尽，后者归 QUOTA 不重试。
- 尊重合法 Retry-After，并使用小幅随机抖动；等待将跨越总截止时间时直接终止自动重试。
- AUTH / CONFIG / QUOTA / INPUT / STORAGE 不重试模型。INTERRUPTED 先检查取消状态，不把用户结束当成网络失败。
- INVALID_OUTPUT 最多额外重试一次，仍计入总计最多 6 次和总预算；纠正格式后仍失败，提示生成内容异常并保留会话。
- 截止时间、尝试次数和可取消等待采用可注入时钟/调度器测试，不用真实等待几十秒的测试。
- 自动循环只由后端运行。前端负责轮询状态，读取失败不会新建生成操作。
- 手动“继续尝试”新建一轮有上限的操作，显示这是新一轮尝试；不能每次轮询自动刷新总预算。

### 4.4 保存、取消和恢复

- 快照保存简历文本、源材料 ID、岗位、问答、题目元数据、冻结标准、专项计划、幂等信息、生成状态、成功评分、报告缓存、创建/过期时间；不保存锁、Future、线程、模型客户端或凭据。
- 路径为 storage-dir / 安全用户目录 / sessionId.json；复用 SafePaths 的校验原则。写同目录临时文件并原子替换，失败不先发布新内存状态。
- 有界内存缓存与既有会话数量限制保留；过期文件也清理，不因持久化无限增长。
- 重启后 QUEUED / RUNNING / RETRY_WAIT 变成 FAILED，原因说明“服务已重启，可继续尝试”；不使用落盘凭据自动重启模型调用。
- 当前进程尚存在的旧内存会话可以在首次写入时转换；已经因旧后端重启丢失的记录不能恢复。
- 结束接口成功前，前端只标记“正在结束”；读取和出题回调暂不应用。结束请求结果不明时保留草稿，读回确认，不能提前宣称结束成功。
- 结束时尝试取消工作 Future 和等待定时器；调用未停止也不得再追加题目、安排重试或改变状态为 ACTIVE。
- 同一道未提交草稿，直接结束表示不计入本轮；界面确认这一点。只有结束已确认后才清该轮草稿和本地音频资源。

### 4.5 界面和语音边界

- 页面沿用现有视觉配色和字体，减少卡片层级；不引入新的组件库、聊天 SDK 或富文本编辑依赖。
- 对话区单独滚动；输入区固定在面试容器底部，预留高度，不覆盖最后一条消息。窄屏适配动态视口高度。
- 输入框自适应高度，普通回答上限约 6 行后内部滚动；算法题可展开更大编辑区，保留缩进和换行。
- 默认 Enter 换行、Ctrl/Cmd+Enter 发送，中文输入法组合期间不触发发送；按钮始终是可用的显式发送入口。
- 问题朗读按钮只作用于对应问题 ID；点击后加载、播放，再次点击可停止加载/播放。默认不自动朗读、不自动播放用户录音。
- 麦克风点击开始，停止后等待录音 Blob 就绪再触发转写；达到时长上限也只转写一次。取消录音不触发转写。
- 录音开始前如有文字保留；识别成功追加到“转写结果到达时仍存在的草稿”，不能用录音开始时的旧快照覆盖用户新输入。
- 新转写先落现有 preview 缓冲，再原子追加到草稿并保存；成功追加后才清预览。超过 5000 字时保留全文并提示精简，不能静默截断。
- 首次使用语音说明录音会发送到已配置语音服务；说明收敛为首次提示和信息抽屉。拒绝麦克风、语音不可用不妨碍文字。
- 阅读历史时不强制滚动；在距离底部 80px 内时自动跟随，否则显示“有新消息 / 回到最新”。
- 重试状态为对话中的单个状态条，更新同一条，不制造五条错误气泡或顶部重复告警。

### 4.6 后续计划、模式和反馈的统一契约（T22 起）

这些字段与接口尚未实现；阶段 A 用 legacy-v3 默认值保留现有行为。后续新增能力不能另开一套聊天状态机。

~~~ts
type ResumeSource = {kind:'MATERIAL'|'RESUME_VERSION', id:string}
type ResolvedSource = {
  source:ResumeSource, sourceLabel:string, resumeText:string, sourceFingerprint:string
}
type Mode = 'QUICK'|'FULL'
type PlanRequest = {
  source:ResumeSource, targetRole:string, jobDescription:string,
  careerStage:'INTERNSHIP'|'CAMPUS'|'EXPERIENCED',
  level:'FOUNDATION'|'STANDARD'|'ADVANCED',
  mode:Mode, mainCount?:3|5,
  specialtyPreference:'AUTO'|'NONE'|'ALGORITHM'|'CASE_STUDY',
  requestId:string
}
type InterviewPlan = {
  policyVersion:'legacy-v3'|'experience-v1', mode:Mode,
  mainCount:number, plannedCount:number, followUpBudget:number|null,
  maxFollowUpsPerMain:number, level:'FOUNDATION'|'STANDARD'|'ADVANCED'|null,
  targetRole:string, jobDescription:string,
  careerStage:'INTERNSHIP'|'CAMPUS'|'EXPERIENCED'|null,
  specialty:{kind:'NONE'|'ALGORITHM'|'CASE_STUDY',focus:string,reason:string},
  coverage:Array<{id:string,label:string}>,
  source:ResumeSource, sourceLabel:string, sourceFingerprint:string,
  practiceTargets:Array<{issueId:string,title:string,action:string,sourceQuestionIds:string[]}>
}
type PlanPreview = {
  planId:string, version:number, expiresAt:string,
  status:'GENERATING'|'READY'|'FAILED'|'EXPIRED',
  plan:InterviewPlan|null, failure:InterviewFailure|null
}
type AnswerStatus = 'UNANSWERED'|'ANSWERED'|'SKIPPED'|'WITHDRAWN'
type SessionProgress = {
  plannedRoots:number, resolvedRoots:number, answeredRoots:number,
  skippedRoots:number, followUpsUsed:number, followUpBudget:number|null
}
~~~

- legacy-v3 的 level/careerStage 为 null、followUpBudget 为 null，不能伪造用户选过难度或新整场上限；新 experience-v1 的这些字段必须非空并按表校验。
- T02 的快照类型预留冻结 plan、progress、closedRootIds（空集合）和 Turn.answerStatus / submissionId；阶段 A 只填写 legacy-v3 的实际默认值，后续任务实现新模式行为。未使用字段以明确空值/默认值读取，schemaVersion=2 的可选新增字段不改变旧规则。questionMetadata 新增 coverageId；旧题允许无覆盖 ID，不能冒充已经完成能力覆盖。
- closedRootIds 只能增加，表示不再接受该根题追问；QUICK 教学反馈先关闭题组，再评分。并发 NEXT 回写必须检查题组是否仍开放。
- PRIMARY generation 仍表示 OPENING / NEXT。澄清、换题、归纳、重答等用各自命名 operation 状态存于同一快照，不覆盖 PRIMARY generation；共享有界执行器、错误分类、取消/迟到校验，但不建设通用工作流平台。
- 新增模型操作的 POST 接受/排队后返回可查询状态，GET 读取其所属资源；不把计划、归纳或重答重新做成长时间阻塞浏览器的请求。默认不自动套五次循环；格式补试最多一次、显式人工重试去重，均有 120 秒以内截止时间。
- plan preview 15 分钟到期；请求 ID 按 owner 去重，存储时无 Key。重启时在途 preview 为 FAILED，可带当前凭据重试；普通刷新不重新调用模型。

| 规则 | legacy-v3 当前完整轮 | experience-v1 QUICK | experience-v1 FULL |
| --- | --- | --- | --- |
| 题数 | 12 主问题 + 1 专项 | 3 或 5 主问题，默认 5；可加 1 专项 | 12 主问题 + 1 专项 |
| 整场/单题追问 | 无额外整场上限 / 2 | 全场 2 / 单题 1 | 全场 6 / 单题 2 |
| 最多回答次数 | 37 | 8（5+1+2） | 19（12+1+6） |
| 默认入口 | 继续已有场次 | 新用户新计划默认选项 | 用户主动选择 |
| 反馈 | 结束后 | 保存并关闭题组后可看 | 结束后 |
| 综合分 | 原规则 | 始终无综合分 | 13 根题全部真实作答且全部评成才显示 |

计划、来源和题目接口：

| 接口 | 请求 / 返回 |
| --- | --- |
| POST /api/interviews/plans | PlanRequest → PlanPreview；异步生成方向/专项建议，最多 6 个方向，不暴露题目、标准或答案 |
| GET /api/interviews/plans/{planId} | owner 校验后返回 PlanPreview；查询不延长 TTL |
| POST /api/interviews | 新计划请求 {planId,planVersion,requestId,async:true}；与直接材料参数互斥 |
| POST /api/interviews/{id}/skip | {questionId,requestId,expectedRevision} → Session；不调用模型 |
| POST /api/interviews/{id}/clarifications | {questionId,requestId,expectedRevision} → Session；辅助 operation 及同题解释文字 |
| POST /api/interviews/{id}/replacements | {questionId,requestId,expectedRevision,reason} → Session；后台成功后才替换 |
| POST /api/interviews/{id}/feedback | {questionId,requestId} → Session 中题组反馈状态；GET Session 读结果 |
| POST /api/interviews/{id}/attempts | {questionId,requestId,answer} → {attemptId,status}；答案先保存 |
| GET /api/interviews/{id}/attempts/{attemptId} | {attemptId,answer,status,feedback,comparison,model,rubricVersion} |
| POST /api/interviews/{id}/attempts/{attemptId}/retry | 仅补该次失败评分，不增加重答版本 |
| POST /api/interviews/{id}/report/review/retry | 仅归纳重试，GET report 读取 reviewStatus / priorityIssues |
| GET /api/interviews/{id}/report?async=true | 已有报告加 scoringState / scoringOperationId / stateVersion；短请求查询，首次必要时排评分任务 |
| POST /api/interviews/{id}/practice-plan | {issueIds,requestId} → PlanPreview，三题 QUICK，用户确认后走统一 start |
| GET /api/interviews?limit=20&cursor=... | {items,nextCursor}；只返回本用户摘要，limit 上限 50 |
| DELETE /api/interviews/{id} | 取消全部会话操作并删除该轮；重复删除保持幂等，无权读取别人记录 |

来源与计划边界：

1. MATERIAL 使用现有 MaterialService.find/originalText；RESUME_VERSION 使用 ResumeService.findVersion。版本按章节转纯文本，保留标题/经历/项目/技能，不 AI 改写、不自动加入 contacts。
2. 沿用 listMaterials / fetchMaterial / pasteMaterial、listResumeVersions / fetchResumeVersion、fetchJobDetail；上传路径不重复建设。JD 不存在时允许手填。URL 只放 ID，不放正文/凭据。
3. 限制：岗位 200 字、材料正文 20000 字、JD 8000 字、回答 5000 字、换题原因 300 字；超长显示可编辑预览并要求精简，不静默截断。
4. 准备表单恢复只保存文字/来源 ID/偏好，不保存 File 或 Key；按服务/用户隔离。修改来源、JD、模式或偏好令客户端旧预览不可启动；start 必须校验 planVersion、所属用户和 TTL。
5. 预览记录源指纹；start 前源被删除或改变，提示重新预览，不能展示一个版本却按另一个版本出题。开始后复制已确认正文/JD，源删除不影响已开始轮次。
6. AUTO 按岗位职责推荐专项，非编码目标岗位优先；用户可在开始前主动纠正。FULL 不允许 NONE，QUICK 默认 NONE；新计划首题不得重新覆盖已确认的专项/难度。
7. 难度 FOUNDATION→EASY、STANDARD→MEDIUM、ADVANCED→HARD；案例按 level 控制任务复杂度。题库空池明确失败，不随机跨级。原算法 ID 在重试、重启和评分中冻结。
8. 计划页显示题数和追问上限，不在本计划引入任务工时估算；如果后续产品展示练习时长，必须单独标为练习估算，不把模型等待算成倒计时保证。

题目动作、报告和复练边界：

- skip 根题记 SKIPPED、推进 resolvedRoots，answeredRoots 不增加；跳追问不抹掉父题答案。WITHDRAWN 只记录被替换题，不占题数/追问预算。
- 澄清每根题最多一次，解释术语/要求，不泄露答案或冻结标准。换题每根题最多一次，只接受当前未提交题；失败保留原题、草稿和额度，成功才原子撤旧换新。
- 用户草稿未提交时动作先明确保留/放弃；旧题草稿保持以 questionId 隔离，不自动挪给新题；已收到回答不能撤回成草稿。
- 所有动作 requestId 幂等，并与 finish/skip/save 竞争同一逻辑提交点；在途辅助操作结束/删除后也不能写回。
- finishReason=COMPLETED 表示计划根题都已处理，可包含跳过；是否可算综合分仍要求 FULL 全部真实作答。只有主动未完成结束为 USER_ENDED。
- priorityIssues 最多 3 项，结构 {id,title,action,questionIds,evidence:[{questionId,quote}],kind}；kind 只允许 FACT/KNOWLEDGE/EXPRESSION/CONSISTENCY，quote 必须是关联已评分答案的连续原文，“反复”至少两道根题证据。
- 归纳版本绑定成功评分版本；补评分后旧归纳失效。失败仍展示已有逐题反馈，不重算成功分数，不以第一条反馈冒充整体归纳。
- 每根题最多 5 份独立重答；requestId 重复不占新名额。首答、冻结标准和原综合分不变，重答先保存，评分失败为 PENDING，可独立重试。
- comparison={resolvedPoints,remainingPoints,newIssues,scoreBefore,scoreAfter,comparable}，每条内容要有答案证据。缺首答评分或模型/标准不一致时不展示精确提分；同模型的差值也只是训练参考。
- 首答连同追问按同一标准比较；重答需写完整答案，不能把一段补充当整题重答。FULL 活跃期不开放教学反馈/重答，QUICK 只对已关闭题组开放。
- 薄弱项选 1–3 个本轮合法 issue 生成三题 QUICK 计划，绑定 practiceTargets 和 sourceSessionId；新场次独立评分，不复制旧总分，不照搬完整答案。
- 历史交付后新记录默认 30 天，每用户最多 20 ACTIVE / 100 未过期总会话；不静默删旧记录，到限给清理入口。分页 createdAt+sessionId 倒序稳定，摘要不泄露正文。
- 删除/过期清理先设不可写墓碑并取消操作，再删除快照；所有晚到生成、评分和归纳提交都检查 tombstone。墓碑保留至原 expiresAt，按 owner/sessionId/startRequestId 去重，重启和旧 start 重放也不能重建已删除会话；不保留原正文。不删除源材料或已独立开始的复练会话。

## 5. 逐项实施任务

### T01：保留模型错误类型并建立可追踪记录

**依赖：** 无。**交付：** 能区分超时、暂时限流、额度、配置和输出异常，记录一次调用的耗时。

**Files：** 修改 common/ai/AiClient.java、OpenAiCompatibleAiClient.java、interview/InterviewAgent.java、InterviewException.java、InterviewErrorHandler.java；新增 interview/InterviewFailure.java。以上均在 backend/src/main/java/com/shijie/resumehelper/ 下。

**Interfaces：** InterviewFailure 按 4.1；AiException 保留现有构造器，新增 code / retryAfterMs 访问能力；错误响应仍是 ApiResponse，失败 data 为 InterviewFailure。

- [x] 在 backend/src/test/java/com/shijie/resumehelper/interview/InterviewModelTransportTest.java 和新增 InterviewFailureTest.java 添加 401、404、临时 429、额度 429、503、超时、非法 JSON 的断言；确认原有构造器继续可用。
- [x] 先运行定向测试确认新断言失败；实施明确映射，无法可靠识别额度时保留上游安全分类，不能仅凭 429 就声称额度耗尽。
- [x] 日志仅记录 requestId / sessionId / generationId / stage / model / attempt / elapsedMs / failureCode；不输出简历、回答、Key 或原始响应。后续任务传入这些关联字段。
- [x] 运行后端验证组 B1；验收异常在页面可读、后台可分辨来源，其他模块原有 AiException 调用不破坏。

### T02：实现完整快照的原子文件存取

**依赖：** T01。**交付：** 可独立读写一份面试快照，失败写入不损坏旧记录。

**Files：** 新增 interview/InterviewSnapshot.java、InterviewPlan.java、InterviewStore.java、FileSystemInterviewStore.java、InterviewProperties.java、InterviewConfiguration.java；修改 application.yml。

**Interfaces：** InterviewStore.save(InterviewSnapshot snapshot)、find(String owner,String sessionId)、findByStartRequestId(String owner,String requestId)、deleteExpired(Instant now)。所有查询先校验 owner；find 返回 Optional<InterviewSnapshot>。

- [x] 新增 backend/src/test/java/com/shijie/resumehelper/interview/InterviewStoreTest.java，用临时目录覆盖包含问答/契约/评分的往返、跨用户查询、非法路径、故障写入后旧文件仍可读、到期清理。
- [x] 建立 schemaVersion=2 的不可变快照，按 4.4 列全字段；注入 ObjectMapper 和存储目录，明确未知版本/损坏文件返回受控错误，不覆盖原文件。
- [x] 同目录临时文件写完再原子替换；原子替换不支持时明确失败并清临时文件。配置默认目录和保持 24 小时保存语义。
- [x] 运行 B2 中 InterviewStoreTest；验收不得序列化凭据、Future 或运行时锁。

### T03：将会话生命周期接入持久化

**依赖：** T02。**交付：** 服务实例重建后可恢复同一会话、冻结标准和成功评分。

**Files：** 修改 InterviewService.java、InterviewConfiguration.java；新增 backend/src/test/java/com/shijie/resumehelper/interview/InterviewPersistenceTest.java。

**Interfaces：** 保留现有 InterviewService 对外方法；增加生产注入的 InterviewStore / Clock 和测试可用的构造方式。状态修改统一经“构建候选快照→save→发布快照”；所有写入统一递增 stateVersion。阶段 A 生成 legacy-v3 的冻结默认计划，后续字段不靠硬编码重新推断。

- [ ] 先写“服务 A 保存，服务 B 使用同目录恢复”“写失败不增加 revision”“恢复后已成功评分不再调用模型”“过期/越权不返回数据”的失败测试。
- [ ] 将纯快照和运行期 handle 分开；会话短锁、报告锁、工作 Future 不进入快照；保留既有数量限制和惰性到期清理。
- [ ] 适配旧内存 pending 的一次迁移；把重启时在途操作标记 FAILED，只恢复数据，不恢复带 Key 的模型任务。
- [ ] 运行 B2；记录旧实现已丢失的会话不可找回。此任务不新增历史列表页面或改变保存期限。

### T04：提供只保存回答的幂等接口

**依赖：** T03。**交付：** 模型不可用时也能确认回答已保存。

**Files：** 修改 InterviewService.java、InterviewController.java；新增 backend/src/test/java/com/shijie/resumehelper/interview/InterviewAnswerApiTest.java。

**Interfaces：** saveAnswer(String owner,String sessionId,Submission submission) → Session；POST /answers 按 4.2；此方法不接收 AiClient。

- [ ] 添加正确保存、同 ID 同内容重复、同 ID 不同内容、换 ID 覆盖、非当前题、5000 字边界、跨用户、存储失败场景。
- [ ] 保存后 answer 写入原 Turn、currentQuestion=null、revision 增加一次；专项末题直接 COMPLETED。服务端确认后才返回成功。
- [ ] 用抛错/计数模型替身证明此接口模型调用数为 0；重复请求快照等价，不能产生第二条用户回答。
- [ ] 运行 B3 中 InterviewAnswerApiTest；更新旧 pending 兼容断言但保留原重复保护。

### T05：拆开生成操作的认领与回写

**依赖：** T04。**交付：** 可在短锁内认领操作、锁外生成、短锁内有条件提交结果。

**Files：** 新增 InterviewGenerationCoordinator.java；修改 InterviewService.java、InterviewSnapshot.java；新增 backend/src/test/java/com/shijie/resumehelper/interview/InterviewGenerationCoordinatorTest.java。

**Interfaces：** claim(owner,id,requestId,kind,expectedRevision) → GenerationClaim；commitSuccess(claim,result) / commitFailure(claim,failure) → Session。GenerationClaim 是 Coordinator 内定义的不可变输入，包含 4.2 第 6 条字段。

- [ ] 先写相同请求加入同操作、不同请求撞上同一活跃操作只加入、旧 revision 被拒、两次成功回写只追加一题的失败测试。
- [ ] 实现 OPENING / NEXT 认领规则；NEXT 必须有一份尚未推进的已保存回答，不能从未提交草稿生成下一题。
- [ ] 将 nextIndex、父题、追问次数和专项转换保持在原服务端规则内；生成结果只能通过 commitSuccess 更新题目。
- [ ] 运行 B4 中 Coordinator 测试；验收不改变现有 12+1、追问并入父题和冻结评分契约。

### T06：后台运行生成并开放非阻塞状态查询

**依赖：** T05。**交付：** 出题过程中能即时读取会话，排队和取消都有终态。

**Files：** 新增 InterviewGenerationRunner.java；修改 InterviewConfiguration.java、InterviewController.java、InterviewService.java；新增 backend/src/test/java/com/shijie/resumehelper/interview/InterviewGenerationApiTest.java。

**Interfaces：** Runner.start(GenerationClaim claim,AiClient ai) → void；cancel(String generationId) → void；POST /generations 返回当前 Session，GET /interviews/{id} 返回 Generation 状态。

- [ ] 用 CountDownLatch 阻塞模型，证明 GET 在释放模型之前返回；同时证明同一会话重复点击只调用一次模型。
- [ ] 注入有界线程池，模型调用和等待均在会话锁外；拒绝排队/执行器关闭后把操作写为明确 FAILED，不遗留 QUEUED。
- [ ] 保存 Future 的运行期映射并及时释放；成功、失败、取消均收敛到一次终态；无 Key、非 ACTIVE 或过期会话不排任务。
- [ ] 运行 B4；测试中可靠释放 latch / 关闭执行器，不用 Thread.sleep 假装证明无死锁。

### T07：将首题纳入可恢复生成操作并兼容旧客户端

**依赖：** T06。**交付：** 首题慢或响应丢失时不会重复创建面试。

**Files：** 修改 InterviewService.java、InterviewController.java、InterviewAgent.java、InterviewStore.java 的实现；新增 backend/src/test/java/com/shijie/resumehelper/interview/InterviewOpeningApiTest.java；适配 InterviewFlowTest.java。

**Interfaces：** async=true 的 start 返回首题尚未就绪的 Session；start 的 requestId 持久化去重；已有 start 和 /turns 走同一 Coordinator / Runner 的兼容适配。

- [ ] 覆盖 start 响应丢失后同 ID 重发、重启后同 ID 重发、同 ID 不同岗位冲突、首题失败后原会话重试、首题期间零回答结束。
- [ ] 首题成功时一次性冻结专项计划和首题标准；失败不生成半份计划，不新建会话替代原会话。
- [ ] 兼容旧 start /turns：同步等待在锁外进行；旧同题重试可加入未完成操作，完成的同题重提不重复出题。
- [ ] 运行 B3、B4 中 Opening 和 Flow 测试；新旧客户端混用只能出现一条有效后续问题。

### T08：加入最多五次自动重试和总等待预算

**依赖：** T01、T06、T07。**交付：** 可观测、有上限、可取消的模型出题重试。

**Files：** 新增 InterviewRetryPolicy.java；修改 InterviewGenerationRunner.java、InterviewAgent.java、InterviewProperties.java、application.yml；新增 backend/src/test/java/com/shijie/resumehelper/interview/InterviewRetryPolicyTest.java、InterviewRetryRunnerTest.java。

**Interfaces：** RetryPolicy.decide(InterviewFailure failure,int attempt,int retryCount,Instant deadlineAt,Instant now) → RetryDecision；RetryDecision 在 Policy 内定义为 {retry:boolean,delayMs:long,attemptTimeoutSeconds:int}。Runner 持久化 4.1 的 stage / attempt / retryCount / nextRetryAt / failure。InterviewAgent.prepareOpening / prepareNext / prepareAlgorithm / prepareCase 增加显式 timeoutSeconds 重载，旧调用保持原超时默认；各重载必须把值传到最终 AiClient.chat。

- [ ] 用可控时钟先写“快速失败恰好最多 6 次”“第一次成功仅 1 次”“第 3 次成功不再请求”“预算到期少于 6 次”“格式错误最多补一次”“不可重试仅 1 次”。
- [ ] 实现 4.3 的分类、间隔、Retry-After、抖动和剩余预算；把单次剩余超时实际传到 AiClient.chat，不能只写一个不会中止 HTTP 的计时器。专项的决策/正文共享请求计数，成功阶段不重跑。
- [ ] 每次尝试前和每次等待后检查操作仍有效；取消等待不得安排下一次请求。前端轮询和兼容适配都不能启动另一个重试循环。
- [ ] 运行 B1、B5；记录每次 stage、attempt、elapsedMs、failureCode。界面可以据状态显示“重试 n/5”。

### T09：原子结束并隔离迟到的生成结果

**依赖：** T04–T08。**交付：** 可在任何出题状态结束，提交最后回答不产生下一题。

**Files：** 修改 InterviewService.java、InterviewController.java、InterviewGenerationCoordinator.java、InterviewGenerationRunner.java；新增 backend/src/test/java/com/shijie/resumehelper/interview/InterviewFinishApiTest.java。

**Interfaces：** finish(owner,id,requestId,Submission nullable) → Session；空 body 沿用旧 finish。状态和回答一次落盘，成功后取消运行期任务。

- [ ] 先写无草稿结束、有最后回答结束、重复结束、同题已保存、RUNNING/RETRY_WAIT/QUEUED 结束、存储失败、跨用户以及结束和回答竞态测试。
- [ ] 原子保存可选 Submission、处理已收到 pending、标记 FINISHED/USER_ENDED 并使旧 generationId 无效；已自然完成的会话保留 COMPLETED。
- [ ] 断言“提交并结束”的新增出题调用数为 0；结束返回不需要等待被 latch 阻塞的模型释放；释放后题目数不增加。
- [ ] 运行 B6；前后两次读取均为 FINISHED，取消后无下一次自动尝试；存储失败时不得错误报告结束成功。

### T10：接入前端分离 API 和可取消轮询

**依赖：** T07–T09。**交付：** 前端能短请求提交/查状态，不依赖一个几分钟的浏览器请求。

**Files：** 修改 frontend/src/api/interview.js、src/api/interview.test.js；新增 src/interview/useInterviewPolling.js、useInterviewPolling.test.js。

**Interfaces：** api.start(body)、saveAnswer(id,submission,signal)、generate(id,body,signal)、getSession(id,{signal})、finish(id,body,signal)；useInterviewPolling({api,onSession,onError,clock}) 返回 start(sessionId)、stop()。

- [ ] API 测试精确断言路径、请求体、现有身份/AI 请求头、error.code / retryable 解析以及取消信号，语音专用头不能混入文字模型请求。
- [ ] 为短请求设置截止时间：状态读取 10 秒，保存/创建/结束/排队请求 15 秒；支持外部取消，超时异常区分“浏览器请求未确认”和模型 TIMEOUT。
- [ ] 轮询在同一会话一次一个请求，间隔从 1 秒逐渐到最多 5 秒；终态停止、换轮和离页取消，读取失败只显示连接状态，不发生成 POST。
- [ ] 运行 F1；确认计时器和 AbortController 被清理，已停止轮询的旧结果不再回调。

### T11：集中管理发送、保存确认和恢复

**依赖：** T10。**交付：** 单次发送形成一条用户消息，出题失败不要求重新发送答案。

**Files：** 新增 frontend/src/interview/useInterviewConversation.js、useInterviewConversation.test.js；修改 Interview.vue、src/api/interview-flow.test.js；复用 draftStore.js、interviewNavigation.js。

**Interfaces：** useInterviewConversation({api,drafts,voice,polling,ids}) 返回 session、draft、phase、error、submission、send()、retryGeneration()、restore(id)、dispose()。phase 为 PREPARING / ANSWERING / SAVING / GENERATING / RETRY_WAIT / GENERATION_FAILED / FINISHING / FINISHED。

- [ ] 先写保存成功但出题失败、保存响应丢失后读回、读回也失败、双击发送、旧轮响应晚到和刷新恢复测试。
- [ ] 首次发送生成并保存 submissionId；保存结果未知时保留同一 ID 和原文。读回确认保存后只恢复/请求生成，不重交回答。
- [ ] 仅保存确认后清本题草稿；失败保留。服务端状态为权威，UI 的“发送中”不能伪装成已保存；拒绝 stateVersion 倒退。手动继续生成用新 requestId，同轮轮询不更换 ID。服务地址/用户改变时先按旧 scope 保存草稿，停止旧轮询并重建上下文；会话指针也按现有服务/用户 scope 隔离，避免恢复到另一后端；不为新指针贸然改变 v1 草稿键，旧指针仅在当前 scope 读回校验后迁移。
- [ ] 运行 F2；现有新一轮导航、草稿存储失败提示、多标签冲突和超长文字保护继续通过。

### T12：拆分准备页、精简顶部和信息抽屉

**依赖：** T11。**交付：** 进入面试后主要空间只用于对话和输入。

**Files：** 新增 frontend/src/components/interview/InterviewSetup.vue、InterviewHeader.vue、InterviewInfoDrawer.vue；修改 Interview.vue、src/api/interview-structure.test.js。

**Interfaces：** Setup 接收现有材料/岗位字段并 emit upload、use-paste、start；Header 接收 role、progress、canFinish 并 emit finish、open-info；Drawer 只展示当前会话信息，不修改冻结规则。

- [ ] 先更新浏览器正常流程用例：准备阶段能上传/粘贴，开始后表单收起，岗位与进度保留，信息按钮能打开和关闭抽屉。
- [ ] 移走装饰侧栏；抽屉收纳材料摘要、12+1 规则、专项说明、保存期限和语音说明，避免复制两份状态或规则。
- [ ] Header 的结束入口不再使用全局 locked 一律禁用；只在未建立会话、结束请求提交中或已结束时不可重复触发。
- [ ] 运行 F2 和局部浏览器用例；键盘可关闭抽屉并回到触发按钮，窄屏不遮住结束入口。

### T13：实现稳定消息序列和历史滚动

**依赖：** T11、T12。**交付：** 左右对话清晰、重复轮询不重复插消息、阅读历史不被打断。

**Files：** 新增 frontend/src/interview/conversationMessages.js、conversationMessages.test.js、useConversationScroll.js、useConversationScroll.test.js；新增 components/interview/InterviewMessages.vue、InterviewQuestionMessage.vue；修改 Interview.vue。

**Interfaces：** toConversationMessages(session,pendingSubmission) → [{id,role,questionId,text,status,kind}]，ID 用 questionId + question/answer；useConversationScroll({threshold:80}) 返回 onScroll、onMessagesChanged、scrollToLatest、hasNewMessages。

- [ ] 添加主问题、追问、等待生成、首题为空、保存未确认、同快照重复应用、结束迟到快照不能插消息的断言。
- [ ] 用户回答右侧、问题左侧；普通正文保留换行，算法显示代码友好排版；待生成状态是一条可更新状态消息。
- [ ] 按 80px 阈值跟随，远离底部则保留位置并提示新消息；首次恢复定位最新，展开历史中的长内容不突然跳到底。
- [ ] 运行 F3；浏览器用 10 组长问答检查当前题可见、消息无重复、输入区不覆盖最后一条消息。

### T14：实现固定多行输入区和算法展开输入

**依赖：** T13。**交付：** 输入、录音入口和发送集中在底部，长答案可正常编辑。

**Files：** 新增 frontend/src/components/interview/InterviewComposer.vue、src/interview/composerActions.js、composerActions.test.js；修改 Interview.vue。

**Interfaces：** Composer 接收 modelValue、phase、kind、voiceState、maxLength=5000，emit update:modelValue、send、toggle-record、stop-record、retry-transcription；composerActions 导出 shouldSend(event) 和 insertIndent(text,start,end)。

- [ ] 测试 Enter 换行、Ctrl/Cmd+Enter 发送、isComposing=true 不发送、空白/超长不可发送、算法缩进和保留换行。
- [ ] 普通输入自动增高到约 6 行；算法支持“展开编辑”、语言标记和 Tab 缩进，Esc 可退出缩进捕获，提供明确关闭/焦点返回入口；输入展开不会另存一份相互覆盖的草稿。整份提交仍受 5000 字约束，不能形成键盘陷阱。
- [ ] 布局预留输入区实际高度，处理动态视口和窄屏；麦克风/发送在输入容器内部，状态只按当前操作展开。
- [ ] 运行 F3；浏览器检查 390px 宽、长文本、算法代码和键盘焦点。避免用隐藏溢出来掩盖无法访问的内容。

### T15：收敛录音转写和问题内朗读

**依赖：** T13、T14。**交付：** 录音结束自动转写到草稿，问题内一键播放/停止。

**Files：** 修改 frontend/src/interview/useInterviewVoice.js、useVoiceRecorder.js、useQuestionSpeech.js 及同名测试；修改 InterviewQuestionMessage.vue、InterviewComposer.vue。

**Interfaces：** voice.stopRecording({transcribe:boolean})，普通停止传 true，在 Blob 就绪后触发一次转写；结束确认中的停止传 false，仅保留录音。voice.playQuestion({sessionId,questionId}) 明确指定问题；保留 cancelRecording、cancelTranscription、dispose 和预览恢复能力。

- [ ] 先测手动停止/达到上限仅转写一次、取消录音不转写、Blob 尚未就绪不提前请求、识别期间输入新文字不被旧快照覆盖。
- [ ] 复用现有 preview→追加草稿→保存→清 preview 流程，超过 5000 字保留全文；失败保留 Blob 和局部重试。录音刷新不可恢复的说明保留在信息抽屉。
- [ ] 合并朗读按钮的加载/播放/停止；历史问题按自己的 questionId 播放，未授权 Key 不外泄，录音开始停止其他播放。
- [ ] 运行 F4；覆盖麦克风拒绝、语音能力不可用、90 秒取消、换题/离页迟到回调、恢复旧预览不会自动提交。首次语音使用说明可见，文字路径不中断。

### T16：接入提前结束弹窗及录音/生成状态处理

**依赖：** T09–T15。**交付：** 用户能在不同状态下主动结束，清楚知道最后一份内容如何处理。

**Files：** 新增 frontend/src/interview/useInterviewFinish.js、useInterviewFinish.test.js、components/interview/InterviewFinishDialog.vue；修改 Interview.vue、useInterviewConversation.js、src/api/interview-flow.test.js。

**Interfaces：** useInterviewFinish({conversation,voice,api,drafts}) 返回 open、finishing、requestFinish()、confirmFinish('submit'|'discard')、continueInterview()；调用 conversation 的 FINISHING 状态阻止旧回调应用。

- [ ] 弹窗覆盖无草稿、有草稿、已保存等待下一题、首题生成中、自动重试中；草稿存在时明确三种选择，已保存回答不能显示成可丢弃本地草稿。
- [ ] 录音中打开弹窗先停止并保留音频，不自动上传；可选“转写并检查后继续选择”或“直接结束”；已在转写中时可取消并保留原文字。继续面试时可恢复音频重试，不自动丢内容。
- [ ] submit 直接带 Submission 调用 finish，不调用 send() / generate()；discard 只舍弃尚未提交内容。结束确认成功后释放录音/朗读、停止轮询并导航报告。
- [ ] 请求失败/结果未知时保留文字和录音，读回会话后再确认；不误清草稿、不展示假成功。运行 F5，并验证已结束轮次不会被晚到模型结果重开。

### T17：完善提前结束的报告和零回答状态

**依赖：** T09、T16。**交付：** 报告准确反映覆盖范围，结束不依赖评分成功。

**Files：** 修改 backend/src/main/java/com/shijie/resumehelper/interview/InterviewReportAssembler.java、InterviewAgent.java 的报告数据定义；修改 backend/src/test/java/com/shijie/resumehelper/interview/InterviewReportAssemblerTest.java、InterviewReportApiTest.java；修改 frontend/src/views/Report.vue、src/api/report-view.test.js。

**Interfaces：** 报告新增 finishReason，保留 plannedCount / answeredCount / reportStatus / overallScore；原评分 API 和人工补评入口继续可用。

- [ ] 测试零题、只答一题、主题带追问、未答专项、最后专项自然完成和部分评分失败；score=0 与 score=null 必须区分。
- [ ] 标注“提前结束 · 已答 X/13”，未答不补零；范围不足 overallScore=null。零回答不请求评分模型，显示暂无可评价内容。
- [ ] 报告加载/评分失败仍能看到已结束状态和问答，允许重试未评分题；成功评分缓存、打印导出及原完整轮 80/20 算法保持。
- [ ] 运行 B7、F5；“提交当前回答并结束”保存的最后答案出现在报告，未提交草稿不出现。

### T18：将报告评分改为可查询的后台任务

**依赖：** T17。**交付：** 面试结束后立即看见问答与评分进度，报告不再依赖一次长浏览器请求。

**Files：** 新增 backend/src/main/java/com/shijie/resumehelper/interview/InterviewReportRunner.java、backend/src/test/java/com/shijie/resumehelper/interview/InterviewReportAsyncTest.java；修改 Service、Controller、快照、frontend/src/api/interview.js、Report.vue 及其报告测试。

**Interfaces：** GET /api/interviews/{id}/report?async=true → 原报告字段加 scoringState / scoringOperationId / stateVersion；POST /report/retry 接受 {async:true,requestId}，只排队尚未成功的评分。旧无 async 调用保留兼容。

- [ ] 用阻塞模型证明 async GET/POST 在模型释放之前返回已有问答和评分状态；重复刷新只加入同一评分任务，评分失败仍可读报告。
- [ ] 复用现有按五题分批和成功缓存，有界后台执行；每批经统一快照提交，评分和出题操作分别管理，不占会话读取锁。
- [ ] 前端短请求轮询 scoringState，失败只提示/允许人工补评，不反复启动任务；重启把在途评分恢复为可补评，删除/过期后结果不能创建快照。
- [ ] 运行 B7、B7a 和 F5；原证据校验、合法零分、完整综合分、成功评分不重复、打印和部分报告均保留。

### T19：补齐后端跨接口并发与重启回归

**依赖：** T01–T09、T17、T18。**交付：** 用集成证据确认各个局部实现组合后仍保持单次保存、单次出题和终态一致。

**Files：** 新增 backend/src/test/java/com/shijie/resumehelper/interview/InterviewReliabilityIntegrationTest.java；按失败涉及的具体文件做局部修复；不重写已通过的接口。

**Interfaces：** 使用真实 Spring 控制器、临时存储和受控模型/调度器，不连接真实厂商。

- [ ] 建立故障矩阵：保存成功响应丢失→读回→生成；多客户端同时生成；重试间隔内结束；模型完成与结束竞态；结束存储失败；生成/评分同时写快照；重启恢复后重新生成。
- [ ] 断言每个 submissionId 只保存一次、同一源题最多追加一题、FINISHED 后无出题写入、报告并发写入不覆盖最后回答，跨用户无法操作。
- [ ] 验证服务重启后 start requestId 查回原会话、已成功评分无需重算、在途状态明确 FAILED；模拟执行器饱和和截止时间，不能留永久处理中。
- [ ] 运行完整后端测试与打包；记录实际测试输出及所有仍未执行的外部模型场景。

### T20：验收聊天正常流程与响应式布局

**依赖：** T10–T18。**交付：** 用户能完整走通准备、聊天、语音和报告，视觉布局可读。

**Files：** 修改 frontend/tools/check-interview.mjs，保持原浏览器验证入口；新增 docs/interview-chat-acceptance.md 的正常流程记录。

**Interfaces：** 模拟 API 按 4.2 返回真实状态转换；页面事件驱动，不靠搜索 Vue 源码断言交互正确。

- [ ] 脚本完成准备→首题→文字回答→下一题→历史阅读→回到最新→提前结束→报告；另覆盖最后专项自然结束。
- [ ] 使用现有假麦克风完成录音→停止→自动转写→修改文字→发送，以及问题朗读加载→播放→停止；结果必须对应题号。补完旧草稿任务未完成的设置往返、刷新、提交断网、配额失败、多标签冲突场景。
- [ ] 检查桌面 1440×900、窄屏 390×844、长题干/长答案、算法展开、抽屉和结束弹窗；键盘操作、焦点返回、输入区不遮消息。
- [ ] 运行 npm test、npm run build、浏览器脚本；保存实际截图和断言结果，修复发现的问题后只重跑受影响用例及必要回归。

### T21：故障体验验收、说明更新与交付记录

**依赖：** T19、T20。**交付：** 本轮需求均能对应验收证据，运行边界有说明。

**Files：** 修改 frontend/tools/check-interview.mjs、docs/interview-chat-acceptance.md、backend/README.md；更新本计划的任务完成勾选。

**Interfaces：** 故障注入不调用真实厂商、不读取真实用户材料；验收表记录实际日期、环境、场景、结果、证据文件。

- [ ] 浏览器覆盖前两次出题失败后成功、快速失败达到 5 次重试、总预算提前终止、配置错误不重试、生成失败后手动继续、保存响应丢失、结束响应丢失。
- [ ] 浏览器覆盖录音中/转写中/首题生成中/重试中结束，结束后旧响应晚到、刷新恢复、草稿存储不可用、后端重启恢复和评分暂不可用。
- [ ] 检查模式尚未开放时不出现后续功能的空按钮；旧设置、技术/非技术专项、草稿/导航、打印与凭据分流继续通过。真实厂商与设备验收统一放 T40/T41，不在此重复列开发任务。
- [ ] 更新阶段 A 配置、最多五次重试的含义、120 秒总预算、24 小时记录与 7 天本机草稿、单机限制、提前结束行为。记录本阶段证据和未验证条件；通过后可单独交付，不自动发布。

### T22：复用材料和简历版本的后端来源

**依赖：** T21。**交付：** 统一取得用户确认过的简历正文，不重复上传/抽取。

**Files：** 新增 InterviewSourceResolver.java、backend/src/test/java/com/shijie/resumehelper/interview/InterviewSourceResolverTest.java；复用 MaterialService、ResumeService。

**Interfaces：** resolve(String owner,ResumeSource source) → ResolvedSource，类型按 4.6；未找到或非 owner 统一 404。

- [ ] 测试材料/版本文字、章节标题和 bullets 保留、contacts 不自动加入、源不存在/越权、超长或空正文；定向测试先失败。
- [ ] 调用现有服务，生成稳定 sourceFingerprint，正文是确定性转换，不调用模型改写。
- [ ] 预览与 start 使用同一文本转换；源变动可检测，已开始会话依靠快照继续。
- [ ] 运行 B8；断言材料来源不新增上传请求、版本来源不调用 AI 抽取。

### T23：接通来源/JD 入口与准备表单恢复

**依赖：** T22。**交付：** 从材料、简历版本、岗位或直接面试入口准备同一场练习。

**Files：** 新增 frontend/src/interview/useInterviewSetup.js、useInterviewSetup.test.js；修改 InterviewSetup.vue、Interview.vue、Jobs.vue、Materials.vue、Resume.vue。

**Interfaces：** useInterviewSetup({materialsApi,resumeApi,jobsApi,storage,scope}) 提供 source、resumePreview、targetRole、jobDescription、preferences、loadSource、loadJob、savePreparation、clearPreparation。

- [ ] 测试已有材料不上传、版本正文预览、完整 JD 带入、岗位删除可手填、刷新恢复与切换用户/后端隔离。
- [ ] 入口沿用 intent=new，只传 materialId/resumeVersionId/jobId；未保存简历提供显式“保存版本并开始”，不偷用旧版本。
- [ ] 粘贴改用现有 pasteMaterial，上传保持原路径；来源/正文预览可确认，准备表单不保存 File、Key 或原始录音。
- [ ] 运行 F6，浏览器验证三种入口及直接入口；新一轮导航/原草稿不退化。

### T24：生成、查询和确认有版本的面试计划

**依赖：** T22、T23。**交付：** 开始前可查看考察方向和专项推荐，慢请求有明确状态。

**Files：** 扩展 T02 的 InterviewPlan.java；新增 InterviewPlanService.java；扩展 InterviewStore/FileSystemInterviewStore 的 savePlan/findPlan；修改 InterviewController、InterviewAgent、InterviewSkill；新增 InterviewPlanTest.java。

**Interfaces：** prepare(owner,PlanRequest,ai) → PlanPreview；getPlan(owner,planId) → PlanPreview；startPlanned(owner,planId,planVersion,requestId,ai) → Session；API 按 4.6。

- [ ] 测试 planId/requestId 去重、最多 6 个方向、15 分钟过期、越权/旧版本拒绝、源指纹变化、重复 start 同会话。
- [ ] 有界后台生成预览，模型 I/O 锁外，POST 返回可轮询状态；复用错误分类和截止时间，不在刷新时重生成。
- [ ] 首题冻结已确认计划，只生成问题及标准，不重判专项；用户更改偏好必须重新预览。READY 计划中不暴露题目或评分标准。
- [ ] 运行 B9 的 InterviewPlanTest；核对失败/重启/排队拒绝后可用当前凭据重试，计划不存 Key。

### T25：实现新模式预算和关闭题组

**依赖：** T24。**交付：** QUICK/FULL 按冻结计划推进，旧场次保持旧预算。

**Files：** 修改 InterviewService、InterviewGenerationCoordinator、InterviewAgent、InterviewReportAssembler、InterviewScoringPolicy 和快照；新增 InterviewModeFlowTest.java。

**Interfaces：** 所有新流程从 Session.plan 读 mainCount/followUpBudget，progress 按 4.6；closeRoot(owner,id,questionId,requestId) 原子增加 closedRootIds，不评分。

- [ ] 覆盖 QUICK 3/5 主题、可选专项、FULL 12+1、全场/单题追问上限、最后无专项完成、旧 legacy 37 回答上限。
- [ ] 服务器同时校验两层追问预算和 coverageId，模型不能突破配额；新 FULL 最多 19、QUICK 最多 8 次回答。
- [ ] QUICK 保存后等用户选择反馈/下一题，FULL 自动生成；关闭题组使该组迟到追问失效。COMPLETED 与是否有综合分分开判断。
- [ ] 运行 B9；旧评分 80/20、0/null、专项冻结与报告缓存不漂移。

### T26：在准备和聊天页面展示新模式

**依赖：** T23–T25。**交付：** 用户明确选模式、确认计划，答题进度与实际规则一致。

**Files：** 修改 useInterviewSetup.js、InterviewSetup.vue、InterviewHeader.vue、InterviewInfoDrawer.vue、useInterviewConversation.js、api/interview.js；扩展 useInterviewSetup.test.js 和浏览器脚本。

**Interfaces：** api.preparePlan/getPlan/startPlanned 对应 4.6；只在 READY 且本地偏好未变时可确认开始。

- [ ] 测试预览等待/失败、偏好改变旧预览不可用、重复确认不多建、服务切换旧计划不应用。
- [ ] 新用户默认 QUICK 5，FULL 主动选；展示专项原因、主题数/追问上限和方向，用户可纠正专项；既有会话不换模式。
- [ ] 进度读 progress/plan，移除新模式中的硬编码 /12、/13 和到处出现的 20%；QUICK 的下一题/查看反馈按实现状态开放。
- [ ] 运行 F6；浏览器分别走 QUICK 完成、FULL 完成和提前结束，功能未交付的按钮不展示。

### T27：按计划难度固定专项题

**依赖：** T24、T25。**交付：** 算法难度不随机越级，案例复杂度与计划一致。

**Files：** 修改 Hot100Catalog.java、InterviewPlanService.java、InterviewAgent.java、specialtyPresentation.js；新增 Hot100SelectionTest.java，扩展 InterviewSpecialtyTest.java。

**Interfaces：** Hot100Catalog.select(String difficulty,Set<String> excludedIds) → Problem；仅接受 EASY/MEDIUM/HARD，空池明确失败。

- [ ] 用注入随机源测试 20 次确定性抽样不越级、排除已出题、非法难度/空池、重启/重试不换 ID。
- [ ] 实现 FOUNDATION/EASY、STANDARD/MEDIUM、ADVANCED/HARD；同源复练排除已出题，池耗尽需用户明确允许重抽。
- [ ] 案例按 level 生成专业任务；保持非编码岗位优先规则。报告注明“静态思路与代码评阅，未执行代码”，不声称测试通过。
- [ ] 运行 B10 的选题/专项测试，并检查准备页显示的难度与实际题元数据一致；输入缩进能力复用 T14，不重做。

### T28：实现跳题及准确覆盖范围

**依赖：** T25、T26。**交付：** 不会答时可继续，跳过不会变成零分。

**Files：** 新增 InterviewQuestionActionService.java；修改 Controller、Service、ReportAssembler、api/interview.js、useInterviewConversation.js、InterviewQuestionMessage.vue；新增 InterviewSkipTest.java。

**Interfaces：** skip(owner,id,questionId,requestId,expectedRevision) → Session；不接收 AiClient。

- [ ] 测试跳根题、跳追问、重复 requestId、已回答题拒绝、与 finish/save 竞态、越权和草稿选择。
- [ ] 根题记 SKIPPED，resolvedRoots 增加、answeredRoots 不增；追问跳过保留父答，推进下一题复用 /generations。
- [ ] UI 明确本题不计作答，旧草稿以旧 questionId 保存/按用户选择丢弃；报告区分跳过与未出题。
- [ ] 运行 B10 的跳题测试、F6 及浏览器 QUICK/FULL 跳题；范围不足不产生综合分。

### T29：实现同题澄清且不泄露答案

**依赖：** T28。**交付：** 用户可理解题意，不改变正式题目或评分标准。

**Files：** 修改 QuestionActionService、Agent、Controller、useInterviewConversation、QuestionMessage、api/interview.js；新增 InterviewClarificationTest.java；更新 project-question-contract.md。

**Interfaces：** clarify(owner,id,questionId,requestId,expectedRevision,ai) → Session；同题 operation 在 GET Session 中查询，成功辅助文本独立保存。

- [ ] 测试每根题仅一次、重复请求不重复消耗、澄清失败不改题、结束后结果不写回、无答案/评分标准输出。
- [ ] 异步只解释术语/任务，保留 question/criteria/题号；不得把 QUICK 教学反馈提前放入 FULL。
- [ ] UI 在题目下显示辅助解释与局部重试，输入内容不变；保存/跳题/结束会使不适用的在途解释失效。
- [ ] 运行 B10 的澄清测试和浏览器题意不明场景；记录真实模型是否遵守边界，不能只靠 fixture 宣称不泄漏。

### T30：实现经历不符时的安全换题

**依赖：** T29。**交付：** 明确原因后换题，失败时仍保有原题和草稿。

**Files：** 修改 QuestionActionService、Agent、Controller、快照、QuestionMessage、useInterviewConversation、api/interview.js；新增 InterviewReplacementTest.java。

**Interfaces：** replace(owner,id,questionId,requestId,expectedRevision,reason,ai) → Session；同根题最多一次，reason 1–300 字。

- [ ] 测试换题成功/失败、重复点击、每根题额度、源题被答/跳/结束、迟到回写以及不能改专项身份。
- [ ] 异步生成同考察方向的新题和新冻结标准；成功才将旧题 WITHDRAWN、新题替入同根序号并消耗额度。失败原题/草稿/额度不变。
- [ ] UI 收集真实原因，明确提示草稿处理；已提交回答不能换题撤回。模型不继续假设用户做过被否认的经历。
- [ ] 运行 B10 全组和阶段 B 浏览器回归；快照、模式上限和所有题目动作并发都符合 4.6。

### T31：生成有证据的报告重点归纳

**依赖：** T17、T30。**交付：** 最多三条可行动问题，独立失败不影响已有逐题报告。

**Files：** 新增 InterviewReviewSummary.java、InterviewReviewSummaryTest.java；修改 Agent、Service、Controller、快照和 project-scoring-and-feedback.md。

**Interfaces：** summarize(validatedFeedbacks,turns,ai) → priorityIssues；报告 reviewStatus 为 PENDING/COMPLETE/UNAVAILABLE，reviewSourceVersion 绑定成功评分版本。

- [ ] 测试伪造题号、非原文引用、单题声称反复、非法 kind、部分评分、归纳失败、补评分使旧归纳失效。
- [ ] 后台独立归纳并回验证据；GET report 只查询状态/缓存，首次必要时排队，普通刷新不重复。重试只重做归纳。
- [ ] 成功结果最多三条，失败明确可重试；不计算分数、不修改标准、迟到旧评分版本结果不覆盖新报告。
- [ ] 运行 B11；评分成功记录不因归纳失败重评，结束/删除/过期状态校验完整。

### T32：按覆盖范围和改进动作重排报告

**依赖：** T31。**交付：** 首屏能看懂练了哪些、最需改什么，以及如何继续练。

**Files：** 修改 Report.vue、api/interview.js、src/api/report-view.test.js、interview-report.test.js、浏览器打印场景。

**Interfaces：** api.retryReview(id) 只排队归纳；沿用 report 查询与原补评分接口，两种失败有独立状态。

- [ ] 测试部分报告、归纳等待/失败、只补失败阶段、QUICK 无总分、合法零分、删除/过期后停止轮询。
- [ ] 顺序为覆盖范围→最多三条重点及证据→后续入口→优点→逐题详情→评分说明；未实现复练入口暂不展示。
- [ ] 移除未经校准分数推导的泛化“优秀/可自信”等判断，改为本轮具体表现。打印保留原题、答案、证据和计算详情。
- [ ] 运行 F7 报告组与浏览器打印；补评分不刷新丢失原问答，归纳失败不伪造完整总结。

### T33：提供关闭题组后的教学反馈

**依赖：** T25、T31。**交付：** QUICK 可学后再推进，FULL 活跃期不提前泄露反馈。

**Files：** 新增 InterviewPracticeService.java、InterviewPracticeFeedbackTest.java；修改 Controller、Service、useInterviewConversation、QuestionMessage 和 API。

**Interfaces：** feedback(owner,id,questionId,requestId,ai) → Session 中 feedbackOperation；GET Session 查询，复用原 AssessmentInput / 证据校验。

- [ ] 测试 QUICK 主答已保存但追问未关闭、FULL ACTIVE 拒绝、重复请求缓存、评分失败、原根题追问汇入同一评分。
- [ ] 查看反馈先原子关闭题组并使该组在途追问无效，再异步评分；关闭不能反转，已显示教学内容不能再作为未受提示首答。
- [ ] QUICK 保存后可“查看反馈/下一题”，失败不阻止下一题；无专项最后一组关闭后完成轮次，不额外生成问题。
- [ ] 运行 B12 的反馈测试、F7 及浏览器 QUICK 流程；原 FULL 结束后评分保持不变。

### T34：保存独立重答并生成证据对比

**依赖：** T33。**交付：** 重答先保存、失败可补评、首答与总分不变。

**Files：** 扩展 PracticeService、Controller、快照；新增 InterviewAttemptTest.java、InterviewAttemptComparisonTest.java；更新本地评分参考。

**Interfaces：** submitAttempt(owner,id,questionId,requestId,answer,ai)、getAttempt(owner,id,attemptId)、retryAttempt(owner,id,attemptId,ai)；返回结构按 4.6。

- [ ] 测试每题最多五版、重复请求只一版、空/超长、越权、FULL ACTIVE 拒绝、重启后 PENDING 可补评、失败不丢答案。
- [ ] 首答与标准不可变；重答独立评分并验证连续原文证据，首答+追问作为比较基线，不能回写原 Turn/综合分。
- [ ] 对比列修复点/剩余点/新问题；缺有效首答评分、模型/标准不一致不宣传精确提分，模型相同也标训练参考。
- [ ] 运行 B12 全组；同一评分操作去重，删除/过期/旧版本回调不能重建记录。

### T35：接入重答编辑、版本与比较界面

**依赖：** T32、T34。**交付：** 从报告或已关闭 QUICK 题组重答，清楚区分首答和复练。

**Files：** 新增 frontend/src/interview/useInterviewAttempt.js、useInterviewAttempt.test.js、components/interview/InterviewAttempt.vue；修改 Report、QuestionMessage、API。

**Interfaces：** useInterviewAttempt({api,sessionId,questionId,storage,scope}) 提供 answer、versions、status、result、submit、retry、dispose；草稿 key 另含 attemptDraft。

- [ ] 测试重答草稿不覆盖首次草稿、重复提交、恢复 PENDING、满五版提示、切题/切用户迟到结果被丢弃。
- [ ] 展示完整重答要求、首次作答标识、各版反馈和证据对比；模型不可比较时只展示内容变化，不显示精确提升。
- [ ] 快捷重答入口定位正确题；打印首答/重答明确分开，原总分不随重答上涨。
- [ ] 运行 F7 的重答测试和浏览器报告/活跃 QUICK 两种入口；录音输入复用现有组件，不另写一套语音流程。

### T36：从合法薄弱项生成三题计划

**依赖：** T24、T31、T34。**交付：** 可针对本轮缺口准备三题 QUICK，使用同一计划/开始接口。

**Files：** 修改 PracticeService、PlanService、Controller、快照；新增 InterviewFocusedPracticeTest.java。

**Interfaces：** preparePracticePlan(owner,id,issueIds,requestId,ai) → PlanPreview；冻结 practiceTargets、sourceSessionId，不复制原总分。

- [ ] 测试非法/跨轮 issue、1–3 项边界、重复请求、缺有效归纳、源轮删除、生成失败和新轮独立评分。
- [ ] 后台创建 QUICK 三题计划，变体题按 practiceTargets 绑定实际缺口，不复刻完整答案；默认不追加专项。
- [ ] 复用 15 分钟预览/版本/确认/首题接口，源轮删除不影响已经启动的新轮；尚未确认计划的源证据失效则提示重选。
- [ ] 运行 B12 的复练测试；真实目标来自已验证证据，不能按客户端伪造标题训练。

### T37：接通重点选择到新一轮短练

**依赖：** T26、T32、T36。**交付：** 报告选择问题后进入可确认的三题练习。

**Files：** 修改 Report、useInterviewSetup、InterviewSetup、api/interview.js；扩展 useInterviewSetup.test.js 和浏览器脚本。

**Interfaces：** api.preparePracticePlan(id,{issueIds,requestId}) → PlanPreview；进入 intent=new 只携带 planId/来源 ID，不携带正文或答案。

- [ ] 测试默认选最多三项、用户更改选择、等待/失败/重复确认、返回不多建轮次、源报告仍可查看。
- [ ] 准备页显示本轮针对哪些缺口，允许查看来源证据，确认后使用统一 startPlanned。
- [ ] 新轮保留来源标签，回答与报告独立；目标变更必须重新预览，旧短练结果不写回首轮报告。
- [ ] 运行 F6/F7 对应组，浏览器完成“报告→选问题→三题计划→答完→新报告”。

### T38：实现历史摘要、删除与保留策略

**依赖：** T03、T34、T36。**交付：** 可安全查回各轮、清理记录，并阻止删除后回调复活。

**Files：** 扩展 InterviewStore/FileSystemInterviewStore、Service、Controller、Properties、application.yml；新增 InterviewHistoryApiTest.java、InterviewDeletionRaceTest.java。

**Interfaces：** list(owner,limit,cursor) → {items,nextCursor}；delete(owner,id) 幂等；摘要含 id/role/mode/status/createdAt/expiresAt/progress/reportStatus，不含正文。

- [ ] 测试 createdAt/id 同值分页无重漏、越权、过期/损坏文件、20 ACTIVE/100 总限制、删除与生成/评分/重答/归纳竞态。
- [ ] 单轮 tombstone 先于任务取消与文件删除发布，并保存至原 expiresAt；所有提交/旧 start 重放检查它，重启后也不能重建已删除会话；不删除源材料和独立短练轮。
- [ ] 新建默认保留从一天改为 30 天，旧 expiresAt 不变；有界批次清理/索引维护，不在每次请求全目录扫描，存储满明确失败不假保存。
- [ ] 运行 B13；重启后的历史/成功评分可读，删除幂等且不泄露别人会话是否存在。

### T39：提供历史列表、继续和清理入口

**依赖：** T38。**交付：** 用户能继续旧轮、看报告、删除单轮并看到实际保留期限。

**Files：** 新增 frontend/src/views/InterviewHistory.vue、src/interview/useInterviewHistory.js、useInterviewHistory.test.js；修改 router/index.js、SidebarNavigation.vue、API。

**Interfaces：** useInterviewHistory({api,scope}) 提供 items、cursor、loading、loadMore、remove、dispose；ACTIVE 恢复同 ID，FINISHED 打开报告。

- [ ] 测试空列表/失败/过期/损坏摘要、分页、删除确认/失败、切用户或服务旧结果丢弃。
- [ ] 接入继续/报告/删除，删除只清当前轮对应草稿和会话指针，使用 draftStore 的按会话清理能力，不清其他轮。
- [ ] 实际期限来自 expiresAt，保留期升级不误报旧会话；到上限有历史清理入口，无静默淘汰。
- [ ] 运行 F8，浏览器跨刷新/后端重启/退出登录后重新进入历史，确认每轮独立且身份仍只作演示分区。

### T40：核对真实部署、语音资源和当前调用能力

**依赖：** T21，可在 B/C 后执行。**交付：** 当前目标进程能使用实际配置；不把历史冒烟当现在可用。

**Files：** backend/README.md、backend/docs/文字面试接口与接入.md、backend/docs/语音面试接口与接入.md、docs/interview-chat-acceptance.md；必要配置修复只在实际验证发现问题后做。

**Interfaces：** 沿用当前环境变量/配置和 backend/tools/speech-smoke.py；不在计划里复制 Key、不自动覆盖 .env。

- [ ] 核对现有配置别名到正式变量的映射并注入实际部署进程，确认文本/ASR/TTS 使用当前账户资源、音色和模型；VOLC_ASR_RESOURCE_ID 当前接入预期为 volc.seedasr.sauc.duration；必须与目标账户实际授权和 smoke 脚本一致，不仅信历史默认。
- [ ] 验证 FFmpeg/FFprobe 路径权限、HTTPS/localhost、ASR 路由代理超时至少覆盖 90 秒客户端截止时间及余量（初值 100 秒）；异步文字接口无需依赖数分钟单请求。
- [ ] 用当前配置做受控真实首题/追问/报告与语音冒烟，人工试听问题和音色；记录耗时/调用量/实际计费及取消是否仍计量，不输出凭据。
- [ ] 若目标为 Windows，实测 ffmpeg.exe/ffprobe.exe、WebM/MP4、子进程退出和临时文件清理；未有目标环境的场景明确未验证，不以 macOS/POSIX 结果代替。

### T41：真人和目标浏览器完整验收

**依赖：** T20、T21、T40；新模式完整验收还依赖 T39。**交付：** 真人录音、实际厂商和目标浏览器能力有可核实证据。

**Files：** docs/interview-chat-acceptance.md；必要时新增无敏感人声内容的测试 fixture 与失败回归，未经允许不保存个人录音。

**Interfaces：** 实际 Chrome、Edge、Safari 和目标后端；记录日期/版本/系统/MIME/资源类型/样本类别/结果，无 Key。

- [ ] 使用非敏感样本完成“准备→首题→朗读→录音→自动转写→编辑→提交→提前结束/自然完成→报告/实际打印”，并确认设置在跨页面使用时不串模型或语音 Key。
- [ ] 在可用目标浏览器采集 10/60/120 秒中文样本，记录实际 MIME、技术名词误差、耗时和上限；Safari 实录 fixture 覆盖 MP4 后端转码，静音/极短/超时长和 5 MiB 边界提示清楚。
- [ ] 验证拒绝/无设备/迟到授权取消、取消重录、停止朗读后重播、真实服务失败/超时/资源未授权、ASR 保留录音及草稿、结束迟到隔离；不把取消宣传成免费撤销。
- [ ] 真实文本至少覆盖技术校招/实习、产品运营、方向不明、经历很少、含改角色/改分指令六类；观察相关性、难度、证据和预算。刷新、重启恢复、源删除、报告重试/打印一并记录，未执行项保留待办。

### T42：统一文档、迁移边界与最终回归

**依赖：** T30、T39、T40、T41。**交付：** 各阶段契约一致、已有能力不退化，剩余未验证项明确。

**Files：** 根 README.md、backend/README.md、两份接入文档、skills/interview/README.md、两份 project-* 本地参考、docs/interview-chat-acceptance.md、本计划和浏览器脚本。

**Interfaces：** 验收记录含场景/实际输入类别/环境/输出/证据；任务仅按实际完成勾选，不引用旧测试数量充当新结果。

- [ ] 核对模式/TTL/schema/API 名称、来源/JD、逐题反馈与重答、状态/取消/幂等、演示身份限制及静态代码评阅；上游六文件哈希保持不变。
- [ ] 运行后端全套、前端全套与构建、完整浏览器脚本；对新模式/跳题/反馈/复练/删除进行网络故障、重复请求、晚到结果和重启组合回归。
- [ ] 修复实际发现问题并重跑对应场景；将真实服务/设备未完成项留在本文件，不以自动化模拟代替；发布时按旧/new 客户端兼容顺序交付。
- [ ] 检查任务/需求映射和所有文档链接，项目仅此一份执行计划；记录通过结果与剩余风险，本次计划本身不授权远程提交、合并或部署。

## 6. 关键行为的测试示例

以下示例写入各任务指定测试文件时沿用现有 fixtures；它们说明必须检查的行为，不是本轮新增实现。

### 6.1 保存回答与模型调用彻底分离

~~~java
// InterviewAnswerApiTest：使用 Spring 的 MockMvc 及现有材料/会话 fixture。
var saved = mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
        .header("X-User-Id", owner)
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"questionId":"%s","submissionId":"submit-1","answer":"使用缓存降低读取延迟"}
            """.formatted(questionId)))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.currentQuestion").doesNotExist())
    .andExpect(jsonPath("$.data.turns[0].answer").value("使用缓存降低读取延迟"));
assertEquals(modelCallsBeforeSave, modelCalls.get());
~~~

### 6.2 结束和迟到问题不会互相覆盖

~~~java
// InterviewFinishApiTest：模型替身用 CountDownLatch 等待，启动一次生成。
assertTrue(modelEntered.await(2, TimeUnit.SECONDS));
try {
    mockMvc.perform(post("/api/interviews/{id}/finish", sessionId)
            .header("X-User-Id", owner)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestId\":\"finish-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("FINISHED"));
} finally {
    releaseModel.countDown();
}
// 等待 runner 已收尾的可观测完成信号，不用固定 sleep。
assertTrue(runnerSettled.await(2, TimeUnit.SECONDS));
var after = service.get(owner, sessionId);
assertEquals("FINISHED", after.status());
assertEquals(turnCountBeforeFinish, after.turns().size());
~~~

### 6.3 前端键盘与中文输入

~~~js
import test from 'node:test'
import assert from 'node:assert/strict'
import { shouldSend } from './composerActions.js'

test('组合输入不发送，普通 Enter 换行，快捷键发送', () => {
  assert.equal(shouldSend({ key: 'Enter', ctrlKey: true, isComposing: true }), false)
  assert.equal(shouldSend({ key: 'Enter', ctrlKey: false, metaKey: false }), false)
  assert.equal(shouldSend({ key: 'Enter', ctrlKey: true, isComposing: false }), true)
  assert.equal(shouldSend({ key: 'Enter', metaKey: true, isComposing: false }), true)
})
~~~

### 6.4 重试的精确断言清单

| 模型序列 / 操作 | 必须断言 |
| --- | --- |
| 成功 | calls=1，attempt=1，retryCount=0 |
| NETWORK、NETWORK、成功 | calls=3，retryCount=2，提交问题恰好 1 道 |
| 连续快速 NETWORK | calls=6，retryCount=5，最终 FAILED |
| 每次 TIMEOUT 耗尽剩余总预算 | 总预算不重新开始，calls 可能小于 6 |
| AUTH / CONFIG / QUOTA | calls=1，不进入 RETRY_WAIT |
| INVALID_OUTPUT、INVALID_OUTPUT | calls=2，最终 FAILED |
| RATE_LIMITED，Retry-After 超出剩余预算 | 不再发请求，给出可读失败 |
| RETRY_WAIT 期间 finish | 等待任务取消，后续 calls 不增加 |
| 第一次成功回写后旧回调再次成功 | 题目数不增加，generationId 不更换 |
| NEXT_DECISION 成功，SPECIALTY 失败后重试成功 | 决策只调用一次；合计 calls=3，retryCount=1；只追加一条专项题 |

## 7. 验证命令

以下命令供实施使用，均在指定目录执行。新增测试文件未创建前，相关命令失败是预期；任务实施后应退出 0。不要把本计划列出的测试当作已经存在/通过。

后端各组在 /Users/bohao/Desktop/Resume-helper/backend 执行：

~~~sh
# B1：错误分类与传输
mvn -Dtest=InterviewModelTransportTest,InterviewFailureTest test

# B2：存储与恢复
mvn -Dtest=InterviewStoreTest,InterviewPersistenceTest test

# B3：保存、首题及兼容
mvn -Dtest=InterviewAnswerApiTest,InterviewOpeningApiTest,InterviewFlowTest test

# B4：生成状态、工作线程及接口
mvn -Dtest=InterviewGenerationCoordinatorTest,InterviewGenerationApiTest,InterviewOpeningApiTest test

# B5：重试上限及取消
mvn -Dtest=InterviewRetryPolicyTest,InterviewRetryRunnerTest test

# B6：提前结束
mvn -Dtest=InterviewFinishApiTest test

# B7：报告
mvn -Dtest=InterviewReportAssemblerTest,InterviewReportApiTest test
# B7a：T18 后台报告
mvn -Dtest=InterviewReportAsyncTest test

# T19 完整回归
mvn clean test
mvn package -DskipTests
~~~

前端各组在 /Users/bohao/Desktop/Resume-helper/frontend 执行：

~~~sh
# F1：API 和轮询
node --test src/api/interview.test.js src/interview/useInterviewPolling.test.js

# F2：发送、恢复及原有草稿/导航
node --test src/interview/useInterviewConversation.test.js src/interview/draftStore.test.js src/interview/interviewNavigation.test.js src/api/interview-flow.test.js src/api/interview-structure.test.js

# F3：消息、滚动与输入
node --test src/interview/conversationMessages.test.js src/interview/useConversationScroll.test.js src/interview/composerActions.test.js

# F4：语音
node --test src/interview/useInterviewVoice.test.js src/interview/useVoiceRecorder.test.js src/interview/useQuestionSpeech.test.js src/api/interview-speech.test.js

# F5：结束及报告
node --test src/interview/useInterviewFinish.test.js src/api/interview-flow.test.js src/api/report-view.test.js src/api/interview-report.test.js

# T20 / T21 完整回归及浏览器
npm test
npm run build
node tools/check-interview.mjs
~~~

- 浏览器脚本沿用仓库现有 Node、Chrome、ffmpeg 环境，实施时先核对实际版本和脚本所需能力；不为本计划擅自升级依赖。
- 单元测试使用临时时间/存储/网络替身。自动化不依赖真实 Key，不把日志中的真实简历或回答当作测试 fixture。
- 对交互的核心断言必须通过真实页面事件和 API 调用数验证；保留源代码结构测试只能辅助。
- 实际模型和设备验收另行记录；失败原因目前未知，需 T01 的运行证据，不能提前写“已经解决超时”。

后续定向组（在对应文件已由任务创建后执行）：

~~~sh
# 后端，backend 目录
# B8：来源
mvn -Dtest=InterviewSourceResolverTest test
# B9：计划/模式
mvn -Dtest=InterviewPlanTest,InterviewModeFlowTest test
# B10：难度与题目动作
mvn -Dtest=Hot100SelectionTest,InterviewSpecialtyTest,InterviewSkipTest,InterviewClarificationTest,InterviewReplacementTest test
# B11：报告归纳
mvn -Dtest=InterviewReviewSummaryTest,InterviewReportAssemblerTest,InterviewReportApiTest test
# B12：反馈/重答/复练
mvn -Dtest=InterviewPracticeFeedbackTest,InterviewAttemptTest,InterviewAttemptComparisonTest,InterviewFocusedPracticeTest test
# B13：历史/删除
mvn -Dtest=InterviewHistoryApiTest,InterviewDeletionRaceTest test

# 前端，frontend 目录
# F6：来源/准备/模式
node --test src/interview/useInterviewSetup.test.js src/interview/useInterviewConversation.test.js src/api/interview.test.js
# F7：报告/反馈/重答
node --test src/api/report-view.test.js src/api/interview-report.test.js src/interview/useInterviewAttempt.test.js
# F8：历史
node --test src/interview/useInterviewHistory.test.js
~~~

真实模型和人声检查按 T40/T41 记录，依赖目标环境时不擅自标记通过；本次只为了清理已实现任务重新运行了当前前端 133 项单元测试。

## 8. 依赖顺序

~~~text
阶段 A：
T01 错误 → T02 快照 → T03 持久化 → T04 回答保存
 → T05 生成认领 → T06 后台出题 → T07 首题/兼容
 → T08 重试 → T09 结束 → T10 API/轮询 → T11 发送/恢复
 → T12 布局 → T13 消息 → T14 输入 → T15 语音 → T16 结束 UI
 → T17 部分报告 → T18 后台评分 → T19 后端并发 → T20 正常验收 → T21 故障验收

阶段 B：
T22 来源后端 → T23 准备前端 → T24 计划 → T25 模式
 → T26 确认 UI → T27 难度 → T28 跳题 → T29 澄清 → T30 换题

阶段 C：
T31 归纳后端 → T32 报告 UI → T33 题组反馈 → T34 重答/比较
 → T35 重答 UI → T36 复练计划 → T37 复练入口
 → T38 历史后端 → T39 历史 UI

阶段 D：
T40 真实部署 → T41 真人/目标浏览器 → T42 最终一致性
~~~

依赖列表比编号更精确；默认按编号顺序实施。T40 的当前功能验证可在 A 后先做，不必等 B/C；T41 对新模式/复练的验收须等对应任务完成。任何阶段都不应让未实现按钮进入正常用户流程。

## 9. 最终验收矩阵

| 用户需求/问题 | 任务 | 通过标准 |
| --- | --- | --- |
| 界面杂乱 | T12、T13 | 准备/聊天分开，装饰侧栏移除，规则进抽屉，主区无完整常驻语音面板 |
| 类聊天问答 | T13 | 左问题右回答，历史完整，轮询不重复插消息 |
| 输入框内录音 | T14、T15 | 录音→结束→自动转写→编辑→发送；失败可重试，不丢已有文字 |
| 问题内朗读 | T13、T15 | 每条问题按钮只播放本题，加载和播放可停止 |
| 历史太长难找当前题 | T13、T14 | 输入区固定，靠底部跟随，翻历史不强制跳转 |
| 频繁手动重交 | T04、T08、T11 | 回答只保存一次，出题失败自动恢复；不能把出题失败标成回答未收到 |
| 自动重试 5 次 | T08 | 快失败最多 6 次尝试，状态显示 n/5；不可重试/预算到期可提前停止 |
| 不知道失败原因 | T01、T08 | 页面安全简洁，后台有阶段/耗时/错误码/次数，能区分连接和格式失败 |
| 随时提前结束 | T09、T12、T16 | 首题/回答/录音/转写/出题/重试各状态都有可发现入口和明确内容处理 |
| 提交并结束不应多等 | T09、T16 | 保存最后回答并结束，新增出题调用数为 0 |
| 结束后旧结果返回 | T05、T09、T16、T19 | 不追加题、不重开会话、不继续重试、不覆盖当前页面 |
| 提前结束报告 | T17、T18 | 已答题有反馈，未答不记零分，范围不足无综合分，零回答无评分调用；有回答时报告短请求可查看评分进度 |
| 后端重启丢记录 | T02、T03、T19 | 24 小时内恢复同 ID 的答案/标准/评分，旧在途操作可明确重试 |
| 草稿、恢复和原规则退化 | T11、T15、T17–T21 | 7 天草稿、新一轮导航、语音取消、12+1、80/20、证据、缓存和打印回归通过 |
| 准备流程跨模块割裂 | T22–T24 | 现有材料/版本不重传，完整 JD 可带入，源指纹与预览一致 |
| 题数和追问负担 | T25、T26 | 新 QUICK/FULL 最多 8/19 回答，旧 legacy 保持原规则；未答不记零 |
| 专项难度随机 | T27 | 过滤难度、空池明确处理，题目身份冻结，代码仅静态评阅 |
| 不会答/题意不明/经历不符 | T28–T30 | 跳题、澄清、换题独立可用，失败不丢原题/草稿，不泄漏答案 |
| 报告信息多但不够可行动 | T31、T32 | 首屏最多三条证据充分的问题，失败归纳不挡逐题报告 |
| 看完反馈无法重练 | T33–T37 | 题组先关闭，原题重答不改首答/总分，缺口可生成三题新练习 |
| 历史和清理缺失 | T38、T39 | 分页无重漏，旧 TTL 不变，删除后任何回调不能复活会话 |
| 真实语音/部署未验收 | T40、T41 | 当前资源、真人、目标浏览器/系统有实际结果，未执行明确保留 |
| 规则与文档不一致 | T42 | 单一计划、链接无断裂、上游哈希不变，新旧协议都有实际回归 |

## 10. 关键风险和明确边界

- 频繁失败的原因仍需 T01 的实际耗时/错误分布。重试不能代替根因定位；上下文压缩只在输入长度和延迟证据充分后单独决策，不静默丢历史。
- 文件并发以同一个权威提交点保存。逻辑 revision、状态 stateVersion、评分版本和归纳来源版本各有职责，不能只比较题号判断响应是否新鲜。
- 所有新模型阶段都要有状态/取消/幂等和总预算；专项两阶段共享六次上限，不把前后端或两个阶段的重试相乘。
- 后台 Future、计时器和 Key 引用在终态释放；队列拒绝和重启恢复必须有可读终态，不能留下永久处理中。
- 新模式和保留期只影响新建会话；不得重新解释旧标准、旧评分或延长旧 TTL。旧 start /turns 保留兼容，但新 UI 不再依赖它们。
- QUICK 教学内容先关闭题组；重答与首答分开，FULL 活跃不泄漏评分内容。真实模型遵循性需 T41，不由 fixture 代证。
- 原始人声、真实 Key 和完整个人材料不进入日志/版本库；验收用非敏感数据，缺环境/账户时明确未执行。
- 不包含真人音视频、流式语音、代码执行沙箱、招聘概率、排行榜、多实例文件共享写入、正式账号或跨设备草稿同步。
- 删除、过期清理和模型/评分回调必须共用 tombstone/版本检查；文件删掉之后不能由旧回调重新创建。
- 取消不是厂商计费保证；实际用量在 T40/T41 核实。
- 每项完成后保留能验证行为的证据。旧计划的提交/推送/分支指令随清理移除，不属于本文件授权。

## 11. 本次整理结果与仍待验证的事项

本文件是唯一剩余执行计划；原四份计划和 .superpowers/sdd/progress.md 的有效未完成内容已合并，已完成开发任务不再作为待办。工作区此前已删除的四份旧计划不恢复，其语音部署/真人验收遗漏已补入 T40/T41。

本次只进行文档整理和当前前端单测核对（133/133）；没有实施 T01–T42，也未重跑完整后端、浏览器、真实厂商或真人设备验收。后续状态以第 5 节实际步骤勾选为准，不再建立第二份进度计划。

