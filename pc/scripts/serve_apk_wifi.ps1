param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [int]$Port = 8099
)

if (-not (Test-Path $ApkPath)) {
    Write-Host "  [ERROR] APK file not found: $ApkPath" -ForegroundColor Red
    exit 1
}

$apkBytes = [System.IO.File]::ReadAllBytes($ApkPath)
$apkName = 'ets2-phone-controller.apk'

$ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object {
        $_.IPAddress -notlike '127.*' -and
        $_.IPAddress -notlike '169.254.*' -and
        $_.PrefixOrigin -ne 'WellKnown' -and
        $_.InterfaceAlias -notmatch 'vEthernet|Hyper-V|VMware|VirtualBox|WSL|Tailscale|WireGuard|ZeroTier'
    } |
    Select-Object -ExpandProperty IPAddress -Unique

Write-Host ''
Write-Host '========================================================================' -ForegroundColor Cyan
Write-Host '  WI-FI APK INSTALLER  (No USB cable or Android Studio needed)' -ForegroundColor Cyan
Write-Host '========================================================================' -ForegroundColor Cyan
Write-Host ''
Write-Host '  Open Chrome on your Android phone (on the same Wi-Fi/Hotspot) and go to:' -ForegroundColor White
Write-Host ''
foreach ($ip in $ips) {
    Write-Host "    http://${ip}:${Port}/" -ForegroundColor Green
}
if (-not $ips) {
    Write-Host "    http://<YOUR-PC-IP>:${Port}/" -ForegroundColor Yellow
}
Write-Host ''
Write-Host '  Tap "Download APK" -> Open downloaded file -> Allow "Install unknown apps".' -ForegroundColor Gray
Write-Host '  Press Ctrl+C (or close this window) once installed on your phone.' -ForegroundColor Yellow
Write-Host ''

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
try {
    $listener.Start()
} catch {
    Write-Host "  [ERROR] Could not bind port ${Port}: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "  Copy manually: $ApkPath" -ForegroundColor Yellow
    exit 1
}

$html = @"
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ETS2 Phone Cockpit APK</title>
<style>
  body { font-family: system-ui, sans-serif; background: #0B0E13; color: #E8ECF2; text-align: center; padding: 36px 20px; }
  .card { max-width: 420px; margin: 0 auto; background: #151A22; border: 1px solid #2A323E; border-radius: 16px; padding: 28px 20px; }
  h1 { color: #FFB020; font-size: 22px; margin-top: 0; }
  a.btn { display: inline-block; background: #FFB020; color: #0B0E13; font-weight: 800; text-decoration: none; padding: 14px 28px; border-radius: 12px; margin: 16px 0; font-size: 16px; }
  p { color: #98A2B3; font-size: 14px; line-height: 1.5; }
</style>
</head>
<body>
  <div class="card">
    <h1>ETS2 Phone Cockpit</h1>
    <p>Download and install the controller app on your Android phone:</p>
    <a class="btn" href="/$apkName">Download APK ($([math]::Round($apkBytes.Length / 1MB, 1)) MB)</a>
    <p>If Android prompts you, tap <b>Settings -&gt; Allow from this source</b>, then tap <b>Install</b>.</p>
  </div>
</body>
</html>
"@
$htmlBytes = [System.Text.Encoding]::UTF8.GetBytes($html)

try {
    while ($true) {
        if (-not $listener.Pending()) {
            Start-Sleep -Milliseconds 100
            continue
        }
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $stream.ReadTimeout = 2000
            $buf = New-Object byte[] 2048
            $read = $stream.Read($buf, 0, $buf.Length)
            $req = [System.Text.Encoding]::ASCII.GetString($buf, 0, $read)
            $firstLine = ($req -split "`r`n")[0]

            if ($firstLine -match "GET /$apkName") {
                $hdr = "HTTP/1.1 200 OK`r`nContent-Type: application/vnd.android.package-archive`r`nContent-Disposition: attachment; filename=`"$apkName`"`r`nContent-Length: $($apkBytes.Length)`r`nConnection: close`r`n`r`n"
                $hdrBytes = [System.Text.Encoding]::ASCII.GetBytes($hdr)
                $stream.Write($hdrBytes, 0, $hdrBytes.Length)
                $stream.Write($apkBytes, 0, $apkBytes.Length)
                Write-Host "  [OK] Served APK ($($apkBytes.Length) bytes) to $($client.Client.RemoteEndPoint)" -ForegroundColor Green
            } else {
                $hdr = "HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nContent-Length: $($htmlBytes.Length)`r`nConnection: close`r`n`r`n"
                $hdrBytes = [System.Text.Encoding]::ASCII.GetBytes($hdr)
                $stream.Write($hdrBytes, 0, $hdrBytes.Length)
                $stream.Write($htmlBytes, 0, $htmlBytes.Length)
            }
            $stream.Flush()
        } catch {
        } finally {
            $client.Close()
        }
    }
} finally {
    $listener.Stop()
}
