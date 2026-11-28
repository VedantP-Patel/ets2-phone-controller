param(
    [string]$OverrideFile = ""
)

$cands = [System.Collections.Generic.List[string]]::new()

# 1. Explicit user override saved by start.bat [P]
if (-not $OverrideFile) {
    $OverrideFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'ets2_path.txt'
}
if (Test-Path $OverrideFile) {
    $saved = (Get-Content $OverrideFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($saved) {
        $saved = $saved.Trim().Trim('"')
        if ($saved -match 'eurotrucks2\.exe$') { $saved = Split-Path -Parent $saved }
        if (Test-Path (Join-Path $saved 'bin\win_x64\eurotrucks2.exe')) {
            $cands.Add((Join-Path $saved 'bin\win_x64'))
        } else {
            $cands.Add($saved)
        }
    }
}

# 2. Steam registry + libraryfolders.vdf
$steamPaths = [System.Collections.Generic.List[string]]::new()
$sp1 = (Get-ItemProperty -Path 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamPath
$sp2 = (Get-ItemProperty -Path 'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam' -ErrorAction SilentlyContinue).InstallPath
$sp3 = (Get-ItemProperty -Path 'HKLM:\SOFTWARE\Valve\Steam' -ErrorAction SilentlyContinue).InstallPath
foreach ($sp in @($sp1, $sp2, $sp3)) {
    if ($sp -and (Test-Path $sp)) { $steamPaths.Add(($sp -replace '/', '\')) }
}

foreach ($sp in ($steamPaths | Select-Object -Unique)) {
    $cands.Add((Join-Path $sp 'steamapps\common\Euro Truck Simulator 2\bin\win_x64'))
    $vdf = Join-Path $sp 'steamapps\libraryfolders.vdf'
    if (Test-Path $vdf) {
        Select-String -Path $vdf -Pattern '"path"\s+"([^"]+)"' -ErrorAction SilentlyContinue | ForEach-Object {
            $lib = $_.Matches[0].Groups[1].Value -replace '\\\\', '\'
            $cands.Add((Join-Path $lib 'steamapps\common\Euro Truck Simulator 2\bin\win_x64'))
        }
    }
}

# 3. Windows Uninstall registry entries (Steam, Retail, standalone installs)
$uninstRoots = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
Get-ItemProperty $uninstRoots -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -like '*Euro Truck Simulator 2*' -and $_.InstallLocation } |
    ForEach-Object {
        $loc = $_.InstallLocation.Trim().Trim('"')
        $cands.Add((Join-Path $loc 'bin\win_x64'))
        $cands.Add($loc)
    }

# 4. Standard & common library paths across all local fixed drives (C..Z)
$drives = [System.IO.DriveInfo]::GetDrives() |
    Where-Object { $_.DriveType -eq 'Fixed' -and $_.IsReady } |
    ForEach-Object { $_.RootDirectory.FullName.TrimEnd('\') }

foreach ($d in $drives) {
    $cands.Add("$d\Program Files (x86)\Steam\steamapps\common\Euro Truck Simulator 2\bin\win_x64")
    $cands.Add("$d\Program Files\Steam\steamapps\common\Euro Truck Simulator 2\bin\win_x64")
    $cands.Add("$d\SteamLibrary\steamapps\common\Euro Truck Simulator 2\bin\win_x64")
    $cands.Add("$d\Steam\steamapps\common\Euro Truck Simulator 2\bin\win_x64")
    $cands.Add("$d\Games\Euro Truck Simulator 2\bin\win_x64")
    $cands.Add("$d\Euro Truck Simulator 2\bin\win_x64")
}

$found = $cands |
    Where-Object { $_ } |
    Select-Object -Unique |
    Where-Object { Test-Path (Join-Path $_ 'eurotrucks2.exe') } |
    Select-Object -First 1

if ($found) { Write-Output $found }
