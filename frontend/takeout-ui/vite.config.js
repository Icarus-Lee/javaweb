import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/takeout-ui/',
  plugins: [vue()],
  server: {
    port: 5181,
    proxy: {
      '/apitakeout': {
        target: 'http://127.0.0.1:8085',
        rewrite: p => p.replace(/^\/apitakeout/, '/api'),
      }
    }
  }
})
