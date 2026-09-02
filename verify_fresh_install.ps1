param(
  [string]$ProjectRoot = $PSScriptRoot,
  [string]$SetupExe = ''
)
$ErrorActionPreference = 'Stop'

function Fail([int]$Code, [string]$Message) {
  Write-Error "[fresh-install:$Code] $Message"
  exit $Code
}
function Require-File([string]$Path, [string]$Label) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Fail 2 "$Label is missing" }
  if ((Get-Item -LiteralPath $Path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { Fail 2 "$Label is a reparse point" }
}
function Pick-Port([int]$Start) {
  for ($port = $Start; $port -lt ($Start + 300); $port++) {
    if (-not (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) { return $port }
  }
  Fail 2 "no free port in range $Start..$($Start + 299)"
}
function Read-Env([string]$Path) {
  $values = @{}
  foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
    if (-not $line.Trim() -or $line.TrimStart().StartsWith('#')) { continue }
    $separator = $line.IndexOf('=')
    if ($separator -gt 0) { $values[$line.Substring(0,$separator)] = $line.Substring($separator + 1) }
  }
  return $values
}
function Stop-IsolatedListener([int]$Port, [string]$InstallRoot) {
  $connections = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
  foreach ($processId in @($connections.OwningProcess | Sort-Object -Unique)) {
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    $path = $process.Path
    $prefix = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\') + '\'
    if (-not $path -or -not ([IO.Path]::GetFullPath($path).StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase))) {
      Fail 8 "port $Port belongs to a process outside the isolated installation"
    }
    Stop-Process -Id $processId -Force -ErrorAction Stop
  }
}
function Remove-VerifiedTree([string]$Path, [string]$Base) {
  if (-not (Test-Path -LiteralPath $Path)) { return }
  $target = [IO.Path]::GetFullPath($Path).TrimEnd('\')
  $allowedBase = [IO.Path]::GetFullPath($Base).TrimEnd('\')
  if (-not $target.StartsWith($allowedBase + '\', [StringComparison]::OrdinalIgnoreCase)) { Fail 9 "cleanup target escaped verification base: $target" }
  Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop
}

$root = (Resolve-Path -LiteralPath $ProjectRoot).Path
if ([string]::IsNullOrWhiteSpace($SetupExe)) {
  $releaseNotesPath = Join-Path $root 'backend\src\main\resources\release-notes.json'
  Require-File $releaseNotesPath 'release-notes.json'
  try {
    $releaseVersion = [string](Get-Content -Raw -LiteralPath $releaseNotesPath -Encoding UTF8 | ConvertFrom-Json).version
  } catch {
    Fail 2 'release-notes.json is not valid JSON'
  }
  if ([string]::IsNullOrWhiteSpace($releaseVersion) -or $releaseVersion -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') {
    Fail 2 'release-notes.json has an invalid version'
  }
  $expectedSetupName = "Mework-Setup-$releaseVersion.exe"
  $candidate = Get-ChildItem -LiteralPath (Join-Path $root 'installer\output') -Filter $expectedSetupName -File -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $candidate) { Fail 2 "$expectedSetupName is missing" }
  $SetupExe = $candidate.FullName
}
Require-File $SetupExe 'Setup EXE'

# Keep the isolated root short: bundled Python packages contain deep paths and
# Windows path-length limits must not make the release verifier fail artificially.
$verifyBase = if (Test-Path -LiteralPath 'D:\') { 'D:\MWV' } else { Join-Path $env:LOCALAPPDATA 'MWV' }
New-Item -ItemType Directory -Force -Path $verifyBase | Out-Null
$installRoot = Join-Path $verifyBase ('mework-fresh-' + [guid]::NewGuid().ToString('N'))
$appData = Join-Path $installRoot 'data'
$installerLog = Join-Path $verifyBase ('installer-' + [guid]::NewGuid().ToString('N') + '.log')
$appPort = Pick-Port 18760
$mysqlPort = Pick-Port 23306
$launcherPid = $null
$verificationSucceeded = $false

try {
  New-Item -ItemType Directory -Force -Path $installRoot | Out-Null
  $setupDir = Split-Path -Parent ([IO.Path]::GetFullPath($SetupExe))
  $installerArgs = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/LOG=$installerLog", "/DIR=$installRoot")
  $installer = Start-Process -FilePath $SetupExe -ArgumentList $installerArgs -WorkingDirectory $setupDir -Wait -PassThru
  if ($installer.ExitCode -ne 0) { Fail 4 "installer exited with $($installer.ExitCode)" }
  foreach ($relative in @('start.bat','ensure_env.ps1','start_mysql.ps1','backend\target\mixcut-delivery.jar','backend\src\main\resources\db\schema.sql','installer\ai-setup-manifest.json')) {
    Require-File (Join-Path $installRoot $relative) "installed $relative"
  }
  foreach ($blocked in @('portable\mysqldata','portable\maven','materials','sample-materials','cache')) {
    if (Test-Path -LiteralPath (Join-Path $installRoot $blocked)) { Fail 6 "blocked release directory was installed: $blocked" }
  }

  $env:APP_DATA_DIR = $appData
  $env:APP_PORT = [string]$appPort
  $env:MYSQL_PORT = [string]$mysqlPort
  $env:APP_BIND_ADDRESS = '127.0.0.1'
  $env:APP_ACCESS_TOKEN = ''
  $env:APP_SKIP_BROWSER = 'true'
  $launcher = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d','/c',(Join-Path $installRoot 'start.bat') -WorkingDirectory $installRoot -PassThru -WindowStyle Hidden
  $launcherPid = $launcher.Id

  $health = $false
  for ($attempt = 0; $attempt -lt 180; $attempt++) {
    Start-Sleep -Seconds 1
    try {
      $response = Invoke-WebRequest -UseBasicParsing -Uri ("http://127.0.0.1:{0}/api/system/env" -f $appPort) -TimeoutSec 2
      if ($response.StatusCode -eq 200) { $health = $true; break }
    } catch { }
  }
  if (-not $health) { Fail 5 'fresh install did not return /api/system/env 200 within 180 seconds' }

  $envPath = Join-Path $installRoot '.env'
  Require-File $envPath 'generated .env'
  $values = Read-Env $envPath
  if ([string]$values.DB_PASSWORD -notmatch '^[0-9a-f]{64}$') { Fail 6 'DB_PASSWORD is not a random 64-character hex value' }
  if ([string]$values.MYSQL_ROOT_PASSWORD -notmatch '^[0-9a-f]{64}$') { Fail 6 'MYSQL_ROOT_PASSWORD is not a random 64-character hex value' }
  if ($values.DB_PASSWORD -eq $values.MYSQL_ROOT_PASSWORD) { Fail 6 'database application and root passwords must differ' }
  try { $masterBytes = [Convert]::FromBase64String([string]$values.APP_MASTER_KEY) } catch { Fail 6 'APP_MASTER_KEY is not valid Base64' }
  if ($masterBytes.Length -ne 48) { Fail 6 'APP_MASTER_KEY must decode to 48 bytes' }
  foreach ($name in @('APP_ACCESS_TOKEN','APP_FREESOUND_API_KEY','APP_PIXABAY_API_KEY','APP_PEXELS_API_KEY','APP_UNSPLASH_API_KEY')) {
    if (-not [string]::IsNullOrWhiteSpace([string]$values[$name])) { Fail 6 "$name must be empty on a fresh install" }
  }

  $mysql = Join-Path $installRoot 'portable\mysql\bin\mysql.exe'
  $queryOutput = Join-Path $installRoot 'fresh-counts.txt'
  $queryError = Join-Path $installRoot 'fresh-counts.err.txt'
  $query = 'SELECT (SELECT COUNT(*) FROM material),(SELECT COUNT(*) FROM ai_provider),(SELECT COUNT(*) FROM job),(SELECT COUNT(*) FROM job_output),(SELECT COUNT(*) FROM crawl_job);'
  $mysqlArgs = @('--protocol=TCP','-h','127.0.0.1','-P',[string]$mysqlPort,'-u',[string]$values.DB_USERNAME,'-D','ai_mix_video','-N','-B','-e',$query)
  $env:MYSQL_PWD = [string]$values.DB_PASSWORD
  try {
    & $mysql @mysqlArgs 1> $queryOutput 2> $queryError
    $dbExitCode = $LASTEXITCODE
  } finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
  if ($dbExitCode -ne 0) {
    $detail = (Get-Content -Raw -LiteralPath $queryError -ErrorAction SilentlyContinue).Trim() -replace '\s+', ' '
    if ($detail.Length -gt 300) { $detail = $detail.Substring(0, 300) }
    if (-not $detail) { $detail = 'mysql returned a non-zero exit code without an error message' }
    Fail 7 "fresh database count query failed: $detail"
  }
  $counts = (Get-Content -Raw -LiteralPath $queryOutput).Trim() -split "\s+"
  if ($counts.Count -ne 5 -or @($counts | Where-Object { $_ -ne '0' }).Count) { Fail 7 "fresh user tables are not empty: $($counts -join ',')" }

  $verificationSucceeded = $true
  Write-Host "[fresh-install:0] Windows install, random secrets, empty user database and local launch passed on ports $appPort/$mysqlPort"
} finally {
  try { Stop-IsolatedListener $appPort $installRoot } catch { if ($_.Exception.Message) { Write-Warning $_.Exception.Message } }
  try { Stop-IsolatedListener $mysqlPort $installRoot } catch { if ($_.Exception.Message) { Write-Warning $_.Exception.Message } }
  if ($launcherPid) {
    $launcher = Get-Process -Id $launcherPid -ErrorAction SilentlyContinue
    if ($launcher) { Stop-Process -Id $launcherPid -Force -ErrorAction SilentlyContinue }
  }
  Remove-Item Env:APP_DATA_DIR,Env:APP_PORT,Env:MYSQL_PORT,Env:APP_BIND_ADDRESS,Env:APP_ACCESS_TOKEN,Env:APP_SKIP_BROWSER -ErrorAction SilentlyContinue
  $uninstaller = Get-ChildItem -LiteralPath $installRoot -Filter 'unins*.exe' -File -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($uninstaller) { Start-Process -FilePath $uninstaller.FullName -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART' -Wait -WindowStyle Hidden | Out-Null }
  Remove-VerifiedTree $installRoot $verifyBase
  if ($verificationSucceeded) {
    Remove-Item -LiteralPath $installerLog -Force -ErrorAction SilentlyContinue
  } else {
    Write-Warning "Fresh-install diagnostic log retained at $installerLog"
  }
}
