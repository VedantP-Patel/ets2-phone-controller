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
import androidx.compose.ui.geometry.Size
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
    val iconType: String
)

@Composable
fun AuxSwitchboard(
    activeMask: Int,
    onPressChange: (Int, Boolean) -> Unit,
    onAirHornBlast: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val switches = listOf(
        AuxSwitchDef(
            bitIndex = 18,
            keyHint = "V",
            title = "DIFF LOCK",
            subtitle = "Cross-Axle Lock",
            activeColor = Color(0xFFFF9100),
            iconType = "DIFF"
        ),
        AuxSwitchDef(
            bitIndex = 19,
            keyHint = "U",
            title = "LIFT AXLE",
            subtitle = "Chassis Elevation",
            activeColor = Color(0xFF00E5FF),
            iconType = "LIFT"
        ),
        AuxSwitchDef(
            bitIndex = 20,
            keyHint = "T",
            title = "TRAILER HOOK",
            subtitle = "5th Wheel Coupler",
            activeColor = Color(0xFFFF5252),
            iconType = "TRAILER"
        ),
        AuxSwitchDef(
            bitIndex = 3,
            keyHint = "F",
            title = "HAZARDS",
            subtitle = "4-Way Emergency",
            activeColor = Color(0xFFFF3D00),
            iconType = "HAZARD"
        ),
        AuxSwitchDef(
            bitIndex = 22,
            keyHint = "O",
            title = "BEACONS",
            subtitle = "Strobe Hazards",
            activeColor = Color(0xFFFFD600),
            iconType = "BEACON"
        ),
        AuxSwitchDef(
            bitIndex = 23,
            keyHint = "N",
            title = "AIR HORN",
            subtitle = "Dual Roof Trumpet",
            activeColor = Color(0xFFFFAB00),
            iconType = "AIR_HORN"
        )
    )

    // Local toggled state cache for visual indicator lights
    var activeSwitches by remember { mutableStateOf(setOf<Int>()) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF13171F).copy(alpha = 0.96f))
            .border(1.5.dp, CockpitBorder, RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎛️", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "AUXILIARY HEAVY TRUCK SWITCHBOARD",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(GaugeAccent.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "DIRECTINPUT 100HZ",
                            color = GaugeAccent,
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

            // 6-Switch Grid: 2 rows of 3 heavy rocker cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                switches.take(3).forEach { sw ->
                    val isToggled = activeSwitches.contains(sw.bitIndex)
                    val isPhysicallyPressed = (activeMask and (1 shl sw.bitIndex)) != 0
                    val isEngaged = isToggled || isPhysicallyPressed

                    RockerSwitchCard(
                        def = sw,
                        isEngaged = isEngaged,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Pulse key press
                            onPressChange(sw.bitIndex, true)
                            onPressChange(sw.bitIndex, false)

                            if (sw.bitIndex == 23) {
                                // Air Horn sound effect
                                onAirHornBlast()
                            } else {
                                // Toggle UI active state for latching controls
                                activeSwitches = if (isToggled) activeSwitches - sw.bitIndex else activeSwitches + sw.bitIndex
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                switches.drop(3).forEach { sw ->
                    val isToggled = activeSwitches.contains(sw.bitIndex)
                    val isPhysicallyPressed = (activeMask and (1 shl sw.bitIndex)) != 0
                    val isEngaged = isToggled || isPhysicallyPressed

                    RockerSwitchCard(
                        def = sw,
                        isEngaged = isEngaged,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPressChange(sw.bitIndex, true)
                            onPressChange(sw.bitIndex, false)

                            if (sw.bitIndex == 23) {
                                onAirHornBlast()
                            } else {
                                activeSwitches = if (isToggled) activeSwitches - sw.bitIndex else activeSwitches + sw.bitIndex
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
            // Icon & Labels
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Vector Graphic Icon
                AuxSwitchIcon(
                    type = def.iconType,
                    color = if (isEngaged) def.activeColor else Color.Gray,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = def.title,
                            color = if (isEngaged) Color.White else Color.LightGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        // Keyboard shortcut badge
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
                                fontSize = 9.sp,
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

            // Heavy Rocker LED Indicator Light
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
                    text = if (isEngaged) "ON" else "OFF",
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
                // Differential Lock: Axle line with center cross-gear locked
                drawLine(color, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(color, radius = w * 0.28f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = 1.8.dp.toPx()))
                drawLine(color, Offset(w * 0.35f, h * 0.35f), Offset(w * 0.65f, h * 0.65f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.65f, h * 0.35f), Offset(w * 0.35f, h * 0.65f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            "LIFT" -> {
                // Lift Axle: Wheel circle with elevation arrow above
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
                // Trailer Kingpin: Semi 5th-wheel horseshoe hitch with locking pin
                val hitch = Path().apply {
                    moveTo(w * 0.20f, h * 0.85f)
                    lineTo(w * 0.20f, h * 0.35f)
                    quadraticBezierTo(w * 0.50f, h * 0.15f, w * 0.80f, h * 0.35f)
                    lineTo(w * 0.80f, h * 0.85f)
                }
                drawPath(hitch, color = color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                // Kingpin ball
                drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.50f, h * 0.50f))
            }
            "HIGH_BEAM" -> {
                // High Beams: Classic automotive high beam light dome with 5 straight parallel horizontal rays
                val bulb = Path().apply {
                    moveTo(w * 0.60f, h * 0.25f)
                    quadraticBezierTo(w * 0.90f, h * 0.50f, w * 0.60f, h * 0.75f)
                    close()
                }
                drawPath(bulb, color = color, style = Stroke(width = 2.dp.toPx()))
                for (i in 0 until 5) {
                    val y = h * (0.28f + i * 0.11f)
                    drawLine(color, Offset(w * 0.10f, y), Offset(w * 0.52f, y), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            "HAZARD" -> {
                // Emergency Hazard Warning Triangle
                val outerTri = Path().apply {
                    moveTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.88f, h * 0.82f)
                    lineTo(w * 0.12f, h * 0.82f)
                    close()
                }
                drawPath(outerTri, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                // Exclamation mark bar & dot
                drawLine(color, Offset(w * 0.50f, h * 0.38f), Offset(w * 0.50f, h * 0.58f), strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(color, radius = 1.4.dp.toPx(), center = Offset(w * 0.50f, h * 0.70f))
            }
            "BEACON" -> {
                // Roof Beacon: Rotating strobe dome with hazard radiation flares
                val dome = Path().apply {
                    moveTo(w * 0.25f, h * 0.75f)
                    lineTo(w * 0.25f, h * 0.50f)
                    quadraticBezierTo(w * 0.50f, h * 0.22f, w * 0.75f, h * 0.50f)
                    lineTo(w * 0.75f, h * 0.75f)
                    close()
                }
                drawPath(dome, color = color, style = Stroke(width = 2.dp.toPx()))
                // Strobe rays
                drawLine(color, Offset(w * 0.50f, h * 0.12f), Offset(w * 0.50f, h * 0.02f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.20f, h * 0.26f), Offset(w * 0.10f, h * 0.16f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.80f, h * 0.26f), Offset(w * 0.90f, h * 0.16f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            "AIR_HORN" -> {
                // Dual Air Horn Trumpets
                val horn1 = Path().apply {
                    moveTo(w * 0.15f, h * 0.40f)
                    lineTo(w * 0.65f, h * 0.32f)
                    lineTo(w * 0.88f, h * 0.18f)
                    lineTo(w * 0.88f, h * 0.52f)
                    lineTo(w * 0.65f, h * 0.42f)
                    close()
                }
                drawPath(horn1, color = color, style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round))
                // Lower secondary trumpet
                val horn2 = Path().apply {
                    moveTo(w * 0.20f, h * 0.68f)
                    lineTo(w * 0.60f, h * 0.62f)
                    lineTo(w * 0.82f, h * 0.52f)
                    lineTo(w * 0.82f, h * 0.80f)
                    lineTo(w * 0.60f, h * 0.72f)
                    close()
                }
                drawPath(horn2, color = color.copy(alpha = 0.8f), style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round))
            }
        }
    }
}

