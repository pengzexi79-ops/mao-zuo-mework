<template>
  <div class="app-layout">
    <aside class="sidebar" :class="{ 'sidebar-open': menuOpen }">
      <div class="brand">
        <img class="brand-icon" src="/icon-512.png" alt="猫作 · Mework 图标" />
        <span>猫作 · Mework</span>
        <small>本地素材 · FFmpeg · 批量出片</small>
      </div>
      <nav class="nav">
        <router-link v-for="m in menus" :key="m.path" :to="m.path"
                     :class="{ active: route.path === m.path }">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </router-link>
      </nav>
      <div class="foot">
        <button class="health-status" type="button" :disabled="envLoading" title="点击重新检测后端和数据库状态" @click="loadEnv">
          后端：<b :style="{ color: backendColor }">{{ backendStatus }}</b>
        </button>
        <button class="health-status" type="button" title="点击查看 FFmpeg 配置" @click="openFix('ffmpeg')">
          ffmpeg：<b :style="{ color: env.ffmpeg ? '#67c23a' : '#f56c6c' }">{{ env.ffmpeg ? '已就绪' : '未检测到' }}</b>
        </button>
        <button class="health-status" type="button" title="点击配置 AI 接入" @click="router.push('/ai')">
          AI：<b :style="{ color: aiReady ? '#67c23a' : '#e6a23c' }">{{ aiReady ? '已配置' : '未配置' }}</b>
        </button>
        <button class="health-status" type="button" @click="openEnvCenter">环境中心</button>
      </div>
    </aside>

    <div v-if="menuOpen" class="sidebar-overlay" @click="menuOpen = false"></div>

    <div class="main">
      <header class="topbar">
        <el-button class="menu-toggle" :icon="Menu" circle @click="menuOpen = !menuOpen" />
        <h2>{{ route.meta.title || '' }}</h2>
        <div class="spacer"></div>
        <el-button v-if="envLoadError" type="danger" size="small" plain @click="openFix('backend')">后端不可达，查看启动配置</el-button>
        <el-button v-else-if="env.databaseConnected === false" type="danger" size="small" plain @click="openFix('mysql')">数据库未连接，配置认证</el-button>
        <el-button v-else-if="!env.ffmpeg" type="danger" size="small" plain @click="openFix('ffmpeg')">未检测到 ffmpeg，查看配置</el-button>
        <el-button size="small" :icon="Refresh" :loading="envLoading" @click="loadEnv">
          {{ envLoadError ? '重试环境检测' : '刷新状态' }}
        </el-button>
      </header>
      <el-alert v-if="frontendStale" type="warning" :closable="false" show-icon style="margin:0 16px 12px" title="界面资源与后端版本不一致" description="当前浏览器仍在使用旧版界面，请刷新页面（或重启应用）以加载最新版本。" />
      <main class="page">
        <router-view />
      </main>
    </div>
  </div>

  <AiChat />

  <div v-if="globalDropVisible" class="global-drop-mask">
    <div class="global-drop-card">
      <img src="/icon-512.png" alt="猫作导入" />
      <h3>{{ globalDropPackageMode ? `导入素材总包（${globalDropFiles.length} 个文件）` : '导入本地素材到素材库' }}</h3>
      <p class="muted">{{ globalDropPackageMode
        ? '总包会按包名归档；其中的工作流 JSON 会进入工作流模块。'
        : `将扫描 ${globalDropFiles.length} 个文件，生成素材记录、缩略图和媒体属性；选择角色与文件夹后，素材才会进入素材库、Studio 和自动规划。取消只取消本次入库，不会删除或覆盖本地文件。` }}</p>
      <p v-if="!globalDropPackageMode" class="muted">这不是导入成片：MP4 会作为原始素材处理；已生成成片请到成片库使用“扫描输出目录中的成片”补录。</p>
      <el-form v-if="globalDropPackageMode" label-position="top" style="margin-bottom:12px">
        <el-form-item label="总包名称"><el-input v-model="globalDropPackageName" maxlength="80" show-word-limit /></el-form-item>
        <el-alert v-if="globalDropPackageAudit" :type="globalDropPackageAudit.valid ? 'info' : 'warning'" :closable="false" show-icon :title="globalDropPackageAudit.reason || '名称审核中'" />
      </el-form>
      <el-select v-if="!globalDropPackageMode" v-model="globalDropRole" placeholder="自动识别角色" clearable style="width:100%;margin-bottom:10px">
        <el-option label="自动识别" value="none" /><el-option label="实拍主体" value="body" /><el-option label="自家产品" value="product" /><el-option label="钩子" value="hook" /><el-option label="背景音乐" value="bgm" /><el-option label="人声口播" value="voice" />
      </el-select>
      <el-select v-model="globalDropFolderId" :placeholder="globalDropPackageMode ? '不选择则按总包名创建/复用' : '不归档'" clearable style="width:100%;margin-bottom:14px">
        <el-option v-for="folder in globalDropFolders.filter((item) => item.enabled !== false)" :key="folder.id" :label="folder.name" :value="folder.id" />
      </el-select>
      <div><el-button @click="globalDropVisible = false">取消</el-button><el-button type="primary" :loading="globalUploading" @click="uploadDroppedFiles">确认导入</el-button></div>
    </div>
  </div>

  <el-dialog v-model="envDialogVisible" title="环境中心" width="700px" destroy-on-close>
    <el-alert v-if="envLoadError" type="error" :closable="false" show-icon title="后端不可达">
      请确认本地服务已启动，并检查数据库配置后重新检测。
    </el-alert>
      <el-descriptions v-else :column="2" border size="small">
        <el-descriptions-item label="应用版本">{{ env.version || '-' }}</el-descriptions-item>
        <el-descriptions-item label="检测时间">{{ env.checkedAt ? new Date(env.checkedAt).toLocaleString() : '-' }}</el-descriptions-item>
      <el-descriptions-item label="系统">{{ env.os || '-' }} · {{ env.arch || '-' }}</el-descriptions-item>
      <el-descriptions-item label="Java">{{ env.javaVersion || '-' }}</el-descriptions-item>
      <el-descriptions-item label="后端"><el-tag :type="env.databaseConnected === false ? 'danger' : 'success'">{{ env.databaseConnected === false ? '数据库未连接' : '已连接' }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="FFmpeg"><el-tag :type="env.ffmpeg && env.ffprobe ? 'success' : 'danger'">{{ env.ffmpeg && env.ffprobe ? '已就绪' : '需要安装' }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="网页导入"><el-tag :type="env['yt-dlp'] ? 'success' : 'info'">yt-dlp {{ env['yt-dlp'] ? '可用' : '可选' }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="备选导入"><el-tag :type="env['you-get'] ? 'success' : 'info'">you-get {{ env['you-get'] ? '可用' : '可选' }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="自动字幕"><el-tag :type="toolTag(env.whisperCpp)">whisper.cpp {{ toolLabel(env.whisperCpp) }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="音频处理"><el-tag :type="toolTag(env.ffmpegNormalize)">ffmpeg-normalize {{ toolLabel(env.ffmpegNormalize) }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="人声分离"><el-tag :type="toolTag(env.demucs)">demucs {{ toolLabel(env.demucs) }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="图片转码"><el-tag :type="toolTag(env.imageMagick)">ImageMagick {{ toolLabel(env.imageMagick) }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="AI"><el-tag :type="aiReady ? 'success' : 'warning'">{{ aiReady ? '已配置' : '待配置' }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="凭据保护"><el-tag :type="env.credentialProtection === 'ready' ? 'success' : 'warning'">{{ env.credentialProtection === 'ready' ? 'APP_MASTER_KEY 已配置' : '未配置，不能保存新密钥' }}</el-tag></el-descriptions-item>
    </el-descriptions>
    <el-alert type="info" :closable="false" show-icon style="margin-top:14px" title="缺失环境怎么处理">
      FFmpeg 未就绪：安装后将 ffmpeg、ffprobe 加入 PATH，或配置 APP_FFMPEG / APP_FFPROBE 后重启；数据库未连接：检查 DB_URL、DB_USERNAME、DB_PASSWORD。应用不会替你安装系统软件或读取密码。
    </el-alert>
    <el-collapse class="env-sections" :model-value="['guide']">
      <el-collapse-item title="需要配置的环境与安装路径" name="guide">
        <el-table :data="env.environmentGuide || []" size="small" max-height="330">
          <el-table-column prop="name" label="环境" width="128" />
          <el-table-column prop="requirement" label="级别" width="78" />
          <el-table-column prop="purpose" label="用途" width="180" show-overflow-tooltip />
          <el-table-column label="配置方法 / 环境变量" min-width="310">
            <template #default="{ row }"><div>{{ row.setup }}</div><code v-if="row.variable">{{ row.variable }}</code><el-link v-if="row.url" :href="row.url" target="_blank" rel="noopener noreferrer" type="primary" style="margin-left:8px">官方页面</el-link><el-button size="small" link type="primary" style="margin-left:8px" @click="openFix(guideAction(row))">处理</el-button></template>
          </el-table-column>
        </el-table>
      </el-collapse-item>
      <el-collapse-item v-if="false" title="本机版本更新记录" name="release-manage">
        <el-alert type="info" :closable="false" show-icon title="适用于所有电脑">
          正式应用版本来自安装包；本机记录保存在 data 目录，不修改安装包或源码，也不会影响其他电脑。
        </el-alert>
        <div class="release-manage-grid">
          <div><span class="form-hint">正式应用版本</span><b>{{ localReleaseStatus?.formalJarVersion || releaseNotes.version }}</b></div>
          <div><span class="form-hint">本机记录版本</span><b>{{ localReleaseStatus?.currentVersion || releaseNotes.version }}</b></div>
          <div><span class="form-hint">下一本机记录版本</span><b>{{ localReleaseStatus?.nextVersion || '-' }}</b></div>
          <div><span class="form-hint">完整历史</span><b>{{ historyStatusText }}</b></div>
          <div><span class="form-hint">记录位置</span><code>{{ localReleaseStatus?.storagePath || 'data/release-history' }}</code></div>
        </div>
        <el-form label-position="top" class="release-draft-form">
          <el-form-item label="本次更新标题"><el-input v-model="releaseDraft.title" placeholder="例如：素材导入稳定性修复" /></el-form-item>
          <el-form-item label="更新摘要"><el-input v-model="releaseDraft.summary" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="更新内容（每行一项）"><el-input v-model="releaseDraft.changesText" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="纠错修复（每行一项）"><el-input v-model="releaseDraft.fixesText" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="验证结果（每行一项）"><el-input v-model="releaseDraft.verificationText" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="兼容性与迁移"><el-input v-model="releaseDraft.compatibility" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="证据来源（每行一项，不填密码、令牌或 .env）"><el-input v-model="releaseDraft.evidenceText" type="textarea" :rows="2" /></el-form-item>
        </el-form>
        <div class="release-actions">
          <el-button :loading="releaseSyncing" @click="syncLocalReleaseHistory">同步完整历史</el-button>
          <el-button :loading="releaseSaving" @click="saveLocalRelease">保存待记录</el-button>
          <el-button :loading="releaseChecking" @click="checkLocalRelease">校验记录</el-button>
          <el-popconfirm title="确认应用本次记录并生成下一版本？旧版本会归档到历史。" @confirm="applyLocalRelease">
            <template #reference><el-button type="primary" :loading="releaseApplying">应用并归档</el-button></template>
          </el-popconfirm>
          <span v-if="releaseMessage" class="form-hint">{{ releaseMessage }}</span>
        </div>
      </el-collapse-item>
      <el-collapse-item v-if="releaseNotes" title="历史版本更新与纠错记录" name="history">
        <el-alert type="info" :closable="false" show-icon class="history-summary"
          :title="`共 ${releaseNotes.historyTotal ?? releaseNotes.history?.length ?? 0} 条可核对历史记录，按日期和版本从最新到最早排列；当前应用版本为 ${releaseNotes.version}。`">
          Maven/Jar 的构建坐标与应用发布版本分开管理；本页仅展示经发布记录校验的应用版本。记录源：{{ releaseNotes.source || 'release-notes.json' }}；当前记录：{{ releaseNotes.id || '-' }}。
        </el-alert>
        <div class="release-notes current-release">
          <div class="release-heading"><b>当前版本 {{ releaseNotes.version }} · {{ releaseNotes.releasedAt }}</b><el-tag size="small" type="success">{{ releaseNotes.kind || '当前本机构建' }}</el-tag></div>
          <p v-if="releaseNotes.title" class="release-title">{{ releaseNotes.title }}</p>
          <p v-if="releaseNotes.summary" class="release-summary">{{ releaseNotes.summary }}</p>
          <p class="release-section-title">更新内容</p><ul><li v-for="item in releaseNotes.changes" :key="`current-change-${item}`">{{ item }}</li></ul>
          <p class="release-section-title">纠错修复</p><ul><li v-for="item in (releaseNotes.fixes || [])" :key="`current-fix-${item}`">{{ item }}</li></ul>
          <p v-if="releaseNotes.verification?.length" class="release-section-title">验证结果</p><ul v-if="releaseNotes.verification?.length"><li v-for="item in releaseNotes.verification" :key="`current-check-${item}`">{{ item }}</li></ul>
          <p class="form-hint"><b>兼容性：</b>{{ releaseNotes.compatibility }}</p>
          <p v-if="releaseNotes.evidence?.length" class="form-hint"><b>证据：</b>{{ releaseNotes.evidence.join(' · ') }}</p>
        </div>
        <div class="history-load-more">
          <span class="form-hint">已加载 {{ releaseNotes.history?.length || 0 }} / {{ releaseNotes.historyTotal ?? releaseNotes.history?.length ?? 0 }} 条</span>
          <el-button v-if="releaseNotes.historyHasMore" size="small" :loading="releaseHistoryLoading" @click="loadReleaseData({ full: true })">加载全部历史记录</el-button>
        </div>
        <el-timeline class="history-timeline">
          <el-timeline-item v-for="item in (releaseNotes.history || [])" :key="item.version" :timestamp="item.releasedAt" placement="top">
            <div class="history-entry">
              <div class="release-heading"><b>{{ item.version }}</b><el-tag size="small" effect="plain">{{ item.kind || '历史记录' }}</el-tag></div>
              <p class="release-title">{{ item.title }}</p>
              <p class="release-summary">{{ item.summary }}</p>
              <el-collapse class="history-details">
                <el-collapse-item title="更新内容" :name="`${item.version}-changes`"><ul><li v-for="change in (item.changes || [])" :key="`${item.version}-change-${change}`">{{ change }}</li></ul></el-collapse-item>
                <el-collapse-item title="纠错修复" :name="`${item.version}-fixes`"><ul><li v-for="fix in (item.fixes || [])" :key="`${item.version}-fix-${fix}`">{{ fix }}</li></ul></el-collapse-item>
                <el-collapse-item v-if="item.verification?.length" title="验证结果" :name="`${item.version}-verification`"><ul><li v-for="check in item.verification" :key="`${item.version}-check-${check}`">{{ check }}</li></ul></el-collapse-item>
                <el-collapse-item title="兼容性与迁移" :name="`${item.version}-compatibility`"><p class="form-hint">{{ item.compatibility }}</p></el-collapse-item>
                <el-collapse-item v-if="item.evidence?.length" title="证据来源" :name="`${item.version}-evidence`"><ul class="evidence-list"><li v-for="source in item.evidence" :key="`${item.version}-evidence-${source}`"><code>{{ source }}</code></li></ul></el-collapse-item>
              </el-collapse>
            </div>
          </el-timeline-item>
        </el-timeline>
      </el-collapse-item>
    </el-collapse>
    <template #footer>
      <el-button @click="$router.push('/tutorial'); envDialogVisible = false">查看配置教程</el-button>
      <el-button type="primary" :loading="envLoading" @click="loadEnv">重新检测</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="fixDialogVisible" :title="fixTitle" width="620px" destroy-on-close>
    <el-alert v-if="fixAction === 'mysql'" type="warning" :closable="false" show-icon title="MySQL 认证配置">
      密码仅用于本次连接测试或明确保存到项目本机 `.env`；页面不会读取、回显或记录密码。保存后重启后端即可生效；推荐使用项目根目录的 `start.bat`，它会同时检查 Java 与本机能力。
    </el-alert>
    <el-form v-if="fixAction === 'mysql'" :model="mysqlForm" label-width="92px" style="margin-top:16px">
      <el-form-item label="主机"><el-input v-model="mysqlForm.host" placeholder="127.0.0.1" /></el-form-item>
      <el-form-item label="端口"><el-input-number v-model="mysqlForm.port" :min="1" :max="65535" /></el-form-item>
      <el-form-item label="数据库"><el-input v-model="mysqlForm.database" placeholder="例如 ai_mix_video" /></el-form-item>
      <el-form-item label="用户名"><el-input v-model="mysqlForm.username" autocomplete="username" /></el-form-item>
      <el-form-item label="密码"><el-input v-model="mysqlForm.password" type="password" show-password autocomplete="new-password" placeholder="仅用于本次测试或保存" /></el-form-item>
    </el-form>
    <el-alert v-if="mysqlResult" :type="mysqlResult.connected ? 'success' : 'error'" :closable="false" show-icon style="margin-top:12px" :title="mysqlResult.message" />
    <el-alert v-if="restartState" :type="restartState.includes('已连接') ? 'success' : 'info'" :closable="false" show-icon style="margin-top:12px" :title="restartState" />
    <el-alert v-if="fixAction === 'backend'" type="info" :closable="false" show-icon>
      请使用项目根目录的 `start.bat` 启动应用；该脚本会加载项目根目录 `.env`，并检查 Java、数据库变量、FFmpeg 与 FFprobe。IDE、Git Bash 或直接 `java -jar` 不会自动读取 `.env`。
    </el-alert>
    <el-alert v-if="fixAction === 'ffmpeg'" type="info" :closable="false" show-icon>
      安装 FFmpeg 后将 `ffmpeg` 和 `ffprobe` 加入 PATH，或在项目 `.env` 配置 `APP_FFMPEG`、`APP_FFPROBE` 的绝对路径；重启后点击“重新检测”。
    </el-alert>
    <el-alert v-if="fixAction === 'general'" type="info" :closable="false" show-icon>
      按上方环境变量与安装说明完成本机配置。敏感变量请写入项目根目录 `.env`，不要写入页面、日志或提交到代码库。
    </el-alert>
    <template #footer>
      <el-button @click="fixDialogVisible = false">关闭</el-button>
      <el-button v-if="fixAction === 'mysql'" :loading="mysqlTesting" @click="testMysql">测试连接</el-button>
      <el-popconfirm v-if="fixAction === 'mysql' && !mysqlSaved" title="确认将已验证的数据库配置写入本机 .env？密码不会显示或返回。" @confirm="saveMysql">
        <template #reference><el-button type="primary" :loading="mysqlSaving" :disabled="!mysqlResult?.connected">保存到本机 .env</el-button></template>
      </el-popconfirm>
      <el-button v-if="fixAction === 'mysql' && mysqlSaved" type="primary" :loading="restartingBackend" @click="applyMysqlAndRestart">应用配置并重启后端</el-button>
      <el-button v-else type="primary" @click="router.push('/tutorial'); fixDialogVisible = false">查看配置步骤</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, reactive, onMounted, onBeforeUnmount, watch, markRaw } from 'vue'
import AiChat from './components/AiChat.vue'
import { useRoute, useRouter } from 'vue-router'
import { Menu, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  Odometer, FolderOpened, Download, MagicStick, Tools,
  Briefcase, Share, VideoCamera, Film, Reading, List
} from '@element-plus/icons-vue'
import { api, uploadFile, importMaterialPackage, importMaterialPackageArchive } from './api'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
watch(() => route.path, () => { menuOpen.value = false })
const env = ref({})
const releaseNotes = ref(null)
const localReleaseStatus = ref(null)
const releaseMessage = ref('')
const releaseSyncing = ref(false)
const releaseSaving = ref(false)
const releaseChecking = ref(false)
const releaseApplying = ref(false)
const releaseHistoryLoading = ref(false)
const releaseDraft = reactive({ title: '', summary: '', changesText: '', fixesText: '', verificationText: '', compatibility: '', evidenceText: '' })
const aiReady = ref(false)
const envLoading = ref(false)
const envLoadError = ref(false)
const envDialogVisible = ref(false)
const fixDialogVisible = ref(false)
const fixAction = ref('general')
const mysqlTesting = ref(false)
const mysqlSaving = ref(false)
const restartingBackend = ref(false)
const restartState = ref('')
const mysqlSaved = ref(false)
const mysqlResult = ref(null)
const mysqlForm = reactive({ host: '127.0.0.1', port: 3306, database: 'ai_mix_video', username: '', password: '' })
const globalDropVisible = ref(false)
const globalDropFiles = ref([])
const globalDropFolders = ref([])
const globalDropRole = ref('none')
const globalDropFolderId = ref(null)
const globalDropPackageMode = ref(false)
const globalDropPackageName = ref('')
const globalDropPackageAudit = ref(null)
const globalDropRelativePaths = ref([])
const globalUploading = ref(false)
let dragDepth = 0
// 构建时由 Vite 注入（见 vite.config.js 的 define.__APP_VERSION__），与后端 /api/system/env 返回的 version 对比。
const FRONTEND_VERSION = typeof __APP_VERSION__ !== 'undefined' ? String(__APP_VERSION__) : 'dev'
const frontendStale = computed(() => {
  const frontend = (FRONTEND_VERSION || 'dev').trim()
  const backend = String(env.value?.version || '').trim()
  if (!backend || frontend === 'dev' || backend === 'dev') return false
  return frontend !== backend
})
const backendStatus = computed(() => {
  if (envLoading.value) return '检测中'
  if (envLoadError.value) return '不可达'
  if (env.value.databaseConnected === false) return '数据库未连接'
  return '已连接'
})
const historyStatusText = computed(() => {
  const status = localReleaseStatus.value?.historyStatus
  if (!status) return '读取中'
  const state = status.complete ? '完整' : '待同步'
  return `${state} · ${status.count} 条 · ${status.oldestVersion}–${status.newestVersion}`
})
const backendColor = computed(() => {
  if (envLoading.value) return '#e6a23c'
  if (envLoadError.value || env.value.databaseConnected === false) return '#f56c6c'
  return '#67c23a'
})
const fixTitle = computed(() => ({ mysql: '配置 MySQL 认证', backend: '启动本机后端', ffmpeg: '配置 FFmpeg / FFprobe', general: '环境配置向导' })[fixAction.value] || '环境配置向导')

function guideAction (row) {
  const variable = String(row?.variable || '')
  const name = String(row?.name || '')
  if (variable.includes('DB_') || name.includes('MySQL')) return 'mysql'
  if (variable.includes('FFMPEG') || name.includes('FFmpeg')) return 'ffmpeg'
  return 'general'
}
function openFix (action) {
  fixAction.value = action || 'general'
  mysqlResult.value = null
  mysqlSaved.value = false
  restartState.value = ''
  if (action !== 'mysql') mysqlForm.password = ''
  fixDialogVisible.value = true
}
function openEnvCenter () {
  router.push({ path: '/capabilities', query: { view: 'environment' } })
}
async function testMysql () {
  mysqlTesting.value = true
  mysqlResult.value = null
  try { mysqlResult.value = await api.testMysqlConfig({ ...mysqlForm }) } catch (error) { mysqlResult.value = { connected: false, message: error.message || '连接测试失败' } } finally { mysqlTesting.value = false }
}
async function saveMysql () {
  mysqlSaving.value = true
  try {
    const result = await api.saveMysqlConfig({ ...mysqlForm, confirm: true })
    mysqlResult.value = { connected: true, message: result.message }
    mysqlSaved.value = true
    mysqlForm.password = ''
    ElMessage.success('配置已保存，正在准备应用到后端')
  } catch (error) { mysqlResult.value = { connected: false, message: error.message || '保存失败' } } finally { mysqlSaving.value = false }
}
async function applyMysqlAndRestart () {
  restartingBackend.value = true
  restartState.value = '正在停止旧后端并加载本机配置…'
  try {
    await api.restartLocalBackend()
    for (let attempt = 0; attempt < 24; attempt++) {
      await new Promise(resolve => window.setTimeout(resolve, 1500))
      restartState.value = attempt < 5 ? '正在重启后端…' : '正在检测 MySQL 连接…'
      try {
        const current = await api.env({ silent: true })
        if (current.databaseConnected) {
          env.value = current
          envLoadError.value = false
          restartState.value = '数据库已连接，正在刷新页面…'
          window.setTimeout(() => window.location.reload(), 700)
          return
        }
      } catch { /* old process is expected to be unavailable during restart */ }
    }
    restartState.value = '后端已重启，但数据库仍未连接。请重新打开认证向导检查用户名、密码和数据库权限。'
  } catch (error) { restartState.value = error.message || '自动重启没有完成，请重新点击应用配置并重启后端。' } finally { restartingBackend.value = false }
}

function lines (value) {
  return String(value || '').split(/\\r?\\n/).map(item => item.trim()).filter(Boolean)
}
function draftPayload () {
  return {
    title: releaseDraft.title.trim(),
    summary: releaseDraft.summary.trim(),
    changes: lines(releaseDraft.changesText),
    fixes: lines(releaseDraft.fixesText),
    verification: lines(releaseDraft.verificationText),
    compatibility: releaseDraft.compatibility.trim(),
    evidence: lines(releaseDraft.evidenceText)
  }
}
function fillReleaseDraft (draft) {
  const value = draft || {}
  releaseDraft.title = value.title || ''
  releaseDraft.summary = value.summary || ''
  releaseDraft.changesText = (value.changes || []).join('\\n')
  releaseDraft.fixesText = (value.fixes || []).join('\\n')
  releaseDraft.verificationText = (value.verification || []).join('\\n')
  releaseDraft.compatibility = value.compatibility || ''
  releaseDraft.evidenceText = (value.evidence || []).join('\\n')
}
async function loadLocalRelease () {
  try {
    localReleaseStatus.value = await api.localReleaseStatus()
    fillReleaseDraft(localReleaseStatus.value.pending)
  } catch (error) { releaseMessage.value = error.message || '本机版本记录不可用' }
}
async function loadReleaseData ({ full = false } = {}) {
  releaseHistoryLoading.value = true
  try {
    const [notes, status] = await Promise.all([
      api.releaseNotes({ params: { historyLimit: 500 } }),
      api.localReleaseStatus()
    ])
    releaseNotes.value = notes
    localReleaseStatus.value = status
    fillReleaseDraft(status.pending)
  } catch (error) {
    releaseMessage.value = error.message || '版本记录读取失败'
  } finally {
    releaseHistoryLoading.value = false
  }
}
async function syncLocalReleaseHistory () {
  releaseSyncing.value = true
  try {
    const result = await api.syncLocalReleaseHistory()
    localReleaseStatus.value = result
    await loadReleaseData()
    releaseMessage.value = result.changed ? '已合并交付包中的历史版本记录。' : '本机历史已完整，无需补充。'
  } catch (error) { releaseMessage.value = error.message || '同步版本历史失败' } finally { releaseSyncing.value = false }
}
async function saveLocalRelease () {
  releaseSaving.value = true
  try { localReleaseStatus.value = await api.saveLocalReleasePending(draftPayload()); releaseMessage.value = '待记录已保存到本机 data 目录。' } catch (error) { releaseMessage.value = error.message || '保存待记录失败' } finally { releaseSaving.value = false }
}
async function checkLocalRelease () {
  releaseChecking.value = true
  try { await api.checkLocalReleasePending(); releaseMessage.value = `校验通过，将生成 ${localReleaseStatus.value?.nextVersion || '下一版本'}。` } catch (error) { releaseMessage.value = error.message || '校验未通过' } finally { releaseChecking.value = false }
}
async function applyLocalRelease () {
  releaseApplying.value = true
  try {
    const result = await api.applyLocalReleasePending()
    releaseMessage.value = `已生成 ${result.currentVersion}，历史记录已归档。`
    localReleaseStatus.value = result
    await loadReleaseData()
    fillReleaseDraft({})
  } catch (error) { releaseMessage.value = error.message || '应用版本记录失败' } finally { releaseApplying.value = false }
}
function toolLabel (tool) {
  if (!tool || tool.status === 'missing') return '未安装'
  return tool.status === 'ready' ? '已接入' : '仅检测到，未接入管线'
}
function toolTag (tool) {
  if (!tool || tool.status === 'missing') return 'info'
  return tool.status === 'ready' ? 'success' : 'warning'
}

const menus = [
  { path: '/capabilities', title: '能力中心', icon: markRaw(Tools) },
  { path: '/dashboard', title: '概览', icon: markRaw(Odometer) },
  { path: '/materials', title: '素材库', icon: markRaw(FolderOpened) },
  { path: '/resource-center', title: '资源中心', icon: markRaw(List) },
  { path: '/crawl', title: '素材抓取', icon: markRaw(Download) },
  { path: '/ai', title: 'AI 接入', icon: markRaw(MagicStick) },
  { path: '/ai-create', title: 'AI 创作', icon: markRaw(MagicStick) },
  { path: '/projects', title: '项目', icon: markRaw(Briefcase) },
  { path: '/workflows', title: '工作流 / 技能', icon: markRaw(Share) },
  { path: '/fixed-order-presets', title: '产片固定顺序', icon: markRaw(List) },
  { path: '/tutorial', title: '内制教程', icon: markRaw(Reading) },
  { path: '/studio', title: '出片控制台', icon: markRaw(VideoCamera) },
  { path: '/outputs', title: '成片库', icon: markRaw(Film) }
]

const sleep = (ms) => new Promise((resolve) => window.setTimeout(resolve, ms))

async function loadEnv () {
  envLoading.value = true
  let currentEnv = null
  let overview = null
  try {
    // The first few seconds after a local restart can reject one connection while Tomcat is
    // accepting the next. Only mark the backend unreachable after all short probes fail.
    for (let attempt = 0; attempt < 3 && !currentEnv; attempt++) {
      try {
        currentEnv = await api.refreshEnv({ silent: true })
      } catch {
        if (attempt < 2) await sleep(500 * (attempt + 1))
      }
    }
    if (!currentEnv || typeof currentEnv !== 'object') throw new Error('environment probe unavailable')
    env.value = currentEnv
    envLoadError.value = false
    try {
      overview = await api.overview({ silent: true })
    } catch {
      // Overview combines database counts and can lag behind the lightweight environment probe.
    }
    aiReady.value = !!overview?.aiReady
    if ((env.value.databaseConnected === false || !env.value.ffmpeg) && !sessionStorage.getItem('mework-env-notified')) {
      sessionStorage.setItem('mework-env-notified', '1')
      envDialogVisible.value = true
    }
  } catch {
    envLoadError.value = true
    if (!sessionStorage.getItem('mework-env-notified')) {
      sessionStorage.setItem('mework-env-notified', '1')
      envDialogVisible.value = true
    }
  } finally {
    envLoading.value = false
  }
}
const PACK_EXTENSIONS = ['.mixcut-skill.json', '.mixcut-workflow.json']
const MEDIA_EXTENSIONS = new Set(['mp4', 'mov', 'mkv', 'avi', 'webm', 'm4v', 'mp3', 'wav', 'm4a', 'aac', 'flac', 'ogg', 'opus', 'jpg', 'jpeg', 'png', 'webp', 'bmp', 'gif'])
function fileExt (file) {
  const name = String(file?.name || '').toLowerCase()
  const index = name.lastIndexOf('.')
  return index >= 0 ? name.slice(index + 1) : ''
}
function isPackCandidate (file) {
  const name = String(file?.name || '').toLowerCase()
  return PACK_EXTENSIONS.some((suffix) => name.endsWith(suffix)) || name.endsWith('.json')
}
function expectedPackFormat (file, text) {
  const name = String(file?.name || '').toLowerCase()
  if (name.includes('skill')) return 'mixcut-skill'
  if (name.includes('workflow')) return 'mixcut-workflow'
  try {
    const pack = JSON.parse(text || '')
    return pack?.format === 'mixcut-skill' || pack?.format === 'mixcut-workflow' ? pack.format : null
  } catch { return null }
}
function isMediaCandidate (file) { return MEDIA_EXTENSIONS.has(fileExt(file)) }
async function stagePackFiles (files) {
  const staged = []
  for (const file of files) {
    if ((file.size || 0) > 5 * 1024 * 1024) {
      ElMessage.warning(`${file.name} 超过 5MB，已拒绝作为工作流/Skill 包导入`)
      continue
    }
    try {
      const text = await file.text()
      const expectedFormat = expectedPackFormat(file, text)
      if (!expectedFormat) {
        ElMessage.warning(`${file.name} 不是猫作工作流或 Skill 包，已跳过`)
        continue
      }
      staged.push({ name: file.name, expectedFormat, text })
    } catch {
      ElMessage.warning(`${file.name} 读取失败，已跳过`)
    }
  }
  if (!staged.length) return false
  sessionStorage.setItem('mework-pending-pack-files', JSON.stringify(staged))
  await router.push('/workflows')
  window.setTimeout(() => window.dispatchEvent(new Event('mework-consume-pending-pack')), 60)
  ElMessage.success(`已识别 ${staged.length} 个工作流/Skill 包，正在进入安全导入`)
  return true
}
async function droppedFileEntries (event) {
  const items = Array.from(event.dataTransfer?.items || [])
  const roots = items.map((item) => item.webkitGetAsEntry?.()).filter(Boolean)
  if (!roots.length) return Array.from(event.dataTransfer?.files || []).map((file) => ({ file, relativePath: file.webkitRelativePath || file.name }))
  const output = []
  const readAll = (reader) => new Promise((resolve, reject) => {
    const entries = []
    const next = () => reader.readEntries((batch) => {
      if (!batch.length) return resolve(entries)
      entries.push(...batch)
      next()
    }, reject)
    next()
  })
  const visit = async (entry, prefix = '') => {
    const relativePath = `${prefix}${entry.name}`
    if (entry.isFile) {
      const file = await new Promise((resolve, reject) => entry.file(resolve, reject))
      output.push({ file, relativePath })
      return
    }
    if (entry.isDirectory) {
      const children = await readAll(entry.createReader())
      for (const child of children) await visit(child, `${relativePath}/`)
    }
  }
  for (const root of roots) await visit(root)
  return output
}
async function prepareGlobalDrop (event) {
  const entries = await droppedFileEntries(event)
  if (!entries.length) return
  const packEntries = entries.filter(({ file }) => isPackCandidate(file))
  const zipEntries = entries.filter(({ file }) => /\.zip$/i.test(String(file.name || '')))
  const mediaEntries = entries.filter(({ file }) => !isPackCandidate(file) && !/\.zip$/i.test(String(file.name || '')) && isMediaCandidate(file))
  const rejected = entries.length - packEntries.length - zipEntries.length - mediaEntries.length
  if (packEntries.length) await stagePackFiles(packEntries.map(({ file }) => file))
  if (rejected > 0) ElMessage.warning(`${rejected} 个未知文件已跳过；只支持媒体素材、工作流 JSON 和 Skill JSON`)
  if (zipEntries.length) {
    const archive = zipEntries[0].file
    globalDropFiles.value = [archive]
    globalDropRelativePaths.value = []
    globalDropPackageMode.value = true
    globalDropPackageName.value = archive.name.replace(/\.zip$/i, '')
    globalDropPackageAudit.value = null
    try { globalDropPackageAudit.value = await api.auditMaterialPackageName(globalDropPackageName.value) } catch { globalDropPackageAudit.value = { valid: false, reason: '名称审核失败，请修改后重试' } }
    globalDropVisible.value = true
    return
  }
  if (!mediaEntries.length) return
  const mediaFiles = mediaEntries.map(({ file }) => file)
  const relativePaths = mediaEntries.map(({ relativePath, file }) => relativePath || file.webkitRelativePath || file.name)
  const first = String(relativePaths[0]).replaceAll('\\', '/').split('/')
  const packageMode = relativePaths.some((path) => String(path).includes('/'))
  globalDropFiles.value = mediaFiles
  globalDropRelativePaths.value = relativePaths
  globalDropPackageMode.value = packageMode
  globalDropPackageName.value = packageMode ? (first[0] || mediaFiles[0].name) : ''
  globalDropPackageAudit.value = packageMode ? await api.auditMaterialPackageName(globalDropPackageName.value).catch(() => ({ valid: false, reason: '名称审核失败，请修改后重试' })) : null
  globalDropRole.value = 'none'
  globalDropFolderId.value = null
  try { globalDropFolders.value = await api.materialFolders() } catch { globalDropFolders.value = [] }
  globalDropVisible.value = true
}
function onDragEnter (event) { if (event.dataTransfer?.types?.includes('Files')) { event.preventDefault(); dragDepth++ } }
function onDragOver (event) { if (event.dataTransfer?.types?.includes('Files')) event.preventDefault() }
function onDragLeave (event) { dragDepth = Math.max(0, dragDepth - 1) }
function onDrop (event) {
  if (!event.dataTransfer?.types?.includes('Files')) return
  event.preventDefault()
  dragDepth = 0
  prepareGlobalDrop(event)
}
async function importDroppedWorkflowPacks (packs) {
  let imported = 0
  for (const pack of packs || []) {
    try {
      const text = pack.text || pack.content || ''
      const format = pack.expectedFormat || JSON.parse(text).format
      if (format === 'mixcut-workflow') await api.importWorkflow({ pack: text })
      else if (format === 'mixcut-skill') await api.importSkill({ pack: text })
      else continue
      imported++
    } catch (error) {
      ElMessage.warning(`${pack.name || 'JSON 包'} 导入失败：${error.message || '格式校验未通过'}`)
    }
  }
  return imported
}
async function uploadDroppedFiles () {
  if (!globalDropFiles.value.length) return
  globalUploading.value = true
  try {
    if (globalDropPackageMode.value) {
      const packageName = globalDropPackageName.value.trim()
      const audit = await api.auditMaterialPackageName(packageName)
      globalDropPackageAudit.value = audit
      if (!audit?.valid) { ElMessage.warning(`名称不合法：${audit?.reason || '请修改后重试'}`); return }
      const importData = { packageName, role: 'none', folderId: globalDropFolderId.value || undefined }
      const result = /\.zip$/i.test(globalDropFiles.value[0].name)
        ? await importMaterialPackageArchive(globalDropFiles.value[0], importData)
        : await importMaterialPackage(globalDropFiles.value, packageName, globalDropRelativePaths.value, importData)
      const importedWorkflowPacks = await importDroppedWorkflowPacks((result.workflowPacks || []).map((pack) => ({ name: pack.name, text: pack.content })))
      globalDropVisible.value = false
      if (result.folderId) {
        await router.push({ path: '/materials', query: { folderId: String(result.folderId) } })
        window.setTimeout(() => window.dispatchEvent(new Event('mework-global-upload-complete')), 100)
      }
      ElMessage.success(`素材总包导入完成：视频 ${result.videoImported || 0}，音频 ${result.audioImported || 0}，图片 ${result.imageImported || 0}，跳过 ${result.skipped || 0}，失败 ${result.failed || 0}`)
      return
    }
    let done = 0; let failed = 0
    const files = [...globalDropFiles.value]
    let nextIndex = 0
    const worker = async () => {
      while (nextIndex < files.length) {
        const file = files[nextIndex++]
        try { await uploadFile(file, { role: globalDropRole.value || 'none', folderId: globalDropFolderId.value }); done++ } catch { failed++ }
      }
    }
    await Promise.all(Array.from({ length: Math.min(3, files.length) }, worker))
    globalDropVisible.value = false
    await router.push('/materials')
    window.setTimeout(() => window.dispatchEvent(new Event('mework-global-upload-complete')), 100)
    if (failed) window.alert(`已导入 ${done} 个文件，${failed} 个失败；失败条目可在素材库重新选择后重试。`)
  } finally {
    globalUploading.value = false
    globalDropPackageMode.value = false
  }
}
onMounted(() => { loadEnv(); window.addEventListener('dragenter', onDragEnter); window.addEventListener('dragover', onDragOver); window.addEventListener('dragleave', onDragLeave); window.addEventListener('drop', onDrop) })
onBeforeUnmount(() => { window.removeEventListener('dragenter', onDragEnter); window.removeEventListener('dragover', onDragOver); window.removeEventListener('dragleave', onDragLeave); window.removeEventListener('drop', onDrop) })
</script>

<style scoped>
.env-sections { margin-top: 14px; }
.env-sections :deep(.el-collapse-item__header) { font-weight: 600; }
.history-summary { margin-bottom: 14px; }
.release-notes { margin-top: 14px; padding: 12px 14px; border: 1px solid #d9ecff; border-radius: 6px; background: #f5faff; font-size: 13px; line-height: 1.7; }
.release-notes ul, .history-entry ul { margin: 5px 0 10px; padding-left: 20px; }
.release-heading { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.release-title { margin: 7px 0 2px; color:#303133; font-size:14px; font-weight:600; }
.release-summary { margin: 3px 0 9px; color:#606266; }
.release-section-title { margin: 8px 0 2px; font-weight: 600; color: #303133; }
.current-release { margin-bottom: 16px; }
.history-timeline { margin-top: 18px; }
.release-manage-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; margin:14px 0; }
.release-manage-grid > div { display:flex; flex-direction:column; gap:3px; min-width:0; }
.release-manage-grid code { overflow-wrap:anywhere; }
.release-draft-form { margin-top:14px; }
.release-draft-form :deep(.el-form-item) { margin-bottom:12px; }
.release-actions { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
@media (max-width: 680px) { .release-manage-grid { grid-template-columns:1fr; } }
.history-entry { padding: 0 0 12px; }
.history-details { margin-top: 10px; }
.history-details :deep(.el-collapse-item__header) { font-size:13px; height:34px; line-height:34px; }
.history-details :deep(.el-collapse-item__content) { padding-bottom:8px; }
.evidence-list { word-break:break-all; }
code { padding: 1px 4px; border-radius: 3px; background: #f2f3f5; color: #606266; }
</style>
