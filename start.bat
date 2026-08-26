@echo off
setlocal EnableExtensions

set "APP_DIR=%~dp0"
set "BACKEND_DIR=%APP_DIR%backend"
set "PORT=8760"

REM Create a minimal .env with bundled-MySQL defaults when none exists.
call "%APP_DIR%ensure_env.bat"

REM Load local configuration without printing secrets.
if exist "%APP_DIR%.env" (
  for /f "usebackq tokens=1,* delims==" %%A in ("%APP_DIR%.env") do (
    if not "%%A"=="" if not "%%A:~0,1"=="#" set "%%A=%%B"
  )
)
if defined APP_PORT set "PORT=%APP_PORT%"

if not defined APP_DATA_DIR set "APP_DATA_DIR=%APP_DIR%data"
if defined APP_DATA_DIR if not exist "%APP_DATA_DIR%" set "APP_DATA_DIR=%APP_DIR%data"
if defined APP_MATERIALS_DIR if not exist "%APP_MATERIALS_DIR%" set "APP_MATERIALS_DIR=%APP_DATA_DIR%materials"
if defined APP_OUTPUT_DIR if not exist "%APP_OUTPUT_DIR%" set "APP_OUTPUT_DIR=%APP_DATA_DIR%output"
if defined APP_CACHE_DIR if not exist "%APP_CACHE_DIR%" set "APP_CACHE_DIR=%APP_DATA_DIR%cache"
if defined APP_LOCAL_PYTHON if not exist "%APP_LOCAL_PYTHON%" set "APP_LOCAL_PYTHON=%BACKEND_DIR%\.venv\Scripts\python.exe"
if defined APP_FFMPEG if not exist "%APP_FFMPEG%" if exist "%APP_DIR%portable\ffmpeg\bin\ffmpeg.exe" set "APP_FFMPEG=%APP_DIR%portable\ffmpeg\bin\ffmpeg.exe"
if defined APP_FFPROBE if not exist "%APP_FFPROBE%" if exist "%APP_DIR%portable\ffmpeg\bin\ffprobe.exe" set "APP_FFPROBE=%APP_DIR%portable\ffmpeg\bin\ffprobe.exe"
if not defined APP_BIND_ADDRESS set "APP_BIND_ADDRESS=127.0.0.1"
REM The browser cannot open 0.0.0.0 / :: / *; always show a real reachable address.
set "BROWSER_ADDR=%APP_BIND_ADDRESS%"
if "%BROWSER_ADDR%"=="0.0.0.0" set "BROWSER_ADDR=127.0.0.1"
if "%BROWSER_ADDR%"=="::" set "BROWSER_ADDR=127.0.0.1"
if "%BROWSER_ADDR%"=="*" set "BROWSER_ADDR=127.0.0.1"
if not exist "%APP_DATA_DIR%\logs" mkdir "%APP_DATA_DIR%\logs"

REM Offline speech-to-text models: use an app-local HF cache pre-seeded from the bundle,
REM so ASR works on a fresh PC without downloading (faster-whisper "small" is ~460 MB).
if not defined HF_HOME set "HF_HOME=%APP_DATA_DIR%\hf-cache"
if exist "%APP_DIR%portable\whisper-models\hub" if not exist "%HF_HOME%\hub" (
  robocopy "%APP_DIR%portable\whisper-models\hub" "%HF_HOME%\hub" /E /NFL /NDL /NJH /NJS /NP >nul
  if errorlevel 8 echo WARN: failed to pre-seed offline whisper models
)
REM Surface a missing offline ASR model cache right away instead of failing on the first transcription.
if not exist "%HF_HOME%\hub\models--Systran--faster-whisper-small" (
  echo WARN: offline whisper model cache is missing at %HF_HOME%\hub\models--Systran--faster-whisper-small.
  echo       ASR will download the model on first use, which requires network.
)

if /I not "%APP_BIND_ADDRESS%"=="127.0.0.1" if /I not "%APP_BIND_ADDRESS%"=="localhost" if /I not "%APP_BIND_ADDRESS%"=="::1" (
  if not defined APP_ACCESS_TOKEN (
    echo ERROR: LAN access requires APP_ACCESS_TOKEN in %APP_DIR%.env.
    echo Set a long random token before using APP_BIND_ADDRESS=%APP_BIND_ADDRESS%.
    exit /b 1
  )
  echo LAN mode is enabled with token protection. Local configuration and restart APIs remain PC-only.
  echo If Windows Firewall blocks access, allow inbound TCP port %PORT% on Private networks only.
)

for /f "tokens=5" %%P in ('netstat -ano -p TCP ^| findstr /R /C:":%PORT% .*LISTENING"') do set "PORT_PID=%%P"
if not defined PORT_PID goto port_free
REM An already-running Mework instance on the fixed port is the common "restart" case:
REM detect it by its own command line instead of failing with a bare port-in-use error.
set "IS_OWN="
for /f "usebackq delims=" %%C in (`powershell -NoProfile -Command "$p=Get-CimInstance Win32_Process -Filter 'ProcessId=%PORT_PID%' -ErrorAction SilentlyContinue; if($p -and $p.CommandLine -match 'mixcut'){'OWN'}"`) do set "IS_OWN=%%C"
if not defined IS_OWN (
  echo ERROR: port %PORT% is already in use by PID %PORT_PID%.
  echo Close the existing Mework process or change the port before starting again.
  exit /b 1
)
REM Verify the existing instance actually serves the backend before reporting success:
REM a half-finished restart or a stale process must never be misreported as "started".
set "HEALTH_CODE="
for /f "usebackq delims=" %%H in (`powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:%PORT%/api/system/env' -TimeoutSec 8; [int]$r.StatusCode } catch { '' }"`) do set "HEALTH_CODE=%%H"
if "%HEALTH_CODE%"=="200" (
  echo Mework is already running and healthy (PID %PORT_PID% on port %PORT%; /api/system/env 200).
  echo Close the existing Mework process first if you intended to restart it.
  exit /b 0
)
echo WARN: port %PORT% is occupied by PID %PORT_PID% but /api/system/env did not return 200 (health="%HEALTH_CODE%").
echo       This is usually a stale or half-finished instance. Restart it cleanly:
echo       taskkill /F /PID %PORT_PID%   then re-run start.bat
exit /b 1
:port_free

REM mixcut-current.jar is rebuilt alongside a running legacy delivery jar, so Windows file locks
REM never leave the next normal restart on an old application build.
set "JAR=%BACKEND_DIR%\target\mixcut-current.jar"
if not exist "%JAR%" set "JAR=%BACKEND_DIR%\target\mixcut-delivery.jar"
if not exist "%JAR%" set "JAR=%BACKEND_DIR%\target\mixcut.jar"
if not exist "%JAR%" (
  echo ERROR: backend jar is missing: %BACKEND_DIR%\target\mixcut-current.jar, mixcut-delivery.jar, or mixcut.jar
  exit /b 1
)
REM Prefer the bundled JDK 17 so the app runs identically on any PC regardless of JAVA_HOME;
REM fall back to JAVA_HOME then PATH.
set "JAVA_BIN="
if exist "%APP_DIR%portable\jdk-17\bin\java.exe" set "JAVA_BIN=%APP_DIR%portable\jdk-17\bin\java.exe"
if not defined JAVA_BIN if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_BIN set "JAVA_BIN=java"

REM Validate the selected Java is 17 or newer; Java 8/11 cannot boot this Spring Boot 3 jar.
set "JAVA_VER_FILE=%APP_DATA_DIR%\logs\java-version-check.txt"
"%JAVA_BIN%" -version > "%JAVA_VER_FILE%" 2>&1
if errorlevel 1 (
  echo ERROR: no working Java runtime found via "%JAVA_BIN%".
  exit /b 1
)
set "JAVA_FULL="
for /f "tokens=3" %%V in ('findstr /C:"version" "%JAVA_VER_FILE%"') do set "JAVA_FULL=%%~V"
del "%JAVA_VER_FILE%" >nul 2>&1
if not defined JAVA_FULL (
  echo ERROR: could not determine the Java version. Java 17 or newer is required.
  exit /b 1
)
set "JAVA_MAJOR="
for /f "tokens=1,2 delims=." %%A in ("%JAVA_FULL%") do (
  if "%%A"=="1" (set "JAVA_MAJOR=%%B") else (set "JAVA_MAJOR=%%A")
)
if %JAVA_MAJOR% LSS 17 (
  echo ERROR: Java 17 or newer is required, but found Java %JAVA_FULL%.
  echo Install a JDK 17+ or ship portable\jdk-17 alongside the app, then retry.
  exit /b 1
)
REM A Maven thin jar can appear if Windows locks the running artifact during a rebuild.
REM Verify Spring Boot's launcher metadata before attempting to start it.
set "JAR_TOOL=jar"
if exist "%APP_DIR%portable\jdk-17\bin\jar.exe" set "JAR_TOOL=%APP_DIR%portable\jdk-17\bin\jar.exe"
if exist "%JAVA_HOME%\bin\jar.exe" set "JAR_TOOL=%JAVA_HOME%\bin\jar.exe"
"%JAR_TOOL%" tf "%JAR%" | findstr /C:"org/springframework/boot/loader/launch/JarLauncher.class" >nul 2>&1
if errorlevel 1 (
  echo ERROR: %JAR% is not a complete Spring Boot application jar.
  echo Stop Mework before rebuilding, then run: cd backend ^&^& mvn package -Ddelivery.jar.name=mixcut-delivery
  exit /b 1
)
REM The bundled web UI must be inside the jar, otherwise the browser shows a blank page.
"%JAR_TOOL%" tf "%JAR%" | findstr /C:"BOOT-INF/classes/static/index.html" >nul 2>&1
if errorlevel 1 (
  echo ERROR: %JAR% is missing the bundled web UI BOOT-INF/classes/static/index.html
  echo Rebuild the frontend then repackage the backend jar.
  exit /b 1
)

REM Make sure MySQL is up (bundled or existing) before launching the backend.
call "%APP_DIR%start_mysql.bat"
if errorlevel 1 (
  echo ERROR: MySQL is required to run Mework. Start a MySQL 8 instance on 127.0.0.1:3306 and retry.
  exit /b 1
)

if not defined APP_LOCAL_PYTHON set "APP_LOCAL_PYTHON=%BACKEND_DIR%\.venv\Scripts\python.exe"
if not defined APP_FFMPEG if exist "%APP_DIR%portable\ffmpeg\bin\ffmpeg.exe" set "APP_FFMPEG=%APP_DIR%portable\ffmpeg\bin\ffmpeg.exe"
if not defined APP_FFPROBE if exist "%APP_DIR%portable\ffmpeg\bin\ffprobe.exe" set "APP_FFPROBE=%APP_DIR%portable\ffmpeg\bin\ffprobe.exe"
if not defined APP_FFMPEG set "APP_FFMPEG=ffmpeg"
if not defined APP_FFPROBE set "APP_FFPROBE=ffprobe"
set "PATH=%BACKEND_DIR%\.venv\Scripts;%APP_DIR%portable\ffmpeg\bin;%PATH%"

call "%BACKEND_DIR%\tools\bootstrap_media_runtime.bat"
if errorlevel 1 echo WARN: optional media runtime is unavailable; see %APP_DATA_DIR%\logs\dependency-bootstrap.log

REM Open the web UI shortly after the backend is up.
start "" /b powershell -NoProfile -WindowStyle Hidden -Command "Start-Sleep -Seconds 10; Start-Process 'http://%BROWSER_ADDR%:%PORT%'"
echo Mework: http://%BROWSER_ADDR%:%PORT%
if /I not "%APP_BIND_ADDRESS%"=="127.0.0.1" if /I not "%APP_BIND_ADDRESS%"=="localhost" if /I not "%APP_BIND_ADDRESS%"=="::1" (
  echo Phone: connect on the same Wi-Fi with http://YOUR-PC-LAN-IP:%PORT%/?access_token=YOUR_APP_ACCESS_TOKEN
)
echo Data: %APP_DATA_DIR%
"%JAVA_BIN%" -Dfile.encoding=UTF-8 -jar "%JAR%" --server.port=%PORT% --server.address=%APP_BIND_ADDRESS% --app.data-dir="%APP_DATA_DIR%" >>"%APP_DATA_DIR%\logs\start.log" 2>&1
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
  echo ERROR: Mework stopped with exit code %EXIT_CODE%. See %APP_DATA_DIR%\logs\start.log
)
exit /b %EXIT_CODE%