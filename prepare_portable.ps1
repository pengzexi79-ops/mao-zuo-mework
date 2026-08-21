# Prepare portable runtime for the Mework installer.
# Usage:
#   powershell -ExecutionPolicy Bypass -File prepare_portable.ps1            # dev: junctions (fast, no copy)
#   powershell -ExecutionPolicy Bypass -File prepare_portable.ps1 -Copy      # packaging: real copy into portable\
param([switch]$Copy)
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
# MySQL 8 (bin + data)
Link-Tool 'MySQL8' (Join-Path $tc 'mysql-8.0.28-winx64') (Join-Path $portable 'mysql')
Link-Tool 'MySQL data' (Join-Path $tc 'mysqldata') (Join-Path $portable 'mysqldata')
# Maven (build convenience)
Link-Tool 'Maven' (Join-Path $tc 'apache-maven-3.9.11') (Join-Path $portable 'maven')

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
      Write-Host ("[media] 预置媒体增强工具到 venv（按 capabilities.json approvedPip 固定版本：" + ($pipTargets -join ' ') + "）...")
      & $venvPy -m pip install --disable-pip-version-check --no-input @pipTargets 2>&1 | Out-Null
      Write-Host '[media] 完成（缺失时环境中心会标红并给出一键处理）'
    } else {
      Write-Host '[media] WARN: capabilities.json approvedPip 为空，跳过预置'
    }
  } else {
    Write-Host "[media] WARN: 缺少能力清单 $manifestPath，跳过预置（发行包将无法固定媒体工具版本）"
  }
}

Write-Host 'portable/ 内容：'
Get-ChildItem $portable | Select-Object Name
Write-Host ''
if (-not $Copy) { Write-Host '提示：打包安装器前请用 -Copy 生成真实文件（Inno Setup 不跟随 Junction）。' }