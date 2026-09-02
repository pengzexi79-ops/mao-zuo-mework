# 猫作·Mework 2.2.163 安装手册

## 支持范围

- Windows 10 或 Windows 11，x64。
- 建议至少预留 8 GB 磁盘空间。
- 有 D 盘时默认安装到 `D:\Mework`；没有 D 盘时安装到当前用户的应用目录。
- 安装与首次启动不需要另装 Java、MySQL、FFmpeg、Python、ImageMagick 或离线语音识别模型。

## 下载

从私人仓库 `pengzexi79-ops/mao-zuo-mework` 的 `v2.2.163` Release 下载以下文件，并放在同一目录：

- `Mework-Setup-2.2.163.exe`
- 所有同版本 `.bin` 分片
- `SHA256SUMS.txt`
- `release-manifest.json`
- `ai-setup-manifest.json`

私人仓库只有仓库所有者和被邀请的协作者可以访问。分享给好友前，请在 GitHub 仓库设置中把对方账号添加为只读协作者。

## 校验与安装

在下载目录打开 PowerShell：

```powershell
Get-FileHash .\Mework-Setup-2.2.163.exe -Algorithm SHA256
```

将结果与 `SHA256SUMS.txt` 对照。确认所有 `.bin` 分片和 EXE 位于同一目录后，双击 EXE 安装。不要单独移动或重命名分片。

首次启动会自动完成：

1. 选择未占用的本机应用端口和 MySQL 端口。
2. 生成每台电脑独立的数据库应用密码、root 密码和 Provider 凭据加密主密钥。
3. 在应用数据目录创建全新的空 MySQL 数据库并载入 schema。
4. 启动只监听本机回环地址的应用，然后打开浏览器。

安装包不包含开发者素材、任务、成片、日志、数据库、API Key、中转站地址或账号登录态。

## 已内置环境

- Java 17
- MySQL 8 服务端与客户端
- FFmpeg 与 FFprobe
- Python 媒体运行时及固定版本依赖
- whisper.cpp、faster-whisper 离线模型
- ImageMagick
- 应用后端、前端静态资源、数据库 schema、能力清单和安装诊断脚本

## 用户需要自行配置的内容

这些内容具有个人授权、计费或硬件差异，不能预先写进通用安装包：

- AI 供应商或中转站的服务地址、API Key、模型 ID 和能力类型。
- Pixabay、Pexels、Unsplash、Freesound 的个人 API Key。
- 必须登录的网站账号、Cookie 和下载授权。猫作不会读取浏览器登录态。
- NVIDIA/AMD/Intel 显卡驱动、系统代理、防火墙放行和企业网络证书。
- 用户自己的素材、项目、品牌资料、声音样本和输出目录。

请在应用的「AI 接入」和「能力中心」页面输入个人凭据。不要把 API Key 发给 AI 助手、写进截图、README、Issue 或聊天记录。

## 内置素材来源边界

- 无需 Key：Openverse、Wikimedia Commons、Internet Archive。
- 用户配置 Key 后可用：Pixabay、Pexels、Unsplash、Freesound。
- 仅有网页登录、没有公开 API 的站点保留官方入口；用户应在取得授权后手工下载，再导入素材库。

每条素材是否可商用仍以来源页面的许可证、肖像权和平台条款为准。

## 数据位置与卸载

默认安装在 `D:\Mework` 时，运行数据位于 `D:\Mework\data`。卸载前如需保留项目，请先备份该目录。卸载程序不会把开发者数据库或素材恢复到用户电脑。

## 故障检查

1. 运行安装目录下的 `setup_runtime.bat verify`。
2. 查看「能力中心」的环境状态。
3. 查看安装目录 `data\logs` 下的启动和数据库日志。
4. 若端口冲突，关闭占用程序后重启；首次配置会自动选择空闲端口。
5. 若第三方 API 失败，核对服务地址、模型能力、额度和供应商真实接口，不要把聊天、生图、视频和配音模型混用同一请求通道。
