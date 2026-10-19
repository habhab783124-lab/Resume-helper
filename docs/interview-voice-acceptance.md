# 实时语音面试验收记录

更新日期：2026-10-18

## 自动检查

| 检查 | 环境/命令 | 结果 |
| --- | --- | --- |
| 前端全量测试 | Node.js 24.16.0，`cd frontend && npm test` | 263 项通过；新增跳过草稿选择与换题草稿处理交互覆盖 |
| 类型检查与生产构建 | `cd frontend && npm run typecheck && npm run build` | 通过；生产产物包含 `voiceCapture.worklet-*.js` |
| 浏览器语音冒烟 | Chrome 无头模式、Chrome 假麦克风、确定性 440 Hz PCM/WAV；`cd frontend && node tools/check-interview-voice.mjs` | 开发/生产预览均通过 AudioWorklet 加载、连续 PCM 帧、seal 屏障和麦克风释放；本地 HTTP 票据 + 真实浏览器 WebSocket stub 验证 hello、TTS start、说话打断、暂停和票据关闭 |
| 后端全量测试 | `cd backend && mvn -Dnet.bytebuddy.experimental=true test` | 407 项通过，0 失败、0 错误 |
| WebSocket 集成边界 | JDK WebSocket + 本地 Spring Boot 测试服务器 | 无 hello 超时、错票、一次性票据重复使用、非法 Origin、过期题目、客户端二进制帧、活动流期间拒绝第二个 start、客户端收到 `speech.started` 前发取消并移除排队请求，以及 ACK 窗口阻塞时取消或断线均能唤醒上游；票据 registry 另验证跨 owner/session 关闭均拒绝 |

Chrome 假设备冒烟不访问语音供应商，不保存音频、用户回答、Key 或票据。结构化结果和两张截图保存在 [`interview-voice-artifacts`](interview-voice-artifacts/)。

## 真人设备与供应商验收

以下项目需要启用测试环境的语音开关和用户自己的语音服务配置，并在真实浏览器中操作。目前尚未执行：

- [ ] Chrome/Edge + 耳机：至少 5 道题；确认题目播放立即开始、说话可打断、自己的声音不会触发误提交。
- [ ] Chrome/Edge + 扬声器：至少 5 道题；记录回声误触发和阈值调整，不保存原始用户音频。
- [ ] 说话停顿、2.9 秒内续说、30 秒长答切段、ASR 失败重试与片段丢弃。
- [ ] 页面级真实 UI 的边播边到、倒计时、取消结束、断线恢复、Key 变化、QUICK/ALGORITHM 与移动布局。当前浏览器 stub 只覆盖控制器的 ticket HTTP、WebSocket 握手/TTS start、打断和暂停清理。
- [ ] FULL 自动出下一题、QUICK 等待用户选择、ALGORITHM 不自动提交代码、CASE_STUDY 连续模式。
- [ ] 结束确认取消、切题、后台标签、用户/API/语音 Key 变化后，确认草稿与片段按预期保留且旧连接关闭。
- [ ] 连续启停 20 次，确认麦克风、音频节点、计时器、WebSocket 和供应商流均释放。
- [ ] Safari/iOS 设备能力和回退路径（若作为支持目标）。

## 代理与部署要求

- WebSocket 将 `/api/interview-voice/ws` 与现有 `/api` 路由转发到同一后端，并保留 HTTP Upgrade/Connection 头；不要覆盖或替换其他 `/api` 路由。
- 开发代理 `/api` 必须开启 WebSocket 转发（Vite `ws: true`）；生产环境只使用 WSS。
- WebSocket Origin 必须匹配服务端允许的来源配置；MVC CORS 不会自动配置 WebSocket Origin。
- 代理和负载均衡器的空闲连接超时至少 60 秒，以容纳 ping/pong 保活；应转发关闭帧并允许服务端及时终止流。
- `app.interview.voice.enabled` / `INTERVIEW_VOICE_ENABLED` 默认关闭。关闭服务端开关时，需关闭已有连续语音连接；客户端 capabilities 不可用时回退现有手动语音/文字流程。

## 回滚

关闭 `app.interview.voice.enabled`（环境变量 `INTERVIEW_VOICE_ENABLED=false`）即可关闭连续 PCM/WebSocket 功能。回滚后仍保留旧手动录音和文字面试路径；客户端应继续保留未提交草稿。
