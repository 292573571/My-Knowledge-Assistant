import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    // 默认 node 环境；需要 DOM 的用例在文件头用 @vitest-environment jsdom 单独声明，
    // 避免每个文件都拉起一遍 jsdom。
    environment: 'node',
    include: ['src/**/*.test.js'],
    restoreMocks: true,
    // 本机沙箱下 forks/threads 池的 worker 起不来（tinypool 握手超时），
    // vmThreads 可以直接跑；CI 上同样可用。
    pool: 'vmThreads'
  }
})
