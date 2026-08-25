param([string]$Root = $PSScriptRoot)
$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $Root).Path
$errors = [System.Collections.Generic.List[string]]::new()
function Require-File([string]$relative) {
  $path = Join-Path $rootPath $relative
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { $errors.Add("missing: $relative") }
  elseif ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { $errors.Add("link-not-allowed: $relative") }
}
foreach ($file in @('start.bat','start_mysql.bat','setup_runtime.bat','ensure_env.bat','.env.example','backend\target\mixcut-current.jar','portable\python\python.exe','portable\ffmpeg\bin\ffmpeg.exe','portable\ffmpeg\bin\ffprobe.exe','portable\mysql\bin\mysqld.exe','backend\.venv\Scripts\python.exe')) { Require-File $file }
$start = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'start.bat')
$application = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'backend\src\main\resources\application.yml')
if ($start -notmatch 'set "PORT=8760"') { $errors.Add('start.bat missing default PORT') }
if ($start -notmatch 'APP_PORT') { $errors.Add('start.bat does not honor APP_PORT') }
if ($application -notmatch 'port:\s*\$\{PORT:8760\}') { $errors.Add('application.yml does not honor PORT') }
$mysql = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'start_mysql.bat')
if ($mysql -notmatch 'mysqladmin') { $errors.Add('start_mysql.bat lacks protocol ping') }
if ($mysql -notmatch 'DB_URL') { $errors.Add('start_mysql.bat lacks DB_URL consistency check') }
if ($mysql -notmatch 'MYSQL_PWD=%DB_PASSWORD%') { $errors.Add('start_mysql.bat does not pass the configured database identity to mysqladmin') }
if ($mysql -match '--password|DB_PASSWORD%"?\s+ping') { $errors.Add('start_mysql.bat exposes the database password on the mysqladmin command line') }
$iss = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'installer\Mework.iss')
$runSection = if ($iss -match '(?s)\[Run\](.*?)(?:\r?\n\[|$)') { $Matches[1] } else { '' }
if ($runSection -match 'setup_runtime\.bat' -and $runSection -match 'start\.bat') { $errors.Add('installer must not run setup_runtime and start concurrently') }
$venvCfg = Join-Path $rootPath 'backend\.venv\pyvenv.cfg'
if (Test-Path -LiteralPath $venvCfg -PathType Leaf) {
  $homeLine = (Get-Content -LiteralPath $venvCfg | Where-Object { $_ -match '^home\s*=' } | Select-Object -First 1)
  if ($homeLine -and $homeLine -match 'WorkBuddy|ai-douyin-mixcut') { Write-Warning 'pyvenv.cfg contains a development path; relocation verify must rebind it before use.' }
}
if ($errors.Count) { $errors | ForEach-Object { Write-Error $_ }; exit 1 }
Write-Host 'Windows install compatibility static checks passed.'
