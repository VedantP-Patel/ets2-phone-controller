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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

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
    onAirHornBlast: () -> Unit = {},
    onLookXChange: (Float) -> Unit = {}
) {
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAuxSwitchboard by remember { mutableStateOf(false) }
    val activeAccent = getThemeAccent(settings.clusterTheme)

    // Left Outer Column: Left Turn Stalk, Lights (Tap=Cycle L, SwipeUp=Hi-Beam K), Horn (Hold=Horn, SwipeUp=Air Horn), Park Brake
    val leftOuterButtons = listOf(
        ActionButtonDef(bitIndex = 4, icon = "TURN_LEFT", label = "L. TURN", customColor = Color(0xFF00E676)),
        ActionButtonDef(
            bitIndex = 2,
            icon = "💡",
            label = "LIGHTS ↕",
            swipeUpBit = 21,   // Swipe Up = High Beams ('K')
            swipeDownBit = 22  // Swipe Down = Roof Beacons ('O')
        ),
        ActionButtonDef(
            bitIndex = 0,
            icon = "🔊",
            label = "HORN / AIR",
            isPrimary = true,
            swipeUpBit = 23    // Swipe Up = Roof Air Horn ('N')
        ),
        ActionButtonDef(bitIndex = 6, icon = "🅿️", label = "PARK BRK", isWarning = true)
    )

    // Left Inner Column: Right Turn Stalk (beside L. TURN!), Retarder Stalk, Cruise Stalk, Engine/Enter
    val leftInnerButtons = listOf(
        ActionButtonDef(bitIndex = 5, icon = "TURN_RIGHT", label = "R. TURN", customColor = Color(0xFF00E676)),
        ActionButtonDef(
            bitIndex = 7,
            icon = "RETARDER",
            label = "RETARDER ↕",
            customColor = Color(0xFFFF9100),
            swipeUpBit = 24,   // Swipe Up   = Increase Retarder (;)
            swipeDownBit = 25  // Swipe Down = Decrease Retarder (')
        ),
        ActionButtonDef(
            bitIndex = 9,
            icon = "⏸",
            label = "CRUISE ↕",
            customColor = Color(0xFF2979FF),
            swipeUpBit = 26,   // Swipe Up   = Cruise +5 km/h
            swipeDownBit = 27, // Swipe Down = Cruise -5 km/h
            longPressBit = 28  // Hold       = Cruise Resume
        ),
        ActionButtonDef(
            bitIndex = 30,     // Primary Tap = ENTER (Gas/Toll/Ferry/Job Confirm!)
            icon = "⏎",
            label = "ENTER / ENG",
            customColor = Color(0xFF00E676),
            longPressBit = 1   // Hold 0.4s  = Start/Stop Engine ('E')
        )
    )

    // Center Bottom Bar: Quick Utility & Camera Views (Tap=Interior Cam 1, SwipeUp=Chase Cam 2, SwipeDown=Window Peek Cam 5)
    val centerUtilityButtons = listOf(
        ActionButtonDef(bitIndex = 3, icon = "⚠️", label = "HAZARD", isWarning = true),
        ActionButtonDef(
            bitIndex = 8,
            icon = "🗺",
            label = "ADVISOR ↕",
            swipeUpBit = 29,   // Swipe Up   = Dash Computer ('I')
            swipeDownBit = 31, // Swipe Down = Job Info ('F6')
            longPressBit = 32  // Hold       = Damage/Tow ('F7')
        ),
        ActionButtonDef(bitIndex = 12, icon = "WIPER", label = "WIPERS"),
        ActionButtonDef(
            bitIndex = 13,
            icon = "📷",
            label = "CAM ↕",
            swipeUpBit = 34,   // Swipe Up   = Exterior Chase Cam ('2')
            swipeDownBit = 33  // Swipe Down = Lean Out Window Reverse Cam ('5')
        ),
        ActionButtonDef(bitIndex = -2, icon = "🎛️", label = "SWITCHES", isPrimary = true)
    )

    // Transmission gear buttons (right of wheel, left of pedals)
    val gearButtons = listOf(
        ActionButtonDef(bitIndex = 10, icon = "🔼", label = "SHIFT UP", isPrimary = true),
        ActionButtonDef(
            bitIndex = 17,
            icon = "N",
            label = "NEUTRAL",
            longPressBit = 18  // Hold Neutral = Toggle Diff Lock ('V') for heavy mud/quarries!
        ),
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
                val isHighBeamOn = (telemetry.auxStateFlags and (1 shl 3)) != 0
                val innerMask = if (isHighBeamOn) (buttonMask or (1 shl 21)) else buttonMask
                ButtonColumn(
                    buttons = leftInnerButtons,
                    activeMask = innerMask,
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
                    // Live Digital Cockpit Telemetry Cluster + Cabin Look Trackpad
                    var lookOffset by remember { mutableFloatStateOf(0f) }
                    DashboardGauges(
                        telemetry = telemetry,
                        useMph = settings.speedUnitMph,
                        accentColor = activeAccent,
                        modifier = Modifier
                            .fillMaxWidth(0.96f)
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        lookOffset = 0f
                                        onLookXChange(0f)
                                    },
                                    onDragCancel = {
                                        lookOffset = 0f
                                        onLookXChange(0f)
                                    },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        val maxPx = size.width * 0.35f
                                        lookOffset = (lookOffset + dragAmount / maxPx).coerceIn(-1f, 1f)
                                        onLookXChange(lookOffset)
                                    }
                                )
                            }
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
                                val isPressed = btn.bitIndex in 0..30 && (buttonMask and (1 shl btn.bitIndex)) != 0
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
                                val isPressed = btn.bitIndex in 0..30 && (buttonMask and (1 shl btn.bitIndex)) != 0
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
                telemetryAuxFlags = telemetry.auxStateFlags,
                isHazardActive = telemetry.isHazard,
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
