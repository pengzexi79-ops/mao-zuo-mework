# P3 隔离验收

## P3-1 固定媒体 Fixture

验收 fixture 位于 `backend/src/test/resources/acceptance/fixtures/`，清单为 `backend/src/test/resources/acceptance/fixture-manifest.json`。

生成和校验只使用项目 `portable/ffmpeg/bin/ffmpeg.exe` 与 `ffprobe.exe`，不回退到系统 PATH，不读取 `.env`、用户素材目录、数据库或网络。

```powershell
cd <project-root>
powershell -NoProfile -ExecutionPolicy Bypass -File backend/tools/generate_acceptance_fixtures.ps1 -ProjectRoot (Get-Location).Path -Force
powershell -NoProfile -ExecutionPolicy Bypass -File backend/tools/verify_acceptance_fixtures.ps1 -ProjectRoot (Get-Location).Path
cd backend
mvn -q -Dtest=FixtureManifestTest test
```

Fixture 覆盖：运动视频、音视频、黑场、纯色、音频和图片。Manifest 只保存相对路径、媒体元数据、大小、SHA256 和预期质量闸门分类，不保存绝对路径、账号、API Key、签名 URL 或机器配置。

## P3-2 隔离数据库与本地 Mock HTTP

业务应用继续使用 `ai_mix_video`。P3 验收数据库只能使用 `ai_mix_video_acceptance`，凭据必须通过 `ACCEPTANCE_DB_URL`、`ACCEPTANCE_DB_USERNAME`、`ACCEPTANCE_DB_PASSWORD` 显式提供；没有环境变量时，数据库校验只输出 skipped，不连接任何数据库。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File backend/tools/verify_acceptance_database.ps1
cd backend
mvn -q -Dtest=AcceptanceDatabaseContractTest,LocalMockHttpServerTest,OutboundPolicyAcceptanceTest test
```

`acceptance-schema.sql` 是隔离验收用的最小幂等 schema。校验脚本只做本地地址、数据库名和只读表检查，不创建、删除、重置或迁移当前业务库。

本地 HTTP mock 只绑定 `127.0.0.1` 随机端口，覆盖成功、429、500、超时、有限重试和幂等 POST。它只证明 `SafeHttpClient` 的边界行为，不代表真实公网来源、AI Provider 或 Edge-TTS 已验收。

## 验收边界

P3-1 证明固定媒体输入可复核、路径未越界、文件未被替换、FFprobe 元数据稳定。P3-2 证明隔离数据库契约和本地 HTTP 传输策略可复核。它们都不代表真实公网供应商、模型下载或安装器全链路已验收。

后续 P3 顺序：

1. 隔离 MySQL database/schema 与可回滚初始化。
2. 本地 mock HTTP 服务，覆盖 AI、公开来源和 Edge-TTS 的成功、429、5xx、超时、恢复与幂等。
3. 离线导入 -> 诊断 -> 分析 -> 渲染 -> QC -> 成片记录。
4. 联网 mock 链路与重启恢复。
5. 全新安装目录启动、中文路径/不同盘符和多 Windows 电脑矩阵。

本模块不修改 `FixedOrderPresets`、Studio UI、Provider 配置或 `.env`，也不复制任何凭据。
