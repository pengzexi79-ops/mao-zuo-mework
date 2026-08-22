<template>
  <div class="page media-tools-page">
    <el-alert type="info" :closable="false" show-icon title="可视化媒体工具">
      选择卡片即可预览图片、视频或音频。图片处理、视频切段和音频分离只处理已入库素材；原始文件不会被覆盖，结果会作为新素材入库。
    </el-alert>
    <el-alert v-if="!aiProviders.length" type="warning" :closable="false" show-icon title="本地媒体工具可直接使用；AI 图片、视频、配音尚未接入">
      请先在 AI 接入页保存有效 API Key，并点击“识别模型”。<el-button link type="primary" @click="$router.push('/ai')">前往 AI 接入</el-button>
    </el-alert>

    <div class="tool-location">
      <div><b>媒体工具输出目录</b><code>{{ outputLocation.path || '读取中…' }}</code><el-button link type="primary" @click="copyPath(outputLocation.path)">复制路径</el-button><el-button link type="primary" @click="openOutputDirectory">打开文件夹</el-button><el-button link type="primary" @click="locationVisible = true">设置</el-button></div>
      <div class="form-hint">所有新图片、视频片段、Auto-Editor 视频和 Demucs 音频都会先保存到此目录；默认同时登记到素材库，原文件不会覆盖。</div>
    </div>

    <el-collapse v-model="openTools" class="tool-collapse">
      <el-collapse-item title="图片工具" name="image">
        <section class="tool-panel">
          <VisualPicker
            :items="images"
            :selected="imageForm.materialId"
            title="选择图片素材"
            @select="imageForm.materialId = $event"
            @preview="openPreview"
          />
          <el-form label-position="top" class="image-tool-form">
            <el-form-item label="处理方式">
              <el-select v-model="imageForm.operation" style="width:100%">
                <el-option label="尺寸 / 旋转 / 翻转" value="resize" />
                <el-option label="移除背景（本机 rembg）" value="remove-background" />
              </el-select>
            </el-form-item>
            <div class="two-col">
              <el-form-item label="宽度"><el-input-number v-model="imageForm.width" :min="32" :max="4096" /></el-form-item>
              <el-form-item label="高度"><el-input-number v-model="imageForm.height" :min="32" :max="4096" /></el-form-item>
            </div>
            <div class="two-col">
              <el-form-item label="旋转"><el-select v-model="imageForm.rotate"><el-option v-for="n in [0, 90, 180, 270]" :key="n" :label="`${n}°`" :value="n" /></el-select></el-form-item>
              <el-form-item label="质量"><el-slider v-model="imageForm.quality" :min="10" :max="100" show-input /></el-form-item>
            </div>
            <el-checkbox v-model="imageForm.flipHorizontal">水平翻转</el-checkbox>
            <el-checkbox v-model="imageForm.flipVertical">垂直翻转</el-checkbox>
            <div class="panel-actions">
              <el-button type="primary" :loading="imageSubmitting" :disabled="!imageForm.materialId" @click="submitImage">生成图片</el-button>
            </div>
          </el-form>
        </section>
      </el-collapse-item>

      <el-collapse-item title="视频时间线 / 分割" name="video">
        <section class="tool-panel timeline-panel">
          <div class="timeline-editor">
            <VisualPicker
              :items="videos"
              :selected="timelineForm.materialId"
              title="选择视频素材"
              @select="selectTimelineMaterial"
              @preview="openPreview"
            />
            <div class="timeline-form">
              <template v-if="selectedTimelineVideo">
                <div class="timeline-playfield">
                  <video ref="timelinePlayer" class="timeline-preview" :src="api.materialPreviewUrl(selectedTimelineVideo.id)" controls preload="metadata" @loadedmetadata="onTimelineMetadata" @timeupdate="onTimelineTimeUpdate" />
                  <div class="timeline-strip" ref="timelineStrip" @pointerdown="onTimelineStripPointerDown">
                    <div class="timeline-strip-track" />
                    <div class="timeline-strip-played" :style="timelinePlayedStyle" />
                    <div class="timeline-strip-range" :style="timelineSourceRangeStyle" />
                    <div v-for="range in timelineForm.removeRanges" :key="`${range.start}-${range.end}`" class="timeline-strip-remove" :style="removeRangeStyle(range)" />
                    <button type="button" class="timeline-handle start" :style="timelineStartStyle" @pointerdown.stop.prevent="startTimelineDrag('start', $event)" />
                    <button type="button" class="timeline-handle end" :style="timelineEndStyle" @pointerdown.stop.prevent="startTimelineDrag('end', $event)" />
                    <button type="button" class="timeline-handle play" :style="timelinePlayheadStyle" @pointerdown.stop.prevent="startTimelineDrag('playhead', $event)" />
                  </div>
                  <div class="timeline-readout"><b>{{ formatSec(timelineCursor) }}</b><span>/ {{ formatSec(timelineDuration) }}</span><em>{{ selectedTimelineVideo.muteOriginalAudio ? '素材当前被静音标记' : '素材原声可用' }}</em></div>
                </div>
                <div class="timeline-editor-form">
                  <div class="timeline-bounds">
                    <el-form-item label="入点（秒）"><el-input-number v-model="timelineForm.sourceStart" :min="0" :max="timelineDuration" :step="0.1" :precision="2" /></el-form-item>
                    <el-form-item label="出点（秒）"><el-input-number v-model="timelineForm.sourceEnd" :min="0" :max="timelineDuration" :step="0.1" :precision="2" /></el-form-item>
                  </div>
                  <div class="timeline-actions"><el-button size="small" @click="setTimelineStart">当前时间设为入点</el-button><el-button size="small" @click="setTimelineEnd">当前时间设为出点</el-button></div>
                  <div class="timeline-bounds">
                    <el-form-item label="删除开始（秒）"><el-input-number v-model="pendingRemove.start" :min="timelineForm.sourceStart" :max="timelineForm.sourceEnd" :step="0.1" :precision="2" /></el-form-item>
                    <el-form-item label="删除结束（秒）"><el-input-number v-model="pendingRemove.end" :min="timelineForm.sourceStart" :max="timelineForm.sourceEnd" :step="0.1" :precision="2" /></el-form-item>
                  </div>
                  <div class="timeline-actions"><el-button size="small" type="danger" plain @click="addRemoveRange">加入删除区间</el-button><span class="form-hint">删除区间外的内容会被保留并重新拼接</span></div>
                  <div v-if="timelineForm.removeRanges.length" class="remove-ranges"><span v-for="(range, index) in timelineForm.removeRanges" :key="`${range.start}-${range.end}`"><b>{{ formatSec(range.start) }} - {{ formatSec(range.end) }}</b><el-button link type="danger" @click="removeTimelineRange(index)">移除</el-button></span></div>
                  <div class="timeline-options">
                    <el-form-item label="输出音轨" class="compact-form-item"><el-radio-group v-model="timelineForm.audioMode"><el-radio label="keep-original-audio">保留原声</el-radio><el-radio label="unmute">解除静音</el-radio><el-radio label="silent">输出静音</el-radio></el-radio-group></el-form-item>
                    <el-form-item label="处理结果" class="compact-form-item"><el-radio-group v-model="timelineForm.resultPolicy"><el-radio label="library_keep_original">入库并保留原文件</el-radio><el-radio label="computer_only">仅保存到电脑</el-radio><el-radio label="library_delete_original">入库后删除原素材</el-radio></el-radio-group></el-form-item>
                  </div>
                  <el-alert v-if="timelineForm.resultPolicy === 'library_delete_original'" class="delete-source-alert" type="warning" :closable="false" title="删除只会在新结果成功入库后执行">
                    <el-checkbox v-model="timelineForm.deleteSourceConfirmed">我确认删除当前源素材；被进行中任务引用或不在应用管理目录的文件会被保留。</el-checkbox>
                  </el-alert>
                  <div class="panel-actions"><el-button type="primary" :loading="timelineSubmitting" :disabled="timelineForm.resultPolicy === 'library_delete_original' && !timelineForm.deleteSourceConfirmed" @click="submitTimeline">生成时间线结果</el-button></div>
                </div>
              </template>
              <p v-else class="form-hint">选择视频后可拖动进度，设置入点和出点，并加入多个要删除的区间。生成的是新文件，原视频不会覆盖。</p>
            </div>
          </div>
        </section>
        <section class="quick-split">
          <span>快速均分切段</span><el-input-number v-model="splitSec" :min="1" :max="15" :step="0.5" /><el-button :loading="splitSubmitting" :disabled="!timelineForm.materialId" @click="submitSplit">按秒数切段并入库</el-button>
        </section>
      </el-collapse-item>

      <el-collapse-item title="字幕遮盖（图片 / 视频）" name="subtitle-cover">
        <section class="tool-panel cover-panel">
          <VisualPicker :items="coverSources" :selected="coverForm.materialId" title="选择待处理素材" @select="coverForm.materialId = $event" @preview="openPreview" />
          <el-form label-position="top">
            <el-alert type="warning" :closable="false" title="这是有损遮盖，不是无损去字幕" description="烧录到画面的字幕无法恢复原始像素；请确认矩形位置后生成新文件，原文件会保留。" />
            <div class="two-col">
              <el-form-item label="左（像素）"><el-input-number v-model="coverForm.x" :min="0" :max="10000" /></el-form-item>
              <el-form-item label="上（像素）"><el-input-number v-model="coverForm.y" :min="0" :max="10000" /></el-form-item>
            </div>
            <div class="two-col">
              <el-form-item label="宽（像素）"><el-input-number v-model="coverForm.width" :min="1" :max="10000" /></el-form-item>
              <el-form-item label="高（像素）"><el-input-number v-model="coverForm.height" :min="1" :max="10000" /></el-form-item>
            </div>
            <div class="two-col">
              <el-form-item label="颜色"><el-input v-model="coverForm.color" placeholder="black 或 white@0.85" /></el-form-item>
              <template v-if="coverSourceIsVideo">
                <el-form-item label="开始 / 结束（秒）"><div class="two-col"><el-input-number v-model="coverForm.start" :min="0" :step="0.1" /><el-input-number v-model="coverForm.end" :min="0.1" :step="0.1" /></div></el-form-item>
              </template>
            </div>
            <div class="panel-actions"><el-button type="primary" :loading="coverSubmitting" :disabled="!coverForm.materialId" @click="submitCover">确认区域并生成新素材</el-button></div>
          </el-form>
        </section>
      </el-collapse-item>

      <el-collapse-item title="智能剪除静音 / 废片" name="trim">
        <section class="tool-panel">
          <VisualPicker
            :items="videos"
            :selected="trimMaterialId"
            title="选择视频素材"
            @select="trimMaterialId = $event"
            @preview="openPreview"
          />
          <el-form label-position="top">
            <p class="form-hint">使用随包 Auto-Editor 识别并剪除长静音区间。输出会作为新视频素材入库，原视频保持不变。</p>
            <div class="panel-actions">
              <el-button type="primary" :loading="trimSubmitting" :disabled="!trimMaterialId" @click="submitAutoTrim">智能剪除并入库</el-button>
            </div>
          </el-form>
        </section>
      </el-collapse-item>

      <el-collapse-item title="音频分离" name="audio">
        <section class="tool-panel">
          <VisualPicker
            :items="audioSources"
            :selected="separateMaterialId"
            title="选择音频或视频"
            @select="separateMaterialId = $event"
            @preview="openPreview"
          />
          <el-form label-position="top">
            <p class="form-hint">使用本机 Demucs 分离人声与伴奏。完成后两条生成音频会自动进入素材库。</p>
            <div class="panel-actions">
              <el-button type="primary" :loading="separateSubmitting" :disabled="!separateMaterialId" @click="submitSeparation">开始后台分离</el-button>
            </div>
          </el-form>
        </section>
      </el-collapse-item>
    </el-collapse>

    <section class="tool-panel tasks-panel">
      <div class="panel-title-row">
        <div class="panel-title">媒体任务</div>
        <el-button size="small" @click="loadTasks">刷新</el-button>
      </div>
      <div v-if="!tasks.length" class="muted">暂无媒体任务</div>
      <div class="task-list">
        <article v-for="row in tasks" :key="row.id" class="task-card">
          <div class="task-summary">
            <div><b>{{ taskKind(row.kind) }}</b><span class="muted">{{ row.engine || '本地引擎' }}</span></div>
            <div class="task-summary-actions"><el-tag :type="taskType(row.status)">{{ taskLabel(row.status) }}</el-tag><el-popconfirm v-if="['pending','running'].includes(row.status)" title="取消此媒体任务？已生成的源素材不会删除。" @confirm="cancelTask(row)"><template #reference><el-button link type="warning" size="small">取消</el-button></template></el-popconfirm></div>
          </div>
          <el-progress :percentage="row.progress || 0" :status="row.status === 'failed' ? 'exception' : row.status === 'done' ? 'success' : undefined" />
          <p class="task-message">{{ row.message || '等待处理' }}</p>
          <div class="task-path"><span>输出目录</span><code>{{ row.outputDirectory || outputLocation.path || '历史任务未记录路径' }}</code><el-button v-if="row.outputDirectory || outputLocation.path" link type="primary" @click="copyPath(row.outputDirectory || outputLocation.path)">复制</el-button></div>
          <div v-if="row.results?.length" class="result-grid">
            <article v-for="item in row.results" :key="item.materialId || item.filePath" class="result-card">
              <button type="button" class="result-preview" @click="openResultPreview(item)">
                <img v-if="item.fileType !== 'audio'" :src="item.previewUrl || api.materialPreviewUrl(item.materialId)" alt="结果预览" @error="hideImage" />
                <span v-else class="audio-art">音频</span>
              </button>
              <b>{{ item.name || '未命名结果' }}</b>
              <span class="muted">{{ item.fileType }} · {{ item.inLibrary ? `已入素材库 #${item.materialId}` : '仅保存到电脑' }}</span>
              <code class="result-path">{{ item.filePath || '历史任务未记录文件路径' }}</code>
              <div class="result-actions"><el-button v-if="item.materialId" link type="primary" @click="openResultPreview(item)">应用内预览</el-button><el-button v-if="item.materialId" link type="primary" @click="openMaterialLibrary(item.materialId)">素材库</el-button><el-button v-if="item.filePath" link type="primary" @click="copyPath(item.filePath)">复制电脑路径</el-button></div>
            </article>
          </div>
          <div v-else-if="row.resultPaths?.length" class="legacy-paths"><code v-for="path in row.resultPaths" :key="path">{{ path }}</code></div>
        </article>
      </div>
    </section>

    <el-dialog v-model="previewVisible" :title="preview?.name || '素材预览'" width="760px" destroy-on-close>
      <img v-if="preview?.fileType === 'image'" class="preview-media" :src="api.materialPreviewUrl(preview.id)" alt="素材预览" />
      <video v-else-if="preview?.fileType === 'video'" class="preview-media" :src="api.materialPreviewUrl(preview.id)" controls autoplay />
      <audio v-else-if="preview" class="preview-audio" :src="api.materialPreviewUrl(preview.id)" controls autoplay />
      <p v-if="preview" class="form-hint">{{ preview.role || '未分类' }} · {{ preview.durationSec ? `${Number(preview.durationSec).toFixed(1)} 秒` : preview.fileType }}</p>
    </el-dialog>

    <el-dialog v-model="locationVisible" title="后续媒体工具保存位置" width="560px">
      <el-alert type="info" :closable="false" show-icon title="只影响之后的图片工具、切段和音频分离；既有文件不会移动或删除。" />
      <el-radio-group v-model="outputLocation.mode" style="margin:16px 0;display:flex;gap:16px;flex-wrap:wrap">
        <el-radio label="default">默认应用目录</el-radio>
        <el-radio label="desktop">桌面 / Mework Media</el-radio>
        <el-radio label="custom">自定义目录</el-radio>
      </el-radio-group>
      <el-input v-if="outputLocation.mode === 'custom'" v-model="outputLocation.customPath" placeholder="本机绝对路径，例如 D:\Mework Media" />
      <template #footer>
        <el-button @click="locationVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingLocation" @click="saveLocation">保存位置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import VisualPicker from '../components/media/VisualPicker.vue'

// Keep every tool collapsed on entry; users may open one or several panels explicitly.
const openTools = ref([])
const materials = ref([])
const tasks = ref([])
const aiProviders = ref([])
const imageSubmitting = ref(false)
const splitSubmitting = ref(false)
const timelineSubmitting = ref(false)
const coverSubmitting = ref(false)
const trimSubmitting = ref(false)
const separateSubmitting = ref(false)
const trimMaterialId = ref(null)
const separateMaterialId = ref(null)
const splitSec = ref(3)
const timelinePlayer = ref(null)
const timelineCursor = ref(0)
const timelineDuration = ref(0)
const timelineDrag = reactive({ active: false, mode: '', pointerId: null, rect: null })
const pendingRemove = reactive({ start: 0, end: 1 })
const timelineForm = reactive({ materialId: null, sourceStart: 0, sourceEnd: 0, removeRanges: [], audioMode: 'keep-original-audio', resultPolicy: 'library_keep_original', deleteSourceConfirmed: false })
const coverForm = reactive({ materialId: null, x: 0, y: 0, width: 320, height: 120, color: 'black@1.0', start: 0, end: 5 })
const preview = ref(null)
const previewVisible = ref(false)
const locationVisible = ref(false)
const savingLocation = ref(false)
const outputLocation = reactive({ path: '', mode: 'default', customPath: '' })
const imageForm = reactive({ materialId: null, operation: 'resize', width: 1080, height: 1920, rotate: 0, quality: 90, flipHorizontal: false, flipVertical: false })
let pollTimer = null

const images = computed(() => materials.value.filter(item => item.fileType === 'image' && item.status === 'ready'))
const videos = computed(() => materials.value.filter(item => item.fileType === 'video' && item.status === 'ready'))
const audioSources = computed(() => materials.value.filter(item => ['audio', 'video'].includes(item.fileType) && item.status === 'ready'))
const coverSources = computed(() => materials.value.filter(item => ['image', 'video'].includes(item.fileType) && item.status === 'ready'))
const selectedTimelineVideo = computed(() => videos.value.find(item => item.id === timelineForm.materialId) || null)
const coverSourceIsVideo = computed(() => coverSources.value.find(item => item.id === coverForm.materialId)?.fileType === 'video')
const timelineStrip = ref(null)
const timelineSourceRangeStyle = computed(() => timelineRangeStyle(timelineForm.sourceStart, timelineForm.sourceEnd))
const timelineStartStyle = computed(() => handleStyle(timelineForm.sourceStart))
const timelineEndStyle = computed(() => handleStyle(timelineForm.sourceEnd))
const timelinePlayheadStyle = computed(() => handleStyle(timelineCursor.value))
const timelinePlayedStyle = computed(() => ({ left: '12px', width: `calc(${percent(timelineCursor.value)}% - 12px)` }))

function taskLabel (status) { return ({ pending: '排队中', running: '处理中', done: '已完成', failed: '失败' })[status] || status || '未知' }
function taskType (status) { return ({ pending: 'warning', running: 'primary', done: 'success', failed: 'danger' })[status] || 'info' }
function taskKind (kind) { return ({ image: '图片处理', 'video-split': '视频分割', 'video-timeline': '时间线编辑', 'subtitle-cover': '字幕遮盖', 'auto-trim': '智能剪除', 'audio-separate': '音频分离' })[kind] || kind || '媒体任务' }
function thumb (item) { return api.protectedUrl(item.thumbnailUrl || item.thumbnail || `/api/materials/${item.id}/preview`) }
function hideImage (event) { event.currentTarget.style.display = 'none' }
function percent (value) {
  const total = Math.max(0.1, timelineDuration.value || 0.1)
  const v = Math.max(0, Math.min(total, Number(value || 0)))
  return Math.max(0, Math.min(100, (v / total) * 100))
}
function handleStyle (value) {
  const pct = percent(value)
  return { left: `${pct}%` }
}
function timelineRangeStyle (start, end) {
  const left = percent(start)
  const right = percent(end)
  return { left: `${left}%`, width: `${Math.max(0.5, right - left)}%` }
}
function clampTime (value) {
  return Math.max(0, Math.min(timelineDuration.value || 0, Number(value || 0)))
}
function snapTime (value) {
  return Math.round(clampTime(value) * 100) / 100
}
function timelineValueFromClient (clientX) {
  const el = timelineStrip.value
  if (!el) return 0
  const rect = el.getBoundingClientRect()
  const ratio = rect.width <= 0 ? 0 : Math.max(0, Math.min(1, (clientX - rect.left) / rect.width))
  return ratio * (timelineDuration.value || 0)
}
function updateTimelineByMode (mode, value) {
  const next = snapTime(value)
  if (mode === 'start') {
    timelineForm.sourceStart = Math.min(next, Math.max(0, timelineForm.sourceEnd - 0.1))
    if (pendingRemove.start < timelineForm.sourceStart) pendingRemove.start = timelineForm.sourceStart
  } else if (mode === 'end') {
    timelineForm.sourceEnd = Math.max(next, timelineForm.sourceStart + 0.1)
    if (pendingRemove.end > timelineForm.sourceEnd) pendingRemove.end = timelineForm.sourceEnd
  } else if (mode === 'playhead') {
    timelineCursor.value = next
    if (timelinePlayer.value) timelinePlayer.value.currentTime = next
  }
}
function startTimelineDrag (mode, event) {
  if (!timelineDuration.value) return
  timelineDrag.active = true
  timelineDrag.mode = mode
  timelineDrag.pointerId = event.pointerId
  timelineDrag.rect = timelineStrip.value?.getBoundingClientRect?.() || null
  event.currentTarget?.setPointerCapture?.(event.pointerId)
  updateTimelineByMode(mode, timelineValueFromClient(event.clientX))
  window.addEventListener('pointermove', onTimelineDragMove)
  window.addEventListener('pointerup', stopTimelineDrag, { once: true })
}
function onTimelineDragMove (event) {
  if (!timelineDrag.active) return
  if (timelineDrag.pointerId != null && event.pointerId != null && event.pointerId !== timelineDrag.pointerId) return
  updateTimelineByMode(timelineDrag.mode, timelineValueFromClient(event.clientX))
}
function stopTimelineDrag () {
  timelineDrag.active = false
  timelineDrag.mode = ''
  timelineDrag.pointerId = null
  timelineDrag.rect = null
  window.removeEventListener('pointermove', onTimelineDragMove)
}
function onTimelineStripPointerDown (event) {
  if (event.target && event.target.closest && event.target.closest('.timeline-handle')) return
  updateTimelineByMode('playhead', timelineValueFromClient(event.clientX))
  if (timelinePlayer.value) timelinePlayer.value.currentTime = timelineCursor.value
}
function removeRangeStyle (range) {
  return timelineRangeStyle(range?.start || 0, range?.end || 0)
}

function openPreview (item) { preview.value = item; previewVisible.value = true }
function openResultPreview (item) {
  if (!item?.materialId) return ElMessage.info('该结果未登记到素材库，只能使用电脑文件路径')
  preview.value = { id: item.materialId, name: item.name, fileType: item.fileType }
  previewVisible.value = true
}
function openMaterialLibrary (materialId) {
  ElMessage.success(`结果已在素材库中，可按素材编号 #${materialId} 查找`)
  window.location.hash = '#/materials'
}
async function openOutputDirectory () {
  try {
    await api.mediaToolOpenOutputDirectory()
    ElMessage.success('已打开媒体工具输出目录')
  } catch (error) {
    ElMessage.error(`打开输出目录失败：${error.message}`)
  }
}
async function copyPath (path) {
  if (!path) return ElMessage.info('暂无可复制的输出路径')
  try {
    await navigator.clipboard.writeText(path)
    ElMessage.success('路径已复制')
  } catch {
    ElMessage.warning('浏览器不允许直接复制，请手动选择路径')
  }
}

async function cancelTask (row) {
  try { await api.cancelMediaToolTask(row.id); await loadTasks(); ElMessage.success('媒体任务已取消') }
  catch (error) { ElMessage.error(error.message || '取消媒体任务失败') }
}

async function loadTasks () {
  try {
    tasks.value = await api.mediaToolTasks({ silent: true })
  } catch (error) {
    ElMessage.error(`读取媒体任务失败：${error.message}`)
  }
}

async function loadMaterials () {
  try {
    materials.value = await api.materials({}, { silent: true })
  } catch (error) {
    ElMessage.error(`读取素材失败：${error.message}`)
  }
}

async function loadLocation () {
  try {
    const value = await api.mediaToolOutputLocation()
    outputLocation.path = value.path || ''
    outputLocation.mode = value.mode || 'default'
  } catch {}
}

async function saveLocation () {
  savingLocation.value = true
  try {
    const value = await api.saveMediaToolOutputLocation({ mode: outputLocation.mode, path: outputLocation.customPath, confirm: true })
    outputLocation.path = value.path
    locationVisible.value = false
    ElMessage.success('后续媒体工具保存位置已更新')
  } catch (error) {
    ElMessage.error(`保存位置失败：${error.message}`)
  } finally {
    savingLocation.value = false
  }
}

async function submitImage () {
  imageSubmitting.value = true
  try {
    await api.mediaToolImage({ ...imageForm })
    ElMessage.success('图片处理任务已进入后台')
    await loadTasks()
    startPolling()
  } catch (error) {
    ElMessage.error(`提交图片任务失败：${error.message}`)
  } finally {
    imageSubmitting.value = false
  }
}

function selectTimelineMaterial (materialId) {
  timelineForm.materialId = materialId
  const video = videos.value.find(item => item.id === materialId)
  timelineDuration.value = Number(video?.durationSec || 0)
  timelineCursor.value = 0
  timelineForm.sourceStart = 0
  timelineForm.sourceEnd = timelineDuration.value
  timelineForm.removeRanges = []
  timelineForm.deleteSourceConfirmed = false
  pendingRemove.start = 0
  pendingRemove.end = Math.min(1, timelineDuration.value || 1)
  requestAnimationFrame(() => { if (timelinePlayer.value) timelinePlayer.value.currentTime = 0 })
}

function onTimelineMetadata (event) {
  const duration = Number(event.target.duration)
  if (!Number.isFinite(duration) || duration <= 0) return
  timelineDuration.value = duration
  if (!timelineForm.sourceEnd || timelineForm.sourceEnd > duration) timelineForm.sourceEnd = duration
  pendingRemove.end = Math.min(Math.max(pendingRemove.end, pendingRemove.start + 0.1), duration)
}

function onTimelineTimeUpdate (event) { timelineCursor.value = Number(event.target.currentTime || 0) }
function seekTimeline (value) { if (timelinePlayer.value) timelinePlayer.value.currentTime = Number(value || 0) }
function formatSec (value) {
  const seconds = Math.max(0, Number(value || 0))
  const minutes = Math.floor(seconds / 60)
  return `${String(minutes).padStart(2, '0')}:${(seconds % 60).toFixed(2).padStart(5, '0')}`
}
function setTimelineStart () { timelineForm.sourceStart = Math.min(timelineCursor.value, Math.max(0, timelineForm.sourceEnd - 0.1)) }
function setTimelineEnd () { timelineForm.sourceEnd = Math.max(timelineCursor.value, timelineForm.sourceStart + 0.1) }
function addRemoveRange () {
  const start = Number(pendingRemove.start)
  const end = Number(pendingRemove.end)
  if (!Number.isFinite(start) || !Number.isFinite(end) || end - start < 0.05) return ElMessage.warning('删除区间至少需要 0.05 秒')
  if (start < timelineForm.sourceStart || end > timelineForm.sourceEnd) return ElMessage.warning('删除区间必须位于入点和出点之间')
  if (timelineForm.removeRanges.some(item => start < item.end - 0.001 && end > item.start + 0.001)) return ElMessage.warning('删除区间不能重叠')
  timelineForm.removeRanges.push({ start, end })
  timelineForm.removeRanges.sort((a, b) => a.start - b.start)
  pendingRemove.start = start
  pendingRemove.end = Math.min(timelineForm.sourceEnd, end + 1)
}
function removeTimelineRange (index) { timelineForm.removeRanges.splice(index, 1) }

async function submitTimeline () {
  if (!timelineForm.materialId) return ElMessage.warning('请选择视频素材')
  if (timelineForm.sourceEnd - timelineForm.sourceStart < 0.1) return ElMessage.warning('入点和出点至少需要保留 0.1 秒')
  if (timelineForm.resultPolicy === 'library_delete_original' && !timelineForm.deleteSourceConfirmed) return ElMessage.warning('请先确认删除当前源素材')
  timelineSubmitting.value = true
  try {
    await api.mediaToolTimeline({ ...timelineForm, removeRanges: timelineForm.removeRanges.map(({ start, end }) => ({ start, end })) })
    ElMessage.success('时间线处理任务已进入后台')
    await loadTasks()
    startPolling()
  } catch (error) {
    ElMessage.error(`时间线处理失败：${error.message}`)
  } finally {
    timelineSubmitting.value = false
  }
}

async function submitSplit () {
  splitSubmitting.value = true
  try {
    await api.mediaToolSplit({ materialId: timelineForm.materialId, clipSec: splitSec.value })
    ElMessage.success('视频切段任务已进入后台')
    await loadTasks()
    startPolling()
  } catch (error) {
    ElMessage.error(`视频切段失败：${error.message}`)
  } finally {
    splitSubmitting.value = false
  }
}

async function submitCover () {
  if (!coverForm.materialId) return ElMessage.warning('请选择图片或视频素材')
  if (coverSourceIsVideo.value && Number(coverForm.end) <= Number(coverForm.start)) return ElMessage.warning('视频遮盖结束时间必须大于开始时间')
  coverSubmitting.value = true
  try {
    await api.mediaToolSubtitleCover({ ...coverForm, start: coverSourceIsVideo.value ? coverForm.start : 0, end: coverSourceIsVideo.value ? coverForm.end : 1 })
    ElMessage.success('字幕遮盖任务已进入后台，原素材未覆盖')
    await loadTasks()
    startPolling()
  } catch (error) {
    ElMessage.error(`字幕遮盖失败：${error.message}`)
  } finally {
    coverSubmitting.value = false
  }
}

async function submitAutoTrim () {
  trimSubmitting.value = true
  try {
    await api.mediaToolAutoTrim({ materialId: trimMaterialId.value })
    ElMessage.success('智能剪除任务已进入后台')
    await loadTasks()
    startPolling()
  } catch (error) {
    ElMessage.error(`智能剪除失败：${error.message}`)
  } finally {
    trimSubmitting.value = false
  }
}

async function submitSeparation () {
  separateSubmitting.value = true
  try {
    await api.mediaToolSeparate({ materialId: separateMaterialId.value })
    ElMessage.success('音频分离任务已进入后台')
    await loadTasks()
    startPolling()
  } catch (error) {
    ElMessage.error(`提交音频分离失败：${error.message}`)
  } finally {
    separateSubmitting.value = false
  }
}

function startPolling () {
  if (pollTimer) return
  pollTimer = window.setInterval(async () => {
    await loadTasks()
    if (!tasks.value.some(task => ['pending', 'running'].includes(task.status))) {
      clearInterval(pollTimer)
      pollTimer = null
      await loadMaterials()
    }
  }, 2500)
}

onMounted(async () => {
  await Promise.all([loadMaterials(), loadTasks(), loadLocation(), loadAiProviders()])
  if (tasks.value.some(task => ['pending', 'running'].includes(task.status))) startPolling()
})

async function loadAiProviders () {
  try {
    aiProviders.value = await api.aiMediaProviders()
  } catch {
    aiProviders.value = []
  }
}

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
  stopTimelineDrag()
})
</script>

<style scoped>
.media-tools-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.tool-location {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.tool-location code {
  margin: 0 8px;
  overflow-wrap: anywhere;
}

.tool-collapse {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.tool-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  width: 100%;
  max-width: 100%;
  overflow: hidden;
  background: var(--el-bg-color-overlay, #fff);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 14px;
  box-sizing: border-box;
}
.tool-panel:not(.timeline-panel):not(.tasks-panel) {
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 680px);
  justify-content: start;
  gap: 22px;
  align-items: start;
}
.tool-panel > .visual-picker,
.tool-panel > .el-form,
.tool-panel > .timeline-editor,
.tool-panel > .quick-split { width: 100%; min-width: 0; max-width: 100%; }
.tool-panel > .el-form { min-height: 0; }
.tool-panel:not(.timeline-panel):not(.tasks-panel) > .el-form { max-width: 680px; }
.tasks-panel { display: block; }

.panel-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.panel-title {
  font-weight: 700;
  margin-bottom: 12px;
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.two-col .el-input-number,
.two-col .el-select {
  width: 100%;
}

.timeline-panel { gap: 12px; }
.timeline-editor {
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 920px);
  justify-content: start;
  gap: 22px;
  width: 100%;
  min-width: 0;
  align-items: start;
}
.timeline-form { min-width: 0; max-width: 920px; }
.timeline-editor-form { max-width: 760px; }
.timeline-preview {
  display: block;
  width: 100%;
  height: min(58vh, 460px);
  max-height: 460px;
  min-height: 260px;
  object-fit: contain;
  background: #111;
  border-radius: 6px;
}
.timeline-playfield {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.timeline-strip {
  position: relative;
  width: 100%;
  height: 54px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: linear-gradient(180deg, #f8fafc 0%, #eef2f7 100%);
  overflow: hidden;
  cursor: pointer;
}
.timeline-strip-track {
  position: absolute;
  left: 12px;
  right: 12px;
  top: 24px;
  height: 6px;
  border-radius: 999px;
  background: #dbe2ee;
}
.timeline-strip-played {
  position: absolute;
  left: 12px;
  top: 24px;
  height: 6px;
  border-radius: 999px;
  background: #409eff;
}
.timeline-strip-range {
  position: absolute;
  top: 10px;
  bottom: 10px;
  border-radius: 5px;
  background: rgba(64, 158, 255, 0.18);
  border: 1px solid rgba(64, 158, 255, 0.38);
}
.timeline-strip-remove {
  position: absolute;
  top: 14px;
  bottom: 14px;
  border-radius: 4px;
  background: rgba(245, 108, 108, 0.34);
}
.timeline-handle {
  position: absolute;
  top: 10px;
  width: 14px;
  height: 34px;
  margin-left: -7px;
  border-radius: 999px;
  background: #fff;
  border: 2px solid #409eff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.18);
  cursor: ew-resize;
}
.timeline-handle.end {
  border-color: #67c23a;
}
.timeline-handle.play {
  border-color: #e6a23c;
}
.timeline-readout {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
  margin: 2px 0 0;
  font: 12px/1.4 Consolas, Monaco, monospace;
}
.timeline-readout em { margin-left: auto; color: var(--el-text-color-secondary); font: 12px/1.4 inherit; }
.timeline-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin: -4px 0 8px; }
.remove-range-inputs { margin-top: 4px; }
.remove-ranges { display: flex; flex-direction: column; gap: 4px; margin: 4px 0 12px; padding: 7px 8px; background: var(--el-fill-color-light); border-radius: 4px; }
.remove-ranges span { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-width: 0; }
.quick-split { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 0 2px 2px; color: var(--el-text-color-secondary); font-size: 12px; }
.compact-form-item { margin-bottom: 10px; }
.timeline-bounds,
.timeline-options {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 10px;
}
.timeline-options .compact-form-item { margin-bottom: 0; }
.timeline-editor-form { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.cover-panel {
  display: flex;
  flex-direction: column;
}
.cover-panel .el-form { display: flex; flex-direction: column; gap: 10px; }
.cover-panel .two-col { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
.cover-panel .el-alert { line-height: 1.6; }
.image-tool-form { display: flex; flex-direction: column; gap: 10px; }
.image-tool-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; }
.image-tool-grid .el-form-item { margin-bottom: 0; }
.delete-source-alert { margin-top: 2px; }
.delete-source-alert :deep(.el-checkbox) { align-items: flex-start; line-height: 1.5; white-space: normal; }

.panel-actions {
  margin-top: 14px;
}

.tasks-panel {
  min-height: 180px;
}

.form-hint {
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  font-size: 13px;
}

.task-list { display: flex; flex-direction: column; gap: 10px; }
.task-card { min-width: 0; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-bg-color-overlay, #fff); overflow: hidden; }
.task-summary-actions { display:flex; align-items:center; gap:6px; }.task-summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.task-summary div { display: flex; align-items: center; gap: 10px; min-width: 0; }
.task-message { margin: 8px 0; color: var(--el-text-color-regular); overflow-wrap: anywhere; }
.task-path { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 8px; align-items: center; padding: 8px; background: var(--el-fill-color-light); border-radius: 4px; font-size: 12px; }
.task-path code, .result-path, .legacy-paths code { min-width: 0; overflow-wrap: anywhere; white-space: normal; color: var(--el-text-color-secondary); }
.result-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; margin-top: 10px; }
.result-card { display: flex; flex-direction: column; gap: 5px; min-width: 0; padding: 8px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.result-preview { width: 100%; aspect-ratio: 16 / 9; min-height: 90px; padding: 0; border: 0; border-radius: 4px; overflow: hidden; background: #f3f5f8; cursor: pointer; }
.result-preview img { width: 100%; height: 100%; object-fit: contain; }
.result-preview .audio-art { width: 100%; height: 100%; display: grid; place-items: center; color: var(--el-color-primary); }
.result-path { font: 11px/1.45 Consolas, Monaco, monospace; }
.result-actions { display: flex; flex-wrap: wrap; gap: 4px; }
.legacy-paths { display: flex; flex-direction: column; gap: 4px; margin-top: 10px; }

.preview-media {
  display: block;
  width: 100%;
  max-height: 60vh;
  object-fit: contain;
  background: #111;
}

.preview-audio {
  width: 100%;
}

@media (max-width: 1080px) {
  .tool-panel:not(.timeline-panel):not(.tasks-panel),
  .timeline-editor { grid-template-columns: minmax(220px, 280px) minmax(0, 1fr); gap: 14px; }
  .tool-panel:not(.timeline-panel):not(.tasks-panel) > .el-form { max-width: 620px; }
  .timeline-preview { height: min(52vh, 360px); min-height: 220px; }
}

@media (max-width: 840px) {
  .tool-panel:not(.timeline-panel):not(.tasks-panel),
  .timeline-editor { grid-template-columns: minmax(190px, 240px) minmax(0, 1fr); gap: 12px; }
  .timeline-editor-form { max-width: none; }
}

@media (max-width: 720px) {
  .media-tools-page { width: 100%; min-width: 0; }
  .tool-panel,
  .tool-panel:not(.timeline-panel):not(.tasks-panel),
  .timeline-editor { display: flex; flex-direction: column; padding: 10px; }
  .two-col,
  .timeline-bounds,
  .timeline-options { grid-template-columns: 1fr; }
  .timeline-preview { height: min(60vh, 320px); min-height: 200px; max-height: 320px; }
  .task-path { grid-template-columns: 1fr; }
  .task-path code { display: block; }
  .result-grid { grid-template-columns: 1fr; }
}
</style>
