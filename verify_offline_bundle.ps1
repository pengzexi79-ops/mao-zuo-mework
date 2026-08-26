param(
  [string]$BundleRoot = $PSScriptRoot,
  [string]$ManifestPath = '',
  [switch]$KeepWorkDir
)
$ErrorActionPreference = 'Stop'

function Fail([int]$Code, [string]$Message) {
  Write-Error "[offline-smoke:$Code] $Message"
  exit $Code
}

function Assert-File([string]$Path, [string]$Label) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Fail 3 "$Label is missing" }
  $item = Get-Item -LiteralPath $Path -Force
  if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { Fail 3 "$Label is a junction or symlink" }
  return $item
}

function Run-Process([string]$FilePath, [string[]]$Arguments, [string]$Label, [int]$FailureCode) {
  $stdout = Join-Path $script:workDir (([guid]::NewGuid().ToString('N')) + '.out')
  $stderr = Join-Path $script:workDir (([guid]::NewGuid().ToString('N')) + '.err')
  try {
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $script:workDir -Wait -PassThru -NoNewWindow -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $out = if (Test-Path $stdout) { Get-Content -Raw -LiteralPath $stdout -Encoding UTF8 } else { '' }
    $err = if (Test-Path $stderr) { Get-Content -Raw -LiteralPath $stderr -Encoding UTF8 } else { '' }
    if ($process.ExitCode -ne 0) {
      $tail = (($out + "`n" + $err).Trim() -replace '\s+', ' ')
      if ($tail.Length -gt 500) { $tail = $tail.Substring($tail.Length - 500) }
      Fail $FailureCode "$Label failed: $tail"
    }
    return $out
  } finally {
    Remove-Item -LiteralPath $stdout,$stderr -Force -ErrorAction SilentlyContinue
  }
}

$root = (Resolve-Path -LiteralPath $BundleRoot).Path
$tempBase = if ($env:TEMP) { (Resolve-Path -LiteralPath $env:TEMP).Path } else { [IO.Path]::GetTempPath().TrimEnd('\') }
$work = Join-Path $tempBase ('mework-offline-smoke-' + [guid]::NewGuid().ToString('N'))
$script:workDir = $work
$cleanupCode = 0
try {
  $ffmpeg = Join-Path $root 'portable\ffmpeg\bin\ffmpeg.exe'
  $ffprobe = Join-Path $root 'portable\ffmpeg\bin\ffprobe.exe'
  $python = Join-Path $root 'backend\.venv\Scripts\python.exe'
  $diagnose = Join-Path $root 'backend\tools\media_diagnose.py'
  Assert-File $ffmpeg 'bundled ffmpeg.exe' | Out-Null
  Assert-File $ffprobe 'bundled ffprobe.exe' | Out-Null
  Assert-File $python 'bundled venv python.exe' | Out-Null
  Assert-File $diagnose 'media_diagnose.py' | Out-Null

  $rootFull = [IO.Path]::GetFullPath($root).TrimEnd('\')
  $tempFull = [IO.Path]::GetFullPath($tempBase).TrimEnd('\')
  if ($rootFull -eq $tempFull -or $rootFull.StartsWith($tempFull + '\', [StringComparison]::OrdinalIgnoreCase)) { Fail 2 'BundleRoot must not be the temporary directory' }
  New-Item -ItemType Directory -Force -Path $work | Out-Null
  $workFull = [IO.Path]::GetFullPath($work).TrimEnd('\')
  if ((Split-Path -Parent $workFull) -ne $tempFull) { Fail 2 'Smoke directory escaped TEMP' }

  $env:HF_HUB_OFFLINE = '1'
  $env:TRANSFORMERS_OFFLINE = '1'
  $env:HF_DATASETS_OFFLINE = '1'
  $env:PIP_NO_INDEX = '1'
  $env:PIP_DISABLE_PIP_VERSION_CHECK = '1'
  $env:NO_PROXY = '*'
  $env:HTTP_PROXY = ''
  $env:HTTPS_PROXY = ''
  $env:ALL_PROXY = ''

  $video = Join-Path $work 'offline-test.mp4'
  Run-Process $ffmpeg @('-y','-v','error','-f','lavfi','-i','testsrc=size=360x640:rate=30','-t','1.8','-an','-c:v','libx264','-pix_fmt','yuv420p',$video) 'FFmpeg test media generation' 4 | Out-Null
  Assert-File $video 'generated test video' | Out-Null

  $probe = Run-Process $ffprobe @('-v','error','-print_format','json','-show_streams','-show_format',$video) 'FFprobe test media probe' 5
  $probeJson = $probe | ConvertFrom-Json
  $videoStream = @($probeJson.streams | Where-Object { $_.codec_type -eq 'video' }) | Select-Object -First 1
  if (-not $videoStream -or [double]$probeJson.format.duration -le 0) { Fail 5 'FFprobe did not report a valid video stream and duration' }

  $diagnosis = Run-Process $python @($diagnose,'--video-quality',$video) 'media_diagnose video quality' 5
  try { $json = $diagnosis | ConvertFrom-Json } catch { Fail 5 'media_diagnose output is not valid JSON' }
  if ($json.readable -ne $true -or [int]$json.frames -le 0) { Fail 5 'diagnosis JSON did not report readable frames' }
  foreach ($field in @('blurryRatio','darkRatio','brightRatio')) {
    if ($null -eq $json.$field -or [double]$json.$field -lt 0 -or [double]$json.$field -gt 1) { Fail 5 "diagnosis field $field is outside 0..1" }
  }
  if (-not ([IO.Path]::GetFullPath($video).StartsWith($workFull + '\', [StringComparison]::OrdinalIgnoreCase))) { Fail 2 'Generated output escaped smoke directory' }
  Write-Host '[offline-smoke:0] FFmpeg + FFprobe + venv Python + OpenCV diagnosis passed'
} catch {
  if ($_.Exception -is [System.Management.Automation.ActionPreferenceStopException]) { throw }
  Fail 1 $_.Exception.Message
} finally {
  if (-not $KeepWorkDir -and (Test-Path -LiteralPath $work)) {
    try { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction Stop } catch { $cleanupCode = 6 }
  }
  if ($cleanupCode -ne 0) { Fail $cleanupCode 'Failed to clean isolated smoke directory' }
}
