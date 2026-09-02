# GitHub 私人分享说明：猫作·Mework

本仓库是猫作·Mework 的完整私人源码与发行仓库。当前正式发行版为 `v2.2.163`，支持 Windows 10/11 x64。

## 分享给好友

仓库为 private。好友必须拥有 GitHub 账号，并由仓库所有者在仓库设置中添加为协作者后，才能查看源码、文档和 Release。不要公开分享个人访问令牌、API Key、Cookie 或带签名的临时下载地址。

## Release 下载内容

在 `v2.2.163` Release 中下载并放在同一目录：

- `Mework-Setup-2.2.163.exe`
- 安装器生成的全部同版本 `.bin` 分片
- `SHA256SUMS.txt`
- `release-manifest.json`
- `ai-setup-manifest.json`
- `INSTALLATION_GUIDE.md`
- `AI_INSTALLATION_GUIDE.md`
- `PRIVACY_RELEASE.md`

安装器内置应用运行所需的 Java 17、MySQL 8 程序、FFmpeg/FFprobe、Python 媒体运行时、whisper.cpp、离线 ASR 模型和 ImageMagick。有 D 盘时默认安装到 `D:\Mework`，否则安装到当前用户应用目录。

## 首次启动

首次启动会在用户电脑上创建空数据库，选择空闲端口，并生成该电脑独立的随机数据库密码和 Provider 凭据加密主密钥。用户随后在应用内自行配置 AI 供应商、素材 API Key、账号授权和自己的素材。

## 隐私排除

源码提交和安装包均不应包含：

- 用户或开发者的 API Key、中转站地址、账号、Cookie、访问令牌和私钥。
- 本机 `.env`、数据库数据目录、素材、样片、成片、任务、日志、缓存和临时文件。
- 开发机绝对路径、浏览器登录态、第三方个人授权和项目私有内容。
- Maven 构建工具与旧 `portable\mysqldata`；它们不是应用运行时的一部分。

完整安装步骤见仓库根目录的 `INSTALLATION_GUIDE.md`。AI 助手应读取 `AI_INSTALLATION_GUIDE.md` 和 `installer/ai-setup-manifest.json`，且不得要求用户把秘密粘贴到聊天中。

## 仓库包含

- 前后端源码、测试、数据库 schema、启动与环境初始化脚本。
- 安装器源码、发行 manifest、隐私检查和全新安装验收脚本。
- 应用截图、版本记录、交接文档及脱敏后的完整 Git 历史。
- GitHub Release 中的可安装 EXE、数据分片、校验清单和安装说明。
