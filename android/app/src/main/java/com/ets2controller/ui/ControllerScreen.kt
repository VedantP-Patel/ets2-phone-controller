package com.ets2controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.config.ControllerSettings
import com.ets2controller.network.TruckTelemetryV2
import com.ets2controller.ui.theme.*
import kotlin.math.abs

@Composable
fun ControllerScreen(
    isConnected: Boolean,
    connectedIp: String,
    packetsPerSec: Int,
    savedManualIp: String,
    onSetManualIp: (String) -> Unit,
    steeringProvider: () -> Float,
    rawAngleProvider: () -> Float,
    throttle: Float,
    brake: Float,
    clutch: Float,
    buttonMask: Long,
    coachSmoothMode: Boolean,
    settings: ControllerSettings,
    telemetry: TruckTelemetryV2,
    onThrottleChange: (Float) -> Unit,
    onBrakeChange: (Float) -> Unit,
    onClutchChange: (Float) -> Unit,
    onButtonStateChange: (Int, Boolean) -> Unit,
    onPulseBit: (Int) -> Unit,
    onToggleCoachMode: () -> Unit,
    onCalibrateClick: () -> Unit,
    onSaveSettings: (ControllerSettings) -> Unit,
    onLookChange: (Float, Float) -> Unit
) {
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAuxSwitchboard by remember { mutableStateOf(false) }
    var showConnectionDialog by remember { mutableStateOf(false) }
    val activeAccent = getThemeAccent(settings.clusterTheme)

    val leftOuterButtons = remember {
        listOf(
            ActionButtonDef(bitIndex = 4, icon = "TURN_LEFT", label = "L. TURN", customColor = Color(0xFF00E676)),
            ActionButtonDef(
                bitIndex = 2,
                icon = "☀",
                label = "LIGHTS ↕",
                swipeUpBit = 21,
                swipeDownBit = 22,
                longPressBit = 35
            ),
            ActionButtonDef(
                bitIndex = 0,
                icon = "((!))",
                label = "HORN / AIR",
                isPrimary = true,
                swipeUpBit = 23
            ),
            ActionButtonDef(bitIndex = 6, icon = "(P)", label = "PARK BRK", isWarning = true)
        )
    }

    val leftInnerButtons = remember {
        listOf(
            ActionButtonDef(bitIndex = 5, icon = "TURN_RIGHT", label = "R. TURN", customColor = Color(0xFF00E676)),
            ActionButtonDef(
                bitIndex = 7,
                icon = "RETARDER",
                label = "RETARDER ↕",
                customColor = Color(0xFFFF9100),
                swipeUpBit = 24,
                swipeDownBit = 25
            ),
            ActionButtonDef(
                bitIndex = 9,
                icon = "CC",
                label = "CRUISE ↕",
                customColor = Color(0xFF2979FF),
                swipeUpBit = 26,
                swipeDownBit = 27,
                longPressBit = 28
            ),
            ActionButtonDef(
                bitIndex = 30,
                icon = "⏎",
                label = "ENTER / ENG",
                customColor = Color(0xFF00E676),
                longPressBit = 1
            )
        )
    }

    val centerUtilityButtons = remember {
        listOf(
            ActionButtonDef(bitIndex = 3, icon = "▲", label = "HAZARD", isWarning = true),
            ActionButtonDef(
                bitIndex = 8,
                icon = "GPS",
                label = "NAV / MAP ↕",
                swipeUpBit = 29,
                swipeDownBit = 31,
                longPressBit = 42
            ),
            ActionButtonDef(
                bitIndex = 12,
                icon = "WIPER",
                label = "WIPERS ↕",
                swipeDownBit = 36
            ),
            ActionButtonDef(
                bitIndex = 13,
                icon = "CAM",
                label = "VIEW ↕",
                swipeUpBit = 34,
                swipeDownBit = 33,
                longPressBit = 37
            ),
            ActionButtonDef(bitIndex = -2, icon = "AUX", label = "SWITCHES", isPrimary = true)
        )
    }

    val gearButtons = remember {
        listOf(
            ActionButtonDef(bitIndex = 10, icon = "▲", label = "SHIFT UP", isPrimary = true),
            ActionButtonDef(
                bitIndex = 17,
                icon = "N",
                label = "NEUTRAL",
                longPressBit = 18
            ),
            ActionButtonDef(bitIndex = 11, icon = "▼", label = "SHIFT DN", isPrimary = true)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (telemetry.isBlindSpotLeft) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(5.dp)
                    .fillMaxHeight(0.75f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFFF1744))
            )
        }
        if (telemetry.isBlindSpotRight) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(5.dp)
                    .fillMaxHeight(0.75f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFFF1744))
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top Telemetry & Link Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tap-to-Configure Connection Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CockpitPanel)
                        .border(
                            1.dp,
                            if (isConnected) ConnectedGreen.copy(alpha = 0.5f) else CockpitBorder,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { showConnectionDialog = true }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    val statusColor = when {
                        isConnected && telemetry.roundTripPingMs < 25 -> ConnectedGreen
                        isConnected -> Color(0xFFFFA726)
                        connectedIp.isNotEmpty() -> Color(0xFFFFA726)
                        else -> DisconnectedRed
                    }
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isConnected -> "LINKED: $connectedIp  |  ${packetsPerSec}Hz  |  ${telemetry.roundTripPingMs}ms"
                            connectedIp.isNotEmpty() -> "TARGET: $connectedIp (WAITING FOR ETS2... TAP FOR IP)"
                            else -> "SCANNING LAN... (TAP TO ENTER PC IP)"
                        },
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val gPeak = maxOf(abs(telemetry.lateralG), abs(telemetry.longitudinalG))
                    val gColor = when {
                        gPeak > 0.30f -> Color(0xFFFF1744)
                        gPeak > 0.20f -> Color(0xFFFF9100)
                        else -> ConnectedGreen
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (coachSmoothMode) activeAccent.copy(alpha = 0.2f) else CockpitPanel)
                            .border(1.dp, if (coachSmoothMode) activeAccent else CockpitBorder, RoundedCornerShape(8.dp))
                            .clickable { onToggleCoachMode() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (coachSmoothMode) "COACH SMOOTH  ${"%.2f".format(gPeak)}G" else "TRUCK STD  ${"%.2f".format(gPeak)}G",
                            color = gColor,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CockpitPanel)
                            .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                            .clickable { showSettingsDialog = true }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("SETUP", color = activeAccent, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Main Cockpit Grid
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var outerMask = buttonMask.toInt()
                if (telemetry.isParkBrake) outerMask = outerMask or (1 shl 6)
                ButtonColumn(
                    buttons = leftOuterButtons,
                    activeMask = outerMask,
                    onPressStateChange = onButtonStateChange,
                    modifier = Modifier.width(82.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                var innerMask = buttonMask.toInt()
                if (telemetry.isHighBeams) innerMask = innerMask or (1 shl 21)
                if (telemetry.isRetarderActive) innerMask = innerMask or (1 shl 7)
                if (telemetry.isCruiseActive) innerMask = innerMask or (1 shl 9)
                ButtonColumn(
                    buttons = leftInnerButtons,
                    activeMask = innerMask,
                    onPressStateChange = onButtonStateChange,
                    modifier = Modifier.width(82.dp)
                )

                // Center Column: Digital Instrument Cluster + Rotating Truck Wheel + Bottom Utility Row
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    var lookXOffset by remember { mutableFloatStateOf(0f) }
                    var lookYOffset by remember { mutableFloatStateOf(0f) }

                    DashboardGauges(
                        telemetry = telemetry,
                        useMph = settings.speedUnitMph,
                        accentColor = activeAccent,
                        modifier = Modifier
                            .fillMaxWidth(0.97f)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragEnd = {
                                        lookXOffset = 0f
                                        lookYOffset = 0f
                                        onLookChange(0f, 0f)
                                    },
                                    onDragCancel = {
                                        lookXOffset = 0f
                                        lookYOffset = 0f
                                        onLookChange(0f, 0f)
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        lookXOffset = (lookXOffset + drag.x / (size.width * 0.35f)).coerceIn(-1f, 1f)
                                        lookYOffset = (lookYOffset - drag.y / (size.height * 0.50f)).coerceIn(-1f, 1f)
                                        onLookChange(lookXOffset, lookYOffset)
                                    }
                                )
                            }
                    )

                    SteeringVisual(
                        steeringProvider = steeringProvider,
                        rawAngleProvider = rawAngleProvider,
                        clusterTheme = settings.clusterTheme,
                        onCalibrateClick = onCalibrateClick,
                        modifier = Modifier.weight(1f)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.97f)
                            .height(44.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        centerUtilityButtons.forEach { btn ->
                            if (btn.bitIndex == -2) {
                                CockpitButton(
                                    def = btn,
                                    isPressed = showAuxSwitchboard,
                                    onPressStateChange = { _, isPressed ->
                                        if (isPressed) showAuxSwitchboard = !showAuxSwitchboard
                                    },
                                    modifier = Modifier.weight(1.1f).fillMaxHeight()
                                )
                            } else {
                                val telemLit = (btn.bitIndex == 3 && telemetry.isHazard) ||
                                    (btn.bitIndex == 12 && telemetry.isWipersActive)
                                val isPressed = telemLit || (btn.bitIndex in 0..63 && ((buttonMask shr btn.bitIndex) and 1L) != 0L)
                                CockpitButton(
                                    def = btn,
                                    isPressed = isPressed,
                                    onPressStateChange = onButtonStateChange,
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                        }
                    }
                }

                // Right Section: Radio Module + Sequential Shifter + 3 Pedals
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(bottom = 2.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Row(
                        modifier = Modifier
                            .width(266.dp)
                            .height(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(CockpitPanel)
                            .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(end = 2.dp)) {
                            Text("AUDIO", color = activeAccent, fontSize = 10.sp, fontWeight = FontWeight.Black)
                            Text("CAB DECK", color = Color.Gray, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }

                        CockpitButton(
                            def = ActionButtonDef(bitIndex = 16, icon = "◀◀", label = "PREV"),
                            isPressed = ((buttonMask shr 16) and 1L) != 0L,
                            onPressStateChange = onButtonStateChange,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )

                        CockpitButton(
                            def = ActionButtonDef(bitIndex = 14, icon = "FM", label = "RADIO", isPrimary = true),
                            isPressed = ((buttonMask shr 14) and 1L) != 0L,
                            onPressStateChange = onButtonStateChange,
                            modifier = Modifier.weight(1.2f).fillMaxHeight()
                        )

                        CockpitButton(
                            def = ActionButtonDef(bitIndex = 15, icon = "▶▶", label = "NEXT"),
                            isPressed = ((buttonMask shr 15) and 1L) != 0L,
                            onPressStateChange = onButtonStateChange,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }

                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(
                            modifier = Modifier
                                .width(68.dp)
                                .height(185.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            gearButtons.forEach { btn ->
                                val isPressed = btn.bitIndex in 0..63 && ((buttonMask shr btn.bitIndex) and 1L) != 0L
                                CockpitButton(
                                    def = btn,
                                    isPressed = isPressed,
                                    onPressStateChange = onButtonStateChange,
                                    modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        PedalSlider(
                            label = "CLUTCH",
                            activeColor = Color(0xFF29B6F6),
                            value = clutch,
                            onValueChange = onClutchChange
                        )

                        Spacer(modifier = Modifier.width(5.dp))

                        PedalSlider(
                            label = "BRAKE",
                            activeColor = BrakePedalRed,
                            value = brake,
                            onValueChange = onBrakeChange
                        )

                        Spacer(modifier = Modifier.width(5.dp))

                        PedalSlider(
                            label = "GAS",
                            activeColor = GasPedalGreen,
                            value = throttle,
                            onValueChange = onThrottleChange,
                            onDoubleTap = { onPulseBit(9) }
                        )
                    }
                }
            }
        }
    }

    if (showConnectionDialog) {
        var ipInput by remember { mutableStateOf(connectedIp.ifBlank { savedManualIp }) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable { showConnectionDialog = false },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(380.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CockpitPanel)
                    .border(1.dp, activeAccent, RoundedCornerShape(12.dp))
                    .clickable(enabled = false) {}
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("PC TELEMETRY LINK", color = activeAccent, fontSize = 14.sp, fontWeight = FontWeight.Black)
                Text(
                    text = "1. Launch ETS2 via start.bat & click OK on 'SDK features detected'.\n2. Auto-discovery scans Wi-Fi & USB Tethering automatically, or enter your PC IPv4 below:",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    label = { Text("PC IPv4 Address (e.g. 192.168.1.10)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        onSetManualIp("")
                        ipInput = ""
                        showConnectionDialog = false
                    }) {
                        Text("AUTO SCAN", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = {
                        onSetManualIp(ipInput)
                        showConnectionDialog = false
                    }) {
                        Text("CONNECT", color = activeAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            currentSettings = settings,
            onSave = onSaveSettings,
            onDismiss = { showSettingsDialog = false }
        )
    }

    if (showAuxSwitchboard) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable { showAuxSwitchboard = false },
            contentAlignment = Alignment.Center
        ) {
            AuxiliarySwitchboard(
                activeMask = buttonMask,
                telemetry = telemetry,
                onPressChange = onButtonStateChange,
                onPulseBit = onPulseBit,
                onDismiss = { showAuxSwitchboard = false },
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .clickable(enabled = false) {}
            )
        }
    }
}