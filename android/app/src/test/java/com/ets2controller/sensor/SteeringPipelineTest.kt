package com.ets2controller.sensor

import com.ets2controller.config.ControllerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringPipelineTest {

    @Test
    fun oneEuroFilterSmoothsStaticJitterAndTracksFastTurns() {
        val filter = OneEuroFilter(freq = 100f, mincutoff = 0.4f, beta = 0.06f)
        // Small tremor around 0 deg should stay attenuated
        var out = 0f
        for (i in 1..20) {
            val tremor = if (i % 2 == 0) 0.3f else -0.3f
            out = filter.filter(tremor, i * 0.01)
        }
        assertTrue("Static tremor should remain well damped: $out", kotlin.math.abs(out) < 0.25f)

        // Rapid turn to 35 deg should track responsively
        for (i in 21..45) {
            out = filter.filter(35.0f, i * 0.01)
        }
        assertTrue("Fast turn should converge toward target: $out", out > 30.0f)
    }

    @Test
    fun steeringMathAppliesSingleSpeedAttenuationAndSpeedSensitiveDeadzone() {
        val settings = ControllerSettings(
            steeringDeadzoneDeg = 2.0f,
            steeringCurvePower = 1.0f,
            speedSensitivity = 0.4f,
            speedSensitiveDeadzone = true,
            centerSnapAssistance = false
        )

        // At 0 km/h, effective deadzone is 0.45 * 2.0 = 0.9 deg, so 1.2 deg produces output
        val lowSpeedSmallAngle = SteeringMath.shapeAngle(1.2f, 45f, 0f, settings, coachSmoothMode = false)
        assertTrue("Low-speed small angle should pass reduced deadzone", lowSpeedSmallAngle > 0f)

        // At 120 km/h, effective deadzone is 2.0 deg, so 1.2 deg is suppressed
        val highSpeedSmallAngle = SteeringMath.shapeAngle(1.2f, 45f, 120f, settings, coachSmoothMode = false)
        assertEquals(0f, highSpeedSmallAngle, 1e-5f)

        // Full lock at 120 km/h uses single speedScale = 1 - 0.4 * 0.45 = 0.82
        val fullLockHighSpeed = SteeringMath.shapeAngle(45f, 45f, 120f, settings, coachSmoothMode = false)
        assertEquals(0.82f, fullLockHighSpeed, 1e-3f)
    }
}
