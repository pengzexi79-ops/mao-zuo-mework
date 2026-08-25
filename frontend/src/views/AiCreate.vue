<template>
  <div class="page ai-create">
    <div class="ai-create-head"><div><h2>AI 创作</h2><p>选择已确认并可执行的图片、视频或配音能力；每次生成都会进入任务队列并自动入素材库。</p></div><div class="ai-create-head-actions"><el-tag size="small" :type="providers.length ? 'success' : 'warning'">{{ providers.length ? `${providers.length} 个可执行 Provider` : '暂无可执行能力' }}</el-tag><el-button size="small" plain @click="$router.push('/ai')">配置 AI 能力</el-button></div></div>
    <el-alert v-if="!providers.length" type="warning" :closable="false" show-icon title="当前没有可执行的媒体能力"><template #default>请在 AI 接入中保存有效 API Key，并明确确认图片、视频或配音模型；仅识别到的候选模型不会自动开放生成。</template></el-alert>

    <el-tabs v-model="mode" class="create-tabs">
      <el-tab-pane label="AI 图片" name="image" :disabled="!operationProviders('image').length">
        <div v-if="!operationProviders('image').length" class="capability-empty">没有已确认的图片生成模型。请到 AI 接入识别并采用图片模型，且 Provider 必须真实支持 `/v1/images/generations`。</div>
        <section class="create-panel">
          <ProviderSelect v-model="image.providerId" operation="image" :providers="operationProviders('image')" @changed="ensureModel(image, 'image')" />
          <el-form label-position="top">
            <el-form-item label="图片模型"><el-select v-model="image.model" style="width:100%" :disabled="!image.providerId"><el-option v-for="model in activeModels(image.providerId, 'image')" :key="model" :label="model" :value="model" /></el-select></el-form-item>
            <el-form-item label="画面提示词"><el-input v-model="image.prompt" type="textarea" :rows="7" maxlength="4000" show-word-limit placeholder="主体、场景、镜头、光线、构图、画风和不希望出现的内容。" /></el-form-item>
            <div class="two"><el-form-item label="尺寸"><el-select v-model="image.size"><el-option label="正方形 1024 × 1024" value="1024x1024" /><el-option label="竖图 1024 × 1536" value="1024x1536" /><el-option label="横图 1536 × 1024" value="1536x1024" /></el-select></el-form-item><el-form-item label="质量"><el-select v-model="image.quality"><el-option label="低" value="low" /><el-option label="中" value="medium" /><el-option label="高" value="high" /></el-select></el-form-item></div>
            <ConfirmBox v-model="image.confirm" /><el-button type="primary" :loading="imageSubmitting" :disabled="!canSubmit(image)" @click="generateImage">确认并生成图片</el-button>
          </el-form>
        </section>
      </el-tab-pane>
      <el-tab-pane label="AI 视频" name="video" :disabled="!operationProviders('video').length">
        <div v-if="!operationProviders('video').length" class="capability-empty">没有已确认的视频生成模型。请到 AI 接入识别并采用视频模型，且 Provider 必须真实支持 `/v1/videos`。</div>
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
      <el-tab-pane label="AI 配音" name="voice" :disabled="!operationProviders('voice').length">
        <div v-if="!operationProviders('voice').length" class="capability-empty">没有已确认的配音模型。请到 AI 接入识别并采用配音模型，且 Provider 必须真实支持 `/v1/audio/speech`。</div>
        <section class="create-panel">
          <ProviderSelect v-model="voice.providerId" operation="voice" :providers="operationProviders('voice')" @changed="ensureModel(voice, 'voice')" />
          <el-form label-position="top">
            <el-form-item label="配音模型"><el-select v-model="voice.model" style="width:100%" :disabled="!voice.providerId"><el-option v-for="model in activeModels(voice.providerId, 'voice')" :key="model" :label="model" :value="model" /></el-select></el-form-item>
            <el-form-item label="配音文本"><el-input v-model="voice.input" type="textarea" :rows="7" maxlength="6000" show-word-limit placeholder="输入需要配音的台词或旁白。" /></el-form-item>
            <div class="two"><el-form-item label="声音"><el-select v-model="voice.voice" filterable allow-create><el-option v-for="name in voiceOptions(voice)" :key="name" :label="name" :value="name" /></el-select></el-form-item><el-form-item label="风格说明"><el-input v-model="voice.instructions" maxlength="1000" placeholder="例如：平静、清晰、适合短剧旁白" /></el-form-item></div>
            <ConfirmBox v-model="voice.confirm" /><el-button type="primary" :loading="voiceSubmitting" :disabled="!canSubmit(voice, 'input')" @click="generateVoice">确认并生成配音</el-button>
          </el-form>
        </section>
      </el-tab-pane>
    </el-tabs>

    <section class="create-panel">
      <div class="title-row"><h3>AI 生成任务</h3><el-button size="small" @click="loadTasks">刷新</el-button></div>
      <el-table :data="tasks" empty-text="暂无 AI 生成任务"><el-table-column prop="kind" label="类型" width="96"><template #default="{ row }">{{ kindLabel(row.kind) }}</template></el-table-column><el-table-column prop="status" label="状态" width="112"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template></el-table-column><el-table-column prop="phase" label="阶段" width="130"><template #default="{ row }"><code>{{ row.phase || row.status || '-' }}</code></template></el-table-column><el-table-column label="进度" width="150"><template #default="{ row }"><el-progress :percentage="row.progress || 0" :status="failureStatus(row.status) ? 'exception' : row.status === 'done' ? 'success' : undefined" /></template></el-table-column><el-table-column label="诊断" min-width="190"><template #default="{ row }"><div class="task-diagnostic"><code v-if="row.errorCode">{{ row.errorCode }}</code><span>{{ row.message || '-' }}</span><small v-if="row.maxAttempts">尝试 {{ row.attemptCount || 0 }} / {{ row.maxAttempts }}</small></div></template></el-table-column><el-table-column label="结果" width="120"><template #default="{ row }"><el-button v-if="row.materialId" link type="primary" @click="$router.push('/materials')">素材 #{{ row.materialId }}</el-button><span v-else>-</span></template></el-table-column></el-table>
    </section>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import ProviderSelect from '../components/ai/ProviderSelect.vue'

const ConfirmBox = defineComponent({ props: { modelValue: Boolean }, emits: ['update:modelValue'], setup (props, { emit }) { return () => h('label', { class: 'confirm-box' }, [h('input', { type: 'checkbox', checked: props.modelValue, onChange: event => emit('update:modelValue', event.target.checked) }), ' 我已确认本次请求会消耗该 Provider 的账号额度']) } })
const mode = ref('image'); const providers = ref([]); const tasks = ref([]); const imageSubmitting = ref(false); const videoSubmitting = ref(false); const voiceSubmitting = ref(false); const voices = ['alloy', 'ash', 'ballad', 'coral', 'echo', 'fable', 'onyx', 'nova', 'sage', 'shimmer']; let timer
const image = reactive({ providerId: null, model: '', prompt: '', size: '1024x1024', quality: 'medium', confirm: false }); const video = reactive({ providerId: null, model: '', prompt: '', size: '1280x720', seconds: 4, confirm: false }); const voice = reactive({ providerId: null, model: '', input: '', voice: 'coral', instructions: '', confirm: false })
function operationProviders (operation) { const field = `${operation}Models`; return providers.value.filter(provider => Array.isArray(provider[field]) && provider[field].length) }
function activeModels (providerId, operation) { return operationProviders(operation).find(provider => provider.id === providerId)?.[`${operation}Models`] || [] }
function ensureModel (form, operation) {
  const provider = operationProviders(operation).find(item => item.id === form.providerId)
  const choices = activeModels(form.providerId, operation)
  const providerBase = String(provider?.baseUrl || '').toLowerCase()
  const preferred = operation === 'voice' && providerBase.includes('dashscope')
    ? choices.find(model => /qwen(?:3)?-tts|qwen-tts/i.test(model))
    : choices[0]
  form.model = choices.includes(form.model) ? form.model : (preferred || choices[0] || '')
  if (operation === 'voice') {
    const lower = String(form.model || '').toLowerCase()
    if ((lower.includes('qwen3-tts') || lower.includes('qwen-tts')) && !['Cherry', 'longanhuan_v3.6'].includes(form.voice)) form.voice = 'Cherry'
  }
}
function voiceOptions (form) { const model = String(form.model || '').toLowerCase(); return (model.includes('qwen3-tts') || model.includes('qwen-tts')) ? ['Cherry', 'longanhuan_v3.6'] : voices }
function canSubmit (form, field = 'prompt') { return !!form.providerId && !!form.model && String(form[field] || '').trim().length >= 2 && !!form.confirm }
function kindLabel (kind) { return ({ 'ai-image': '图片', 'ai-video': '视频', 'ai-voice': '配音' })[kind] || kind }
function failureStatus (status) { return ['failed', 'failed_terminal', 'manual_review'].includes(status) }
function statusLabel (status) { return ({ accepted: '已受理', pending: '排队中', submitting: '提交中', running: '处理中', remote_submitted: '已远端提交', polling: '远端处理中', downloading: '下载中', validating: '校验中', done: '已完成', failed: '失败', failed_terminal: '终止失败', manual_review: '人工复核' })[status] || status }
function statusType (status) { return status === 'done' ? 'success' : failureStatus(status) ? 'danger' : ['accepted', 'pending'].includes(status) ? 'warning' : ['submitting', 'running', 'remote_submitted', 'polling', 'downloading', 'validating'].includes(status) ? 'primary' : 'info' }
function defaultProviders () { for (const [form, operation] of [[image, 'image'], [video, 'video'], [voice, 'voice']]) { if (!form.providerId || !activeModels(form.providerId, operation).length) form.providerId = operationProviders(operation)[0]?.id || null; ensureModel(form, operation) } if (!operationProviders(mode.value).length) mode.value = operationProviders('image').length ? 'image' : operationProviders('video').length ? 'video' : 'voice' }
async function load () { try { providers.value = await api.aiMediaProviders(); defaultProviders(); await loadTasks() } catch (error) { ElMessage.error(`读取 AI 创作配置失败：${error.message}`) } }
async function loadTasks () { try { tasks.value = await api.aiGenerationTasks({ silent: true }) } catch {} }
async function submit (action, form, flag, success) { flag.value = true; try { await action({ ...form }); ElMessage.success(success); form.confirm = false; await loadTasks() } catch (error) { ElMessage.error(`提交失败：${error.message}`) } finally { flag.value = false } }
const generateImage = () => submit(api.generateAiImage, image, imageSubmitting, '图片生成任务已提交')
const generateVideo = () => submit(api.generateAiVideo, video, videoSubmitting, '视频生成任务已提交')
const generateVoice = () => submit(api.generateAiVoice, voice, voiceSubmitting, '配音生成任务已提交')
onMounted(async () => { await load(); timer = setInterval(loadTasks, 2500) })
onBeforeUnmount(() => clearInterval(timer))
</script>

<style scoped>
.ai-create { display:flex; flex-direction:column; gap:14px; }.ai-create-head { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:4px 0; }.ai-create-head h2 { margin:0; font-size:22px; }.ai-create-head p { margin:5px 0 0; color:var(--el-text-color-secondary); font-size:13px; }.ai-create-head-actions { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }.capability-empty { margin-bottom:12px; padding:10px 12px; border:1px solid var(--el-color-warning-light-7); border-radius:5px; color:var(--el-color-warning-dark-2); background:var(--el-color-warning-light-9); font-size:13px; line-height:1.5; }.create-panel { border:1px solid var(--el-border-color-lighter); background:var(--el-bg-color-overlay,#fff); border-radius:8px; padding:16px; }.two { display:grid; grid-template-columns:1fr 1fr; gap:12px; }.two .el-select,.two .el-input-number { width:100%; }.title-row { display:flex; align-items:center; justify-content:space-between; }.task-diagnostic { display:flex; flex-direction:column; gap:3px; min-width:0; }.task-diagnostic code { color:var(--el-color-danger); font-size:12px; }.task-diagnostic span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.task-diagnostic small { color:var(--el-text-color-secondary); }.native-select { width:100%; height:32px; border:1px solid var(--el-border-color); border-radius:4px; background:var(--el-fill-color-blank); padding:0 8px; }.provider-box { margin-bottom:14px; }.provider-box label { display:block; margin-bottom:7px; color:var(--el-text-color-regular); font-size:14px; }.provider-links { display:flex; gap:14px; margin-top:7px; font-size:13px; }.provider-links a { color:var(--el-color-primary); }.confirm-box { display:block; margin:12px 0; font-size:13px; color:var(--el-text-color-regular); }@media(max-width:900px){.two{grid-template-columns:1fr}}
</style>
