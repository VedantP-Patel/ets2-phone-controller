package com.ets2controller.sensor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
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
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

object SteeringMath {
    /**
     * Maps a filtered steering angle (degrees) to normalized [-1, 1] output using
     * a single consolidated speed-sensitivity and deadzone model.
     */
    fun shapeAngle(
        totalAngleDeg: Float,
        halfLockDeg: Float,
        speedKmh: Float,
        settings: ControllerSettings,
        coachSmoothMode: Boolean
    ): Float {
        val baseDz = settings.steeringDeadzoneDeg.coerceIn(0.0f, 10.0f)
        val absSpeed = abs(speedKmh)
        val effectiveDz = if (settings.speedSensitiveDeadzone) {
            val speedRatio = (absSpeed / 60.0f).coerceIn(0.0f, 1.0f)
            baseDz * (0.45f + 0.55f * speedRatio)
        } else {
            baseDz
        }

        val absAngle = abs(totalAngleDeg)
        if (absAngle <= effectiveDz) return 0.0f

        val rawNorm = ((absAngle - effectiveDz) / (halfLockDeg - effectiveDz).coerceAtLeast(1.0f)).coerceIn(0.0f, 1.0f)
        val normalized = if (settings.centerSnapAssistance && absAngle < effectiveDz + 2.5f) {
            val pocket = ((absAngle - effectiveDz) / 2.5f).coerceIn(0.0f, 1.0f)
            rawNorm * (pocket * pocket * (3.0f - 2.0f * pocket))
        } else {
            rawNorm
        }

        val speedFactor = (absSpeed / 120.0f).coerceIn(0.0f, 1.0f)
        val speedScale = 1.0f - settings.speedSensitivity.coerceIn(0.0f, 1.0f) * 0.45f * speedFactor

        val shaped = if (settings.wheelMode1to1 || coachSmoothMode) {
            val cubicWeight = if (coachSmoothMode) 0.30f else (0.15f + (absSpeed / 110.0f).coerceIn(0.0f, 0.15f))
            (1.0f - cubicWeight) * normalized + cubicWeight * (normalized * normalized * normalized)
        } else {
            val curve = settings.steeringCurvePower.coerceIn(1.0f, 2.5f)
            normalized.pow(curve)
        }

        return (sign(totalAngleDeg) * shaped * speedScale).coerceIn(-1.0f, 1.0f)
    }
}


class SteeringManager(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val hasHardwareGyro: Boolean =
        gyroSensor != null && !gyroSensor.name.contains("Virtual", ignoreCase = true)

    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val hasDedicatedGravity: Boolean = gravitySensor != null

    private val rotMatrix = FloatArray(9)
    private val filteredGravity = FloatArray(3) { if (it == 0) 9.81f else 0f }
    private var hasGravitySample = false

    private var baselineRollDeg = 0.0f
    private var isCalibrated = false

    private var fusedAngleDeg = 0.0f
    private var prevGravRollDeg = 0.0f
    private var continuousGravDeg = 0.0f
    private var hasPrevGravRoll = false

    private var lastGyroTimestampNs = 0L
    private var prevOmegaDegPerSec = 0.0f
    private var gyroBiasDegPerSec = 0.0f
    private var anchoredAngleDeg = 0.0f
    private var lastLinearAccelError = 0.0f

    private val filter = OneEuroFilter(120f, 1.3f, 0.06f, 2.5f)

    var normalizedSteering by mutableFloatStateOf(0.0f)
        private set

    var rawAngleDeg by mutableFloatStateOf(0.0f)
        private set

    val sensorModeLabel: String = if (hasHardwareGyro) "HW GYRO" else "ACCEL"

    @Volatile var currentSpeedKmh: Float = 0.0f
    @Volatile var isLeftBlinkerActive: Boolean = false
    @Volatile var isRightBlinkerActive: Boolean = false
    @Volatile var isHapticVibrating: Boolean = false
    @Volatile var coachSmoothMode: Boolean = false

    var onAutoCancelBlinker: ((isLeft: Boolean) -> Unit)? = null
    var onMicroHapticFeedback: (() -> Unit)? = null

    private var leftBlinkerArmed = false
    private var rightBlinkerArmed = false
    private var lastHapticNotch = 0
    private var lastAccelTimestampNs = 0L

    var settings: ControllerSettings = ControllerSettings()
        set(value) {
            field = value
            filter.updateParameters(
                value.filterMinCutoff.coerceIn(0.4f, 2.5f),
                value.filterBeta.coerceIn(0.02f, 0.25f)
            )
        }

    fun start() {
        if (sensorThread != null) return
        val thread = HandlerThread("SteeringSensorThread", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorHandler = handler

        if (hasHardwareGyro) {
            rotationSensor?.let { registerSafe(it, handler) }
            gyroSensor?.let { registerSafe(it, handler) }
        }
        gravitySensor?.let { registerSafe(it, handler) }
        accelSensor?.let { registerSafe(it, handler) }
    }

    private fun registerSafe(sensor: Sensor, handler: Handler) {
        try {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
        } catch (_: SecurityException) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
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
        prevOmegaDegPerSec = 0.0f
        anchoredAngleDeg = 0.0f
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

    private fun computeScreenRollFromGravity(gx: Float, gy: Float): Float =
        Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat()

    private fun wrapAngle180(angle: Float): Float {
        var a = (angle + 180.0f) % 360.0f
        if (a < 0.0f) a += 360.0f
        return a - 180.0f
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val rawGx = event.values[0]
                val rawGy = event.values[1]
                val rawGz = event.values[2]
                val totalMag = sqrt(rawGx * rawGx + rawGy * rawGy + rawGz * rawGz)
                lastLinearAccelError = abs(totalMag - 9.81f)

                if ((!hasHardwareGyro || rotationSensor == null) && !hasDedicatedGravity) {
                    updateGravityHorizon(rawGx, rawGy, rawGz, isEkfFused = false, timestampNs = event.timestamp)
                }
            }

            Sensor.TYPE_GRAVITY -> {
                if (!hasHardwareGyro || rotationSensor == null) {
                    updateGravityHorizon(event.values[0], event.values[1], event.values[2], isEkfFused = true, timestampNs = event.timestamp)
                }
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                val gx = rotMatrix[6] * 9.81f
                val gy = rotMatrix[7] * 9.81f
                val gz = rotMatrix[8] * 9.81f
                updateGravityHorizon(gx, gy, gz, isEkfFused = true, timestampNs = event.timestamp)
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (!isCalibrated && hasGravitySample) calibrateCenter()

                val nowNs = event.timestamp
                val dt = if (lastGyroTimestampNs > 0L) {
                    ((nowNs - lastGyroTimestampNs) * 1e-9f).coerceIn(0.0005f, 0.05f)
                } else 0.008f
                lastGyroTimestampNs = nowNs

                val gx = filteredGravity[0]
                val gy = filteredGravity[1]
                val gz = filteredGravity[2]
                val gravMag = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1.0f)
                val screenPlaneMag = sqrt(gx * gx + gy * gy)

                val wx = event.values[0]
                val wy = event.values[1]
                val wz = event.values[2]
                val gravAxisRateRad = -(wx * gx + wy * gy + wz * gz) / gravMag
                val screenRateRad = -wz
                val screenWeight = (screenPlaneMag / gravMag).coerceIn(0.35f, 1.0f)
                val trueOmegaDeg = Math.toDegrees(
                    (screenWeight * screenRateRad + (1.0f - screenWeight) * gravAxisRateRad).toDouble()
                ).toFloat()

                val tiltRateDeg = Math.toDegrees(sqrt((wx * wx + wy * wy).toDouble())).toFloat()

                if (abs(trueOmegaDeg - gyroBiasDegPerSec) < 1.5f && tiltRateDeg < 3.5f && lastLinearAccelError < 0.7f) {
                    gyroBiasDegPerSec += (trueOmegaDeg - gyroBiasDegPerSec) * (0.8f * dt).coerceIn(0.0f, 0.03f)
                }

                var omegaClean = trueOmegaDeg - gyroBiasDegPerSec
                if (abs(omegaClean) < 0.18f) omegaClean = 0.0f

                val midpointOmega = 0.5f * (prevOmegaDegPerSec + omegaClean)
                prevOmegaDegPerSec = omegaClean
                fusedAngleDeg += midpointOmega * dt

                if (hasPrevGravRoll && screenPlaneMag > 2.5f) {
                    val turnOffset = ((fusedAngleDeg - continuousGravDeg) / 360.0f).roundToInt() * 360.0f
                    val alignedGravDeg = continuousGravDeg + turnOffset
                    val gravError = alignedGravDeg - fusedAngleDeg

                    val isShiftingGrip = (tiltRateDeg > 10.0f && tiltRateDeg > abs(omegaClean) * 0.6f) || lastLinearAccelError > 1.1f

                    if (isShiftingGrip) {
                        baselineRollDeg = wrapAngle180(baselineRollDeg + gravError * (4.0f * dt).coerceIn(0.0f, 0.25f))
                        continuousGravDeg = fusedAngleDeg
                    } else {
                        val isHandsSteady = abs(omegaClean) < 2.0f && tiltRateDeg < 4.5f && lastLinearAccelError < 0.6f
                        if (isHandsSteady && abs(fusedAngleDeg) < 9.0f) {
                            baselineRollDeg = wrapAngle180(baselineRollDeg + gravError * (1.2f * dt).coerceIn(0.0f, 0.05f))
                            val bleedRate = 0.45f * settings.autoZeroRecenterSensitivity
                            val snapBoost = if (settings.centerSnapAssistance && abs(fusedAngleDeg) < 3.0f) 2.2f else 1.0f
                            fusedAngleDeg *= (1.0f - (bleedRate * snapBoost * dt).coerceIn(0.0f, 0.05f))
                            continuousGravDeg = fusedAngleDeg
                        } else if (settings.gravityAnchorEnabled) {
                            val tiltGate = (1.0f - (tiltRateDeg / 30.0f)).coerceIn(0.0f, 1.0f)
                            val whipGate = (1.0f - (lastLinearAccelError / 1.2f)).coerceIn(0.0f, 1.0f)
                            val kp = if (abs(fusedAngleDeg) < 120.0f) 1.8f else 0.8f
                            fusedAngleDeg += (kp * gravError * tiltGate * whipGate * dt).coerceIn(-1.5f, 1.5f)
                            continuousGravDeg = alignedGravDeg
                        }
                    }
                }

                if (settings.casterSpringStrength > 0.01f && abs(currentSpeedKmh) > 10.0f && abs(omegaClean) < 4.0f) {
                    val speedFactor = (abs(currentSpeedKmh) / 80.0f).coerceIn(0.0f, 1.0f)
                    val maxStep = settings.casterSpringStrength * speedFactor * 38.0f * dt
                    fusedAngleDeg = if (abs(fusedAngleDeg) <= maxStep) 0.0f else fusedAngleDeg - sign(fusedAngleDeg) * maxStep
                    continuousGravDeg = fusedAngleDeg
                }

                processFinalSteeringAngle(fusedAngleDeg, nowNs)
            }
        }
    }

    private fun updateGravityHorizon(gxIn: Float, gyIn: Float, gzIn: Float, isEkfFused: Boolean, timestampNs: Long) {
        if (!hasGravitySample) {
            filteredGravity[0] = gxIn
            filteredGravity[1] = gyIn
            filteredGravity[2] = gzIn
            hasGravitySample = true
            if (!isCalibrated) calibrateCenter()
        } else if (isEkfFused) {
            filteredGravity[0] = gxIn
            filteredGravity[1] = gyIn
            filteredGravity[2] = gzIn
        } else {
            val dt = if (lastAccelTimestampNs > 0L) {
                ((timestampNs - lastAccelTimestampNs) * 1e-9f).coerceIn(0.002f, 0.05f)
            } else 0.015f
            val whipGate = (1.0f - (lastLinearAccelError / 3.5f)).coerceIn(0.05f, 1.0f)
            val tau = if (isHapticVibrating) 0.45f else (0.045f / whipGate)
            val alpha = (1.0f - exp((-dt / tau).toDouble()).toFloat()).coerceIn(0.01f, 0.45f)
            filteredGravity[0] += alpha * (gxIn - filteredGravity[0])
            filteredGravity[1] += alpha * (gyIn - filteredGravity[1])
            filteredGravity[2] += alpha * (gzIn - filteredGravity[2])
        }
        lastAccelTimestampNs = timestampNs

        val gx = filteredGravity[0]
        val gy = filteredGravity[1]
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
        }

        if (!hasHardwareGyro) {
            processFinalSteeringAngle(continuousGravDeg, timestampNs)
        }
    }

    private fun processFinalSteeringAngle(unfilteredAngleDeg: Float, timestampNs: Long) {
        val baseHalfLockDeg = if (settings.wheelMode1to1) {
            (settings.wheelDegreesRange / 2.0f).coerceAtLeast(30.0f)
        } else {
            settings.steeringMaxAngleDeg.coerceAtLeast(15.0f)
        }

        val cityBlend = ((50.0f - abs(currentSpeedKmh)) / 25.0f).coerceIn(0.0f, 1.0f)
        val halfLockDeg = baseHalfLockDeg * (1.0f - 0.22f * cityBlend)

        val maxTurnLimit = halfLockDeg + 10.0f
        fusedAngleDeg = fusedAngleDeg.coerceIn(-maxTurnLimit, maxTurnLimit)
        continuousGravDeg = continuousGravDeg.coerceIn(-maxTurnLimit, maxTurnLimit)

        val clampedTarget = unfilteredAngleDeg.coerceIn(-halfLockDeg, halfLockDeg)
        val stictionDeg = if (isHapticVibrating) 0.55f else if (abs(prevOmegaDegPerSec) < 2.0f) 0.22f else 0.0f
        val diff = clampedTarget - anchoredAngleDeg
        if (abs(diff) > stictionDeg) {
            anchoredAngleDeg = clampedTarget - sign(diff) * stictionDeg
        }

        val smoothedAngle = filter.filter(anchoredAngleDeg, timestampNs * 1e-9)
        val totalAngle = if (settings.invertSteering) -smoothedAngle else smoothedAngle
        rawAngleDeg = totalAngle

        normalizedSteering = SteeringMath.shapeAngle(
            totalAngleDeg = totalAngle,
            halfLockDeg = halfLockDeg,
            speedKmh = currentSpeedKmh,
            settings = settings,
            coachSmoothMode = coachSmoothMode
        )

        val absAngle = abs(totalAngle)

        if (settings.steeringHapticTension) {
            val currentNotch = (absAngle / 15.0f).toInt()
            if (currentNotch != lastHapticNotch) {
                lastHapticNotch = currentNotch
                if (currentNotch > 0) onMicroHapticFeedback?.invoke()
            }
        }

        if (settings.autoCancelBlinkers) {
            if (isLeftBlinkerActive) {
                if (totalAngle < -18.0f) leftBlinkerArmed = true
                else if (leftBlinkerArmed && totalAngle > -4.0f) {
                    leftBlinkerArmed = false
                    onAutoCancelBlinker?.invoke(true)
                }
            } else leftBlinkerArmed = false

            if (isRightBlinkerActive) {
                if (totalAngle > 18.0f) rightBlinkerArmed = true
                else if (rightBlinkerArmed && totalAngle < 4.0f) {
                    rightBlinkerArmed = false
                    onAutoCancelBlinker?.invoke(false)
                }
            } else rightBlinkerArmed = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}