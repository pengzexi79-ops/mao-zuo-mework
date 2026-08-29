@echo off
chcp 65001 >nul
setlocal EnableExtensions

set "APP_DIR=%~dp0"
set "APP_ROOT=%APP_DIR:~0,-1%"
powershell -NoProfile -ExecutionPolicy Bypass -File "%APP_DIR%start_mysql.ps1" -Root "%APP_ROOT%"
exit /b %ERRORLEVEL%
