<template>
  <div>
    <div class="card">
      <div class="card-title outputs-toolbar">
        <div class="outputs-summary">成片库 <span class="hint">共 {{ list.length }} 条，可直接播放、下载，或按任务筛选</span></div>
        <span class="output-location">保存位置：<code>{{ outputLocation.path || '读取中…' }}</code><el-button size="small" link type="primary" @click="locationVisible = true">设置</el-button></span>
        <span class="outputs-spacer"></span>
        <el-select v-model="jobFilter" clearable size="small" style="width:200px" placeholder="全部任务">
          <el-option v-for="j in jobs" :key="j.id" :label="`#${j.id} ${j.name || ''}`" :value="j.id" />
        </el-select>
        <el-button size="small" style="margin-left:8px" :loading="scanning" title="扫描输出目录中的已有成片并补录到成片库，不导入原始素材" @click="scanCandidates">扫描输出目录中的成片</el-button>
        <el-button size="small" @click="load">刷新</el-button>
        <el-button v-if="selectedIds.length" size="small" type="danger" plain :loading="batchDeleting" style="margin-left:8px" @click="batchDelete">批量删除（{{ selectedIds.length }}）</el-button>
        <el-button v-if="selectedIds.length" size="small" type="primary" plain style="margin-left:8px" @click="batchDownload">批量下载（{{ selectedIds.length }}）</el-button>
        <el-checkbox :model-value="allChecked" :indeterminate="someChecked" style="margin-left:12px" @change="toggleAll">全选</el-checkbox>
      </div>

      <el-alert v-if="loadError" type="error" :closable="false" show-icon style="margin-bottom:10px"
        title="成片库加载失败，请刷新重试" />
      <div v-else-if="loading" v-loading="loading" style="height:80px"></div>
      <div v-else-if="!filtered.length" class="muted">还没有成片。去「出片控制台」跑一批。</div>

      <section v-if="!loading && !loadError" class="output-gallery" aria-label="成片作品浏览">
        <article v-for="o in filtered" :key="o.id" class="output-card">
          <div class="output-media" :class="mediaClass(o)">
            <el-checkbox v-model="selectedIds" :label="o.id" class="card-select" @click.stop></el-checkbox>
            <video
              v-if="o.filePath && !videoErrors[o.id]"
              :src="fileUrl(o)"
              :poster="thumbUrl(o.thumbnail)"
              controls
              preload="metadata"
              @play="onVideoPlay($event.currentTarget)"
              @error="onVideoError(o)"
              @loadedmetadata="onVideoMetadata(o, $event)"
              @loadeddata="onVideoLoaded(o)"
            ></video>
            <div v-else-if="o.filePath && videoErrors[o.id]" class="video-play-error">
              <span class="video-play-error-icon">!</span>
              <b>无法播放该成片</b>
              <span>文件可能缺失、被移动或无访问权限，请复制路径核对或刷新后重试。</span>
            </div>
            <div v-else class="output-blocked muted">该条已被成品质检拦截，未保留可播放文件。</div>
          </div>
          <div class="output-info">
            <div class="output-title">{{ o.filePath ? baseName(o.filePath) : `任务 #${o.jobId} 第 ${o.idx} 条质检诊断` }}</div>
            <div class="output-tags">
              <el-tag size="small" :type="o.qcStatus === 'pass' ? 'success' : o.qcStatus === 'warn' ? 'warning' : 'danger'">{{ qcLabel(o.qcStatus) }}</el-tag>
              <el-tag v-if="o.hookStrategy" size="small" effect="plain">{{ strategyLabel(o.hookStrategy) }}</el-tag>
              <el-tag v-if="o.retryCount > 0" size="small" type="info">重试 {{ o.retryCount }} 次</el-tag>
            </div>
            <div class="output-meta muted">
              任务 #{{ o.jobId }} · 第 {{ o.idx }} 条 · {{ o.durationSec ? o.durationSec.toFixed(1) + 's' : '-' }}
              <span :style="{ color: durColor(o.durationSec) }">{{ durTip(o.durationSec) }}</span>
            </div>
            <div class="output-actions">
              <el-button size="small" link type="primary" @click="openQc(o)">质检</el-button>
              <el-button v-if="o.jobId" size="small" link :type="o.qcStatus === 'fail' ? 'warning' : 'info'" @click="openRepair(o)">{{ o.qcStatus === 'fail' ? '修复' : '版本' }}</el-button>
              <el-link v-if="o.filePath" class="output-action-link" type="primary" :href="api.downloadUrl(o.id)" target="_blank">下载</el-link>
              <el-button v-if="o.filePath" size="small" link @click="copyPath(o.filePath)">路径</el-button>
              <el-button v-if="o.jobId != null && o.idx > 0" size="small" link type="success" @click="openEditor(null, o)">剪辑</el-button>
              <el-button v-if="o.jobId != null && o.idx > 0" size="small" link type="warning" @click="openEditor(null, o, 'audio')">音频/字幕</el-button>
              <el-popconfirm :title="o.filePath ? '连磁盘文件一起删掉？' : '删除这条质检诊断记录？'" @confirm="del(o)">
                <template #reference><el-button size="small" link type="danger">删除</el-button></template>
              </el-popconfirm>
            </div>
          </div>
        </article>
      </section>
    </div>

    <el-dialog v-model="locationVisible" title="后续成片保存位置" width="560px">
      <el-alert type="info" :closable="false" show-icon title="只影响后续成片；既有文件不会移动、覆盖或删除。" />
      <el-radio-group v-model="outputLocation.mode" style="margin:16px 0;display:flex;gap:16px;flex-wrap:wrap">
        <el-radio label="default">默认应用目录</el-radio>
        <el-radio label="desktop">桌面 / Mework Outputs</el-radio>
        <el-radio label="custom">自定义目录</el-radio>
      </el-radio-group>
      <el-input v-if="outputLocation.mode === 'custom'" v-model="outputLocation.customPath" placeholder="本机绝对路径，例如 D:\\Mework Outputs" />
      <div class="form-hint" style="margin-top:10px">保存后可直接使用“扫描输出目录中的成片”补录该位置的已有文件。</div>
      <template #footer><el-button @click="locationVisible = false">取消</el-button><el-button type="primary" :loading="savingLocation" @click="saveLocation">保存位置</el-button></template>
    </el-dialog>

    <el-dialog v-model="qcVisible" title="成片质检详情" width="760px">
      <template v-if="qcReportData">
        <div style="margin-bottom:10px">
          <el-tag size="small" :type="qcReportData.status === 'pass' ? 'success' : qcReportData.status === 'warn' ? 'warning' : 'danger'">{{ qcLabel(qcReportData.status) }}</el-tag>
          <span class="muted" style="margin-left:8px">{{ qcReportData.summary }}</span>
        </div>
        <el-table :data="qcReportData.categories" size="small" max-height="300">
          <el-table-column label="维度" width="100">
            <template #default="{ row }">{{ qcCategoryLabel(row.category) }}</template>
          </el-table-column>
          <el-table-column label="结论" width="80">
            <template #default="{ row }"><el-tag size="small" :type="row.status === 'pass' ? 'success' : row.status === 'warn' ? 'warning' : 'danger'">{{ qcLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="检查与问题" min-width="360">
            <template #default="{ row }">
              <div v-for="c in row.checks" :key="c" class="muted">{{ c }}</div>
              <div v-for="i in row.issues" :key="i" style="color:#e6a23c">{{ i }}</div>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="downgradeList.length" style="margin-top:12px">
          <div class="muted" style="margin-bottom:4px">降级信息</div>
          <div v-for="d in downgradeList" :key="d" style="color:#e6a23c">{{ d }}</div>
        </div>
        <div v-if="materialList.length" style="margin-top:12px">
          <div class="muted" style="margin-bottom:4px">使用素材时间线（{{ materialList.length }} 段）</div>
          <el-table :data="materialList" size="small" max-height="220">
            <el-table-column prop="name" label="素材" min-width="180" show-overflow-tooltip />
            <el-table-column label="角色" width="100"><template #default="{ row }">{{ slotLabel(row.slot) }}</template></el-table-column>
            <el-table-column label="起点" width="90"><template #default="{ row }">{{ num(row.start) }}</template></el-table-column>
            <el-table-column label="时长" width="90"><template #default="{ row }">{{ num(row.duration) }}</template></el-table-column>
          </el-table>
        </div>
      </template>
      <template v-else>
        <div class="muted">该成片暂无结构化质检报告（旧记录或补录成片）。</div>
      </template>
    </el-dialog>

    <el-dialog v-model="repairVisible" title="成片修复与版本历史" width="760px">
      <div v-if="repairLoading" v-loading="repairLoading" style="height:90px"></div>
      <template v-else-if="repairDetail">
        <el-alert :type="repairBanner.type" :closable="false" show-icon :title="repairBanner.title" />
        <div v-for="repair in activeRepairs" :key="repair.id" class="repair-card">
          <b>{{ qcCategoryLabel(repair.category) }} · {{ repair.severity || 'P2' }}</b>
          <div>问题：{{ repair.evidence || '-' }}</div>
          <div>AI 判断：{{ repair.aiAssessment || repair.executionImpact || '已按本地媒体检查生成建议' }}</div>
          <div>推荐：{{ repairActionLabel(repair.recommendedAction) }}</div>
          <div v-if="repairNeedsDecision(repair)" class="repair-actions">
            <el-button v-if="repair.recommendedAction && repair.recommendedAction !== 'await-human-audio-choice' && repair.recommendedAction !== 'replace-bgm'" size="small" type="primary" :loading="repairSubmitting" @click="applyRepair(repair.recommendedAction)">采用推荐方案</el-button>
            <el-button size="small" :loading="repairSubmitting" @click="applyRepair('retry-auto')">按当前安全策略重试</el-button>
            <el-button v-if="repair.category === 'audio'" size="small" :disabled="!hasOriginalAudioChoice(repair)" @click="applyRepair('keep-original-audio')">保留原声重试</el-button>
            <el-button v-if="repair.category === 'subtitle'" size="small" @click="applyRepair('relax-subtitle')">关闭字幕后重试</el-button>
            <el-button v-if="repair.category === 'video' || repair.category === 'duplicate'" size="small" @click="applyRepair('regenerate-plan')">重新规划镜头</el-button>
            <el-button size="small" type="info" plain @click="openEditor(repair)">在应用内编辑</el-button>
          </div>
          <div v-else class="muted">{{ repairStatusLabel(repair.status) }}</div>
        </div>
        <div v-if="hasPendingAudioRepair && repairDetail.readableBgms?.length" class="repair-bgm-choice">
          <div class="muted">选择一条已探测为可读的背景音乐后重新出片</div>
          <el-select v-model="selectedRepairBgmId" size="small" placeholder="选择背景音乐" style="width:260px">
            <el-option v-for="bgm in repairDetail.readableBgms" :key="bgm.id" :label="`${bgm.name || '未命名音频'}${bgm.durationSec ? ` · ${Number(bgm.durationSec).toFixed(1)}s` : ''}`" :value="bgm.id" />
          </el-select>
          <el-button size="small" type="primary" :disabled="!selectedRepairBgmId" :loading="repairSubmitting" @click="applyRepair('replace-bgm', selectedRepairBgmId)">使用此背景音乐</el-button>
        </div>
        <div v-else-if="hasPendingAudioRepair" class="muted" style="margin-top:10px">当前项目没有经探测可读的背景音乐，请先补充音频素材。</div>
        <div v-if="repairDetail.versions?.length" style="margin-top:12px">
          <div class="muted" style="margin-bottom:4px">版本历史</div>
          <el-table :data="repairDetail.versions" size="small" max-height="220">
            <el-table-column prop="versionNo" label="版本" width="70" />
            <el-table-column label="状态" width="120"><template #default="{ row }">{{ versionStatusLabel(row.status) }}</template></el-table-column>
            <el-table-column prop="qcReport" label="质检" min-width="220" show-overflow-tooltip />
            <el-table-column prop="error" label="结果" min-width="220" show-overflow-tooltip />
          </el-table>
        </div>
      </template>
      <template #footer><el-button @click="repairVisible = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="reindexVisible" title="补录输出目录中的已有成片" width="680px">
      <el-alert type="warning" :closable="false" show-icon title="只补录候选文件，不会移动或删除磁盘中的任何文件。" />
      <el-table :data="candidates" size="small" max-height="360" @selection-change="(rows) => (selectedCandidates = rows)">
        <el-table-column type="selection" width="44" :selectable="(row) => row.eligible !== false" />
        <el-table-column prop="name" label="文件" min-width="280" show-overflow-tooltip />
        <el-table-column label="时长" width="100"><template #default="{ row }">{{ row.durationSec ? row.durationSec.toFixed(1) + 's' : '无法读取' }}</template></el-table-column>
        <el-table-column label="大小" width="110"><template #default="{ row }">{{ Math.round((row.sizeBytes || 0) / 1024 / 1024) }} MB</template></el-table-column>
        <el-table-column label="检查" min-width="180"><template #default="{ row }"><el-tag v-if="row.eligible !== false" type="success" size="small">可补录</el-tag><span v-else style="color:#f56c6c">{{ row.reason || '不可补录' }}</span></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="reindexVisible = false">取消</el-button><el-button type="primary" :disabled="!selectedCandidates.length" :loading="reindexing" @click="confirmReindex">补录选中 {{ selectedCandidates.length }} 条</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const router = useRouter()
const list = ref([])
const jobs = ref([])
const jobFilter = ref(null)
const loading = ref(false)
const loadError = ref(false)

// 用户成片视频播放失败时给出可见错误态，而不是留下黑屏/破碎图标。
const videoErrors = ref({})
const mediaRatios = ref({})
function onVideoError (o) { videoErrors.value[o.id] = true }
function onVideoMetadata (output, event) {
  const video = event.currentTarget
  const width = Number(video.videoWidth || 0)
  const height = Number(video.videoHeight || 0)
  if (width > 0 && height > 0) mediaRatios.value[output.id] = width / height
}
function mediaClass (output) {
  const ratio = mediaRatios.value[output.id]
  if (!ratio) return 'media-pending'
  if (ratio > 1.25) return 'media-landscape'
  if (ratio > 0.85) return 'media-square'
  return 'media-portrait'
}
function onVideoLoaded (o) { delete videoErrors.value[o.id] }

const scanning = ref(false)
const reindexVisible = ref(false)
const candidates = ref([])
const selectedCandidates = ref([])
const reindexing = ref(false)
const qcVisible = ref(false)
const qcSource = ref(null)
const repairVisible = ref(false)
const repairLoading = ref(false)
const repairSubmitting = ref(false)
const repairDetail = ref(null)
const repairSource = ref(null)
const selectedRepairBgmId = ref(null)
const locationVisible = ref(false)
const savingLocation = ref(false)
const outputLocation = ref({ path: '', mode: 'default', customPath: '' })

// 批量操作：全选/批量删除/批量下载
const selectedIds = ref([])
const playingVideo = ref(null)
function onVideoPlay (el) { if (playingVideo.value && playingVideo.value !== el) { try { playingVideo.value.pause() } catch (e) {} } playingVideo.value = el }
const batchDeleting = ref(false)
const allChecked = computed(() => filtered.value.length > 0 && selectedIds.value.length === filtered.value.length)
const someChecked = computed(() => selectedIds.value.length > 0 && !allChecked.value)
function toggleAll (val) { selectedIds.value = val ? filtered.value.map((o) => o.id) : [] }
async function batchDelete () {
  if (!selectedIds.value.length) return
  batchDeleting.value = true
  try {
    const ids = [...selectedIds.value]
    let ok = 0
    for (const id of ids) { try { await api.deleteOutput(id); ok++ } catch (e) { /* 继续删其余 */ } }
    ElMessage.success('已删除 ' + ok + ' 条')
    selectedIds.value = []
    await load()
  } finally { batchDeleting.value = false }
}
function batchDownload () {
  if (!selectedIds.value.length) return
  for (const id of selectedIds.value) window.open(api.downloadUrl(id), '_blank')
  ElMessage.success('已开始下载')
}

const filtered = computed(() =>
  jobFilter.value ? list.value.filter((o) => o.jobId === jobFilter.value) : list.value)

const qcReportData = computed(() => parseJson(qcSource.value && qcSource.value.qcJson))
const downgradeList = computed(() => {
  const parsed = parseJson(qcSource.value && qcSource.value.downgradeInfo)
  return Array.isArray(parsed) ? parsed.filter((x) => typeof x === 'string') : []
})
const materialList = computed(() => {
  const parsed = parseJson(qcSource.value && qcSource.value.usedMaterials)
  return Array.isArray(parsed) ? parsed : []
})
const activeRepairs = computed(() => {
  const latest = new Map()
  for (const repair of repairDetail.value?.repairs || []) {
    const key = repair.issueId || `${repair.category}:${repair.evidence || ''}`
    const known = latest.get(key)
    if (!known || Number(repair.id || 0) > Number(known.id || 0)) latest.set(key, repair)
  }
  return [...latest.values()].sort((a, b) => severityRank(a.severity) - severityRank(b.severity))
})
const hasAudioRepair = computed(() => activeRepairs.value.some((repair) => repair.category === 'audio'))
const hasPendingAudioRepair = computed(() => activeRepairs.value.some((repair) => repair.category === 'audio' && repairNeedsDecision(repair)))
const repairBanner = computed(() => {
  const repairs = activeRepairs.value
  if (repairs.some(repairNeedsDecision)) return { type: 'warning', title: '存在需要人工确认的修复选择' }
  if (repairs.some((repair) => repair.status === 'completed')) return { type: 'success', title: '已完成自动修复并通过第二次完整质检' }
  if (repairs.some((repair) => repair.status === 'no_improvement')) return { type: 'warning', title: '自动修复未改善，当前版本已保留为回滚证据' }
  return { type: 'info', title: '当前成片的修复与版本记录' }
})

function parseJson(text) {
  if (!text) return null
  try { return typeof text === 'string' ? JSON.parse(text) : text } catch { return null }
}
function baseName(fp) {
  return String(fp || '').replace(/\\/g, '/').split('/').pop()
}
function fileUrl(output) {
  if (typeof output === 'object') return output.publicUrl ? api.protectedUrl(output.publicUrl) : (output.fileUrl ? api.protectedUrl(output.fileUrl) : fileUrl(output.filePath))
  return api.protectedUrl(`/files/output/${baseName(output)}`)
}
function thumbUrl(t) {
  return t ? api.protectedUrl(`/files/thumbs/${baseName(t)}`) : undefined
}
function durColor(d) {
  if (!d) return ''
  return d >= 50 && d <= 150 ? '#67c23a' : '#e6a23c'
}
function durTip(d) {
  if (!d) return ''
  return d >= 50 && d <= 150 ? ' ✓合规' : ' ⚠超区间'
}
function num(v) {
  return v == null ? '-' : Number(v).toFixed(1) + 's'
}
function qcLabel(status) {
  if (status === 'pass') return '可发布'
  if (status === 'warn') return '建议复核'
  if (status === 'fail') return '已拦截'
  return '未知'
}
function qcCategoryLabel(category) {
  return { audio: '音频', video: '画面', subtitle: '字幕', duplicate: '重复', semantic: '语义', hook: '钩子' }[category] || category
}
function strategyLabel(strategy) {
  const map = {
    CONFLICT: '冲突', RESULT: '结果', SUSPENSE: '悬念', REWARD: '奖励',
    COUNTERINTUITIVE: '反常识', QUESTION: '提问', VISUAL_IMPACT: '视觉冲击'
  }
  return map[String(strategy || '').toUpperCase()] || strategy
}
function slotLabel(slot) {
  return { hook: '钩子', body: '实拍主体', product: '自家产品', celebrity: '明星达人', endcard: '片尾', intro: '片头' }[slot] || (slot || '-')
}
function openQc(o) {
  qcSource.value = o
  qcVisible.value = true
}
function severityRank (severity) { return ({ P0: 0, P1: 1, P2: 2 })[severity] ?? 3 }
function repairActionLabel(action) {
  return ({ 'replace-bgm': '替换为可读背景音乐', 'keep-original-audio': '保留原片声音', 'relax-subtitle': '关闭当前字幕后重试', 'regenerate-plan': '重新规划镜头', 'switch-hook': '切换钩子策略', 'retry-auto': '按当前安全策略重试' })[action] || action || '需要在应用内编辑'
}
function repairNeedsDecision(repair) {
  return ['awaiting_decision', 'proposed'].includes(repair?.status)
}
function repairStatusLabel(status) {
  return ({ completed: '已自动修复并通过复检', no_improvement: '自动修复未改善，已保留回滚证据', approved_auto: '自动修复执行中', approved_manual: '人工决策已进入渲染队列' })[status] || '修复记录已归档'
}
function versionStatusLabel(status) {
  return ({ passed: '已通过复检', rolled_back: '已回滚保留证据', awaiting_decision: '等待人工决策', repairing: '修复中', qc_failed: '质检未通过' })[status] || status || '-'
}
function hasOriginalAudioChoice(repair) {
  const candidates = parseJson(repair?.candidateActions)
  return Array.isArray(candidates) && candidates.includes('keep-original-audio')
}
async function openRepair(o) {
  repairSource.value = o
  selectedRepairBgmId.value = null
  repairVisible.value = true
  repairLoading.value = true
  try { repairDetail.value = await api.outputRepair(o.jobId, o.idx) } catch (error) { ElMessage.error(`读取修复方案失败：${error.message}`); repairVisible.value = false } finally { repairLoading.value = false }
}
async function applyRepair(action, bgmMaterialId = null) {
  if (!repairSource.value || !action) return
  repairSubmitting.value = true
  try {
    await api.applyOutputRepairDecision(repairSource.value.jobId, repairSource.value.idx, { action, bgmMaterialId })
    ElMessage.success('修复决策已保存，任务已重新进入渲染与完整质检队列')
    repairVisible.value = false
    await load()
  } catch (error) { ElMessage.error(`提交修复决策失败：${error.message}`) } finally { repairSubmitting.value = false }
}

function openEditor (repair = null, source = repairSource.value, mode = '') {
  if (!source || source.jobId == null || Number(source.idx) < 1) return
  router.push({ path: '/editor', query: {
    jobId: source.jobId,
    idx: source.idx,
    issue: repair?.issueId || '',
    category: repair?.category || '',
    mode
  } })
}

async function saveLocation () {
  savingLocation.value = true
  try {
    const saved = await api.saveOutputLocation({
      mode: outputLocation.value.mode,
      path: outputLocation.value.mode === 'custom' ? outputLocation.value.customPath : null,
      confirm: true
    })
    outputLocation.value.path = saved.path
    locationVisible.value = false
    ElMessage.success('后续成片保存位置已更新；既有成片未移动')
  } catch (error) { ElMessage.error(`保存位置失败：${error.message}`) } finally { savingLocation.value = false }
}

async function copyPath(fp) {
  try {
    await navigator.clipboard.writeText(fp)
    ElMessage.success('路径已复制')
  } catch {
    ElMessage.info(fp)
  }
}

async function del(o) {
  await api.deleteOutput(o.id)
  load()
}

async function scanCandidates () {
  scanning.value = true
  try {
    candidates.value = await api.outputReindexCandidates()
    selectedCandidates.value = []
    if (!candidates.value.length) {
      ElMessage.info('输出目录中没有未收录的可识别 MP4 文件')
      return
    }
    reindexVisible.value = true
  } catch (error) {
    ElMessage.error(`扫描输出目录失败：${error.message}`)
  } finally { scanning.value = false }
}

async function confirmReindex () {
  reindexing.value = true
  try {
    const count = await api.reindexOutputs(selectedCandidates.value.map((row) => row.filePath))
    ElMessage.success(`已补录 ${count} 条成片记录`)
    reindexVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(`补录失败：${error.message}`)
  } finally { reindexing.value = false }
}

async function load() {
  loading.value = true
  try {
    const [outputRows, jobRows, location] = await Promise.all([api.allOutputs(), api.jobs(), api.outputLocation()])
    list.value = outputRows
    jobs.value = jobRows
    outputLocation.value.path = location.path || ''
    loadError.value = false
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.outputs-toolbar { flex-wrap: wrap; align-items: center; }
.outputs-summary { display: flex; align-items: baseline; gap: 8px; }
.outputs-spacer { flex: 1; }
.output-location { display: flex; align-items: center; gap: 4px; min-width: 0; }
.output-location code { overflow-wrap: anywhere; }

.output-gallery {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 230px));
  gap: 16px;
  align-items: start;
  justify-content: start;
}

.output-card {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 2px 8px rgba(31, 35, 41, 0.05);
}

.output-media {
  position: relative;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: #101828;
}
.output-media.media-pending { aspect-ratio: 4 / 3; min-height: 150px; }
.output-media.media-portrait { aspect-ratio: 9 / 16; max-height: 408px; }
.output-media.media-landscape { aspect-ratio: 16 / 9; }
.output-media.media-square { aspect-ratio: 1 / 1; }
.output-media video { width: 100%; height: 100%; object-fit: contain; background: #000; }

.output-info { display: flex; flex-direction: column; gap: 8px; padding: 12px; }
.output-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; font-weight: 600; color: #303133; }
.output-tags { display: flex; flex-wrap: wrap; gap: 6px; min-height: 24px; }
.output-meta { min-height: 19px; overflow-wrap: anywhere; }
.output-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 8px; padding-top: 4px; border-top: 1px solid #f0f2f5; }
.output-action-link { font-size: 12px; }

.card-select { position: absolute; top: 8px; left: 8px; z-index: 5; background: rgba(0, 0, 0, .35); border-radius: 4px; padding: 2px; }
.video-play-error, .output-blocked { width: 100%; height: 100%; min-height: 180px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; padding: 18px; box-sizing: border-box; text-align: center; }
.video-play-error { background: #fff4ec; color: #b42318; font-size: 13px; }
.video-play-error-icon { width: 26px; height: 26px; border-radius: 50%; background: #f56c6c; color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 700; }
.output-blocked { background: #f7f8fa; }

.repair-card { margin-top: 10px; padding: 10px 12px; border-left: 3px solid #e6a23c; background: #fffaf0; line-height: 1.75; }
.repair-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
.repair-bgm-choice { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 10px; padding: 10px 12px; border: 1px solid #d9ecff; background: #f0f9ff; }
.repair-bgm-choice .muted { width: 100%; }

@media (min-width: 1500px) {
  .output-gallery { grid-template-columns: repeat(auto-fill, minmax(210px, 240px)); }
}

@media (max-width: 1100px) {
  .outputs-toolbar { align-items:flex-start; }
  .outputs-summary { min-width:0; flex:1 1 260px; flex-wrap:wrap; }
  .output-location { flex:1 1 100%; flex-wrap:wrap; }
  .outputs-spacer { display:none; }
}

@media (max-width: 820px) {
  .outputs-toolbar { display: flex; align-items: stretch; }
  .outputs-summary, .output-location { width: 100%; }
  .outputs-summary { flex-wrap: wrap; }
  .output-location { align-items: flex-start; flex-wrap: wrap; }
  .output-location code { flex: 1 1 220px; }
  .outputs-spacer { display: none; }
  .outputs-toolbar :deep(.el-select), .outputs-toolbar :deep(.el-button), .outputs-toolbar :deep(.el-checkbox) { width: 100%; margin-left: 0 !important; }
  .output-gallery { grid-template-columns: repeat(auto-fill, minmax(200px, 230px)); gap: 14px; }
}

@media (max-width: 560px) {
  .output-gallery { grid-template-columns: 1fr; }
  .output-media { max-height: 64vh; }
}
</style>
