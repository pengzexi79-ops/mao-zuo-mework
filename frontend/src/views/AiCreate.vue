<template>
  <div class="page ai-create">
    <div class="ai-create-head"><div><h2>AI 创作</h2><p>生成结果会自动进入素材库，可继续导入、预览和人工二创。</p></div><div class="ai-create-head-actions"><el-tag size="small" :type="providers.length ? 'success' : 'warning'">{{ providers.length ? `${providers.length} 个可执行 Provider` : '暂无可执行能力' }}</el-tag><el-button size="small" plain @click="$router.push('/materials')">导入素材</el-button><el-button size="small" plain @click="$router.push('/media-tools')">媒体二创</el-button><el-button size="small" plain @click="$router.push('/ai')">配置 AI 能力</el-button></div></div>
    <el-alert v-if="!providers.length" type="warning" :closable="false" show-icon title="当前没有可执行的媒体能力"><template #default>请在 AI 接入中保存有效 API Key 并执行 AI 识别。猫作会自动接通已支持的官方工作空间；未知中转站的候选模型仍需人工确认协议。</template></el-alert>

    <el-tabs v-model="mode" class="create-tabs">
      <el-tab-pane label="AI 图片" name="image" :disabled="!operationProviders('image').length">
        <div v-if="!operationProviders('image').length" class="capability-empty">没有可执行的图片能力。请在 AI 接入中填写实际模型，并选择已注册的图片协议。</div>
        <section class="create-panel">
          <ProviderSelect v-model="image.providerId" operation="image" :providers="operationProviders('image')" @changed="ensureModel(image, 'image')" />
          <el-form label-position="top">
            <el-form-item label="图片模型"><el-select v-model="image.model" style="width:100%" :disabled="!image.providerId"><el-option v-for="model in activeModels(image.providerId, 'image')" :key="model" :label="model" :value="model" /></el-select></el-form-item>
            <el-form-item label="画面提示词"><el-input v-model="image.prompt" type="textarea" :rows="7" maxlength="4000" show-word-limit placeholder="主体、场景、镜头、光线、构图、画风和不希望出现的内容。" /></el-form-item>
            <div class="two"><el-form-item label="尺寸"><el-select v-model="image.size"><el-option label="自动（模型原生画布）" value="auto" /><el-option label="正方形 1024 × 1024" value="1024x1024" /><el-option label="竖图 1024 × 1536" value="1024x1536" /><el-option label="横图 1536 × 1024" value="1536x1024" /></el-select></el-form-item><el-form-item label="质量"><el-select v-model="image.quality"><el-option label="低" value="low" /><el-option label="中" value="medium" /><el-option label="高" value="high" /></el-select></el-form-item></div>
            <ConfirmBox v-model="image.confirm" /><el-button type="primary" :loading="imageSubmitting" :disabled="!canSubmit(image)" @click="generateImage">确认并生成图片</el-button>
          </el-form>
        </section>
      </el-tab-pane>
      <el-tab-pane label="AI 视频" name="video">
        <div v-if="!operationProviders('video').length" class="capability-empty">没有可执行的视频能力。请在 AI 接入中填写实际模型，并选择已注册的视频协议。</div>
        <section class="create-panel">
          <ProviderSelect v-model="video.providerId" operation="video" :providers="operationProviders('video')" @changed="ensureModel(video, 'video')" />
          <el-form label-position="top">
            <el-form-item label="视频模型"><el-select v-model="video.model" style="width:100%" :disabled="!video.providerId"><el-option v-for="model in activeModels(video.providerId, 'video')" :key="model" :label="model" :value="model" /></el-select></el-form-item>
            <el-form-item label="视频提示词"><el-input v-model="video.prompt" type="textarea" :rows="7" maxlength="4000" show-word-limit placeholder="角色、动作、镜头运动、节奏、音画、场景与画幅。" /></el-form-item>
            <div class="two"><el-form-item label="画幅"><el-select v-model="video.size"><el-option label="横屏 1280 × 720" value="1280x720" /><el-option label="竖屏 720 × 1280" value="720x1280" /><el-option label="方形 1024 × 1024" value="1024x1024" /></el-select></el-form-item><el-form-item label="时长（秒）"><el-input-number v-model="video.seconds" :min="2" :max="12" /></el-form-item></div>
            <ConfirmBox v-model="video.confirm" /><el-button type="primary" :loading="videoSubmitting" :disabled="!canSubmit(video)" @click="generateVideo">确认并生成视频</el-button>
          </el-form>
        </section>
      </el-tab-pane>
      <el-tab-pane label="AI 配音" name="voice">
        <div v-if="!operationProviders('voice').length" class="capability-empty">没有可执行的配音能力。请在 AI 接入中填写实际模型，并选择已注册的配音协议。</div>
        <section class="create-panel">
          <ProviderSelect v-model="voice.providerId" operation="voice" :providers="operationProviders('voice')" @changed="ensureModel(voice, 'voice')" />
          <el-form label-position="top">
            <el-form-item label="配音模型"><el-select v-model="voice.model" style="width:100%" :disabled="!voice.providerId" @change="adaptVoiceSelection(voice)"><el-option v-for="model in activeModels(voice.providerId, 'voice')" :key="model" :label="model" :value="model" /></el-select></el-form-item>
            <el-form-item label="配音文本"><el-input v-model="voice.input" type="textarea" :rows="7" maxlength="6000" show-word-limit placeholder="输入需要配音的台词或旁白。" /></el-form-item>
            <div class="two"><el-form-item label="声音"><el-select v-model="voice.voice" filterable allow-create><el-option v-for="name in voiceOptions(voice)" :key="name" :label="name" :value="name" /></el-select></el-form-item><el-form-item label="风格说明"><el-input v-model="voice.instructions" maxlength="1000" placeholder="例如：平静、清晰、适合短剧旁白" /></el-form-item></div>
            <ConfirmBox v-model="voice.confirm" /><el-button type="primary" :loading="voiceSubmitting" :disabled="!canSubmit(voice, 'input')" @click="generateVoice">确认并生成配音</el-button>
          </el-form>
        </section>
      </el-tab-pane>
    </el-tabs>

    <section class="create-panel">
      <div class="title-row task-title-row"><div><h3>AI 生成任务</h3><span class="task-hint">保存只确认任务记录；生成素材始终保留在素材库</span></div><div class="task-toolbar"><el-button size="small" @click="loadTasks">刷新</el-button><el-button size="small" type="success" plain :loading="taskSaving" :disabled="!saveableTasks.length" @click="saveAllTasks">一键保存（{{ saveableTasks.length }}）</el-button><el-button size="small" type="success" :loading="taskSaving" :disabled="!selectedSavableTasks.length" @click="saveSelectedTasks">保存选中（{{ selectedSavableTasks.length }}）</el-button><el-button size="small" type="danger" plain :loading="taskDeleting" :disabled="!selectedTasks.length" @click="deleteSelectedTasks">删除选中（{{ selectedTasks.length }}）</el-button><el-popconfirm title="仅删除已结束的 AI 生成任务记录，不删除素材库中的生成文件。继续吗？" @confirm="clearFinishedTasks"><template #reference><el-button size="small" type="danger" plain :loading="taskDeleting" :disabled="!tasks.some(row => !isActiveTask(row))">删除全部已结束</el-button></template></el-popconfirm><el-button size="small" :disabled="!selectedTasks.length" @click="clearTaskSelection">清空选择</el-button></div></div>
      <el-table ref="taskTable" :data="tasks" row-key="id" empty-text="暂无 AI 生成任务" @select="onTaskSelection" @select-all="onTaskSelectAll">
        <el-table-column type="selection" reserve-selection width="44" :selectable="taskSelectable" />
        <el-table-column prop="kind" label="类型" width="96"><template #default="{ row }">{{ kindLabel(row.kind) }}</template></el-table-column>
        <el-table-column prop="status" label="状态" width="112"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="phase" label="阶段" width="130"><template #default="{ row }"><code>{{ row.phase || row.status || '-' }}</code></template></el-table-column>
        <el-table-column label="进度" width="150"><template #default="{ row }"><el-progress :percentage="row.progress || 0" :status="failureStatus(row.status) ? 'exception' : row.status === 'done' ? 'success' : undefined" /></template></el-table-column>
        <el-table-column label="诊断" min-width="300"><template #default="{ row }"><div class="task-diagnostic"><code v-if="row.errorCode">{{ row.errorCode }}</code><span>{{ row.message || '-' }}</span><small>{{ row.providerName || '未记录供应商' }} · {{ row.model || '未记录模型' }}</small><small v-if="taskAdvice(row)" class="task-advice">{{ taskAdvice(row) }}</small><small v-if="row.maxAttempts">尝试 {{ row.attemptCount || 0 }} / {{ row.maxAttempts }}</small></div></template></el-table-column>
        <el-table-column label="结果" min-width="260"><template #default="{ row }"><div v-if="row.materialId" class="result-actions"><el-tag v-if="row.status === 'done'" size="small" type="success">已保存</el-tag><el-button v-if="row.status === 'done'" link type="success" @click="saveTask(row)">确认保存</el-button><el-button v-else link type="success" :disabled="true">待完成</el-button><el-button link type="primary" @click="openTaskPreview(row)">预览</el-button><el-button link type="primary" @click="openMaterialLibrary(row.materialId)">素材 #{{ row.materialId }}</el-button><el-button link type="success" @click="openMediaTools(row.materialId)">继续二创</el-button><el-popconfirm title="仅删除任务记录，不删除素材库中的生成文件。继续吗？" @confirm="deleteTask(row)"><template #reference><el-button link type="danger" :disabled="isActiveTask(row)">删除</el-button></template></el-popconfirm></div><span v-else-if="!isActiveTask(row)" class="result-actions"><span>-</span><el-popconfirm title="仅删除任务记录，不影响素材库。继续吗？" @confirm="deleteTask(row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm></span><span v-else>-</span></template></el-table-column>
      </el-table>
    </section>
    <el-dialog v-model="previewVisible" :title="previewTask ? `${kindLabel(previewTask.kind)}结果预览` : '结果预览'" width="min(760px, calc(100vw - 28px))" destroy-on-close>
      <div v-if="previewTask?.materialId" class="task-preview">
        <img v-if="previewTask.kind === 'ai-image'" :src="api.materialPreviewUrl(previewTask.materialId)" alt="AI 生成图片" />
        <video v-else-if="previewTask.kind === 'ai-video'" :src="api.materialPreviewUrl(previewTask.materialId)" controls preload="metadata" />
        <audio v-else :src="api.materialPreviewUrl(previewTask.materialId)" controls preload="metadata" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import ProviderSelect from '../components/ai/ProviderSelect.vue'
import { useRouter } from 'vue-router'

const ConfirmBox = defineComponent({ props: { modelValue: Boolean }, emits: ['update:modelValue'], setup (props, { emit }) { return () => h('label', { class: 'confirm-box' }, [h('input', { type: 'checkbox', checked: props.modelValue, onChange: event => emit('update:modelValue', event.target.checked) }), ' 我已确认本次请求会消耗该 Provider 的账号额度']) } })
const router = useRouter()
const mode = ref('image'); const providers = ref([]); const tasks = ref([]); const selectedTasks = ref([]); const selectedTaskIds = ref(new Set()); const taskTable = ref(null); const taskSaving = ref(false); const taskDeleting = ref(false); const imageSubmitting = ref(false); const videoSubmitting = ref(false); const voiceSubmitting = ref(false); const previewTask = ref(null); const previewVisible = ref(false); const openAiVoices = ['alloy', 'ash', 'ballad', 'coral', 'echo', 'fable', 'onyx', 'nova', 'sage', 'shimmer']; const qwenVoices = ['Cherry', 'longanhuan_v3.6']; const miniMaxVoices = ['male-qn-qingse']; let tasksLoading = false; let syncingSelection = false; let timer
const image = reactive({ providerId: null, model: '', prompt: '', size: '1024x1024', quality: 'medium', confirm: false }); const video = reactive({ providerId: null, model: '', prompt: '', size: '1280x720', seconds: 4, confirm: false }); const voice = reactive({ providerId: null, model: '', input: '', voice: '', instructions: '', confirm: false })
function operationProviders (operation) { const field = `${operation}Models`; return providers.value.filter(provider => Array.isArray(provider[field]) && provider[field].length) }
function activeModels (providerId, operation) { return operationProviders(operation).find(provider => provider.id === providerId)?.[`${operation}Models`] || [] }
function voiceProtocol (form) {
  const provider = operationProviders('voice').find(item => item.id === form.providerId)
  return provider?.modelRoutes?.voice?.[form.model]?.protocol || provider?.voiceProtocol || ''
}
function isMiniMaxVoiceModel (model) {
  const lower = String(model || '').toLowerCase()
  return (lower.includes('minimax') && /(speech|tts|voice)/.test(lower)) || /(^|[\/_:.-])speech-(?:0?[12]|2(?:\.\d+)?)(?:[_.-]|$)/.test(lower)
}
function isQwenVoiceModel (model) { const lower = String(model || '').toLowerCase(); return lower.includes('qwen3-tts') || lower.includes('qwen-tts') }
function isOpenAiVoiceModel (model) {
  const lower = String(model || '').toLowerCase()
  return lower.startsWith('tts-1') || lower.includes('gpt-4o-mini-tts') || (lower.includes('openai') && lower.includes('tts'))
}
function includesVoice (voices, current) { return voices.some(item => item.toLowerCase() === String(current || '').toLowerCase()) }
function voiceFamily (form) {
  const provider = operationProviders('voice').find(item => item.id === form.providerId)
  if (isMiniMaxVoiceModel(form.model)) return 'minimax'
  if (isQwenVoiceModel(form.model)) return 'qwen'
  const protocol = voiceProtocol(form)
  if (protocol === 'dashscope_minimax_tts_http') return 'minimax'
  if (protocol === 'dashscope_tts_http') return 'qwen'
  if (protocol === 'openai_audio_speech' && (provider?.providerMode === 'official' || isOpenAiVoiceModel(form.model))) return 'openai'
  return 'custom'
}
function adaptVoiceSelection (form) {
  const family = voiceFamily(form)
  const current = String(form.voice || '').trim()
  if (family === 'minimax' && (!current || includesVoice(openAiVoices, current) || includesVoice(qwenVoices, current))) form.voice = miniMaxVoices[0]
  if (family === 'qwen' && (!current || includesVoice(openAiVoices, current) || includesVoice(miniMaxVoices, current))) form.voice = qwenVoices[0]
  if (family === 'openai' && (!current || includesVoice(qwenVoices, current) || includesVoice(miniMaxVoices, current))) form.voice = 'coral'
  if (family === 'custom' && includesVoice([...openAiVoices, ...qwenVoices, ...miniMaxVoices], current)) form.voice = ''
}
function ensureModel (form, operation) {
  const choices = activeModels(form.providerId, operation)
  const preferred = choices[0]
  form.model = choices.includes(form.model) ? form.model : (preferred || choices[0] || '')
  if (operation === 'voice') adaptVoiceSelection(form)
}
function voiceOptions (form) { return voiceFamily(form) === 'qwen' ? qwenVoices : voiceFamily(form) === 'minimax' ? miniMaxVoices : voiceFamily(form) === 'openai' ? openAiVoices : [] }
function canSubmit (form, field = 'prompt') { const minimum = field === 'prompt' && form === video ? 2 : field === 'input' ? 2 : 1; return !!form.providerId && !!form.model && String(form[field] || '').trim().length >= minimum && !!form.confirm }
function kindLabel (kind) { return ({ 'ai-image': '图片', 'ai-video': '视频', 'ai-voice': '配音' })[kind] || kind }
function failureStatus (status) { return ['failed', 'failed_terminal', 'manual_review'].includes(status) }
function statusLabel (status) { return ({ accepted: '已受理', pending: '排队中', submitting: '提交中', running: '处理中', remote_submitted: '已远端提交', polling: '远端处理中', downloading: '下载中', validating: '校验中', done: '已完成', failed: '失败', failed_terminal: '终止失败', manual_review: '人工复核' })[status] || status }
function statusType (status) { return status === 'done' ? 'success' : failureStatus(status) ? 'danger' : ['accepted', 'pending'].includes(status) ? 'warning' : ['submitting', 'running', 'remote_submitted', 'polling', 'downloading', 'validating'].includes(status) ? 'primary' : 'info' }
function taskAdvice (task) {
  if (!task?.providerAvailable) return '这是已停用、已删除或地址与密钥不匹配的历史供应商任务，不代表当前可执行配置。'
  const message = String(task.message || '')
  if (task.errorCode === 'AUTH_REQUIRED') return '供应商拒绝鉴权，请在 AI 接入检查密钥、账号状态和图片生成权限。'
  if (task.errorCode === 'MEDIA_ENDPOINT_NOT_FOUND' || /HTTP\s*404/i.test(message)) return '图片协议或端点不匹配，请重新识别能力或配置供应商的真实图片端点。'
  return ''
}
const ACTIVE_TASK_STATUSES = ['accepted', 'pending', 'submitting', 'running', 'remote_submitted', 'polling', 'downloading', 'validating']
function isActiveTask (task) { return ACTIVE_TASK_STATUSES.includes(task?.status) }
function taskSelectable (task) { return !isActiveTask(task) }
const selectedSavableTasks = computed(() => selectedTasks.value.filter(task => task.materialId && task.status === 'done'))
const saveableTasks = computed(() => tasks.value.filter(task => task.materialId && task.status === 'done'))
function openTaskPreview (task) { previewTask.value = task; previewVisible.value = true }
function openMaterialLibrary (materialId) { router.push({ path: '/materials', query: { materialId: String(materialId) } }) }
function openMediaTools (materialId) { router.push({ path: '/media-tools', query: { materialId: String(materialId) } }) }
function defaultProviders () { for (const [form, operation] of [[image, 'image'], [video, 'video'], [voice, 'voice']]) { if (!form.providerId || !activeModels(form.providerId, operation).length) form.providerId = operationProviders(operation)[0]?.id || null; ensureModel(form, operation) } if (!operationProviders(mode.value).length) mode.value = operationProviders('image').length ? 'image' : operationProviders('video').length ? 'video' : 'voice' }
async function load () { try { providers.value = await api.aiMediaProviders(); defaultProviders(); await loadTasks() } catch (error) { ElMessage.error(`读取 AI 创作配置失败：${error.message}`) } }
async function loadTasks () {
  if (tasksLoading || taskSaving.value || taskDeleting.value) return
  tasksLoading = true
  try {
    const nextTasks = await api.aiGenerationTasks({ silent: true })
    const selectedIds = new Set(selectedTaskIds.value)
    tasks.value = nextTasks
    const validSelected = nextTasks.filter(task => selectedIds.has(String(task.id)) && taskSelectable(task))
    selectedTasks.value = validSelected
    selectedTaskIds.value = new Set(validSelected.map(task => String(task.id)))
    await nextTick()
    if (taskTable.value) {
      syncingSelection = true
      try {
        taskTable.value.clearSelection()
        for (const task of validSelected) taskTable.value.toggleRowSelection(task, true)
      } finally { syncingSelection = false }
    }
  } catch {} finally { tasksLoading = false }
}
function onTaskSelection (rows) {
  if (syncingSelection) return
  selectedTasks.value = rows || []
  selectedTaskIds.value = new Set(selectedTasks.value.map(task => String(task.id)))
}
function onTaskSelectAll (rows) { onTaskSelection(rows) }
function clearTaskSelection () {
  selectedTasks.value = []
  selectedTaskIds.value = new Set()
  taskTable.value?.clearSelection()
}
async function saveTask (task) {
  if (!task?.materialId || task.status !== 'done') return ElMessage.warning('该任务还没有可保存的生成结果')
  try { await api.saveAiGenerationTask(task.id); ElMessage.success(`任务已保存到素材库（素材 #${task.materialId}）`); await loadTasks() } catch (error) { ElMessage.error(`保存失败：${error.message}`) }
}
async function saveSelectedTasks () {
  const ids = selectedSavableTasks.value.map(task => task.id)
  if (!ids.length) return ElMessage.warning('请先勾选已完成且有生成结果的任务')
  taskSaving.value = true
  try { const result = await api.batchSaveAiGenerationTasks({ ids }); ElMessage.success(`已确认 ${result.saved || 0} 条生成结果，当前选择已保留`) } catch (error) { ElMessage.error(`保存失败：${error.message}`) } finally { taskSaving.value = false }
}
async function saveAllTasks () {
  const ids = saveableTasks.value.map(task => task.id)
  if (!ids.length) return
  taskSaving.value = true
  try { const result = await api.batchSaveAiGenerationTasks({ ids }); ElMessage.success(`已确认 ${result.saved || 0} 条生成结果，当前选择已保留`) } catch (error) { ElMessage.error(`保存失败：${error.message}`) } finally { taskSaving.value = false }
}
async function deleteTask (task) {
  if (isActiveTask(task)) return ElMessage.warning('任务正在处理中，请完成或失败后再删除')
  taskDeleting.value = true
  try { await api.deleteAiGenerationTask(task.id); ElMessage.success('任务记录已删除，素材库文件未删除'); clearTaskSelection(); await loadTasks() } catch (error) { ElMessage.error(`删除失败：${error.message}`) } finally { taskDeleting.value = false }
}
async function deleteSelectedTasks () {
  if (!selectedTasks.value.length) return
  const ids = [...selectedTaskIds.value]
  if (!ids.length) return ElMessage.warning('请先勾选要删除的任务')
  taskDeleting.value = true
  try { const result = await api.batchDeleteAiGenerationTasks({ ids }); ElMessage.success(`已删除 ${result.deleted || 0} 条任务记录，素材库文件未删除${result.skipped?.length ? `，跳过 ${result.skipped.length} 条` : ''}`); clearTaskSelection(); await loadTasks() } catch (error) { ElMessage.error(`删除失败：${error.message}`) } finally { taskDeleting.value = false }
}
async function clearFinishedTasks () {
  taskDeleting.value = true
  try { const result = await api.clearFinishedAiGenerationTasks(); ElMessage.success(`已删除 ${result.deleted || 0} 条已结束任务记录，素材库文件未删除`); clearTaskSelection(); await loadTasks() } catch (error) { ElMessage.error(`清理失败：${error.message}`) } finally { taskDeleting.value = false }
}
async function submit (action, form, flag, success) { flag.value = true; try { await action({ ...form }); ElMessage.success(success); form.confirm = false; await loadTasks() } catch (error) { ElMessage.error(`提交失败：${error.message}`) } finally { flag.value = false } }
const generateImage = () => submit(api.generateAiImage, image, imageSubmitting, '图片生成任务已提交')
const generateVideo = () => submit(api.generateAiVideo, video, videoSubmitting, '视频生成任务已提交')
const generateVoice = () => { adaptVoiceSelection(voice); return submit(api.generateAiVoice, voice, voiceSubmitting, '配音生成任务已提交') }
onMounted(async () => { await load(); timer = setInterval(loadTasks, 2500) })
onBeforeUnmount(() => clearInterval(timer))
</script>

<style scoped>
.ai-create { display:flex; flex-direction:column; gap:14px; }.ai-create-head { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:4px 0; }.ai-create-head h2 { margin:0; font-size:22px; }.ai-create-head p { margin:5px 0 0; color:var(--el-text-color-secondary); font-size:13px; }.ai-create-head-actions { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }.capability-empty { margin-bottom:12px; padding:10px 12px; border:1px solid var(--el-color-warning-light-7); border-radius:5px; color:var(--el-color-warning-dark-2); background:var(--el-color-warning-light-9); font-size:13px; line-height:1.5; }.create-panel { border:1px solid var(--el-border-color-lighter); background:var(--el-bg-color-overlay,#fff); border-radius:8px; padding:16px; }.two { display:grid; grid-template-columns:1fr 1fr; gap:12px; }.two .el-select,.two .el-input-number { width:100%; }.title-row { display:flex; align-items:center; justify-content:space-between; }.task-title-row { align-items:flex-start; gap:12px; }.task-hint { display:block; margin-top:4px; color:var(--el-text-color-secondary); font-size:12px; }.task-toolbar { display:flex; justify-content:flex-end; gap:8px; flex-wrap:wrap; }.task-diagnostic { display:flex; flex-direction:column; gap:3px; min-width:0; }.task-diagnostic code { color:var(--el-color-danger); font-size:12px; }.task-diagnostic span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.task-diagnostic small { color:var(--el-text-color-secondary); }.task-diagnostic .task-advice { color:var(--el-color-warning-dark-2); white-space:normal; line-height:1.45; }.result-actions { display:flex; align-items:center; flex-wrap:wrap; gap:2px 8px; }.task-preview { display:flex; align-items:center; justify-content:center; min-height:160px; background:#111; border-radius:6px; padding:8px; }.task-preview img,.task-preview video { display:block; max-width:100%; max-height:65vh; object-fit:contain; }.task-preview audio { width:100%; }.task-preview audio { width:100%; }.native-select { width:100%; height:32px; border:1px solid var(--el-border-color); border-radius:4px; background:var(--el-fill-color-blank); padding:0 8px; }.provider-box { margin-bottom:14px; }.provider-box label { display:block; margin-bottom:7px; color:var(--el-text-color-regular); font-size:14px; }.provider-links { display:flex; gap:14px; margin-top:7px; font-size:13px; }.confirm-box { display:block; margin:12px 0; font-size:13px; color:var(--el-text-color-regular); }@media(max-width:1100px){.task-title-row{flex-direction:column}.task-toolbar{justify-content:flex-start}}@media(max-width:900px){.two{grid-template-columns:1fr}.ai-create-head{align-items:flex-start;flex-direction:column}}
</style>
