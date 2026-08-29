param(
  [string]$Root = $PSScriptRoot,
  [string]$InstalledRoot = ''
)
$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $Root).Path
$errors = [System.Collections.Generic.List[string]]::new()

$tracked = @(& git -C $rootPath ls-files)
if ($LASTEXITCODE -ne 0) { throw 'git ls-files failed' }
foreach ($relative in $tracked) {
  $normalized = $relative.Replace('\','/')
  if ($normalized -match '(^|/)\.env($|\.)' -and $normalized -ne '.env.example') { $errors.Add("tracked private environment file: $normalized") }
}

$patterns = [ordered]@{
  'OpenAI-style API key' = '(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}'
  'GitHub token' = '(?<![A-Za-z0-9])(gho_|ghp_|github_pat_)[A-Za-z0-9_]{20,}'
  'Google API key' = '(?<![A-Za-z0-9])AIza[0-9A-Za-z_-]{30,}'
  'private key block' = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
  'legacy fixed database password' = 'MixcutLocal[0-9A-Za-z]+'
  'development workspace path' = '(?i)(?:C:\\Users\\[^\\]+\\WorkBuddy\\[^\r\n"'']+\\ai-douyin-mixcut|D:\\zcode\\projects\\ai-douyin-mixcut)'
}

foreach ($relative in $tracked) {
  $path = Join-Path $rootPath $relative
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
  $item = Get-Item -LiteralPath $path
  if ($item.Length -gt 10MB) { continue }
  $bytes = [IO.File]::ReadAllBytes($path)
  if ($bytes -contains 0) { continue }
  $text = [Text.Encoding]::UTF8.GetString($bytes)
  foreach ($entry in $patterns.GetEnumerator()) {
    if ($text -match $entry.Value) { $errors.Add("$($entry.Key): $relative") }
  }
}

$issPath = Join-Path $rootPath 'installer\Mework.iss'
if (-not (Test-Path -LiteralPath $issPath -PathType Leaf)) { $errors.Add('installer/Mework.iss is missing') }
else {
  $iss = Get-Content -Raw -LiteralPath $issPath
  $filesSection = if ($iss -match '(?s)\[Files\](.*?)(?:\r?\n\[|$)') { $Matches[1] } else { '' }
  $blockedSources = @(
    '^\.\.\\portable\\\*$',
    'portable\\mysqldata',
    'portable\\maven',
    '^\.\.\\\.env$',
    '(?:^|\\)(?:sample-materials|materials|data|cache|logs)\\\*$'
  )
  foreach ($line in ($filesSection -split '\r?\n')) {
    if ($line -notmatch '^\s*Source:\s*"([^"]+)"') { continue }
    $source = $Matches[1]
    foreach ($pattern in $blockedSources) {
      if ($source -match $pattern) { $errors.Add("installer blocked input: $source") }
    }
  }
}

if (-not [string]::IsNullOrWhiteSpace($InstalledRoot)) {
  $installed = (Resolve-Path -LiteralPath $InstalledRoot).Path
  foreach ($blocked in @('portable\mysqldata','portable\maven','materials','sample-materials','cache')) {
    if (Test-Path -LiteralPath (Join-Path $installed $blocked)) { $errors.Add("installed blocked directory: $blocked") }
  }
  $envPath = Join-Path $installed '.env'
  if (Test-Path -LiteralPath $envPath -PathType Leaf) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $envPath -Encoding UTF8) {
      $separator = $line.IndexOf('=')
      if ($separator -gt 0) { $values[$line.Substring(0,$separator)] = $line.Substring($separator + 1) }
    }
    foreach ($name in @('APP_ACCESS_TOKEN','APP_FREESOUND_API_KEY','APP_PIXABAY_API_KEY','APP_PEXELS_API_KEY','APP_UNSPLASH_API_KEY')) {
      if (-not [string]::IsNullOrWhiteSpace([string]$values[$name])) { $errors.Add("fresh install contains configured user credential: $name") }
    }
  }
}

if ($errors.Count) { $errors | Sort-Object -Unique | ForEach-Object { Write-Error $_ }; exit 1 }
Write-Host 'Release privacy checks passed.'
