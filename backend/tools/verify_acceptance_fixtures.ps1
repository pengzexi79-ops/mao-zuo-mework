param(
  [string]$ProjectRoot = (Join-Path (Join-Path $PSScriptRoot '..') '..'),
  [string]$ManifestPath = ''
)
$ErrorActionPreference = 'Stop'

function Fail([int]$Code, [string]$Message) {
  Write-Error "[fixture-verify:$Code] $Message"
  exit $Code
}

function Assert-PlainFile([string]$Path, [string]$Label) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Fail 3 "$Label is missing" }
  $item = Get-Item -LiteralPath $Path -Force
  if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { Fail 2 "$Label is a reparse point" }
  return $item
}

function Hash([string]$Path) {
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$root = (Resolve-Path -LiteralPath $ProjectRoot).Path
if ($ManifestPath) {
  $manifest = (Resolve-Path -LiteralPath $ManifestPath).Path
} else {
  $manifest = Join-Path $root 'backend/src/test/resources/acceptance/fixture-manifest.json'
}
Assert-PlainFile $manifest 'fixture manifest' | Out-Null
$ffprobe = Join-Path $root 'portable/ffmpeg/bin/ffprobe.exe'
Assert-PlainFile $ffprobe 'portable ffprobe.exe' | Out-Null
try {
  $data = Get-Content -Raw -LiteralPath $manifest -Encoding UTF8 | ConvertFrom-Json
} catch {
  Fail 1 'manifest is not valid JSON'
}
if ([int]$data.schemaVersion -ne 1) { Fail 1 'unsupported manifest schemaVersion' }
if (-not $data.fixtures -or @($data.fixtures).Count -ne 6) { Fail 1 'manifest must contain exactly six fixtures' }

$acceptanceRoot = [IO.Path]::GetFullPath((Join-Path $root 'backend/src/test/resources/acceptance'))
$separator = [IO.Path]::DirectorySeparatorChar
foreach ($fixture in @($data.fixtures)) {
  $relative = [string]$fixture.relativePath
  if ([IO.Path]::IsPathRooted($relative)) { Fail 2 "$($fixture.id) uses an absolute path" }
  if ($relative -like '*..*') { Fail 2 "$($fixture.id) escapes fixture root" }
  $path = [IO.Path]::GetFullPath((Join-Path $acceptanceRoot $relative))
  if (-not $path.StartsWith($acceptanceRoot + $separator, [StringComparison]::OrdinalIgnoreCase)) { Fail 2 "$($fixture.id) escapes acceptance root" }
  $item = Assert-PlainFile $path ([string]$fixture.id)
  if ([int64]$item.Length -ne [int64]$fixture.sizeBytes) { Fail 3 "$($fixture.id) size mismatch" }
  if ((Hash $path) -ne ([string]$fixture.sha256).ToLowerInvariant()) { Fail 3 "$($fixture.id) SHA256 mismatch" }

  $raw = & $ffprobe -v error -print_format json -show_streams -show_format -- $path 2>&1
  if ($LASTEXITCODE -ne 0) { Fail 4 "ffprobe failed for $($fixture.id)" }
  try {
    $meta = ($raw -join "`n") | ConvertFrom-Json
  } catch {
    Fail 4 "ffprobe JSON invalid for $($fixture.id)"
  }
  $video = @($meta.streams | Where-Object { $_.codec_type -eq 'video' } | Select-Object -First 1)
  $audio = @($meta.streams | Where-Object { $_.codec_type -eq 'audio' } | Select-Object -First 1)
  if ([double]$fixture.durationSec -gt 0 -and [math]::Abs(([double]$meta.format.duration) - [double]$fixture.durationSec) -gt 0.08) { Fail 4 "$($fixture.id) duration mismatch" }
  if ([int]$fixture.width -gt 0 -and ($video.Count -eq 0 -or [int]$video[0].width -ne [int]$fixture.width)) { Fail 4 "$($fixture.id) width mismatch" }
  if ([int]$fixture.height -gt 0 -and ($video.Count -eq 0 -or [int]$video[0].height -ne [int]$fixture.height)) { Fail 4 "$($fixture.id) height mismatch" }
  if ([bool]$fixture.hasAudio -ne ($audio.Count -gt 0)) { Fail 4 "$($fixture.id) audio presence mismatch" }
  if (-not $fixture.expected.readable) { Fail 1 "$($fixture.id) must be readable in this manifest" }
}
Write-Host '[fixture-verify:0] six deterministic fixtures, hashes, paths, and ffprobe metadata passed'
