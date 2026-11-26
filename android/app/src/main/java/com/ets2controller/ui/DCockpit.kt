package com.ets2controller.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.config.ControllerSettings
import com.ets2controller.network.TruckTelemetryV2
import com.ets2controller.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SteerMode { GYRO, PAD, SLIDE }

private class Banner(val text: String, val icon: String, val color: Color, val pulse: Boolean)

/**
 * The redesigned cockpit. Same inputs as the original ControllerScreen so it can replace it
 * in MainActivity. Layout, sizes, colours, icons and button rules follow the design canvas.
 */
@Composable
fun DesignCockpitScreen(
    isConnected: Boolean,
    connectedIp: String,
    packetsPerSec: Int,
    savedManualIp: String,
    onSetManualIp: (String) -> Unit,
    rawAngleProvider: () -> Float,
    throttle: Float,
    brake: Float,
    clutch: Float,
    buttonMask: Long,
    settings: ControllerSettings,
    telemetry: TruckTelemetryV2,
    onThrottleChange: (Float) -> Unit,
    onBrakeChange: (Float) -> Unit,
    onClutchChange: (Float) -> Unit,
    onButtonStateChange: (Int, Boolean) -> Unit,
    onPulseBit: (Int) -> Unit,
    onCalibrateClick: () -> Unit,
    onSaveSettings: (ControllerSettings) -> Unit,
    onLookChange: (Float, Float) -> Unit,
    onTouchSteerChange: (Float?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = telemetry
    val accent = getThemeAccent(settings.clusterTheme)

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    val buzz: (Long, Int) -> Unit = remember(settings.hapticFeedbackEnabled, settings.hapticStrength) {
        { ms: Long, amp: Int ->
            if (settings.hapticFeedbackEnabled && vibrator.hasVibrator()) {
                val a = (amp * settings.hapticStrength / 0.8f).roundToInt().coerceIn(1, 255)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(ms, a))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(ms)
                }
            }
        }
    }
    val blink = rememberBlink()

    var mode by remember { mutableStateOf(SteerMode.GYRO) }
    var padNorm by remember { mutableFloatStateOf(0f) }
    var showMore by remember { mutableStateOf(false) }
    var showData by remember { mutableStateOf(false) }
    var showDock by remember { mutableStateOf(false) }
    var showTuning by remember { mutableStateOf(false) }
    var showConn by remember { mutableStateOf(false) }
    var localRetarder by remember { mutableIntStateOf(0) }
    var radioOn by remember { mutableStateOf(false) }
    var recenterLabel by remember { mutableStateOf("CENTER") }
    var brakeTempC by remember { mutableFloatStateOf(140f) }

    LaunchedEffect(brake, t.speedKmh, t.retarderLevel, localRetarder) {
        while (true) {
            val r = maxOf(t.retarderLevel, localRetarder)
            val gain = if (brake > 0.1f && t.speedKmh > 5f) brake * 6.5f else 0f
            brakeTempC = (brakeTempC + gain - (1.8f + r * 0.6f)).coerceIn(140f, 440f)
            delay(250L)
        }
    }

    var startSweep by remember { mutableFloatStateOf(0f) }
    var wasRunning by remember { mutableStateOf(t.isEngineRunning) }
    LaunchedEffect(t.isEngineRunning) {
        if (t.isEngineRunning && !wasRunning) {
            animate(0f, 1f, animationSpec = tween(500)) { v, _ -> startSweep = v }
            animate(1f, 0f, animationSpec = tween(650)) { v, _ -> startSweep = v }
        }
        wasRunning = t.isEngineRunning
    }

    val brakeWins = settings.brakeWins && brake > 0.02f
    LaunchedEffect(brake, settings.brakeWins) {
        if (settings.brakeWins && brake > 0.02f && throttle > 0f) onThrottleChange(0f)
    }

    val isAuto = (t.safetyFlags and (1 shl 5)) != 0
    val rpmRatio = if (t.maxRpm > 100f) (t.engineRpm / t.maxRpm).coerceIn(0f, 1.1f) else 0f
    val isRedline = rpmRatio > 0.90f
    val shiftCue = !isAuto && rpmRatio > 0.86f
    val k = if (settings.speedUnitMph) 0.621371f else 1f
    val speed = (t.speedKmh * k).roundToInt()
    val limit = if (t.speedLimitKmh > 0) (t.speedLimitKmh * k).roundToInt() else 0
    val overspeed = limit > 0 && speed > limit + (5 * k).roundToInt()
    val rawAngle = if (mode == SteerMode.GYRO) rawAngleProvider() else padNorm * 45f
    val angle = ((rawAngle + 180f).mod(360f)) - 180f
    val centered = abs(angle) < 1.2f
    val drift = mode == SteerMode.GYRO && t.speedKmh > 35f && abs(angle) in 4f..14f && recenterLabel == "CENTER"
    val parkWarn = t.isParkBrake && t.speedKmh > 3f
    val gearText = when {
        t.gear == 0 -> "N"
        t.gear < 0 -> "R"
        isAuto -> "D${t.gear}"
        else -> "${t.gear}"
    }
    val banner: Banner? = when {
        parkWarn -> Banner("Release park brake", "park", BrakePedalRed, true)
        shiftCue -> Banner("Shift up", "up", BrakePedalRed, true)
        isRedline -> Banner("Redline", "gauge", BrakePedalRed, true)
        t.isLowAir -> Banner("Low air", "air", accent, true)
        t.fuelPercent in 1..15 -> Banner("Low fuel", "fuel", accent, true)
        t.isCruiseActive -> Banner("Cruise ${(t.cruiseSpeedKmh * k).roundToInt()}", "gauge", CruiseSkyBlue, false)
        else -> null
    }
    val rowH = (58f * settings.controlScale).dp
    val edge = maxOf(12, settings.edgeGuardDp).dp
    val holdMs = settings.holdConfirmMs.toLong()
    val slideOff = settings.slideOffCancel
    val retarderLevel = maxOf(t.retarderLevel, localRetarder).coerceIn(0, 4)
    val lightsLabel = when {
        t.isLowBeams -> "Low"
        (t.safetyFlags and (1 shl 6)) != 0 -> "Park"
        else -> "Off"
    }
    val lightDot = when (lightsLabel) { "Low" -> 2; "Park" -> 1; else -> 0 }
    val ctx = TileCtx(holdMs, slideOff)

    fun pulse(bit: Int) = onPulseBit(bit)
    fun setMode(m: SteerMode) {
        mode = m
        padNorm = 0f
        onTouchSteerChange(if (m == SteerMode.GYRO) null else 0f)
    }

    CompositionLocalProvider(LocalBlink provides blink, LocalBuzz provides buzz) {
        Box(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(start = edge, end = edge, top = 10.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ---------------- TOP BAR ----------------
                Row(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.width(120.dp).clickable { showConn = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val dot by rememberInfiniteTransition(label = "linkDot").animateFloat(
                            0.3f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "linkDotA"
                        )
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background((if (isConnected) ConnectedGreen else DisconnectedRed).copy(alpha = if (isConnected) dot else blink.value))
                        )
                        Text(
                            if (isConnected) "PC LINKED" else "NO PC",
                            color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp, maxLines = 1
                        )
                    }
                    DCard(modifier = Modifier.width(300.dp).fillMaxHeight()) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(44.dp).fillMaxHeight().clickable { pulse(16) }, Alignment.Center) { DIcon("prev", TextBright, Modifier.size(16.dp)) }
                            Row(
                                Modifier.weight(1f).clickable { radioOn = !radioOn; pulse(14) },
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DEq(radioOn)
                                Spacer(Modifier.width(10.dp))
                                Text(if (radioOn) "CAB RADIO" else "TAP FOR RADIO", color = TextBright, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp, maxLines = 1)
                            }
                            Box(Modifier.width(44.dp).fillMaxHeight().clickable { pulse(15) }, Alignment.Center) { DIcon("next", TextBright, Modifier.size(16.dp)) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        DModeSwitch(mode, accent) { setMode(it) }
                        DTile("", "data", Modifier.size(44.dp, 40.dp), iconSize = 18.dp, onTap = { showData = true })
                        DTile("", "more", Modifier.size(44.dp, 40.dp), tint = accent, iconSize = 20.dp, onTap = { showMore = true })
                        DTile("", "gear", Modifier.size(44.dp, 40.dp), guarded = true, holdMs = holdMs, iconSize = 18.dp, onTap = { showTuning = true })
                    }
                }

                // ---------------- BODY ----------------
                val dash: @Composable (Modifier, Boolean) -> Unit = { m, compact ->
                    Dash(
                        modifier = m, compact = compact, banner = banner, limit = limit, speed = speed,
                        speedUnit = if (settings.speedUnitMph) "mph" else "km/h", gear = gearText, rpmRatio = maxOf(rpmRatio, startSweep), overspeed = overspeed,
                        rpmText = "%,d".format(t.engineRpm.roundToInt()), shake = isRedline || overspeed,
                        speedColor = if (overspeed || isRedline) BrakePedalRed else TextBright,
                        showWheel = mode == SteerMode.GYRO, angle = angle, centered = centered, drift = drift,
                        recenterLabel = if (drift) "DRIFT?" else recenterLabel, holdMs = holdMs,
                        onCenter = {
                            scope.launch {
                                recenterLabel = "HOLD STEADY"
                                delay(600L)
                                onCalibrateClick()
                                recenterLabel = "CENTERED"
                                delay(900L)
                                recenterLabel = "CENTER"
                            }
                        },
                        air = t.airPressureBar, airWarn = t.isLowAir || t.airPressureBar < 4.5f,
                        fuel = t.fuelPercent, adblue = t.adbluePercent, brakeTemp = brakeTempC.roundToInt()
                    )
                }
                val retarder: @Composable (Modifier) -> Unit = { m ->
                    RetarderStepper(retarderLevel, m,
                        onDown = { localRetarder = (localRetarder - 1).coerceAtLeast(0); pulse(25) },
                        onUp = { localRetarder = (localRetarder + 1).coerceAtMost(4); pulse(24) })
                }
                val wipers: @Composable (Modifier) -> Unit = { m ->
                    WipersTile(m, t.isWipersActive, ctx, onTap = { pulse(12) }, onOff = { pulse(36) })
                }
                val guardedRow: @Composable (Modifier) -> Unit = { m ->
                    Row(m, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DTile("Hazard", "hazard", Modifier.weight(1f).fillMaxHeight(), tint = SoftRed, borderTint = SoftRed,
                            ts = if (t.isHazard) TS.PULSE else TS.NONE, flashKey = t.isHazard, guarded = settings.hazardNeedsHold, holdMs = holdMs, slideOffCancel = slideOff, onTap = { pulse(3) })
                        DTile("Park", "park", Modifier.weight(1f).fillMaxHeight(), tint = SoftRed,
                            ts = if (parkWarn) TS.PULSE else if (t.isParkBrake) TS.LATCHED else TS.NONE, flashKey = t.isParkBrake, guarded = true, holdMs = holdMs, onTap = { pulse(6) })
                        DTile("Engine", "engine", Modifier.weight(1f).fillMaxHeight(), tint = ConnectedGreen,
                            ts = if (t.isEngineRunning) TS.LATCHED else TS.NONE, flashKey = t.isEngineRunning, guarded = true, holdMs = holdMs, onTap = { pulse(1) })
                        DTile("Trailer", "trailer", Modifier.weight(1f).fillMaxHeight(),
                            ts = if ((t.safetyFlags and 1) != 0) TS.LATCHED else TS.NONE, flashKey = (t.safetyFlags and 1), guarded = true, holdMs = holdMs, onTap = { pulse(20) })
                        DTile("Diff lock", "lock", Modifier.weight(1f).fillMaxHeight(),
                            ts = if (t.isDiffLock) TS.LATCHED else TS.NONE, flashKey = t.isDiffLock, slideOffCancel = slideOff, onTap = { pulse(18) })
                    }
                }
                val turnLeft: @Composable (Modifier) -> Unit = { m ->
                    DTile("Left", "left", m, tint = ConnectedGreen, borderTint = ConnectedGreen, row = true, iconSize = 26.dp,
                        ts = if (t.isLeftBlinker || t.isHazard) TS.BLINK else TS.NONE, slideOffCancel = slideOff, onTap = { pulse(4) })
                }
                val turnRight: @Composable (Modifier) -> Unit = { m ->
                    DTile("Right", "right", m, tint = ConnectedGreen, borderTint = ConnectedGreen, row = true, iconSize = 26.dp,
                        ts = if (t.isRightBlinker || t.isHazard) TS.BLINK else TS.NONE, slideOffCancel = slideOff, onTap = { pulse(5) })
                }
                val horn: @Composable (Modifier) -> Unit = { m ->
                    DTile("Horn / Air", "horn", m, tint = accent, borderTint = accent, row = true, onPressChanged = { onButtonStateChange(0, it) })
                }
                val lights: @Composable (Modifier) -> Unit = { m ->
                    DTile("Lights $lightsLabel", "low", m, slideOffCancel = slideOff, onTap = { pulse(2) }, extra = {
                        Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (i in 0..2) Box(Modifier.size(5.dp).clip(CircleShape).background(if (i == lightDot) TextBright else CockpitBorder))
                        }
                    })
                }
                val highBeam: @Composable (Modifier) -> Unit = { m ->
                    DTile("High beam", "high", m, ts = if (t.isHighBeams) TS.GLOW else TS.NONE, flashKey = t.isHighBeams, slideOffCancel = slideOff, onTap = { pulse(21) })
                }
                val shift: @Composable (Modifier) -> Unit = { m ->
                    Row(m, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DTile("Up", "up", Modifier.weight(1f).fillMaxHeight(), tint = accent, borderTint = accent,
                            ts = if (shiftCue) TS.PULSE else TS.NONE, slideOffCancel = slideOff, onTap = { pulse(10) })
                        DTile("Neut", "n", Modifier.weight(1f).fillMaxHeight(), slideOffCancel = slideOff, onTap = { pulse(17) })
                        DTile("Down", "down", Modifier.weight(1f).fillMaxHeight(), tint = accent, slideOffCancel = slideOff, onTap = { pulse(11) })
                    }
                }
                val cruiseRow: @Composable (Modifier) -> Unit = { m ->
                    Row(m, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DTile("Slower", "minus", Modifier.weight(1f).fillMaxHeight(), tint = CruiseSkyBlue, slideOffCancel = slideOff, onTap = { pulse(27) })
                        DTile(
                            if (t.isCruiseActive) "${(t.cruiseSpeedKmh * k).roundToInt()}" else "Cruise", "gauge",
                            Modifier.weight(1.2f).fillMaxHeight(), tint = CruiseSkyBlue, borderTint = CruiseSkyBlue,
                            ts = if (t.isCruiseActive) TS.RIPPLE else TS.NONE, slideOffCancel = slideOff,
                            onSwipeUp = { pulse(28) }, onTap = { pulse(9) }
                        )
                        DTile("Faster", "plus", Modifier.weight(1f).fillMaxHeight(), tint = CruiseSkyBlue, slideOffCancel = slideOff, onTap = { pulse(26) })
                    }
                }
                val brakePedal: @Composable (Modifier) -> Unit = { m ->
                    DPedal("BRAKE", "brake", BrakePedalRed, brake, m, hot = brakeTempC > 350f, labelSize = 14) { onBrakeChange(it) }
                }
                val gasPedal: @Composable (Modifier) -> Unit = { m ->
                    DPedal("GAS", "gas", GasPedalGreen, if (brakeWins) 0f else throttle, m, dimmed = brakeWins) {
                        onThrottleChange(if (brakeWins) 0f else it)
                    }
                }

                Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (mode) {
                        SteerMode.GYRO -> {
                            val leftCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.width(200.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    turnLeft(Modifier.fillMaxWidth().height(60.dp))
                                    horn(Modifier.fillMaxWidth().height(rowH))
                                    Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        lights(Modifier.weight(1f).fillMaxHeight())
                                        highBeam(Modifier.weight(1f).fillMaxHeight())
                                    }
                                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (!isAuto) DPedal("CLUTCH", "gear", CruiseSkyBlue, clutch, Modifier.width(48.dp).fillMaxHeight(), labelSize = 8) { onClutchChange(it) }
                                        brakePedal(Modifier.weight(1f).fillMaxHeight())
                                    }
                                }
                            }
                            val centerCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    dash(Modifier.fillMaxWidth().weight(1f), false)
                                    Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        retarder(Modifier.weight(2f).fillMaxHeight())
                                        wipers(Modifier.weight(1f).fillMaxHeight())
                                        DTile("Camera", "cam", Modifier.weight(1f).fillMaxHeight(), slideOffCancel = slideOff,
                                            onSwipeUp = { pulse(34) }, onSwipeDown = { pulse(33) }, onTap = { pulse(13) })
                                    }
                                    guardedRow(Modifier.fillMaxWidth().height(rowH))
                                }
                            }
                            val rightCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.width(200.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    turnRight(Modifier.fillMaxWidth().height(60.dp))
                                    shift(Modifier.fillMaxWidth().height(rowH))
                                    cruiseRow(Modifier.fillMaxWidth().height(rowH))
                                    gasPedal(Modifier.fillMaxWidth().weight(1f))
                                }
                            }
                            if (settings.leftHanded) { rightCol(); centerCol(); leftCol() } else { leftCol(); centerCol(); rightCol() }
                        }
                        SteerMode.PAD -> {
                            val leftCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    turnLeft(Modifier.fillMaxWidth().height(60.dp))
                                    PadWheel(padNorm, accent, Modifier.fillMaxWidth().weight(1f)) { n ->
                                        padNorm = n
                                        onTouchSteerChange(n)
                                        onLookChange(n * 0.25f, 0f)
                                    }
                                }
                            }
                            val centerCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    dash(Modifier.fillMaxWidth().weight(1f), true)
                                    Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        lights(Modifier.weight(1f).fillMaxHeight()); highBeam(Modifier.weight(1f).fillMaxHeight())
                                        wipers(Modifier.weight(1f).fillMaxHeight())
                                        DTile("Horn / Air", "horn", Modifier.weight(1f).fillMaxHeight(), tint = accent, borderTint = accent, onPressChanged = { onButtonStateChange(0, it) })
                                    }
                                    Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        shift(Modifier.weight(3f).fillMaxHeight())
                                        retarder(Modifier.weight(2f).fillMaxHeight())
                                    }
                                    guardedRow(Modifier.fillMaxWidth().height(rowH))
                                }
                            }
                            val rightCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    turnRight(Modifier.fillMaxWidth().height(60.dp))
                                    cruiseRow(Modifier.fillMaxWidth().height(rowH))
                                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        brakePedal(Modifier.weight(1f).fillMaxHeight())
                                        gasPedal(Modifier.weight(1.35f).fillMaxHeight())
                                    }
                                }
                            }
                            if (settings.leftHanded) { rightCol(); centerCol(); leftCol() } else { leftCol(); centerCol(); rightCol() }
                        }
                        SteerMode.SLIDE -> {
                            val main: @Composable RowScope.() -> Unit = {
                                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Column(Modifier.width(190.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            turnLeft(Modifier.fillMaxWidth().height(52.dp))
                                            Row(Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                lights(Modifier.weight(1f).fillMaxHeight()); highBeam(Modifier.weight(1f).fillMaxHeight())
                                            }
                                            Row(Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                DTile("Horn", "horn", Modifier.weight(1f).fillMaxHeight(), tint = accent, borderTint = accent, onPressChanged = { onButtonStateChange(0, it) })
                                                wipers(Modifier.weight(1f).fillMaxHeight())
                                            }
                                            shift(Modifier.fillMaxWidth().height(50.dp))
                                        }
                                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            dash(Modifier.fillMaxWidth().weight(1f), true)
                                            Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                retarder(Modifier.weight(2f).fillMaxHeight())
                                                DTile("Camera", "cam", Modifier.weight(1f).fillMaxHeight(), slideOffCancel = slideOff, onTap = { pulse(13) })
                                            }
                                            guardedRow(Modifier.fillMaxWidth().height(52.dp))
                                        }
                                    }
                                    SlideSteeringBar(
                                        accentColor = accent,
                                        onSteerChange = { s -> padNorm = s; onTouchSteerChange(s) },
                                        modifier = Modifier.fillMaxWidth().height(74.dp)
                                    )
                                }
                            }
                            val rightCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    turnRight(Modifier.fillMaxWidth().height(60.dp))
                                    cruiseRow(Modifier.fillMaxWidth().height(rowH))
                                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        brakePedal(Modifier.weight(1f).fillMaxHeight())
                                        gasPedal(Modifier.weight(1.35f).fillMaxHeight())
                                    }
                                }
                            }
                            if (settings.leftHanded) { rightCol(); main() } else { main(); rightCol() }
                        }
                    }
                }
            }

            DAlertOverlays(
                left = t.isLeftBlinker || t.isHazard, right = t.isRightBlinker || t.isHazard,
                redline = isRedline, cruise = t.isCruiseActive,
                emergency = t.isLowAir && t.airPressureBar < 2f,
                brake = brake
            )

            // Quick dock handle: tap it or swipe up from it
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).width(200.dp).height(22.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val d = awaitFirstDown(requireUnconsumed = false)
                            var dyTotal = 0f
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == d.id } ?: break
                                dyTotal = ch.position.y - d.position.y
                                if (!ch.pressed) break
                                ch.consume()
                            }
                            if (dyTotal < -24.dp.toPx() || abs(dyTotal) < 8.dp.toPx()) {
                                buzz(18L, 120)
                                showDock = true
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DIcon("up", TextMuted.copy(alpha = 0.35f + 0.65f * blink.value), Modifier.size(10.dp), 2.4f)
                    Box(Modifier.size(width = 56.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(CockpitBorderStrong))
                }
            }

            if (showMore) DMorePanel(onTrigger = { pulse(it) }, onDismiss = { showMore = false })
            if (showDock) QuickDockOverlay(accent, t, onTrigger = { pulse(it) }, onDismiss = { showDock = false })
            if (showData) DataOverlay(t, brakeTempC, settings.speedUnitMph, accent, onDismiss = { showData = false })
            if (showTuning) DTuningScreen(
                settings, onSave = onSaveSettings, onDismiss = { showTuning = false },
                isConnected = isConnected, connectedIp = connectedIp, packetsPerSec = packetsPerSec,
                savedManualIp = savedManualIp, onSetManualIp = onSetManualIp
            )
            if (showConn) DConnectDialog(isConnected, connectedIp, packetsPerSec, savedManualIp, onSetManualIp, accent) { showConn = false }
        }
    }
}

private class TileCtx(val holdMs: Long, val slideOff: Boolean)

/** Relative horizontal drag: where the thumb lands is zero. Springs back to zero on release. */
private fun Modifier.padDrag(scope: CoroutineScope, anim: Animatable<Float, AnimationVector1D>): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val x0 = down.position.x
            val halfW = (size.width / 2f).coerceAtLeast(1f)
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id }
                if (ch == null || !ch.pressed) break
                val n = ((ch.position.x - x0) / halfW).coerceIn(-1f, 1f)
                scope.launch { anim.snapTo(n) }
                ch.consume()
            }
            scope.launch { anim.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium)) }
        }
    }

@Composable
private fun DModeSwitch(mode: SteerMode, accent: Color, onChange: (SteerMode) -> Unit) {
    Row(
        modifier = Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(CockpitPanel)
            .border(1.dp, CockpitBorder, RoundedCornerShape(10.dp))
    ) {
        listOf(Triple(SteerMode.GYRO, "tilt", "GYRO"), Triple(SteerMode.PAD, "wheel", "PAD"), Triple(SteerMode.SLIDE, "slide", "SLIDE")).forEach { (m, ic, label) ->
            val on = mode == m
            Row(
                modifier = Modifier.fillMaxHeight().background(if (on) accent else Color.Transparent)
                    .clickable { onChange(m) }.padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DIcon(ic, if (on) DarkBackground else TextBright, Modifier.size(16.dp), 2f)
                Text(label, color = if (on) DarkBackground else TextBright, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun RetarderStepper(level: Int, modifier: Modifier, onDown: () -> Unit, onUp: () -> Unit) {
    DCard(modifier) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(44.dp).fillMaxHeight().background(CockpitDeep).clickable { onDown() }, Alignment.Center) { DIcon("down", ScaniaAmber, Modifier.size(20.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DPips(level)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DIcon("pulse", TextMuted, Modifier.size(12.dp), 2f)
                    Text("RETARDER", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
                }
            }
            Box(Modifier.width(44.dp).fillMaxHeight().background(CockpitDeep).clickable { onUp() }, Alignment.Center) { DIcon("up", ScaniaAmber, Modifier.size(20.dp)) }
        }
    }
}

/** Wipers: only the arm sweeps when active. Tap cycles, swipe down turns off. */
@Composable
private fun WipersTile(modifier: Modifier, active: Boolean, ctx: TileCtx, onTap: () -> Unit, onOff: () -> Unit) {
    val tr = rememberInfiniteTransition(label = "wiper")
    val sweep by tr.animateFloat(
        -50f, 50f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wiperArm"
    )
    DTile("Wipers", null, modifier, slideOffCancel = ctx.slideOff, onSwipeDown = onOff, onTap = onTap, iconSlot = {
        Canvas(Modifier.size(22.dp)) {
            val st = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round)
            drawArc(CockpitBorderStrong, 180f, 180f, false, topLeft = Offset(size.width * 0.05f, size.height * 0.15f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.9f, size.height * 1.6f), style = st)
            rotate(if (active) sweep else -50f, pivot = Offset(size.width / 2f, size.height * 0.95f)) {
                drawLine(TextBright, Offset(size.width / 2f, size.height * 0.95f), Offset(size.width / 2f, size.height * 0.25f), 1.9.dp.toPx(), StrokeCap.Round)
            }
        }
    })
}

/** Circular thumb steering pad: slide anywhere, ticks every 15 degrees, springs back to zero. */
@Composable
private fun PadWheel(norm: Float, accent: Color, modifier: Modifier, onSteer: (Float) -> Unit) {
    val anim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val cur by rememberUpdatedState(onSteer)
    LaunchedEffect(Unit) { snapshotFlow { anim.value }.collect { cur(slideCurve(it)) } }
    DCard(
        modifier = modifier.padDrag(scope, anim),
        borderColor = CockpitBorderStrong
    ) {
        val steer = slideCurve(anim.value)
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val r = size.minDimension / 2f * 0.9f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(CockpitDeep, r + 4.dp.toPx(), c)
            drawCircle(CockpitBorder, r + 4.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            for (i in 0 until 24) {
                val a = Math.toRadians(i * 15.0 - 90.0)
                val r0 = r - if (i % 3 == 0) 12.dp.toPx() else 8.dp.toPx()
                drawLine(
                    CockpitBorderStrong,
                    Offset(c.x + (r0 * Math.cos(a)).toFloat(), c.y + (r0 * Math.sin(a)).toFloat()),
                    Offset(c.x + ((r + 3.dp.toPx()) * Math.cos(a)).toFloat(), c.y + ((r + 3.dp.toPx()) * Math.sin(a)).toFloat()),
                    2.dp.toPx(), StrokeCap.Round
                )
            }
            val rr = r * 0.78f
            drawCircle(CockpitBorder, rr, c, style = Stroke(10.dp.toPx()))
            drawArc(accent, -90f, steer * 180f, false, Offset(c.x - rr, c.y - rr),
                androidx.compose.ui.geometry.Size(rr * 2f, rr * 2f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
            val ang = Math.toRadians(-90.0 + steer * 180.0)
            val dot = Offset(c.x + (rr * Math.cos(ang)).toFloat(), c.y + (rr * Math.sin(ang)).toFloat())
            drawCircle(accent.copy(alpha = 0.25f), 17.dp.toPx(), dot)
            drawCircle(accent, 8.dp.toPx(), dot)
            rotate(steer * 180f, pivot = c) {
                drawCircle(TextMuted, r * 0.44f, c, style = Stroke(5.dp.toPx()))
                drawCircle(accent, 4.dp.toPx(), Offset(c.x, c.y - r * 0.44f))
            }
        }
        Text("%+d\u00B0".format((steer * 450f).roundToInt()), color = TextBright, fontSize = 22.sp,
            fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center))
        Text("SLIDE ANYWHERE \u00B7 SPRINGS TO 0", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
    }
}

@Composable
private fun Dash(
    modifier: Modifier, compact: Boolean, banner: Banner?, limit: Int, speed: Int, speedUnit: String, gear: String,
    rpmRatio: Float, overspeed: Boolean, rpmText: String, shake: Boolean, speedColor: Color, showWheel: Boolean, angle: Float,
    centered: Boolean, drift: Boolean, recenterLabel: String, holdMs: Long, onCenter: () -> Unit,
    air: Float, airWarn: Boolean, fuel: Int, adblue: Int, brakeTemp: Int
) {
    val blink = LocalBlink.current
    val tr = rememberInfiniteTransition(label = "shake")
    val jitter by tr.animateFloat(-2f, 2f, infiniteRepeatable(tween(70), RepeatMode.Reverse), label = "shakeX")
    var gearFlash by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(gear) { gearFlash = 0.8f; delay(220L); gearFlash = 0f }
    val speedShown by animateIntAsState(speed, tween(280), label = "speedAnim")
    val signRot by tr.animateFloat(-8f, 8f, infiniteRepeatable(tween(250), RepeatMode.Reverse), label = "signShake")

    @Composable
    fun readouts() {
        val items = listOf(
            Triple("air", "%.1f".format(air), airWarn), Triple("fuel", "$fuel%", fuel <= 15),
            Triple("drop", "$adblue%", adblue <= 15), Triple("temp", "$brakeTemp\u00B0", brakeTemp > 350)
        )
        items.forEach { (ic, v, warn) ->
            val col = if (warn) ScaniaAmber.copy(alpha = blink.value) else TextMuted
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DIcon(ic, col, Modifier.size(if (compact) 11.dp else 13.dp), 2f)
                Text(v, color = col, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    DCard(modifier) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 8.dp),
            verticalArrangement = if (compact) Arrangement.SpaceEvenly else Arrangement.SpaceBetween
        ) {
            if (!compact || banner != null || limit > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (banner != null) DChip(banner.text, banner.icon, banner.color, banner.pulse) else Spacer(Modifier.height(24.dp))
                if (limit > 0) Box(
                    Modifier.size(30.dp).graphicsLayer { rotationZ = if (overspeed) signRot else 0f }.clip(CircleShape).border(3.dp, BrakePedalRed, CircleShape), Alignment.Center
                ) { Text("$limit", color = TextBright, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (showWheel) DWheelCenter(angle, centered, drift, recenterLabel, holdMs, onCenter, Modifier.size(84.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$speedShown", color = speedColor, fontSize = if (compact) 38.sp else 56.sp, fontWeight = FontWeight.Black,
                        lineHeight = if (compact) 38.sp else 56.sp, modifier = Modifier.offset(x = if (shake) jitter.dp else 0.dp)
                    )
                    Text(speedUnit.uppercase(), color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
                }
                Box(
                    Modifier.size(if (compact) 36.dp else 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(ScaniaAmber.copy(alpha = gearFlash))
                        .border(2.dp, ScaniaAmber, RoundedCornerShape(12.dp)),
                    Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = gear,
                        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                        label = "gearRoll"
                    ) { g ->
                        Text(g, color = if (gearFlash > 0.4f) DarkBackground else ScaniaAmber, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Black)
                    }
                }
                if (compact) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { readouts() }
            }
            if (compact) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("RPM", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
                    DRpmBar(rpmRatio, Modifier.weight(1f))
                    Text(rpmText, color = speedColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("RPM", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
                    Text(rpmText, color = speedColor, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
                DRpmBar(rpmRatio, Modifier.fillMaxWidth())
            }
            if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { readouts() }
        }
    }
}
