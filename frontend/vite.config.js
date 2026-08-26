import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import fs from 'node:fs'
import path from 'node:path'

// WorkBuddy can expose this project through a mapped path while Node resolves
// dependencies through the underlying real path. Keep Vite root and output on
// that same path so Rollup never receives a cross-drive absolute chunk name.
const projectRoot = fs.realpathSync(fileURLToPath(new URL('.', import.meta.url)))
const backendStaticDir = path.resolve(projectRoot, '../backend/src/main/resources/static')

// Inject the packaged release version so the UI can detect a stale frontend
// bundle (browser still holding the old assets after the backend jar updated).
let appVersion = 'dev'
try {
  const notesPath = fileURLToPath(new URL('../backend/src/main/resources/release-notes.json', import.meta.url))
  appVersion = JSON.parse(fs.readFileSync(notesPath, 'utf8')).version || 'dev'
} catch { /* keep 'dev' when release-notes.json is unavailable */ }

export default defineConfig({
  root: projectRoot,
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
    outDir: backendStaticDir,
    // Keep index.html and hashed chunks as one coherent bundle. A failed build must
    // fail the build instead of leaving old chunks that can produce a blank page.
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500
  }
})
