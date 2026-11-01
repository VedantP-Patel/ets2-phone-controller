package com.ets2controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.CockpitBorder
import com.ets2controller.ui.theme.CockpitPanel
import com.ets2controller.ui.theme.GaugeAccent

data class AuxSwitchDef(
    val bitIndex: Int,
    val keyHint: String,
    val title: String,
    val subtitle: String,
    val activeColor: Color,
    val iconType: String,
    val isMomentary: Boolean = false
)

@Composable
fun AuxSwitchboard(
    activeMask: Int,
    telemetryAuxFlags: Int = 0,
    isHazardActive: Boolean = false,
    onPressChange: (Int, Boolean) -> Unit,
    onAirHornBlast: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val page1Switches = listOf(
        AuxSwitchDef(18, "V", "DIFF LOCK", "Cross-Axle Lock", Color(0xFFFF9100), "DIFF"),
        AuxSwitchDef(19, "U", "LIFT AXLE", "Chassis Elevation", Color(0xFF00E5FF), "LIFT"),
        AuxSwitchDef(20, "T", "TRAILER HOOK", "5th Wheel Coupler", Color(0xFFFF5252), "TRAILER"),
        AuxSwitchDef(3, "F", "HAZARDS", "4-Way Emergency", Color(0xFFFF3D00), "HAZARD"),
        AuxSwitchDef(22, "O", "BEACONS", "Strobe Hazards", Color(0xFFFFD600), "BEACON"),
        AuxSwitchDef(23, "N", "AIR HORN", "Dual Roof Trumpet", Color(0xFFFFAB00), "AIR_HORN", isMomentary = true)
    )

    val page2Switches = listOf(
        AuxSwitchDef(30, "ENTER", "CONFIRM / PAY", "Fuel / Ferry / Toll", Color(0xFF00E676), "ENTER", isMomentary = true),
        AuxSwitchDef(29, "I", "DASH DISPLAY", "Trip Info Computer", Color(0xFF2979FF), "INFO", isMomentary = true),
        AuxSwitchDef(31, "F6", "JOB & CARGO", "Delivery Details", Color(0xFF00E5FF), "INFO", isMomentary = true),
        AuxSwitchDef(32, "F7", "DAMAGE & TOW", "Service / Assistance", Color(0xFFFF9100), "HAZARD", isMomentary = true),
        AuxSwitchDef(33, "CAM 5", "REVERSE PEEK", "Lean Out Window", Color(0xFFAB47BC), "LIFT", isMomentary = true),
        AuxSwitchDef(34, "CAM 2", "EXTERIOR CAM", "3rd Person Orbit", Color(0xFF26A69A), "BEACON", isMomentary = true)
    )

    val currentSwitches = if (selectedTab == 0) page1Switches else page2Switches

    // Real telemetry flags from SCS SDK telemetry
    val isDiffLocked = (telemetryAuxFlags and (1 shl 0)) != 0
    val isLiftActive = (telemetryAuxFlags and (1 shl 1)) != 0
    val isTrailerConnected = (telemetryAuxFlags and (1 shl 2)) != 0
    val isHighBeamOn = (telemetryAuxFlags and (1 shl 3)) != 0
    val isBeaconOn = (telemetryAuxFlags and (1 shl 4)) != 0

    fun isSwitchEngaged(sw: AuxSwitchDef): Boolean {
        val isPhysicallyPressed = sw.bitIndex < 32 && (activeMask and (1 shl sw.bitIndex)) != 0
        if (sw.isMomentary) return isPhysicallyPressed

        val isTelemetryActive = when (sw.bitIndex) {
            18 -> isDiffLocked
            19 -> isLiftActive
            20 -> isTrailerConnected
            21 -> isHighBeamOn
            22 -> isBeaconOn
            3 -> isHazardActive
            else -> false
        }
        return isTelemetryActive || isPhysicallyPressed
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF13171F).copy(alpha = 0.96f))
            .border(1.5.dp, CockpitBorder, RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar with Tab Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("🎛️", fontSize = 15.sp)
                    Text(
                        "MULTIPLEX SWITCHBOARD",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp
                    )

                    // Tab 0: Traction & Lights
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedTab == 0) GaugeAccent else CockpitPanel)
                            .border(1.dp, if (selectedTab == 0) GaugeAccent else CockpitBorder, RoundedCornerShape(6.dp))
                            .clickable { selectedTab = 0 }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "1. TRACTION & LIGHTS",
                            color = if (selectedTab == 0) Color.Black else Color.LightGray,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Tab 1: Suspension, Windows & Service Prompts
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedTab == 1) GaugeAccent else CockpitPanel)
                            .border(1.dp, if (selectedTab == 1) GaugeAccent else CockpitBorder, RoundedCornerShape(6.dp))
                            .clickable { selectedTab = 1 }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "2. SUSPENSION & CABIN",
                            color = if (selectedTab == 1) Color.Black else Color.LightGray,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Close button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(CockpitPanel)
                        .border(1.dp, CockpitBorder, CircleShape)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 1 (3 switches)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                currentSwitches.take(3).forEach { sw ->
                    val isEngaged = isSwitchEngaged(sw)

                    RockerSwitchCard(
                        def = sw,
                        isEngaged = isEngaged,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPressChange(sw.bitIndex, true)
                            onPressChange(sw.bitIndex, false)

                            if (sw.bitIndex == 23) {
                                onAirHornBlast()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2 (3 switches)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                currentSwitches.drop(3).forEach { sw ->
                    val isEngaged = isSwitchEngaged(sw)

                    RockerSwitchCard(
                        def = sw,
                        isEngaged = isEngaged,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPressChange(sw.bitIndex, true)
                            onPressChange(sw.bitIndex, false)

                            if (sw.bitIndex == 23) {
                                onAirHornBlast()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RockerSwitchCard(
    def: AuxSwitchDef,
    isEngaged: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isEngaged) def.activeColor else CockpitBorder
    val glowBg = if (isEngaged) def.activeColor.copy(alpha = 0.18f) else CockpitPanel

    Box(
        modifier = modifier
            .height(68.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(glowBg)
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                AuxSwitchIcon(
                    type = def.iconType,
                    color = if (isEngaged) def.activeColor else Color.Gray,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = def.title,
                            color = if (isEngaged) Color.White else Color.LightGray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .border(0.8.dp, Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = def.keyHint,
                                color = def.activeColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = def.subtitle,
                        color = Color.Gray,
                        fontSize = 9.sp
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isEngaged) def.activeColor else Color(0xFF22262E))
                        .border(1.dp, if (isEngaged) def.activeColor else Color.DarkGray, CircleShape)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (def.isMomentary) "PUSH" else if (isEngaged) "ON" else "OFF",
                    color = if (isEngaged) def.activeColor else Color.Gray,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AuxSwitchIcon(type: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        when (type) {
            "DIFF" -> {
                drawLine(color, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(color, radius = w * 0.28f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = 1.8.dp.toPx()))
                drawLine(color, Offset(w * 0.35f, h * 0.35f), Offset(w * 0.65f, h * 0.65f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.65f, h * 0.35f), Offset(w * 0.35f, h * 0.65f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            "LIFT" -> {
                drawCircle(color, radius = w * 0.24f, center = Offset(w * 0.5f, h * 0.65f), style = Stroke(width = 2.dp.toPx()))
                val arrow = Path().apply {
                    moveTo(w * 0.5f, h * 0.10f)
                    lineTo(w * 0.30f, h * 0.32f)
                    lineTo(w * 0.42f, h * 0.32f)
                    lineTo(w * 0.42f, h * 0.45f)
                    lineTo(w * 0.58f, h * 0.45f)
                    lineTo(w * 0.58f, h * 0.32f)
                    lineTo(w * 0.70f, h * 0.32f)
                    close()
                }
                drawPath(arrow, color = color)
            }
            "TRAILER" -> {
                val hitch = Path().apply {
                    moveTo(w * 0.20f, h * 0.85f)
                    lineTo(w * 0.20f, h * 0.35f)
                    quadraticBezierTo(w * 0.50f, h * 0.15f, w * 0.80f, h * 0.35f)
                    lineTo(w * 0.80f, h * 0.85f)
                }
                drawPath(hitch, color = color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.50f, h * 0.50f))
            }
            "HAZARD" -> {
                val outerTri = Path().apply {
                    moveTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.88f, h * 0.82f)
                    lineTo(w * 0.12f, h * 0.82f)
                    close()
                }
                drawPath(outerTri, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(color, Offset(w * 0.50f, h * 0.38f), Offset(w * 0.50f, h * 0.58f), strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(color, radius = 1.4.dp.toPx(), center = Offset(w * 0.50f, h * 0.70f))
            }
            "BEACON" -> {
                val dome = Path().apply {
                    moveTo(w * 0.25f, h * 0.75f)
                    lineTo(w * 0.25f, h * 0.50f)
                    quadraticBezierTo(w * 0.50f, h * 0.22f, w * 0.75f, h * 0.50f)
                    lineTo(w * 0.75f, h * 0.75f)
                    close()
                }
                drawPath(dome, color = color, style = Stroke(width = 2.dp.toPx()))
                drawLine(color, Offset(w * 0.50f, h * 0.12f), Offset(w * 0.50f, h * 0.02f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.20f, h * 0.26f), Offset(w * 0.10f, h * 0.16f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.80f, h * 0.26f), Offset(w * 0.90f, h * 0.16f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            "AIR_HORN", "ENTER", "INFO" -> {
                drawCircle(color, radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = 2.dp.toPx()))
                drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f))
            }
        }
    }
}