# ZCode monitor: single-pass scanner (idempotent, crash-safe)
# Reads appended bytes of ZCode logs since last run, emits compact events to activity.log,
# persists offsets/state in state.json. Run every 20s by looper.ps1 (detached) or manually.
$ErrorActionPreference = 'Stop'
$outDir = 'D:\deepseek\zcode-monitor'
$activity = Join-Path $outDir 'activity.log'
$statePath = Join-Path $outDir 'state.json'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$base = Join-Path $env:USERPROFILE '.zcode'
$cliLogDir = Join-Path $base 'cli\log'
$v2LogDir = Join-Path $base 'v2\logs'
$hookLog = Join-Path $base 'mimosa-debug.log'

# ---- state ----
$state = @{ offsets = @{}; recent = @(); lastCpu = $null; lastSnap = $null }
if (Test-Path $statePath) {
  try {
    $loaded = Get-Content $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($p in $loaded.offsets.PSObject.Properties) { $state.offsets[$p.Name] = [long]$p.Value }
    $state.recent = @($loaded.recent)
    if ($null -ne $loaded.lastCpu) { $state.lastCpu = [double]$loaded.lastCpu }
    if ($null -ne $loaded.lastSnap) { $state.lastSnap = [DateTime]$loaded.lastSnap }
  } catch { $state = @{ offsets = @{}; recent = @(); lastCpu = $null; lastSnap = $null } }
}
$recent = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($r in $state.recent) { [void]$recent.Add($r) }

function Save-State {
  $sorted = @{}
  $state.offsets.Keys | Sort-Object | ForEach-Object { $sorted[$_] = $state.offsets[$_] }
  $obj = [ordered]@{
    offsets = $sorted
    recent = @($recent) | Select-Object -Last 1500
    lastCpu = $state.lastCpu
    lastSnap = if ($state.lastSnap) { $state.lastSnap.ToString('o') } else { $null }
  }
  $tmp = "$statePath.tmp"
  $obj | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $tmp -Encoding UTF8
  Move-Item -LiteralPath $tmp -Destination $statePath -Force
}

function Emit([string]$s, [string]$when) {
  if (-not $recent.Add($s)) { return }
  if ($recent.Count -gt 4000) { $recent.Clear() }
  $ts = if ($when) {
    try { ([DateTime]::Parse($when)).ToLocalTime().ToString('HH:mm:ss') } catch { Get-Date -Format 'HH:mm:ss' }
  } else { Get-Date -Format 'HH:mm:ss' }
  Add-Content -LiteralPath $activity -Value "[$ts] $s" -Encoding UTF8
}

function Read-Append([string]$path, [string]$key, [int]$bootstrapTail) {
  if (-not (Test-Path -LiteralPath $path)) { return @() }
  $fi = Get-Item -LiteralPath $path
  if (-not $state.offsets.ContainsKey($key)) {
    $state.offsets[$key] = $fi.Length
    if ($bootstrapTail -gt 0) {
      return @(Get-Content -LiteralPath $path -Tail $bootstrapTail -Encoding UTF8)
    }
    return @()
  }
  if ($fi.Length -lt $state.offsets[$key]) { $state.offsets[$key] = 0 }
  if ($fi.Length -eq $state.offsets[$key]) { return @() }
  $newLines = @()
  try {
    $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    $fs.Seek($state.offsets[$key], [System.IO.SeekOrigin]::Begin) | Out-Null
    $len = [int]($fi.Length - $state.offsets[$key])
    $buf = New-Object byte[] $len
    [void]$fs.Read($buf, 0, $len)
    $state.offsets[$key] = $fi.Length
    $fs.Close()
    $txt = [System.Text.Encoding]::UTF8.GetString($buf)
    $newLines = @($txt -split "`n" | Where-Object { $_.Trim().Length -gt 0 })
  } catch {
    $newLines = @(Get-Content -LiteralPath $path -Tail 300 -Encoding UTF8)
    $state.offsets[$key] = $fi.Length
  }
  return $newLines
}

function Short([string]$s) {
  if (-not $s) { return '?' }
  if ($s -match '([a-f0-9]{8})') { return $matches[1] }
  return $s.Substring(0, [Math]::Min(8, $s.Length))
}

function Handle-Jsonl([string[]]$lines) {
  foreach ($line in $lines) {
    if ($line -notmatch '^\{"timestamp"') { continue }
    try { $o = $line | ConvertFrom-Json } catch { continue }
    $ev = [string]$o.event; $lvl = [string]$o.level; $cx = $o.context; $when = [string]$o.timestamp
    if ($ev -eq 'tool.call.started') {
      Emit "TOOL> $($cx.toolName) by=$(Short $o.sessionId) type=$($cx.agentType) iter=$($cx.iteration) parent=$(Short $cx.parentSessionId)" $when
    }
    elseif ($ev -eq 'tool.call.completed') {
      $st = [string]$o.status
      if ($st -ne 'completed' -or [int]$o.durationMs -gt 5000) {
        Emit "TOOL! $($cx.toolName) ${st} $($o.durationMs)ms by=$(Short $o.sessionId)" $when
      }
    }
    elseif ($ev -match 'agent\.(task|spawn|subagent|done|complete|failed|result|exit)') {
      Emit "AGENT $ev status=$($o.status) msg=$($o.message)" $when
    }
    elseif ($ev -match 'task\.(created|updated|completed|failed|status|archived)') {
      Emit "TASK $ev status=$($o.status) $($o.message)" $when
    }
    elseif ($lvl -eq 'error') {
      $err = [string]$cx.error
      if ($err.Length -gt 200) { $err = $err.Substring(0, 200) }
      Emit "ERROR $ev :: $($o.message) :: $err" $when
    }
    elseif ($lvl -eq 'warn' -and $ev -notmatch 'gateway_error|telemetry') {
      Emit "WARN $ev :: $($o.message)" $when
    }
    elseif ($ev -eq 'model.request.completed' -and ([int]$o.durationMs -gt 45000)) {
      Emit "MODEL-SLOW $($cx.model) $($o.durationMs)ms by=$(Short $o.sessionId)" $when
    }
    elseif ($ev -eq 'model.request.completed' -and $o.status -ne 'completed') {
      Emit "MODEL-FAIL $($cx.model) $($o.status)" $when
    }
  }
}

function Handle-Hook([string[]]$lines) {
  foreach ($line in $lines) {
    if ($line -match 'hook_completed.*"outcome"') {
      if ($line -match '"outcome":"([^"]+)".*"findingCount":([0-9]+).*"durationMs":([0-9]+)') {
        $out = $matches[1]; $fc = [int]$matches[2]; $dur = $matches[3]
        if ($out -ne 'clear' -or $fc -gt 0) {
          if ($line -match '"toolName":"([^"]+)"') {
            Emit "SCAN $($matches[1]) outcome=$out findings=$fc ${dur}ms"
          }
        }
      }
    }
    elseif ($line -match 'stop-hook\] invoked') {
      if ($line -match 'project=(\S+)\s+touched=(\d+)') {
        Emit "STOP-HOOK project=$($matches[1]) touched=$($matches[2])"
      }
    }
    elseif ($line -match 'error=|ETIMEDOUT|review_failed|report_write_failed|EACCES|EPERM') {
      $short = $line
      if ($short.Length -gt 250) { $short = $short.Substring(0, 250) }
      Emit "HOOK-PROBLEM $short"
    }
    elseif ($line -match '\[session-hook\]') {
      Emit "SESSION $line"
    }
  }
}

function Handle-Applog([string[]]$lines) {
  foreach ($line in $lines) {
    if ($line -match '\[(error|warn)\]') {
      $short = $line
      if ($short.Length -gt 240) { $short = $short.Substring(0, 240) }
      Emit "APP $short"
    }
  }
}

# ---- one pass ----
$cliFile = Get-ChildItem $cliLogDir -Filter 'zcode-*.jsonl' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($cliFile) { Handle-Jsonl (Read-Append $cliFile.FullName ("cli:" + $cliFile.Name) 40) }
$v2File = Get-ChildItem $v2LogDir -Filter '*.log' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($v2File) { Handle-Applog (Read-Append $v2File.FullName ("v2:" + $v2File.Name) 0) }
if (Test-Path $hookLog) { Handle-Hook (Read-Append $hookLog 'hook' 0) }

# snapshot every 5 min
if ($null -eq $state.lastSnap -or ((Get-Date) - $state.lastSnap).TotalMinutes -ge 5) {
  $procs = @(Get-Process -Name ZCode -ErrorAction SilentlyContinue)
  $ws = ($procs | Measure-Object WorkingSet64 -Sum).Sum / 1MB
  $cpu = ($procs | Measure-Object CPU -Sum).Sum
  $delta = if ($null -ne $state.lastCpu) { [math]::Round($cpu - $state.lastCpu, 1) } else { 0 }
  $state.lastCpu = $cpu
  $state.lastSnap = Get-Date
  Emit "SNAP procs=$($procs.Count) ws=$([math]::Round($ws,0))MB cpuTotal=$([math]::Round($cpu,0))s cpuDelta5m=$delta s"
}

Save-State
