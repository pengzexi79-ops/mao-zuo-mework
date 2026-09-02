param([string]$Root = $PSScriptRoot)
$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $Root).Path
$errors = [System.Collections.Generic.List[string]]::new()

function Require-File([string]$Relative) {
  $path = Join-Path $rootPath $Relative
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { $errors.Add("missing: $Relative"); return }
  if ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { $errors.Add("link-not-allowed: $Relative") }
}

$required = @(
  'start.bat','ensure_env.bat','ensure_env.ps1','start_mysql.bat','start_mysql.ps1','setup_runtime.bat','.env.example',
  'INSTALLATION_GUIDE.md','AI_INSTALLATION_GUIDE.md','PRIVACY_RELEASE.md','installer\ai-setup-manifest.json','installer\Mework.ico',
  'backend\src\main\resources\db\schema.sql','backend\target\mixcut-delivery.jar',
  'portable\jdk-17\bin\java.exe','portable\mysql\bin\mysqld.exe','portable\mysql\bin\mysql.exe',
  'portable\python\python.exe','portable\ffmpeg\bin\ffmpeg.exe','portable\ffmpeg\bin\ffprobe.exe',
  'portable\whisper\Release\whisper-cli.exe','portable\whisper-models\ggml-small.bin',
  'portable\imagemagick\magick.exe','backend\.venv\Scripts\python.exe',
  'installer\launcher\output\Mework.exe',
  'installer\launcher\output\Microsoft.Web.WebView2.Core.dll',
  'installer\launcher\output\Microsoft.Web.WebView2.WinForms.dll',
  'installer\launcher\output\WebView2Loader.dll',
  'installer\launcher\output\MicrosoftEdgeWebView2RuntimeInstallerX64.exe'
)
$required | ForEach-Object { Require-File $_ }

$start = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'start.bat')
$ensureEnvBat = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'ensure_env.bat')
$startMysqlBat = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'start_mysql.bat')
$application = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'backend\src\main\resources\application.yml')
if ($start -notmatch 'set "PORT=8760"') { $errors.Add('start.bat missing default PORT') }
if ($start -notmatch 'APP_PORT') { $errors.Add('start.bat does not honor APP_PORT') }
if ($start -notmatch 'APP_SKIP_BROWSER') { $errors.Add('start.bat cannot suppress the browser during isolated verification') }
if ($application -notmatch 'port:\s*\$\{PORT:8760\}') { $errors.Add('application.yml does not honor PORT') }
foreach ($launcher in @(@{ Name = 'ensure_env.bat'; Text = $ensureEnvBat; Script = 'ensure_env.ps1' }, @{ Name = 'start_mysql.bat'; Text = $startMysqlBat; Script = 'start_mysql.ps1' })) {
  if ($launcher.Text -notmatch 'set "APP_ROOT=%APP_DIR:~0,-1%"') { $errors.Add("$($launcher.Name) must remove the trailing directory separator before passing -Root") }
  $expectedInvocation = '-File "%APP_DIR%' + $launcher.Script + '" -Root "%APP_ROOT%"'
  if ($launcher.Text -notmatch [regex]::Escape($expectedInvocation)) { $errors.Add("$($launcher.Name) must pass a normalized APP_ROOT to PowerShell") }
}

$envScript = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'ensure_env.ps1')
foreach ($needle in @('RandomNumberGenerator','MYSQL_ROOT_PASSWORD','APP_MASTER_KEY','Select-Port')) {
  if ($envScript -notmatch [regex]::Escape($needle)) { $errors.Add("ensure_env.ps1 missing $needle") }
}
$example = Get-Content -Raw -LiteralPath (Join-Path $rootPath '.env.example')
foreach ($name in @('DB_PASSWORD','MYSQL_ROOT_PASSWORD','APP_MASTER_KEY','APP_ACCESS_TOKEN','APP_FREESOUND_API_KEY','APP_PIXABAY_API_KEY','APP_PEXELS_API_KEY','APP_UNSPLASH_API_KEY')) {
  if ($example -notmatch "(?m)^$([regex]::Escape($name))=\s*$") { $errors.Add(".env.example must leave $name blank") }
}

$mysqlScript = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'start_mysql.ps1')
foreach ($needle in @('Test-MySqlIdentity','SELECT 1','--initialize-insecure','schema.sql','APP_DATA_DIR')) {
  if ($mysqlScript -notmatch [regex]::Escape($needle)) { $errors.Add("start_mysql.ps1 missing $needle") }
}
if ($mysqlScript -match 'mysqladmin') { $errors.Add('start_mysql.ps1 must verify credentials with mysql SELECT 1, not mysqladmin ping') }
if ($mysqlScript -match '--password') { $errors.Add('start_mysql.ps1 must not place passwords on command lines') }

$iss = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'installer\Mework.iss')
$filesSection = if ($iss -match '(?s)\[Files\](.*?)(?:\r?\n\[|$)') { $Matches[1] } else { '' }
if (-not $filesSection) { $errors.Add('installer has no [Files] section') }
if ($filesSection -match 'Source:\s*"\.\.\\portable\\\*"') { $errors.Add('installer uses forbidden broad portable wildcard') }
foreach ($blocked in @('portable\mysqldata','portable\maven','sample-materials')) {
  if ($filesSection -match [regex]::Escape($blocked)) { $errors.Add("installer includes blocked input: $blocked") }
}
if ($filesSection -match 'Source:\s*"\.\.\\\.env"') { $errors.Add('installer must not include a local .env file') }
foreach ($allowed in @('jdk-17','mysql','ffmpeg','python','whisper','whisper-models','imagemagick')) {
  if ($filesSection -notmatch [regex]::Escape("portable\$allowed\*")) { $errors.Add("installer allowlist missing portable\$allowed") }
}
if ($iss -notmatch 'DefaultDirName=\{code:GetDefaultInstallDir\}' -or $iss -notmatch "Result := 'D:\\Mework'") { $errors.Add('installer does not prefer D:\Mework') }
if ($iss -notmatch 'SetupIconFile=Mework\.ico' -or $iss -notmatch 'IconFilename: "\{app\}\\Mework\.ico"') { $errors.Add('installer does not use the Mework application icon') }
$runSection = if ($iss -match '(?s)\[Run\](.*?)(?:\r?\n\[|$)') { $Matches[1] } else { '' }
if ($runSection -notmatch 'Filename:\s*"\{app\}\\\{#AppExeName\}"') { $errors.Add('installer must launch Mework.exe after installation') }
if ($runSection -match 'Filename:\s*"\{app\}\\start\.bat"') { $errors.Add('installer must not use start.bat as the post-install desktop entry') }

try {
  $releaseVersion = [string](Get-Content -Raw -LiteralPath (Join-Path $rootPath 'backend\src\main\resources\release-notes.json') -Encoding UTF8 | ConvertFrom-Json).version
  $aiManifest = Get-Content -Raw -LiteralPath (Join-Path $rootPath 'installer\ai-setup-manifest.json') -Encoding UTF8 | ConvertFrom-Json
  if ($aiManifest.product.version -ne $releaseVersion) { $errors.Add("ai-setup-manifest version $($aiManifest.product.version) does not match release $releaseVersion") }
  if (-not $aiManifest.privacy.excluded) { $errors.Add('ai-setup-manifest has no privacy exclusions') }
} catch { $errors.Add("ai-setup-manifest is invalid JSON: $($_.Exception.Message)") }

if ($errors.Count) { $errors | ForEach-Object { Write-Error $_ }; exit 1 }
Write-Host 'Windows installer static checks passed.'
