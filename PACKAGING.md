# 打包发布指南（全公司装完即用）· 已验证 2026-08-14

## 前置
1. 安装 Inno Setup 6（本机已装 6.7.3，ISCC 位于 C:\Program Files (x86)\Inno Setup 6\ISCC.exe）
2. 确认 .toolchain 在项目上一级（jdk-17.0.2 / mysql-8.0.28-winx64 / mysqldata / maven）
3. 确认 ffmpeg（C:\DevTools\ffmpeg-9.0-full_build 或系统 PATH）

## 步骤（推荐由 build_installer.bat 统一执行）
```powershell
cd C:\Users\Windows\WorkBuddy\2026-08-09-14-55-27\ai-douyin-mixcut
# 默认只做本地离线检查，不执行 pip 或网络安装
setup_runtime.bat verify
# 只有明确需要联网修复时才执行：
setup_runtime.bat repair
# 构建时使用真实文件复制，不使用 Junction；默认不联网安装 venv 依赖
build_installer.bat
```
`build_installer.bat` 会生成 `installer\version.iss` 和 `installer\output\release-manifest.json`，并在编译前校验版本、关键运行时文件、SHA256 和 Junction。不要直接运行 `ISCC.exe installer\Mework.iss`，因为 `version.iss` 是构建时生成文件。
产物：installer\output\Mework-Setup-{当前版本}.exe（实际大小取决于随包模型和运行时）。

## 发行包内容（Mework.iss 已配置）
- start.bat / setup_runtime.bat / ensure_env.bat / start_mysql.bat / .env.example
- backend\target\mixcut-delivery.jar（安装器只打包该交付产物；start.bat 在开发环境仍保留 mixcut.jar 回退，但该回退 jar 不随包分发）
- backend\requirements-windows.txt + bootstrap_media_runtime.bat + media_diagnose.py + natural_tts.py
- release-notes.json
- portable\*（JDK17 / MySQL8+mysqldata[已含 ai_mix_video 全量 schema 与 mixcut 用户] / FFmpeg / Maven / backend\.venv）

## 启动与修复边界
- `ensure_env.bat`：仅在缺少本机 `.env` 时生成默认本地配置和密钥；这是本地配置写入，不是联网安装。
- `start_mysql.bat`：3306 已有 MySQL 则直接复用；否则用便携 MySQL 自动拉起，配置和日志写入应用 `data` 目录。
- `start.bat`：便携 JDK17 优先 → 校验 JAR → 拉起 MySQL → **离线 verify 媒体运行时** → 启动后端；缺依赖时只告警，不自动 pip 安装。
- `setup_runtime.bat verify`：只检查现有 venv、模块和便携运行时，不创建 venv、不联网。
- `setup_runtime.bat repair`：用户明确选择后，才创建 venv 并执行 requirements 固定版本安装；修复日志位于 `data\logs\dependency-bootstrap.log`。
- 便携 FFmpeg 优先，缺则系统；缺失时能力中心会显示明确状态。
- `prepare_portable.ps1 -Copy` 默认只复制已准备好的 venv；构建机需要联网修复时必须显式传 `-RepairDependencies`。

## 能力交付边界
- Windows x64 安装包必须预置 Java 17、MySQL 8、FFmpeg/FFprobe、Python 媒体运行时、yt-dlp、you-get、gallery-dl、Demucs、Rembg、Auto-Editor、OpenCV、whisper.cpp 与 ImageMagick；`build_installer.bat` 会在这些文件或基础 Python 模块缺失时直接失败（含便携 Python `portable\python\python.exe` 与离线转写模型缓存 `portable\whisper-models\hub\models--Systran--faster-whisper-small`）。
- 能力清单 `backend\src\main\resources\capabilities.json` 是能力中心与「修复安装」边界的唯一数据源（schemaVersion + manifestVersion 版本化）：环境检测项、修复安装目标、官方入口全部由它驱动；清单中 approvedPip 全部固定 `name==version`（与随包 venv 一致），`prepare_portable.ps1` 按该清单预置媒体工具，BootstrapService 的修复安装也只允许使用清单内固定版本，绝不接受任意包名。
- 安装后的能力中心只把实际探测通过的组件显示为“已安装可用”。预置组件缺失时仅对受控白名单组件提供“修复安装”；其余组件明确要求重新运行安装器的运行环境检查。
- 模型文件、第三方 API Key、账号授权和硬件驱动不属于可通用预置内容：faster-whisper / Demucs / Rembg / ChatTTS / whisper.cpp 模型可能在首次使用时下载；Pixabay/Freesound Key、NVENC 驱动必须由用户按官方条件完成。
- ChatTTS 仅在随包运行时已经可用时显示“已安装可用”。当前不向浏览器提供 ChatTTS 的一键 pip 安装，避免不同 Python/PyTorch/Rust 组合造成不可复现安装。

## 阶段 3B 离线 Smoke 验收

`verify_offline_bundle.ps1` 是构建前的隔离媒体验收门禁。它只使用发行包内的 FFmpeg、FFprobe、venv Python 和 `media_diagnose.py`，在系统 TEMP 下生成短 `testsrc` 视频并检查 OpenCV 质量 JSON；不启动 Java/MySQL，不读取或修改 `.env`、data、数据库、用户素材，不执行 pip 或网络下载。

```powershell
.\verify_offline_bundle.ps1 -BundleRoot (Get-Location).Path
```

退出码：`0` 通过；`2` 隔离目录安全失败；`3` 随包入口缺失/为链接；`4` FFmpeg 无法生成测试媒体；`5` FFprobe/诊断 JSON 失败；`6` 临时目录清理失败；`1` 未预期异常。`-KeepWorkDir` 只用于保留本次 GUID 临时目录排障。

该 smoke 不代表 Edge-TTS、网页抓取、Demucs 模型、ChatTTS、API Key、NVENC 或 ASR 模型下载条件已离线满足；这些仍按能力中心显示的网络/外部条件处理。

## 版本记录
每次发布前：`cd backend && python tools/release_notes.py new --title "..."`，填 pending，check，apply（自动生成新版本号）。
不要手工修改 `installer\Mework.iss` 的版本；`build_installer.bat` 会从 `release-notes.json` 读取当前版本，和 `AppProps.RELEASE_VERSION` 比较后生成 `installer\version.iss`。
构建失败表示版本不一致，不能继续生成安装器。历史记录中的旧版本（例如 2.2.100）保留在 release history 中，不代表当前发行版本。