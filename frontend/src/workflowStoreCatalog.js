function workflowPack (name, description, steps, sourceRepo) {
  return {
    format: 'mixcut-workflow',
    schemaVersion: 1,
    name,
    version: '1.0',
    description,
    definition: { steps },
    source: '开源模板商店',
    sourceRepo
  }
}

export const WORKFLOW_STORE_ITEMS = [
  {
    key: 'mework-ai-material-director',
    name: 'Mework AI 素材导演',
    repo: 'local/Mework',
    repoUrl: 'https://github.com/',
    homepageUrl: 'https://github.com/',
    stars: 0,
    tags: ['电商带货', 'AI补素材', '旧字幕清理', '内容勘察'],
    summary: '从顶尖混剪师、切片师和电商带货视角，先勘察项目与画面内容，再允许应用 AI 从固定公开源补齐素材，并可选清理旧字幕安全区。',
    pack: workflowPack(
      'AI 素材导演带货混剪',
      '面向抖音带货成片：AI 识别项目、本地匹配、公开素材补齐、强钩子快切、严格去重和可选旧字幕清理。',
      [
        { skill: 'select_materials', args: { roles: ['hook', 'body', 'celebrity', 'product', 'endcard', 'voice', 'bgm'], limit: 300, keyword: '带货 种草 产品 实拍 达人 口播 细节' } },
        { skill: 'set_duration', args: { minSec: 50, maxSec: 120, dense: true } },
        { skill: 'set_slice', args: { sliceSec: 2.2, jitter: 0.4, explode: true, maxPerMaterial: 3 } },
        { skill: 'set_structure', args: { hookSec: 3, celebrityRatio: 0.2, productSlots: 5, productSec: 2.6, endcard: true } },
        { skill: 'set_canvas', args: { width: 1080, height: 1920, fps: 30 } },
        { skill: 'set_quality', args: { autoUseCrawledMaterials: true, projectRelevantOnly: true, dedupStrictness: 'strict', cleanSourceSubtitles: true, sourceSubtitleCleanMode: 'subtitle-safe-band', autoSubtitles: false, burnAiVoiceCaptions: true } },
        { skill: 'gen_hook', args: { extra: '以前3秒强钩子开场，优先使用画面冲击、结果对比、痛点反常识；口播短句、有停顿、有成交导向。' } },
        { skill: 'pick_audio', args: { bgmVolume: 0.16, autoMatchAudio: true, hookAudioVolume: 1.0 } }
      ],
      'local/Mework'
    )
  },
  {
    key: 'moneyprinterturbo',
    name: 'MoneyPrinterTurbo',
    repo: 'harry0703/MoneyPrinterTurbo',
    repoUrl: 'https://github.com/harry0703/MoneyPrinterTurbo',
    homepageUrl: 'https://github.com/harry0703/MoneyPrinterTurbo',
    stars: 103276,
    tags: ['AI生成', '短视频', '自动化工作流'],
    summary: '一键生成高清短视频的自动化工作流，适合带货、口播和知识类内容。',
    pack: workflowPack(
      '爆款带货混剪（开源参考）',
      '参考 MoneyPrinterTurbo 的自动化短视频流程整理而成',
      [
        { skill: 'select_materials', args: { roles: ['hook', 'body', 'celebrity', 'product', 'voice', 'bgm'], limit: 120, keyword: '带货 口播 种草' } },
        { skill: 'set_duration', args: { minSec: 50, maxSec: 120, dense: true } },
        { skill: 'set_slice', args: { sliceSec: 2.4, sliceJitter: 0.35, explodeLongClips: true, maxSlicesPerMaterial: 3 } },
        { skill: 'set_structure', args: { hookSec: 3, celebrityRatio: 0.25, productSlots: 4, productSec: 3, endcard: true } },
        { skill: 'set_canvas', args: { width: 1080, height: 1920, fps: 30 } },
        { skill: 'gen_hook', args: { extra: '前3秒直接抛卖点，保持高密度快切与强钩子。' } },
        { skill: 'pick_audio', args: { bgmVolume: 0.16 } }
      ],
      'harry0703/MoneyPrinterTurbo'
    )
  },
  {
    key: 'narratoai',
    name: 'NarratoAI',
    repo: 'linyqh/NarratoAI',
    repoUrl: 'https://github.com/linyqh/NarratoAI',
    homepageUrl: 'https://www.narratoai.co',
    stars: 10709,
    tags: ['解说剪辑', 'AI脚本', '视频自动化'],
    summary: '适合影视解说、知识口播和长文案拆解的自动化剪辑模板。',
    pack: workflowPack(
      '影视解说节奏包（开源参考）',
      '参考 NarratoAI 的影视解说与自动化剪辑流程整理而成',
      [
        { skill: 'select_materials', args: { roles: ['hook', 'body', 'voice', 'bgm'], limit: 80, keyword: '解说 剧情 讲解' } },
        { skill: 'set_duration', args: { minSec: 90, maxSec: 180, dense: false } },
        { skill: 'set_slice', args: { sliceSec: 4.0, sliceJitter: 0.5, explodeLongClips: true, maxSlicesPerMaterial: 2 } },
        { skill: 'set_structure', args: { hookSec: 4, celebrityRatio: 0.1, productSlots: 1, productSec: 3, endcard: false } },
        { skill: 'set_canvas', args: { width: 1080, height: 1920, fps: 30 } },
        { skill: 'gen_hook', args: { extra: '更适合长解说节奏，先交代主题，再分段讲清楚。' } },
        { skill: 'pick_audio', args: { bgmVolume: 0.2 } }
      ],
      'linyqh/NarratoAI'
    )
  },
  {
    key: 'video-shotcraft',
    name: 'video-shotcraft',
    repo: 'Vincentwei1021/video-shotcraft',
    repoUrl: 'https://github.com/Vincentwei1021/video-shotcraft',
    homepageUrl: 'https://vincentwei1021.github.io/video-shotcraft/',
    stars: 4978,
    tags: ['Remotion', '镜头脚本', '商品视频'],
    summary: '面向商品短视频和镜头脚本的生产级 Remotion 模板。',
    pack: workflowPack(
      '商品镜头脚本包（开源参考）',
      '参考 video-shotcraft 的商品视频镜头编排整理而成',
      [
        { skill: 'select_materials', args: { roles: ['hook', 'body', 'product', 'bgm'], limit: 100, keyword: '商品 镜头 展示' } },
        { skill: 'set_duration', args: { minSec: 30, maxSec: 90, dense: true } },
        { skill: 'set_slice', args: { sliceSec: 2.2, sliceJitter: 0.25, explodeLongClips: true, maxSlicesPerMaterial: 4 } },
        { skill: 'set_structure', args: { hookSec: 2.5, celebrityRatio: 0.05, productSlots: 5, productSec: 2.5, endcard: true } },
        { skill: 'set_canvas', args: { width: 1080, height: 1920, fps: 30 } },
        { skill: 'gen_hook', args: { extra: '重点突出商品细节和镜头切换节奏。' } },
        { skill: 'pick_audio', args: { bgmVolume: 0.18 } }
      ],
      'Vincentwei1021/video-shotcraft'
    )
  },
  {
    key: 'auto-editor',
    name: 'auto-editor',
    repo: 'WyattBlue/auto-editor',
    repoUrl: 'https://github.com/WyattBlue/auto-editor',
    homepageUrl: 'https://auto-editor.com',
    stars: 4877,
    tags: ['自动去停顿', '快切', '节奏修剪'],
    summary: '自动去除停顿、修剪节奏和处理讲话类视频的常用开源工具。',
    pack: workflowPack(
      '自动去停顿快切包（开源参考）',
      '参考 auto-editor 的自动修剪思路整理而成',
      [
        { skill: 'select_materials', args: { roles: ['body', 'voice', 'bgm'], limit: 60, keyword: '讲话 去停顿 口播' } },
        { skill: 'set_duration', args: { minSec: 40, maxSec: 100, dense: true } },
        { skill: 'set_slice', args: { sliceSec: 1.4, sliceJitter: 0.2, explodeLongClips: true, maxSlicesPerMaterial: 6 } },
        { skill: 'set_structure', args: { hookSec: 2, celebrityRatio: 0.05, productSlots: 0, productSec: 0.8, endcard: false } },
        { skill: 'set_canvas', args: { width: 1080, height: 1920, fps: 30 } },
        { skill: 'pick_audio', args: { bgmVolume: 0.2 } }
      ],
      'WyattBlue/auto-editor'
    )
  },
  {
    key: 'template-audiogram',
    name: 'template-audiogram',
    repo: 'remotion-dev/template-audiogram',
    repoUrl: 'https://github.com/remotion-dev/template-audiogram',
    homepageUrl: 'https://template-audiogram.vercel.app',
    stars: 267,
    tags: ['Remotion', '播客切片', '波形视频'],
    summary: '官方模板级别的播客音频短视频方案，适合解说、播客和口播切片。',
    pack: workflowPack(
      '播客波形短切包（开源参考）',
      '参考 Remotion audiogram 模板整理而成',
      [
        { skill: 'select_materials', args: { roles: ['voice', 'bgm', 'hook'], limit: 40, keyword: '播客 口播 波形' } },
        { skill: 'set_duration', args: { minSec: 30, maxSec: 80, dense: false } },
        { skill: 'set_slice', args: { sliceSec: 3.2, sliceJitter: 0.3, explodeLongClips: false, maxSlicesPerMaterial: 2 } },
        { skill: 'set_structure', args: { hookSec: 1.5, celebrityRatio: 0, productSlots: 0, productSec: 0.8, endcard: false } },
        { skill: 'set_canvas', args: { width: 1080, height: 1920, fps: 30 } },
        { skill: 'pick_audio', args: { bgmVolume: 0.22 } }
      ],
      'remotion-dev/template-audiogram'
    )
  }
]
