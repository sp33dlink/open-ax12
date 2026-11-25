package org.openax12.core.data.crsf

import org.junit.Test
import org.openax12.core.model.Sensors
import org.openax12.core.radio.CrsfFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class CrsfDecoderTest {

    private fun decoded(frame: ByteArray): CrsfDecodeResult.Decoded =
        CrsfDecoder.decode(frame) as CrsfDecodeResult.Decoded

    @Test
    fun batteryFrameIsDecoded() {
        // 12.6 V, 3.5 A, 1500 mAh, 80 %
        val payload = byteArrayOf(0, 126, 0, 35, 0, 0x05, 0xDC.toByte(), 80)
        val result = decoded(CrsfDecoder.buildFrame(CrsfFrame.TYPE_BATTERY, payload))
        assertTrue(result.supported)
        val byId = result.readings.associateBy { it.id }
        assertEquals(12.6, byId.getValue(Sensors.BATTERY_VOLTAGE).value, 1e-9)
        assertEquals(3.5, byId.getValue(Sensors.BATTERY_CURRENT).value, 1e-9)
        assertEquals(1500.0, byId.getValue(Sensors.BATTERY_CAPACITY).value, 1e-9)
        assertEquals(80.0, byId.getValue(Sensors.BATTERY_REMAINING).value, 1e-9)
    }

    @Test
    fun varioIsSignedCentimetresPerSecond() {
        val frame = CrsfDecoder.buildFrame(CrsfFrame.TYPE_VARIO, byteArrayOf(0xFF.toByte(), 0x38)) // -200
        assertEquals(-2.0, decoded(frame).readings.single().value, 1e-9)
    }

    @Test
    fun corruptCrcIsRejected() {
        val frame = CrsfDecoder.buildFrame(CrsfFrame.TYPE_VARIO, byteArrayOf(0, 100))
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()
        val result = CrsfDecoder.decode(frame) as CrsfDecodeResult.Invalid
        assertEquals(CrsfFrameError.BAD_CRC, result.error)
    }

    @Test
    fun truncatedFrameIsRejected() {
        assertTrue(CrsfDecoder.decode(byteArrayOf(0xEA.toByte(), 5)) is CrsfDecodeResult.Invalid)
    }

    @Test
    fun unknownTypeIsReportedAsUnsupported() {
        val result = decoded(CrsfDecoder.buildFrame(0x55, byteArrayOf(1, 2, 3)))
        assertFalse(result.supported)
        assertTrue(result.readings.isEmpty())
    }

    @Test
    fun verticalSpeedUnpackingIsSymmetric() {
        assertEquals(0.0, CrsfDecoder.unpackVerticalSpeed(0), 0.0)
        assertEquals(-CrsfDecoder.unpackVerticalSpeed(40), CrsfDecoder.unpackVerticalSpeed(-40), 1e-12)
    }
}
