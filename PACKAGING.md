# 猫作·Mework Windows 发行构建

目标版本：`2.2.164`，Windows 10/11 x64。

## 构建原则

- 发行输入使用显式白名单，禁止 `portable\*`。
- 只打包 JDK、MySQL 程序、FFmpeg、Python、whisper、离线 ASR 模型、ImageMagick 和 `backend\.venv`。
- 不打包 `portable\mysqldata`、Maven、`.env`、素材、任务、成片、缓存、日志或开发机路径。
- 数据库在首次启动时从 `schema.sql` 创建，数据库应用密码、root 密码和 `APP_MASTER_KEY` 每台机器随机生成。
- 第三方 API Key、账号登录、Cookie、GPU 驱动、代理和企业证书由用户自行配置。

## 构建机要求

- Inno Setup 6
- 可用的 Node.js/npm 和 Maven，仅用于构建，不进入安装包
- 已准备的发行运行时目录和 `backend\.venv`
- 足够的 D 盘空间；安装器使用约 1.5 GB 的 `.bin` 分片

## 发布命令

```powershell
python backend\tools\release_notes.py check
python backend\tools\release_notes.py apply

cd frontend
npm run build
cd ..\backend
mvn clean test
mvn clean package -DskipTests -Ddelivery.jar.name=mixcut-delivery
cd ..

powershell -NoProfile -ExecutionPolicy Bypass -File .\verify_release_privacy.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\verify_windows_install.ps1
```

推荐使用 `build_installer.bat` 统一生成 `installer\version.iss`、`release-manifest.json` 和 Inno Setup 产物。安装器完成后必须运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\verify_fresh_install.ps1
```

真实安装验收必须证明：

- `/api/system/env` 返回 200。
- `material`、`ai_provider`、`job`、`job_output`、`crawl_job` 均为 0。
- 三类本机密钥随机、长度正确且互不复用。
- 用户 API Key 为空，登录态抓取默认关闭。
- 安装后不存在 `portable\mysqldata`、`portable\maven`、开发者素材或缓存。
- 只停止隔离安装目录对应的 Java/MySQL 进程，不触碰现有开发实例。

## 发行文件

- `Mework-Setup-2.2.164.exe`
- `Mework-Setup-2.2.164-*.bin`
- `SHA256SUMS.txt`
- `release-manifest.json`
- `ai-setup-manifest.json`
- `INSTALLATION_GUIDE.md`
- `AI_INSTALLATION_GUIDE.md`
- `PRIVACY_RELEASE.md`

GitHub Release 标签为 `v2.2.164`。EXE、全部分片和校验文件必须同时上传；缺少任一 `.bin` 的下载目录无法完成安装。
