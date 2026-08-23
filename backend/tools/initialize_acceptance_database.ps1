param(
  [string]$DatabaseUrl = $env:ACCEPTANCE_DB_URL,
  [string]$Username = $env:ACCEPTANCE_DB_USERNAME,
  [string]$Password = $env:ACCEPTANCE_DB_PASSWORD,
  [string]$Mysql = '',
  [switch]$ConfirmAcceptanceDatabase
)
$ErrorActionPreference = 'Stop'

function Fail([int]$Code, [string]$Message) {
  Write-Error "[acceptance-db-init:$Code] $Message"
  exit $Code
}

if (-not $ConfirmAcceptanceDatabase) { Fail 2 'pass -ConfirmAcceptanceDatabase to initialize the isolated schema' }
if ([string]::IsNullOrWhiteSpace($DatabaseUrl) -or [string]::IsNullOrWhiteSpace($Username)) { Fail 2 'explicit ACCEPTANCE_DB_URL and ACCEPTANCE_DB_USERNAME are required' }
if ($DatabaseUrl -notmatch '^jdbc:mysql://') { Fail 2 'ACCEPTANCE_DB_URL must be a MySQL JDBC URL' }
$databaseName = ($DatabaseUrl -replace '^jdbc:mysql://[^/]+/', '') -split '\?' | Select-Object -First 1
if ($databaseName -ne 'ai_mix_video_acceptance') { Fail 2 "refusing non-isolated database: $databaseName" }
$hostPort = (($DatabaseUrl -replace '^jdbc:mysql://', '') -split '/' | Select-Object -First 1) -split ':'
$hostName = $hostPort[0]
$port = if ($hostPort.Count -gt 1) { $hostPort[1] } else { '3306' }
if ($hostName -notin @('127.0.0.1', 'localhost', '::1')) { Fail 2 'acceptance database must be local-only' }
if ([string]::IsNullOrWhiteSpace($Mysql)) {
  $candidate = Join-Path $PSScriptRoot '..\..\portable\mysql\bin\mysql.exe'
  $Mysql = if (Test-Path -LiteralPath $candidate -PathType Leaf) { $candidate } else { 'mysql.exe' }
}
if (-not (Get-Command $Mysql -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath $Mysql -PathType Leaf)) { Fail 3 'mysql client is not available' }
$root = (Resolve-Path -LiteralPath (Join-Path (Join-Path $PSScriptRoot '..') '..')).Path
$baseSchema = Join-Path $root 'backend\src\test\resources\acceptance\acceptance-schema.sql'
$outputSchema = Join-Path $root 'backend\src\test\resources\acceptance\acceptance-output-recovery-schema.sql'
if (-not (Test-Path -LiteralPath $baseSchema -PathType Leaf) -or -not (Test-Path -LiteralPath $outputSchema -PathType Leaf)) { Fail 3 'acceptance schema files are missing' }
$env:MYSQL_PWD = $Password
try {
  $identity = & $Mysql --protocol=TCP --host=$hostName --port=$port --user=$Username --batch --skip-column-names --execute='SELECT DATABASE();' $databaseName 2>&1
  if ($LASTEXITCODE -ne 0 -or ($identity -join "`n").Trim() -ne 'ai_mix_video_acceptance') { Fail 4 'database identity precheck failed' }
  $duplicates = & $Mysql --protocol=TCP --host=$hostName --port=$port --user=$Username --batch --skip-column-names --execute="SELECT CONCAT('job_output:',job_id,':',idx) FROM job_output GROUP BY job_id,idx HAVING COUNT(*) > 1; SELECT CONCAT('output_version:',job_id,':',idx,':',version_no) FROM output_version GROUP BY job_id,idx,version_no HAVING COUNT(*) > 1;" $databaseName 2>&1
  if ($LASTEXITCODE -ne 0) {
    # Missing tables are expected before first initialization; fixed DDL creates them idempotently.
    $duplicates = @()
  }
  if (($duplicates -join "`n").Trim().Length -gt 0) { Fail 4 'duplicate acceptance checkpoint/version rows exist; refusing to apply schema' }
  Get-Content -Raw -LiteralPath $baseSchema | & $Mysql --protocol=TCP --host=$hostName --port=$port --user=$Username $databaseName
  if ($LASTEXITCODE -ne 0) { Fail 5 'base acceptance schema initialization failed' }
  Get-Content -Raw -LiteralPath $outputSchema | & $Mysql --protocol=TCP --host=$hostName --port=$port --user=$Username $databaseName
  if ($LASTEXITCODE -ne 0) { Fail 5 'output recovery schema initialization failed' }
  Write-Host '[acceptance-db-init:0] isolated acceptance schema initialized'
} finally {
  Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
