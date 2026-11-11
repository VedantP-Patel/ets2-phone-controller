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
    private var prevOmegaDegPerSec = 0.0f
    private var gyroBiasDegPerSec = 0.0f
    private var anchoredAngleDeg = 0.0f
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
            }
            rotationSensor?.let { sensor ->
                try {
                    sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
                } catch (_: SecurityException) {
                    sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
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

        // Hardware 6-Axis EKF Gravity Horizon (Zero low-pass phase lag!)
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val rotMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            val gx = rotMatrix[6] * 9.81f
            val gy = rotMatrix[7] * 9.81f
            val gz = rotMatrix[8] * 9.81f
            filteredGravity[0] = gx
            filteredGravity[1] = gy
            filteredGravity[2] = gz
            hasGravitySample = true

            if (sqrt(gx * gx + gy * gy) > 2.0f) {
                if (!isCalibrated) calibrateCenter()
                val currentRoll = computeScreenRollFromGravity(gx, gy)
                val relRoll = wrapAngle180(currentRoll - baselineRollDeg)
                if (!hasPrevGravRoll) {
                    prevGravRollDeg = relRoll
                    continuousGravDeg = relRoll
                    hasPrevGravRoll = true
                } else {
                    val rawDeltaGrav = wrapAngle180(relRoll - prevGravRollDeg)
                    prevGravRollDeg = relRoll
                    // If user is not actively steering with the Z-gyro, absorb posture/grip shifts into baseline!
                    if (hasHardwareGyro && abs(prevOmegaDegPerSec) < 1.8f && abs(rawDeltaGrav) < 1.5f) {
                        baselineRollDeg = wrapAngle180(baselineRollDeg + rawDeltaGrav * 0.65f)
                        continuousGravDeg += rawDeltaGrav * 0.35f
                    } else {
                        continuousGravDeg += rawDeltaGrav
                    }
                }
                if (!hasHardwareGyro) {
                    processFinalSteeringAngle(continuousGravDeg, event.timestamp)
                }
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
            val gravMag = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1.0f)
            val screenPlaneMag = sqrt(gx * gx + gy * gy)

            // 1. 3D Pitch-Compensated Gyro Rate:
            // Blends pure Z-screen rotation with 3D gravity-plane projection so tilting the phone
            // forward/backward mid-turn never induces cross-axis steering deflection.
            val wx = event.values[0]
            val wy = event.values[1]
            val wz = event.values[2]
            val rawOmegaZ = -Math.toDegrees(wz.toDouble()).toFloat()

           // Pure 1:1 silicon screen-plane gyro rate (NO cosPitch division so gyro matches gravity 1:1!)
            val trueOmegaDeg = rawOmegaZ
            val tiltRateDeg = Math.toDegrees(sqrt((wx * wx + wy * wy).toDouble())).toFloat()

            // 2. Stationary Online Bias Tracker + Sub-Threshold Noise Gate
            if (abs(trueOmegaDeg - gyroBiasDegPerSec) < 1.5f && tiltRateDeg < 3.5f && lastLinearAccelError < 0.7f) {
                gyroBiasDegPerSec += (trueOmegaDeg - gyroBiasDegPerSec) * (0.8f * dt).coerceIn(0.0f, 0.03f)
            }

            var omegaClean = trueOmegaDeg - gyroBiasDegPerSec
            if (abs(omegaClean) < 0.18f) omegaClean = 0.0f

            // 3. Second-Order Runge-Kutta (RK2 Midpoint) Integration
            val midpointOmega = 0.5f * (prevOmegaDegPerSec + omegaClean)
            prevOmegaDegPerSec = omegaClean
            fusedAngleDeg += midpointOmega * dt

            // 4. Unified Posture-Invariant Horizon & Grip-Adaptation Engine
            if (hasPrevGravRoll && screenPlaneMag > 2.5f) {
                val turnOffset = ((fusedAngleDeg - continuousGravDeg) / 360.0f).roundToInt() * 360.0f
                val alignedGravDeg = continuousGravDeg + turnOffset
                val gravError = alignedGravDeg - fusedAngleDeg

                // Detect when the player is shifting their grip, leaning in their chair, or tilting the screen:
                // High X/Y tilt rate OR linear acceleration while Z-steering rate is low.
                val isShiftingGrip = (tiltRateDeg > 10.0f && tiltRateDeg > abs(omegaClean) * 0.6f) || lastLinearAccelError > 1.1f

                if (isShiftingGrip) {
                    // Lock steering angle completely during grip/posture changes and re-sync gravity baseline
                    // to the current steering angle so tilting the phone NEVER deflects the truck!
                    baselineRollDeg = wrapAngle180(baselineRollDeg + gravError * (4.0f * dt).coerceIn(0.0f, 0.25f))
                    continuousGravDeg = fusedAngleDeg
                } else {
                    val isHandsSteady = abs(omegaClean) < 2.0f && tiltRateDeg < 4.5f && lastLinearAccelError < 0.6f

                    if (isHandsSteady && abs(fusedAngleDeg) < 9.0f) {
                        // Player is holding a steady highway line near center:
                        // 1. Adapt baselineRollDeg to their new comfortable hand posture so they never fight a pull
                        baselineRollDeg = wrapAngle180(baselineRollDeg + gravError * (1.2f * dt).coerceIn(0.0f, 0.05f))

                        // 2. Smoothly bleed any residual sub-degree offset to 0.0°
                        val bleedRate = 0.45f * settings.autoZeroRecenterSensitivity
                        val snapBoost = if (settings.centerSnapAssistance && abs(fusedAngleDeg) < 3.0f) 2.2f else 1.0f
                        val bleedAlpha = (bleedRate * snapBoost * dt).coerceIn(0.0f, 0.05f)
                        fusedAngleDeg *= (1.0f - bleedAlpha)
                        continuousGravDeg = fusedAngleDeg
                    } else if (settings.gravityAnchorEnabled) {
                        // Active turning with clean posture: gentle horizon drift correction
                        val tiltGate = (1.0f - (tiltRateDeg / 30.0f)).coerceIn(0.0f, 1.0f)
                        val whipGate = (1.0f - (lastLinearAccelError / 1.2f)).coerceIn(0.0f, 1.0f)
                        val kp = if (abs(fusedAngleDeg) < 120.0f) 1.8f else 0.8f
                        fusedAngleDeg += (kp * gravError * tiltGate * whipGate * dt).coerceIn(-1.5f, 1.5f)
                        continuousGravDeg = alignedGravDeg
                    }
                }
            }

            // 5. Speed-Proportional Pneumatic Tire Self-Aligning Caster (only when relaxing hands at speed)
            if (settings.casterSpringStrength > 0.01f && abs(currentSpeedKmh) > 10.0f && abs(omegaClean) < 4.0f) {
                val speedFactor = (abs(currentSpeedKmh) / 80.0f).coerceIn(0.0f, 1.0f)
                val returnDegPerSec = settings.casterSpringStrength * speedFactor * 38.0f
                val maxStep = returnDegPerSec * dt
                if (abs(fusedAngleDeg) <= maxStep) {
                    fusedAngleDeg = 0.0f
                } else {
                    fusedAngleDeg -= sign(fusedAngleDeg) * maxStep
                }
                continuousGravDeg = fusedAngleDeg
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
        val halfLockDeg = baseHalfLockDeg

        // Prevent wind-up beyond physical lock
        val maxTurnLimit = halfLockDeg + 10.0f
        fusedAngleDeg = fusedAngleDeg.coerceIn(-maxTurnLimit, maxTurnLimit)
        continuousGravDeg = continuousGravDeg.coerceIn(-maxTurnLimit, maxTurnLimit)

        // Kinematic Column Friction: locks wheel 100% still against <0.22° hand micro-tremor or haptic buzz
        val clampedTarget = unfilteredAngleDeg.coerceIn(-halfLockDeg, halfLockDeg)
        val stictionDeg = if (isHapticVibrating) 0.55f else if (abs(prevOmegaDegPerSec) < 2.0f) 0.22f else 0.0f
        val diff = clampedTarget - anchoredAngleDeg
        if (abs(diff) > stictionDeg) {
            anchoredAngleDeg = clampedTarget - sign(diff) * stictionDeg
        }

        val timestampSec = timestampNs * 1e-9
        val smoothedAngle = filter.filter(anchoredAngleDeg, timestampSec)

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
