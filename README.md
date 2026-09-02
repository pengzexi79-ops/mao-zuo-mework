# 猫作·Mework

前端 Vue 3 + 后端 Spring Boot 3（Java 17）+ MySQL 8 + ffmpeg。将一组已授权的本地素材、产品信息和受约束工作流，批量生成 50–150 秒的短视频混剪。

![猫作·Mework 应用概览](docs/assets/mework-dashboard.png)

> 本项目默认仅供本机使用。素材版权、肖像权、平台条款和商业授权由使用者负责确认。

## 当前进展

当前发行版本为 `2.2.163`。截至 2026-09-02，D 盘工作树已包含按真实协议分流的文本、图片、视频和配音链路，OpenAI、Qwen、MiniMax 与未知兼容网关的音色隔离，素材后台导入、持续出片、生成结果入库、成片质检与修复，以及使用初代猫作图标的 Windows 全量安装流程。2026-08-30、2026-08-31 和 2026-09-02 的独立更新均已写入应用内历史。

完整开发提交记录见 [`docs/zcode-handoff/GIT_HISTORY.md`](docs/zcode-handoff/GIT_HISTORY.md) 及 GitHub 的 [`legacy-history-sanitized` 分支](https://github.com/pengzexi79-ops/mao-zuo-mework/tree/legacy-history-sanitized)；`main` 是可直接分享的最新源码快照。

8 月 28–29 日的实际工作树变更、验证范围、未接通能力和发布边界见 [`docs/zcode-handoff/DEVELOPMENT_LOG_20260828_20260829.md`](docs/zcode-handoff/DEVELOPMENT_LOG_20260828_20260829.md)。仓库截图与应用能力页截图见 [`docs/REPOSITORY_EVIDENCE_20260829.md`](docs/REPOSITORY_EVIDENCE_20260829.md)。

## Windows 安装包

私人仓库的 `v2.2.163` Release 提供 Windows 10/11 x64 全量安装包。下载 EXE 和全部 `.bin` 分片后即可安装；有 D 盘时默认安装到 `D:\Mework`，没有 D 盘时回退当前用户应用目录。

安装包内置 Java 17、MySQL 8、FFmpeg/FFprobe、Python 媒体环境、whisper.cpp、离线 ASR 模型和 ImageMagick。首次启动会创建空数据库并生成每台机器独立的随机密钥，不包含开发者素材、任务、成片、数据库、API Key、中转站地址、账号或 Cookie。

- 普通用户：[`INSTALLATION_GUIDE.md`](INSTALLATION_GUIDE.md)
- AI 助手：[`AI_INSTALLATION_GUIDE.md`](AI_INSTALLATION_GUIDE.md)
- 隐私边界：[`PRIVACY_RELEASE.md`](PRIVACY_RELEASE.md)
- 机器清单：[`installer/ai-setup-manifest.json`](installer/ai-setup-manifest.json)

## 功能概览

```text
[AI 钩子] → [实拍/明星素材切片，均匀插入产品段] → [片尾]
                            ↓
                BGM/可选口播 + 字幕 + ffmpeg 渲染
```

不同 `variant` 会移动切片起点，减少批量出片的重复。计划会在渲染前生成，方便人工检查时间线、时长和素材分配。

## 应用能力

猫作的核心是“素材进入系统后，先检查，再规划，再渲染，再质检，再交付”，不是把 AI 生成或 ffmpeg 调用直接拼在页面按钮上。

| 工作区 | 已实现的主要工作 |
| --- | --- |
| 能力中心 | 查看 AI、媒体处理和环境状态；按能力确认可执行 Provider；查看历史更新记录 |
| AI 接入 | 保存 OpenAI-compatible 或已注册供应商配置；区分文本、视觉、图片、视频、配音能力；探测模型并保留明确的能力声明；查看路由和诊断日志 |
| AI 创作 | 分开的图片、视频、配音生成链路；任务队列、处理中状态、错误诊断、预览、保存、批量保存、删除和清理已结束记录；生成媒体自动进入素材库 |
| 素材库 | 扫描本地目录、按角色/标签/关键词筛选、预览媒体、指定素材送去出片、管理图片/视频/音频素材 |
| 出片控制台 | 项目/工作流/行业预设、时长区间、片段分配、产品结构、画布帧率、音频处理、字幕遮盖、固定顺序、干跑时间线、批量/持续出片 |
| 成片库 | 图片和视频预览、横屏/竖屏/正方形自适应展示、文件名/任务/交付状态筛选、批量下载、批量删除、质检报告和修复决策 |
| 编辑与工具 | 单条成片编辑、媒体二创、封面区域选择、音频/字幕处理；复用真实渲染和 QC 链路，不用未验证的假时间线 |
| 项目与工作流 | 产品、卖点、受众和禁用词；受约束 JSON DSL 工作流；素材选择、参数、钩子、脚本、音频和画布设置 |
| 资源与抓取 | 本地资源中心和公开素材抓取；默认拒绝私网/保留地址和登录态抓取，避免 SSRF 与越权使用 |

### AI 请求链路

AI 能力按实际协议分流，不能只靠模型名称猜测：

- 文本/视觉对话走 `POST /v1/chat/completions`。
- 图片生成走 `POST /v1/images/generations`，兼容供应商返回的 `url` 和 `b64_json`，保存并导入本地素材。
- 视频生成走“提交任务 → 轮询状态 → 下载 → 校验 → 入库”，供应商路径由媒体适配器配置，不把视频请求伪装成图片请求。
- 配音使用独立的语音请求和音频校验链路；生成音频必须通过文件类型、可读性和时间线合同检查。
- 供应商只声明或实际返回某一种能力时，其他能力不会被 UI 虚构为可用；识别结果需要经过确认后才开放生成。

每条生成任务都有可追踪状态、错误信息脱敏、超时/失败处理和素材引用。任务记录的“保存”只管理任务记录，生成文件仍由素材库负责保存和使用。

### 出片质量闭环

出片台先做素材容量和参数预检，再生成只读干跑计划；通过人工确认后才进入渲染。渲染完成后由音频合同、响度、画面、字幕、重复和语义等检查生成 QC 结果。成片库中的修复动作会回到已有任务/渲染链路，避免页面显示成功但文件不可交付。

这套闭环的边界很明确：单条时间线编辑由编辑器负责，出片台的干跑结果不是可直接写入渲染器的时间线协议；OCR、ASR、视觉理解、任意供应商视频协议等没有真实实现和验证的能力不会在文档中标记为“已接通”。

## 前置条件

| 依赖 | 是否必需 | 说明 |
| --- | --- | --- |
| JDK 17+ | 是 | 运行后端 |
| MySQL 8 | 是 | 数据库 |
| ffmpeg / ffprobe | 是 | 渲染和媒体探测；均需在 PATH 中 |
| Node 18+ | 否 | 仅修改前端时需要 |
| yt-dlp / you-get | 否 | 仅网页公开素材抓取时需要 |

## 本机启动

1. 初始化新数据库（可重复执行）：

   ```bash
   mysql -uroot -p < backend/src/main/resources/db/schema.sql
   ```

2. 创建权限最小化的本地数据库用户，并把真实密码配置为环境变量。不要使用 README 中的示例密码，也不要提交密码：

   ```sql
CREATE USER IF NOT EXISTS 'mixcut'@'localhost' IDENTIFIED BY '请替换为强密码';
GRANT ALL PRIVILEGES ON ai_mix_video.* TO 'mixcut'@'localhost';
   FLUSH PRIVILEGES;
   ```

   复制 [`.env.example`](.env.example) 为项目根目录的本机 `.env` 后填写 `DB_PASSWORD`。Spring Boot、IDE 和 [`start.bat`](start.bat) 都会读取该文件；不要提交它。

3. 双击项目根目录的 [`start.bat`](start.bat)。脚本会检查 `java`、`ffmpeg` 和 `ffprobe`，随后优先启动 `backend/target/mixcut-delivery.jar`，不存在时回退到 `mixcut.jar`。

   默认服务仅绑定 `127.0.0.1:8760`，访问 <http://127.0.0.1:8760/>。如需同一 Wi-Fi 的手机访问，必须在本机 `.env` 同时设置 `APP_BIND_ADDRESS=0.0.0.0` 和一个足够长的 `APP_ACCESS_TOKEN`；随后用启动器输出的电脑局域网地址加 `?access_token=...` 打开。不要把此服务暴露到公网。

手动启动：

```bash
cd backend
mvn package -Ddelivery.jar.name=mixcut-delivery
java -jar target/mixcut-delivery.jar --server.port=8760 --server.address=127.0.0.1
```

## 配置、令牌与 CORS

后端默认值在 [`backend/src/main/resources/application.yml`](backend/src/main/resources/application.yml)，所有密钥应通过环境变量或本机未提交配置提供。

| 环境变量 | 用途 |
| --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接；生产或共享机器必须设置强密码 |
| `APP_BIND_ADDRESS` | 默认 `127.0.0.1`；仅配合 `APP_ACCESS_TOKEN` 才可显式设为 `0.0.0.0` 供同 Wi‑Fi 手机访问 |
| `APP_ACCESS_TOKEN` | 非空时保护 `/api/**` 和 `/files/**`；局域网监听时为必填 |
| `APP_CORS_ALLOWED_ORIGINS` | 精确、逗号分隔的浏览器 Origin 白名单 |
| `APP_FREESOUND_API_KEY` / `APP_PIXABAY_API_KEY` / `APP_PEXELS_API_KEY` / `APP_UNSPLASH_API_KEY` | 可选官方素材目录 API Key；分别在能力中心配置后由后端长期读取 |
| `APP_ALLOW_LOGIN_CRAWL` | 登录态站点抓取开关；默认 `false` |

CORS 开启凭据模式，因此 `APP_CORS_ALLOWED_ORIGINS` 必须是明确 Origin，例如 `http://localhost:5273,http://127.0.0.1:5273`；不得使用 `*`。内置页面与 API 同源运行时无需额外配置 CORS。

`.env`、本地 application 覆盖文件、运行数据、构建产物和前端依赖均已由 [`.gitignore`](.gitignore) 排除。不要把令牌、数据库密码、浏览器 Cookie 或第三方 API Key 写入 `start.bat`、README 或 Git。

## 首次出片

1. 在「素材库」指定本机目录并扫描；可按 `hook`、`body`、`celebrity`、`product`、`endcard`、`voice`、`bgm` 标记素材。
2. 在「项目」新建品牌、产品、卖点与受众信息。
3. 按需在「AI 接入」添加模型供应商。AI 调用失败会回退，不应阻塞本地渲染。
4. 在「出片控制台」先预览第 N 条时间线，再提交批量渲染。
5. 在「成片库」播放或下载结果。

常用参数：`minSec` / `maxSec` 约束时长；`sliceSec`、`sliceJitter` 和 `maxSlicesPerMaterial` 控制切片；`celebrityRatio`、`productSlots` 和 `productSec` 控制结构；`bgmVolume` 控制配乐音量。

## 公开素材抓取与合规

网页抓取仅面向用户有权访问和使用的**公开** `http/https` 链接。服务端会拒绝 `localhost`、私网、保留地址、非 HTTP 协议和重定向到这些地址的目标，以防 SSRF。

- 不会自动读取、导入或保存浏览器 Cookie。
- Pixabay、Pexels、Freesound 和 Unsplash 使用各自官方 API：用户在官方开发者页面申请 Key，在「能力中心」保存到 D 盘项目的本机 `.env`，重启后应用即可随时调用。Key 只在服务端请求头中发送，不进入前端、任务记录、日志或 Git。
- 没有公开 API 的来源（例如 Mixkit、Coverr、Videvo、爱给网、站酷、新片场、VJ 师等）不能仅靠网页登录变成应用 API。应用只保留官方入口，用户完成登录并下载自己获授权的文件后，再通过素材库导入；不会提取 Cookie、密码、会话令牌，也不会绕过平台权限。
- Openverse、Wikimedia Commons 和 Internet Archive 的公开检索链路无需账号；是否能商用仍以每条素材的许可证为准。
- 默认不抓取必须登录的平台；`APP_ALLOW_LOGIN_CRAWL=false` 是默认安全状态。
- 运行 Windows 批处理请使用 `start.bat` 或 `cmd.exe /c`，不要在 Git Bash 中用重定向创建文件，避免生成设备名文件（如 `nul`）。
- 即使明确开启该开关，也应自行确认平台条款、下载权限、版权、肖像授权与商用范围。
- 明星片段、影视片段和第三方素材是高风险内容，商用前必须取得相应授权。

## Skill DSL 与工作流安全

内置工作流可调用 `select_materials`、`set_duration`、`set_slice`、`set_structure`、`gen_hook`、`gen_script`、`pick_audio`、`set_canvas`、`fetch_web_video` 和 `fetch_audio_library`。

自定义 `script` / `ai` Skill 不是可执行脚本，而是受约束的 JSON DSL：

```json
{
  "version": 1,
  "steps": [
    {"op": "select_materials", "roles": ["body", "product"], "keyword": "", "limit": 100},
    {"op": "set_params", "params": {"minSec": 50, "maxSec": 90, "sliceSec": 3}},
    {"op": "note", "text": "产品段均匀插入主体"}
  ]
}
```

DSL 仅允许 `select_materials`、`set_params`、`set_hook`、`set_script`、`pick_audio`、`note` 六种操作。命令、shell、URL、HTTP、下载、模板、文件路径及未知字段会被拒绝；AI 不能生成或执行 ffmpeg 命令。

## 任务恢复

渲染任务使用事务提交后派发、活动心跳、总超时和僵死检测：

- `job.timeout_sec`、`job.stale_after_sec` 为单任务覆盖值；`0` 表示应用级默认值。
- `job.last_activity_at` 是 watchdog 的恢复依据。
- 重启前已写入的 `job_output(job_id, idx)` 检查点由唯一索引保护，已完成输出不会重复记录。
- 如任务因本机重启或 ffmpeg 故障失败，请在页面检查错误和输出文件后重新提交未完成任务；不要手工删除检查点来“恢复”。

## 数据库升级（已有 V1 数据库）

新库使用 `schema.sql`。已有数据库请在备份完成、应用停止写入或低峰期执行：

```bash
mysql -uroot -p < docs/UPGRADE_V1_TO_V2.sql
```

该脚本安全、幂等地补充 `job` 可靠性字段、`job_output(job_id, idx)` 唯一索引和 `material(file_path)` 索引；不重建表、不新增外键，也不会删除历史记录。若历史 `job_output` 存在重复 `(job_id, idx)`，脚本会列出重复组并跳过唯一索引创建；请备份、人工核对处理后再重新执行升级。

旧版 [`backend/src/main/resources/db/reliability-migration.sql`](backend/src/main/resources/db/reliability-migration.sql) 仍可用于仅补任务可靠性字段；交付与升级统一以 `docs/UPGRADE_V1_TO_V2.sql` 为准。

## 前端开发

```bash
cd frontend
npm install
npm run dev
npm run build
```

`npm run build` 会先生成一组完整的静态资源并校验 `index.html` 引用，随后再执行后端 `mvn package`。应用只从 `http://127.0.0.1:8760/` 提供的最新 Jar 使用前端；`5273` 仅用于正在运行的 Vite 开发服务器。

Vite 开发服务器默认使用 `http://localhost:5273` 并代理后端；若修改端口，需同步更新 `APP_CORS_ALLOWED_ORIGINS`。

## 测试

不启动服务或访问网络的最小后端验证：

```bash
cd backend
mvn test
```

其中 `UrlGuardTest` 使用纯 URI 语法检查和地址分类断言，不依赖公网 DNS；生产 `UrlGuard.validate` 仍会在实际抓取前解析所有 DNS 结果并拦截私网/保留地址。

## 目录结构

```text
ai-douyin-mixcut/
├── backend/                         Spring Boot 后端
│   ├── src/main/resources/db/
│   │   ├── schema.sql               新库幂等建库脚本
│   │   └── reliability-migration.sql
│   └── src/test/java/               离线单元测试
├── docs/
│   ├── ARCHITECTURE.md
│   └── UPGRADE_V1_TO_V2.sql         现有数据库安全升级脚本
├── frontend/                        Vue 3 + Vite 前端
├── .env.example                     不含真实机密的环境变量模板
└── start.bat                        本机回环地址启动脚本
```
