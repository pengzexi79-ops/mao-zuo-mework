@echo off
setlocal EnableExtensions

set "APP_DIR=%~dp0"
set "APP_DATA_DIR=%APP_DIR%data"
set "JAVA_BIN=%APP_DIR%portable\jdk-17\bin\java.exe"
set "JAR=%APP_DIR%backend\target\mixcut-current.jar"
set "LOG_DIR=%APP_DATA_DIR%\logs"
set "PORT=8762"

rem Resolve relative configuration such as .env from the application root.
cd /d "%APP_DIR%"

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%JAVA_BIN%" (
  echo ERROR: bundled Java 17 is missing: %JAVA_BIN%
  exit /b 1
)
if not exist "%JAR%" (
  echo ERROR: D-drive application jar is missing: %JAR%
  exit /b 1
)

call "%APP_DIR%start_mysql.bat"
if errorlevel 1 (
  echo ERROR: MySQL is not ready. See %LOG_DIR%\mysql.log
  exit /b 1
)

echo Mework D-drive instance: http://127.0.0.1:%PORT%
echo Data: %APP_DATA_DIR%
"%JAVA_BIN%" -Dfile.encoding=UTF-8 -jar "%JAR%" ^
  --server.port=%PORT% ^
  --server.address=127.0.0.1 ^
  --app.data-dir="%APP_DATA_DIR%" ^
  --app.materials-dir="%APP_DATA_DIR%\materials" ^
  --app.output-dir="D:\猫作" ^
  --app.cache-dir="%APP_DATA_DIR%\cache" ^
  --app.local-python="%APP_DIR%backend\.venv\Scripts\python.exe" ^
  --app.ffmpeg="%APP_DIR%portable\ffmpeg\bin\ffmpeg.exe" ^
  --app.ffprobe="%APP_DIR%portable\ffmpeg\bin\ffprobe.exe" ^
  >>"%LOG_DIR%\start-d.log" 2>&1

exit /b %ERRORLEVEL%

