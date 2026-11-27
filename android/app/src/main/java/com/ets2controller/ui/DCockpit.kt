package com.ets2controller.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.config.ControllerSettings
import com.ets2controller.network.RadioStation
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
    sensorModeLabel: String = "GYRO",
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
    onTouchSteerChange: (Float?) -> Unit,
    radioStations: List<RadioStation> = emptyList(),
    streamPlaying: Boolean = false,
    streamBuffering: Boolean = false,
    streamStation: RadioStation? = null,
    streamError: String? = null,
    streamVolume: Float = 0.85f,
    usePhoneStream: Boolean = false,
    onToggleRadioMode: (Boolean) -> Unit = {},
    onPlayStreamStation: (RadioStation) -> Unit = {},
    onToggleStreamPlayPause: () -> Unit = {},
    onStreamVolumeChange: (Float) -> Unit = {},
    onRequestRadioSync: () -> Unit = {},
    onUiCommand: (Byte) -> Unit = {}
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
    var showRadio by remember { mutableStateOf(false) }
    var showUiNav by remember { mutableStateOf(false) }
    var localRetarder by remember { mutableIntStateOf(0) }
    var radioOn by remember { mutableStateOf(false) }
    var ets2StationIdx by remember { mutableIntStateOf(0) }
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
    val layoutDir = LocalLayoutDirection.current
    val cutoutPad = WindowInsets.displayCutout.asPaddingValues()
    val safePad = WindowInsets.safeDrawing.asPaddingValues()
    val userEdge = maxOf(12, settings.edgeGuardDp).dp
    val leftEdge = maxOf(cutoutPad.calculateLeftPadding(layoutDir), safePad.calculateLeftPadding(layoutDir), userEdge)
    val rightEdge = maxOf(cutoutPad.calculateRightPadding(layoutDir), safePad.calculateRightPadding(layoutDir), userEdge)
    val topPad = maxOf(8.dp, cutoutPad.calculateTopPadding())
    val navBottom = safePad.calculateBottomPadding()
    val dockHandleH = 34.dp
    val bottomPad = maxOf(dockHandleH + 4.dp, navBottom + dockHandleH)

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

    BackHandler(enabled = showMore || showDock || showData || showTuning || showConn || showRadio || showUiNav) {
        when {
            showConn -> showConn = false
            showTuning -> showTuning = false
            showRadio -> showRadio = false
            showUiNav -> showUiNav = false
            showData -> showData = false
            showMore -> showMore = false
            showDock -> showDock = false
        }
    }

    CompositionLocalProvider(
        LocalBlink provides blink,
        LocalBuzz provides buzz,
        LocalAccent provides accent,
        LocalReduceMotion provides settings.reduceMotion
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
            val availW = (maxWidth - leftEdge - rightEdge).coerceAtLeast(320.dp)
            val availH = (maxHeight - topPad - bottomPad).coerceAtLeast(240.dp)
            val isNarrow = availW < 680.dp
            val isCompactH = availH < 340.dp
            val topBarH = if (isCompactH) 38.dp else 44.dp
            val colGap = if (isNarrow) 8.dp else 10.dp
            val bodyH = (availH - topBarH - 8.dp).coerceAtLeast(200.dp)
            val sideW = (availW * 0.23f).coerceIn(154.dp, 224.dp)
            val rowH = (bodyH * 0.175f * settings.controlScale).coerceIn(42.dp, 62.dp)
            val turnH = (bodyH * 0.18f).coerceIn(44.dp, 60.dp)
            val slideH = (bodyH * 0.21f).coerceIn(54.dp, 74.dp)

            Column(
                modifier = Modifier.fillMaxSize().padding(start = leftEdge, end = rightEdge, top = topPad, bottom = bottomPad),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ---------------- TOP BAR ----------------
                Row(
                    modifier = Modifier.fillMaxWidth().height(topBarH),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DCard(
                        modifier = Modifier
                            .weight(0.95f)
                            .widthIn(min = 108.dp, max = 188.dp)
                            .fillMaxHeight(),
                        borderColor = if (isConnected) ConnectedGreen.copy(alpha = 0.5f) else CockpitBorder
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics { contentDescription = if (isConnected) "PC linked, tap for connection" else "No PC, tap to connect" }
                                .clickable { showConn = true }
                                .padding(horizontal = 10.dp),
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
                                if (isConnected) "${t.roundTripPingMs}ms \u00B7 $sensorModeLabel" else "NO PC \u00B7 $sensorModeLabel",
                                color = if (isConnected) TextBright else TextMuted,
                                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1
                            )
                        }
                    }
                    val stepRadio: (Int) -> Unit = { delta ->
                        if (usePhoneStream) {
                            if (radioStations.isNotEmpty()) {
                                val curPos = radioStations.indexOfFirst { it.index == streamStation?.index }.let { if (it < 0) 0 else it }
                                val nextPos = (curPos + delta).mod(radioStations.size)
                                onPlayStreamStation(radioStations[nextPos])
                            }
                        } else {
                            if (radioStations.isNotEmpty()) {
                                ets2StationIdx = (ets2StationIdx + delta).mod(radioStations.size)
                            }
                            pulse(if (delta > 0) 15 else 16)
                        }
                    }
                    val toggleRadioPlay: () -> Unit = {
                        if (usePhoneStream) {
                            onToggleStreamPlayPause()
                        } else {
                            radioOn = !radioOn
                            pulse(60) // radiotoggle: direct Play/Pause while driving without pausing ETS2
                        }
                    }
                    val activeRadioPlaying = if (usePhoneStream) streamPlaying else radioOn
                    val activeStationName = when {
                        usePhoneStream -> streamStation?.name ?: radioStations.firstOrNull()?.name ?: "PHONE STREAM"
                        radioOn -> radioStations.getOrNull(ets2StationIdx)?.name ?: "ETS2 RADIO ON"
                        radioStations.isNotEmpty() -> radioStations.getOrNull(ets2StationIdx)?.name ?: "CAB RADIO"
                        else -> if (isNarrow) "RADIO" else "TAP FOR RADIO"
                    }
                    DCard(modifier = Modifier.weight(1.45f).fillMaxHeight()) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.width(36.dp).fillMaxHeight()
                                    .semantics { contentDescription = "Previous radio station" }
                                    .clickable { stepRadio(-1) },
                                Alignment.Center
                            ) { DIcon("prev", TextBright, Modifier.size(15.dp)) }
                            Row(
                                Modifier.weight(1f).fillMaxHeight()
                                    .semantics { contentDescription = "Toggle cabin radio" }
                                    .clickable { toggleRadioPlay() }
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DEq(activeRadioPlaying)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    activeStationName.uppercase(),
                                    color = if (activeRadioPlaying) TextBright else TextMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.4.sp,
                                    maxLines = 1
                                )
                            }
                            Box(
                                Modifier.width(34.dp).fillMaxHeight()
                                    .semantics { contentDescription = "Open radio browser" }
                                    .clickable { showRadio = true },
                                Alignment.Center
                            ) { DIcon("list", accent, Modifier.size(15.dp)) }
                            Box(
                                Modifier.width(36.dp).fillMaxHeight()
                                    .semantics { contentDescription = "Next radio station" }
                                    .clickable { stepRadio(1) },
                                Alignment.Center
                            ) { DIcon("next", TextBright, Modifier.size(15.dp)) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        DModeSwitch(mode, accent, topBarH, isNarrow) { setMode(it) }
                        DTile("", "dpad", Modifier.size(42.dp, topBarH), tint = CruiseSkyBlue, iconSize = 17.dp, onTap = { showUiNav = true })
                        DTile("", "data", Modifier.size(42.dp, topBarH), iconSize = 17.dp, onTap = { showData = true })
                        DTile("", "more", Modifier.size(42.dp, topBarH), tint = accent, iconSize = 18.dp, onTap = { showMore = true })
                        DTile("", "gear", Modifier.size(42.dp, topBarH), guarded = true, holdMs = holdMs, iconSize = 17.dp, onTap = { showTuning = true })
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
                        fuel = t.fuelPercent, adblue = t.adbluePercent, brakeTemp = brakeTempC.roundToInt(),
                        routeDistKm = t.routeDistanceKm, routeTimeMin = t.routeTimeMin, speedUnitMph = settings.speedUnitMph,
                        onPeekChange = { lx -> onLookChange(lx, 0f) }
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
                    DPedal("BRAKE", "brake", BrakePedalRed, brake, m, hot = brakeTempC > 350f, relativeDrag = settings.relativePedalDrag, labelSize = 14) { onBrakeChange(it) }
                }
                val gasPedal: @Composable (Modifier) -> Unit = { m ->
                    DPedal("GAS", "gas", GasPedalGreen, if (brakeWins) 0f else throttle, m, dimmed = brakeWins, relativeDrag = settings.relativePedalDrag) {
                        onThrottleChange(if (brakeWins) 0f else it)
                    }
                }
                val clutchPedal: @Composable (Modifier) -> Unit = { m ->
                    DPedal("CLUTCH", "clutch", CruiseSkyBlue, clutch, m, relativeDrag = settings.relativePedalDrag, labelSize = 8) { onClutchChange(it) }
                }
                val lightsPair: @Composable (Modifier) -> Unit = { m ->
                    Row(m, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        lights(Modifier.weight(1f).fillMaxHeight())
                        highBeam(Modifier.weight(1f).fillMaxHeight())
                    }
                }

                Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(colGap)) {
                    when (mode) {
                        SteerMode.GYRO -> {
                            val leftCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.width(sideW).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (settings.ergonomicLayout) {
                                        lightsPair(Modifier.fillMaxWidth().height(rowH))
                                        turnLeft(Modifier.fillMaxWidth().height(turnH))
                                        horn(Modifier.fillMaxWidth().height(rowH))
                                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            brakePedal(Modifier.weight(1f).fillMaxHeight())
                                            if (!isAuto) clutchPedal(Modifier.width(50.dp).fillMaxHeight())
                                        }
                                    } else {
                                        turnLeft(Modifier.fillMaxWidth().height(turnH))
                                        horn(Modifier.fillMaxWidth().height(rowH))
                                        lightsPair(Modifier.fillMaxWidth().height(rowH))
                                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (!isAuto) clutchPedal(Modifier.width(50.dp).fillMaxHeight())
                                            brakePedal(Modifier.weight(1f).fillMaxHeight())
                                        }
                                    }
                                }
                            }
                            val centerCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    dash(Modifier.fillMaxWidth().weight(1f), false)
                                    Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        retarder(Modifier.weight(2f).fillMaxHeight())
                                        wipers(Modifier.weight(1f).fillMaxHeight())
                                        DTile("Camera", "cam", Modifier.weight(1f).fillMaxHeight(), slideOffCancel = slideOff,
                                            onSwipeUp = { pulse(34) }, onSwipeDown = { pulse(33) }, onTap = { pulse(13) })
                                    }
                                    guardedRow(Modifier.fillMaxWidth().height(rowH))
                                }
                            }
                            val rightCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.width(sideW).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (settings.ergonomicLayout) {
                                        cruiseRow(Modifier.fillMaxWidth().height(rowH))
                                        turnRight(Modifier.fillMaxWidth().height(turnH))
                                        shift(Modifier.fillMaxWidth().height(rowH))
                                    } else {
                                        turnRight(Modifier.fillMaxWidth().height(turnH))
                                        shift(Modifier.fillMaxWidth().height(rowH))
                                        cruiseRow(Modifier.fillMaxWidth().height(rowH))
                                    }
                                    gasPedal(Modifier.fillMaxWidth().weight(1f))
                                }
                            }
                            if (settings.leftHanded) { rightCol(); centerCol(); leftCol() } else { leftCol(); centerCol(); rightCol() }
                        }
                        SteerMode.PAD -> {
                            val leftCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.width(sideW).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    turnLeft(Modifier.fillMaxWidth().height(turnH))
                                    PadWheel(padNorm, accent, Modifier.fillMaxWidth().weight(1f)) { n ->
                                        padNorm = n
                                        onTouchSteerChange(n)
                                        onLookChange(n * 0.25f, 0f)
                                    }
                                }
                            }
                            val centerCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    dash(Modifier.fillMaxWidth().weight(1f), true)
                                    Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        lights(Modifier.weight(1f).fillMaxHeight())
                                        highBeam(Modifier.weight(1f).fillMaxHeight())
                                        retarder(Modifier.weight(2f).fillMaxHeight())
                                    }
                                    Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        shift(Modifier.weight(3f).fillMaxHeight())
                                        wipers(Modifier.weight(1f).fillMaxHeight())
                                        DTile("Horn", "horn", Modifier.weight(1f).fillMaxHeight(), tint = accent, borderTint = accent, onPressChanged = { onButtonStateChange(0, it) })
                                    }
                                    guardedRow(Modifier.fillMaxWidth().height(rowH))
                                }
                            }
                            val rightCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.width(sideW).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (settings.ergonomicLayout) {
                                        cruiseRow(Modifier.fillMaxWidth().height(rowH))
                                        turnRight(Modifier.fillMaxWidth().height(turnH))
                                    } else {
                                        turnRight(Modifier.fillMaxWidth().height(turnH))
                                        cruiseRow(Modifier.fillMaxWidth().height(rowH))
                                    }
                                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        brakePedal(Modifier.weight(1f).fillMaxHeight())
                                        gasPedal(Modifier.weight(1.3f).fillMaxHeight())
                                    }
                                }
                            }
                            if (settings.leftHanded) { rightCol(); centerCol(); leftCol() } else { leftCol(); centerCol(); rightCol() }
                        }
                        SteerMode.SLIDE -> {
                            val main: @Composable RowScope.() -> Unit = {
                                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Column(Modifier.width((sideW * 0.92f).coerceAtLeast(146.dp)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (settings.ergonomicLayout) {
                                                lightsPair(Modifier.fillMaxWidth().weight(1f))
                                                turnLeft(Modifier.fillMaxWidth().weight(1f))
                                                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    DTile("Horn", "horn", Modifier.weight(1f).fillMaxHeight(), tint = accent, borderTint = accent, onPressChanged = { onButtonStateChange(0, it) })
                                                    wipers(Modifier.weight(1f).fillMaxHeight())
                                                }
                                                shift(Modifier.fillMaxWidth().weight(1f))
                                            } else {
                                                turnLeft(Modifier.fillMaxWidth().weight(1f))
                                                lightsPair(Modifier.fillMaxWidth().weight(1f))
                                                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    DTile("Horn", "horn", Modifier.weight(1f).fillMaxHeight(), tint = accent, borderTint = accent, onPressChanged = { onButtonStateChange(0, it) })
                                                    wipers(Modifier.weight(1f).fillMaxHeight())
                                                }
                                                shift(Modifier.fillMaxWidth().weight(1f))
                                            }
                                        }
                                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            dash(Modifier.fillMaxWidth().weight(1.7f), true)
                                            Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                retarder(Modifier.weight(2f).fillMaxHeight())
                                                DTile("Camera", "cam", Modifier.weight(1f).fillMaxHeight(), slideOffCancel = slideOff, onTap = { pulse(13) })
                                            }
                                            guardedRow(Modifier.fillMaxWidth().height(rowH))
                                        }
                                    }
                                    SlideSteeringBar(
                                        accentColor = accent,
                                        onSteerChange = { s -> padNorm = s; onTouchSteerChange(s) },
                                        modifier = Modifier.fillMaxWidth().height(slideH)
                                    )
                                }
                            }
                            val rightCol: @Composable RowScope.() -> Unit = {
                                Column(Modifier.width(sideW).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (settings.ergonomicLayout) {
                                        cruiseRow(Modifier.fillMaxWidth().height(rowH))
                                        turnRight(Modifier.fillMaxWidth().height(turnH))
                                    } else {
                                        turnRight(Modifier.fillMaxWidth().height(turnH))
                                        cruiseRow(Modifier.fillMaxWidth().height(rowH))
                                    }
                                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        brakePedal(Modifier.weight(1f).fillMaxHeight())
                                        gasPedal(Modifier.weight(1.3f).fillMaxHeight())
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

            // Quick dock handle: sits completely inside bottomPad below guardedRow (zero overlap with Engine tile)
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).width(220.dp).height(dockHandleH)
                    .padding(bottom = navBottom.coerceAtLeast(2.dp))
                    .semantics { contentDescription = "Open quick dock" }
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
                            if (dyTotal < -20.dp.toPx() || abs(dyTotal) < 10.dp.toPx()) {
                                buzz(18L, 120)
                                showDock = true
                            }
                        }
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier.padding(bottom = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DIcon("up", TextMuted.copy(alpha = 0.55f + 0.45f * blink.value), Modifier.size(10.dp), 2.4f)
                    Box(Modifier.size(width = 56.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(CockpitBorderStrong))
                }
            }

            val overlayEnter = if (settings.reduceMotion) EnterTransition.None else fadeIn(tween(200, easing = FastOutSlowInEasing)) + scaleIn(tween(200, easing = FastOutSlowInEasing), initialScale = 0.96f)
            val overlayExit = if (settings.reduceMotion) ExitTransition.None else fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.97f)

            AnimatedVisibility(visible = showMore, enter = overlayEnter, exit = overlayExit) {
                DMorePanel(
                    holdMs = holdMs, slideOffCancel = slideOff,
                    quickDockBits = settings.quickDockBits,
                    onUpdateQuickDock = { onSaveSettings(settings.copy(quickDockBits = it)) },
                    onTrigger = { pulse(it) }, onHoldChange = onButtonStateChange, onDismiss = { showMore = false }
                )
            }
            AnimatedVisibility(
                visible = showDock,
                enter = if (settings.reduceMotion) EnterTransition.None else fadeIn(tween(180)),
                exit = if (settings.reduceMotion) ExitTransition.None else fadeOut(tween(150))
            ) {
                QuickDockOverlay(
                    accent, t, holdMs = holdMs, slideOffCancel = slideOff,
                    quickDockBits = settings.quickDockBits,
                    onUpdateQuickDock = { onSaveSettings(settings.copy(quickDockBits = it)) },
                    sheetModifier = Modifier.animateEnterExit(
                        enter = if (settings.reduceMotion) EnterTransition.None else slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it },
                        exit = if (settings.reduceMotion) ExitTransition.None else slideOutVertically(tween(200)) { it }
                    ),
                    onTrigger = { pulse(it) }, onHoldChange = onButtonStateChange, onDismiss = { showDock = false }
                )
            }
            AnimatedVisibility(visible = showData, enter = overlayEnter, exit = overlayExit) {
                DataOverlay(t, brakeTempC, settings.speedUnitMph, accent, onDismiss = { showData = false })
            }
            AnimatedVisibility(visible = showTuning, enter = overlayEnter, exit = overlayExit) {
                DTuningScreen(
                    settings, onSave = onSaveSettings, onDismiss = { showTuning = false },
                    isConnected = isConnected, connectedIp = connectedIp, packetsPerSec = packetsPerSec,
                    savedManualIp = savedManualIp, onSetManualIp = onSetManualIp
                )
            }
            AnimatedVisibility(visible = showConn, enter = overlayEnter, exit = overlayExit) {
                DConnectDialog(isConnected, connectedIp, packetsPerSec, savedManualIp, onSetManualIp, accent) { showConn = false }
            }
            AnimatedVisibility(visible = showRadio, enter = overlayEnter, exit = overlayExit) {
                DRadioOverlay(
                    stations = radioStations,
                    accent = accent,
                    usePhoneStream = usePhoneStream,
                    ets2Playing = radioOn,
                    ets2Index = ets2StationIdx,
                    streamPlaying = streamPlaying,
                    streamBuffering = streamBuffering,
                    streamStation = streamStation,
                    streamError = streamError,
                    streamVolume = streamVolume,
                    onToggleMode = { phoneMode ->
                        onToggleRadioMode(phoneMode)
                    },
                    onSelectStation = { st ->
                        if (usePhoneStream) {
                            onPlayStreamStation(st)
                        } else {
                            ets2StationIdx = st.index
                        }
                    },
                    onPrev = {
                        if (usePhoneStream && radioStations.isNotEmpty()) {
                            val curPos = radioStations.indexOfFirst { it.index == streamStation?.index }.let { if (it < 0) 0 else it }
                            onPlayStreamStation(radioStations[(curPos - 1).mod(radioStations.size)])
                        } else {
                            if (radioStations.isNotEmpty()) ets2StationIdx = (ets2StationIdx - 1).mod(radioStations.size)
                            pulse(16)
                        }
                    },
                    onPlayPause = {
                        if (usePhoneStream) onToggleStreamPlayPause()
                        else { radioOn = !radioOn; pulse(60) }
                    },
                    onNext = {
                        if (usePhoneStream && radioStations.isNotEmpty()) {
                            val curPos = radioStations.indexOfFirst { it.index == streamStation?.index }.let { if (it < 0) 0 else it }
                            onPlayStreamStation(radioStations[(curPos + 1).mod(radioStations.size)])
                        } else {
                            if (radioStations.isNotEmpty()) ets2StationIdx = (ets2StationIdx + 1).mod(radioStations.size)
                            pulse(15)
                        }
                    },
                    onEts2VolDown = { pulse(62) },
                    onEts2VolUp = { pulse(61) },
                    onEts2Fav = { pulse(63) },
                    onOpenEts2RadioScreen = { pulse(14) },
                    onStreamVolumeChange = onStreamVolumeChange,
                    onRequestSync = onRequestRadioSync,
                    onDismiss = { showRadio = false }
                )
            }
            AnimatedVisibility(visible = showUiNav, enter = overlayEnter, exit = overlayExit) {
                DUiNavOverlay(accent = accent, onUiCommand = onUiCommand, onDismiss = { showUiNav = false })
            }
        }
    }
}

private class TileCtx(val holdMs: Long, val slideOff: Boolean)

/** Relative horizontal drag: where the thumb lands is zero, reporting anchor fraction (-1..1). */
private fun Modifier.padDrag(
    scope: CoroutineScope,
    anim: Animatable<Float, AnimationVector1D>,
    onAnchor: (Offset?) -> Unit
): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val x0 = down.position.x
            onAnchor(down.position)
            val halfW = (size.width / 2f).coerceAtLeast(1f)
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id }
                if (ch == null || !ch.pressed) break
                val n = ((ch.position.x - x0) / halfW).coerceIn(-1f, 1f)
                scope.launch { anim.snapTo(n) }
                ch.consume()
            }
            onAnchor(null)
            scope.launch { anim.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium)) }
        }
    }

@Composable
private fun DModeSwitch(mode: SteerMode, accent: Color, barHeight: Dp, compact: Boolean, onChange: (SteerMode) -> Unit) {
    Row(
        modifier = Modifier.height(barHeight).clip(RoundedCornerShape(10.dp)).background(CockpitPanel)
            .border(1.dp, CockpitBorder, RoundedCornerShape(10.dp))
    ) {
        listOf(Triple(SteerMode.GYRO, "tilt", "GYRO"), Triple(SteerMode.PAD, "wheel", "PAD"), Triple(SteerMode.SLIDE, "slide", "SLIDE")).forEach { (m, ic, label) ->
            val on = mode == m
            Row(
                modifier = Modifier.fillMaxHeight().background(if (on) accent else Color.Transparent)
                    .clickable { onChange(m) }.padding(horizontal = if (compact) 7.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DIcon(ic, if (on) DarkBackground else TextBright, Modifier.size(15.dp), 2f)
                if (!compact || on) {
                    Text(label, color = if (on) DarkBackground else TextBright, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun RetarderStepper(level: Int, modifier: Modifier, onDown: () -> Unit, onUp: () -> Unit) {
    val accent = LocalAccent.current
    DCard(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val btnW = (maxWidth * 0.24f).coerceIn(34.dp, 48.dp)
            val narrow = maxWidth < 170.dp
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(btnW).fillMaxHeight().background(CockpitDeep)
                        .semantics { contentDescription = "Decrease retarder" }.clickable { onDown() },
                    Alignment.Center
                ) { DIcon("down", accent, Modifier.size(18.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    DPips(level, pipW = if (narrow) 7.dp else 10.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (!narrow) DIcon("pulse", TextMuted, Modifier.size(11.dp), 2f)
                        Text(
                            if (narrow) "RET" else "RETARDER",
                            color = TextMuted, fontSize = if (narrow) 8.5.sp else 9.sp,
                            fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp, maxLines = 1
                        )
                    }
                }
                Box(
                    Modifier.width(btnW).fillMaxHeight().background(CockpitDeep)
                        .semantics { contentDescription = "Increase retarder" }.clickable { onUp() },
                    Alignment.Center
                ) { DIcon("up", accent, Modifier.size(18.dp)) }
            }
        }
    }
}

/** Wipers: only the arm sweeps when active. Tap cycles, swipe down turns off. */
@Composable
private fun WipersTile(modifier: Modifier, active: Boolean, ctx: TileCtx, onTap: () -> Unit, onOff: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val sweep = if (active && !reduceMotion) {
        val tr = rememberInfiniteTransition(label = "wiper")
        tr.animateFloat(
            -50f, 50f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wiperArm"
        ).value
    } else if (active) 20f else -50f
    DTile("Wipers", null, modifier, slideOffCancel = ctx.slideOff, onSwipeDown = onOff, onTap = onTap, iconSlot = {
        Canvas(Modifier.size(22.dp)) {
            val st = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round)
            drawArc(CockpitBorderStrong, 180f, 180f, false, topLeft = Offset(size.width * 0.05f, size.height * 0.15f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.9f, size.height * 1.6f), style = st)
            rotate(sweep, pivot = Offset(size.width / 2f, size.height * 0.95f)) {
                drawLine(TextBright, Offset(size.width / 2f, size.height * 0.95f), Offset(size.width / 2f, size.height * 0.25f), 1.9.dp.toPx(), StrokeCap.Round)
            }
        }
    })
}

/** Circular thumb steering pad: slide anywhere, shows touch anchor ring, springs back to zero. */
@Composable
private fun PadWheel(norm: Float, accent: Color, modifier: Modifier, onSteer: (Float) -> Unit) {
    val anim = remember { Animatable(0f) }
    var anchor by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()
    val cur by rememberUpdatedState(onSteer)
    LaunchedEffect(Unit) { snapshotFlow { anim.value }.collect { cur(slideCurve(it)) } }
    DCard(
        modifier = modifier.padDrag(scope, anim) { anchor = it },
        borderColor = if (anchor != null) accent else CockpitBorderStrong
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
            anchor?.let { aPos ->
                val padPx = 10.dp.toPx()
                val localA = Offset(
                    (aPos.x - padPx).coerceIn(0f, size.width),
                    (aPos.y - padPx).coerceIn(0f, size.height)
                )
                drawCircle(TextBright.copy(alpha = 0.22f), 12.dp.toPx(), localA, style = Stroke(1.5.dp.toPx()))
                drawCircle(TextBright.copy(alpha = 0.55f), 3.dp.toPx(), localA)
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
    air: Float, airWarn: Boolean, fuel: Int, adblue: Int, brakeTemp: Int,
    routeDistKm: Int = 0, routeTimeMin: Int = 0, speedUnitMph: Boolean = false,
    onPeekChange: (Float) -> Unit = {}
) {
    val blink = LocalBlink.current
    val accent = LocalAccent.current
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val curPeek by rememberUpdatedState(onPeekChange)
    val peekAnim = remember { Animatable(0f) }

    var gearFlash by remember { mutableFloatStateOf(0f) }
    var gearTrigger by remember { mutableStateOf(false) }
    LaunchedEffect(gear) {
        gearFlash = 0.8f
        gearTrigger = true
        delay(160L)
        gearTrigger = false
        delay(80L)
        gearFlash = 0f
    }
    val gearPop by animateFloatAsState(
        targetValue = if (gearTrigger && !reduceMotion) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "gearPop"
    )
    val speedShown by animateIntAsState(speed, tween(280), label = "speedAnim")
    val signRot = if (overspeed && !reduceMotion) {
        val tr = rememberInfiniteTransition(label = "overspeedSign")
        tr.animateFloat(-6f, 6f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "signShake").value
    } else 0f

    val routeDist = if (speedUnitMph) (routeDistKm * 0.621371f).roundToInt() else routeDistKm
    val routeUnit = if (speedUnitMph) "mi" else "km"
    val etaStr = if (routeTimeMin > 0) "%d:%02d".format(routeTimeMin / 60, routeTimeMin % 60) else ""

    @Composable
    fun readoutItem(ic: String, v: String, warn: Boolean) {
        val col = if (warn) accent.copy(alpha = blink.value) else TextMuted
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DIcon(ic, col, Modifier.size(if (compact) 12.dp else 13.dp), 2f)
            Text(v, color = col, fontSize = if (compact) 10.sp else 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }

    DCard(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val x0 = down.position.x
                val halfW = 110.dp.toPx().coerceAtLeast(1f)
                var dragging = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed) break
                    val dx = ch.position.x - x0
                    if (abs(dx) > 12.dp.toPx()) dragging = true
                    if (dragging) {
                        val n = (dx / halfW).coerceIn(-1f, 1f)
                        scope.launch { peekAnim.snapTo(n) }
                        curPeek(n)
                        ch.consume()
                    }
                }
                if (dragging) {
                    curPeek(0f)
                    scope.launch { peekAnim.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium)) }
                }
            }
        },
        borderColor = when {
            abs(peekAnim.value) > 0.08f -> VolvoIceBlue
            shake -> BrakePedalRed.copy(alpha = 0.55f + 0.45f * blink.value)
            else -> CockpitBorder
        }
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 7.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (!compact || banner != null || limit > 0 || routeDistKm > 0) Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedContent(
                    targetState = banner,
                    transitionSpec = {
                        if (reduceMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                        }
                    },
                    label = "dashBannerTransition"
                ) { activeBanner ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (activeBanner != null) DChip(activeBanner.text, activeBanner.icon, activeBanner.color, activeBanner.pulse)
                        else if (!compact) DChip("CAB", "eye", if (abs(peekAnim.value) > 0.08f) VolvoIceBlue else TextMuted, false)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (routeDistKm > 0) {
                        Row(
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(CockpitDeep)
                                .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            DIcon("route", accent, Modifier.size(11.dp), 2f)
                            Text(
                                if (etaStr.isNotEmpty()) "$routeDist $routeUnit \u00B7 $etaStr" else "$routeDist $routeUnit",
                                color = TextBright, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1
                            )
                        }
                    } else if (!compact) {
                        Text("SWIPE TO PEEK", color = TextMuted.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
                    }
                    if (limit > 0) Box(
                        Modifier.size(28.dp).graphicsLayer { rotationZ = signRot }.clip(CircleShape).border(3.dp, BrakePedalRed, CircleShape), Alignment.Center
                    ) { Text("$limit", color = TextBright, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (showWheel) DWheelCenter(angle, centered, drift, recenterLabel, holdMs, onCenter, Modifier.size(76.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$speedShown", color = speedColor, fontSize = if (compact) 34.sp else 50.sp, fontWeight = FontWeight.Black,
                        lineHeight = if (compact) 34.sp else 50.sp
                    )
                    Text(speedUnit.uppercase(), color = TextMuted, fontSize = 9.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
                }
                Box(
                    Modifier.size(if (compact) 36.dp else 46.dp)
                        .graphicsLayer { scaleX = gearPop; scaleY = gearPop }
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = gearFlash))
                        .border(2.dp, accent, RoundedCornerShape(12.dp)),
                    Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = gear,
                        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                        label = "gearRoll"
                    ) { g ->
                        Text(g, color = if (gearFlash > 0.4f) DarkBackground else accent, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Black)
                    }
                }
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            readoutItem("air", "%.1f".format(air), airWarn)
                            readoutItem("fuel", "$fuel%", fuel <= 15)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            readoutItem("drop", "$adblue%", adblue <= 15)
                            readoutItem("temp", "$brakeTemp\u00B0", brakeTemp > 350)
                        }
                    }
                }
            }
            if (compact) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("RPM", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
                    DRpmBar(rpmRatio, Modifier.weight(1f))
                    Text(rpmText, color = speedColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("RPM", color = TextMuted, fontSize = 9.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
                        Text(rpmText, color = speedColor, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    DRpmBar(rpmRatio, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        readoutItem("air", "%.1f".format(air), airWarn)
                        readoutItem("fuel", "$fuel%", fuel <= 15)
                        readoutItem("drop", "$adblue%", adblue <= 15)
                        readoutItem("temp", "$brakeTemp\u00B0", brakeTemp > 350)
                    }
                }
            }
        }
    }
}
