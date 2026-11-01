package com.ets2controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ets2controller.config.ControllerSettings
import com.ets2controller.ui.theme.CockpitBorder
import com.ets2controller.ui.theme.CockpitPanel
import com.ets2controller.ui.theme.GaugeAccent
import com.ets2controller.ui.theme.getThemeAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentSettings: ControllerSettings,
    onSave: (ControllerSettings) -> Unit,
    onDismiss: () -> Unit
) {
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
    var ffbEnabled by remember { mutableStateOf(currentSettings.forceFeedbackEnabled) }
    var soundEnabled by remember { mutableStateOf(currentSettings.soundEffectsEnabled) }
    var autoCancelBlinkers by remember { mutableStateOf(currentSettings.autoCancelBlinkers) }
    var steeringHapticTension by remember { mutableStateOf(currentSettings.steeringHapticTension) }
    var gravityAnchorEnabled by remember { mutableStateOf(currentSettings.gravityAnchorEnabled) }
    var autoZeroRecenterSensitivity by remember { mutableFloatStateOf(currentSettings.autoZeroRecenterSensitivity) }
    var centerSnapAssistance by remember { mutableStateOf(currentSettings.centerSnapAssistance) }

    val activeAccent = getThemeAccent(clusterTheme)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(CockpitPanel)
                .border(1.5.dp, CockpitBorder, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "⚙️ Controller Tuning & Cockpit",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xFF1B1E26))
                            .border(1.dp, CockpitBorder, androidx.compose.foundation.shape.CircleShape)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✕", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                // Server IP
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("Server IP (Leave blank for Auto-Discovery)") },
                    placeholder = { Text("e.g. 192.168.1.50 or 192.168.42.x") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 1:1 ETS2 Wheel Mode Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("1:1 ETS2 Wheel Mode", color = activeAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            if (wheelMode1to1) "1:1 multi-turn rotation locked to ETS2 truck wheel"
                            else "Handheld Tilt Mode (quick lock for casual holding)",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = wheelMode1to1,
                        onCheckedChange = { wheelMode1to1 = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = activeAccent, checkedTrackColor = activeAccent.copy(alpha = 0.5f))
                    )
                }

                // Steering Deadzone Slider (Universal for Wheel & Tilt Modes)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Steering Deadzone (0.0° - 5.0°):", color = Color.White, fontSize = 13.sp)
                        Text(
                            if (deadzone <= 0.1f) "0.0° (Instant 1:1)" else "${String.format("%.1f", deadzone)}°",
                            color = activeAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Slider(
                        value = deadzone,
                        onValueChange = { deadzone = it },
                        valueRange = 0f..5f,
                        colors = SliderDefaults.colors(thumbColor = activeAccent, activeTrackColor = activeAccent)
                    )
                }

                if (wheelMode1to1) {
                    // Wheel Degrees Range Selector (360°, 540°, 720°, 900°, 1080°)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Wheel Range (ETS2 Default is 900°):", color = Color.White, fontSize = 13.sp)
                            Text("${wheelRange.toInt()}° (±${(wheelRange / 2).toInt()}°)", color = activeAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Slider(
                            value = wheelRange,
                            onValueChange = { wheelRange = it },
                            valueRange = 360f..1080f,
                            steps = 3,
                            colors = SliderDefaults.colors(thumbColor = activeAccent, activeTrackColor = activeAccent)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf(360, 540, 720, 900, 1080).forEach { deg ->
                                Text(
                                    text = "${deg}°",
                                    color = if (wheelRange.toInt() == deg) activeAccent else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = if (wheelRange.toInt() == deg) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.clickable { wheelRange = deg.toFloat() }
                                )
                            }
                        }
                    }
                } else {
                    // Max Lock Angle
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Full Steering Lock Angle:", color = Color.White, fontSize = 13.sp)
                            Text("${maxAngle.toInt()}°", color = activeAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Slider(
                            value = maxAngle,
                            onValueChange = { maxAngle = it },
                            valueRange = 25f..90f,
                            colors = SliderDefaults.colors(thumbColor = activeAccent, activeTrackColor = activeAccent)
                        )
                    }

                    // Response Curve (Non-linearity)
                    Column {
                        val curveDesc = when {
                            curvePower < 1.05f -> "Linear (1:1 Instant)"
                            curvePower < 1.4f -> "Progressive (Stable Highway + Fast Turn)"
                            else -> "Sharp Exponent"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Steering Curve ($curveDesc):", color = Color.White, fontSize = 13.sp)
                            Text(if (curvePower <= 1.05f) "1.00x (Linear)" else "${String.format("%.2f", curvePower)}x", color = activeAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Slider(
                            value = curvePower,
                            onValueChange = { curvePower = it },
                            valueRange = 1.0f..2.0f,
                            colors = SliderDefaults.colors(thumbColor = activeAccent, activeTrackColor = activeAccent)
                        )
                    }
                }

                // Fast Steer Responsiveness (Adaptive 1-Euro Beta)
                Column {
                    val speedDesc = when {
                        filterBeta < 0.06f -> "Damped (Smooth / Casual)"
                        filterBeta < 0.16f -> "Ultra-Responsive (Zero Lag Flicks)"
                        else -> "Instant Twitch (Esports)"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Fast Steer Responsiveness ($speedDesc):", color = Color.White, fontSize = 13.sp)
                        Text("${String.format("%.2f", filterBeta)}", color = activeAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Slider(
                        value = filterBeta,
                        onValueChange = { filterBeta = it },
                        valueRange = 0.02f..0.25f,
                        colors = SliderDefaults.colors(thumbColor = activeAccent, activeTrackColor = activeAccent)
                    )
                    Text("Higher values eliminate trailing phase lag and overshoot during rapid steering flicks", color = Color.Gray, fontSize = 11.sp)
                }

                // Caster Centering Spring Assistance
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Caster Spring Assistance:", color = Color.White, fontSize = 13.sp)
                        Text("${(casterSpring * 100).toInt()}%", color = activeAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Slider(
                        value = casterSpring,
                        onValueChange = { casterSpring = it },
                        valueRange = 0.0f..1.0f,
                        colors = SliderDefaults.colors(thumbColor = activeAccent, activeTrackColor = activeAccent)
                    )
                    Text("Self-aligns the wheel toward center when cruising to eliminate forearm fatigue", color = Color.Gray, fontSize = 11.sp)
                }

                // Cockpit Cluster Illumination Theme
                Column {
                    Text("Cockpit Lighting Theme:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            "SCANIA_AMBER" to "Scania",
                            "VOLVO_CYAN" to "Volvo",
                            "MERCEDES_GREEN" to "Mercedes",
                            "DAF_WHITE" to "DAF"
                        ).forEach { (key, label) ->
                            val isSelected = clusterTheme == key
                            val themeCol = getThemeAccent(key)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) themeCol else Color(0xFF101317))
                                    .border(1.dp, if (isSelected) Color.White else CockpitBorder, RoundedCornerShape(6.dp))
                                    .clickable { clusterTheme = key }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Speedometer Unit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Speedometer Unit", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(if (useMph) "Miles per Hour (MPH)" else "Kilometers per Hour (KM/H)", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = useMph,
                        onCheckedChange = { useMph = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = activeAccent, checkedTrackColor = activeAccent.copy(alpha = 0.5f))
                    )
                }

                // Hand Tremor Smoothing (1-Euro filter)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Resting Tremor Smoothing:", color = Color.White, fontSize = 13.sp)
                        Text("${String.format("%.1f", minCutoff)}", color = activeAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Slider(
                        value = minCutoff,
                        onValueChange = { minCutoff = it },
                        valueRange = 0.4f..2.5f,
                        colors = SliderDefaults.colors(thumbColor = activeAccent, activeTrackColor = activeAccent)
                    )
                }

                // Invert Steering Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Invert Steering Direction", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Flips left/right turning axis", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = invertSteering,
                        onCheckedChange = { invertSteering = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = activeAccent, checkedTrackColor = activeAccent.copy(alpha = 0.5f))
                    )
                }

                // Force Feedback Rumble Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Force Feedback Rumble", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Vibrates phone on rumble strips & collisions", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = ffbEnabled,
                        onCheckedChange = { ffbEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = activeAccent, checkedTrackColor = activeAccent.copy(alpha = 0.5f))
                    )
                }

                // Procedural Mechanical Sound Effects Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Cockpit Audio Synthesis", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Relay tick-tock, air brake hiss, and shifter clicks", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = soundEnabled,
                        onCheckedChange = { soundEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = activeAccent, checkedTrackColor = activeAccent.copy(alpha = 0.5f))
                    )
                }

                // Auto-Cancelling Turn Indicators (ACTI) Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Cancelling Turn Stalks (ACTI)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Mechanical auto-cancel when wheel straightens past center", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoCancelBlinkers,
                        onCheckedChange = { autoCancelBlinkers = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = activeAccent, checkedTrackColor = activeAccent.copy(alpha = 0.5f))
                    )
                }

                // Continuous Steering Tension Micro-Haptics Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Virtual Steering Tension Haptics", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Micro-tactile detent notches every 15° of steering lock", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = steeringHapticTension,
                        onCheckedChange = { steeringHapticTension = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = activeAccent, checkedTrackColor = activeAccent.copy(alpha = 0.5f))
                    )
                }

                // Zero-Drift Gravity Anchor Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Zero-Drift Gravity Anchor", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Anchors steering zero-point to Earth's gravitational field to eliminate post-turn deflection", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = gravityAnchorEnabled,
                        onCheckedChange = { gravityAnchorEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = activeAccent, checkedTrackColor = activeAccent.copy(alpha = 0.5f))
                    )
                }

                // Stationary Zero-Velocity Drift Bleed Sensitivity
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Stationary Recenter Drift Bleed:", color = Color.White, fontSize = 13.sp)
                        Text("${String.format("%.1f", autoZeroRecenterSensitivity)}x", color = activeAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Slider(
                        value = autoZeroRecenterSensitivity,
                        onValueChange = { autoZeroRecenterSensitivity = it },
                        valueRange = 0.5f..2.5f,
                        colors = SliderDefaults.colors(thumbColor = activeAccent, activeTrackColor = activeAccent)
                    )
                    Text("Smoothly bleeds out virtual gyro deflection when holding wheel near center", color = Color.Gray, fontSize = 11.sp)
                }

                // Center Snap Assistance Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hydraulic Center Snap Assistance", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Asymptotically snaps wheel straight out of turns within deadzone pocket", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = centerSnapAssistance,
                        onCheckedChange = { centerSnapAssistance = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = activeAccent, checkedTrackColor = activeAccent.copy(alpha = 0.5f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Fixed Action Bottom Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.White, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        val port = portText.toIntOrNull() ?: 5005
                        onSave(
                            currentSettings.copy(
                                serverIp = ipText.trim(),
                                serverPort = port,
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
                    colors = ButtonDefaults.buttonColors(containerColor = activeAccent)
                ) {
                    Text("Save & Apply", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
}
