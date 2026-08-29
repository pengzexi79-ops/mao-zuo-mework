param(
  [Parameter(Mandatory = $true)][string]$Root,
  [Parameter(Mandatory = $true)][string]$OutputPath,
  [string]$Version
)
$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $Root).Path
$notesPath = Join-Path $rootPath 'backend\src\main\resources\release-notes.json'
if (-not (Test-Path -LiteralPath $notesPath -PathType Leaf)) { throw 'Missing release-notes.json' }
$notes = Get-Content -Raw -LiteralPath $notesPath -Encoding UTF8 | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($Version)) { $Version = [string]$notes.version }
if ($Version -ne [string]$notes.version) { throw "Version mismatch: $Version vs $($notes.version)" }

$fileTargets = @(
  @{ Path = 'backend\src\main\resources\release-notes.json'; Component = 'release-notes'; Purpose = 'application release metadata' },
  @{ Path = 'backend\src\main\resources\capabilities.json'; Component = 'capabilities'; Purpose = 'capability allowlist and repair policy' },
  @{ Path = 'backend\src\main\resources\db\schema.sql'; Component = 'database-schema'; Purpose = 'empty first-run database schema' },
  @{ Path = 'backend\target\mixcut-delivery.jar'; Component = 'application'; Purpose = 'Spring Boot delivery JAR' },
  @{ Path = 'start.bat'; Component = 'launcher'; Purpose = 'application launcher' },
  @{ Path = 'ensure_env.bat'; Component = 'local-config-launcher'; Purpose = 'local configuration bootstrap entry' },
  @{ Path = 'ensure_env.ps1'; Component = 'local-config'; Purpose = 'machine-specific secret and port generation' },
  @{ Path = 'start_mysql.bat'; Component = 'mysql-launcher'; Purpose = 'private MySQL bootstrap entry' },
  @{ Path = 'start_mysql.ps1'; Component = 'mysql-bootstrap'; Purpose = 'empty database initialization and credential verification' },
  @{ Path = 'setup_runtime.bat'; Component = 'runtime-setup'; Purpose = 'offline verification and explicit repair' },
  @{ Path = 'backend\requirements-windows.txt'; Component = 'python-requirements'; Purpose = 'pinned Python dependencies' },
  @{ Path = 'backend\tools\bootstrap_media_runtime.bat'; Component = 'runtime-bootstrap'; Purpose = 'media runtime verification' },
  @{ Path = 'backend\tools\media_diagnose.py'; Component = 'media-diagnosis'; Purpose = 'local media diagnosis' },
  @{ Path = 'backend\tools\natural_tts.py'; Component = 'tts-helper'; Purpose = 'local speech helper' },
  @{ Path = 'INSTALLATION_GUIDE.md'; Component = 'installation-guide'; Purpose = 'human installation manual' },
  @{ Path = 'AI_INSTALLATION_GUIDE.md'; Component = 'ai-installation-guide'; Purpose = 'AI-readable installation manual' },
  @{ Path = 'PRIVACY_RELEASE.md'; Component = 'privacy'; Purpose = 'release privacy boundary' },
  @{ Path = 'installer\ai-setup-manifest.json'; Component = 'ai-setup-manifest'; Purpose = 'machine-readable setup contract' }
)

$treeTargets = @(
  @{ Path = 'portable\jdk-17'; Component = 'jdk'; Purpose = 'Java 17 runtime'; Excludes = @() },
  @{ Path = 'portable\mysql'; Component = 'mysql'; Purpose = 'MySQL 8 server and client binaries'; Excludes = @('*/data/*','*.log') },
  @{ Path = 'portable\ffmpeg'; Component = 'ffmpeg'; Purpose = 'FFmpeg and FFprobe'; Excludes = @('*.log') },
  @{ Path = 'portable\python'; Component = 'python'; Purpose = 'Python runtime'; Excludes = @('*/__pycache__/*','*.pyc','*.pyo','*.log') },
  @{ Path = 'portable\whisper'; Component = 'whisper.cpp'; Purpose = 'local ASR engine'; Excludes = @('*.log') },
  @{ Path = 'portable\whisper-models'; Component = 'asr-models'; Purpose = 'offline ASR models'; Excludes = @('*.log') },
  @{ Path = 'portable\imagemagick'; Component = 'imagemagick'; Purpose = 'image processing runtime'; Excludes = @('*.log') },
  @{ Path = 'backend\.venv'; Component = 'python-environment'; Purpose = 'preinstalled media Python environment'; Excludes = @('*/__pycache__/*','*.pyc','*.pyo','*.log') }
)

function Get-IncludedFiles([string]$FullRoot, [string[]]$Excludes) {
  $rootItem = Get-Item -LiteralPath $FullRoot -Force
  if ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Release directory is a junction or symlink: $FullRoot" }
  $allItems = @(Get-ChildItem -LiteralPath $FullRoot -Recurse -Force)
  $linked = @($allItems | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint })
  if ($linked.Count) { throw "Release directory contains a junction or symlink: $($linked[0].FullName)" }
  return @($allItems | Where-Object { -not $_.PSIsContainer } | Where-Object {
    $relative = $_.FullName.Substring($FullRoot.TrimEnd('\').Length).TrimStart('\').Replace('\','/')
    -not (@($Excludes | Where-Object { $relative -like $_ }).Count)
  })
}
function Get-Sha256Hex([string]$Path) {
  $sha = [Security.Cryptography.SHA256]::Create()
  $stream = [IO.File]::OpenRead($Path)
  try {
    return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
  } finally {
    $stream.Dispose()
    $sha.Dispose()
  }
}

function Get-TreeSummary([hashtable]$Target) {
  $fullRoot = Join-Path $rootPath $Target.Path
  if (-not (Test-Path -LiteralPath $fullRoot -PathType Container)) { throw "Missing release directory: $($Target.Path)" }
  $files = @(Get-IncludedFiles $fullRoot $Target.Excludes)
  if (-not $files.Count) { throw "Release directory is empty: $($Target.Path)" }
  $parts = foreach ($file in ($files | Sort-Object FullName)) {
    $relative = $file.FullName.Substring($fullRoot.TrimEnd('\').Length).TrimStart('\').Replace('\','/')
    $hash = Get-Sha256Hex $file.FullName
    "$relative`n$($file.Length)`n$hash`n"
  }
  $sha = [Security.Cryptography.SHA256]::Create()
  try {
    $treeHash = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($parts -join ''))))).Replace('-','').ToLowerInvariant()
  } finally { $sha.Dispose() }
  [ordered]@{
    path = $Target.Path.Replace('\','/')
    type = 'treeSummary'
    component = $Target.Component
    purpose = $Target.Purpose
    fileCount = $files.Count
    sizeBytes = [int64](($files | Measure-Object Length -Sum).Sum)
    sha256 = $treeHash
    excludes = @($Target.Excludes)
  }
}

$entries = foreach ($target in $fileTargets) {
  $full = Join-Path $rootPath $target.Path
  if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "Missing release file: $($target.Path)" }
  $item = Get-Item -LiteralPath $full -Force
  if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Release file is a junction or symlink: $($target.Path)" }
  [ordered]@{
    path = $target.Path.Replace('\','/')
    component = $target.Component
    purpose = $target.Purpose
    sizeBytes = [int64]$item.Length
     sha256 = Get-Sha256Hex $full
  }
}
$runtimeTrees = @($treeTargets | ForEach-Object { Get-TreeSummary $_ })
$manifest = [ordered]@{
  schemaVersion = 3
  manifestVersion = '2.0.0'
  generatedAt = [DateTime]::UtcNow.ToString('o')
  productVersion = $Version
  source = 'Mework build_installer.bat'
  platform = [ordered]@{ os = 'Windows 10/11'; architecture = 'x64' }
  entries = @($entries)
  runtimeTrees = $runtimeTrees
  releasePolicy = [ordered]@{
    allowedRuntimeDirectories = @($treeTargets.Path | ForEach-Object { $_.Replace('\','/') })
    excluded = @('.env','.env.backup-*','portable/mysqldata','portable/maven','data','materials','sample-materials','cache','logs','temp','installer/output')
    firstRunData = '%APP_DATA_DIR%'
    firstRunDatabase = '%APP_DATA_DIR%/mysql'
  }
  sbom = [ordered]@{
    format = 'minimal-spdx-like'
    components = @('JDK 17','MySQL 8','FFmpeg/FFprobe','Python','whisper.cpp','offline ASR models','ImageMagick','pinned Python environment')
  }
}
$parent = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Host "Release manifest generated: $OutputPath (version $Version, files $($entries.Count), trees $($runtimeTrees.Count))"
