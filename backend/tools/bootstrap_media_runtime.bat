@echo off
setlocal EnableExtensions

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
  echo [错误] 缺少应用内 Python 运行时：%PYTHON%
  exit /b 1
)

REM Self-heal: a venv bundled from another PC has pyvenv.cfg.home pointing at that PC's
REM base interpreter, so its python.exe cannot start elsewhere. Rebind home to the
REM bundled portable\python (full CPython distribution shipped inside the app) so the
REM venv and all installed media packages work on any computer without reinstalling.
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
  echo [错误] 缺少默认媒体依赖清单。
  exit /b 1
)

"%PYTHON%" -c "import edge_tts, faster_whisper, rapidocr_onnxruntime, ffmpeg_normalize" >>"%LOG_FILE%" 2>&1
if not errorlevel 1 (
  echo [%date% %time%] OK: default media runtime already ready>>"%LOG_FILE%"
  exit /b 0
)

"%PYTHON%" -m ensurepip --upgrade >>"%LOG_FILE%" 2>&1
if errorlevel 1 (
  echo [%date% %time%] ERROR: ensurepip failed>>"%LOG_FILE%"
  echo [错误] 应用内 Python 无法初始化 pip，详见 %LOG_FILE%
  exit /b 1
)

"%PYTHON%" -m pip install --disable-pip-version-check --no-input -r "%REQUIREMENTS%" >>"%LOG_FILE%" 2>&1
if errorlevel 1 (
  echo [%date% %time%] ERROR: default media dependency installation failed>>"%LOG_FILE%"
  echo [错误] 默认媒体依赖安装失败。请检查网络后重试，详见 %LOG_FILE%
  exit /b 1
)

"%PYTHON%" -c "import edge_tts, faster_whisper, rapidocr_onnxruntime, ffmpeg_normalize" >>"%LOG_FILE%" 2>&1
if errorlevel 1 (
  echo [%date% %time%] ERROR: default media dependency verification failed>>"%LOG_FILE%"
  echo [错误] 默认媒体依赖校验失败，详见 %LOG_FILE%
  exit /b 1
)

echo [%date% %time%] OK: default media runtime ready>>"%LOG_FILE%"
exit /b 0
