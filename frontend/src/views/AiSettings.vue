<template>
  <div>
    <div class="ai-summary-grid">
      <div class="ai-summary-card"><span>已配置 Provider</span><b>{{ providers.filter(item => item.hasKey).length }}</b><small>密钥只保存在本机服务端</small></div>
      <div class="ai-summary-card"><span>文本 / 视觉</span><b>{{ providers.filter(item => item.hasKey && textModels(item).length).length }} / {{ providers.filter(item => item.hasKey && visionModels(item).length).length }}</b><small>用于脚本、镜头规划与素材理解</small></div>
      <div class="ai-summary-card"><span>媒体执行</span><b>{{ providers.filter(item => item.hasKey && mediaModelCount(item) > 0 && item.kind === 'openai').length }}</b><small>仅显示真实兼容且已声明的媒体模型</small></div>
      <div class="ai-summary-card"><span>待处理</span><b>{{ providers.filter(item => item.discoveryStatus === 'failed' || !item.hasKey).length }}</b><small>识别失败或尚未配置密钥</small></div>
    </div>
    <div class="card">
      <div class="card-title">
        人工智能服务商
        <span class="hint">支持多种人工智能服务协议，中转服务请选择兼容协议类型</span>
        <span style="flex:1"></span>
        <el-button size="small" plain @click="$router.push('/capabilities')">插件接口</el-button>
        <el-button size="small" type="primary" @click="openNew">新增供应商</el-button>
      </div>

      <el-alert v-if="providersError" type="error" :closable="false" show-icon style="margin-bottom:10px"
        title="供应商加载失败，请刷新页面重试" />
      <div v-else-if="!loading && !providers.length" class="muted" style="margin-bottom:10px">暂无 人工智能服务商。</div>
      <div v-if="loading" v-loading="loading" class="provider-loading"></div>
      <div v-else-if="!providers.length" class="muted provider-empty">暂无人工智能服务商，请从官方模板开始。</div>
      <div v-else class="provider-grid">
        <article v-for="row in providers" :key="row.id" class="provider-card">
          <div class="provider-card-head">
            <div><h3>{{ row.name }}</h3><span class="muted">{{ row.kind }} · 优先级 {{ row.priority }}</span></div>
            <el-switch v-model="row.enabled" size="small" @change="(v) => toggle(row, v)" />
          </div>
          <div class="provider-card-meta"><span :class="['provider-status', providerState(row).tone]">{{ providerState(row).label }}</span><code>{{ row.apiKeyMasked || '未配置 API Key' }}</code></div>
          <div class="provider-model-main"><span class="muted">默认文本模型</span><b>{{ row.defaultModel || '未选择' }}</b></div>
          <div class="provider-capabilities">
            <el-tag v-for="cap in providerCapabilities(row)" :key="cap.label" size="small" :type="cap.type" effect="plain">{{ cap.label }} {{ cap.count }}</el-tag>
            <span v-if="!providerCapabilities(row).length" class="muted">尚未确认能力</span>
          </div>
          <div class="provider-card-url muted" :title="row.baseUrl">{{ row.baseUrl }}</div>
          <div class="provider-card-actions">
            <el-button link type="success" size="small" :loading="testingId === row.id" @click="doTest(row)">测试</el-button>
            <el-button link type="warning" size="small" :loading="discoveringId === row.id" @click="discoverModels(row)">识别模型</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-popconfirm title="确定删除该供应商？" @confirm="doDelete(row)"><template #reference><el-button link type="danger" size="small">删除</el-button></template></el-popconfirm>
          </div>
        </article>
      </div>

      <div class="form-hint">优先级数字小的先用。模型识别只是观测结果；图片、视频、配音只有在确认模型能力且协议真实支持时才会进入执行链。密钥只提交给本机后端保存。</div>
    </div>

    <el-collapse class="advanced-ai-sections">
      <el-collapse-item name="routes" title="用途策略：为每个任务选择最合适的模型">
    <div class="card nested-ai-card">
      <div class="card-title">
        用途路由
        <span class="hint">不同环节可以用不同模型：钩子用强的，标签用便宜的</span>
        <span style="flex:1"></span>
        <el-button size="small" @click="loadRoutes">刷新</el-button>
      </div>
      <el-alert v-if="routesError" type="error" :closable="false" show-icon style="margin-bottom:10px"
        title="用途路由加载失败，请点击刷新重试" />
      <div v-else-if="routesLoading" v-loading="routesLoading" style="height:52px"></div>
      <div v-else-if="!routes.length" class="muted" style="margin-bottom:10px">暂无用途路由。</div>
      <el-table v-if="!routesLoading && !routesError" :data="routes" size="small">
        <el-table-column label="用途" width="150">
          <template #default="{ row }">
            <b>{{ USE_CASE_LABEL[row.useCase] || row.useCase }}</b>
          </template>
        </el-table-column>
        <el-table-column label="主供应商" width="200">
          <template #default="{ row }">
            <el-select v-model="row.providerId" clearable teleported popper-class="ai-route-popper" placeholder="跟随全局优先级" size="small" style="width:100%">
              <el-option v-for="p in providers" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="指定模型" width="250">
          <template #default="{ row }">
            <el-select v-model="row.model" clearable filterable allow-create teleported popper-class="ai-route-popper" size="small" style="width:100%" placeholder="留空用供应商默认模型">
              <el-option v-for="model in routeModels(row.providerId)" :key="model" :label="model" :value="model" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="备用链（失败自动顺延）" min-width="240">
          <template #default="{ row }">
            <el-select v-model="row.fallbackIds" multiple collapse-tags teleported popper-class="ai-route-popper" size="small" style="width:100%"
              placeholder="可多选">
              <el-option v-for="p in providers" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="saveRoute(row)">保存</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
      </el-collapse-item>
      <el-collapse-item name="logs" title="故障诊断：调用日志与响应摘要">
    <div class="card nested-ai-card">
      <div class="card-title">
        调用日志
        <span class="hint">出问题先看这里：是没配 密钥，还是模型名写错</span>
        <span style="flex:1"></span>
        <el-button size="small" @click="loadLogs">刷新</el-button>
      </div>
      <el-alert v-if="logsError" type="error" :closable="false" show-icon style="margin-bottom:10px"
        title="调用日志加载失败，请点击刷新重试" />
      <div v-else-if="logsLoading" v-loading="logsLoading" style="height:52px"></div>
      <div v-else-if="!logs.length" class="muted" style="margin-bottom:10px">暂无调用日志。</div>
      <el-table v-if="!logsLoading && !logsError" :data="logs" size="small" max-height="340">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="用途" width="100">
          <template #default="{ row }">{{ USE_CASE_LABEL[row.useCase] || row.useCase }}</template>
        </el-table-column>
        <el-table-column prop="model" label="模型" width="180" show-overflow-tooltip />
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.ok ? 'success' : 'danger'" size="small">{{ row.ok ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ row.latencyMs }} ms</template>
        </el-table-column>
        <el-table-column label="令牌用量" width="110">
          <template #default="{ row }">{{ (row.promptTokens || 0) + '/' + (row.completionTokens || 0) }}</template>
        </el-table-column>
        <el-table-column label="内容 / 错误" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span :style="{ color: row.ok ? '' : '#f56c6c' }">{{ row.ok ? row.preview : row.error }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
      </el-collapse-item>
    </el-collapse>

    <el-dialog v-model="dlgVisible" :title="form.id ? '编辑供应商' : '新增供应商'" width="600px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px" title="常用接口模板">
        <el-button v-for="template in providerTemplates" :key="template.name" link type="primary" @click="applyTemplate(template)">{{ template.name }}</el-button>
      </el-alert>
      <el-form label-width="100px">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="例如 主力中转站 / DeepSeek 官方" />
        </el-form-item>
        <el-form-item label="协议">
          <el-radio-group v-model="form.kind" @change="onKindChange">
            <el-radio-button value="openai">通用兼容协议</el-radio-button>
            <el-radio-button value="anthropic">智谱兼容协议</el-radio-button>
            <el-radio-button value="gemini">谷歌兼容协议</el-radio-button>
          </el-radio-group>
          <div class="form-hint">
            国内中转服务、常见国产模型及本地模型一般都选择「通用兼容协议」。
          </div>
        </el-form-item>
        <el-form-item label="服务地址">
          <el-input v-model="form.baseUrl" :placeholder="basePlaceholder" />
          <div class="form-hint">填到域名即可，路径会自动补。例：https://api.deepseek.com</div>
        </el-form-item>
        <el-form-item label="服务密钥">
          <el-input v-model="form.apiKey" show-password
            :placeholder="form.id ? '留空则不修改已保存的 密钥' : 'sk-...'" />
        </el-form-item>
        <el-form-item label="文本默认模型">
          <el-select v-model="form.defaultModel" filterable allow-create style="width:100%"
            placeholder="供助手、脚本和分镜编排使用">
            <el-option v-for="m in presetList" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-divider content-position="left">图片 / 视频 / 配音能力</el-divider>
        <div class="form-hint" style="margin:-8px 0 12px">只填写该服务商实际支持的模型。AI 创作会严格按这里显示，未填写的能力不能提交。</div>
        <div v-if="form.observedMediaText" class="observed-models"><span>最近识别到候选媒体模型：{{ form.observedMediaText }}</span><el-button size="small" plain type="primary" @click="adoptObservedMedia">全部带入草稿</el-button></div>
        <div v-if="form.observedMedia" class="observed-picker"><el-input v-model="form.observedQuery" size="small" clearable placeholder="搜索候选模型名称" /><div v-for="group in observedGroups" :key="group.key" class="observed-group"><div class="observed-group-head"><b>{{ group.label }}</b><span>{{ group.models.length }} 个候选</span><el-button link size="small" @click="toggleObservedGroup(group.key)">{{ group.models.every(model => form.observedSelected[group.key].includes(model)) ? '清除本组' : '全选本组' }}</el-button></div><label v-for="model in group.models" :key="model" class="observed-model-row"><input v-model="form.observedSelected[group.key]" type="checkbox" :value="model" /><code>{{ model }}</code><span class="muted">已发现 · 未确认</span></label></div></div>
        <el-form-item label="图片模型"><el-input v-model="form.imageModels" placeholder="逗号分隔，例如 image-model-a,image-model-b" /></el-form-item>
        <el-form-item label="视频模型"><el-input v-model="form.videoModels" placeholder="逗号分隔，例如 video-model-a" /></el-form-item>
        <el-form-item label="配音模型"><el-input v-model="form.voiceModels" placeholder="逗号分隔，例如 tts-model-a" /></el-form-item>
        <el-form-item label="官方接入页"><el-input v-model="form.setupUrl" placeholder="https:// 服务商获取 API Key 的官方页面" /><el-link v-if="form.setupUrl" :href="form.setupUrl" target="_blank" rel="noopener noreferrer" type="primary">打开官方接入 / API Key 页面</el-link></el-form-item>
        <el-form-item label="计费 / 额度页"><el-input v-model="form.billingUrl" placeholder="https:// 服务商官方计费或额度页面" /><el-link v-if="form.billingUrl" :href="form.billingUrl" target="_blank" rel="noopener noreferrer" type="primary">打开官方计费 / 额度页面</el-link></el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="1" :max="99" />
          <span class="muted" style="margin-left:10px">数字越小越优先</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlgVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const providerTemplates = [
  { name: 'OpenAI 官方', kind: 'openai', baseUrl: 'https://api.openai.com', model: 'gpt-4o-mini', image: 'gpt-image-1,gpt-image-1-mini', video: 'sora-2,sora-2-pro', voice: 'gpt-4o-mini-tts,tts-1,tts-1-hd', setupUrl: 'https://platform.openai.com/api-keys', billingUrl: 'https://platform.openai.com/settings/organization/billing/overview' },
  { name: 'OpenAI-compatible 第三方', kind: 'openai', baseUrl: '', model: '', image: '', video: '', voice: '', setupUrl: '', billingUrl: '' },
  { name: '火山方舟 / 豆包（文本 / 视觉理解）', kind: 'openai', baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', model: 'doubao-seed-1-6-250615', image: '', video: '', voice: '', setupUrl: 'https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey', billingUrl: 'https://console.volcengine.com/finance/expense' },
  { name: '通义千问（备用 / 视觉理解）', kind: 'openai', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode', model: 'qwen-plus', image: '', video: '', voice: '', setupUrl: 'https://dashscope.console.aliyun.com/apiKey', billingUrl: 'https://usercenter.console.aliyun.com/#/manage-account/payment' },
  { name: 'DeepSeek 官方（文本 / 分镜）', kind: 'openai', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat', image: '', video: '', voice: '', setupUrl: 'https://platform.deepseek.com/api_keys', billingUrl: 'https://platform.deepseek.com/usage' },
  { name: 'Claude 官方（文本 / 分镜）', kind: 'anthropic', baseUrl: 'https://api.anthropic.com', model: 'claude-3-5-haiku-20241022', image: '', video: '', voice: '', setupUrl: 'https://console.anthropic.com/settings/keys', billingUrl: 'https://console.anthropic.com/settings/billing' },
  { name: 'Gemini 官方（文本 / 分镜）', kind: 'gemini', baseUrl: 'https://generativelanguage.googleapis.com', model: 'gemini-2.0-flash', image: '', video: '', voice: '', setupUrl: 'https://aistudio.google.com/app/apikey', billingUrl: 'https://aistudio.google.com/' }
]

const USE_CASE_LABEL = {
  hook: '钩子文案',
  script: '脚本',
  titles: '标题',
  cta: '行动号召',
  tag: '标签',
  plan: '工作流编排',
  general: '通用'
}

const providers = ref([])
const routes = ref([])
const logs = ref([])
const presets = ref({})
const loading = ref(false)
const routesLoading = ref(false)
const logsLoading = ref(false)
const providersError = ref(false)
const routesError = ref(false)
const logsError = ref(false)
const saving = ref(false)
const testingId = ref(null)
const discoveringId = ref(null)
const dlgVisible = ref(false)

const form = reactive({
  id: null, name: '', kind: 'openai', baseUrl: '', apiKey: '', defaultModel: '', priority: 10, enabled: true,
  imageModels: '', videoModels: '', voiceModels: '', setupUrl: '', billingUrl: '', observedMedia: null, observedMediaText: '', observedQuery: '', observedSelected: { image: [], video: [], voice: [] }
})

const presetList = computed(() => presets.value[form.kind] || [])
const observedGroups = computed(() => {
  const query = String(form.observedQuery || '').trim().toLowerCase()
  const observed = form.observedMedia || { image: [], video: [], voice: [] }
  return [{ key: 'image', label: '图片', models: observed.image.filter(model => !query || model.toLowerCase().includes(query)) }, { key: 'video', label: '视频', models: observed.video.filter(model => !query || model.toLowerCase().includes(query)) }, { key: 'voice', label: '配音 / TTS', models: observed.voice.filter(model => !query || model.toLowerCase().includes(query)) }]
})
const basePlaceholder = computed(() => ({
  openai: 'https://api.openai.com  或  https://你的中转站域名',
  anthropic: 'https://api.anthropic.com',
  gemini: 'https://generativelanguage.googleapis.com'
}[form.kind]))

function providerModels (provider) {
  try { return typeof provider?.models === 'string' ? JSON.parse(provider.models || '{}') : (provider?.models || {}) } catch { return {} }
}
function textModels (provider) {
  const models = providerModels(provider)
  return Array.isArray(models) ? models : (Array.isArray(models.text) ? models.text : [])
}
function visionModels (provider) {
  const models = providerModels(provider)
  return Array.isArray(models?.vision) ? models.vision : []
}
function mediaModelCount (provider) {
  const caps = provider?.mediaCapabilities || {}
  return ['imageModels', 'videoModels', 'voiceModels'].reduce((total, key) => total + (Array.isArray(caps[key]) ? caps[key].length : 0), 0)
}
function providerCapabilities (provider) {
  const caps = provider?.mediaCapabilities || {}
  const groups = [
    ['文本', textModels(provider).length, 'info'],
    ['视觉', visionModels(provider).length, 'success'],
    ['图片', caps.imageModels?.length || 0, 'warning'],
    ['视频', caps.videoModels?.length || 0, 'danger'],
    ['配音', caps.voiceModels?.length || 0, 'primary']
  ]
  return groups.filter(([, count]) => count).map(([label, count, type]) => ({ label, count, type }))
}
function providerState (provider) {
  if (!provider.hasKey) return { label: '未配置', tone: 'muted' }
  if (!provider.enabled) return { label: '已停用', tone: 'muted' }
  if (provider.discoveryStatus === 'failed') return { label: '识别失败', tone: 'warning' }
  if (mediaModelCount(provider) && provider.kind === 'openai') return { label: '媒体可执行', tone: 'success' }
  if (provider.discoveryStatus === 'success') return { label: '模型已发现', tone: 'info' }
  return { label: '已授权', tone: 'info' }
}

function fmt(s) {
  if (!s) return '-'
  return String(s).replace('T', ' ').slice(5, 19)
}

function applyTemplate (template) {
  form.name = template.name
  form.kind = template.kind
  form.baseUrl = template.baseUrl
  form.defaultModel = template.model
  form.imageModels = template.image
  form.videoModels = template.video
  form.voiceModels = template.voice
  form.setupUrl = template.setupUrl
  form.billingUrl = template.billingUrl
  form.apiKey = ''
}

function splitModels (value) {
  return [...new Set(String(value || '').split(/[,，\n]+/).map(item => item.trim()).filter(Boolean))]
}

function capabilityBody () {
  return JSON.stringify({
    image: splitModels(form.imageModels),
    video: splitModels(form.videoModels),
    voice: splitModels(form.voiceModels),
    setupUrl: form.setupUrl.trim(),
    billingUrl: form.billingUrl.trim()
  })
}

function capabilityText (row, key) {
  return Array.isArray(row?.mediaCapabilities?.[key]) ? row.mediaCapabilities[key].join(',') : ''
}
function observedMedia (row) {
  const observed = providerModels(row)?.observed || {}
  return {
    image: Array.isArray(observed.image) ? observed.image : [],
    video: Array.isArray(observed.video) ? observed.video : [],
    voice: Array.isArray(observed.voice) ? observed.voice : []
  }
}
function currentMedia (row) {
  const caps = row?.mediaCapabilities || {}
  return { image: Array.isArray(caps.imageModels) ? caps.imageModels : [], video: Array.isArray(caps.videoModels) ? caps.videoModels : [], voice: Array.isArray(caps.voiceModels) ? caps.voiceModels : [] }
}
function observedMediaText (observed) {
  const groups = [['图片', observed.image], ['视频', observed.video], ['配音', observed.voice]]
    .filter(([, models]) => models.length)
    .map(([label, models]) => `${label} ${models.join('、')}`)
  return groups.join('；')
}
function toggleObservedGroup (key) {
  const models = form.observedMedia?.[key] || []
  const selected = form.observedSelected[key] || []
  form.observedSelected[key] = models.every(model => selected.includes(model)) ? [] : [...models]
}
function adoptObservedMedia () {
  const selected = form.observedSelected || { image: [], video: [], voice: [] }
  form.imageModels = selected.image.join(',')
  form.videoModels = selected.video.join(',')
  form.voiceModels = selected.voice.join(',')
  ElMessage.success('已将勾选的候选模型带入草稿；保存 Provider 后才会成为可执行能力')
}

function onKindChange() {
  form.defaultModel = presetList.value[0] || ''
  if (!form.baseUrl) {
    form.baseUrl = {
      openai: 'https://api.openai.com',
      anthropic: 'https://api.anthropic.com',
      gemini: 'https://generativelanguage.googleapis.com'
    }[form.kind]
  }
}

async function load() {
  loading.value = true
  try {
    providers.value = await api.providers()
    const pending = providers.value.filter((provider) => provider.hasKey && provider.discoveryStatus !== 'success')
    if (pending.length) {
      await Promise.allSettled(pending.map((provider) => api.discoverProviderModels(provider.id)))
      providers.value = await api.providers()
    }
    providersError.value = false
  } catch {
    providersError.value = true
  } finally {
    loading.value = false
  }
}

async function loadRoutes() {
  routesLoading.value = true
  try {
    const rs = await api.routes()
    routes.value = rs.map((r) => ({
      ...r,
      fallbackIds: parseFallbacks(r.fallbacks)
    }))
    routesError.value = false
  } catch {
    routesError.value = true
  } finally {
    routesLoading.value = false
  }
}

function parseFallbacks(s) {
  if (!s) return []
  try {
    const v = JSON.parse(s)
    return Array.isArray(v) ? v : []
  } catch {
    return []
  }
}

function routeModels (providerId) {
  const provider = providers.value.find((item) => item.id === providerId)
  if (!provider) return []
  try {
    const parsed = typeof provider.models === 'string' ? JSON.parse(provider.models || '{}') : provider.models
    const models = Array.isArray(parsed) ? parsed : parsed?.text
    return Array.isArray(models) ? models.filter(Boolean) : []
  } catch {
    return []
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    logs.value = await api.aiLogs()
    logsError.value = false
  } catch {
    logsError.value = true
  } finally {
    logsLoading.value = false
  }
}

function openNew() {
  Object.assign(form, {
    id: null, name: '', kind: 'openai', baseUrl: 'https://api.openai.com', apiKey: '',
    defaultModel: presets.value.openai?.[0] || '', priority: 10, enabled: true,
    imageModels: '', videoModels: '', voiceModels: '', setupUrl: '', billingUrl: '', observedMedia: null, observedMediaText: '', observedQuery: '', observedSelected: { image: [], video: [], voice: [] }
  })
  dlgVisible.value = true
}

function openEdit(row) {
  const observed = observedMedia(row)
  const current = currentMedia(row)
  Object.assign(form, {
    id: row.id, name: row.name, kind: row.kind, baseUrl: row.baseUrl, apiKey: '',
    defaultModel: row.defaultModel, priority: row.priority, enabled: row.enabled,
    imageModels: capabilityText(row, 'imageModels'), videoModels: capabilityText(row, 'videoModels'), voiceModels: capabilityText(row, 'voiceModels'),
    setupUrl: row.mediaCapabilities?.setupUrl || '', billingUrl: row.mediaCapabilities?.billingUrl || '',
    observedMedia: observed, observedMediaText: observedMediaText(observed), observedQuery: '', observedSelected: { image: [...current.image], video: [...current.video], voice: [...current.voice] }
  })
  dlgVisible.value = true
}

async function save() {
  if (!form.name) return ElMessage.warning('请填写名称')
  saving.value = true
  try {
    if (form.observedMedia) {
      form.imageModels = (form.observedSelected?.image || []).join(',') || form.imageModels
      form.videoModels = (form.observedSelected?.video || []).join(',') || form.videoModels
      form.voiceModels = (form.observedSelected?.voice || []).join(',') || form.voiceModels
    }
    const body = {
      name: form.name, kind: form.kind, baseUrl: form.baseUrl,
      defaultModel: form.defaultModel, mediaCapabilities: capabilityBody(), priority: form.priority, enabled: form.enabled
    }
    if (form.apiKey) body.apiKey = form.apiKey
    if (form.id && form.observedMedia) {
      for (const capability of ['image', 'video', 'voice']) {
        const selected = form.observedSelected?.[capability] || []
        if (selected.length) await api.adoptProviderMedia(form.id, { capability, models: selected })
      }
    }
    if (form.id) await api.updateProvider(form.id, body)
    else await api.createProvider(body)
    dlgVisible.value = false
    ElMessage.success('已保存')
    await load()
    await loadRoutes()
  } finally {
    saving.value = false
  }
}

async function toggle(row, v) {
  await api.updateProvider(row.id, { enabled: v })
}

async function doDelete(row) {
  await api.deleteProvider(row.id)
  load()
}

async function doTest(row) {
  testingId.value = row.id
  try {
    const r = await api.testProvider({ providerId: row.id })
    ElMessageBox.alert(r.text || '（空响应）', `${r.provider} · ${r.model} 连通正常`, { type: 'success' })
  } catch {
    /* 拦截器已提示 */
  } finally {
    testingId.value = null
    loadLogs()
  }
}

function escapeHtml (value) {
  return String(value ?? '').replace(/[&<>\"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '\"': '&quot;', "'": '&#39;' })[char])
}

async function discoverModels(row) {
  discoveringId.value = row.id
  try {
    const result = await api.discoverProviderModels(row.id)
    await load()
    const groups = [
      ['文本模型', result.textModels || result.models || []],
      ['图片生成模型', result.imageModels || []],
      ['视频生成模型', result.videoModels || []],
      ['配音 / TTS 模型', result.voiceModels || []],
      ['视觉理解候选', result.visionModels || []]
    ]
    const modelGroups = groups.map(([label, values]) => {
      const models = Array.isArray(values) ? values : []
      const body = models.length
        ? `<div class="model-lines">${models.map((model, index) => `<div class="model-line"><span class="model-index">${index + 1}</span><code>${escapeHtml(model)}</code></div>`).join('')}</div>`
        : '<div class="model-empty">该 Key / 端点当前未提供</div>'
      return `<section class="model-group"><div class="model-group-title"><h4>${escapeHtml(label)}</h4><em>${models.length} 个</em></div>${body}</section>`
    }).join('')
    const recommendationGroups = Object.entries(result.recommendations || {})
      .filter(([, values]) => Array.isArray(values) && values.length)
      .map(([label, values]) => `<div class="recommendation-row"><b>${escapeHtml(label)}</b><div class="recommendation-models">${values.map((model, index) => `<div class="recommendation-line"><span class="model-index">${index + 1}</span><code>${escapeHtml(model)}</code></div>`).join('')}</div></div>`)
      .join('')
    const recommendations = recommendationGroups
      ? `<section class="model-recommendations"><h4>文本用途推荐</h4>${recommendationGroups}</section>`
      : ''
    const html = `<div class="model-discovery-result"><div class="model-discovery-meta"><span>探测延迟 <b>${Number(result.latencyMs || 0)} ms</b></span><span>仅展示供应商实际返回或明确声明的能力</span></div>${modelGroups}${recommendations}<p class="model-discovery-note">普通文本模型不会被伪装成图片、视频或 TTS。若某项显示未提供，请在供应商控制台开通对应能力或更换支持该能力的 Provider。</p></div>`
    ElMessageBox.alert(html, '模型识别结果', { type: 'success', dangerouslyUseHTMLString: true, customClass: 'model-discovery-dialog', width: '680px' })
  } catch {
    /* 拦截器已提示 */
  } finally {
    discoveringId.value = null
  }
}

async function saveRoute(row) {
  await api.saveRoute(row.useCase, {
    providerId: row.providerId || null,
    model: row.model || null,
    fallbacks: JSON.stringify(row.fallbackIds || [])
  })
  ElMessage.success('路由已保存')
}

onMounted(async () => {
  try {
    presets.value = await api.presetModels()
  } catch {
    // 预设模型不可用不阻塞供应商、路由和日志的独立加载。
  }
  await Promise.all([load(), loadRoutes(), loadLogs()])
})
</script>

<style>
.model-discovery-dialog .el-message-box__message { width: 100%; max-height: 58vh; overflow: auto; }
.model-discovery-result { color: var(--el-text-color-primary); line-height: 1.5; }
.model-discovery-meta { display: flex; justify-content: space-between; gap: 16px; padding-bottom: 10px; color: var(--el-text-color-secondary); font-size: 12px; border-bottom: 1px solid var(--el-border-color-lighter); }
.model-group { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.model-group-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.model-group h4, .model-recommendations h4 { margin: 0; font-size: 14px; color: var(--el-text-color-primary); }
.model-group-title em { flex: 0 0 auto; padding: 2px 7px; border-radius: 10px; background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-size: 12px; font-style: normal; }
.model-lines { display: flex; flex-direction: column; gap: 5px; }
.model-line, .recommendation-line { display: flex; align-items: flex-start; gap: 8px; min-width: 0; padding: 5px 8px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; background: var(--el-fill-color-light); }
.model-line code, .recommendation-line code { min-width: 0; color: var(--el-text-color-regular); font: 12px/1.5 Consolas, Monaco, monospace; overflow-wrap: anywhere; white-space: normal; }
.model-index { flex: 0 0 20px; color: var(--el-text-color-placeholder); font-size: 11px; text-align: right; }
.model-empty { padding: 7px 8px; color: var(--el-text-color-placeholder); font-size: 13px; background: var(--el-fill-color-light); border-radius: 4px; }
.model-recommendations { padding: 12px 0 2px; }
.recommendation-row { display: grid; grid-template-columns: 170px minmax(0, 1fr); gap: 12px; padding: 9px 0; font-size: 12px; border-top: 1px solid var(--el-border-color-lighter); }
.recommendation-row b { padding-top: 5px; color: var(--el-text-color-primary); }
.recommendation-models { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.model-discovery-note { margin: 12px 0 0; padding: 9px 10px; color: var(--el-text-color-secondary); background: var(--el-fill-color-light); border-radius: 4px; font-size: 12px; }
@media (max-width: 720px) { .model-discovery-meta { display: block; } .model-discovery-meta span { display: block; margin-bottom: 4px; } .recommendation-row { display: block; } .recommendation-row b { display: block; margin-bottom: 3px; } }
.ai-summary-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:12px; margin-bottom:14px; }
.ai-summary-card { min-height:94px; padding:14px; border:1px solid var(--el-border-color-lighter); border-radius:6px; background:var(--el-bg-color-overlay); display:flex; flex-direction:column; gap:6px; }
.ai-summary-card span,.ai-summary-card small { color:var(--el-text-color-secondary); font-size:12px; }.ai-summary-card b { font-size:22px; }
.provider-loading { height:96px; }.provider-empty { padding:16px 0; }.provider-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(270px,1fr)); gap:12px; }.provider-card { display:flex; flex-direction:column; gap:10px; min-width:0; padding:14px; border:1px solid var(--el-border-color-lighter); border-radius:6px; background:var(--el-bg-color-overlay); }.provider-card-head,.provider-card-meta,.provider-card-actions { display:flex; align-items:center; justify-content:space-between; gap:10px; }.provider-card h3 { margin:0 0 4px; font-size:15px; }.provider-card-meta code,.provider-card-url { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:12px; }.provider-status { flex:0 0 auto; padding:3px 7px; border-radius:4px; background:var(--el-fill-color-light); font-size:12px; }.provider-status.success { color:var(--el-color-success); background:var(--el-color-success-light-9); }.provider-status.warning { color:var(--el-color-warning); background:var(--el-color-warning-light-9); }.provider-status.info { color:var(--el-color-primary); background:var(--el-color-primary-light-9); }.provider-status.muted { color:var(--el-text-color-secondary); }.provider-model-main { display:flex; flex-direction:column; gap:3px; }.provider-capabilities { display:flex; flex-wrap:wrap; gap:6px; min-height:24px; }.provider-card-actions { justify-content:flex-start; }.advanced-ai-sections { margin-top:14px; border:1px solid var(--el-border-color-lighter); border-radius:6px; }.nested-ai-card { margin:10px 0; border:0; box-shadow:none; }.ai-route-popper { z-index:3001 !important; max-width:calc(100vw - 24px); }.ai-route-popper .el-select-dropdown__item { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.observed-models { display:flex; align-items:center; justify-content:space-between; gap:10px; margin:-2px 0 10px; padding:8px 10px; border:1px solid var(--el-color-warning-light-7); border-radius:4px; background:var(--el-color-warning-light-9); color:var(--el-text-color-regular); font-size:12px; }.observed-picker { display:flex; flex-direction:column; gap:8px; max-height:280px; overflow:auto; margin-bottom:12px; padding:8px; border:1px solid var(--el-border-color-lighter); border-radius:5px; background:var(--el-fill-color-light); }.observed-group { padding:6px 0; border-bottom:1px solid var(--el-border-color-lighter); }.observed-group:last-child { border-bottom:0; }.observed-group-head { display:flex; align-items:center; gap:8px; }.observed-group-head span { color:var(--el-text-color-secondary); font-size:12px; }.observed-group-head .el-button { margin-left:auto; }.observed-model-row { display:flex; align-items:center; gap:8px; min-height:28px; padding:3px 6px; border-radius:4px; cursor:pointer; }.observed-model-row:hover { background:var(--el-bg-color-overlay); }.observed-model-row code { min-width:0; flex:1; overflow-wrap:anywhere; font-size:12px; }
@media (max-width:900px) { .ai-summary-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } } @media (max-width:560px) { .ai-summary-grid { grid-template-columns:1fr; }.observed-models { align-items:flex-start; flex-direction:column; } }
</style>
