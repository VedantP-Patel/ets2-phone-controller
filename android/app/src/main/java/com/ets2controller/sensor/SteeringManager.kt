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
import kotlin.math.sign
import kotlin.math.sqrt

class SteeringManager(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // Hardware sensor detection:
    // Check for a true hardware gyroscope vs. virtual/missing gyro
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val hasHardwareGyro: Boolean = gyroSensor != null && !gyroSensor.name.contains("Virtual", ignoreCase = true)

    // Gravity / Accelerometer provides absolute, zero-drift horizon reference
    // independent of room yaw (chair rotation) and forward/backward screen pitch!
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Fallback rotation vector if needed
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    // Low-pass filtered gravity vector [gx, gy, gz] in device coordinates
    private val filteredGravity = FloatArray(3) { if (it == 0) 9.81f else 0f }
    private var hasGravitySample = false

    // Calibrated horizon baseline angle in the screen X-Y plane (degrees)
    private var baselineRollDeg = 0.0f
    private var isCalibrated = false

    // Continuous unwrapped steering angle (supports multi-turn ±450°..±900°)
    private var fusedAngleDeg = 0.0f
    private var prevGravRollDeg = 0.0f
    private var continuousGravDeg = 0.0f
    private var hasPrevGravRoll = false

    // Timestamps for exact hardware integration
    private var lastGyroTimestampNs = 0L

    // Responsive 1-Euro Filter (eliminates hand tremor while keeping <12ms latency)
    private val filter = OneEuroFilter(100f, 1.8f, 0.04f, 2.5f)

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
                value.filterMinCutoff.coerceAtLeast(1.8f),
                value.filterBeta.coerceAtLeast(0.04f)
            )
        }

    fun start() {
        if (sensorThread == null) {
            val thread = HandlerThread("SteeringSensorThread", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
            sensorThread = thread
            val handler = Handler(thread.looper)
            sensorHandler = handler

            gravitySensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
            }
            if (hasHardwareGyro && gyroSensor != null) {
                sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            } else {
                rotationSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
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

    /**
     * Computes pure steering roll angle in the phone's X-Y screen plane [-180, +180].
     * Turning the phone clockwise (steering right) increases +gy relative to +gx.
     * Completely immune to chair rotation (yaw) and forward/backward screen tilt (pitch gz)!
     */
    private fun computeScreenRollFromGravity(gx: Float, gy: Float): Float {
        return Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat()
    }

    private fun wrapAngle180(angle: Float): Float {
        var a = angle
        while (a > 180.0f) a -= 360.0f
        while (a < -180.0f) a += 360.0f
        return a
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        // 1. Update absolute Earth Gravity Vector (Screen-Plane Horizon Reference)
        if (event.sensor.type == Sensor.TYPE_GRAVITY || event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val rawGx = event.values[0]
            val rawGy = event.values[1]
            val rawGz = event.values[2]

            if (!hasGravitySample) {
                filteredGravity[0] = rawGx
                filteredGravity[1] = rawGy
                filteredGravity[2] = rawGz
                hasGravitySample = true
                if (!isCalibrated) {
                    calibrateCenter()
                }
            } else {
                // If vibrating from haptic feedback, use a tighter low-pass filter to reject motor buzz
                val alpha = if (isHapticVibrating) 0.03f else 0.18f
                filteredGravity[0] += alpha * (rawGx - filteredGravity[0])
                filteredGravity[1] += alpha * (rawGy - filteredGravity[1])
                filteredGravity[2] += alpha * (rawGz - filteredGravity[2])
            }

            val gx = filteredGravity[0]
            val gy = filteredGravity[1]
            val screenPlaneMag = sqrt(gx * gx + gy * gy)

            // Only update gravity roll when phone is held upright enough (not lying completely flat on a table)
            if (screenPlaneMag > 1.8f) {
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

            // If this phone has NO physical gyro (or uses a Virtual Gyro), drive steering directly from gravity
            if (!hasHardwareGyro) {
                processFinalSteeringAngle(continuousGravDeg, event.timestamp)
            }
            return
        }

        // 2. High-Speed Hardware Gyroscope Integration + Continuous Gravity Drift Anchor
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            if (!isCalibrated && hasGravitySample) {
                calibrateCenter()
            }

            val nowNs = event.timestamp
            val dt = if (lastGyroTimestampNs > 0L) {
                ((nowNs - lastGyroTimestampNs) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
            } else {
                0.005f
            }
            lastGyroTimestampNs = nowNs

            // event.values[2] is angular velocity around the +Z screen normal axis (rad/s).
            // Counter-clockwise is positive +Z, so clockwise (steering right) is -values[2].
            val omegaZDegPerSec = -Math.toDegrees(event.values[2].toDouble()).toFloat()

            // Integrate pure screen-normal rotation
            fusedAngleDeg += omegaZDegPerSec * dt

            // Complementary Filter Drift Correction:
            // Continuously pulls any gyro bias/posture shift back toward the true gravity horizon!
            val gx = filteredGravity[0]
            val gy = filteredGravity[1]
            val screenPlaneMag = sqrt(gx * gx + gy * gy)

            if (settings.gravityAnchorEnabled && hasPrevGravRoll && screenPlaneMag > 2.2f) {
                // Find the gravity angle in the same multi-turn revolution as fusedAngleDeg
                val gravError = wrapAngle180(continuousGravDeg - fusedAngleDeg)

                // Strongest drift correction inside the normal driving range (|angle| < 120°),
                // smoothly tapering during rapid turns so gyro stays 100% crisp.
                val speedGate = (1.0f - (abs(omegaZDegPerSec) / 180.0f)).coerceIn(0.15f, 1.0f)
                val correctionRate = if (abs(fusedAngleDeg) < 120.0f) 2.5f else 0.6f
                val alphaComplement = (correctionRate * speedGate * dt).coerceIn(0.0f, 0.08f)

                fusedAngleDeg += gravError * alphaComplement
            }

            processFinalSteeringAngle(fusedAngleDeg, nowNs)
        }
    }

    private fun processFinalSteeringAngle(unfilteredAngleDeg: Float, timestampNs: Long) {
        val halfLockDeg = if (settings.wheelMode1to1) {
            (settings.wheelDegreesRange / 2.0f).coerceAtLeast(30.0f)
        } else {
            settings.steeringMaxAngleDeg.coerceAtLeast(10.0f)
        }

        // Clamp continuous angle so over-rotating past full lock doesn't wind up
        val maxTurnLimit = (halfLockDeg + 25.0f).coerceAtLeast(180.0f)
        fusedAngleDeg = fusedAngleDeg.coerceIn(-maxTurnLimit, maxTurnLimit)
        continuousGravDeg = continuousGravDeg.coerceIn(-maxTurnLimit, maxTurnLimit)

        val timestampSec = timestampNs / 1_000_000_000.0
        val smoothedAngle = filter.filter(unfilteredAngleDeg.coerceIn(-maxTurnLimit, maxTurnLimit), timestampSec)

        val totalAngle = if (settings.invertSteering) -smoothedAngle else smoothedAngle
        rawAngleDeg = totalAngle

        val deadzone = settings.steeringDeadzoneDeg
        val absAngle = abs(totalAngle)
        val speedFactor = (currentSpeedKmh / 90.0f).coerceIn(0.0f, 1.0f)

        var steerVal = 0.0f
        if (absAngle <= deadzone) {
            steerVal = 0.0f
        } else {
            val blendWindow = 1.0f
            val linearEffective = ((absAngle - deadzone) / (halfLockDeg - deadzone).coerceAtLeast(1.0f)).coerceIn(0.0f, 1.0f)

            val effective = if (absAngle < deadzone + blendWindow) {
                val u = ((absAngle - deadzone) / blendWindow).coerceIn(0.0f, 1.0f)
                val smooth = u * u * u * (u * (u * 6.0f - 15.0f) + 10.0f)
                linearEffective * smooth
            } else {
                linearEffective
            }

            val casterCurveBoost = settings.casterSpringStrength.coerceIn(0.0f, 1.0f) * 0.35f
            val dynamicCurve = if (settings.wheelMode1to1) {
                1.0f + (0.20f * speedFactor) + (casterCurveBoost * 0.5f)
            } else {
                settings.steeringCurvePower + (0.35f * speedFactor) + casterCurveBoost
            }

            steerVal = sign(totalAngle) * if (dynamicCurve == 1.0f) effective else effective.pow(dynamicCurve)
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

        // Auto-Cancelling Turn Indicators (ACTI)
        if (settings.autoCancelBlinkers) {
            if (isLeftBlinkerActive) {
                if (totalAngle < -20.0f) {
                    leftBlinkerArmed = true
                } else if (leftBlinkerArmed && totalAngle > -5.0f) {
                    leftBlinkerArmed = false
                    onAutoCancelBlinker?.invoke(true)
                }
            } else {
                leftBlinkerArmed = false
            }

            if (isRightBlinkerActive) {
                if (totalAngle > 20.0f) {
                    rightBlinkerArmed = true
                } else if (rightBlinkerArmed && totalAngle < 5.0f) {
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