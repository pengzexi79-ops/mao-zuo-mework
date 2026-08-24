param(
  [string]$ProjectRoot = (Join-Path (Join-Path $PSScriptRoot '..') '..'),
  [switch]$Force
)
$ErrorActionPreference = 'Stop'

function Fail([int]$Code, [string]$Message) {
  Write-Error "[fixture-generate:$Code] $Message"
  exit $Code
}

function Assert-PlainFile([string]$Path, [string]$Label) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Fail 3 "$Label is missing" }
  $item = Get-Item -LiteralPath $Path -Force
  if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { Fail 2 "$Label is a reparse point" }
}

function Invoke-Tool([string]$FilePath, [string[]]$Arguments, [string]$Label) {
  $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -Wait -PassThru -NoNewWindow
  if ($process.ExitCode -ne 0) { Fail 1 "$Label failed with exit code $($process.ExitCode)" }
}

function Hash([string]$Path) {
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Probe([string]$Path) {
  $raw = & $script:ffprobe -v error -print_format json -show_streams -show_format -- $Path
  if ($LASTEXITCODE -ne 0) { Fail 4 "ffprobe metadata failed for $Path" }
  try { return ($raw -join "`n") | ConvertFrom-Json } catch { Fail 4 "ffprobe JSON invalid for $Path" }
}

$root = (Resolve-Path -LiteralPath $ProjectRoot).Path
$script:fixtureRoot = Join-Path $root 'backend/src/test/resources/acceptance/fixtures'
$manifestPath = Join-Path $root 'backend/src/test/resources/acceptance/fixture-manifest.json'
$script:ffmpeg = Join-Path $root 'portable/ffmpeg/bin/ffmpeg.exe'
$script:ffprobe = Join-Path $root 'portable/ffmpeg/bin/ffprobe.exe'
Assert-PlainFile $script:ffmpeg 'portable ffmpeg.exe'
Assert-PlainFile $script:ffprobe 'portable ffprobe.exe'

if ((Test-Path -LiteralPath $script:fixtureRoot) -and -not $Force) {
  $existing = Get-ChildItem -LiteralPath $script:fixtureRoot -Force
  if ($existing.Count -gt 0) { Fail 2 'fixture directory is not empty; use -Force to regenerate' }
}
New-Item -ItemType Directory -Force -Path $script:fixtureRoot | Out-Null
$rootFull = [IO.Path]::GetFullPath($root).TrimEnd('\')
$fixtureFull = [IO.Path]::GetFullPath($script:fixtureRoot).TrimEnd('\')
if (-not $fixtureFull.StartsWith($rootFull + '\', [StringComparison]::OrdinalIgnoreCase)) { Fail 2 'fixture root escaped project root' }

$motion = Join-Path $script:fixtureRoot 'video_motion.mp4'
$av = Join-Path $script:fixtureRoot 'video_av.mp4'
$black = Join-Path $script:fixtureRoot 'video_black.mp4'
$solid = Join-Path $script:fixtureRoot 'video_solid.mp4'
$audio = Join-Path $script:fixtureRoot 'audio_voice.wav'
$bgm = Join-Path $script:fixtureRoot 'audio_bgm.wav'
$silence = Join-Path $script:fixtureRoot 'audio_silence.wav'
$cover = Join-Path $script:fixtureRoot 'cover.png'

Invoke-Tool $script:ffmpeg @('-y','-v','error','-f','lavfi','-i','testsrc=size=320x568:rate=24','-t','2.0','-an','-c:v','libx264','-pix_fmt','yuv420p',$motion) 'motion fixture'
Invoke-Tool $script:ffmpeg @('-y','-v','error','-f','lavfi','-i','testsrc=size=320x568:rate=24','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','2.0','-c:v','libx264','-pix_fmt','yuv420p','-c:a','pcm_s16le',$av) 'audio-video fixture'
Invoke-Tool $script:ffmpeg @('-y','-v','error','-f','lavfi','-i','color=c=black:size=320x568:rate=24','-t','2.0','-an','-c:v','libx264','-pix_fmt','yuv420p',$black) 'black fixture'
Invoke-Tool $script:ffmpeg @('-y','-v','error','-f','lavfi','-i','color=c=blue:size=320x568:rate=24','-t','2.0','-an','-c:v','libx264','-pix_fmt','yuv420p',$solid) 'solid fixture'
Invoke-Tool $script:ffmpeg @('-y','-v','error','-f','lavfi','-i','sine=frequency=220:sample_rate=44100','-t','2.0','-c:a','pcm_s16le',$audio) 'voice audio fixture'
Invoke-Tool $script:ffmpeg @('-y','-v','error','-f','lavfi','-i','sine=frequency=880:sample_rate=44100','-t','2.0','-c:a','pcm_s16le',$bgm) 'bgm audio fixture'
Invoke-Tool $script:ffmpeg @('-y','-v','error','-f','lavfi','-i','anullsrc=channel_layout=stereo:sample_rate=44100','-t','2.0','-c:a','pcm_s16le',$silence) 'silence audio fixture'
Invoke-Tool $script:ffmpeg @('-y','-v','error','-f','lavfi','-i','color=c=white:size=320x320','-frames:v','1',$cover) 'image fixture'

$definitions = @(
  @{ id='video_motion'; path='fixtures/video_motion.mp4'; kind='positive_motion'; mediaType='video'; expected=@{ readable=$true; qualityGate='admit'; minSegments=2 } },
  @{ id='video_av'; path='fixtures/video_av.mp4'; kind='positive_audio_video'; mediaType='video'; expected=@{ readable=$true; qualityGate='admit'; minSegments=2 } },
  @{ id='video_black'; path='fixtures/video_black.mp4'; kind='negative_black'; mediaType='video'; expected=@{ readable=$true; qualityGate='reject'; minSegments=1 } },
  @{ id='video_solid'; path='fixtures/video_solid.mp4'; kind='negative_solid'; mediaType='video'; expected=@{ readable=$true; qualityGate='reject'; minSegments=1 } },
  @{ id='audio_voice'; path='fixtures/audio_voice.wav'; kind='positive_audio_voice'; mediaType='audio'; expected=@{ readable=$true; qualityGate='admit'; minSegments=0 } },
  @{ id='audio_bgm'; path='fixtures/audio_bgm.wav'; kind='positive_audio_bgm'; mediaType='audio'; expected=@{ readable=$true; qualityGate='admit'; minSegments=0 } },
  @{ id='audio_silence'; path='fixtures/audio_silence.wav'; kind='negative_audio_silence'; mediaType='audio'; expected=@{ readable=$true; qualityGate='reject'; minSegments=0 } },
  @{ id='cover'; path='fixtures/cover.png'; kind='positive_image'; mediaType='image'; expected=@{ readable=$true; qualityGate='manual'; minSegments=0 } }
)

$fixtures = foreach ($definition in $definitions) {
  $path = Join-Path $root ('backend/src/test/resources/acceptance/' + $definition.path)
  Assert-PlainFile $path $definition.id
  $meta = Probe $path
  $video = @($meta.streams | Where-Object { $_.codec_type -eq 'video' } | Select-Object -First 1)
  $audioStream = @($meta.streams | Where-Object { $_.codec_type -eq 'audio' } | Select-Object -First 1)
  $fps = 0.0
  if ($video.Count -gt 0 -and $video[0].r_frame_rate) {
    $parts = $video[0].r_frame_rate -split '/'
    $fps = [double]$parts[0] / [double]$(if ($parts.Count -gt 1) { $parts[1] } else { 1 })
  }
  $item = Get-Item -LiteralPath $path -Force
  [ordered]@{
    id=$definition.id
    relativePath=$definition.path.Replace('\','/')
    kind=$definition.kind
    mediaType=$definition.mediaType
    durationSec=[math]::Round([double]$meta.format.duration, 3)
    width=if ($video.Count -gt 0) { [int]$video[0].width } else { 0 }
    height=if ($video.Count -gt 0) { [int]$video[0].height } else { 0 }
    fps=[math]::Round($fps, 3)
    hasAudio=($audioStream.Count -gt 0)
    sha256=(Hash $path)
    sizeBytes=[int64]$item.Length
    expected=$definition.expected
  }
}

$manifest = [ordered]@{
  schemaVersion=1
  generatedBy='generate_acceptance_fixtures.ps1'
  generatedAt=(Get-Date).ToUniversalTime().ToString('o')
  fixtures=@($fixtures)
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $manifestPath) | Out-Null
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
Write-Host "[fixture-generate:0] generated $($fixtures.Count) deterministic fixtures"
