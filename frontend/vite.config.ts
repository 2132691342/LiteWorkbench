import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import UnoCSS from 'unocss/vite'
import { defineConfig } from 'vite'

/** 构建配置：别名 @、开发代理 /api → 8080、UnoCSS 原子类 */
export default defineConfig({
  plugins: [vue(), UnoCSS()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    // 图表库与语法高亮体积较大，放宽告警阈值
    chunkSizeWarningLimit: 2000,
    rollupOptions: {
      output: {
        manualChunks: {
          'element-plus': ['element-plus', '@element-plus/icons-vue'],
          echarts: ['echarts'],
          // 富文本与编辑器体积大且只在创作台使用，独立成块避免拖慢主包
          'wang-editor': ['@wangeditor/editor', '@wangeditor/editor-for-vue'],
          markdown: ['markdown-it', 'highlight.js'],
        },
      },
    },
  },
})
