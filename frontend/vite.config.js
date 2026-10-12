import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  // GitHub Pages publishes this repository beneath /Resume-helper/.
  // Local development and other preview environments keep the root path.
  base: process.env.GITHUB_ACTIONS === 'true' ? '/Resume-helper/' : '/',
  plugins: [vue()],
  server: {
    port: 5173,
    host: '0.0.0.0'
  }
})
