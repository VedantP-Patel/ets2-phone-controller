package com.ets2controller.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finger travel (-1..1) to steering (-1..1). Inside the fine zone the gain is halved
 * for small corrections; outside it the rest of the travel maps up to full lock.
 */
internal fun slideCurve(n: Float, fine: Float = 0.10f): Float {
    val a = abs(n)
    val out = if (a <= fine) {
        a * 0.5f
    } else {
        0.5f * fine + (a - fine) * ((1f - 0.5f * fine) / (1f - fine))
    }
    return if (n < 0f) -out else out
}

/**
 * Horizontal sliding steering bar. Touch anywhere on the bar, drag left or right
 * relative to where the thumb landed, and the handle springs back to zero on release.
 * [onSteerChange] receives the steering value in -1..1 on every frame.
 */
@Composable
fun SlideSteeringBar(
    accentColor: Color,
    halfRangeDeg: Float = 450f,
    onSteerChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(0f) }
    val currentOnSteer by rememberUpdatedState(onSteerChange)
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        snapshotFlow { pos.value }.collect { currentOnSteer(slideCurve(it)) }
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CockpitTile)
            .border(1.dp, accentColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = down.id
                    val x0 = down.position.x
                    val halfW = (size.width / 2f).coerceAtLeast(1f)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == id }
                        if (ch == null || !ch.pressed) break
                        val n = ((ch.position.x - x0) / halfW).coerceIn(-1f, 1f)
                        scope.launch { pos.snapTo(n) }
                        ch.consume()
                    }
                    scope.launch {
                        pos.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium))
                    }
                }
            }
    ) {
        val handleW = 70.dp
        val wPx = with(density) { maxWidth.toPx() }
        val handleWPx = with(density) { handleW.toPx() }
        val travel = (wPx - handleWPx).coerceAtLeast(0f)
        val handleX = (pos.value * 0.5f + 0.5f) * travel
        val steer = slideCurve(pos.value)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val trackH = 6.dp.toPx()
            val pad = 8.dp.toPx()
            drawRoundRect(
                color = CockpitBorder,
                topLeft = Offset(pad, cy - trackH / 2f),
                size = Size(size.width - pad * 2f, trackH),
                cornerRadius = CornerRadius(trackH / 2f)
            )
            val fz = cx * 0.10f
            drawRoundRect(
                color = VolvoIceBlue.copy(alpha = 0.14f),
                topLeft = Offset(cx - fz, cy - 12.dp.toPx()),
                size = Size(fz * 2f, 24.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
            for (i in -10..10) {
                val x = cx + (i / 10f) * (cx - pad)
                val len = if (i % 5 == 0) 10.dp.toPx() else 6.dp.toPx()
                drawLine(
                    color = TextMuted.copy(alpha = 0.5f),
                    start = Offset(x, cy + 10.dp.toPx()),
                    end = Offset(x, cy + 10.dp.toPx() + len),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            val hc = handleX + handleWPx / 2f
            drawRoundRect(
                color = accentColor,
                topLeft = Offset(min(cx, hc), cy - trackH / 2f),
                size = Size(abs(hc - cx), trackH),
                cornerRadius = CornerRadius(trackH / 2f)
            )
            drawLine(
                color = ConnectedGreen,
                start = Offset(cx, cy - 20.dp.toPx()),
                end = Offset(cx, cy - 13.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(handleX.roundToInt(), 0) }
                .size(width = handleW, height = maxHeight - 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accentColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "%+d\u00B0".format((steer * halfRangeDeg).roundToInt()),
                color = Color(0xFF0D1015),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}
