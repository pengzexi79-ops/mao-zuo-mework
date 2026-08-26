# Mework 视频内容勘察指令

用途：在出片前或质检返工时，让 ZCode 从顶尖混剪师、切片师、电商带货剪辑师视角勘察素材与成片，不直接安装模型，不破坏现有环境。

## 执行边界

- 先审计现有环境和代码，再决定是否需要补环境。
- 优先复用 Mework 已有 FFmpeg、FFprobe、RapidOCR、faster-whisper、Demucs、Edge-TTS、ChatTTS、ProcRunner、素材库、任务日志和渲染参数。
- 缺少依赖时，只通过项目已有安装脚本、私有 `.venv`、前端 package 管理和 Maven 依赖补齐；不要升级 PyTorch、CUDA、Python、FFmpeg 或系统环境。
- 不从前端接收任意 URL、命令、FFmpeg filter、模型路径或本机绝对路径。
- 所有新素材、分离音频、缓存和输出必须进入项目受控目录。

## 勘察维度

1. 产品识别
   - 产品是否清楚出镜。
   - 品类、品牌、核心卖点、使用场景是否可从画面或项目文本推断。
   - 是否缺产品 close-up、包装、质地、使用前后、测评证明、成交引导。

2. 画面结构
   - 前 3 秒是否有强钩子画面。
   - 是否存在片头、片尾、黑屏、空镜、水时长、无关 IP、重复素材。
   - 是否有足够 hook/body/product/celebrity/endcard 角色素材。
   - 是否需要 AI 从固定公开来源补齐素材。

3. 旧字幕与画面干净度
   - 是否有旧字幕、旧贴纸、水印、平台 UI、关注引导、无关文字。
   - 若旧字幕位于常见底部区域，建议开启 `cleanSourceSubtitles=true` 和 `sourceSubtitleCleanMode=subtitle-safe-band`。
   - 若旧字幕位置复杂，只标记风险，不要伪装成已完成深度 inpaint。

4. 音频结构
   - 判断素材音频是人声、BGM、环境声还是混合音轨。
   - 人声和 BGM 混在一起时，优先建议在素材库使用人声分离，分离后 voice / bgm 分轨入库。
   - BGM 不压人声，带货口播优先，默认 BGM 音量建议 0.16 左右。

5. 字幕同步
   - AI 口播字幕必须来自真实生成音频的 ASR 时间轴。
   - 授权素材字幕只使用素材库中开启 `transcribeForSubtitles` 并成功转录的素材。
   - 发现字幕过长、断句差、错字、重叠，应返工。

6. 带货剪辑判断
   - 前 3 秒：冲突、结果、反常识、痛点、视觉冲击至少命中一个。
   - 中段：产品段均匀插入，不能只剪达人或泛素材。
   - 节奏：2 到 3 秒切片为主，长素材拆分并严格避免同源重叠。
   - 结尾：有 endcard 或明确行动引导。

## 推荐工作流参数

- `autoUseCrawledMaterials=true`
- `projectRelevantOnly=true`
- `dedupStrictness=strict`
- `sliceSec=2.2`
- `sliceJitter=0.4`
- `maxSlicesPerMaterial=3`
- `hookSec=3`
- `productSlots=5`
- `bgmVolume=0.16`
- `cleanSourceSubtitles=true` when old subtitles are visible in the lower caption band
- `sourceSubtitleCleanMode=subtitle-safe-band`

## 交付标准

- 音画同步。
- 无明显旧字幕和新字幕叠加。
- 无黑屏、无片头片尾、无水时长。
- 产品至少在前 10 秒内出现一次，且中段持续穿插。
- 人声清楚，BGM 不压口播。
- 成片能直接发布，不需要人工二次修。 
