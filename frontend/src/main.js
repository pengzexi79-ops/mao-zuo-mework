import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import * as Icons from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './styles.css'

// —— 陈旧懒加载分块防护 ——
// 后端 Jar 更新并重新打包前端后，浏览器可能仍持有旧 index.html / 旧模块清单，
// 而带 hash 的懒加载分块已被 emptyOutDir 清空，动态 import 会失败。
// 这里只针对“懒加载分块缺失/加载失败”这一种错误做一次受控自动刷新，
// 避免反复刷新死循环，也避免整页白屏。
const STALE_CHUNK_RELOAD_KEY = 'mework-stale-chunk-reload-at'
const STALE_CHUNK_RELOAD_WINDOW_MS = 30 * 1000
let staleChunkReloadScheduled = false
function isStaleChunkError (error) {
  const message = String(error?.message || error?.reason || error || '')
  return /dynamically imported module/i.test(message) ||
    /Importing a module script failed/i.test(message) ||
    /error loading dynamically imported module/i.test(message) ||
    (error?.type === 'chunk-load-error')
}
function autoReloadForStaleChunk (error) {
  if (!isStaleChunkError(error)) return false
  if (staleChunkReloadScheduled) return true
  try {
    const last = Number(window.sessionStorage?.getItem(STALE_CHUNK_RELOAD_KEY) || 0)
    if (Date.now() - last < STALE_CHUNK_RELOAD_WINDOW_MS) return false
    window.sessionStorage.setItem(STALE_CHUNK_RELOAD_KEY, String(Date.now()))
  } catch {
    // sessionStorage 不可用时仍允许当前会话内刷新一次。
  }
  staleChunkReloadScheduled = true
  window.location.reload()
  return true
}
window.addEventListener('vite:preloadError', (event) => {
  autoReloadForStaleChunk(event?.payload || event?.error || event)
})

const app = createApp(App)
for (const [key, comp] of Object.entries(Icons)) {
  app.component(key, comp)
}
app.config.errorHandler = (error, instance, info) => {
  console.error('猫作页面运行异常', error, info)
  const root = document.getElementById('app')
  const message = String(error?.message || error || '未知页面异常').replace(/[<>&]/g, '')
  // A route component can fail after the shell mounted. Surface the reason instead of leaving
  // the user with a blank work area, while preserving navigation and the environment controls.
  const main = root?.querySelector('.main .page')
  if (main && !main.querySelector('.boot-error')) {
    main.innerHTML = `<div class="boot-state boot-error">此页面加载失败：${message}<br>请点击“刷新状态”或按 Ctrl + F5；若仍失败，请查看后端日志。</div>`
  } else if (root && !root.querySelector('.app-layout')) {
    root.innerHTML = `<div class="boot-state boot-error">页面运行异常：${message}<br>请按 Ctrl + F5 强制刷新；若持续出现，请重新构建前端并启动最新 Jar。</div>`
  }
}
router.onError((error) => {
  console.error('猫作页面资源加载失败', error)
  if (autoReloadForStaleChunk(error)) return
  const root = document.getElementById('app')
  if (root) root.innerHTML = '<div class="boot-state boot-error">页面资源加载失败。请按 Ctrl + F5 强制刷新后重试。</div>'
})
app.use(ElementPlus, { locale: zhCn })
app.use(router)
app.mount('#app')
