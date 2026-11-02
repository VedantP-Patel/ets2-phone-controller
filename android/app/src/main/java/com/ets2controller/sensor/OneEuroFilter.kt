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
    private var mincutoff: Float = 1.8f,
    private var beta: Float = 0.04f,
    private val dcutoff: Float = 2.5f
) {
    private val xFilter = LowPassFilter(computeAlpha(mincutoff))
    private val dxFilter = LowPassFilter(computeAlpha(dcutoff))
    private var lastRawX: Float = 0.0f
    private var lastTime: Double = 0.0
    private var hasLastTime: Boolean = false

    fun updateParameters(mincutoff: Float, beta: Float) {
        this.mincutoff = mincutoff.coerceIn(0.5f, 10.0f)
        this.beta = beta.coerceIn(0.005f, 0.5f)
    }

    private fun computeAlpha(cutoff: Float): Float {
        val te = 1.0f / freq.coerceIn(20.0f, 500.0f)
        val tau = 1.0f / (2.0f * PI.toFloat() * cutoff.coerceAtLeast(0.1f))
        return 1.0f / (1.0f + tau / te)
    }

    fun filter(x: Float, timestampSec: Double): Float {
        if (hasLastTime) {
            val elapsed = (timestampSec - lastTime).coerceIn(0.001, 0.05)
            // Smooth frequency estimate so OS scheduling jitter doesn't spike rawDx
            val instantFreq = (1.0 / elapsed).toFloat()
            freq = 0.8f * freq + 0.2f * instantFreq
        }
        lastTime = timestampSec
        hasLastTime = true

        // True instantaneous derivative from raw input delta (zero lag feedback)
        val rawDx = if (xFilter.hasValue) (x - lastRawX) * freq else 0.0f
        lastRawX = x

        val dx = rawDx.coerceIn(-500.0f, 500.0f)
        val edx = dxFilter.filter(dx, computeAlpha(dcutoff))

        val cutoff = (mincutoff + beta * abs(edx)).coerceIn(mincutoff, 25.0f)
        return xFilter.filter(x, computeAlpha(cutoff))
    }

    fun reset() {
        xFilter.reset()
        dxFilter.reset()
        lastRawX = 0.0f
        hasLastTime = false
        lastTime = 0.0
    }
}