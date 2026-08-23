param(
  [string]$ProjectRoot = $PSScriptRoot,
  [string]$SetupExe = ''
)
$ErrorActionPreference = 'Stop'

function Skip([string]$Message) {
  Write-Host "[fresh-install:skipped] $Message"
  exit 0
}
function Fail([int]$Code, [string]$Message) {
  Write-Error "[fresh-install:$Code] $Message"
  exit $Code
}
function RequirePlainFile([string]$Path, [string]$Label) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Skip "$Label is missing" }
  $item = Get-Item -LiteralPath $Path -Force
  if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { Fail 2 "$Label is a reparse point" }
  return $item
}
function PickPort([int]$Start) {
  for ($port = $Start; $port -lt ($Start + 200); $port++) {
    $occupied = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    if (-not $occupied) { return $port }
  }
  Fail 2 "no free port in range $Start..$($Start + 199)"
}

$root = (Resolve-Path -LiteralPath $ProjectRoot).Path
$exe = $SetupExe
if ([string]::IsNullOrWhiteSpace($exe)) {
  $candidates = @(Get-ChildItem -LiteralPath (Join-Path $root 'installer/output') -Filter 'Mework-Setup-*.exe' -File -ErrorAction SilentlyContinue)
  if ($candidates.Count -eq 0) { Skip 'Setup EXE is not available; static gate does not pretend to be an install test' }
  $exe = $candidates[0].FullName
}
RequirePlainFile $exe 'Setup EXE' | Out-Null
$manifest = Join-Path $root 'installer/output/release-manifest.json'
RequirePlainFile $manifest 'release manifest' | Out-Null
foreach ($relative in @('start.bat','start_mysql.bat','setup_runtime.bat','portable/jdk-17/bin/java.exe','portable/mysql/bin/mysqld.exe','portable/ffmpeg/bin/ffmpeg.exe','portable/ffmpeg/bin/ffprobe.exe','backend/.venv/Scripts/python.exe')) {
  RequirePlainFile (Join-Path $root $relative) $relative | Out-Null
}
try { $release = Get-Content -Raw -LiteralPath $manifest | ConvertFrom-Json } catch { Fail 1 'release manifest is invalid JSON' }
if (-not $release.files -and -not $release.components) { Fail 1 'release manifest has no component inventory' }

$installRoot = Join-Path ([IO.Path]::GetTempPath()) ('mework-fresh-install-' + [guid]::NewGuid().ToString('N'))
$appData = Join-Path $installRoot 'data'
$appPort = PickPort 18760
$mysqlPort = PickPort 23306
$started = @()
try {
  New-Item -ItemType Directory -Force -Path $installRoot | Out-Null
  $args = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /DIR=`"$installRoot`""
  $installer = Start-Process -FilePath $exe -ArgumentList $args -Wait -PassThru
  if ($installer.ExitCode -ne 0) { Fail 4 "installer exited with $($installer.ExitCode)" }
  $start = Join-Path $installRoot 'start.bat'
  RequirePlainFile $start 'installed start.bat' | Out-Null
  $env:APP_DATA_DIR = $appData
  $env:APP_PORT = [string]$appPort
  $env:MYSQL_PORT = [string]$mysqlPort
  $env:APP_BIND_ADDRESS = '127.0.0.1'
  $env:APP_ACCESS_TOKEN = ''
  $proc = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d','/c',$start -WorkingDirectory $installRoot -PassThru -WindowStyle Hidden
  $started += $proc.Id
  $health = $false
  for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 1
    try {
      $response = Invoke-WebRequest -UseBasicParsing -Uri ("http://127.0.0.1:{0}/api/system/env" -f $appPort) -TimeoutSec 2
      if ($response.StatusCode -eq 200) { $health = $true; break }
    } catch { }
  }
  if (-not $health) { Fail 5 'fresh install did not return /api/system/env 200' }
  Write-Host "[fresh-install:0] fresh install launched on isolated app port $appPort and mysql port $mysqlPort"
} finally {
  foreach ($pid in $started) {
    $process = Get-Process -Id $pid -ErrorAction SilentlyContinue
    if ($process) { Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue }
  }
  Remove-Item Env:APP_DATA_DIR,Env:APP_PORT,Env:MYSQL_PORT,Env:APP_BIND_ADDRESS,Env:APP_ACCESS_TOKEN -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $installRoot) { Remove-Item -LiteralPath $installRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
