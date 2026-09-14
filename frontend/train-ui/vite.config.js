import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/train-ui/',
  plugins: [vue()],
  server: {
    port: 5180,
    proxy: {
      '/apitrain': {
        target: 'http://127.0.0.1:8084',
        rewrite: p => p.replace(/^\/apitrain/, '/api'),
      }
    }
  }
})
