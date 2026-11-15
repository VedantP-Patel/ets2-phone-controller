package com.ets2controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.config.ControllerSettings
import com.ets2controller.ui.theme.*

private enum class TuningTab(val title: String) {
    STEERING("STEERING"),
    FEEDBACK("FEEDBACK"),
    COCKPIT("COCKPIT"),
    CONNECTION("CONNECTION")
}

@Composable
fun SettingsDialog(
    currentSettings: ControllerSettings,
    onSave: (ControllerSettings) -> Unit,
    onResetPinnedSlot: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var activeTab by remember { mutableStateOf(TuningTab.STEERING) }

    var ipText by remember { mutableStateOf(currentSettings.serverIp) }
    var portText by remember { mutableStateOf(currentSettings.serverPort.toString()) }
    var deadzone by remember { mutableFloatStateOf(currentSettings.steeringDeadzoneDeg) }
    var maxAngle by remember { mutableFloatStateOf(currentSettings.steeringMaxAngleDeg) }
    var curvePower by remember { mutableFloatStateOf(currentSettings.steeringCurvePower) }
    var minCutoff by remember { mutableFloatStateOf(currentSettings.filterMinCutoff) }
    var filterBeta by remember { mutableFloatStateOf(currentSettings.filterBeta) }
    var invertSteering by remember { mutableStateOf(currentSettings.invertSteering) }
    var wheelMode1to1 by remember { mutableStateOf(currentSettings.wheelMode1to1) }
    var wheelRange by remember { mutableFloatStateOf(currentSettings.wheelDegreesRange) }
    var casterSpring by remember { mutableFloatStateOf(currentSettings.casterSpringStrength) }
    var clusterTheme by remember { mutableStateOf(currentSettings.clusterTheme) }
    var useMph by remember { mutableStateOf(currentSettings.speedUnitMph) }
    var hapticEnabled by remember { mutableStateOf(currentSettings.hapticFeedbackEnabled) }
    var ffbEnabled by remember { mutableStateOf(currentSettings.forceFeedbackEnabled) }
    var soundEnabled by remember { mutableStateOf(currentSettings.soundEffectsEnabled) }
    var autoCancelBlinkers by remember { mutableStateOf(currentSettings.autoCancelBlinkers) }
    var steeringHapticTension by remember { mutableStateOf(currentSettings.steeringHapticTension) }
    var gravityAnchorEnabled by remember { mutableStateOf(currentSettings.gravityAnchorEnabled) }
    var autoZeroRecenterSensitivity by remember { mutableFloatStateOf(currentSettings.autoZeroRecenterSensitivity) }
    var centerSnapAssistance by remember { mutableStateOf(currentSettings.centerSnapAssistance) }

    val activeAccent = getThemeAccent(clusterTheme)

    // Full-screen edge-to-edge overlay (eliminates Dialog window inset peeking)
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .clickable(enabled = false) {}
    ) {
        // Left Sidebar Navigation Rail
        Column(
            modifier = Modifier
                .width(172.dp)
                .fillMaxHeight()
                .background(Color(0xFF0A0C10))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Tuning",
                color = TextBright,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
            )

            TuningTab.entries.forEach { tab ->
                val selected = activeTab == tab
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) activeAccent else CockpitTile)
                        .border(1.dp, if (selected) activeAccent else CockpitBorder, RoundedCornerShape(12.dp))
                        .clickable { activeTab = tab }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = tab.title,
                        color = if (selected) Color(0xFF0D1015) else TextBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp
                    )
                }
            }
        }

        // Right Content Area + Fixed Bottom Bar
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(CockpitPanel)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                when (activeTab) {
                    TuningTab.STEERING -> {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Left Column: Quick Preset | Wheel Range | Square Checkboxes
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("QUICK PRESET", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val presets = listOf(
                                            "Highway" to {
                                                wheelMode1to1 = true; wheelRange = 900f; deadzone = 0.5f
                                                minCutoff = 1.0f; filterBeta = 0.03f; casterSpring = 0.20f
                                                autoZeroRecenterSensitivity = 1.2f
                                            },
                                            "1:1" to {
                                                wheelMode1to1 = true; wheelRange = 900f; deadzone = 0.5f
                                                minCutoff = 1.3f; filterBeta = 0.02f; casterSpring = 0.0f
                                                autoZeroRecenterSensitivity = 1.0f
                                            },
                                            "Yard" to {
                                                wheelMode1to1 = true; wheelRange = 540f; deadzone = 0.2f
                                                minCutoff = 1.8f; filterBeta = 0.12f; casterSpring = 0.0f
                                                autoZeroRecenterSensitivity = 0.8f
                                            }
                                        )
                                        presets.forEach { (name, apply) ->
                                            val isCurrent = (name == "1:1" && wheelRange.toInt() == 900 && casterSpring == 0f) ||
                                                            (name == "Highway" && wheelRange.toInt() == 900 && casterSpring > 0f) ||
                                                            (name == "Yard" && wheelRange.toInt() == 540)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(42.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isCurrent) activeAccent else CockpitTile)
                                                    .border(1.dp, if (isCurrent) activeAccent else CockpitBorder, RoundedCornerShape(10.dp))
                                                    .clickable { apply() },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = name,
                                                    color = if (isCurrent) Color(0xFF0D1015) else TextBright,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("WHEEL RANGE", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(360, 540, 720, 900, 1080).forEach { deg ->
                                            val selected = wheelRange.toInt() == deg
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(42.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (selected) activeAccent else CockpitTile)
                                                    .border(1.dp, if (selected) activeAccent else CockpitBorder, RoundedCornerShape(10.dp))
                                                    .clickable { wheelRange = deg.toFloat() },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "$deg",
                                                    color = if (selected) Color(0xFF0D1015) else TextBright,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                SquareCheckboxRow(
                                    label = "1:1 ETS2 wheel mode",
                                    checked = wheelMode1to1,
                                    accentColor = activeAccent,
                                    onCheckedChange = { wheelMode1to1 = it }
                                )

                                SquareCheckboxRow(
                                    label = "Invert steering",
                                    checked = invertSteering,
                                    accentColor = activeAccent,
                                    onCheckedChange = { invertSteering = it }
                                )
                            }

                            // Right Column: All 5 Sleek Circular-Knob Amber Sliders
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                TuningSliderRow(
                                    label = "Deadzone",
                                    valueLabel = "${"%.1f".format(deadzone)}°",
                                    value = deadzone,
                                    range = 0f..5f,
                                    accentColor = activeAccent,
                                    onValueChange = { deadzone = it }
                                )
                                val tremorDisplay = ((2.5f - minCutoff) / 2.1f).coerceIn(0f, 1f)
                                TuningSliderRow(
                                    label = "Tremor smoothing",
                                    valueLabel = "${"%.1f".format(tremorDisplay)}",
                                    value = tremorDisplay,
                                    range = 0f..1f,
                                    accentColor = activeAccent,
                                    onValueChange = { minCutoff = (2.5f - it * 2.1f).coerceIn(0.4f, 2.5f) }
                                )
                                TuningSliderRow(
                                    label = "Fast-steer response",
                                    valueLabel = "${"%.2f".format(filterBeta)}",
                                    value = filterBeta,
                                    range = 0.02f..0.25f,
                                    accentColor = activeAccent,
                                    onValueChange = { filterBeta = it }
                                )
                                TuningSliderRow(
                                    label = "Caster self-centering",
                                    valueLabel = "${(casterSpring * 100).toInt()}%",
                                    value = casterSpring,
                                    range = 0f..1f,
                                    accentColor = activeAccent,
                                    onValueChange = { casterSpring = it }
                                )
                                TuningSliderRow(
                                    label = "Drift bleed",
                                    valueLabel = "${"%.1f".format(autoZeroRecenterSensitivity)}x",
                                    value = autoZeroRecenterSensitivity,
                                    range = 0.5f..2.5f,
                                    accentColor = activeAccent,
                                    onValueChange = { autoZeroRecenterSensitivity = it }
                                )
                            }
                        }
                    }

                    TuningTab.FEEDBACK -> {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TuningToggleRow("Haptic feedback & confirm buzz", "0.4s hold buzz and tactile clicks", hapticEnabled, activeAccent) { hapticEnabled = it }
                            TuningToggleRow("Force feedback rumble", "Vibrates on road bumps and lateral G spikes", ffbEnabled, activeAccent) { ffbEnabled = it }
                            TuningToggleRow("Virtual steering tension detents", "Micro-ticks every 15° of wheel rotation", steeringHapticTension, activeAccent) { steeringHapticTension = it }
                            TuningToggleRow("Cockpit audio synthesis", "Relay blinker tick-tock, air brake hiss, air horn", soundEnabled, activeAccent) { soundEnabled = it }
                            TuningToggleRow("Auto-cancelling turn stalks", "Cancels indicator when wheel returns past center", autoCancelBlinkers, activeAccent) { autoCancelBlinkers = it }
                            TuningToggleRow("Zero-drift gravity anchor", "Anchors horizon to prevent gyro drift", gravityAnchorEnabled, activeAccent) { gravityAnchorEnabled = it }
                            TuningToggleRow("Hydraulic center snap", "Smoothly centers wheel inside deadzone pocket", centerSnapAssistance, activeAccent) { centerSnapAssistance = it }
                        }
                    }

                    TuningTab.COCKPIT -> {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("CLUSTER ACCENT COLOR", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf(
                                    "SCANIA_AMBER" to "Scania Amber",
                                    "VOLVO_CYAN" to "Volvo Cyan",
                                    "MERCEDES_GREEN" to "Mercedes Green",
                                    "DAF_WHITE" to "DAF White"
                                ).forEach { (key, title) ->
                                    val selected = clusterTheme == key
                                    val col = getThemeAccent(key)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (selected) col else CockpitTile)
                                            .border(1.dp, if (selected) col else CockpitBorder, RoundedCornerShape(10.dp))
                                            .clickable { clusterTheme = key },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = title,
                                            color = if (selected) Color(0xFF0D1015) else TextBright,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            TuningToggleRow(
                                title = "Imperial Units (MPH)",
                                subtitle = if (useMph) "Displaying speed in MPH" else "Displaying speed in KM/H",
                                checked = useMph,
                                accentColor = activeAccent,
                                onCheckedChange = { useMph = it }
                            )

                            OutlinedButton(
                                onClick = onResetPinnedSlot,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Reset Bottom Slot 5 to DIFF LOCK", color = TextBright)
                            }
                        }
                    }

                    TuningTab.CONNECTION -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text("WINDOWS PC TARGET", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            OutlinedTextField(
                                value = ipText,
                                onValueChange = { ipText = it },
                                label = { Text("PC IPv4 Address (Leave blank for Auto-Discovery)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it },
                                label = { Text("UDP Control Port (Default: 5005)") },
                                singleLine = true,
                                modifier = Modifier.width(240.dp)
                            )
                        }
                    }
                }
            }

            // Bottom Action Bar: Cancel + Save & apply
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .border(1.dp, CockpitBorder)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextBright, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = {
                        onSave(
                            currentSettings.copy(
                                serverIp = ipText.trim(),
                                serverPort = portText.toIntOrNull() ?: 5005,
                                steeringDeadzoneDeg = deadzone,
                                steeringMaxAngleDeg = maxAngle,
                                steeringCurvePower = curvePower,
                                filterMinCutoff = minCutoff,
                                filterBeta = filterBeta,
                                invertSteering = invertSteering,
                                wheelMode1to1 = wheelMode1to1,
                                wheelDegreesRange = wheelRange,
                                casterSpringStrength = casterSpring,
                                clusterTheme = clusterTheme,
                                speedUnitMph = useMph,
                                hapticFeedbackEnabled = hapticEnabled,
                                forceFeedbackEnabled = ffbEnabled,
                                soundEffectsEnabled = soundEnabled,
                                autoCancelBlinkers = autoCancelBlinkers,
                                steeringHapticTension = steeringHapticTension,
                                gravityAnchorEnabled = gravityAnchorEnabled,
                                autoZeroRecenterSensitivity = autoZeroRecenterSensitivity,
                                centerSnapAssistance = centerSnapAssistance
                            )
                        )
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = activeAccent),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text("Save & apply", color = Color(0xFF0D1015), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SquareCheckboxRow(
    label: String,
    checked: Boolean,
    accentColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextBright, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) accentColor else Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Canvas(modifier = Modifier.size(14.dp)) {
                    val check = Path().apply {
                        moveTo(size.width * 0.18f, size.height * 0.52f)
                        lineTo(size.width * 0.42f, size.height * 0.76f)
                        lineTo(size.width * 0.84f, size.height * 0.26f)
                    }
                    drawPath(
                        path = check,
                        color = Color(0xFF0D1015),
                        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }
    }
}

@Composable
private fun TuningSliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accentColor: Color,
    onValueChange: (Float) -> Unit
) {
    val norm = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(valueLabel, color = accentColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(range) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onValueChange(range.start + frac * (range.endInclusive - range.start))
                    }
                }
                .pointerInput(range) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onValueChange(range.start + frac * (range.endInclusive - range.start))
                    }
                }
        ) {
            val trackH = 6.dp.toPx()
            val knobR = 8.dp.toPx()
            val padX = knobR
            val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
            val cy = size.height / 2f
            val knobX = padX + norm * usableW

            drawRoundRect(
                color = CockpitBorder,
                topLeft = Offset(padX, cy - trackH / 2f),
                size = Size(usableW, trackH),
                cornerRadius = CornerRadius(trackH / 2f)
            )
            drawRoundRect(
                color = accentColor,
                topLeft = Offset(padX, cy - trackH / 2f),
                size = Size((knobX - padX).coerceAtLeast(0f), trackH),
                cornerRadius = CornerRadius(trackH / 2f)
            )
            drawCircle(
                color = accentColor,
                radius = knobR,
                center = Offset(knobX, cy)
            )
        }
    }
}

@Composable
private fun TuningToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accentColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextBright, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = accentColor, checkedTrackColor = accentColor.copy(alpha = 0.45f))
        )
    }
}