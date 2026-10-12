# 拾阶 · 求职工作台前端

本目录包含 Vue 3 前端页面。当前阶段以静态页面展示为主，登录入口仅用于进入演示工作台；上传、AI 简历修改、岗位匹配、语音面试等业务暂未连接后端。

## 本地运行

需要 Node.js 18 或更新版本。

```bash
npm install
npm run dev
```

启动后打开终端输出的本地地址，通常为 `http://localhost:5173/`。

## 页面结构

```text
src/
├── assets/
│   └── career-studio.css       # 从静态原型迁移的全局视觉样式
├── components/
│   ├── IconLibrary.vue         # 公共 SVG 图标
│   ├── SidebarNavigation.vue   # 工作台侧边导航
│   └── TopBar.vue              # 页面标题与顶部栏
├── layouts/
│   └── AppShell.vue            # 登录后的公共工作台布局
├── router/
│   └── index.js                # 页面路由
└── views/
    ├── Login.vue
    ├── Dashboard.vue
    ├── Profile.vue
    ├── Materials.vue
    ├── Resume.vue
    ├── Jobs.vue
    ├── Interview.vue
    └── Report.vue
```

界面内容和视觉风格来自仓库中的 `prototype-static` 原型。Vue 版将工作台、个人资料、材料整理、简历、岗位匹配、模拟面试和面试报告拆成独立路由页面，方便后续逐个接入 Java 后端与业务逻辑。

## 发布为浏览器可访问的网页

仓库已包含 `vercel.json`，用于让刷新 `/dashboard`、`/jobs` 等前端路由时仍返回应用入口。

1. 将代码推送到 GitHub。
2. 登录 Vercel 并用 GitHub 导入 `Resume-helper` 仓库。
3. 导入设置中将 **Root Directory** 设为 `frontend`，构建命令设为 `npm run build`，输出目录设为 `dist`。
4. 点击 **Deploy**。部署成功后会得到一个 `*.vercel.app` 地址；连接 GitHub 后，之后推送新提交会自动重新部署。

Vercel Hobby 目前提供免费方案，适合个人非商业项目；首次使用需要登录并授权 Vercel 访问目标 GitHub 仓库。具体以服务商当前计划与用量规则为准。
