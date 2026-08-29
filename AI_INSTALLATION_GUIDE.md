# 猫作·Mework AI 助手安装手册

本文件供 Codex、ChatGPT、Claude 或其他本地 AI 助手读取。目标是安装并验证猫作，不接收、不记录、不回显用户的 API Key、Cookie 或密码。

## 安装合同

- 产品：猫作·Mework
- 版本：`2.2.158`
- 平台：Windows 10/11 x64
- 仓库：`pengzexi79-ops/mao-zuo-mework`，private
- Release：`v2.2.158`
- 默认目录：有 D 盘时 `D:\Mework`，否则 `%LOCALAPPDATA%\Programs\Mework`
- 机器清单：`installer\ai-setup-manifest.json`

## AI 执行步骤

1. 先确认用户已获私人仓库访问权限，并确认 `gh auth status` 成功。
2. 在 D 盘临时目录下载完整 Release，不能只下载 EXE：

```powershell
gh release download v2.2.158 --repo pengzexi79-ops/mao-zuo-mework --pattern 'Mework-Setup-2.2.158*' --pattern 'SHA256SUMS.txt' --pattern 'release-manifest.json' --pattern 'ai-setup-manifest.json'
```

3. 校验 `SHA256SUMS.txt`，确认 EXE 和全部 `.bin` 分片在同一目录。
4. 以普通用户运行安装器。需要静默安装时：

```powershell
Start-Process .\Mework-Setup-2.2.158.exe -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART' -Wait
```

5. 首次启动后等待 `http://127.0.0.1:<port>/api/system/env` 返回 HTTP 200。端口以安装目录 `.env` 的 `PORT` 为准，读取时不得输出该文件中的秘密值。
6. 运行 `setup_runtime.bat verify`，确认 Java、MySQL、FFmpeg、FFprobe、Python 媒体依赖、ASR 和 ImageMagick 状态。
7. 只向用户报告组件状态和修复动作，不展示 `.env`、数据库密码、Provider Key 或请求头。

## 不可预置环境的协助方式

- 显卡：检测驱动和 FFmpeg 硬件编码能力；缺失时引导用户安装显卡厂商官方驱动，不能下载来历不明的驱动包。
- 网络：检测 DNS、HTTPS 和代理；企业证书或系统代理必须由用户或管理员授权配置。
- AI Provider：让用户在应用 UI 内输入服务地址和 Key。AI 助手不得要求用户把 Key 粘贴到聊天中。
- 素材 API：Pixabay、Pexels、Unsplash、Freesound 由用户在官方开发者页面申请 Key，再在能力中心配置。
- 登录站点：不读取浏览器 Cookie，不复制会话文件，不绕过登录或版权控制。
- 用户媒体：由用户选择目录或通过素材库导入；安装包和验证流程必须保持用户表为空。

## 验收标准

- 应用 API 可访问。
- `material`、`ai_provider`、`job`、`job_output`、`crawl_job` 在全新安装时均为 0。
- 数据库应用密码与 root 密码不同，均为随机值；`APP_MASTER_KEY` 为随机 48 字节 Base64。
- 用户 API Key 字段为空，`APP_ALLOW_LOGIN_CRAWL=false`。
- 安装目录不存在 `portable\mysqldata`、`portable\maven`、开发者素材、缓存或样例素材。
- 文本对话、图片生成、视频生成、配音必须按模型能力走独立请求链路，不以模型名称代替能力判断。

## 卸载与清理

只终止可执行路径位于安装目录中的 Java/MySQL 进程。删除目录前必须解析绝对路径并确认它位于明确的测试或安装根目录中；不得停止现有开发实例或删除其他工作区。
