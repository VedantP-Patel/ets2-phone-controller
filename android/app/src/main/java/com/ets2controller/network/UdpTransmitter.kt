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
    @Volatile var currentFlags: Byte = 0

    // Button pulse extension: guarantees every tap is sent in at least 15 UDP packets (150ms)
    // so Wi-Fi packet drops NEVER cause missed button presses
    private var rawButtons: Int = 0
    private val buttonHoldUntil = LongArray(32) { 0L }
    private val buttonLock = Any()

    @Volatile var packetsSentPerSec: Int = 0
        private set

    fun setButton(bitIndex: Int, isPressed: Boolean) {
        if (bitIndex !in 0..31) return
        val now = SystemClock.elapsedRealtime()
        synchronized(buttonLock) {
            if (isPressed) {
                rawButtons = rawButtons or (1 shl bitIndex)
                buttonHoldUntil[bitIndex] = now + 150L // Minimum 150ms transmission guarantee
            } else {
                rawButtons = rawButtons and (1 shl bitIndex).inv()
            }
        }
    }

    fun getEffectiveButtons(): Int {
        val now = SystemClock.elapsedRealtime()
        synchronized(buttonLock) {
            var effective = rawButtons
            for (i in 0 until 32) {
                if (now < buttonHoldUntil[i]) {
                    effective = effective or (1 shl i)
                }
            }
            return effective
        }
    }

    fun connect(ip: String, port: Int) {
        disconnect()
        try {
            targetPort = port
            targetAddress = InetAddress.getByName(ip)
            socket = DatagramSocket()
            isRunning.set(true)

            // Dedicated transmission loop running at ~100Hz (every 10ms)
            workerThread = Thread({
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
                    val sleepMs = 10L - elapsed
                    if (sleepMs > 0) {
                        try {
                            Thread.sleep(sleepMs)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }, "UdpTransmitterThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
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

        val activeButtons = getEffectiveButtons()

        val steer = (currentSteering * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
        val gas = (currentThrottle * 65535.0f).toInt().coerceIn(0, 65535).toShort()
        val brake = (currentBrake * 65535.0f).toInt().coerceIn(0, 65535).toShort()
        val btns = (activeButtons and 0xFFFF).toShort()

        // Bit 16 in activeButtons maps to flags bit 0 (RADIO PREV)
        val radioPrev = if ((activeButtons and (1 shl 16)) != 0) (1 shl 0) else 0
        // Bit 17 in activeButtons maps to flags bit 1 (SHIFT TO NEUTRAL)
        val shiftNeutral = if ((activeButtons and (1 shl 17)) != 0) (1 shl 1) else 0
        // Auxiliary Heavy Truck Switchboard: Bits 18..23 map to flags bits 2..7
        val diffLock = if ((activeButtons and (1 shl 18)) != 0) (1 shl 2) else 0
        val liftAxle = if ((activeButtons and (1 shl 19)) != 0) (1 shl 3) else 0
        val trailerHook = if ((activeButtons and (1 shl 20)) != 0) (1 shl 4) else 0
        val highBeams = if ((activeButtons and (1 shl 21)) != 0) (1 shl 5) else 0
        val beacons = if ((activeButtons and (1 shl 22)) != 0) (1 shl 6) else 0
        val airHorn = if ((activeButtons and (1 shl 23)) != 0) (1 shl 7) else 0

        val flags = (currentFlags.toInt() or radioPrev or shiftNeutral or diffLock or liftAxle or trailerHook or highBeams or beacons or airHorn).toByte()

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
            val packet = DatagramPacket(packetBuffer, packetBuffer.size, addr, targetPort)
            sock.send(packet)
        } catch (_: Exception) {
            // UDP send dropped, non-fatal
        }
    }
}
