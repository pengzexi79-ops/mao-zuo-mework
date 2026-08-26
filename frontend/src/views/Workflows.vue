<template>
  <div class="workflow-layout">
    <aside class="card workflow-list">
      <div class="card-title">
        工作流 <span class="grow"></span>
        <input ref="workflowImportInput" class="hidden-file-input" type="file" accept="application/json,.json" @change="importWorkflowFile" />
        <el-button size="small" :disabled="busy" @click="workflowImportInput?.click()">导入</el-button>
        <el-button size="small" type="primary" :disabled="busy" @click="openNew">新建</el-button>
      </div>
      <el-collapse v-model="workflowPanelNames" class="workflow-list-collapse">
        <el-collapse-item :title="`我的工作流（${userWorkflows.length}）`" name="mine">
          <div v-if="!userWorkflows.length" class="muted empty-section">暂无自建工作流，可点击右上角"新建"。</div>
          <div v-for="workflow in userWorkflows" :key="workflow.id" :class="['workflow-item', { active: cur?.id === workflow.id }]" @click="select(workflow)">
            <div class="workflow-item-name">{{ workflow.name }}</div>
            <div class="muted workflow-item-description">{{ workflow.description || '无描述' }}</div>
          </div>
        </el-collapse-item>
        <el-collapse-item :title="`内置模板（${builtinPacks.length}）`" name="builtin">
          <div class="form-hint section-hint">模板只读；需要修改时先"复制一份"或使用下方模板商店。</div>
          <div v-for="workflow in builtinPacks" :key="workflow.id" :class="['workflow-item', { active: cur?.id === workflow.id }]" @click="select(workflow)">
            <div class="workflow-item-name">
              {{ workflow.name }} <el-tag size="small" type="info" effect="plain">内置</el-tag>
            </div>
            <div class="muted workflow-item-description">{{ workflow.description || '内置可执行模板' }}</div>
          </div>
        </el-collapse-item>
      </el-collapse>

      <el-collapse v-model="toolPanelNames" class="workflow-tools-collapse">
      <el-collapse-item title="智能自动编排" name="ai-plan">
      <el-input v-model="aiReq" type="textarea" :rows="3" placeholder="例如：做 100 秒左右的护肤混剪，多用明星片段，产品段插 4 次" :disabled="planning" />
      <el-select v-model="aiProjectId" clearable placeholder="关联项目（可选）" size="small" style="width:100%;margin-top:8px" :disabled="planning">
        <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
      </el-select>
      <el-button type="success" style="width:100%;margin-top:8px" :loading="planning" :disabled="busy" @click="doAiPlan">智能生成草稿</el-button>
      <div v-if="aiDraftDef" style="margin-top:10px;padding:12px;background:#f0f9eb;border-radius:8px;border:1px solid #c2e7b0">
        <div class="form-hint" style="margin-bottom:8px">AI 已生成工作流草稿。请审阅后明确接受，草稿才会填入表单；之后仍需手动保存。</div>
        <el-input v-model="aiDraftDef" type="textarea" :rows="10" readonly class="mono"
          style="background:#fff;font-size:12px" />
        <div style="margin-top:8px;display:flex;gap:8px">
          <el-button type="primary" size="small" @click="acceptAiDraft">接受草稿，填入表单</el-button>
          <el-button size="small" @click="aiDraftDef = ''">放弃草稿</el-button>
          <el-tag v-if="aiDraftSavedId > 0" size="small" type="success">已暂存</el-tag>
        </div>
      </div>
      <div class="form-hint">人工智能仅能调用内置能力或已启用的受约束自定义技能，不能生成命令、下载或网络请求。</div>
      </el-collapse-item>
        <el-collapse-item :title="`开源模板商店（${workflowStoreItems.length}）`" name="store">
          <div class="fixed-order-workflow-hint">
            <b>需要固定素材读取顺序？</b>
            <span>可选择美妆、食品、母婴、3C、穿搭、家居和知识讲解预置；应用后仍可在出片控制台自由增删步骤和绑定文件夹。</span>
            <el-button link type="primary" @click="$router.push('/fixed-order-presets')">打开产片固定顺序</el-button>
          </div>
          <div class="form-hint section-hint">商店里的模板来自高星或公认好用的开源项目，只做仓库跳转和示例包导入，不会混入本机内置模板。</div>
          <div class="workflow-store-grid">
            <div v-for="pack in workflowStoreItems" :key="pack.key" class="workflow-store-card">
              <div class="workflow-store-head">
                <div>
                  <div class="workflow-item-name">{{ pack.name }}</div>
                  <div class="workflow-store-subtitle">{{ formatStars(pack.stars) }} · {{ pack.repo }}</div>
                </div>
                <el-tag size="small" type="success" effect="plain">开源推荐</el-tag>
              </div>
              <div class="muted workflow-item-description">{{ pack.summary }}</div>
              <div class="workflow-store-tags">
                <el-tag v-for="tag in pack.tags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
              </div>
              <div class="workflow-store-links">
                <el-button size="small" plain @click="openExternal(pack.repoUrl)">查看仓库</el-button>
                <el-button size="small" plain @click="openExternal(pack.homepageUrl || pack.repoUrl)">打开首页</el-button>
                <el-button size="small" @click="downloadStorePack(pack)">下载 JSON</el-button>
                <el-button size="small" type="primary" :disabled="busy" @click="importStorePack(pack)">导入工作流</el-button>
              </div>
            </div>
          </div>
          <div v-if="!workflowStoreItems.length" class="muted" style="margin-top:8px">正在加载开源模板目录。</div>
        </el-collapse-item>
      </el-collapse>
    </aside>

    <section class="workflow-main">
      <div v-if="cur" class="card">
        <div class="card-title">
          {{ cur.id ? cur.name || '未命名工作流' : '新建工作流' }} <span class="grow"></span>
          <el-button v-if="cur.id" size="small" :loading="duplicating" :disabled="busy" @click="doDuplicate">复制一份</el-button>
          <el-button v-if="cur.id" size="small" :disabled="busy" @click="exportWorkflow">导出 JSON</el-button>
          <el-button v-if="cur.id && !cur.isBuiltin" size="small" type="danger" plain :loading="deleting" :disabled="busy" @click="doDelete">删除</el-button>
          <el-button size="small" type="primary" :loading="saving" :disabled="busy" @click="save">保存</el-button>
        </div>

        <el-tabs v-model="tab" @tab-change="onTabChange">
          <el-tab-pane label="中文向导" name="wizard">
            <div class="form-hint">按顺序设置即可。保存时会自动转换为系统原有的规则定义，您无需手动填写技术字段。</div>
            <el-steps :active="wizardStep" simple class="workflow-steps">
              <el-step title="素材范围" />
              <el-step title="时长节奏" />
              <el-step title="内容结构" />
              <el-step title="画面规格" />
              <el-step title="完成" />
            </el-steps>

            <section v-show="wizardStep === 0" class="wizard-panel">
              <h3>1. 选择素材范围</h3>
              <div class="form-hint">选择本次出片可使用的素材类型；不选择任何类型表示不限。</div>
              <el-form label-width="108px">
                <el-form-item label="素材类型">
                  <el-checkbox-group v-model="wizard.roles">
                    <el-checkbox v-for="role in materialRoles" :key="role.value" :label="role.value">{{ role.label }}</el-checkbox>
                  </el-checkbox-group>
                </el-form-item>
                <el-form-item label="关键词筛选"><el-input v-model="wizard.keyword" maxlength="120" show-word-limit placeholder="可选，例如：精华、夏日" /></el-form-item>
                <el-form-item label="最多使用">
                  <el-slider v-model="wizard.limit" :min="1" :max="500" show-input />
                  <span class="unit">条素材</span>
                </el-form-item>
              </el-form>
            </section>

            <section v-show="wizardStep === 1" class="wizard-panel">
              <h3>2. 设置视频时长与切片节奏</h3>
              <el-form label-width="132px">
                <el-form-item label="最短成片时长"><el-input-number v-model="wizard.minSec" :min="5" :max="300" /><span class="unit">秒</span></el-form-item>
                <el-form-item label="最长成片时长"><el-input-number v-model="wizard.maxSec" :min="5" :max="300" /><span class="unit">秒</span></el-form-item>
                <el-form-item label="节奏偏好"><el-switch v-model="wizard.dense" active-text="节奏紧凑" inactive-text="节奏自然" /></el-form-item>
                <el-form-item label="单段切片时长"><el-slider v-model="wizard.sliceSec" :min="0.8" :max="15" :step="0.1" show-input /><span class="unit">秒</span></el-form-item>
                <el-form-item label="切片随机变化"><el-slider v-model="wizard.sliceJitter" :min="0" :max="3" :step="0.1" show-input /><span class="unit">秒</span></el-form-item>
                <el-form-item label="拆分长素材"><el-switch v-model="wizard.explodeLongClips" active-text="开启" inactive-text="关闭" /></el-form-item>
                <el-form-item label="每条素材最多"><el-input-number v-model="wizard.maxSlicesPerMaterial" :min="1" :max="20" /><span class="unit">段</span></el-form-item>
              </el-form>
            </section>

            <section v-show="wizardStep === 2" class="wizard-panel">
              <h3>3. 设置内容结构</h3>
              <el-form label-width="132px">
                <el-form-item label="开场钩子时长"><el-input-number v-model="wizard.hookSec" :min="0" :max="15" :step="0.5" /><span class="unit">秒</span></el-form-item>
                <el-form-item label="明星/达人占比"><el-slider v-model="wizard.celebrityRatio" :min="0" :max="0.8" :step="0.05" show-input /><span class="unit">比例</span></el-form-item>
                <el-form-item label="产品插入次数"><el-input-number v-model="wizard.productSlots" :min="0" :max="10" /><span class="unit">次</span></el-form-item>
                <el-form-item label="单次产品时长"><el-input-number v-model="wizard.productSec" :min="0.8" :max="15" :step="0.1" /><span class="unit">秒</span></el-form-item>
                <el-form-item label="结尾片尾卡"><el-switch v-model="wizard.endcard" active-text="保留" inactive-text="不使用" /></el-form-item>
              </el-form>
            </section>

            <section v-show="wizardStep === 3" class="wizard-panel">
              <h3>4. 设置音频、钩子与画面规格</h3>
              <el-form label-width="132px">
                <el-form-item label="背景音乐"><el-select v-model="wizard.bgmMaterialId" clearable filterable placeholder="自动选择背景音乐" style="width:320px"><el-option v-for="item in bgmMaterials" :key="item.id" :label="item.name || item.filename || `背景音乐 #${item.id}`" :value="item.id" /></el-select></el-form-item>
                <el-form-item label="人声口播"><el-select v-model="wizard.voiceMaterialId" clearable filterable placeholder="不使用预设人声" style="width:320px"><el-option v-for="item in voiceMaterials" :key="item.id" :label="item.name || item.filename || `人声 #${item.id}`" :value="item.id" /></el-select></el-form-item>
                <el-form-item label="背景音乐音量"><el-slider v-model="wizard.bgmVolume" :min="0" :max="1" :step="0.01" show-input /></el-form-item>
                <el-form-item label="钩子文案"><el-switch v-model="wizard.includeHook" active-text="自动生成" inactive-text="不生成" /><el-input v-if="wizard.includeHook" v-model="wizard.hookExtra" maxlength="1000" show-word-limit placeholder="可选：补充钩子文案方向" style="margin-top:8px" /></el-form-item>
                <el-form-item label="画面宽度"><el-input-number v-model="wizard.width" :min="240" :max="3840" /><span class="unit">像素</span></el-form-item>
                <el-form-item label="画面高度"><el-input-number v-model="wizard.height" :min="240" :max="3840" /><span class="unit">像素</span></el-form-item>
                <el-form-item label="帧率"><el-input-number v-model="wizard.fps" :min="12" :max="60" /><span class="unit">fps</span></el-form-item>
              </el-form>
            </section>

            <section v-show="wizardStep === 4" class="wizard-panel">
              <h3>5. 命名并完成</h3>
              <el-form label-width="82px">
                <el-form-item label="名称" required><el-input v-model="cur.name" maxlength="100" show-word-limit placeholder="例如：护肤产品自然混剪" /></el-form-item>
                <el-form-item label="版本"><el-input v-model="cur.version" maxlength="40" placeholder="1.0" /></el-form-item>
                <el-form-item label="说明"><el-input v-model="cur.description" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="说明这个工作流适合的内容和使用场景" /></el-form-item>
              </el-form>
              <el-alert title='保存后即可在"出片控制台"中选择本工作流。' type="info" :closable="false" show-icon />
            </section>

            <div class="wizard-actions">
              <el-button :disabled="wizardStep === 0 || busy" @click="wizardStep--">上一步</el-button>
              <el-button v-if="wizardStep < 4" type="primary" :disabled="busy" @click="wizardStep++">下一步</el-button>
              <el-button v-else type="primary" :loading="saving" :disabled="busy" @click="save">保存工作流</el-button>
            </div>

            <el-collapse class="workflow-extra-skills">
              <el-collapse-item title="附加技能（高级，可选）" name="extra">
                <div class="form-hint">此处用于保留或追加启用的自定义技能。它们会按顺序写入原规则定义，不能填写额外参数。</div>
                <div v-for="(step, index) in extraSteps" :key="`${step.skill}-${index}`" class="extra-step">
                  <el-tag size="small">{{ index + 1 }}</el-tag><span class="mono">{{ step.skill }}</span><span class="grow"></span>
                  <el-button link size="small" :disabled="index === 0 || busy" @click="moveExtra(index, -1)">上移</el-button>
                  <el-button link size="small" :disabled="index === extraSteps.length - 1 || busy" @click="moveExtra(index, 1)">下移</el-button>
                  <el-button link type="danger" size="small" :disabled="busy" @click="extraSteps.splice(index, 1)">删除</el-button>
                </div>
                <el-select v-model="newExtraSkill" clearable placeholder="选择已启用的自定义技能" style="width:280px;margin-top:8px"><el-option v-for="skill in enabledCustomSkills" :key="skill.id" :label="skill.name" :value="skill.name" /></el-select>
                <el-button size="small" style="margin-left:8px" :disabled="!newExtraSkill || busy" @click="addExtraSkill">添加技能</el-button>
              </el-collapse-item>
            </el-collapse>
          </el-tab-pane>

          <el-tab-pane label="高级编辑（适合高级用户）" name="json">
            <el-alert title='此处直接编辑系统规则定义。请保留内部技能名和参数键；一般使用请回到"中文向导"。' type="warning" :closable="false" show-icon style="margin-bottom:12px" />
            <el-input v-model="defJson" type="textarea" :rows="20" class="mono" />
            <div style="margin-top:8px"><el-button size="small" :disabled="busy" @click="applyAdvancedJson">应用到中文向导</el-button><span class="muted" style="margin-left:10px">保存此标签时，将以当前规则定义为准。</span></div>
          </el-tab-pane>
        </el-tabs>
      </div>
      <div v-else class="card"><div class="muted">正在加载工作流；若未显示内容，请使用左侧"新建"创建工作流。</div></div>

      <div class="card">
        <div class="card-title">内置技能清单 <span class="hint">稳定能力；包含外部抓取的内置技能会保持既有权限策略</span></div>
        <el-collapse><el-collapse-item title="查看内置技能参数" name="builtin">
        <el-table :data="builtin" size="small" max-height="360">
          <el-table-column prop="name" label="技能名称" width="180"><template #default="{ row }"><span class="mono">{{ row.name }}</span></template></el-table-column>
          <el-table-column prop="description" label="作用" min-width="250" />
          <el-table-column label="参数" min-width="300"><template #default="{ row }"><div v-for="(value, key) in row.params" :key="key" class="muted" style="line-height:1.7"><span class="mono">{{ key }}</span> — {{ value }}</div></template></el-table-column>
        </el-table>
        </el-collapse-item></el-collapse>
      </div>

      <div class="card">
        <div class="card-title">自定义技能（受约束规则）<span class="hint">仅操作素材池、参数、文案和音量；不会执行命令、模板、下载或 HTTP 请求</span><span class="grow"></span><input ref="skillImportInput" class="hidden-file-input" type="file" accept="application/json,.json" @change="importSkillFile" /><el-button size="small" :disabled="busy" @click="skillImportInput?.click()">导入</el-button><el-button size="small" type="primary" :disabled="busy" @click="openNewSkill">新建技能</el-button></div>
        <div class="workflow-drop-zone" @dragover.prevent @drop.prevent="onWorkflowDrop">
          <div class="muted">把 `.mixcut-workflow.json` 或 `.mixcut-skill.json` 拖到这里，会先在浏览器内识别，再进入对应导入流程。</div>
        </div>
        <el-table :data="customSkills" size="small" max-height="210" @row-click="editSkill">
          <el-table-column prop="name" label="名称" width="170"><template #default="{ row }"><span class="mono">{{ row.name }}</span></template></el-table-column>
          <el-table-column prop="type" label="类型" width="90" />
          <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '已启用' : '已停用' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="200"><template #default="{ row }"><el-button link size="small" :disabled="busy" @click.stop="editSkill(row)">编辑</el-button><el-button link size="small" :disabled="busy" @click.stop="exportSkill(row)">导出</el-button><el-button link size="small" :loading="changingSkillId === row.id" :disabled="busy && changingSkillId !== row.id" @click.stop="toggleSkill(row)">{{ row.enabled ? '停用' : '启用' }}</el-button><el-button link type="danger" size="small" :disabled="busy" @click.stop="removeSkill(row)">删除</el-button></template></el-table-column>
        </el-table>

        <div v-if="skillDraft" class="skill-draft">
          <el-form label-width="90px" size="small">
            <el-form-item label="名称"><el-input v-model="skillDraft.name" :disabled="!!skillDraft.id" placeholder="例如：护肤平衡混剪" /></el-form-item>
            <el-form-item label="类型"><el-select v-model="skillDraft.type" style="width:180px"><el-option label="脚本规则" value="script" /><el-option label="智能规则" value="ai" /></el-select></el-form-item>
            <el-form-item label="说明"><el-input v-model="skillDraft.description" type="textarea" :rows="2" maxlength="1000" show-word-limit placeholder="给智能编排使用的用途说明" /></el-form-item>
            <el-form-item label="DSL 定义"><el-input v-model="skillDraft.def" type="textarea" :rows="12" class="mono" /></el-form-item>
          </el-form>
          <div class="form-hint">允许 op：<span class="mono">select_materials / set_params / set_hook / set_script / pick_audio / note</span>。<span class="mono">set_params</span> 只允许 MixParams 字段（不含字体路径）。最多 30 步。</div>
          <div style="margin:8px 0"><el-button size="small" :disabled="busy" @click="copyDslExample">复制 DSL 示例</el-button><el-button size="small" :loading="validatingSkill" :disabled="busy && !validatingSkill" @click="validateDsl">校验技能</el-button><el-button type="primary" size="small" :loading="savingSkill" :disabled="busy && !savingSkill" @click="saveSkill">保存技能</el-button><el-button size="small" :disabled="busy" @click="skillDraft = null">取消</el-button></div>
          <el-alert v-if="skillValidation" :title="skillValidation.message" :type="skillValidation.valid ? 'success' : 'error'" :closable="false" show-icon />
          <el-collapse style="margin-top:10px"><el-collapse-item title="DSL 示例" name="dsl"><pre class="mono" style="white-space:pre-wrap;margin:0">{{ DSL_EXAMPLE }}</pre></el-collapse-item></el-collapse>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ROLE_LABEL } from '../api'
import { WORKFLOW_STORE_ITEMS } from '../workflowStoreCatalog'

const DSL_EXAMPLE = `{
  "version": 1,
  "steps": [
    { "op": "select_materials", "roles": ["body", "product"], "keyword": "精华", "limit": 80 },
    { "op": "set_params", "params": { "minSec": 50, "maxSec": 100, "productSlots": 3, "bgmVolume": 0.22 } },
    { "op": "set_hook", "text": "这一步真的不能省" },
    { "op": "note", "text": "护肤平衡混剪" }
  ]
}`

const list = ref([])
const builtin = ref([])
const customSkills = ref([])
const projects = ref([])
const materials = ref([])
const cur = ref(null)
const defJson = ref('')
const tab = ref('wizard')
const wizardStep = ref(0)
const extraSteps = ref([])
const newExtraSkill = ref('')
const wizard = ref(defaultWizard())
const saving = ref(false)
const planning = ref(false)
const duplicating = ref(false)
const deleting = ref(false)
const loading = ref(false)
const aiReq = ref('')
const aiProjectId = ref(null)
const aiDraftDef = ref('')
const aiDraftSavedId = ref(-1)
const skillDraft = ref(null)
const skillValidation = ref(null)
const validatingSkill = ref(false)
const savingSkill = ref(false)
const changingSkillId = ref(null)
const workflowImportInput = ref(null)
const skillImportInput = ref(null)
const workflowPanelNames = ref([])
const toolPanelNames = ref([])
const PENDING_PACK_KEY = 'mework-pending-pack-files'

const materialRoles = Object.entries(ROLE_LABEL).filter(([value]) => value !== 'none').map(([value, label]) => ({ value, label }))
const userWorkflows = computed(() => list.value.filter((workflow) => !workflow.isBuiltin))
const builtinPacks = computed(() => list.value.filter((workflow) => workflow.isBuiltin))
const workflowStoreItems = computed(() => WORKFLOW_STORE_ITEMS)
const enabledCustomSkills = computed(() => customSkills.value.filter((skill) => skill.enabled))
const bgmMaterials = computed(() => materials.value.filter((item) => item.role === 'bgm'))
const voiceMaterials = computed(() => materials.value.filter((item) => item.role === 'voice'))
const busy = computed(() => saving.value || planning.value || duplicating.value || deleting.value || loading.value || validatingSkill.value || savingSkill.value || changingSkillId.value !== null)

function defaultWizard () {
  return {
    roles: [], keyword: '', limit: 300,
    minSec: 50, maxSec: 150, dense: true,
    sliceSec: 3, sliceJitter: 0.4, explodeLongClips: true, maxSlicesPerMaterial: 5,
    hookSec: 3, celebrityRatio: 0.25, productSlots: 3, productSec: 3, endcard: true,
    bgmMaterialId: null, voiceMaterialId: null, bgmVolume: 0.22, includeHook: true, hookExtra: '',
    width: 1080, height: 1920, fps: 30
  }
}

function pretty (value) {
  try { return JSON.stringify(typeof value === 'string' ? JSON.parse(value) : value, null, 2) } catch { return '{\n  "steps": []\n}' }
}

function normalizeWorkflow (definition) {
  const next = defaultWizard()
  const extras = []
  const parsed = typeof definition === 'string' ? JSON.parse(definition || '{}') : (definition || {})
  for (const raw of parsed.steps || []) {
    const skill = raw.skill || raw.name
    const args = raw.args || {}
    if (skill === 'select_materials') Object.assign(next, { roles: Array.isArray(args.roles) ? args.roles : [], keyword: args.keyword || '', limit: numberOr(args.limit, next.limit) })
    else if (skill === 'set_duration') Object.assign(next, { minSec: numberOr(args.minSec, next.minSec), maxSec: numberOr(args.maxSec, next.maxSec), dense: boolOr(args.dense, next.dense) })
    else if (skill === 'set_slice') Object.assign(next, { sliceSec: numberOr(args.sliceSec, next.sliceSec), sliceJitter: numberOr(args.sliceJitter ?? args.jitter, next.sliceJitter), explodeLongClips: boolOr(args.explodeLongClips ?? args.explode, next.explodeLongClips), maxSlicesPerMaterial: numberOr(args.maxSlicesPerMaterial ?? args.maxPerMaterial, next.maxSlicesPerMaterial) })
    else if (skill === 'set_structure') Object.assign(next, { hookSec: numberOr(args.hookSec, next.hookSec), celebrityRatio: numberOr(args.celebrityRatio, next.celebrityRatio), productSlots: numberOr(args.productSlots, next.productSlots), productSec: numberOr(args.productSec, next.productSec), endcard: boolOr(args.endcard, next.endcard) })
    else if (skill === 'pick_audio') Object.assign(next, { bgmMaterialId: nullableNumber(args.bgmMaterialId), voiceMaterialId: nullableNumber(args.voiceMaterialId), bgmVolume: numberOr(args.bgmVolume, next.bgmVolume) })
    else if (skill === 'gen_hook') Object.assign(next, { includeHook: true, hookExtra: args.extra || '' })
    else if (skill === 'set_canvas') Object.assign(next, { width: numberOr(args.width, next.width), height: numberOr(args.height, next.height), fps: numberOr(args.fps, next.fps) })
    else if (skill) extras.push({ skill, args })
  }
  return { wizard: next, extras }
}

function numberOr (value, fallback) { const result = Number(value); return Number.isFinite(result) ? result : fallback }
function nullableNumber (value) { return value === null || value === undefined || value === '' ? null : numberOr(value, null) }
function boolOr (value, fallback) { return typeof value === 'boolean' ? value : fallback }

function buildDefinition () {
  const value = wizard.value
  const audio = { bgmVolume: value.bgmVolume }
  if (value.bgmMaterialId) audio.bgmMaterialId = value.bgmMaterialId
  if (value.voiceMaterialId) audio.voiceMaterialId = value.voiceMaterialId
  const steps = [
    { skill: 'select_materials', args: { ...(value.roles.length ? { roles: value.roles } : {}), ...(value.keyword.trim() ? { keyword: value.keyword.trim() } : {}), limit: value.limit } },
    { skill: 'set_duration', args: { minSec: value.minSec, maxSec: value.maxSec, dense: value.dense } },
    { skill: 'set_slice', args: { sliceSec: value.sliceSec, sliceJitter: value.sliceJitter, explodeLongClips: value.explodeLongClips, maxSlicesPerMaterial: value.maxSlicesPerMaterial } },
    { skill: 'set_structure', args: { hookSec: value.hookSec, celebrityRatio: value.celebrityRatio, productSlots: value.productSlots, productSec: value.productSec, endcard: value.endcard } },
    { skill: 'set_canvas', args: { width: value.width, height: value.height, fps: value.fps } }
  ]
  if (value.includeHook) steps.push({ skill: 'gen_hook', args: value.hookExtra.trim() ? { extra: value.hookExtra.trim() } : {} })
  steps.push({ skill: 'pick_audio', args: audio }, ...extraSteps.value.map((step) => ({ skill: step.skill, args: step.args || {} })))
  return JSON.stringify({ steps }, null, 2)
}

function select (workflow) {
  cur.value = { ...workflow }
  wizardStep.value = 0
  tab.value = 'wizard'
  try {
    const normalized = normalizeWorkflow(workflow.def)
    wizard.value = normalized.wizard
    extraSteps.value = normalized.extras
    defJson.value = pretty(workflow.def)
  } catch (error) {
    wizard.value = defaultWizard()
    extraSteps.value = []
    defJson.value = '{\n  "steps": []\n}'
    ElMessage.error(`工作流规则解析失败：${error.message}`)
  }
}

function openNew () {
  cur.value = { id: null, name: '', description: '', version: '1.0', isBuiltin: false }
  wizard.value = defaultWizard()
  extraSteps.value = []
  defJson.value = buildDefinition()
  wizardStep.value = 0
  tab.value = 'wizard'
}

function onTabChange (name) {
  if (name === 'json') defJson.value = buildDefinition()
}

function applyAdvancedJson () {
  try {
    const normalized = normalizeWorkflow(defJson.value)
    wizard.value = normalized.wizard
    extraSteps.value = normalized.extras
    tab.value = 'wizard'
    ElMessage.success('已应用到中文向导')
  } catch (error) {
    ElMessage.error(`规则定义不是有效 JSON：${error.message}`)
  }
}

function moveExtra (index, delta) {
  const target = index + delta
  if (target < 0 || target >= extraSteps.value.length) return
  const item = extraSteps.value[index]
  extraSteps.value[index] = extraSteps.value[target]
  extraSteps.value[target] = item
}

function addExtraSkill () {
  if (!newExtraSkill.value) return
  extraSteps.value.push({ skill: newExtraSkill.value, args: {} })
  newExtraSkill.value = ''
}

function validateWorkflow () {
  if (!cur.value?.name?.trim()) {
    ElMessage.warning('请填写工作流名称')
    wizardStep.value = 4
    return false
  }
  if (wizard.value.minSec > wizard.value.maxSec) {
    ElMessage.warning('最短成片时长不能大于最长成片时长')
    wizardStep.value = 1
    return false
  }
  return true
}

async function save () {
  if (!validateWorkflow()) return
  let definition = defJson.value
  if (tab.value === 'wizard') definition = buildDefinition()
  else {
    try { JSON.parse(definition || '{}') } catch (error) { ElMessage.error(`规则定义不是有效 JSON：${error.message}`); return }
  }
  saving.value = true
  try {
    const body = { name: cur.value.name.trim(), description: cur.value.description || '', version: cur.value.version || '1.0', def: definition }
    const saved = cur.value.id ? await api.updateWorkflow(cur.value.id, body) : await api.createWorkflow(body)
    ElMessage.success('工作流已保存')
    await loadWorkflows()
    const found = list.value.find((item) => item.id === saved.id)
    if (found) select(found)
  } catch (error) {
    ElMessage.error(`保存工作流失败：${error.message}`)
  } finally { saving.value = false }
}

function downloadJson (filename, value) {
  const blob = new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}
function formatStars (value) {
  const stars = Number(value || 0)
  if (!stars) return '社区推荐'
  if (stars >= 100000) return `${Math.round(stars / 1000) / 10}w★`
  if (stars >= 1000) return `${Math.round(stars / 100) / 10}k★`
  return `${stars}★`
}

function openExternal (url) {
  if (!url) return
  window.open(url, '_blank', 'noopener,noreferrer')
}

function downloadStorePack (pack) {
  downloadJson(`${pack.key}.mixcut-workflow.json`, pack.pack)
  ElMessage.success('示例包已下载，可在工作流页导入')
}

async function importStorePack (pack) {
  try {
    const imported = await api.importWorkflow({ pack: JSON.stringify(pack.pack) })
    await loadWorkflows()
    const found = list.value.find((item) => item.id === imported.id)
    if (found) select(found)
    ElMessage.success('开源模板已导入，可在中文向导继续调整')
  } catch (error) {
    ElMessage.error(`导入开源模板失败：${error.message}`)
  }
}
async function exportWorkflow () {
  if (!cur.value?.id) return
  try { downloadJson(`${cur.value.name || 'workflow'}.mixcut-workflow.json`, await api.exportWorkflow(cur.value.id)); ElMessage.success('工作流 JSON 已导出') } catch (error) { ElMessage.error(`导出工作流失败：${error.message}`) }
}
async function importPackText (raw, expectedFormat = null) {
  if (!raw) return
  let pack
  try {
    pack = JSON.parse(raw)
  } catch {
    throw new Error('导入包不是有效 JSON')
  }
  const format = pack?.format || ''
  if (expectedFormat && format !== expectedFormat) {
    throw new Error(`文件格式不匹配：需要 ${expectedFormat}`)
  }
  if (format === 'mixcut-workflow' || expectedFormat === 'mixcut-workflow') {
    const imported = await api.importWorkflow({ pack: raw })
    await loadWorkflows()
    const found = list.value.find((item) => item.id === imported.id)
    if (found) select(found)
    ElMessage.success('工作流已安全导入')
    return 'mixcut-workflow'
  }
  if (format === 'mixcut-skill' || expectedFormat === 'mixcut-skill') {
    const imported = await api.importSkill({ pack: raw })
    await loadSkills()
    editSkill(imported)
    ElMessage.success('Skill 已安全导入并可继续编辑')
    return 'mixcut-skill'
  }
  throw new Error('导入包类型不匹配')
}

async function importPackFile (file, expectedFormat = null) {
  if (!file) return
  return importPackText(await file.text(), expectedFormat)
}

async function importWorkflowFile (event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  try {
    await importPackFile(file, 'mixcut-workflow')
  } catch (error) { ElMessage.error(`导入工作流失败：${error.message}`) }
}
async function doDuplicate () {
  if (!cur.value?.id) return
  duplicating.value = true
  try {
    const workflow = await api.duplicateWorkflow(cur.value.id)
    await loadWorkflows()
    const found = list.value.find((item) => item.id === workflow.id)
    if (found) select(found)
    ElMessage.success('已复制工作流')
  } catch (error) { ElMessage.error(`复制工作流失败：${error.message}`) } finally { duplicating.value = false }
}

async function doDelete () {
  if (!cur.value?.id) return
  try {
    await ElMessageBox.confirm(`删除工作流"${cur.value.name}"？此操作不能恢复。`, '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  deleting.value = true
  try {
    await api.deleteWorkflow(cur.value.id)
    cur.value = null
    await loadWorkflows()
    ElMessage.success('工作流已删除')
  } catch (error) { ElMessage.error(`删除工作流失败：${error.message}`) } finally { deleting.value = false }
}

async function doAiPlan () {
  if (!aiReq.value.trim()) { ElMessage.warning('请先填写想要生成的工作流需求'); return }
  planning.value = true
  aiDraftDef.value = ''
  aiDraftSavedId.value = -1
  try {
    // Generate draft only — never auto-save. User must explicitly accept.
    const result = await api.aiPlan({ projectId: aiProjectId.value, requirement: aiReq.value.trim(), save: false })
    if (result.def) {
      aiDraftDef.value = pretty(result.def)
      aiDraftSavedId.value = result.savedId || -1
    }
    ElMessage.success('AI 已生成工作流草稿，请审阅后接受')
  } catch (error) { ElMessage.error(`智能生成失败：${error.message}`) } finally { planning.value = false }
}

function acceptAiDraft() {
  if (!aiDraftDef.value) return
  openNew()
  defJson.value = aiDraftDef.value
  applyAdvancedJson()
  aiDraftDef.value = ''
  aiDraftSavedId.value = -1
  ElMessage.success('草稿已填入表单，请审阅后手动保存')
}

function openNewSkill () { skillDraft.value = { id: null, name: '', type: 'script', description: '', def: DSL_EXAMPLE, enabled: true }; skillValidation.value = null }
function editSkill (skill) { skillDraft.value = { ...skill, def: pretty(skill.def) }; skillValidation.value = null }
async function copyDslExample () { try { await navigator.clipboard.writeText(DSL_EXAMPLE); ElMessage.success('DSL 示例已复制') } catch { skillDraft.value.def = DSL_EXAMPLE; ElMessage.success('示例已填入编辑器') } }
async function validateDsl () {
  if (!skillDraft.value) return null
  validatingSkill.value = true
  try {
    const result = await api.validateSkill({ type: skillDraft.value.type, def: skillDraft.value.def })
    skillValidation.value = result
    if (result.valid && result.normalizedDef) skillDraft.value.def = pretty(result.normalizedDef)
    if (!result.valid) ElMessage.error(result.message || '技能规则校验失败')
    return result
  } catch (error) { ElMessage.error(`校验技能失败：${error.message}`); return null } finally { validatingSkill.value = false }
}
async function saveSkill () {
  if (!skillDraft.value?.name?.trim()) { ElMessage.warning('请填写技能名称'); return }
  const result = await validateDsl()
  if (!result?.valid) return
  savingSkill.value = true
  try {
    const body = { name: skillDraft.value.name.trim(), type: skillDraft.value.type, description: skillDraft.value.description || '', def: skillDraft.value.def, enabled: skillDraft.value.enabled }
    const saved = skillDraft.value.id ? await api.updateSkill(skillDraft.value.id, body) : await api.createSkill(body)
    ElMessage.success('自定义技能已保存')
    await loadSkills()
    editSkill(saved)
  } catch (error) { ElMessage.error(`保存自定义技能失败：${error.message}`) } finally { savingSkill.value = false }
}
async function exportSkill (skill) {
  try { downloadJson(`${skill.name}.mixcut-skill.json`, await api.exportSkill(skill.id)); ElMessage.success('Skill JSON 已导出') } catch (error) { ElMessage.error(`导出 Skill 失败：${error.message}`) }
}
async function importSkillFile (event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  try {
    await importPackFile(file, 'mixcut-skill')
  } catch (error) { ElMessage.error(`导入 Skill 失败：${error.message}`) }
}

function onWorkflowDrop (event) {
  const file = event.dataTransfer?.files?.[0]
  if (!file) return
  const lower = file.name.toLowerCase()
  const expected = lower.includes('skill') ? 'mixcut-skill' : (lower.includes('workflow') ? 'mixcut-workflow' : null)
  importPackFile(file, expected).catch((error) => ElMessage.error(`拖拽导入失败：${error.message}`))
}

async function consumePendingPackFiles () {
  const raw = sessionStorage.getItem(PENDING_PACK_KEY)
  if (!raw) return
  sessionStorage.removeItem(PENDING_PACK_KEY)
  let packs = []
  try {
    packs = JSON.parse(raw)
  } catch {
    return
  }
  if (!Array.isArray(packs) || !packs.length) return
  for (const pack of packs) {
    await importPackText(pack.text || '', pack.expectedFormat || null)
  }
}
async function toggleSkill (skill) {
  changingSkillId.value = skill.id
  try { await api.updateSkill(skill.id, { enabled: !skill.enabled }); await loadSkills(); ElMessage.success(`技能已${skill.enabled ? '停用' : '启用'}`) } catch (error) { ElMessage.error(`更新技能状态失败：${error.message}`) } finally { changingSkillId.value = null }
}
async function removeSkill (skill) {
  try { await ElMessageBox.confirm(`删除自定义技能"${skill.name}"？`, '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }) } catch { return }
  changingSkillId.value = skill.id
  try { await api.deleteSkill(skill.id); if (skillDraft.value?.id === skill.id) skillDraft.value = null; await loadSkills(); ElMessage.success('自定义技能已删除') } catch (error) { ElMessage.error(`删除自定义技能失败：${error.message}`) } finally { changingSkillId.value = null }
}

async function loadWorkflows () { list.value = await api.workflows() }
async function loadSkills () { customSkills.value = (await api.skills()).filter((skill) => skill.type === 'script' || skill.type === 'ai') }
function onGlobalPackDrop (event) {
  const files = Array.from(event.detail?.files || [])
  if (!files.length) return
  for (const file of files) {
    const lower = String(file.name || '').toLowerCase()
    const expected = lower.includes('skill') ? 'mixcut-skill' : (lower.includes('workflow') ? 'mixcut-workflow' : null)
    if (!expected) continue
    importPackFile(file, expected).catch((error) => ElMessage.error(`拖拽导入失败：${error.message}`))
  }
}

async function loadAll () {
  loading.value = true
  try {
    const [builtins, loadedProjects, loadedMaterials] = await Promise.all([api.builtinSkills(), api.projects(), api.materials({})])
    builtin.value = builtins
    projects.value = loadedProjects
    materials.value = Array.isArray(loadedMaterials) ? loadedMaterials : (loadedMaterials.items || loadedMaterials.records || [])
    await Promise.all([loadWorkflows(), loadSkills()])
  } catch (error) { ElMessage.error(`加载工作流页面失败：${error.message}`) } finally { loading.value = false }
}

watch(list, (value) => { if (!cur.value && value.length) select(value[0]) })
onMounted(async () => {
  await loadAll()
  await consumePendingPackFiles()
  window.addEventListener('mework-global-pack-drop', onGlobalPackDrop)
  window.addEventListener('mework-consume-pending-pack', consumePendingPackFiles)
})
onBeforeUnmount(() => {
  window.removeEventListener('mework-global-pack-drop', onGlobalPackDrop)
  window.removeEventListener('mework-consume-pending-pack', consumePendingPackFiles)
})
</script>

<style scoped>
.hidden-file-input { position:absolute; width:1px; height:1px; opacity:0; pointer-events:none; }
.workflow-layout { display:flex; gap:16px; align-items:stretch; min-height: calc(100vh - 132px); }
.workflow-list { width:304px; flex:0 0 304px; align-self:stretch; position:sticky; top:12px; height:calc(100vh - 132px); overflow-y:auto; }
.workflow-main { flex:1; min-width:0; }
.workflow-list-collapse, .workflow-tools-collapse { border-top:0; border-bottom:0; }
.workflow-list-collapse :deep(.el-collapse-item__header), .workflow-tools-collapse :deep(.el-collapse-item__header) { height:36px; font-size:13px; font-weight:700; color:#1f2d3d; border-bottom-color:#edf0f5; }
.workflow-list-collapse :deep(.el-collapse-item__wrap), .workflow-tools-collapse :deep(.el-collapse-item__wrap) { border-bottom-color:#edf0f5; }
.workflow-list-collapse :deep(.el-collapse-item__content), .workflow-tools-collapse :deep(.el-collapse-item__content) { padding-bottom:8px; }
.workflow-store-grid { display:flex; flex-direction:column; gap:12px; margin-top:8px; }
.workflow-store-card { padding:12px; border:1px solid #e9ebef; border-radius:8px; background:#fff; }
.workflow-store-head { display:flex; justify-content:space-between; align-items:flex-start; gap:8px; margin-bottom:8px; }
.workflow-store-subtitle { margin-top:4px; font-size:12px; color:#8b93a5; word-break:break-all; }
.workflow-store-tags { display:flex; flex-wrap:wrap; gap:6px; margin-top:10px; }
.workflow-store-links { display:flex; flex-wrap:wrap; gap:8px; margin-top:10px; }
.empty-section, .section-hint { margin:2px 0 8px; font-size:12px; }
.workflow-drop-zone { margin:10px 0 12px; padding:10px 12px; border:1px dashed #c7d7ef; border-radius:6px; background:#f8fbff; }
@media (max-width: 900px) {
  .workflow-layout { flex-direction:column; min-height:auto; }
  .workflow-list { width:100%; flex-basis:auto; position:static; top:auto; height:auto; overflow:visible; }
  .workflow-main { min-width:0; }
}
</style>
