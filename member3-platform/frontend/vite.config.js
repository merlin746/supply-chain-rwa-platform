import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5178,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.RWA_API_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})
