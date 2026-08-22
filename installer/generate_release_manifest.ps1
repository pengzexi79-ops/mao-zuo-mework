param(
  [Parameter(Mandatory = $true)][string]$Root,
  [Parameter(Mandatory = $true)][string]$OutputPath,
  [string]$Version
)
$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $Root).Path
$releaseNotesPath = Join-Path $rootPath 'backend\src\main\resources\release-notes.json'
if (-not (Test-Path -LiteralPath $releaseNotesPath)) { throw 'Missing release-notes.json' }
$notes = Get-Content -Raw -LiteralPath $releaseNotesPath -Encoding UTF8 | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($Version)) { $Version = [string]$notes.version }
if ($Version -ne [string]$notes.version) { throw "Version mismatch: $Version vs $($notes.version)" }
$targets = @(
  @{ Path = 'backend\src\main\resources\release-notes.json'; Component = 'release-notes'; Purpose = 'application release metadata'; Required = $true },
  @{ Path = 'backend\src\main\resources\capabilities.json'; Component = 'capabilities'; Purpose = 'capability allowlist and repair policy'; Required = $true },
  @{ Path = 'backend\target\mixcut-delivery.jar'; Component = 'application'; Purpose = 'Spring Boot delivery JAR'; Required = $true },
  @{ Path = 'portable\jdk-17\bin\java.exe'; Component = 'jdk'; Purpose = 'Java 17 runtime'; Required = $true },
  @{ Path = 'portable\mysql\bin\mysqld.exe'; Component = 'mysql'; Purpose = 'MySQL server'; Required = $true },
  @{ Path = 'portable\ffmpeg\bin\ffmpeg.exe'; Component = 'ffmpeg'; Purpose = 'media renderer'; Required = $true },
  @{ Path = 'portable\ffmpeg\bin\ffprobe.exe'; Component = 'ffprobe'; Purpose = 'media probe'; Required = $true },
  @{ Path = 'portable\python\python.exe'; Component = 'python'; Purpose = 'bundled Python runtime'; Required = $true },
  @{ Path = 'portable\whisper\Release\whisper-cli.exe'; Component = 'whisper.cpp'; Purpose = 'local ASR engine'; Required = $true },
  @{ Path = 'portable\imagemagick\magick.exe'; Component = 'imagemagick'; Purpose = 'image processing'; Required = $true },
  @{ Path = 'portable\whisper-models\ggml-small.bin'; Component = 'whisper-model'; Purpose = 'local whisper.cpp model'; Required = $true },
  @{ Path = 'backend\.venv\Scripts\python.exe'; Component = 'venv'; Purpose = 'application Python environment'; Required = $true },
  @{ Path = 'backend\.venv\pyvenv.cfg'; Component = 'venv-config'; Purpose = 'relocatable venv configuration'; Required = $true }
)
$entries = foreach ($target in $targets) {
  $full = Join-Path $rootPath $target.Path
  $exists = Test-Path -LiteralPath $full -PathType Leaf
  if ($target.Required -and -not $exists) { throw "Missing release file: $($target.Path)" }
  if ($exists) {
    $item = Get-Item -LiteralPath $full -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Release file is a junction or symlink: $($target.Path)" }
    $hash = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
    [ordered]@{ path = $target.Path.Replace('\','/'); component = $target.Component; purpose = $target.Purpose; required = [bool]$target.Required; sizeBytes = [int64]$item.Length; sha256 = $hash }
  }
}
$manifest = [ordered]@{
  schemaVersion = 1
  manifestVersion = '1.0.0'
  generatedAt = [DateTime]::UtcNow.ToString('o')
  productVersion = $Version
  source = 'Mework build_installer.bat'
  entries = @($entries)
  sbom = [ordered]@{
    format = 'minimal-spdx-like'
    components = @('JDK 17','MySQL 8','FFmpeg/FFprobe','Python 3.13','whisper.cpp','ImageMagick','requirements-windows.txt','capabilities.approvedPip')
  }
}
$parent = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Host "Release manifest generated: $OutputPath (version $Version, entries $($entries.Count))"
