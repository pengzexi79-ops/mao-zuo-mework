<template>
  <div class="page">
    <el-tabs v-model="activeView" class="system-tabs">
      <el-tab-pane label="能力中心" name="capabilities" />
      <el-tab-pane label="环境中心" name="environment" />
      <el-tab-pane label="历史更新" name="history" />
    </el-tabs>
    <section v-if="activeView === 'environment'" class="environment-view">
      <div class="system-view-head"><div><h2>环境中心</h2><p>集中检查本机运行时、凭据保护和需要修复的系统条件。</p></div><div class="environment-head-actions"><el-button :loading="environmentLoading" @click="loadEnvironment(true)">重新检测</el-button><el-button type="primary" plain :loading="connectivityLoading" @click="loadConnectivity">检测联网能力</el-button></div></div>
      <el-alert v-if="environmentError" type="error" :closable="false" show-icon :title="environmentError" />
      <template v-else-if="environment">
        <div v-if="connectivity.length" class="connectivity-strip"><div class="connectivity-strip-head"><b>联网增强</b><span class="muted">仅在点击“检测联网能力”后更新，不影响离线核心</span></div><div class="connectivity-targets"><div v-for="item in connectivity" :key="item.target" class="connectivity-target"><span>{{ item.target }}</span><el-tag size="small" :type="connectivityType(item)">{{ connectivityLabel(item) }}</el-tag><small v-if="item.httpStatus">HTTP {{ item.httpStatus }}</small><small v-else-if="item.errorCode">{{ item.errorCode }}</small></div></div></div>
        <div class="environment-summary">
          <div class="environment-tile"><span>后端与数据库</span><b>{{ environment.databaseConnected ? '已连接' : '需处理' }}</b></div>
          <div class="environment-tile"><span>渲染运行时</span><b>{{ environment.ffmpeg && environment.ffprobe ? 'FFmpeg 已就绪' : '需配置' }}</b></div>
          <div class="environment-tile"><span>凭据保护</span><b>{{ environment.credentialProtection === 'ready' ? '已就绪' : 'APP_MASTER_KEY 缺失' }}</b></div>
          <div class="environment-tile"><span>运行平台</span><b>{{ environment.os || '-' }} · {{ environment.javaVersion || '-' }}</b></div>
        </div>
        <div class="environment-detail-grid">
          <div class="card"><div class="card-title">核心运行时</div><el-descriptions :column="1" border size="small"><el-descriptions-item label="后端"><el-tag :type="environment.databaseConnected ? 'success' : 'danger'">{{ environment.databaseConnected ? '已连接' : '数据库未连接' }}</el-tag></el-descriptions-item><el-descriptions-item label="FFmpeg / FFprobe"><el-tag :type="environment.ffmpeg && environment.ffprobe ? 'success' : 'danger'">{{ environment.ffmpeg && environment.ffprobe ? '已就绪' : '需要配置' }}</el-tag></el-descriptions-item><el-descriptions-item label="凭据保护"><el-tag :type="environment.credentialProtection === 'ready' ? 'success' : 'warning'">{{ environment.credentialProtection === 'ready' ? 'APP_MASTER_KEY 已配置' : '未配置，不能保存新密钥' }}</el-tag></el-descriptions-item></el-descriptions></div>
          <div class="card"><div class="card-title">媒体与诊断能力 <span class="hint">状态来自实际探测；网络能力不会伪装成离线可用</span></div><div class="environment-tool-list"><div v-for="tool in environmentTools" :key="tool.label" class="environment-tool"><div class="environment-tool-head"><b>{{ tool.label }}</b><el-tag size="small" :type="tool.statusType">{{ tool.statusLabel }}</el-tag></div><div class="environment-tool-meta"><el-tag v-if="tool.offlineReady" size="small" type="success" effect="plain">离线可用</el-tag><el-tag v-else-if="tool.needsNetwork" size="small" type="warning" effect="plain">需要网络</el-tag><el-tag v-if="tool.fallback" size="small" type="info" effect="plain">有回退</el-tag></div></div></div></div>
        </div>
        <div class="card environment-guide"><div class="card-title">配置指导 <span class="hint">展开每行查看安装、配置、验证和运行条件</span></div><el-table :data="environment.environmentGuide || []" size="small" row-key="name"><el-table-column type="expand"><template #default="{ row }"><div class="guide-detail"><div class="guide-detail-block"><b>安装方式</b><ol><li v-for="step in row.installSteps || [row.setup]" :key="step">{{ step }}</li></ol></div><div class="guide-detail-block"><b>配置方法</b><ol v-if="(row.configureSteps || []).length"><li v-for="step in row.configureSteps" :key="step">{{ step }}</li></ol><span v-else class="muted">无需额外配置</span></div><div class="guide-detail-block"><b>验证方法</b><ol><li v-for="step in row.verifySteps || []" :key="step">{{ step }}</li></ol></div><div class="guide-detail-meta"><el-tag size="small" :type="row.offlineCapable ? 'success' : 'warning'">{{ row.offlineCapable ? '本地/离线可用' : '需要网络或外部条件' }}</el-tag><el-tag v-if="row.restartRequired" size="small" type="info">需要重启后端</el-tag><span v-if="row.variable">配置项：{{ row.variable }}</span></div></div></template></el-table-column><el-table-column prop="name" label="环境" width="150" /><el-table-column prop="requirement" label="级别" width="90" /><el-table-column prop="purpose" label="用途" min-width="190" /><el-table-column prop="setup" label="处理方式" min-width="280" /><el-table-column label="官方" width="90"><template #default="{ row }"><el-link v-if="row.url" :href="row.url" target="_blank" rel="noopener noreferrer" type="primary">打开</el-link></template></el-table-column></el-table></div>
      </template>
      <el-empty v-else-if="!environmentLoading" description="未读取到环境状态" />
    </section>
    <section v-else-if="activeView === 'history'" class="history-view">
      <div class="system-view-head"><div><h2>历史更新</h2><p>查看本机完整版本记录；同步只合并应用内置历史，不会读取或提交凭据。</p></div><div class="history-actions"><el-button :loading="historyLoading" @click="loadHistory">刷新</el-button><el-button type="primary" plain :loading="historySyncing" @click="syncHistory">同步完整历史</el-button></div></div>
      <el-alert v-if="historyError" type="error" :closable="false" show-icon :title="historyError" />
      <template v-else-if="releaseNotes">
        <div class="history-summary-grid"><div class="environment-tile"><span>当前版本</span><b>{{ releaseNotes.version }}</b><small>{{ releaseNotes.releasedAt }}</small></div><div class="environment-tile"><span>历史记录</span><b>{{ releaseNotes.historyTotal ?? releaseNotes.history?.length ?? 0 }} 条</b><small>{{ releaseNotes.historyHasMore ? '可继续加载' : '完整记录已加载' }}</small></div><div class="environment-tile"><span>当前更新</span><b>{{ releaseNotes.title }}</b><small>{{ releaseNotes.kind || '当前本机构建' }}</small></div></div>
        <article class="history-current"><h3>{{ releaseNotes.version }} · {{ releaseNotes.title }}</h3><p>{{ releaseNotes.summary }}</p><el-collapse><el-collapse-item title="查看本次更新和验证" name="current"><p><b>更新：</b>{{ (releaseNotes.changes || []).join('；') }}</p><p><b>修复：</b>{{ (releaseNotes.fixes || []).join('；') }}</p><p><b>验证：</b>{{ (releaseNotes.verification || []).join('；') }}</p><p class="muted"><b>兼容性：</b>{{ releaseNotes.compatibility }}</p></el-collapse-item></el-collapse></article>
        <el-timeline class="history-timeline"><el-timeline-item v-for="item in releaseNotes.history || []" :key="item.version" :timestamp="`${item.version} · ${item.releasedAt}`" placement="top"><article class="history-entry"><h3>{{ item.title }}</h3><p>{{ item.summary }}</p><el-collapse><el-collapse-item title="更新详情" :name="item.version"><p><b>更新：</b>{{ (item.changes || []).join('；') }}</p><p><b>修复：</b>{{ (item.fixes || []).join('；') }}</p><p><b>验证：</b>{{ (item.verification || []).join('；') }}</p><p class="muted"><b>兼容性：</b>{{ item.compatibility }}</p></el-collapse-item></el-collapse></article></el-timeline-item></el-timeline>
      </template>
      <el-empty v-else-if="!historyLoading" description="未读取到版本历史" />
    </section>
    <template v-else>
    <el-alert v-if="loadError" type="error" :closable="false" show-icon style="margin-bottom:14px"
      title="能力检测失败" :description="loadError">
      <template #default><el-button size="small" type="primary" plain :loading="loading" @click="refresh(true)">重新检测</el-button></template>
    </el-alert>
    <el-alert v-else-if="loading" type="info" :closable="false" show-icon style="margin-bottom:14px" title="正在检测应用能力…" />
    <el-alert v-else-if="readyCount" type="success" :closable="false" show-icon style="margin-bottom:14px"
      :title="`${readyCount} 项能力已安装可用`"
      :description="missingCount ? `${missingCount} 项随安装包预置能力需要处理，已按卡片操作完成修复。` : '当前检测到的随安装包能力均可直接使用。模型、账号授权和硬件能力会在下方单独说明。'" />
    <el-alert v-else type="warning" :closable="false" show-icon style="margin-bottom:14px" title="未检测到可用能力" description="后端没有返回可用能力。请点击右上角刷新状态；若仍为空，请运行安装目录中的“检查运行环境”。" />

    <el-collapse v-model="activeGroups" class="cap-collapse">
      <el-collapse-item v-for="g in operationalGroups" :key="g.name" :name="g.name"
        :title="`${g.name}（${g.ready}/${g.items.length} 已安装可用）`">
        <div class="cap-grid">
          <div v-for="c in g.items" :key="c.key" class="cap-card">
            <div class="cap-head">
              <el-tag size="small" :type="statusType(c)">{{ statusLabel(c) }}</el-tag>
              <code class="muted">{{ c.tool }}</code>
            </div>
            <h4 class="cap-name">{{ c.name }}</h4>
            <p class="cap-guide">{{ c.guide }}</p>
            <p v-if="c.usedBy" class="cap-guide"><b>默认链路：</b>{{ c.usedBy }}</p>
            <div class="cap-state-row">
              <el-tag v-if="c.runtimeReady" size="small" type="success" effect="plain">运行就绪</el-tag>
              <el-tag v-if="c.offlineCapable" size="small" type="success" effect="plain">可离线</el-tag>
              <el-tag v-else-if="c.needsNetwork" size="small" type="warning" effect="plain">需网络</el-tag>
              <el-tag v-if="c.fallback" size="small" type="info" effect="plain">可自动回退</el-tag>
              <el-tag v-if="c.activationRequired" size="small" type="warning" effect="plain">需账号授权</el-tag>
            </div>
            <div class="cap-actions">
              <el-button v-if="c.action === 'install'" size="small" type="primary" plain :loading="installing && installTarget?.key === c.key" @click="openInstall(c)">修复安装</el-button>
              <el-button v-else-if="c.action === 'configure'" size="small" type="primary" plain @click="openSourceKey(c)">{{ c.actionLabel || '配置 API Key' }}</el-button>
              <el-button v-else-if="c.action === 'official' && c.officialUrl" size="small" plain @click="openOfficial(c)">{{ c.actionLabel || '查看安装说明' }}</el-button>
              <el-tag v-else-if="c.status === 'ready'" size="small" type="success" effect="plain">已安装可用</el-tag>
            </div>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>

    <el-collapse v-if="storeItems.length" v-model="activeStore" class="cap-collapse" style="margin-top:14px">
      <el-collapse-item name="store" title="能力商店（模型、授权与硬件扩展）">
        <div class="cap-grid">
          <div v-for="c in storeItems" :key="c.key" class="cap-card">
            <div class="cap-head">
              <el-tag size="small" :type="statusType(c)">{{ statusLabel(c) }}</el-tag>
              <code class="muted">{{ c.tool }}</code>
            </div>
            <h4 class="cap-name">{{ c.name }}</h4>
            <p class="cap-guide">{{ c.guide }}</p>
            <p v-if="c.usedBy" class="cap-guide"><b>默认链路：</b>{{ c.usedBy }}</p>
            <div class="cap-state-row">
              <el-tag v-if="c.runtimeReady" size="small" type="success" effect="plain">运行就绪</el-tag>
              <el-tag v-if="c.offlineCapable" size="small" type="success" effect="plain">可离线</el-tag>
              <el-tag v-else-if="c.needsNetwork" size="small" type="warning" effect="plain">需网络</el-tag>
              <el-tag v-if="c.fallback" size="small" type="info" effect="plain">可自动回退</el-tag>
              <el-tag v-if="c.activationRequired" size="small" type="warning" effect="plain">需账号授权</el-tag>
            </div>
            <div class="cap-actions">
              <el-button v-if="c.action === 'install'" size="small" type="primary" plain :loading="installing && installTarget?.key === c.key" @click="openInstall(c)">修复安装</el-button>
              <el-button v-else-if="c.action === 'configure'" size="small" type="primary" plain @click="openSourceKey(c)">{{ c.actionLabel || '配置 API Key' }}</el-button>
              <el-button v-else-if="c.officialUrl" size="small" plain @click="openOfficial(c)">{{ c.actionLabel || '打开官方说明' }}</el-button>
              <el-tag v-else-if="c.status === 'ready'" size="small" type="success" effect="plain">已安装可用</el-tag>
            </div>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>

    <div class="card" style="margin-top:14px">
      <div class="card-title">
        插件接口
        <span class="hint">登记用户自定义功能入口与 manifest，不执行远程代码</span>
        <span style="flex:1"></span>
        <el-button size="small" @click="loadPlugins">刷新</el-button>
        <el-button size="small" type="primary" @click="openPlugin">新增插件</el-button>
      </div>
      <el-alert v-if="pluginError" type="error" :closable="false" show-icon style="margin-bottom:10px" title="插件列表加载失败，请刷新重试" />
      <el-table v-else :data="plugins" size="small" empty-text="暂无插件注册">
        <el-table-column prop="priority" label="优先级" width="80" />
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column prop="category" label="分类" width="120" />
        <el-table-column label="入口" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <a v-if="row.entryUrl" :href="row.entryUrl" target="_blank" rel="noopener noreferrer">{{ row.entryUrl }}</a>
            <span v-else class="muted">未配置</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '已启用' : '已停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="editPlugin(row)">编辑</el-button>
            <el-popconfirm title="确定删除该插件？" @confirm="deletePlugin(row)"><template #reference><el-button link type="danger" size="small">删除</el-button></template></el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-alert type="info" :closable="false" style="margin-top:14px" title="交付说明">
      基础视频、字幕、配音、抓取和图像能力由安装包预置，不依赖本机 PATH 或全局 Python。模型文件、第三方 API Key、账号授权和硬件驱动需要按官方条件单独完成。
    </el-alert>

    <el-dialog v-model="sourceKeyVisible" title="配置官方素材来源 API Key" width="560px" destroy-on-close>
      <template v-if="sourceKeyTarget">
        <el-alert type="info" :closable="false" show-icon :title="sourceKeyTarget.name"
          description="请先在官方页面完成注册并创建自己的 API Key。密钥仅写入本机服务端 .env，不会回显、不会传到成片任务或前端构建产物。" />
        <div class="cap-actions" style="margin:12px 0">
          <el-button plain @click="openOfficial(sourceKeyTarget)">打开官方注册/文档</el-button>
          <el-tag v-if="sourceKeyStatus?.[sourceConfigId]?.configured" type="success">已配置：{{ sourceKeyStatus[sourceConfigId].masked }}</el-tag>
          <el-tag v-else-if="restartSuggested" type="warning">已保存，重启后生效</el-tag>
          <el-tag v-else type="info">尚未配置</el-tag>
        </div>
        <el-form label-position="top">
          <el-form-item label="API Key">
            <el-input v-model="sourceKeyForm.apiKey" type="password" show-password autocomplete="off" placeholder="粘贴官方控制台生成的 API Key" />
          </el-form-item>
        </el-form>
        <el-alert v-if="sourceKeyResult" :type="sourceKeyResult.connected || sourceKeyResult.saved ? 'success' : 'warning'" :closable="false" show-icon :title="sourceKeyResult.message" style="margin-bottom:12px" />
        <div class="cap-actions">
          <el-button type="primary" :loading="sourceKeySaving" :disabled="!sourceKeyForm.apiKey" @click="saveSourceKey">安全保存</el-button>
          <el-button :loading="sourceKeyTesting" :disabled="restartSuggested || !sourceKeyStatus?.[sourceConfigId]?.configured" @click="testSourceKey">测试当前配置</el-button>
          <el-button v-if="restartSuggested" type="success" plain @click="restartAfterSourceKey">应用配置并重启后端</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="installVisible" title="修复预置能力" width="640px">
      <template v-if="installTarget">
        <el-alert :type="installTarget.status === 'ready' ? 'success' : 'warning'" :closable="false" show-icon
          :title="`${installTarget.name}：${installTarget.status === 'ready' ? '已安装可用' : '需要修复'}`" />
        <p style="margin:12px 0">{{ installTarget.guide }}</p>
        <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px" title="处理范围">
          只会修复安装包中允许的固定组件，不会执行浏览器提供的命令、路径或安装参数。
        </el-alert>
        <div class="cap-actions">
          <el-button v-if="installTarget.action === 'install' && installTarget.status !== 'ready'" type="primary" :loading="installing" @click="doInstall">开始修复安装</el-button>
          <el-button v-if="installTarget.officialUrl" plain @click="openOfficial(installTarget)">打开官方说明</el-button>
        </div>
        <el-alert v-if="installResult" :type="installResult.ok ? 'success' : 'warning'" :closable="false" show-icon style="margin-top:12px" :title="installResult.message" />
        <el-collapse v-if="installResult?.detail" class="install-diagnostics">
          <el-collapse-item title="查看技术诊断（已截断）" name="detail"><pre>{{ installResult.detail }}</pre></el-collapse-item>
        </el-collapse>
      </template>
    </el-dialog>

    <el-dialog v-model="pluginVisible" :title="pluginForm.id ? '编辑插件' : '新增插件'" width="640px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="标识">
          <el-input v-model="pluginForm.key" placeholder="例如 video-enhancer" :disabled="!!pluginForm.id" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="pluginForm.name" placeholder="例如 视频增强插件" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="pluginForm.category" placeholder="例如 视频 / 音频 / AI / 扩展" />
        </el-form-item>
        <el-form-item label="入口 URL">
          <el-input v-model="pluginForm.entryUrl" placeholder="http://localhost:3000 或 https://example.com" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="pluginForm.description" type="textarea" :rows="4" placeholder="简述插件功能和接入方式" />
        </el-form-item>
        <el-form-item label="manifest JSON">
          <el-input v-model="pluginForm.manifest" type="textarea" :rows="5" placeholder='{"features":["image","video"]}' />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="pluginForm.priority" :min="1" :max="999" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="pluginForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pluginVisible = false">取消</el-button>
        <el-button type="primary" :loading="pluginSaving" @click="savePlugin">保存</el-button>
      </template>
    </el-dialog>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const route = useRoute()
const router = useRouter()
const activeView = ref(['environment', 'history'].includes(route.query.view) ? route.query.view : 'capabilities')
const environment = ref(null)
const releaseNotes = ref(null)
const historyLoading = ref(false)
const historySyncing = ref(false)
const historyError = ref('')
const environmentLoading = ref(false)
const environmentError = ref('')
const connectivity = ref([])
const connectivityLoading = ref(false)
const environmentTools = computed(() => [
  ['FFmpeg', environment.value?.ffmpeg, 'ffmpeg'],
  ['FFprobe', environment.value?.ffprobe, 'ffprobe'],
  ['yt-dlp', environment.value?.['yt-dlp'], 'yt-dlp'],
  ['you-get', environment.value?.['you-get'], 'you-get'],
  ['faster-whisper', environment.value?.fasterWhisper, 'fasterWhisper'],
  ['whisper.cpp', environment.value?.whisperCpp, 'whisperCpp'],
  ['RapidOCR', environment.value?.rapidOcr, 'rapidOcr'],
  ['OpenCV', environment.value?.openCv, 'openCv'],
  ['Edge-TTS', environment.value?.neuralTts, 'neuralTts'],
  ['Demucs', environment.value?.demucs, 'demucs'],
  ['Rembg', environment.value?.rembg, 'rembg'],
  ['Auto-Editor', environment.value?.autoEditor, 'autoEditor'],
  ['ImageMagick', environment.value?.imageMagick, 'imageMagick'],
  ['gallery-dl', environment.value?.galleryDl, 'galleryDl']
].map(([label, raw, key]) => {
  const state = typeof raw === 'object' && raw !== null ? raw : { installed: !!raw, integrated: !!raw, status: raw ? 'ready' : 'missing' }
  return {
    label,
    key,
    ready: state.status === 'ready',
    offlineReady: state.status === 'ready' && !['yt-dlp', 'you-get', 'galleryDl', 'neuralTts', 'demucs'].includes(key),
    needsNetwork: ['yt-dlp', 'you-get', 'galleryDl', 'neuralTts', 'demucs'].includes(key),
    fallback: ['whisperCpp', 'imageMagick', 'autoEditor'].includes(key),
    statusLabel: state.status === 'ready' ? '运行就绪' : state.status === 'detected_only' ? '已检测，待接入' : '未就绪',
    statusType: state.status === 'ready' ? 'success' : state.status === 'detected_only' ? 'warning' : 'info'
  }
}))
const items = ref([])
const plugins = ref([])
const installVisible = ref(false)
const pluginVisible = ref(false)
const installTarget = ref(null)
const pluginTarget = ref(null)
const installResult = ref(null)
const installing = ref(false)
const pluginSaving = ref(false)
const loading = ref(false)
const loadError = ref('')
const pluginError = ref('')
const activeGroups = ref([])
const activeStore = ref(['store'])
const sourceKeyVisible = ref(false)
const sourceKeyTarget = ref(null)
const sourceKeyStatus = ref({})
const sourceKeyForm = ref({ apiKey: '' })
const sourceKeyResult = ref(null)
const sourceKeySaving = ref(false)
const sourceKeyTesting = ref(false)
const restartSuggested = ref(false)
const pluginForm = ref({ id: null, key: '', name: '', category: '', description: '', entryUrl: '', priority: 100, enabled: true, manifest: '{}' })

const sourceConfigId = computed(() => sourceKeyTarget.value?.credential?.configId || '')

const operationalGroups = computed(() => {
  const map = new Map()
  for (const capability of items.value.filter((item) => item.group !== '能力商店')) {
    if (!map.has(capability.group)) map.set(capability.group, { name: capability.group, items: [], ready: 0 })
    const group = map.get(capability.group)
    group.items.push(capability)
    if (capability.status === 'ready') group.ready++
  }
  return [...map.values()]
})
const storeItems = computed(() => items.value.filter((item) => item.group === '能力商店'))
const readyCount = computed(() => items.value.filter((item) => item.status === 'ready').length)
const missingCount = computed(() => items.value.filter((item) => item.status === 'missing').length)

function statusType (capability) {
  if (capability.status === 'ready') return capability.wired === false ? 'warning' : 'success'
  if (capability.status === 'external') return 'info'
  return capability.action === 'install' ? 'warning' : 'danger'
}
function statusLabel (capability) {
  if (capability.status === 'ready') return capability.offlineReady === false && capability.needsNetwork ? '已安装，需网络' : capability.runtimeReady === false ? '已安装，等待运行时' : '已安装可用'
  if (capability.status === 'detected_only') return '已检测，待接入'
  if (capability.status === 'external') return ({ authorization: '需账号授权', hardware: '需硬件/驱动', manual: '需官方安装' })[capability.installMode] || '需外部处理'
  return capability.action === 'install' ? '可修复安装' : '预置缺失'
}
function openInstall (capability) {
  installTarget.value = { ...capability }
  installResult.value = null
  installVisible.value = true
}
function openOfficial (capability) {
  if (!capability?.officialUrl) return
  window.open(capability.officialUrl, '_blank', 'noopener,noreferrer')
}
async function openSourceKey (capability) {
  sourceKeyTarget.value = { ...capability }
  sourceKeyForm.value = { apiKey: '' }
  sourceKeyResult.value = null
  restartSuggested.value = false
  try {
    sourceKeyStatus.value = await api.sourceKeyStatus()
  } catch (error) {
    sourceKeyStatus.value = {}
    ElMessage.error(error.message || '无法读取本机来源配置状态')
  }
  sourceKeyVisible.value = true
}
async function saveSourceKey () {
  if (!sourceConfigId.value || !sourceKeyForm.value.apiKey) return
  sourceKeySaving.value = true
  sourceKeyResult.value = null
  try {
    sourceKeyResult.value = await api.saveSourceKey({
      configId: sourceConfigId.value,
      apiKey: sourceKeyForm.value.apiKey,
      confirm: true
    })
    sourceKeyForm.value.apiKey = ''
    restartSuggested.value = true
    ElMessage.success('来源密钥已保存，重启后生效')
  } catch (error) {
    sourceKeyResult.value = { saved: false, message: error.message || '保存来源密钥失败' }
  } finally {
    sourceKeySaving.value = false
  }
}
async function testSourceKey () {
  if (!sourceConfigId.value) return
  sourceKeyTesting.value = true
  sourceKeyResult.value = null
  try {
    sourceKeyResult.value = await api.testSourceKey(sourceConfigId.value, sourceKeyTarget.value?.credential?.provider)
  } catch (error) {
    sourceKeyResult.value = { connected: false, message: error.message || '测试来源连接失败' }
  } finally {
    sourceKeyTesting.value = false
  }
}
async function restartAfterSourceKey () {
  try {
    await api.restartLocalBackend()
    ElMessage.success('正在重启后端，稍后刷新能力状态并测试来源连接')
    sourceKeyVisible.value = false
  } catch (error) {
    ElMessage.error(error.message || '无法重启后端')
  }
}

function normalizeManifest (text) {
  const value = String(text || '').trim() || '{}'
  try {
    const parsed = JSON.parse(value)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('manifest must be object')
    return JSON.stringify(parsed)
  } catch {
    throw new Error('manifest 必须是 JSON 对象')
  }
}

function openPlugin () {
  pluginTarget.value = null
  pluginForm.value = { id: null, key: '', name: '', category: '', description: '', entryUrl: '', priority: 100, enabled: true, manifest: '{}' }
  pluginVisible.value = true
}

function editPlugin (row) {
  pluginTarget.value = { ...row }
  pluginForm.value = {
    id: row.id,
    key: row.key || '',
    name: row.name || '',
    category: row.category || '',
    description: row.description || '',
    entryUrl: row.entryUrl || '',
    priority: row.priority || 100,
    enabled: !!row.enabled,
    manifest: row.manifestText || '{}'
  }
  pluginVisible.value = true
}

async function loadPlugins () {
  pluginError.value = ''
  try {
    plugins.value = await api.plugins({ silent: true })
  } catch (error) {
    plugins.value = []
    pluginError.value = error.message || '插件列表加载失败'
  }
}

async function savePlugin () {
  if (!pluginForm.value.key || !pluginForm.value.name) return ElMessage.warning('请填写插件标识和名称')
  pluginSaving.value = true
  try {
    const body = {
      key: pluginForm.value.key,
      name: pluginForm.value.name,
      category: pluginForm.value.category,
      description: pluginForm.value.description,
      entryUrl: pluginForm.value.entryUrl,
      priority: pluginForm.value.priority,
      enabled: pluginForm.value.enabled,
      manifest: normalizeManifest(pluginForm.value.manifest)
    }
    if (pluginForm.value.id) await api.updatePlugin(pluginForm.value.id, body)
    else await api.createPlugin(body)
    pluginVisible.value = false
    await loadPlugins()
    ElMessage.success('插件已保存')
  } catch (error) {
    ElMessage.error(error.message || '保存插件失败')
  } finally {
    pluginSaving.value = false
  }
}

async function deletePlugin (row) {
  try {
    await api.deletePlugin(row.id)
    await loadPlugins()
    ElMessage.success('插件已删除')
  } catch (error) {
    ElMessage.error(error.message || '删除插件失败')
  }
}

async function doInstall () {
  if (!installTarget.value || installTarget.value.action !== 'install') return
  installing.value = true
  installResult.value = null
  try {
    const result = await api.installCapability(installTarget.value.key)
    installResult.value = result
    await refresh(true)
    const updated = items.value.find((item) => item.key === installTarget.value.key)
    if (updated) installTarget.value = { ...updated }
    if (result.ok) ElMessage.success(result.message)
  } catch (error) {
    installResult.value = { ok: false, message: `修复安装失败：${error.message}` }
  } finally {
    installing.value = false
  }
}
async function loadHistory () {
  historyLoading.value = true
  historyError.value = ''
  try {
    releaseNotes.value = await api.releaseNotes({ params: { historyLimit: 500 }, silent: true })
  } catch (error) {
    historyError.value = error.message || '无法读取版本历史'
  } finally {
    historyLoading.value = false
  }
}
async function syncHistory () {
  historySyncing.value = true
  historyError.value = ''
  try {
    await api.syncLocalReleaseHistory()
    await loadHistory()
    ElMessage.success('已恢复并同步完整历史记录')
  } catch (error) {
    historyError.value = error.message || '同步版本历史失败'
  } finally {
    historySyncing.value = false
  }
}

async function loadEnvironment (force = false) {
  environmentLoading.value = true
  environmentError.value = ''
  try {
    environment.value = force ? await api.refreshEnv({ silent: true }) : await api.env({ silent: true })
  } catch (error) {
    environmentError.value = error.message || '无法读取本机环境状态'
  } finally {
    environmentLoading.value = false
  }
}

async function loadConnectivity () {
  connectivityLoading.value = true
  try {
    const result = await api.connectivity({ silent: true })
    connectivity.value = Array.isArray(result) ? result : []
    ElMessage.success('联网能力检测完成')
  } catch (error) {
    connectivity.value = []
    ElMessage.error(error.message || '联网能力检测失败')
  } finally {
    connectivityLoading.value = false
  }
}

function connectivityLabel (item) {
  if (!item.configured) return '未配置'
  if (item.rateLimited) return `限流${item.retryAfterSeconds ? ` · ${item.retryAfterSeconds}s 后重试` : ''}`
  if (item.reachable) return '网络可达'
  return item.errorCode || '不可达'
}

function connectivityType (item) {
  if (item.rateLimited) return 'warning'
  if (item.reachable) return 'success'
  if (item.errorCode === 'NOT_CHECKED') return 'info'
  return 'danger'
}

watch(activeView, async (view) => {
  if (view === 'environment') {
    if (route.query.view !== 'environment') await router.replace({ query: { ...route.query, view: 'environment' } })
    if (!environment.value) await loadEnvironment()
  } else if (view === 'history') {
    if (route.query.view !== 'history') await router.replace({ query: { ...route.query, view: 'history' } })
    if (!releaseNotes.value) await loadHistory()
  } else if (route.query.view) {
    const query = { ...route.query }
    delete query.view
    await router.replace({ query })
  }
})
watch(() => route.query.view, async (view) => {
  const next = ['environment', 'history'].includes(view) ? view : 'capabilities'
  if (activeView.value !== next) activeView.value = next
  if (next === 'environment' && !environment.value) await loadEnvironment()
  if (next === 'history' && !releaseNotes.value) await loadHistory()
})

async function refresh (force = false) {
  loading.value = true
  loadError.value = ''
  try {
    const capabilities = await api.capabilities({ params: force ? { refresh: true } : undefined })
    items.value = Array.isArray(capabilities) ? capabilities : []
    activeGroups.value = operationalGroups.value.map((group) => group.name)
  } catch (error) {
    items.value = []
    loadError.value = error.message || '无法连接后端服务，请确认应用已启动后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await Promise.all([
    refresh(true),
    loadPlugins(),
    activeView.value === 'environment' ? loadEnvironment(true) : Promise.resolve(),
    activeView.value === 'history' ? loadHistory() : Promise.resolve()
  ])
})
</script>

<style scoped>
.system-tabs { margin-bottom: 14px; }
.environment-view { display: flex; flex-direction: column; gap: 14px; }
.environment-head-actions { display:flex; gap:8px; flex-wrap:wrap; }.connectivity-strip { padding:12px 14px; border:1px solid var(--el-border-color-lighter); border-radius:6px; background:var(--el-bg-color-overlay); }.connectivity-strip-head { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-bottom:8px; }.connectivity-targets { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:8px; }.connectivity-target { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:3px 6px; align-items:center; padding:7px 9px; border:1px solid var(--el-border-color-lighter); border-radius:5px; }.connectivity-target span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.connectivity-target small { grid-column:1 / -1; color:var(--el-text-color-secondary); font-size:11px; }.system-view-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.system-view-head h2 { margin: 0; font-size: 20px; }
.system-view-head p { margin: 5px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.environment-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.environment-tile { min-height: 92px; padding: 14px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; background: var(--el-bg-color-overlay); display: flex; flex-direction: column; gap: 9px; }
.environment-tile span { color: var(--el-text-color-secondary); font-size: 12px; }
.environment-tile b { font-size: 15px; overflow-wrap: anywhere; }
.environment-detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.environment-tool-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }
.environment-tool-list span { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.environment-tool { padding: 8px 0; border-bottom: 1px solid var(--el-border-color-lighter); }.environment-tool-head { display:flex; align-items:center; justify-content:space-between; gap:8px; }.environment-tool-meta { display:flex; gap:5px; flex-wrap:wrap; margin-top:5px; }.guide-detail { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; padding:8px 16px; background:var(--el-fill-color-lighter); }.guide-detail-block { min-width:0; color:var(--el-text-color-regular); font-size:12px; line-height:1.6; }.guide-detail-block b { color:var(--el-text-color-primary); }.guide-detail-block ol { margin:5px 0 0; padding-left:18px; }.guide-detail-meta { grid-column:1 / -1; display:flex; align-items:center; gap:7px; flex-wrap:wrap; color:var(--el-text-color-secondary); font-size:12px; }.environment-guide { overflow: hidden; }
@media (max-width:900px) { .guide-detail { grid-template-columns:1fr 1fr; } }
@media (max-width:600px) { .guide-detail { grid-template-columns:1fr; } .guide-detail-meta { grid-column:auto; } }
.history-view { display:flex; flex-direction:column; gap:14px; }.history-actions { display:flex; gap:8px; flex-wrap:wrap; }.history-summary-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; }.history-summary-grid small { color:var(--el-text-color-secondary); }.history-current,.history-entry { padding:14px; border:1px solid var(--el-border-color-lighter); border-radius:6px; background:var(--el-bg-color-overlay); }.history-current h3,.history-entry h3 { margin:0 0 7px; font-size:15px; }.history-current p,.history-entry p { margin:5px 0; color:var(--el-text-color-regular); font-size:13px; line-height:1.6; }.history-timeline { padding:8px 12px; }
@media (max-width:600px) { .history-summary-grid { grid-template-columns:1fr; } }
@media (max-width: 900px) { .environment-summary, .environment-detail-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 600px) { .environment-summary, .environment-detail-grid, .environment-tool-list { grid-template-columns: 1fr; } .system-view-head { align-items: flex-start; flex-direction: column; } }
.cap-collapse { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.cap-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
.cap-card { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 12px; }
.cap-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 8px; }
.cap-name { margin: 0 0 6px; font-size: 15px; }
.cap-guide { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
.cap-state-row { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
.cap-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 8px; }
.install-diagnostics { margin-top: 10px; }
.install-diagnostics :deep(pre) { max-height: 180px; margin: 0; overflow: auto; padding: 10px; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--el-text-color-secondary); background: var(--el-fill-color-light); border-radius: 6px; font-size: 12px; line-height: 1.5; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
