$ErrorActionPreference = 'Stop';
$bt = [char]96;

if (Get-Process -Name('eurotrucks2') -ErrorAction('SilentlyContinue')) {
    Write-Host('  [ERROR] Close Euro Truck Simulator 2 first so it does not overwrite controls.sii.') -ForegroundColor('Red');
    exit 1;
};

$myDocs = [Environment]::GetFolderPath('MyDocuments');
$uProf = $env:USERPROFILE;
$candidateRoots = @(
    "$myDocs\Euro Truck Simulator 2",
    "$uProf\OneDrive\Documents\Euro Truck Simulator 2",
    "$uProf\Documents\Euro Truck Simulator 2"
) | Select-Object -Unique;

$files = @();
$candidateRoots | ForEach-Object {
    $root = $_;
    if (Test-Path($root)) {
        @('profiles', 'steam_profiles') | ForEach-Object {
            $p = "$root\$_";
            if (Test-Path($p)) {
                $files += Get-ChildItem -Path($p) -Filter('controls.sii') -Recurse -ErrorAction('SilentlyContinue');
            };
        };
    };
};

$files = $files | Sort-Object FullName -Unique;
if ($files.Count -eq 0) {
    Write-Host('  [ERROR] No controls.sii found.') -ForegroundColor('Red');
    exit 1;
};

$inputAxisMap = @{
    'j_steer'    = 'steering';
    'j_throttle' = 'throttle';
    'j_brake'    = 'brake';
    'j_clutch'   = 'clutch';
    'j_look_x'   = 'look_x';
    'j_look_y'   = 'look_y';
};

$buttonMap = @{
    'horn'          = 'horn';
    'engine'        = 'engine_toggle';
    'light'         = 'lights_cycle';
    'flasher4way'   = 'hazard_lights';
    '4wayflasher'   = 'hazard_lights';
    'lblinker'      = 'blinker_left';
    'rblinker'      = 'blinker_right';
    'parkingbrake'  = 'park_brake';
    'motorbrake'    = 'engine_brake';
    'advpage0'      = 'advisor_map';
    'cruiectrl'     = 'cruise_toggle';
    'cruisectrl'    = 'cruise_toggle';
    'gearup'        = 'shift_up';
    'geardown'      = 'shift_down';
    'wipers'        = 'wipers_up';
    'wipers0'       = 'wipers_down';
    'cam1'          = 'cam_interior';
    'radio'         = 'radio_toggle';
    'radionext'     = 'radio_next';
    'radioprev'     = 'radio_prev';
    'gear0'         = 'shift_neutral';
    'diflock'       = 'diff_lock';
    'liftaxle'      = 'lift_axle';
    'attach'        = 'trailer_couple';
    'hblight'       = 'high_beams';
    'beacon'        = 'beacons';
    'airhorn'       = 'air_horn';
    'retarderup'    = 'retarder_up';
    'retarderdown'  = 'retarder_down';
    'cruiectrlinc'  = 'cruise_up';
    'cruisectrlinc' = 'cruise_up';
    'cruiectrldec'  = 'cruise_down';
    'cruisectrldec' = 'cruise_down';
    'cruiectrlres'  = 'cruise_resume';
    'cruisectrlres' = 'cruise_resume';
    'display'       = 'dash_info';
    'activate'      = 'activate';
    'advpage1'      = 'advisor_job';
    'quicksave'     = 'advisor_service';
    'cam5'          = 'cam_window_lean';
    'cam2'          = 'cam_chase';
    'lighthorn'     = 'light_horn';
    'cam3'          = 'cam_top';
    'lookpos5'      = 'look_left_snap';
    'lookpos4'      = 'look_right_snap';
    'showmirrors'   = 'mirrors_toggle';
    'hudmirrors'    = 'mirrors_toggle';
    'advpage3'      = 'advisor_rest';
    'worldmap'      = 'world_map';
    'menu'          = 'esc_menu';
    'trl_lift_axle' = 'trailer_lift_axle';
    'trailerlift'   = 'trailer_lift_axle';
    'trl_brake'     = 'trailer_brake';
    'trailerbrake'  = 'trailer_brake';
    'acc_dist'      = 'acc_distance';
    'accdistance'   = 'acc_distance';
    'lane_assist'   = 'lka_toggle';
    'lka'           = 'lka_toggle';
    'emer_brake'    = 'aebs_toggle';
    'aebs'          = 'aebs_toggle';
    'susp_f_up'     = 'susp_front_up';
    'suspfrontup'   = 'susp_front_up';
    'susp_f_dn'     = 'susp_front_down';
    'suspfrontdn'   = 'susp_front_down';
    'susp_r_up'     = 'susp_rear_up';
    'susprearup'    = 'susp_rear_up';
    'susp_r_dn'     = 'susp_rear_down';
    'suspreardn'    = 'susp_rear_down';
    'susp_reset'    = 'susp_reset';
    'suspreset'     = 'susp_reset';
    'win_open_l'    = 'window_left';
    'winleft'       = 'window_left';
    'win_open_r'    = 'window_right';
    'winright'      = 'window_right';
    'geardrive'     = 'shift_drive';
    'gearreverse'   = 'shift_reverse';
    'auxlight'      = 'coach_light';
    'photo'         = 'coach_display';
};

$files | ForEach-Object {
    $f = $_;
    Copy-Item -Path($f.FullName) -Destination($f.FullName + '.bak') -Force;
    $lines = [System.Collections.Generic.List[string]](Get-Content -Path($f.FullName) -Encoding('UTF8'));

    $joySlot = $null;
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $parts = $lines[$i].Split($bt);
        if ($parts.Count -eq 3 -and $parts[1] -like '*ets2_phone_cockpit*') {
            if ($parts[0] -match 'device\s+(joy\d*)') {
                $joySlot = $Matches[1];
                break;
            };
        };
    };

    if (-not $joySlot) {
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $parts = $lines[$i].Split($bt);
            if ($parts.Count -eq 3 -and $parts[0] -match 'device\s+(joy\d*)' -and $parts[1] -eq '') {
                $joySlot = $Matches[1];
                $lines[$i] = $parts[0] + $bt + 'sdk.ets2_phone_cockpit' + $bt + $parts[2];
                break;
            };
        };
    };

    if (-not $joySlot) {
        $joySlot = 'joy';
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $parts = $lines[$i].Split($bt);
            if ($parts.Count -eq 3 -and $parts[0] -match 'device\s+joy\s+') {
                $lines[$i] = $parts[0] + $bt + 'sdk.ets2_phone_cockpit' + $bt + $parts[2];
                break;
            };
        };
    };

    $boundAxes = 0;
    $boundBtns = 0;

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i];

        if ($line -match '^(.*constant\s+(?:c_jzthrottle|c_jzbrake)\s+)[\d\.]+(".*)$') {
            $lines[$i] = $Matches[1] + '1.000000' + $Matches[2];
            continue;
        };
        if ($line -match '^(.*constant\s+(?:c_jzsteer|c_steer_dz)\s+)[\d\.]+(".*)$') {
            $lines[$i] = $Matches[1] + '0.000000' + $Matches[2];
            continue;
        };

        $parts = $line.Split($bt);
        if ($parts.Count -ne 3) { continue; };

        $head = $parts[0];
        $expr = $parts[1];
        $tail = $parts[2];

        if ($head -match '"input\s+(\w+)\s+$') {
            $inName = $Matches[1];
            if ($inputAxisMap.ContainsKey($inName)) {
                $chan = $inputAxisMap[$inName];
                $lines[$i] = $head + $bt + ($joySlot + '.' + $chan) + $bt + $tail;
                $boundAxes++;
            };
        }
        elseif ($head -match '"mix\s+(\w+)\s+$') {
            $mixName = $Matches[1];
            if ($buttonMap.ContainsKey($mixName)) {
                $chan = $buttonMap[$mixName];
                $token = $joySlot + '.' + $chan + '?0';
                $cleanExpr = $expr.Trim();
                $cleanExpr = [regex]::Replace($cleanExpr, '^' + [regex]::Escape($joySlot + '.') + '\s*\|\s*', '');
                if ($cleanExpr -notlike "*$token*") {
                    $newExpr = if ($cleanExpr -eq '' -or $cleanExpr -eq 'nil') { $token } else { "$token | $cleanExpr" };
                    $lines[$i] = $head + $bt + $newExpr + $bt + $tail;
                }
                else {
                    $lines[$i] = $head + $bt + $cleanExpr + $bt + $tail;
                };
                $boundBtns++;
            };
        };
    };

    Set-Content -Path($f.FullName) -Value($lines) -Encoding('UTF8');
    Write-Host("  [OK] Patched $($f.FullName) ($joySlot): $boundAxes axes + $boundBtns buttons.") -ForegroundColor('Green');
    $lines | Where-Object { $_ -match '(mix wipers0\s|mix cruiectrl\s|mix cruiectrlinc\s|mix cruiectrldec\s|mix flasher4way\s)' } | ForEach-Object {
        Write-Host("       $_") -ForegroundColor('Cyan');
    };
};