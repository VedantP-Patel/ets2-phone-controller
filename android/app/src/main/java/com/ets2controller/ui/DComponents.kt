package com.ets2controller.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Visual state of a tile. Matches the classes used in the design canvas. */
enum class TS { NONE, BLINK, LATCHED, PULSE, GLOW, RIPPLE }

private val RoundTile = RoundedCornerShape(12.dp)

/** Breathing value shared by every blinking element. Provide once at the screen root. */
@Composable
fun rememberBlink(): State<Float> {
    val t = rememberInfiniteTransition(label = "blink")
    return t.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "blinkValue"
    )
}

/** Inward ripple that repeats while cruise is active. */
@Composable
private fun BoxScope.RippleRing(color: Color) {
    val tr = rememberInfiniteTransition(label = "ring")
    val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ringP")
    Canvas(Modifier.matchParentSize()) {
        val g = 10.dp.toPx() * p
        drawRoundRect(
            color.copy(alpha = (1f - p) * 0.8f), Offset(g, g),
            Size(size.width - 2f * g, size.height - 2f * g), CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx())
        )
    }
}

/**
 * The design's button. Icon above a 9sp label.
 * - guarded = needs a hold of [holdMs] with a small ring in the corner (hazard, park, engine, trailer, setup)
 * - onPressChanged = momentary hold (horn)
 * - otherwise a tap that is cancelled if the thumb slides off before lifting
 */
@Composable
fun DTile(
    label: String,
    icon: String?,
    modifier: Modifier = Modifier,
    tint: Color = TextBright,
    borderTint: Color? = null,
    ts: TS = TS.NONE,
    guarded: Boolean = false,
    row: Boolean = false,
    iconSize: Dp = 20.dp,
    holdMs: Long = 400L,
    slideOffCancel: Boolean = true,
    onPressChanged: ((Boolean) -> Unit)? = null,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
    flashKey: Any? = null,
    iconSlot: (@Composable () -> Unit)? = null,
    extra: @Composable BoxScope.() -> Unit = {},
    onTap: () -> Unit = {}
) {
    val buzz = LocalBuzz.current
    val blink = LocalBlink.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    var hold by remember { mutableFloatStateOf(0f) }
    val curTap by rememberUpdatedState(onTap)
    val curPress by rememberUpdatedState(onPressChanged)
    val curUp by rememberUpdatedState(onSwipeUp)
    val curDown by rememberUpdatedState(onSwipeDown)
    val ripple = remember { Animatable(0f) }
    val flash: () -> Unit = { scope.launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(340)) } }
    var firstKey by remember { mutableStateOf(true) }
    LaunchedEffect(flashKey) {
        if (flashKey == null) return@LaunchedEffect
        if (firstKey) { firstKey = false; return@LaunchedEffect }
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(520))
    }

    val live = when (ts) {
        TS.BLINK -> ConnectedGreen
        TS.LATCHED -> ScaniaAmber
        TS.PULSE -> BrakePedalRed
        TS.GLOW, TS.RIPPLE -> VolvoIceBlue
        TS.NONE -> null
    }
    val a = if (ts == TS.BLINK || ts == TS.PULSE || ts == TS.GLOW) blink.value else 1f
    val bg = when {
        ts == TS.PULSE -> BrakePedalRed.copy(alpha = 0.55f * blink.value)
        live != null -> live.copy(alpha = if (ts == TS.LATCHED) 0.14f else 0.18f * a + 0.04f)
        pressed -> ScaniaAmber.copy(alpha = 0.14f)
        else -> CockpitTile
    }
    val bd = when {
        pressed -> ScaniaAmber
        live != null -> live
        else -> borderTint ?: CockpitBorder
    }
    val fg = if (live != null && ts != TS.PULSE) live else tint

    Box(
        modifier = modifier
            .clip(RoundTile)
            .background(bg)
            .border(1.dp, bd, RoundTile)
            .pointerInput(guarded, holdMs, slideOffCancel) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    curPress?.invoke(true)
                    if (curPress == null) buzz(10L, 60)
                    var fired = false
                    var cancelled = false
                    val job = if (guarded) scope.launch {
                        val t0 = System.currentTimeMillis()
                        while (!fired) {
                            val dt = System.currentTimeMillis() - t0
                            hold = (dt / holdMs.toFloat()).coerceIn(0f, 1f)
                            if (dt >= holdMs) {
                                fired = true
                                hold = 0f
                                buzz(32L, 210)
                                curTap()
                                flash()
                            }
                            delay(16L)
                        }
                    } else null
                    val swipePx = 36.dp.toPx()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        val p = ch.position
                        val inside = p.x >= 0f && p.y >= 0f && p.x <= size.width && p.y <= size.height
                        if (!inside && slideOffCancel && curPress == null) cancelled = true
                        if (!ch.pressed) {
                            val dy = p.y - down.position.y
                            val dx = p.x - down.position.x
                            if (!fired && !guarded && curPress == null) {
                                when {
                                    curUp != null && dy < -swipePx && abs(dy) > abs(dx) -> { buzz(18L, 120); curUp?.invoke(); flash() }
                                    curDown != null && dy > swipePx && abs(dy) > abs(dx) -> { buzz(18L, 120); curDown?.invoke(); flash() }
                                    !cancelled && inside -> { curTap(); flash() }
                                }
                            }
                            break
                        }
                        ch.consume()
                    }
                    job?.cancel()
                    hold = 0f
                    pressed = false
                    curPress?.invoke(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (row) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (iconSlot != null) iconSlot() else if (icon != null) DIcon(icon, fg, Modifier.size(iconSize))
                Text(label.uppercase(), color = fg, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp, maxLines = 1)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (iconSlot != null) iconSlot() else if (icon != null) DIcon(icon, fg, Modifier.size(iconSize))
                if (label.isNotEmpty()) Text(
                    label.uppercase(), color = fg, fontSize = 9.sp, lineHeight = 10.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp, maxLines = 2, textAlign = TextAlign.Center
                )
            }
        }
        extra()
        Canvas(Modifier.matchParentSize()) {
            val r = ripple.value
            if (r > 0.001f && r < 0.999f) {
                drawCircle(fg.copy(alpha = (1f - r) * 0.30f), size.maxDimension * 0.75f * r, Offset(size.width / 2f, size.height / 2f))
            }
        }
        if (ts == TS.RIPPLE) RippleRing(fg)
        if (guarded) {
            Canvas(modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(12.dp)) {
                val st = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                if (hold > 0f) {
                    drawArc(CockpitBorder, 0f, 360f, false, style = st)
                    drawArc(ScaniaAmber, -90f, 360f * hold, false, style = st)
                } else {
                    drawArc(TextMuted, -90f, 250f, false, style = st)
                }
            }
        }
    }
}

/** Full-height analog pedal. Touch anywhere, height of the touch is the value, lifting returns to 0. */
@Composable
fun DPedal(
    label: String,
    icon: String,
    color: Color,
    value: Float,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    hot: Boolean = false,
    labelSize: Int = 15,
    onChange: (Float) -> Unit
) {
    val curChange by rememberUpdatedState(onChange)
    val buzz = LocalBuzz.current
    val blinkP = LocalBlink.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitPanel)
            .border(1.dp, if (hot) Color(0xFFFF7A28).copy(alpha = 0.4f + 0.6f * blinkP.value) else if (dimmed) CockpitBorder else color, RoundedCornerShape(14.dp))
            .drawBehind {
                if (hot) drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFFF7A28).copy(alpha = 0.30f * blinkP.value))))
                val h = size.height * value.coerceIn(0f, 1f)
                drawRect(color.copy(alpha = if (dimmed) 0.12f else 0.35f), Offset(0f, size.height - h), Size(size.width, h))
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    buzz(10L, 70)
                    fun v(y: Float) = (1f - y / size.height.toFloat()).coerceIn(0f, 1f)
                    curChange(v(down.position.y))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) break
                        curChange(v(ch.position.y))
                        ch.consume()
                    }
                    curChange(0f)
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier.padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            DIcon(icon, if (dimmed) TextMuted else color, Modifier.size(26.dp))
            Text(label, color = if (dimmed) TextMuted else color, fontSize = labelSize.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun DCard(modifier: Modifier = Modifier, borderColor: Color = CockpitBorder, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitPanel)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp)),
        content = content
    )
}

/** Four pips for the retarder level, lit one by one with a soft glow. */
@Composable
fun DPips(level: Int, count: Int = 4) {
    val t = rememberInfiniteTransition(label = "pips")
    val glow by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "pipGlow"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 1..count) {
            val on = i <= level
            Box(
                modifier = Modifier
                    .size(width = 10.dp, height = 16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (on) ScaniaAmber.copy(alpha = 0.75f + 0.25f * glow) else CockpitBorder)
            )
        }
    }
}

/** Bouncing equalizer bars for the radio strip. */
@Composable
fun DEq(active: Boolean) {
    val t = rememberInfiniteTransition(label = "eq")
    val phases = (0 until 5).map { i ->
        t.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(520 + i * 70, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "eq$i"
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(20.dp)) {
        phases.forEach { p ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((20f * (if (active) p.value else 0.25f)).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ScaniaAmber)
            )
        }
    }
}

/** RPM bar: blue to amber to red track with the unfilled part masked. */
@Composable
fun DRpmBar(ratio: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.height(10.dp).clip(RoundedCornerShape(5.dp))) {
        drawRect(
            Brush.horizontalGradient(
                0f to VolvoIceBlue, 0.70f to VolvoIceBlue, 0.70f to ScaniaAmber,
                0.86f to ScaniaAmber, 0.86f to BrakePedalRed, 1f to BrakePedalRed
            )
        )
        val filled = size.width * ratio.coerceIn(0f, 1f)
        drawRect(DarkBackground, Offset(filled, 0f), Size(size.width - filled, size.height))
    }
}

/** Full-screen edge glows. Left and right turn sweeps, redline vignette, cruise top line. */
@Composable
fun DAlertOverlays(
    left: Boolean,
    right: Boolean,
    redline: Boolean,
    cruise: Boolean,
    emergency: Boolean,
    brake: Float = 0f
) {
    val blink = LocalBlink.current
    val a = blink.value
    val stripeTr = rememberInfiniteTransition(label = "stripes")
    val stripe by stripeTr.animateFloat(0f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "stripePhase")
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = 90.dp.toPx()
        if (left) drawRect(Brush.horizontalGradient(listOf(ConnectedGreen.copy(alpha = 0.55f * a), Color.Transparent), 0f, w), size = Size(w, size.height))
        if (right) drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, ConnectedGreen.copy(alpha = 0.55f * a)), size.width - w, size.width),
            topLeft = Offset(size.width - w, 0f), size = Size(w, size.height)
        )
        if (redline) {
            val e = 70.dp.toPx()
            val c = BrakePedalRed.copy(alpha = 0.6f * a)
            drawRect(Brush.verticalGradient(listOf(c, Color.Transparent), 0f, e), size = Size(size.width, e))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, c), size.height - e, size.height), topLeft = Offset(0f, size.height - e), size = Size(size.width, e))
            drawRect(Brush.horizontalGradient(listOf(c, Color.Transparent), 0f, e), size = Size(e, size.height))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c), size.width - e, size.width), topLeft = Offset(size.width - e, 0f), size = Size(e, size.height))
        }
        if (brake > 0.25f) {
            val bh = 70.dp.toPx()
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, BrakePedalRed.copy(alpha = 0.28f * brake)), size.height - bh, size.height),
                topLeft = Offset(0f, size.height - bh), size = Size(size.width, bh)
            )
        }
        if (cruise) drawRect(VolvoIceBlue.copy(alpha = 0.55f + 0.45f * a), size = Size(size.width, 3.dp.toPx()))
        if (emergency) {
            val h = 14.dp.toPx()
            var x = -h + stripe * h
            while (x < size.width) {
                drawRect(BrakePedalRed.copy(alpha = 0.9f), Offset(x, size.height - h), Size(h / 2f, h))
                x += h
            }
        }
    }
}

/** Thin pill used for the dash banner. */
@Composable
fun DChip(text: String, icon: String?, color: Color, pulse: Boolean) {
    val blink = LocalBlink.current
    val bg = if (pulse) color.copy(alpha = 0.55f * blink.value) else color.copy(alpha = 0.16f)
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, color, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) DIcon(icon, if (pulse) TextBright else color, Modifier.size(13.dp), 2f)
        Text(text.uppercase(), color = if (pulse) TextBright else color, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

/** Wheel angle button in the dash. Hold to re-centre. Ring pulses green when centred, amber on drift. */
@Composable
fun DWheelCenter(
    angleDeg: Float,
    centered: Boolean,
    drift: Boolean,
    stageLabel: String,
    holdMs: Long,
    onCenter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buzz = LocalBuzz.current
    val blink = LocalBlink.current
    val scope = rememberCoroutineScope()
    var hold by remember { mutableFloatStateOf(0f) }
    val ringColor = when {
        drift -> ScaniaAmber
        centered -> ConnectedGreen
        else -> CockpitBorder
    }
    val curCenter by rememberUpdatedState(onCenter)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitDeep)
            .border(1.dp, if (hold > 0f) ScaniaAmber else CockpitBorder, RoundedCornerShape(14.dp))
            .pointerInput(holdMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var fired = false
                    val job = scope.launch {
                        val t0 = System.currentTimeMillis()
                        while (!fired) {
                            val dt = System.currentTimeMillis() - t0
                            hold = (dt / holdMs.toFloat()).coerceIn(0f, 1f)
                            if (dt >= holdMs) {
                                fired = true
                                hold = 0f
                                buzz(32L, 210)
                                curCenter()
                            }
                            delay(16L)
                        }
                    }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) break
                        ch.consume()
                    }
                    job.cancel()
                    hold = 0f
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (drift || centered) ringColor.copy(alpha = 0.4f + 0.6f * blink.value) else ringColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(28.dp)) {
                    rotate(angleDeg) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val st = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        drawCircle(TextMuted, size.width * 0.40f, c, style = st)
                        drawLine(TextMuted, Offset(size.width * 0.1f, c.y), Offset(size.width * 0.9f, c.y), 3.dp.toPx(), StrokeCap.Round)
                        drawLine(TextMuted, c, Offset(c.x, size.height * 0.9f), 3.dp.toPx(), StrokeCap.Round)
                        drawCircle(ScaniaAmber, 2.5.dp.toPx(), Offset(c.x, size.height * 0.1f))
                    }
                }
            }
            Text("%+.1f\u00B0".format(angleDeg), color = TextBright, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                DIcon("center", if (drift) ScaniaAmber else TextMuted, Modifier.size(11.dp), 2f)
                Text(stageLabel, color = if (drift) ScaniaAmber else TextMuted, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Canvas(modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(12.dp)) {
            val st = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
            if (hold > 0f) {
                drawArc(CockpitBorder, 0f, 360f, false, style = st)
                drawArc(ScaniaAmber, -90f, 360f * hold, false, style = st)
            } else {
                drawArc(TextMuted, -90f, 250f, false, style = st)
            }
        }
    }
}
