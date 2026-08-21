<template>
  <div class="fixed-order-page">
    <div class="card page-intro">
      <div class="card-title">{{ FIXED_ORDER_NAME }} <span class="grow"></span><el-tag type="info" effect="plain">可预置、可编辑</el-tag></div>
      <p class="muted">为不同行业提供一套容易理解的产片顺序建议。选择后会把步骤带到出片控制台，用户仍可修改名称、文件夹、时长，也可以增加或删除步骤。</p>
      <el-alert type="info" :closable="false" show-icon title="这里不是固定八段模板">
        顺序只限制当前任务的读取边界；应用不会替你写死文件夹，也不会自动跨步骤取材。
      </el-alert>
    </div>
    <div class="card creation-card">
      <div class="card-title">自定义顺序 <span class="grow"></span><el-tag size="small" effect="plain">可导入、可编辑</el-tag></div>
      <el-form label-position="top" class="creation-form">
        <el-form-item label="顺序名称"><el-input v-model="custom.name" maxlength="80" placeholder="例如：新品测评转化顺序" /></el-form-item>
        <el-form-item label="步骤（每行一个步骤名称）"><el-input v-model="custom.stepsText" type="textarea" :rows="4" maxlength="1000" placeholder="开头钩子&#10;问题演示&#10;产品展示&#10;行动收尾" /></el-form-item>
        <el-form-item label="AI 编排要求"><el-input v-model="custom.requirement" maxlength="500" placeholder="例如：面向新手妈妈，突出安全、真实体验和购买理由" /></el-form-item>
      </el-form>
      <div class="template-row">
        <el-button v-for="template in marketingTemplates" :key="template.pattern" size="small" plain @click="applyStages(template.name, template.stages)">{{ template.pattern }} · {{ template.name }}</el-button>
      </div>
      <div class="preset-actions">
        <el-button type="primary" @click="applyCustom">应用自定义顺序</el-button>
        <el-button :loading="aiLoading" @click="suggestWithAi">AI 生成顺序建议</el-button>
        <el-upload :show-file-list="false" accept="application/json,.json" :auto-upload="false" :on-change="importPreset"><el-button plain>导入预置 JSON</el-button></el-upload>
      </div>
      <p class="muted">导入内容只接受猫作固定顺序 JSON。AI 只生成步骤建议，不会生成链接、路径或下载命令。</p>
    </div>
    <div class="preset-grid">
      <div v-for="preset in FIXED_ORDER_PRESETS" :key="preset.key" class="card preset-card">
        <div class="card-title">{{ preset.name }} <span class="grow"></span><el-tag size="small" effect="plain">{{ preset.stages.length }} 步建议</el-tag></div>
        <p class="muted">{{ preset.description }}</p>
        <ol class="stage-preview">
          <li v-for="stage in preset.stages" :key="stage.order"><b>{{ stage.order }}</b><span>{{ stage.name }}</span></li>
        </ol>
        <div class="preset-actions">
          <el-button type="primary" @click="apply(preset.key)">应用到出片控制台</el-button>
          <el-button plain @click="download(preset)">下载预置 JSON</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { FIXED_ORDER_NAME, FIXED_ORDER_PRESETS } from '../fixedOrderPresets'

const router = useRouter()
const aiLoading = ref(false)
const custom = reactive({ name: '', stepsText: '', requirement: '' })
const marketingTemplates = [
  { pattern: '1234', name: '标准成交结构', stages: [{ name: '开头钩子', targetSec: 8, folderKeywords: ['钩子', '开场', 'hook'] }, { name: '痛点讲解', targetSec: 16, folderKeywords: ['痛点', '口播', '讲解'] }, { name: '产品展示', targetSec: 18, folderKeywords: ['产品', '使用', '证明'] }, { name: '成交收口', targetSec: 10, folderKeywords: ['下单', '收尾', '片尾'] }] },
  { pattern: '12234', name: '双讲解强化', stages: [{ name: '开头钩子', targetSec: 8, folderKeywords: ['钩子', '开场'] }, { name: '问题讲解', targetSec: 14, folderKeywords: ['痛点', '问题'] }, { name: '卖点讲解', targetSec: 14, folderKeywords: ['口播', '卖点'] }, { name: '产品展示', targetSec: 16, folderKeywords: ['产品', '使用'] }, { name: '成交收口', targetSec: 10, folderKeywords: ['下单', '片尾'] }] },
  { pattern: '123234', name: '展示后再解释', stages: [{ name: '开头钩子', targetSec: 8, folderKeywords: ['钩子', '开场'] }, { name: '痛点讲解', targetSec: 14, folderKeywords: ['痛点', '讲解'] }, { name: '产品证明', targetSec: 16, folderKeywords: ['产品', '证明'] }, { name: '补充讲解', targetSec: 12, folderKeywords: ['卖点', '口播'] }, { name: '二次展示', targetSec: 14, folderKeywords: ['使用', '产品'] }, { name: '成交收口', targetSec: 10, folderKeywords: ['下单', '收尾'] }] }
]

function validStages (stages) {
  if (!Array.isArray(stages) || stages.length < 1 || stages.length > 32) return null
  const normalized = []
  for (const [index, stage] of stages.entries()) {
    const name = String(stage?.name || '').trim()
    if (name.length < 2 || name.length > 80) return null
    const targetSec = Number(stage?.targetSec || 6)
    if (!Number.isFinite(targetSec) || targetSec < 1 || targetSec > 300) return null
    const folderKeywords = Array.isArray(stage?.folderKeywords) ? stage.folderKeywords
      .map((value) => String(value || '').trim()).filter((value) => value && value.length <= 40).slice(0, 5) : []
    normalized.push({ order: index + 1, name, targetSec, folderKeywords, shortagePolicy: stage?.shortagePolicy === 'fallback' ? 'fallback' : 'block' })
  }
  return normalized
}
function applyStages (name, stages) {
  const normalized = validStages(stages)
  if (!normalized) { ElMessage.error('顺序至少需要一个有效步骤；名称 2–80 字，时长 1–300 秒'); return false }
  const payload = { format: 'mework-fixed-order', schemaVersion: 1, key: `custom-${Date.now()}`, name: String(name || '自定义顺序').trim().slice(0, 80), description: '用户自定义的可编辑产片顺序', stages: normalized }
  sessionStorage.setItem('mework-fixed-order-custom', JSON.stringify(payload))
  router.push({ path: '/studio', query: { fixedOrderCustom: '1' } })
  ElMessage.success('已载入自定义顺序，请在出片控制台继续绑定文件夹和调整步骤')
  return true
}
function applyCustom () {
  const lines = custom.stepsText.split(/\r?\n/).map((value) => value.trim()).filter(Boolean)
  applyStages(custom.name || '自定义顺序', lines.map((name, index) => ({ name, order: index + 1, targetSec: 6 })))
}
async function importPreset (uploadFile) {
  try {
    const raw = await uploadFile.raw.text()
    const pack = JSON.parse(raw)
    if (pack?.format !== 'mework-fixed-order' || pack?.schemaVersion !== 1) throw new Error('不是受支持的猫作固定顺序 JSON')
    if (!applyStages(pack.name, pack.stages)) return
  } catch (error) { ElMessage.error(`导入失败：${error.message || '文件不是有效 JSON'}`) }
}
async function suggestWithAi () {
  if (aiLoading.value) return
  aiLoading.value = true
  try {
    const result = await api.fixedOrderSuggestion({ requirement: custom.requirement || custom.stepsText || '生成适合当前项目的产片顺序建议' })
    if (applyStages(custom.name || 'AI 顺序建议', result.stages)) ElMessage.success(result.message || '已生成可编辑的顺序建议')
  } catch (error) { ElMessage.error(`AI 建议失败：${error.message || '请检查 AI 配置'}`) } finally { aiLoading.value = false }
}

function apply (key) {
  sessionStorage.setItem('mework-fixed-order-preset', key)
  router.push({ path: '/studio', query: { fixedOrderPreset: key } })
  ElMessage.success('已选择产片固定顺序技能，请在出片控制台继续绑定文件夹和调整步骤')
}
function download (preset) {
  const blob = new Blob([JSON.stringify({ format: 'mework-fixed-order', schemaVersion: 1, name: preset.name, description: preset.description, stages: preset.stages }, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${preset.key}.mework-fixed-order.json`
  link.click()
  // 延迟释放对象 URL,避免部分浏览器在下载刚开始时同步 revoke 导致下载被取消
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<style scoped>
.fixed-order-page { display:flex; flex-direction:column; gap:16px; }
.page-intro p { line-height:1.7; }
.creation-card { display:flex; flex-direction:column; }
.creation-form { display:grid; grid-template-columns:repeat(3,minmax(180px,1fr)); gap:0 12px; }
.creation-form :deep(.el-form-item) { margin-bottom:8px; }
.preset-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(320px,1fr)); gap:16px; }
.preset-card { min-height:320px; display:flex; flex-direction:column; }
@media (max-width: 760px) { .creation-form { grid-template-columns:1fr; } }
.stage-preview { display:grid; grid-template-columns:1fr 1fr; gap:7px 16px; padding-left:24px; color:#606266; line-height:1.5; flex:1; }
.stage-preview li { display:flex; gap:8px; }
.stage-preview b { color:#409eff; min-width:18px; }
.template-row { display:flex; flex-wrap:wrap; gap:8px; margin:4px 0 10px; }
.preset-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:16px; }
</style>
