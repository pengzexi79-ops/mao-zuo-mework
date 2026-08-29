<template>
  <div>
    <div class="card">
      <div class="card-title">
        导入素材
        <span class="hint">"调用所有电脑里面的素材" —— 填目录直接递归扫，不用一个个传</span>
      </div>
      <el-form inline>
        <el-form-item label="本机目录">
          <el-input v-model="scanPath" style="width:420px"
            placeholder="例如：D:\素材\美妆 或 C:\用户\你\视频" clearable />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="autoRole">按文件名自动打角色标</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="scanning" @click="doScan">开始扫描</el-button>
        </el-form-item>
        <el-form-item label="上传角色">
          <el-select v-model="uploadData.role" style="width:150px"><el-option label="自动识别" value="none" /><el-option v-for="(label, key) in ROLE_LABEL" :key="key" :label="label" :value="key" /></el-select>
        </el-form-item>
        <el-form-item label="归档文件夹">
          <el-select v-model="uploadData.folderId" clearable style="width:170px" placeholder="不归档"><el-option v-for="folder in enabledFolders" :key="folder.id" :label="folder.name" :value="folder.id" /></el-select>
        </el-form-item>
        <el-form-item>
          <input ref="folderInput" class="native-folder-input" type="file" webkitdirectory directory multiple @change="onFolderPicked" />
          <el-button plain @click="folderInput?.click()">选择文件夹导入</el-button>
          <input ref="fileInput" class="native-folder-input" type="file" multiple :accept="acceptTypes" @change="onFilesPicked" />
          <el-button plain @click="fileInput?.click()">选择文件（兼容模式）</el-button>
          <input ref="archiveInput" class="native-folder-input" type="file" accept=".zip,application/zip" @change="onArchivePicked" />
          <el-button plain :loading="importingArchive" @click="archiveInput?.click()">导入 ZIP 素材总包</el-button>
        </el-form-item>
      </el-form>
      <div class="material-dropzone" @dragover.prevent @drop.prevent="onMaterialDrop" @click="folderInput?.click()">
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将文件或整个文件夹拖到这里，或点击选择文件夹</div>
        <div class="el-upload__tip">文件夹会作为一次总包导入，保留文件夹名称；非媒体文件会汇总跳过，单文件最大 2GB。</div>
      </div>
      <div class="form-hint">
        可直接拖入桌面文件或文件夹，也可使用“选择文件夹导入”“本机目录扫描”或“导入 ZIP 素材包”。目录扫描会继续处理可读媒体，即使遇到锁定或非媒体文件。
        请从微信将图片、视频、音频“另存为”正常文件后再导入；微信缓存的 .dat/.silk 是加密数据，应用不会尝试读取或破解。
        自动打标规则：文件名含「产品/product」→ 产品段；含「明星/达人/celeb」→ 明星段；含「钩子/hook/开头」→ 钩子段；
        音频含「配音/口播/voice」→ 人声，其余音频 → BGM。标错了下面可以批量改。
      </div>
      <el-alert v-if="scanResult" style="margin-top:10px" :type="scanResult.failed ? 'warning' : 'success'" :closable="true" show-icon
        :title="`扫描完成：新增 ${scanResult.imported || 0} 条，更新 ${scanResult.updated || 0} 条，跳过 ${scanResult.skipped || 0} 条，失败 ${scanResult.failed || 0} 条`"
        :description="scanResult.errors?.length ? scanResult.errors.join('；') : ((scanResult.skipped || 0) ? '已跳过非媒体、不可读或受控范围外的文件；可缩小目录后再次扫描。' : '可在下方素材列表中重新探测失败文件。')" />
    </div>

    <div class="card">
      <div class="card-title">
        素材库
        <span class="hint">共 {{ list.length }} 条</span>
        <span style="flex:1"></span>
        <el-button size="small" type="primary" @click="openFolderDialog()">管理文件夹</el-button>
        <el-button size="small" type="success" @click="ttsVisible = true">自动配音</el-button>
        <el-tag size="small" :type="audioEngine.separation ? 'success' : 'warning'">人声分离 {{ audioEngine.separation ? '可用' : '未就绪' }}</el-tag>
        <el-button size="small" @click="load">刷新</el-button>
        <el-button size="small" type="warning" plain @click="doPurge">清理失效记录</el-button>
        <el-tag size="small" type="info">分析后按语义选镜</el-tag>
      </div>

      <el-form inline>
        <el-form-item label="角色">
          <el-select v-model="q.role" style="width:130px" @change="load">
            <el-option label="全部" value="all" />
            <el-option v-for="(v, k) in ROLE_LABEL" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="q.type" style="width:110px" @change="load">
            <el-option label="全部" value="all" />
            <el-option label="视频" value="video" />
            <el-option label="音频" value="audio" />
            <el-option label="图片" value="image" />
          </el-select>
        </el-form-item>
          <el-form-item label="文件夹">
          <el-select v-model="q.folderId" style="width:180px" @change="load">
            <el-option label="全部文件夹" value="all" />
            <el-option v-for="folder in enabledFolders" :key="folder.id" :label="folder.name" :value="folder.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="q.kw" style="width:200px" clearable placeholder="文件名 / 标签"
            @keyup.enter="load" @clear="load" />
        </el-form-item>
        <el-form-item>
          <el-button @click="load">查询</el-button>
        </el-form-item>
        <el-form-item v-if="selected.length">
          <el-select v-model="batchRoleVal" placeholder="批量改角色" style="width:140px">
            <el-option v-for="(v, k) in ROLE_LABEL" :key="k" :label="v" :value="k" />
          </el-select>
          <el-button type="primary" style="margin-left:8px" @click="doBatchRole">
            应用到选中 {{ selected.length }} 条
          </el-button>
        </el-form-item>
      </el-form>
      <div class="selection-toolbar">
        <el-tag type="primary">已选 {{ selected.length }} 条</el-tag>
        <el-button size="small" :disabled="!list.length" @click="selectAllVisible">全选当前筛选结果</el-button>
        <el-input-number v-model="sliceSec" size="small" :min="1" :max="15" :step="1" :disabled="!selected.length" style="width:112px" />
        <el-button size="small" type="success" :loading="slicing" :disabled="!selected.length" @click="doAutoSlice">自动切片</el-button>
        <el-button size="small" type="primary" :loading="batchIndexing" :disabled="!selected.length" @click="doBatchIndex(false)">索引选中素材</el-button>
        <el-popconfirm title="将重新检测选中素材；已有转写和 OCR 缓存仍会复用，继续吗？" @confirm="doBatchIndex(true)">
          <template #reference><el-button size="small" :loading="batchIndexing" :disabled="!selected.length">强制重建索引</el-button></template>
        </el-popconfirm>
        <el-button size="small" type="primary" :disabled="!selected.length" @click="sendToStudio">送去出片</el-button>
        <el-popconfirm :title="`仅删除 ${selected.length} 条素材记录，不删除本地原始文件，继续吗？`" @confirm="doBatchDelete">
          <template #reference><el-button size="small" type="danger" plain :disabled="!selected.length">批量删除</el-button></template>
        </el-popconfirm>
        <el-button size="small" :disabled="!selected.length" @click="clearSelection">清空选择</el-button>
        <span class="muted">先勾选左侧复选框；批量删除只删除记录，不删除本地原文件</span>
      </div>
      <div v-if="uploadItems.length" class="upload-status-list">
        <div v-for="item in uploadItems" :key="item.uid" class="upload-status-row">
          <span class="upload-name">{{ item.name }}</span>
          <el-progress :percentage="item.percentage" :status="item.status === 'exception' ? 'exception' : (item.status === 'success' ? 'success' : '')" />
          <span class="muted">{{ item.message }}</span>
          <el-button v-if="item.status === 'exception' && item.file" link type="primary" size="small" @click="retryUpload(item)">重试</el-button>
        </div>
      </div>

      <el-collapse v-model="expandedFolderGroups" class="material-folder-groups" v-loading="loading">
        <el-collapse-item v-for="group in materialGroups" :key="group.key" :name="group.key">
          <template #title><b>{{ group.name }}</b><span class="muted material-group-count">视频 {{ group.counts.video }} · 音频 {{ group.counts.audio }} · 图片 {{ group.counts.image }}</span></template>
          <el-table :data="group.items" size="small" @selection-change="(rows) => syncGroupSelection(group.items, rows)">
        <el-table-column type="selection" width="42" />
        <el-table-column label="缩略" width="70">
          <template #default="{ row }">
            <img v-if="previewImageUrl(row)" :src="previewImageUrl(row)" :alt="row.name"
              style="width:34px;height:60px;object-fit:cover;border-radius:3px;background:#000"
              @error="markPreviewError(row)" />
            <el-icon v-else-if="row.fileType === 'audio'" style="font-size:22px;color:#909399"><Headset /></el-icon>
            <el-tooltip v-else :content="previewErrors[row.id] ? '图片预览失败：请确认文件仍存在且可读，然后重新扫描' : '该素材尚未生成预览'">
              <el-icon style="font-size:22px;color:#909399"><Picture /></el-icon>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="220" show-overflow-tooltip />
        <el-table-column label="角色" width="130">
          <template #default="{ row }">
            <el-select v-model="row.role" size="small" @change="(v) => saveRole(row, v)">
              <el-option v-for="(v, k) in ROLE_LABEL" :key="k" :label="v" :value="k" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="文件夹" width="140"><template #default="{ row }"><el-select :model-value="row.folderId" clearable size="small" placeholder="未归档" @change="(folderId) => moveMaterial(row, folderId)"><el-option v-for="folder in enabledFolders" :key="folder.id" :label="folder.name" :value="folder.id" /></el-select></template></el-table-column>
        <el-table-column prop="fileType" label="类型" width="70" />
        <el-table-column label="时长" width="80">
          <template #default="{ row }">{{ row.durationSec ? row.durationSec.toFixed(1) + 's' : '-' }}</template>
        </el-table-column>
        <el-table-column label="分辨率" width="100">
          <template #default="{ row }">{{ row.width ? row.width + '×' + row.height : '-' }}</template>
        </el-table-column>
        <el-table-column label="标签" min-width="140">
          <template #default="{ row }">
            <span class="muted">{{ row.tags || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="智能分析" width="120">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="openAnalysis(row)">查看 / 分析</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="80" />
        <el-table-column label="静音" width="60" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.muteOriginalAudio" size="small" @change="(v) => saveFlag(row, 'muteOriginalAudio', v)" />
          </template>
        </el-table-column>
        <el-table-column label="转录字幕" width="80" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.transcribeForSubtitles" size="small" @change="(v) => saveFlag(row, 'transcribeForSubtitles', v)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openPreview(row)">预览</el-button>
            <el-button link type="success" size="small" @click="diagnose(row)">AI 检查</el-button>
            <el-button v-if="row.fileType === 'audio' || row.fileType === 'video'" link type="warning" size="small" :loading="separatingIds.has(row.id)" :disabled="!audioEngine.separation" @click="separateAudio(row)">分离人声</el-button>
            <el-button link type="primary" size="small" @click="reprobe(row)">重新探测</el-button>
            <el-button v-if="row.fileType !== 'audio'" link type="primary" size="small" @click="retryThumbnail(row)">重试缩略图</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-popconfirm title="仅移出素材库记录，不删磁盘文件" @confirm="doDelete(row)">
              <template #reference><el-button link type="danger" size="small">移出</el-button></template>
            </el-popconfirm>
            <el-button link type="danger" size="small" @click="permanentDelete(row)">永久删除</el-button>
          </template>
        </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </div>

    <el-dialog v-model="analysisVisible" title="素材智能分析" width="760px" destroy-on-close @closed="stopAnalysisPolling">
      <div v-if="analysisLoading" v-loading="analysisLoading" style="height:90px"></div>
      <template v-else-if="analysis">
        <el-alert :type="analysis.status === 'completed' ? 'success' : analysis.status === 'failed' ? 'error' : 'warning'" :closable="false" show-icon :title="analysisStatusText(analysis.status)" :description="analysis.summary || analysis.error || '正在等待分析结果'" />
        <div v-if="analysis.tags?.length" style="margin-top:14px"><span class="muted">语义标签：</span><el-tag v-for="tag in analysis.tags" :key="tag" size="small" style="margin-right:6px">{{ tag }}</el-tag></div>
        <div v-if="analysis.ocrTexts?.length" style="margin-top:14px"><span class="muted">OCR 文字：</span>{{ analysis.ocrTexts.join(' · ') }}</div>
        <div v-if="analysis.issues?.length" style="margin-top:14px"><span class="muted">风险提示：</span><ul class="diagnosis-issues"><li v-for="issue in analysis.issues" :key="issue">{{ issue }}</li></ul></div>
        <el-table v-if="analysis.segments?.length" :data="analysis.segments" size="small" max-height="300" style="margin-top:14px"><el-table-column type="index" width="52" label="#" /><el-table-column label="代表帧" width="88"><template #default="{ row }"><img v-if="row.representativeFrameUrl" :src="api.protectedUrl(row.representativeFrameUrl)" alt="片段代表帧" style="width:64px;height:42px;object-fit:cover;border-radius:4px;background:#000" /></template></el-table-column><el-table-column label="时间" width="150"><template #default="{ row }">{{ Number(row.startSec).toFixed(1) }}–{{ Number(row.endSec).toFixed(1) }}s</template></el-table-column><el-table-column prop="durationSec" label="时长" width="80" /><el-table-column prop="score" label="场景分" width="80" /><el-table-column prop="reason" label="来源" min-width="150" /></el-table>
      </template>
      <template #footer><el-button @click="analysisVisible = false">关闭</el-button><el-button type="primary" :loading="analysisStarting" :disabled="isAnalysisActive" @click="runAnalysis(analysisMaterial)">{{ isAnalysisActive ? '分析进行中' : '重新分析' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="deleteVisible" title="永久删除素材" width="620px" destroy-on-close>
      <el-alert type="warning" :closable="false" show-icon title="这是不可恢复操作" description="仅应用管理目录中的原文件和派生数据会被删除；外部扫描目录的源文件、历史成片不会被删除。" />
      <el-descriptions v-if="deleteImpact" :column="1" border size="small" style="margin-top:14px"><el-descriptions-item label="源文件">{{ deleteImpact.appManaged ? (deleteImpact.sourceFilePath || '受控文件') : '外部扫描文件：原文件保留' }}</el-descriptions-item><el-descriptions-item label="分析/切片/转写">{{ deleteImpact.analysisCount }} / {{ deleteImpact.segmentCount }} / {{ deleteImpact.transcriptCount }}</el-descriptions-item><el-descriptions-item label="进行中引用"><span :style="{color: deleteImpact.blocked ? '#f56c6c' : '#67c23a'}">{{ deleteImpact.blocked ? '存在，必须先取消任务' : '无' }}</span></el-descriptions-item></el-descriptions>
      <el-checkbox v-model="deleteConfirmed" :disabled="deleteImpact?.blocked">我已确认永久删除受控文件与派生记录</el-checkbox>
      <template #footer><el-button @click="deleteVisible = false">取消</el-button><el-button type="danger" :disabled="!deleteConfirmed || deleteImpact?.blocked" :loading="deleting" @click="confirmPermanentDelete">永久删除</el-button></template>
    </el-dialog>

    <el-dialog v-model="diagnosisVisible" title="AI 素材检查" width="620px" destroy-on-close>
      <div v-if="diagnosis" class="diagnosis-result">
        <el-alert :type="diagnosis.level === '可用' ? 'success' : diagnosis.level === '不可用' ? 'error' : 'warning'" :closable="false" show-icon :title="diagnosis.level" :description="diagnosis.summary" />
        <el-descriptions :column="2" border size="small" style="margin-top:14px"><el-descriptions-item label="素材类型">{{ diagnosis.contentType }}</el-descriptions-item><el-descriptions-item label="建议角色">{{ ROLE_LABEL[diagnosis.roleSuggestion] || diagnosis.roleSuggestion }}</el-descriptions-item></el-descriptions>
        <div v-if="diagnosis.tags?.length" style="margin-top:14px"><span class="muted">识别标签：</span><el-tag v-for="tag in diagnosis.tags" :key="tag" size="small" style="margin-right:6px">{{ tag }}</el-tag></div>
        <div v-if="diagnosis.ocrTexts?.length" style="margin-top:14px"><div class="muted">画面文字识别：</div><div class="ocr-text">{{ diagnosis.ocrTexts.join(' · ') }}</div></div>
        <div v-if="diagnosis.transcript?.length" style="margin-top:14px"><div class="muted">口播识别：</div><div class="ocr-text"><div v-for="cue in diagnosis.transcript" :key="`${cue.start}-${cue.end}`">{{ cue.start.toFixed(1) }}–{{ cue.end.toFixed(1) }} 秒：{{ cue.text }}</div></div></div>
        <div v-if="diagnosis.transcriptStatus" style="margin-top:14px">
          <span class="muted">转录状态：</span>
          <el-tag :type="diagnosis.transcriptStatus === 'completed' ? 'success' : diagnosis.transcriptStatus === 'failed' ? 'danger' : 'warning'" size="small">{{ diagnosis.transcriptStatus === 'completed' ? '已完成' : diagnosis.transcriptStatus === 'failed' ? '失败' : diagnosis.transcriptStatus === 'running' ? '进行中' : diagnosis.transcriptStatus }}</el-tag>
          <el-button v-if="diagnosis.transcriptStatus !== 'running'" link type="primary" size="small" :loading="retranscribing" @click="retryTranscription(diagnosis.materialId)">重新转录</el-button>
        </div>
        <div v-if="diagnosis.transcribeForSubtitles !== undefined" style="margin-top:10px">
          <span class="muted">字幕授权：{{ diagnosis.transcribeForSubtitles ? '已开启，成片将使用本素材语音转录字幕' : '未开启，本素材不会被用于字幕生成' }}</span>
        </div>
        <div v-if="diagnosis.muteOriginalAudio !== undefined" style="margin-top:4px">
          <span class="muted">原声静音：{{ diagnosis.muteOriginalAudio ? '已开启，本素材原声将在渲染时被移除' : '未开启，按声音模式保留原声' }}</span>
        </div>
        <div v-if="diagnosis.issues?.length" style="margin-top:14px"><div class="muted">需要留意：</div><ul class="diagnosis-issues"><li v-for="issue in diagnosis.issues" :key="issue">{{ issue }}</li></ul></div>
      </div>
    </el-dialog>

    <el-dialog v-model="ttsVisible" title="自动配音" width="620px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="项目"><el-select v-model="ttsProjectId" clearable filterable placeholder="不选则按通用带货口播生成" style="width:100%"><el-option v-for="project in projects" :key="project.id" :label="project.name || project.product || `项目 #${project.id}`" :value="project.id" /></el-select></el-form-item>
        <el-form-item label="目标时长"><el-input-number v-model="ttsSeconds" :min="5" :max="180" controls-position="right" /><span class="muted" style="margin-left:8px">预计 {{ estimatedTtsSeconds }} 秒</span></el-form-item>
        <el-form-item label="补充要求"><el-input v-model="ttsExtra" maxlength="200" placeholder="例如：语气温和，突出使用场景" /></el-form-item>
        <el-form-item label="配音文案"><div style="width:100%"><div class="tts-ai-actions"><el-button size="small" type="primary" plain :loading="ttsDrafting" @click="draftTtsScript">AI 生成口播稿</el-button><el-button size="small" :loading="ttsHookDrafting" @click="draftTtsHook">AI 生成钩子稿</el-button><el-tag size="small" :type="audioEngine.tts ? 'success' : 'warning'">TTS {{ audioEngine.tts ? '可用' : '未就绪' }}</el-tag><el-tag size="small" :type="audioEngine.naturalTts ? 'success' : 'info'">自然配音 {{ audioEngine.naturalTts ? '可用' : '可选' }}</el-tag></div><el-input v-model="ttsDraft.text" type="textarea" :rows="6" maxlength="1200" show-word-limit placeholder="粘贴需要读出来的带货文案，或使用 AI 按项目生成。" /></div></el-form-item>
        <el-form-item label="声音"><div style="width:100%"><div class="tts-voice-recommendation"><span class="muted">建议：{{ ttsVoiceRecommendation.label }}（{{ ttsVoiceRecommendation.reason }}）</span><el-button link type="primary" size="small" @click="useRecommendedTtsVoice">采用建议</el-button></div><el-select v-model="ttsDraft.voice" style="width:100%"><el-option v-for="voice in ttsVoices" :key="voice.value" :label="voice.label" :value="voice.value" /></el-select></div></el-form-item>
        <div class="form-hint">建议只从本机可选音色中挑选；AI 只生成可朗读文案，生成后仍会检查时长和静音，异常音频不会进入素材库。</div>
      </el-form>
      <template #footer><el-button @click="ttsVisible = false">取消</el-button><el-button type="primary" :loading="ttsGenerating" :disabled="!audioEngine.tts" @click="generateTts">生成并入库</el-button></template>
    </el-dialog>

    <el-dialog v-model="folderVisible" title="管理素材文件夹" width="620px">
      <el-form label-width="84px"><el-form-item label="名称"><el-input v-model="folderDraft.name" placeholder="例如：护肤主体素材" /></el-form-item><el-form-item label="说明"><el-input v-model="folderDraft.description" placeholder="仅用于说明，不影响现有路径" /></el-form-item><el-form-item label="启用"><el-switch v-model="folderDraft.enabled" /></el-form-item></el-form>
      <div style="margin-bottom:12px"><el-button type="primary" :loading="savingFolder" @click="saveFolder">{{ folderDraft.id ? '保存文件夹' : '新建文件夹' }}</el-button><el-button @click="resetFolderDraft">取消编辑</el-button></div>
      <el-table :data="folders" size="small" max-height="220"><el-table-column prop="name" label="名称" /><el-table-column prop="description" label="说明" /><el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="190"><template #default="{ row }"><el-button link size="small" @click="loadFolderContents(row)">查看素材</el-button><el-button link size="small" @click="filterToFolder(row)">筛选</el-button><el-button link size="small" @click="editFolder(row)">编辑</el-button><el-popconfirm title="仅能删除没有关联素材的文件夹" @confirm="deleteFolder(row)"><template #reference><el-button link type="danger" size="small">删除</el-button></template></el-popconfirm></template></el-table-column></el-table>
      <el-collapse v-if="Object.keys(folderContents).length" style="margin-top:12px"><el-collapse-item v-for="folder in folders.filter((item) => folderContents[item.id])" :key="folder.id" :name="String(folder.id)" :title="`${folder.name} 内的素材`"><div v-if="folderContentsLoading === folder.id" v-loading="true" style="height:64px"></div><div v-else v-for="group in folderMediaSummary(folder)" :key="group.type" class="folder-media-type"><b>{{ group.type === 'video' ? '视频' : group.type === 'audio' ? '音频' : '图片' }}</b><span class="muted">{{ group.items.length }} 条</span><div v-if="group.items.length" class="folder-media-items">{{ group.items.map((item) => item.name).join(' · ') }}</div></div></el-collapse-item></el-collapse>
    </el-dialog>

    <el-dialog v-model="previewVisible" class="material-preview-dialog" :title="previewing?.name || '素材预览'" width="min(760px, calc(100vw - 28px))" destroy-on-close>
      <div v-if="previewing" class="material-preview">
        <img v-if="previewing.fileType === 'image'" :src="materialUrl(previewing)" :alt="previewing.name" @error="markPreviewError(previewing)" />
        <audio v-else-if="previewing.fileType === 'audio'" :src="materialUrl(previewing)" controls preload="metadata" @error="markPreviewError(previewing)" style="width:100%"></audio>
        <video v-else :src="materialUrl(previewing)" controls preload="metadata" @error="markPreviewError(previewing)"></video>
        <el-alert v-if="previewErrors[previewing.id]" type="error" :closable="false" show-icon
          title="预览读取失败：请检查文件是否被移动、删除或被其他程序占用，然后重新扫描素材目录。" />
        <div class="muted" style="margin-top:10px">{{ previewing.fileType === 'audio' ? '音频试听' : (previewing.width ? `${previewing.width}×${previewing.height}` : '尺寸待检测') }} · {{ previewing.durationSec ? previewing.durationSec.toFixed(1) + 's' : '时长待检测' }}</div>
      </div>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑素材" width="520px">
      <el-form v-if="editing" label-width="80px">
        <el-form-item label="名称"><el-input v-model="editing.name" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="editing.role" style="width:100%">
            <el-option v-for="(v, k) in ROLE_LABEL" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="editing.tags" placeholder="逗号分隔，混剪时可按标签筛选" />
        </el-form-item>
        <el-form-item label="路径">
          <span class="mono muted">{{ editing.filePath }}</span>
        </el-form-item>
        <el-form-item label="静音原声">
          <el-switch v-model="editing.muteOriginalAudio" />
          <span class="muted" style="margin-left:8px">开启后，本素材在混剪渲染时将移除原始音频</span>
        </el-form-item>
        <el-form-item label="转录字幕">
          <el-switch v-model="editing.transcribeForSubtitles" />
          <span class="muted" style="margin-left:8px">开启后，本素材语音将被转录并可用于字幕生成</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="packageNameVisible" title="确认素材总包名称" width="520px" destroy-on-close>
      <el-alert :type="packageAudit?.valid ? 'info' : 'warning'" :closable="false" show-icon
        :title="packageAudit?.valid ? '系统将按这个顶层名称归类' : '名称需要修改后才能创建素材库'"
        :description="packageAudit?.reason || '名称审核中'" />
      <el-form label-width="86px" style="margin-top:14px">
        <el-form-item label="总包名称"><el-input v-model="packageNameDraft" maxlength="80" show-word-limit /></el-form-item>
      </el-form>
      <div class="form-hint">同名总包会合并到已有素材库；内部子目录名称不会创建新的素材库。</div>
      <template #footer><el-button @click="packageNameVisible = false">取消</el-button><el-button type="primary" :loading="packageImporting" @click="confirmPackageImport">确认导入</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { api, ROLE_LABEL, uploadFile, importMaterialPackage, importMaterialPackageArchive } from '../api'

const route = useRoute()
const router = useRouter()
const list = ref([])
const tableRef = ref(null)
const loading = ref(false)
const selected = ref([])
const expandedFolderGroups = ref([])
const folders = ref([])
const uploadItems = ref([])
const batchRoleVal = ref('')
const sliceSec = ref(3)
const slicing = ref(false)
const batchIndexing = ref(false)
const q = reactive({ role: 'all', type: 'all', kw: '', folderId: 'all' })

const scanPath = ref('')
const autoRole = ref(true)
const folderInput = ref(null)
const fileInput = ref(null)
const archiveInput = ref(null)
const importingArchive = ref(false)
const packageNameVisible = ref(false)
const packageImporting = ref(false)
const packageNameDraft = ref('')
const packageAudit = ref(null)
const pendingPackage = ref(null)
// Browser picker hint only. Final audio/video acceptance is confirmed by local FFprobe.
const acceptTypes = '.mp4,.mov,.mkv,.avi,.flv,.webm,.m4v,.wmv,.ts,.mts,.m2ts,.3gp,.3g2,.ogv,.vob,.mpg,.mpeg,.m2v,.mxf,.asf,.divx,.f4v,.rm,.rmvb,.qt,.dv,.mp3,.wav,.m4a,.aac,.flac,.ogg,.oga,.opus,.wma,.aiff,.aif,.amr,.ape,.alac,.ac3,.eac3,.dts,.caf,.au,.ra,.jpg,.jpeg,.png,.webp,.bmp,.gif,.avif,.tif,.tiff,.zip'
const uploadData = reactive({ role: 'none', folderId: '' })
const scanning = ref(false)
const scanResult = ref(null)
const ttsVisible = ref(false)
const ttsGenerating = ref(false)
const ttsDrafting = ref(false)
const ttsHookDrafting = ref(false)
const ttsProjectId = ref(null)
const ttsSeconds = ref(30)
const ttsExtra = ref('')
const projects = ref([])
const ttsDraft = reactive({ text: '', voice: 'zh-CN-XiaoxiaoNeural' })
const audioEngine = reactive({ tts: false, naturalTts: false, separation: false, separationProvider: 'Demucs' })
const ttsVoices = [
  { value: 'zh-CN-XiaoxiaoNeural', label: '晓晓女声' },
  { value: 'zh-CN-XiaoyiNeural', label: '晓伊女声' },
  { value: 'zh-CN-YunxiNeural', label: '云希男声' },
  { value: 'zh-CN-YunjianNeural', label: '云健男声' }
]
const selectedTtsProject = computed(() => projects.value.find((project) => String(project.id) === String(ttsProjectId.value)) || null)
const ttsVoiceRecommendation = computed(() => {
  const context = [selectedTtsProject.value?.audience, selectedTtsProject.value?.tone, ttsExtra.value, ttsDraft.text].filter(Boolean).join(' ').toLowerCase()
  if (/(男性|男士|男性向|硬朗|专业|沉稳|科技|数码|汽车|商务)/.test(context)) return { value: 'zh-CN-YunjianNeural', label: '云健男声', reason: '项目偏专业、硬朗或男性受众' }
  if (/(儿童|亲子|学生|活泼|年轻|轻松|可爱|元气)/.test(context)) return { value: 'zh-CN-XiaoyiNeural', label: '晓伊女声', reason: '项目偏年轻、轻松或亲子场景' }
  if (/(成熟|中老年|长辈|温和|治愈|可信|稳重|家庭)/.test(context)) return { value: 'zh-CN-YunxiNeural', label: '云希男声', reason: '项目需要温和、可信的叙述感' }
  return { value: 'zh-CN-XiaoxiaoNeural', label: '晓晓女声', reason: '通用带货口播，清晰自然' }
})
const estimatedTtsSeconds = computed(() => Math.max(1, Math.ceil((ttsDraft.text || '').replace(/\s/g, '').length / 4.2)))
const separatingIds = reactive(new Set())

const editVisible = ref(false)
const editing = ref(null)
const previewVisible = ref(false)
const previewing = ref(null)
const diagnosisVisible = ref(false)
const diagnosis = ref(null)
const analysisVisible = ref(false)
const analysis = ref(null)
const analysisMaterial = ref(null)
const analysisLoading = ref(false)
const analysisStarting = ref(false)
const deleteVisible = ref(false)
const deleteImpact = ref(null)
const deleteTarget = ref(null)
const deleteConfirmed = ref(false)
const deleting = ref(false)
const previewErrors = reactive({})
const folderVisible = ref(false)
const savingFolder = ref(false)
const folderDraft = reactive({ id: null, name: '', description: '', enabled: true })
const folderContents = reactive({})
const folderContentsLoading = ref(null)
const enabledFolders = computed(() => folders.value.filter((folder) => folder.enabled !== false))
const folderNameById = computed(() => new Map(folders.value.map((folder) => [String(folder.id), folder.name])))
const materialGroups = computed(() => {
  const groups = new Map()
  for (const item of list.value) {
    const key = item.folderId == null ? 'unfiled' : `folder-${item.folderId}`
    if (!groups.has(key)) {
      groups.set(key, {
        key,
        name: item.folderId == null ? '未归档素材' : (folderNameById.value.get(String(item.folderId)) || `文件夹 #${item.folderId}`),
        items: [],
        counts: { video: 0, audio: 0, image: 0 }
      })
    }
    const group = groups.get(key)
    group.items.push(item)
    if (group.counts[item.fileType] !== undefined) group.counts[item.fileType] += 1
  }
  return [...groups.values()]
})
const isAnalysisActive = computed(() => ['pending', 'running'].includes(analysis.value?.status))
let analysisPollingTimer = null
let analysisPollingInFlight = false

function resetFolderDraft () { Object.assign(folderDraft, { id: null, name: '', description: '', enabled: true }) }
function openFolderDialog () { resetFolderDraft(); folderVisible.value = true }
function editFolder (folder) { Object.assign(folderDraft, { id: folder.id, name: folder.name, description: folder.description || '', enabled: folder.enabled !== false }); folderVisible.value = true }
async function loadFolders () { folders.value = await api.materialFolders() }
async function saveFolder () { if (!folderDraft.name.trim()) return ElMessage.warning('请填写文件夹名称'); savingFolder.value = true; try { if (folderDraft.id) await api.updateMaterialFolder(folderDraft.id, folderDraft); else await api.createMaterialFolder(folderDraft); await loadFolders(); resetFolderDraft(); ElMessage.success('文件夹已保存') } catch (error) { ElMessage.error(`保存文件夹失败：${error.message}`) } finally { savingFolder.value = false } }
async function deleteFolder (folder) { try { await api.deleteMaterialFolder(folder.id); await loadFolders(); ElMessage.success('文件夹已删除') } catch (error) { ElMessage.error(`删除文件夹失败：${error.message}`) } }
async function loadFolderContents (folder) {
  if (!folder || folderContentsLoading.value === folder.id) return
  folderContentsLoading.value = folder.id
  try { folderContents[folder.id] = await api.materials({ folderId: folder.id }) } catch (error) { ElMessage.error(`读取文件夹素材失败：${error.message}`) } finally { folderContentsLoading.value = null }
}
function folderMediaSummary (folder) {
  const media = folderContents[folder.id] || []
  return ['video', 'audio', 'image'].map((type) => ({ type, items: media.filter((item) => item.fileType === type) }))
}
function filterToFolder (folder) {
  q.folderId = String(folder.id)
  folderVisible.value = false
  load()
}
async function moveMaterial (row, folderId) { try { const saved = await api.moveMaterial(row.id, { folderId: folderId || null }); row.folderId = saved.folderId; ElMessage.success('素材归属已更新') } catch (error) { ElMessage.error(`移动素材失败：${error.message}`) } }

function materialUrl(row) {
  if (!row) return ''
  return api.protectedUrl(row.previewUrl || api.materialPreviewUrl(row.id))
}
function previewImageUrl(row) {
  if (previewErrors[row.id]) return ''
  if (row.fileType === 'image') return materialUrl(row)
  return row.thumbnailUrl ? api.protectedUrl(row.thumbnailUrl) : (row.thumbnail ? api.protectedUrl(row.thumbnail) : '')
}
function markPreviewError(row) {
  if (row?.id) previewErrors[row.id] = true
}
function openPreview(row) {
  previewErrors[row.id] = false
  previewing.value = row
  previewVisible.value = true
}

async function diagnose (row) {
  try {
    diagnosis.value = await api.diagnoseMaterial(row.id)
    diagnosisVisible.value = true
  } catch (error) {
    ElMessage.error(`素材检查失败：${error.message}`)
  }
}

function analysisStatusText (status) {
  return ({ pending: '等待分析', running: '正在分析', completed: '分析完成', failed: '分析失败' })[status] || '分析状态未知'
}
function stopAnalysisPolling () {
  if (analysisPollingTimer) window.clearTimeout(analysisPollingTimer)
  analysisPollingTimer = null
}
async function loadAnalysis (material, { initial = false } = {}) {
  if (!material || analysisPollingInFlight) return
  analysisPollingInFlight = true
  if (initial) analysisLoading.value = true
  try {
    analysis.value = await api.materialAnalysis(material.id, { silent: !initial })
  } catch (error) {
    analysis.value = { status: 'failed', error: error.message || '读取分析结果失败', segments: [] }
  } finally {
    analysisPollingInFlight = false
    if (initial) analysisLoading.value = false
  }
  if (analysisVisible.value && isAnalysisActive.value) {
    analysisPollingTimer = window.setTimeout(() => loadAnalysis(material), 2200)
  } else {
    stopAnalysisPolling()
  }
}
async function openAnalysis (material) {
  stopAnalysisPolling()
  analysisMaterial.value = material
  analysis.value = null
  analysisVisible.value = true
  await loadAnalysis(material, { initial: true })
}
async function runAnalysis (material) {
  if (!material || isAnalysisActive.value) return
  analysisStarting.value = true
  try {
    stopAnalysisPolling()
    analysis.value = await api.analyzeMaterial(material.id)
    ElMessage.success('已启动素材分析，结果会自动刷新')
    if (isAnalysisActive.value) analysisPollingTimer = window.setTimeout(() => loadAnalysis(material), 1200)
  } catch (error) {
    ElMessage.error(`启动分析失败：${error.message}`)
  } finally {
    analysisStarting.value = false
  }
}
async function permanentDelete (material) {
  deleteTarget.value = material
  deleteImpact.value = null
  deleteConfirmed.value = false
  deleteVisible.value = true
  try {
    deleteImpact.value = await api.materialDeleteImpact(material.id)
  } catch (error) {
    deleteVisible.value = false
    ElMessage.error(`无法读取删除影响：${error.message}`)
  }
}
async function confirmPermanentDelete () {
  if (!deleteTarget.value || !deleteConfirmed.value || deleteImpact.value?.blocked) return
  deleting.value = true
  try {
    const result = await api.permanentlyDeleteMaterial(deleteTarget.value.id)
    deleteVisible.value = false
    clearSelection()
    await load()
    ElMessage.success(`已永久删除素材记录及 ${result.deletedFiles?.length || 0} 个受控文件`)
  } catch (error) {
    ElMessage.error(`永久删除失败：${error.message}`)
  } finally {
    deleting.value = false
  }
}

const retranscribing = ref(false)
async function retryTranscription (materialId) {
  retranscribing.value = true
  try {
    diagnosis.value = await api.retryTranscription(materialId)
    ElMessage.success('重新转录完成')
  } catch (error) {
    ElMessage.error(`重新转录失败：${error.message}`)
  } finally {
    retranscribing.value = false
  }
}

async function saveFlag (row, field, value) {
  try {
    const body = {}
    body[field] = value
    await api.updateMaterial(row.id, body)
    ElMessage.success(field === 'muteOriginalAudio' ? '静音设置已更新' : '转录设置已更新')
  } catch (error) {
    ElMessage.error(`设置更新失败：${error.message}`)
    row[field] = !value
  }
}

async function load() {
  loading.value = true
  try {
    list.value = await api.materials({ role: q.role, type: q.type, kw: q.kw, folderId: q.folderId === 'all' ? undefined : q.folderId })
  } finally {
    loading.value = false
  }
}

async function openMaterialFromRoute () {
  const materialId = Number(route.query.materialId)
  if (!Number.isFinite(materialId)) return
  try {
    const target = await api.material(materialId)
    openPreview(target)
    const query = { ...route.query }
    delete query.materialId
    await router.replace({ path: '/materials', query })
  } catch (error) {
    ElMessage.error(`打开生成素材失败：${error.message || '素材不存在或已被移除'}`)
  }
}

async function loadAudioEngineStatus () {
  try {
    Object.assign(audioEngine, await api.audioEngineStatus())
  } catch {
    Object.assign(audioEngine, { tts: false, naturalTts: false, separation: false })
  }
}

async function draftTtsScript () {
  ttsDrafting.value = true
  try {
    const text = await api.genScript({ projectId: ttsProjectId.value || undefined, seconds: ttsSeconds.value, extra: ttsExtra.value || undefined })
    if (!text?.trim()) return ElMessage.warning('AI 没有生成可用口播稿，请补充项目或卖点后重试')
    ttsDraft.text = text.trim()
    ElMessage.success('AI 口播稿已生成，可继续编辑后配音')
  } catch (error) { ElMessage.error(`AI 生成口播稿失败：${error.message}`) } finally { ttsDrafting.value = false }
}
async function draftTtsHook () {
  ttsHookDrafting.value = true
  try {
    const hooks = await api.genHooks({ projectId: ttsProjectId.value || undefined, count: 1, seconds: Math.min(15, ttsSeconds.value), extra: ttsExtra.value || undefined })
    const hook = Array.isArray(hooks) ? hooks[0] : ''
    if (!hook?.trim()) return ElMessage.warning('AI 没有生成可用钩子稿，请补充项目或卖点后重试')
    ttsDraft.text = ttsDraft.text.trim() ? `${hook.trim()}\n${ttsDraft.text.trim()}` : hook.trim()
    ElMessage.success('AI 钩子稿已加入配音文案开头')
  } catch (error) { ElMessage.error(`AI 生成钩子稿失败：${error.message}`) } finally { ttsHookDrafting.value = false }
}

function useRecommendedTtsVoice () {
  ttsDraft.voice = ttsVoiceRecommendation.value.value
  ElMessage.success(`已采用${ttsVoiceRecommendation.value.label}建议`)
}

async function generateTts () {
  if (!ttsDraft.text.trim()) return ElMessage.warning('请先填写需要配音的文案')
  ttsGenerating.value = true
  try {
    const material = await api.generateTts(ttsDraft)
    ttsVisible.value = false
    ttsDraft.text = ''
    await load()
    ElMessage.success(`配音已入库：${material.name}`)
  } catch (error) {
    ElMessage.error(`配音生成失败：${error.message}`)
  } finally {
    ttsGenerating.value = false
  }
}

async function separateAudio (row) {
  if (!row || separatingIds.has(row.id)) return
  separatingIds.add(row.id)
  try {
    const result = await api.separateAudio(row.id)
    await load()
    ElMessage.success(result?.message || '人声和伴奏已分离并入库')
  } catch (error) {
    ElMessage.error(`人声分离失败：${error.message}`)
    await loadAudioEngineStatus()
  } finally {
    separatingIds.delete(row.id)
  }
}

async function doScan() {
  if (!scanPath.value) return ElMessage.warning('请填写目录路径')
  scanning.value = true
  try {
    scanResult.value = await api.scanFolder({ path: scanPath.value, autoRole: autoRole.value })
    await load()
  } finally {
    scanning.value = false
  }
}

function isSupportedMediaOrZip (file) {
  const name = String(file?.name || '').toLowerCase()
  return acceptTypes.split(',').some((extension) => name.endsWith(extension))
}

function beforeUpload(file) {
  if (!file || !file.name || file.isDirectory) {
    ElMessage.warning('浏览器未能展开这个文件夹。请使用“选择文件夹导入”或填写本机目录后开始扫描。')
    return false
  }
  if (file.size <= 0) {
    ElMessage.warning(`${file.name}：文件为空、仍在下载，或来自微信临时窗口。请先在微信中另存到桌面后重试。`)
    return false
  }
  if (/\.(dat|silk)$/i.test(file.name)) {
    ElMessage.warning(`${file.name} 是微信加密缓存文件，请在微信中另存为正常图片、视频或音频后再导入。`)
    return false
  }
  if (file.size > 2 * 1024 * 1024 * 1024) {
    ElMessage.error(`${file.name}：超过 2GB 限制`)
    return false
  }
  if (!isSupportedMediaOrZip(file)) {
    ElMessage.info(`${file.name} 不是可识别的图片、视频、音频或 ZIP，已跳过`)
    return false
  }
  return true
}
async function onFilesPicked (event) { await uploadEntries(Array.from(event.target.files || [])); event.target.value = '' }
async function onArchivePicked (event) {
  const archive = event.target.files?.[0]
  event.target.value = ''
  if (!archive) return
  if (!/\.zip$/i.test(archive.name)) return ElMessage.warning('目前只支持 ZIP 素材包；RAR/7Z 请先在本机解压后选择文件夹导入。')
  if (archive.size <= 0) return ElMessage.warning('ZIP 素材包为空或尚未完整保存。')
  await openPackageImport({ kind: 'archive', files: [archive], packageName: archive.name.replace(/\.zip$/i, '') })
}
async function openPackageImport (payload) {
  pendingPackage.value = payload
  packageNameDraft.value = payload.packageName || ''
  packageAudit.value = null
  packageNameVisible.value = true
  try {
    packageAudit.value = await api.auditMaterialPackageName(packageNameDraft.value)
    if (packageAudit.value?.suggestion && packageAudit.value.valid) packageNameDraft.value = packageAudit.value.suggestion
  } catch (error) {
    packageAudit.value = { valid: false, reason: error.message || '名称审核失败，请修改后重试' }
  }
}
async function importWorkflowPacks (packs) {
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
async function confirmPackageImport () {
  if (!pendingPackage.value || packageImporting.value) return
  packageImporting.value = true
  try {
    const audit = await api.auditMaterialPackageName(packageNameDraft.value.trim())
    packageAudit.value = audit
    if (!audit?.valid) return ElMessage.warning(`名称不合法：${audit?.reason || '请修改后重试'}`)
    const payload = pendingPackage.value
    let files = payload.files || []
    if (payload.kind === 'folder') {
      const jsonFiles = files.filter((file) => /\.json$/i.test(file.name || ''))
      const mediaFiles = files.filter((file) => !/\.json$/i.test(file.name || ''))
      if (jsonFiles.length) {
        const staged = []
        for (const file of jsonFiles) {
          if ((file.size || 0) > 5 * 1024 * 1024) { ElMessage.warning(`${file.name} 超过 5MB，已跳过工作流导入`); continue }
          try {
            const text = await file.text()
            const expectedFormat = expectedPackFormat(file, text)
            if (expectedFormat) staged.push({ name: file.name, expectedFormat, text })
            else ElMessage.warning(`${file.name} 不是可识别的工作流/Skill 包，已跳过`)
          } catch { ElMessage.warning(`${file.name} 读取失败，已跳过`) }
        }
        if (staged.length) await importWorkflowPacks(staged)
      }
      files = mediaFiles
      if (!files.length) {
        packageNameVisible.value = false
        pendingPackage.value = null
        ElMessage.success('工作流/Skill JSON 已通过安全校验并导入，未创建素材库')
        return
      }
    }
    const importData = { role: uploadData.role, folderId: uploadData.folderId || undefined }
    const result = payload.kind === 'archive'
      ? await importMaterialPackageArchive(payload.files[0], { packageName: packageNameDraft.value.trim(), ...importData })
      : await importMaterialPackage(files, packageNameDraft.value.trim(), payload.relativePaths || files.map((file) => file.webkitRelativePath || file.name), importData)
    packageNameVisible.value = false
    pendingPackage.value = null
    scanResult.value = result
    const importedWorkflowPacks = await importWorkflowPacks((result.workflowPacks || []).map((pack) => ({ name: pack.name, text: pack.content })))
    await loadFolders()
    if (result.folderId) {
      q.folderId = result.folderId
      await load()
      await router.push({ path: '/materials', query: { folderId: String(result.folderId) } })
    }
    ElMessage.success(`素材总包导入完成：视频 ${result.videoImported || 0}，音频 ${result.audioImported || 0}，图片 ${result.imageImported || 0}，跳过 ${result.skipped || 0}，失败 ${result.failed || 0}`)
  } catch (error) {
    ElMessage.error(`素材总包导入失败：${error.message || '请检查文件是否完整可读'}`)
  } finally {
    packageImporting.value = false
  }
}

async function uploadEntries (files) {
  if (!files.length) return
  const validFiles = files.filter((file) => beforeUpload(file))
  const batchId = Date.now()
  const entries = validFiles.map((file, index) => ({ file, item: { uid: `upload-${batchId}-${index}`, name: file.webkitRelativePath || file.name, file, percentage: 0, status: 'pending', message: '等待上传' } }))
  entries.forEach(({ item }) => uploadItems.value.push(item))
  let nextIndex = 0
  const worker = async () => { while (nextIndex < entries.length) { const entry = entries[nextIndex++]; await uploadOneItem(entry.file, entry.item); if (entry.item.status === 'exception') ElMessage.error(`${entry.file.name} 导入失败：${entry.item.message}`) } }
  await Promise.all(Array.from({ length: Math.min(3, entries.length) }, worker))
  await load()
}
async function onFolderPicked (event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (!files.length) return
  const validFiles = files.filter((file) => beforeUpload(file) && !/\.zip$/i.test(file.name))
  if (!validFiles.length) return
  const relativePaths = validFiles.map((file) => file.webkitRelativePath || file.name)
  const firstPath = String(relativePaths[0]).replaceAll('\\\\', '/')
  const packageName = firstPath.split('/')[0] || validFiles[0].name
  await openPackageImport({ kind: 'folder', files: validFiles, relativePaths, packageName })
}

async function readDroppedEntries (event) {
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

async function onMaterialDrop (event) {
  const entries = await readDroppedEntries(event)
  const validEntries = entries.filter(({ file }) => beforeUpload(file))
  if (!validEntries.length) return
  const archives = validEntries.filter(({ file }) => /\.zip$/i.test(file.name))
  const media = validEntries.filter(({ file }) => !/\.zip$/i.test(file.name))
  if (archives.length) {
    await openPackageImport({ kind: 'archive', files: [archives[0].file], packageName: archives[0].file.name.replace(/\.zip$/i, '') })
    return
  }
  const relativePaths = media.map(({ relativePath, file }) => relativePath || file.webkitRelativePath || file.name)
  const folderDrop = relativePaths.some((path) => String(path).includes('/'))
  if (folderDrop) {
    const packageName = String(relativePaths[0]).replaceAll('\\\\', '/').split('/')[0] || media[0].file.name
    await openPackageImport({ kind: 'folder', files: media.map(({ file }) => file), relativePaths, packageName })
    return
  }
  await uploadEntries(media.map(({ file }) => file))
}

async function retryUpload (item) {
  if (!item.file || item.status === 'uploading') return
  await uploadOneItem(item.file, item)
  await load()
}

async function uploadOneItem (file, item) {
  item.status = 'uploading'
  item.percentage = 0
  item.message = '上传中'
  try {
    const result = await uploadFile(file, { role: uploadData.role, folderId: uploadData.folderId }, (percentage) => {
      item.percentage = percentage
      item.message = `上传中 ${percentage}%`
    })
    item.percentage = 100
    item.status = result?.status === 'processing' ? 'processing' : 'success'
    item.message = result?.status === 'processing' ? '文件已接收，正在用本机 FFprobe 检测容器和音视频流' : '已保存并完成媒体探测'
  } catch (error) {
    item.status = 'exception'
    item.message = error.message || '上传失败，可重试'
  }
}

async function uploadFromDrop (options) {
  const file = options.file
  if (!beforeUpload(file)) {
    options.onError(new Error('文件未进入上传队列，请按提示选择可读取的本地媒体文件。'))
    return
  }
  try {
    const result = await uploadFile(file, { role: uploadData.role, folderId: uploadData.folderId }, (percentage) => {
      options.onProgress({ percent: percentage })
    })
    options.onSuccess({ ok: true, data: result })
  } catch (error) {
    options.onError(error)
  }
}

function findUpload(file) {
  let item = uploadItems.value.find((x) => x.uid === file.uid)
  if (!item) {
    item = { uid: file.uid, name: file.name, percentage: 0, status: 'uploading', message: '准备上传' }
    uploadItems.value.push(item)
  }
  return item
}
function onUploadProgress(event, file) {
  const item = findUpload(file)
  item.percentage = Math.round(event.percent || 0)
  item.message = `上传中 ${item.percentage}%`
}
function onUploadSuccess(response, file) {
  const item = findUpload(file)
  item.percentage = 100
  item.status = response?.ok === false ? 'exception' : 'success'
  const material = response?.data
  item.message = item.status === 'success' ? (material?.status === 'processing' ? '文件已接收，正在用本机 FFprobe 检测容器和音视频流' : '已保存并完成媒体探测') : (response?.message || '服务端处理失败')
  if (item.status === 'success') load()
}
function onUploadError(error, file) {
  const item = findUpload(file)
  item.status = 'exception'
  item.message = error?.message || '上传失败；请确认文件已完整保存到本机后重试'
}
function removeUpload(file) {
  uploadItems.value = uploadItems.value.filter((x) => x.uid !== file.uid)
}
function syncGroupSelection(groupItems, groupSelection) {
  const groupIds = new Set(groupItems.map((item) => item.id))
  selected.value = [
    ...selected.value.filter((item) => !groupIds.has(item.id)),
    ...(groupSelection || [])
  ]
}
function selectAllVisible() {
  selected.value = [...list.value]
  expandedFolderGroups.value = materialGroups.value.map((group) => group.key)
}
function clearSelection() {
  selected.value = []
}
function sendToStudio () {
  if (!selected.value.length) return ElMessage.warning('请先勾选至少一条素材')
  localStorage.setItem('mework-selected-material-ids', JSON.stringify(selected.value.map((row) => row.id)))
  router.push('/studio')
}

async function doBatchIndex (force) {
  if (!selected.value.length) return ElMessage.warning('请先勾选至少一条素材')
  batchIndexing.value = true
  try {
    const result = await api.batchIndexMaterials({ materialIds: selected.value.map((row) => row.id), force, limit: 48 })
    ElMessage.success(`索引请求已处理：排队 ${result.queued || 0} 条，复用 ${result.reused || 0} 条，跳过 ${result.skipped || 0} 条`)
  } catch (error) {
    ElMessage.error(`批量索引失败：${error.message || '请稍后重试'}`)
  } finally {
    batchIndexing.value = false
  }
}

async function doAutoSlice () {
  const videos = selected.value.filter((row) => row.fileType === 'video')
  if (!videos.length) return ElMessage.warning('请先勾选至少一条视频素材')
  slicing.value = true
  try {
    let created = 0
    const failures = []
    for (const video of videos) {
      try {
        const clips = await api.splitMaterial(video.id, { clipSec: sliceSec.value })
        created += clips.length
      } catch (error) {
        failures.push(video.name)
      }
    }
    await load()
    if (created) ElMessage.success(`自动切片完成，已新增 ${created} 段素材`)
    if (failures.length) ElMessage.warning(`${failures.length} 条视频切片失败：${failures.join('、')}`)
  } finally {
    slicing.value = false
  }
}

async function saveRole(row, v) {
  await api.updateMaterial(row.id, { role: v })
  ElMessage.success('已更新')
}

async function doBatchRole() {
  if (!batchRoleVal.value) return ElMessage.warning('请选择角色')
  const n = await api.batchRole({ ids: selected.value.map((x) => x.id), role: batchRoleVal.value })
  ElMessage.success(`已更新 ${n} 条`)
  load()
}

async function doBatchDelete () {
  if (!selected.value.length) return
  try {
    const deleted = await api.batchDeleteMaterials({ ids: selected.value.map((row) => row.id) })
    ElMessage.success(`已删除 ${deleted} 条素材记录，本地原始文件未删除`)
    clearSelection()
    await load()
  } catch (error) {
    ElMessage.error(`批量删除失败：${error.message || '请刷新后重试'}`)
  }
}

async function doDelete(row) {
  await api.deleteMaterial(row.id)
  load()
}

async function reprobe(row) {
  try {
    await api.reprobeMaterial(row.id)
    ElMessage.success(`${row.name} 已重新探测`)
    await load()
  } catch (error) {
    ElMessage.error(`${row.name} 重新探测失败：${error.message || '请检查文件是否仍存在且可读取'}`)
  }
}

async function retryThumbnail(row) {
  try {
    await api.retryThumbnail(row.id)
    ElMessage.success(`${row.name} 已重试生成缩略图`)
    await load()
  } catch (error) {
    ElMessage.error(`${row.name} 缩略图生成失败：${error.message || '请检查图片/视频是否损坏'}`)
  }
}

async function doPurge() {
  const n = await api.purgeMissing()
  ElMessage.success(`清理了 ${n} 条文件已不存在的记录`)
  load()
}

function openEdit(row) {
  editing.value = { ...row }
  editVisible.value = true
}

async function saveEdit() {
  await api.updateMaterial(editing.value.id, {
    name: editing.value.name,
    role: editing.value.role,
    tags: editing.value.tags,
    muteOriginalAudio: editing.value.muteOriginalAudio,
    transcribeForSubtitles: editing.value.transcribeForSubtitles
  })
  editVisible.value = false
  ElMessage.success('已保存')
  load()
}

onMounted(async () => {
  if (route.query.folderId) q.folderId = String(route.query.folderId)
  await Promise.all([load(), loadAudioEngineStatus(), loadFolders().catch(() => { folders.value = [] }), api.projects().then((rows) => { projects.value = rows || [] }).catch(() => { projects.value = [] })])
  await openMaterialFromRoute()
  window.addEventListener('mework-global-upload-complete', load)
})
onBeforeUnmount(() => {
  stopAnalysisPolling()
  window.removeEventListener('mework-global-upload-complete', load)
})
</script>

<style scoped>
.material-dropzone { display:block; width:100%; margin:10px 0 12px; }
.material-dropzone :deep(.el-upload), .material-dropzone :deep(.el-upload-dragger) { display:block; width:100%; }
.material-dropzone :deep(.el-upload-dragger) { min-height:112px; padding:22px 18px; border-color:#91caff; background:#f7fbff; }
.material-dropzone :deep(.el-upload__text) { font-size:15px; color:#303133; }
.material-dropzone :deep(.el-upload__tip) { margin-top:8px; color:#606266; }
.native-folder-input { position:absolute; width:1px; height:1px; opacity:0; pointer-events:none; }
.selection-toolbar { display:flex; align-items:center; gap:8px; padding:8px 10px; margin:-4px 0 10px; background:#f5f9ff; border:1px solid #dbeafe; border-radius:6px; }
.material-folder-groups { border-top:1px solid #ebeef5; }
.material-group-count { margin-left:12px; font-size:12px; }
.folder-media-type { margin:8px 0; padding:8px; background:#fafafa; border-radius:4px; }
.folder-media-type .muted { margin-left:8px; }
.folder-media-items { margin-top:5px; color:#606266; font-size:12px; line-height:1.7; word-break:break-word; }
.tts-ai-actions { display:flex; gap:8px; align-items:center; flex-wrap:wrap; margin-bottom:8px; }
.upload-status-list { margin:10px 0; padding:10px; background:#fafafa; border-radius:6px; }
.upload-status-row { display:grid; grid-template-columns:220px minmax(180px,1fr) 260px; gap:10px; align-items:center; padding:5px 0; }
 .upload-name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
 .material-preview { width:100%; max-width:100%; min-width:0; overflow:hidden; box-sizing:border-box; }
 .material-preview img, .material-preview video { display:block; width:auto; max-width:100%; max-height:calc(100vh - 190px); height:auto; object-fit:contain; margin:0 auto; }
 .material-preview audio { display:block; width:100%; max-width:100%; }
 .material-preview-dialog :deep(.el-dialog__body) { max-width:100%; max-height:calc(100vh - 140px); overflow:auto; box-sizing:border-box; }
.ocr-text { margin-top:6px; line-height:1.7; word-break:break-word; }
 .diagnosis-issues { margin:6px 0 0; padding-left:20px; color:#b54708; line-height:1.7; }
</style>

<style>
/* Element Plus teleports dialogs outside this component, so preview sizing must be global. */
.material-preview {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  overflow: hidden;
  box-sizing: border-box;
}
.material-preview img,
.material-preview video {
  display: block;
  width: auto !important;
  max-width: 100% !important;
  max-height: calc(100vh - 220px) !important;
  height: auto !important;
  object-fit: contain;
  margin: 0 auto;
}
.material-preview audio {
  display: block;
  width: 100%;
  max-width: 100%;
}
.el-dialog__body:has(.material-preview) {
  max-width: 100%;
  max-height: calc(100vh - 150px);
  overflow: auto;
  box-sizing: border-box;
}
</style>
