import axios from 'axios'
import { ElMessage } from 'element-plus'

const runtimeEnv = import.meta.env || {}
const baseURL = (runtimeEnv.VITE_API_BASE || '').replace(/\/$/, '')
// A LAN user may open the one-time share URL from start.bat. Prefer a local build token when set.
const queryAccessToken = new URLSearchParams(window.location.search).get('access_token')
if (queryAccessToken) window.sessionStorage?.setItem('mework-access-token', queryAccessToken)
const accessToken = runtimeEnv.VITE_ACCESS_TOKEN || queryAccessToken || window.sessionStorage?.getItem('mework-access-token')
if (queryAccessToken && window.history?.replaceState) {
  const safeUrl = `${window.location.pathname}${window.location.hash || ''}`
  window.history.replaceState(null, '', safeUrl)
}
const http = axios.create({ baseURL, timeout: 300000 })
const apiUrl = (path) => `${baseURL}${path}`
const pendingGets = new Map()
function stableQuery (params) {
  if (!params) return ''
  return Object.keys(params).sort().map((key) => `${key}=${JSON.stringify(params[key])}`).join('&')
}
function get (url, config) {
  const key = `${url}?${stableQuery(config?.params)}`
  const existing = pendingGets.get(key)
  if (existing) return existing
  const request = http.request({ method: 'get', url, ...(config || {}) })
    .finally(() => pendingGets.delete(key))
  pendingGets.set(key, request)
  return request
}

// 原生上传需要进度事件；统一解析非 JSON/代理错误，避免“fail to post”吞掉真实原因。
export function uploadFile (file, data = {}, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', apiUrl('/api/materials/upload'))
    if (accessToken) xhr.setRequestHeader('X-Mixcut-Token', accessToken)
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && onProgress) onProgress(Math.round(event.loaded * 100 / event.total))
    }
    xhr.onerror = () => reject(new Error('上传连接中断：请确认应用仍在运行；若从微信窗口拖入，请先另存为本地文件后重试'))
    xhr.ontimeout = () => reject(new Error('上传超时：请确认应用仍在运行并检查磁盘剩余空间'))
    xhr.onload = () => {
      let body
      try { body = xhr.responseText ? JSON.parse(xhr.responseText) : null } catch { body = null }
      if (!body || typeof body !== 'object') {
        const hint = xhr.status === 413 ? '当前运行环境拒绝了本次上传，请检查反向代理或磁盘配置' : `服务器返回 ${xhr.status || '空响应'}`
        reject(new Error(`上传失败：${hint}`)); return
      }
      if (xhr.status < 200 || xhr.status >= 300 || body.ok === false) {
        reject(new Error(body.message || `上传失败（HTTP ${xhr.status}）`)); return
      }
      resolve(body.data ?? body)
    }
    // File size is constrained by available disk space, not an application-side timeout.
    xhr.timeout = 0
    const form = new FormData()
    form.append('file', file)
    Object.entries(data || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') form.append(key, value)
    })
    xhr.send(form)
  })
}
export function importMaterialPackage (files, packageName, relativePaths = [], data = {}, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', apiUrl('/api/materials/import-package'))
    if (accessToken) xhr.setRequestHeader('X-Mixcut-Token', accessToken)
    xhr.upload.onprogress = (event) => { if (event.lengthComputable && onProgress) onProgress(Math.round(event.loaded * 100 / event.total)) }
    xhr.onerror = () => reject(new Error('素材总包上传连接中断，请确认文件已完整保存到本机后重试'))
    xhr.onload = () => {
      let body
      try { body = xhr.responseText ? JSON.parse(xhr.responseText) : null } catch { body = null }
      if (!body || typeof body !== 'object') return reject(new Error(`素材总包导入失败：服务器返回 ${xhr.status || '空响应'}`))
      if (xhr.status < 200 || xhr.status >= 300 || body.ok === false) return reject(new Error(body.message || `素材总包导入失败（HTTP ${xhr.status}）`))
      resolve(body.data ?? body)
    }
    xhr.timeout = 0
    const form = new FormData()
    files.forEach((file, index) => { form.append('files', file); form.append('relativePaths', relativePaths[index] || file.webkitRelativePath || file.name) })
    form.append('packageName', packageName)
    Object.entries(data || {}).forEach(([key, value]) => { if (value !== undefined && value !== null && value !== '') form.append(key, value) })
    xhr.send(form)
  })
}
export function importMaterialPackageArchive (file, data = {}, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', apiUrl('/api/materials/import-package-archive'))
    if (accessToken) xhr.setRequestHeader('X-Mixcut-Token', accessToken)
    xhr.upload.onprogress = (event) => { if (event.lengthComputable && onProgress) onProgress(Math.round(event.loaded * 100 / event.total)) }
    xhr.onerror = () => reject(new Error('ZIP 总包上传连接中断，请确认压缩包已完整保存到本机后重试'))
    xhr.onload = () => {
      let body
      try { body = xhr.responseText ? JSON.parse(xhr.responseText) : null } catch { body = null }
      if (!body || typeof body !== 'object') return reject(new Error(`ZIP 总包导入失败：服务器返回 ${xhr.status || '空响应'}`))
      if (xhr.status < 200 || xhr.status >= 300 || body.ok === false) return reject(new Error(body.message || `ZIP 总包导入失败（HTTP ${xhr.status}）`))
      resolve(body.data ?? body)
    }
    xhr.timeout = 0
    const form = new FormData()
    form.append('file', file)
    Object.entries(data || {}).forEach(([key, value]) => { if (value !== undefined && value !== null && value !== '') form.append(key, value) })
    xhr.send(form)
  })
}
export function importMaterialArchive (file, data = {}, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', apiUrl('/api/materials/import-archive'))
    if (accessToken) xhr.setRequestHeader('X-Mixcut-Token', accessToken)
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && onProgress) onProgress(Math.round(event.loaded * 100 / event.total))
    }
    xhr.onerror = () => reject(new Error('ZIP 上传连接中断：请确认压缩包已完整保存到本机后重试'))
    xhr.onload = () => {
      let body
      try { body = xhr.responseText ? JSON.parse(xhr.responseText) : null } catch { body = null }
      if (!body || typeof body !== 'object') return reject(new Error(`ZIP 导入失败：服务器返回 ${xhr.status || '空响应'}`))
      if (xhr.status < 200 || xhr.status >= 300 || body.ok === false) return reject(new Error(body.message || `ZIP 导入失败（HTTP ${xhr.status}）`))
      resolve(body.data ?? body)
    }
    xhr.timeout = 0
    const form = new FormData()
    form.append('file', file)
    Object.entries(data || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') form.append(key, value)
    })
    xhr.send(form)
  })
}
const protectedUrl = (path) => {
  const url = apiUrl(path)
  return accessToken ? `${url}${url.includes('?') ? '&' : '?'}access_token=${encodeURIComponent(accessToken)}` : url
}

// 同一错误短时间内只提示一次；轮询调用可传 { silent: true } 完全静默。
const recentErrors = new Map()
function notifyError (message, silent) {
  if (silent) return
  const now = Date.now()
  if (now - (recentErrors.get(message) || 0) < 3000) return
  recentErrors.set(message, now)
  ElMessage.error(message)
}

function normalizeError (error, fallbackMessage, status) {
  const serverMessage = error?.response?.data?.message
  // Axios uses English transport messages such as "Network Error". Never surface them
  // when the browser did not receive a usable server response.
  const message = serverMessage || fallbackMessage || error?.message || '请求失败，请稍后重试'
  const normalized = new Error(message)
  normalized.status = status ?? error?.response?.status ?? 0
  normalized.code = error?.code
  normalized.cause = error
  return normalized
}

function fallbackMessage (error) {
  if (error?.code === 'ECONNABORTED' || /timeout/i.test(error?.message || '')) return '请求超时，请检查后端服务是否繁忙后重试'
  if (!error?.response) return '无法连接后端服务。请确认已通过 start.bat 启动猫作，然后点击“刷新状态”。'
  if (error.response.status === 400) return '请求参数不正确，请检查填写内容'
  if (error.response.status === 401 || error.response.status === 403) return '当前操作没有权限，请检查访问令牌配置'
  if (error.response.status === 413) return '当前运行环境拒绝了本次上传，请检查外部代理限制或磁盘剩余空间'
  if (error.response.status >= 500) return '服务端处理失败，请稍后重试；若持续出现，请在环境中心检查后端日志'
  return '请求失败，请稍后重试'
}

http.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers = config.headers || {}
    config.headers['X-Mixcut-Token'] = accessToken
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
    const d = resp.data
    if (d && typeof d === 'object' && 'ok' in d) {
      if (!d.ok) {
        const normalized = normalizeError(null, d.message || '请求失败', resp.status)
        notifyError(normalized.message, resp.config?.silent)
        return Promise.reject(normalized)
      }
      return d.data
    }
    return d
  },
  (err) => {
    const normalized = normalizeError(err, fallbackMessage(err))
    notifyError(normalized.message, err.config?.silent)
    return Promise.reject(normalized)
  }
)

export const api = {
  // 系统
  overview: (config = {}) => get('/api/system/overview', config),
  env: (config) => get('/api/system/env', config),
  refreshEnv: (config = {}) => get('/api/system/env', {
    ...config,
    params: { ...(config.params || {}), refresh: true }
  }),
  queue: (config) => get('/api/system/queue', config),
  releaseNotes: (config) => get('/api/system/release-notes', config),
  capabilities: (config) => get('/api/system/capabilities', config),
  connectivity: (config = {}) => get('/api/system/connectivity', config),
  tasks: (config = {}) => get('/api/tasks', config),
  resourceCatalog: (config) => get('/api/system/resources', config),
  installCapability: (key) => http.post('/api/system/capabilities/install', { key }),
  testMysqlConfig: (body) => http.post('/api/local-config/mysql/test', body),
  saveMysqlConfig: (body) => http.post('/api/local-config/mysql/save', body),
  sourceKeyStatus: () => get('/api/local-config/source-keys'),
  saveSourceKey: (body) => http.post('/api/local-config/source-keys/save', body),
  testSourceKey: (configId, provider) => http.post('/api/local-config/source-keys/test', { configId, provider }),
  localSetupStatus: () => get('/api/local-config/setup-status'),
  restartLocalBackend: () => http.post('/api/local-config/restart'),
  localReleaseStatus: () => get('/api/local-config/release/status'),
  syncLocalReleaseHistory: () => http.post('/api/local-config/release/sync'),
  saveLocalReleasePending: (body) => http.put('/api/local-config/release/pending', body),
  checkLocalReleasePending: () => http.post('/api/local-config/release/check'),
  applyLocalReleasePending: () => http.post('/api/local-config/release/apply'),
  outputLocation: () => get('/api/local-config/output-location'),
  saveOutputLocation: (body) => http.post('/api/local-config/output-location', body),
  mediaToolOutputLocation: () => get('/api/local-config/media-tools-output-location'),
  saveMediaToolOutputLocation: (body) => http.post('/api/local-config/media-tools-output-location', body),

  // 素材
  materials: (params, config = {}) => get('/api/materials', { ...config, params }),
  material: (id, config = {}) => get(`/api/materials/${id}`, config),
  materialStats: () => get('/api/materials/stats'),
  // Directory scans probe every readable media file locally; keep this request alive for large desktop folders.
  scanFolder: (body) => http.post('/api/materials/scan', body, { timeout: 1800000 }),
  updateMaterial: (id, body) => http.put(`/api/materials/${id}`, body),
  batchRole: (body) => http.post('/api/materials/batch-role', body),
  batchDeleteMaterials: (body) => http.post('/api/materials/batch-delete', body),
  deleteMaterial: (id) => http.delete(`/api/materials/${id}`),
  materialAnalysis: (id, config) => get(`/api/materials/${id}/analysis`, config),
  analyzeMaterial: (id) => http.post(`/api/materials/${id}/analyze`),
  batchIndexMaterials: (body = {}) => http.post('/api/materials/index', body),
  materialDeleteImpact: (id) => http.post(`/api/materials/${id}/delete-impact`),
  permanentlyDeleteMaterial: (id) => http.post(`/api/materials/${id}/delete`, { confirm: true }),
  purgeMissing: () => http.post('/api/materials/purge-missing'),
  materialFolders: (config = {}) => get('/api/materials/folders', config),
  auditMaterialPackageName: (name) => get('/api/materials/package-name-audit', { params: { name } }),
  createMaterialFolder: (body) => http.post('/api/materials/folders', body),
  updateMaterialFolder: (id, body) => http.put(`/api/materials/folders/${id}`, body),
  deleteMaterialFolder: (id) => http.delete(`/api/materials/folders/${id}`),
  batchDeleteMaterialFolders: (body) => http.post('/api/materials/folders/batch-delete', body),
  moveMaterial: (id, body) => http.post(`/api/materials/${id}/move`, body),
  reprobeMaterial: (id) => http.post(`/api/materials/${id}/reprobe`),
  retryThumbnail: (id) => http.post(`/api/materials/${id}/thumbnail/retry`),
  generateTts: (body) => http.post('/api/materials/tts', body),
  audioEngineStatus: () => get('/api/materials/audio-engine/status'),
  separateAudio: (id) => http.post(`/api/materials/${id}/audio/separate`),
  diagnoseMaterial: (id) => http.post(`/api/materials/${id}/diagnose`),
  retryTranscription: (id) => http.post(`/api/materials/${id}/transcribe/retry`),
  batchFlags: (body) => http.post('/api/materials/batch-flags', body),
  splitMaterial: (id, body) => http.post(`/api/materials/${id}/split`, body),
  mediaToolImage: (body) => http.post('/api/media-tools/image', body),
  mediaToolSeparate: (body) => http.post('/api/media-tools/audio-separate', body),
  mediaToolSplit: (body) => http.post('/api/media-tools/video-split', body),
  mediaToolTimeline: (body) => http.post('/api/media-tools/timeline', body),
  mediaToolSubtitleCover: (body) => http.post('/api/media-tools/subtitle-cover', body),
  mediaToolAutoTrim: (body) => http.post('/api/media-tools/auto-trim', body),
  mediaToolOpenOutputDirectory: () => http.post('/api/media-tools/open-output-directory'),
  mediaToolTasks: (config) => get('/api/media-tools/tasks', config),
  mediaToolTask: (id, config) => get(`/api/media-tools/tasks/${id}`, config),
  cancelMediaToolTask: (id) => http.post(`/api/media-tools/tasks/${id}/cancel`),
  retryMediaToolTask: (id) => http.post(`/api/media-tools/tasks/${id}/retry`),
  uploadUrl: apiUrl('/api/materials/upload'),
  uploadHeaders: accessToken ? { 'X-Mixcut-Token': accessToken } : undefined,
  materialPreviewUrl: (id) => protectedUrl(`/api/materials/${id}/preview`),
  protectedUrl,
  materialPreviewHeaders: accessToken ? { 'X-Mixcut-Token': accessToken } : undefined,

  // 抓取
  crawlSources: () => get('/api/crawl/sources'),
  crawlCurated: () => get('/api/crawl/curated'),
  crawlVideo: (body) => http.post('/api/crawl/video', body),

  crawlDirect: (body) => http.post('/api/crawl/direct', body),  crawlVideoBatch: (body) => http.post('/api/crawl/video/batch', body),
  crawlJobs: (config) => get('/api/crawl/jobs', config),
  crawlJob: (id, config) => get(`/api/crawl/jobs/${id}`, config),
  cancelCrawlJob: (id) => http.post(`/api/crawl/jobs/${id}/cancel`),
  retryCrawlJob: (id) => http.post(`/api/crawl/jobs/${id}/retry`),
  deleteCrawlJob: (id) => http.delete(`/api/crawl/jobs/${id}`),
  cleanupCrawlJobs: () => http.post('/api/crawl/jobs/cleanup'),
  searchAudio: (params) => get('/api/crawl/audio/search', { params }),
  importAudio: (body) => http.post('/api/crawl/audio/import', body),
  searchImage: (params) => get('/api/crawl/image/search', { params }),
  importImage: (body) => http.post('/api/crawl/image/import', body),
  searchPublicVideo: (params) => get('/api/crawl/video/search', { params }),
  importPublicVideo: (body) => http.post('/api/crawl/video/import', body),

  // AI
  providers: () => get('/api/ai/providers'),
  createProvider: (body) => http.post('/api/ai/providers', body),
  updateProvider: (id, body) => http.put(`/api/ai/providers/${id}`, body),
  discoverProviderModels: (id) => http.post(`/api/ai/providers/${id}/discover-models`),
  discoverProviderDraftModels: (body) => http.post('/api/ai/providers/discover-models', body),
  adoptProviderMedia: (id, body) => http.post(`/api/ai/providers/${id}/adopt-media`, body),
  deleteProvider: (id) => http.delete(`/api/ai/providers/${id}`),
  presetModels: () => get('/api/ai/preset-models'),
  testProvider: (body) => http.post('/api/ai/test', body),
  routes: () => get('/api/ai/routes'),
  saveRoute: (useCase, body) => http.put(`/api/ai/routes/${useCase}`, body),
  aiLogs: () => get('/api/ai/logs'),
  aiReady: () => get('/api/ai/ready'),
  genHooks: (body) => http.post('/api/ai/copy/hooks', body),
  genScript: (body) => http.post('/api/ai/copy/script', body),
  genTitles: (body) => http.post('/api/ai/copy/titles', body),
  chat: (body) => http.post('/api/ai/chat', body),
  aiGenerationCapabilities: () => get('/api/ai-generation/capabilities'),
  aiMediaProviders: () => get('/api/ai-generation/providers'),
  aiImageProviders: () => get('/api/ai-generation/providers'),
  generateAiImage: (body) => http.post('/api/ai-generation/image', body, { timeout: 180000 }),
  generateAiVideo: (body) => http.post('/api/ai-generation/video', body, { timeout: 180000 }),
  generateAiVoice: (body) => http.post('/api/ai-generation/voice', body, { timeout: 180000 }),
  aiGenerationTasks: (config) => get('/api/ai-generation/tasks', config),
  aiGenerationTask: (id, config) => get(`/api/ai-generation/tasks/${id}`, config),
  saveAiGenerationTask: (id) => http.post(`/api/ai-generation/tasks/${id}/save`),
  batchSaveAiGenerationTasks: (body) => http.post('/api/ai-generation/tasks/batch-save', body),
  deleteAiGenerationTask: (id) => http.delete(`/api/ai-generation/tasks/${id}`),
  batchDeleteAiGenerationTasks: (body) => http.post('/api/ai-generation/tasks/batch-delete', body),
  clearFinishedAiGenerationTasks: () => http.post('/api/ai-generation/tasks/clear-finished'),

  // 插件
  plugins: (config) => get('/api/plugins', config),
  createPlugin: (body) => http.post('/api/plugins', body),
  updatePlugin: (id, body) => http.put(`/api/plugins/${id}`, body),
  deletePlugin: (id) => http.delete(`/api/plugins/${id}`),

  // 项目
  projects: (config = {}) => get('/api/projects', config),
  project: (id) => get(`/api/projects/${id}`),
  createProject: (body) => http.post('/api/projects', body),
  duplicateProject: (id) => http.post(`/api/projects/${id}/duplicate`),
  projectDraft: (body) => http.post('/api/projects/draft', body),
  updateProject: (id, body) => http.put(`/api/projects/${id}`, body),
  deleteProject: (id) => http.delete(`/api/projects/${id}`),

  // 工作流 / Skill
  workflows: (config = {}) => get('/api/workflows', config),
  workflow: (id) => get(`/api/workflows/${id}`),
  createWorkflow: (body) => http.post('/api/workflows', body),
  updateWorkflow: (id, body) => http.put(`/api/workflows/${id}`, body),
  duplicateWorkflow: (id) => http.post(`/api/workflows/${id}/duplicate`),
  exportWorkflow: (id) => get(`/api/workflows/${id}/export`),
  importWorkflow: (body) => http.post('/api/workflows/import', body),
  deleteWorkflow: (id) => http.delete(`/api/workflows/${id}`),
  skills: () => get('/api/skills'),
  builtinSkills: () => get('/api/skills/builtin'),
  createSkill: (body) => http.post('/api/skills', body),
  updateSkill: (id, body) => http.put(`/api/skills/${id}`, body),
  exportSkill: (id) => get(`/api/skills/${id}/export`),
  importSkill: (body) => http.post('/api/skills/import', body),
  deleteSkill: (id) => http.delete(`/api/skills/${id}`),
  validateSkill: (body) => http.post('/api/skills/validate', body),
  aiPlan: (body) => http.post('/api/workflows/ai-plan', body),
  aiComic: (body) => http.post('/api/workflows/ai-comic', body),
  fixedOrderSuggestion: (body) => http.post('/api/fixed-order/suggest', body),
  // OUT-1 admission snapshot/preparationId remain nested in the existing request body.
  dryRun: (body) => http.post('/api/workflows/dry-run', body),

  // 素材缺口分析
  materialGap: (body) => http.post('/api/material/gap', body),
  materialAutoFill: (body) => http.post('/api/material/auto-fill', body),

  // 出片
  prepareRender: (body) => http.post('/api/jobs/prepare', body),
  // 异步出片准备轮询：POST /api/jobs/prepare 立即返回 { id, status }，此接口持续返回当前快照
  prepareRenderStatus: (id, config) => get(`/api/jobs/prepare/${id}`, config),
  cancelPreparation: (id) => http.post(`/api/jobs/prepare/${id}/cancel`),
  recentPreparationTasks: (config) => get('/api/jobs/prepare', config),
  submitJob: (body) => http.post('/api/jobs', body),
  jobs: (config) => get('/api/jobs', config),
  job: (id, config) => get(`/api/jobs/${id}`, config),
  cancelJob: (id) => http.post(`/api/jobs/${id}/cancel`),
  pauseJob: (id) => http.post(`/api/jobs/${id}/pause`),
  resumeJob: (id) => http.post(`/api/jobs/${id}/resume`),
  forceResumeJob: (id) => http.post(`/api/jobs/${id}/force-resume`),
  setForceContinue: (id, enabled) => http.post(`/api/jobs/${id}/force-continue`, { enabled }),
  retryFailedJob: (id) => http.post(`/api/jobs/${id}/retry-failed`),
  outputRepair: (jobId, idx) => get(`/api/jobs/${jobId}/outputs/${idx}/repair`),
  applyOutputRepairDecision: (jobId, idx, body) => http.post(`/api/jobs/${jobId}/outputs/${idx}/repair-decision`, body),
  outputEditor: (jobId, idx) => get(`/api/jobs/${jobId}/outputs/${idx}/editor`),
  saveOutputEditor: (jobId, idx, sessionId, body) => http.put(`/api/jobs/${jobId}/outputs/${idx}/editor/${sessionId}`, body),
  renderOutputEditor: (jobId, idx, sessionId) => http.post(`/api/jobs/${jobId}/outputs/${idx}/editor/${sessionId}/render`),
  applyOutputEditor: (jobId, idx, sessionId) => http.post(`/api/jobs/${jobId}/outputs/${idx}/editor/${sessionId}/apply`, { confirm: true }),
  deleteJob: (id) => http.delete(`/api/jobs/${id}`),
  batchDeleteJobs: (body) => http.post('/api/jobs/batch-delete', body),
  cleanupJobs: () => http.post('/api/jobs/cleanup'),
  allOutputs: (config) => get('/api/jobs/outputs/all', config),
  outputReindexCandidates: () => get('/api/jobs/outputs/reindex-candidates'),
  reindexOutputs: (filePaths) => http.post('/api/jobs/outputs/reindex', { filePaths }),
  deleteOutput: (id) => http.delete(`/api/jobs/outputs/${id}`),
  downloadUrl: (id) => protectedUrl(`/api/jobs/outputs/${id}/download`)
}

export const ROLE_LABEL = {
  none: '未分类',
  hook: '钩子',
  body: '实拍主体',
  product: '自家产品',
  celebrity: '明星达人',
  voice: '人声口播',
  bgm: '背景音乐',
  endcard: '片尾'
}

export const ROLE_COLOR = {
  none: 'info',
  hook: 'danger',
  body: '',
  product: 'success',
  celebrity: 'warning',
  voice: 'primary',
  bgm: 'primary',
  endcard: 'info'
}

export default http
