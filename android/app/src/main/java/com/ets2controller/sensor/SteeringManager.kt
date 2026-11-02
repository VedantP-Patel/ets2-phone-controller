package com.ets2controller.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.ets2controller.config.ControllerSettings
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

class SteeringManager(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val hasHardwareGyro: Boolean = gyroSensor != null && !gyroSensor.name.contains("Virtual", ignoreCase = true)

    // Prefer TYPE_GRAVITY (fused) over raw accelerometer to avoid linear shake artifacts
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val filteredGravity = FloatArray(3) { if (it == 0) 9.81f else 0f }
    private var hasGravitySample = false

    private var baselineRollDeg = 0.0f
    private var isCalibrated = false

    private var fusedAngleDeg = 0.0f
    private var prevGravRollDeg = 0.0f
    private var continuousGravDeg = 0.0f
    private var hasPrevGravRoll = false

    private var lastGyroTimestampNs = 0L
    // Online gyro zero-rate bias tracker (cancels MEMS drift before integration)
    private var gyroBiasZDegPerSec = 0.0f
    private var lastLinearAccelError = 0.0f

    // Tuned 1-Euro Filter: lower mincutoff (1.3Hz) for rock-steady highway center,
    // higher beta (0.06) for zero-lag fast counter-steering.
    private val filter = OneEuroFilter(100f, 1.3f, 0.06f, 2.5f)

    @Volatile
    var normalizedSteering: Float = 0.0f
        private set

    @Volatile
    var rawAngleDeg: Float = 0.0f
        private set

    @Volatile
    var currentSpeedKmh: Float = 0.0f

    @Volatile
    var isLeftBlinkerActive: Boolean = false

    @Volatile
    var isRightBlinkerActive: Boolean = false

    @Volatile
    var isHapticVibrating: Boolean = false

    var onAutoCancelBlinker: ((isLeft: Boolean) -> Unit)? = null
    var onMicroHapticFeedback: (() -> Unit)? = null

    private var leftBlinkerArmed = false
    private var rightBlinkerArmed = false
    private var lastHapticNotch = 0

    var settings: ControllerSettings = ControllerSettings()
        set(value) {
            field = value
            filter.updateParameters(
                value.filterMinCutoff.coerceAtLeast(1.0f),
                value.filterBeta.coerceAtLeast(0.03f)
            )
        }

    fun start() {
        if (sensorThread == null) {
            val thread = HandlerThread("SteeringSensorThread", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
            sensorThread = thread
            val handler = Handler(thread.looper)
            sensorHandler = handler

            gravitySensor?.let { sensor ->
                try {
                    sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
                } catch (_: SecurityException) {
                    sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
                }
            }
            if (hasHardwareGyro && gyroSensor != null) {
                try {
                    sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
                } catch (_: SecurityException) {
                    sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME, handler)
                }
            } else {
                rotationSensor?.let { sensor ->
                    try {
                        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
                    } catch (_: SecurityException) {
                        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
                    }
                }
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        filter.reset()
    }

    fun calibrateCenter() {
        if (hasGravitySample) {
            baselineRollDeg = computeScreenRollFromGravity(filteredGravity[0], filteredGravity[1])
        }
        fusedAngleDeg = 0.0f
        continuousGravDeg = 0.0f
        prevGravRollDeg = 0.0f
        hasPrevGravRoll = false
        rawAngleDeg = 0.0f
        normalizedSteering = 0.0f
        lastGyroTimestampNs = 0L
        leftBlinkerArmed = false
        rightBlinkerArmed = false
        lastHapticNotch = 0
        isCalibrated = true
        filter.reset()
    }

    private fun computeScreenRollFromGravity(gx: Float, gy: Float): Float {
        return Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat()
    }

    private fun wrapAngle180(angle: Float): Float {
        var a = (angle + 180.0f) % 360.0f
        if (a < 0.0f) a += 360.0f
        return a - 180.0f
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_GRAVITY || event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val rawGx = event.values[0]
            val rawGy = event.values[1]
            val rawGz = event.values[2]
            val totalMag = sqrt(rawGx * rawGx + rawGy * rawGy + rawGz * rawGz)
            // How far total acceleration is from pure 1G (9.81 m/s^2) due to hand shaking/whipping
            lastLinearAccelError = abs(totalMag - 9.81f)

            if (!hasGravitySample) {
                filteredGravity[0] = rawGx
                filteredGravity[1] = rawGy
                filteredGravity[2] = rawGz
                hasGravitySample = true
                if (!isCalibrated) calibrateCenter()
            } else {
                // TYPE_GRAVITY is already hardware-filtered; use higher alpha unless accelerometer fallback
                val baseAlpha = if (event.sensor.type == Sensor.TYPE_GRAVITY) 0.45f else 0.18f
                // Reject linear hand-whip acceleration and haptic buzz from corrupting gravity horizon
                val whipGate = (1.0f - (lastLinearAccelError / 3.5f)).coerceIn(0.05f, 1.0f)
                val alpha = if (isHapticVibrating) 0.02f else (baseAlpha * whipGate)
                filteredGravity[0] += alpha * (rawGx - filteredGravity[0])
                filteredGravity[1] += alpha * (rawGy - filteredGravity[1])
                filteredGravity[2] += alpha * (rawGz - filteredGravity[2])
            }

            val gx = filteredGravity[0]
            val gy = filteredGravity[1]
            val screenPlaneMag = sqrt(gx * gx + gy * gy)

            if (screenPlaneMag > 2.0f) {
                val currentRoll = computeScreenRollFromGravity(gx, gy)
                val relRoll = wrapAngle180(currentRoll - baselineRollDeg)

                if (!hasPrevGravRoll) {
                    prevGravRollDeg = relRoll
                    continuousGravDeg = relRoll
                    hasPrevGravRoll = true
                } else {
                    val deltaGrav = wrapAngle180(relRoll - prevGravRollDeg)
                    continuousGravDeg += deltaGrav
                    prevGravRollDeg = relRoll
                }
            }

            if (!hasHardwareGyro && rotationSensor == null) {
                processFinalSteeringAngle(continuousGravDeg, event.timestamp)
            }
            return
        }

        // Fallback for phones with Virtual Gyro using Game Rotation Vector
        if (!hasHardwareGyro && event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val rotMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            // Extract gravity direction in device frame from bottom row of rotation matrix
            val gx = rotMatrix[6] * 9.81f
            val gy = rotMatrix[7] * 9.81f
            if (sqrt(gx * gx + gy * gy) > 2.0f) {
                val currentRoll = computeScreenRollFromGravity(gx, gy)
                val relRoll = wrapAngle180(currentRoll - baselineRollDeg)
                if (!hasPrevGravRoll) {
                    prevGravRollDeg = relRoll
                    continuousGravDeg = relRoll
                    hasPrevGravRoll = true
                } else {
                    continuousGravDeg += wrapAngle180(relRoll - prevGravRollDeg)
                    prevGravRollDeg = relRoll
                }
                processFinalSteeringAngle(continuousGravDeg, event.timestamp)
            }
            return
        }

        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            if (!isCalibrated && hasGravitySample) {
                calibrateCenter()
            }

            val nowNs = event.timestamp
            val dt = if (lastGyroTimestampNs > 0L) {
                ((nowNs - lastGyroTimestampNs) * 1e-9f).coerceIn(0.0005f, 0.05f)
            } else {
                0.004f
            }
            lastGyroTimestampNs = nowNs

            val gx = filteredGravity[0]
            val gy = filteredGravity[1]
            val gz = filteredGravity[2]
            val screenPlaneMag = sqrt(gx * gx + gy * gy)

            val rawOmegaZ = -Math.toDegrees(event.values[2].toDouble()).toFloat()
            val tiltRateDeg = Math.toDegrees(
                sqrt((event.values[0] * event.values[0] + event.values[1] * event.values[1]).toDouble())
            ).toFloat()

            // 1. Online Gyro Bias Cancellation: when phone is held steady (< 1.8 deg/s),
            // learn the sensor's thermal zero-rate offset so it NEVER drifts while holding a line.
            if (abs(rawOmegaZ - gyroBiasZDegPerSec) < 1.8f && tiltRateDeg < 4.0f && lastLinearAccelError < 0.8f) {
                gyroBiasZDegPerSec += (rawOmegaZ - gyroBiasZDegPerSec) * (0.5f * dt).coerceIn(0.0f, 0.02f)
            }

            var omegaZDegPerSec = rawOmegaZ - gyroBiasZDegPerSec
            // Micro-noise gate: ignore sub-0.12 deg/s sensor floor noise so stationary wheel is 100% frozen
            if (abs(omegaZDegPerSec) < 0.12f) omegaZDegPerSec = 0.0f

            fusedAngleDeg += omegaZDegPerSec * dt

            // 2. Zero-Deflection Horizon Lock
            if (settings.gravityAnchorEnabled && hasPrevGravRoll && screenPlaneMag > 2.5f) {
                val turnOffset = ((fusedAngleDeg - continuousGravDeg) / 360.0f).roundToInt() * 360.0f
                val alignedGravDeg = continuousGravDeg + turnOffset
                val gravError = alignedGravDeg - fusedAngleDeg

                // Gate out gravity error if user is whipping the wheel fast or tilting phone forward/back
                val tiltGate = (1.0f - (tiltRateDeg / 75.0f)).coerceIn(0.0f, 1.0f)
                val whipGate = (1.0f - (lastLinearAccelError / 2.5f)).coerceIn(0.0f, 1.0f)
                val speedGate = (1.0f - (abs(omegaZDegPerSec) / 120.0f)).coerceIn(0.0f, 1.0f)

                // Strong anchor inside first ±135°; gentle anchor on deep multi-turn parking locks
                val correctionRate = if (abs(fusedAngleDeg) < 135.0f) 3.2f else 1.2f
                val alphaComplement = (correctionRate * speedGate * tiltGate * whipGate * dt).coerceIn(0.0f, 0.08f)

                fusedAngleDeg += gravError * alphaComplement
                continuousGravDeg = alignedGravDeg
            }

            // 3. Stationary Recenter Drift Bleed + Caster Spring Assistance:
            // When holding the wheel nearly still within ±12° of center, gently bleed out any
            // residual sub-degree posture/sensor offset so the truck tracks laser-straight.
            val isHoldingSteady = abs(omegaZDegPerSec) < 2.5f && tiltRateDeg < 6.0f
            if (isHoldingSteady && abs(fusedAngleDeg) < 12.0f) {
                val bleedRate = 0.35f * settings.autoZeroRecenterSensitivity
                val snapBoost = if (settings.centerSnapAssistance && abs(fusedAngleDeg) < 3.5f) 2.2f else 1.0f
                val bleedAlpha = (bleedRate * snapBoost * dt).coerceIn(0.0f, 0.05f)
                fusedAngleDeg *= (1.0f - bleedAlpha)
                continuousGravDeg *= (1.0f - bleedAlpha)
            }

            // 4. Active Caster Self-Aligning Spring when cruising (> 15 km/h) and relaxing hands
            if (settings.casterSpringStrength > 0.01f && abs(currentSpeedKmh) > 15.0f && abs(omegaZDegPerSec) < 8.0f) {
                val speedScale = (abs(currentSpeedKmh) / 80.0f).coerceIn(0.2f, 1.0f)
                val springPull = settings.casterSpringStrength * speedScale * 1.8f * dt
                fusedAngleDeg *= (1.0f - springPull.coerceIn(0.0f, 0.04f))
            }

            processFinalSteeringAngle(fusedAngleDeg, nowNs)
        }
    }

    private fun processFinalSteeringAngle(unfilteredAngleDeg: Float, timestampNs: Long) {
        val baseHalfLockDeg = if (settings.wheelMode1to1) {
            (settings.wheelDegreesRange / 2.0f).coerceAtLeast(30.0f)
        } else {
            settings.steeringMaxAngleDeg.coerceAtLeast(15.0f)
        }

        // City & Parking Power Steering Assist:
        // Below 35 km/h (and in Reverse), smoothly reduce required wrist rotation by 25%
        // so tight roundabouts and trailer backing don't require awkward arm crossovers.
        // Above 45 km/h, smoothly locks to 100% full range for highway stability.
        val cityAgilityScale = (0.75f + 0.25f * (( abs(currentSpeedKmh) - 10.0f) / 35.0f).coerceIn(0.0f, 1.0f))
        val halfLockDeg = (baseHalfLockDeg * cityAgilityScale).coerceAtLeast(30.0f)

        // Prevent wind-up beyond physical lock
        val maxTurnLimit = halfLockDeg + 15.0f
        fusedAngleDeg = fusedAngleDeg.coerceIn(-maxTurnLimit, maxTurnLimit)
        continuousGravDeg = continuousGravDeg.coerceIn(-maxTurnLimit, maxTurnLimit)

        val timestampSec = timestampNs * 1e-9
        val smoothedAngle = filter.filter(unfilteredAngleDeg.coerceIn(-halfLockDeg, halfLockDeg), timestampSec)

        val totalAngle = if (settings.invertSteering) -smoothedAngle else smoothedAngle
        rawAngleDeg = totalAngle

        val deadzone = settings.steeringDeadzoneDeg.coerceIn(0.0f, 10.0f)
        val absAngle = abs(totalAngle)

       val steerVal = if (absAngle <= deadzone) {
            0.0f
        } else {
            val rawNorm = ((absAngle - deadzone) / (halfLockDeg - deadzone).coerceAtLeast(1.0f)).coerceIn(0.0f, 1.0f)
            // Hydraulic Center Snap: smooth Hermite pocket across the first 2.5° outside deadzone
            // so exiting a turn never leaves a 1% lingering steering angle
            val normalized = if (settings.centerSnapAssistance && absAngle < deadzone + 2.5f) {
                val pocket = ((absAngle - deadzone) / 2.5f).coerceIn(0.0f, 1.0f)
                rawNorm * (pocket * pocket * (3.0f - 2.0f * pocket))
            } else {
                rawNorm
            }

            if (settings.wheelMode1to1) {
                // In 1:1 Wheel Mode, use a clean cubic blend (15% non-linear) instead of power curves
                // so small highway micro-corrections are smooth without distorting large turns.
                val cubicWeight = 0.15f + (currentSpeedKmh / 110.0f).coerceIn(0.0f, 0.15f)
                val shaped = (1.0f - cubicWeight) * normalized + cubicWeight * (normalized * normalized * normalized)
                sign(totalAngle) * shaped
            } else {
                val speedFactor = (currentSpeedKmh / 90.0f).coerceIn(0.0f, 1.0f)
                val curve = (settings.steeringCurvePower + 0.25f * speedFactor).coerceIn(1.0f, 2.5f)
                sign(totalAngle) * normalized.pow(curve)
            }
        }

        normalizedSteering = steerVal.coerceIn(-1.0f, 1.0f)

        // Micro-haptic notches every 15 degrees
        if (settings.steeringHapticTension) {
            val currentNotch = (absAngle / 15.0f).toInt()
            if (currentNotch != lastHapticNotch) {
                lastHapticNotch = currentNotch
                if (currentNotch > 0) {
                    onMicroHapticFeedback?.invoke()
                }
            }
        }

        // Auto-Cancelling Turn Indicators (ACTI) with hysteresis
        if (settings.autoCancelBlinkers) {
            if (isLeftBlinkerActive) {
                if (totalAngle < -18.0f) leftBlinkerArmed = true
                else if (leftBlinkerArmed && totalAngle > -4.0f) {
                    leftBlinkerArmed = false
                    onAutoCancelBlinker?.invoke(true)
                }
            } else {
                leftBlinkerArmed = false
            }

            if (isRightBlinkerActive) {
                if (totalAngle > 18.0f) rightBlinkerArmed = true
                else if (rightBlinkerArmed && totalAngle < 4.0f) {
                    rightBlinkerArmed = false
                    onAutoCancelBlinker?.invoke(false)
                }
            } else {
                rightBlinkerArmed = false
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
