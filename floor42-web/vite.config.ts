import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// In standalone `npm run dev`, forward API and event traffic to the Quarkus daemon.
export default defineConfig({
  // The online demo is served under the repository's GitHub Pages path (ADR-0031); the daemon serves the UI at /.
  base: process.env.VITE_BASE ?? '/',
  plugins: [vue()],
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:7420',
      '/auth': 'http://127.0.0.1:7420',
      '/ws': { target: 'ws://127.0.0.1:7420', ws: true },
    },
  },
})
