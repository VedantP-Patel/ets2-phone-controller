$cands = @(
    'D:\Games\Euro Truck Simulator 2\bin\win_x64',
    'C:\Program Files (x86)\Steam\steamapps\common\Euro Truck Simulator 2\bin\win_x64',
    'C:\Program Files\Steam\steamapps\common\Euro Truck Simulator 2\bin\win_x64'
)
$sp = (Get-ItemProperty -Path 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamPath
if (-not $sp) {
    $sp = (Get-ItemProperty -Path 'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam' -ErrorAction SilentlyContinue).InstallPath
}
if ($sp) {
    $cands += Join-Path $sp 'steamapps\common\Euro Truck Simulator 2\bin\win_x64'
    $vdf = Join-Path $sp 'steamapps\libraryfolders.vdf'
    if (Test-Path $vdf) {
        Select-String -Path $vdf -Pattern '"path"\s+"([^"]+)"' | ForEach-Object {
            $lib = $_.Matches[0].Groups[1].Value -replace '\\\\', '\'
            $cands += Join-Path $lib 'steamapps\common\Euro Truck Simulator 2\bin\win_x64'
        }
    }
}
$found = $cands | Where-Object { Test-Path (Join-Path $_ 'eurotrucks2.exe') } | Select-Object -First 1
if ($found) { Write-Output $found }
