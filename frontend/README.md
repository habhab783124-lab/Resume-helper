# 拾阶 · 求职工作台前端

本目录包含 Vue 3 前端页面。目前以静态原型展示为主，登录入口用于进入演示工作台；简历生成、岗位匹配和面试业务尚未连接后端。

## 本地运行

需要 Node.js 18 或更新版本。

```bash
npm ci
npm run dev
```

页面和界面风格基于仓库中的 `prototype-static` 原型，主要页面包括工作台、个人资料、材料整理、简历、岗位匹配、模拟面试和面试报告。

## 发布到 GitHub Pages

仓库使用 GitHub Actions 构建并发布。首次启用时，请在 GitHub 仓库设置中完成一次配置：

1. 打开仓库 **Settings → Pages**。
2. 在 **Build and deployment** 下将 **Source** 设为 **GitHub Actions**。
3. 推送到 `main` 分支，或在 **Actions** 页面手动运行 `Deploy to GitHub Pages` 工作流。

构建成功后，网页地址为：<https://habhab783124-lab.github.io/Resume-helper/>。后续推送到 `main` 会自动更新网站。

GitHub Pages 使用仓库子路径部署，因此 Vite 只在 Actions 构建时设置 `/Resume-helper/` 资源前缀；Vue Router 使用哈希路由，页面链接形如 `/#/resume`，刷新时也能正确打开。
