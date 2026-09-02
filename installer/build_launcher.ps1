param([string]$Root = (Split-Path -Parent $PSScriptRoot))

$ErrorActionPreference = 'Stop'
$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\')
$launcherDir = Join-Path $rootPath 'installer\launcher'
$outDir = Join-Path $launcherDir 'output'
$cacheDir = Join-Path $rootPath 'installer\.cache\webview2'
$versionPath = Join-Path $rootPath 'installer\version.iss'
$sourcePath = Join-Path $launcherDir 'MeworkApp.cs'
$iconPath = Join-Path $rootPath 'installer\Mework.ico'
$sdkVersion = '1.0.4191.47'
$nupkg = Join-Path $cacheDir ("microsoft.web.webview2.$sdkVersion.nupkg")
$packageZip = Join-Path $cacheDir ("microsoft.web.webview2.$sdkVersion.zip")
$packageDir = Join-Path $cacheDir 'package'

$compiler = @(
    "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
    "$env:WINDIR\Microsoft.NET\Framework\v4.0.30319\csc.exe"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $compiler) { throw 'Windows .NET Framework C# compiler was not found.' }
if (-not (Test-Path -LiteralPath $sourcePath)) { throw "Launcher source is missing: $sourcePath" }
if (-not (Test-Path -LiteralPath $iconPath)) { throw "Launcher icon is missing: $iconPath" }

New-Item -ItemType Directory -Path $cacheDir,$outDir -Force | Out-Null
if (-not (Test-Path -LiteralPath $nupkg)) {
    Invoke-WebRequest -Uri "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$sdkVersion/microsoft.web.webview2.$sdkVersion.nupkg" -OutFile $nupkg -TimeoutSec 180
}
if (-not (Test-Path -LiteralPath $packageDir)) {
    Copy-Item -LiteralPath $nupkg -Destination $packageZip -Force
    Expand-Archive -LiteralPath $packageZip -DestinationPath $packageDir
}

$core = Join-Path $packageDir 'lib\net462\Microsoft.Web.WebView2.Core.dll'
$forms = Join-Path $packageDir 'lib\net462\Microsoft.Web.WebView2.WinForms.dll'
$loader = Join-Path $packageDir 'runtimes\win-x64\native\WebView2Loader.dll'
foreach($file in @($core,$forms,$loader)) {
    if(-not (Test-Path -LiteralPath $file)) { throw "WebView2 SDK file is missing: $file" }
}
Copy-Item -LiteralPath $core -Destination (Join-Path $outDir 'Microsoft.Web.WebView2.Core.dll') -Force
Copy-Item -LiteralPath $forms -Destination (Join-Path $outDir 'Microsoft.Web.WebView2.WinForms.dll') -Force
Copy-Item -LiteralPath $loader -Destination (Join-Path $outDir 'WebView2Loader.dll') -Force

$versionLine = Get-Content -LiteralPath $versionPath -Encoding UTF8 |
    Where-Object { $_ -match '#define\s+AppVersion\s+"([^"]+)"' } | Select-Object -First 1
if(-not $versionLine) { throw "Could not read AppVersion from $versionPath" }
$version = [regex]::Match($versionLine, '#define\s+AppVersion\s+"([^"]+)"').Groups[1].Value
if($version -notmatch '^\d+\.\d+\.\d+$') { throw "Unsupported launcher version: $version" }
$generated = Join-Path $launcherDir 'AssemblyInfo.generated.cs'
$assemblyInfo = @"
using System.Reflection;
[assembly: AssemblyTitle("Mework")]
[assembly: AssemblyProduct("Mework")]
[assembly: AssemblyCompany("Mework")]
[assembly: AssemblyVersion("$version.0")]
[assembly: AssemblyFileVersion("$version.0")]
"@
[IO.File]::WriteAllText($generated,$assemblyInfo,[Text.UTF8Encoding]::new($false))

try {
    $outputExe = Join-Path $outDir 'Mework.exe'
    $arguments = @(
        '/nologo','/target:winexe','/platform:x64','/optimize+',
        "/out:$outputExe", "/win32icon:$iconPath",
        '/reference:System.Windows.Forms.dll','/reference:System.Drawing.dll',
        '/reference:System.Web.Extensions.dll',"/reference:$core","/reference:$forms",
        $sourcePath,$generated
    )
    & $compiler @arguments
    if($LASTEXITCODE -ne 0) { throw "Desktop launcher compilation failed with exit code $LASTEXITCODE." }
} finally {
    Remove-Item -LiteralPath $generated -Force -ErrorAction SilentlyContinue
}

$runtimeInstaller = Join-Path $outDir 'MicrosoftEdgeWebView2RuntimeInstallerX64.exe'
if(-not (Test-Path -LiteralPath $runtimeInstaller)) {
    Invoke-WebRequest -Uri 'https://go.microsoft.com/fwlink/p/?LinkId=2124703' -OutFile $runtimeInstaller -TimeoutSec 300
}
$windowsPowerShell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
if(-not (Test-Path -LiteralPath $windowsPowerShell)) { throw 'Windows PowerShell is required to validate the WebView2 runtime signature.' }
$signatureProbe = Join-Path $env:TEMP ("mework-signature-" + [Guid]::NewGuid().ToString('N') + '.ps1')
$escapedRuntimeInstaller = $runtimeInstaller.Replace("'", "''")
$probeScript = @"
`$s = Get-AuthenticodeSignature -LiteralPath '$escapedRuntimeInstaller'
[pscustomobject]@{ Status = [string]`$s.Status; Signer = [string]`$s.SignerCertificate.Subject } | ConvertTo-Json -Compress
"@
[IO.File]::WriteAllText($signatureProbe, $probeScript, [Text.UTF8Encoding]::new($false))
try {
    $previousModulePath = $env:PSModulePath
    $env:PSModulePath = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\Modules'
    $signatureJson = & $windowsPowerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $signatureProbe
    if($LASTEXITCODE -ne 0 -or -not $signatureJson) { throw 'Could not inspect the WebView2 runtime signature.' }
} finally {
    $env:PSModulePath = $previousModulePath
    Remove-Item -LiteralPath $signatureProbe -Force -ErrorAction SilentlyContinue
}
$signature = $signatureJson | ConvertFrom-Json
if($signature.Status -ne 'Valid' -or $signature.Signer -notmatch 'Microsoft') {
    throw 'The WebView2 runtime installer did not pass Microsoft signature validation.'
}
Write-Host "[ready] Desktop launcher $version and offline WebView2 runtime prepared."
