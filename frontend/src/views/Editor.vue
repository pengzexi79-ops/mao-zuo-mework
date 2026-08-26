<template>
  <div class="editor-page">
    <div class="editor-head">
      <div>
        <h2>可编辑出片工作台</h2>
        <p class="muted">编辑草稿会保存为独立会话。候选成片必须通过完整质检，且只有明确确认后才会替换当前成片。</p>
      </div>
      <div class="editor-actions">
        <el-button @click="router.push('/outputs')">返回成片库</el-button>
        <el-button type="primary" plain @click="router.push({ path: '/crawl', query: { projectId: job?.projectId } })">补充合规素材</el-button>
      </div>
    </div>

    <el-alert v-if="issue" type="warning" :closable="false" show-icon :title="`当前修复定位：${categoryLabel(category)} · ${issue}`" />
    <el-alert type="info" :closable="false" show-icon style="margin-top:12px"
      title="编辑器只使用已入库、已探测的素材。网络素材请先在“素材抓取”完成来源授权、下载与媒体准入。" />
    <el-alert v-if="editorMode === 'audio'" type="success" :closable="false" show-icon style="margin-top:12px"
      title="当前进入音频 / 字幕处理：请在下方音频与字幕区完成设置，保存草稿后再生成质检候选。" />

    <div v-if="loading" v-loading="loading" class="loading"></div>
    <el-empty v-else-if="!hasTarget" description="请从成片库、质检详情或修复方案进入需要编辑的成片。">
      <el-button type="primary" @click="router.push('/outputs')">前往成片库</el-button>
    </el-empty>
    <template v-else-if="state">
      <el-alert
        v-if="publicSourceSegments.length"
        type="error"
        :closable="false"
        show-icon
        style="margin-top:12px"
        title="当前编辑计划包含公开来源镜头，已阻止候选生成。请替换或移除下列片段后再保存。"
      >
        <template #default>
          <div class="source-blocker">
            <span v-for="row in publicSourceSegments" :key="`${row.index}-${row.id}`">
              #{{ row.index }} {{ row.name }}<span v-if="row.sourceUrl"> · {{ row.sourceUrl }}</span>
            </span>
          </div>
        </template>
      </el-alert>
      <el-alert v-if="dirty" type="warning" :closable="false" show-icon style="margin-top:12px" title="草稿尚未保存。修剪、排序、替换和启用状态只有保存后才会写入编辑会话。" />
      <section class="editor-grid">
        <div class="editor-panel preview-panel">
          <div class="panel-title">当前成片 / 候选版本</div>
          <video v-if="previewPath" :src="fileUrl(previewPath)" controls preload="metadata"></video>
          <div v-else class="empty">当前版本没有可播放文件。仍可编辑计划并生成受控候选。</div>
          <div class="status-line">
            <el-tag :type="sessionTag.type">{{ sessionTag.label }}</el-tag>
            <span class="muted">基础版本 {{ state.baseVersion?.versionNo || '-' }} · 候选 {{ state.candidateVersion?.versionNo || '-' }}</span>
          </div>
          <el-alert v-if="state.session?.error" type="error" :closable="false" show-icon :title="state.session.error" style="margin-top:10px" />
          <el-alert v-if="state.candidateVersion?.qcReport" :type="state.candidateVersion?.status === 'passed' ? 'success' : 'warning'" :closable="false" show-icon :title="state.candidateVersion.qcReport" style="margin-top:10px" />
        </div>

        <div class="editor-panel settings-panel">
          <div class="panel-title">音频与字幕</div>
          <el-form label-position="top" size="small">
            <el-form-item label="音频模式">
              <el-radio-group v-model="draft.audio.mode" @change="onAudioModeChange">
                <el-radio-button label="original">保留原声</el-radio-button>
                <el-radio-button label="material-audio">背景音乐</el-radio-button>
                <el-radio-button label="silent">静音</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="draft.audio.mode === 'material-audio'" label="背景音乐">
              <el-select v-model="draft.audio.bgmMaterialId" filterable placeholder="选择经探测可读的 BGM" style="width:100%">
                <el-option v-for="bgm in state.readableBgms || []" :key="bgm.id" :value="bgm.id" :label="`${bgm.name} · ${Number(bgm.durationSec || 0).toFixed(1)} 秒`" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="draft.audio.mode === 'material-audio'" label="口播音频（可选）">
              <el-select v-model="draft.audio.voiceMaterialId" clearable filterable placeholder="不选则只使用背景音乐" style="width:100%">
                <el-option v-for="voice in state.readableVoices || []" :key="voice.id" :value="voice.id" :label="`${voice.name} · ${Number(voice.durationSec || 0).toFixed(1)} 秒`" />
              </el-select>
            </el-form-item>
            <el-row :gutter="10" v-if="draft.audio.mode !== 'silent'">
              <el-col :span="12"><el-form-item :label="draft.audio.mode === 'original' ? '原声音量' : 'BGM 音量'"><el-slider v-model="draft.audio.volume" :min="0" :max="1" :step="0.05" show-input /></el-form-item></el-col>
            </el-row>
            <el-form-item label="字幕">
              <el-switch v-model="draft.subtitle.enabled" :disabled="draft.audio.mode === 'silent'" active-text="烧录字幕" inactive-text="关闭字幕" />
            </el-form-item>
            <el-form-item label="原片字幕处理">
              <el-switch v-model="draft.subtitle.cleanSourceSubtitles" :disabled="draft.audio.mode === 'silent'" active-text="底部安全区遮挡" inactive-text="保留原片字幕" />
            </el-form-item>
          </el-form>
        </div>
      </section>

      <section class="editor-panel timeline-panel">
        <div class="panel-title-row">
          <div>
            <div class="panel-title">镜头时间线</div>
            <div class="muted">拖动排序可通过上下移动完成；裁剪单位为秒。禁用片段不会进入候选渲染。</div>
          </div>
          <div class="timeline-total">总时长 {{ totalDuration.toFixed(1) }} 秒</div>
        </div>
        <div class="visual-timeline" aria-label="可视化时间线">
          <button
            v-for="(row, index) in draft.segments"
            :key="`${row.index}-${row.materialId}-${index}`"
            class="visual-segment"
            :class="{ disabled: !row.enabled }"
            :style="{ '--segment-width': `${Math.max(8, Number(row.duration || 0) / Math.max(1, totalDuration) * 100)}%` }"
            type="button"
            @click="openTrim(row)"
          >
            <img v-if="material(row.materialId)?.thumbnailUrl" :src="material(row.materialId).thumbnailUrl" :alt="material(row.materialId)?.name || '素材缩略图'" />
            <span class="visual-segment-index">{{ index + 1 }}</span>
            <span class="visual-segment-label">{{ material(row.materialId)?.name || '未命名素材' }}</span>
            <small>{{ Number(row.sourceStart || 0).toFixed(1) }}s · {{ Number(row.duration || 0).toFixed(1) }}s</small>
          </button>
        </div>
        <el-table :data="draft.segments" size="small" max-height="480" class="timeline-table">
          <el-table-column label="#" width="60"><template #default="{ $index }">{{ $index + 1 }}</template></el-table-column>
          <el-table-column label="启用" width="75"><template #default="{ row }"><el-switch v-model="row.enabled" @change="markDirty" /></template></el-table-column>
          <el-table-column label="镜头 / 素材" min-width="250">
            <template #default="{ row }">
              <el-select v-model="row.materialId" filterable @change="(id) => replaceMaterial(row, id)" style="width:100%">
                <el-option v-for="material in state.materials || []" :key="material.id" :value="material.id" :label="`${material.name} · ${roleLabel(material.role)}`" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="角色" width="112"><template #default="{ row }"><el-select v-model="row.slot" @change="markDirty"><el-option v-for="slot in slots" :key="slot.value" :label="slot.label" :value="slot.value" /></el-select></template></el-table-column>
          <el-table-column label="源起点" width="120"><template #default="{ row }"><el-input-number v-model="row.sourceStart" :min="0" :max="segmentMaxStart(row)" :step="0.1" controls-position="right" @change="markDirty" /></template></el-table-column>
          <el-table-column label="时长" width="120"><template #default="{ row }"><el-input-number v-model="row.duration" :min="segmentMinDuration(row)" :max="segmentMaxDuration(row)" :step="0.1" controls-position="right" @change="markDirty" /></template></el-table-column>
          <el-table-column label="顺序" width="145"><template #default="{ $index }"><el-button size="small" :disabled="$index === 0" @click="move($index, -1)">上移</el-button><el-button size="small" :disabled="$index === draft.segments.length - 1" @click="move($index, 1)">下移</el-button></template></el-table-column>
          <el-table-column label="操作" width="155"><template #default="{ row, $index }"><el-button size="small" type="primary" plain @click="openTrim(row)">修剪</el-button><el-button size="small" link type="danger" @click="remove($index)">移除</el-button></template></el-table-column>
        </el-table>
        <div class="timeline-actions">
          <el-select v-model="insertMaterialId" filterable placeholder="从已入库素材插入" style="width:300px">
            <el-option v-for="material in state.materials || []" :key="material.id" :value="material.id" :label="`${material.name} · ${roleLabel(material.role)}`" />
          </el-select>
          <el-button :disabled="!insertMaterialId" @click="insertMaterial">插入到末尾</el-button>
        </div>
      </section>

      <section class="editor-panel commit-panel">
        <el-alert v-if="audioNotice" type="warning" :closable="false" show-icon :title="audioNotice" style="margin-bottom:10px" />
        <el-input v-model="draft.comment" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="编辑备注（可选，会保留在编辑会话中）" />
        <div class="commit-actions">
          <el-button :disabled="state.session?.status === 'rendering'" :loading="saving" @click="saveDraft">保存草稿</el-button>
          <el-button type="primary" :disabled="!canRender" :loading="rendering" @click="renderCandidate">生成质检候选</el-button>
          <el-popconfirm title="确认将通过质检的候选设为当前成片？当前成片会保留在版本历史中。" @confirm="applyCandidate">
            <template #reference><el-button type="success" :disabled="state.session?.status !== 'passed'" :loading="applying">应用为当前成片</el-button></template>
          </el-popconfirm>
        </div>
      </section>

      <section class="editor-panel material-panel">
        <div class="panel-title">版本历史</div>
        <el-table :data="repairDetail?.versions || []" size="small" max-height="220">
          <el-table-column prop="versionNo" label="版本" width="70" />
          <el-table-column prop="status" label="状态" width="130" />
          <el-table-column prop="qcReport" label="质检" min-width="220" show-overflow-tooltip />
          <el-table-column prop="error" label="结果" min-width="220" show-overflow-tooltip />
        </el-table>
      </section>

      <el-dialog v-model="trimVisible" title="修剪镜头" width="760px" destroy-on-close>
        <template v-if="trimMaterial">
          <div class="trim-meta">
            <strong>{{ trimMaterial.name }}</strong>
            <span>源素材时长 {{ trimSourceDuration.toFixed(1) }} 秒</span>
          </div>
          <video v-if="trimMaterial.fileType === 'video'" :src="trimPreviewUrl" controls preload="metadata" class="trim-preview"></video>
          <img v-else-if="trimMaterial.fileType === 'image'" :src="trimPreviewUrl" :alt="trimMaterial.name" class="trim-preview trim-image" />
          <el-alert v-else type="error" :closable="false" title="音频不能作为画面片段修剪" />
          <el-slider v-if="trimMaterial.fileType === 'video'" v-model="trimRange" range :min="0" :max="trimSourceDuration" :step="0.1" @change="syncTrimFromRange" />
          <el-row :gutter="12" class="trim-fields">
            <el-col :span="12"><el-form-item label="入点（秒）"><el-input-number v-model="trimStart" :min="0" :max="trimMaxStart" :step="0.1" controls-position="right" @change="syncTrimFromFields" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="出点（秒）"><el-input-number v-model="trimEnd" :min="trimMinEnd" :max="trimSourceDuration" :step="0.1" controls-position="right" @change="syncTrimFromFields" /></el-form-item></el-col>
          </el-row>
          <div class="trim-duration">修剪后时长：{{ trimDuration.toFixed(1) }} 秒<span v-if="trimMaterial.fileType === 'video'">（最短 0.5 秒）</span><span v-else>（图片最长 12 秒）</span></div>
        </template>
        <template #footer>
          <el-button @click="trimVisible = false">取消</el-button>
          <el-button type="primary" :disabled="!trimValid" @click="applyTrim">应用修剪到草稿</el-button>
        </template>
      </el-dialog>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, ROLE_LABEL } from '../api'

const route = useRoute()
const router = useRouter()
const hashQuery = new URLSearchParams((window.location.hash || '').split('?')[1] || '')
function queryValue (name) { return route.query[name] || hashQuery.get(name) || '' }
const jobId = Number(queryValue('jobId')) || null
const idx = Number(queryValue('idx')) || null
const issue = String(queryValue('issue'))
const category = String(queryValue('category'))
const editorMode = String(queryValue('mode'))
const hasTarget = computed(() => jobId !== null && idx !== null && idx > 0)
const loading = ref(false)
const saving = ref(false)
const rendering = ref(false)
const applying = ref(false)
const job = ref(null)
const state = ref(null)
const repairDetail = ref(null)
const insertMaterialId = ref(null)
const dirty = ref(false)
const hydrating = ref(false)
const trimVisible = ref(false)
const trimRow = ref(null)
const trimMaterial = ref(null)
const trimSourceDuration = ref(0)
const trimRange = ref([0, 0])
const trimStart = ref(0)
const trimEnd = ref(0)
const audioNotice = ref('')
const draft = ref({ segments: [], audio: { mode: 'original', bgmMaterialId: null, voiceMaterialId: null, volume: 1 }, subtitle: { enabled: false, cleanSourceSubtitles: false, safeBandMode: 'off' }, comment: '' })
const slots = [
  { value: 'hook', label: '钩子' }, { value: 'body', label: '实拍主体' }, { value: 'product', label: '产品' },
  { value: 'celebrity', label: '达人' }, { value: 'endcard', label: '片尾' }, { value: 'intro', label: '片头' }
]

const publicSourceSegments = computed(() => state.value?.publicSourceSegments || [])
const totalDuration = computed(() => draft.value.segments.filter((row) => row.enabled).reduce((sum, row) => sum + Number(row.duration || 0), 0))
const canRender = computed(() => state.value?.session?.status !== 'rendering' && publicSourceSegments.value.length === 0 && !dirty.value && draft.value.segments.some((row) => row.enabled) && totalDuration.value >= 1 && totalDuration.value <= 300)
const trimDuration = computed(() => Math.max(0, Number(trimEnd.value || 0) - Number(trimStart.value || 0)))
const trimMinEnd = computed(() => trimMaterial.value?.fileType === 'image' ? 0.1 : Math.min(trimSourceDuration.value, Number(trimStart.value || 0) + 0.5))
const trimMaxStart = computed(() => trimMaterial.value?.fileType === 'image' ? 0 : Math.max(0, trimSourceDuration.value - 0.5))
const trimValid = computed(() => trimMaterial.value?.fileType === 'image'
  ? trimDuration.value > 0 && trimDuration.value <= 12
  : trimStart.value >= 0 && trimDuration.value >= 0.5 && trimEnd.value <= trimSourceDuration.value + 0.05)
const previewPath = computed(() => state.value?.candidateVersion?.status === 'passed' ? state.value.candidateVersion.filePath : state.value?.baseVersion?.filePath)
const sessionTag = computed(() => {
  const status = state.value?.session?.status || 'draft'
  return ({ draft: { type: 'info', label: '草稿未渲染' }, rendering: { type: 'warning', label: '候选渲染中' }, passed: { type: 'success', label: '候选已通过质检' }, qc_failed: { type: 'danger', label: '候选未通过质检' }, applied: { type: 'success', label: '已应用为当前成片' }, failed: { type: 'danger', label: '候选渲染失败' } })[status] || { type: 'info', label: status }
})
function categoryLabel (value) { return ({ audio: '音频', video: '画面', subtitle: '字幕', duplicate: '重复镜头', hook: '钩子', semantic: '素材关联性' })[value] || value || '编辑' }
function roleLabel (role) { return ROLE_LABEL[role] || role || '未分类' }
function fileUrl (path) { return api.protectedUrl(`/files/output/${String(path).replace(/\\/g, '/').split('/').pop()}`) }
function copy (value) { return JSON.parse(JSON.stringify(value)) }
function material (id) { return (state.value?.materials || []).find((item) => Number(item.id) === Number(id)) }
function segmentSourceDuration (row) { const item = material(row.materialId); return Number(item?.durationSec || row.sourceDuration || 0) }
function segmentMaxStart (row) { const item = material(row.materialId); return item?.fileType === 'image' ? 0 : Math.max(0, segmentSourceDuration(row) - Number(row.duration || 0)) }
function segmentMinDuration (row) { return material(row.materialId)?.fileType === 'image' ? 0.1 : 0.5 }
function segmentMaxDuration (row) { const item = material(row.materialId); return item?.fileType === 'image' ? 12 : Math.max(0.5, segmentSourceDuration(row) - Number(row.sourceStart || 0)) }
function editorSlot (role) { return ['hook', 'body', 'product', 'celebrity', 'endcard', 'intro'].includes(role) ? role : 'body' }
function markDirty () { dirty.value = true }
function move (index, direction) { const target = index + direction; if (target < 0 || target >= draft.value.segments.length) return; const rows = draft.value.segments; [rows[index], rows[target]] = [rows[target], rows[index]]; markDirty() }
function remove (index) { if (draft.value.segments.length <= 1) return ElMessage.warning('请至少保留一个画面片段'); draft.value.segments.splice(index, 1); markDirty() }
function replaceMaterial (row, id) { const item = material(id); if (!item) return; row.sourceStart = 0; row.sourceDuration = Number(item.durationSec || 0); row.duration = item.fileType === 'image' ? 3 : Math.min(Math.max(0.5, Number(item.durationSec || 0)), 6); row.slot = item.role && item.role !== 'none' ? editorSlot(item.role) : row.slot; markDirty() }
function insertMaterial () { const item = material(insertMaterialId.value); if (!item) return; draft.value.segments.push({ index: draft.value.segments.length + 1, materialId: item.id, sourceStart: 0, sourceDuration: Number(item.durationSec || 0), duration: item.fileType === 'image' ? 3 : Math.min(Math.max(0.5, Number(item.durationSec || 0)), 6), slot: item.role && item.role !== 'none' ? editorSlot(item.role) : 'body', enabled: true }); insertMaterialId.value = null; markDirty() }
function onAudioModeChange () { if (draft.value.audio.mode === 'silent') { draft.value.audio.bgmMaterialId = null; draft.value.audio.voiceMaterialId = null; draft.value.subtitle.enabled = false; draft.value.subtitle.cleanSourceSubtitles = false } else if (draft.value.audio.mode === 'original') { draft.value.audio.bgmMaterialId = null; draft.value.audio.voiceMaterialId = null }; markDirty() }
function payload () { return { segments: draft.value.segments.map((row, index) => ({ ...row, index: index + 1 })), audio: { mode: draft.value.audio.mode, bgmMaterialId: draft.value.audio.mode === 'material-audio' ? draft.value.audio.bgmMaterialId : null, voiceMaterialId: draft.value.audio.mode === 'material-audio' ? draft.value.audio.voiceMaterialId : null, bgmVolume: draft.value.audio.mode === 'material-audio' ? draft.value.audio.volume : 0, originalAudioVolume: draft.value.audio.mode === 'original' ? draft.value.audio.volume : 0 }, subtitle: { enabled: draft.value.subtitle.enabled, cleanSourceSubtitles: draft.value.subtitle.cleanSourceSubtitles, safeBandMode: draft.value.subtitle.cleanSourceSubtitles ? 'subtitle-safe-band' : 'off' }, comment: draft.value.comment } }
const trimPreviewUrl = computed(() => trimMaterial.value?.id ? api.materialPreviewUrl(trimMaterial.value.id) : '')
function openTrim (row) { const item = material(row.materialId); if (!item || item.fileType === 'audio') return ElMessage.warning('音频不能作为画面片段修剪'); trimRow.value = row; trimMaterial.value = item; trimSourceDuration.value = item.fileType === 'image' ? 12 : Math.max(0.1, Number(item.durationSec || row.sourceDuration || 0)); trimStart.value = item.fileType === 'image' ? 0 : Math.max(0, Number(row.sourceStart || 0)); trimEnd.value = item.fileType === 'image' ? Math.min(12, Math.max(0.1, Number(row.duration || 3))) : Math.min(trimSourceDuration.value, trimStart.value + Number(row.duration || 0.5)); trimRange.value = [trimStart.value, trimEnd.value]; trimVisible.value = true }
function syncTrimFromRange (value) { if (!Array.isArray(value)) return; trimStart.value = Number(value[0] || 0); trimEnd.value = Number(value[1] || 0) }
function syncTrimFromFields () { trimStart.value = Math.max(0, Number(trimStart.value || 0)); trimEnd.value = Math.min(trimSourceDuration.value, Math.max(trimStart.value + (trimMaterial.value?.fileType === 'image' ? 0.1 : 0.5), Number(trimEnd.value || 0))); trimRange.value = [trimStart.value, trimEnd.value] }
function applyTrim () { if (!trimRow.value || !trimValid.value) return; trimRow.value.sourceStart = trimMaterial.value.fileType === 'image' ? 0 : trimStart.value; trimRow.value.sourceDuration = trimSourceDuration.value; trimRow.value.duration = trimDuration.value; trimVisible.value = false; markDirty(); ElMessage.success('修剪已应用到草稿，请保存后再生成候选') }
function hydrate (next) { hydrating.value = true; state.value = next; const plan = next.plan || {}; const params = next.params || {}; const mode = ['original', 'material-audio', 'silent'].includes(params.audioMode) ? params.audioMode : 'original'; audioNotice.value = params.audioMode && params.audioMode !== mode ? `旧版本音频模式“${params.audioMode}”无法在编辑器直接复用，已降级为保留原声。` : ''; draft.value = { segments: (plan.segments || []).map((row) => ({ index: row.index, materialId: row.materialId, sourceStart: Number(row.sourceStart || 0), sourceDuration: Number(row.sourceDuration || 0), duration: Number(row.duration || 0), slot: row.slot || 'body', enabled: row.enabled !== false })), audio: { mode, bgmMaterialId: params.bgmMaterialId ?? null, voiceMaterialId: params.voiceMaterialId ?? null, volume: Number(mode === 'original' ? (params.originalAudioVolume ?? 1) : (params.bgmVolume ?? 0.2)) }, subtitle: { enabled: Boolean(params.autoSubtitles), cleanSourceSubtitles: Boolean(params.cleanSourceSubtitles), safeBandMode: params.sourceSubtitleCleanMode || 'off' }, comment: next.session?.comment || '' }; dirty.value = false; nextTick(() => { hydrating.value = false }) }
watch(draft, () => { if (state.value && !hydrating.value) dirty.value = true }, { deep: true })
async function saveDraft () { saving.value = true; try { const saved = await api.saveOutputEditor(jobId, idx, state.value.session.id, payload()); hydrate(saved); ElMessage.success('编辑草稿已保存'); return saved } catch (error) { ElMessage.error(`保存草稿失败：${error.message}`); throw error } finally { saving.value = false } }
async function renderCandidate () { rendering.value = true; try { await saveDraft(); hydrate(await api.renderOutputEditor(jobId, idx, state.value.session.id)); ElMessage.success('候选已进入单条渲染与完整质检队列'); pollCandidate() } catch (error) { ElMessage.error(`生成候选失败：${error.message}`) } finally { rendering.value = false } }
async function applyCandidate () { applying.value = true; try { hydrate(await api.applyOutputEditor(jobId, idx, state.value.session.id)); ElMessage.success('候选已应用为当前成片，旧版本仍保留在历史中'); await loadRepair() } catch (error) { ElMessage.error(`应用候选失败：${error.message}`) } finally { applying.value = false } }
async function pollCandidate () { for (let count = 0; count < 120 && state.value?.session?.status === 'rendering'; count++) { await new Promise((resolve) => setTimeout(resolve, 2000)); try { hydrate(await api.outputEditor(jobId, idx)); } catch { return } } await loadRepair() }
async function loadRepair () { repairDetail.value = await api.outputRepair(jobId, idx) }
async function load () { if (!hasTarget.value) return; loading.value = true; try { const [jobRow, editor, repair] = await Promise.all([api.job(jobId), api.outputEditor(jobId, idx), api.outputRepair(jobId, idx)]); job.value = jobRow; repairDetail.value = repair; hydrate(editor); if (editor.session?.status === 'rendering') pollCandidate() } catch (error) { ElMessage.error(`编辑工作台加载失败：${error.message}`) } finally { loading.value = false } }
onMounted(load)
</script>

<style scoped>
.editor-page { max-width: 1600px; margin: 0 auto; padding: 2px 0 24px; }
.editor-head { display:flex; justify-content:space-between; align-items:flex-start; gap:16px; margin-bottom:16px; }
.editor-head h2 { margin:0 0 6px; font-size:22px; }
.editor-actions, .commit-actions, .timeline-actions { display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
.editor-grid { display:grid; grid-template-columns:minmax(0, 1.4fr) minmax(300px, .6fr); gap:16px; margin-top:14px; align-items:start; }
.editor-panel { min-width:0; border:1px solid #e4e7ed; border-radius:6px; background:#fff; padding:14px; }
.preview-panel { display:flex; flex-direction:column; }
.settings-panel { max-height:660px; overflow-y:auto; }
.panel-title { font-weight:700; margin-bottom:10px; }
.panel-title-row { display:flex; justify-content:space-between; align-items:flex-start; gap:12px; margin-bottom:10px; }
.timeline-total { white-space:nowrap; font-weight:700; color:#409eff; }
.preview-panel video { display:block; width:100%; height:min(64vh, 640px); min-height:360px; max-height:640px; object-fit:contain; background:#101828; border-radius:4px; }
.status-line { margin-top:8px; display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
.empty { min-height:360px; display:flex; align-items:center; justify-content:center; text-align:center; color:#8b93a5; background:#f7f8fa; padding:20px; }
.timeline-panel,.material-panel,.commit-panel { margin-top:14px; }
.timeline-actions { margin-top:12px; }
.visual-timeline { display:flex; gap:6px; min-height:104px; padding:8px; overflow-x:auto; background:#f7f8fa; border:1px solid #e4e7ed; border-radius:6px; margin-bottom:12px; }
.visual-segment { position:relative; flex:0 0 var(--segment-width); min-width:92px; height:86px; overflow:hidden; border:1px solid #cfd7e6; border-radius:5px; padding:0; background:#fff; text-align:left; cursor:pointer; }
.visual-segment img { width:100%; height:48px; display:block; object-fit:cover; background:#101828; }
.visual-segment-index { position:absolute; top:4px; left:5px; color:#fff; font-weight:700; text-shadow:0 1px 2px #000; }
.visual-segment-label, .visual-segment small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; padding:2px 5px 0; }
.visual-segment small { color:#667085; font-size:11px; }
.visual-segment.disabled { opacity:.48; filter:grayscale(1); }
.commit-actions { justify-content:flex-end; margin-top:10px; }
.source-blocker { display:flex; flex-direction:column; gap:4px; overflow-wrap:anywhere; }
.trim-meta { display:flex; justify-content:space-between; gap:12px; margin-bottom:10px; }
.trim-preview { display:block; width:100%; max-height:360px; object-fit:contain; background:#101828; border-radius:4px; margin-bottom:14px; }
.trim-image { background:#f7f8fa; object-fit:contain; }
.trim-fields { margin-top:10px; }
.trim-duration { font-weight:700; color:#409eff; }
.loading { height:260px; }
@media (max-width: 980px) {
  .editor-page { padding: 0 0 20px; }
  .editor-head, .panel-title-row { flex-direction: column; }
  .editor-grid { grid-template-columns: 1fr; }
  .settings-panel { max-height: none; overflow: visible; }
  .preview-panel video { height: min(60vh, 520px); min-height: 280px; }
}
@media (max-width: 640px) {
  .editor-actions, .commit-actions { width: 100%; }
  .editor-actions :deep(.el-button), .commit-actions :deep(.el-button) { flex: 1 1 150px; }
  .preview-panel video { height: min(58vh, 420px); min-height: 220px; }
  .trim-meta { flex-direction: column; }
}
</style>
