package com.ets2controller.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.CockpitBorder
import com.ets2controller.ui.theme.CockpitPanel

@Composable
fun PedalSlider(
    label: String,
    activeColor: Color,
    value: Float,
    onValueChange: (Float) -> Unit,
    onDoubleTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var sliderSize by remember { mutableStateOf(IntSize.Zero) }
    var lastStep by remember { mutableIntStateOf(0) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }

    // Smooth visual animation
    val animatedValue by animateFloatAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 30),
        label = "pedal_anim"
    )

    fun calculateValue(y: Float) {
        if (sliderSize.height <= 0) return
        // Bottom is 0.0, Top is 1.0
        val clampedY = y.coerceIn(0f, sliderSize.height.toFloat())
        val normalized = 1.0f - (clampedY / sliderSize.height.toFloat())
        val result = normalized.coerceIn(0.0f, 1.0f)

        // Haptic feedback at 25%, 50%, 75%, 100%
        val step = (result * 4).toInt()
        if (step != lastStep) {
            lastStep = step
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        onValueChange(result)
    }

    Column(
        modifier = modifier
            .height(185.dp)
            .width(76.dp)
            .padding(vertical = 2.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Percentage readout
        Text(
            text = "${(value * 100).toInt()}%",
            color = if (value > 0.05f) activeColor else Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Vertical pedal track with atomic single-pass gesture listener
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CockpitPanel)
                .border(2.dp, if (value > 0.05f) activeColor.copy(alpha = 0.6f) else CockpitBorder, RoundedCornerShape(12.dp))
                .onSizeChanged { sliderSize = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        calculateValue(down.position.y)

                        // Check double tap (e.g. gas double tap triggers cruise control)
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastTapTimeMs < 320L) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDoubleTap?.invoke()
                            lastTapTimeMs = 0L
                        } else {
                            lastTapTimeMs = nowMs
                        }

                        // Fluid single-pass drag tracking loop until finger lifts or is canceled
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointerChange = event.changes.firstOrNull { it.id == down.id }
                            if (pointerChange == null || !pointerChange.pressed) {
                                break
                            }
                            pointerChange.consume()
                            calculateValue(pointerChange.position.y)
                        }

                        // Return-to-center spring back to 0.0
                        onValueChange(0.0f)
                        lastStep = 0
                    }
                }
        ) {
            // Fill level rising from bottom to top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedValue)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(activeColor, activeColor.copy(alpha = 0.5f))
                        )
                    )
            )

            // Tread grips on pedal surface (realistic ribbed texture)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                repeat(7) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Pedal name label
        Text(
            text = label,
            color = activeColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black
        )
    }
}
