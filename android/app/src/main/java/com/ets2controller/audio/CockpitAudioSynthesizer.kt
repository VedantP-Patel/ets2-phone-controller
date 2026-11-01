package com.ets2controller.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class CockpitAudioSynthesizer {
    var isEnabled: Boolean = true

    private val sampleRate = 44100
    private val executor = Executors.newSingleThreadExecutor()
    private val random = Random()

    // Pre-synthesized PCM 16-bit sound buffers
    private val relayTickBuffer: ShortArray by lazy { synthesizeRelayTick(isAlt = false) }
    private val relayTockBuffer: ShortArray by lazy { synthesizeRelayTick(isAlt = true) }
    private val airBrakeHissBuffer: ShortArray by lazy { synthesizeAirBrakeHiss() }
    private val shifterThunkBuffer: ShortArray by lazy { synthesizeShifterThunk() }
    private val airHornBuffer: ShortArray by lazy { synthesizeAirHornBlast() }

    private var tickToggle = false

    fun playIndicatorTick() {
        if (!isEnabled) return
        executor.execute {
            tickToggle = !tickToggle
            val buffer = if (tickToggle) relayTickBuffer else relayTockBuffer
            playPcmBuffer(buffer)
        }
    }

    fun playAirBrakeHiss() {
        if (!isEnabled) return
        executor.execute {
            playPcmBuffer(airBrakeHissBuffer)
        }
    }

    fun playShifterThunk() {
        if (!isEnabled) return
        executor.execute {
            playPcmBuffer(shifterThunkBuffer)
        }
    }

    fun playAirHornBlast() {
        if (!isEnabled) return
        executor.execute {
            playPcmBuffer(airHornBuffer)
        }
    }

    private fun playPcmBuffer(buffer: ShortArray) {
        try {
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(buffer, 0, buffer.size)
            audioTrack.play()
            Thread.sleep((buffer.size * 1000L / sampleRate) + 20L)
            audioTrack.release()
        } catch (_: Exception) {}
    }

    // 1. Synthesize Relay Mechanical Tick / Tock (12ms resonant pulse)
    private fun synthesizeRelayTick(isAlt: Boolean): ShortArray {
        val numSamples = (sampleRate * 0.012).toInt() // 12ms
        val buffer = ShortArray(numSamples)
        val f1 = if (isAlt) 1100.0 else 1350.0
        val f2 = if (isAlt) 680.0 else 820.0

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val envelope = exp(-t * 400.0) // Fast exponential decay
            val wave = (sin(2.0 * PI * f1 * t) * 0.7 + sin(2.0 * PI * f2 * t) * 0.3) * envelope
            buffer[i] = (wave * 26000.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return buffer
    }

    // 2. Synthesize Pneumatic Air Brake Release Hiss (400ms shaped noise)
    private fun synthesizeAirBrakeHiss(): ShortArray {
        val numSamples = (sampleRate * 0.40).toInt() // 400ms
        val buffer = ShortArray(numSamples)

        var filterState = 0.0
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val whiteNoise = (random.nextDouble() * 2.0 - 1.0)

            // Resonant low-pass filter
            filterState = filterState * 0.85 + whiteNoise * 0.15

            // Initial puff punch + smooth exponential tail release
            val attack = (t / 0.015).coerceAtMost(1.0)
            val decay = exp(-t * 9.0)
            val envelope = attack * decay

            val sample = filterState * envelope * 28000.0
            buffer[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
        }
        return buffer
    }

    // 3. Synthesize Transmission Shifter Knock / Gate Thunk (28ms low transient)
    private fun synthesizeShifterThunk(): ShortArray {
        val numSamples = (sampleRate * 0.028).toInt() // 28ms
        val buffer = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val envelope = exp(-t * 180.0)
            val wave = sin(2.0 * PI * 85.0 * t) * envelope
            buffer[i] = (wave * 30000.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return buffer
    }

    // 4. Synthesize Dual Roof Heavy Air Horn Blast (320ms twin-trumpet harmonic chord)
    private fun synthesizeAirHornBlast(): ShortArray {
        val numSamples = (sampleRate * 0.32).toInt() // 320ms
        val buffer = ShortArray(numSamples)
        val f1 = 310.0 // Low trumpet
        val f2 = 370.0 // High trumpet

        var airHissState = 0.0
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val attack = (t / 0.02).coerceAtMost(1.0)
            val release = ((0.32 - t) / 0.05).coerceIn(0.0, 1.0)
            val envelope = attack * release

            // Primary twin trumpet fundamentals + overtones
            val hornTone = 0.45 * sin(2.0 * PI * f1 * t) +
                           0.40 * sin(2.0 * PI * f2 * t) +
                           0.20 * sin(2.0 * PI * (f1 * 2) * t) +
                           0.15 * sin(2.0 * PI * (f2 * 2) * t)

            // Pneumatic air rush turbulence
            val noise = (random.nextDouble() * 2.0 - 1.0)
            airHissState = airHissState * 0.8 + noise * 0.2

            val combined = (hornTone * 0.9 + airHissState * 0.1) * envelope
            buffer[i] = (combined * 29000.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return buffer
    }
}

