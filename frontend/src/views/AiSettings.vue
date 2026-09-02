<template>
  <div>
    <div class="ai-summary-grid">
      <div class="ai-summary-card"><span>已配置 Provider</span><b>{{ providers.filter(item => item.hasKey).length }}</b><small>密钥只保存在本机服务端</small></div>
      <div class="ai-summary-card"><span>文本 / 视觉</span><b>{{ providers.filter(item => item.hasKey && textModels(item).length).length }} / {{ providers.filter(item => item.hasKey && visionModels(item).length).length }}</b><small>用于脚本、镜头规划与素材理解</small></div>
      <div class="ai-summary-card"><span>媒体执行</span><b>{{ providers.filter(item => item.hasKey && executableMediaModelCount(item) > 0).length }}</b><small>按已声明模型与已注册协议计算</small></div>
      <div class="ai-summary-card"><span>待处理</span><b>{{ providers.filter(item => item.discoveryStatus === 'failed' || !item.hasKey).length }}</b><small>识别失败或尚未配置密钥</small></div>
    </div>
    <div class="card">
      <div class="card-title provider-section-title">
        <div class="provider-section-heading">
          <b>人工智能服务商</b>
          <span class="hint">按供应商真实协议接入；兼容协议不代表所有媒体接口都通用</span>
        </div>
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
          <div class="provider-model-main"><span class="muted">文本对话模型</span><b>{{ row.defaultModel || '未选择' }}</b></div>
          <div class="provider-capabilities">
            <el-tag v-for="cap in providerCapabilities(row)" :key="cap.label" size="small" :type="cap.type" effect="plain">{{ cap.label }} {{ cap.count }}</el-tag>
            <span v-if="!providerCapabilities(row).length" class="muted">尚未确认能力</span>
          </div>
          <div class="provider-card-url muted" :title="row.baseUrl">{{ row.baseUrl }}</div>
          <div class="provider-card-actions">
            <el-button link type="success" size="small" :loading="testingId === row.id" @click="doTest(row)">测试</el-button>
            <el-button link type="warning" size="small" :loading="discoveringId === row.id" @click="openAndDiscover(row)">AI 识别</el-button>
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
      <div class="card-title route-card-title">
        <div>
          用途路由
          <span class="hint">不同环节可以用不同模型：钩子用强的，标签用便宜的</span>
        </div>
        <span style="flex:1"></span>
        <span class="route-actions-label">操作</span>
        <el-button size="small" plain type="primary" :loading="routeMatching" @click="smartRouteMatch">AI 智能识别</el-button>
        <el-button size="small" type="primary" :loading="routeSaving" :disabled="!routesDirty || routeMatching" @click="saveAllRoutes">一键保存</el-button>
        <el-button size="small" plain :disabled="!routesDirty || routeSaving || routeMatching" @click="clearRouteDrafts">一键清空</el-button>
        <el-button size="small" @click="loadRoutes">刷新</el-button>
      </div>
      <div class="route-guide">
        <button class="route-guide-toggle" type="button" @click="routeGuideOpen = !routeGuideOpen">
          <span><b>为什么要设置用途路由？</b><small>让不同任务使用合适的模型，调用更顺畅，也能减少不必要的费用</small></span>
          <span class="route-guide-state">{{ routeGuideOpen ? '收起' : '说明' }} <span aria-hidden="true">{{ routeGuideOpen ? '−' : '+' }}</span></span>
        </button>
        <div v-if="routeGuideOpen" class="route-guide-body">
          <p>猫作按实际任务拆分用途标签，而不是把所有请求都交给同一个模型。不同标签可以绑定不同模型，让文本、视觉、媒体生成、对话和编程调用各自走合适的能力。</p>
          <div class="route-guide-grid">
            <div><b>按任务分流</b><span>脚本、视觉理解、对话、编程和媒体生成分别选择更匹配的模型。</span></div>
            <div><b>按能力匹配</b><span>AI 识别会参考已发现的模型能力，避免把视觉或媒体任务交给只支持文本的模型。</span></div>
            <div><b>按成本回退</b><span>每个用途都可以保留备用链，主模型失败时自动顺延，减少重复配置。</span></div>
          </div>
          <p class="route-guide-usage">使用方法：先完成 Provider 的 AI 识别，再点击“AI 智能识别”生成待保存结果；确认后点击“一键保存”，不满意则点击“一键清空”恢复到上次已保存状态，也可以逐行调整。</p>
        </div>
      </div>
      <el-alert v-if="routesError" type="error" :closable="false" show-icon style="margin-bottom:10px"
        title="用途路由加载失败，请点击刷新重试" />
      <div v-else-if="routesLoading" v-loading="routesLoading" style="height:52px"></div>
      <div v-else-if="!routes.length" class="muted" style="margin-bottom:10px">暂无用途路由。</div>
      <div v-if="!routesLoading && !routesError" class="route-groups">
        <section v-for="group in routeGroups" :key="group.key" class="route-group">
          <div class="route-group-heading"><b>{{ group.label }}</b><span>{{ group.description }}</span></div>
          <el-table :data="group.routes" size="small">
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
              <el-option v-for="model in routeModels(row.providerId, row.useCase)" :key="model" :label="model" :value="model" />
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
            <el-button link type="primary" size="small" @click="saveRoute(row)">保存本行</el-button>
          </template>
        </el-table-column>
          </el-table>
        </section>
      </div>
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

    <el-dialog v-model="dlgVisible" class="ai-provider-dialog" :title="form.id ? '编辑供应商' : '新增供应商'" width="min(760px, calc(100vw - 24px))" top="5vh">
      <div class="template-section">
        <button class="template-toggle" type="button" @click="templatesOpen = !templatesOpen">
          <span><b>常用接口模板</b><small>快速填入常见国内外服务和中转协议</small></span>
          <span class="template-toggle-state">{{ templatesOpen ? '收起' : '展开' }} <span aria-hidden="true">{{ templatesOpen ? '−' : '+' }}</span></span>
        </button>
        <div v-if="templatesOpen" class="template-grid">
          <button v-for="template in providerTemplates" :key="template.name" class="template-option" type="button" @click="applyTemplate(template)">
            <b>{{ template.name }}</b><small>{{ template.description }}</small>
          </button>
        </div>
      </div>
      <el-form label-position="top" class="provider-form">
        <div class="provider-basic-grid">
          <el-form-item label="名称">
            <el-input v-model="form.name" placeholder="例如 主力中转站 / DeepSeek 官方" />
          </el-form-item>
          <el-form-item label="协议">
            <el-radio-group v-model="form.kind" @change="onKindChange">
              <el-radio-button value="openai">通用兼容协议</el-radio-button>
              <el-radio-button value="anthropic">Anthropic Messages</el-radio-button>
              <el-radio-button value="gemini">Gemini 原生协议</el-radio-button>
            </el-radio-group>
            <div class="form-hint">仅适用于实现 OpenAI-compatible 请求格式的服务；图片、视频和配音仍按模型路由适配。</div>
          </el-form-item>
          <el-form-item label="服务地址">
            <el-input v-model="form.baseUrl" :placeholder="basePlaceholder" />
            <div class="form-hint">填到服务根地址即可，路径按协议自动补。</div>
          </el-form-item>
          <el-form-item label="服务密钥">
            <el-input v-model="form.apiKey" show-password :placeholder="form.id ? '留空则不修改已保存的密钥' : 'sk-...'" />
          </el-form-item>
        </div>
        <el-alert v-if="credentialNotice" class="credential-notice" type="warning" :closable="false" show-icon :title="credentialNotice" />
        <div v-if="form.discoverySummary" class="discovery-summary" role="status">
          <div class="discovery-summary-head"><span>本次识别已完成</span><small>{{ form.discoverySummary.latencyMs }} ms</small></div>
          <div class="discovery-counts"><span v-for="item in form.discoverySummary.counts" :key="item.label">{{ item.label }} <b>{{ item.count }}</b></span></div>
          <div v-if="form.discoverySummary.textModels.length" class="discovery-text-models">文本：<code v-for="model in form.discoverySummary.textModels.slice(0, 4)" :key="model">{{ model }}</code></div>
          <div v-if="form.discoverySummary.message" class="discovery-message">{{ form.discoverySummary.message }}</div>
        </div>
        <div class="capability-heading">
          <div><b>模型能力</b><span>模型和能力由服务地址返回结果自动匹配，媒体端点统一跟随上面的服务地址。</span></div>
          <el-button type="primary" plain size="small" :disabled="!form.baseUrl || (!form.id && !form.apiKey)" :loading="discoveringId === (form.id || 'draft')" @click="discoverFormModels">AI 识别并匹配</el-button>
        </div>
        <div v-if="!form.id" class="capability-hint">填写服务地址和密钥即可先识别；识别结果点击“保存”后正式生效。</div>
        <div class="capability-editor">
          <div v-for="row in capabilityRows" :key="row.key" :class="['capability-row', { 'capability-row-simple': !row.protocol }]">
            <div class="capability-row-title"><b>{{ row.label }}</b><span>{{ row.note }}</span></div>
            <el-input v-model="form[row.models]" class="capability-models" :placeholder="row.placeholder" :readonly="row.key === 'text'" />
            <span v-if="row.protocol" class="capability-protocol-label">{{ protocolLabel(form[row.protocol]) }}</span>
            <span v-else class="capability-auto-note">AI 识别自动匹配</span>
          </div>
        </div>
        <div class="provider-options-row">
          <el-form-item label="优先级">
            <div class="priority-control">
              <el-input-number v-model="form.priority" :min="1" :max="99" />
              <el-popover trigger="click" placement="top-start" :width="280">
                <template #reference><el-button class="priority-help" circle text size="small" aria-label="查看优先级说明">?</el-button></template>
                <div class="priority-help-content"><b>优先级怎么用</b><p>数字越小越优先。多个 Provider 都支持同一用途时，猫作先尝试数字最小的；失败后再按备用链或全局顺序继续。</p></div>
              </el-popover>
            </div>
          </el-form-item>
          <el-form-item label="状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
        </div>
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
  { name: 'OpenAI 官方', description: '填写 Key 后自动识别模型与能力', kind: 'openai', baseUrl: 'https://api.openai.com' },
  { name: '通用中转 / NewAPI', description: 'OpenAI-compatible 中转网关', kind: 'openai', baseUrl: '' },
  { name: 'DeepSeek', description: '国内文本与推理模型', kind: 'openai', baseUrl: 'https://api.deepseek.com' },
  { name: '智谱 GLM', description: 'GLM 兼容接口', kind: 'openai', baseUrl: 'https://open.bigmodel.cn/api/paas/v4' },
  { name: 'Moonshot Kimi', description: 'Kimi 兼容接口', kind: 'openai', baseUrl: 'https://api.moonshot.cn/v1' },
  { name: '阿里云百炼标准 API', description: '猫作可用：标准 API Key + DashScope 兼容地址', kind: 'openai', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { name: '火山方舟 / 豆包', description: '豆包兼容接口', kind: 'openai', baseUrl: 'https://ark.cn-beijing.volces.com/api/v3' },
  { name: '硅基流动', description: '国内多模型聚合接口', kind: 'openai', baseUrl: 'https://api.siliconflow.cn/v1' },
  { name: 'ModelScope', description: '魔搭社区模型接口', kind: 'openai', baseUrl: 'https://api-inference.modelscope.cn/v1' },
  { name: 'OpenRouter', description: '海外多模型聚合接口', kind: 'openai', baseUrl: 'https://openrouter.ai/api/v1' },
  { name: 'Groq', description: '海外高速推理接口', kind: 'openai', baseUrl: 'https://api.groq.com/openai/v1' },
  { name: 'Together AI', description: '海外开源模型聚合接口', kind: 'openai', baseUrl: 'https://api.together.xyz/v1' },
  { name: 'Claude', description: 'Anthropic Messages 协议', kind: 'anthropic', baseUrl: 'https://api.anthropic.com' },
  { name: 'Gemini', description: 'Google Gemini 原生协议', kind: 'gemini', baseUrl: 'https://generativelanguage.googleapis.com' }
]

const capabilityRows = [
  { key: 'text', label: '文本对话模型', note: '识别后自动匹配，默认使用最强模型', models: 'textModels', placeholder: '识别后自动填入文本对话模型', protocol: '', endpoint: '' },
  { key: 'image', label: '图片生成', note: '识别后自动匹配', models: 'imageModels', protocol: 'imageProtocol', endpoint: '', placeholder: '识别后自动填入图片模型' },
  { key: 'video', label: '视频生成', note: '识别后自动匹配', models: 'videoModels', protocol: 'videoProtocol', endpoint: '', placeholder: '识别后自动填入视频模型' },
  { key: 'voice', label: '配音 / TTS', note: '识别后自动匹配', models: 'voiceModels', protocol: 'voiceProtocol', endpoint: '', placeholder: '识别后自动填入音频模型' },
  { key: 'vision', label: '视觉识别', note: '图片 / 视频理解', models: 'visionModels', placeholder: '识别后自动填入视觉模型', protocol: '', endpoint: '' }
]

const USE_CASE_LABEL = {
  hook: '开场钩子文案',
  script: '脚本创作',
  plan: '分镜与镜头规划',
  product: '产品信息提炼',
  titles: '标题与摘要',
  cta: '行动号召',
  tag: '素材语义标注',
  naming: '素材命名',
  qc: '内容质量检查',
  vision: '视觉内容理解',
  transcription: '音频转写',
  translation: '翻译与本地化',
  rewrite: '文案改写与润色',
  summarize: '内容摘要与结构化',
  chat: 'AI 文本对话',
  research: '信息整理与研究',
  coding: '编程开发',
  capability: '能力调用编排',
  image: '图片生成',
  video: '视频生成',
  voice: '语音生成',
  general: '通用对话'
}
const ROUTE_GROUPS = [
  { key: 'content', label: '内容创作', description: '结构、表达、改写与信息提炼', cases: ['script', 'plan', 'hook', 'product', 'titles', 'cta', 'rewrite', 'summarize'] },
  { key: 'understanding', label: '内容理解', description: '素材、画面、音频与质量分析', cases: ['vision', 'tag', 'naming', 'qc', 'transcription', 'translation'] },
  { key: 'conversation', label: '对话与知识', description: '文本问答、研究和通用协作', cases: ['chat', 'research', 'general'] },
  { key: 'development', label: '编程与能力调用', description: '代码开发、工具编排和受控工作流', cases: ['coding', 'capability'] },
  { key: 'generation', label: '媒体生成', description: '图片、视频与语音生成', cases: ['image', 'video', 'voice'] }
]

const providers = ref([])
const routes = ref([])
const logs = ref([])
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
const templatesOpen = ref(false)
const routeGuideOpen = ref(false)
const routeMatching = ref(false)
const routeSaving = ref(false)
const savedRouteState = ref('')

const routeGroups = computed(() => ROUTE_GROUPS
  .map(group => ({ ...group, routes: routes.value.filter(row => group.cases.includes(row.useCase)) }))
  .filter(group => group.routes.length))
const routesDirty = computed(() => Boolean(routes.value.length) && routeState(routes.value) !== savedRouteState.value)

const form = reactive({
  id: null, name: '', kind: 'openai', baseUrl: '', apiKey: '', defaultModel: '', priority: 10, enabled: true,
  textModels: '', imageModels: '', videoModels: '', voiceModels: '', visionModels: '', imageEndpoint: '', videoEndpoint: '', voiceEndpoint: '', imageProtocol: '', videoProtocol: '', voiceProtocol: '', modelRoutes: {}, discoverySummary: null
})

const basePlaceholder = computed(() => ({
  openai: 'https://api.openai.com  或  https://你的中转站域名',
  anthropic: 'https://api.anthropic.com',
  gemini: 'https://generativelanguage.googleapis.com'
}[form.kind]))
const credentialNotice = computed(() => {
  if (!dlgVisible.value) return ''
  const base = String(form.baseUrl || '').toLowerCase()
  const key = String(form.apiKey || '').trim()
  if (!key) return ''
  if (base.includes('dashscope.aliyuncs.com') && (key.startsWith('sk-sp-') || key.startsWith('o1_'))) {
    return '当前是百炼标准 API 地址，不能搭配 Coding Plan / Token Plan 订阅密钥；请使用该地址对应的标准 API Key，或改用与订阅密钥匹配的服务地址。'
  }
  return ''
})

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
function executableMediaModelCount (provider) {
  const caps = provider?.mediaCapabilities || {}
  return ['image', 'video', 'voice'].reduce((total, key) => {
    const models = Array.isArray(caps[`${key}Models`]) ? caps[`${key}Models`] : []
    return total + models.filter(model => isExecutableProtocol(caps.modelRoutes?.[key]?.[model]?.protocol || caps[`${key}Protocol`])).length
  }, 0)
}
function isExecutableProtocol (protocol) {
  return ['openai_image_generation', 'dashscope_image_http', 'dashscope_image_task_http', 'openai_video_generation', 'dashscope_video_task_http', 'openai_audio_speech', 'dashscope_tts_http', 'dashscope_minimax_tts_http'].includes(protocol)
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
  if (executableMediaModelCount(provider)) return { label: '媒体可执行', tone: 'success' }
  if (mediaModelCount(provider)) return { label: '媒体待适配', tone: 'warning' }
  if (provider.discoveryStatus === 'success') return { label: '模型已发现', tone: 'info' }
  return { label: '已授权', tone: 'info' }
}

function fmt(s) {
  if (!s) return '-'
  return String(s).replace('T', ' ').slice(5, 19)
}

function applyTemplate (template) {
  templatesOpen.value = false
  form.name = template.name
  form.kind = template.kind
  form.baseUrl = template.baseUrl
  form.defaultModel = ''
  form.textModels = ''
  form.imageModels = ''
  form.videoModels = ''
  form.voiceModels = ''
  form.imageProtocol = ''
  form.videoProtocol = ''
  form.voiceProtocol = ''
  form.imageEndpoint = ''
  form.videoEndpoint = ''
  form.voiceEndpoint = ''
  form.modelRoutes = {}
  form.visionModels = ''
  form.discoverySummary = null
  form.apiKey = ''
}

function splitModels (value) {
  return [...new Set(String(value || '').split(/[,，\n]+/).map(item => item.trim()).filter(Boolean))]
}

function cloneRoutes (value) {
  try { return JSON.parse(JSON.stringify(value || {})) } catch { return {} }
}

function capabilityBody () {
  return JSON.stringify({
    image: splitModels(form.imageModels),
    video: splitModels(form.videoModels),
    voice: splitModels(form.voiceModels),
    vision: splitModels(form.visionModels),
    imageEndpoint: form.imageEndpoint.trim(),
    videoEndpoint: form.videoEndpoint.trim(),
    voiceEndpoint: form.voiceEndpoint.trim(),
    imageProtocol: form.imageProtocol,
    videoProtocol: form.videoProtocol,
    voiceProtocol: form.voiceProtocol,
    routes: cloneRoutes(form.modelRoutes)
  })
}

function capabilityText (row, key) {
  return Array.isArray(row?.mediaCapabilities?.[key]) ? row.mediaCapabilities[key].join(',') : ''
}

function protocolLabel (protocol) {
  return ({
    openai_image_generation: '已识别：OpenAI 图片接口',
    dashscope_image_http: '已识别：DashScope 同步图片',
    dashscope_image_task_http: '已识别：DashScope 异步图片',
    dashscope_image_edit_http: '已识别：图片编辑模型（需导入原图）',
    openai_video_generation: '已识别：OpenAI 视频接口',
    openai_audio_speech: '已识别：OpenAI 音频接口',
    dashscope_tts_http: '已识别：DashScope TTS HTTP',
    dashscope_minimax_tts_http: '已识别：DashScope MiniMax TTS',
    dashscope_video_task_http: '已识别：DashScope 异步视频',
    dashscope_tts_websocket: '已识别：DashScope TTS WebSocket'
  }[protocol] || '等待 AI 识别')
}

function onKindChange() {
  form.defaultModel = ''
  form.textModels = ''
  form.discoverySummary = null
  form.imageModels = ''
  form.videoModels = ''
  form.voiceModels = ''
  form.visionModels = ''
  form.imageProtocol = ''
  form.videoProtocol = ''
  form.voiceProtocol = ''
}

async function load() {
  loading.value = true
  try {
    providers.value = await api.providers()
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
    savedRouteState.value = routeState(routes.value)
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

function routeState (rows) {
  return JSON.stringify((rows || []).map(row => ({
    useCase: row.useCase,
    providerId: row.providerId || null,
    model: row.model || null,
    fallbackIds: Array.isArray(row.fallbackIds) ? row.fallbackIds : []
  })))
}

function routeModels (providerId, useCase = '') {
  const provider = providers.value.find((item) => item.id === providerId)
  if (!provider) return []
  const mediaKey = ({ image: 'imageModels', video: 'videoModels', voice: 'voiceModels' })[useCase]
  if (mediaKey) {
    const mediaModels = provider.mediaCapabilities?.[mediaKey]
    return Array.isArray(mediaModels) ? mediaModels.filter(Boolean) : []
  }
  if (useCase === 'vision') return providerVisionModels(provider)
  try {
    const parsed = typeof provider.models === 'string' ? JSON.parse(provider.models || '{}') : provider.models
    const models = Array.isArray(parsed) ? parsed : parsed?.text
    return Array.isArray(models) ? models.filter(Boolean) : []
  } catch {
    return []
  }
}

function providerVisionModels (provider) {
  const models = providerModels(provider)
  return Array.isArray(models?.vision) ? models.vision.filter(Boolean) : []
}

function modelTier (model) {
  const id = String(model || '').toLowerCase()
  if (/(opus|sonnet|pro|max|reason|reasoning|o1|o3|o4|gpt-5|gpt-4|deepseek-r1|qwen-max|glm-4-plus)/.test(id)) return 'strong'
  if (/(mini|flash|haiku|small|lite|turbo|instant|nano|micro|\b[0-9]{1,2}b\b|free)/.test(id)) return 'light'
  return 'middle'
}

function routeIntent (useCase) {
  const key = String(useCase || '').toLowerCase()
  const middle = ['naming', 'translation', 'transcription']
  return {
    tier: middle.includes(key) ? 'middle' : 'strong',
    needsVision: ['plan', 'script', 'tag', 'qc', 'vision'].includes(key)
  }
}

function routeCandidateModels (provider, useCase = '') {
  const models = routeModels(provider.id, useCase)
  const mediaUse = ['image', 'video', 'voice', 'vision'].includes(useCase)
  const fallback = !mediaUse && provider.defaultModel ? [provider.defaultModel] : []
  return [...new Set([...models, ...fallback].filter(Boolean))]
}

function chooseRouteModel (provider, useCase) {
  const candidates = routeCandidateModels(provider, useCase)
  if (!candidates.length) return ''
  const intent = routeIntent(useCase)
  const vision = new Set(providerVisionModels(provider))
  return [...candidates].sort((a, b) => {
    const score = (model) => {
      let value = modelTier(model) === intent.tier ? 30 : modelTier(model) === 'middle' ? 15 : 5
      if (intent.needsVision && vision.has(model)) value += 20
      if (model === provider.defaultModel) value += 3
      return value
    }
    return score(b) - score(a) || modelStrengthScore(b) - modelStrengthScore(a) || String(a).localeCompare(String(b))
  })[0]
}

function routeProviderScore (provider, useCase) {
  if (!provider?.enabled || !provider.hasKey) return -100000
  const model = chooseRouteModel(provider, useCase)
  if (!model) return -100000
  const intent = routeIntent(useCase)
  let score = 100 - Number(provider.priority || 10)
  score += modelTier(model) === intent.tier ? 40 : modelTier(model) === 'middle' ? 20 : 0
  if (intent.needsVision && providerVisionModels(provider).includes(model)) score += 35
  if (provider.discoveryStatus === 'success') score += 10
  return score
}

function autoRouteRow (row) {
  const candidates = providers.value
    .filter(provider => routeProviderScore(provider, row.useCase) > -100000)
    .sort((a, b) => routeProviderScore(b, row.useCase) - routeProviderScore(a, row.useCase) || Number(a.priority || 10) - Number(b.priority || 10))
  const primary = candidates[0]
  if (!primary) return false
  row.providerId = primary.id
  row.model = chooseRouteModel(primary, row.useCase) || null
  row.fallbackIds = candidates.slice(1, 3).map(provider => provider.id)
  return true
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
  ElMessage.closeAll()
  Object.assign(form, {
    id: null, name: '', kind: 'openai', baseUrl: '', apiKey: '',
    defaultModel: '', textModels: '', priority: 10, enabled: true,
    imageModels: '', videoModels: '', voiceModels: '', visionModels: '', imageEndpoint: '', videoEndpoint: '', voiceEndpoint: '', imageProtocol: '', videoProtocol: '', voiceProtocol: '', modelRoutes: {}, discoverySummary: null
  })
  templatesOpen.value = false
  dlgVisible.value = true
}

function openEdit(row) {
  ElMessage.closeAll()
  Object.assign(form, {
    id: row.id, name: row.name, kind: row.kind, baseUrl: row.baseUrl, apiKey: '',
    defaultModel: row.defaultModel, textModels: textModels(row).join(','), priority: row.priority, enabled: row.enabled,
    imageModels: capabilityText(row, 'imageModels'), videoModels: capabilityText(row, 'videoModels'), voiceModels: capabilityText(row, 'voiceModels'), visionModels: capabilityText(row, 'visionModels'),
    imageEndpoint: row.mediaCapabilities?.imageEndpoint || '', videoEndpoint: row.mediaCapabilities?.videoEndpoint || '', voiceEndpoint: row.mediaCapabilities?.voiceEndpoint || '',
    imageProtocol: row.mediaCapabilities?.imageProtocol || '',
    videoProtocol: row.mediaCapabilities?.videoProtocol || '',
    voiceProtocol: row.mediaCapabilities?.voiceProtocol || '',
    modelRoutes: cloneRoutes(row.mediaCapabilities?.modelRoutes),
    discoverySummary: null
  })
  templatesOpen.value = false
  dlgVisible.value = true
}

async function save() {
  if (!form.name) return ElMessage.warning('请填写名称')
  saving.value = true
  try {
    const body = {
      name: form.name, kind: form.kind, baseUrl: form.baseUrl,
      defaultModel: form.defaultModel, mediaCapabilities: capabilityBody(), priority: form.priority, enabled: form.enabled
    }
    if (form.apiKey) body.apiKey = form.apiKey
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
  const text = textModels(row)
  if (!row.defaultModel && !text.length) {
    const image = row.mediaCapabilities?.imageModels?.[0]
    const video = row.mediaCapabilities?.videoModels?.[0]
    const voice = row.mediaCapabilities?.voiceModels?.[0]
    const media = image ? `图片模型 ${image}` : video ? `视频模型 ${video}` : voice ? `配音模型 ${voice}` : '已配置媒体能力'
    return ElMessage.info(`${media}；该 Provider 没有文本模型，文本连通性测试不适用，请在“AI 创作”页测试媒体生成`)
  }
  testingId.value = row.id
  try {
    const r = await api.testProvider({ providerId: row.id, model: row.defaultModel || strongestModel(text) })
    ElMessageBox.alert(r.text || '（空响应）', `${r.provider} · ${r.model} 连通正常`, { type: 'success' })
  } catch {
    /* 拦截器已提示 */
  } finally {
    testingId.value = null
    loadLogs()
  }
}

function discoveryCounts (result) {
  return [
    ['文本', result.textModels || result.models],
    ['图片', result.imageModels],
    ['视频', result.videoModels],
    ['音频', result.voiceModels],
    ['视觉', result.visionModels]
  ].map(([label, values]) => ({ label, count: Array.isArray(values) ? values.length : 0 }))
}

function applyDiscoveryToForm (result) {
  const textModels = strongestFirst(Array.isArray(result.textModels) ? result.textModels : (Array.isArray(result.models) ? result.models : []))
  const imageModels = strongestFirst(Array.isArray(result.imageModels) ? result.imageModels : [])
  const videoModels = strongestFirst(Array.isArray(result.videoModels) ? result.videoModels : [])
  const voiceModels = strongestFirst(Array.isArray(result.voiceModels) ? result.voiceModels : [])
  const visionModels = Array.isArray(result.visionModels) ? result.visionModels : []
  // Recognition is authoritative for the automatic section. Replace stale values instead of
  // appending them, otherwise an old manually-entered text model can masquerade as media support.
  form.textModels = textModels.join(',')
  form.defaultModel = strongestModel(textModels)
  form.imageModels = imageModels.join(',')
  form.videoModels = videoModels.join(',')
  form.voiceModels = voiceModels.join(',')
  form.visionModels = visionModels.join(',')
  form.modelRoutes = cloneRoutes(result.modelRoutes)
  const firstRoute = (operation, models) => form.modelRoutes?.[operation]?.[models[0]] || {}
  const imageRoute = firstRoute('image', imageModels)
  const videoRoute = firstRoute('video', videoModels)
  const voiceRoute = firstRoute('voice', voiceModels)
  form.imageProtocol = imageRoute.protocol || ''
  form.videoProtocol = videoRoute.protocol || ''
  form.voiceProtocol = voiceRoute.protocol || ''
  form.imageEndpoint = imageRoute.endpoint || ''
  form.videoEndpoint = videoRoute.endpoint || ''
  form.voiceEndpoint = voiceRoute.endpoint || ''
  form.discoverySummary = {
    latencyMs: Number(result.latencyMs || 0),
    counts: discoveryCounts(result),
    textModels,
    message: result.message || ''
  }
}

function modelStrengthScore (model) {
  const id = String(model || '').toLowerCase()
  let score = 0
  if (/(gpt-5|o1|o3|o4|opus|reason|reasoning|deepseek-r1|qwen-max|glm-4-plus|(?:^|[-_./])max(?:$|[-_./]))/.test(id)) score += 1000
  else if (/(gpt-4|sonnet|(?:^|[-_./])pro(?:$|[-_./]))/.test(id)) score += 700
  if (/(mini|flash|haiku|small|lite|turbo|instant|nano|micro|free)/.test(id)) score -= 800
  const version = id.match(/qwen([0-9]+)(?:\.([0-9]+))?/)
  if (version) score += Number(version[1]) * 100 + Number(version[2] || 0) * 10
  const size = id.match(/\b([0-9]{1,3})b\b/)
  if (size) score += Math.min(200, Number(size[1]))
  return score
}

function strongestFirst (models) {
  return [...new Set((models || []).filter(Boolean))]
    .sort((a, b) => modelStrengthScore(b) - modelStrengthScore(a) || String(a).localeCompare(String(b)))
}

function strongestModel (models) {
  return strongestFirst(models)[0] || ''
}

async function runDiscovery (providerId, applyToDraft) {
  discoveringId.value = providerId
  try {
    const result = await api.discoverProviderModels(providerId)
    if (applyToDraft) applyDiscoveryToForm(result)
    await load()
    ElMessage.success(applyToDraft ? '已识别并匹配到当前草稿，保存后生效' : '模型识别完成')
  } catch {
    /* 拦截器已提示 */
  } finally {
    discoveringId.value = null
  }
}

async function discoverFormModels () {
  if (!form.baseUrl) return ElMessage.warning('请填写服务地址')
  if (!form.id && !form.apiKey) return ElMessage.warning('请填写服务密钥')
  discoveringId.value = form.id || 'draft'
  try {
    const body = { id: form.id, name: form.name, kind: form.kind, baseUrl: form.baseUrl }
    if (form.apiKey) body.apiKey = form.apiKey
    const result = await api.discoverProviderDraftModels(body)
    applyDiscoveryToForm(result)
    ElMessage.success('已按当前地址和密钥识别，点击保存后生效')
  } catch {
    /* 拦截器已提示 */
  } finally {
    discoveringId.value = null
  }
}

async function openAndDiscover (row) {
  openEdit(row)
  await runDiscovery(row.id, true)
}

async function saveRoute(row, notify = true) {
  await api.saveRoute(row.useCase, {
    providerId: row.providerId || null,
    model: row.model || null,
    fallbacks: JSON.stringify(row.fallbackIds || [])
  })
  if (notify) {
    await loadRoutes()
    ElMessage.success('本行用途已保存')
  }
}

async function saveAllRoutes () {
  if (!routesDirty.value) return ElMessage.info('没有待保存的用途配置')
  routeSaving.value = true
  try {
    const results = await Promise.allSettled(routes.value.map(row => saveRoute(row, false)))
    const failed = results.filter(result => result.status === 'rejected').length
    await loadRoutes()
    if (failed) ElMessage.warning(`已保存 ${results.length - failed} 项，${failed} 项保存失败，请重试`)
    else ElMessage.success(`已一键保存 ${results.length} 项用途配置`)
  } finally {
    routeSaving.value = false
  }
}

function clearRouteDrafts () {
  if (!routesDirty.value) return ElMessage.info('没有待清空的识别结果')
  try {
    routes.value = JSON.parse(savedRouteState.value).map(row => ({
      ...row,
      fallbackIds: Array.isArray(row.fallbackIds) ? row.fallbackIds : []
    }))
    ElMessage.success('已清空未保存结果，恢复到上次已保存状态')
  } catch {
    loadRoutes()
    ElMessage.warning('未保存结果恢复失败，已重新加载用途配置')
  }
}

async function smartRouteMatch () {
  if (!providers.value.some(provider => provider.enabled && provider.hasKey && routeCandidateModels(provider, 'general').length)) {
    return ElMessage.warning('请先完成至少一个 Provider 的 AI 识别')
  }
  routeMatching.value = true
  try {
    const matched = routes.value.filter(autoRouteRow)
    if (!matched.length) return ElMessage.warning('当前没有可匹配的用途路由')
    ElMessage.success(`AI 已完成 ${matched.length} 项用途匹配，请点击“一键保存”确认`)
  } finally {
    routeMatching.value = false
  }
}

onMounted(async () => {
  await Promise.all([load(), loadRoutes(), loadLogs()])
})
</script>

<style>
.ai-summary-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:12px; margin-bottom:14px; }
.ai-summary-card { min-height:94px; padding:14px; border:1px solid var(--el-border-color-lighter); border-radius:6px; background:var(--el-bg-color-overlay); display:flex; flex-direction:column; gap:6px; }
.ai-summary-card span,.ai-summary-card small { color:var(--el-text-color-secondary); font-size:12px; }.ai-summary-card b { font-size:22px; }
.ai-provider-dialog .el-dialog__body { max-height:calc(100vh - 190px); overflow-y:auto; overflow-x:hidden; padding:0 20px 8px; }
.ai-provider-dialog .el-dialog__footer { padding-top:12px; }
.template-section { margin-bottom:14px; border:1px solid var(--el-border-color-lighter); border-radius:6px; background:var(--el-fill-color-light); }
.template-toggle { display:flex; align-items:center; justify-content:space-between; width:100%; min-height:48px; padding:9px 12px; border:0; background:transparent; color:var(--el-text-color-primary); cursor:pointer; text-align:left; }
.template-toggle:hover { background:var(--el-fill-color); }
.template-toggle b,.template-toggle small { display:block; }.template-toggle small { margin-top:3px; color:var(--el-text-color-secondary); font-size:12px; }.template-toggle-state { color:var(--el-color-primary); font-size:12px; white-space:nowrap; }.template-toggle-state span { display:inline-block; width:16px; font-size:17px; text-align:center; }
.template-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:7px; padding:0 10px 10px; border-top:1px solid var(--el-border-color-lighter); }
.template-option { min-width:0; padding:9px 10px; border:1px solid var(--el-border-color-lighter); border-radius:4px; background:var(--el-bg-color-overlay); color:var(--el-text-color-primary); cursor:pointer; text-align:left; }.template-option:hover { border-color:var(--el-color-primary-light-5); color:var(--el-color-primary); }.template-option b,.template-option small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.template-option small { margin-top:3px; color:var(--el-text-color-secondary); font-size:11px; }
.provider-form { min-width:0; }.provider-basic-grid { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); column-gap:14px; }.provider-form .el-form-item { min-width:0; margin-bottom:12px; }.provider-form .el-form-item__label { height:auto; padding:0 0 5px; line-height:1.2; }.provider-form .el-radio-group { display:flex; flex-wrap:wrap; max-width:100%; }.provider-form .el-radio-button__inner { padding:8px 10px; }.form-hint { margin-top:5px; color:var(--el-text-color-secondary); font-size:11px; line-height:1.4; }
 .credential-notice { margin-bottom:12px; }.discovery-summary { min-width:0; margin-bottom:14px; padding:9px 10px; border:1px solid var(--el-color-success-light-7); border-radius:5px; background:var(--el-color-success-light-9); color:var(--el-text-color-regular); font-size:12px; }.discovery-summary-head { display:flex; justify-content:space-between; gap:8px; color:var(--el-color-success); font-weight:600; }.discovery-summary-head small { color:var(--el-text-color-secondary); font-weight:400; }.discovery-counts { display:flex; flex-wrap:wrap; gap:8px; margin-top:6px; }.discovery-counts span { white-space:nowrap; }.discovery-text-models { display:flex; flex-wrap:wrap; align-items:center; gap:4px; margin-top:6px; color:var(--el-text-color-secondary); }.discovery-text-models code { max-width:160px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:var(--el-text-color-regular); }.discovery-message { margin-top:6px; color:var(--el-text-color-secondary); font-size:11px; line-height:1.4; }
.capability-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:3px 0 6px; padding-top:12px; border-top:1px solid var(--el-border-color-lighter); }.capability-heading > div { min-width:0; }.capability-heading b,.capability-heading span { display:block; }.capability-heading span { margin-top:3px; color:var(--el-text-color-secondary); font-size:11px; }.capability-hint { margin:0 0 9px; color:var(--el-text-color-secondary); font-size:12px; }
 .capability-editor { display:flex; flex-direction:column; gap:7px; margin-bottom:12px; }.capability-row { display:grid; grid-template-columns:118px minmax(190px,1.25fr) minmax(180px,.9fr); align-items:center; gap:7px; min-width:0; }.capability-row-simple { grid-template-columns:118px minmax(0,1fr) auto; }.capability-row-title { display:flex; flex-direction:column; gap:2px; min-width:0; }.capability-row-title span { color:var(--el-text-color-secondary); font-size:11px; }.capability-row :deep(.el-input) { width:100%; min-width:0; }.capability-models :deep(input) { font-family:Consolas,Monaco,monospace; font-size:12px; }.capability-protocol-label { min-width:0; overflow:hidden; color:var(--el-color-success); font-size:11px; text-overflow:ellipsis; white-space:nowrap; }.capability-auto-note { color:var(--el-text-color-secondary); font-size:11px; white-space:nowrap; }
 .provider-options-row { display:flex; align-items:flex-end; gap:28px; }.provider-options-row .el-form-item { margin-bottom:2px; }.provider-options-row .el-form-item__content { min-height:32px; }.priority-control { display:flex; align-items:center; gap:5px; }.priority-help { width:24px; height:24px; min-height:24px; padding:0; color:var(--el-color-primary); font-weight:700; }.priority-help-content b { display:block; margin-bottom:5px; }.priority-help-content p { margin:0; color:var(--el-text-color-regular); font-size:12px; line-height:1.6; }
.provider-loading { height:96px; }.provider-empty { padding:16px 0; }.provider-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(270px,1fr)); gap:12px; }.provider-card { display:flex; flex-direction:column; gap:10px; min-width:0; padding:14px; border:1px solid var(--el-border-color-lighter); border-radius:6px; background:var(--el-bg-color-overlay); }.provider-card-head,.provider-card-meta,.provider-card-actions { display:flex; align-items:center; justify-content:space-between; gap:10px; }.provider-card h3 { margin:0 0 4px; font-size:15px; }.provider-card-meta code,.provider-card-url { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:12px; }.provider-status { flex:0 0 auto; padding:3px 7px; border-radius:4px; background:var(--el-fill-color-light); font-size:12px; }.provider-status.success { color:var(--el-color-success); background:var(--el-color-success-light-9); }.provider-status.warning { color:var(--el-color-warning); background:var(--el-color-warning-light-9); }.provider-status.info { color:var(--el-color-primary); background:var(--el-color-primary-light-9); }.provider-status.muted { color:var(--el-text-color-secondary); }.provider-model-main { display:flex; flex-direction:column; gap:3px; }.provider-capabilities { display:flex; flex-wrap:wrap; gap:6px; min-height:24px; }.provider-card-actions { justify-content:flex-start; }.advanced-ai-sections { margin-top:14px; border:1px solid var(--el-border-color-lighter); border-radius:6px; }.nested-ai-card { margin:10px 0; border:0; box-shadow:none; }.ai-route-popper { z-index:3001 !important; max-width:calc(100vw - 24px); }.ai-route-popper .el-select-dropdown__item { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.provider-section-title { align-items:flex-start; }.provider-section-heading { display:flex; align-items:baseline; flex-wrap:wrap; gap:8px; min-width:0; }.provider-section-heading b { white-space:nowrap; }.route-card-title { align-items:flex-start; }.route-card-title > div { min-width:0; }.route-card-title .hint { display:inline; }.route-actions-label { align-self:center; color:var(--el-text-color-secondary); font-size:11px; white-space:nowrap; }.route-guide { margin:0 0 12px; border:1px solid var(--el-border-color-lighter); border-radius:5px; background:var(--el-fill-color-light); }.route-guide-toggle { display:flex; align-items:center; justify-content:space-between; width:100%; min-height:45px; padding:8px 10px; border:0; background:transparent; color:var(--el-text-color-primary); cursor:pointer; text-align:left; }.route-guide-toggle:hover { background:var(--el-fill-color); }.route-guide-toggle b,.route-guide-toggle small { display:block; }.route-guide-toggle small { margin-top:3px; color:var(--el-text-color-secondary); font-size:12px; }.route-guide-state { flex:0 0 auto; color:var(--el-color-primary); font-size:12px; white-space:nowrap; }.route-guide-state span { display:inline-block; width:15px; font-size:16px; text-align:center; }.route-guide-body { padding:0 12px 11px; border-top:1px solid var(--el-border-color-lighter); color:var(--el-text-color-secondary); font-size:12px; line-height:1.55; }.route-guide-body p { margin:9px 0; }.route-guide-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:8px; }.route-guide-grid div { min-width:0; padding:8px 9px; border:1px solid var(--el-border-color-lighter); border-radius:4px; background:var(--el-bg-color-overlay); }.route-guide-grid b,.route-guide-grid span { display:block; }.route-guide-grid b { color:var(--el-text-color-primary); }.route-guide-grid span { margin-top:3px; }.route-guide-usage { padding-top:8px; border-top:1px solid var(--el-border-color-lighter); }.route-groups { display:flex; flex-direction:column; gap:14px; }.route-group { min-width:0; }.route-group-heading { display:flex; align-items:baseline; gap:9px; margin:12px 0 6px; padding-left:2px; }.route-group-heading b { color:var(--el-text-color-primary); }.route-group-heading span { color:var(--el-text-color-secondary); font-size:12px; }
@media (max-width:900px) { .ai-summary-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media (max-width:680px) { .provider-basic-grid { grid-template-columns:1fr; }.discovery-summary { margin-bottom:12px; }.capability-row,.capability-row-simple { grid-template-columns:1fr; gap:5px; padding:9px 0; border-bottom:1px solid var(--el-border-color-lighter); }.capability-row:last-child { border-bottom:0; }.capability-auto-note { white-space:normal; }.capability-heading { align-items:flex-start; flex-direction:column; }.provider-options-row { gap:18px; } }
@media (max-width:560px) { .ai-summary-grid { grid-template-columns:1fr; }.template-grid { grid-template-columns:1fr; }.ai-provider-dialog .el-dialog__body { padding-left:14px; padding-right:14px; }.provider-form .el-radio-button__inner { padding-left:7px; padding-right:7px; }.provider-section-title { align-items:flex-start; flex-wrap:wrap; }.provider-section-heading { flex:1 1 100%; align-items:flex-start; flex-direction:column; gap:3px; }.provider-section-heading .hint { display:block; }.provider-section-title > .el-button { margin-left:0; }.route-card-title { align-items:flex-start; flex-wrap:wrap; }.route-card-title .hint { display:block; margin-top:3px; }.route-card-title > .el-button { margin-left:0; }.route-actions-label { margin-left:0; }.route-guide-grid { grid-template-columns:1fr; }.route-group-heading { align-items:flex-start; flex-direction:column; gap:3px; } }
</style>
