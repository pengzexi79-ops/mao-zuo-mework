<template>
  <div class="projects-layout">
    <div class="card projects-list-panel">
      <div class="card-title">
        项目
        <span style="flex:1"></span>
        <el-button size="small" type="primary" @click="openNew">新建</el-button>
      </div>
      <el-alert v-if="loadError" type="error" :closable="false" show-icon style="margin-bottom:10px"
        title="项目列表加载失败，请刷新页面重试" />
      <div v-else-if="loading" v-loading="loading" style="height:48px"></div>
      <div v-else-if="!userProjects.length" class="muted">还没有我的项目。可新建项目，或从下方内置模板商店复制一份。</div>
      <div v-if="userProjects.length > 8" style="margin-top:8px;text-align:center"><el-button size="small" link type="primary" @click="showAll = !showAll">{{ showAll ? '收起' : '展开全部（' + userProjects.length + '）' }}</el-button></div>
      <div v-for="p in visibleList" :key="p.id"
        :style="itemStyle(p)"
        @click="select(p)">
        <div style="font-weight:600;font-size:13px">{{ p.name }}</div>
        <div class="muted" style="margin-top:2px">{{ p.brand || '未填品牌' }} · {{ p.category || '未填品类' }}</div>
      </div>
      <el-collapse v-if="builtinProjects.length" style="margin-top:12px">
        <el-collapse-item :title="`内置项目模板商店（${builtinProjects.length}）`" name="project-store">
          <div class="form-hint">模板本体只读。复制后可修改品牌、卖点、AI 路由和默认出片参数。</div>
          <div v-for="p in builtinProjects" :key="p.id" class="project-template-row">
            <div><b>{{ p.name }}</b><div class="muted">{{ p.category || '通用' }} · {{ p.product || '项目模板' }}</div></div>
            <el-button link type="primary" size="small" :loading="duplicatingId === p.id" :disabled="duplicatingId !== null" @click.stop="duplicateTemplate(p)">复制使用</el-button>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <div class="projects-main">
      <div class="card" v-if="cur">
        <div class="card-title">
          {{ cur.id ? '编辑项目' : '新建项目' }}
          <span class="hint">这些信息会作为系统提示提供给人工智能，填得越具体，钩子越对味</span>
          <span style="flex:1"></span>
          <el-tag v-if="cur.isBuiltin" size="small" type="info" effect="plain">内置模板</el-tag>
          <el-button v-if="cur.id && !cur.isBuiltin" size="small" type="danger" plain @click="doDelete">删除</el-button>
          <el-button size="small" type="primary" :loading="saving" :disabled="cur.isBuiltin" @click="save">保存</el-button>
        </div>

        <!-- AI 辅助起草 -->
        <el-collapse v-model="aiCollapse">
          <el-collapse-item title="AI 辅助起草" name="ai">
            <div class="form-hint" style="margin-bottom:8px">
              输入需求描述，AI 会生成一个项目草稿。您需要审阅后明确点击"接受草稿"，才会填入下方表单。
              草稿不会自动保存，也不会覆盖已有项目。
            </div>
            <el-input v-model="draftRequirement" type="textarea" :rows="3"
              placeholder="例如：花梨记小棕瓶精华液 8月抖音投放，主打成分党的成分透明和28天回购率高"
              :disabled="drafting" />
            <div style="margin-top:10px;display:flex;align-items:center;gap:10px">
              <el-button type="success" :loading="drafting" :disabled="!draftRequirement.trim()"
                @click="generateDraft">生成草稿</el-button>
              <el-button v-if="draftResult" type="primary" @click="acceptDraft">接受草稿，填入表单</el-button>
              <el-button v-if="draftResult" @click="clearDraft">放弃草稿</el-button>
              <el-tag v-if="draftResult" style="margin-left:8px" :type="draftResult.aiGenerated ? 'success' : 'warning'" size="small">
                {{ draftResult.aiGenerated ? 'AI 生成' : '本地兜底' }}
              </el-tag>
            </div>
            <el-alert v-if="draftError" :title="draftError" type="error" :closable="false" show-icon
              style="margin-top:10px" />
            <!-- 草稿预览卡片 -->
            <div v-if="draftResult" style="margin-top:12px;padding:12px;background:#f5f7fa;border-radius:8px;border:1px solid #e4e7ed">
              <div style="display:flex;flex-wrap:wrap;gap:8px">
                <el-tag size="small" type="primary">名称：{{ draftResult.name }}</el-tag>
                <el-tag size="small">品牌：{{ draftResult.brand }}</el-tag>
                <el-tag size="small">品类：{{ draftResult.category }}</el-tag>
                <el-tag size="small">产品：{{ draftResult.product }}</el-tag>
                <el-tag size="small">语气：{{ draftResult.tone }}</el-tag>
                <el-tag size="small">人群：{{ draftResult.audience }}</el-tag>
              </div>
              <div style="margin-top:6px;font-size:12px;color:#606266">
                <div><b>卖点：</b>{{ draftResult.sellingPoints }}</div>
                <div v-if="draftResult.bannedWords" style="margin-top:2px"><b>禁用词：</b>{{ draftResult.bannedWords }}</div>
                <div v-if="draftResult.extraPrompt" style="margin-top:2px"><b>补充提示：</b>{{ draftResult.extraPrompt }}</div>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>

        <el-form label-width="90px">
          <div class="grid c2">
            <el-form-item label="项目名"><el-input v-model="cur.name" placeholder="必填，例如 XX 精华液-8月投放" /></el-form-item>
            <el-form-item label="品牌"><el-input v-model="cur.brand" placeholder="例如 花梨记" /></el-form-item>
            <el-form-item label="品类">
              <el-select v-model="cur.category" filterable allow-create style="width:100%" placeholder="美妆 / 护肤 / 食品 …">
                <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
            <el-form-item label="产品名"><el-input v-model="cur.product" placeholder="出现在口播与字幕里" /></el-form-item>
            <el-form-item label="目标人群"><el-input v-model="cur.audience" placeholder="例如 25-35 岁 敏感肌 上班族" /></el-form-item>
            <el-form-item label="语气">
              <el-select v-model="cur.tone" filterable allow-create style="width:100%">
                <el-option v-for="t in TONES" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="核心卖点">
            <el-input v-model="cur.sellingPoints" type="textarea" :rows="3"
              placeholder="一行一个，例如&#10;3 秒吸收不黏腻&#10;成分党实测烟酰胺 5%&#10;28 天回购率 62%" />
          </el-form-item>
          <el-form-item label="禁用词">
            <el-input v-model="cur.bannedWords" type="textarea" :rows="2"
              placeholder="逗号分隔。广告法高危词建议全填：最、第一、国家级、根治、永久…" />
            <div class="form-hint">
              这些词会写进 AI 提示词里要求规避。但最终合规责任在投放方，发布前请人工过一遍。
            </div>
          </el-form-item>
          <el-form-item label="补充提示">
            <el-input v-model="cur.extraPrompt" type="textarea" :rows="2"
              placeholder="额外要求，例如：不要出现价格；结尾统一引导「主页领券」" />
          </el-form-item>
        </el-form>

        <el-divider content-position="left">
          <span style="font-size:13px">默认出片参数</span>
        </el-divider>
        <div class="form-hint" style="margin-bottom:10px">
          在这里定好，出片控制台就不用每次调。控制台里改的值只覆盖当次，不会写回这里。
        </div>
        <el-form label-width="110px">
          <div class="grid c3">
            <el-form-item label="时长区间">
              <el-slider v-model="durRange" range :min="30" :max="180" :marks="{ 50: '50', 100: '100', 150: '150' }" />
            </el-form-item>
            <el-form-item label="密集模式">
              <el-switch v-model="dp.dense" />
              <span class="muted" style="margin-left:8px">开启后向 100 秒收敛</span>
            </el-form-item>
            <el-form-item label="切片时长">
              <el-input-number v-model="dp.sliceSec" :min="1" :max="8" :step="0.5" size="small" />
              <span class="muted" style="margin-left:6px">秒</span>
            </el-form-item>
            <el-form-item label="产品段数量">
              <el-input-number v-model="dp.productSlots" :min="0" :max="8" size="small" />
            </el-form-item>
            <el-form-item label="明星占比">
              <el-slider v-model="celebPct" :min="0" :max="80" :format-tooltip="(v) => v + '%'" />
            </el-form-item>
            <el-form-item label="画布">
              <el-select v-model="canvasKey" style="width:100%">
                <el-option label="竖屏 1080×1920（抖音）" value="1080x1920" />
                <el-option label="方形 1080×1080" value="1080x1080" />
                <el-option label="横屏 1920×1080" value="1920x1080" />
              </el-select>
            </el-form-item>
          </div>
        </el-form>
      </div>

      <div class="card" v-else>
        <div class="muted">左侧选一个项目，或点「新建」。</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const CATEGORIES = ['美妆', '护肤', '食品饮料', '母婴', '3C 数码', '家清日用', '服饰', '保健品']
const TONES = ['真诚测评', '闺蜜安利', '专业成分党', '搞笑玩梗', '高级质感', '急促带货']

const list = ref([])
const showAll = ref(false)
const duplicatingId = ref(null)
const userProjects = computed(() => list.value.filter((project) => !project.isBuiltin))
const builtinProjects = computed(() => list.value.filter((project) => project.isBuiltin))
const visibleList = computed(() => (showAll.value ? userProjects.value : userProjects.value.slice(0, 8)))
const cur = ref(null)
const saving = ref(false)
const loading = ref(false)
const loadError = ref(false)

const dp = reactive({
  minSec: 50, maxSec: 150, dense: true, sliceSec: 3,
  productSlots: 3, celebrityRatio: 0.25, width: 1080, height: 1920
})

// AI draft state
const aiCollapse = ref([])
const draftRequirement = ref('')
const drafting = ref(false)
const draftResult = ref(null)
const draftError = ref('')

const durRange = computed({
  get: () => [dp.minSec, dp.maxSec],
  set: (v) => { dp.minSec = v[0]; dp.maxSec = v[1] }
})
const celebPct = computed({
  get: () => Math.round((dp.celebrityRatio || 0) * 100),
  set: (v) => { dp.celebrityRatio = v / 100 }
})
const canvasKey = computed({
  get: () => `${dp.width}x${dp.height}`,
  set: (v) => { const [w, h] = v.split('x'); dp.width = +w; dp.height = +h }
})

function itemStyle(p) {
  const active = cur.value && cur.value.id === p.id
  return {
    padding: '9px 10px',
    borderRadius: '6px',
    cursor: 'pointer',
    marginBottom: '6px',
    border: '1px solid ' + (active ? '#409eff' : '#e9ebef'),
    background: active ? '#ecf5ff' : '#fff'
  }
}

function loadParams(json) {
  let o = {}
  try { o = json ? JSON.parse(json) : {} } catch { o = {} }
  Object.assign(dp, {
    minSec: 50, maxSec: 150, dense: true, sliceSec: 3,
    productSlots: 3, celebrityRatio: 0.25, width: 1080, height: 1920
  }, o)
}

function select(p) {
  cur.value = { ...p }
  loadParams(p.defaultParams)
}

function openNew() {
  cur.value = {
    id: null, name: '', brand: '', category: '美妆', product: '',
    sellingPoints: '', audience: '', tone: '真诚测评',
    bannedWords: '最,第一,顶级,国家级,根治,永久,100%,绝对', extraPrompt: ''
  }
  loadParams(null)
}

async function save() {
  if (!cur.value.name) return ElMessage.warning('请填写项目名')
  saving.value = true
  try {
    const body = { ...cur.value, defaultParams: JSON.stringify({ ...dp }) }
    const saved = cur.value.id
      ? await api.updateProject(cur.value.id, body)
      : await api.createProject(body)
    ElMessage.success('已保存')
    await load()
    const found = list.value.find((x) => x.id === saved.id)
    if (found) select(found)
  } finally {
    saving.value = false
  }
}

async function doDelete() {
  await api.deleteProject(cur.value.id)
  cur.value = null
  load()
}

async function duplicateTemplate (template) {
  duplicatingId.value = template.id
  try {
    const saved = await api.duplicateProject(template.id)
    await load()
    const found = list.value.find((project) => project.id === saved.id)
    if (found) select(found)
    ElMessage.success('项目模板副本已创建，可继续编辑')
  } catch (error) {
    ElMessage.error(`复制项目模板失败：${error.message}`)
  } finally {
    duplicatingId.value = null
  }
}

async function load() {
  loading.value = true
  try {
    list.value = await api.projects()
    loadError.value = false
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function generateDraft() {
  const requirement = draftRequirement.value.trim()
  if (!requirement) return
  drafting.value = true
  draftError.value = ''
  draftResult.value = null
  try {
    draftResult.value = await api.projectDraft({ requirement })
    aiCollapse.value = ['ai']
  } catch (e) {
    draftError.value = e.message || 'AI 起草失败，请重试'
  } finally {
    drafting.value = false
  }
}

function acceptDraft() {
  const d = draftResult.value
  if (!d) return
  cur.value.name = d.name || cur.value.name || ''
  cur.value.brand = d.brand || ''
  cur.value.category = d.category || ''
  cur.value.product = d.product || ''
  cur.value.sellingPoints = d.sellingPoints || ''
  cur.value.audience = d.audience || ''
  cur.value.tone = d.tone || ''
  cur.value.bannedWords = d.bannedWords || ''
  cur.value.extraPrompt = d.extraPrompt || ''
  if (d.defaultParams) loadParams(d.defaultParams)
  clearDraft()
  ElMessage.success('草稿已填入表单，请审阅后手动保存')
}

function clearDraft() {
  draftResult.value = null
  draftError.value = ''
  draftRequirement.value = ''
}

watch(list, () => {
  if (!cur.value && userProjects.value.length) select(userProjects.value[0])
})

onMounted(load)
</script>

<style scoped>
.projects-layout { display: flex; gap: 14px; align-items: flex-start; min-width: 0; }
.projects-list-panel { width: 280px; flex: 0 0 280px; }
.projects-main { flex: 1; min-width: 0; }
.project-template-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 0; border-bottom: 1px solid #f0f2f5; }

@media (max-width: 900px) {
  .projects-layout { flex-direction: column; }
  .projects-list-panel, .projects-main { width: 100%; flex-basis: auto; box-sizing: border-box; }
}
</style>
