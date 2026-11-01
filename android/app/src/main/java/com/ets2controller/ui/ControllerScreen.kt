package com.ets2controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.config.ControllerSettings
import com.ets2controller.network.TruckTelemetry
import com.ets2controller.ui.theme.*

@Composable
fun ControllerScreen(
    isConnected: Boolean,
    connectedIp: String,
    packetsPerSec: Int,
    steeringValue: Float,
    rawAngleDeg: Float,
    throttle: Float,
    brake: Float,
    buttonMask: Int,
    settings: ControllerSettings,
    telemetry: TruckTelemetry,
    onThrottleChange: (Float) -> Unit,
    onBrakeChange: (Float) -> Unit,
    onButtonStateChange: (Int, Boolean) -> Unit,
    onCalibrateClick: () -> Unit,
    onSaveSettings: (ControllerSettings) -> Unit,
    onAirHornBlast: () -> Unit = {}
) {
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAuxSwitchboard by remember { mutableStateOf(false) }
    val activeAccent = getThemeAccent(settings.clusterTheme)

    // Left outer button group (Zero-Stretch Edge, Fitts's Law Infinite Lateral Boundary)
    val leftOuterButtons = listOf(
        ActionButtonDef(bitIndex = 4, icon = "TURN_LEFT", label = "L. TURN", customColor = Color(0xFF00E676)),
        ActionButtonDef(bitIndex = 5, icon = "TURN_RIGHT", label = "R. TURN", customColor = Color(0xFF00E676)),
        ActionButtonDef(bitIndex = 0, icon = "🔊", label = "HORN / OK", isPrimary = true),
        ActionButtonDef(bitIndex = 6, icon = "🅿️", label = "PARK BRK", isWarning = true)
    )

    // Left inner button group (Tactical Driving Spoke)
    val leftInnerButtons = listOf(
        ActionButtonDef(bitIndex = 21, icon = "HIGH_BEAMS", label = "HI BEAMS", customColor = Color(0xFF2979FF)),
        ActionButtonDef(bitIndex = 7, icon = "RETARDER", label = "RETARDER", customColor = Color(0xFFFF9100)),
        ActionButtonDef(bitIndex = 2, icon = "💡", label = "LIGHTS"),
        ActionButtonDef(bitIndex = 1, icon = "🔑", label = "ENGINE", isWarning = true)
    )

    // Center bottom utilities
    val centerUtilityButtons = listOf(
        ActionButtonDef(bitIndex = 9, icon = "⏸", label = "CRUISE"),
        ActionButtonDef(bitIndex = 8, icon = "🗺", label = "MAP / ADV"),
        ActionButtonDef(bitIndex = 12, icon = "WIPER", label = "WIPERS"),
        ActionButtonDef(bitIndex = 13, icon = "📷", label = "CAM"),
        ActionButtonDef(bitIndex = -2, icon = "🎛️", label = "AUX BOX", isPrimary = true)
    )

    // Transmission gear buttons (right of wheel, left of pedals)
    val gearButtons = listOf(
        ActionButtonDef(bitIndex = 10, icon = "🔼", label = "SHIFT UP", isPrimary = true),
        ActionButtonDef(bitIndex = 17, icon = "N", label = "NEUTRAL"),
        ActionButtonDef(bitIndex = 11, icon = "🔽", label = "SHIFT DN", isPrimary = true)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top HUD Bar with Network Health and Ping Monitor
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connection status chip with RTT ping latency
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(CockpitPanel)
                        .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    val pingColor = when {
                        !isConnected -> DisconnectedRed
                        telemetry.roundTripPingMs < 20 -> ConnectedGreen
                        telemetry.roundTripPingMs < 60 -> Color(0xFFFFA726)
                        else -> DisconnectedRed
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(pingColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isConnected) "Connected: $connectedIp ($packetsPerSec pps | ${telemetry.roundTripPingMs}ms)" 
                               else "Searching for PC beacon...",
                        color = if (isConnected) Color.White else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Settings button
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(CockpitPanel)
                        .border(1.dp, CockpitBorder, CircleShape)
                        .clickable { showSettingsDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⚙️", fontSize = 14.sp)
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
                // Left Column 1 (4 buttons)
                ButtonColumn(
                    buttons = leftOuterButtons,
                    activeMask = buttonMask,
                    onPressStateChange = onButtonStateChange,
                    modifier = Modifier.width(84.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Left Column 2 (4 buttons)
                ButtonColumn(
                    buttons = leftInnerButtons,
                    activeMask = buttonMask,
                    onPressStateChange = onButtonStateChange,
                    modifier = Modifier.width(84.dp)
                )

                // Center Column: Dashboard Gauge Cluster + Steering Wheel Visualizer + Bottom Utilities
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Live Digital Cockpit Telemetry Cluster
                    DashboardGauges(
                        telemetry = telemetry,
                        useMph = settings.speedUnitMph,
                        accentColor = activeAccent,
                        modifier = Modifier.fillMaxWidth(0.96f)
                    )

                    // Steering Wheel Visualizer
                    SteeringVisual(
                        steeringValue = steeringValue,
                        rawAngleDeg = rawAngleDeg,
                        clusterTheme = settings.clusterTheme,
                        onCalibrateClick = onCalibrateClick,
                        modifier = Modifier.weight(1f)
                    )

                    // Bottom horizontal bar of action buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.96f)
                            .height(46.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        centerUtilityButtons.forEach { btn ->
                            if (btn.bitIndex == -2) {
                                CockpitButton(
                                    def = btn,
                                    isPressed = showAuxSwitchboard,
                                    onPressStateChange = { _, isPressed ->
                                        if (isPressed) showAuxSwitchboard = !showAuxSwitchboard
                                    },
                                    modifier = Modifier
                                        .weight(1.1f)
                                        .fillMaxHeight()
                                )
                            } else {
                                val isPressed = (buttonMask and (1 shl btn.bitIndex)) != 0
                                CockpitButton(
                                    def = btn,
                                    isPressed = isPressed,
                                    onPressStateChange = onButtonStateChange,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                        }
                    }
                }

                // Right section: In-Cabin Radio Unit (top) + Gear Shift & Pedals (bottom)
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(bottom = 2.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    // Cockpit Truck Radio Player Widget (positioned above pedals)
                    Row(
                        modifier = Modifier
                            .width(248.dp)
                            .height(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CockpitPanel)
                            .border(1.dp, CockpitBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(end = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📻", fontSize = 11.sp)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("RADIO", color = activeAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("R / PGDN", color = Color.Gray, fontSize = 8.sp)
                        }

                        // Prev Favorite Station (Page Up)
                        CockpitButton(
                            def = ActionButtonDef(bitIndex = 16, icon = "⏮", label = "PREV"),
                            isPressed = (buttonMask and (1 shl 16)) != 0,
                            onPressStateChange = onButtonStateChange,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )

                        // Radio Toggle / Audio Player (Key R)
                        CockpitButton(
                            def = ActionButtonDef(bitIndex = 14, icon = "📻", label = "RADIO", isPrimary = true),
                            isPressed = (buttonMask and (1 shl 14)) != 0,
                            onPressStateChange = onButtonStateChange,
                            modifier = Modifier
                                .weight(1.2f)
                                .fillMaxHeight()
                        )

                        // Next Favorite Station (Page Down)
                        CockpitButton(
                            def = ActionButtonDef(bitIndex = 15, icon = "⏭", label = "NEXT"),
                            isPressed = (buttonMask and (1 shl 15)) != 0,
                            onPressStateChange = onButtonStateChange,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }

                    // Bottom Row: Gear Shift + Pedals
                    Row(
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // Sequential Gear Up/Down Column
                        Column(
                            modifier = Modifier
                                .width(74.dp)
                                .height(185.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            gearButtons.forEach { btn ->
                                val isPressed = (buttonMask and (1 shl btn.bitIndex)) != 0
                                CockpitButton(
                                    def = btn,
                                    isPressed = isPressed,
                                    onPressStateChange = onButtonStateChange,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        // Left Pedal: BRAKE (Analog slider)
                        PedalSlider(
                            label = "BRAKE",
                            activeColor = BrakePedalRed,
                            value = brake,
                            onValueChange = onBrakeChange
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // Right Pedal: GAS / ACCEL (Analog slider with double-tap Cruise Control)
                        PedalSlider(
                            label = "GAS",
                            activeColor = GasPedalGreen,
                            value = throttle,
                            onValueChange = onThrottleChange,
                            onDoubleTap = {
                                // Double tap gas pedal engages/disengages Cruise Control (Bit 9)
                                onButtonStateChange(9, true)
                                onButtonStateChange(9, false)
                            }
                        )
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
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable { showAuxSwitchboard = false },
            contentAlignment = Alignment.Center
        ) {
            AuxSwitchboard(
                activeMask = buttonMask,
                onPressChange = onButtonStateChange,
                onAirHornBlast = onAirHornBlast,
                onDismiss = { showAuxSwitchboard = false },
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .clickable(enabled = false) {}
            )
        }
    }
}
