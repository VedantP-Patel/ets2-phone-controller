package com.ets2controller.sensor

import kotlin.math.PI
import kotlin.math.abs

class LowPassFilter(private var alpha: Float = 1.0f) {
    private var s: Float = 0.0f
    var hasValue: Boolean = false
        private set

    val lastValue: Float
        get() = s

    fun filter(value: Float, alpha: Float): Float {
        this.alpha = alpha
        s = if (!hasValue) {
            hasValue = true
            value
        } else {
            alpha * value + (1.0f - alpha) * s
        }
        return s
    }

    fun reset() {
        hasValue = false
        s = 0.0f
    }
}

class OneEuroFilter(
    private var freq: Float = 100.0f,
    private var mincutoff: Float = 0.6f,
    private var beta: Float = 0.008f,
    private val dcutoff: Float = 1.0f
) {
    private val xFilter = LowPassFilter(computeAlpha(mincutoff))
    private val dxFilter = LowPassFilter(computeAlpha(dcutoff))
    private var lastTime: Double = 0.0
    private var hasLastTime: Boolean = false

    fun updateParameters(mincutoff: Float, beta: Float) {
        this.mincutoff = mincutoff.coerceAtLeast(0.1f)
        this.beta = beta.coerceAtLeast(0.0001f)
    }

    private fun computeAlpha(cutoff: Float): Float {
        val te = 1.0f / freq.coerceIn(10.0f, 200.0f)
        val tau = 1.0f / (2.0f * PI.toFloat() * cutoff.coerceAtLeast(0.1f))
        return 1.0f / (1.0f + tau / te)
    }

    fun filter(x: Float, timestampSec: Double): Float {
        if (hasLastTime) {
            val elapsed = (timestampSec - lastTime).coerceIn(0.002, 0.05)
            freq = (1.0 / elapsed).toFloat()
        }
        lastTime = timestampSec
        hasLastTime = true

        val rawDx = if (xFilter.hasValue) (x - xFilter.lastValue) * freq else 0.0f
        // Clamp derivative rate of change to ±300°/s to prevent numerical derivative blowouts
        val dx = rawDx.coerceIn(-300.0f, 300.0f)
        val edx = dxFilter.filter(dx, computeAlpha(dcutoff))

        // Cap cutoff frequency between mincutoff and 12Hz (eliminates human hand tremor noise)
        val cutoff = (mincutoff + beta * abs(edx)).coerceIn(mincutoff, 12.0f)
        return xFilter.filter(x, computeAlpha(cutoff))
    }

    fun reset() {
        xFilter.reset()
        dxFilter.reset()
        hasLastTime = false
        lastTime = 0.0
    }
}

