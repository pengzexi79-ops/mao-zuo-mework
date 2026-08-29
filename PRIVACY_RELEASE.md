# 猫作·Mework 发行隐私边界

`v2.2.158` 安装包采用显式白名单构建。只有应用交付文件和七类运行时目录会进入安装包。

## 允许进入安装包

- `portable/jdk-17`
- `portable/mysql`，仅程序文件，不含任何数据库 data 目录
- `portable/ffmpeg`
- `portable/python`
- `portable/whisper`
- `portable/whisper-models`
- `portable/imagemagick`
- `backend/.venv`
- 编译后的 `mixcut-delivery.jar`、空库 schema、启动脚本、能力清单和安装文档

## 明确排除

- `.env` 及所有备份
- API Key、中转站地址、访问令牌、账号、Cookie 和请求日志
- `portable/mysqldata`、构建机数据库和固定数据库密码
- `portable/maven` 和其他仅用于开发构建的工具
- `data`、`materials`、`sample-materials`、`cache`、`logs`、`temp`
- 用户素材、AI 生成素材、任务、成片、项目、声音样本和数据库
- 开发机绝对路径、个人目录和运行中实例状态

## 新机初始化

安装包只携带 MySQL 程序和空库 schema。首次启动在用户应用数据目录初始化新数据库，并为数据库应用账号、root 和 Provider 凭据加密分别生成随机秘密。模板中的所有第三方 Key 保持空白。

## 发布门禁

- `verify_release_privacy.ps1` 扫描 Git 跟踪文件中的常见令牌、私钥、历史固定密码和开发机路径。
- `verify_windows_install.ps1` 检查安装器没有宽泛 `portable\*` 输入。
- `verify_fresh_install.ps1` 真实静默安装、启动、查询空表、检查随机密钥，并只停止隔离安装目录中的进程。
- `release-manifest.json` 对发行文件和运行时目录生成 SHA-256 清单。
- `SHA256SUMS.txt` 对 GitHub Release 的 EXE、分片和文档生成最终下载校验值。

任何曾经在聊天、截图、日志或 Issue 中出现过的真实 API Key 都应立即在供应商后台吊销并重新生成，不能只依赖删除消息。
