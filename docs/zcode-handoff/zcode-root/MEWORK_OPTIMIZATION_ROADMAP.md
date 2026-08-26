# 猫作 · Mework 全量优化与模块化开发地图

更新时间：2026-08-26  
当前应用版本：2.2.150  
当前运行地址：http://127.0.0.1:8760/  
当前 Git 分支：codex/ai2-history-closeout-20260825

## 先看结论

当前已经完成并验证：

- P2 任务可靠性：任务持久化、取消、超时边界、恢复、去重、进程级取消。
- P3-1：固定媒体 fixture、SHA256、FFprobe 元数据校验。
- P3-2：隔离数据库契约、本地 HTTP mock、429/5xx/超时/重试/幂等验证。
- P3-3：离线素材登记、质量准入、结构化分析、真实 FFmpeg 渲染、QC、失败候选隔离。
- P3-4：`ai_mix_video_acceptance` 的 JobOutput/OutputVersion/OutputRepair 恢复契约门控。
- P3-5：全新安装门控、独立端口/数据目录、健康检查和进程清理契约。
- AI 第一轮适配：Qwen 图片/视觉/TTS 模型分类、voice 自定义、endpoint/protocol 配置、错误分类。
- 应用内版本历史：当前 2.2.150，共 160 条历史记录，已按 8 月 24-26 日真实更新拆分。
- OUT-1 第一批：有效配置、准入 hash、服务端预检复核、任务 phase 和 Studio 快照已完成。
- AUDIO-1.1 第一批：AudioContract、audioMode、真实音频 probe 和 VoiceTimeline 边界已接入预检。
- AI-2.1 第一批：OpenAI-compatible adapter registry、图片/视频/配音任务已完成；未定义协议继续拒绝。

仍未完成并明确标记：

- 完整 2.2.126 Setup EXE：当前工作区便携运行时约 5 GB，Inno 在大体积收尾阶段被 Windows 终止，未生成可交付安装器。
- 真实 Setup EXE 全新安装启动和重启恢复：必须等完整 Setup EXE 生成后执行。
- 多台不同 Windows 电脑矩阵：当前只能完成本机静态/门控和现有运行实例验证，不能伪造其他电脑结果。
- AI 全协议适配：目前 OpenAI-compatible HTTP 已覆盖；千问原生 WebSocket TTS、各中转自定义图片/视频协议仍需按中转实际文档逐一适配。
- 出片台预检规则统一和 UI 重构：这是下一条主线，尚未大范围修改。

## 进度总览

| 阶段 | 模块 | 状态 | 说明 |
| --- | --- | --- | --- |
| P0 | Git、依赖、版本与项目安全边界 | 已完成 | 已有安全存档和提交纪律 |
| P1 | 联网策略与来源适配 | 已完成 | 仍有调用方迁移增强项 |
| P2 | 任务持久化、取消、恢复、进程管理 | 已完成 | 已通过专项和后端回归 |
| P3-1 | 固定媒体验收 fixture | 已完成 | 6 个固定媒体 fixture |
| P3-2 | 隔离 DB 与本地 HTTP mock | 已完成 | 默认不连接数据库 |
| P3-3 | 离线导入到 QC | 已完成 | 真实本地媒体链路 |
| P3-4 | JobService 恢复契约 | 门控完成 | 真实 MySQL 需显式环境变量 |
| P3-5 | 新安装目录门控 | 门控完成 | 缺 Setup EXE 时安全 skipped |
| AI-1 | 模型能力识别与手动采用 | 第一轮完成 | Qwen 图片/视觉/TTS 已修复 |
| AI-2 | 多协议 Provider Adapter | 第一批完成 | OpenAI-compatible registry 已完成；DashScope/Qwen WebSocket/Relay 待正式协议 |
| OUT-1 | 出片台预检逻辑统一 | 第一批完成 | 有效配置、准入 hash、服务端复核、Studio 状态已收口 |
| OUT-2 | 出片任务卡死与恢复 | 基础+专项完成 | heartbeat、phase、租约、检查点、暂停恢复和批量测试已覆盖 |
| AUDIO-1 | 音画同步与音频生产 | 第一批完成 | AudioContract/audioMode/VoiceTimeline 已接入；语义匹配仍需深化 |
| UI-1 | 出片台交互重构 | 待处理 | 业务状态稳定后进行 |
| RELEASE-1 | Setup EXE 与多机矩阵 | 阻塞 | 发行包体积/构建机限制 |

## 模块依赖图

```text
AI-1 能力识别
    ↓
AI-2 Provider 多协议适配
    ↓
AI-3 AI 建议结构化
    ↓
OUT-1 出片台预检统一
    ↓
OUT-2 批量任务状态机与恢复
    ↓
AUDIO-1 音画同步 / TTS / BGM / 字幕
    ↓
OUT-3 剪映式批量工作台
    ↓
UI-1 全局交互与视觉重构
    ↓
RELEASE-1 Setup EXE + 多 Windows 矩阵
```

横向基础设施：

```text
固定 Fixture + Mock HTTP + acceptance DB
                 ↓
        每个模块的自动验收与回归
```

## 重点模块一：AI 能力与 Provider 适配

### 已完成

- Qwen `qwen-image-*` 识别为图片生成。
- Qwen `qwen3-vl-*` / `qwen-vl-*` 单独识别为视觉理解。
- Qwen TTS 模型识别为配音候选。
- Provider 返回 `visionModels`、`imageGenerationModels`、`voiceProtocol`、`voiceEndpoint`。
- 配音音色不再锁死 OpenAI voice；Qwen 默认优先 `Cherry`。
- 400、401/403、404/405、429、5xx 错误分开提示。
- 千问图片模型已重新识别并采用：`qwen-image-2.0-pro`。

### 当前限制

- “识别到”与“已采用”仍然是两个动作，这是付费能力安全要求，不应自动替用户开放全部模型。
- OpenAI-compatible `/v1/audio/speech` 不能代表千问原生 TTS WebSocket。
- 图片生成、视频生成、视觉理解、图片编辑仍需要更细的协议能力描述。
- 各家中转的非标准返回体还需适配器化。

### AI-2 当前状态与后续

已完成第一批：

1. `MediaAdapter` / registry 抽象已建立，OpenAI-compatible 是当前唯一 executable adapter。
2. 图片生成、异步视频提交/轮询/流式下载、配音提交已隔离并有本地 mock 测试。
3. 协议错误、响应大小、MIME、staging、submit_unknown 和任务错误码已接入。

后续仍需正式协议资料后再做：

1. DashScope native HTTP。
2. Qwen 原生 WebSocket TTS。
3. Relay 固定 request/response profile。
4. 每种协议独立处理重试、幂等和收费未知状态。

验收标准：

- 用户能看懂“模型能做什么”和“接口怎么调用”。
- 图片生成不会误用视觉模型。
- TTS 不会误用 OpenAI voice 或错误 endpoint。
- 未知协议会明确提示配置要求，而不是显示 Key 无效。

## 重点模块二：出片台预检与产片逻辑

OUT-1 第一批已完成；下一阶段转向音画语义匹配、批量吞吐验收和发布门槛深化。

### 当前已发现的问题

- 计划时长在范围内，页面仍可能显示干跑未通过。
- “可用画面时长”“要求时长”“可选画面”“质量排除时长”口径混在一起。
- 角色缺料、时长缺料、质量拒绝、音频覆盖不足没有统一主状态。
- 项目默认参数、工作流参数、固定顺序参数、Studio 当前参数优先级不够直观。
- AI 生成建议、自动补料、固定顺序和 Studio 当前表单存在互相覆盖风险。
- 普通用户看到太多高级字段，无法快速判断下一步动作。

### OUT-1 已完成第一批

已将所有预检结果统一到现有结构，并新增有效配置和提交准入快照：

```json
{
  "status": "ready | blocked | needs_user_action | warning",
  "plannedSec": 85,
  "minSec": 50,
  "maxSec": 150,
  "usableVisualSec": 115,
  "excludedVisualSec": 46,
  "audio": {
    "mode": "material-audio",
    "hasVoice": false,
    "hasBgm": false,
    "hasOriginalAudio": false,
    "status": "blocked"
  },
  "blockers": [],
  "warnings": [],
  "actions": []
}
```

规则：

- warning 不阻断；blocker 才阻断。
- 每个 blocker 必须有一个明确动作。
- 干跑和正式渲染使用同一套验证。
- 提交任务前冻结参数和预检快照。
- 前端不自行猜状态，以后端状态为唯一事实。

### OUT-1 文件范围

第一轮只允许修改：

- `frontend/src/views/Studio.vue`
- `backend/src/main/java/com/douyin/mixcut/service/JobService.java`
- `backend/src/main/java/com/douyin/mixcut/service/MixPlanner.java`
- 对应 DTO、Controller、测试文件

不修改：

- `FixedOrderPresets.vue`
- 无关 AI 页面
- 数据库生产 schema
- 外部依赖目录

## 重点模块三：任务卡死与恢复

### 已完成基础

- MediaTask 持久化。
- MediaGenerationTask 持久化。
- PreparationTask 取消。
- AI 视频 remoteTaskId 恢复。
- active polling worker 去重。
- ProcessRegistry 和 TaskAwareProcRunner。
- 取消后临时输出清理。
- 晚到 worker 不覆盖 cancelled。

### 仍需专项

- JobService 渲染阶段的统一 heartbeat。
- OCR/ASR 不得阻塞渲染主 worker。
- 所有阶段统一 errorCode 和 phase。
- 前端统一任务抽屉和任务详情。
- watchdog、恢复和取消的竞态压力测试。

## 重点模块四：音画同步与音频生产

统一 Audio Contract：

```text
sampleRate
channels
codec
inputDuration
outputDuration
startSec
endSec
loudness
silenceRatio
sourceType
```

生产链必须是：

```text
TTS 生成
→ 下载/写文件
→ FFprobe
→ 真实时长校验
→ 静音/响度检查
→ 字幕时间轴
→ BGM/原声混音
→ 最终 FFprobe
→ QC
→ 成片记录
```

禁止：

- 用文字长度推算音频时长。
- 混音失败后登记成功素材。
- 原声、BGM、口播同时无规则叠加。
- 只验证视频不验证音频。

## 重点模块五：剪映式批量工作台

### 普通模式

只显示：

- 项目。
- 素材范围。
- 成片数量。
- 目标时长。
- 声音模式。
- 自动补料开关。
- 开始预检。
- 开始批量出片。

### 专业模式

显示：

- 固定顺序。
- 角色比例。
- 去重策略。
- 片段时长。
- 钩子策略。
- 字幕策略。
- QC 严格度。
- 回退策略。

### 调试模式

显示：

- taskKey。
- phase。
- heartbeat。
- timeout。
- retry。
- fallback。
- 参数快照。
- 错误码。

普通用户默认不显示调试模式。

## 视觉/UI 重构原则

UI 重构放在状态机和业务边界稳定之后。

重点不是增加装饰，而是让用户快速完成：

```text
选项目 → 选素材 → 预检 → 修 blocker → 提交 → 看进度 → 看结果
```

设计规则：

- 一个页面只保留一个主动作。
- 高级参数默认折叠。
- 每个阻断项直接提供处理动作。
- 页面上下文不重复显示同一字段。
- 任务状态用统一颜色和阶段名。
- 长任务显示阶段、进度、最近心跳和可执行操作。
- 动效只服务于状态变化，不用动效掩盖等待或失败。
- 不把页面拆成互相嵌套的卡片迷宫。
- 不在本阶段修改 FixedOrderPresets 独立模块。

## 每个模块的固定开发流程

```text
1. 只读审计
2. 列出涉及文件和禁止触碰文件
3. 明确状态/接口/数据契约
4. 先写失败测试
5. 最小实现
6. 定向测试
7. 全量回归
8. 浏览器验收
9. Git 独立提交
10. 再进入下一个模块
```

失败规则：

- 定向测试失败：停止当前模块。
- 全量回归失败：不进入下一个模块。
- 浏览器验收失败：不宣称 UI 完成。
- 真实安装产物缺失：标记阻塞，不伪造安装通过。
- 不修改与当前模块无关的文件。

## 当前可选择的下一模块

### 选项 A：出片台预检逻辑统一（推荐）

处理截图中的：

- 计划范围内仍提示失败。
- 可用时长口径冲突。
- 质量排除和角色缺料混淆。
- 音频覆盖与时长规则互相打架。
- blocker/warning/action 不清晰。

### 选项 B：AI 多协议适配器

处理：

- Qwen native TTS。
- 不同中转的图片返回格式。
- 视频同步/异步差异。
- 视觉理解与图片生成的输入输出协议。
- 适配器 Mock 测试。

### 选项 C：出片任务卡死专项

处理：

- JobService heartbeat。
- OCR/ASR 阻塞。
- 取消/超时/恢复竞态。
- 前端任务状态刷新。
- watchdog 压力测试。

### 选项 D：音画同步与音频生产

处理：

- TTS 实际时长。
- BGM/原声/口播互斥和混音。
- 字幕时间轴。
- 音频 codec、采样率、声道。
- 最终 QC。

## 当前最近提交

```text
3abd8dc fix(ai): qualify media executor at runtime
1cdacab fix(ai): make adapter registry bootable
35418f0 fix(render): close audio and snapshot review gaps
9e2e315 fix(render): recompute admission status on submit
341f0e6 test(render): cover batch recovery and task controls
e4abc51 feat(ai): register explicit provider media adapters
1f9bc6a feat(render): enforce preflight admission and audio contracts
```

## 发行与多机状态

- 当前应用本机运行：已验证。
- 当前版本：2.2.150。
- 完整 Setup EXE：阻塞，约 5 GB 运行时的 Inno 大包编译在当前机器收尾阶段被 Windows 中断。
- 旧安装包：最高只有旧版本，不能冒充当前包。
- 多 Windows 电脑矩阵：未完成，需要真实安装包和其他 Windows 机器。
