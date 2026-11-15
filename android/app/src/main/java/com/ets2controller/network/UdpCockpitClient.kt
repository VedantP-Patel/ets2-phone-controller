package com.ets2controller.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

data class TruckTelemetryV2(
    val speedKmh: Float = 0f,
    val engineRpm: Float = 0f,
    val maxRpm: Float = 2500f,
    val airPressureBar: Float = 8.5f,
    val gear: Int = 0,
    val forwardGears: Int = 12,
    val fuelPercent: Int = 100,
    val adbluePercent: Int = 100,
    val speedLimitKmh: Int = 0,
    val cruiseSpeedKmh: Int = 0,
    val truckDamagePct: Int = 0,
    val retarderLevel: Int = 0,
    val accDistanceLevel: Int = 0,
    val lateralG: Float = 0f,
    val longitudinalG: Float = 0f,
    val dashboardFlags: Int = 0,
    val safetyFlags: Int = 0,
    val routeDistanceKm: Int = 0,
    val routeTimeMin: Int = 0,
    val roundTripPingMs: Int = 0
) {
    val isLeftBlinker: Boolean get() = (dashboardFlags and (1 shl 0)) != 0
    val isRightBlinker: Boolean get() = (dashboardFlags and (1 shl 1)) != 0
    val isHazard: Boolean get() = (dashboardFlags and (1 shl 2)) != 0
    val isParkBrake: Boolean get() = (dashboardFlags and (1 shl 3)) != 0
    val isEngineRunning: Boolean get() = (dashboardFlags and (1 shl 4)) != 0
    val isLowAir: Boolean get() = (dashboardFlags and (1 shl 5)) != 0
    val isCruiseActive: Boolean get() = (dashboardFlags and (1 shl 6)) != 0
    val isRetarderActive: Boolean get() = (dashboardFlags and (1 shl 7)) != 0
    val isEngineBrakeActive: Boolean get() = (dashboardFlags and (1 shl 8)) != 0
    val isLowBeams: Boolean get() = (dashboardFlags and (1 shl 9)) != 0
    val isHighBeams: Boolean get() = (dashboardFlags and (1 shl 10)) != 0
    val isBeacons: Boolean get() = (dashboardFlags and (1 shl 11)) != 0
    val isWipersActive: Boolean get() = (dashboardFlags and (1 shl 12)) != 0
    val isDiffLock: Boolean get() = (dashboardFlags and (1 shl 13)) != 0
    val isTruckLiftAxle: Boolean get() = (dashboardFlags and (1 shl 14)) != 0
    val isTrailerLiftAxle: Boolean get() = (dashboardFlags and (1 shl 15)) != 0

    val isTrailerConnected: Boolean get() = (safetyFlags and (1 shl 0)) != 0
    val isBlindSpotLeft: Boolean get() = (safetyFlags and (1 shl 1)) != 0
    val isBlindSpotRight: Boolean get() = (safetyFlags and (1 shl 2)) != 0
    val isCollisionWarning: Boolean get() = (safetyFlags and (1 shl 3)) != 0
    val isLkaActive: Boolean get() = (safetyFlags and (1 shl 4)) != 0
    val isParkLights: Boolean get() = (safetyFlags and (1 shl 6)) != 0
}

class UdpCockpitClient(
    context: Context,
    private val onTelemetryUpdate: (TruckTelemetryV2) -> Unit,
    private val onHapticPulse: (large: Int, small: Int) -> Unit,
    private val onConnectionState: (connected: Boolean, hostIp: String, pps: Int) -> Unit
) {
    companion object {
        const val MAGIC: Short = 0x4532
        const val VERSION: Byte = 2
        const val PORT_CONTROL = 5005
        const val PORT_BEACON = 5006
        const val UPLINK_SIZE = 29
        const val DOWNLINK_SIZE = 42
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("ets2_cockpit_net", Context.MODE_PRIVATE)
    private var multicastLock: WifiManager.MulticastLock? = null

    private val running = AtomicBoolean(false)
    private val targetHost = AtomicReference<InetAddress?>(null)
    private val lastTelemetryRxMs = AtomicLong(0L)

    private val heldButtons = AtomicLong(0L)
    private val pulseExpiryMap = ConcurrentHashMap<Int, Long>()

    @Volatile var steeringProvider: () -> Float = { 0f }
    @Volatile var throttle: Float = 0f
    @Volatile var brake: Float = 0f
    @Volatile var clutch: Float = 0f
    @Volatile var lookX: Float = 0f
    @Volatile var lookY: Float = 0f
    @Volatile var coachSmoothMode: Boolean = false

    private var smoothedThrottle = 0f
    private var smoothedBrake = 0f

    private var txThread: Thread? = null
    private var rxThread: Thread? = null
    private var beaconThread: Thread? = null

    init {
        val savedIp = prefs.getString("last_pc_ip", null)
        if (!savedIp.isNullOrBlank()) {
            parseIpv4Literal(savedIp)?.let { targetHost.set(it) }
        }
    }

    private fun parseIpv4Literal(ip: String): InetAddress? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            bytes[i] = v.toByte()
        }
        return try {
            InetAddress.getByAddress(bytes)
        } catch (_: Exception) {
            null
        }
    }

    fun getSavedIp(): String = prefs.getString("last_pc_ip", "") ?: ""

    fun setManualHost(ip: String) {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove("last_pc_ip").apply()
            targetHost.set(null)
            onConnectionState(false, "", 0)
            return
        }
        val addr = parseIpv4Literal(trimmed) ?: return
        targetHost.set(addr)
        prefs.edit().putString("last_pc_ip", trimmed).apply()
        val alive = (SystemClock.elapsedRealtime() - lastTelemetryRxMs.get()) < 2000L
        onConnectionState(alive, trimmed, 0)
    }

    fun setButtonHeld(bitIndex: Int, pressed: Boolean) {
        if (bitIndex !in 0..63) return
        val mask = 1L shl bitIndex
        while (true) {
            val cur = heldButtons.get()
            val next = if (pressed) (cur or mask) else (cur and mask.inv())
            if (heldButtons.compareAndSet(cur, next)) break
        }
    }

    fun triggerPulse(bitIndex: Int, durationMs: Long = 150L) {
    if (bitIndex !in 0..63) return
    pulseExpiryMap[bitIndex] = SystemClock.elapsedRealtime() + durationMs
}

    fun start() {
        if (!running.compareAndSet(false, true)) return

        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("ets2_cockpit_beacon")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}

        val socket = DatagramSocket().apply {
            soTimeout = 250
            reuseAddress = true
            broadcast = true
        }

        beaconThread = Thread({
            var bSock: DatagramSocket? = null
            try {
                bSock = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT_BEACON))
                    soTimeout = 400
                }
                val buf = ByteArray(64)
                val pkt = DatagramPacket(buf, buf.size)
                while (running.get()) {
                    try {
                        bSock.receive(pkt)
                        val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        if (msg.startsWith("ETS2_COCKPIT_V2")) {
                            val current = targetHost.get()
                            val rxAlive = (SystemClock.elapsedRealtime() - lastTelemetryRxMs.get()) < 1500L
                            if (current == null || !rxAlive) {
                                targetHost.set(pkt.address)
                                pkt.address.hostAddress?.let {
                                    prefs.edit().putString("last_pc_ip", it).apply()
                                }
                            }
                        }
                    } catch (_: SocketTimeoutException) {}
                }
            } catch (_: Exception) {
            } finally {
                bSock?.close()
            }
        }, "CockpitBeaconThread").apply { start() }

        rxThread = Thread({
            val buf = ByteArray(128)
            val pkt = DatagramPacket(buf, buf.size)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

            while (running.get()) {
                try {
                    socket.receive(pkt)
                    if (pkt.length == DOWNLINK_SIZE) {
                        bb.rewind()
                        val magic = bb.short
                        val ver = bb.get()
                        if (magic == MAGIC && ver == VERSION && verifyXor(buf, DOWNLINK_SIZE)) {
                            lastTelemetryRxMs.set(SystemClock.elapsedRealtime())
                            val prev = targetHost.getAndSet(pkt.address)
                            if (prev?.hostAddress != pkt.address.hostAddress) {
                                pkt.address.hostAddress?.let {
                                    prefs.edit().putString("last_pc_ip", it).apply()
                                }
                            }

                            val safety = bb.get().toInt() and 0xFF
                            val speed = bb.float
                            val rpm = bb.float
                            val maxRpm = bb.float
                            val air = bb.float
                            val gear = bb.get().toInt()
                            val fwdGears = bb.get().toInt() and 0xFF
                            val fuel = bb.get().toInt() and 0xFF
                            val adblue = bb.get().toInt() and 0xFF
                            val limit = bb.get().toInt() and 0xFF
                            val cruise = bb.get().toInt() and 0xFF
                            val damage = bb.get().toInt() and 0xFF
                            val retarder = bb.get().toInt() and 0xFF
                            val accDist = bb.get().toInt() and 0xFF
                            val latG = bb.get().toInt() / 100f
                            val longG = bb.get().toInt() / 100f
                            val rLarge = bb.get().toInt() and 0xFF
                            val rSmall = bb.get().toInt() and 0xFF
                            val dashFlags = bb.short.toInt() and 0xFFFF
                            val distKm = bb.short.toInt() and 0xFFFF
                            val timeMin = bb.short.toInt() and 0xFFFF
                            val echoTs = bb.short.toInt() and 0xFFFF

                            val now16 = (SystemClock.elapsedRealtime() and 0xFFFFL).toInt()
                            val rtt = (now16 - echoTs) and 0xFFFF

                            onTelemetryUpdate(
                                TruckTelemetryV2(
                                    speedKmh = speed, engineRpm = rpm, maxRpm = maxRpm,
                                    airPressureBar = air, gear = gear, forwardGears = fwdGears,
                                    fuelPercent = fuel, adbluePercent = adblue,
                                    speedLimitKmh = limit, cruiseSpeedKmh = cruise,
                                    truckDamagePct = damage, retarderLevel = retarder,
                                    accDistanceLevel = accDist, lateralG = latG, longitudinalG = longG,
                                    dashboardFlags = dashFlags, safetyFlags = safety,
                                    routeDistanceKm = distKm, routeTimeMin = timeMin,
                                    roundTripPingMs = rtt.coerceIn(0, 999)
                                )
                            )

                            val coachJerkAlert = if (coachSmoothMode && (abs(latG) > 0.25f || abs(longG) > 0.30f)) 140 else 0
                            onHapticPulse(max(rLarge, coachJerkAlert), rSmall)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {}
            }
        }, "CockpitRxThread").apply { start() }

        txThread = Thread({
            val txArray = ByteArray(UPLINK_SIZE)
            val bb = ByteBuffer.wrap(txArray).order(ByteOrder.LITTLE_ENDIAN)
            val pkt = DatagramPacket(txArray, UPLINK_SIZE)
            var seq = 0
            var ppsCounter = 0
            var lastStatsMs = SystemClock.elapsedRealtime()
            var lastProbeMs = 0L

            while (running.get()) {
                val loopStart = SystemClock.elapsedRealtime()
                val host = targetHost.get()
                val telemetryAlive = (loopStart - lastTelemetryRxMs.get()) < 2000L

                var effectiveMask = heldButtons.get()
                val iter = pulseExpiryMap.entries.iterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    if (loopStart < entry.value) effectiveMask = effectiveMask or (1L shl entry.key)
                    else iter.remove()
                }

                val rawGas = throttle.coerceIn(0f, 1f)
                val rawBrk = brake.coerceIn(0f, 1f)
                if (coachSmoothMode) {
                    val maxStep = 0.025f
                    smoothedThrottle += (rawGas - smoothedThrottle).coerceIn(-maxStep, maxStep)
                    smoothedBrake += (rawBrk - smoothedBrake).coerceIn(-maxStep * 1.4f, maxStep * 1.4f)
                } else {
                    smoothedThrottle = rawGas
                    smoothedBrake = rawBrk
                }

                bb.clear()
                bb.putShort(MAGIC)
                bb.put(VERSION)
                bb.put(if (coachSmoothMode) 1 else 0)
                bb.putShort((seq++ and 0xFFFF).toShort())
                bb.putShort((loopStart and 0xFFFFL).toShort())
                bb.putLong(effectiveMask)
                bb.putShort((steeringProvider().coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                bb.putShort((smoothedThrottle * 65535f).toInt().toShort())
                bb.putShort((smoothedBrake * 65535f).toInt().toShort())
                bb.putShort((clutch.coerceIn(0f, 1f) * 65535f).toInt().toShort())
                bb.putShort((lookX.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                bb.putShort((lookY.coerceIn(-1f, 1f) * 32767f).toInt().toShort())

                var cs: Byte = 0
                for (i in 0 until (UPLINK_SIZE - 1)) cs = (cs.toInt() xor txArray[i].toInt()).toByte()
                bb.put(cs)

                if (host != null) {
                    try {
                        pkt.address = host
                        pkt.port = PORT_CONTROL
                        socket.send(pkt)
                        ppsCounter++
                    } catch (_: Exception) {}
                }

                // Active /24 subnet discovery probe every 1.2s when not yet receiving telemetry
                if (!telemetryAlive && (loopStart - lastProbeMs) > 1200L) {
                    lastProbeMs = loopStart
                    for (candidate in getDiscoveryCandidates()) {
                        try {
                            pkt.address = candidate
                            pkt.port = PORT_CONTROL
                            socket.send(pkt)
                        } catch (_: Exception) {}
                    }
                }

                if (loopStart - lastStatsMs >= 400L) {
                    val hostStr = host?.hostAddress ?: ""
                    onConnectionState(telemetryAlive, hostStr, (ppsCounter * 1000L / 400L).toInt())
                    ppsCounter = 0
                    lastStatsMs = loopStart
                }

                val elapsed = SystemClock.elapsedRealtime() - loopStart
                if (elapsed < 10L) {
                    try { Thread.sleep(10L - elapsed) } catch (_: InterruptedException) {}
                }
            }
            socket.close()
        }, "CockpitTxThread").apply { start() }
    }

    private fun getDiscoveryCandidates(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address) {
                        ia.broadcast?.let { list.add(it) }
                        val base = addr.address.copyOf()
                        val selfLastOctet = base[3].toInt() and 0xFF
                        for (hostOctet in 1..254) {
                            if (hostOctet == selfLastOctet) continue
                            base[3] = hostOctet.toByte()
                            list.add(InetAddress.getByAddress(base.clone()))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun stop() {
        running.set(false)
        txThread?.join(300)
        rxThread?.join(300)
        beaconThread?.join(300)
        txThread = null
        rxThread = null
        beaconThread = null
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
    }

    private fun verifyXor(buf: ByteArray, len: Int): Boolean {
        var cs = 0
        for (i in 0 until (len - 1)) cs = cs xor (buf[i].toInt() and 0xFF)
        return cs == (buf[len - 1].toInt() and 0xFF)
    }
}