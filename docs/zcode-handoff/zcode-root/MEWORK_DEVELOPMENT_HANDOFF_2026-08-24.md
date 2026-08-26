# 猫作 · Mework 开发交接与后续路线

更新时间：2026-08-24
当前项目：`ai-douyin-mixcut`
当前分支：`main`
当前最新提交：`ec7eb29 fix(render): isolate cancellation contexts and outputs`
规划地图：[MEWORK_OPTIMIZATION_ROADMAP.md](D:/zcode/MEWORK_OPTIMIZATION_ROADMAP.md)

## 一、当前结论

猫作已经从“能导入素材并渲染”推进到“可解释预检、任务持久化、任务恢复、Job 执行 fencing、受控渲染取消”的阶段。

当前最重要的业务目标不是继续堆功能，而是建立一条可证明的生产闭环：

```text
素材进入
→ 质量准入
→ 人工角色/文件夹范围
→ AI 语义理解与素材匹配
→ 音频真实时长与语义时间轴
→ 画面/音频同步规划
→ 预检
→ 受控渲染
→ FFprobe/QC
→ 成片版本与失败证据
→ 批量任务恢复/重试
```

核心原则：

- AI 负责理解、分类、建议和匹配；不直接绕过权限或未经验证地执行外部能力。
- 规划器只使用已经通过准入的素材，不把“素材总时长”当作“可交付容量”。
- 音频时长必须来自真实媒体探测，不用文字长度猜测。
- 预检和正式渲染必须共享同一套硬规则。
- 任务状态必须可恢复、可解释、可取消，旧 worker 不能覆盖新 worker。
- 任何取消、超时、租约失效后的迟到结果都不能进入成片检查点。
- 不修改独立的 `FixedOrderPresets` 模块。
- 不读取、复制或提交 `.env`、API key、密码或机器凭据。

## 二、版本与开发历史

### 当前版本

- 应用版本：`2.2.126`
- 版本常量：[AppProps.java](C:/Users/Windows/WorkBuddy/2026-08-09-14-55-27/ai-douyin-mixcut/backend/src/main/java/com/douyin/mixcut/config/AppProps.java:20-23)
- 应用内历史：约 136 条记录，已重建 8 月 22-23 日时间线。
- 当前完整 Setup EXE：未交付。约 5 GB 便携运行时在 Inno Setup 收尾阶段被 Windows 中断，不能宣称安装包验收通过。

### 最近关键提交

| 提交 | 作用 |
| --- | --- |
| `ec7eb29` | OUT-2C 第一轮：隔离渲染取消 context、受控输出和 RenderService 传播 |
| `80953dc` | OUT-2B：Job execution epoch、lease、watchdog fencing、旧 worker 隔离 |
| `1415bb9` | OUT-2：Studio heartbeat、stale、ETA、准备后台状态展示 |
| `3039749` | OUT-1：统一 dry-run/preflight blocker、warning、音频和去重原因 |
| `0fc739b` | Qwen 图片/视觉/TTS 能力识别、voice、endpoint/protocol、错误诊断 |
| `37d3886` | 重建 8 月 22-23 日版本时间线 |
| `42a66b7` | 记录应用内版本历史 |
| `c2670bb` | 全新安装和重启恢复门控 |
| `4cdb754` | 隔离 MySQL Job 恢复契约 |
| `7661b60` | 离线导入、质量准入、FFmpeg、QC 验收 |
| `aeca5e4` | 隔离数据库和本地 HTTP mock |
| `36a2932` | 固定媒体 fixture、SHA256、FFprobe 清单 |
| `c56b090` | 统一任务中心 |
| `227462c` | MediaTask 持久化 |
| `975910a` | AI 生成任务持久化 |
| `8133d36` | AI 生成任务恢复，避免重复计费 |
| `dc1edb5` | 准备任务取消与远程视频恢复 |
| `4f11d9c` | 受控媒体任务取消 |
| `59c1d8e` / `576fafd` | 媒体取消上下文传播、按 task context 终止进程 |
| `94d997a` | 取消/恢复并发加固 |
| `abc0ca5` | Studio 固定顺序说明和草稿创建优化 |
| `b413ed9` / `f77e30f` / `d9bda4f` | 媒体能力契约、诊断路由、能力配置说明 |
| `6174d26` / `7a23429` / `2ed43de` | 离线运行时、隔离 smoke、Windows 便携兼容 |

### 8 月 22-23 日已完成的基础设施

- Wikimedia Commons、Internet Archive 等受控公开来源适配。
- URL 安全、出站网络策略、重试、429/5xx/超时诊断。
- Portable JDK/MySQL/Python/venv 运行时检查。
- 固定 fixture、离线媒体管线、QC、隔离 MySQL、Fresh Install contract。
- Job、PreparationTask、MediaTask、MediaGenerationTask 的持久化和取消/恢复基础。
- 版本历史和应用内 release notes。

## 三、OUT-1 到 OUT-2C 已完成内容

### OUT-1：出片台预检统一

关键文件：

- `backend/src/main/java/com/douyin/mixcut/dto/PreflightIssue.java`
- `backend/src/main/java/com/douyin/mixcut/dto/PreflightResult.java`
- `backend/src/main/java/com/douyin/mixcut/service/PreflightService.java`
- `backend/src/main/java/com/douyin/mixcut/web/WorkflowController.java`
- `backend/src/main/java/com/douyin/mixcut/service/MixPlanner.java`
- `frontend/src/views/Studio.vue`

效果：

- dry-run 返回 `plan + preflight`。
- `ready / warning / blocked / needs_user_action` 分离。
- 计划实际时长在硬范围内但低于推荐目标，只是 warning。
- 去重、同源重叠、音频缺失、口播覆盖不足、素材容量不足分别报错。
- 前后端视频最低可排片时长统一为 `1.0s`。
- warning 不禁用开始按钮，blocker 才阻断。

### OUT-2：任务状态可观测性

关键文件：

- `frontend/src/views/Studio.vue`

效果：

- heartbeat 年龄独立时钟刷新。
- 区分排队、准备、渲染、延迟、stale、暂停、人工决策。
- 缺少 ETA 不再显示“预计还需 0 秒”。
- 缺少 elapsed 不再显示“已运行 0 秒”。
- 准备任务后台运行有独立标题和标签。
- paused/awaiting_decision 保留任务详情快照。
- 离开页面会停止准备轮询和观察时钟。

### OUT-2B：Job fencing

关键文件：

- `backend/src/main/java/com/douyin/mixcut/domain/Job.java`
- `backend/src/main/java/com/douyin/mixcut/repository/Repositories.java`
- `backend/src/main/java/com/douyin/mixcut/service/JobService.java`
- `backend/src/main/java/com/douyin/mixcut/service/BootstrapService.java`
- `backend/src/main/resources/db/job-execution-lease-migration.sql`
- `backend/src/main/resources/db/schema.sql`
- `backend/src/test/resources/acceptance/acceptance-schema.sql`

效果：

- Job 增加 `version`、`execution_epoch`、`lease_token`、`lease_expires_at`。
- pending claim 使用数据库条件更新。
- heartbeat 使用 `status + epoch + token` 条件更新。
- watchdog 不再把 `dispatched` 当作永久健康证明。
- cancel/pause 使用原子状态转换并使旧 lease 失效。
- 旧 worker 不能覆盖新状态。
- lease token 标记为 JSON 忽略，不返回浏览器。
- JobOutput 原有 `(job_id, idx)` 唯一检查点继续保留。

### OUT-2C：受控渲染取消第一轮

关键文件：

- `backend/src/main/java/com/douyin/mixcut/external/ProcessRegistry.java`
- `backend/src/main/java/com/douyin/mixcut/external/TaskAwareProcRunner.java`
- `backend/src/main/java/com/douyin/mixcut/external/FfmpegTool.java`
- `backend/src/main/java/com/douyin/mixcut/service/RenderService.java`
- `backend/src/main/java/com/douyin/mixcut/service/JobService.java`

已完成能力：

- Job 使用后端生成的 `job:<id>` CancellationContext。
- cancel/watchdog lease loss 会调用 `ProcessRegistry.cancel`。
- RenderService 增加带 context 的重载，旧重载保持兼容。
- 切片、probe、concat、音频切片拼接、混音、原声混音、字幕烧录、缩略图和 QC 阶段已开始贯通 context。
- RenderService 对取消返回统一“渲染已取消”错误。
- 最终文件、缩略图和 QC candidate 开始纳入受控输出登记/清理。

## 四、当前未提交状态与注意事项

当前工作树不是干净状态，主要有：

- 前端构建产生的 `backend/src/main/resources/static` hash 资源变更/新增/删除；这些是生成物，不应直接当作业务源码提交。
- `docs/development-roadmap.html` 是未跟踪的规划视觉文件，需要确认是否为用户保留的工作成果。
- OUT-2C 源码目前已经有最新取消 context 改动，应在下一轮先 `git status`、`git diff`、`git diff --check`，再决定是否继续或单独提交。
- 不要用 `git reset --hard`、`git clean -fd` 或覆盖式恢复清理用户未确认的文件。
- `.env` 和 `.env.backup-*` 只能保留在本机，绝不能写入交接文档或 Git。

## 五、从第一性原理重新定义产品

### 1. 生产质量的最小事实

一条可交付带货视频必须同时满足：

```text
画面有意义
+ 声音有意义
+ 声音与画面在同一时间轴上表达同一件事
+ 时长真实
+ 输出可播放
+ QC 通过
+ 任务可恢复
```

因此“素材够不够”不是按文件数量判断，而是按以下容量判断：

```text
可读素材
× 角色适配
× 语义适配
× 可切片区间
× 去重约束
× 音频时间覆盖
× 交付时长
```

任一乘数为 0，最终交付能力就是 0。

### 2. 音画同步不能靠随机混剪

目标链路应改成：

```text
音轨/脚本
→ ASR 或脚本语义分段
→ 每段得到主题、动作、对象、时间窗
→ 素材做视觉标签/动作/对象/场景 embedding
→ 时间窗内选择匹配画面
→ 不足时使用明确 fallback
→ 重新检查音画一致性
```

例如：

| 音频语义 | 画面要求 |
| --- | --- |
| “吃饭” | 用餐、食物、餐桌、进食动作 |
| “产品展示” | 产品本体、包装、使用细节 |
| “涂抹/使用” | 手部动作、产品接触目标区域 |
| “前后对比” | 同主体、同角度或显式对比镜头 |
| “价格/优惠” | 产品、价格卡、购买动作或收口画面 |

如果没有匹配素材，不应随机塞入明星或无关 B-roll；应该返回：

```text
matched / fallback / missing / blocked
```

并在预检中说明缺口。

### 3. AI 的正确位置

AI 不应该直接控制 FFmpeg 命令。正确职责分层：

```text
AI Provider
  → 结构化语义结果
  → 本地安全适配器
  → 领域规划器
  → 预检契约
  → 渲染执行器
  → QC
```

AI 返回必须经过：

- JSON schema 校验；
- 模型能力和协议验证；
- 素材 ID/角色/授权范围校验；
- 时长和音频覆盖校验；
- 不通过则 fallback 或 blocker。

### 4. 一天 30 条的实际工程约束

30 条/天不是单纯提高线程数。需要：

- 预检失败前置，避免渲染后才发现错误。
- 任务队列和检查点，失败只重做失败条目。
- 音频、视觉和 QC 的缓存，避免每条重复分析。
- 同一素材的语义标签、OCR、ASR、embedding 持久化。
- 模板化结构与批次差异化 seed。
- 2-4 个受控渲染 worker，按 CPU/GPU/磁盘吞吐压测后调优。
- 明确 fallback，不让缺素材导致整批无限等待。

## 六、已知 Bug 与文件定位

### P0/P1：OUT-2C 尚未完全闭环

- `JobService.java`
  - 仍需完善 context 替换后的 worker identity 清理和旧 worker finally 隔离。
  - cancel/watchdog context 取消与输出清理的竞态需要专项测试。
- `RenderService.java`
  - `requiredDuration`、delivery QC、audio/video quality 已在继续接入 context；需要确保所有质量探针都不绕过 context。
  - QC candidate 移动后应重新登记新路径，避免取消清理漏掉 candidate。
- `ProcessRegistry.java`
  - `replace(taskKey)` 已需要用于取消后重新执行；必须验证不影响其他 task key。
  - `cancel`、`cleanupOutputs`、`forget` 的顺序必须保持：取消时先清理输出，再 forget。
- `FfmpegTool.java`
  - 旧构造函数没有 TaskAwareProcRunner 时，context 只能在调用边界生效，不能保证正在运行的进程立即终止；Spring 正式容器路径必须使用 TaskAware runner。
  - `colorClip`、`detectSceneCuts`、`analysisFrame` 等非主渲染路径仍需判断是否纳入 context。

### P1：Job 后端状态

- `JobService.java`
  - 跨进程 fencing 已有 epoch/token 基础，但完整数据库条件状态转换仍需压力测试。
  - `lease_expires_at`、heartbeat、watchdog 的边界需要 MySQL acceptance 真跑一次。
  - 外部 FFmpeg 仍可能在未注册/旧构造路径下继续运行，OUT-2C 只承诺受控链路。

### P1：音画同步

- `RenderService.java`
  - 当前已有真实时长、字幕和混音校验，但还没有“音频语义 → 视觉语义 → 时间窗”完整契约。
- `FfmpegTool.java`
  - 音频质量、视频质量和部分探针需要统一 context 与诊断错误码。
- `MixPlanner.java`
  - 现有结构和角色规划仍主要是角色/时长驱动，不是语义时间轴驱动。

### P2：AI 协议

- `AiClient.java`
- `MediaProviderCatalog.java`
- `MediaGenerationService.java`
- `AiSettings.vue`
- `AiCreate.vue`

Qwen 图片/视觉/TTS 第一轮已修复；原生 WebSocket TTS、非标准图片/视频中转协议仍需逐家适配，不能把 OpenAI-compatible HTTP 当作全部能力。

### P2：发行

- `installer/Mework.iss`
- `installer/generate_release_manifest.ps1`
- `verify_fresh_install.ps1`

完整 Setup EXE 尚未生成，真实全新安装、重启恢复和多台 Windows 电脑矩阵不能宣称完成。

## 七、下一阶段开发方案

### OUT-2C 收尾

1. 完成 RenderService 所有质量探针 context 传播。
2. 为取消/lease loss 增加主文件、thumbnail、QC candidate 清理测试。
3. 验证 context 替换后旧 worker finally 不会清理新 worker context。
4. 验证取消后重新 resume 能创建新 context，不会复用 cancelled context。
5. 运行 ProcessRegistry、TaskAwareProcRunner、FfmpegTool、RenderService、JobService 专项测试。
6. 完整 Maven 回归和前端 build。
7. 隔离端口只读验收 API 不泄露 token，清理生成物后再提交。

### OUT-3：MediaTask timeout/stale 与统一进程任务契约

OUT-2C 稳定后再处理 MediaTask：

- `MediaTask.timeoutSec` 真正驱动命令 timeout。
- `MediaTask.staleAfterSec` 真正驱动恢复 cutoff。
- MediaTask 增加稳定 phase/errorCode/recovery reason。
- MediaToolsService watchdog 与 ProcessRegistry context 统一。
- retryCount 按实际恢复递增，而不是只持久化字段。
- MediaTask API 返回 heartbeat、phase、timeout、stale、recovery 信息。
- 补充 image/audio-separate/video-split/timeline/auto-trim 的配置生效测试。

### AUDIO-1：音画同步契约

- 建立 `AudioContract`：采样率、声道、codec、输入/输出时长、起止时间、响度、静音比例、sourceType。
- TTS 生成后立即 FFprobe。
- ASR 字幕时间轴来自真实音频。
- BGM/原声/口播按 audioMode 互斥或明确混音。
- 每个语义片段生成视觉匹配候选和 fallback 原因。
- 最终音频、视频和字幕再次 QC。

### AI-2：Provider Adapter

- `OpenAiCompatibleAdapter`。
- `DashScopeNativeAdapter`。
- `QwenTtsAdapter`。
- `RelayAdapter`。
- 每个 adapter 独立处理 endpoint、payload、response、错误、重试和幂等。
- 能力状态分成 `discovered / verified / adopted / executable`。

### OUT-3 剪映式批量工作台

普通模式只显示：

```text
项目 → 素材范围 → 数量 → 目标时长 → 声音模式 → 预检 → 批量出片
```

专业模式再显示固定顺序、角色比例、去重、钩子、字幕、QC 和 fallback。

调试模式显示 taskKey、phase、heartbeat、timeout、retry、fallback、参数快照和错误码。

## 八、下一次新对话启动提示词

把下面内容直接作为下一轮对话开头：

> 继续猫作 Mework 开发。先读取 `D:\zcode\MEWORK_DEVELOPMENT_HANDOFF_2026-08-24.md` 和 `D:\zcode\MEWORK_OPTIMIZATION_ROADMAP.md`。当前最新提交是 `ec7eb29 fix(render): isolate cancellation contexts and outputs`，当前主线为 OUT-2C。先检查 `git status`，不要清理或覆盖未提交文件。按一次一个模块推进：先收尾 OUT-2C 的 CancellationContext、ProcessRegistry 输出清理和 RenderService QC 探针 context 传播；先写失败测试，再实现，跑专项测试、完整 Maven、前端 build、隔离浏览器验收，最后独立提交。禁止修改 `FixedOrderPresets`、AI Provider、生产数据和凭据文件。完成后汇报：优化内容、遗漏、下一方向。

## 九、当前验收边界

已经可以明确宣称：

- 本机后端测试与构建链可重复运行。
- 固定媒体离线验收基础已存在。
- Job 持久 fencing 基础已提交。
- Studio 预检和任务状态展示已提交。
- Qwen 第一轮能力识别已提交。

不能宣称：

- 30 条/天已在真实业务素材上压测通过。
- 音画语义同步已经完整实现。
- 千问原生 WebSocket TTS 已完成。
- 完整 Setup EXE 已生成并通过新机安装。
- 多台 Windows 电脑矩阵已完成。
- 所有 FFmpeg/ProcessRegistry 取消路径已覆盖。
