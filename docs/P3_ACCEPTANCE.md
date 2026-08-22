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

## 验收边界

P3-1 只证明固定媒体输入可复核、路径未越界、文件未被替换、FFprobe 元数据稳定。它不代表数据库、联网来源、AI Provider、Edge-TTS、模型下载或安装器全链路已验收。

后续 P3 顺序：

1. 隔离 MySQL database/schema 与可回滚初始化。
2. 本地 mock HTTP 服务，覆盖 AI、公开来源和 Edge-TTS 的成功、429、5xx、超时、恢复与幂等。
3. 离线导入 -> 诊断 -> 分析 -> 渲染 -> QC -> 成片记录。
4. 联网 mock 链路与重启恢复。
5. 全新安装目录启动、中文路径/不同盘符和多 Windows 电脑矩阵。

本模块不修改 `FixedOrderPresets`、Studio UI、Provider 配置或 `.env`，也不复制任何凭据。
