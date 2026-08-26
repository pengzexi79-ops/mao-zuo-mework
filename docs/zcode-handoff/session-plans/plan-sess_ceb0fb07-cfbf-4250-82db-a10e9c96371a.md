# OUT-2C 受控渲染进程取消与进程级心跳

## 目标与边界

把已经存在但未接通的进程控制能力接入批量 Job 渲染链：

```text
Job cancel / lease loss
        ↓
ProcessRegistry.CancellationContext
        ↓
RenderService
        ↓
FfmpegTool context overloads
        ↓
TaskAwareProcRunner / ProcRunner
        ↓
终止当前 FFmpeg 进程树 + 清理受控输出
```

本轮只处理受控渲染进程，不修改：

- `FixedOrderPresets`；
- AI Provider/AI 生成；
- Studio UI；
- Job lease 状态模型本身；
- PreparationTask 和 MediaTask 生命周期；
- 数据库 schema；
- 外部授权来源和业务数据。

## 已确认根因

1. `JobService` 目前只有数据库 lease 和 `cancelled` 集合，没有 Job 对应的 `CancellationContext`。
2. `RenderService` 只接收 deadline，没有接收 context。
3. RenderService 内部所有关键 FFmpeg 调用都使用无 context 重载：切片、探测、拼接、混音、字幕、缩略图。
4. `TaskAwareProcRunner` 的取消链本身可用：取消 context 后中断调用线程，`ProcRunner` 会终止进程树；但 Job 渲染链没有调用它。
5. `ProcessRegistry.register(Process)` 当前没有接入 ProcRunner，暂不在本轮重写 ProcessBuilder/进程注册机制，先使用已有 TaskAware watcher + 受控输出注册，降低跨平台风险。
6. `RenderResult` 迟到清理目前只删主 `filePath`，thumbnail/QC candidate 可能残留。

## 1. Job 创建和持有进程 context

修改：

- `backend/src/main/java/com/douyin/mixcut/service/JobService.java`
- 必要时新增测试辅助类，不改数据库字段。

实现：

- 注入 `ProcessRegistry`。
- 增加进程内 `Map<Long, CancellationContext>`，key 使用后端生成的 `job:<id>`，不复用数据库 lease token。
- Job worker claim 成功后创建/复用 context。
- `safeRun` finally 移除 context，并调用 `ProcessRegistry.forget(context)`，但不删除已完成输出。
- `cancel(jobId)`：数据库原子取消成功后调用 `ProcessRegistry.cancel(context)`。
- watchdog 成功让 Job lease 失效后调用同一 Job context 的 cancel。
- lease 丢失、用户取消、进程取消统一让 render 返回可识别的 cancellation 结果，不把它当普通业务失败。
- 取消操作不影响其它 Job 或 MediaTask 的 context。

## 2. RenderService 接收并传播 context

修改：

- `backend/src/main/java/com/douyin/mixcut/service/RenderService.java`
- `backend/src/main/java/com/douyin/mixcut/service/JobService.java`

兼容策略：

```java
render(plan, params, outName, onStep)
render(plan, params, outName, onStep, deadline)
render(plan, params, outName, onStep, deadline, context)
```

旧重载统一转发到 `CancellationContext.none()`，不影响现有单测和其它调用方。

RenderService 每个阶段前后调用 `context.throwIfCancelled()`，并将 context 传给所有已有 overload：

- `imageToClip` / `cutNormalize`；
- `probe`；
- `concat`；
- `concatAudioSlices`；
- `muxAudio`；
- `muxOriginalAudio`；
- `burnText`；
- `thumbnail`；
- 质量检查相关 FFmpeg 调用；
- 其它 Job 渲染路径中的 FfmpegTool 调用。

不新增第二套 runner；复用既有 `TaskAwareProcRunner`。

## 3. 受控输出注册与清理

修改：

- `RenderService.java`
- `JobService.java`
- 必要时 `ProcessRegistry.java`（仅增加安全的多路径清理辅助，不放宽 root 限制）

规则：

- RenderService 创建最终输出、thumbnail、QC candidate 后，使用 context 注册到明确的允许 root：
  - 最终成片：`props.output()`；
  - thumbnail：对应输出/thumbnail root；
  - QC candidate：`props.cache()` 下明确候选目录。
- 继续保留现有 `finally cleanup(work)`，不删除 source media 和整个 output root。
- `discardRenderResult()` 扩展为清理：
  - `filePath`；
  - `thumbnail`；
  - 受控 QC candidate；
  - 只清理已知且位于允许根目录中的文件。
- 正常成功完成后 `forgetOutput`/`forget` 只移除 bookkeeping，不删除最终交付文件。
- 取消、lease loss、TaskAware 返回 `-4` 时清理受控中间和最终文件。
- 不修改 OutputVersion 数据库记录；如果现有版本记录指向已删除文件，先通过现有 Job 收尾逻辑验证是否会写入，再决定是否增加最小清理，避免扩大 schema/版本模块范围。

## 4. 进程级 heartbeat 语义

本轮采用保守可验证语义：

- Job 的 `updateStep` 继续提供业务阶段 heartbeat；
- `TaskAwareProcRunner`/`ProcRunner` 的取消链保证单次 FFmpeg 阻塞可被中断；
- 不在本轮解析 FFmpeg `-progress pipe` 或新增实时进度协议；
- 不声称“进程存活”就是编码有进展；只保证 watchdog lease loss 能触发受控取消，避免无期限占用资源。

如在现有 runner 中能以 callback 方式安全增加“进程仍在运行”观察，则只增加内部诊断，不改变 Job API。

## 5. 测试先行矩阵

新增/扩展：

- `backend/src/test/java/com/douyin/mixcut/external/ProcessRegistryTest.java`
- `backend/src/test/java/com/douyin/mixcut/external/TaskAwareProcRunnerTest.java`
- `backend/src/test/java/com/douyin/mixcut/external/ProcRunnerTest.java`（若已有则扩展）
- `backend/src/test/java/com/douyin/mixcut/external/FfmpegToolTest.java`
- `backend/src/test/java/com/douyin/mixcut/service/RenderServiceTest.java`
- `backend/src/test/java/com/douyin/mixcut/service/JobServiceReliabilityTest.java`

至少覆盖：

1. ProcessRegistry 只取消目标 Job context，不影响其它 task。
2. 已注册输出目录递归清理，root/source/root 外路径保持不变。
3. TaskAware `run` 取消返回 `-4`，进程退出；`runSeparate` stderr 取消也返回 `-4`。
4. ProcRunner timeout 返回 `-2`，诊断包含 timeout、process tree terminated 和命令摘要。
5. FfmpegTool 已取消 context 在执行前/执行后拒绝命令，不产生输出。
6. RenderService 中途失败或异常仍清理 work 临时目录，不删除 source/既有最终文件。
7. RenderService deadline 过期返回明确时限错误，不生成最终成片。
8. Job cancel 调用 ProcessRegistry.cancel，当前 Job 的外部渲染 context 被取消，其它 Job 不受影响。
9. Job lease 丢失后，迟到 RenderResult 不写成功 checkpoint、不覆盖状态，并清理主成片和 thumbnail。
10. 正常成功 Job 不因 context bookkeeping 清理而删除交付文件。

不运行真实生产库；若 acceptance 环境未显式配置，只报告 skipped。

## 6. 验证与提交

执行顺序：

1. 先补失败的 context/RenderService/Job cancel 测试。
2. 接入 Job context 和 RenderService context overload。
3. 接通所有 Job 渲染 FFmpeg 调用。
4. 补输出注册与取消清理。
5. 运行进程/渲染/Job 专项测试。
6. 运行完整 Maven 回归。
7. 运行前端构建，确认 Job API 兼容。
8. 启动隔离端口，浏览器只读验收 Studio 和任务 API；不提交任务。
9. 清理静态构建产物。
10. 代码审查后独立提交：

```text
fix(tasks): cancel controlled render processes by job context
```

## 本轮完成后的遗漏与下一方向

仍不包括：

- FFmpeg `-progress` 解析和真实编码百分比；
- MediaTask timeout/stale 执行化；
- PreparationTask context/lease；
- 多机分布式进程协调；
- 全局任务中心 UI 重构。

下一板块固定为：`OUT-3 MediaTask timeout/stale 与统一进程任务契约`。