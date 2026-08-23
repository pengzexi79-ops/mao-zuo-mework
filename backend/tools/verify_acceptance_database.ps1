param(
  [string]$DatabaseUrl = $env:ACCEPTANCE_DB_URL,
  [string]$Username = $env:ACCEPTANCE_DB_USERNAME,
  [string]$Password = $env:ACCEPTANCE_DB_PASSWORD,
  [string]$Mysql = ''
)
$ErrorActionPreference = 'Stop'

function Fail([int]$Code, [string]$Message) {
  Write-Error "[acceptance-db:$Code] $Message"
  exit $Code
}

if ([string]::IsNullOrWhiteSpace($DatabaseUrl)) {
  Write-Host '[acceptance-db:skipped] ACCEPTANCE_DB_URL is not set; no database connection attempted'
  exit 0
}
if ($DatabaseUrl -notmatch '^jdbc:mysql://') { Fail 2 'ACCEPTANCE_DB_URL must be a MySQL JDBC URL' }
$databaseName = ($DatabaseUrl -replace '^jdbc:mysql://[^/]+/', '') -split '\?' | Select-Object -First 1
if ($databaseName -ne 'ai_mix_video_acceptance') { Fail 2 "refusing non-isolated database: $databaseName" }
if ([string]::IsNullOrWhiteSpace($Username)) { Fail 2 'ACCEPTANCE_DB_USERNAME is required when URL is set' }
if ([string]::IsNullOrWhiteSpace($Mysql)) {
  $candidate = Join-Path $PSScriptRoot '..\..\portable\mysql\bin\mysql.exe'
  $Mysql = if (Test-Path -LiteralPath $candidate -PathType Leaf) { $candidate } else { 'mysql.exe' }
}
if (-not (Get-Command $Mysql -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath $Mysql -PathType Leaf)) { Fail 3 'mysql client is not available' }
$hostPart = ($DatabaseUrl -replace '^jdbc:mysql://', '') -split '/' | Select-Object -First 1
$parts = $hostPart -split ':'
$hostName = $parts[0]
$port = if ($parts.Count -gt 1) { $parts[1] } else { '3306' }
if ($hostName -notin @('127.0.0.1', 'localhost', '::1')) { Fail 2 'acceptance database must be local-only' }
$env:MYSQL_PWD = $Password
try {
  $result = & $Mysql --protocol=TCP --host=$hostName --port=$port --user=$Username --batch --skip-column-names --execute='SELECT DATABASE(); SHOW TABLES;' $databaseName 2>&1
  if ($LASTEXITCODE -ne 0) { Fail 4 'read-only acceptance database check failed' }
  if (($result -join "`n") -notmatch 'ai_mix_video_acceptance') { Fail 4 'database identity check failed' }
  Write-Host '[acceptance-db:0] isolated database identity and read-only table query passed'
} finally {
  Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
