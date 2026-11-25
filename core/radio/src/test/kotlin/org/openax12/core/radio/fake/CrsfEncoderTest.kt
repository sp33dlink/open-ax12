package org.openax12.core.radio.fake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.radio.CrsfFrame

class CrsfEncoderTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun CrsfFrame.u16(offset: Int): Int = (payload(offset) shl 8) or payload(offset + 1)
    private fun CrsfFrame.i16(offset: Int): Int = u16(offset).toShort().toInt()
    private fun CrsfFrame.i32(offset: Int): Int =
        (payload(offset) shl 24) or (payload(offset + 1) shl 16) or (payload(offset + 2) shl 8) or payload(offset + 3)

    /** Checks the frame envelope: address, LEN = payload + type + CRC, type and a correct CRC. */
    private fun assertWellFormed(frame: CrsfFrame, type: Int, payloadLength: Int) {
        val raw = frame.bytes
        assertEquals(payloadLength + 4, raw.size)
        assertEquals(CrsfEncoder.ADDRESS_RADIO, frame.address)
        assertEquals(payloadLength + 2, raw[1].toInt() and 0xFF)
        assertEquals(raw.size, (raw[1].toInt() and 0xFF) + 2)
        assertEquals(type, frame.type)
        assertEquals(payloadLength, frame.payloadLength)
        assertEquals(CrsfEncoder.crc8(raw, 2, raw.size - 1), raw.last().toInt() and 0xFF)
        assertTrue(CrsfEncoder.isValid(raw))
    }

    @Test
    fun crc8UsesPolynomialD5() {
        // CRC-8/DVB-S2 check value.
        assertEquals(0xBC, CrsfEncoder.crc8("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0x00, CrsfEncoder.crc8(ByteArray(0)))
        assertEquals(0xD5, CrsfEncoder.crc8(bytes(0x01)))
    }

    @Test
    fun crcCoversTypeAndPayloadOnly() {
        val frame = CrsfEncoder.frame(0x07, bytes(0x12, 0x34), address = 0xC8).bytes
        assertEquals(CrsfEncoder.crc8(bytes(0x07, 0x12, 0x34)), frame.last().toInt() and 0xFF)
        // A different address leaves the CRC alone.
        val other = CrsfEncoder.frame(0x07, bytes(0x12, 0x34), address = 0xEA).bytes
        assertEquals(frame.last(), other.last())
        assertEquals(0xC8, frame[0].toInt() and 0xFF)
    }

    @Test
    fun batteryFrame() {
        val frame = CrsfEncoder.battery(voltage = 11.6, current = 4.2, capacityMah = 310.0, remainingPercent = 86)
        assertWellFormed(frame, CrsfFrame.TYPE_BATTERY, 8)
        assertArrayEquals(
            bytes(0xEA, 0x0A, 0x08, 0x00, 0x74, 0x00, 0x2A, 0x00, 0x01, 0x36, 0x56),
            frame.bytes.copyOf(frame.bytes.size - 1),
        )
    }

    @Test
    fun varioFrame() {
        val sink = CrsfEncoder.vario(-1.25)
        assertWellFormed(sink, CrsfFrame.TYPE_VARIO, 2)
        assertEquals(-125, sink.i16(0))
        assertEquals(250, CrsfEncoder.vario(2.5).i16(0))
        // Out-of-range values saturate instead of wrapping.
        assertEquals(32767, CrsfEncoder.vario(1000.0).i16(0))
    }

    @Test
    fun baroAltitudeFrame() {
        val low = CrsfEncoder.baroAltitude(120.5, 1.5)
        assertWellFormed(low, CrsfFrame.TYPE_BARO_ALTITUDE, 4)
        assertEquals(11205, low.u16(0))
        assertEquals(150, low.i16(2))

        // Below the start point: still decimetres + 10000.
        assertEquals(9500, CrsfEncoder.baroAltitude(-50.0, -0.5).u16(0))
        assertEquals(-50, CrsfEncoder.baroAltitude(-50.0, -0.5).i16(2))

        // Above 2276.7 m the altitude is sent in metres with the top bit set.
        assertEquals(0x7FFF, CrsfEncoder.baroAltitude(2276.7, 0.0).u16(0))
        assertEquals(0x8000 or 3000, CrsfEncoder.baroAltitude(3000.0, 0.0).u16(0))
    }

    @Test
    fun gpsFrame() {
        val frame = CrsfEncoder.gps(
            latitude = 50.4983,
            longitude = -9.9536,
            groundSpeedKmh = 42.0,
            headingDeg = 90.0,
            altitudeM = 500.0,
            satellites = 12,
        )
        assertWellFormed(frame, CrsfFrame.TYPE_GPS, 15)
        assertEquals(504_983_000, frame.i32(0))
        assertEquals(-99_536_000, frame.i32(4))
        assertEquals(420, frame.u16(8))
        assertEquals(9000, frame.u16(10))
        assertEquals(1500, frame.u16(12))
        assertEquals(12, frame.payload(14))

        // Headings wrap into 0..360, altitudes below -1000 m saturate.
        val wrapped = CrsfEncoder.gps(0.0, 0.0, 0.0, -90.0, -2000.0, 0)
        assertEquals(27000, wrapped.u16(10))
        assertEquals(0, wrapped.u16(12))
    }

    @Test
    fun airspeedFrame() {
        val frame = CrsfEncoder.airspeed(45.0)
        assertWellFormed(frame, CrsfFrame.TYPE_AIRSPEED, 2)
        assertEquals(450, frame.u16(0))
    }

    @Test
    fun linkStatisticsFrame() {
        val frame = CrsfEncoder.linkStatistics(
            uplinkRssi1 = -58,
            uplinkRssi2 = -61,
            uplinkQuality = 100,
            uplinkSnr = 9,
            activeAntenna = 0,
            rfMode = 3,
            txPowerMw = 100,
            downlinkRssi = -60,
            downlinkQuality = 99,
            downlinkSnr = -2,
        )
        assertWellFormed(frame, CrsfFrame.TYPE_LINK_STATISTICS, 10)
        assertArrayEquals(
            bytes(58, 61, 100, 9, 0, 3, 3, 60, 99, 0xFE),
            frame.bytes.copyOfRange(3, 13),
        )
    }

    @Test
    fun txPowerSteps() {
        assertEquals(0, CrsfEncoder.txPowerIndex(0))
        assertEquals(1, CrsfEncoder.txPowerIndex(10))
        assertEquals(2, CrsfEncoder.txPowerIndex(25))
        assertEquals(8, CrsfEncoder.txPowerIndex(50))
        assertEquals(3, CrsfEncoder.txPowerIndex(100))
        assertEquals(7, CrsfEncoder.txPowerIndex(250))
        assertEquals(4, CrsfEncoder.txPowerIndex(500))
        assertEquals(5, CrsfEncoder.txPowerIndex(1000))
        assertEquals(6, CrsfEncoder.txPowerIndex(5000))
    }

    @Test
    fun attitudeFrame() {
        val frame = CrsfEncoder.attitude(pitchDeg = 10.0, rollDeg = -20.0, yawDeg = 190.0)
        assertWellFormed(frame, CrsfFrame.TYPE_ATTITUDE, 6)
        // 1e-4 rad: 10° = 0.17453 rad, -20° = -0.34907 rad, 190° wraps to -170° = -2.96706 rad.
        assertEquals(1745, frame.i16(0))
        assertEquals(-3491, frame.i16(2))
        assertEquals(-29671, frame.i16(4))
        assertEquals(31416, CrsfEncoder.attitude(0.0, 0.0, 180.0).i16(4))
    }

    @Test
    fun flightModeFrame() {
        val frame = CrsfEncoder.flightMode("MANU")
        assertWellFormed(frame, CrsfFrame.TYPE_FLIGHT_MODE, 5)
        assertArrayEquals(bytes(0x4D, 0x41, 0x4E, 0x55, 0x00), frame.bytes.copyOfRange(3, 8))

        // Text that does not fit is cut, the terminator stays; non-ASCII becomes '?'.
        val long = CrsfEncoder.flightMode("x".repeat(200))
        assertTrue(CrsfEncoder.isValid(long.bytes))
        assertEquals(64, long.bytes.size)
        assertEquals(0, long.payload(long.payloadLength - 1))
        assertEquals('?'.code, CrsfEncoder.flightMode("ä").payload(0))
    }

    @Test
    fun deviceInfoFrame() {
        val frame = CrsfEncoder.deviceInfo("AX12 Internal ELRS", origin = CrsfEncoder.ADDRESS_TX_MODULE, parameterCount = 18)
        assertTrue(CrsfEncoder.isValid(frame.bytes))
        assertEquals(CrsfEncoder.TYPE_DEVICE_INFO, frame.type)
        assertEquals(CrsfEncoder.ADDRESS_RADIO, frame.payload(0))
        assertEquals(CrsfEncoder.ADDRESS_TX_MODULE, frame.payload(1))
        assertEquals('A'.code, frame.payload(2))
        assertEquals(18, frame.payload(frame.payloadLength - 2))
    }

    @Test
    fun isValidRejectsDamagedFrames() {
        val good = CrsfEncoder.vario(1.0).bytes
        assertTrue(CrsfEncoder.isValid(good))
        assertFalse(CrsfEncoder.isValid(good.copyOf().also { it[3] = (it[3] + 1).toByte() }))
        assertFalse(CrsfEncoder.isValid(good.copyOf(good.size - 1)))
        assertFalse(CrsfEncoder.isValid(good.copyOf().also { it[1] = 9 }))
        assertFalse(CrsfEncoder.isValid(ByteArray(0)))
        assertFalse(CrsfEncoder.isValid(bytes(0xEA, 0x02)))
    }

    @Test
    fun nanValuesAreSentAsZero() {
        val frame = CrsfEncoder.battery(Double.NaN, Double.NaN, Double.NaN, 0)
        assertTrue(CrsfEncoder.isValid(frame.bytes))
        assertEquals(0, frame.u16(0))
    }
}
