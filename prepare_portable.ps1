# Prepare portable runtime for the Mework installer.
# Usage:
#   powershell -ExecutionPolicy Bypass -File prepare_portable.ps1            # dev: junctions (fast, no copy)
#   powershell -ExecutionPolicy Bypass -File prepare_portable.ps1 -Copy      # packaging: real copy into portable\
param([switch]$Copy, [switch]$RepairDependencies)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$tc = Join-Path (Split-Path $root -Parent) '.toolchain'
$portable = Join-Path $root 'portable'
New-Item -ItemType Directory -Force -Path $portable | Out-Null

function Link-Tool([string]$name, [string]$src, [string]$dst) {
  if (Test-Path $dst) { Write-Host "[skip] $name already at $dst"; return }
  if (-not (Test-Path $src)) { Write-Host "[missing] source $src"; return }
  if ($Copy) {
    Write-Host "[copy] $name -> $dst (this may take a while)..."
    Copy-Item -LiteralPath $src -Destination $dst -Recurse -Force
  } else {
    New-Item -ItemType Junction -Path $dst -Target $src | Out-Null
    Write-Host "[link] $name -> $dst"
  }
}

# JDK 17
Link-Tool 'JDK17' (Join-Path $tc 'jdk-17.0.2') (Join-Path $portable 'jdk-17')
# MySQL 8 server binaries. User databases are created after installation.
Link-Tool 'MySQL8' (Join-Path $tc 'mysql-8.0.28-winx64') (Join-Path $portable 'mysql')

# FFmpeg: prefer the configured local build, else system ffmpeg, else instruct.
$ffmpegDir = 'C:\DevTools\ffmpeg-9.0-full_build'
$dst = Join-Path $portable 'ffmpeg'
if (-not (Test-Path (Join-Path $dst 'bin\ffmpeg.exe'))) {
  if (Test-Path (Join-Path $ffmpegDir 'bin\ffmpeg.exe')) {
    Link-Tool 'FFmpeg' $ffmpegDir $dst
  } else {
    $g = Get-Command ffmpeg -ErrorAction SilentlyContinue
    if ($g) {
      New-Item -ItemType Directory -Force -Path (Join-Path $dst 'bin') | Out-Null
      Copy-Item $g.Source (Join-Path $dst 'bin\ffmpeg.exe') -Force
      $g2 = Get-Command ffprobe -ErrorAction SilentlyContinue
      if ($g2) { Copy-Item $g2.Source (Join-Path $dst 'bin\ffprobe.exe') -Force }
      Write-Host "[copy] system FFmpeg -> $dst"
    } else {
      Write-Host '[missing] FFmpeg: put ffmpeg.exe/ffprobe.exe into portable\ffmpeg\bin before packaging'
    }
  }
}

Write-Host ''
# Media enhancement tools (mandatory per boss decision; installer preloads them into the venv).
# Pinned specs come from the versioned capability manifest (backend/src/main/resources/capabilities.json,
# approvedPip) so the packaged venv matches the runtime repair targets and is reproducible.
$venvPy = Join-Path $root 'backend\.venv\Scripts\python.exe'
$manifestPath = Join-Path $root 'backend\src\main\resources\capabilities.json'
if (Test-Path $venvPy) {
  if (Test-Path $manifestPath) {
    $manifest = Get-Content -Raw -LiteralPath $manifestPath -Encoding UTF8 | ConvertFrom-Json
    $pipTargets = @($manifest.approvedPip.PSObject.Properties | ForEach-Object { $_.Value.spec } | Where-Object { $_ })
    if ($pipTargets.Count -gt 0) {
      if ($RepairDependencies) {
        Write-Host ("[media] 显式修复 venv 固定依赖：" + ($pipTargets -join ' ') + " ...")
        & $venvPy -m pip install --disable-pip-version-check --no-input @pipTargets
        if ($LASTEXITCODE -ne 0) { throw "媒体增强依赖修复失败，退出码 $LASTEXITCODE" }
        Write-Host '[media] 显式修复完成'
      } else {
        Write-Host '[media] 离线准备模式：不执行 pip install；请先准备完整 venv，或显式传入 -RepairDependencies'
      }
    } else {
      Write-Host '[media] WARN: capabilities.json approvedPip 为空，跳过预置'
    }
  } else {
    throw "缺少能力清单 $manifestPath，无法生成可复现发行包"
  }
}

Write-Host 'portable/ 内容：'
Get-ChildItem $portable | Select-Object Name
Write-Host '发行白名单：jdk-17、mysql、ffmpeg、python、whisper、whisper-models、imagemagick。'
Write-Host 'mysqldata、maven、素材、缓存和日志不会进入安装包。'
Write-Host ''
if (-not $Copy) { Write-Host '提示：打包安装器前请用 -Copy 生成真实文件（Inno Setup 不跟随 Junction）。' }
