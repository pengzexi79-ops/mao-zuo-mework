param([string]$Root = $PSScriptRoot)
$ErrorActionPreference = 'Stop'

$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\')
$envPath = Join-Path $rootPath '.env'

function Import-LocalEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing local configuration: $Path" }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { continue }
        $name = $line.Substring(0, $separator).Trim()
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            [Environment]::SetEnvironmentVariable($name, $line.Substring($separator + 1), 'Process')
        }
    }
}

function Resolve-AppPath([string]$Value, [string]$DefaultRelative) {
    $candidate = if ([string]::IsNullOrWhiteSpace($Value)) { Join-Path $rootPath $DefaultRelative } else { $Value }
    if (-not [IO.Path]::IsPathRooted($candidate)) { $candidate = Join-Path $rootPath $candidate }
    return [IO.Path]::GetFullPath($candidate).TrimEnd('\')
}

function Convert-MySqlPath([string]$Path) { return ([IO.Path]::GetFullPath($Path) -replace '\\', '/') }

function Test-TcpPort([int]$Port, [int]$TimeoutMs = 500) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return $task.Wait($TimeoutMs) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Invoke-Client([string]$Exe, [string[]]$Arguments, [string]$Password, [string]$InputText = '') {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Exe
    $info.Arguments = ($Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    if (-not [string]::IsNullOrEmpty($Password)) { $info.EnvironmentVariables['MYSQL_PWD'] = $Password }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    if (-not $process.Start()) { throw "Unable to start $Exe" }
    if ($InputText) { $process.StandardInput.Write($InputText) }
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    return [pscustomobject]@{ ExitCode = $process.ExitCode; StdOut = $stdout; StdErr = $stderr }
}

function Test-MySqlIdentity([string]$ClientExe, [int]$Port, [string]$User, [string]$Password) {
    $result = Invoke-Client $ClientExe @('--protocol=TCP','-h','127.0.0.1','-P',[string]$Port,'-u',$User,'-N','-B','-e','SELECT 1') $Password
    return $result.ExitCode -eq 0 -and $result.StdOut.Trim() -eq '1'
}

function Escape-SqlLiteral([string]$Value) { return $Value.Replace("'", "''") }

Import-LocalEnv $envPath
$mysqlPort = if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3306 }
$dbUser = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'mixcut' }
$dbPassword = $env:DB_PASSWORD
$rootPassword = $env:MYSQL_ROOT_PASSWORD
if ($dbUser -notmatch '^[A-Za-z0-9_]{1,32}$') { throw 'DB_USERNAME must contain only letters, digits, or underscore.' }
if ([string]::IsNullOrWhiteSpace($dbPassword)) { throw 'DB_PASSWORD must be generated before MySQL starts.' }
if ($env:DB_URL -and $env:DB_URL -notmatch "^jdbc:mysql://(?:127\.0\.0\.1|localhost):$mysqlPort/") {
    throw "MYSQL_PORT=$mysqlPort does not match the local DB_URL."
}

$mysqlHome = Join-Path $rootPath 'portable\mysql'
$mysqld = Join-Path $mysqlHome 'bin\mysqld.exe'
$mysql = Join-Path $mysqlHome 'bin\mysql.exe'
foreach ($required in @($mysqld, $mysql)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing bundled MySQL file: $required" }
}

$appData = Resolve-AppPath $env:APP_DATA_DIR 'data'
$legacyData = Join-Path $rootPath 'portable\mysqldata'
$legacyReady = (Test-Path -LiteralPath (Join-Path $legacyData 'mysql') -PathType Container) -and (Test-Path -LiteralPath (Join-Path $legacyData 'auto.cnf') -PathType Leaf)
$dataDir = if ($legacyReady) { $legacyData } else { Join-Path $appData 'mysql' }
$logs = Join-Path $appData 'logs'
$ini = Join-Path $appData 'mysql.ini'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

if (Test-TcpPort $mysqlPort) {
    if (Test-MySqlIdentity $mysql $mysqlPort $dbUser $dbPassword) {
        Write-Host "[ready] MySQL is already available on 127.0.0.1:$mysqlPort."
        exit 0
    }
    throw "Port $mysqlPort is occupied by a different service or MySQL identity. Change MYSQL_PORT and DB_URL together."
}

$freshData = -not (Test-Path -LiteralPath (Join-Path $dataDir 'mysql') -PathType Container)
if ($freshData) {
    if ([string]::IsNullOrWhiteSpace($rootPassword)) { throw 'MYSQL_ROOT_PASSWORD is required only when initializing a new private MySQL data directory.' }
    Write-Host '[preparing] Initializing an empty private MySQL data directory...'
    $initialize = Invoke-Client $mysqld @('--initialize-insecure','--console',"--basedir=$mysqlHome","--datadir=$dataDir") ''
    if ($initialize.ExitCode -ne 0) {
        $tail = (($initialize.StdErr + "`n" + $initialize.StdOut).Trim() -replace '\s+', ' ')
        if ($tail.Length -gt 600) { $tail = $tail.Substring($tail.Length - 600) }
        throw "MySQL initialization failed: $tail"
    }
}

$iniLines = @(
    '[mysqld]',
    ('basedir=' + (Convert-MySqlPath $mysqlHome)),
    ('datadir=' + (Convert-MySqlPath $dataDir)),
    "port=$mysqlPort",
    'bind-address=127.0.0.1',
    'mysqlx=0',
    'skip-log-bin',
    'character-set-server=utf8mb4',
    'collation-server=utf8mb4_unicode_ci',
    ('log-error=' + (Convert-MySqlPath (Join-Path $logs 'mysql.log'))),
    ('pid-file=' + (Convert-MySqlPath (Join-Path $appData 'mysql.pid')))
)
[IO.File]::WriteAllLines($ini, $iniLines, [Text.UTF8Encoding]::new($false))

Write-Host "[starting] Starting private MySQL on 127.0.0.1:$mysqlPort..."
$server = Start-Process -FilePath $mysqld -ArgumentList "--defaults-file=`"$ini`"" -WorkingDirectory $rootPath -WindowStyle Hidden -PassThru
for ($attempt = 0; $attempt -lt 90 -and -not (Test-TcpPort $mysqlPort 300); $attempt++) { Start-Sleep -Seconds 1 }
if (-not (Test-TcpPort $mysqlPort 1000)) {
    if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    throw "MySQL did not become ready within 90 seconds. See $logs\mysql.log"
}

if (Test-MySqlIdentity $mysql $mysqlPort $dbUser $dbPassword) {
    Write-Host '[ready] Existing private MySQL database passed credential verification.'
    exit 0
}
if ($legacyReady) { throw 'The legacy bundled database started, but its configured application credentials were rejected. Existing data was left untouched.' }

$schemaPath = Join-Path $rootPath 'backend\src\main\resources\db\schema.sql'
if (-not (Test-Path -LiteralPath $schemaPath -PathType Leaf)) { throw "Missing database schema: $schemaPath" }
$escapedUser = Escape-SqlLiteral $dbUser
$escapedDbPassword = Escape-SqlLiteral $dbPassword
$escapedRootPassword = Escape-SqlLiteral $rootPassword
$bootstrapSql = @"
CREATE DATABASE IF NOT EXISTS ai_mix_video DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$escapedUser'@'localhost' IDENTIFIED BY '$escapedDbPassword';
CREATE USER IF NOT EXISTS '$escapedUser'@'127.0.0.1' IDENTIFIED BY '$escapedDbPassword';
ALTER USER '$escapedUser'@'localhost' IDENTIFIED BY '$escapedDbPassword';
ALTER USER '$escapedUser'@'127.0.0.1' IDENTIFIED BY '$escapedDbPassword';
GRANT ALL PRIVILEGES ON ai_mix_video.* TO '$escapedUser'@'localhost';
GRANT ALL PRIVILEGES ON ai_mix_video.* TO '$escapedUser'@'127.0.0.1';
FLUSH PRIVILEGES;
"@
$schemaSql = Get-Content -Raw -LiteralPath $schemaPath -Encoding UTF8
$finalizeSql = "`nALTER USER 'root'@'localhost' IDENTIFIED BY '$escapedRootPassword';`nFLUSH PRIVILEGES;`n"
$rootProbe = Invoke-Client $mysql @('--protocol=TCP','-h','127.0.0.1','-P',[string]$mysqlPort,'-u','root','-N','-B','-e','SELECT 1') $rootPassword
$rootCredential = if ($rootProbe.ExitCode -eq 0) { $rootPassword } else { '' }
$bootstrap = Invoke-Client $mysql @('--protocol=TCP','-h','127.0.0.1','-P',[string]$mysqlPort,'-u','root','--default-character-set=utf8mb4') $rootCredential ($bootstrapSql + "`n" + $schemaSql + $finalizeSql)
if ($bootstrap.ExitCode -ne 0) {
    $tail = ($bootstrap.StdErr.Trim() -replace '\s+', ' ')
    if ($tail.Length -gt 600) { $tail = $tail.Substring($tail.Length - 600) }
    throw "Empty database bootstrap failed: $tail"
}
if (-not (Test-MySqlIdentity $mysql $mysqlPort $dbUser $dbPassword)) { throw 'MySQL bootstrap completed, but the application identity could not connect.' }
Write-Host '[ready] Empty Mework database initialized with machine-specific credentials.'
