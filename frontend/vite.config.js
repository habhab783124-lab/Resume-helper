import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 后端地址。
 *
 * 默认 http://localhost:8080。如果本机 8080 被别的程序占了
 * （例如某些机器上 NI Application Web Server 会占着 8080），
 * 用环境变量覆盖即可，不需要改代码：
 *
 *   $env:VITE_PROXY_TARGET = 'http://localhost:8081'; npm run dev
 */
const proxyTarget = process.env.VITE_PROXY_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  worker: { format: 'es' },
  server: {
    port: 5173,
    host: '0.0.0.0',
    proxy: {
      // 前端代码里统一写相对路径 /api/xxx，由 Vite 转发到后端。
      // 好处：开发时没有跨域问题；生产构建里 /api 也不会被当成前端路由。
      '/api': {
        target: proxyTarget,
        changeOrigin: true,
        ws: true
      }
    }
  }
})
