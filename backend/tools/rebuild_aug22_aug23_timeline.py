import json
from copy import deepcopy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NOTES = ROOT / 'src/main/resources/release-notes.json'

COMMITS = [
    ('2026-08-22', 'feat(studio): clarify fixed-order presets and speed up draft creation', 'Studio 预设与出片草稿体验收口', '优化固定顺序预设展示、新建草稿和步骤复制，保持独立 FixedOrderPresets 模块边界。', '预设信息更清晰；新增草稿操作更快；复制步骤会清空文件夹绑定避免冲突。', ['npm run build 通过', '浏览器验证 Studio 新建草稿与复制步骤', '独立固定顺序页面未修改'], ['frontend/src/views/Studio.vue']),
    ('2026-08-22', 'feat(capabilities): define media runtime contract', '媒体运行时能力契约', '定义离线能力、联网要求、状态、回退、配置变量和验证步骤的统一契约。', '修复能力状态把外部依赖误报为离线可用的问题。', ['后端能力清单测试通过'], ['backend/src/main/resources/capabilities.json', 'backend/src/main/java/com/douyin/mixcut/service/BootstrapService.java']),
    ('2026-08-22', 'feat(media): route diagnostics through controlled capabilities', '受控媒体诊断路由', '将 FFmpeg、Python、ASR、OCR、OpenCV 诊断调用收敛到固定能力路由。', '拒绝目录、符号链接和越权素材路径，禁止浏览器提交任意命令。', ['媒体路由、素材诊断和质量闸门测试通过'], ['backend/src/main/java/com/douyin/mixcut/external/MediaCapabilityRouter.java', 'backend/src/main/java/com/douyin/mixcut/service/MaterialDiagnosisService.java']),
    ('2026-08-22', 'feat(capabilities): explain media runtime configuration', '能力中心结构化配置指导', '能力中心展示工具状态、安装方式、配置变量、验证步骤、离线边界和官方入口。', '补齐未就绪、需要网络、可回退和需要重启等状态说明。', ['后端能力测试通过', '前端构建通过'], ['frontend/src/views/Capabilities.vue', 'backend/src/main/resources/capabilities.json']),
    ('2026-08-22', 'feat(packaging): enforce offline runtime verification', '发行包离线校验门禁', '启动与构建流程增加便携工具、版本、manifest 和 SHA256 校验。', '默认 verify 不联网、不静默 pip 安装，只有显式 repair 才允许修复依赖。', ['发行脚本静态校验通过'], ['build_installer.bat', 'prepare_portable.ps1', 'verify_offline_bundle.ps1']),
    ('2026-08-22', 'test(packaging): add isolated offline media smoke', '真实离线媒体 Smoke', '使用随包 FFmpeg、FFprobe、venv Python 和 OpenCV 诊断执行隔离媒体链路。', '补齐生成 testsrc、探测、诊断和临时目录清理验收。', ['offline-smoke 通过'], ['verify_offline_bundle.ps1']),
    ('2026-08-22', 'fix(windows): harden portable install compatibility', 'Windows 安装兼容加固', '统一 PORT/APP_PORT、MySQL ping、旧路径回退、中文路径和不同盘符适配。', '避免安装器并发启动 setup/start，修复端口与 DB_URL 不一致误启动。', ['Windows 静态兼容检查通过'], ['start.bat', 'start_mysql.bat', 'verify_windows_install.ps1']),
    ('2026-08-22', 'feat(network): add bounded outbound HTTP policy', '公共联网策略底座', '新增受控 HTTP 超时、总时限、响应大小、重试和脱敏日志策略。', '限制重定向和非幂等 POST 重放，统一 429/5xx 退避。', ['SafeHttpClient 定向测试通过'], ['backend/src/main/java/com/douyin/mixcut/external/SafeHttpClient.java', 'backend/src/main/java/com/douyin/mixcut/external/OutboundNetworkPolicy.java']),
    ('2026-08-22', 'feat(network): show explicit connectivity status', '联网能力显式检测', '能力中心增加 Wikimedia、Archive、Pexels、Pixabay、Freesound、Edge-TTS 的按需联网检测。', '未配置 Key 或未点击检测时不发公网请求，结果不泄露凭据。', ['ConnectivityService 测试通过'], ['backend/src/main/java/com/douyin/mixcut/service/ConnectivityService.java', 'frontend/src/views/Capabilities.vue']),
    ('2026-08-22', 'feat(crawl): isolate Wikimedia source adapter', 'Wikimedia 来源适配器', '将 Wikimedia 搜索、许可过滤、映射和抓取结果隔离为来源适配器。', '拒绝 NC/ND/SA 和占位素材，保留合规来源说明。', ['Wikimedia adapter 测试通过'], ['backend/src/main/java/com/douyin/mixcut/external/WikimediaSourceAdapter.java']),
    ('2026-08-22', 'feat(crawl): add Internet Archive source adapter', 'Internet Archive 来源适配器', '新增 Archive advancedsearch、metadata 和媒体映射适配。', '补齐多 identifier metadata 请求和许可过滤边界。', ['Archive adapter 测试通过'], ['backend/src/main/java/com/douyin/mixcut/external/InternetArchiveSourceAdapter.java']),
    ('2026-08-22', 'feat(media): persist media tool task lifecycle', '媒体工具任务持久化', '媒体任务新增 pending/running/done/failed 生命周期、参数快照、结果快照和 stale recovery。', '避免 HTTP 线程执行长任务，修复重启后任务状态丢失。', ['MediaToolsService 定向测试和后端回归通过'], ['backend/src/main/java/com/douyin/mixcut/domain/MediaTask.java', 'backend/src/main/java/com/douyin/mixcut/service/MediaToolsService.java']),
    ('2026-08-22', 'feat(ai): persist media generation task state', 'AI 媒体生成任务持久化', '图片、视频和语音生成任务记录 providerId、inputSnapshot、远端任务 ID、阶段和重试状态。', '不保存完整 provider 对象和 API Key，未知提交状态进入人工复核。', ['MediaGenerationService 测试通过'], ['backend/src/main/java/com/douyin/mixcut/domain/MediaGenerationTask.java']),
    ('2026-08-22', 'fix(ai): recover generation tasks without duplicate billing', 'AI 远端视频恢复防重复计费', '远端视频只在获得 remoteTaskId 后进入 remote_submitted/polling 恢复链。', '恢复只 GET 轮询，不重复 POST；provider 缺失、快照损坏和远端失败进入终态。', ['AI 生成任务恢复测试通过'], ['backend/src/main/java/com/douyin/mixcut/service/MediaGenerationService.java']),
    ('2026-08-22', 'feat(tasks): add unified task center', '统一任务中心', '聚合媒体、AI、抓取、准备和渲染任务，提供只读任务抽屉和刷新。', '避免把聚合 ID 误发到具体来源接口，统一 canCancel 映射。', ['任务查询测试和前端构建通过'], ['backend/src/main/java/com/douyin/mixcut/service/TaskQueryService.java', 'frontend/src/App.vue']),
    ('2026-08-22', 'fix(tasks): cancel preparation and recover remote video', '准备任务取消与视频恢复', '准备任务支持取消，AI 视频恢复保持 remote_submitted 直到真实 polling worker 启动。', '共享抓取任务不连带取消，远端任务不重复提交。', ['任务取消与恢复测试通过'], ['backend/src/main/java/com/douyin/mixcut/service/RenderPreparationService.java']),
    ('2026-08-22', 'feat(media): add controlled task cancellation', '媒体任务取消入口', '媒体工具 API、统一任务中心和 MediaTools 页面增加受控取消操作。', '取消状态不会被晚到 worker 的 done/failed 更新覆盖。', ['媒体取消专项测试通过'], ['backend/src/main/java/com/douyin/mixcut/service/MediaToolsService.java']),
    ('2026-08-22', 'fix(tasks): harden cancellation and recovery concurrency', '取消与恢复并发加固', '加入取消状态重新读库、active polling 去重和旧实体实例保护。', '修复准备任务旧实体覆盖 cancelled、AI polling worker 重复启动。', ['P2-D 并发专项测试通过'], ['backend/src/main/java/com/douyin/mixcut/service/RenderPreparationService.java', 'backend/src/main/java/com/douyin/mixcut/service/MediaGenerationService.java']),
    ('2026-08-22', 'fix(tasks): propagate media cancellation context', '媒体取消上下文传播', '新增 ProcessRegistry、CancellationContext 和输出登记清理，贯穿 FFmpeg、Demucs、切片和后处理。', '取消后不登记素材、不删除源文件，临时输出限制在应用管理根目录。', ['ProcessRegistry、媒体专项和后端全量测试通过'], ['backend/src/main/java/com/douyin/mixcut/external/ProcessRegistry.java', 'backend/src/main/java/com/douyin/mixcut/service/MaterialService.java']),
    ('2026-08-22', 'fix(tasks): terminate media processes by task context', '媒体进程按任务终止', '新增 TaskAwareProcRunner，将 task context 接入生产媒体进程执行层。', '取消任务可终止对应进程树，保留旧 ProcRunner API 兼容。', ['进程级取消测试、后端全量回归和前端构建通过'], ['backend/src/main/java/com/douyin/mixcut/external/TaskAwareProcRunner.java']),
    ('2026-08-22', 'test(acceptance): add deterministic media fixture manifest', '固定媒体 Fixture 清单', '生成运动视频、音视频、黑场、纯色、音频和图片 fixture，并记录 SHA256/FFprobe 元数据。', '验收输入固定、路径不越界、文件不被替换。', ['fixture-verify 和 FixtureManifestTest 通过'], ['backend/src/test/resources/acceptance/fixture-manifest.json', 'backend/tools/verify_acceptance_fixtures.ps1']),
    ('2026-08-23', 'test(acceptance): add isolated database and local http mock', '隔离数据库与本地联网 Mock', '新增 ai_mix_video_acceptance schema、数据库门控和本地 HTTP mock，覆盖 429/5xx/超时/重试/幂等。', '默认测试不连接数据库，生产 UrlGuard 继续拒绝回环公网请求。', ['P3-2 acceptance 测试和后端回归通过'], ['backend/src/test/java/com/douyin/mixcut/acceptance/LocalMockHttpServer.java', 'backend/tools/verify_acceptance_database.ps1']),
    ('2026-08-23', 'test(acceptance): cover offline media pipeline through qc', '离线导入到 QC 全链路验收', '真实执行素材登记、质量准入、结构化分析、FFmpeg 渲染、Delivery QC 和候选隔离。', '黑场/纯色被拒绝，失败候选不产生 public URL，所有写入限定 TEMP。', ['P3-3 acceptance 测试、fixture 校验、后端回归通过'], ['backend/src/test/java/com/douyin/mixcut/acceptance/OfflineRenderQcAcceptanceTest.java']),
    ('2026-08-23', 'test(acceptance): verify job recovery on isolated mysql', '隔离 MySQL JobService 恢复契约', '补齐 job_output、output_version、output_repair 和 stale checkpoint 验收。', 'QC fail/空路径不计成功检查点，重复 checkpoint 受唯一索引保护；无显式 acceptance 环境时安全跳过。', ['P3-4 门控测试通过；未连接业务库'], ['backend/src/test/java/com/douyin/mixcut/acceptance/JobServiceRecoveryDatabaseAcceptanceTest.java', 'backend/tools/initialize_acceptance_database.ps1']),
    ('2026-08-23', 'test(acceptance): gate fresh install and restart recovery', '全新安装目录启动门控', '新增 Setup EXE、独立端口、独立 APP_DATA_DIR、健康检查和进程清理门控。', '缺少完整 Setup EXE/release manifest 时明确 skipped，不伪造安装验收通过。', ['FreshInstallContractTest、verify_windows_install.ps1 通过；当前发行包缺 Setup EXE，真实安装未执行'], ['verify_fresh_install.ps1', 'backend/src/test/java/com/douyin/mixcut/acceptance/FreshInstallContractTest.java']),
]

def record(version, date, subject, title, summary, changes, verification, evidence, kind='历史开发阶段'):
    return {
        'id': 'release-' + version.replace('.', '-'), 'version': version, 'releasedAt': date,
        'kind': kind, 'title': title, 'summary': summary, 'changes': [changes],
        'fixes': [f'完成提交 {subject} 对应的功能或验收闭环；未记录额外回归修复。'],
        'verification': verification,
        'compatibility': '仅增加可复核的应用功能、验收基座或任务可靠性，不修改用户素材源文件，不读取或提交凭据。',
        'evidence': evidence,
    }

notes = json.loads(NOTES.read_text(encoding='utf-8-sig'))
old_history = list(notes.get('history', []))
# Remove previous aggregate/current records for versions >= 2.2.102; preserve 2.2.101 and older history.
old_history = [item for item in old_history if not (str(item.get('version', '')).startswith('2.2.') and int(str(item['version']).split('.')[2]) >= 102)]
base_version = 101
new_records = []
for index, item in enumerate(COMMITS, start=1):
    version = f'2.2.{base_version + index}'
    date, subject, title, summary, changes, verification, evidence = item
    new_records.append(record(version, date, subject, title, summary, changes, verification, evidence))
newest = deepcopy(new_records[-1])
newest['kind'] = '当前本机构建'
notes.update(newest)
notes['history'] = [*reversed(new_records[:-1]), *old_history]
notes['source'] = 'backend/src/main/resources/release-notes.json'
NOTES.write_text(json.dumps(notes, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'rebuilt {len(new_records)} records; current={newest["version"]}; history={len(notes["history"])}')
