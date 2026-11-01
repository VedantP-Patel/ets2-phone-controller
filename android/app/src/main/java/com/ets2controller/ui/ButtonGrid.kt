package com.ets2controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.CockpitBorder
import com.ets2controller.ui.theme.CockpitPanel
import com.ets2controller.ui.theme.GaugeAccent

data class ActionButtonDef(
    val bitIndex: Int,
    val icon: String,
    val label: String,
    val isPrimary: Boolean = false,
    val isWarning: Boolean = false,
    val customColor: Color? = null,
    val swipeUpBit: Int? = null,
    val swipeDownBit: Int? = null,
    val longPressBit: Int? = null
)

@Composable
fun HighBeamIcon(color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val dome = Path().apply {
            moveTo(w * 0.60f, h * 0.22f)
            quadraticBezierTo(w * 0.95f, h * 0.50f, w * 0.60f, h * 0.78f)
            close()
        }
        drawPath(dome, color = color, style = Stroke(width = 1.8.dp.toPx()))
        for (i in 0 until 5) {
            val y = h * (0.28f + i * 0.11f)
            drawLine(
                color = color,
                start = Offset(w * 0.08f, y),
                end = Offset(w * 0.52f, y),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun RetarderIcon(color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawCircle(
            color = color,
            radius = w * 0.38f,
            center = Offset(w * 0.5f, h * 0.5f),
            style = Stroke(width = 1.6.dp.toPx())
        )
        drawCircle(
            color = color,
            radius = w * 0.14f,
            center = Offset(w * 0.5f, h * 0.5f)
        )
        drawLine(color, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.5f, h * 0.28f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.86f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.14f, h * 0.5f), Offset(w * 0.28f, h * 0.5f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.72f, h * 0.5f), Offset(w * 0.86f, h * 0.5f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
fun WindshieldWiperIcon(color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.15f, h * 0.82f)
            lineTo(w * 0.26f, h * 0.22f)
            quadraticBezierTo(w * 0.50f, h * 0.10f, w * 0.74f, h * 0.22f)
            lineTo(w * 0.85f, h * 0.82f)
            quadraticBezierTo(w * 0.50f, h * 0.70f, w * 0.15f, h * 0.82f)
            close()
        }
        drawPath(
            path = path,
            color = color.copy(alpha = 0.45f),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawLine(
            color = color,
            start = Offset(w * 0.46f, h * 0.80f),
            end = Offset(w * 0.68f, h * 0.22f),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.58f, h * 0.14f),
            end = Offset(w * 0.78f, h * 0.28f),
            strokeWidth = 2.8.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun TurnArrowIcon(isLeft: Boolean, color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (isLeft) {
                moveTo(w * 0.82f, h * 0.35f)
                lineTo(w * 0.46f, h * 0.35f)
                lineTo(w * 0.46f, h * 0.15f)
                lineTo(w * 0.12f, h * 0.50f)
                lineTo(w * 0.46f, h * 0.85f)
                lineTo(w * 0.46f, h * 0.65f)
                lineTo(w * 0.82f, h * 0.65f)
                close()
            } else {
                moveTo(w * 0.18f, h * 0.35f)
                lineTo(w * 0.54f, h * 0.35f)
                lineTo(w * 0.54f, h * 0.15f)
                lineTo(w * 0.88f, h * 0.50f)
                lineTo(w * 0.54f, h * 0.85f)
                lineTo(w * 0.54f, h * 0.65f)
                lineTo(w * 0.18f, h * 0.65f)
                close()
            }
        }
        drawPath(path, color = color)
    }
}

@Composable
fun NeutralGearIcon(color: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Box(
        modifier = modifier.border(1.5.dp, color, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "N",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
fun CockpitButton(
    def: ActionButtonDef,
    isPressed: Boolean,
    onPressStateChange: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val hasGestures = def.swipeUpBit != null || def.swipeDownBit != null || def.longPressBit != null

    val bgColor = when {
        isPressed -> def.customColor ?: GaugeAccent
        def.isWarning -> Color(0xFF2A1515)
        def.customColor != null -> def.customColor.copy(alpha = 0.15f)
        def.isPrimary -> Color(0xFF1F242E)
        else -> CockpitPanel
    }

    val borderColor = when {
        isPressed -> def.customColor ?: GaugeAccent
        def.isWarning -> Color(0xFFE53935).copy(alpha = 0.5f)
        def.customColor != null -> def.customColor.copy(alpha = 0.6f)
        def.isPrimary -> GaugeAccent.copy(alpha = 0.5f)
        else -> CockpitBorder
    }

    val textColor = when {
        isPressed -> Color.White
        def.isWarning -> Color(0xFFFF8A80)
        def.customColor != null -> def.customColor
        def.isPrimary -> GaugeAccent
        else -> Color.White
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
            .pointerInput(def.bitIndex, def.swipeUpBit, def.swipeDownBit, def.longPressBit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downId = down.id
                    val startPos = down.position
                    val downTime = System.currentTimeMillis()
                    down.consume()

                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                    if (!hasGestures) {
                        // Standard instant-response button (Horn, Blinkers, Shifter, etc.)
                        onPressStateChange(def.bitIndex, true)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == downId }
                            if (change == null || !change.pressed) {
                                change?.consume()
                                break
                            }
                            change.consume()
                        }
                        onPressStateChange(def.bitIndex, false)
                    } else {
                        // Smart multi-gesture stalk (Tap vs. Swipe Up vs. Swipe Down vs. Long Press)
                        var gestureTriggered = false
                        val swipeThresholdPx = 18.dp.toPx()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == downId }
                            if (change == null || !change.pressed) {
                                change?.consume()
                                break
                            }
                            change.consume()

                            val dy = change.position.y - startPos.y
                            if (!gestureTriggered) {
                                if (dy < -swipeThresholdPx && def.swipeUpBit != null) {
                                    gestureTriggered = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPressStateChange(def.swipeUpBit, true)
                                    onPressStateChange(def.swipeUpBit, false)
                                } else if (dy > swipeThresholdPx && def.swipeDownBit != null) {
                                    gestureTriggered = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPressStateChange(def.swipeDownBit, true)
                                    onPressStateChange(def.swipeDownBit, false)
                                } else if (System.currentTimeMillis() - downTime > 420L && def.longPressBit != null) {
                                    gestureTriggered = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPressStateChange(def.longPressBit, true)
                                    onPressStateChange(def.longPressBit, false)
                                }
                            }
                        }

                        if (!gestureTriggered) {
                            onPressStateChange(def.bitIndex, true)
                            onPressStateChange(def.bitIndex, false)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(2.dp)
        ) {
            when (def.icon) {
                "TURN_LEFT" -> TurnArrowIcon(isLeft = true, color = if (isPressed) Color.White else Color(0xFF00E676), modifier = Modifier.size(18.dp))
                "TURN_RIGHT" -> TurnArrowIcon(isLeft = false, color = if (isPressed) Color.White else Color(0xFF00E676), modifier = Modifier.size(18.dp))
                "HIGH_BEAMS", "HIGH_BEAM" -> HighBeamIcon(color = if (isPressed) Color.White else (def.customColor ?: Color(0xFF2979FF)), modifier = Modifier.size(18.dp))
                "EXHAUST", "RETARDER" -> RetarderIcon(color = if (isPressed) Color.White else (def.customColor ?: Color(0xFFFF9100)), modifier = Modifier.size(18.dp))
                "WIPER", "WIPERS" -> WindshieldWiperIcon(color = if (isPressed) Color.White else textColor, modifier = Modifier.size(18.dp))
                "N", "NEUTRAL" -> NeutralGearIcon(color = if (isPressed) Color.White else GaugeAccent, modifier = Modifier.size(16.dp))
                else -> Text(
                    text = def.icon,
                    fontSize = 15.sp,
                    color = textColor
                )
            }
            Text(
                text = def.label,
                color = textColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun ButtonColumn(
    buttons: List<ActionButtonDef>,
    activeMask: Int,
    onPressStateChange: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        buttons.forEach { btn ->
            val isPressed = btn.bitIndex >= 0 && (activeMask and (1 shl btn.bitIndex)) != 0
            CockpitButton(
                def = btn,
                isPressed = isPressed,
                onPressStateChange = onPressStateChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 2.dp)
            )
        }
    }
}