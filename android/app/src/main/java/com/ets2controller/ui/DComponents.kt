package com.ets2controller.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Ergonomic thumb-pedal mapping with bottom rest pad, top full-lock band, and optional relative drag. */
object PedalMath {
    fun mapAbsolutePosition(y: Float, height: Float, bottomPadFrac: Float = 0.10f, topSatFrac: Float = 0.12f): Float {
        if (height <= 1f) return 0f
        val normFromBottom = 1f - (y / height).coerceIn(0f, 1f)
        if (normFromBottom <= bottomPadFrac) return 0f
        if (normFromBottom >= 1f - topSatFrac) return 1f
        val activeSpan = (1f - bottomPadFrac - topSatFrac).coerceAtLeast(0.1f)
        val linear = ((normFromBottom - bottomPadFrac) / activeSpan).coerceIn(0f, 1f)
        return linear.pow(1.22f).coerceIn(0f, 1f)
    }

    fun mapRelativeDelta(startY: Float, currentY: Float, activeTravelPx: Float): Float {
        if (activeTravelPx <= 1f) return 0f
        val upDrag = (startY - currentY) / activeTravelPx
        return upDrag.coerceIn(0f, 1f).pow(1.15f)
    }
}

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

    val accent = LocalAccent.current
    val live = when (ts) {
        TS.BLINK -> ConnectedGreen
        TS.LATCHED -> accent
        TS.PULSE -> BrakePedalRed
        TS.GLOW, TS.RIPPLE -> VolvoIceBlue
        TS.NONE -> null
    }
    val a = if (ts == TS.BLINK || ts == TS.PULSE || ts == TS.GLOW) blink.value else 1f
    val bg = when {
        ts == TS.PULSE -> BrakePedalRed.copy(alpha = 0.55f * blink.value)
        live != null -> live.copy(alpha = if (ts == TS.LATCHED) 0.14f else 0.18f * a + 0.04f)
        pressed -> accent.copy(alpha = 0.14f)
        else -> CockpitTile
    }
    val bd = when {
        pressed -> accent
        live != null -> live
        else -> borderTint ?: CockpitBorder
    }
    val fg = if (live != null && ts != TS.PULSE) live else tint
    val reduceMotion = LocalReduceMotion.current
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "tilePressScale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .semantics {
                contentDescription = label.ifBlank { icon ?: "Control" }
                role = Role.Button
            }
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
                        if (awaitHoldConfirm(holdMs) { hold = it }) {
                            fired = true
                            buzz(32L, 210)
                            curTap()
                            flash()
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
                    drawArc(accent, -90f, 360f * hold, false, style = st)
                } else {
                    drawArc(TextMuted, -90f, 250f, false, style = st)
                }
            }
        }
    }
}

/** Full-height analog pedal with bottom rest pad, top full-lock zone, meniscus glow, and 25/50/75/100% detents. */
@Composable
fun DPedal(
    label: String,
    icon: String,
    color: Color,
    value: Float,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    hot: Boolean = false,
    relativeDrag: Boolean = false,
    labelSize: Int = 15,
    onChange: (Float) -> Unit
) {
    val curChange by rememberUpdatedState(onChange)
    val curRel by rememberUpdatedState(relativeDrag)
    val buzz = LocalBuzz.current
    val blinkP = LocalBlink.current
    val displayVal by animateFloatAsState(
        targetValue = value.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 45, easing = LinearEasing),
        label = "pedalTrail"
    )
    val pct = (displayVal * 100f).roundToInt()

    Box(
        modifier = modifier
            .semantics { contentDescription = "$label pedal" }
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitPanel)
            .border(1.dp, if (hot) Color(0xFFFF7A28).copy(alpha = 0.4f + 0.6f * blinkP.value) else if (dimmed) CockpitBorder else color, RoundedCornerShape(14.dp))
            .drawBehind {
                if (hot) drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFFF7A28).copy(alpha = 0.30f * blinkP.value))))
                val h = size.height * displayVal
                if (h > 1f) {
                    drawRect(
                        Brush.verticalGradient(
                            colors = listOf(color.copy(alpha = if (dimmed) 0.16f else 0.46f), color.copy(alpha = if (dimmed) 0.06f else 0.22f)),
                            startY = size.height - h,
                            endY = size.height
                        ),
                        topLeft = Offset(0f, size.height - h),
                        size = Size(size.width, h)
                    )
                    drawLine(
                        color = color.copy(alpha = if (dimmed) 0.35f else 0.92f),
                        start = Offset(4.dp.toPx(), size.height - h),
                        end = Offset(size.width - 4.dp.toPx(), size.height - h),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    buzz(10L, 70)
                    val travelPx = 88.dp.toPx().coerceAtLeast(size.height * 0.65f)
                    fun eval(y: Float): Float = if (curRel) {
                        PedalMath.mapRelativeDelta(down.position.y, y, travelPx)
                    } else {
                        PedalMath.mapAbsolutePosition(y, size.height.toFloat())
                    }
                    var lastNotch = -1
                    fun emit(v: Float) {
                        curChange(v)
                        val notch = (v * 4f).toInt()
                        if (notch != lastNotch && notch > 0) {
                            lastNotch = notch
                            buzz(8L, 45)
                        } else if (notch == 0) {
                            lastNotch = 0
                        }
                    }
                    emit(eval(down.position.y))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) break
                        emit(eval(ch.position.y))
                        ch.consume()
                    }
                    curChange(0f)
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        if (pct > 1 && !dimmed) {
            Text(
                text = "$pct%",
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
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

/** Four pips for the retarder level; animates glow and bounce-pop on activation. */
@Composable
fun DPips(level: Int, count: Int = 4, pipW: Dp = 10.dp) {
    val accent = LocalAccent.current
    val reduceMotion = LocalReduceMotion.current
    val glow = if (level > 0 && !reduceMotion) {
        val t = rememberInfiniteTransition(label = "pips")
        t.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
            label = "pipGlow"
        ).value
    } else 1f

    Row(horizontalArrangement = Arrangement.spacedBy(if (pipW < 9.dp) 3.dp else 4.dp)) {
        for (i in 1..count) {
            val on = i <= level
            val pipScale by animateFloatAsState(
                targetValue = if (on) 1.0f else 0.86f,
                animationSpec = if (reduceMotion) tween(0) else spring(dampingRatio = 0.48f, stiffness = Spring.StiffnessMedium),
                label = "pipScale$i"
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { scaleX = pipScale; scaleY = pipScale }
                    .size(width = pipW, height = 14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (on) accent.copy(alpha = 0.75f + 0.25f * glow) else CockpitBorder)
            )
        }
    }
}

/** Bouncing equalizer bars for the radio strip; animates only when radio is active. */
@Composable
fun DEq(active: Boolean) {
    val accent = LocalAccent.current
    val reduceMotion = LocalReduceMotion.current
    val phases: List<Float> = if (active && !reduceMotion) {
        val t = rememberInfiniteTransition(label = "eq")
        (0 until 5).map { i ->
            t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(520 + i * 70, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "eq$i"
            ).value
        }
    } else {
        listOf(0.28f, 0.45f, 0.32f, 0.50f, 0.28f)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(20.dp)) {
        phases.forEach { p ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((20f * (if (active) p else 0.25f)).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
    }
}

/** Segmented LED RPM bar: 18 crisp cells (blue -> accent -> red) with 650ms peak-hold LED and shift flash. */
@Composable
fun DRpmBar(ratio: Float, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val blink = LocalBlink.current
    val reduceMotion = LocalReduceMotion.current
    val clamped = ratio.coerceIn(0f, 1f)

    var peakTarget by remember { mutableFloatStateOf(0f) }
    var peakHoldUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(clamped) {
        val now = System.currentTimeMillis()
        if (clamped >= peakTarget) {
            peakTarget = clamped
            peakHoldUntil = now + 650L
        } else if (now > peakHoldUntil) {
            peakTarget = clamped
        }
        if (peakTarget > clamped) {
            val wait = (peakHoldUntil - now).coerceAtLeast(40L)
            delay(wait)
            peakTarget = clamped
        }
    }
    val peakDisplay by animateFloatAsState(
        targetValue = peakTarget,
        animationSpec = if (reduceMotion) tween(0) else tween(220, easing = FastOutSlowInEasing),
        label = "rpmPeak"
    )

    Canvas(modifier = modifier.height(10.dp)) {
        val segCount = 18
        val gap = 3.dp.toPx()
        val segW = ((size.width - gap * (segCount - 1)) / segCount).coerceAtLeast(2f)
        val litCount = (clamped * segCount + 0.2f).toInt().coerceIn(0, segCount)
        val peakIdx = ((peakDisplay * segCount).toInt() - 1).coerceIn(-1, segCount - 1)
        val shiftFlash = if (clamped > 0.86f) (0.55f + 0.45f * blink.value) else 1f
        val r = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())

        for (i in 0 until segCount) {
            val frac = (i + 1) / segCount.toFloat()
            val segColor = when {
                frac <= 0.70f -> VolvoIceBlue
                frac <= 0.86f -> accent
                else -> BrakePedalRed
            }
            val isLit = i < litCount
            val isPeak = !isLit && i == peakIdx && peakDisplay > 0.12f
            drawRoundRect(
                color = when {
                    isLit -> segColor.copy(alpha = if (frac > 0.86f) shiftFlash else 1f)
                    isPeak -> segColor.copy(alpha = 0.80f)
                    else -> CockpitDeep
                },
                topLeft = Offset(i * (segW + gap), 0f),
                size = Size(segW, size.height),
                cornerRadius = r
            )
        }
    }
}

/** Full-screen edge glows. Left/right turn sweeps, redline vignette, cruise top line, active-only emergency stripes. */
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
    val reduceMotion = LocalReduceMotion.current
    val stripe = if (emergency && !reduceMotion) {
        val stripeTr = rememberInfiniteTransition(label = "stripes")
        stripeTr.animateFloat(0f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "stripePhase").value
    } else 0f

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

private suspend fun awaitHoldConfirm(holdMs: Long, onProgress: (Float) -> Unit): Boolean {
    val duration = holdMs.coerceAtLeast(1L).toFloat()
    val startMs = withFrameMillis { it }
    while (true) {
        val dt = withFrameMillis { it } - startMs
        onProgress((dt / duration).coerceIn(0f, 1f))
        if (dt >= holdMs) {
            onProgress(0f)
            return true
        }
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
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var hold by remember { mutableFloatStateOf(0f) }
    val ringColor = when {
        drift -> accent
        centered -> ConnectedGreen
        else -> CockpitBorder
    }
    val curCenter by rememberUpdatedState(onCenter)
    Box(
        modifier = modifier
            .semantics {
                contentDescription = "Steering angle %+.1f degrees, hold to center".format(angleDeg)
                role = Role.Button
            }
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitDeep)
            .border(1.dp, if (hold > 0f) accent else CockpitBorder, RoundedCornerShape(14.dp))
            .pointerInput(holdMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val job = scope.launch {
                        if (awaitHoldConfirm(holdMs) { hold = it }) {
                            buzz(32L, 210)
                            curCenter()
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
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (drift || centered) ringColor.copy(alpha = 0.4f + 0.6f * blink.value) else ringColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(32.dp)) {
                    val arcSweep = angleDeg.coerceIn(-135f, 135f)
                    if (abs(arcSweep) > 1.0f) {
                        val pad = 2.dp.toPx()
                        drawArc(
                            color = accent.copy(alpha = 0.85f),
                            startAngle = -90f,
                            sweepAngle = arcSweep,
                            useCenter = false,
                            topLeft = Offset(pad, pad),
                            size = Size(size.width - pad * 2f, size.height - pad * 2f),
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    rotate(angleDeg) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val st = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                        drawCircle(TextMuted, size.width * 0.29f, c, style = st)
                        drawLine(TextMuted, Offset(size.width * 0.22f, c.y), Offset(size.width * 0.78f, c.y), 2.4.dp.toPx(), StrokeCap.Round)
                        drawLine(TextMuted, c, Offset(c.x, size.height * 0.78f), 2.4.dp.toPx(), StrokeCap.Round)
                        drawCircle(accent, 2.2.dp.toPx(), Offset(c.x, size.height * 0.21f))
                    }
                }
            }
            Text("%+.1f\u00B0".format(angleDeg), color = TextBright, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                DIcon("center", if (drift) accent else TextMuted, Modifier.size(10.dp), 2f)
                Text(stageLabel, color = if (drift) accent else TextMuted, fontSize = 8.5.sp, lineHeight = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
        Canvas(modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(12.dp)) {
            val st = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
            if (hold > 0f) {
                drawArc(CockpitBorder, 0f, 360f, false, style = st)
                drawArc(accent, -90f, 360f * hold, false, style = st)
            } else {
                drawArc(TextMuted, -90f, 250f, false, style = st)
            }
        }
    }
}
