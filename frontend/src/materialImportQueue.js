import { markRaw, reactive } from 'vue'
import { api, importMaterialPackage, importMaterialPackageArchive, uploadFile } from './api.js'

const MAX_BATCH_HISTORY = 30
const FILE_UPLOAD_CONCURRENCY = 3
const MATERIAL_POLL_INTERVAL_MS = 1500
const MATERIAL_POLL_LIMIT = 1200

export const materialImportState = reactive({ batches: [] })

let sequence = 0

function timestamp () {
  return new Date().toISOString()
}

function nextId (prefix) {
  sequence += 1
  return `${prefix}-${Date.now()}-${sequence}`
}

function emit (name, detail) {
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(name, { detail }))
}

function delay (ms) {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

function addBatch (batch) {
  materialImportState.batches.unshift(batch)
  if (materialImportState.batches.length > MAX_BATCH_HISTORY) materialImportState.batches.length = MAX_BATCH_HISTORY
  emit('mework-material-import-updated', { batchId: batch.id })
  return batch
}

function touch (batch) {
  const items = batch.items || []
  const total = Math.max(1, items.length)
  batch.progress = Math.round(items.reduce((sum, item) => sum + Number(item.progress || 0), 0) / total)
  batch.updatedAt = timestamp()
  if (items.some((item) => ['queued', 'uploading', 'processing'].includes(item.status))) {
    batch.status = items.some((item) => item.status === 'uploading') ? 'uploading' : 'processing'
  }
  emit('mework-material-import-updated', { batchId: batch.id })
}

function finish (batch) {
  if (batch.items.some((item) => ['queued', 'uploading', 'processing'].includes(item.status))) return
  const done = batch.items.filter((item) => item.status === 'done').length
  const failed = batch.items.filter((item) => item.status === 'failed').length
  batch.progress = 100
  batch.status = failed ? 'failed' : 'done'
  batch.message = failed
    ? `导入结束：成功 ${done}，失败 ${failed}`
    : `导入完成：${done} 个任务已处理`
  batch.updatedAt = timestamp()
  for (const item of batch.items) {
    // Only failed ordinary files are retryable. Do not retain a large package File in memory.
    if (item.status === 'done' || batch.type !== 'files') item.file = null
  }
  emit('mework-material-import-finished', {
    batchId: batch.id,
    type: batch.type,
    label: batch.label,
    status: batch.status,
    message: batch.message,
    result: batch.result || null
  })
}

function createItem (file, name, prefix = 'material-file') {
  return {
    id: nextId(prefix),
    name: name || file?.name || '未命名素材',
    file: file ? markRaw(file) : null,
    materialId: null,
    progress: 0,
    status: 'queued',
    message: '等待导入'
  }
}

function createBatch (type, label, items, meta = {}) {
  const now = timestamp()
  return addBatch({
    id: nextId('material-import'),
    type,
    label,
    status: 'queued',
    progress: 0,
    message: '已加入后台导入队列',
    items,
    meta,
    result: null,
    createdAt: now,
    updatedAt: now
  })
}

async function waitForMaterialProbe (batch, item) {
  let transientErrors = 0
  for (let attempt = 0; attempt < MATERIAL_POLL_LIMIT; attempt++) {
    await delay(MATERIAL_POLL_INTERVAL_MS)
    try {
      const material = await api.material(item.materialId, { silent: true })
      transientErrors = 0
      if (material?.status === 'ready') return material
      if (material?.status === 'failed') throw new Error('文件已上传，但本机媒体探测未通过；请在素材库查看诊断信息')
      item.progress = Math.min(99, 92 + Math.floor(attempt / 20))
      item.message = '文件已接收，正在后台检测媒体容器和音视频流'
      touch(batch)
    } catch (error) {
      if (String(error?.message || '').includes('媒体探测未通过')) throw error
      transientErrors += 1
      if (transientErrors >= 20) throw new Error('文件已上传，但暂时无法读取后台探测进度；请确认应用仍在运行')
    }
  }
  throw new Error('文件已上传，但后台媒体探测耗时过长；可在素材库刷新查看最终状态')
}

async function runFileItem (batch, item, data) {
  item.status = 'uploading'
  item.message = '正在上传到应用数据目录'
  touch(batch)
  try {
    const material = await uploadFile(item.file, data, (percentage) => {
      item.progress = Math.min(90, Math.round(percentage * 0.9))
      item.message = `正在上传 ${percentage}%`
      touch(batch)
    })
    item.materialId = material?.id || null
    item.progress = 92
    item.status = material?.status === 'processing' ? 'processing' : 'done'
    item.message = item.status === 'processing' ? '上传完成，正在后台探测媒体' : '已完成导入'
    touch(batch)
    if (item.status === 'processing' && item.materialId) await waitForMaterialProbe(batch, item)
    item.progress = 100
    item.status = 'done'
    item.message = '已完成导入和媒体探测'
  } catch (error) {
    item.progress = 100
    item.status = 'failed'
    item.message = error?.message || '导入失败，可重新选择文件后重试'
  } finally {
    touch(batch)
  }
}

async function runFileBatch (batch, data) {
  let nextIndex = 0
  const worker = async () => {
    while (nextIndex < batch.items.length) {
      const item = batch.items[nextIndex++]
      await runFileItem(batch, item, data)
    }
  }
  await Promise.all(Array.from({ length: Math.min(FILE_UPLOAD_CONCURRENCY, batch.items.length) }, worker))
  finish(batch)
}

export function enqueueMaterialFiles (files, data = {}) {
  const items = Array.from(files || []).map((file) => createItem(file, file.webkitRelativePath || file.name))
  if (!items.length) return null
  const label = items.length === 1 ? `导入素材：${items[0].name}` : `批量导入 ${items.length} 个素材`
  const batch = createBatch('files', label, items, { data: { ...data } })
  void runFileBatch(batch, { ...data })
  return batch.id
}

async function importWorkflowPacks (packs) {
  let imported = 0
  for (const pack of packs || []) {
    try {
      const text = pack.text || pack.content || ''
      const format = pack.expectedFormat || JSON.parse(text).format
      if (format === 'mixcut-workflow') await api.importWorkflow({ pack: text })
      else if (format === 'mixcut-skill') await api.importSkill({ pack: text })
      else continue
      imported += 1
    } catch {
      // Package media import remains valid when an optional workflow JSON cannot be parsed.
    }
  }
  return imported
}

async function runPackageBatch (batch, payload) {
  const item = batch.items[0]
  item.status = 'uploading'
  item.message = '正在上传素材总包'
  touch(batch)
  try {
    const onProgress = (percentage) => {
      item.progress = Math.min(90, Math.round(percentage * 0.9))
      item.status = percentage >= 100 ? 'processing' : 'uploading'
      item.message = percentage >= 100 ? '上传完成，正在归档并探测素材' : `正在上传素材总包 ${percentage}%`
      touch(batch)
    }
    const result = payload.kind === 'archive'
      ? await importMaterialPackageArchive(payload.files[0], { packageName: payload.packageName, ...payload.data }, onProgress)
      : await importMaterialPackage(payload.files, payload.packageName, payload.relativePaths, payload.data, onProgress)
    item.status = 'processing'
    item.progress = 96
    item.message = '上传完成，正在归档并探测素材'
    touch(batch)
    const workflowImported = await importWorkflowPacks((result?.workflowPacks || []).map((pack) => ({ name: pack.name, text: pack.content })))
    batch.result = { ...(result || {}), workflowImported }
    item.status = 'done'
    item.progress = 100
    item.message = `已导入视频 ${result?.videoImported || 0}、音频 ${result?.audioImported || 0}、图片 ${result?.imageImported || 0}`
  } catch (error) {
    item.status = 'failed'
    item.progress = 100
    item.message = error?.message || '素材总包导入失败'
  } finally {
    touch(batch)
    finish(batch)
  }
}

export function enqueueMaterialPackage (payload) {
  const files = Array.from(payload?.files || [])
  if (!files.length) return null
  const packageName = String(payload.packageName || files[0].name || '素材总包').trim()
  const item = createItem(files[0], `${packageName}（${files.length} 个文件）`, 'material-package')
  const batch = createBatch('package', `导入素材总包：${packageName}`, [item], { packageName, fileCount: files.length })
  const safePayload = {
    kind: payload.kind,
    packageName,
    files: files.map((file) => markRaw(file)),
    relativePaths: Array.from(payload.relativePaths || files.map((file) => file.webkitRelativePath || file.name)),
    data: { ...(payload.data || {}) }
  }
  void runPackageBatch(batch, safePayload).finally(() => { safePayload.files.length = 0 })
  return batch.id
}

async function runScanBatch (batch, path, autoRole) {
  const item = batch.items[0]
  item.status = 'processing'
  item.progress = 5
  item.message = '正在后台递归扫描本机目录'
  touch(batch)
  try {
    const result = await api.scanFolder({ path, autoRole })
    batch.result = result
    item.status = 'done'
    item.progress = 100
    item.message = `扫描完成：新增 ${result?.imported || 0}，更新 ${result?.updated || 0}，跳过 ${result?.skipped || 0}，失败 ${result?.failed || 0}`
  } catch (error) {
    item.status = 'failed'
    item.progress = 100
    item.message = error?.message || '目录扫描失败'
  } finally {
    touch(batch)
    finish(batch)
  }
}

export function enqueueMaterialScan (path, autoRole = true) {
  const normalized = String(path || '').trim()
  if (!normalized) return null
  const item = createItem(null, normalized, 'material-scan')
  const batch = createBatch('scan', `扫描本机素材目录：${normalized}`, [item], { path: normalized })
  void runScanBatch(batch, normalized, autoRole)
  return batch.id
}

export function retryMaterialImportItem (batchId, itemId) {
  const batch = materialImportState.batches.find((candidate) => candidate.id === batchId)
  const item = batch?.items.find((candidate) => candidate.id === itemId)
  if (!batch || !item?.file || item.status !== 'failed' || batch.type !== 'files') return false
  batch.status = 'queued'
  batch.message = '失败素材已重新加入后台队列'
  void runFileItem(batch, item, batch.meta?.data || {}).then(() => finish(batch))
  return true
}

export function clearFinishedMaterialImports () {
  materialImportState.batches = materialImportState.batches.filter((batch) => !['done', 'failed'].includes(batch.status))
}

export function materialImportTaskRows () {
  return materialImportState.batches.map((batch) => ({
    id: batch.id,
    source: 'material-import',
    type: batch.type,
    rawStatus: batch.status,
    phase: batch.status,
    progress: batch.progress,
    label: batch.label,
    message: batch.message,
    createdAt: batch.createdAt,
    updatedAt: batch.updatedAt,
    canCancel: false,
    canRetry: batch.items.some((item) => item.status === 'failed' && item.file)
  }))
}
