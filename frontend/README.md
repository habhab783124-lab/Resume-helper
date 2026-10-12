# AI 求职与模拟面试工作台 - 前端工程

这是项目的 Vue 3 前端工程代码（包含完整的登录界面与工作台首页）。

## 快速运行指引（给队友）

### 1. 前置环境要求
电脑上需要安装好 [Node.js](https://nodejs.org/)（建议 Node.js 18 或 20 以上）。

### 2. 安装依赖
解压压缩包后，进入 `frontend` 文件夹，在空白处右键选择“在终端中打开”，然后运行：
```bash
npm install
```
*(提示：国内若下载较慢，可使用国内镜像源：`npm install --registry=https://registry.npmmirror.com`)*

### 3. 启动项目
依赖安装完成后，在终端运行：
```bash
npm run dev
```
运行成功后终端会显示本地地址：
```text
➜  Local:   http://localhost:5173/
```
在浏览器中打开该地址即可看到登录页面！

---

## 登录测试说明
- **管理员账号**：`admin` / 密码：`123456`
- **学生账号**：`student` / 密码：`123456`
- 登录卡片底部支持一键快捷填入账号密码。
- 目前已配置智能体验模式，无需启动后端也可直接体验登录及进入工作台。

## 项目核心代码结构
```text
frontend/
├── src/
│   ├── views/
│   │   ├── Login.vue       # 登录界面组件
│   │   └── Dashboard.vue   # 登录后的工作台主页
│   ├── router/
│   │   └── index.js        # 页面路由配置
│   ├── App.vue             # 根组件
│   └── main.js             # 入口配置 (引入 Element Plus)
├── index.html              # 网页入口
├── vite.config.js          # Vite 配置文件
└── package.json            # 依赖包配置文件
```
