<template>
  <div>
    <el-alert type="warning" :closable="false" show-icon style="margin-bottom:14px" title="合规提醒">
      <div class="crawl-compliance">仅处理公开、免登录链接；不会读取 Cookie 或浏览器登录态。抓取素材版权归原作者，商用前请确认授权。需要登录态的素材站点默认关闭。</div>
    </el-alert>

    <div class="grid c2">
      <div class="card">
        <div class="card-title">视频抓取 <span class="hint">后台排队执行，可离开页面后再回来查看</span></div>
        <el-input ref="videoInput" v-model="urls" type="textarea" :rows="7" :disabled="submitting" placeholder="一行一个公开链接，例如：https://www.bilibili.com/video/BVxxxx" />
        <div class="crawl-toolbar">
          <span class="muted">入库角色</span>
          <el-select v-model="videoRole" size="small" style="width:130px" :disabled="submitting"><el-option v-for="(label, value) in ROLE_LABEL" :key="value" :label="label" :value="value" /></el-select>
          <el-select v-model="crawlFolderId" clearable size="small" style="width:150px" placeholder="目标文件夹"><el-option v-for="folder in folders" :key="folder.id" :label="folder.name" :value="folder.id" /></el-select>
          <el-button type="primary" :loading="submitting" :disabled="hasActiveJob" @click="doFetch">提交抓取任务</el-button>
        </div>
        <div class="form-hint">未安装 yt-dlp 时只能抓直链。先展开下方“新手抓取步骤”完成安装和验证。</div>
      </div>

      <div class="card">
        <div class="card-title">已接入公开素材源 <span class="hint">按真实调用条件分组；免 Key 不代表免版权</span></div>
        <el-alert v-if="sourcesError" type="error" :closable="false" show-icon title="素材源加载失败，请刷新页面重试" />
        <div v-else class="source-status-strip"><span><b>{{ noKeySources.length }}</b> 个免 Key 可自动补齐</span><span><b>{{ keySources.length }}</b> 个配置 Key 后可检索</span><span><b>{{ manualSearchSources.length }}</b> 个可手动检索导入</span><span><b>{{ manualSources.length }}</b> 个官方页面 / 登录后导入</span></div>
        <el-table :data="noKeySources" v-loading="sourcesLoading" size="small">
          <el-table-column prop="name" label="站点" width="150" />
          <el-table-column label="密钥" width="70"><template #default="{ row }"><el-tag size="small" :type="row.needKey ? 'warning' : 'success'" effect="plain">{{ row.needKey ? '需要' : '免' }}</el-tag></template></el-table-column>
          <el-table-column prop="note" label="说明" show-overflow-tooltip />
          <el-table-column label="操作" width="190"><template #default="{ row }">
            <el-button v-if="row.needKey && row.authUrl" link type="primary" size="small" @click="openAuth(row)">{{ row.mode === 'oauth' ? '官方授权' : '获取 Key' }}</el-button>
            <el-button v-if="row.mode === 'login-disabled'" link type="warning" size="small" @click="confirmLoginSource(row)">确认授权后开启</el-button>
            <el-button v-if="row.mode !== 'oauth' && row.mode !== 'login-disabled'" link type="primary" size="small" @click="selectSource(row.key)">选择检索</el-button>
            <el-button v-if="row.authUrl" link type="info" size="small" @click="openAuth(row)">打开官网</el-button>
          </template></el-table-column>
        </el-table>
        <el-collapse class="source-groups"><el-collapse-item title="配置官方 Key 后可检索" name="key"><el-table :data="keySources" size="small"><el-table-column prop="name" label="站点" width="150" /><el-table-column prop="note" label="说明" show-overflow-tooltip /><el-table-column label="操作" width="220"><template #default="{ row }"><el-button v-if="row.configId" link type="primary" size="small" @click="goToSourceConfig(row)">配置 Key</el-button><el-button v-if="row.authUrl" link type="info" size="small" @click="openAuth(row)">官方文档</el-button><el-button v-if="row.searchReady" link type="success" size="small" @click="selectSource(row.key)">选择检索</el-button></template></el-table-column></el-table></el-collapse-item><el-collapse-item title="已接入手动检索 / 选择后导入" name="manual-search"><el-table :data="manualSearchSources" size="small"><el-table-column prop="name" label="站点" width="150" /><el-table-column prop="note" label="边界说明" show-overflow-tooltip /><el-table-column label="操作" width="180"><template #default="{ row }"><el-button link type="primary" size="small" @click="selectSource(row.key)">选择检索</el-button><el-button v-if="row.authUrl" link type="info" size="small" @click="openAuth(row)">官方页面</el-button></template></el-table-column></el-table></el-collapse-item><el-collapse-item title="官方处理 / 登录后自行导入" name="manual"><el-table :data="manualSources" size="small"><el-table-column prop="name" label="站点" width="150" /><el-table-column prop="note" label="边界说明" show-overflow-tooltip /><el-table-column label="操作" width="210"><template #default="{ row }"><el-button v-if="row.authUrl" link type="primary" size="small" @click="openAuth(row)">打开官方入口</el-button><el-button v-if="row.configId" link type="primary" size="small" @click="goToSourceConfig(row)">配置 Key</el-button><el-button v-if="row.mode === 'login-disabled'" link type="warning" size="small" @click="confirmLoginSource(row)">查看授权边界</el-button></template></el-table-column></el-table></el-collapse-item></el-collapse>
      </div>
    </div>

    <div class="card">
      <div class="card-title">新手抓取步骤 <span class="hint">只处理公开、免登录且你有权使用的链接</span></div>
      <el-steps :active="runtime['yt-dlp'] ? 4 : 1" finish-status="success" simple>
        <el-step title="检查工具" /><el-step title="安装 yt-dlp" /><el-step title="验证版本" /><el-step title="提交公开链接" />
      </el-steps>
      <el-collapse id="crawl-guide" style="margin-top:12px">
        <el-collapse-item title="1. 如何安装 yt-dlp？" name="install"><ol class="guide-list"><li>Windows 推荐在终端运行 <span class="mono">winget install yt-dlp.yt-dlp</span>；已有 Python 可运行 <span class="mono">python -m pip install -U yt-dlp</span>。</li><li>也可以从 <el-link href="https://github.com/yt-dlp/yt-dlp/releases" target="_blank" rel="noopener noreferrer" type="primary">yt-dlp 官方 Releases</el-link> 下载可执行文件，放入系统 PATH 中的目录。</li><li>应用不会替你下载或安装系统软件；安装完成后需要重新检测。</li></ol></el-collapse-item>
        <el-collapse-item title="2. 怎么确认安装成功？" name="verify"><ol class="guide-list"><li>新开终端，执行 <span class="mono">yt-dlp --version</span>。</li><li>看到版本号后，点击顶部“刷新状态”或环境中心“重新检测”。</li><li>仍显示未安装时，在本机配置 <span class="mono">APP_YTDLP_PATH</span> 为 yt-dlp 的绝对路径后重启后端。</li></ol></el-collapse-item>
        <el-collapse-item title="3. 怎样提交链接和处理失败？" name="use"><ol class="guide-list"><li>每行输入一个公开 HTTP/HTTPS 视频链接，选择入库角色，点击提交。</li><li>不要输入 localhost、内网地址、需要登录、DRM 或无授权的链接；系统会拒绝这些地址。</li><li>失败后查看任务卡片“结果/失败原因”，修正链接、安装工具或确认授权后点“重试失败项”。</li></ol></el-collapse-item>
      </el-collapse>
    </div>

    <div v-if="crawlDetail" class="card crawl-job-card">
      <div class="card-title">采集任务 #{{ crawlDetail.job.id }} <el-tag size="small" :type="statusType(crawlDetail.job.status)">{{ statusText(crawlDetail.job.status) }}</el-tag><span class="grow"></span><el-button v-if="hasActiveJob" size="small" type="danger" plain :loading="cancelling" @click="cancelJob">取消任务</el-button><el-button v-else-if="hasFailedTasks" size="small" type="warning" plain :loading="retrying" @click="retryJob">重试失败项</el-button><el-popconfirm v-if="!hasActiveJob" title="仅删除采集任务记录，不删除已入库素材，继续吗？" @confirm="deleteCrawlJob"><template #reference><el-button size="small" type="danger" plain>删除记录</el-button></template></el-popconfirm></div>
      <el-progress :percentage="crawlDetail.job.progress || 0" :status="crawlDetail.job.status === 'failed' ? 'exception' : crawlDetail.job.status === 'done' ? 'success' : undefined" />
      <div class="crawl-job-meta">{{ crawlDetail.job.currentItem || 0 }} / {{ crawlDetail.job.total || 0 }} 条 · {{ crawlDetail.itemsPerMinute || 0 }} 条/分钟 · {{ etaText(crawlDetail.etaSec) }} · {{ crawlDetail.job.summary || '等待开始' }}</div>
      <div v-if="crawlDetail.downloadedCount != null" class="crawl-admission-summary"><span>已下载 <b>{{ crawlDetail.downloadedCount }}</b></span><span class="admission-ready">准入可用 <b>{{ crawlDetail.admittedCount }}</b></span><span class="admission-failed">下载完成但未准入 <b>{{ crawlDetail.admissionFailedCount }}</b></span></div>
      <el-table :data="crawlDetail.tasks || []" size="small" max-height="300"><el-table-column prop="idx" label="#" width="52"><template #default="{ row }">{{ row.idx + 1 }}</template></el-table-column><el-table-column prop="title" label="素材" min-width="220" show-overflow-tooltip><template #default="{ row }"><span v-if="row.guardRejected">{{ row.title || '无效/诊断地址' }}</span><el-link v-else-if="row.url" :href="row.url" target="_blank" rel="noopener noreferrer" type="primary">{{ row.title || row.url }}</el-link><span v-else>{{ row.title || '-' }}</span></template></el-table-column><el-table-column label="下载 / 准入" width="190"><template #default="{ row }"><el-tag v-if="row.guardRejected" size="small" type="info">安全策略已拦截</el-tag><template v-else><el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag><el-tag v-if="row.admissionStatus === 'ready'" size="small" type="success" effect="plain">可用于出片</el-tag><el-tag v-else-if="row.admissionStatus === 'failed'" size="small" type="warning" effect="plain">未通过准入</el-tag></template></template></el-table-column><el-table-column prop="via" label="方式" width="90" /><el-table-column label="结果/失败原因" min-width="300"><template #default="{ row }"><div class="failure-cell"><el-tooltip v-if="row.message" :content="row.message" placement="top"><span class="failure-message">{{ row.message }}</span></el-tooltip><div v-if="row.errorCode" class="failure-diagnostic"><el-tag size="small" effect="plain" :type="row.guardRejected ? 'info' : 'warning'">{{ crawlErrorLabel(row.errorCode) }}</el-tag><span v-if="row.httpStatus" class="muted">HTTP {{ row.httpStatus }}</span><span v-if="row.source" class="muted">来源：{{ row.source }}</span></div><div v-if="row.admissionStatus === 'failed'" class="failure-diagnostic"><span class="muted">{{ row.admissionReason || '素材未通过质量准入' }}</span><el-button v-if="row.materialId && row.fileExists" link type="primary" size="small" @click="reprobe(row)">重新检测</el-button></div><el-button v-if="row.status === 'failed' && !row.guardRejected" link type="warning" size="small" @click="handleFailure(row)">处理此问题</el-button></div></template></el-table-column></el-table>
    </div>

    <div class="card">
      <div class="card-title">内置能力选择 <span class="hint">用户必须知道的入口</span></div>
      <div class="grid c4">
        <el-button plain @click="focusUpload">本地素材库</el-button>
        <el-dropdown trigger="click" @command="selectCapability">
          <el-button type="primary" plain>公开视频 / 音频 <el-icon><ArrowDown /></el-icon></el-button>
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="video">抓取公开视频</el-dropdown-item>
            <el-dropdown-item command="audio">搜索公开音效 / BGM</el-dropdown-item>
            <el-dropdown-item command="license">查看许可证说明</el-dropdown-item>
            <el-dropdown-item command="local">导入已授权本地文件</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
        <el-button plain @click="$router.push('/ai')">AI 文案与对话</el-button>
        <el-button plain @click="$router.push('/studio')">剪辑渲染与字幕</el-button>
      </div>
    </div>

    <div class="card">
      <div class="card-title">关键词公开视频检索 <span class="hint">仅检索可公开下载、许可证可见的素材</span></div>
      <div style="margin-bottom:8px"><span class="muted">预设方案：</span><el-button v-for="p in PRESETS" :key="'v-'+p.label" link type="primary" size="small" @click="applyVideoPreset(p)">{{ p.label }}</el-button><span class="hint">点击即按预设关键词搜索公开视频</span></div>
      <el-form inline><el-form-item label="关联项目"><el-select v-model="vq.projectId" clearable placeholder="不限定项目" style="width:170px"><el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" /></el-select></el-form-item><el-form-item label="来源"><el-select v-model="vq.source" style="width:170px"><el-option label="智能检索（已连接来源）" value="all" /><el-option label="Pixabay（需 Key）" value="pixabay" /><el-option label="Pexels（需 Key）" value="pexels" /><el-option label="Wikimedia Commons" value="wikimedia" /><el-option label="Internet Archive" value="archive" /></el-select></el-form-item><el-form-item label="关键词"><el-input v-model="vq.keyword" style="width:220px" placeholder="例如：护肤 / 食品 / 数码" @keyup.enter="doVideoSearch" /></el-form-item><el-form-item label="条数"><el-input-number v-model="vq.limit" :min="1" :max="40" size="small" /></el-form-item><el-form-item><el-button type="primary" :loading="videoSearching" @click="doVideoSearch">搜索</el-button></el-form-item><el-form-item v-if="pickedVideo.length"><el-select v-model="publicVideoRole" size="small" style="width:120px"><el-option v-for="(label, value) in ROLE_LABEL" :key="value" :label="label" :value="value" /></el-select><el-select v-model="crawlFolderId" clearable size="small" style="width:140px;margin-left:8px" placeholder="目标文件夹"><el-option v-for="folder in folders" :key="folder.id" :label="folder.name" :value="folder.id" /></el-select><el-button type="success" style="margin-left:8px" :loading="videoImporting" :disabled="hasActiveJob" @click="doVideoImport">导入选中 {{ pickedVideo.length }} 条</el-button></el-form-item></el-form>
      <el-alert v-for="(item, index) in videoNoticeRows" :key="`video-notice-${index}`" :title="item.title" type="warning" :closable="false" show-icon style="margin-bottom:8px"><el-button v-if="item.authUrl" link type="primary" size="small" @click="openAuth(item)">打开官方文档</el-button><el-button v-if="item.configKey" link type="primary" size="small" @click="goToEnv">查看配置方法</el-button></el-alert>
      <el-table :data="videoRealRows" v-loading="videoSearching" size="small" max-height="300" @selection-change="(value) => (pickedVideo = value)"><el-table-column type="selection" width="42" /><el-table-column prop="source" label="来源" width="90" /><el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip /><el-table-column prop="hitKeywords" label="命中词" min-width="110" show-overflow-tooltip /><el-table-column prop="relevanceScore" label="相关性" width="76" /><el-table-column prop="tags" label="匹配标签" min-width="120" show-overflow-tooltip /><el-table-column prop="license" label="授权" width="170" show-overflow-tooltip /><el-table-column label="预览" width="90"><template #default="{ row }"><el-link v-if="row.pageUrl" :href="row.pageUrl" target="_blank" rel="noopener noreferrer" type="primary">来源页</el-link></template></el-table-column></el-table>
    </div>

    <div class="card">
      <div class="card-title">音效 / 背景音乐检索 <span class="hint">检索到的公开素材会进入相同的后台导入队列</span></div>
      <div style="margin-bottom:8px"><span class="muted">预设方案：</span><el-button v-for="p in PRESETS" :key="'a-'+p.label" link type="primary" size="small" @click="applyAudioPreset(p)">{{ p.label }}</el-button></div>
      <div style="margin-bottom:8px"><span class="muted">关联项目</span><el-select v-model="aq.projectId" clearable placeholder="不限定项目" size="small" style="width:170px;margin-left:8px"><el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" /></el-select></div><div class="form-hint" style="margin-bottom:8px">快捷关键词：<el-button v-for="word in ['卡点','轻快','科技','舒缓','温馨','测评','开箱','护肤','美妆','食品','保健品','香水','数码']" :key="word" link type="primary" size="small" @click="aq.keyword = word">{{ word }}</el-button></div><el-form inline><el-form-item label="来源"><el-select v-model="aq.source" style="width:170px"><el-option label="智能检索（已连接来源）" value="all" /><el-option label="Mixkit" value="mixkit" /><el-option label="Freesound" value="freesound" /><el-option label="Wikimedia Commons" value="wikimedia" /><el-option label="Internet Archive" value="archive" /><el-option label="Openverse" value="openverse" /><el-option label="淘声网 toSound" value="tosound" /></el-select></el-form-item><el-form-item label="关键词"><el-input ref="audioInput" v-model="aq.keyword" style="width:220px" placeholder="例如：人文 / 科技 / 开箱" @keyup.enter="doSearch" /></el-form-item><el-form-item label="条数"><el-input-number v-model="aq.limit" :min="1" :max="40" size="small" /></el-form-item><el-form-item><el-button type="primary" :loading="searching" @click="doSearch">搜索</el-button></el-form-item><el-form-item v-if="pickedAudio.length"><el-select v-model="audioRole" size="small" style="width:120px"><el-option label="背景音乐" value="bgm" /><el-option label="人声口播" value="voice" /></el-select><el-select v-model="crawlFolderId" clearable size="small" style="width:140px;margin-left:8px" placeholder="目标文件夹"><el-option v-for="folder in folders" :key="folder.id" :label="folder.name" :value="folder.id" /></el-select><el-button type="success" style="margin-left:8px" :loading="importing" :disabled="hasActiveJob" @click="doImport">导入选中 {{ pickedAudio.length }} 条</el-button></el-form-item></el-form>
      <el-alert v-for="(item, index) in noticeRows" :key="`notice-${index}`" :title="item.title" type="warning" :closable="false" show-icon style="margin-bottom:8px">
        <el-button v-if="item.authUrl" link type="primary" size="small" @click="openAuth(item)">打开官方申请页</el-button>
        <el-button v-if="item.configKey" link type="primary" size="small" @click="goToEnv">查看配置方法</el-button>
      </el-alert>
      <el-table :data="realRows" v-loading="searching" size="small" max-height="360" @selection-change="(value) => (pickedAudio = value)"><el-table-column type="selection" width="42" /><el-table-column prop="source" label="来源" width="90" /><el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip /><el-table-column prop="hitKeywords" label="命中词" min-width="110" show-overflow-tooltip /><el-table-column prop="relevanceScore" label="相关性" width="76" /><el-table-column label="时长" width="80"><template #default="{ row }">{{ row.duration ? row.duration.toFixed(1) + 's' : '-' }}</template></el-table-column><el-table-column prop="license" label="授权" width="130" show-overflow-tooltip /><el-table-column label="试听" width="240"><template #default="{ row }"><audio v-if="row.previewUrl || row.downloadUrl" controls preload="none" :src="row.previewUrl || row.downloadUrl" style="height:28px;width:200px" @error="markAudioPreviewError(row)" /><div v-if="audioPreviewErrors[audioPreviewKey(row)]" class="muted" style="color:#e6a23c;font-size:12px">直链试听失败；导入后可在素材库本地试听</div><span v-else-if="!row.previewUrl && !row.downloadUrl" class="muted">暂无可试听地址</span></template></el-table-column></el-table>
     </div>
  </div>
    <div class="card">
      <div class="card-title">图片素材检索 <span class="hint">免 Key 的 Openverse / Wikimedia，或使用已配置的 Pixabay / Pexels Key</span></div>
      <el-form inline>
        <el-form-item label="来源"><el-select v-model="iq.source" style="width:180px"><el-option label="智能检索（已连接来源）" value="all" /><el-option label="Openverse（免 Key）" value="openverse" /><el-option label="Wikimedia Commons" value="wikimedia" /><el-option label="Pixabay（需 Key）" value="pixabay" /><el-option label="Pexels（需 Key）" value="pexels" /><el-option label="Unsplash（需 Access Key）" value="unsplash" /></el-select></el-form-item>
        <el-form-item label="关键词"><el-input ref="imageInput" v-model="iq.keyword" style="width:230px" placeholder="例如：产品背景 / 城市 / 美食" @keyup.enter="doImageSearch" /></el-form-item>
        <el-form-item label="条数"><el-input-number v-model="iq.limit" :min="1" :max="40" size="small" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="imageSearching" @click="doImageSearch">搜索</el-button></el-form-item>
        <el-form-item v-if="pickedImage.length"><el-select v-model="imageRole" size="small" style="width:120px"><el-option v-for="(label, value) in ROLE_LABEL" :key="value" :label="label" :value="value" /></el-select><el-select v-model="crawlFolderId" clearable size="small" style="width:140px;margin-left:8px" placeholder="目标文件夹"><el-option v-for="folder in folders" :key="folder.id" :label="folder.name" :value="folder.id" /></el-select><el-button type="success" style="margin-left:8px" :loading="imageImporting" :disabled="hasActiveJob" @click="doImageImport">导入选中 {{ pickedImage.length }} 张</el-button></el-form-item>
      </el-form>
      <el-alert v-for="(item, index) in imageNoticeRows" :key="`image-notice-${index}`" :title="item.title" type="warning" :closable="false" show-icon style="margin-bottom:8px"><el-button v-if="item.authUrl" link type="primary" size="small" @click="openAuth(item)">打开官方文档</el-button><el-button v-if="item.configKey" link type="primary" size="small" @click="goToSourceConfig({ key: item.source, configId: item.configKey.replace(/^APP_|_API_KEY$/g, '') })">配置 Key</el-button></el-alert>
      <el-table :data="imageRealRows" v-loading="imageSearching" size="small" max-height="360" @selection-change="(value) => (pickedImage = value)">
        <el-table-column type="selection" width="42" /><el-table-column label="预览" width="90"><template #default="{ row }"><el-image v-if="row.previewUrl || row.downloadUrl" :src="row.previewUrl || row.downloadUrl" fit="cover" style="width:64px;height:42px" :preview-src-list="[row.previewUrl || row.downloadUrl]" preview-teleported /><span v-else class="muted">暂无</span></template></el-table-column><el-table-column prop="source" label="来源" width="100" /><el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip /><el-table-column prop="tags" label="标签" min-width="140" show-overflow-tooltip /><el-table-column prop="license" label="授权" width="180" show-overflow-tooltip /><el-table-column label="来源页" width="90"><template #default="{ row }"><el-link v-if="row.pageUrl" :href="row.pageUrl" target="_blank" rel="noopener noreferrer" type="primary">打开</el-link></template></el-table-column>
      </el-table>
    </div>
    <div class="card">
      <div class="card-title">精选素材库 <span class="hint">视频、音频和图片均可进入应用检索；3D/电商仍按官方页面授权导入</span></div>
      <el-tabs>
        <el-tab-pane label="视频">
          <div v-loading="curatedLoading" class="curated-grid">
            <div v-for="m in curated.video" :key="m.id" class="curated-item">
              <div class="curated-title">{{ m.name }}</div>
              <div class="muted" style="font-size:12px">{{ m.note }}</div>
              <div style="margin-top:6px"><el-button size="small" type="primary" plain @click="runCuratedSearch(m, 'video')">检索可导入素材</el-button></div>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="音频">
          <div v-loading="curatedLoading" class="curated-grid">
            <div v-for="m in curated.audio" :key="m.id" class="curated-item">
              <div class="curated-title">{{ m.name }}</div>
              <div class="muted" style="font-size:12px">{{ m.note }}</div>
              <div style="margin-top:6px"><el-button size="small" type="success" plain @click="runCuratedSearch(m, 'audio')">检索可导入音频</el-button></div>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="图片">
          <div v-loading="curatedLoading" class="curated-grid">
            <div v-for="m in curated.image" :key="m.id" class="curated-item">
              <div class="curated-title">{{ m.name }}</div>
              <div class="muted" style="font-size:12px">{{ m.note }}</div>
              <div style="margin-top:6px"><el-button size="small" type="warning" plain @click="runCuratedSearch(m, 'image')">检索可导入图片</el-button></div>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="3D/电商/更多">
          <div v-loading="curatedLoading" class="curated-grid">
            <div v-for="m in curated.extra" :key="m.id" class="curated-item">
              <div class="curated-title">{{ m.name }}</div>
              <div class="muted" style="font-size:12px">{{ m.note }}</div>
              <div style="margin-top:6px;display:flex;gap:8px;flex-wrap:wrap">
                <el-button size="small" type="info" plain @click="openCurated(m.url)">打开官方页</el-button>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { api, ROLE_LABEL } from '../api'

const router = useRouter()
const runtime = ref({})
const projects = ref([])
const folders = ref([])
const crawlFolderId = ref(null)
const curatedLoading = ref(false)
const curatedBusy = ref('')
const curated = reactive({ video: [], audio: [], image: [], extra: [] })
function runCuratedSearch (item, type) {
  if (type === 'video') {
    vq.source = item.source || 'wikimedia'
    vq.keyword = item.keyword || ''
    vq.limit = 12
    doVideoSearch()
  } else if (type === 'image') {
    iq.source = item.source || 'openverse'
    iq.keyword = item.keyword || ''
    iq.limit = 12
    doImageSearch()
  } else {
    aq.source = item.source || 'wikimedia'
    aq.keyword = item.keyword || ''
    aq.limit = 12
    doSearch()
  }
}
function openCurated (url) {
  if (url) window.open(url, '_blank', 'noopener,noreferrer')
}
async function fetchCurated (m, type, role) {
  curatedBusy.value = m.url
  try {
    if (type === 'video') await api.crawlVideo({ url: m.url, role })
    else await api.crawlDirect({ url: m.url, type, role })
    ElMessage.success(`已入库：${m.name}`)
  } catch (e) { ElMessage.error(`抓取失败：${e.message || e}`) }
  finally { curatedBusy.value = '' }
}
const urls = ref(''), videoRole = ref('body'), submitting = ref(false), cancelling = ref(false), retrying = ref(false)
const videoInput = ref(null)
const audioInput = ref(null)
const sources = ref([]), sourcesLoading = ref(false), sourcesError = ref(false)
const aq = reactive({ source: 'all', keyword: '', limit: 12, projectId: null })
const audioList = ref([]), pickedAudio = ref([]), audioRole = ref('bgm'), searching = ref(false), importing = ref(false)
const audioPreviewErrors = reactive({})
const iq = reactive({ source: 'all', keyword: '', limit: 12, projectId: null })
const imageList = ref([]), pickedImage = ref([]), imageRole = ref('body'), imageSearching = ref(false), imageImporting = ref(false)
const vq = reactive({ source: 'all', keyword: '', limit: 12, projectId: null })
const PRESETS = [
  { label: '美食探店', keyword: '美食 探店', limit: 12 },
  { label: '美妆测评', keyword: '美妆 测评', limit: 12 },
  { label: '科技开箱', keyword: '科技 开箱', limit: 12 },
  { label: '旅行风光', keyword: '旅行 风光', limit: 12 },
  { label: '母婴亲子', keyword: '母婴 温馨', limit: 12 }
]
function applyVideoPreset (p) { vq.keyword = p.keyword; vq.limit = p.limit; doVideoSearch() }
function applyAudioPreset (p) { aq.keyword = p.keyword; aq.limit = p.limit; doSearch() }
const publicVideoList = ref([]), pickedVideo = ref([]), publicVideoRole = ref('body'), videoSearching = ref(false), videoImporting = ref(false)
const crawlDetail = ref(null)
let pollingTimer = null
let pollingInFlight = false
const hasActiveJob = computed(() => ['pending', 'running'].includes(crawlDetail.value?.job?.status))
const hasFailedTasks = computed(() => (crawlDetail.value?.tasks || []).some((task) => task.status === 'failed' && !task.guardRejected))
const readySources = computed(() => sources.value.filter((source) => source.status === 'ready'))
const noKeySources = computed(() => readySources.value.filter((source) => !source.needKey && source.autoFill !== false && source.mode !== 'login-disabled'))
const keySources = computed(() => sources.value.filter((source) => source.needKey))
const manualSearchSources = computed(() => readySources.value.filter((source) => !source.needKey && source.autoFill === false))
const manualSources = computed(() => sources.value.filter((source) => !source.needKey && (source.status !== 'ready' || source.mode === 'login-disabled')))
const noticeRows = computed(() => audioList.value.filter((item) => item.notice || item.license === 'notice' || item.license === 'blocked'))
const realRows = computed(() => audioList.value.filter((item) => !(item.notice || item.license === 'notice' || item.license === 'blocked')))
const imageNoticeRows = computed(() => imageList.value.filter((item) => item.notice || item.license === 'notice' || item.license === 'blocked'))
const imageRealRows = computed(() => imageList.value.filter((item) => !(item.notice || item.license === 'notice' || item.license === 'blocked')))
const videoNoticeRows = computed(() => publicVideoList.value.filter((item) => item.notice || item.license === 'notice' || item.license === 'blocked'))
const videoRealRows = computed(() => publicVideoList.value.filter((item) => !(item.notice || item.license === 'notice' || item.license === 'blocked')))

function statusText (status) { return ({ pending: '排队中', running: '抓取中', done: '已完成', failed: '失败', cancelled: '已取消' })[status] || status || '未知' }
function statusType (status) { return ({ pending: 'warning', running: 'primary', done: 'success', failed: 'danger', cancelled: 'info' })[status] || 'info' }
function etaText (seconds) { if (!seconds) return '预计剩余时间：计算中'; const minutes = Math.ceil(seconds / 60); return `预计剩余：${minutes} 分钟` }
function stopPolling () { if (pollingTimer) { clearTimeout(pollingTimer); pollingTimer = null } }
function audioPreviewKey (row) { return row?.title || row?.previewUrl || row?.downloadUrl || 'unknown-audio-preview' }
function markAudioPreviewError (row) { audioPreviewErrors[audioPreviewKey(row)] = true }
async function refreshJob (silent = true) {
  if (pollingInFlight) return
  const id = crawlDetail.value?.job?.id
  if (!id) return
  pollingInFlight = true
  try {
    crawlDetail.value = await api.crawlJob(id, { silent })
    if (!hasActiveJob.value) { stopPolling(); return }
  } catch (error) {
    stopPolling()
    ElMessage.error(`获取采集进度失败：${error.message}`)
    return
  } finally {
    pollingInFlight = false
  }
  if (hasActiveJob.value) pollingTimer = window.setTimeout(() => refreshJob(true), 2500)
}
function beginPolling () { stopPolling(); refreshJob(false) }
async function startJob (request, successMessage) { submitting.value = true; try { const job = await request(); crawlDetail.value = { job, tasks: [] }; ElMessage.success(successMessage); beginPolling() } catch (error) { ElMessage.error(`提交采集任务失败：${error.message}`) } finally { submitting.value = false } }
function openAuth (source) {
  if (!source?.authUrl) return
  window.open(source.authUrl, '_blank', 'noopener,noreferrer')
  const config = source.configKey ? `，返回后在本机配置 ${source.configKey} 并重启后端` : '；授权完成后请按官方说明配置本机凭据'
  ElMessage.info(`已打开 ${source.name || source.source} 官方页面${config}`)
}
function goToSourceConfig (source) {
  const configId = source?.configId || source?.key
  if (!configId) return
  router.push({ path: '/capabilities', query: { source: configId } })
}
function goToEnv () {
  router.push('/tutorial')
  window.setTimeout(() => window.dispatchEvent(new CustomEvent('mework-open-guide', { detail: { section: 'environment' } })), 120)
}
const CRAWL_ERROR_LABELS = {
  URL_GUARD_REJECTED: '安全地址限制', TOOL_MISSING: '缺少下载工具', TOOL_FAILED: '下载工具失败',
  NO_DOWNLOAD_URL: '没有媒体直链', SOURCE_NOTICE: '来源需要处理', REDIRECT_REJECTED: '重定向被拒绝',
  TOO_MANY_REDIRECTS: '重定向过多', HTTP_AUTH_REQUIRED: '来源拒绝访问', HTTP_NOT_FOUND: '媒体不存在',
  HTTP_RATE_LIMITED: '来源限流', HTTP_SERVER_ERROR: '来源服务异常', HTTP_CLIENT_ERROR: '来源请求失败',
  EMPTY_OR_TOO_SMALL: '下载内容不可用', TIMEOUT: '来源响应超时', NETWORK: '网络连接失败', LOCAL_IO: '本机写入失败',
  DOWNLOAD_FAILED: '下载失败'
}
function crawlErrorLabel (code) { return CRAWL_ERROR_LABELS[code] || code || '抓取失败' }
async function reprobe (task) {
  if (!task?.materialId) return
  try {
    await api.reprobeMaterial(task.materialId)
    ElMessage.success('已重新检测素材，返回 Studio 后请重新检查素材')
    await refreshJob(true)
  } catch (error) { ElMessage.error(`重新检测失败：${error.message}`) }
}
function handleFailure (task) {
  const code = String(task?.errorCode || '')
  const message = String(task?.message || '')
  if (code === 'URL_GUARD_REJECTED' || (!code && /private|reserved|localhost|本机|内网|URL/i.test(message))) {
    ElMessageBox.alert('该地址被安全策略拒绝：只允许公开 HTTP/HTTPS 地址，不提供绕过内网或保留地址限制的方法。请换用公开链接，或导入你已获授权的本地文件。', 'URL 处理方法', { type: 'warning' })
    return
  }
  if (code === 'TOOL_MISSING' || (!code && /yt-dlp|you-get/i.test(message))) {
    document.getElementById('crawl-guide')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    ElMessage.info('当前本机未检测到下载工具。安装并验证 yt-dlp 后，可重新尝试公开视频页面链接。')
    return
  }
  if (['HTTP_AUTH_REQUIRED', 'SOURCE_NOTICE'].includes(code) || (!code && /登录|授权|DRM|版权|不支持/i.test(message))) {
    ElMessageBox.alert('该来源需要登录、授权或不提供公开直链。请在官网确认使用授权后导入已下载的本地文件，或选择免登录的公开来源；应用不会读取 Cookie、密码或绕过登录。', '来源处理方法', { type: 'warning' })
    return
  }
  if (['HTTP_NOT_FOUND', 'REDIRECT_REJECTED', 'TOO_MANY_REDIRECTS', 'NO_DOWNLOAD_URL'].includes(code)) {
    ElMessageBox.alert('该链接已失效、不是媒体直链或跳转不稳定。请重新从来源页面选择公开媒体文件，或换一个公开来源。', '链接处理方法', { type: 'info' })
    return
  }
  if (['HTTP_RATE_LIMITED', 'HTTP_SERVER_ERROR', 'TIMEOUT', 'NETWORK'].includes(code)) {
    ElMessageBox.alert('这是来源或网络的临时问题。请稍后重试，或切换到 Wikimedia、Internet Archive 等可公开访问的来源；当前本地素材仍可继续出片。', '重试建议', { type: 'info' })
    return
  }
  if (code === 'EMPTY_OR_TOO_SMALL') {
    ElMessageBox.alert('来源返回的内容为空、过小或不是有效媒体。请换用来源提供的原始媒体链接，或导入已下载的本地文件。', '媒体内容处理方法', { type: 'warning' })
    return
  }
  if (/key|密钥|Freesound|FREESOUND/i.test(message)) {
    const source = sources.value.find((item) => message.toLowerCase().includes(item.key))
    if (source) openAuth(source)
    else goToEnv()
    return
  }
  ElMessageBox.alert('请查看上方的失败类别和详细原因，确认链接是否公开、免登录且仍有效；修正后可重试失败项或切换来源。', '失败处理方法', { type: 'info' })
}
async function confirmLoginSource (source) {
  try {
    await ElMessageBox.confirm(
      `${source.name} 需要你在官方页面自行登录并确认拥有下载与使用授权。本应用不会读取 Cookie、账号或密码，也不会绕过登录。点击只会打开官网，不会假装启用。确认授权后，请在本机 .env 设置 APP_ALLOW_LOGIN_CRAWL=true，重启后端并在环境中心重新检测；也可直接导入你已获授权的本地文件。`,
      '确认授权范围', { confirmButtonText: '打开官网', cancelButtonText: '取消', type: 'warning' }
    )
    if (source.authUrl) window.open(source.authUrl, '_blank', 'noopener,noreferrer')
  } catch {}
}
function selectSource (source) {
  const knownAudioSources = ['mixkit', 'freesound', 'wikimedia', 'archive', 'openverse', 'tosound']
  if (!knownAudioSources.includes(source)) {
    const detail = source === 'pixabay'
      ? 'Pixabay 当前仅作为视频素材授权源，不能用于背景音乐检索。请使用 Mixkit、Freesound、Wikimedia Commons 或 Internet Archive。'
      : source === 'openverse'
        ? 'Openverse 支持免 Key 的公开音频检索，注册 OAuth 只用于提高配额；请直接选择 Openverse 检索。'
        : '该素材源需要登录或未开放音频检索，请使用公开免登录素材源或导入已授权本地文件。'
    ElMessage.warning(detail)
    return
  }
  aq.source = source
  audioInput.value?.focus()
}
function inputElement (component) {
  const root = component?.$el || component
  if (!root) return null
  if (typeof root.matches === 'function' && root.matches('input, textarea')) return root
  return typeof root.querySelector === 'function' ? root.querySelector('input, textarea') : null
}
function focusInput (component) {
  const input = inputElement(component)
  if (!input) return
  input.scrollIntoView({ behavior: 'smooth', block: 'center' })
  input.focus()
}
function focusUpload () { router.push('/materials') }
function selectCapability (command) {
  if (command === 'video') { focusInput(videoInput.value); return }
  if (command === 'audio') { focusInput(audioInput.value); return }
  if (command === 'local') { router.push('/materials'); return }
  ElMessage.info('只导入拥有明确许可的素材；来源页面的许可证与商用限制请逐条保存记录。')
}
function doFetch () { const items = urls.value.split('\n').map((item) => item.trim()).filter(Boolean); if (!items.length) return ElMessage.warning('请填写至少一个公开链接'); if (items.length > 200) return ElMessage.warning('单次最多抓取 200 条链接'); startJob(() => api.crawlVideoBatch({ urls: items, role: videoRole.value, folderId: crawlFolderId.value || undefined }), '采集任务已提交，完成后会归档到目标文件夹并可直接用于出片') }
async function doSearch () { searching.value = true; pickedAudio.value = []; try { audioList.value = await api.searchAudio({ source: aq.source, keyword: aq.keyword, limit: aq.limit, projectId: aq.projectId || undefined }); if (!realRows.value.length) ElMessage.info(noticeRows.value.length ? '没有可用素材，请查看上方提示' : '没搜到，换个关键词试试') } catch (error) { ElMessage.error(`搜索素材失败：${error.message}`) } finally { searching.value = false } }
async function doImageSearch () { imageSearching.value = true; pickedImage.value = []; try { imageList.value = await api.searchImage({ source: iq.source, keyword: iq.keyword, limit: iq.limit, projectId: iq.projectId || undefined }); if (!imageRealRows.value.length) ElMessage.info(imageNoticeRows.value.length ? '没有可用图片，请查看上方提示' : '没搜到，换个关键词试试') } catch (error) { ElMessage.error(`搜索图片素材失败：${error.message}`) } finally { imageSearching.value = false } }
function doImageImport () { if (!pickedImage.value.length) return ElMessage.warning('请先选择要导入的图片'); imageImporting.value = true; api.importImage({ items: pickedImage.value, role: imageRole.value, folderId: crawlFolderId.value || undefined }).then((job) => { crawlDetail.value = { job, tasks: [] }; ElMessage.success('图片导入任务已提交，完成后可直接用于出片'); beginPolling() }).catch((error) => ElMessage.error(`提交图片导入失败：${error.message}`)).finally(() => { imageImporting.value = false }) }
function doImport () { if (!pickedAudio.value.length) return ElMessage.warning('请先选择要导入的素材'); importing.value = true; api.importAudio({ items: pickedAudio.value, role: audioRole.value, folderId: crawlFolderId.value || undefined }).then((job) => { crawlDetail.value = { job, tasks: [] }; ElMessage.success('音频导入任务已提交，完成后可直接用于出片'); beginPolling() }).catch((error) => ElMessage.error(`提交导入任务失败：${error.message}`)).finally(() => { importing.value = false }) }
async function doVideoSearch () { videoSearching.value = true; pickedVideo.value = []; try { publicVideoList.value = await api.searchPublicVideo({ source: vq.source, keyword: vq.keyword, limit: vq.limit, projectId: vq.projectId || undefined }); if (!videoRealRows.value.length) ElMessage.info(videoNoticeRows.value.length ? '没有可用视频，请查看上方提示' : '未找到与关键词匹配的视频素材') } catch (error) { ElMessage.error(`搜索公开视频失败：${error.message}`) } finally { videoSearching.value = false } }
function doVideoImport () { if (!pickedVideo.value.length) return ElMessage.warning('请先选择要导入的素材'); videoImporting.value = true; api.importPublicVideo({ items: pickedVideo.value, role: publicVideoRole.value, folderId: crawlFolderId.value || undefined }).then((job) => { crawlDetail.value = { job, tasks: [] }; ElMessage.success('公开视频导入任务已提交，完成后可直接用于出片'); beginPolling() }).catch((error) => ElMessage.error(`提交公开视频失败：${error.message}`)).finally(() => { videoImporting.value = false }) }
async function cancelJob () { const id = crawlDetail.value?.job?.id; if (!id) return; cancelling.value = true; try { await api.cancelCrawlJob(id); ElMessage.success('已请求取消任务'); await refreshJob(false) } catch (error) { ElMessage.error(`取消任务失败：${error.message}`) } finally { cancelling.value = false } }
async function retryJob () { const id = crawlDetail.value?.job?.id; if (!id) return; retrying.value = true; try { const job = await api.retryCrawlJob(id); crawlDetail.value = { job, tasks: crawlDetail.value.tasks }; ElMessage.success('失败项已重新排队'); beginPolling() } catch (error) { ElMessage.error(`重试失败项失败：${error.message}`) } finally { retrying.value = false } }
async function deleteCrawlJob () { const id = crawlDetail.value?.job?.id; if (!id) return; try { await api.deleteCrawlJob(id); crawlDetail.value = null; ElMessage.success('采集记录已删除，已入库素材保留') } catch (error) { ElMessage.error(`删除采集记录失败：${error.message}`) } }
async function loadSources () { sourcesLoading.value = true; try { sources.value = await api.crawlSources(); sourcesError.value = false } catch { sourcesError.value = true } finally { sourcesLoading.value = false } }
async function loadCurated () {
  curatedLoading.value = true
  try {
    const rows = await api.crawlCurated()
    curated.video = rows.filter((row) => row.category === 'video')
    curated.audio = rows.filter((row) => row.category === 'audio')
    curated.image = rows.filter((row) => row.category === 'image')
    curated.extra = rows.filter((row) => row.category === 'extra')
  } catch (error) {
    ElMessage.error(`精选素材库加载失败：${error.message}`)
  } finally {
    curatedLoading.value = false
  }
}
watch(() => aq.projectId, () => { audioList.value = []; pickedAudio.value = [] })
watch(() => iq.projectId, () => { imageList.value = []; pickedImage.value = [] })
watch(() => vq.projectId, () => { publicVideoList.value = []; pickedVideo.value = [] })
onMounted(async () => { await Promise.all([loadSources(), loadCurated()]); try { const [projectRows, folderRows] = await Promise.all([api.projects(), api.materialFolders()]); projects.value = projectRows; folders.value = folderRows.filter(folder => folder.enabled !== false); const defaultProjectId = projects.value[0]?.id || null; aq.projectId = defaultProjectId; iq.projectId = defaultProjectId; vq.projectId = defaultProjectId } catch {} try { runtime.value = await api.env() } catch {} try { const jobs = await api.crawlJobs({ silent: true }); if (jobs.length) { crawlDetail.value = { job: jobs[0], tasks: [] }; await refreshJob(true); if (hasActiveJob.value) beginPolling() } } catch {} })
onBeforeUnmount(stopPolling)
</script>

<style scoped>
.guide-list { margin: 0; padding-left: 22px; line-height: 2; font-size: 14px; }
.failure-cell { display: flex; align-items: center; gap: 8px; min-width: 0; flex-wrap: wrap; }
.failure-message { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #f56c6c; max-width: 100%; }
.failure-diagnostic { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; width: 100%; font-size: 12px; }.crawl-admission-summary { display:flex; flex-wrap:wrap; gap:10px; margin:8px 0; padding:8px 10px; border:1px solid #ebeef5; border-radius:5px; background:#fbfcfe; font-size:12px; }.admission-ready { color:#67c23a; }.admission-failed { color:#e6a23c; }
.source-status-strip { display:flex; gap:10px; flex-wrap:wrap; margin:10px 0; }.source-status-strip span { padding:7px 10px; border:1px solid #ebeef5; border-radius:5px; color:#606266; font-size:12px; background:#fbfcfe; }.source-groups { margin-top:10px; }
.source-manual-card :deep(.el-button) { white-space: nowrap; }
.curated-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; }
.curated-item { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 10px; }
.curated-title { font-weight: 600; font-size: 13px; margin-bottom: 2px; }
</style>
