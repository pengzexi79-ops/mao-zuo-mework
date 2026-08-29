# Looper: runs scan.ps1 every 20 seconds forever. Detached process, owned by the monitor.
while ($true) {
  try {
    & 'D:\deepseek\zcode-monitor\scan.ps1'
  } catch {
    try { Add-Content -LiteralPath 'D:\deepseek\zcode-monitor\looper.err.log' -Value ("[$(Get-Date -Format 'HH:mm:ss')] $($_.Exception.Message)") -Encoding UTF8 } catch {}
  }
  Start-Sleep -Seconds 20
}
