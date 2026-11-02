package com.ets2controller

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.ets2controller.config.ControllerSettings
import com.ets2controller.config.SettingsRepository
import com.ets2controller.network.BeaconReceiver
import com.ets2controller.network.HapticReceiver
import com.ets2controller.network.UdpTransmitter
import com.ets2controller.sensor.SteeringManager
import com.ets2controller.ui.ControllerScreen
import com.ets2controller.ui.theme.ETS2ControllerTheme

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var steeringManager: SteeringManager
    private lateinit var udpTransmitter: UdpTransmitter
    private lateinit var beaconReceiver: BeaconReceiver
    private lateinit var hapticReceiver: HapticReceiver
    private lateinit var audioSynthesizer: com.ets2controller.audio.CockpitAudioSynthesizer

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var uiPollThread: Thread? = null
    @Volatile private var isUiPolling = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clearMicroHapticRunnable = Runnable { steeringManager.isHapticVibrating = false }

    private var localLeftBlinker = false
    private var localRightBlinker = false

    private var activeSettings by mutableStateOf(ControllerSettings())
    private var isConnectedState by mutableStateOf(false)
    private var connectedIpState by mutableStateOf("")
    private var currentSteerState by mutableFloatStateOf(0.0f)
    private var rawAngleState by mutableFloatStateOf(0.0f)
    private var throttleState by mutableFloatStateOf(0.0f)
    private var brakeState by mutableFloatStateOf(0.0f)
    private var buttonMaskState by mutableIntStateOf(0)
    private var ppsState by mutableIntStateOf(0)
    private var telemetryState by mutableStateOf(com.ets2controller.network.TruckTelemetry())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock strictly to standard landscape
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Keep screen awake during gameplay
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Supercharge v3.0: High-Performance Wi-Fi MulticastLock & CPU WakeLock
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        try {
            multicastLock = wifiManager?.createMulticastLock("ETS2ControllerMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}

        try {
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(lockMode, "ETS2Controller:LowLatencyWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        try {
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ETS2Controller:HighPerfWakeLock")?.apply {
                acquire(8 * 60 * 60 * 1000L) // 8 hours max
            }
        } catch (_: Exception) {}

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        settingsRepo = SettingsRepository(this)
        activeSettings = settingsRepo.loadSettings()

        steeringManager = SteeringManager(this).apply {
            settings = activeSettings
            onMicroHapticFeedback = {
                triggerMicroHaptic()
            }
            onAutoCancelBlinker = { isLeft ->
                runOnUiThread {
                    if (isLeft) {
                        localLeftBlinker = false
                        udpTransmitter.setButton(4, true)
                        udpTransmitter.setButton(4, false)
                    } else {
                        localRightBlinker = false
                        udpTransmitter.setButton(5, true)
                        udpTransmitter.setButton(5, false)
                    }
                }
            }
        }

        udpTransmitter = UdpTransmitter()
        audioSynthesizer = com.ets2controller.audio.CockpitAudioSynthesizer().apply {
            isEnabled = activeSettings.soundEffectsEnabled
        }
        hapticReceiver = HapticReceiver(this).apply {
            isEnabled = activeSettings.forceFeedbackEnabled
            audioSynthesizer = this@MainActivity.audioSynthesizer
            rttCalculator = { seq -> udpTransmitter.getRttForSeq(seq) }
            onVibrationActive = { active ->
                steeringManager.isHapticVibrating = active
            }
            onTelemetryReceived = { telem ->
                steeringManager.currentSpeedKmh = telem.speedKmh
                steeringManager.isLeftBlinkerActive = telem.isLeftBlinker || localLeftBlinker
                steeringManager.isRightBlinkerActive = telem.isRightBlinker || localRightBlinker
                runOnUiThread {
                    telemetryState = telem
                }
            }
        }

        // Initialize auto-discovery beacon listener (auto-switches if PC IP changed!)
        beaconReceiver = BeaconReceiver(this) { discoveredIp ->
            if (!isConnectedState || connectedIpState != discoveredIp) {
                runOnUiThread {
                    connectToServer(discoveredIp, activeSettings.serverPort)
                }
            }
        }

        // If user already specified a fixed manual IP in settings, connect directly
        if (activeSettings.serverIp.isNotEmpty()) {
            connectToServer(activeSettings.serverIp, activeSettings.serverPort)
        }

        setContent {
            ETS2ControllerTheme {
                ControllerScreen(
                    isConnected = isConnectedState,
                    connectedIp = connectedIpState,
                    packetsPerSec = ppsState,
                    steeringValue = currentSteerState,
                    rawAngleDeg = rawAngleState,
                    throttle = throttleState,
                    brake = brakeState,
                    buttonMask = buttonMaskState,
                    settings = activeSettings,
                    telemetry = telemetryState,
                    onThrottleChange = { newGas ->
                        throttleState = newGas
                        udpTransmitter.currentThrottle = newGas
                    },
                    onBrakeChange = { newBrake ->
                        brakeState = newBrake
                        udpTransmitter.currentBrake = newBrake
                    },
                    onButtonStateChange = { bitIndex, isPressed ->
                        if (isPressed) {
                            if (bitIndex == 4) {
                                localLeftBlinker = !localLeftBlinker
                                localRightBlinker = false
                            } else if (bitIndex == 5) {
                                localRightBlinker = !localRightBlinker
                                localLeftBlinker = false
                            } else if (bitIndex == 23) {
                                audioSynthesizer.playAirHornBlast()
                            }
                        }
                        udpTransmitter.setButton(bitIndex, isPressed)
                        buttonMaskState = udpTransmitter.getEffectiveButtons()
                    },
                    onCalibrateClick = {
                        steeringManager.calibrateCenter()
                    },
                    onSaveSettings = { newSettings ->
                        activeSettings = newSettings
                        settingsRepo.saveSettings(newSettings)
                        steeringManager.settings = newSettings
                        hapticReceiver.isEnabled = newSettings.forceFeedbackEnabled
                        audioSynthesizer.isEnabled = newSettings.soundEffectsEnabled

                        if (newSettings.serverIp.isNotEmpty()) {
                            connectToServer(newSettings.serverIp, newSettings.serverPort)
                        }
                    },
                    onAirHornBlast = {
                        audioSynthesizer.playAirHornBlast()
                    },
                    onLookXChange = { lookX ->
                        udpTransmitter.currentLookX = lookX
                    }
                )
            }
        }

        // Immersive cockpit mode: applied after the decor view is attached
        window.decorView.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.let { controller ->
                        controller.hide(android.view.WindowInsets.Type.systemBars())
                        controller.systemBarsBehavior =
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun triggerMicroHaptic() {
        if (!activeSettings.hapticFeedbackEnabled || !activeSettings.steeringHapticTension) return
        try {
            steeringManager.isHapticVibrating = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(10)
            }
            mainHandler.removeCallbacks(clearMicroHapticRunnable)
            mainHandler.postDelayed(clearMicroHapticRunnable, 45L)
        } catch (_: Exception) {
            steeringManager.isHapticVibrating = false
        }
    }

    private fun connectToServer(ip: String, port: Int) {
        connectedIpState = ip
        isConnectedState = true
        udpTransmitter.connect(ip, port)
    }

    override fun onResume() {
        super.onResume()
        steeringManager.start()
        beaconReceiver.startListening()
        hapticReceiver.startListening()

        if (activeSettings.serverIp.isNotEmpty()) {
            connectToServer(activeSettings.serverIp, activeSettings.serverPort)
        }

        isUiPolling = true
        uiPollThread = Thread({
            var tick = 0
            while (isUiPolling && !isFinishing) {
                // Feed UDP transmitter at 200Hz (every 5ms) with zero UI overhead
                val steer = steeringManager.normalizedSteering
                udpTransmitter.currentSteering = steer

                // Update Compose UI states every 3rd tick (~60Hz) to prevent UI thread lag
                tick++
                if (tick % 3 == 0) {
                    val rawDeg = steeringManager.rawAngleDeg
                    val mask = udpTransmitter.getEffectiveButtons()
                    val pps = udpTransmitter.packetsSentPerSec
                    runOnUiThread {
                        currentSteerState = steer
                        rawAngleState = rawDeg
                        buttonMaskState = mask
                        ppsState = pps
                    }
                }

                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "SensorPollThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        isUiPolling = false
        uiPollThread?.interrupt()
        uiPollThread = null
        steeringManager.stop()
        beaconReceiver.stopListening()
        hapticReceiver.stopListening()
        udpTransmitter.disconnect()
        isConnectedState = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isUiPolling = false
        uiPollThread?.interrupt()
        udpTransmitter.disconnect()
        hapticReceiver.stopListening()
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }
}
