package com.ets2controller.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PedalMathTest {

    @Test
    fun absolutePedalHasRestBottomDeadbandAndTopSaturation() {
        val h = 200f
        // Bottom 10% rest pad returns 0.0
        assertEquals(0f, PedalMath.mapAbsolutePosition(195f, h), 1e-5f)
        assertEquals(0f, PedalMath.mapAbsolutePosition(180f, h), 1e-5f)

        // Top 12% saturation returns 1.0
        assertEquals(1f, PedalMath.mapAbsolutePosition(24f, h), 1e-5f)
        assertEquals(1f, PedalMath.mapAbsolutePosition(0f, h), 1e-5f)

        // Mid-point applies gentle progressive feathering (< 0.50)
        val mid = PedalMath.mapAbsolutePosition(102f, h)
        assertTrue("Midpoint should feather gently between 0.35 and 0.49: $mid", mid in 0.35f..0.49f)
    }

    @Test
    fun relativePedalTracksUpwardDragAndClamps() {
        assertEquals(0f, PedalMath.mapRelativeDelta(startY = 150f, currentY = 165f, activeTravelPx = 100f), 1e-5f)
        assertEquals(1f, PedalMath.mapRelativeDelta(startY = 150f, currentY = 30f, activeTravelPx = 100f), 1e-5f)
        val half = PedalMath.mapRelativeDelta(startY = 150f, currentY = 100f, activeTravelPx = 100f)
        assertTrue("Half upward drag should be modulated ~0.45: $half", half in 0.40f..0.50f)
    }

    @Test
    fun slideCurvePreservesFineCenterPrecisionAndHitsFullLock() {
        assertEquals(0f, slideCurve(0f), 1e-5f)
        assertEquals(0.025f, slideCurve(0.05f), 1e-4f)
        assertEquals(1f, slideCurve(1f), 1e-5f)
        assertEquals(-1f, slideCurve(-1f), 1e-5f)
    }
}
