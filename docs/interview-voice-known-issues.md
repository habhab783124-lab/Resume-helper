# 语音迁移问题记录

记录日期：2026-10-17。范围是 Resume-helper 当前工作区与 Hermes 9da6d455c 的桌面语音实现。本文记录问题，不代表已修复。迁移实施入口：[连续语音面试实施计划](superpowers/plans/2026-10-17-interview-realtime-voice-plan.md)。

用户已确认：ASR 已测试可用，ASR 与 TTS 共用语音 Key，配置在 .env；保留用户自带 Key 和当前身份分区，不把正式认证作为本次前置任务。不读取、展示或复制 Key。

| ID | 所属 | 严重度 | 证据状态 | 处理位置 |
| --- | --- | --- | --- | --- |
| V01 | Resume-helper | P1 | 已修复；新提示和结束恢复行为有测试 | `InterviewComposer.vue`、`Interview.vue`；T10 |
| V02 | Hermes | P2 | 实际分句器离线复现 | 本次不引入文本增量分句器；未来迁移时修复 |
| V03 | Hermes | P1 | Resume-helper 已用 epoch/abort/假设备迟到响应回归覆盖；未在 Hermes 桌面端复现 | `useInterviewVoiceConversation.test.ts`、`useVoiceCapture.test.ts`；T06/T08 |
| V04 | Hermes | P1 | Resume-helper 已覆盖统一采集器、暂停封存和重复释放；未在 Hermes 桌面端复现 | `useVoiceCapture.test.ts`、`useInterviewVoiceConversation.test.ts`；T08/T10 |
| V05 | Resume-helper | P2 | 已修复；请求时身份、API 与短期能力 Key 隔离有回归测试 | `requestContext.ts` 及 `requestContext.test.js`；T01 |
| V06 | Resume-helper | P1 | 已修复；响应体读取和解析均受整体截止时间及取消控制 | `requestDeadline.ts`、`requestDeadline.test.ts`；T01 |

## V01 上传时机提示与实际行为不一致

- 位置：frontend/src/components/interview/InterviewComposer.vue:44；frontend/src/interview/useInterviewVoice.js:43、115。
- 页面称“录音由你主动识别时发送至火山引擎”。实际 stopRecording 默认转写，录音上限触发 recorded 状态也会自动转写。
- 证据：useInterviewVoice.test.js 的 stopping a recording automatically creates a transcription preview once、recorder limit stop starts one transcription when its Blob arrives 均通过。
- 影响：用户对录音何时上传形成错误预期。自动转写本身是已有产品行为，不应为修文案而移除功能。
- 修复方向：手动录音模式明确“停止录音后自动上传并转写，确认后提交”；连续模式明确“语音分段上传，预览后倒计时提交，继续说话取消倒计时”。不得声称音频永不离开本机。
- 修复证据（2026-10-18）：页面已分别说明手动录音上传/转写、连续分段上传、续说取消倒计时和暂停保留回答；`interview-flow.test.js` 校验文案与结束后仍暂停。

## V02 Hermes 中文句子不能及时分段送入 TTS

- 位置：hermes-agent/tools/tts_streaming.py:85，SentenceChunker.feed。
- 规则只匹配英文 . ! ? 后的空白或空行，不识别 。！？。
- 复现：导入实际 SentenceChunker，仅隔离与分句无关的供应商配置依赖，依次 feed 两段超过 min_len 的中文完整句子，均返回 []；flush 才一次性返回两句。对应英文句子逐次 feed 可输出。
- 影响：连续中文增量输出会积压到结束或空闲强制刷新，降低首句出声速度；不是“中文完全无法朗读”。
- 处理：本次 TTS 输入采用已校验、已保存的整道题正文，使用火山音频分片输出，因此不引入此分句器。若未来增加文本增量朗读，需覆盖中文标点、英文缩写、小数、代码、跨增量标签和未闭合 think 块。

## V03 Hermes 语音结束后迟到转写仍有提交路径

- 位置：hermes-agent/apps/desktop/src/app/chat/composer/hooks/use-voice-conversation.ts:162、188、327、357、602。
- handleTurn 与 submitCapturedUtterance 等待 onTranscribeAudio 后，成功路径没有验证本轮是否结束或失效；end 清理录音和播放，但没有中止在途转写或使它的提交令牌失效。
- 触发路径：发起转写 → 点击结束语音 → 转写成功返回 → 仍进入 onSubmit。是否最终被上层拒绝取决于 busy 等状态，不能依赖它作为取消契约。
- 证据限度：静态调用链，尚未运行 Hermes 的 React hook 回归或真人设备验证。
- 迁移要求：每个回调绑定 scopeKey/sessionId/questionId/epoch/attempt，停止、切题、卸载先使令牌失效，再清理设备和请求；成功回调执行任何写草稿或提交前重新校验。

## V04 Hermes 静音未清理独立打断监听

- 位置：hermes-agent/apps/desktop/src/app/chat/composer/hooks/use-voice-conversation.ts:386、621。
- toggleMute 只调用普通录音 handle.cancel；独立 stopBargeMonitorRef 未在这条路径停止。其 onSpeech/onUtterance 路径也没有统一的静音失效检查。
- 影响：在模型生成或语音播放阶段静音时，打断监听仍可能持有麦克风并产生后续动作。
- 证据限度：静态调用链，未做 Hermes 桌面端复现。
- 迁移要求：连续模式只持有一个采集流；暂停收音必须停 tracks、关闭 worklet、取消倒计时与在途转写，保留未提交文字和可重试片段；恢复必须由用户主动触发。

## V05 前端丢失后端结构化错误码

- 位置：frontend/src/api/interview.js 的 request；backend/src/main/java/com/shijie/resumehelper/interview/InterviewErrorHandler.java 的 interview。
- 后端返回 {success:false,message,data:{code,retryable,message,retryAfterMs}}；前端却读取 envelope.code/envelope.retryable，且未传递 retryAfterMs。
- 离线复现：用 createInterviewApi 的 fetchImpl 返回 HTTP 429，data.code=RATE_LIMITED、data.retryable=true、data.retryAfterMs=1000；捕获错误的 status=429，但其余三个字段均为 undefined。
- 影响：调用方无法可靠区分限流、配置失败、可重试故障；语音状态机会失去恢复依据。现有部分前端测试用了顶层 code 的伪响应，未覆盖真实封装。
- 修复：优先读取 envelope.data；可兼容旧顶层字段，保留 retryAfterMs。用真实后端响应形状做契约测试，不改现有 ApiResponse 包装。

## V06 前端短请求截止时间没有覆盖响应体消费

- 位置：frontend/src/api/interview.js 的 request：fetch 的 finally 清理 timer 与外部 abort 监听，然后才 await reply.json()。
- 离线复现：read timeout=5ms；fetch 立即返回响应头，ReadableStream 延迟约 60ms 才提供 JSON。getSession 最终约 61ms 成功，未触发 BROWSER_UNCONFIRMED。
- 影响：代理或服务端返回头后停住，读取会话、提交回答等操作可能超过截止时间；外部 abort 在读 body 阶段也已解除关联。若把这个封装照搬到音频流，会产生长期卡住。
- 修复：截止时间与外部取消必须覆盖 fetch、消费 body、解析、校验全过程；Promise.race 保证忽略 signal 的测试传输也能结束等待，同时 abort 真实传输并取消 reader。未知写入结果继续由现有会话读回确认，不能推断“未保存”。

## 已识别的迁移约束

以下是已有设计与新模式的适配工作，不单独标成现有 bug：

- 现有语音每 owner 只有一个并发槽；朗读与 ASR 重叠需要拆成 ASR/TTS 两个槽，继续保留全局并发上限。
- 现有 TTS 将分片拼接成完整 MP3；是现有文件播放契约，新增流式路径，不改变旧接口。
- 现有题目生成是完整结构化结果，含标准等字段；只朗读持久化题目正文，不播模型原始输出。
- Hermes 收音 60 秒、打断录音 30 秒、静音 1.25 秒；这些是聊天参数，不直接作为面试答案提交规则。
- 当前轮询最多间隔 5 秒，会增加下一题显示/朗读等待；连续模式使用单个轮询器的较短间隔，不并行再建一个循环。
- .env 中有值不等于任意 Java 启动命令自动读取它。用户已验证现有链路，本次保留已使用的启动注入方式；部署说明记录启动入口，不打印变量值，不重新索要 Key。
- 原统一计划有“自动追加草稿”的设计，而当前普通录音仍显式接受预览。迁移仅让连续模式按新确认规则追加与自动提交；普通录音既有交互不在本次顺便重写。

## 验证记录

2026-10-18 已执行：

- 本轮前端全量测试 261/261 通过；另有静音、30 秒切段、seal 屏障、同一提交重试、不同 payload 并发拒绝、页面隐藏后旧 WS 音频隔离、dispose 时活动票据关闭和迟到票据关闭等回归。
- 前端 typecheck、生产构建以及 Chrome 假麦克风 AudioWorklet 开发/生产预览冒烟通过；截图与 JSON 结果见 `docs/interview-voice-artifacts/`。
- 后端使用 `-Dnet.bytebuddy.experimental=true` 运行全量测试，407/407 通过；真实 WebSocket 测试覆盖无 hello 超时、错票、一次性票据重复消费、非法 Origin、错误题、客户端二进制拒绝、活动流期间第二次 start 被拒绝、队列占满时客户端收到 speech.started 前发送 cancel 且不上游，以及 ACK 窗口阻塞后取消唤醒、唯一终态和断线清理。票据 registry 测试也确认跨 owner/session 关闭拒绝且不会删票、默认 32/4 限额与无语音 Key 时关闭能力。当前 WebSocket 集成类 10 项、registry 类 4 项、capabilities 类 1 项通过。验收记录见连续语音验收文档。
- V03/V04 的生命周期防护已由本仓库注入延迟响应和假设备回归覆盖；未在 Hermes 桌面端复现，也不以此证明真人设备行为。
- 真人耳机/扬声器、当前供应商账号、线上代理与 Safari/iOS 验收仍未执行；见 `docs/interview-voice-acceptance.md`。

修复后在对应条目补充提交或变更标识、定向回归命令、实际结果；没有验证前不改成已修复。
