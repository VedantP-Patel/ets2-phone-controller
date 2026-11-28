param(
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$bt = [char]96

# Collect all candidate controls.sii locations (Documents, OneDrive, Steam Cloud userdata)
$files = [System.Collections.Generic.List[System.IO.FileInfo]]::new()

$myDocs = [Environment]::GetFolderPath('MyDocuments')
$uProf = $env:USERPROFILE
$docRoots = @(
    "$myDocs\Euro Truck Simulator 2",
    "$uProf\OneDrive\Documents\Euro Truck Simulator 2",
    "$uProf\Documents\Euro Truck Simulator 2"
) | Where-Object { $_ } | Select-Object -Unique

foreach ($root in $docRoots) {
    if (Test-Path $root) {
        foreach ($sub in @('profiles', 'steam_profiles')) {
            $p = Join-Path $root $sub
            if (Test-Path $p) {
                Get-ChildItem -Path $p -Filter 'controls.sii' -Recurse -ErrorAction SilentlyContinue |
                    ForEach-Object { $files.Add($_) }
            }
        }
    }
}

# Steam Cloud profiles: <Steam>\userdata\<uid>\227300\remote\profiles\*
$steamRoots = @(
    (Get-ItemProperty -Path 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamPath,
    (Get-ItemProperty -Path 'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam' -ErrorAction SilentlyContinue).InstallPath,
    'C:\Program Files (x86)\Steam',
    'C:\Program Files\Steam'
) | Where-Object { $_ -and (Test-Path $_) } | ForEach-Object { $_ -replace '/', '\' } | Select-Object -Unique

foreach ($sr in $steamRoots) {
    $ud = Join-Path $sr 'userdata'
    if (Test-Path $ud) {
        Get-ChildItem -Path $ud -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $remote = Join-Path $_.FullName '227300\remote'
            if (Test-Path $remote) {
                Get-ChildItem -Path $remote -Filter 'controls.sii' -Recurse -ErrorAction SilentlyContinue |
                    ForEach-Object { $files.Add($_) }
            }
        }
    }
}

$uniqueFiles = $files | Sort-Object FullName -Unique
if (-not $uniqueFiles -or $uniqueFiles.Count -eq 0) {
    if ($CheckOnly) { exit 3 }
    Write-Host '  [ERROR] No ETS2 profile controls.sii found.' -ForegroundColor Red
    Write-Host '  -> Launch Euro Truck Simulator 2 once, create or load a driver profile,' -ForegroundColor Yellow
    Write-Host '     click OK on "Request to use advanced SDK features", exit ETS2, then press [A].' -ForegroundColor Yellow
    exit 1
}

if ($CheckOnly) {
    $allBound = $true
    foreach ($f in $uniqueFiles) {
        $raw = Get-Content -Path $f.FullName -Raw -ErrorAction SilentlyContinue
        $hasDevice = ($raw -like '*ets2_phone_cockpit*')
        $hasRadio = ($raw -like '*radio_play_pause*') -or ($raw -notmatch 'mix\s+radiotoggle\b')
        $hasCoach = ($raw -like '*coach_display*') -or ($raw -notmatch 'mix\s+photo_mode\b')
        if (-not ($hasDevice -and $hasRadio -and $hasCoach)) {
            $allBound = $false
            break
        }
    }
    if ($allBound) { exit 0 } else { exit 2 }
}

if (Get-Process -Name 'eurotrucks2' -ErrorAction SilentlyContinue) {
    Write-Host '  [ERROR] Close Euro Truck Simulator 2 first so it does not overwrite controls.sii.' -ForegroundColor Red
    exit 1
}

$inputAxisMap = @{
    'j_steer'    = 'steering'
    'j_throttle' = 'throttle'
    'j_brake'    = 'brake'
    'j_clutch'   = 'clutch'
    'j_look_lr'  = 'look_x'
    'j_look_ud'  = 'look_y'
}

$buttonMap = @{
    'horn'          = 'horn'
    'engine'        = 'engine_toggle'
    'light'         = 'lights_cycle'
    'flasher4way'   = 'hazard_lights'
    '4wayflasher'   = 'hazard_lights'
    'lblinker'      = 'blinker_left'
    'rblinker'      = 'blinker_right'
    'parkingbrake'  = 'park_brake'
    'engbraketog'   = 'engine_brake'
    'advmouse'      = 'advisor_map'
    'cruiectrl'     = 'cruise_toggle'
    'cruisectrl'    = 'cruise_toggle'
    'gearup'        = 'shift_up'
    'geardown'      = 'shift_down'
    'wipers'        = 'wipers_up'
    'wipers0'       = 'wipers_down'
    'cam1'          = 'cam_interior'
    'radio'         = 'radio_toggle'
    'radiotoggle'   = 'radio_play_pause'
    'radionext'     = 'radio_next'
    'radioprev'     = 'radio_prev'
    'radioup'       = 'radio_vol_up'
    'radiodown'     = 'radio_vol_down'
    'radiofav'      = 'radio_fav'
    'gear0'         = 'shift_neutral'
    'diflock'       = 'diff_lock'
    'liftaxle'      = 'lift_axle'
    'attach'        = 'trailer_couple'
    'hblight'       = 'high_beams'
    'beacon'        = 'beacons'
    'airhorn'       = 'air_horn'
    'retarderup'    = 'retarder_up'
    'retarderdown'  = 'retarder_down'
    'cruiectrlinc'  = 'cruise_up'
    'cruisectrlinc' = 'cruise_up'
    'cruiectrldec'  = 'cruise_down'
    'cruisectrldec' = 'cruise_down'
    'cruiectrlres'  = 'cruise_resume'
    'cruisectrlres' = 'cruise_resume'
    'display'       = 'dash_info'
    'activate'      = 'activate'
    'advoptions'    = 'advisor_job'
    'quicksave'     = 'advisor_service'
    'cam5'          = 'cam_window_lean'
    'cam2'          = 'cam_chase'
    'lighthorn'     = 'light_horn'
    'cam3'          = 'cam_top'
    'lookpos5'      = 'look_left_snap'
    'lookpos4'      = 'look_right_snap'
    'showmirrors'   = 'mirrors_toggle'
    'hudmirrors'    = 'mirrors_toggle'
    'services'      = 'advisor_rest'
    'navmap'        = 'world_map'
    'menu'          = 'esc_menu'
    'liftaxlet'     = 'trailer_lift_axle'
    'trl_brake'     = 'trailer_brake'
    'trailerbrake'  = 'trailer_brake'
    'accmode'       = 'acc_distance'
    'laneassist'    = 'lka_toggle'
    'embrake'       = 'aebs_toggle'
    'frontsuspup'   = 'susp_front_up'
    'frontsuspdwn'  = 'susp_front_down'
    'rearsuspup'    = 'susp_rear_up'
    'rearsuspdwn'   = 'susp_rear_down'
    'suspreset'     = 'susp_reset'
    'lwinopen'      = 'window_left'
    'rwinopen'      = 'window_right'
    'geardrive'     = 'shift_drive'
    'gearreverse'   = 'shift_reverse'
    'cabinlight'    = 'coach_light'
    'photo_mode'    = 'coach_display'
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

foreach ($f in $uniqueFiles) {
    Copy-Item -Path $f.FullName -Destination ($f.FullName + '.bak') -Force
    $lines = [System.Collections.Generic.List[string]](Get-Content -Path $f.FullName -Encoding UTF8)

    $joySlot = $null
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $parts = $lines[$i].Split($bt)
        if ($parts.Count -eq 3 -and $parts[1] -like '*ets2_phone_cockpit*') {
            if ($parts[0] -match 'device\s+(joy\d*)') {
                $joySlot = $Matches[1]
                break
            }
        }
    }

    if (-not $joySlot) {
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $parts = $lines[$i].Split($bt)
            if ($parts.Count -eq 3 -and $parts[0] -match 'device\s+(joy\d*)' -and $parts[1] -eq '') {
                $joySlot = $Matches[1]
                $lines[$i] = $parts[0] + $bt + 'sdk.ets2_phone_cockpit' + $bt + $parts[2]
                break
            }
        }
    }

    if (-not $joySlot) {
        $joySlot = 'joy'
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $parts = $lines[$i].Split($bt)
            if ($parts.Count -eq 3 -and $parts[0] -match 'device\s+joy\s+') {
                $lines[$i] = $parts[0] + $bt + 'sdk.ets2_phone_cockpit' + $bt + $parts[2]
                break
            }
        }
    }

    $boundAxes = 0
    $boundBtns = 0

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]

        if ($line -match '^(.*constant\s+(?:c_jzthrottle|c_jzbrake)\s+)[\d\.]+(".*)$') {
            $lines[$i] = $Matches[1] + '1.000000' + $Matches[2]
            continue
        }
        if ($line -match '^(.*constant\s+(?:c_jzsteer|c_steer_dz)\s+)[\d\.]+(".*)$') {
            $lines[$i] = $Matches[1] + '0.000000' + $Matches[2]
            continue
        }

        $parts = $line.Split($bt)
        if ($parts.Count -ne 3) { continue }

        $head = $parts[0]
        $expr = $parts[1]
        $tail = $parts[2]

        if ($head -match '"input\s+(\w+)\s+$') {
            $inName = $Matches[1]
            if ($inputAxisMap.ContainsKey($inName)) {
                $chan = $inputAxisMap[$inName]
                $lines[$i] = $head + $bt + ($joySlot + '.' + $chan) + $bt + $tail
                $boundAxes++
            }
        }
        elseif ($head -match '"mix\s+(\w+)\s+$') {
            $mixName = $Matches[1]
            if ($joySlot -eq 'joy' -and $expr -match '\bjoy\.b\d+\?0|(?:short|long)_press\(\)|modifier\([^)]*(?:short|long)_press\(\)\)') {
                $cleanedB = [regex]::Replace($expr, '\|\|\s*modifier\([^`]*?(?:short|long)_press\([^)]*\)[^`]*?\)\s*(?=\|)', '')
                $cleanedB = [regex]::Replace($cleanedB, '\|\|\s*(?:short|long)_press\((?:joy\.b\d+\?0)?\)\s*(?=\|)', '')
                $cleanedB = [regex]::Replace($cleanedB, '\bjoy\.b\d+\?0\s*\|\s*', '')
                $cleanedB = [regex]::Replace($cleanedB, '\s*\|\s*\bjoy\.b\d+\?0', '')
                $cleanedB = [regex]::Replace($cleanedB, '\bjoy\.b\d+\?0', '')
                $cleanedB = [regex]::Replace($cleanedB, '\|\|\s*\|', '|')
                if ($cleanedB -ne $expr) { $expr = $cleanedB.Trim(); $lines[$i] = $head + $bt + $expr + $bt + $tail }
            }
            if ($mixName -eq 'wipersback' -or $mixName -eq 'motorbrake') {
                $staleChan = if ($mixName -eq 'wipersback') { 'wipers_down' } else { 'engine_brake' }
                $cleanedW = [regex]::Replace($expr, [regex]::Escape($joySlot + '.' + $staleChan + '?0') + '\s*\|\s*', '')
                $cleanedW = [regex]::Replace($cleanedW, '\s*\|\s*' + [regex]::Escape($joySlot + '.' + $staleChan + '?0'), '')
                if ($cleanedW -ne $expr) { $lines[$i] = $head + $bt + $cleanedW.Trim() + $bt + $tail }
                continue
            }
            if ($buttonMap.ContainsKey($mixName)) {
                $chan = $buttonMap[$mixName]
                $token = $joySlot + '.' + $chan + '?0'
                $cleanExpr = $expr.Trim()
                $cleanExpr = [regex]::Replace($cleanExpr, '^' + [regex]::Escape($joySlot + '.') + '\s*\|\s*', '')
                if ($cleanExpr -notlike "*$token*") {
                    $newExpr = if ($cleanExpr -eq '' -or $cleanExpr -eq 'nil') { $token } else { "$token | $cleanExpr" }
                    $lines[$i] = $head + $bt + $newExpr + $bt + $tail
                }
                else {
                    $lines[$i] = $head + $bt + $cleanExpr + $bt + $tail
                }
                $boundBtns++
            }
        }
    }

    [System.IO.File]::WriteAllLines($f.FullName, $lines, $utf8NoBom)
    Write-Host ("  [OK] Patched $($f.FullName) ($joySlot): $boundAxes axes + $boundBtns buttons.") -ForegroundColor Green
}
