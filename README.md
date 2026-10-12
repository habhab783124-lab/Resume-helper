# Resume-helper · 拾阶求职工作台

面向应届毕业生的求职准备课程项目，围绕个人资料、简历、岗位探索和模拟面试组织前端页面。

## 仓库结构

- frontend/：Vue 3 + JavaScript 前端工程，包含登录入口、工作台及六个求职功能页面。
- backend/：Spring Boot 后端，包含岗位检索匹配和简历驱动的文字面试。
- prototype-static/：独立 HTML、CSS、JavaScript 原型，展示完整的七个产品页面和交互流程。
- preview-server.cjs：用于本地预览静态原型的 Node.js 小型服务器。
- AI求职与面试平台_20天课程项目方案.md：产品与实现方案。

## 运行 Vue 前端

进入 frontend/ 目录后依次运行 npm install 和 npm run dev。终端会显示本地开发地址。

Vue 页面按路由拆分在 `frontend/src/views/`；公共工作台布局、侧边导航、顶部栏和图标分别放在 `layouts/` 与 `components/`。

## 预览完整静态原型

在仓库根目录运行 node preview-server.cjs，然后打开 <http://127.0.0.1:4173>。该原型无需安装 npm 依赖。

## 文字模拟面试

模拟面试页已接入后端：上传 PDF/DOCX/TXT 简历或粘贴文本、确认提取内容、AI 提问与追问、刷新恢复、结束面试。本期为文字问答，不含 TTS 和 AI 面试报告。

先在 `backend/` 配置 `AI_API_KEY`，运行 `mvn spring-boot:run`；再启动 Vue 前端。默认模型为 `deepseek-chat`，可用 `AI_BASE_URL` 和 `AI_MODEL` 切换兼容接口，也可在面试页填写个人密钥。Vite 已代理 `/api` 到后端 8080 端口。

完整接口、运行步骤和部署限制见 [文字面试接口与接入](backend/docs/文字面试接口与接入.md)。面试数据暂存在内存中，后端重启失效；浏览器演示登录尚非正式账号认证。

## 其他页面说明

除 Vue 文字面试页外，其他业务页面主要用于展示，尚未完成对应后端联调。独立静态原型中的交互与样例数据仍仅用于展示。

后续需补齐正式账号、通用资料管理、简历生成、岗位前端联调与面试报告。
