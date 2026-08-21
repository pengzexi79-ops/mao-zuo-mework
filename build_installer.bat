@echo off
chcp 65001 >nul
setlocal
set "APP_DIR=%~dp0"
echo ============================================================
echo   Mework installer build
echo ============================================================

REM 1) 检查 Inno Setup 6
set "ISCC="
if exist "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" set "ISCC=C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
if exist "C:\Program Files\Inno Setup 6\ISCC.exe" set "ISCC=C:\Program Files\Inno Setup 6\ISCC.exe"
if not defined ISCC (
  echo [ERROR] Inno Setup 6 not found: ISCC.exe.
  echo         Install: winget install JRSoftware.InnoSetup
  echo         Website: https://jrsoftware.org/isdl.php
  pause
  exit /b 1
)

REM 2) 准备并验证便携工具链（真实复制到 portable\）
echo [1/4] Prepare portable runtime...
powershell -ExecutionPolicy Bypass -File "%APP_DIR%prepare_portable.ps1" -Copy
if errorlevel 1 (
  echo [ERROR] Portable runtime preparation failed; installer was not built.
  pause
  exit /b 1
)
REM Required delivery files: a release build must fail rather than silently omit an offline prerequisite.
REM Portable Python is needed to create backend\.venv on a fresh PC; the offline whisper model cache is
REM needed for ASR on machines without network (start.bat pre-seeds data\hf-cache from it).
for %%F in ("%APP_DIR%portable\jdk-17\bin\java.exe" "%APP_DIR%portable\mysql\bin\mysqld.exe" "%APP_DIR%portable\ffmpeg\bin\ffmpeg.exe" "%APP_DIR%portable\ffmpeg\bin\ffprobe.exe" "%APP_DIR%portable\python\python.exe" "%APP_DIR%portable\whisper\Release\whisper-cli.exe" "%APP_DIR%portable\imagemagick\magick.exe" "%APP_DIR%portable\whisper-models\ggml-small.bin" "%APP_DIR%portable\whisper-models\hub\models--Systran--faster-whisper-small\refs\main" "%APP_DIR%backend\.venv\Scripts\python.exe") do (
  if not exist %%~F (
    echo [ERROR] Required delivery file is missing: %%~F
    pause
    exit /b 1
  )
)
"%APP_DIR%backend\.venv\Scripts\python.exe" -c "import edge_tts,faster_whisper,rapidocr_onnxruntime,ffmpeg_normalize,yt_dlp,you_get,gallery_dl,demucs,rembg,auto_editor,cv2" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Bundled Python media runtime is incomplete; installer was not built.
  pause
  exit /b 1
)

REM 3) Build frontend static bundle first; Vite empties old hashed chunks.
echo [2/4] Build frontend static bundle...
pushd "%APP_DIR%frontend"
call npm run build
if errorlevel 1 (
  popd
  echo [ERROR] Frontend build failed; check npm output.
  pause
  exit /b 1
)
popd

REM 4) Build the only delivery JAR, including the freshly built frontend.
echo [3/4] Build backend jar...
pushd "%APP_DIR%backend"
call mvn -f pom.xml clean package -DskipTests -Ddelivery.jar.name=mixcut-delivery
if errorlevel 1 (
  popd
  echo [ERROR] Backend build failed; check Maven output.
  pause
  exit /b 1
)
popd

REM Delivery integrity gate: the JAR must embed the exact current capability manifest and frontend entry.
echo [4/5] Verify delivery artifact...
set "JAR_FILE=%APP_DIR%backend\target\mixcut-delivery.jar"
"%APP_DIR%portable\jdk-17\bin\jar.exe" tf "%JAR_FILE%" | findstr /C:"BOOT-INF/classes/capabilities.json" >nul
if errorlevel 1 (
  echo [ERROR] Delivery JAR is missing capabilities.json.
  pause
  exit /b 1
)
"%APP_DIR%portable\jdk-17\bin\jar.exe" tf "%JAR_FILE%" | findstr /C:"BOOT-INF/classes/static/index.html" >nul
if errorlevel 1 (
  echo [ERROR] Delivery JAR is missing the built frontend entry.
  pause
  exit /b 1
)
set "VERIFY_DIR=%TEMP%\mework-delivery-verify-%RANDOM%"
mkdir "%VERIFY_DIR%" >nul 2>nul
pushd "%VERIFY_DIR%"
"%APP_DIR%portable\jdk-17\bin\jar.exe" xf "%JAR_FILE%" BOOT-INF/classes/capabilities.json
fc /B "BOOT-INF\classes\capabilities.json" "%APP_DIR%backend\src\main\resources\capabilities.json" >nul
set "VERIFY_RESULT=%ERRORLEVEL%"
popd
rmdir /S /Q "%VERIFY_DIR%" >nul 2>nul
if not "%VERIFY_RESULT%"=="0" (
  echo [ERROR] Delivery JAR capability manifest differs from source; rebuild was not accepted.
  pause
  exit /b 1
)

REM 5) Compile installer.
echo [5/5] Compile installer...
set "APP_ROOT=%APP_DIR:~0,-1%"
subst X: /d >nul 2>nul
subst X: "%APP_ROOT%" >nul
set "INNO_SCRIPT=X:\installer\Mework.iss"
"%ISCC%" "%INNO_SCRIPT%"
set "INNO_RESULT=%ERRORLEVEL%"
subst X: /d >nul 2>nul
if not "%INNO_RESULT%"=="0" (
  echo [ERROR] Installer compile failed.
  pause
  exit /b %INNO_RESULT%
)

echo ============================================================
echo   Done: installer\output\Mework-Setup-*.exe
echo ============================================================
pause