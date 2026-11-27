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

    @Test
    fun packUiCommandAndDecodeRadioRowValidateChecksums() {
        val cmdPkt = CockpitProtocol.packUiCommand(UiNavCommand.DELIVERY_OPT_1, 3)
        assertEquals(CockpitProtocol.UI_CMD_SIZE, cmdPkt.size)
        assertTrue(CockpitProtocol.verifyXor(cmdPkt, cmdPkt.size))
        val cmdBb = ByteBuffer.wrap(cmdPkt).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(CockpitProtocol.MAGIC_UI_CMD, cmdBb.short)
        assertEquals(UiNavCommand.DELIVERY_OPT_1, cmdBb.get())
        assertEquals(3.toByte(), cmdBb.get())

        val payload = "TruckersFM\tSim radio\tEN\thttps://radio.truckers.fm/".toByteArray(Charsets.UTF_8)
        val radioBuf = ByteArray(CockpitProtocol.RADIO_HEADER_SIZE + payload.size + 1)
        val rbb = ByteBuffer.wrap(radioBuf).order(ByteOrder.LITTLE_ENDIAN)
        rbb.putShort(CockpitProtocol.MAGIC_RADIO_ROW)
        rbb.putShort(0) // index
        rbb.putShort(329) // totalCount
        rbb.put(1) // fav
        rbb.putShort(320) // bitrate
        rbb.putShort(payload.size.toShort())
        System.arraycopy(payload, 0, radioBuf, CockpitProtocol.RADIO_HEADER_SIZE, payload.size)
        radioBuf[radioBuf.size - 1] = CockpitProtocol.computeXor(radioBuf, radioBuf.size - 1)

        val decoded = CockpitProtocol.decodeRadioRow(radioBuf, radioBuf.size)
        assertNotNull(decoded)
        assertEquals(329, decoded!!.first)
        assertEquals("TruckersFM", decoded.second.name)
        assertEquals("Sim radio", decoded.second.genre)
        assertEquals("EN", decoded.second.country)
        assertEquals(320, decoded.second.bitrate)
        assertTrue(decoded.second.isFavorite)
        assertEquals("https://radio.truckers.fm/", decoded.second.url)

        // Corrupt a payload byte -> must reject
        radioBuf[CockpitProtocol.RADIO_HEADER_SIZE] = (radioBuf[CockpitProtocol.RADIO_HEADER_SIZE].toInt() xor 0xFF).toByte()
        assertNull(CockpitProtocol.decodeRadioRow(radioBuf, radioBuf.size))
    }
}
