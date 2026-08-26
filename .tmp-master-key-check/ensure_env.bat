@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
REM Generate a minimal .env with bundled-MySQL defaults if none exists yet.
set "APP_DIR=%~dp0"
if exist "%APP_DIR%.env" goto ensure_master_key

 echo [准备中] 未找到 .env，正在生成默认配置（绑定便携 MySQL）...
set "line=APP_BIND_ADDRESS=127.0.0.1"
>"%APP_DIR%.env" echo(!line!
set "line=PORT=8760"
>>"%APP_DIR%.env" echo(!line!
set "line=MYSQL_PORT=3306"
>>"%APP_DIR%.env" echo(!line!
set "line=DB_URL=jdbc:mysql://127.0.0.1:3306/ai_mix_video?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_general_ci&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false&rewriteBatchedStatements=true"
>>"%APP_DIR%.env" echo(!line!
set "line=DB_USERNAME=mixcut"
>>"%APP_DIR%.env" echo(!line!
REM Configure this value locally to match the MySQL user on the target machine.
REM Never commit or share a real database password.
set "line=DB_PASSWORD=replace-with-a-local-db-password"
>>"%APP_DIR%.env" echo(!line!

:ensure_master_key
findstr /R /B /C:"APP_MASTER_KEY=" "%APP_DIR%.env" >nul 2>&1
if not errorlevel 1 goto done
for /f "usebackq delims=" %%K in (`powershell -NoProfile -Command "[Convert]::ToBase64String((1..48 ^| ForEach-Object { Get-Random -Maximum 256 }))"`) do set "MASTER_KEY=%%K"
if not defined MASTER_KEY (
  echo [错误] 无法生成本机凭据加密密钥。
  exit /b 1
)
>>"%APP_DIR%.env" echo(APP_MASTER_KEY=!MASTER_KEY!
echo [已就绪] 已生成本机专用凭据加密密钥。

:done
echo [已就绪] .env 已准备完成（DB 用户 mixcut；Provider 密钥将仅加密保存于本机）。
exit /b 0
