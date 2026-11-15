package com.ets2controller.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.audio.CockpitAudioSynthesizer
import com.ets2controller.config.ControllerSettings
import com.ets2controller.network.TruckTelemetryV2
import com.ets2controller.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class RecenterStage { IDLE, HOLD_TO_CENTER, HOLD_STEADY, EASING_ZERO }

@Composable
fun ControllerScreen(
    isConnected: Boolean,
    connectedIp: String,
    packetsPerSec: Int,
    savedManualIp: String,
    onSetManualIp: (String) -> Unit,
    steeringProvider: () -> Float,
    rawAngleProvider: () -> Float,
    throttle: Float,
    brake: Float,
    clutch: Float,
    buttonMask: Long,
    coachSmoothMode: Boolean,
    settings: ControllerSettings,
    telemetry: TruckTelemetryV2,
    onThrottleChange: (Float) -> Unit,
    onBrakeChange: (Float) -> Unit,
    onClutchChange: (Float) -> Unit,
    onButtonStateChange: (Int, Boolean) -> Unit,
    onPulseBit: (Int) -> Unit,
    onToggleCoachMode: () -> Unit,
    onCalibrateClick: () -> Unit,
    onSaveSettings: (ControllerSettings) -> Unit,
    onLookChange: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeAccent = getThemeAccent(settings.clusterTheme)
    val audioSynth = remember { CockpitAudioSynthesizer() }
    LaunchedEffect(settings.soundEffectsEnabled) {
        audioSynth.isEnabled = settings.soundEffectsEnabled
    }

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun buzzStrong() {
        if (!settings.hapticFeedbackEnabled || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(32L, 210))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(32L)
        }
    }

    var rootWidthPx by remember { mutableFloatStateOf(0f) }
    var showTuningModal by remember { mutableStateOf(false) }
    var showMoreDrawer by remember { mutableStateOf(false) }
    var showConnectionDialog by remember { mutableStateOf(false) }

    var isGyroMode by remember { mutableStateOf(true) }
    var padSteerNorm by remember { mutableFloatStateOf(0f) }

    var pinnedTile by remember {
        mutableStateOf(MoreTileDef(id = "diff_lock", label = "DIFF LOCK", icon = "DIFF_LOCK", bitIndex = 18, isLatched = true))
    }

    var lightStage by remember { mutableIntStateOf(2) }
    var localRetarder by remember { mutableIntStateOf(0) }
    var brakeTempC by remember { mutableFloatStateOf(140f) }
    var radioStationIdx by remember { mutableIntStateOf(0) }
    val stations = remember {
        listOf(
            "HT DRIVE   FM 98.4 • NIGI",
            "FM 98.4 • NIGHT DRIVE",
            "EURO TRUCK RADIO   FM 102.6",
            "SCANDIA FREIGHT   FM 91.2"
        )
    }

    LaunchedEffect(brake, telemetry.speedKmh, telemetry.retarderLevel, localRetarder) {
        while (true) {
            val activeRetarder = maxOf(telemetry.retarderLevel, localRetarder)
            val heatGain = if (brake > 0.1f && telemetry.speedKmh > 5f) brake * 6.5f else 0f
            val coolRate = 1.8f + activeRetarder * 0.6f
            brakeTempC = (brakeTempC + heatGain - coolRate).coerceIn(140f, 440f)
            delay(250L)
        }
    }

    val isGasCutByBrake = brake > 0.02f && throttle > 0.02f
    LaunchedEffect(brake) {
        if (brake > 0.02f && throttle > 0f) {
            onThrottleChange(0f)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "cockpitAnim")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(460, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    val wiperSweep by infiniteTransition.animateFloat(
        initialValue = -28f,
        targetValue = 28f,
        animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wiperSweep"
    )
    val eqPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
        label = "eqPhase"
    )

    var gearFlashAlpha by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(telemetry.gear) {
        if (telemetry.gear != 0) {
            gearFlashAlpha = 0.55f
            delay(220L)
            gearFlashAlpha = 0f
        }
    }

    val rpmRatio = if (telemetry.maxRpm > 100f) (telemetry.engineRpm / telemetry.maxRpm).coerceIn(0f, 1.1f) else 0f
    val isRedline = rpmRatio > 0.90f
    val isShiftCue = rpmRatio > 0.86f
    val speedDisplay = if (settings.speedUnitMph) (telemetry.speedKmh * 0.621371f).roundToInt() else telemetry.speedKmh.roundToInt()
    val limitDisplay = if (telemetry.speedLimitKmh > 0) {
        if (settings.speedUnitMph) (telemetry.speedLimitKmh * 0.621371f).roundToInt() else telemetry.speedLimitKmh
    } else 80
    val isOverspeed = telemetry.speedLimitKmh > 0 && speedDisplay > limitDisplay + 2
    val gPeak = maxOf(abs(telemetry.lateralG), abs(telemetry.longitudinalG))

    val overspeedShakeX by infiniteTransition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(70, easing = LinearEasing), RepeatMode.Reverse),
        label = "overspeedShake"
    )

    val liveRawAngle = if (isGyroMode) rawAngleProvider() else padSteerNorm * 45f
    var recenterStage by remember { mutableStateOf(RecenterStage.IDLE) }
    var recenterRingProgress by remember { mutableFloatStateOf(0f) }
    var easeOutAngleOffset by remember { mutableFloatStateOf(0f) }
    val displayAngle = liveRawAngle - easeOutAngleOffset

    val isDriftHint = isGyroMode && telemetry.speedKmh > 35f && abs(displayAngle) in 4.0f..14.0f
    val isWheelCentered = abs(displayAngle) < 1.2f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .onSizeChanged { rootWidthPx = it.width.toFloat() }
    ) {
        // Ambient Edge Glows
        val leftGreenActive = telemetry.isLeftBlinker || telemetry.isHazard
        val rightGreenActive = telemetry.isRightBlinker || telemetry.isHazard

        if (leftGreenActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(96.dp)
                    .background(Brush.horizontalGradient(listOf(ConnectedGreen.copy(alpha = 0.28f * pulseAlpha), Color.Transparent)))
            )
        }
        if (rightGreenActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(96.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, ConnectedGreen.copy(alpha = 0.28f * pulseAlpha))))
            )
        }
        if (isRedline) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 16.dp,
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, BrakePedalRed.copy(alpha = 0.34f * pulseAlpha)),
                            radius = 900f
                        ),
                        shape = RoundedCornerShape(0.dp)
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------------------------------------------------------------------
            // TOP BAR
            // ---------------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showConnectionDialog = true }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val dotColor = if (isConnected) ConnectedGreen else DisconnectedRed.copy(alpha = pulseAlpha)
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dotColor))
                    Text(
                        text = if (isConnected) "PC LINKED" else "NO PC",
                        color = if (isConnected) TextBright else DisconnectedRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        text = "${"%.2f".format(gPeak)}G",
                        color = ConnectedGreen,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Radio Capsule
                Row(
                    modifier = Modifier
                        .width(340.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CockpitPanel)
                        .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "I<",
                        color = TextBright,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                radioStationIdx = (radioStationIdx - 1 + stations.size) % stations.size
                                onPulseBit(16)
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPulseBit(14) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.height(16.dp)
                        ) {
                            val heights = listOf(
                                0.45f + 0.50f * eqPhase,
                                0.95f - 0.45f * eqPhase,
                                0.55f + 0.45f * (1f - eqPhase),
                                0.85f - 0.35f * eqPhase,
                                0.40f + 0.55f * eqPhase
                            )
                            heights.forEach { frac ->
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .fillMaxHeight(frac.coerceIn(0.25f, 1f))
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(activeAccent)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stations[radioStationIdx],
                            color = TextBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            maxLines = 1
                        )
                    }

                    Text(
                        text = ">I",
                        color = TextBright,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                radioStationIdx = (radioStationIdx + 1) % stations.size
                                onPulseBit(15)
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                // Right Controls: [ GYRO | PAD ] | [ ••• ] | [ ⚙ ]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CockpitPanel)
                            .border(1.dp, CockpitBorder, RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .background(if (isGyroMode) activeAccent else Color.Transparent)
                                .clickable {
                                    isGyroMode = true
                                    padSteerNorm = 0f
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            CockpitVectorIcon(
                                name = "PHONE",
                                tint = if (isGyroMode) Color(0xFF0D1015) else TextBright,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "GYRO",
                                color = if (isGyroMode) Color(0xFF0D1015) else TextBright,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .background(if (!isGyroMode) activeAccent else Color.Transparent)
                                .clickable { isGyroMode = false }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            CockpitVectorIcon(
                                name = "WHEEL",
                                tint = if (!isGyroMode) Color(0xFF0D1015) else TextBright,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "PAD",
                                color = if (!isGyroMode) Color(0xFF0D1015) else TextBright,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(width = 46.dp, height = 40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CockpitPanel)
                            .border(1.dp, CockpitBorder, RoundedCornerShape(10.dp))
                            .clickable { showMoreDrawer = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("•••", color = activeAccent, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    }

                    var setupHoldProgress by remember { mutableFloatStateOf(0f) }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CockpitPanel)
                            .border(1.dp, if (setupHoldProgress > 0f) activeAccent else CockpitBorder, RoundedCornerShape(10.dp))
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val job = scope.launch {
                                        val t0 = System.currentTimeMillis()
                                        while (true) {
                                            val dt = System.currentTimeMillis() - t0
                                            setupHoldProgress = (dt / 400f).coerceIn(0f, 1f)
                                            if (dt >= 400L) {
                                                buzzStrong()
                                                setupHoldProgress = 0f
                                                showTuningModal = true
                                                break
                                            }
                                            delay(16L)
                                        }
                                    }
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) {
                                            job.cancel()
                                            setupHoldProgress = 0f
                                            break
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        CockpitVectorIcon("GEAR", tint = TextBright, modifier = Modifier.size(18.dp))
                        if (setupHoldProgress > 0f) {
                            Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
                                drawArc(
                                    color = activeAccent,
                                    startAngle = -90f,
                                    sweepAngle = 360f * setupHoldProgress,
                                    useCenter = false,
                                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------------------
            // BODY: Left Thumb Zone (23.5%) | Center Instrument Cluster (53%) | Right Thumb Zone (23.5%)
            // ---------------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // =================================================================
                // LEFT COLUMN
                // =================================================================
                Column(
                    modifier = Modifier
                        .weight(0.235f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SafeMomentaryTile(
                        accentColor = ConnectedGreen,
                        isActiveHighlight = telemetry.isLeftBlinker,
                        rootWidthPx = rootWidthPx,
                        onTrigger = {
                            audioSynth.playIndicatorTick()
                            onPulseBit(4)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CockpitVectorIcon(
                                name = "ARROW_LEFT",
                                tint = if (telemetry.isLeftBlinker) ConnectedGreen else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "LEFT",
                                color = if (telemetry.isLeftBlinker) ConnectedGreen else TextMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }

                    // HORN / AIR with amber rim
                    SafeMomentaryTile(
                        accentColor = activeAccent,
                        useColoredBorder = true,
                        rootWidthPx = rootWidthPx,
                        onPressChanged = { pressed -> onButtonStateChange(0, pressed) },
                        onTrigger = { audioSynth.playAirHornBlast() },
                        onSwipeUp = {
                            audioSynth.playAirHornBlast()
                            onPulseBit(23)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CockpitVectorIcon("HORN", tint = activeAccent, modifier = Modifier.size(19.dp))
                            Text(
                                text = "HORN / AIR",
                                color = activeAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }

                    // LIGHTS LOW | HIGH BEAM (dead-centered)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val lightLabel = when (lightStage) {
                            0 -> "LIGHTS OFF"
                            1 -> "LIGHTS PARK"
                            else -> "LIGHTS LOW"
                        }
                        SafeMomentaryTile(
                            accentColor = TextBright,
                            rootWidthPx = rootWidthPx,
                            onTrigger = {
                                lightStage = (lightStage + 1) % 3
                                onPulseBit(2)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 7.dp, end = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    for (dot in 0..2) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(if (dot == lightStage) TextBright else TextMuted.copy(alpha = 0.35f))
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    CockpitVectorIcon("LIGHTS_LOW", tint = TextBright, modifier = Modifier.size(19.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = lightLabel,
                                        color = TextBright,
                                        fontSize = 10.sp,
                                        lineHeight = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        SafeMomentaryTile(
                            accentColor = CruiseSkyBlue,
                            isActiveHighlight = telemetry.isHighBeams,
                            rootWidthPx = rootWidthPx,
                            onTrigger = { onPulseBit(21) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CockpitVectorIcon(
                                    name = "HIGH_BEAM",
                                    tint = if (telemetry.isHighBeams) CruiseSkyBlue else TextBright,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "HIGH BEAM",
                                    color = if (telemetry.isHighBeams) CruiseSkyBlue else TextBright,
                                    fontSize = 10.sp,
                                    lineHeight = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // BRAKE Pedal
                    CockpitPedalTile(
                        label = "BRAKE",
                        iconName = "BRAKE_PEDAL",
                        accentColor = BrakePedalRed,
                        value = brake,
                        onValueChange = { newBrake ->
                            onBrakeChange(newBrake)
                            if (newBrake > 0.02f && throttle > 0f) {
                                onThrottleChange(0f)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }

                // =================================================================
                // CENTER COLUMN
                // =================================================================
                Column(
                    modifier = Modifier
                        .weight(0.53f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    var lookXOffset by remember { mutableFloatStateOf(0f) }
                    var lookYOffset by remember { mutableFloatStateOf(0f) }

                   // Primary Instrument Cluster Card (Unified Upper Row + Bottom RPM/Telemetry Strip)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(CockpitPanel)
                            .border(1.25.dp, CockpitBorder, RoundedCornerShape(16.dp))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragEnd = {
                                        lookXOffset = 0f
                                        lookYOffset = 0f
                                        onLookChange(0f, 0f)
                                    },
                                    onDragCancel = {
                                        lookXOffset = 0f
                                        lookYOffset = 0f
                                        onLookChange(0f, 0f)
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        lookXOffset = (lookXOffset + drag.x / (size.width * 0.35f)).coerceIn(-1f, 1f)
                                        lookYOffset = (lookYOffset - drag.y / (size.height * 0.50f)).coerceIn(-1f, 1f)
                                        onLookChange(lookXOffset, lookYOffset)
                                    }
                                )
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Upper Region: Left (Alert Pill + Wheel) | Center (Speed + KM/H) | Right (80 Sign + Gear Box)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left Column: Priority Alert Pill + Safe Re-Centre Wheel Widget
                            Column(
                                modifier = Modifier.fillMaxHeight(),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.Start
                            ) {
                                when {
                                    telemetry.isParkBrake && (telemetry.speedKmh > 2f || throttle > 0.05f) -> {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(BrakePedalRed)
                                                .padding(horizontal = 10.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            CockpitVectorIcon("PARK", tint = Color(0xFF0D1015), modifier = Modifier.size(12.dp))
                                            Text(
                                                text = "RELEASE PARK BRAKE",
                                                color = Color(0xFF0D1015),
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 0.4.sp
                                            )
                                        }
                                    }
                                    isShiftCue && telemetry.gear > 0 && telemetry.gear < telemetry.forwardGears -> {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(CockpitTile)
                                                .padding(horizontal = 10.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Text("^", color = TextBright, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                            Text(
                                                text = "SHIFT UP",
                                                color = TextBright,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 0.4.sp
                                            )
                                        }
                                    }
                                    telemetry.isCruiseActive -> {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(CruiseSkyBlue.copy(alpha = 0.16f))
                                                .padding(horizontal = 10.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            CockpitVectorIcon("CRUISE", tint = CruiseSkyBlue, modifier = Modifier.size(12.dp))
                                            Text(
                                                text = "CRUISE ${if (telemetry.cruiseSpeedKmh > 0) telemetry.cruiseSpeedKmh else 80}",
                                                color = CruiseSkyBlue,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 0.4.sp
                                            )
                                        }
                                    }
                                    else -> {
                                        Spacer(modifier = Modifier.height(2.dp))
                                    }
                                }

                                // Rule 8 Safe Re-Centre Wheel Widget
                                val wheelRingSize = if (isGyroMode) 36.dp else 28.dp
                                val wheelIconSize = if (isGyroMode) 22.dp else 17.dp
                                Row(
                                    modifier = Modifier
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val job = scope.launch {
                                                    recenterStage = RecenterStage.HOLD_TO_CENTER
                                                    val t0 = System.currentTimeMillis()
                                                    while (true) {
                                                        val dt = System.currentTimeMillis() - t0
                                                        recenterRingProgress = (dt / 400f).coerceIn(0f, 1f)
                                                        if (dt >= 400L) break
                                                        delay(16L)
                                                    }
                                                    buzzStrong()

                                                    recenterStage = RecenterStage.HOLD_STEADY
                                                    val anchorAngle = liveRawAngle
                                                    val t1 = System.currentTimeMillis()
                                                    while (true) {
                                                        val dt = System.currentTimeMillis() - t1
                                                        if (abs(liveRawAngle - anchorAngle) > 3.5f) {
                                                            recenterStage = RecenterStage.IDLE
                                                            recenterRingProgress = 0f
                                                            return@launch
                                                        }
                                                        recenterRingProgress = (dt / 600f).coerceIn(0f, 1f)
                                                        if (dt >= 600L) break
                                                        delay(16L)
                                                    }
                                                    buzzStrong()

                                                    recenterStage = RecenterStage.EASING_ZERO
                                                    val startOffset = liveRawAngle
                                                    onCalibrateClick()
                                                    padSteerNorm = 0f
                                                    val t2 = System.currentTimeMillis()
                                                    while (true) {
                                                        val frac = ((System.currentTimeMillis() - t2) / 500f).coerceIn(0f, 1f)
                                                        val smooth = frac * frac * (3f - 2f * frac)
                                                        easeOutAngleOffset = startOffset * (1f - smooth)
                                                        if (frac >= 1f) break
                                                        delay(16L)
                                                    }
                                                    easeOutAngleOffset = 0f
                                                    recenterStage = RecenterStage.IDLE
                                                    recenterRingProgress = 0f
                                                }

                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                    if (!ch.pressed) {
                                                        if (recenterStage != RecenterStage.EASING_ZERO) {
                                                            job.cancel()
                                                            recenterStage = RecenterStage.IDLE
                                                            recenterRingProgress = 0f
                                                        }
                                                        break
                                                    }
                                                }
                                            }
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(modifier = Modifier.size(wheelRingSize), contentAlignment = Alignment.Center) {
                                        val ringColor = when {
                                            recenterStage != RecenterStage.IDLE -> activeAccent
                                            isWheelCentered -> ConnectedGreen
                                            isDriftHint -> activeAccent
                                            else -> TextMuted.copy(alpha = 0.4f)
                                        }
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            drawCircle(ringColor.copy(alpha = 0.25f), style = Stroke(width = 2.dp.toPx()))
                                            val sweep = if (recenterStage != RecenterStage.IDLE) 360f * recenterRingProgress else 360f
                                            drawArc(
                                                color = ringColor,
                                                startAngle = -90f,
                                                sweepAngle = sweep,
                                                useCenter = false,
                                                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                                            )
                                        }
                                        CockpitVectorIcon(
                                            name = "WHEEL",
                                            tint = if (isWheelCentered) ConnectedGreen else TextBright,
                                            modifier = Modifier
                                                .size(wheelIconSize)
                                                .graphicsLayer { rotationZ = displayAngle.coerceIn(-180f, 180f) }
                                        )
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(
                                            text = when (recenterStage) {
                                                RecenterStage.HOLD_TO_CENTER -> "HOLD..."
                                                RecenterStage.HOLD_STEADY -> "STEADY"
                                                RecenterStage.EASING_ZERO -> "0.0°"
                                                RecenterStage.IDLE -> if (isWheelCentered) "CENTER" else "${"%+.1f".format(displayAngle)}°"
                                            },
                                            color = if (isWheelCentered) ConnectedGreen else TextBright,
                                            fontSize = 10.sp,
                                            lineHeight = 11.sp,
                                            fontFamily = FontFamily.SansSerif,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Text(
                                            text = if (isDriftHint) "+ DRIFT?" else "+ CENTER",
                                            color = if (isDriftHint) activeAccent else TextMuted,
                                            fontSize = 8.5.sp,
                                            lineHeight = 9.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))
                            }

                            // Center Column: Oversized Geometric Sans-Serif Speed + KM/H
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.graphicsLayer {
                                    translationX = if (isOverspeed) overspeedShakeX else 0f
                                }
                            ) {
                                Text(
                                    text = "$speedDisplay",
                                    color = if (isOverspeed || isRedline) BrakePedalRed else TextBright,
                                    fontSize = if (isGyroMode) 54.sp else 44.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif,
                                    lineHeight = if (isGyroMode) 52.sp else 42.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (settings.speedUnitMph) "MPH" else "KM/H",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    lineHeight = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }

                            // Right Column: 36.dp EU Speed Limit Sign + Gear Box (D12 / N)
                            val gearLabel = when {
                                telemetry.gear > 0 -> "D${telemetry.gear}"
                                telemetry.gear < 0 -> "R${abs(telemetry.gear)}"
                                else -> "N"
                            }
                            Column(
                                modifier = Modifier.fillMaxHeight(),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.End
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(CockpitTile)
                                        .border(2.4.dp, BrakePedalRed, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$limitDisplay",
                                        color = TextBright,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(width = 54.dp, height = if (isGyroMode) 46.dp else 38.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(activeAccent.copy(alpha = gearFlashAlpha))
                                        .border(1.8.dp, activeAccent, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = gearLabel,
                                        color = activeAccent,
                                        fontSize = if (isGyroMode) 20.sp else 17.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                }

                                Spacer(modifier = Modifier.height(2.dp))
                            }
                        }

                        // Bottom RPM Bar + 4 Icon-Labeled Telemetry Stats
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("RPM", color = TextMuted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "%,d".format(telemetry.engineRpm.roundToInt()),
                                    color = if (isRedline) BrakePedalRed else TextBright,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            ) {
                                drawRect(color = CockpitTile)
                                val fillW = size.width * rpmRatio.coerceIn(0f, 1f)
                                if (fillW > 0f) {
                                    val cyanEnd = (size.width * 0.72f).coerceAtMost(fillW)
                                    drawRect(color = CruiseSkyBlue, size = Size(cyanEnd, size.height))
                                    if (fillW > size.width * 0.72f) {
                                        val amberEnd = (size.width * 0.88f).coerceAtMost(fillW)
                                        drawRect(
                                            color = activeAccent,
                                            topLeft = Offset(size.width * 0.72f, 0f),
                                            size = Size(amberEnd - size.width * 0.72f, size.height)
                                        )
                                    }
                                    if (fillW > size.width * 0.88f) {
                                        drawRect(
                                            color = BrakePedalRed,
                                            topLeft = Offset(size.width * 0.88f, 0f),
                                            size = Size(fillW - size.width * 0.88f, size.height)
                                        )
                                    }
                                }
                            }

                            val isBrakeHot = brakeTempC >= 340f
                            val isLowFuel = telemetry.fuelPercent <= 15
                            val isLowAir = telemetry.isLowAir || telemetry.airPressureBar < 5.8f
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val airCol = if (isLowAir) activeAccent.copy(alpha = pulseAlpha) else TextMuted
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    CockpitVectorIcon("AIR_PRESSURE", tint = airCol, modifier = Modifier.size(12.dp))
                                    Text("${"%.1f".format(telemetry.airPressureBar)}", color = airCol, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                val fuelCol = if (isLowFuel) activeAccent.copy(alpha = pulseAlpha) else TextMuted
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    CockpitVectorIcon("FUEL_PUMP", tint = fuelCol, modifier = Modifier.size(12.dp))
                                    Text("${telemetry.fuelPercent}%", color = fuelCol, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    CockpitVectorIcon("DROPLET", tint = TextMuted, modifier = Modifier.size(12.dp))
                                    Text("${telemetry.adbluePercent}%", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                val brkCol = if (isBrakeHot) BrakePedalRed else TextMuted
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    CockpitVectorIcon("THERMOMETER", tint = brkCol, modifier = Modifier.size(12.dp))
                                    Text("${brakeTempC.roundToInt()}°", color = brkCol, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Compact Touch Steering Pad Bar when PAD mode is active
                    if (!isGyroMode) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CockpitTile)
                                .border(1.dp, activeAccent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val pointerId = down.id
                                        fun updatePad(x: Float) {
                                            val norm = ((x / size.width.toFloat()) * 2f - 1f).coerceIn(-1f, 1f)
                                            padSteerNorm = norm
                                            onLookChange(norm * 0.25f, 0f)
                                        }
                                        updatePad(down.position.x)
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val ch = ev.changes.firstOrNull { it.id == pointerId }
                                            if (ch == null || !ch.pressed) {
                                                padSteerNorm = 0f
                                                onLookChange(0f, 0f)
                                                break
                                            }
                                            updatePad(ch.position.x)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "TOUCH STEER PAD  ${"%+.0f".format(padSteerNorm * 100)}%",
                                color = activeAccent,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }

                    // Slide-In Utility Row: RETARDER (Dead-Centered) | WIPERS | CAMERA
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val activeRetarder = maxOf(telemetry.retarderLevel, localRetarder).coerceIn(0, 4)
                        Row(
                            modifier = Modifier
                                .weight(1.75f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CockpitTile)
                                .border(1.25.dp, CockpitBorder, RoundedCornerShape(14.dp)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(42.dp)
                                    .fillMaxHeight()
                                    .clickable {
                                        localRetarder = (localRetarder - 1).coerceAtLeast(0)
                                        onPulseBit(25)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                CockpitVectorIcon("CHEVRON_DOWN", tint = activeAccent, modifier = Modifier.size(16.dp))
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    for (pip in 1..4) {
                                        Box(
                                            modifier = Modifier
                                                .size(width = 11.dp, height = 16.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(
                                                    if (pip <= activeRetarder) activeAccent
                                                    else TextMuted.copy(alpha = 0.25f)
                                                )
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    CockpitVectorIcon("RETARDER_COIL", tint = TextMuted, modifier = Modifier.size(12.dp))
                                    Text(
                                        text = "RETARDER",
                                        color = TextMuted,
                                        fontSize = 9.5.sp,
                                        lineHeight = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.6.sp
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .width(42.dp)
                                    .fillMaxHeight()
                                    .clickable {
                                        localRetarder = (localRetarder + 1).coerceAtMost(4)
                                        onPulseBit(24)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                CockpitVectorIcon("CHEVRON_UP", tint = activeAccent, modifier = Modifier.size(16.dp))
                            }
                        }

                        SafeMomentaryTile(
                            accentColor = TextBright,
                            isActiveHighlight = telemetry.isWipersActive,
                            rootWidthPx = rootWidthPx,
                            onTrigger = { onPulseBit(12) },
                            onSwipeDown = { onPulseBit(36) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CockpitVectorIcon(
                                    name = "WIPERS",
                                    tint = TextBright,
                                    wiperAngleDeg = if (telemetry.isWipersActive) wiperSweep else 0f,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("WIPERS", color = TextBright, fontSize = 9.5.sp, lineHeight = 10.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }

                        SafeMomentaryTile(
                            accentColor = TextBright,
                            rootWidthPx = rootWidthPx,
                            onTrigger = { onPulseBit(13) },
                            onSwipeUp = { onPulseBit(34) },
                            onSwipeDown = { onPulseBit(33) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CockpitVectorIcon("CAMERA", tint = TextBright, modifier = Modifier.size(19.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("CAMERA", color = TextBright, fontSize = 9.5.sp, lineHeight = 10.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }

                    // Bottom 5-Tile Hold-to-Confirm Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HoldConfirmLatchedTile(
                            label = "HAZARD",
                            iconName = "HAZARD",
                            accentColor = BrakePedalRed,
                            isLatchedOn = telemetry.isHazard,
                            rootWidthPx = rootWidthPx,
                            onStrongBuzz = ::buzzStrong,
                            onConfirmedToggle = {
                                audioSynth.playIndicatorTick()
                                onPulseBit(3)
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )

                        HoldConfirmLatchedTile(
                            label = "PARK",
                            iconName = "PARK",
                            accentColor = BrakePedalRed,
                            isLatchedOn = false,
                            isPulsingAlert = telemetry.isParkBrake,
                            rootWidthPx = rootWidthPx,
                            onStrongBuzz = ::buzzStrong,
                            onConfirmedToggle = {
                                audioSynth.playAirBrakeHiss()
                                onPulseBit(6)
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )

                        HoldConfirmLatchedTile(
                            label = "ENGINE",
                            iconName = "ENGINE",
                            accentColor = ConnectedGreen,
                            isLatchedOn = false,
                            isPulsingAlert = telemetry.isEngineRunning,
                            rootWidthPx = rootWidthPx,
                            onStrongBuzz = ::buzzStrong,
                            onConfirmedToggle = { onPulseBit(1) },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )

                        HoldConfirmLatchedTile(
                            label = "TRAILER",
                            iconName = "TRAILER",
                            accentColor = activeAccent,
                            isLatchedOn = false,
                            isPulsingAlert = telemetry.isTrailerConnected,
                            rootWidthPx = rootWidthPx,
                            onStrongBuzz = ::buzzStrong,
                            onConfirmedToggle = { onPulseBit(20) },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )

                        val slot5Active = when (pinnedTile.bitIndex) {
                            18 -> telemetry.isDiffLock
                            22 -> telemetry.isBeacons
                            47 -> telemetry.isLkaActive
                            else -> ((buttonMask shr pinnedTile.bitIndex) and 1L) != 0L
                        }
                        SafeMomentaryTile(
                            accentColor = activeAccent,
                            isActiveHighlight = slot5Active,
                            useColoredBorder = true,
                            rootWidthPx = rootWidthPx,
                            onTrigger = { onPulseBit(pinnedTile.bitIndex) },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CockpitVectorIcon(
                                    name = pinnedTile.icon,
                                    tint = if (slot5Active) activeAccent else TextBright,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = pinnedTile.label,
                                    color = if (slot5Active) activeAccent else TextBright,
                                    fontSize = 9.sp,
                                    lineHeight = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // =================================================================
                // RIGHT COLUMN
                // =================================================================
                Column(
                    modifier = Modifier
                        .weight(0.235f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SafeMomentaryTile(
                        accentColor = ConnectedGreen,
                        isActiveHighlight = telemetry.isRightBlinker,
                        rootWidthPx = rootWidthPx,
                        onTrigger = {
                            audioSynth.playIndicatorTick()
                            onPulseBit(5)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CockpitVectorIcon(
                                name = "ARROW_RIGHT",
                                tint = if (telemetry.isRightBlinker) ConnectedGreen else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "RIGHT",
                                color = if (telemetry.isRightBlinker) ConnectedGreen else TextMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }

                    // ^ UP (amber border) | N NEUT | v DOWN (amber border)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SafeMomentaryTile(
                            accentColor = if (isShiftCue) BrakePedalRed else activeAccent,
                            isActiveHighlight = isShiftCue,
                            useColoredBorder = true,
                            rootWidthPx = rootWidthPx,
                            onTrigger = {
                                audioSynth.playShifterThunk()
                                onPulseBit(10)
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CockpitVectorIcon(
                                    name = "CHEVRON_UP",
                                    tint = if (isShiftCue) BrakePedalRed else activeAccent,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "UP",
                                    color = if (isShiftCue) BrakePedalRed else activeAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        SafeMomentaryTile(
                            accentColor = TextBright,
                            rootWidthPx = rootWidthPx,
                            onTrigger = {
                                audioSynth.playShifterThunk()
                                onPulseBit(17)
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("N", color = TextBright, fontSize = 14.sp, fontWeight = FontWeight.Black)
                                Text("NEUT", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }

                        SafeMomentaryTile(
                            accentColor = activeAccent,
                            useColoredBorder = true,
                            rootWidthPx = rootWidthPx,
                            onTrigger = {
                                audioSynth.playShifterThunk()
                                onPulseBit(11)
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CockpitVectorIcon("CHEVRON_DOWN", tint = activeAccent, modifier = Modifier.size(15.dp))
                                Text("DOWN", color = activeAccent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }

                    // - SLOWER | CRUISE OFF/80 | + FASTER (all 3 with sky-blue rims)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SafeMomentaryTile(
                            accentColor = CruiseSkyBlue,
                            useColoredBorder = true,
                            rootWidthPx = rootWidthPx,
                            onTrigger = { onPulseBit(27) },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("—", color = CruiseSkyBlue, fontSize = 13.sp, fontWeight = FontWeight.Black)
                                Text("SLOWER", color = CruiseSkyBlue, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }

                        SafeMomentaryTile(
                            accentColor = CruiseSkyBlue,
                            isActiveHighlight = telemetry.isCruiseActive,
                            useColoredBorder = true,
                            rootWidthPx = rootWidthPx,
                            onTrigger = { onPulseBit(9) },
                            onSwipeUp = { onPulseBit(28) },
                            modifier = Modifier.weight(1.1f).fillMaxHeight()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                CockpitVectorIcon("CRUISE", tint = CruiseSkyBlue, modifier = Modifier.size(16.dp))
                                Text(
                                    text = if (telemetry.isCruiseActive) "${if (telemetry.cruiseSpeedKmh > 0) telemetry.cruiseSpeedKmh else 80}" else "OFF",
                                    color = CruiseSkyBlue,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        SafeMomentaryTile(
                            accentColor = CruiseSkyBlue,
                            useColoredBorder = true,
                            rootWidthPx = rootWidthPx,
                            onTrigger = { onPulseBit(26) },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("+", color = CruiseSkyBlue, fontSize = 15.sp, fontWeight = FontWeight.Black)
                                Text("FASTER", color = CruiseSkyBlue, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }

                    // GAS Pedal
                    CockpitPedalTile(
                        label = "GAS",
                        iconName = "GAS_PEDAL",
                        accentColor = GasPedalGreen,
                        value = if (brake > 0.02f) 0f else throttle,
                        isDimmedByBrake = isGasCutByBrake || brake > 0.02f,
                        onValueChange = { newGas ->
                            if (brake <= 0.02f) {
                                onThrottleChange(newGas)
                            } else {
                                onThrottleChange(0f)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }

    if (showMoreDrawer) {
        MoreDrawerOverlay(
            pinnedTileId = pinnedTile.id,
            accentColor = activeAccent,
            onTriggerTile = { tile -> onPulseBit(tile.bitIndex) },
            onPinTile = { tile ->
                pinnedTile = tile
                buzzStrong()
            },
            onDismiss = { showMoreDrawer = false }
        )
    }

    if (showTuningModal) {
        SettingsDialog(
            currentSettings = settings,
            onSave = onSaveSettings,
            onResetPinnedSlot = {
                pinnedTile = MoreTileDef("diff_lock", "DIFF LOCK", "DIFF_LOCK", 18, true)
            },
            onDismiss = { showTuningModal = false }
        )
    }

    if (showConnectionDialog) {
        var ipInput by remember { mutableStateOf(connectedIp.ifBlank { savedManualIp }) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .clickable { showConnectionDialog = false },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(400.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CockpitPanel)
                    .border(1.25.dp, activeAccent, RoundedCornerShape(14.dp))
                    .clickable(enabled = false) {}
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("PC TELEMETRY LINK", color = activeAccent, fontSize = 14.sp, fontWeight = FontWeight.Black)
                Text(
                    text = "Launch ETS2 via start.bat & click OK on 'SDK features detected'. Auto-discovery scans Wi-Fi & USB Tethering automatically, or enter your PC IPv4:",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    label = { Text("PC IPv4 Address (e.g. 192.168.1.10)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        onSetManualIp("")
                        ipInput = ""
                        showConnectionDialog = false
                    }) {
                        Text("AUTO SCAN", color = TextMuted)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = {
                        onSetManualIp(ipInput)
                        showConnectionDialog = false
                    }) {
                        Text("CONNECT", color = activeAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}