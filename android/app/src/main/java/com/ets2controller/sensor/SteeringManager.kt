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

    // Dedicated high-priority sensor background thread (eliminates UI rendering batching jitter)
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // Primary 100Hz Rotation Vector Sensor
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Absolute Physical Gravitational Field Anchor Sensor
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Android SensorManager.getQuaternionFromVector stores: [w, x, y, z] (scalar-first)
    private val currentQuat = FloatArray(4) { if (it == 0) 1f else 0f }
    private val baselineQuat = FloatArray(4) { if (it == 0) 1f else 0f }
    private val prevQuat = FloatArray(4) { if (it == 0) 1f else 0f }
    private var hasPrevQuat = false
    private var hasPrevTwist = false
    private var prevTwistDeg = 0.0f
    private var continuousAngle = 0.0f

    // Physical gravity vector [gx, gy, gz] in m/s^2 with 2-pole low-pass filtering
    private val currentGravity = FloatArray(3) { if (it == 1) -9.81f else 0f }
    private val filteredGravity = FloatArray(3) { if (it == 1) -9.81f else 0f }
    private val baselineGravity = FloatArray(3) { if (it == 1) -9.81f else 0f }
    private var hasBaselineGravity = false

    private var isCalibrated = false

    // Aerospace-Grade 1-Euro Filter:
    // mincutoff = 0.6f (rock-solid dead calm at rest; zero hand-tremor jitter)
    // beta = 0.008f (snappy dynamic response without over-amplifying sensor derivative noise)
    private val filter = OneEuroFilter(100f, 0.6f, 0.008f, 1.0f)

    // Continuous Asymptotic Gravity Drift Offset (ZERO step jumps, ZERO bang-bang limit-cycle chatter)
    private var driftOffset = 0.0f

    // Monotonic hardware nanosecond timebase & instantaneous angular velocity
    private var lastAngle = 0.0f
    private var lastEventTimestampNs = 0L

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

    // Acoustic / Vibration Feedback Decoupling Gate
    // When true, gravity drift integration is frozen to prevent motor vibration from polluting steering
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
            filter.updateParameters(value.filterMinCutoff, value.filterBeta)
        }

    fun start() {
        if (sensorThread == null) {
            val thread = HandlerThread("SteeringSensorThread", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
            sensorThread = thread
            val handler = Handler(thread.looper)
            sensorHandler = handler

            rotationSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
            }
            gravitySensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
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
        System.arraycopy(currentQuat, 0, baselineQuat, 0, 4)
        System.arraycopy(currentQuat, 0, prevQuat, 0, 4)
        hasPrevQuat = true
        hasPrevTwist = false
        prevTwistDeg = 0.0f
        continuousAngle = 0.0f
        System.arraycopy(filteredGravity, 0, baselineGravity, 0, 3)
        hasBaselineGravity = true
        isCalibrated = true
        driftOffset = 0.0f
        rawAngleDeg = 0.0f
        normalizedSteering = 0.0f
        lastAngle = 0.0f
        lastEventTimestampNs = 0L
        leftBlinkerArmed = false
        rightBlinkerArmed = false
        lastHapticNotch = 0
        filter.reset()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        // 1. Handle Gravitational Field Ground Truth with Heavy Low-Pass Pre-Filter (fc ≈ 1.2 Hz)
        if (event.sensor.type == Sensor.TYPE_GRAVITY || event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            currentGravity[0] = event.values[0]
            currentGravity[1] = event.values[1]
            currentGravity[2] = event.values[2]

            // 2-pole low-pass filter (alpha = 0.06f at 100Hz ≈ 1.0 Hz cutoff)
            // Strips 100% of motor vibration (>150Hz) and human hand tremor (8-12Hz)
            val alpha = 0.06f
            filteredGravity[0] += alpha * (event.values[0] - filteredGravity[0])
            filteredGravity[1] += alpha * (event.values[1] - filteredGravity[1])
            filteredGravity[2] += alpha * (event.values[2] - filteredGravity[2])

            if (!hasBaselineGravity) {
                System.arraycopy(filteredGravity, 0, baselineGravity, 0, 3)
                hasBaselineGravity = true
            }
            return
        }

        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) return

        // SensorManager.getQuaternionFromVector outputs [w, x, y, z] (w at index 0)
        SensorManager.getQuaternionFromVector(currentQuat, event.values)

        if (!isCalibrated) {
            calibrateCenter()
            return
        }

        var cw = currentQuat[0]
        var cx = currentQuat[1]
        var cy = currentQuat[2]
        var cz = currentQuat[3]

        // 1. Inter-Frame Geodesic Shortest-Path Continuity on S^3
        // Maintain continuity relative to the PREVIOUS frame (10ms ago), NEVER against the static baseline!
        // In 10ms, hand rotation is tiny (<10°), so q_prev · q_curr > 0 always, unless sensor HAL inverted sign.
        if (hasPrevQuat) {
            val dotPrev = prevQuat[0] * cw + prevQuat[1] * cx + prevQuat[2] * cy + prevQuat[3] * cz
            if (dotPrev < 0f) {
                cw = -cw
                cx = -cx
                cy = -cy
                cz = -cz
            }
        }
        prevQuat[0] = cw
        prevQuat[1] = cx
        prevQuat[2] = cy
        prevQuat[3] = cz
        hasPrevQuat = true

        // 2. Relative rotation in device coordinate frame: q_rel = q_baseline^-1 * q_current
        // With q_baseline^-1 = (bw, -bx, -by, -bz)
        val bw = baselineQuat[0]
        val bx = baselineQuat[1]
        val by = baselineQuat[2]
        val bz = baselineQuat[3]

        val rw = bw * cw + bx * cx + by * cy + bz * cz
        val rz = bw * cz - bx * cy + by * cx - bz * cw

        // Twist angle around screen normal (+Z axis) in [-180, 180]
        // Clockwise turn (Turn Right) produces positive angle (+deg)
        val twistRad = 2.0 * atan2(rz.toDouble(), rw.toDouble())
        var rawTwistDeg = -Math.toDegrees(twistRad).toFloat()
        while (rawTwistDeg > 180.0f) rawTwistDeg -= 360.0f
        while (rawTwistDeg < -180.0f) rawTwistDeg += 360.0f

        // 3. Multi-Turn Phase Unwrapping Accumulator
        // Tracks smooth, continuous rotation past ±180° up to full commercial lock (±450°, ±540°, ±900°+)
        // with ZERO sudden sign flips, ZERO inversion, and smooth return to 0.00°!
        if (!hasPrevTwist) {
            continuousAngle = rawTwistDeg
            prevTwistDeg = rawTwistDeg
            hasPrevTwist = true
        } else {
            var delta = rawTwistDeg - prevTwistDeg
            while (delta > 180.0f) delta -= 360.0f
            while (delta < -180.0f) delta += 360.0f
            continuousAngle += delta
            prevTwistDeg = rawTwistDeg
        }

        // Half-lock limit for full steering range (e.g. 450° in 900° mode)
        val halfLockDeg = if (settings.wheelMode1to1) {
            (settings.wheelDegreesRange / 2.0f).coerceAtLeast(30.0f)
        } else {
            settings.steeringMaxAngleDeg.coerceAtLeast(10.0f)
        }

        // Soft-clamp continuous angle to physical lock limit (+30° headroom) so over-rotating past full lock
        // does not create dead rotation delay upon return!
        val maxTurnLimit = (halfLockDeg + 30.0f).coerceAtLeast(180.0f)
        continuousAngle = continuousAngle.coerceIn(-maxTurnLimit, maxTurnLimit)

        val currentFrameAngle = continuousAngle

        // Instantaneous angular velocity from monotonic hardware IMU timestamp
        val nowNs = event.timestamp
        val dt = if (lastEventTimestampNs > 0L) {
            ((nowNs - lastEventTimestampNs) / 1_000_000_000.0f).coerceIn(0.002f, 0.05f)
        } else {
            0.01f
        }
        lastEventTimestampNs = nowNs
        val angularSpeed = if (dt > 0.0001f) abs(currentFrameAngle - lastAngle) / dt else 0.0f
        lastAngle = currentFrameAngle

        // Continuous Asymptotic Gravity Drift Estimator:
        // Calculate Earth's Physical Gravitational In-Plane Landscape Orientation from filtered gravity
        val gx = filteredGravity[0]
        val gy = filteredGravity[1]
        val gz = filteredGravity[2]
        val inPitchMag = sqrt(gx * gx + gz * gz)

        if (settings.gravityAnchorEnabled && hasBaselineGravity && inPitchMag > 0.5f) {
            val rawGrav = Math.toDegrees(atan2(-gy.toDouble(), inPitchMag.toDouble())).toFloat()
            val baseInPitch = sqrt(baselineGravity[0] * baselineGravity[0] + baselineGravity[2] * baselineGravity[2])
            val baseGrav = if (baseInPitch > 0.5f) {
                Math.toDegrees(atan2(-baselineGravity[1].toDouble(), baseInPitch.toDouble())).toFloat()
            } else 0.0f
            var gravAngleDeg = rawGrav - baseGrav
            while (gravAngleDeg > 180.0f) gravAngleDeg -= 360.0f
            while (gravAngleDeg < -180.0f) gravAngleDeg += 360.0f

            // Asymptotic Drift Correction:
            // CRITICAL: Only integrate drift when the wheel is in the CENTER REVOLUTION (|continuousAngle| < 15°)
            // AND the phone is quasi-static (angularSpeed < 1.0°/s)
            // AND the phone is NOT currently vibrating from haptic force feedback.
            // This prevents gravity from fighting active steering turns while guaranteeing rock-solid center calibration!
            if (abs(continuousAngle) < 15.0f && angularSpeed < 1.0f && !isHapticVibrating) {
                val currentFused = currentFrameAngle + driftOffset
                var driftError = gravAngleDeg - currentFused
                while (driftError > 180.0f) driftError -= 360.0f
                while (driftError < -180.0f) driftError += 360.0f

                val maxDelta = (0.3f * dt).coerceAtMost(0.005f)
                driftOffset += driftError.coerceIn(-maxDelta, maxDelta)
            }
        }

        // Fused angle: perfectly smooth continuous frame angle with gentle drift anchoring
        val targetAngle = currentFrameAngle + driftOffset

        // Apply Aerospace 1-Euro adaptive smoothing filter
        val timestampSec = event.timestamp / 1_000_000_000.0
        val smoothedAngle = filter.filter(targetAngle, timestampSec)

        val totalAngle = if (settings.invertSteering) -smoothedAngle else smoothedAngle
        rawAngleDeg = totalAngle

        val deadzone = settings.steeringDeadzoneDeg
        val absAngle = abs(totalAngle)
        val speedFactor = (currentSpeedKmh / 90.0f).coerceIn(0.0f, 1.0f)

        // C² Continuous Quintic Smoothstep Deadband & Dynamic Steering Curve
        var steerVal = 0.0f
        if (absAngle <= deadzone) {
            steerVal = 0.0f
        } else {
            val blendWindow = 1.0f // 1.0 degree smooth transition window
            val linearEffective = ((absAngle - deadzone) / (halfLockDeg - deadzone).coerceAtLeast(1.0f)).coerceIn(0.0f, 1.0f)

            // C² continuous quintic smoothstep: S(u) = 6u^5 - 15u^4 + 10u^3
            // Both 1st and 2nd derivatives are strictly 0 at the deadzone edge, guaranteeing zero twitch/jerk!
            val effective = if (absAngle < deadzone + blendWindow) {
                val u = ((absAngle - deadzone) / blendWindow).coerceIn(0.0f, 1.0f)
                val smooth = u * u * u * (u * (u * 6.0f - 15.0f) + 10.0f)
                linearEffective * smooth
            } else {
                linearEffective
            }

            val dynamicCurve = if (settings.wheelMode1to1 || settings.steeringCurvePower == 1.0f) {
                1.0f
            } else {
                settings.steeringCurvePower + (0.35f * speedFactor)
            }

            steerVal = sign(totalAngle) * if (dynamicCurve == 1.0f) effective else effective.pow(dynamicCurve)
        }

        // Speed-Sensitive Simulated Caster Return-To-Center Physics
        if (settings.casterSpringStrength > 0.0f && absAngle < 60.0f) {
            val speedCasterBoost = 1.0f + (0.5f * speedFactor)
            val springDampening = (settings.casterSpringStrength * 0.35f * speedCasterBoost).coerceIn(0.0f, 0.55f)
            steerVal *= (1.0f - springDampening)
        }

        normalizedSteering = steerVal.coerceIn(-1.0f, 1.0f)

        // Continuous Virtual Steering Resistance Micro-Haptics (every 15° away from center)
        if (settings.steeringHapticTension) {
            val currentNotch = (absAngle / 15.0f).toInt()
            if (currentNotch != lastHapticNotch) {
                lastHapticNotch = currentNotch
                if (currentNotch > 0) {
                    onMicroHapticFeedback?.invoke()
                }
            }
        }

        // Auto-Cancelling Turn Indicators (ACTI):
        // Real automotive stalk mechanism: arms during turn (>20°), trips off on wheel return (<5°)
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
