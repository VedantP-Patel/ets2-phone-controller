package com.ets2controller.network

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class UdpTransmitter {
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 5005

    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    private var sequenceId: Short = 0
    private val packetBuffer = ByteArray(12)
    private val reusablePacket = DatagramPacket(packetBuffer, packetBuffer.size)
    private val byteBuffer = ByteBuffer.wrap(packetBuffer).order(ByteOrder.LITTLE_ENDIAN)
    private val seqTimestamps = LongArray(1024)

    fun getRttForSeq(seq: Int): Int {
        val sentTime = seqTimestamps[seq and 1023]
        if (sentTime <= 0L) return 0
        val rtt = (SystemClock.elapsedRealtime() - sentTime).toInt()
        return if (rtt in 0..1000) rtt else 0
    }

    // Thread-safe controller input states
    @Volatile var currentSteering: Float = 0.0f
    @Volatile var currentThrottle: Float = 0.0f
    @Volatile var currentBrake: Float = 0.0f
    @Volatile var currentLookX: Float = 0.0f // -1.0f (Look Left) to +1.0f (Look Right)
    @Volatile var currentFlags: Byte = 0

    // Button pulse extension: guarantees every tap is sent in at least 15 UDP packets (150ms)
    // so Wi-Fi packet drops NEVER cause missed button presses
    private var rawButtons: Long = 0L
    private val buttonHoldUntil = LongArray(64) { 0L }
    private val buttonLock = Any()

    @Volatile var packetsSentPerSec: Int = 0
        private set

    fun setButton(bitIndex: Int, isPressed: Boolean) {
        if (bitIndex !in 0..62) return
        val now = SystemClock.elapsedRealtime()
        synchronized(buttonLock) {
            if (isPressed) {
                rawButtons = rawButtons or (1L shl bitIndex)
                // Give Air Horn (bit 23) and Neutral (bit 17) a 600ms hold window if tapped/swiped
                val holdMs = if (bitIndex == 23 || bitIndex == 17) 600L else 150L
                buttonHoldUntil[bitIndex] = now + holdMs
            } else {
                rawButtons = rawButtons and (1L shl bitIndex).inv()
            }
        }
    }

    fun getEffectiveButtons(): Int {
        return getEffectiveButtonsLong().toInt()
    }

    private fun getEffectiveButtonsLong(): Long {
        val now = SystemClock.elapsedRealtime()
        synchronized(buttonLock) {
            var effective = rawButtons
            for (i in 0 until 63) {
                if (now < buttonHoldUntil[i]) {
                    effective = effective or (1L shl i)
                }
            }
            return effective
        }
    }

    fun connect(ip: String, port: Int) {
        if (ip.isBlank()) return
        disconnect()
        targetPort = port
        isRunning.set(true)

        workerThread = Thread({
            try {
                targetAddress = InetAddress.getByName(ip.trim())
                socket = DatagramSocket().apply {
                    trafficClass = 0x10
                }
                var ppsCounter = 0
                var lastStatsTime = SystemClock.elapsedRealtime()

                while (isRunning.get()) {
                    val loopStart = SystemClock.elapsedRealtime()

                    sendCurrentState()
                    ppsCounter++

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStatsTime >= 1000) {
                        packetsSentPerSec = ppsCounter
                        ppsCounter = 0
                        lastStatsTime = now
                    }

                    val elapsed = now - loopStart
                    val sleepMs = 8L - elapsed
                    if (sleepMs > 0) {
                        try {
                            Thread.sleep(sleepMs)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, "UdpTransmitterThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun disconnect() {
        isRunning.set(false)
        workerThread?.interrupt()
        workerThread = null
        socket?.close()
        socket = null
        targetAddress = null
    }

    private fun sendCurrentState() {
        val sock = socket ?: return
        val addr = targetAddress ?: return

        val activeButtons = getEffectiveButtonsLong()

        val steer = (currentSteering * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
        val gas = (currentThrottle * 65535.0f).toInt().coerceIn(0, 65535).toShort()
        // Pack 10-bit brake (0..1023) in bits 0..9 + 6-bit cabin look X (-31..+31) in bits 10..15
        val brake10 = (currentBrake.coerceIn(0.0f, 1.0f) * 1023.0f).toInt().coerceIn(0, 1023)
        val look6 = if (kotlin.math.abs(currentLookX) < 0.05f) {
            0
        } else {
            (currentLookX.coerceIn(-1.0f, 1.0f) * 31.0f).toInt().coerceIn(-31, 31) and 0x3F
        }
        val brake = (brake10 or (look6 shl 10)).toShort()
        val btns = (activeButtons and 0xFFFFL).toShort()

        // Lower 3 bits of flags: direct hold/pulse bits
        val radioPrev = if ((activeButtons and (1L shl 16)) != 0L) (1 shl 0) else 0
        val shiftNeutral = if ((activeButtons and (1L shl 17)) != 0L) (1 shl 1) else 0
        val airHorn = if ((activeButtons and (1L shl 23)) != 0L) (1 shl 2) else 0

        // Upper 5 bits of flags (bits 3..7): Encoded Command ID (1..20) for bits 18..34
        var extCmdId = 0
        for (bit in 18..34) {
            if (bit == 23) continue // Air Horn handled in bit 2
            if ((activeButtons and (1L shl bit)) != 0L) {
                extCmdId = (bit - 17) // Maps bit 18 -> 1, 19 -> 2, ..., 34 -> 17
                break
            }
        }

        val flags = (currentFlags.toInt() or radioPrev or shiftNeutral or airHorn or (extCmdId shl 3)).toByte()

        synchronized(byteBuffer) {
            byteBuffer.clear()
            byteBuffer.putShort(sequenceId)
            byteBuffer.putShort(btns)
            byteBuffer.putShort(steer)
            byteBuffer.putShort(gas)
            byteBuffer.putShort(brake)
            byteBuffer.put(flags)

            // Calculate XOR checksum across bytes 0..10
            var checksum: Byte = 0
            for (i in 0..10) {
                checksum = (checksum.toInt() xor packetBuffer[i].toInt()).toByte()
            }
            byteBuffer.put(checksum)
            val currentSeq = sequenceId.toInt() and 0xFFFF
            seqTimestamps[currentSeq and 1023] = SystemClock.elapsedRealtime()
            sequenceId++
        }

        try {
            reusablePacket.address = addr
            reusablePacket.port = targetPort
            sock.send(reusablePacket)
        } catch (_: Exception) {
            // UDP send dropped, non-fatal
        }
    }
}
