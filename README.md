# Resume-helper · 拾阶求职工作台

面向应届毕业生的求职准备课程项目，围绕个人资料、简历、岗位探索和模拟面试组织前端页面。

## 仓库结构

- frontend/：Vue 3 前端工程，包含登录页和工作台首页。
- prototype-static/：独立 HTML、CSS、JavaScript 原型，展示完整的七个产品页面和交互流程。
- preview-server.cjs：用于本地预览静态原型的 Node.js 小型服务器。
- AI求职与面试平台_20天课程项目方案.md：产品与实现方案。

## 运行 Vue 前端

进入 frontend/ 目录后依次运行 npm install 和 npm run dev。终端会显示本地开发地址。

## 预览完整静态原型

在仓库根目录运行 node preview-server.cjs，然后打开 <http://127.0.0.1:4173>。该原型无需安装 npm 依赖。

## 原型说明

完整静态原型的登录、材料、岗位、AI 建议和面试报告使用演示交互与样例数据，尚未连接账号、Java 后端、大模型、真实招聘源或 TTS 服务。文件选择仅在页面展示文件名，不会上传或解析；语音试听使用浏览器内置语音能力。

后续可由 Java / Spring Boot 提供 HTTP API，逐步接入登录、材料解析、简历生成、岗位匹配与面试服务。
