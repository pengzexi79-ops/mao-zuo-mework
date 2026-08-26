@echo off
setlocal EnableExtensions

set "MODE=%~1"
if not defined MODE set "MODE=verify"
if /I not "%MODE%"=="verify" if /I not "%MODE%"=="repair" (
  echo 用法：bootstrap_media_runtime.bat [verify^|repair]
  exit /b 2
)

set "ROOT_DIR=%~dp0..\.."
for %%I in ("%ROOT_DIR%") do set "ROOT_DIR=%%~fI"
set "PYTHON=%ROOT_DIR%\backend\.venv\Scripts\python.exe"
set "REQUIREMENTS=%ROOT_DIR%\backend\requirements-windows.txt"
if not defined APP_DATA_DIR set "APP_DATA_DIR=%ROOT_DIR%\data"
set "LOG_DIR=%APP_DATA_DIR%\logs"
set "LOG_FILE=%LOG_DIR%\dependency-bootstrap.log"
set "PATH=%ROOT_DIR%\backend\.venv\Scripts;%PATH%"

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%PYTHON%" (
  echo [%date% %time%] ERROR: missing bundled Python: %PYTHON%>>"%LOG_FILE%"
  echo [离线检查失败] 缺少应用内 Python 运行时，未联网。需要修复时执行：setup_runtime.bat repair
  exit /b 1
)

REM A copied venv may retain a build-machine home. Rebind only to the bundled interpreter.
set "PYVENV_CFG=%ROOT_DIR%\backend\.venv\pyvenv.cfg"
if exist "%ROOT_DIR%\portable\python\python.exe" (
  "%PYTHON%" -c "import sys" >nul 2>&1
  if errorlevel 1 (
    echo [%date% %time%] REPAIR: rebinding venv home to bundled portable python>>"%LOG_FILE%"
    > "%PYVENV_CFG%" echo(home = %ROOT_DIR%\portable\python
    >>"%PYVENV_CFG%" echo(implementation = CPython
    >>"%PYVENV_CFG%" echo(version_info = 3.13.5
    >>"%PYVENV_CFG%" echo(include-system-site-packages = false
  )
)
if not exist "%REQUIREMENTS%" (
  echo [%date% %time%] ERROR: missing requirements file: %REQUIREMENTS%>>"%LOG_FILE%"
  echo [离线检查失败] 缺少固定版本媒体依赖清单，未联网。
  exit /b 1
)

"%PYTHON%" -c "import edge_tts, faster_whisper, rapidocr_onnxruntime, ffmpeg_normalize" >>"%LOG_FILE%" 2>&1
if not errorlevel 1 (
  echo [%date% %time%] OK: offline media runtime verified>>"%LOG_FILE%"
  echo [离线检查通过] 默认媒体运行时可用。
  exit /b 0
)

if /I not "%MODE%"=="repair" (
  echo [%date% %time%] VERIFY: media imports missing; no network repair attempted>>"%LOG_FILE%"
  echo [离线检查失败] 默认媒体依赖未就绪，未联网安装。
  echo [处理方法] 如需联网修复，请明确执行：setup_runtime.bat repair
  echo [详情] %LOG_FILE%
  exit /b 1
)

 echo [%date% %time%] REPAIR: explicit dependency repair requested>>"%LOG_FILE%"
echo [联网修复] 仅安装 requirements-windows.txt 中的固定版本依赖；详见 %LOG_FILE%
"%PYTHON%" -m ensurepip --upgrade >>"%LOG_FILE%" 2>&1
if errorlevel 1 (
  echo [%date% %time%] ERROR: ensurepip failed>>"%LOG_FILE%"
  echo [修复失败] Python 无法初始化 pip，详见 %LOG_FILE%
  exit /b 1
)

"%PYTHON%" -m pip install --disable-pip-version-check --no-input -r "%REQUIREMENTS%" >>"%LOG_FILE%" 2>&1
if errorlevel 1 (
  echo [%date% %time%] ERROR: default media dependency installation failed>>"%LOG_FILE%"
  echo [修复失败] 固定版本媒体依赖安装失败，请检查网络后重试，详见 %LOG_FILE%
  exit /b 1
)

"%PYTHON%" -c "import edge_tts, faster_whisper, rapidocr_onnxruntime, ffmpeg_normalize" >>"%LOG_FILE%" 2>&1
if errorlevel 1 (
  echo [%date% %time%] ERROR: default media dependency verification failed>>"%LOG_FILE%"
  echo [修复失败] 安装后验证仍未通过，详见 %LOG_FILE%
  exit /b 1
)

echo [%date% %time%] OK: repaired media runtime verified>>"%LOG_FILE%"
echo [修复完成] 默认媒体运行时已就绪。
exit /b 0
