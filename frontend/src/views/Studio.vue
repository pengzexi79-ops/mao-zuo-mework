<template>
  <div>
    <section class="studio-workbench-head">
      <div class="studio-workbench-title"><div><h2>出片工作台</h2><p>从项目、模板和已入库素材开始；预检只阻止不安全的渲染，并提供补料入口。</p></div><div class="studio-system-status"><el-tag size="small" :type="initialError || runtimeUnavailable ? 'danger' : 'success'">{{ initialError || runtimeUnavailable ? '系统需处理' : '系统就绪' }}</el-tag><span v-if="queue">渲染 {{ queue.active }} · 等待 {{ queue.pendingJobs }}</span><el-button size="small" plain @click="router.push({ path: '/capabilities', query: { view: 'environment' } })">查看系统状态</el-button></div></div>
      <div class="studio-quick-actions">
        <button type="button" class="studio-action" @click="router.push('/ai-create')"><b>AI 创作</b><span>生成图片、视频或配音后自动入素材库</span></button>
        <button type="button" class="studio-action" @click="router.push('/fixed-order-presets')"><b>固定顺序</b><span>{{ p.strictFolderSequence ? `${p.folderReadSteps.length} 个步骤已载入` : '选择模板并绑定文件夹' }}</span></button>
        <button type="button" class="studio-action" @click="router.push('/materials')"><b>素材库</b><span>{{ selectedVisuals.length }} 条当前范围画面 · {{ usableVisualSeconds.toFixed(0) }} 秒</span></button>
      </div>
    </section>
    <div class="studio-layout">
    <!-- 左：参数 -->
    <div class="studio-left" v-loading="initialLoading">
      <div class="card autonomy-card">
        <div class="card-title"><span>生产策略</span><span class="hint">当前：{{ AUTONOMY_MODES.find(mode => mode.key === autonomyMode)?.label || '辅助模式' }}</span><span style="flex:1"></span><el-button link size="small" @click="strategyOpen = !strategyOpen">{{ strategyOpen ? '收起' : '调整策略' }}</el-button></div>
        <div v-if="!strategyOpen" class="strategy-summary">系统会先检查素材和时间线，再由你确认开始出片。需要自动补料或自主修复时，可点击“调整策略”。</div>
        <div v-show="strategyOpen" class="autonomy-modes">
          <div v-for="mode in AUTONOMY_MODES" :key="mode.key" class="autonomy-mode"
            :class="{ active: autonomyMode === mode.key }" @click="autonomyMode = mode.key">
            <div class="autonomy-mode-head">
              <span class="autonomy-mode-radio" :class="{ checked: autonomyMode === mode.key }"></span>
              <b>{{ mode.label }}</b>
              <el-tag v-if="mode.key === 'autonomous'" size="small" type="warning" effect="plain">默认严格交付</el-tag>
            </div>
            <div class="autonomy-mode-desc">{{ mode.desc }}</div>
          </div>
        </div>
        <div v-show="strategyOpen" class="autonomy-toggles">
          <div class="autonomy-toggle-row">
            <el-switch v-model="publicAutoFill" size="small" />
            <div class="autonomy-toggle-text">
              <b>公开素材自动补齐</b>
              <div class="form-hint">仅使用许可可验证的免登录公开源（Wikimedia / Archive）；高质量Pexels/Pixabay 视频需在能力中心配置官方Key。关闭后只用已有本地素材</div>
            </div>
          </div>
          <div class="autonomy-toggle-row">
            <el-tag size="small" type="success" effect="plain" style="flex:0 0 auto">内置启用</el-tag>
            <div class="autonomy-toggle-text">
              <b>失败自动修复</b>
              <div class="form-hint">成品质检失败时按安全策略自动修复（替换背景音乐 / 切换钩子 / 回退原声等），仅使用已安装的本机能力，无需安装任何组件</div>
            </div>
          </div>
          <div v-if="autonomyMode === 'autonomous'" class="autonomy-toggle-row">
            <el-switch v-model="p.strictDelivery" size="small" />
            <div class="autonomy-toggle-text">
              <b>严格交付（自主模式默认开启）</b>
              <div class="form-hint">钩子 / 重复 / 字幕同步等质检从提示升级为硬性拦截，不达标的成片不会通过；关闭后与半自动模式保持一致</div>
            </div>
          </div>
        </div>
        <div v-show="strategyOpen" class="autonomy-boundary">自主模式只使用：已有本地/项目素材、固定免登录公开源、已安装能力与渲染/质检/修复；绝不进行支付、第三方登录、验证码/DRM 绕过或系统设置修改。首次提交半自动/自主模式前会展示完整权限说明</div>
      </div>
      <el-alert v-if="initialError" type="error" :closable="false" show-icon style="margin-bottom:14px"
        title="部分控制台数据加载失败；已加载的内容仍可使用" >
        <el-button size="small" type="primary" plain @click="loadInitial">重新加载</el-button>
      </el-alert>
      <div class="card">
        <div class="card-title">1. 选项目和工作</div>
        <el-form label-width="80px">
          <el-form-item label="项目">
            <el-select v-model="projectId" clearable style="width:100%" @change="onProjectChange"
              placeholder="不选也能跑，但 AI 文案会很差">
              <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="工作">
            <el-select v-model="workflowId" clearable style="width:100%" placeholder="不选则使用当前表单参数" @change="onWorkflowChange">
              <el-option v-for="w in workflows" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
            <div class="form-hint">选择项目或工作流只会载入它提供的默认参数；你随后修改的出片参数会作为本次出片的最终值</div>
            <el-button v-if="workflowId" link type="warning" size="small" @click="clearWorkflow">撤销当前工作</el-button>
            <el-button link type="danger" size="small" @click="resetDraft">重置出片草稿</el-button>
          </el-form-item>
        </el-form>
      </div>

      <div class="card">
        <div class="card-title">
          2. 出片参数
          <span class="hint">可从项目、工作流或行业预置载入后继续修改</span>
        </div>
        <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:0 0 12px">
          <span class="muted" style="font-size:12px">行业预设</span>
          <el-button size="small" :type="activeIndustryPreset === 'beauty' ? 'primary' : 'default'" :plain="activeIndustryPreset === 'beauty'" @click="applyPreset('beauty')" @dblclick="clearIndustryPreset">美妆</el-button>
          <el-button size="small" :type="activeIndustryPreset === 'skincare' ? 'primary' : 'default'" :plain="activeIndustryPreset === 'skincare'" @click="applyPreset('skincare')" @dblclick="clearIndustryPreset">护肤</el-button>
          <el-button size="small" :type="activeIndustryPreset === 'food' ? 'primary' : 'default'" :plain="activeIndustryPreset === 'food'" @click="applyPreset('food')" @dblclick="clearIndustryPreset">食品</el-button>
          <el-button size="small" :type="activeIndustryPreset === 'maternal' ? 'primary' : 'default'" :plain="activeIndustryPreset === 'maternal'" @click="applyPreset('maternal')" @dblclick="clearIndustryPreset">母婴</el-button>
          <el-button size="small" :type="activeIndustryPreset === 'digital' ? 'primary' : 'default'" :plain="activeIndustryPreset === 'digital'" @click="applyPreset('digital')" @dblclick="clearIndustryPreset">3C 数码</el-button>
          <el-button size="small" :type="activeIndustryPreset === 'longmix' ? 'primary' : 'default'" :plain="activeIndustryPreset === 'longmix'" @click="applyLongMixPreset" @dblclick="clearIndustryPreset">专业长混剪 120–150s</el-button>
          <el-button v-if="activeIndustryPreset" link type="warning" size="small" @click="clearIndustryPreset">取消当前预设</el-button>
          <span class="form-hint" style="flex:0 0 100%;margin:0">预设会优先按文件夹、背景音乐和口播关键词选素材；再次点击或双击已选预设可取消并恢复之前参数</span>
        </div>
        <el-form label-width="96px" size="small">
          <el-alert type="info" :closable="false" show-icon class="duration-guide"
            title="先设时长，再开始出片">
            当前有效区间 <b>{{ p.minSec }}–{{ p.maxSec }} </b>；总时长必须落在这个区间内。默认推荐约 100 秒，最长支持 300 秒。
          </el-alert>
          <el-form-item label="时长区间" class="duration-range-item">
            <el-slider v-model="durRange" range :min="5" :max="300"
              :marks="{ 50: '50s', 100: '100s', 150: '150s', 300: '300s' }" />
            <div class="form-hint">拖动两端设置最小/最长时长；范围越窄，批量成片越稳定</div>
          </el-form-item>
          <div class="material-summary">当前可用<b>{{ selectedVisuals.length }}</b> 条画面，<b>{{ usableVisualSeconds.toFixed(1) }} </b>。这里是素材容量估算，不等于可直接交付；开始批量前请先完成右侧干跑预览</div>
          <el-form-item label="钩子声音">
            <el-switch v-model="p.autoMatchAudio" active-text="自动匹配" inactive-text="手动选择" />
            <el-select v-if="!p.autoMatchAudio" v-model="p.hookAudioMaterialId" clearable filterable style="width:210px;margin-left:8px" placeholder="选择人声或短音频">
              <el-option v-for="m in voiceList" :key="m.id" :label="m.name" :value="m.id" />
            </el-select>
            <span class="form-hint">钩子声音只在钩子画面时间窗播放；未匹配时不会打断 BGM</span>
          </el-form-item>
          <el-form-item label="密集模式">
            <el-switch v-model="p.dense" />
            <span class="muted" style="margin-left:8px">100 秒收缩</span>
          </el-form-item>
          <el-form-item label="总时长" class="target-duration-item">
            <el-input-number v-model="p.targetDurationSec" :min="p.minSec" :max="p.maxSec" :step="1" controls-position="right" />
            <span class="duration-unit"></span>
            <el-button v-if="p.targetDurationSec" link type="primary" size="small" @click="p.targetDurationSec = null">按区间自动生成</el-button>
            <div class="form-hint">这是最容易调整的主控项：输入 {{ p.minSec }}–{{ p.maxSec }} 秒；不填则按区间自动生成</div>
          </el-form-item>
          <el-alert v-if="durationWarning" type="warning" :closable="false" show-icon class="duration-warning" :title="durationWarning">
            <el-button size="small" type="warning" plain @click="normalizeDuration">改为 {{ p.targetDurationSec < p.minSec ? p.minSec : p.maxSec }} </el-button>
          </el-alert>
          <el-form-item label="片段分配">
            <el-radio-group v-model="p.durationAllocationMode" size="small">
              <el-radio-button label="equal">均等</el-radio-button>
              <el-radio-button label="random">随机</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="自定义片头">
            <el-switch v-model="p.introEnabled" />
            <template v-if="p.introEnabled">
              <el-radio-group v-model="p.introMode" size="small" style="margin-left:8px">
                <el-radio-button label="fixed">固定片头</el-radio-button>
                <el-radio-button label="rotate">批量轮换</el-radio-button>
              </el-radio-group>
              <el-select v-if="p.introMode === 'fixed'" v-model="p.introMaterialId" clearable filterable style="width:230px;margin-left:8px" placeholder="选择本地视频或图片素材">
                <el-option v-for="m in introCandidates" :key="m.id" :label="`${m.name} · ${m.durationSec ? m.durationSec.toFixed(1) + 's' : '图片'}`" :value="m.id" />
              </el-select>
              <el-select v-else v-model="p.introMaterialId" clearable filterable style="width:230px;margin-left:8px" placeholder="可选：优先用作第一条片头">
                <el-option v-for="m in introCandidates" :key="m.id" :label="`${m.name} · ${m.durationSec ? m.durationSec.toFixed(1) + 's' : '图片'}`" :value="m.id" />
              </el-select>
              <el-checkbox v-if="p.introMode === 'rotate'" v-model="p.introNoRepeat" style="margin-left:8px">本批次片头不重复</el-checkbox>
              <el-checkbox v-if="p.introMode === 'rotate' && p.introNoRepeat" v-model="p.introAllowRepeatWhenInsufficient" style="margin-left:8px">候选不足时允许重复</el-checkbox>
              <span class="form-hint" style="flex-basis:100%">{{ introHint }}</span>
            </template>
          </el-form-item>
          <el-form-item label="授权文件" v-if="!p.strictFolderSequence">
            <el-select v-model="p.folderIds" multiple clearable filterable collapse-tags style="width:100%" placeholder="不选择则使用全部已归档素材"><el-option v-for="folder in folders.filter((item) => item.enabled !== false)" :key="folder.id" :label="folder.name" :value="folder.id" /></el-select>
            <span class="form-hint">选择后，干跑和出片只会使用这些文件夹中的素材</span>
          </el-form-item>
          <el-form-item label="营销结构">
            <el-select v-model="p.marketingStructure" style="width:220px">
              <el-option label="1234 · 钩子/讲解/展示/成交" value="1234" />
              <el-option label="12234 · 双讲解强化卖点" value="12234" />
              <el-option label="123234 · 展示后再解释再成交" value="123234" />
            </el-select>
            <span class="form-hint">1 钩子，2 讲解/痛点，3 产品展示/证明，4 成交收口；干跑会显示结构预览</span>
          </el-form-item>
          <el-form-item label="产片固定顺序">
            <el-switch v-model="p.strictFolderSequence" active-text="按当前顺序读" inactive-text="普通混" @change="onStrictFolderSequenceChange" />
            <div class="fixed-order-entry">
              <el-select v-model="fixedOrderSkillKey" size="small" clearable filterable style="width:100%" placeholder="从内置预设开始" @change="applyFixedOrderPreset">
                <el-option v-for="preset in FIXED_ORDER_PRESETS" :key="preset.key" :label="preset.name" :value="preset.key">
                  <div class="fixed-order-option"><b>{{ preset.name }}</b><el-tag size="small" effect="plain">{{ preset.stages.length }} 步</el-tag><span>{{ preset.description }}</span></div>
                </el-option>
              </el-select>
              <div class="fixed-order-actions">
                <el-button size="small" type="primary" plain @click="openFixedOrderDraft">新建顺序草稿</el-button>
                <el-button link type="primary" size="small" @click="router.push('/fixed-order-presets')">查看全部预置</el-button>
              </div>
            </div>
            <span class="form-hint">内置预设和新建草稿都会变成可编辑的当前顺序；任务提交后会锁定本次步骤和文件夹。</span>
          </el-form-item>
          <div v-if="p.strictFolderSequence" class="folder-stage-list">
            <div class="fixed-order-overview">
              <div><span>当前顺序</span><b>{{ fixedOrderSourceLabel }}</b></div>
              <div><span>步骤</span><b>{{ fixedOrderEnabledStepCount }} / {{ p.folderReadSteps.length }}</b></div>
              <div><span>已绑定</span><b>{{ fixedOrderBoundCount }} / {{ fixedOrderRequiredStepCount }}</b></div>
              <div :class="{ warning: fixedOrderRequiredUnboundCount }"><span>必填待绑定</span><b>{{ fixedOrderRequiredUnboundCount }}</b></div>
              <div><span>目标时长</span><b>{{ fixedOrderTargetSeconds }} 秒</b></div>
            </div>
            <div class="folder-stage-toolbar">
              <span class="muted">每一步只读取自己绑定的应用内文件夹；未绑定的必填步骤会在干跑和提交前阻断。</span>
              <div><el-button size="small" plain @click="fixedOrderOpen = !fixedOrderOpen">{{ fixedOrderOpen ? '收起步骤' : '编辑步骤' }}</el-button><el-button size="small" type="primary" plain @click="addFolderReadStep">＋ 添加步骤</el-button></div>
            </div>
            <div v-if="!fixedOrderOpen" class="fixed-order-summary"><span v-for="step in p.folderReadSteps" :key="step.order"><b>{{ step.order }}</b>{{ step.name }}<i :class="{ ready: !!step.folderId, warning: step.required !== false && !step.folderId }">{{ step.folderId ? '已绑定' : (step.required !== false ? '待绑定' : '可跳过') }}</i></span></div>
            <div v-show="fixedOrderOpen" v-for="(step, index) in visibleFolderReadSteps" :key="step.order" class="folder-stage-row">
              <el-tag size="small" type="primary">{{ step.order }}</el-tag>
              <el-input v-model="step.name" maxlength="80" style="width:132px" />
              <el-select v-model="step.folderId" clearable filterable style="width:170px" placeholder="选择文件"><el-option v-for="folder in folders.filter((item) => item.enabled !== false)" :key="folder.id" :label="folder.name" :value="folder.id" /></el-select>
              <el-button v-if="step.folderId" link type="warning" size="small" @click="clearStepFolder(step)">取消绑定</el-button>
              <el-input-number v-model="step.targetSec" :min="1" :max="300" :step="1" style="width:106px" />
              <span class="muted"></span>
              <el-switch v-model="step.enabled" active-text="启用" inactive-text="停用" />
              <el-switch v-model="step.required" active-text="必填" inactive-text="可跳" />
              <el-select v-model="step.shortagePolicy" style="width:118px"><el-option label="不足即阻" value="block" /><el-option label="本步骤备" value="fallback" /></el-select>
              <el-select v-if="step.shortagePolicy === 'fallback'" v-model="step.fallbackFolderId" clearable filterable style="width:150px" placeholder="备用文件"><el-option v-for="folder in folders.filter((item) => item.enabled !== false && item.id !== step.folderId)" :key="folder.id" :label="folder.name" :value="folder.id" /></el-select>
              <el-button link size="small" title="复制此读取步骤（不复制文件夹绑定）" @click="copyFolderReadStep(index)">复制</el-button>
              <el-button circle text type="danger" title="删除此读取步骤" @click="removeFolderReadStep(index)"></el-button>
            </div>
            <div v-if="p.folderReadSteps.length > 6" class="form-hint">已载入 {{ p.folderReadSteps.length }} 个步骤；每一步都按自己的主/备用文件夹读取，不会缩减或跨步骤补位。</div>
            <div class="form-hint">启用且必填的步骤必须配置文件夹；停用或可跳过步骤不会阻断。任务创建时会锁定本次顺序、文件夹和参数。</div>
          </div>
          <el-form-item label="指定素材" v-if="!p.strictFolderSequence">
            <el-tag type="primary">已指定 {{ p.materialIds?.length || 0 }} </el-tag>
            <el-button link type="danger" :disabled="!(p.materialIds?.length)" @click="p.materialIds = []">清空指定</el-button>
            <span class="form-hint">从素材库勾选“送去出片”后带入；与授权文件夹同选时使用并集</span>
            <div v-if="p.materialIds?.length" class="selected-materials">
              <el-tag v-for="material in selectedVisuals" :key="material.id" closable size="small" @close="p.materialIds = p.materialIds.filter((id) => Number(id) !== Number(material.id))">{{ material.name }}</el-tag>
              <span v-if="!selectedVisuals.length" class="muted">指定素材不可读取或已被移除，请回素材库重新选择</span>
            </div>
          </el-form-item>
          <el-form-item label="素材规则">
            <el-radio-group v-model="p.materialSelectionMode" size="small"><el-radio-button label="rules-only">仅人工分</el-radio-button><el-radio-button label="cross-folder">跨文件夹组合</el-radio-button></el-radio-group>
          </el-form-item>
          <el-form-item label="项目相关">
            <el-switch v-model="p.projectRelevantOnly" active-text="只用相关素材" inactive-text="允许全部素材" />
            <span class="form-hint">开启后会按项目品类、卖点、禁用词和素材标签排除明显无关画面；人工指定素材优先保留</span>
          </el-form-item>
          <el-form-item label="兼容旧配置" class="legacy-duration-item">
            <el-input-number v-model="p.targetSec" :min="p.minSec" :max="p.maxSec" placeholder="留空" />
            <span class="muted" style="margin-left:6px">仅用于读取旧项目；新任务请使用上方“总时长”</span>
          </el-form-item>
          <el-divider content-position="left"><span style="font-size:12px">常用质量参数</span></el-divider>
          <el-form-item label="单片时长">
            <el-input-number v-model="p.sliceSec" :min="1" :max="10" :step="0.5" />
            <span class="muted" style="margin-left:6px">秒；2~3 秒节奏快，1~2 秒更稳</span>
          </el-form-item>
          <el-form-item label="片段范围">
            <el-input-number v-model="p.minSegmentSec" :min="0.8" :max="15" :step="0.1" />
            <span class="muted" style="margin:0 6px"></span>
            <el-input-number v-model="p.maxSegmentSec" :min="p.minSegmentSec" :max="15" :step="0.1" />
            <span class="muted" style="margin-left:6px">秒；范围越窄越稳</span>
          </el-form-item>
          <el-form-item label="长素材拆分">
            <el-switch v-model="p.explodeLongClips" />
            <span class="muted" style="margin-left:8px">开启可提高素材利用率，但重复风险会上升</span>
          </el-form-item>
          <el-form-item label="同素材上限">
            <el-input-number v-model="p.maxSlicesPerMaterial" :min="1" :max="10" />
            <span class="muted" style="margin-left:6px">片；建议 3，太高容易画面重</span>
          </el-form-item>
          <el-form-item label="去重严格度">
            <el-radio-group v-model="p.dedupStrictness" size="small">
              <el-radio-button label="standard">标准（禁完全重复）</el-radio-button>
              <el-radio-button label="strict">严格（同源不重叠）</el-radio-button>
              <el-radio-button label="off">关闭</el-radio-button>
            </el-radio-group>
            <div class="form-hint">严格模式会在同一条成片内禁止同一素材源的时间区间重叠；素材源不足时会在干跑日志里给出明确不足报告</div>
          </el-form-item>
          <el-form-item label="同源不重叠">
            <el-switch v-model="p.allowSameSourceNonoverlap" />
            <span class="muted" style="margin-left:8px">允许同一素材拆成多段互不重叠的画面</span>
          </el-form-item>
          <el-form-item label="自动再钩子">
            <el-switch v-model="p.autoRehook" />
            <span class="muted" style="margin-left:8px">在片中段自动补一句再钩子拉住完播</span>
          </el-form-item>
          <el-form-item v-if="p.autoRehook" label="再钩子文案">
            <el-input v-model="p.rehookText" placeholder="留空由AI 按钩子策略自动生成" />
          </el-form-item>
          <el-form-item label="结构控制">
            <el-input-number v-model="p.hookSec" :min="0" :max="10" :step="0.5" />
            <span class="muted" style="margin:0 6px">钩子</span>
            <el-input-number v-model="p.productSlots" :min="0" :max="10" />
            <span class="muted" style="margin:0 6px">产品</span>
            <el-input-number v-model="p.productSec" :min="1" :max="10" :step="0.5" />
            <span class="muted">秒</span>
          </el-form-item>
          <el-form-item label="画布">
            <el-select v-model="canvasKey" style="width:260px">
              <el-option label="竖屏 1080×1920（抖音推荐）" value="1080x1920" />
              <el-option label="方形 1080×1080" value="1080x1080" />
              <el-option label="横屏 1920×1080" value="1920x1080" />
              <el-option label="竖屏 1080×1350" value="1080x1350" />
              <el-option label="竖屏 1080×1440" value="1080x1440" />
            </el-select>
            <span class="muted" style="margin-left:8px">画幅变化会影响裁切和字幕位置</span>
          </el-form-item>
          <el-form-item label="帧率">
            <el-select v-model="p.fps" style="width:140px">
              <el-option :value="24" label="24" /><el-option :value="25" label="25" />
              <el-option :value="30" label="30（推荐）" /><el-option :value="60" label="60（更慢）" />
            </el-select>
          </el-form-item>
          <el-divider content-position="left"><span style="font-size:12px">出片前媒体处理（音频 / 字幕）</span></el-divider>
          <div class="studio-media-note">此区只控制本次批量出片：保留原音、去掉音频、授权字幕、AI 配音字幕和有损字幕遮盖。不会覆盖原素材。</div>
          <div class="studio-audio-choice">
            <span>音频</span>
            <el-radio-group v-model="p.audioMode" size="small" @change="onAudioModeChange">
              <el-radio-button label="original">保留原音</el-radio-button>
              <el-radio-button label="material-audio">使用素材音频</el-radio-button>
              <el-radio-button label="ai-voice">AI 人声</el-radio-button>
              <el-radio-button label="silent">去掉音频</el-radio-button>
            </el-radio-group>
            <el-slider v-if="p.audioMode === 'original'" v-model="originalAudioPct" :min="0" :max="100" :format-tooltip="(v) => v + '%'" />
            <div class="form-hint">选“使用素材音频”可在高级设置选择 BGM/口播；选“AI 人声”可选择音色；静音会移除所有音轨和新字幕。</div>
          </div>
          <div class="studio-cover-card">
            <div class="studio-cover-head"><b>字幕遮盖</b><span class="muted">智能候选需确认；手动遮盖生成新素材</span></div>
            <div class="studio-media-row">
              <el-switch v-model="p.autoSubtitles" active-text="授权素材字幕" inactive-text="关闭授权字幕" />
              <el-switch v-if="p.audioMode === 'ai-voice'" v-model="p.burnAiVoiceCaptions" active-text="AI 配音字幕" inactive-text="关闭 AI 配音字幕" />
              <el-switch v-model="p.cleanSourceSubtitles" active-text="底部安全区遮盖" inactive-text="保留原字幕" @change="onSourceSubtitleCleanChange" />
            </div>
            <el-select v-model="subtitleCoverForm.materialId" clearable filterable teleported popper-class="studio-select-popper" placeholder="选择当前已入库图片或视频" style="width:100%" @change="resetSubtitleCoverArea">
              <el-option v-for="m in visualList.filter(item => ['image', 'video'].includes(item.fileType) && item.status !== 'failed')" :key="m.id" :label="`${m.name} · ${m.fileType}`" :value="m.id" />
            </el-select>
            <div v-if="coverMaterial" ref="coverStage" class="cover-preview-stage" @pointerdown.prevent="startCoverDrag" @pointermove.prevent="moveCoverDrag" @pointerup.prevent="stopCoverDrag" @pointercancel.prevent="stopCoverDrag">
              <img v-if="coverMaterial.fileType === 'image'" class="cover-preview-media" :src="api.materialPreviewUrl(coverMaterial.id)" :alt="coverMaterial.name" />
              <video v-else class="cover-preview-media" :src="api.materialPreviewUrl(coverMaterial.id)" muted preload="metadata"></video>
              <div class="cover-selection" :style="coverSelectionStyle"><span>拖动调整遮盖区域</span></div>
            </div>
            <div v-if="coverMaterial" class="form-hint">直接在预览上拖出字幕区域。坐标会按原始媒体分辨率换算；默认定位在画面底部安全区。</div>
            <div class="studio-cover-grid">
              <el-input-number v-model="subtitleCoverForm.x" :min="0" :max="10000" placeholder="左" />
              <el-input-number v-model="subtitleCoverForm.y" :min="0" :max="10000" placeholder="上" />
              <el-input-number v-model="subtitleCoverForm.width" :min="1" :max="10000" placeholder="宽" />
              <el-input-number v-model="subtitleCoverForm.height" :min="1" :max="10000" placeholder="高" />
            </div>
            <div v-if="subtitleCoverForm.materialId && visualList.find(item => item.id === subtitleCoverForm.materialId)?.fileType === 'video'" class="studio-cover-grid">
              <el-input-number v-model="subtitleCoverForm.start" :min="0" :step="0.1" placeholder="开始秒" />
              <el-input-number v-model="subtitleCoverForm.end" :min="0.1" :step="0.1" placeholder="结束秒" />
            </div>
            <div class="studio-media-row">
              <el-button size="small" plain :loading="subtitleAnalysis?.status === 'running'" :disabled="!subtitleCoverForm.materialId" @click="analyzeSubtitleCandidate">智能分析候选</el-button>
              <el-button size="small" type="primary" :loading="subtitleCoverSubmitting" :disabled="!subtitleCoverForm.materialId" @click="submitSubtitleCover">确认区域并生成新素材</el-button>
            </div>
            <div class="form-hint">智能分析只提供候选文本/诊断，当前没有可靠的字幕坐标跟踪；没有人工确认区域时不会自动遮盖。</div>
            <el-alert v-if="subtitleAnalysis" :type="subtitleAnalysis.status === 'failed' ? 'warning' : 'info'" :closable="false" :title="subtitleAnalysis.message || '候选分析已提交，请确认区域后处理'" />
          </div>
          <el-form-item><el-button plain size="small" @click="advancedOpen = !advancedOpen">{{ advancedOpen ? '收起高级设置' : '展开高级设置（片头、随机种子、音频细调、字幕）' }}</el-button></el-form-item>

          <div v-show="advancedOpen">
          <el-divider content-position="left"><span style="font-size:12px">高级拆条与结构</span></el-divider>
          <el-form-item label="片头时长" v-if="p.introEnabled">
            <el-input-number v-model="p.introDurationSec" :min="0.8" :max="15" :step="0.5" />
            <span class="muted" style="margin-left:6px">秒；片头会占用总时长</span>
          </el-form-item>
          <el-form-item label="随机种子">
            <el-input-number v-model="p.seed" :min="0" :max="2147483647" controls-position="right" placeholder="自动" />
            <el-button v-if="p.seed != null" link type="primary" size="small" @click="p.seed = null">恢复自动</el-button>
            <div class="form-hint">同一项目和种子会尽量复现相同取片；留空让每次自然变化</div>
          </el-form-item>
          <el-form-item label="时长抖动">
            <el-input-number v-model="p.sliceJitter" :min="0" :max="2" :step="0.1" />
            <div class="form-hint">±随机；建议 0.2~0.5 秒，过大会降低时长稳定</div>
          </el-form-item>
          <el-form-item label="明星占比">
            <el-slider v-model="celebPct" :min="0" :max="80" :format-tooltip="(v) => v + '%'" />
            <div class="form-hint">只在素材库有明星/达人角色时生效</div>
          </el-form-item>
          <el-form-item label="片尾卡">
            <el-switch v-model="p.endcard" />
            <el-input-number v-if="p.endcard" v-model="p.endcardSec" :min="0.8" :max="15" :step="0.5" style="width:110px;margin-left:8px" />
            <span class="muted" style="margin-left:8px">秒；用于产品、品牌、优惠或行动引导的收尾画面</span>
            <div v-if="p.endcard" class="form-hint">可提供产品图、包装图、品牌 Logo、优惠/二维码图或 3–5 秒收尾视频。未提供独立片尾卡时，系统优先使用产品图，再回退到合格主体镜头，不会默认阻断出片。</div>
            <el-checkbox v-if="p.endcard" v-model="p.requireDedicatedEndcard" style="margin-top:6px">必须使用独立片尾卡</el-checkbox>
          </el-form-item>
          <el-form-item v-if="p.audioMode === 'ai-voice'" label="AI 人声音色">
            <el-select v-model="p.ttsVoice" teleported popper-class="studio-select-popper" style="width:100%"><el-option label="晓晓（女声）" value="zh-CN-XiaoxiaoNeural" /><el-option label="晓伊（女声）" value="zh-CN-XiaoyiNeural" /><el-option label="云希（男声）" value="zh-CN-YunxiNeural" /><el-option label="云健（男声）" value="zh-CN-YunjianNeural" /></el-select>
          </el-form-item>
          <el-form-item label="背景音乐">
            <el-select v-model="p.bgmMaterialId" clearable filterable teleported popper-class="studio-select-popper" style="width:100%"
              placeholder="留空时从背景音乐角色中随机选择">
              <el-option v-for="m in bgmList" :key="m.id" :label="m.name" :value="m.id" />
            </el-select>
            <div class="form-hint">可直接选择任意可读音频作为循环背景声；旧素材即使标为“人声口播”也无需重新导入</div>
          </el-form-item>
          <el-form-item label="背景音乐音量">
            <el-slider v-model="bgmPct" :min="0" :max="100" :format-tooltip="(v) => v + '%'" />
          </el-form-item>
          <el-form-item label="钩子声音音量">
            <el-slider v-model="hookAudioPct" :min="0" :max="100" :format-tooltip="(v) => v + '%'" />
            <span class="muted" style="margin-left:8px">只影响钩子时间窗</span>
          </el-form-item>
          <el-form-item label="口播人声">
            <el-select v-model="p.voiceMaterialId" clearable filterable teleported popper-class="studio-select-popper" style="width:100%"
              placeholder="留空=按当前模式处" @change="onVoiceMaterialChange">
              <el-option v-for="m in voiceList" :key="m.id" :label="m.name" :value="m.id" />
            </el-select>
            <div v-if="p.voiceMaterialId" class="form-hint">已锁定指定口播；提交时不会生成AI 语音，也不会自动选第二条人声</div>
          </el-form-item>

          <el-divider content-position="left"><span style="font-size:12px">钩子文案</span></el-divider>
          <el-form-item label="智能生成">
            <el-switch v-model="p.aiHook" />
            <span class="muted" style="margin-left:8px">批量时每条自动换一</span>
          </el-form-item>
          <el-form-item label="手填钩子">
            <el-input v-model="p.hookText" placeholder="填写后不使用智能生成，整批使用同一" />
          </el-form-item>
          <el-form-item label="烧钩子字">
            <el-switch v-model="p.burnHookText" />
          </el-form-item>
          <el-form-item label="全片自动字幕">
            <el-switch v-model="p.autoSubtitles" active-text="按授权转" inactive-text="关闭" />
            <span class="form-hint">仅烧录在素材库中明确开启转录字幕"且已成功完成语音识别的素材字幕。请在素材列表中对需要的素材开启转录授权</span>
          </el-form-item>
          <el-form-item label="旧字幕遮挡">
            <el-switch v-model="p.cleanSourceSubtitles" active-text="清理" inactive-text="保留" @change="onSourceSubtitleCleanChange" />
            <span class="form-hint">这是底部旧字幕安全区遮挡，不是 OCR 修复。仅在确认素材有底部旧字幕时开启；之后可烧录新的 ASR/AI 口播字幕，避免双字幕叠加</span>
          </el-form-item>
          <el-form-item v-if="p.cleanSourceSubtitles" label="清理模式">
            <el-select v-model="p.sourceSubtitleCleanMode" style="width:100%">
              <el-option label="底部旧字幕安全区遮盖（稳定）" value="subtitle-safe-band" />
            </el-select>
          </el-form-item>
          <el-form-item label="字号 / 颜色">
            <el-input-number v-model="p.hookFontSize" :min="20" :max="160" style="width:120px" />
            <el-input v-model="p.hookFontColor" style="width:110px;margin-left:8px" placeholder="白色" />
          </el-form-item>
          <el-form-item label="字体文件">
            <el-input v-model="p.fontFile" placeholder="可选：服务器上的中文字体文件路" />
            <div class="form-hint">可留空，由服务器自动选择可用中文字体；不再绑定Windows 路径</div>
          </el-form-item>
          <el-form-item label="文件名前缀">
            <el-input v-model="p.namePrefix" placeholder="mix" />
          </el-form-item>
          </div>
        </el-form>
      </div>
    </div>

    <!-- 右：预览 + 出片 -->
    <div class="studio-main">
      <div class="card">
        <div class="card-title">
          3. 干跑预览
          <span class="hint">不渲染，只看时间线。确认产品段进去了、时长对了再批量</span>
          <span style="flex:1"></span>
          <el-input-number v-model="variant" :min="0" :max="99" size="small" style="width:110px" />
          <el-button size="small" style="margin-left:8px" :loading="dry" @click="doDryRun">预览第 N 条</el-button>
        </div>

        <div v-if="plan">
          <div style="display:flex;gap:16px;font-size:13px;margin-bottom:10px;flex-wrap:wrap">
            <span>目标 <b>{{ plan.targetSec.toFixed(1) }}s</b></span>
            <span>实际 <b :style="{ color: inRange ? '#67c23a' : '#f56c6c' }">{{ plan.plannedSec.toFixed(1) }}s</b></span>
            <span>片段 <b>{{ plan.segments.length }}</b> </span>
            <span>钩子 <b>{{ plan.hookText || '（无）' }}</b></span>
            <span v-if="plan.hookEndSec > plan.hookStartSec" class="muted">钩子声画 {{ plan.hookStartSec.toFixed(1) }}–{{ plan.hookEndSec.toFixed(1) }}s</span>
            <span class="muted">钩子音频 {{ plan.hookAudioPath ? '已绑' : '未设' }}</span>
            <span class="muted">BGM {{ plan.bgmPath ? '已选择' : '未设' }} · 口播 {{ plan.voicePath ? '已选择' : '未设' }}</span>
            <span class="muted">随机标识 {{ plan.seed }}</span>
          </div>
          <div v-if="plan.marketingStructurePreview" class="structure-preview">
            <b>结构 {{ plan.marketingStructure }}</b><span>{{ plan.marketingStructurePreview }}</span>
          </div>
          <div v-if="plan.hookStrategy || plan.rehookText || plan.semanticSegmentCount || plan.gridFallbackCount" style="display:flex;gap:14px;font-size:12px;flex-wrap:wrap;margin-bottom:10px">
            <span v-if="plan.hookStrategy" class="muted">钩子策略 <b>{{ hookStrategyLabel(plan.hookStrategy) }}</b></span>
            <span v-if="plan.rehookText" class="muted">中段再钩子 {{ plan.rehookWindowStart?.toFixed?.(1) ?? plan.rehookWindowStart }}–{{ plan.rehookWindowEnd?.toFixed?.(1) ?? plan.rehookWindowEnd }}s · {{ plan.rehookText }}</span>
            <span v-if="plan.semanticSegmentCount" class="muted">语义候选 <b>{{ plan.semanticSegmentCount }}</b> </span>
            <span v-if="plan.gridFallbackCount" class="muted" style="color:#e6a23c">网格降级 <b>{{ plan.gridFallbackCount }}</b> </span>
          </div>

          <div class="timeline-bar">
            <div v-for="(s, i) in plan.segments" :key="i" class="seg" :class="'seg-' + s.slot"
              :style="{ flex: s.duration }" :title="segTitle(s)">
              {{ s.duration >= 2.2 ? s.duration.toFixed(1) : '' }}
            </div>
          </div>
          <div style="margin-top:8px;display:flex;gap:14px;font-size:12px;flex-wrap:wrap">
            <span v-for="legendItem in legendItems" :key="legendItem.key">
              <i :style="{ display: 'inline-block', width: '10px', height: '10px', background: legendItem.color, borderRadius: '2px', marginRight: '4px' }"></i>
              {{ legendItem.label }} {{ countSlot(legendItem.key) }}
            </span>
          </div>

          <el-collapse style="margin-top:12px">
            <el-collapse-item :title="`时间线明细（${plan.segments.length} 段）`" name="1">
              <el-table :data="plan.segments" size="small" max-height="300">
                <el-table-column prop="index" label="#" width="50" />
                <el-table-column label="段位" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" :color="slotColor(row.slot)" style="color:#fff;border:none">
                      {{ slotLabel(row.slot) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="materialName" label="素材" min-width="200" show-overflow-tooltip />
                <el-table-column label="取自" width="100">
                  <template #default="{ row }">{{ Number(row.sourceStart || 0).toFixed(1) }}s </template>
                </el-table-column>
                <el-table-column label="时长" width="80">
                  <template #default="{ row }">{{ row.duration.toFixed(2) }}s</template>
                </el-table-column>
                <el-table-column prop="kind" label="类型" width="70" />
              </el-table>
            </el-collapse-item>
            <el-collapse-item v-if="plan.notes?.length" :title="`执行日志（${plan.notes.length} 条）`" name="2">
              <div v-for="(n, i) in plan.notes" :key="i" class="mono muted" style="line-height:1.9">{{ n }}</div>
            </el-collapse-item>
          </el-collapse>
        </div>
        <div v-else class="muted">点「预览第 N 条」看看这次会剪成什么样。改 N 可以对比不同条之间的差异化</div>
      </div>

      <!-- 素材缺口分析 -->
      <div v-if="materialGap" class="card gap-card">
        <div class="card-title">
          素材容量与角色诊断
          <span class="hint">用于判断素材范围和结构角色；是否可出片以干跑预检为准</span>
          <span style="flex:1"></span>
          <el-button size="small" :loading="gapLoading" @click="doGapAnalysis">重新分析</el-button>
        </div>
        <el-alert v-if="materialGap.sufficient" type="success" :closable="false" show-icon title="画面容量满足最低时长"
          description="这只是容量估算；提交前仍会按当前范围、项目相关性、切片约束和音频覆盖执行干跑预检。" style="margin-bottom:10px" />
        <el-alert v-else type="warning" :closable="false" show-icon title="素材不足"
          style="margin-bottom:10px">
          <template #default>
            {{ materialGap.notes?.[0] || '当前素材不足以覆盖目标时长' }}
          </template>
        </el-alert>
        <div class="gap-grid">
          <div class="gap-item">
            <span class="gap-label">可用画面时长</span>
            <b :style="{ color: materialGap.sufficient ? '#67c23a' : '#f56c6c' }">{{ materialGap.availableVisualSec?.toFixed(1) || '0.0' }} </b>
          </div>
          <div class="gap-item">
            <span class="gap-label">要求最短时长</span>
            <b>{{ materialGap.requestedMinSec }} </b>
          </div>
          <div class="gap-item">
            <span class="gap-label">可读画面</span>
            <b>{{ materialGap.visualCount }}</b>
            <span class="muted" style="font-size:11px">（总计 {{ materialGap.totalVisualCount }} 条）</span>
          </div>
          <div v-if="materialGap.projectKeyword" class="gap-item">
            <span class="gap-label">项目关键词</span>
            <b>{{ materialGap.projectKeyword }}</b>
          </div>
        </div>
        <div v-if="materialGap.excludedBySourceMode || materialGap.excludedByFolder || materialGap.failedAdmission" class="gap-recovery-actions"><el-alert type="warning" :closable="false" show-icon title="有素材被当前范围排除"><template #default><span v-if="materialGap.excludedBySourceMode">已抓取素材范围外 {{ materialGap.excludedBySourceMode }} 条；</span><span v-if="materialGap.excludedByFolder">文件夹范围外 {{ materialGap.excludedByFolder }} 条；</span><span v-if="materialGap.failedAdmission">未通过质量准入 {{ materialGap.failedAdmission }} 条。</span><el-button v-if="materialGap.excludedBySourceMode" link type="primary" @click="materialSourceMode = 'builtin'; doGapAnalysis()">包含已抓取素材后重新检查</el-button><el-button v-if="materialGap.excludedByFolder" link type="primary" @click="$router.push('/materials')">去绑定素材文件夹</el-button><el-button v-if="materialGap.failedAdmission" link type="primary" @click="$router.push('/crawl')">去重新检测失败素材</el-button></template></el-alert></div>
        <div v-if="materialGap.missingRoles?.length" class="gap-roles">
          <span class="muted">缺少的角色：</span>
          <el-tag v-for="role in materialGap.missingRoles" :key="role" size="small" type="warning" effect="plain" style="margin-right:6px">
            {{ ROLE_LABEL[role] || role }}
          </el-tag>
          <div v-if="materialGap.missingRoles.includes('endcard')" class="gap-endcard-hint" style="margin-top:8px">
            <el-alert type="warning" :closable="false" show-icon style="margin-bottom:8px"
              title="需要独立片尾卡"
              description="你已开启“必须使用独立片尾卡”。请提供产品图、品牌 Logo、优惠/二维码图或收尾视频，并在素材库标记为「片尾卡」角色。未开启此选项时，系统会自动使用产品图或主体镜头收尾，不会阻断出片。" />
            <el-button size="small" type="primary" plain @click="$router.push('/materials')">去素材库标记片尾卡</el-button>
          </div>
        </div>
        <div v-if="materialGap.roleCounts" class="gap-roles" style="margin-top:6px">
          <span v-for="(count, role) in materialGap.roleCounts" :key="role" style="margin-right:12px;font-size:12px">
            <span class="muted">{{ ROLE_LABEL[role] || role }}</span><b>{{ count }}</b>
          </span>
        </div>
        <div v-if="materialGap.notes?.length > 1" class="gap-notes">
          <div v-for="(n, i) in materialGap.notes.slice(1)" :key="i" class="muted" style="font-size:12px;line-height:1.8">{{ n }}</div>
        </div>
        <div class="gap-auto" style="margin:10px 0;display:flex;align-items:center;gap:8px;flex-wrap:wrap">
          <el-radio-group v-model="materialSourceMode" size="small">
            <el-radio-button label="local">仅本地素材</el-radio-button>
            <el-radio-button label="builtin">内置公开补齐</el-radio-button>
            <el-radio-button label="extended">公开直链 / 官方授权</el-radio-button>
          </el-radio-group>
          <span class="muted" style="font-size:12px">
            {{ materialSourceMode === 'local' ? '本地模式不会访问外部来源，只使用当前素材库。' : materialSourceMode === 'builtin' ? '内置公开补齐只使用免 Key、可验证许可的公开来源；点击按钮即执行一次受控检索。' : '公开直链和官方授权素材请在素材抓取页导入；未配置官方 Key 时不会伪造自动结果。' }}
          </span>
        </div>
        <div v-if="materialGap.missingRoles?.some(role => ['product', 'endcard'].includes(role))" class="gap-local-required" style="margin:8px 0">
          <el-alert type="warning" :closable="false" show-icon title="产品和片尾素材必须来自本地或已授权导入">
            公共 B-roll 不能替代你的产品图、品牌 Logo、优惠图、二维码或专用片尾卡。
            <el-button link type="primary" @click="$router.push('/materials')">前往素材库</el-button>
          </el-alert>
        </div>
        <div class="gap-actions" style="margin:10px 0;display:flex;align-items:center;gap:8px;flex-wrap:wrap">
          <el-button type="primary" :loading="autoFillLoading" @click="doAutoFill"
            :disabled="materialSourceMode === 'local' || (materialGap.sufficient && !hasPublicVisualRoleGap)">
            <span>{{ materialSourceMode === 'builtin' ? '执行内置公开补齐' : '查看授权来源并补齐' }}</span>
            <span class="form-hint" style="margin-left:6px;font-size:11px">{{ materialSourceMode === 'builtin' ? '（Wikimedia / Archive）' : '（进入素材抓取）' }}</span>
          </el-button>
          <el-button @click="goToCrawlWithProject">{{ materialSourceMode === 'extended' ? '前往公开直链 / 官方授权' : '前往素材抓取' }}</el-button>
          <el-button @click="$router.push('/materials')">管理素材</el-button>
        </div>
        <div v-if="autoFillResult" class="auto-fill-result" style="margin-top:10px">
          <el-alert :type="autoFillResult.any ? 'success' : 'warning'" :closable="false" show-icon
            :title="autoFillResult.any ? `已排队 ${autoFillResult.totalItemsQueued} 条公开素材` : autoFillTitle" />
          <div v-if="autoFillResult.crawlJobIds?.length" class="muted" style="margin-top:6px;font-size:12px">
            采集任务 ID：{{ autoFillResult.crawlJobIds.join('、') }}。请前往
            <el-link type="primary" @click="goToCrawlWithProject">素材抓取</el-link> 页面查看进度。
          </div>
          <div v-if="autoFillResult.sourceResults?.length" class="prepare-source-issues" style="margin-top:8px">
            <div v-for="(source, idx) in autoFillResult.sourceResults" :key="'manual-' + idx" class="prepare-source-issue">
              <el-tag size="small" :type="sourceStatusType(source.status)">{{ sourceStatusLabel(source.status) }}</el-tag>
              <span class="prepare-source-name">{{ source.source }}</span>
              <span class="muted">{{ sourceResultMessage(source) }}</span>
            </div>
          </div>
        </div>
        <div v-if="preparationResult" class="auto-fill-result" style="margin-top:10px">
          <el-alert :type="preparationResult.ready ? 'success' : 'warning'" :closable="false" show-icon
            :title="preparationResult.ready ? '项目素材与所需画面角色已准备完成，干跑会使用已匹配和已入库的素材' : (preparationResult.finalGap?.sufficient ? '基础时长已满足，但仍有指定画面角色缺口；已继续使用当前可读素材预检' : (preparationResult.timedOut ? '公开素材仍在后台入库；自主模式会完成受控恢复，当前只使用已成功入库的素材' : '项目素材仍有缺口，干跑将仅使用当前已成功入库的素材'))" />
          <div v-if="preparationResult.autoFill && !preparationResult.autoFill.any" class="prepare-fallback-hint" style="margin-top:8px">
            <el-alert type="info" :closable="false" show-icon title="没有可用的公开素材"
              description="公开来源未能入库素材，本次出片会继续使用当前本地素材，不会中断干跑；可前往「素材抓取」页手动导入公开素材后再试" />
          </div>
          <div v-if="preparationSourceIssueRows.length" class="prepare-source-issues" style="margin-top:8px">
            <div v-for="(issue, idx) in preparationSourceIssueRows" :key="'final-' + idx" class="prepare-source-issue">
              <el-tag size="small" :type="issue.status === 'failed' ? 'danger' : 'warning'">{{ issue.status === 'failed' ? '来源失败' : '来源熔断' }}</el-tag>
              <span class="prepare-source-name">{{ issue.source }}</span>
              <span class="muted">{{ issue.message }}{{ issue.retryAfterSeconds ? `（约 ${issue.retryAfterSeconds} 秒后恢复）` : '' }}</span>
            </div>
          </div>
          <div class="prepare-stage-list">
            <div v-for="(stage, idx) in preparationResult.stages || []" :key="stage.name + '-' + idx" class="prepare-stage-row">
              <el-tag size="small" :type="prepareStageType(stage.status)">{{ stage.name }}</el-tag>
              <span class="muted">{{ stage.message }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="card batch-card">
        <div class="card-title">4. 批量出片</div>
        <el-alert v-if="preflightMessage" :type="preflightAlertType" :closable="false" show-icon style="margin-bottom:12px" :title="preflightMessage" />
        <div v-if="preflight" class="preflight-summary" :class="`preflight-${preflight.status || 'blocked'}`">
          <div class="preflight-summary-head">
            <strong>出片预检</strong>
            <el-tag size="small" :type="preflightTagType">{{ preflightStatusLabel }}</el-tag>
            <span class="muted">计划 {{ Number(preflight.plannedSec || 0).toFixed(1) }} 秒 · 区间 {{ Number(preflight.minSec || 0).toFixed(0) }}–{{ Number(preflight.maxSec || 0).toFixed(0) }} 秒</span>
          </div>
          <div class="preflight-summary-metrics">
            <span>可用画面 {{ Number(preflight.usableVisualSec || 0).toFixed(1) }} 秒</span>
            <span>片段 {{ preflight.visualCount || 0 }} 条</span>
            <span>音频 {{ audioCoverageLabel(preflight.audioCoverageStatus) }}</span>
          </div>
          <div v-if="preflight.blockers?.length" class="preflight-issues">
            <div v-for="issue in preflight.blockers" :key="issue.code" class="preflight-issue blocker"><el-tag size="small" type="danger">阻断</el-tag><span>{{ issue.message }}</span></div>
          </div>
          <div v-if="preflight.warnings?.length" class="preflight-issues">
            <div v-for="issue in preflight.warnings" :key="issue.code" class="preflight-issue warning"><el-tag size="small" type="warning">提示</el-tag><span>{{ issue.message }}</span></div>
          </div>
        </div>
        <div v-if="preparationPanelActive || dry || submitting || activeRenderJob" class="batch-live-panel"
          :class="activeRenderJob ? `batch-live-${activeRenderJob.status}` : 'batch-live-working'">
          <div class="batch-live-header">
            <div class="batch-live-title">
              <span class="live-dot" :class="{ paused: activeRenderJob?.status === 'paused' }"></span>
              <strong>{{ livePanelTitle }}</strong>
              <el-tag size="small" :type="livePanelTagType">{{ livePanelTag }}</el-tag>
            </div>
              <span class="batch-live-refresh">每 3 秒检查一次 · {{ activeRenderJob ? liveHeartbeatText : (preparationPanelActive ? '正在准备素材并轮询进度，请不要重复点击' : '正在执行，请不要重复点击') }}</span>

          </div>
          <template v-if="activeRenderJob">
            <div class="batch-live-main">
              <div class="batch-live-number">
                <span class="batch-live-count">{{ liveCompletedCount }}<small>/{{ liveTotalCount }}</small></span>
                <span>已完成条</span>
              </div>
              <div class="batch-live-stage">
                <div class="batch-live-stage-line"><b>{{ liveCurrentItemText }}</b><span>{{ livePhaseText }}</span></div>
                <el-progress :percentage="liveOverallProgress" :status="activeRenderJob.status === 'running' ? undefined : 'warning'" :stroke-width="14" text-inside />
                <div class="batch-live-subline">
                  <span>总进度 {{ liveOverallProgress }}%</span>
                  <span>当前条目 {{ liveItemProgress }}%</span>
                  <span v-if="liveEtaText">预计还需 {{ liveEtaText }}</span>
                  <span>已运行 {{ liveElapsedText }}</span>
                </div>
                <el-progress v-if="activeRenderJob.status === 'running' && liveItemProgress > 0" :percentage="liveItemProgress" :stroke-width="7" :show-text="false" status="success" />
              </div>
            </div>
          </template>
          <div v-else class="batch-preflight-state">
            <div class="batch-preflight-spinner"></div>
            <div style="flex:1;min-width:0">
              <b>{{ preparationPanelActive ? '正在识别项目、匹配本地素材并补齐公开素材' : (dry ? '正在检查素材、时长和音频可用' : '正在提交后台出片任务') }}</b>
              <div>{{ preparationPanelActive ? '只会使用固定公开来源；成功入库的素材会立刻参加本次干跑' : (dry ? '会先完成安全预检，预检结束后才会真正出片；这不是卡住' : '提交成功后会自动显示当前条目和实时进度') }}</div>
              <template v-if="preparationPanelActive">
                <div class="prepare-live-stage">
                  <span class="muted">当前阶段</span><b>{{ preparingStageName }}</b>
                  <span v-if="preparingStageMessage" class="muted">{{ preparingStageMessage }}</span>
                </div>
                <div class="prepare-live-meta">
                  <span>已运行 {{ formatDuration(preparingElapsedSec) }}</span>
                  <span v-if="preparingWaitedSec > 0">等待公开素材 {{ preparingWaitedSec }} </span>
                  <span v-if="preparingSourceIssues.length" style="color:#d46b08">公开来源问题 {{ preparingSourceIssues.length }} </span>
                </div>
                <div v-if="preparingSourceIssues.length" class="prepare-source-issues">
                  <div v-for="(issue, idx) in preparingSourceIssues" :key="'live-' + idx" class="prepare-source-issue">
                    <el-tag size="small" :type="issue.status === 'failed' ? 'danger' : 'warning'">{{ issue.status === 'failed' ? '来源失败' : '来源熔断' }}</el-tag>
                    <span class="prepare-source-name">{{ issue.source }}</span>
                    <span class="muted">{{ issue.message }}{{ issue.retryAfterSeconds ? `（约 ${issue.retryAfterSeconds} 秒后恢复）` : '' }}</span>
                  </div>
                </div>
              </template>
            </div>
          </div>
          <div class="batch-live-tip">{{ activeRenderJob ? liveActionText : (preparationPanelActive ? (preparationPanelMessage || preparingStageMessage || '正在轮询素材准备进度，请保持页面打开') : (dry ? '正在读取 FFmpeg/FFprobe 检查结果，请等待预检完成' : '正在把任务交给后台队列，请保持当前页面打开')) }}</div>
        </div>
        <div class="batch-row batch-mode-row">
          <span class="batch-label">生成方式</span>
          <span class="batch-option" :class="{ active: !continuous }">固定数量</span>
          <el-switch v-model="continuous" class="batch-switch" />
          <span class="batch-option" :class="{ active: continuous }">持续生成</span>
          <div v-if="!continuous" class="batch-inline-field">
            <span>数量</span><el-input-number v-model="count" :min="1" :max="200" controls-position="right" />
          </div>
        </div>
        <el-button link size="small" @click="batchOptionsOpen = !batchOptionsOpen">{{ batchOptionsOpen ? '收起高级任务选项' : '高级任务选项' }}</el-button>
        <div v-show="batchOptionsOpen" class="batch-row">
          <div class="batch-field"><span>任务</span><el-input v-model="jobName" placeholder="留空自动命名" /></div>
          <div class="batch-field"><span>超时(分)</span><el-input-number v-model="jobTimeoutMin" :min="0" :max="240" :step="5" controls-position="right" /><small>0 = 使用默认</small></div>
          <div class="batch-field"><span>僵死(分)</span><el-input-number v-model="jobStaleMin" :min="0" :max="120" :step="5" controls-position="right" /><small>0 = 10 分钟保护</small></div>
        </div>
        <div class="batch-row batch-action-row">
          <template v-if="!activeContinuousJob">
            <el-button type="primary" size="large" :loading="preparing || preparationBackground || submitting || dry" :disabled="preparing || preparationBackground || submitting || dry || preflightBlocked" @click="submit">{{ preparing || preparationBackground ? '识别并补齐素材中' : (dry ? '干跑预检' : (continuous ? '开始持续生成' : `开始出 ${count} 条`)) }}</el-button>
            <el-button v-if="preparing || preparationBackground" size="large" plain type="warning" @click="cancelPrepare">取消准备并继续</el-button>
          </template>
          <template v-else>
            <span class="batch-status">{{ activeContinuousJob.status === 'paused' ? '已暂停，已生成的成片会保留' : `正在生成 · 已产出 ${jobLive[activeContinuousJob.id]?.completedItems ?? activeContinuousJob.current ?? 0} 条` }}<span v-if="jobLive[activeContinuousJob.id]?.phaseLabel || jobLive[activeContinuousJob.id]?.step"> · {{ jobLive[activeContinuousJob.id]?.phaseLabel || jobLive[activeContinuousJob.id]?.step }}</span></span>
            <el-button v-if="activeContinuousJob.status === 'paused'" type="primary" size="large" @click="resume(activeContinuousJob)">继续生成</el-button>
            <el-button v-else type="warning" size="large" @click="pause(activeContinuousJob)">暂停并保留</el-button>
            <el-popconfirm title="放弃未完成内容？已生成的成片会保留" @confirm="cancel(activeContinuousJob)"><template #reference><el-button type="danger" plain>放弃当前任务</el-button></template></el-popconfirm>
          </template>
        </div>
        <div class="form-hint batch-help">
          固定数量适合先出 1–2 条，确认画面、节奏、声音和字幕后再增加数量。持续生成适合干跑通过且素材池充足的项目，每条成功后才继续；暂停会保留已完成成片，素材不足、质量检查失败或连续异常会自动停止。
        </div>
      </div>

      <div class="card">
        <div class="card-title">
          出片任务
          <span style="flex:1"></span>
          <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" />
          <el-button size="small" style="margin-left:8px" @click="loadJobs">刷新</el-button>
          <el-popconfirm v-if="selectedJobIds.length" :title="`删除选中的 ${selectedJobIds.length} 条任务及关联成片文件？运行中的任务必须先取消。`" @confirm="batchDeleteJobs">
            <template #reference><el-button size="small" type="danger" plain style="margin-left:8px">批量删除（{{ selectedJobIds.length }}</el-button></template>
          </el-popconfirm>
          <el-popconfirm title="只删除已完成、失败、已取消的任务及其本地成片文件，继续吗？" @confirm="cleanupJobs">
            <template #reference><el-button size="small" type="danger" plain style="margin-left:8px">清理终态记</el-button></template>
          </el-popconfirm>
        </div>
        <el-alert v-if="jobsError" type="error" :closable="false" show-icon style="margin-bottom:10px"
          title="任务列表加载失败，请刷新重试" />
        <div v-else-if="!initialLoading && !jobs.length" class="muted" style="margin-bottom:10px">暂无出片任务</div>
        <el-button link size="small" @click="jobHistoryOpen = !jobHistoryOpen">{{ jobHistoryOpen ? '收起任务记录' : `查看任务记录（${jobs.length}）` }}</el-button>
        <div v-show="jobHistoryOpen" class="job-history-wrap"><el-table :data="jobs" v-loading="jobsLoading" row-key="id" size="small" max-height="330" @selection-change="onJobSelectionChange" @row-dblclick="onJobRowDblClick">
          <el-table-column type="selection" width="46" :selectable="canSelectJobForDelete" />
          <el-table-column prop="id" label="#" width="55" />
          <el-table-column prop="name" label="任务" min-width="160" show-overflow-tooltip />
          <el-table-column label="状" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="STATUS_TYPE[row.status]">{{ STATUS_LABEL[row.status] || row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="进度" width="200">
            <template #default="{ row }">
              <el-progress :percentage="row.continuous ? 0 : (jobLive[row.id]?.overallProgress ?? row.progress ?? 0)" :stroke-width="12" text-inside
                :status="row.continuous ? undefined : (row.status === 'failed' ? 'exception' : (row.status === 'done' ? 'success' : ''))" />
              <div class="muted">{{ row.continuous ? `连续模式 · 已产出 ${jobLive[row.id]?.completedItems ?? row.current ?? 0} 条` : `${jobLive[row.id]?.completedItems ?? row.current ?? 0} / ${jobLive[row.id]?.totalItems ?? row.total ?? row.count}` }}</div>
              <el-progress v-if="jobLive[row.id]?.currentItemProgress" :percentage="jobLive[row.id].currentItemProgress" :stroke-width="6" :show-text="false" />
              <div class="muted">{{ translateTechnicalText(jobLive[row.id]?.phaseLabel || jobLive[row.id]?.step || row.summary || '等待调度') }}</div>
              <div v-if="jobLive[row.id]" class="muted">已用 {{ formatDuration(jobLive[row.id].elapsedSec) }} · {{ jobLive[row.id].itemsPerMinute || 0 }} 条/分<span v-if="!jobLive[row.id].isContinuous && jobLive[row.id].etaSec"> · 预计 {{ formatDuration(jobLive[row.id].etaSec) }}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span :style="{ color: taskHasFailure(row) ? '#f56c6c' : '' }">{{ shortTaskText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openJob(row)">详情</el-button>
              <el-button v-if="row.continuous && (row.status === 'running' || row.status === 'pending')" link type="warning"
                size="small" @click="pause(row)">暂停</el-button>
              <el-button v-if="row.status === 'paused' || row.status === 'awaiting_decision'" link type="primary" size="small" @click="resume(row)">{{ row.status === 'awaiting_decision' ? '按现有策略继续' : '继续' }}</el-button>
              <el-button v-if="row.status === 'failed' || row.status === 'awaiting_decision' || (row.status === 'done' && taskHasFailure(row))" link type="warning" size="small" @click="retryFailed(row)">重试失败</el-button>
              <el-button v-if="row.status === 'running' || row.status === 'pending' || row.status === 'paused' || row.status === 'awaiting_decision'" link type="danger"
                size="small" @click="cancel(row)">取消</el-button>
              <el-popconfirm title="删除任务及其成片记录" @confirm="delJob(row)">
                <template #reference><el-button link type="danger" size="small">删除</el-button></template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    </div>

    <el-dialog v-model="jobDlg" title="任务详情" width="720px">
      <div v-if="jobDetail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状">{{ STATUS_LABEL[jobDetail.job.status] }}</el-descriptions-item>
          <el-descriptions-item label="总体进度">{{ jobDetail.isContinuous ? '连续生成' : `${jobDetail.overallProgress || 0}%` }}</el-descriptions-item>
          <el-descriptions-item label="产出">{{ jobDetail.completedItems ?? jobDetail.job.current }} / {{ jobDetail.totalItems ?? jobDetail.job.total }}</el-descriptions-item>
          <el-descriptions-item label="当前阶段">{{ translateTechnicalText(jobDetail.phaseLabel || jobDetail.step || '-') }}</el-descriptions-item>
          <el-descriptions-item label="阶段进度">{{ jobDetail.currentItemProgress ?? jobDetail.phaseProgress ?? 0 }}%</el-descriptions-item>
          <el-descriptions-item label="处理结果" :span="2">
            <div v-if="taskIssueRows(jobDetail.job).length" class="task-issues">
              <div v-for="(issue, index) in taskIssueRows(jobDetail.job)" :key="`${issue.text}-${index}`" class="task-issue">
                <div class="task-issue-title">{{ issue.title }}</div>
                <div class="task-issue-reason">原因：{{ issue.text }}</div>
                <div class="task-issue-action">处理方法：{{ issue.action }}</div>
              </div>
            </div>
            <span v-else>{{ translateTechnicalText(jobDetail.job.summary || '-') }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <div style="margin-top:12px" class="video-grid">
          <div class="video-card" v-for="o in jobDetail.outputs" :key="o.id">
            <video v-if="o.filePath" :src="fileUrl(o)" controls preload="metadata"></video>
            <div v-else class="muted" style="min-height:110px;display:flex;align-items:center">该条已被成品质检拦截，未保留可播放文件</div>
            <div class="meta">
              <div class="t">#{{ o.idx }}</div>
              <div class="muted">{{ o.filePath && o.durationSec ? o.durationSec.toFixed(1) + 's' : (o.qcReport || '') }}</div>
              <el-tag size="small" :type="o.qcStatus === 'fail' ? 'danger' : (o.qcStatus === 'warn' ? 'warning' : 'success')">{{ o.qcStatus === 'fail' ? '已拦截' : (o.qcStatus === 'warn' ? '建议复核' : '可发布') }}</el-tag>
              <el-link v-if="o.filePath" type="primary" :href="api.downloadUrl(o.id)" target="_blank">下载</el-link>
            </div>
          </div>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="fixedOrderDraftDialogOpen" title="新建顺序草稿" width="560px" :close-on-click-modal="false" append-to-body>
      <div class="fixed-order-draft-intro">草稿只用于当前 Studio 出片配置：创建后仍可绑定文件夹、修改步骤并执行原有干跑；不会写入独立预设库。</div>
      <el-form label-width="76px" @submit.prevent>
        <el-form-item label="草稿名称">
          <el-input v-model="fixedOrderDraftForm.name" maxlength="80" show-word-limit placeholder="例如：新品测评标准顺序" />
        </el-form-item>
        <el-form-item label="步骤名称">
          <el-input v-model="fixedOrderDraftForm.stepsText" type="textarea" :rows="7" maxlength="640" show-word-limit placeholder="一行一个步骤，例如：&#10;开场钩子&#10;痛点场景&#10;产品展示" />
          <div class="form-hint">支持 1–32 步。创建后主/备用文件夹都为空，避免把现有绑定误带入新的顺序。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="fixedOrderDraftDialogOpen = false">取消</el-button>
        <el-button type="primary" @click="createFixedOrderDraft">创建并编辑步骤</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="autonomyConsentDlg" :title="`AI 生产权限说明（${consentModeLabel}）`" width="680px"
      :close-on-click-modal="false" append-to-body>
      <div class="consent-body">
        <p class="consent-intro">首次使用「{{ consentModeLabel }}」模式提交前，请确认本次 AI 自主生产的授权边界。该说明只针对本机出片流程；确认后本机将记住你的选择，可在上方模式卡片随时调整</p>
        <div class="consent-section">
          <div class="consent-section-title allowed">允许的操</div>
          <ul>
            <li>使用已有本地素材与当前项目素材（不超出你已导入或已授权补齐的范围</li>
            <li>从许可可验证的免登录公开源补齐素材（Wikimedia / Archive）；Pexels/Pixabay 仅在你配置官方Key 后使用</li>
            <li>使用已安装的本机能力（FFmpeg / FFprobe 等已就绪工具，不安装缺失组件</li>
            <li>自动渲染、成品质检（QC）与失败自动修复（仅基于已安装能力与本地素材</li>
          </ul>
        </div>
        <div class="consent-section">
          <div class="consent-section-title prohibited">绝不执行</div>
          <ul>
            <li>任何支付、下单或订阅</li>
            <li>第三方账号登</li>
            <li>绕过验证码/ DRM / 付费</li>
            <li>修改系统设置或安装缺失组</li>
          </ul>
        </div>
        <el-alert v-if="consentTargetMode === 'autonomous'" type="warning" :closable="false" show-icon
          title="自主模式默认开启严格交"
          description="成品质检项从提示升级为硬性拦截，不满足交付标准的成片不会通过；提交前可在参数区手动关闭" />
      </div>
      <template #footer>
        <el-button @click="onConsentCancel">取消</el-button>
        <el-button type="primary" @click="onConsentConfirm">我了解并同意，继续提</el-button>
      </template>
    </el-dialog>
    </div>
  </div>
</template>

<style scoped>
.studio-workbench-head { margin-bottom:14px; padding:16px; border:1px solid #e3e8ef; border-radius:6px; background:#fff; }
.studio-workbench-title { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; }
.studio-workbench-title h2 { margin:0; font-size:22px; }
.studio-workbench-title p { margin:6px 0 0; color:#606266; font-size:13px; }
.studio-system-status { display:flex; align-items:center; justify-content:flex-end; flex-wrap:wrap; gap:8px; color:#606266; font-size:12px; }
.studio-quick-actions { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:10px; margin-top:14px; }
.studio-action { min-height:82px; padding:12px; border:1px solid #dcdfe6; border-radius:6px; background:#fbfcfe; color:#303133; text-align:left; cursor:pointer; transition:border-color .15s, background .15s; }
.studio-action:hover { border-color:#409eff; background:#f0f7ff; }
.studio-action b,.studio-action span { display:block; }
.studio-action b { font-size:14px; }
.studio-action span { margin-top:6px; color:#606266; font-size:12px; line-height:1.45; }
.creative-launch-summary { display:grid; grid-template-columns:auto minmax(0,1fr); gap:10px 16px; margin:16px 0; padding:12px; border:1px solid #e3e8ef; border-radius:6px; background:#fbfcfe; }
.creative-launch-summary span { color:#909399; font-size:12px; }
.creative-launch-summary b { overflow-wrap:anywhere; }
.creative-launch-actions { display:flex; justify-content:flex-end; gap:8px; flex-wrap:wrap; }
@media (max-width:900px) { .studio-workbench-title { flex-direction:column; } .studio-system-status { justify-content:flex-start; } .studio-quick-actions { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media (max-width:520px) { .studio-quick-actions { grid-template-columns:1fr; } }
.studio-media-note { margin:4px 0 12px; color:#606266; font-size:12px; line-height:1.6; }
.studio-audio-choice { display:grid; grid-template-columns:auto minmax(0,1fr); align-items:center; gap:10px 12px; margin:8px 0 12px; padding:10px 12px; border:1px solid #e3e8ef; border-radius:6px; background:#fbfcfe; }
.studio-audio-choice :deep(.el-slider) { grid-column:2; min-width:0; }
.studio-media-row { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.studio-cover-card { display:flex; flex-direction:column; gap:8px; margin:8px 0 14px; padding:10px 12px; border:1px solid #d9ecff; border-radius:6px; background:#f8fbff; }
.studio-cover-head { display:flex; align-items:baseline; gap:8px; }
.cover-preview-stage { position:relative; width:min(100%,360px); aspect-ratio:9/16; overflow:hidden; border:1px solid #cfd8e3; border-radius:5px; background:#1f2937; touch-action:none; cursor:crosshair; }.cover-preview-media { width:100%; height:100%; object-fit:contain; display:block; }.cover-selection { position:absolute; box-sizing:border-box; border:2px solid #f56c6c; background:rgba(245,108,108,.22); pointer-events:none; min-width:2px; min-height:2px; }.cover-selection span { position:absolute; left:0; top:0; padding:2px 4px; background:#f56c6c; color:#fff; font-size:10px; white-space:nowrap; }.studio-cover-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; }
.studio-cover-grid :deep(.el-input-number) { width:100%; }
@media (max-width:600px) { .studio-cover-grid { grid-template-columns:1fr; } }
.studio-layout { display:flex; gap:16px; align-items:flex-start; min-width:0; }
.studio-left { width:clamp(320px, 32vw, 420px); flex:0 1 420px; min-width:300px; align-self:flex-start; position:sticky; top:12px; max-height:calc(100vh - 140px); overflow-y:auto; padding-right:4px; }
.studio-main { flex:1 1 0; min-width:0; }
.runtime-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:10px; }
.runtime-grid span { display:flex; align-items:center; justify-content:space-between; gap:8px; padding:8px 10px; background:#f6f8fb; border-radius:6px; }
.runtime-queue { display:flex; flex-wrap:wrap; gap:18px; margin-top:10px; padding:8px 10px; background:#f8fafc; border:1px solid #e9ebef; border-radius:6px; font-size:12px; }
.batch-row { display:flex; align-items:center; gap:14px; flex-wrap:wrap; margin:10px 0; }
.batch-mode-row { min-height:42px; }
.batch-label { color:#303133; font-weight:600; }
.batch-option { color:#909399; font-size:15px; }
.batch-option.active { color:#1890ff; font-weight:600; }
.batch-switch { margin:0 2px; }
.batch-inline-field { display:flex; align-items:center; gap:8px; margin-left:20px; }
.batch-field { display:flex; align-items:center; gap:8px; min-width:230px; }
.batch-field > span { white-space:nowrap; color:#606266; }
.batch-field :deep(.el-input) { width:220px; }
.batch-field :deep(.el-input-number) { width:150px; }
.batch-field small { color:#909399; white-space:nowrap; }
.batch-action-row { min-height:52px; }
.batch-status { color:#606266; font-size:13px; flex:1; min-width:260px; }
.batch-help { line-height:1.8; }
.autonomy-card { border-left: 3px solid #722ed1; }
.autonomy-modes { display:flex; flex-direction:column; gap:8px; margin:4px 0 12px; }
.autonomy-mode { padding:8px 10px; border:1px solid #e3e8ef; border-radius:6px; cursor:pointer; transition:border-color .15s, background .15s; }
.autonomy-mode:hover { border-color:#b8a6dd; }
.autonomy-mode.active { border-color:#722ed1; background:#f7f4ff; }
.autonomy-mode-head { display:flex; align-items:center; gap:8px; color:#303133; }
.autonomy-mode-head b { font-size:13px; }
.autonomy-mode-radio { width:12px; height:12px; border-radius:50%; border:2px solid #c0c4cc; box-sizing:border-box; flex:0 0 auto; }
.autonomy-mode-radio.checked { border-color:#722ed1; background:#722ed1; box-shadow:inset 0 0 0 2px #fff; }
.autonomy-mode-desc { margin-top:4px; font-size:12px; color:#909399; line-height:1.55; }
.autonomy-toggles { display:flex; flex-direction:column; gap:10px; margin-bottom:10px; }
.autonomy-toggle-row { display:flex; align-items:flex-start; gap:10px; }
.autonomy-toggle-row :deep(.el-switch) { margin-top:2px; flex:0 0 auto; }
.autonomy-toggle-text b { font-size:13px; color:#303133; font-weight:600; }
.autonomy-toggle-text .form-hint { margin-top:2px; line-height:1.55; }
.autonomy-boundary { padding:8px 10px; background:#f6f8fb; border-radius:6px; font-size:12px; color:#606266; line-height:1.7; }
.consent-body { line-height:1.7; }
.consent-intro { margin:0 0 12px; color:#606266; font-size:13px; }
.consent-section { margin-bottom:14px; }
.consent-section-title { font-weight:700; margin-bottom:6px; font-size:13px; }
.consent-section-title.allowed { color:#67c23a; }
.consent-section-title.prohibited { color:#f56c6c; }
.consent-section ul { margin:0; padding-left:20px; }
.consent-section li { font-size:13px; color:#303133; margin-bottom:4px; }
.strategy-summary { padding:10px 12px; color:#606266; background:#f8fbff; border:1px solid #dbeafe; border-radius:6px; font-size:13px; line-height:1.6; }.gap-recovery-actions { margin:8px 0; }.studio-left :deep(.el-form-item__content) { min-width:0; }.studio-left :deep(.el-form-item__content) > * { min-width:0; max-width:100%; }.studio-left :deep(.el-select), .studio-left :deep(.el-input), .studio-left :deep(.el-input-number) { max-width:100%; min-width:0; }.studio-left :deep(.el-select__selected-item) { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.studio-left :deep(.el-radio-group) { min-width:0; display:flex; flex-wrap:wrap; gap:4px; }.studio-select-popper { z-index:3001 !important; max-width:calc(100vw - 24px); }.studio-select-popper .el-select-dropdown__item, .ai-route-popper .el-select-dropdown__item { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.folder-stage-row > * { max-width:100%; }.folder-stage-row :deep(.el-select) { width:min(100%, 220px) !important; }.studio-main :deep(.el-table) { max-width:100%; }.studio-main :deep(.el-table__body-wrapper) { overflow-x:auto; }@media (max-width: 1200px) { .studio-layout { flex-direction:column; } .studio-left { width:100%; flex-basis:auto; position:static; max-height:none; overflow:visible; padding-right:0; } .studio-main { width:100%; } .runtime-grid { grid-template-columns:repeat(2,minmax(160px,1fr)); } .batch-field { min-width:min(100%,230px); } .batch-field :deep(.el-input), .batch-field :deep(.el-input-number) { width:min(100%,220px); } .folder-stage-list { margin-left:0; } }@media (max-width:600px) { .duration-warning { margin-left:0; }.studio-audio-choice { grid-template-columns:1fr; }.studio-audio-choice :deep(.el-slider) { grid-column:auto; }.batch-inline-field { margin-left:0; width:100%; }.batch-field { width:100%; }.batch-field :deep(.el-input), .batch-field :deep(.el-input-number) { flex:1; width:auto; }.gap-grid { grid-template-columns:1fr; } }
.duration-range-item :deep(.el-form-item__content) {
  display: block;
  min-width: 0;
}
.duration-range-item :deep(.el-slider) {
  margin: 8px 12px 34px 4px;
}
.duration-guide { margin-bottom: 12px; }
.duration-warning { margin: -4px 0 12px 96px; }
.duration-unit { margin-left: 6px; color: #606266; }
.target-duration-item :deep(.el-input-number) { width: 150px; }
.duration-range-item .form-hint { margin-top: 0; }
.selected-materials { display:flex; gap:6px; flex-wrap:wrap; margin-top:8px; width:100%; }
.fixed-order-entry { display:flex; flex:1 1 230px; min-width:220px; flex-direction:column; gap:6px; }.fixed-order-actions { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }.fixed-order-option { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:2px 8px; align-items:center; padding:4px 0; line-height:1.35; }.fixed-order-option b { min-width:0; overflow:hidden; text-overflow:ellipsis; }.fixed-order-option span { grid-column:1 / -1; color:#909399; font-size:12px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.folder-stage-list { margin:-4px 0 12px 96px; display:flex; flex-direction:column; gap:8px; }.fixed-order-overview { display:grid; grid-template-columns:repeat(auto-fit,minmax(98px,1fr)); gap:6px; }.fixed-order-overview > div { display:flex; flex-direction:column; gap:2px; padding:8px 9px; border:1px solid #dbeafe; border-radius:6px; background:#f8fbff; }.fixed-order-overview span { color:#909399; font-size:11px; }.fixed-order-overview b { color:#303133; font-size:13px; }.fixed-order-overview .warning { border-color:#f3d19e; background:#fdf6ec; }.fixed-order-overview .warning b { color:#d46b08; }.folder-stage-toolbar { display:flex; align-items:center; justify-content:space-between; gap:8px; flex-wrap:wrap; }.fixed-order-summary { display:grid; grid-template-columns:repeat(auto-fit,minmax(140px,1fr)); gap:6px; }.fixed-order-summary span { display:flex; align-items:center; gap:6px; min-width:0; padding:7px 8px; border:1px solid #e3e8ef; border-radius:5px; color:#606266; font-size:12px; }.fixed-order-summary b { color:#409eff; }.fixed-order-summary i { margin-left:auto; color:#909399; font-style:normal; font-size:11px; }.fixed-order-summary i.ready { color:#67c23a; }.fixed-order-summary i.warning { color:#e6a23c; }.folder-stage-row { display:flex; align-items:center; gap:6px; flex-wrap:wrap; padding:8px; border:1px solid #dbeafe; border-radius:6px; background:#f8fbff; }.fixed-order-draft-intro { margin:0 0 14px; padding:9px 10px; border:1px solid #dbeafe; border-radius:6px; background:#f8fbff; color:#606266; font-size:13px; line-height:1.6; }
.gap-card { border-left: 3px solid #e6a23c; }
.gap-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(180px,1fr)); gap:10px; margin-bottom:10px; }
.gap-item { display:flex; flex-direction:column; gap:2px; padding:6px 10px; background:#f8fafc; border-radius:6px; }
.gap-label { font-size:11px; color:#909399; }
.gap-roles { display:flex; align-items:center; flex-wrap:wrap; gap:4px; margin-bottom:8px; }
.gap-notes { margin:6px 0 10px; padding:6px 10px; background:#fef0f0; border-radius:6px; }
.gap-actions { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin-top:6px; }
.structure-preview { display:flex; align-items:center; gap:10px; margin:0 0 10px; padding:8px 10px; border:1px solid #dbeafe; border-radius:6px; background:#f8fbff; color:#303133; font-size:13px; }
.structure-preview b { color:#409eff; }
.structure-preview span { color:#606266; }
.batch-live-panel { margin: 12px 0 16px; padding: 14px; border: 1px solid #b7ebc6; border-radius: 8px; background: #f6ffed; }
.batch-live-panel.batch-live-pending { border-color: #b3d8ff; background: #f0f7ff; }
.batch-live-panel.batch-live-paused { border-color: #f3d19e; background: #fdf6ec; }
.batch-live-panel.batch-live-working { border-color: #e6a23c; background: #fffaf0; }
.batch-live-header, .batch-live-stage-line, .batch-live-subline { display:flex; align-items:center; justify-content:space-between; gap:10px; }
.batch-live-title { display:flex; align-items:center; gap:8px; color:#1f2d3d; }
.batch-live-refresh, .batch-live-subline { color:#6b7785; font-size:12px; }
.live-dot { width:10px; height:10px; border-radius:50%; background:#67c23a; box-shadow:0 0 0 4px rgba(103,194,58,.16); animation: batch-pulse 1.4s ease-in-out infinite; }
.live-dot.paused { background:#e6a23c; box-shadow:0 0 0 4px rgba(230,162,60,.16); animation:none; }
.batch-live-main { display:flex; gap:18px; align-items:center; margin-top:14px; }
.batch-live-number { flex:0 0 92px; display:flex; flex-direction:column; gap:3px; color:#6b7785; font-size:12px; }
.batch-live-count { color:#1f2d3d; font-size:30px; font-weight:700; line-height:1; }
.batch-live-count small { color:#8a96a3; font-size:16px; font-weight:500; }
.batch-live-stage { flex:1; min-width:0; }
.batch-live-stage-line b { color:#303133; }
.batch-live-stage-line span { color:#606266; font-size:12px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.batch-live-subline { margin-top:6px; }
.batch-live-tip { margin-top:10px; padding-top:9px; border-top:1px solid rgba(103,194,58,.22); color:#536171; font-size:12px; line-height:1.6; }
.batch-live-pending .batch-live-tip { border-top-color:rgba(64,158,255,.22); }
.batch-live-paused .batch-live-tip { border-top-color:rgba(230,162,60,.25); }
.preflight-summary { margin:0 0 12px; padding:12px 14px; border:1px solid #dcdfe6; border-radius:6px; background:#fff; }
.preflight-summary.preflight-ready { border-color:#b3e19d; background:#f0f9eb; }
.preflight-summary.preflight-warning { border-color:#f3d19e; background:#fffaf0; }
.preflight-summary.preflight-blocked, .preflight-summary.preflight-needs_user_action { border-color:#f5b7b1; background:#fff5f4; }
.preflight-summary-head, .preflight-summary-metrics, .preflight-issue { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.preflight-summary-head { margin-bottom:8px; }
.preflight-summary-metrics { color:#606266; font-size:12px; gap:16px; }
.preflight-issues { margin-top:8px; display:grid; gap:5px; }
.preflight-issue { font-size:12px; line-height:1.5; }
.preflight-issue.blocker { color:#b42318; }
.preflight-issue.warning { color:#8a5a00; }
.batch-preflight-state { display:flex; align-items:center; gap:12px; margin-top:14px; padding:16px; border:1px dashed #e6a23c; border-radius:6px; color:#6b4b13; background:#fffdf5; }
.batch-preflight-state b { display:block; color:#8a5a00; margin-bottom:4px; }
.batch-preflight-state div:last-child { color:#8b7a5a; font-size:12px; line-height:1.6; }
.batch-preflight-spinner { width:22px; height:22px; flex:0 0 22px; border:3px solid #f3d19e; border-top-color:#e6a23c; border-radius:50%; animation: batch-spin .8s linear infinite; }
.batch-live-stuck { border-color:#f56c6c; background:#fff5f5; }
.batch-live-stuck .batch-live-tip { border-top-color:rgba(245,108,108,.28); color:#a94442; }
.prepare-stage-list { display:flex; flex-direction:column; gap:6px; margin-top:8px; }
.prepare-stage-row { display:flex; align-items:flex-start; gap:8px; line-height:1.55; }
.prepare-live-stage { display:flex; align-items:center; gap:8px; margin-top:8px; flex-wrap:wrap; }
.prepare-live-stage b { color:#8a5a00; }
.prepare-live-meta { display:flex; gap:14px; margin-top:6px; color:#8b7a5a; font-size:12px; flex-wrap:wrap; }
.prepare-source-issues { display:flex; flex-direction:column; gap:4px; }
.prepare-source-issue { display:flex; align-items:flex-start; gap:8px; font-size:12px; line-height:1.6; color:#606266; }
.prepare-source-name { font-weight:600; color:#303133; white-space:nowrap; }
.task-issues { display:flex; flex-direction:column; gap:8px; }
.task-issue { padding:8px 10px; border-left:3px solid #f56c6c; background:#fff5f5; border-radius:4px; line-height:1.55; }
.task-issue-title { color:#c45656; font-weight:600; }
.task-issue-reason, .task-issue-action { color:#606266; font-size:12px; }
@keyframes batch-pulse { 0%,100% { opacity:1; transform:scale(1); } 50% { opacity:.45; transform:scale(.82); } }
@media (max-width: 600px) { .duration-warning { margin-left: 0; } .batch-live-main { align-items:flex-start; } .batch-live-number { flex-basis:70px; } .batch-live-header { align-items:flex-start; flex-direction:column; } .batch-live-subline { align-items:flex-start; flex-direction:column; gap:3px; } }
</style>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { FIXED_ORDER_NAME, FIXED_ORDER_PRESETS, cloneFixedOrderPreset } from '../fixedOrderPresets'

const legendItems = [
  { key: 'intro', label: '片头', color: '#722ed1' },
  { key: 'hook', label: '钩子', color: '#f56c6c' },
  { key: 'body', label: '实拍', color: '#409eff' },
  { key: 'celebrity', label: '明星', color: '#e6a23c' },
  { key: 'product', label: '产品', color: '#67c23a' },
  { key: 'endcard', label: '片尾', color: '#909399' }
]
function slotLegend (slot) { return legendItems.find((item) => item.key === slot) }
function slotLabel (slot) { return slotLegend(slot)?.label || slot || '未分类' }
function slotColor (slot) { return slotLegend(slot)?.color || '#909399' }
const ROLE_LABEL = Object.fromEntries(legendItems.map((item) => [item.key, item.label]))
const route = useRoute()
const router = useRouter()
const STATUS_LABEL = { pending: '排队', running: '渲染', paused: '已暂停', awaiting_decision: '等待决策', done: '完成', failed: '失败', cancelled: '已取消' }
const STATUS_TYPE = { pending: 'info', running: '', paused: 'warning', awaiting_decision: 'warning', done: 'success', failed: 'danger', cancelled: 'info' }

const projects = ref([])
const folders = ref([])
const runtime = reactive({})
const queue = ref(null)
const workflows = ref([])
const bgmList = ref([])
const voiceList = ref([])
const visualList = ref([])
const jobs = ref([])
const selectedJobIds = ref([])
const jobLive = reactive({})
const initialLoading = ref(false)
const initialError = ref(false)
const runtimeUnavailable = ref(false)
const jobsLoading = ref(false)
let queueRequest = null
async function loadQueue () {
  if (queueRequest) return queueRequest
  queueRequest = api.queue({ silent: true })
    .then((result) => {
      queue.value = result
      runtimeUnavailable.value = false
      return result
    })
    .catch(() => {
      queue.value = null
      runtimeUnavailable.value = true
      return null
    })
    .finally(() => { queueRequest = null })
  return queueRequest
}
const jobsError = ref(false)

const projectId = ref(null)
const workflowId = ref(null)
const fixedOrderSkillKey = ref('')
const showAllFolderSteps = ref(false)
const variant = ref(0)
const count = ref(5)
const continuous = ref(localStorage.getItem('mixcut-continuous') === '1')
const jobName = ref('')
// 0 表示使用服务端默认值；服务端按秒存储并限制最大值。
const jobTimeoutMin = ref(0)
const jobStaleMin = ref(0)
const plan = ref(null)
const preflight = ref(null)
const dry = ref(false)
const preparing = ref(false)
const prepareCancelled = ref(false)
const preparationBackground = ref(false)
const submitting = ref(false)
let dryRunRevision = 0

const materialGap = ref(null)
const preparationResult = ref(null)
// 异步出片准备：POST /api/jobs/prepare 立即返回 { id, status }，随后按固定间隔轮询 GET /api/jobs/prepare/{id}
const PREPARE_POLL_MS = 3000
const PREPARE_POLL_MAX_MS = 240000
const preparingSnapshot = ref(null)
const preparationPanelActive = computed(() => preparing.value || preparationBackground.value)
const preparationPanelMessage = computed(() => preparationBackground.value
  ? '准备任务仍在后台执行；页面会继续检查状态。当前可先查看上方来源与入库诊断，不要重复提交。'
  : '')
const usePreparedPoolForRun = ref(false)
const autoUseCrawled = ref(true)
const materialSourceMode = ref(localStorage.getItem('mework-material-source-mode') || 'local')
// 公开素材自动补齐细粒度开关：安全映射到既有 materialSourceMode / autoUseCrawledMaterials 参数。
// 仅影响许可可验证的免登录公开源（Wikimedia / Archive），不安装组件或调用需密钥的云 API。
const publicAutoFill = computed({
  get: () => materialSourceMode.value !== 'local',
  set: (enabled) => {
    localStorage.setItem('mework-autonomy-auto-fill', enabled ? '1' : '0')
    if (enabled && materialSourceMode.value === 'local') materialSourceMode.value = 'builtin'
    if (!enabled && materialSourceMode.value !== 'local') materialSourceMode.value = 'local'
  }
})
const autonomyConsentDlg = ref(false)
const consentTargetMode = ref('assist')
let consentResolver = null
const consentModeLabel = computed(() => AUTONOMY_MODES.find((mode) => mode.key === consentTargetMode.value)?.label || consentTargetMode.value)
function readAutonomyConsent () {
  try {
    const raw = JSON.parse(localStorage.getItem(AUTONOMY_CONSENT_KEY) || '{}')
    return raw && typeof raw === 'object' ? raw : {}
  } catch { return {} }
}
function autonomyConsentGranted (mode) {
  const consent = readAutonomyConsent()
  return consent[mode] === '1' || Number(consent[mode]) > 0
}
function requestAutonomyConsent (mode) {
  return new Promise((resolve) => {
    consentTargetMode.value = mode
    consentResolver = resolve
    autonomyConsentDlg.value = true
  })
}
function onConsentConfirm () {
  const consent = readAutonomyConsent()
  consent[consentTargetMode.value] = String(Date.now())
  localStorage.setItem(AUTONOMY_CONSENT_KEY, JSON.stringify(consent))
  autonomyConsentDlg.value = false
  const resolve = consentResolver
  consentResolver = null
  if (resolve) resolve(true)
}
function onConsentCancel () {
  autonomyConsentDlg.value = false
  const resolve = consentResolver
  consentResolver = null
  if (resolve) resolve(false)
}
const draftSource = ref('当前草稿')
const activeIndustryPreset = ref('')
const industryPresetSnapshot = ref(null)
const gapLoading = ref(false)
const autoFillLoading = ref(false)
const autoFillResult = ref(null)
const hasVisualRoleGap = computed(() => (materialGap.value?.missingRoles || [])
  .some((role) => ['hook', 'body', 'product', 'celebrity', 'endcard'].includes(role)))
const hasPublicVisualRoleGap = computed(() => (materialGap.value?.missingRoles || [])
  .some((role) => ['hook', 'body', 'celebrity'].includes(role)))
const autoRefresh = ref(true)
const subtitleCoverForm = reactive({ materialId: null, x: 0, y: 0, width: 320, height: 120, color: 'black@1.0', start: 0, end: 5 })
const subtitleCoverSubmitting = ref(false)
const subtitleAnalysis = ref(null)
const coverStage = ref(null)
const coverDrag = reactive({ active: false, startX: 0, startY: 0 })
const advancedOpen = ref(false)
const strategyOpen = ref(false)
const fixedOrderOpen = ref(false)
const fixedOrderDraftDialogOpen = ref(false)
const fixedOrderDraftForm = reactive({ name: '', stepsText: '' })
const batchOptionsOpen = ref(false)
const jobHistoryOpen = ref(false)
const jobDlg = ref(false)
const jobDetail = ref(null)
let timer = null

const DEFAULTS = {
  minSec: 50, maxSec: 150, dense: true, targetSec: null, targetDurationSec: null,
  marketingStructure: '123234',
  durationAllocationMode: 'random', minSegmentSec: 1.5, maxSegmentSec: 5,
  introEnabled: false, introMode: 'fixed', introMaterialId: null, introDurationSec: 3, introNoRepeat: true, introAllowRepeatWhenInsufficient: false,
  materialSelectionMode: 'rules-only', projectRelevantOnly: true, folderIds: [], materialIds: [],
  sliceSec: 3, sliceJitter: 0.4, explodeLongClips: true, maxSlicesPerMaterial: 5,
  dedupStrictness: 'strict', allowSameSourceNonoverlap: true,
  hookSec: 3, celebrityRatio: 0.25, productSlots: 3, productSec: 3, endcard: true, endcardSec: 3, requireDedicatedEndcard: false,
  width: 1080, height: 1920, fps: 30,
  bgmVolume: 0.22, bgmMaterialId: null, voiceMaterialId: null,
      hookAudioMaterialId: null, autoMatchAudio: true, hookAudioVolume: 1.0, autoUseCrawledMaterials: true,
      folderReadSteps: [], strictFolderSequence: false,
      audioMode: 'material-audio', originalAudioVolume: 0.28, ttsVoice: 'zh-CN-XiaoxiaoNeural', autoSubtitles: false,
      burnAiVoiceCaptions: true, cleanSourceSubtitles: false, sourceSubtitleCleanMode: 'off',
      aiHook: true, hookText: '', burnHookText: true,
  hookStrategy: '', autoRehook: false, rehookText: '', burnRehookText: true,
  hookFontSize: 64, hookFontColor: 'white',
  fontFile: '', namePrefix: 'mix'
}
const INDUSTRY_PRESETS = {
  beauty: { minSec: 50, maxSec: 90, dense: true, sliceSec: 2.5, sliceJitter: 0.35, celebrityRatio: 0.35, productSlots: 4, productSec: 2.5, hookSec: 3, bgmVolume: 0.2, namePrefix: 'beauty', folderKeywords: ['美妆'], bgmKeywords: ['美妆', '轻快'], voiceKeywords: ['美妆', '口播'] },
  skincare: { minSec: 60, maxSec: 110, dense: true, sliceSec: 3, sliceJitter: 0.3, celebrityRatio: 0.2, productSlots: 3, productSec: 3.5, hookSec: 3.5, bgmVolume: 0.18, namePrefix: 'skincare', folderKeywords: ['护肤'], bgmKeywords: ['舒缓', '护肤'], voiceKeywords: ['成分', '讲解'] },
  food: { minSec: 50, maxSec: 80, dense: true, sliceSec: 2, sliceJitter: 0.45, celebrityRatio: 0.1, productSlots: 4, productSec: 2.5, hookSec: 2.5, bgmVolume: 0.24, namePrefix: 'food', folderKeywords: ['食品'], bgmKeywords: ['欢快', '食品'], voiceKeywords: ['吃播', '口播'] },
  maternal: { minSec: 50, maxSec: 90, dense: false, sliceSec: 2.5, sliceJitter: 0.25, celebrityRatio: 0.15, productSlots: 3, productSec: 3, hookSec: 3, bgmVolume: 0.16, namePrefix: 'maternal', folderKeywords: ['母婴'], bgmKeywords: ['温馨'], voiceKeywords: ['母婴', '口播'] },
  digital: { minSec: 45, maxSec: 75, dense: true, sliceSec: 2, sliceJitter: 0.3, celebrityRatio: 0.05, productSlots: 4, productSec: 2.5, hookSec: 2.5, bgmVolume: 0.2, namePrefix: 'digital', folderKeywords: ['数码', '3C'], bgmKeywords: ['科技'], voiceKeywords: ['评测'] }
}
const PRESET_LABEL = { beauty: '美妆', skincare: '护肤', food: '食品', maternal: '母婴', digital: '3C 数码' }
// AI 生产模式：只决定本次出片的授权边界（assist/auto/autonomous），不驱动任何新的外部能力。
const AUTONOMY_MODES = [
  { key: 'assist', label: '辅助模式', desc: 'AI 只做建议与预检，出片由你手动确认后执行；与既有流程完全一致，无额外授权' },
  { key: 'auto', label: '半自动模式', desc: 'AI 按当前参数执行；公开素材补齐必须在本次提交中明确确认，失败项按安全策略自动修复' },
  { key: 'autonomous', label: '自主模式', desc: '全流程自主生产：自动补齐、自动渲染、严格质检与自动修复；默认开启严格交付' }
]
const AUTONOMY_CONSENT_KEY = 'mework-autonomy-consent'
const autonomyMode = ref(localStorage.getItem('mework-autonomy-mode') || 'assist')
if (!AUTONOMY_MODES.some((mode) => mode.key === autonomyMode.value)) autonomyMode.value = 'assist'
const p = reactive({ ...DEFAULTS })
// 恢复上次会话的自主模式时，让严格交付开关的显示与生效值一致（默认开启）。
if (autonomyMode.value === 'autonomous' && p.strictDelivery == null) p.strictDelivery = true
const visibleFolderReadSteps = computed(() => p.folderReadSteps)
const fixedOrderPreset = computed(() => FIXED_ORDER_PRESETS.find((preset) => preset.key === fixedOrderSkillKey.value) || null)
const fixedOrderSourceLabel = computed(() => fixedOrderPreset.value?.name || (p.strictFolderSequence ? (draftSource.value || '自定义顺序草稿') : '未启用'))
const fixedOrderEnabledSteps = computed(() => (p.folderReadSteps || []).filter((step) => step?.enabled !== false))
const fixedOrderEnabledStepCount = computed(() => fixedOrderEnabledSteps.value.length)
const fixedOrderRequiredSteps = computed(() => fixedOrderEnabledSteps.value.filter((step) => step?.required !== false))
const fixedOrderRequiredStepCount = computed(() => fixedOrderRequiredSteps.value.length)
const fixedOrderBoundCount = computed(() => fixedOrderRequiredSteps.value.filter((step) => step?.folderId).length)
const fixedOrderRequiredUnboundCount = computed(() => Math.max(0, fixedOrderRequiredStepCount.value - fixedOrderBoundCount.value))
const fixedOrderTargetSeconds = computed(() => Math.round(fixedOrderEnabledSteps.value.reduce((total, step) => total + Math.max(0, Number(step?.targetSec) || 0), 0)))

const durRange = computed({
  get: () => [p.minSec, p.maxSec],
  set: (v) => { p.minSec = v[0]; p.maxSec = v[1] }
})
const celebPct = computed({
  get: () => Math.round((p.celebrityRatio || 0) * 100),
  set: (v) => { p.celebrityRatio = v / 100 }
})
const bgmPct = computed({
  get: () => Math.round((p.bgmVolume || 0) * 100),
  set: (v) => { p.bgmVolume = v / 100 }
})
const originalAudioPct = computed({
  get: () => Math.round((p.originalAudioVolume || 0) * 100),
  set: (v) => { p.originalAudioVolume = v / 100 }
})
const hookAudioPct = computed({
  get: () => Math.round((p.hookAudioVolume || 0) * 100),
  set: (v) => { p.hookAudioVolume = v / 100 }
})
const canvasKey = computed({
  get: () => `${p.width}x${p.height}`,
  set: (v) => { const [w, h] = v.split('x'); p.width = +w; p.height = +h }
})

const inRange = computed(() =>
  plan.value && plan.value.plannedSec >= p.minSec && plan.value.plannedSec <= p.maxSec)
const preflightBlocked = computed(() => preflight.value?.status === 'blocked' || preflight.value?.status === 'needs_user_action')
const preflightReady = computed(() => {
  if (!preflight.value) return false
  return preflight.value.status === 'ready' || preflight.value.status === 'warning'
})
const preflightAlertType = computed(() => {
  if (dry.value) return 'info'
  if (preflightReady.value) return preflight.value?.status === 'warning' ? 'warning' : 'success'
  return 'warning'
})
const preflightTagType = computed(() => preflightReady.value ? (preflight.value?.status === 'warning' ? 'warning' : 'success') : 'danger')
const preflightStatusLabel = computed(() => ({ ready: '通过', warning: '通过但有提示', blocked: '已阻断', needs_user_action: '需要处理' }[preflight.value?.status] || '未预检'))
function audioCoverageLabel(status) {
  return ({ not_required: '无需外部音频', ready: '已覆盖', missing_source: '缺少音频', insufficient_voice: '口播不足', invalid_mode: '模式无效' }[status] || '待检查')
}
const durationWarning = computed(() => {
  const value = Number(p.targetDurationSec)
  if (!value) return ''
  if (value < p.minSec) return `总时长 ${value} 秒低于当前下限 ${p.minSec} 秒，后端会自动收敛。`
  if (value > p.maxSec) return `总时长 ${value} 秒超过当前上限 ${p.maxSec} 秒，后端会自动收敛。`
  return ''
})
function normalizeDuration () {
  const value = Number(p.targetDurationSec)
  p.targetDurationSec = value < p.minSec ? p.minSec : p.maxSec
}
const targetSeconds = computed(() => p.targetDurationSec || p.targetSec || p.minSec)
const coverMaterial = computed(() => visualList.value.find(item => item.id === subtitleCoverForm.materialId) || null)
const coverSelectionStyle = computed(() => {
  const material = coverMaterial.value
  if (!material) return {}
  const width = Math.max(1, Number(material.width || 1080))
  const height = Math.max(1, Number(material.height || 1920))
  return { left: `${subtitleCoverForm.x / width * 100}%`, top: `${subtitleCoverForm.y / height * 100}%`, width: `${subtitleCoverForm.width / width * 100}%`, height: `${subtitleCoverForm.height / height * 100}%` }
})
const selectedVisuals = computed(() => {
  const selectedIds = new Set((p.materialIds || []).map(Number))
  const selectedFolders = new Set((p.folderIds || []).map(Number))
  const hasSelection = !usePreparedPoolForRun.value && (selectedIds.size > 0 || selectedFolders.size > 0)
  const selected = visualList.value.filter((material) => {
    const readable = material.status !== 'failed' && (material.fileType === 'image' || Number(material.durationSec) >= 1.0)
    if (!readable) return false
    return !hasSelection || selectedIds.has(Number(material.id)) || selectedFolders.has(Number(material.folderId))
  })
  return selected
})
const usableVisualSeconds = computed(() => selectedVisuals.value.reduce((total, material) => {
  return total + (material.fileType === 'image' ? 3 : Math.max(0, Number(material.durationSec) || 0))
}, 0))
const introCandidates = computed(() => selectedVisuals.value.filter((material) => material.fileType === 'image'
  || Number(material.durationSec || 0) + 0.05 >= Number(p.introDurationSec || 3)))
const introRequiredCount = computed(() => continuous.value ? 2 : Math.max(1, Number(count.value || 1)))
const introHint = computed(() => {
  if (p.introMode !== 'rotate') return '固定片头适合品牌声明；批量任务会重复同一开头。'
  const available = introCandidates.value.length
  if (!p.introNoRepeat) return `片头候选 ${available} 条；已允许重复。`
  if (available >= introRequiredCount.value) return `片头候选 ${available} 条，本批次需要 ${introRequiredCount.value} 条；将按批次轮换且不重复。`
  return p.introAllowRepeatWhenInsufficient
    ? `片头候选仅 ${available} 条，少于本批次 ${introRequiredCount.value} 条；已按你的选择允许轮换后重复。`
    : `片头候选仅 ${available} 条，少于本批次 ${introRequiredCount.value} 条；提交会阻断，请补充候选或明确允许重复。`
})
const activeContinuousJob = computed(() => jobs.value.find((job) => job.continuous && ['running', 'pending', 'paused', 'awaiting_decision'].includes(job.status)) || null)
const activeRenderJob = computed(() => {
  const active = jobs.value.find((job) => ['running', 'pending', 'paused', 'awaiting_decision'].includes(job.status))
  if (!active) return null
  return { ...active, ...(jobLive[active.id] || {}) }
})
const activeRenderLive = computed(() => activeRenderJob.value || {})
const liveCompletedCount = computed(() => Number(activeRenderLive.value.completedItems ?? activeRenderLive.value.current ?? 0))
const liveTotalCount = computed(() => Number(activeRenderLive.value.totalItems ?? activeRenderLive.value.total ?? activeRenderLive.value.count ?? 1))
const liveOverallProgress = computed(() => activeRenderLive.value.isContinuous
  ? Math.min(99, Math.round(liveCompletedCount.value * 100 / Math.max(1, liveTotalCount.value + 1)))
  : Math.max(0, Math.min(100, Number(activeRenderLive.value.overallProgress ?? activeRenderLive.value.progress ?? 0))))
const liveItemProgress = computed(() => Math.max(0, Math.min(100, Number(activeRenderLive.value.currentItemProgress ?? activeRenderLive.value.phaseProgress ?? 0))))
const activeRenderJobStatus = computed(() => ({ running: '正在处理', pending: '等待资源', paused: '已暂停', awaiting_decision: '等待决策' }[activeRenderJob.value?.status] || '未知状态'))
const livePanelTitle = computed(() => {
  if (preparing.value) return '正在准备本次出片素材'
  if (dry.value) return '正在做出片预检'
  if (submitting.value) return '正在提交出片任务'
  if (activeRenderJob.value?.status === 'awaiting_decision') return '任务等待处理选择'
  if (activeRenderJob.value?.status === 'paused') return '任务已暂停。'
  if (activeRenderJob.value?.status === 'pending') return '任务正在排队'
  return '后台正在出片'
})
const livePanelTag = computed(() => {
  if (preparing.value) return '素材准备中。'
  if (dry.value) return '预检中。'
  if (submitting.value) return '提交中。'
  return activeRenderJobStatus.value
})
const livePanelTagType = computed(() => {
  if (preparing.value || dry.value || submitting.value) return 'warning'
  return activeRenderJob.value?.status === 'running' ? 'success' : 'warning'
})
const liveCurrentItemText = computed(() => {
  const current = Math.min(liveTotalCount.value, liveCompletedCount.value + (activeRenderLive.value.status === 'running' ? 1 : 0))
  return activeRenderLive.value.isContinuous ? `连续生成 · 已产出 ${current} 条` : `已产出 ${current} / ${liveTotalCount.value} 条`
})
const livePhaseText = computed(() => translateTechnicalText(activeRenderLive.value.phaseLabel || activeRenderLive.value.step || activeRenderLive.value.summary || '等待调度'))
const liveElapsedText = computed(() => formatDuration(activeRenderLive.value.elapsedSec))
const liveEtaText = computed(() => activeRenderLive.value.isContinuous ? '' : formatDuration(activeRenderLive.value.etaSec))
const liveHeartbeatText = computed(() => {
  const value = activeRenderLive.value.lastHeartbeatAt
  if (!value) return '等待首次心跳'
  const age = Math.max(0, Math.round((Date.now() - new Date(value).getTime()) / 1000))
  return age <= 8 ? '刚刚收到进度' : `${age} 秒前有进度`
})
const liveActionText = computed(() => {
  if (preparing.value) return '正在先匹配本地素材；仅在仍有缺口时使用固定公开来源补齐，并在限定时间内等待成功入库的素材。'
  if (activeRenderLive.value.status === 'awaiting_decision') return '自动修复已停止并保留诊断证据。请在下方任务表使用“按现有策略继续”或“重试失败项”，也可放弃未完成任务。'
  if (activeRenderLive.value.status === 'paused') return '任务已暂停，已完成成片会保留；点击“继续生成”后从下一条继续。'
  if (activeRenderLive.value.status === 'pending') return '任务已提交，正在等待渲染资源；页面会持续刷新，不需要重复点击开始。'
  const age = activeRenderLive.value.lastHeartbeatAt
    ? Math.max(0, Math.round((Date.now() - new Date(activeRenderLive.value.lastHeartbeatAt).getTime()) / 1000)) : 0
  if (age > 20) return `已 ${age} 秒没有新的进度心跳，可能停在媒体处理阶段；请先观察。超过任务保护时间仍无变化时，可取消任务后检查素材和媒体工具再重新提交。`
  return '任务正在后台处理；当前阶段完成后会自动进入下一条，失败会在任务说明中给出处理方法'
})
const hasExternalAudio = computed(() => Boolean(plan.value?.voicePath || plan.value?.bgmPath))
const needsExternalAudio = computed(() => Boolean(plan.value?.requiresExternalAudio))
const hasAudioCoverage = computed(() => !needsExternalAudio.value || Boolean(plan.value?.bgmPath)
  || Number(plan.value?.voiceDurationSec || 0) + 0.5 >= Number(plan.value?.plannedSec || 0))
const preflightMessage = computed(() => {
  if (dry.value) return '正在按当前参数执行干跑预检，确认时长、素材质量和音频覆盖。'
  if (!preflight.value) {
    if (!runtime.ffmpeg || !runtime.ffprobe) return '未检测到媒体处理引擎或媒体探测工具，无法安全开始出片。请在环境中心完成配置。'
    if (!selectedVisuals.value.length) return '当前筛选范围没有可读取的画面素材：检查指定素材、授权文件夹，或回到素材库重新探测失败文件。'
    return `点击“开始”会先自动完成干跑；当前素材池约 ${usableVisualSeconds.value.toFixed(1)} 秒。`
  }
  const firstBlocker = preflight.value.blockers?.[0]
  if (firstBlocker?.message) return `预检已阻断：${firstBlocker.message}`
  const firstWarning = preflight.value.warnings?.[0]
  if (firstWarning?.message) return `预检通过但有提示：${firstWarning.message}`
  return `预检通过：计划 ${Number(preflight.value.plannedSec || 0).toFixed(1)} 秒，满足 ${Number(preflight.value.minSec || p.minSec).toFixed(0)}–${Number(preflight.value.maxSec || p.maxSec).toFixed(0)} 秒交付区间。`
})

function countSlot(k) {
  return plan.value ? plan.value.segments.filter((s) => s.slot === k).length : 0
}
function segTitle(s) {
  return `${slotLabel(s.slot)} · ${s.materialName} · ${s.sourceStart.toFixed(1)}s · ${s.duration.toFixed(2)}s`
}
function fileUrl(output) {
  if (typeof output === 'object') return output.publicUrl || output.fileUrl || fileUrl(output.filePath)
  const name = String(output || '').replace(/\\/g, '/').split('/').pop()
  return api.protectedUrl(`/files/output/${name}`)
}
function formatDuration(seconds) {
  const s = Math.max(0, Number(seconds) || 0)
  if (s < 60) return `${Math.round(s)}秒`
  return `${Math.floor(s / 60)}分 ${Math.round(s % 60)}秒`
}

const TECHNICAL_TRANSLATIONS = [
  [/\bAI voice\b|\bai-voice\b/gi, 'AI 人声'],
  [/\bBGM\b/gi, '背景音乐'],
  [/\bASR\b/gi, '语音识别'],
  [/\bOCR\b/gi, '画面文字识别'],
  [/\bFFmpeg\b/gi, '媒体处理引擎'],
  [/\bffprobe\b/gi, '媒体探测'],
  [/\bQC\b/gi, '成品质检'],
  [/\bducking\b/gi, '背景音乐自动压低'],
  [/\bconcat\b/gi, '视频拼接'],
  [/\bmux(ing|ed)?\b/gi, '音视频合'],
  [/\bthumbnail\b/gi, '封面生成'],
  [/\bpending\b/gi, '排队'],
  [/\brunning\b/gi, '处理'],
  [/\bfailed\b/gi, '失败'],
  [/\bcompleted\b/gi, '已完']
]

function translateTechnicalText (value) {
  let text = String(value ?? '')
  TECHNICAL_TRANSLATIONS.forEach(([pattern, replacement]) => { text = text.replace(pattern, replacement) })
  return text
}

function issueAction (text) {
  const value = String(text || '')
  if (/静音|音频|口播|BGM|背景音乐|声音/.test(value)) {
    return '检查口播文件是否可播放；没有覆盖全片时，补充一条可读背景音乐，或重新选择声音模式后先执行干跑'
  }
  if (/黑屏|画面|视频|不可解码/.test(value)) {
    return '回到素材库重新探测失败素材，移除黑屏或损坏片段后重新预览'
  }
  if (/时长|截断|音画|同步/.test(value)) {
    return '缩短文案或调整目标时长，确保声音与画面时长一致，再重新执行干跑'
  }
  if (/重复|同源|钩子/.test(value)) {
    return '补充不同素材或修改钩子策略后重试；不要连续重复提交同一批参数'
  }
  if (/超时|无活动|卡住|等待/.test(value)) {
    return '先观察当前阶段；超过保护时间仍无心跳时取消任务，检查素材和媒体工具后重新提交'
  }
  return '打开任务详情和素材库检查对应阶段，修正后重新执行干跑再出片'
}

function taskHasFailure (job) {
  const raw = String(job?.error || '').trim()
  if (!raw) return false
  const entries = raw.split(/\r?\n/).map((entry) => entry.trim()).filter(Boolean)
  // Older completed jobs persisted QC pass/warn text in Job.error. Treat those as review hints,
  // while retaining true render failures and blocked QC results as actionable task errors.
  return entries.some((entry) => !/^(?:第\s*\d+\s*条:\s*)?成品质检(?:通过|提示)：/.test(entry))
}

function taskIssueRows (job) {
  const raw = String(job?.error || '').trim()
  if (!raw || !taskHasFailure(job)) return []
  return raw.split(/\r?\n|(?=第\s*\d+\s*条)|(?<=输出)\s+(?=第\s*\d+\s*条)/)
    .map((item) => translateTechnicalText(item.trim()))
    .filter(Boolean)
    .map((text) => ({
      title: /质检未通过|质检失败/.test(text) ? '成品质检未通过' : '出片处理异常',
      text,
      action: issueAction(text)
    }))
}

function shortTaskText (row) {
  const text = String(row?.error || row?.summary || '')
  if (!text) return '-'
  if (row?.status === 'done' && /成品质检通过/.test(text)) return row.summary || '已完成并通过成品质检'
  if (row?.status === 'done' && /成品质检提示/.test(text)) return '已完成，成品质检建议复核；可在详情查看每条成片的质检报告'
  if (/Comparison method violates its general contract/.test(text)) return '计划排序异常，已停止；请刷新后重新干跑。'
  if (/没有可用音轨|没有可用 BGM|素材音轨模式会产生静音/.test(text)) return '缺少音轨：请导入 BGM/口播，或明确选择保留原片声音/AI 人声'
  if (/素材不足|部分素材切片失败|实际可用|短于计划/.test(text)) return '素材不足，未生成合格成片；请补素材或降低交付下限'
  if (/切片.*失败|拼接输出时长校验失败/.test(text)) return '素材切片异常，请到素材库重新探测后重试。'
  if (/超过时限|无活动/.test(text)) return '渲染超时或无活动，已安全停止'
  return text.length > 96 ? `${text.slice(0, 96)}…` : text
}

function resetSubtitleCoverArea () {
  const material = coverMaterial.value
  if (!material) return
  const width = Math.max(1, Number(material.width || 1080))
  const height = Math.max(1, Number(material.height || 1920))
  subtitleCoverForm.x = Math.round(width * 0.08)
  subtitleCoverForm.width = Math.round(width * 0.84)
  subtitleCoverForm.height = Math.max(40, Math.round(height * 0.14))
  subtitleCoverForm.y = Math.max(0, height - subtitleCoverForm.height - Math.round(height * 0.04))
  subtitleCoverForm.start = 0
  subtitleCoverForm.end = Math.max(0.1, Number(material.durationSec || 5))
}
function stagePoint (event) {
  const rect = coverStage.value?.getBoundingClientRect()
  if (!rect || !coverMaterial.value) return null
  const width = Math.max(1, Number(coverMaterial.value.width || 1080))
  const height = Math.max(1, Number(coverMaterial.value.height || 1920))
  return { x: Math.max(0, Math.min(width, (event.clientX - rect.left) / rect.width * width)), y: Math.max(0, Math.min(height, (event.clientY - rect.top) / rect.height * height)) }
}
function startCoverDrag (event) {
  const point = stagePoint(event)
  if (!point) return
  coverDrag.active = true
  coverDrag.startX = point.x
  coverDrag.startY = point.y
  subtitleCoverForm.x = Math.round(point.x)
  subtitleCoverForm.y = Math.round(point.y)
  subtitleCoverForm.width = 1
  subtitleCoverForm.height = 1
  event.currentTarget?.setPointerCapture?.(event.pointerId)
}
function moveCoverDrag (event) {
  if (!coverDrag.active) return
  const point = stagePoint(event)
  if (!point) return
  subtitleCoverForm.x = Math.round(Math.min(coverDrag.startX, point.x))
  subtitleCoverForm.y = Math.round(Math.min(coverDrag.startY, point.y))
  subtitleCoverForm.width = Math.max(1, Math.round(Math.abs(point.x - coverDrag.startX)))
  subtitleCoverForm.height = Math.max(1, Math.round(Math.abs(point.y - coverDrag.startY)))
}
function stopCoverDrag () { coverDrag.active = false }

async function analyzeSubtitleCandidate() {
  if (!subtitleCoverForm.materialId) return
  try {
    subtitleAnalysis.value = { status: 'running', message: '正在分析画面文字候选，不会自动遮盖。' }
    const analysis = await api.diagnoseMaterial(subtitleCoverForm.materialId)
    const texts = Array.isArray(analysis?.ocrTexts) ? analysis.ocrTexts.slice(0, 6).join('、') : ''
    subtitleAnalysis.value = { status: 'done', message: texts ? `检测到候选文字：${texts}。请按画面确认遮盖区域。` : '未检测到可用文字坐标；请按画面手动填写遮盖区域。' }
  } catch (error) {
    subtitleAnalysis.value = { status: 'failed', message: `候选分析失败：${error.message}` }
  }
}

async function submitSubtitleCover() {
  if (!subtitleCoverForm.materialId) return
  subtitleCoverSubmitting.value = true
  try {
    const task = await api.mediaToolSubtitleCover({ ...subtitleCoverForm })
    const completed = await waitForSubtitleCoverTask(task.id)
    const result = completed?.results?.[0]
    if (!result?.materialId) throw new Error(completed?.message || '遮盖任务未返回新素材')
    const sourceId = subtitleCoverForm.materialId
    if (Array.isArray(p.materialIds) && p.materialIds.includes(sourceId)) p.materialIds = p.materialIds.map(id => id === sourceId ? result.materialId : id)
    subtitleCoverForm.materialId = result.materialId
    await loadInitial()
    ElMessage.success('字幕遮盖已生成新素材并继承原文件夹；当前指定素材已自动替换，原素材未覆盖。')
  } catch (error) {
    ElMessage.error(`字幕遮盖失败：${error.message}`)
  } finally {
    subtitleCoverSubmitting.value = false
  }
}

async function waitForSubtitleCoverTask(id) {
  for (let attempt = 0; attempt < 120; attempt++) {
    const task = await api.mediaToolTask(id, { silent: true })
    if (task?.status === 'done') return task
    if (task?.status === 'failed') throw new Error(task.message || '字幕遮盖任务失败')
    await sleep(1000)
  }
  throw new Error('字幕遮盖处理超时，请稍后在素材库查看结果')
}

function onAudioModeChange (mode) {
  if (mode === 'ai-voice' && p.voiceMaterialId) {
    p.audioMode = 'material-audio'
    ElMessage.warning('已指定口播人声；请先清除该选择，才能主动启用 AI 人声')
    return
  }
  if (mode === 'silent') {
    p.voiceMaterialId = null
    p.bgmMaterialId = null
    p.hookAudioMaterialId = null
    p.autoSubtitles = false
    p.burnAiVoiceCaptions = false
    p.burnHookText = false
    p.cleanSourceSubtitles = false
    p.sourceSubtitleCleanMode = 'off'
    ElMessage.info('已启用真正静音视频：不会保留或生成任何音频流、字幕或字幕遮挡区')
  }
}

function onVoiceMaterialChange (voiceId) {
  if (voiceId && p.audioMode === 'ai-voice') {
    p.audioMode = 'material-audio'
    ElMessage.info('已锁定指定口播，AI 人声已关')
  }
}

function onSourceSubtitleCleanChange (enabled) {
  p.sourceSubtitleCleanMode = enabled ? 'subtitle-safe-band' : 'off'
}

const EIGHT_STAGE_NAMES = ['开头钩', '痛点场景', '真实反应', '原理说明', '产品展示', '使用过程', '购买理由', '行动收尾']

function makeFolderStep (name, order, targetSec = null) {
  return { order, name, folderId: null, fallbackFolderId: null, required: true, enabled: true,
    targetSec: targetSec || Math.max(3, Math.round((p.targetDurationSec || p.minSec || 80) / 8)),
    aiSelect: true, shortagePolicy: 'block' }
}

function loadEightStageDefaults () {
  p.folderReadSteps = EIGHT_STAGE_NAMES.map((name, index) => makeFolderStep(name, index + 1))
  showAllFolderSteps.value = false
}

function applyFixedOrderStages (name, stages, announce = true) {
  if (!Array.isArray(stages) || !stages.length || stages.length > 32) return
  resetIndustryPresetState()
  fixedOrderSkillKey.value = ''
  p.strictFolderSequence = true
  p.folderReadSteps = stages.map((step, index) => {
    const item = makeFolderStep(String(step?.name || `读取步骤 ${index + 1}`).slice(0, 80), index + 1, step?.targetSec)
    item.shortagePolicy = step?.shortagePolicy === 'fallback' ? 'fallback' : 'block'
    const match = firstMatch(folders.value, step?.folderKeywords)
    if (match) item.folderId = match.id
    return item
  })
  showAllFolderSteps.value = false
  draftSource.value = name || '固定顺序建议'
  if (announce) ElMessage.success(`已载入 ${name || '顺序建议'}，步骤和文件夹仍可继续修改`)
}

function applyFixedOrderPreset (key, announce = true) {
  const preset = cloneFixedOrderPreset(key)
  if (!preset) return
  fixedOrderSkillKey.value = preset.key
  applyFixedOrderStages(preset.name, preset.stages, announce)
  fixedOrderSkillKey.value = preset.key
}

function openFixedOrderSkill () {
  const key = fixedOrderSkillKey.value || FIXED_ORDER_PRESETS[0]?.key
  if (key) applyFixedOrderPreset(key)
}

function openFixedOrderDraft () {
  fixedOrderDraftForm.name = p.strictFolderSequence && p.folderReadSteps.length
    ? `${fixedOrderSourceLabel.value}草稿`
    : '自定义顺序草稿'
  fixedOrderDraftForm.stepsText = p.strictFolderSequence && p.folderReadSteps.length
    ? p.folderReadSteps.map((step) => step.name).join('\n')
    : '开场钩子\n痛点场景\n产品展示'
  fixedOrderDraftDialogOpen.value = true
}

function createFixedOrderDraft () {
  const names = String(fixedOrderDraftForm.stepsText || '')
    .split(/\r?\n/)
    .map((name) => name.trim())
    .filter(Boolean)
  if (!names.length) {
    ElMessage.warning('请至少填写一个步骤名称')
    return
  }
  if (names.length > 32) {
    ElMessage.warning('顺序草稿最多支持 32 个步骤')
    return
  }
  const title = String(fixedOrderDraftForm.name || '').trim().slice(0, 80) || '自定义顺序草稿'
  const stages = names.map((name, index) => ({ name: name.slice(0, 80), targetSec: null, shortagePolicy: 'block', folderKeywords: [] }))
  applyFixedOrderStages(title, stages, false)
  fixedOrderSkillKey.value = ''
  draftSource.value = title
  fixedOrderOpen.value = true
  fixedOrderDraftDialogOpen.value = false
  ElMessage.success(`已创建「${title}」，请按需绑定文件夹后再执行干跑`)
}

function addFolderReadStep () {
  const next = p.folderReadSteps.length + 1
  p.folderReadSteps.push(makeFolderStep(`读取步骤 ${next}`, next))
  fixedOrderOpen.value = true
}

function copyFolderReadStep (index) {
  const source = p.folderReadSteps[index]
  if (!source) return
  if (p.folderReadSteps.length >= 32) {
    ElMessage.warning('固定顺序最多支持 32 个步骤')
    return
  }
  const copy = makeFolderStep(`${String(source.name || '读取步骤').slice(0, 74)}副本`, index + 2, source.targetSec)
  copy.required = source.required !== false
  copy.enabled = source.enabled !== false
  copy.aiSelect = source.aiSelect !== false
  copy.shortagePolicy = source.shortagePolicy === 'fallback' ? 'fallback' : 'block'
  copy.folderId = null
  copy.fallbackFolderId = null
  p.folderReadSteps.splice(index + 1, 0, copy)
  p.folderReadSteps.forEach((step, stepIndex) => { step.order = stepIndex + 1 })
  fixedOrderOpen.value = true
  ElMessage.success(`已复制第 ${source.order} 步；文件夹绑定未带入`)
}

function removeFolderReadStep (index) {
  if (p.folderReadSteps.length <= 1) {
    ElMessage.warning('至少保留一个读取步')
    return
  }
  p.folderReadSteps.splice(index, 1)
  p.folderReadSteps.forEach((step, stepIndex) => { step.order = stepIndex + 1 })
}

function onStrictFolderSequenceChange (enabled) {
  if (enabled && !(p.folderReadSteps || []).length) {
    p.folderReadSteps = [makeFolderStep('开头钩', 1), makeFolderStep('痛点场景', 2), makeFolderStep('真实反应', 3)]
    showAllFolderSteps.value = false
  }
}

function clearStepFolder (step) {
  step.folderId = null
  step.fallbackFolderId = null
  step.shortagePolicy = 'block'
  ElMessage.success(`已取消第 ${step.order} 步的文件夹绑定`)
}

function resetIndustryPresetState () {
  activeIndustryPreset.value = ''
  industryPresetSnapshot.value = null
}

function resetDraft () {
  workflowId.value = null
  fixedOrderSkillKey.value = ''
  resetIndustryPresetState()
  draftSource.value = '当前草稿'
  applyParamPatch({}, { reset: true })
  ElMessage.success('已重置出片草稿；素材选择和固定顺序步骤会保留供你继续调整')
}

function clearWorkflow () {
  resetIndustryPresetState()
  workflowId.value = null
  const project = projects.value.find((item) => item.id === projectId.value)
  let patch = {}
  try { patch = project?.defaultParams ? JSON.parse(project.defaultParams) : {} } catch { patch = {} }
  applyParamPatch(patch, { reset: true })
  draftSource.value = project ? `项目「${project.name}」默认参数` : '基础默认参数'
  ElMessage.success('已撤销工作流，恢复可编辑的项目/基础参数')
}

function introPolicyError () {
  if (!p.introEnabled || p.introMode !== 'rotate' || !p.introNoRepeat || p.introAllowRepeatWhenInsufficient) return ''
  if (introCandidates.value.length < introRequiredCount.value) return `片头候选仅 ${introCandidates.value.length} 条，少于本批次 ${introRequiredCount.value} 条；请补充片头候选或明确允许不足时重复`
  return ''
}

function strictFolderSequenceError () {
  const introError = introPolicyError()
  if (introError) return introError
  if (!p.strictFolderSequence) return ''
  const steps = (p.folderReadSteps || []).filter((step) => step?.enabled !== false)
  if (!steps.length) return '请至少保留一个启用的读取步骤'
  const missing = steps.find((step) => step.required !== false && !step.folderId)
  if (missing) return `第 ${missing.order || '?'} 步「${missing.name || '未命名步骤'}」尚未选择应用内文件夹`
  // 同一文件夹被多个启用步骤绑定会导致读取边界不明确
  const seen = new Map()
  for (const step of steps) {
    if (!step.folderId) continue
    if (seen.has(step.folderId)) {
      return `第 ${step.order || '?'} 步「${step.name || '未命名步骤'}」与第 ${seen.get(step.folderId)} 步绑定了同一个文件夹`
    }
    seen.set(step.folderId, step.order || '?')
  }
  // 备用文件夹不允许跨步骤借用其他步骤的主文件。也不允许指向本步骤自。
  for (const step of steps) {
    if (step.shortagePolicy !== 'fallback' || !step.fallbackFolderId) continue
    if (step.fallbackFolderId === step.folderId) {
      return `第 ${step.order || '?'} 步「${step.name || '未命名步骤'}」的备用文件夹不能与主文件夹相同`
    }
    if (seen.has(step.fallbackFolderId)) {
      return `第 ${step.order || '?'} 步「${step.name || '未命名步骤'}」的备用文件夹与其他步骤的主文件夹冲突`
    }
  }
  return ''
}

function payload() {
  const o = { ...p }
  o.autoUseCrawledMaterials = materialSourceMode.value !== 'local'
  o.materialSourceMode = materialSourceMode.value
  // 冻结本次提交中。AI 生产模式；所有出片相关请求（干跑/缺口/准备/提交）共用此载荷。
  o.autonomyMode = autonomyMode.value
  // 自主模式默认开启严格交付；用户显式关闭时尊重选择。
  // 非自主旧流程不携带该字段，保持后端既有默认（不拦截），兼容性不变。
  if (autonomyMode.value === 'autonomous') o.strictDelivery = p.strictDelivery == null ? true : p.strictDelivery
  else delete o.strictDelivery
  if (!o.cleanSourceSubtitles) o.sourceSubtitleCleanMode = 'off'
  // Prepared public material is only widened for the current run; manual selections stay intact in the form.
  if (usePreparedPoolForRun.value) {
    delete o.materialIds
    delete o.folderIds
  }
  // Backstop for stale project defaults or programmatic values: manual narration is authoritative.
  if (o.voiceMaterialId) o.audioMode = 'material-audio'
  if (!o.targetSec) delete o.targetSec
  if (!o.targetDurationSec) delete o.targetDurationSec
  if (!o.hookText) delete o.hookText
  if (!o.hookStrategy) delete o.hookStrategy
  if (!o.rehookText) delete o.rehookText
  if (!o.bgmMaterialId) delete o.bgmMaterialId
  if (!o.voiceMaterialId) delete o.voiceMaterialId
  if (!o.hookAudioMaterialId) delete o.hookAudioMaterialId
  return o
}

function applyParamPatch (patch, { reset = false } = {}) {
  if (!patch || typeof patch !== 'object') return
  if (reset) {
    // 重置到默认，但保留用户手动配置：已选素材、文件夹、固定顺序步骤
    const keep = {
      materialIds: p.materialIds,
      folderIds: p.folderIds,
      folderReadSteps: p.folderReadSteps,
      strictFolderSequence: p.strictFolderSequence
    }
    Object.keys(DEFAULTS).forEach((key) => { p[key] = DEFAULTS[key] instanceof Array ? [...DEFAULTS[key]] : DEFAULTS[key] })
    p.materialIds = keep.materialIds || []
    p.folderIds = keep.folderIds || []
    p.folderReadSteps = keep.folderReadSteps || []
    p.strictFolderSequence = keep.strictFolderSequence || false
  }
  Object.entries(patch).forEach(([key, value]) => {
    if (Object.prototype.hasOwnProperty.call(DEFAULTS, key) && value !== undefined) {
      p[key] = Array.isArray(value) ? [...value] : value
    }
  })
  autoUseCrawled.value = p.autoUseCrawledMaterials !== false
  if (p.cleanSourceSubtitles && !p.sourceSubtitleCleanMode) p.sourceSubtitleCleanMode = 'subtitle-safe-band'
}

function workflowParamPatch (definition) {
  const parsed = typeof definition === 'string' ? (() => { try { return JSON.parse(definition) } catch { return {} } })() : (definition || {})
  const patch = {}
  for (const step of parsed.steps || []) {
    const skill = step.skill || step.name
    const args = step.args || {}
    if (skill === 'set_duration' || skill === 'set_slice' || skill === 'set_structure' || skill === 'set_canvas' || skill === 'set_quality' || skill === 'pick_audio') {
      Object.entries(args).forEach(([key, value]) => {
        const mapped = { jitter: 'sliceJitter', explode: 'explodeLongClips', maxPerMaterial: 'maxSlicesPerMaterial' }[key] || key
        if (Object.prototype.hasOwnProperty.call(DEFAULTS, mapped)) patch[mapped] = value
      })
    }
  }
  return patch
}

function onProjectChange (id) {
  resetIndustryPresetState()
  if (!id) {
    // 清空项目选择:参数恢复默认,保留手动选材与固定顺序配置。
    applyParamPatch({}, { reset: true })
    draftSource.value = '基础默认参数'
    ElMessage.success('已清除项目选择，参数恢复默')
    return
  }
  const pj = projects.value.find((x) => x.id === id)
  if (!pj) return
  let patch = {}
  try { patch = pj.defaultParams ? JSON.parse(pj.defaultParams) : {} } catch { patch = {} }
  applyParamPatch(patch, { reset: true })
  draftSource.value = pj.defaultParams ? `项目「${pj.name}」默认参数` : `项目「${pj.name}」`
  ElMessage.success(pj.defaultParams ? '已载入项目默认参数；你可以继续修' : '已选择项目；使用默认出片参')
}

async function onWorkflowChange (id) {
  resetIndustryPresetState()
  if (!id) {
    clearWorkflow()
    return
  }
  const wf = workflows.value.find((item) => item.id === id)
  if (!wf) return
  applyParamPatch(workflowParamPatch(wf.def))
  draftSource.value = `工作流「${wf.name}」建议`
  ElMessage.success(`已载入工作流「${wf.name}」的默认参数；你可以继续修改`)
}

function firstMatch (items, keywords) {
  const words = keywords || []
  return items.find((item) => words.some((word) => String(item.name || '').toLowerCase().includes(word.toLowerCase())))
}
function snapshotIndustryPreset () {
  return {
    params: Object.fromEntries(Object.keys(DEFAULTS).map((key) => [key, Array.isArray(p[key]) ? [...p[key]] : p[key]])),
    draftSource: draftSource.value
  }
}

function restoreIndustryPreset (snapshot) {
  if (!snapshot?.params) return
  Object.entries(snapshot.params).forEach(([key, value]) => { p[key] = Array.isArray(value) ? [...value] : value })
  autoUseCrawled.value = p.autoUseCrawledMaterials !== false
  draftSource.value = snapshot.draftSource || '当前草稿'
}

function clearIndustryPreset () {
  if (!activeIndustryPreset.value || !industryPresetSnapshot.value) return
  const label = activeIndustryPreset.value === 'longmix' ? '专业长混' : PRESET_LABEL[activeIndustryPreset.value]
  restoreIndustryPreset(industryPresetSnapshot.value)
  resetIndustryPresetState()
  ElMessage.success(`已取消 ${label}预设，并恢复应用前参数`)
}

function activateIndustryPreset (key, apply) {
  if (activeIndustryPreset.value === key) {
    clearIndustryPreset()
    return false
  }
  if (activeIndustryPreset.value) clearIndustryPreset()
  industryPresetSnapshot.value = snapshotIndustryPreset()
  activeIndustryPreset.value = key
  apply()
  draftSource.value = key === 'longmix' ? '专业长混剪预' : `行业预设「${PRESET_LABEL[key]}」`
  return true
}

function applyPreset(key) {
  const preset = INDUSTRY_PRESETS[key]
  if (!preset || !activateIndustryPreset(key, () => {})) return
  const { folderKeywords, bgmKeywords, voiceKeywords, ...params } = preset
  Object.assign(p, params)
  const matchedFolders = folders.value.filter((folder) => folderKeywords.some((word) => String(folder.name || '').toLowerCase().includes(word.toLowerCase())))
  p.folderIds = matchedFolders.map((folder) => folder.id)
  const bgm = firstMatch(bgmList.value, bgmKeywords)
  const voice = firstMatch(voiceList.value, voiceKeywords)
  p.bgmMaterialId = bgm?.id || null
  p.voiceMaterialId = voice?.id || null
  const fallback = [matchedFolders.length ? '' : '未匹配文件夹', bgm ? '' : '未匹配背景音', voice ? '' : '未匹配口'].filter(Boolean)
  ElMessage.success(`已应用「${PRESET_LABEL[key]}预设${fallback.length ? `（${fallback.join('、')}将按角色随机）` : '；已匹配目的素材'}`)
}

const HOOK_STRATEGY_LABEL = {
  CONFLICT: '冲突', RESULT: '结果', SUSPENSE: '悬念', REWARD: '奖励',
  COUNTERINTUITIVE: '反常', QUESTION: '提问', VISUAL_IMPACT: '视觉冲击'
}
function hookStrategyLabel(strategy) {
  return HOOK_STRATEGY_LABEL[strategy] || strategy
}

function applyLongMixPreset() {
  if (!activateIndustryPreset('longmix', () => {})) return
  Object.assign(p, {
    minSec: 120, maxSec: 150, dense: true, targetSec: null, targetDurationSec: null,
    sliceSec: 3.5, sliceJitter: 0.3, maxSlicesPerMaterial: 4, explodeLongClips: true,
    hookSec: 3, celebrityRatio: 0.25, productSlots: 4, productSec: 3.5, endcard: true,
    dedupStrictness: 'strict', allowSameSourceNonoverlap: true,
    autoRehook: true, namePrefix: 'longmix'
  })
  ElMessage.success('已应用专业长混剪预设（120–150 秒，严格同源不重复 + 自动中段再钩子）')
}

async function doDryRun() {
  const strictError = strictFolderSequenceError()
  if (strictError) {
    ElMessage.warning(strictError)
    return false
  }
  const revision = dryRunRevision
  dry.value = true
  try {
    const result = await api.dryRun({
      workflowId: workflowId.value, projectId: projectId.value,
      params: payload(), variant: variant.value
    })
    if (revision !== dryRunRevision) {
      ElMessage.warning('出片参数已变化，当前预检结果已失效；请检查参数后重新开始。')
      return false
    }
    const dryRunPlan = result?.plan || result
    const dryRunPreflight = result?.preflight || null
    plan.value = dryRunPlan
    preflight.value = dryRunPreflight
    if (!dryRunPlan?.segments?.length) {
      ElMessage.warning(dryRunPreflight?.blockers?.[0]?.message || '没排出片段，先去素材库导入并打好角色')
      doGapAnalysis({ silent: true })
      return false
    }
    const ready = dryRunPreflight
      ? (dryRunPreflight.status === 'ready' || dryRunPreflight.status === 'warning')
      : Boolean(dryRunPlan.usable) && dryRunPlan.plannedSec >= p.minSec && dryRunPlan.plannedSec <= p.maxSec
    if (!ready && (!dryRunPreflight || dryRunPreflight.blockers?.some((issue) => ['capacity', 'role', 'folder'].includes(issue.category)))) {
      doGapAnalysis({ silent: true })
    }
    return ready
  } catch (error) {
    if (revision === dryRunRevision) ElMessage.error(`干跑预检失败：${error.message || '请检查后端和素材状'}`)
    return false
  } finally {
    dry.value = false
  }
}

async function doGapAnalysis({ silent = false } = {}) {
  gapLoading.value = true
  try {
    materialGap.value = await api.materialGap({
      projectId: projectId.value,
      params: payload()
    })
  } catch (error) {
    if (!silent) ElMessage.error(`缺口分析失败：${error.message || '请检查后端状'}`)
  } finally {
    gapLoading.value = false
  }
}

function autoFillFolderId (roles) {
  if (p.strictFolderSequence) {
    const step = (p.folderReadSteps || []).find((item) => item?.enabled !== false && item.folderId && ['hook', 'body', 'celebrity'].some((role) => roles.includes(role)))
    return step?.folderId || null
  }
  return (p.folderIds || []).length === 1 ? p.folderIds[0] : null
}

async function doAutoFill() {
  if (autoFillLoading.value) return
  if (materialSourceMode.value === 'local') {
    ElMessage.info('当前为仅本地素材模式，不会访问外部来源。切换到“内置公开补齐”后可执行一次受控检索。')
    return
  }
  if (materialSourceMode.value === 'extended') {
    ElMessage.info('公开直链 / 官方授权模式需要在素材抓取页导入。已带入当前项目和关键词。')
    goToCrawlWithProject()
    return
  }
  const missingPublicRoles = (materialGap.value?.missingRoles || [])
    .filter((role) => ['hook', 'body', 'celebrity'].includes(role))
  if (!missingPublicRoles.length && materialGap.value?.sufficient) {
    ElMessage.info('本地素材已满足当前出片要求；产品和片尾角色请从本地素材库补充，不会使用公开 B-roll 替代。')
    return
  }
  autoFillLoading.value = true
  autoFillResult.value = null
  try {
    const sourceKeys = (materialGap.value?.usablePublicSources || [])
      .filter((source) => source?.needKey === 'false')
      .map((source) => source.key)
    autoFillResult.value = await api.materialAutoFill({
      projectId: projectId.value,
      params: payload(),
      sources: sourceKeys.length ? sourceKeys : undefined,
      perSource: 5,
      roles: missingPublicRoles,
      folderId: autoFillFolderId(missingPublicRoles)
    })
    if (autoFillResult.value.any) {
      ElMessage.success(`已排队 ${autoFillResult.value.totalItemsQueued} 条公开素材，可在素材抓取页面查看进度`)
      setTimeout(() => doGapAnalysis({ silent: true }), 2000)
    } else {
      const failed = (autoFillResult.value.sourceResults || []).filter((row) => row.status === 'failed' || row.status === 'skipped_breaker')
      ElMessage.warning(failed.length
        ? `公开来源请求失败：${failed.map((row) => row.source).join('、')}。可稍后重试或前往素材抓取导入公开直链。`
        : '公开来源可访问但没有匹配结果；产品和片尾素材请从本地素材库补充。')
    }
  } catch (error) {
    ElMessage.error(`自动填充失败：${error.message || '请检查后端状态'}`)
  } finally {
    autoFillLoading.value = false
  }
}

function goToCrawlWithProject() {
  const query = {}
  if (projectId.value) query.projectId = projectId.value
  if (materialGap.value?.projectKeyword) query.keyword = materialGap.value.projectKeyword
  const missing = materialGap.value?.missingRoles || []
  if (missing.includes('product') || missing.includes('endcard')) query.roleHint = missing.includes('product') ? 'product' : 'endcard'
  if (materialSourceMode.value === 'extended') query.mode = 'authorized'
  router.push({ path: '/crawl', query })
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function resumeRunningPreparation () {
  if (preparing.value || preparationBackground.value || preparationResult.value) return
  try {
    const tasks = await api.recentPreparationTasks({ silent: true })
    const running = (Array.isArray(tasks) ? tasks : []).find((task) => task?.status === 'running' && task.id != null)
    if (!running) return
    preparingSnapshot.value = running
    ElMessage.info('已恢复上一项素材准备任务的状态检查')
    void continuePreparationPolling(running.id)
  } catch {
    // Discovery is optional. A backend that is still initializing must not block ordinary dry-runs.
  }
}

async function continuePreparationPolling (taskId) {
  if (taskId == null) return
  preparationBackground.value = true
  const deadline = Date.now() + PREPARE_POLL_MAX_MS
  let consecutiveFailures = 0
  let terminal = false
  try {
    while (!prepareCancelled.value && preparationBackground.value && Date.now() < deadline) {
      await sleep(PREPARE_POLL_MS)
      try {
        const latest = await api.prepareRenderStatus(taskId, { silent: true })
        consecutiveFailures = 0
        preparingSnapshot.value = latest
        if (latest.status === 'running') continue
        terminal = true
        preparationResult.value = latest
        materialGap.value = latest.finalGap || latest.initialGap || materialGap.value
        if (latest.autoFill?.any) {
          ElMessage.info('后台素材准备已结束；新入库素材会在下一次预检时自动纳入。')
        }
        return
      } catch {
        consecutiveFailures += 1
        if (consecutiveFailures >= 5) break
      }
    }
  } finally {
    preparationBackground.value = false
    preparingSnapshot.value = null
    if (!terminal && !prepareCancelled.value) {
      ElMessage.warning('素材准备状态长时间未更新或后端不可达，已停止轮询；请刷新状态后重新开始出片。')
    }
    prepareCancelled.value = false
  }
}

function sourceStatusType (status) {
  return ({ queued: 'success', no_results: 'info', failed: 'danger', skipped_breaker: 'warning', local_required: 'warning', manual_only: 'info', unsupported: 'info' })[status] || 'info'
}
function sourceStatusLabel (status) {
  return ({ queued: '已排队', no_results: '无匹配', failed: '来源失败', skipped_breaker: '暂时熔断', local_required: '需本地素材', manual_only: '仅手动导入', unsupported: '不支持自动补齐' })[status] || '来源状态'
}
function sourceResultMessage (source) {
  if (source.message) return source.message
  if (source.status === 'no_results') return '来源可访问，但当前关键词没有符合许可和类型要求的素材。'
  if (source.status === 'queued') return `已提交 ${source.items || 0} 条素材到后台导入队列。`
  return '请查看来源配置或改用素材抓取页导入公开直链。'
}
const autoFillTitle = computed(() => {
  const results = autoFillResult.value?.sourceResults || []
  if (results.some((row) => row?.status === 'local_required')) return '当前缺口需要本地产品或片尾素材，未发起公开 B-roll 抓取'
  if (results.some((row) => row?.status === 'failed' || row?.status === 'skipped_breaker')) return '公开来源请求失败，已保留具体来源诊断'
  return '公开来源可访问，但当前关键词没有匹配的合规素材'
})
// 公开来源失败 / 熔断的行；queued / no_results 不属于问题。
function sourceIssueRows (autoFill) {
  return (autoFill?.sourceResults || []).filter((row) => row?.status === 'failed' || row?.status === 'skipped_breaker' || row?.status === 'local_required')
}
const preparingSourceIssues = computed(() => sourceIssueRows(preparingSnapshot.value?.autoFill))
const preparationSourceIssueRows = computed(() => sourceIssueRows(preparationResult.value?.autoFill))
// 轮询期间的当前阶段：优先取最后一个 working 阶段，否则取最后一条阶段记录。
const preparingStage = computed(() => {
  const stages = preparingSnapshot.value?.stages || []
  const working = [...stages].reverse().find((stage) => stage?.status === 'working')
  return working || stages[stages.length - 1] || null
})
const preparingStageName = computed(() => preparingStage.value?.name || '准备素材')
const preparingStageMessage = computed(() => preparingStage.value?.message || '')
const preparingElapsedSec = computed(() => Number(preparingSnapshot.value?.elapsedSec || 0))
const preparingWaitedSec = computed(() => Number(preparingSnapshot.value?.waitedSeconds || 0))
function prepareStageType (status) {
  if (status === 'done') return 'success'
  if (status === 'failed') return 'danger'
  return status === 'working' || status === 'warning' ? 'warning' : 'info'
}

// 异步出片准备：POST 立即拿到 id/status 快照；running 时按 3 秒间隔轮询到终态。
// 阶段、耗时与按来源失败/熔断信息会实时渲染到右侧面板。无论准备结果如何，
// 后续都会继续执行干跑——公开素材不可用只回退到本地素材，绝不静默中止出片流程。
async function prepareMaterials () {
  preparing.value = true
  preparationBackground.value = false
  prepareCancelled.value = false
  preparingSnapshot.value = null
  preparationResult.value = null
  try {
    const currentParams = payload()
    const snapshot = await api.prepareRender({
      projectId: projectId.value,
      params: currentParams,
      useAi: (autonomyMode.value === 'auto' || autonomyMode.value === 'autonomous') && materialSourceMode.value !== 'local',
      waitSeconds: 45
    })
    preparingSnapshot.value = snapshot
    let final = snapshot
    if (snapshot.status === 'running' && snapshot.id != null) {
      const deadline = Date.now() + PREPARE_POLL_MAX_MS
      let consecutiveFailures = 0
      while (final && final.status === 'running' && !prepareCancelled.value && Date.now() < deadline) {
        await sleep(PREPARE_POLL_MS)
        try {
          final = await api.prepareRenderStatus(snapshot.id, { silent: true })
          consecutiveFailures = 0
          preparingSnapshot.value = final
        } catch {
          // The backend task remains durable; after transient failures keep the panel visible
          // and switch to detached polling instead of treating a client poll failure as completion.
          consecutiveFailures += 1
          if (consecutiveFailures >= 5) break
        }
      }
      if (final && final.status === 'running') final = null
    }
    if (prepareCancelled.value) {
      ElMessage.info('已取消素材准备，本次继续使用当前本地素材出片')
      return
    }
    if (!final) {
      ElMessage.warning('素材准备仍在后台进行；本次将使用当前本地素材继续预检，页面会持续显示准备状态并在完成后更新诊断。')
      void continuePreparationPolling(snapshot?.id)
      return
    }
    preparingSnapshot.value = final
    preparationResult.value = final
    materialGap.value = final.finalGap || final.initialGap || materialGap.value
    const hasManualScope = (currentParams.materialIds?.length || 0) > 0 || (currentParams.folderIds?.length || 0) > 0
    if (final.ready && (final.autoFill?.any || hasManualScope)) {
      usePreparedPoolForRun.value = true
      ElMessage.success(final.autoFill?.any
        ? `公开素材已完成入库并通过预检；本次会临时扩展素材范围以纳入新素材`
        : '项目素材库已满足本次出片；将临时扩展手工范围，让项目相关素材参与本次预检')
    } else if (final.autoFill?.any && final.timedOut) {
      usePreparedPoolForRun.value = autonomyMode.value === 'autonomous'
      ElMessage.warning(autonomyMode.value === 'autonomous'
        ? '公开素材仍在后台入库；自主模式会继续使用已通过准入的素材完成干跑，并在后续版本纳入新素材'
        : '公开素材仍在后台入库，当前使用已通过准入的素材继续预检；可稍后刷新准备状态。')
    } else if (final.autoFill && !final.autoFill.any) {
      // 明确回退：没有可用的公开素材时继续用本地素材走干跑，不让流程静默停住
      const issues = sourceIssueRows(final.autoFill)
      ElMessage.warning(issues.length
        ? `公开素材来源暂不可用（${issues.map((issue) => issue.source).join('')}）；本次继续使用本地素材出片，可稍后在素材抓取页重试`
        : '未找到可用的公开素材；本次继续使用本地素材出片，可前往素材抓取页手动导')
    }
    await loadInitial()
  } catch (error) {
    ElMessage.warning(`项目素材准备未完成：${error.message || '将继续使用当前本地素'}`)
  } finally {
    preparing.value = false
    if (!preparationBackground.value) preparingSnapshot.value = null
    prepareCancelled.value = false
  }
}

async function submit() {
  if (preparing.value || preparationBackground.value || dry.value || submitting.value) return
  submitting.value = true
  try {
    // 首次提交半自动/自主模式前展示一次性权限说明；未同意则不提交中。
    if (autonomyMode.value !== 'assist' && !autonomyConsentGranted(autonomyMode.value)) {
      const granted = await requestAutonomyConsent(autonomyMode.value)
      if (!granted) return
    }
    const strictError = strictFolderSequenceError()
    if (strictError) {
      ElMessage.warning(strictError)
      return
    }
    const automaticFillAuthorized = autonomyMode.value === 'auto' || autonomyMode.value === 'autonomous'
    if (materialSourceMode.value === 'local' || !automaticFillAuthorized) usePreparedPoolForRun.value = false
    if (materialSourceMode.value !== 'local' && automaticFillAuthorized) {
      // Only half-auto and autonomous production may run the bounded automatic recovery pipeline.
      await prepareMaterials()
    }
    if (!await doDryRun()) {
      usePreparedPoolForRun.value = false
      ElMessage.warning(preflightMessage.value || '干跑预检未通过，请检查当前素材与参数后重试。')
      return
    }
    if (!preflightReady.value) {
      usePreparedPoolForRun.value = false
      ElMessage.warning(preflightMessage.value || '干跑预检未通过，请检查当前素材与参数后重试。')
      return
    }
    const created = await api.submitJob({
      workflowId: workflowId.value, projectId: projectId.value,
      count: continuous.value ? 1 : count.value, continuous: continuous.value, name: jobName.value || null,
      timeoutSec: jobTimeoutMin.value > 0 ? jobTimeoutMin.value * 60 : 0,
      staleAfterSec: jobStaleMin.value > 0 ? jobStaleMin.value * 60 : 0,
      params: payload()
    })
    if (created?.id != null && !jobs.value.some((job) => String(job.id) === String(created.id))) {
      jobs.value.unshift(created)
    }
    ElMessage.success(continuous.value ? '已开始连续出片，随时可点击暂' : '已提交，正在后台渲染')
    await loadJobs({ silent: true, refresh: true })
  } catch (error) {
    ElMessage.error(`提交出片任务失败：${error.message || '请检查后端服务后重试'}`)
  } finally {
    submitting.value = false
    usePreparedPoolForRun.value = false
  }
}

let jobsRequest = null
async function loadJobs({ silent = false, refresh = false } = {}) {
  if (jobsRequest) {
    if (!refresh) return jobsRequest
    await jobsRequest
  }
  jobsLoading.value = !silent
  jobsRequest = api.jobs(silent ? { silent: true } : undefined)
    .then(async (rows) => {
      const nextRows = Array.isArray(rows) ? rows : []
      const byId = new Map(jobs.value.map((job) => [String(job.id), job]))
      nextRows.forEach((row) => {
        const previous = byId.get(String(row.id))
        if (previous) Object.assign(previous, row)
        else byId.set(String(row.id), row)
      })
      const nextIds = new Set(nextRows.map((row) => String(row.id)))
      jobs.value = jobs.value.filter((row) => nextIds.has(String(row.id)))
      nextRows.forEach((row) => { if (!jobs.value.some((item) => String(item.id) === String(row.id))) jobs.value.push(byId.get(String(row.id))) })
      const active = nextRows.filter((job) => job.status === 'running' || job.status === 'pending').slice(0, 8)
      const activeIds = new Set(active.map((job) => String(job.id)))
      Object.keys(jobLive).forEach((id) => { if (!activeIds.has(String(id))) delete jobLive[id] })
      await Promise.all(active.map(async (job) => {
        try { jobLive[job.id] = await api.job(job.id, { silent: true }) } catch { /* A single job detail can fail without making the backend unavailable. */ }
      }))
      jobsError.value = false
      return jobs.value
    })
    .catch((error) => {
      if (!silent) jobsError.value = true
      return jobs.value
    })
    .finally(() => {
      jobsRequest = null
      jobsLoading.value = false
    })
  return jobsRequest
}

async function openJob(row) {
  jobDetail.value = await api.job(row.id)
  jobDlg.value = true
}

async function cancel(row) {
  await api.cancelJob(row.id)
  loadJobs()
}
async function cancelPrepare() {
  const id = preparingSnapshot.value?.id
  if (!id) {
    prepareCancelled.value = true
    ElMessage.info('当前准备任务尚未返回任务编号，已停止等待；后台不会被强制中断')
    return
  }
  try {
    const cancelled = await api.cancelPreparation(id)
    preparingSnapshot.value = cancelled
    prepareCancelled.value = true
    ElMessage.info('准备任务已取消；已发起的公开素材任务不会被连带取消')
  } catch (error) {
    ElMessage.error(error.message || '取消准备失败，请稍后重试')
  }
}
async function onJobRowDblClick(row) {
  const active = row.status === 'running' || row.status === 'pending' || row.status === 'paused' || row.status === 'awaiting_decision'
  if (!active) return
  try {
    await ElMessageBox.confirm(`双击取消任务 #${row.id}「${row.name || ''}」？已生成内容会保留。`, '取消任务', { type: 'warning' })
    await cancel(row)
  } catch (e) { /* 用户取消弹窗 */ }
}
async function pause(row) {
  await api.pauseJob(row.id)
  ElMessage.success('已请求暂停，当前一条成片完成后会保留并停止')
  loadJobs()
}
async function resume(row) {
  await api.resumeJob(row.id)
  ElMessage.success(row.status === 'awaiting_decision' ? '已按当前安全策略重新排队' : '已继续出')
  loadJobs()
}
async function retryFailed(row) {
  try {
    await api.retryFailedJob(row.id)
    ElMessage.success('失败项已重新进入修复渲染队列，已通过成片不会重复生成')
    await loadJobs()
  } catch (error) { ElMessage.error(`重试失败项失败：${error.message}`) }
}

function canSelectJobForDelete(row) {
  return !['running', 'pending', 'paused'].includes(row?.status)
}

function onJobSelectionChange(rows) {
  selectedJobIds.value = (rows || []).filter(canSelectJobForDelete).map((row) => row.id)
}

async function delJob(row) {
  try { await api.deleteJob(row.id); ElMessage.success('任务记录和关联成片已删除'); await loadJobs() } catch (error) { ElMessage.error(`删除任务失败：${error.message}`) }
}

async function batchDeleteJobs() {
  if (!selectedJobIds.value.length) return
  try {
    const result = await api.batchDeleteJobs({ ids: selectedJobIds.value })
    selectedJobIds.value = []
    const skipped = Array.isArray(result?.skipped) ? result.skipped : []
    ElMessage.success(skipped.length
      ? `已删除 ${result.deleted || 0} 条；${skipped.length} 条未删除，请先取消运行中的任务`
      : `已删除 ${result.deleted || 0} 条任务及关联成片`)
    await loadJobs()
  } catch (error) {
    ElMessage.error(`批量删除任务失败：${error.message}`)
  }
}

async function cleanupJobs () {
  try {
    const count = await api.cleanupJobs()
    ElMessage.success(`已清理 ${count} 条终态任务记录`)
    await loadJobs()
  } catch (error) { ElMessage.error(`清理任务失败：${error.message}`) }
}

watch(autoRefresh, (v) => (v ? startTimer() : stopTimer()))
watch(continuous, (value) => localStorage.setItem('mixcut-continuous', value ? '1' : '0'))
watch(autonomyMode, (mode) => {
  localStorage.setItem('mework-autonomy-mode', mode)
  // 进入自主模式时默认开启严格交付；用户显式关闭后保留其选择（不做覆盖）。
  if (mode === 'autonomous' && p.strictDelivery == null) p.strictDelivery = true
  invalidateDryRun()
})
watch(materialSourceMode, (value) => {
  localStorage.setItem('mework-material-source-mode', value)
  autoUseCrawled.value = value !== 'local'
  preparationResult.value = null
  usePreparedPoolForRun.value = false
  doGapAnalysis({ silent: true })
})
// A dry-run is a contract for one exact parameter set. Any edit invalidates it.
let invalidateTimer = null
function invalidateDryRun () {
  if (invalidateTimer) clearTimeout(invalidateTimer)
  invalidateTimer = setTimeout(() => {
    dryRunRevision++
    if (preparing.value || preparationBackground.value || dry.value || submitting.value) {
      invalidateTimer = null
      return
    }
    dry.value = false
    plan.value = null
    preflight.value = null
    materialGap.value = null
    autoFillResult.value = null
    usePreparedPoolForRun.value = false
    invalidateTimer = null
  }, 450)
}
watch(p, invalidateDryRun, { deep: true })
watch([projectId, workflowId, variant], invalidateDryRun)

let refreshInFlight = false
function scheduleRefresh (delay = 12000) {
  if (!autoRefresh.value) return
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    if (refreshInFlight) {
      scheduleRefresh(15000)
      return
    }
    refreshInFlight = true
    try {
      const hasActiveJobs = jobs.value.some((job) => ['running', 'pending'].includes(job.status))
      const currentQueue = queue.value
      const hasQueueWork = Number(currentQueue?.active || 0) > 0 || Number(currentQueue?.pendingJobs || 0) > 0 || Number(currentQueue?.queueSize || 0) > 0
      if (initialError.value || runtimeUnavailable.value) {
        await loadInitial()
        await loadJobs({ silent: true })
      } else if (hasActiveJobs) {
        await loadJobs({ silent: true })
      }
      if (hasActiveJobs || hasQueueWork) await loadQueue()
      else if (!currentQueue || runtimeUnavailable.value) await loadQueue()
    } finally {
      refreshInFlight = false
      scheduleRefresh(hasActiveJobsOrQueueWork() ? 3000 : 15000)
    }
  }, delay)
}
function hasActiveJobsOrQueueWork () {
  const hasActiveJobs = jobs.value.some((job) => ['running', 'pending'].includes(job.status))
  return hasActiveJobs || Number(queue.value?.active || 0) > 0 || Number(queue.value?.pendingJobs || 0) > 0 || Number(queue.value?.queueSize || 0) > 0
}
function startTimer() {
  stopTimer()
  scheduleRefresh(12000)
}
function stopTimer() {
  if (timer) clearTimeout(timer)
  timer = null
}

let initialRequest = null
async function loadInitial () {
  if (initialRequest) return initialRequest
  initialLoading.value = true
  initialRequest = (async () => {
    const results = await Promise.allSettled([
      api.projects({ silent: true }),
      api.workflows({ silent: true }),
      api.materials({ type: 'all' }, { silent: true }),
      api.materialFolders({ silent: true }),
      api.env({ silent: true })
    ])
    const [projectRows, workflowRows, materialsRows, folderRows, envRows] = results
    if (projectRows.status === 'fulfilled') projects.value = Array.isArray(projectRows.value) ? projectRows.value : []
    if (workflowRows.status === 'fulfilled') workflows.value = Array.isArray(workflowRows.value) ? workflowRows.value : []
    if (materialsRows.status === 'fulfilled') {
      const materials = Array.isArray(materialsRows.value) ? materialsRows.value : []
      const readableAudio = materials.filter((material) => material.fileType === 'audio' && material.status !== 'failed')
      bgmList.value = readableAudio
      voiceList.value = readableAudio.filter((material) => material.role === 'voice')
      visualList.value = materials.filter((material) => material.fileType === 'video' || material.fileType === 'image')
    }
    if (folderRows.status === 'fulfilled') folders.value = Array.isArray(folderRows.value) ? folderRows.value : []
    if (envRows.status === 'fulfilled') Object.assign(runtime, envRows.value || {})
    initialError.value = results.some((result) => result.status === 'rejected')
    runtimeUnavailable.value = envRows.status === 'rejected'
    try {
      const selected = JSON.parse(localStorage.getItem('mework-selected-material-ids') || '[]')
      if (Array.isArray(selected)) p.materialIds = selected.filter((id) => Number.isFinite(Number(id))).map(Number)
      localStorage.removeItem('mework-selected-material-ids')
    } catch { /* 忽略无效本地选择 */ }
    if (!fixedOrderSkillKey.value) {
      const fromRoute = typeof route.query.fixedOrderPreset === 'string' ? route.query.fixedOrderPreset : ''
      const pendingOrder = fromRoute || sessionStorage.getItem('mework-fixed-order-preset')
      const customOrder = route.query.fixedOrderCustom === '1' ? sessionStorage.getItem('mework-fixed-order-custom') : null
      if (customOrder) {
        try {
          const pack = JSON.parse(customOrder)
          applyFixedOrderStages(pack.name, pack.stages, false)
          sessionStorage.removeItem('mework-fixed-order-custom')
          router.replace({ path: '/studio' })
        } catch { ElMessage.error('自定义顺序数据无效，未应') }
      } else if (pendingOrder) {
        sessionStorage.removeItem('mework-fixed-order-preset')
        applyFixedOrderPreset(pendingOrder, false)
        if (fromRoute) router.replace({ path: '/studio' })
      }
    }
    // Let the parameter form paint before the heavier gap analysis starts.
    window.setTimeout(() => doGapAnalysis({ silent: true }), 250)
    return !initialError.value
  })().finally(() => {
    initialLoading.value = false
    initialRequest = null
  })
  return initialRequest
}

onMounted(async () => {
  await loadInitial()
  void resumeRunningPreparation()
  loadJobs({ silent: true })
  loadQueue()
  startTimer()
})

onUnmounted(stopTimer)
</script>
