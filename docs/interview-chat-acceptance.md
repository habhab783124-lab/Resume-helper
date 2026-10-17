# 面试聊天正常流程验收

**验收日期：** 2026-10-17  
**结果：** 通过，Chrome 浏览器脚本 70 项断言全通过。  
**运行方式：** `cd frontend && CHECK_ARTIFACT_DIR=/private/tmp/interview-artifacts node tools/check-interview.mjs`

## 环境与范围

- 本地 Vite 页面、项目内 mock API 和 Chrome 假麦克风；无真实简历、账号或厂商凭据。
- 桌面视口 1440×900；窄屏视口 390×844。
- 检查准备、会话恢复、文字与语音回答、自动转写、朗读、追问、算法/专业案例专项题、结束选项、完整/部分报告、评分重试、打印/PDF 导出及本地草稿。
- 页面朗读和语音识别由本地 mock 服务模拟，故不代表实际厂商、真实麦克风权限或设备兼容性。

## 结果摘要

- 70 项浏览器断言全部通过；脚本的 `validation.json` 保存完整断言列表。
- T20 桌面与移动端布局通过，无横向溢出；结束弹窗与信息抽屉的 Escape 关闭和焦点返回通过。
- 保存回答后出题失败保留回答；用户结束不会追加出题；算法题与非编码专业案例均完成 13 个主问题，追问不计数。
- 报告评分、部分失败重试、打印/PDF 导出和草稿恢复路径通过。
- `npm test`：168 项通过；`npm run build`：通过。
- 后端 `mvn -q -DargLine=-Dnet.bytebuddy.experimental=true test package`：272 项测试及打包通过。

## 证据文件

- [桌面面试页](interview-chat-artifacts/interview-desktop.png)
- [移动端面试页](interview-chat-artifacts/interview-mobile.png)
- [算法专项题](interview-chat-artifacts/algorithm-question.png)
- [专业案例题](interview-chat-artifacts/professional-case-question.png)
- [专业案例报告](interview-chat-artifacts/professional-case-report.png)
- [移动端加权报告](interview-chat-artifacts/weighted-report-mobile.png)
- [打印 PDF](interview-chat-artifacts/interview-report.pdf)
- [70 项浏览器断言结果](interview-chat-artifacts/validation.json)
