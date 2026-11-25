package org.openax12.core.data.crsf

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import org.openax12.core.model.SensorId
import org.openax12.core.model.Sensors
import org.openax12.core.radio.CrsfFrame

/** CRC-8 with polynomial 0xD5 (DVB-S2), the checksum of CRSF frames. */
object Crc8 {

    private val TABLE = IntArray(256) { index ->
        var crc = index
        repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0xD5) and 0xFF else (crc shl 1) and 0xFF }
        crc
    }

    /** CRC of `bytes[from until toExclusive]`. */
    fun compute(bytes: ByteArray, from: Int = 0, toExclusive: Int = bytes.size): Int {
        var crc = 0
        for (i in from until toExclusive) crc = TABLE[crc xor (bytes[i].toInt() and 0xFF)]
        return crc
    }
}

/** Why a raw frame was dropped. */
enum class CrsfFrameError {
    /** Fewer than the four bytes every frame has (`ADDR LEN TYPE CRC`). */
    TOO_SHORT,

    /** `LEN` is outside 2..62, the buffer is shorter than `LEN + 2`, or the payload is too short for its type. */
    BAD_LENGTH,

    /** The CRC byte does not match CRC8-D5 over `TYPE + DATA`. */
    BAD_CRC,
}

/**
 * One decoded value.
 *
 * @property value numeric value in the sensor's native metric unit; `NaN` for text-only sensors
 * @property text text of a textual sensor (flight mode) or a multi-value sensor (cell voltages)
 */
data class CrsfReading(val id: SensorId, val value: Double, val text: String? = null)

/** The complete content of a GPS frame, for the home / last-known-position logic. */
data class CrsfGps(
    val latitude: Double,
    val longitude: Double,
    val groundSpeedKmh: Double,
    val headingDeg: Double,
    val altitudeM: Double,
    val satellites: Int,
)

/** Result of [CrsfDecoder.decode]. */
sealed interface CrsfDecodeResult {

    /** The frame failed validation and was dropped. */
    data class Invalid(val error: CrsfFrameError) : CrsfDecodeResult

    /**
     * The frame is structurally valid.
     *
     * @property type CRSF frame type byte
     * @property supported false for frame types the decoder does not know ([readings] is empty)
     * @property gps set for GPS frames (0x02)
     * @property unknownSids Rotorflight sensor ids the decoder stopped at (frame 0x88)
     */
    data class Decoded(
        val type: Int,
        val readings: List<CrsfReading>,
        val supported: Boolean = true,
        val gps: CrsfGps? = null,
        val unknownSids: List<Int> = emptyList(),
    ) : CrsfDecodeResult
}

/**
 * Decoder of raw CRSF telemetry frames (`ADDR LEN TYPE DATA… CRC`, big-endian fields).
 *
 * Supported: GPS 0x02, vario 0x07, battery 0x08, baro altitude 0x09, airspeed 0x0A, link
 * statistics 0x14, attitude 0x1E, flight mode 0x21 and the Rotorflight custom telemetry frame
 * 0x88 (sensor table as in the SDK demo `rmAxSdkJni.cpp`). Pure and allocation-light; safe to
 * call from any thread.
 */
object CrsfDecoder {

    /** Largest value of the `LEN` byte (frame of 64 bytes). */
    const val MAX_LEN: Int = 62

    private const val RAD_TO_DEG = 180.0 / PI

    /** Uplink TX power in mW by the index carried in the link statistics frame. */
    private val TX_POWER_MW = intArrayOf(0, 10, 25, 100, 500, 1000, 2000, 250, 50)

    /**
     * Validates and decodes one frame.
     *
     * @param bytes the frame; bytes after `LEN + 2` are ignored
     * @param verifyCrc false skips the CRC comparison (length checks still apply)
     */
    fun decode(bytes: ByteArray, verifyCrc: Boolean = true): CrsfDecodeResult {
        if (bytes.size < 4) return CrsfDecodeResult.Invalid(CrsfFrameError.TOO_SHORT)
        val len = bytes[1].toInt() and 0xFF
        if (len < 2 || len > MAX_LEN || bytes.size < len + 2) return CrsfDecodeResult.Invalid(CrsfFrameError.BAD_LENGTH)
        if (verifyCrc) {
            val expected = bytes[len + 1].toInt() and 0xFF
            if (Crc8.compute(bytes, 2, len + 1) != expected) return CrsfDecodeResult.Invalid(CrsfFrameError.BAD_CRC)
        }
        val type = bytes[2].toInt() and 0xFF
        val payload = Payload(bytes, 3, len - 2)
        return when (type) {
            CrsfFrame.TYPE_GPS -> gps(payload)
            CrsfFrame.TYPE_VARIO -> vario(payload)
            CrsfFrame.TYPE_BATTERY -> battery(payload)
            CrsfFrame.TYPE_BARO_ALTITUDE -> baroAltitude(payload)
            CrsfFrame.TYPE_AIRSPEED -> airspeed(payload)
            CrsfFrame.TYPE_LINK_STATISTICS -> linkStatistics(payload)
            CrsfFrame.TYPE_ATTITUDE -> attitude(payload)
            CrsfFrame.TYPE_FLIGHT_MODE -> flightMode(payload)
            CrsfFrame.TYPE_ROTORFLIGHT_CUSTOM -> rotorflight(payload)
            else -> CrsfDecodeResult.Decoded(type, emptyList(), supported = false)
        }
    }

    /**
     * Builds a valid frame (`address`, length, [type], [payload], CRC). Used by tests and by the
     * diagnostics screen to feed sample frames.
     */
    fun buildFrame(type: Int, payload: ByteArray, address: Int = 0xEA): ByteArray {
        require(payload.size <= MAX_LEN - 2) { "CRSF payload too long: ${payload.size}" }
        val frame = ByteArray(payload.size + 4)
        frame[0] = address.toByte()
        frame[1] = (payload.size + 2).toByte()
        frame[2] = type.toByte()
        payload.copyInto(frame, 3)
        frame[frame.size - 1] = Crc8.compute(frame, 2, frame.size - 1).toByte()
        return frame
    }

    // -- Frame types ------------------------------------------------------------------------------

    private fun gps(p: Payload): CrsfDecodeResult {
        if (p.length < 15) return tooShort()
        val fix = CrsfGps(
            latitude = p.s32(0) / 1e7,
            longitude = p.s32(4) / 1e7,
            groundSpeedKmh = p.u16(8) / 10.0,
            headingDeg = p.u16(10) / 100.0,
            altitudeM = p.u16(12) - 1000.0,
            satellites = p.u8(14),
        )
        return CrsfDecodeResult.Decoded(
            type = CrsfFrame.TYPE_GPS,
            readings = listOf(
                CrsfReading(Sensors.GPS_LATITUDE, fix.latitude),
                CrsfReading(Sensors.GPS_LONGITUDE, fix.longitude),
                CrsfReading(Sensors.GPS_GROUND_SPEED, fix.groundSpeedKmh),
                CrsfReading(Sensors.GPS_HEADING, fix.headingDeg),
                CrsfReading(Sensors.GPS_ALTITUDE, fix.altitudeM),
                CrsfReading(Sensors.GPS_SATELLITES, fix.satellites.toDouble()),
            ),
            gps = fix,
        )
    }

    private fun vario(p: Payload): CrsfDecodeResult {
        if (p.length < 2) return tooShort()
        // int16, cm/s
        return decoded(CrsfFrame.TYPE_VARIO, CrsfReading(Sensors.VERTICAL_SPEED, p.s16(0) / 100.0))
    }

    private fun battery(p: Payload): CrsfDecodeResult {
        if (p.length < 8) return tooShort()
        // voltage 0.1 V, current 0.1 A (signed), capacity 24-bit mAh, remaining %
        return decoded(
            CrsfFrame.TYPE_BATTERY,
            CrsfReading(Sensors.BATTERY_VOLTAGE, p.u16(0) / 10.0),
            CrsfReading(Sensors.BATTERY_CURRENT, p.s16(2) / 10.0),
            CrsfReading(Sensors.BATTERY_CAPACITY, p.u24(4).toDouble()),
            CrsfReading(Sensors.BATTERY_REMAINING, p.u8(7).toDouble()),
        )
    }

    /**
     * Baro altitude 0x09. Altitude: bit 15 clear = decimetres with a 10000 dm offset, bit 15 set
     * = whole metres without offset. Vertical speed: absent (2-byte payload), a logarithmically
     * packed `int8` (3-byte payload, TBS specification) or a plain `int16` in cm/s (payload of 4
     * bytes or more, the ExpressLRS / EdgeTX variant).
     */
    private fun baroAltitude(p: Payload): CrsfDecodeResult {
        if (p.length < 2) return tooShort()
        val packed = p.u16(0)
        val altitude = if (packed and 0x8000 != 0) (packed and 0x7FFF).toDouble() else (packed - 10000) / 10.0
        val readings = ArrayList<CrsfReading>(2)
        readings += CrsfReading(Sensors.BARO_ALTITUDE, altitude)
        when {
            p.length >= 4 -> readings += CrsfReading(Sensors.BARO_VERTICAL_SPEED, p.s16(2) / 100.0)
            p.length == 3 -> readings += CrsfReading(Sensors.BARO_VERTICAL_SPEED, unpackVerticalSpeed(p.s8(2)))
        }
        return CrsfDecodeResult.Decoded(CrsfFrame.TYPE_BARO_ALTITUDE, readings)
    }

    /** Inverse of the CRSF log packing `packed = ln(|v| / 100 + 1) / 0.026` (v in cm/s); result in m/s. */
    internal fun unpackVerticalSpeed(packed: Int): Double {
        if (packed == 0) return 0.0
        val magnitude = exp(abs(packed) * 0.026) - 1.0
        return if (packed < 0) -magnitude else magnitude
    }

    private fun airspeed(p: Payload): CrsfDecodeResult {
        if (p.length < 2) return tooShort()
        // uint16, 0.1 km/h
        return decoded(CrsfFrame.TYPE_AIRSPEED, CrsfReading(Sensors.AIRSPEED, p.u16(0) / 10.0))
    }

    private fun linkStatistics(p: Payload): CrsfDecodeResult {
        if (p.length < 10) return tooShort()
        val powerIndex = p.u8(6)
        return decoded(
            CrsfFrame.TYPE_LINK_STATISTICS,
            CrsfReading(Sensors.LINK_RSSI_1, -p.u8(0).toDouble()),
            CrsfReading(Sensors.LINK_RSSI_2, -p.u8(1).toDouble()),
            CrsfReading(Sensors.LINK_RX_QUALITY, p.u8(2).toDouble()),
            CrsfReading(Sensors.LINK_RX_SNR, p.s8(3).toDouble()),
            CrsfReading(Sensors.LINK_ANTENNA, p.u8(4).toDouble()),
            CrsfReading(Sensors.LINK_RF_MODE, p.u8(5).toDouble()),
            CrsfReading(Sensors.LINK_TX_POWER, TX_POWER_MW.getOrElse(powerIndex) { 0 }.toDouble()),
            CrsfReading(Sensors.LINK_TX_RSSI, -p.u8(7).toDouble()),
            CrsfReading(Sensors.LINK_TX_QUALITY, p.u8(8).toDouble()),
            CrsfReading(Sensors.LINK_TX_SNR, p.s8(9).toDouble()),
        )
    }

    private fun attitude(p: Payload): CrsfDecodeResult {
        if (p.length < 6) return tooShort()
        // int16, radians * 10000
        return decoded(
            CrsfFrame.TYPE_ATTITUDE,
            CrsfReading(Sensors.ATTITUDE_PITCH, p.s16(0) / 10000.0 * RAD_TO_DEG),
            CrsfReading(Sensors.ATTITUDE_ROLL, p.s16(2) / 10000.0 * RAD_TO_DEG),
            CrsfReading(Sensors.ATTITUDE_YAW, p.s16(4) / 10000.0 * RAD_TO_DEG),
        )
    }

    private fun flightMode(p: Payload): CrsfDecodeResult {
        if (p.length < 1) return tooShort()
        val text = StringBuilder(p.length)
        for (i in 0 until p.length) {
            val c = p.u8(i)
            if (c == 0) break
            // Printable ASCII only: the text ends up in the UI, logs and speech.
            if (c in 0x20..0x7E) text.append(c.toChar())
        }
        return decoded(CrsfFrame.TYPE_FLIGHT_MODE, CrsfReading(Sensors.FC_FLIGHT_MODE, Double.NaN, text.toString()))
    }

    /**
     * Rotorflight custom telemetry 0x88. Payload as delivered by the SDK:
     * `DST SRC FrameID [SID(2, big-endian) value…]…`. The value length follows from the SID; an
     * unknown SID therefore ends the frame (everything decoded before it is kept).
     */
    private fun rotorflight(p: Payload): CrsfDecodeResult {
        if (p.length < 3) return tooShort()
        val readings = ArrayList<CrsfReading>()
        val unknown = ArrayList<Int>(1)
        var pos = 3
        while (pos + 2 <= p.length) {
            val sid = p.u16(pos)
            val def = RotorflightSensors.find(sid)
            if (def == null) {
                unknown += sid
                break
            }
            pos += 2
            val id = Sensors.rotorflight(sid)
            if (def.encoding == RotorflightEncoding.CELLS) {
                // All remaining bytes: 12 bits per cell, millivolts. Always the last record.
                val cells = ArrayList<Double>()
                val count = (p.length - pos) * 8 / 12
                for (i in 0 until count) {
                    val bit = i * 12
                    val byte = pos + bit / 8
                    if (byte + 1 >= p.length) break
                    val pair = (p.u8(byte) shl 8) or p.u8(byte + 1)
                    val raw = if (bit % 8 == 0) (pair shr 4) and 0xFFF else pair and 0xFFF
                    cells += raw / def.scale
                }
                if (cells.isNotEmpty()) {
                    readings += CrsfReading(id, cells.min(), cells.joinToString(" ") { formatFixed(it, def.precision) })
                }
                break
            }
            val size = def.encoding.bytes
            if (pos + size > p.length) break // truncated value
            when (def.encoding) {
                RotorflightEncoding.U8 -> readings += CrsfReading(id, p.u8(pos) / def.scale)
                RotorflightEncoding.U16 -> readings += CrsfReading(id, p.u16(pos) / def.scale)
                RotorflightEncoding.S16 -> readings += CrsfReading(id, p.s16(pos) / def.scale)
                RotorflightEncoding.U32 -> readings += CrsfReading(id, p.u32(pos) / def.scale)
                RotorflightEncoding.U12U12 -> {
                    // Two 12-bit values in three bytes: lowest cell, average cell.
                    val raw = p.u24(pos)
                    readings += CrsfReading(id, ((raw shr 12) and 0xFFF) / def.scale)
                    readings += CrsfReading(RotorflightSensors.averageCellId(sid), (raw and 0xFFF) / def.scale)
                }
                RotorflightEncoding.CELLS -> Unit
            }
            pos += size
        }
        return CrsfDecodeResult.Decoded(CrsfFrame.TYPE_ROTORFLIGHT_CUSTOM, readings, unknownSids = unknown)
    }

    private fun decoded(type: Int, vararg readings: CrsfReading): CrsfDecodeResult =
        CrsfDecodeResult.Decoded(type, readings.asList())

    private fun tooShort(): CrsfDecodeResult = CrsfDecodeResult.Invalid(CrsfFrameError.BAD_LENGTH)

    private fun formatFixed(value: Double, decimals: Int): String =
        String.format(java.util.Locale.ROOT, "%.${decimals}f", value)

    /** Big-endian view of a frame's payload. */
    private class Payload(private val bytes: ByteArray, private val offset: Int, val length: Int) {
        fun u8(i: Int): Int = bytes[offset + i].toInt() and 0xFF
        fun s8(i: Int): Int = bytes[offset + i].toInt()
        fun u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
        fun s16(i: Int): Int = u16(i).toShort().toInt()
        fun u24(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)
        fun u32(i: Int): Long = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
        fun s32(i: Int): Int = u32(i).toInt()
    }
}
