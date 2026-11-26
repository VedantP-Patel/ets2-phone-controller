package com.ets2controller

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import com.ets2controller.config.ControllerSettings
import com.ets2controller.config.SettingsRepository
import com.ets2controller.network.TruckTelemetryV2
import com.ets2controller.network.UdpCockpitClient
import com.ets2controller.sensor.SteeringManager
import com.ets2controller.ui.ControllerScreen
import com.ets2controller.ui.DesignCockpitScreen

class MainActivity : ComponentActivity() {
    private lateinit var steeringManager: SteeringManager
    private lateinit var settingsRepo: SettingsRepository
    private var udpClient: UdpCockpitClient? = null
    private var vibrator: Vibrator? = null

    // Touch steering (PAD / SLIDE). When non-null it replaces the gyro value sent to the PC.
    @Volatile private var touchSteer: Float? = null
    @Volatile private var liveSpeedKmh: Float = 0f
    @Volatile private var liveSpeedSens: Float = 0.4f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        settingsRepo = SettingsRepository(this)
        steeringManager = SteeringManager(this).apply {
            settings = settingsRepo.loadSettings()
            onMicroHapticFeedback = { triggerShortHaptic(18, 60) }
            onAutoCancelBlinker = { isLeft -> udpClient?.triggerPulse(if (isLeft) 4 else 5, 65L) }
        }

        setContent {
            val view = LocalView.current
            SideEffect {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    view.doOnLayout {
                        val w = it.width
                        val h = it.height
                        it.systemGestureExclusionRects = listOf(
                            Rect(0, (h * 0.45f).toInt(), (w * 0.28f).toInt(), h),
                            Rect((w * 0.72f).toInt(), (h * 0.45f).toInt(), w, h)
                        )
                    }
                }
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                var settings by remember { mutableStateOf(steeringManager.settings) }
                var isConnected by remember { mutableStateOf(false) }
                var connectedIp by remember { mutableStateOf("") }
                var packetsPerSec by remember { mutableIntStateOf(0) }
                var telemetry by remember { mutableStateOf(TruckTelemetryV2()) }

                var throttle by remember { mutableFloatStateOf(0f) }
                var brake by remember { mutableFloatStateOf(0f) }
                var clutch by remember { mutableFloatStateOf(0f) }
                var buttonMask by remember { mutableLongStateOf(0L) }
                var coachSmoothMode by remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    liveSpeedSens = steeringManager.settings.speedSensitivity
                    val client = UdpCockpitClient(
                        context = this@MainActivity,
                        onTelemetryUpdate = { telem ->
                            telemetry = telem
                            steeringManager.currentSpeedKmh = telem.speedKmh
                            liveSpeedKmh = telem.speedKmh
                            steeringManager.isLeftBlinkerActive = telem.isLeftBlinker
                            steeringManager.isRightBlinkerActive = telem.isRightBlinker
                        },
                        onHapticPulse = { large, small ->
                            val peak = maxOf(large, small)
                            steeringManager.isHapticVibrating = peak > 15
                            if (peak > 20 && settings.forceFeedbackEnabled) {
                                triggerShortHaptic(22, peak.coerceIn(1, 255))
                            }
                        },
                        onConnectionState = { conn, ip, pps ->
                            isConnected = conn
                            connectedIp = ip
                            packetsPerSec = pps
                        }
                    ).apply {
                        steeringProvider = {
                            val raw = touchSteer ?: steeringManager.normalizedSteering
                            val scale = 1f - liveSpeedSens * 0.5f * (liveSpeedKmh / 120f).coerceIn(0f, 1f)
                            (raw * scale).coerceIn(-1f, 1f)
                        }
                    }
                    udpClient = client
                    client.start()
                    steeringManager.start()

                    onDispose {
                        steeringManager.stop()
                        client.stop()
                        udpClient = null
                    }
                }

                DesignCockpitScreen(
                    isConnected = isConnected,
                    connectedIp = connectedIp,
                    packetsPerSec = packetsPerSec,
                    savedManualIp = udpClient?.getSavedIp() ?: "",
                    onSetManualIp = { ip -> udpClient?.setManualHost(ip) },
                    rawAngleProvider = { steeringManager.rawAngleDeg },
                    throttle = throttle,
                    brake = brake,
                    clutch = clutch,
                    buttonMask = buttonMask,
                    settings = settings,
                    telemetry = telemetry,
                    onThrottleChange = {
                        throttle = it
                        udpClient?.throttle = it
                    },
                    onBrakeChange = {
                        brake = it
                        udpClient?.brake = it
                    },
                    onClutchChange = {
                        clutch = it
                        udpClient?.clutch = it
                    },
                    onButtonStateChange = { bit, pressed ->
                        if (bit in 0..63) {
                            val mask = 1L shl bit
                            buttonMask = if (pressed) (buttonMask or mask) else (buttonMask and mask.inv())
                            udpClient?.setButtonHeld(bit, pressed)
                        }
                    },
                    onPulseBit = { bit -> udpClient?.triggerPulse(bit, 65L) },
                    onCalibrateClick = { steeringManager.calibrateCenter() },
                    onSaveSettings = { updated: ControllerSettings ->
                        settings = updated
                        liveSpeedSens = updated.speedSensitivity
                        settingsRepo.saveSettings(updated)
                        steeringManager.settings = updated
                    },
                    onLookChange = { lx, ly ->
                        udpClient?.lookX = lx
                        udpClient?.lookY = ly
                    },
                    onTouchSteerChange = { touchSteer = it }
                )
            }
        }
    }

    private fun triggerShortHaptic(durationMs: Long, amplitude: Int) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(durationMs)
        }
    }
}