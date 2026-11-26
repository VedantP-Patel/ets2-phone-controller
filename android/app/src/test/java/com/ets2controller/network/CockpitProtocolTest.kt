package com.ets2controller.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CockpitProtocolTest {

    @Test
    fun packControlPacketProducesValid29ByteFrameWithXorChecksum() {
        val buf = ByteArray(CockpitProtocol.UPLINK_SIZE)
        CockpitProtocol.packControlPacket(
            out = buf,
            flags = 0x03,
            sequenceId = 42,
            clientTimestampMs = 1234,
            buttonMask = (1L shl 5) or (1L shl 40),
            steering = -0.5f,
            throttle = 1.0f,
            brake = 0.25f,
            clutch = 0.0f,
            lookX = 0.5f,
            lookY = -0.25f
        )

        assertTrue(CockpitProtocol.verifyXor(buf, CockpitProtocol.UPLINK_SIZE))
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(CockpitProtocol.MAGIC, bb.short)
        assertEquals(CockpitProtocol.VERSION, bb.get())
        assertEquals(0x03.toByte(), bb.get())
        assertEquals(42.toShort(), bb.short)
        assertEquals(1234.toShort(), bb.short)
        assertEquals((1L shl 5) or (1L shl 40), bb.long)
    }

    @Test
    fun decodeTelemetryPacketValidatesChecksumAndComputesWrappedRtt() {
        val buf = ByteArray(CockpitProtocol.DOWNLINK_SIZE)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(CockpitProtocol.MAGIC)
        bb.put(CockpitProtocol.VERSION)
        bb.put((1 shl 5).toByte()) // SAFETY_AUTO_TRANSMISSION
        bb.putFloat(82.5f)         // speedKmh
        bb.putFloat(1450f)         // engineRpm
        bb.putFloat(2200f)         // maxRpm
        bb.putFloat(8.2f)          // airPressureBar
        bb.put(12.toByte())        // gear
        bb.put(12.toByte())        // forwardGears
        bb.put(78.toByte())        // fuelPercent
        bb.put(91.toByte())        // adbluePercent
        bb.put(80.toByte())        // speedLimitKmh
        bb.put(80.toByte())        // cruiseSpeedKmh
        bb.put(3.toByte())         // truckDamagePct
        bb.put(2.toByte())         // retarderLevel
        bb.put(1.toByte())         // accDistanceLevel
        bb.put(15.toByte())        // lateralG100 -> 0.15
        bb.put((-20).toByte())     // longitudinalG100 -> -0.20
        bb.put(180.toByte())       // rumbleLarge
        bb.put(45.toByte())        // rumbleSmall
        bb.putShort(0x0041)        // dashboardFlags
        bb.putShort(320)           // routeDistanceKm
        bb.putShort(240)           // routeTimeMin
        bb.putShort(65530.toShort()) // echoTimestampMs (near 16-bit wrap)
        bb.putShort(92)            // oilTempC
        bb.putShort(84)            // waterTempC
        bb.put(244.toByte())       // batteryVolts10 -> 24.4V
        buf[CockpitProtocol.DOWNLINK_SIZE - 1] =
            CockpitProtocol.computeXor(buf, CockpitProtocol.DOWNLINK_SIZE - 1)

        // Wrap-around check: nowElapsedMs = 65542 -> (65542 & 0xFFFF) = 6, rtt = (6 - 65530) & 0xFFFF = 12 ms
        val decoded = CockpitProtocol.decodeTelemetryPacket(buf, CockpitProtocol.DOWNLINK_SIZE, 65542L)
        assertNotNull(decoded)
        assertEquals(82.5f, decoded!!.telemetry.speedKmh, 0.01f)
        assertEquals(12, decoded.telemetry.roundTripPingMs)
        assertEquals(180, decoded.rumbleLarge)
        assertEquals(45, decoded.rumbleSmall)
        assertEquals(92, decoded.telemetry.oilTempC)
        assertEquals(84, decoded.telemetry.waterTempC)
        assertEquals(24.4f, decoded.telemetry.batteryVolts, 0.05f)
        assertTrue((decoded.telemetry.safetyFlags and (1 shl 5)) != 0)

        // Corrupt a byte -> checksum should reject
        buf[10] = (buf[10].toInt() xor 0xFF).toByte()
        assertNull(CockpitProtocol.decodeTelemetryPacket(buf, CockpitProtocol.DOWNLINK_SIZE, 65542L))
    }
}
