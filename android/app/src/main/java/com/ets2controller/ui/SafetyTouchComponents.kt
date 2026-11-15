package com.ets2controller.ui

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SafeMomentaryTile(
    accentColor: Color,
    isActiveHighlight: Boolean = false,
    useColoredBorder: Boolean = false,
    rootWidthPx: Float,
    palmGuardPx: Float = 14f,
    onPressChanged: (Boolean) -> Unit = {},
    onTrigger: () -> Unit,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(isPressed: Boolean) -> Unit
) {
    val currentOnPressChanged by rememberUpdatedState(onPressChanged)
    val currentOnTrigger by rememberUpdatedState(onTrigger)
    val currentOnSwipeUp by rememberUpdatedState(onSwipeUp)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)

    var isPressed by remember { mutableStateOf(false) }
    var tileRootX by remember { mutableFloatStateOf(0f) }

    val bgColor = when {
        isActiveHighlight -> accentColor.copy(alpha = 0.18f)
        isPressed -> accentColor.copy(alpha = 0.12f)
        else -> CockpitTile
    }
    val borderColor = when {
        isActiveHighlight -> accentColor.copy(alpha = 0.88f)
        isPressed -> accentColor.copy(alpha = 0.75f)
        useColoredBorder -> accentColor.copy(alpha = 0.45f)
        else -> CockpitBorder
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                tileRootX = coords.positionInRoot().x
            }
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.25.dp, borderColor, RoundedCornerShape(14.dp))
            .pointerInput(rootWidthPx, tileRootX) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val globalX = tileRootX + down.position.x
                    if (rootWidthPx > 0f && (globalX < palmGuardPx || globalX > rootWidthPx - palmGuardPx)) {
                        return@awaitEachGesture
                    }

                    val startPos = down.position
                    var inside = true
                    var swiped = false
                    isPressed = true
                    currentOnPressChanged(true)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null) {
                            isPressed = false
                            currentOnPressChanged(false)
                            break
                        }

                        val dy = change.position.y - startPos.y

                        if (!swiped && currentOnSwipeUp != null && dy < -34f) {
                            swiped = true
                            isPressed = false
                            currentOnPressChanged(false)
                            currentOnSwipeUp?.invoke()
                        } else if (!swiped && currentOnSwipeDown != null && dy > 34f) {
                            swiped = true
                            isPressed = false
                            currentOnPressChanged(false)
                            currentOnSwipeDown?.invoke()
                        }

                        if (!change.pressed) {
                            isPressed = false
                            currentOnPressChanged(false)
                            if (inside && !swiped) {
                                currentOnTrigger()
                            }
                            break
                        }

                        inside = change.position.x in 0f..size.width.toFloat() &&
                                 change.position.y in 0f..size.height.toFloat()
                        if (!inside && isPressed) {
                            isPressed = false
                            currentOnPressChanged(false)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content(isPressed)
    }
}

@Composable
fun HoldConfirmLatchedTile(
    label: String,
    iconName: String,
    accentColor: Color,
    isLatchedOn: Boolean,
    isPulsingAlert: Boolean = false,
    rootWidthPx: Float,
    palmGuardPx: Float = 14f,
    onStrongBuzz: () -> Unit,
    onConfirmedToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnStrongBuzz by rememberUpdatedState(onStrongBuzz)
    val currentOnConfirmedToggle by rememberUpdatedState(onConfirmedToggle)

    val scope = rememberCoroutineScope()
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }
    var tileRootX by remember { mutableFloatStateOf(0f) }

    val pulseTransition = rememberInfiniteTransition(label = "latchedPulse")
    val alertAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(480, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alertAlpha"
    )

    val bgColor = when {
        isLatchedOn -> accentColor
        isPulsingAlert -> accentColor.copy(alpha = 0.14f * alertAlpha)
        else -> CockpitTile
    }
    val borderColor = when {
        isLatchedOn -> accentColor
        isPulsingAlert -> accentColor.copy(alpha = 0.55f + 0.45f * alertAlpha)
        isHolding -> accentColor.copy(alpha = 0.85f)
        else -> accentColor.copy(alpha = 0.45f)
    }
    val contentTint = if (isLatchedOn) Color(0xFF0D1015) else accentColor

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                tileRootX = coords.positionInRoot().x
            }
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(if (isPulsingAlert) 1.6.dp else 1.25.dp, borderColor, RoundedCornerShape(14.dp))
            .pointerInput(rootWidthPx, tileRootX) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val globalX = tileRootX + down.position.x
                    if (rootWidthPx > 0f && (globalX < palmGuardPx || globalX > rootWidthPx - palmGuardPx)) {
                        return@awaitEachGesture
                    }

                    isHolding = true
                    holdProgress = 0f
                    var confirmed = false

                    val job = scope.launch {
                        val t0 = System.currentTimeMillis()
                        while (true) {
                            val dt = System.currentTimeMillis() - t0
                            holdProgress = (dt / 400f).coerceIn(0f, 1f)
                            if (dt >= 400L) {
                                confirmed = true
                                currentOnStrongBuzz()
                                currentOnConfirmedToggle()
                                break
                            }
                            delay(16L)
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        val inside = change != null &&
                                change.position.x in 0f..size.width.toFloat() &&
                                change.position.y in 0f..size.height.toFloat()

                        if (change == null || !change.pressed || !inside) {
                            if (!confirmed) {
                                job.cancel()
                            }
                            isHolding = false
                            holdProgress = 0f
                            break
                        }
                    }
                }
            }
    ) {
        // Top-right 0.4s hold-to-confirm progress ring
        Canvas(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 6.dp)
                .size(11.dp)
        ) {
            val trackTint = if (isLatchedOn) Color(0xFF0D1015).copy(alpha = 0.28f) else TextMuted.copy(alpha = 0.32f)
            drawArc(
                color = trackTint,
                startAngle = -90f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
            )
            if (holdProgress > 0f) {
                drawArc(
                    color = contentTint,
                    startAngle = -90f,
                    sweepAngle = 360f * holdProgress,
                    useCenter = false,
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CockpitVectorIcon(
                name = iconName,
                tint = contentTint,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = contentTint,
                fontSize = 9.5.sp,
                lineHeight = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun CockpitPedalTile(
    label: String,
    iconName: String,
    accentColor: Color,
    value: Float,
    isDimmedByBrake: Boolean = false,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val effectiveAccent = if (isDimmedByBrake) TextMuted.copy(alpha = 0.45f) else accentColor
    val borderColor = if (isDimmedByBrake) CockpitBorder else accentColor.copy(alpha = 0.55f)
    val clampedValue = value.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitTile)
            .border(1.25.dp, borderColor, RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val h = size.height.toFloat()
                    if (h <= 1f) return@awaitEachGesture

                    val bottomPad = h * 0.14f
                    val topPad = h * 0.16f
                    val travel = (h - bottomPad - topPad).coerceAtLeast(1f)

                    fun computePedal(y: Float): Float {
                        val linear = ((h - bottomPad - y) / travel).coerceIn(0f, 1f)
                        return (linear * (0.35f + 0.65f * linear)).coerceIn(0f, 1f)
                    }

                    currentOnValueChange(computePedal(down.position.y))

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            currentOnValueChange(0f)
                            break
                        }
                        change.consume()
                        currentOnValueChange(computePedal(change.position.y))
                    }
                }
            }
    ) {
        if (clampedValue > 0.005f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(clampedValue)
                    .background(effectiveAccent.copy(alpha = 0.28f))
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CockpitVectorIcon(
                name = iconName,
                tint = effectiveAccent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                color = effectiveAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp
            )
        }
    }
}