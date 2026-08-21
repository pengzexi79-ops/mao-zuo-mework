import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import fs from 'node:fs'

// Inject the packaged release version so the UI can detect a stale frontend
// bundle (browser still holding the old assets after the backend jar updated).
let appVersion = 'dev'
try {
  const notesPath = fileURLToPath(new URL('../backend/src/main/resources/release-notes.json', import.meta.url))
  appVersion = JSON.parse(fs.readFileSync(notesPath, 'utf8')).version || 'dev'
} catch { /* keep 'dev' when release-notes.json is unavailable */ }

export default defineConfig({
  define: { __APP_VERSION__: JSON.stringify(appVersion) },
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    // LAN development is opt-in at runtime (`npm run dev -- --host`); production is served by Spring.
    host: false,
    port: 5273,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8760', changeOrigin: true },
      '/files': { target: 'http://127.0.0.1:8760', changeOrigin: true }
    }
  },
  build: {
    // 打包直接吐到后端的 static 目录，mvn package 就能把前端一起塞进 jar
    outDir: '../backend/src/main/resources/static',
    // Keep index.html and hashed chunks as one coherent bundle. A failed build must
    // fail the build instead of leaving old chunks that can produce a blank page.
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500
  }
})
