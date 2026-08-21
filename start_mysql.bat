@echo off
chcp 65001 >nul
setlocal EnableExtensions

REM Ensure MySQL is reachable on 127.0.0.1:<MYSQL_PORT>. Prefer an already-running
REM MySQL (system service or dev instance); otherwise start the bundled one.
set "APP_DIR=%~dp0"

REM Load local configuration without printing secrets (so MYSQL_PORT from .env works).
if exist "%APP_DIR%.env" (
  for /f "usebackq tokens=1,* delims==" %%A in ("%APP_DIR%.env") do (
    if not "%%A"=="" if not "%%A:~0,1"=="#" set "%%A=%%B"
  )
)
if not defined MYSQL_PORT set "MYSQL_PORT=3306"

set "MYSQLD=%APP_DIR%portable\mysql\bin\mysqld.exe"
set "DATA_DIR=%APP_DIR%portable\mysqldata"
set "MYSQL_UP="

for /f "tokens=5" %%P in ('netstat -ano -p TCP ^| findstr /R /C:":%MYSQL_PORT% .*LISTENING"') do set "MYSQL_UP=1"
if defined MYSQL_UP (
  echo [已就绪] MySQL 已在 127.0.0.1:%MYSQL_PORT% 运行
  exit /b 0
)

if not exist "%MYSQLD%" (
  echo [需处理] 未找到 MySQL：请安装 MySQL 8 服务，或将便携 MySQL 放入 portable\mysql
  exit /b 1
)
if not exist "%DATA_DIR%\ai_mix_video" (
  echo [需处理] 便携 MySQL 数据目录未初始化：portable\mysqldata 缺少 ai_mix_video 库
  exit /b 1
)

if not exist "%APP_DIR%data\logs" mkdir "%APP_DIR%data\logs"
set "MY_INI=%APP_DIR%data\mysql.ini"
> "%MY_INI%" echo [mysqld]
>>"%MY_INI%" echo basedir="%APP_DIR%portable\mysql"
>>"%MY_INI%" echo datadir="%DATA_DIR%"
>>"%MY_INI%" echo port=%MYSQL_PORT%
>>"%MY_INI%" echo bind-address=127.0.0.1
>>"%MY_INI%" echo log-error="%APP_DIR%data\logs\mysql.log"
>>"%MY_INI%" echo character-set-server=utf8mb4

echo [启动中] 正在启动便携 MySQL 8（端口 %MYSQL_PORT%）...
start "Mework MySQL" /MIN "%MYSQLD%" --defaults-file="%MY_INI%"
if errorlevel 1 (
  echo [需处理] 便携 MySQL 启动失败，详见 data\logs\mysql.log
  exit /b 1
)

set "READY="
for /l %%i in (1,1,60) do (
  for /f "tokens=5" %%P in ('netstat -ano -p TCP ^| findstr /R /C:":%MYSQL_PORT% .*LISTENING"') do set "READY=1"
  if defined READY goto mysql_ready
  timeout /t 1 /nobreak >nul
)
echo [需处理] 便携 MySQL %MYSQL_PORT% 60 秒内未就绪，详见 data\logs\mysql.log
exit /b 1

:mysql_ready
echo [已就绪] 便携 MySQL 已在 127.0.0.1:%MYSQL_PORT% 运行
exit /b 0