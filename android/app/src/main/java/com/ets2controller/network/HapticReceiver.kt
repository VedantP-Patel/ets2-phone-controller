package com.ets2controller.network

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ets2controller.audio.CockpitAudioSynthesizer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class HapticReceiver(private val context: Context) {
    private val isListening = AtomicBoolean(false)
    private var listenerThread: Thread? = null
    private var socket: DatagramSocket? = null

    var isEnabled: Boolean = true
    var onTelemetryReceived: ((TruckTelemetry) -> Unit)? = null
    var onVibrationActive: ((Boolean) -> Unit)? = null
    var rttCalculator: ((Int) -> Int)? = null
    var audioSynthesizer: CockpitAudioSynthesizer? = null

    private var prevGear = 0
    private var prevParkBrake = false
    private var prevBlinkerCycle = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clearVibrationRunnable = Runnable { onVibrationActive?.invoke(false) }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun startListening(port: Int = 5007) {
        if (isListening.get()) return
        isListening.set(true)

        listenerThread = Thread({
            try {
                socket = DatagramSocket(port).apply {
                    soTimeout = 2000
                }

                val buffer = ByteArray(64)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isListening.get()) {
                    try {
                        socket?.receive(packet)
                        val len = packet.length
                        if (len <= 0) continue

                        val header = buffer[0].toInt() and 0xFF

                        // 1. Force Feedback Rumble Packet (Header 0xFB, 4 bytes)
                        if (header == 0xFB && len >= 4) {
                            val largeMotor = buffer[1].toInt() and 0xFF
                            val smallMotor = buffer[2].toInt() and 0xFF
                            val expectedChecksum = (buffer[0].toInt() xor buffer[1].toInt() xor buffer[2].toInt()).toByte()

                            if (buffer[3] == expectedChecksum) {
                                triggerHaptics(largeMotor, smallMotor)
                            }
                        }
                        // 2. Real-Time Truck Telemetry Packet (Header 0xFC, 32 or 33 bytes)
                        else if (header == 0xFC && len >= 32) {
                            var valid = false
                            var is33Byte = false

                            if (len >= 33) {
                                var csum33: Byte = 0
                                for (i in 0..31) {
                                    csum33 = (csum33.toInt() xor buffer[i].toInt()).toByte()
                                }
                                if (buffer[32] == csum33) {
                                    valid = true
                                    is33Byte = true
                                }
                            }

                            if (!valid) {
                                var csum32: Byte = 0
                                for (i in 0..30) {
                                    csum32 = (csum32.toInt() xor buffer[i].toInt()).toByte()
                                }
                                if (buffer[31] == csum32) {
                                    valid = true
                                    is33Byte = false
                                }
                            }

                            if (valid) {
                                val byteBuf = ByteBuffer.wrap(buffer, 0, if (is33Byte) 33 else 32).order(ByteOrder.LITTLE_ENDIAN)
                                byteBuf.get() // Skip header 0xFC
                                val speedKmh = byteBuf.getFloat()
                                val engineRpm = byteBuf.getFloat()
                                val maxRpm = byteBuf.getFloat()
                                val gear = byteBuf.get().toInt()
                                val fuelPercent = byteBuf.get().toInt() and 0xFF
                                val airPressure = byteBuf.getFloat()
                                val flags = byteBuf.get().toInt() and 0xFF
                                val auxFlags = if (is33Byte) (byteBuf.get().toInt() and 0xFF) else 0
                                val pingEchoSeq = byteBuf.getShort().toInt() and 0xFFFF
                                val speedLimit = byteBuf.get().toInt() and 0xFF
                                val routeDist = byteBuf.getShort().toInt() and 0xFFFF
                                val routeTime = byteBuf.getShort().toInt() and 0xFFFF
                                val damagePct = byteBuf.get().toInt() and 0xFF
                                val cruiseSpeed = byteBuf.get().toInt() and 0xFF
                                val forwardGears = (byteBuf.get().toInt() and 0xFF).let { if (it > 0) it else 14 }
                                val shifterFlags = byteBuf.get().toInt() and 0xFF
                                val isAutomatic = (shifterFlags and 0x01) != 0

                                val rtt = rttCalculator?.invoke(pingEchoSeq) ?: 0

                                val isLeftBlinker = (flags and (1 shl 0)) != 0
                                val isRightBlinker = (flags and (1 shl 1)) != 0
                                val isHazard = (flags and (1 shl 2)) != 0
                                val isParkBrake = (flags and (1 shl 3)) != 0
                                val isEngineOn = (flags and (1 shl 4)) != 0
                                val isLowAir = (flags and (1 shl 5)) != 0
                                val isCruiseControl = (flags and (1 shl 6)) != 0

                                // Audio triggers for authentic cockpit feedback
                                val anyBlinker = isLeftBlinker || isRightBlinker || isHazard
                                if (anyBlinker != prevBlinkerCycle) {
                                    if (anyBlinker) {
                                        audioSynthesizer?.playIndicatorTick()
                                    }
                                    prevBlinkerCycle = anyBlinker
                                }

                                if (prevParkBrake && !isParkBrake) {
                                    audioSynthesizer?.playAirBrakeHiss()
                                }
                                prevParkBrake = isParkBrake

                                if (prevGear != gear) {
                                    audioSynthesizer?.playShifterThunk()
                                    prevGear = gear
                                }

                                val telemetry = TruckTelemetry(
                                    speedKmh = speedKmh,
                                    engineRpm = engineRpm,
                                    maxRpm = if (maxRpm > 500f) maxRpm else 2500f,
                                    gear = gear,
                                    fuelPercent = fuelPercent,
                                    airPressureBar = airPressure,
                                    isLeftBlinker = isLeftBlinker,
                                    isRightBlinker = isRightBlinker,
                                    isHazard = isHazard,
                                    isParkBrake = isParkBrake,
                                    isEngineOn = isEngineOn,
                                    isLowAir = isLowAir,
                                    isCruiseControl = isCruiseControl,
                                    roundTripPingMs = rtt,
                                    speedLimitKmh = speedLimit,
                                    routeDistanceKm = routeDist,
                                    routeTimeMin = routeTime,
                                    truckDamagePct = damagePct,
                                    cruiseSpeedKmh = cruiseSpeed,
                                    forwardGears = forwardGears,
                                    isAutomatic = isAutomatic,
                                    auxStateFlags = auxFlags
                                )
                                onTelemetryReceived?.invoke(telemetry)
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Socket poll timeout, loop again
                    } catch (_: Exception) {
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopListening()
            }
        }, "HapticAndTelemetryThread").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    fun stopListening() {
        isListening.set(false)
        try {
            socket?.close()
            socket = null
        } catch (_: Exception) {}
    }

    private fun triggerHaptics(largeMotor: Int, smallMotor: Int) {
        if (!isEnabled || vibrator?.hasVibrator() != true) return

        val maxMotor = maxOf(largeMotor, smallMotor)
        if (maxMotor <= 0) return

        val amplitude = maxMotor.coerceIn(1, 255)
        val durationMs = 80L

        try {
            mainHandler.removeCallbacks(clearVibrationRunnable)
            onVibrationActive?.invoke(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(durationMs, amplitude)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
            // Keep gravity filter locked for 45ms after motor stops to let physical phone resonance settle
            mainHandler.postDelayed(clearVibrationRunnable, durationMs + 45L)
        } catch (_: Exception) {
            onVibrationActive?.invoke(false)
        }
    }
}
