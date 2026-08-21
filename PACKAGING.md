# 打包发布指南（全公司装完即用）· 已验证 2026-08-14

## 前置
1. 安装 Inno Setup 6（本机已装 6.7.3，ISCC 位于 C:\Program Files (x86)\Inno Setup 6\ISCC.exe）
2. 确认 .toolchain 在项目上一级（jdk-17.0.2 / mysql-8.0.28-winx64 / mysqldata / maven）
3. 确认 ffmpeg（C:\DevTools\ffmpeg-9.0-full_build 或系统 PATH）

## 步骤（一键：build_installer.bat 已串起 1-3）
```powershell
# 1) 准备便携工具链到项目 portable\（真实复制，Inno Setup 不跟随 Junction）
cd C:\Users\Windows\WorkBuddy\2026-08-09-14-55-27\ai-douyin-mixcut
powershell -ExecutionPolicy Bypass -File .\prepare_portable.ps1 -Copy

# 2) 构建后端（前端静态资源已由 npm run build 输出到 backend/src/main/resources/static）
cd backend
mvn -f pom.xml clean package -DskipTests -Ddelivery.jar.name=mixcut-delivery

# 3) 编译安装器
"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" .\installer\Mework.iss
```
产物：installer\output\Mework-Setup-2.2.x.exe（实测约 780 MB，LZMA2 压缩，编译约 10-12 分钟）

## 发行包内容（Mework.iss 已配置）
- start.bat / setup_runtime.bat / ensure_env.bat / start_mysql.bat / .env.example
- backend\target\mixcut-delivery.jar（安装器只打包该交付产物；start.bat 在开发环境仍保留 mixcut.jar 回退，但该回退 jar 不随包分发）
- backend\requirements-windows.txt + bootstrap_media_runtime.bat + media_diagnose.py + natural_tts.py
- release-notes.json
- portable\*（JDK17 / MySQL8+mysqldata[已含 ai_mix_video 全量 schema 与 mixcut 用户] / FFmpeg / Maven / backend\.venv）

## 首次启动（全自动，无人工）
- ensure_env.bat：无 .env 时自动生成（DB_URL=jdbc:mysql://127.0.0.1:3306/ai_mix_video，DB_USERNAME=mixcut，DB_PASSWORD 为内置本地强口令，与 portable\mysqldata 中 mixcut 用户一致）
- start_mysql.bat：3306 已有 MySQL 则直接复用；否则用便携 MySQL 自动拉起（my.ini 生成于 data\mysql.ini，日志 data\logs\mysql.log），最多等 60 秒
- start.bat：便携 JDK17 优先 → 校验 jar → 拉起 MySQL → venv 媒体依赖 bootstrap → 启动后端
- 便携 FFmpeg 优先，缺则系统
- venv 缺失自动创建 + bootstrap 装依赖

## 能力交付边界
- Windows x64 安装包必须预置 Java 17、MySQL 8、FFmpeg/FFprobe、Python 媒体运行时、yt-dlp、you-get、gallery-dl、Demucs、Rembg、Auto-Editor、OpenCV、whisper.cpp 与 ImageMagick；`build_installer.bat` 会在这些文件或基础 Python 模块缺失时直接失败（含便携 Python `portable\python\python.exe` 与离线转写模型缓存 `portable\whisper-models\hub\models--Systran--faster-whisper-small`）。
- 能力清单 `backend\src\main\resources\capabilities.json` 是能力中心与「修复安装」边界的唯一数据源（schemaVersion + manifestVersion 版本化）：环境检测项、修复安装目标、官方入口全部由它驱动；清单中 approvedPip 全部固定 `name==version`（与随包 venv 一致），`prepare_portable.ps1` 按该清单预置媒体工具，BootstrapService 的修复安装也只允许使用清单内固定版本，绝不接受任意包名。
- 安装后的能力中心只把实际探测通过的组件显示为“已安装可用”。预置组件缺失时仅对受控白名单组件提供“修复安装”；其余组件明确要求重新运行安装器的运行环境检查。
- 模型文件、第三方 API Key、账号授权和硬件驱动不属于可通用预置内容：faster-whisper / Demucs / Rembg / ChatTTS / whisper.cpp 模型可能在首次使用时下载；Pixabay/Freesound Key、NVENC 驱动必须由用户按官方条件完成。
- ChatTTS 仅在随包运行时已经可用时显示“已安装可用”。当前不向浏览器提供 ChatTTS 的一键 pip 安装，避免不同 Python/PyTorch/Rust 组合造成不可复现安装。

## 版本记录
每次发布前：cd backend && python tools/release_notes.py new --title "..."，填 pending，check，apply（自动生成新版本号），
再改 installer\Mework.iss 的 AppVersion 并重编译安装器。
注意：jar 内部版本来自 backend\src\main\java\com\douyin\mixcut\config\AppProps.java 的 version 字段，
发布时确保它与 release-notes 最新版本号一致（2.2.36 起已对齐）。