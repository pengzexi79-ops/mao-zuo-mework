@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

REM Prepare only bundled/local runtime pieces. Network failures are non-fatal.
set "APP_DIR=%~dp0"
set "BACKEND_DIR=%APP_DIR%backend"
set "MODE=%~1"
if not defined MODE set "MODE=verify"
if /I not "%MODE%"=="verify" if /I not "%MODE%"=="repair" (
  echo 用法：setup_runtime.bat [verify^|repair]
  exit /b 2
)
if not defined APP_DATA_DIR set "APP_DATA_DIR=%APP_DIR%data"
set "LOG_DIR=%APP_DATA_DIR%\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

echo ============================================================
echo   Mework runtime preparation
echo ============================================================

REM Create a minimal .env with bundled-MySQL defaults when none exists.
call "%APP_DIR%ensure_env.bat"

if exist "%APP_DIR%portable\jdk-17\bin\java.exe" (
  echo [已就绪] 便携 Java 17
) else (
  java -version >nul 2>&1 && echo [已就绪] 系统 Java || echo [需处理] Java 17+: 将便携 JDK 放入 portable\jdk-17，或配置 JAVA_HOME
)

if exist "%APP_DIR%portable\mysql\bin\mysqld.exe" (
  if exist "%APP_DIR%portable\mysqldata\ai_mix_video" (
    echo [已就绪] 便携 MySQL 8（数据目录已含 ai_mix_video 库）
  ) else (
    echo [提示] 便携 MySQL 已就绪，数据目录未初始化；请先启动一次初始化或使用环境中心处理
  )
) else (
  echo [需处理] MySQL: 将便携 MySQL 放入 portable\mysql（含 bin\mysqld.exe），数据目录放入 portable\mysqldata
)
if exist "%APP_DIR%portable\ffmpeg\bin\ffmpeg.exe" (
  echo [已就绪] 便携 FFmpeg
) else (
  ffmpeg -version >nul 2>&1 && echo [已就绪] 系统 FFmpeg || echo [需处理] FFmpeg: 将 ffmpeg.exe 和 ffprobe.exe 放入 portable\ffmpeg\bin
)

if not exist "%BACKEND_DIR%\.venv\Scripts\python.exe" if /I "%MODE%"=="repair" (
  if exist "%APP_DIR%portable\python\python.exe" (
    echo [准备中] 正在用内置 Python 创建 backend\.venv...
    "%APP_DIR%portable\python\python.exe" -m venv --copies "%BACKEND_DIR%\.venv" >>"%LOG_DIR%\dependency-bootstrap.log" 2>&1
    if errorlevel 1 (
      echo [可选增强未就绪] 无法用内置 Python 创建 backend\.venv；基础界面仍可启动。
      echo [详情] %LOG_DIR%\dependency-bootstrap.log
    )
  ) else (
    set "PYTHON_BOOTSTRAP="
    py -3 -c "import sys; print(sys.executable)" >nul 2>&1 && set "PYTHON_BOOTSTRAP=py -3"
    if not defined PYTHON_BOOTSTRAP python -c "import sys; print(sys.executable)" >nul 2>&1 && set "PYTHON_BOOTSTRAP=python"
    if defined PYTHON_BOOTSTRAP (
      echo [准备中] 正在用系统 Python 创建 backend\.venv...
      call %PYTHON_BOOTSTRAP% -m venv "%BACKEND_DIR%\.venv" >>"%LOG_DIR%\dependency-bootstrap.log" 2>&1
      if errorlevel 1 (
        echo [可选增强未就绪] 无法创建 backend\.venv；基础界面仍可启动。
        echo [详情] %LOG_DIR%\dependency-bootstrap.log
      )
    ) else (
      echo [可选增强未就绪] 未找到 Python 3，无法创建 backend\.venv；基础界面仍可启动。
      echo [处理方法] 将完整 Python 3.13 发行版放入 portable\python 后重新运行本脚本。
    )
  )
)

if exist "%BACKEND_DIR%\.venv\Scripts\python.exe" (
  call "%BACKEND_DIR%\tools\bootstrap_media_runtime.bat" %MODE%
  if errorlevel 1 (
    echo [媒体运行时未就绪] 默认 verify 未联网；需要修复时执行 setup_runtime.bat repair。
    echo [详情] %LOG_DIR%\dependency-bootstrap.log
  ) else (
    echo [已就绪] Python 媒体依赖（%MODE%）
  )
) else if /I not "%MODE%"=="repair" (
  echo [离线检查失败] 未找到 backend\.venv，未创建、未联网。需要修复时执行 setup_runtime.bat repair。
)

REM Make sure MySQL is up before the app starts.
call "%APP_DIR%start_mysql.bat"

echo.
echo 完成。运行 start.bat 启动仅监听本机的服务。
exit /b 0