# 拾阶 · 求职工作台前端

本目录包含 Vue 3 前端页面。目前以静态原型展示为主，登录入口用于进入演示工作台；简历生成、岗位匹配和面试业务尚未连接后端。

## 本地运行

需要 Node.js 18 或更新版本。

```bash
npm ci
npm run dev
```

页面和界面风格基于仓库中的 `prototype-static` 原型，主要页面包括工作台、个人资料、材料整理、简历、岗位匹配、模拟面试和面试报告。

## 发布到 Vercel

在 Vercel 选择 **Add New → Project**，导入 GitHub 仓库 `Resume-helper`，并将 **Root Directory** 设为 `frontend`。构建设置使用：

- Framework Preset：Vite
- Install Command：`npm ci`
- Build Command：`npm run build`
- Output Directory：`dist`

成功部署后，Vercel 会分配一个以项目名为基础的 `*.vercel.app` 网址。连接 GitHub 后，后续推送到生产分支会自动重新部署。
