<template>
  <div>
    <div v-if="loadError && !loading" class="card">
      <el-alert type="error" :closable="false" show-icon title="概览加载失败">
        <el-button size="small" type="primary" plain @click="load">重新加载</el-button>
      </el-alert>
    </div>
    <div class="card" v-if="ov">
      <div class="card-title">
        出片就绪度
        <el-tag v-if="ov.readyToRender" type="success" size="small">可以出片</el-tag>
        <el-tag v-else type="warning" size="small">还差 {{ ov.todo.length }} 项</el-tag>
        <span class="spacer" style="flex:1"></span>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>

      <el-alert v-if="ov.readyToRender" type="success" :closable="false" show-icon
        title="环境自检通过，直接去「出片控制台」批量跑就行" />
      <div v-else>
        <div class="muted" style="margin-bottom:8px">按下面顺序补齐，补一条少一条：</div>
        <ol style="margin:0;padding-left:20px;line-height:2">
          <li v-for="(t, i) in ov.todo" :key="i">{{ t }}</li>
        </ol>
      </div>
    </div>

    <div class="grid c4" style="margin-top:14px" v-if="ov">
      <div class="stat">
        <div class="num">{{ ov.materials.total }}</div>
        <div class="lbl">素材总数（视频 {{ ov.materials.video }} / 音频 {{ ov.materials.audio }} / 图片 {{ ov.materials.image }}）</div>
      </div>
      <div class="stat">
        <div class="num">{{ ov.projects }}</div>
        <div class="lbl">项目数</div>
      </div>
      <div class="stat">
        <div class="num">{{ ov.jobs }}</div>
        <div class="lbl">出片任务</div>
      </div>
      <div class="stat">
        <div class="num" style="color:#67c23a">{{ ov.outputs }}</div>
        <div class="lbl">已产出成片</div>
      </div>
    </div>

    <div class="grid c2" style="margin-top:14px" v-if="ov">
      <div class="card">
        <div class="card-title">素材角色分布 <span class="hint">角色决定它出现在时间线的哪一段</span></div>
        <div v-for="r in roleRows" :key="r.key" style="margin-bottom:9px">
          <div style="display:flex;justify-content:space-between;font-size:13px;margin-bottom:3px">
            <span>
              <el-tag :type="ROLE_COLOR[r.key]" size="small" effect="plain">{{ ROLE_LABEL[r.key] }}</el-tag>
              <span class="muted" style="margin-left:6px">{{ r.desc }}</span>
            </span>
            <b>{{ r.n }}</b>
          </div>
          <el-progress :percentage="pct(r.n)" :show-text="false" :stroke-width="7"
            :color="r.n > 0 ? '#409eff' : '#dcdfe6'" />
        </div>
        <div class="form-hint">
          没有「自家产品」素材，成片就只是搬运；没有背景音乐，成片是哑的。这两项优先补。
        </div>
      </div>

      <div class="card">
        <div class="card-title">运行环境</div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="ffmpeg">
            <el-tag :type="ov.env.ffmpeg ? 'success' : 'danger'" size="small">
              {{ ov.env.ffmpeg ? '已就绪' : '未检测到' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="AI 接入">
            <el-tag :type="ov.aiReady ? 'success' : 'warning'" size="small">
              {{ ov.aiReady ? '已配置' : '未配置' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="yt-dlp">
            <el-tag :type="ov.env['yt-dlp'] ? 'success' : 'info'" size="small">
              {{ ov.env['yt-dlp'] ? '可用' : '未安装（网页抓取受限）' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="you-get">
            <el-tag :type="ov.env['you-get'] ? 'success' : 'info'" size="small">
              {{ ov.env['you-get'] ? '可用' : '未安装' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Freesound 密钥">
            <el-tag :type="ov.env.freesoundKey ? 'success' : 'info'" size="small">
              {{ ov.env.freesoundKey ? '已填' : '未填' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Pixabay 密钥">
            <el-tag :type="ov.env.pixabayKey ? 'success' : 'info'" size="small">
              {{ ov.env.pixabayKey ? '已填' : '未填' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Pexels 密钥">
            <el-tag :type="ov.env.pexelsKey ? 'success' : 'info'" size="small">
              {{ ov.env.pexelsKey ? '已填' : '未填' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="输出目录">
            <span class="mono">{{ ov.env.outputDir }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <div class="card" style="margin-top:14px">
      <div class="card-title">标准出片流程 <span class="hint">第一次用照着走一遍，五分钟出第一条</span></div>
      <el-steps :active="activeStep" finish-status="success" align-center>
        <el-step title="导入素材" description="扫描本机目录 / 上传 / 网页抓取" />
        <el-step title="打角色标" description="产品、明星、实拍、BGM" />
        <el-step title="建项目" description="品牌、产品、卖点（可选）" />
        <el-step title="批量出片" description="干跑预览 → 批量渲染" />
      </el-steps>
      <el-alert v-if="!ov.aiReady" type="info" :closable="false" show-icon style="margin-top:14px"
        title="AI 是可选增强：未配置也会使用本地兜底钩子正常出片；配置后可生成更多差异化文案。" />
      <div style="margin-top:16px;text-align:center">
        <el-button type="primary" @click="$router.push('/materials')">导入真实素材</el-button>
        <el-button @click="$router.push('/ai')">配置 AI</el-button>
        <el-button @click="$router.push('/tutorial')">打开内制教程</el-button>
        <el-button type="success" @click="$router.push('/studio')">直接去出片</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, ROLE_LABEL, ROLE_COLOR } from '../api'

const router = useRouter()
// Keep the overview shape render-safe while the first request is in flight.
const emptyOverview = () => ({ env: {}, materials: { byRole: {}, total: 0, video: 0, audio: 0, image: 0 }, aiReady: false, outputs: 0, projects: 0, jobs: 0, todo: [], readyToRender: false })
const ov = ref(emptyOverview())
const loading = ref(false)
const loadError = ref(false)

const ROLE_DESC = {
  hook: '开头 3 秒抓人',
  body: '中间实拍主体',
  celebrity: '明星/达人片段',
  product: '自家产品段',
  endcard: '片尾引导',
  voice: '口播人声',
  bgm: '背景音乐',
  none: '待分类'
}

const roleRows = computed(() => {
  const by = ov.value?.materials?.byRole || {}
  return ['hook', 'body', 'celebrity', 'product', 'endcard', 'voice', 'bgm', 'none']
    .map((k) => ({ key: k, n: by[k] || 0, desc: ROLE_DESC[k] }))
})

const activeStep = computed(() => {
  const o = ov.value
  if (!o) return 0
  if (o.outputs > 0) return 4
  if (o.projects > 0) return 3
  const by = o.materials?.byRole || {}
  if ((by.product || 0) > 0 || (by.body || 0) > 0) return 2
  if (o.materials?.total > 0) return 1
  return 0
})

function pct(n) {
  const total = ov.value?.materials?.total || 0
  if (!total) return 0
  return Math.min(100, Math.round((n / total) * 100))
}

async function load() {
  loading.value = true
  try {
    const result = await api.overview()
    ov.value = result && typeof result === 'object' ? result : emptyOverview()
    loadError.value = false
  } catch (error) {
    loadError.value = true
    ElMessage.error(`概览加载失败：${error.message}`)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
