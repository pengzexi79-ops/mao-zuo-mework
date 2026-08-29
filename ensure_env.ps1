param([string]$Root = $PSScriptRoot)
$ErrorActionPreference = 'Stop'

$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\')
$envPath = Join-Path $rootPath '.env'

function New-RandomBytes([int]$Length) {
    $bytes = New-Object byte[] $Length
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return $bytes
}

function New-HexSecret([int]$Length = 32) {
    return ((New-RandomBytes $Length | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Test-PortAvailable([int]$Port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $listener.Start(); return $true } catch { return $false } finally { try { $listener.Stop() } catch { } }
}

function Select-Port([int]$Preferred, [int]$Range = 200) {
    for ($port = $Preferred; $port -lt ($Preferred + $Range); $port++) {
        if (Test-PortAvailable $port) { return $port }
    }
    throw "No free local port in range $Preferred..$($Preferred + $Range - 1)"
}

function Read-EnvMap([string]$Path) {
    $map = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $map }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { continue }
        $map[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1)
    }
    return $map
}

function Append-MissingSecret([System.Collections.Generic.List[string]]$Lines, [hashtable]$Values, [string]$Name, [string]$Value) {
    if ($Values.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace([string]$Values[$Name])) { return }
    $Lines.Add("$Name=$Value")
    $Values[$Name] = $Value
}

if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    $requestedAppPort = if ($env:APP_PORT) { [int]$env:APP_PORT } elseif ($env:PORT) { [int]$env:PORT } else { 8760 }
    $requestedMysqlPort = if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3306 }
    $appPort = if ($env:APP_PORT -or $env:PORT) { $requestedAppPort } else { Select-Port $requestedAppPort }
    $mysqlPort = if ($env:MYSQL_PORT) { $requestedMysqlPort } else { Select-Port $requestedMysqlPort }
    $dbPassword = New-HexSecret
    $rootPassword = New-HexSecret
    $masterKey = [Convert]::ToBase64String((New-RandomBytes 48))
    $lines = @(
        '# Generated locally by Mework. Do not share or commit this file.',
        'APP_BIND_ADDRESS=127.0.0.1',
        "PORT=$appPort",
        "MYSQL_PORT=$mysqlPort",
        "DB_URL=jdbc:mysql://127.0.0.1:$mysqlPort/ai_mix_video?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_general_ci&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false&rewriteBatchedStatements=true",
        'DB_USERNAME=mixcut',
        "DB_PASSWORD=$dbPassword",
        "MYSQL_ROOT_PASSWORD=$rootPassword",
        "APP_MASTER_KEY=$masterKey",
        'APP_ACCESS_TOKEN=',
        'APP_CORS_ALLOWED_ORIGINS=http://localhost:5273,http://127.0.0.1:5273,http://localhost:5173,http://127.0.0.1:5173',
        'APP_FREESOUND_API_KEY=',
        'APP_PIXABAY_API_KEY=',
        'APP_PEXELS_API_KEY=',
        'APP_UNSPLASH_API_KEY=',
        'APP_ALLOW_LOGIN_CRAWL=false'
    )
    [IO.File]::WriteAllLines($envPath, $lines, [Text.UTF8Encoding]::new($false))
    Write-Host "[ready] Created private local configuration (app port $appPort, MySQL port $mysqlPort)."
    exit 0
}

$values = Read-EnvMap $envPath
$lines = [System.Collections.Generic.List[string]]::new()
Get-Content -LiteralPath $envPath -Encoding UTF8 | ForEach-Object { $lines.Add($_) }
$before = $lines.Count
Append-MissingSecret $lines $values 'DB_PASSWORD' (New-HexSecret)
Append-MissingSecret $lines $values 'MYSQL_ROOT_PASSWORD' (New-HexSecret)
Append-MissingSecret $lines $values 'APP_MASTER_KEY' ([Convert]::ToBase64String((New-RandomBytes 48)))
if ($lines.Count -ne $before) {
    [IO.File]::WriteAllLines($envPath, $lines, [Text.UTF8Encoding]::new($false))
    Write-Host '[ready] Added missing private credentials to the existing local configuration.'
} else {
    Write-Host '[ready] Existing local configuration is complete.'
}
