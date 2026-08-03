import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://175.178.229.209/',
        changeOrigin: true,
        timeout: 0,
        proxyTimeout: 0
      }
    }
  }
})
