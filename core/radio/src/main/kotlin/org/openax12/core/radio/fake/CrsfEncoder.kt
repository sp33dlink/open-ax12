package org.openax12.core.radio.fake

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.openax12.core.radio.CrsfFrame

/**
 * Builds well-formed raw CRSF frames (`ADDR LEN TYPE DATA… CRC`, LEN = payload + 2, CRC8 with
 * polynomial 0xD5 over TYPE + DATA; all multi-byte fields big-endian). The simulator sends its
 * telemetry through these so that the app's decoders see exactly what a receiver would deliver;
 * tests of decoders can use it to produce input.
 *
 * Field encodings follow the CRSF specification as implemented by ExpressLRS and EdgeTX:
 *
 * | Frame | Payload |
 * |---|---|
 * | GPS `0x02` | lat, lon `int32` in 1e-7°; ground speed `uint16` in 0.1 km/h; heading `uint16` in 0.01°; altitude `uint16` in m + 1000; satellites `uint8` |
 * | Vario `0x07` | vertical speed `int16` in cm/s |
 * | Battery `0x08` | voltage `uint16` in 0.1 V; current `uint16` in 0.1 A; capacity used `uint24` in mAh; remaining `uint8` in % |
 * | Baro altitude `0x09` | altitude `uint16`: dm + 10000, or `0x8000 or metres` above 2276.7 m; vertical speed `int16` in cm/s (the ExpressLRS 4-byte form) |
 * | Airspeed `0x0A` | `uint16` in 0.1 km/h |
 * | Link statistics `0x14` | uplink RSSI 1 / 2 `uint8` in −dBm; uplink LQ `uint8`; uplink SNR `int8`; antenna; RF mode; TX power index; downlink RSSI in −dBm; downlink LQ; downlink SNR `int8` |
 * | Attitude `0x1E` | pitch, roll, yaw `int16` in 1e-4 rad |
 * | Flight mode `0x21` | NUL-terminated ASCII text |
 */
object CrsfEncoder {

    /** Address of the handset; telemetry frames forwarded by the TX module carry it. */
    const val ADDRESS_RADIO: Int = 0xEA

    /** Address of the TX module (ELRS transmitter). */
    const val ADDRESS_TX_MODULE: Int = 0xEE

    /** Address of the receiver. */
    const val ADDRESS_RECEIVER: Int = 0xEC

    /** Sync / flight-controller address. */
    const val ADDRESS_FLIGHT_CONTROLLER: Int = 0xC8

    /** Frame types that are not telemetry values (device discovery). */
    const val TYPE_DEVICE_PING: Int = 0x28
    const val TYPE_DEVICE_INFO: Int = 0x29

    /** Largest payload a CRSF frame can carry (LEN is at most 62). */
    const val MAX_PAYLOAD: Int = 60

    /** TX power steps of the link statistics frame in mW, by index. */
    val TX_POWER_MW: List<Int> = listOf(0, 10, 25, 100, 500, 1000, 2000, 250, 50)

    /** CRC8 (polynomial 0xD5, initial value 0) over [bytes] from [from] (inclusive) to [until] (exclusive). */
    fun crc8(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): Int {
        var crc = 0
        for (i in from until until) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0xD5) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    /**
     * True if [bytes] is a complete frame: at least 4 bytes, LEN matching the array length and a
     * correct CRC.
     */
    fun isValid(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val length = bytes[1].toInt() and 0xFF
        if (length != bytes.size - 2) return false
        return crc8(bytes, 2, bytes.size - 1) == (bytes[bytes.size - 1].toInt() and 0xFF)
    }

    /** A frame of [type] with [payload] (at most [MAX_PAYLOAD] bytes; longer payloads are cut). */
    fun frame(type: Int, payload: ByteArray, address: Int = ADDRESS_RADIO): CrsfFrame {
        val size = payload.size.coerceAtMost(MAX_PAYLOAD)
        val bytes = ByteArray(size + 4)
        bytes[0] = address.toByte()
        bytes[1] = (size + 2).toByte()
        bytes[2] = type.toByte()
        System.arraycopy(payload, 0, bytes, 3, size)
        bytes[bytes.size - 1] = crc8(bytes, 2, bytes.size - 1).toByte()
        return CrsfFrame(bytes)
    }

    /**
     * GPS frame.
     *
     * @param latitude degrees, north positive
     * @param longitude degrees, east positive
     * @param groundSpeedKmh ground speed in km/h
     * @param headingDeg course over ground in degrees, 0..360
     * @param altitudeM altitude in metres (−1000..64535)
     */
    fun gps(
        latitude: Double,
        longitude: Double,
        groundSpeedKmh: Double,
        headingDeg: Double,
        altitudeM: Double,
        satellites: Int,
    ): CrsfFrame {
        val p = Writer(15)
        p.i32((latitude.coerceIn(-90.0, 90.0) * 1e7).roundToLong().toInt())
        p.i32((longitude.coerceIn(-180.0, 180.0) * 1e7).roundToLong().toInt())
        p.u16(scaled(groundSpeedKmh, 10.0, 0, 0xFFFF))
        p.u16(scaled(wrap360(headingDeg), 100.0, 0, 35999))
        p.u16(scaled(altitudeM + 1000.0, 1.0, 0, 0xFFFF))
        p.u8(satellites.coerceIn(0, 255))
        return frame(CrsfFrame.TYPE_GPS, p.bytes)
    }

    /** Vario frame: vertical speed in m/s (climb positive). */
    fun vario(verticalSpeedMs: Double): CrsfFrame {
        val p = Writer(2)
        p.u16(scaled(verticalSpeedMs, 100.0, -32768, 32767))
        return frame(CrsfFrame.TYPE_VARIO, p.bytes)
    }

    /** Battery frame: volts, amperes, used capacity in mAh, remaining charge in percent. */
    fun battery(voltage: Double, current: Double, capacityMah: Double, remainingPercent: Int): CrsfFrame {
        val p = Writer(8)
        p.u16(scaled(voltage, 10.0, 0, 0xFFFF))
        p.u16(scaled(current, 10.0, 0, 0xFFFF))
        p.u24(scaled(capacityMah, 1.0, 0, 0xFFFFFF))
        p.u8(remainingPercent.coerceIn(0, 100))
        return frame(CrsfFrame.TYPE_BATTERY, p.bytes)
    }

    /**
     * Barometric altitude frame with the vertical speed appended (4 payload bytes).
     *
     * @param altitudeM altitude in metres; sent in decimetres up to 2276.7 m, in metres above
     * @param verticalSpeedMs vertical speed in m/s
     */
    fun baroAltitude(altitudeM: Double, verticalSpeedMs: Double): CrsfFrame {
        val p = Writer(4)
        val decimetres = (altitudeM * 10.0).roundToInt() + 10000
        if (decimetres in 0..0x7FFF) {
            p.u16(decimetres)
        } else if (decimetres < 0) {
            p.u16(0)
        } else {
            p.u16(0x8000 or altitudeM.roundToInt().coerceIn(0, 0x7FFF))
        }
        p.u16(scaled(verticalSpeedMs, 100.0, -32768, 32767))
        return frame(CrsfFrame.TYPE_BARO_ALTITUDE, p.bytes)
    }

    /** Airspeed frame: km/h. */
    fun airspeed(speedKmh: Double): CrsfFrame {
        val p = Writer(2)
        p.u16(scaled(speedKmh, 10.0, 0, 0xFFFF))
        return frame(CrsfFrame.TYPE_AIRSPEED, p.bytes)
    }

    /**
     * Link statistics frame. RSSI values are in dBm (negative; sent as their magnitude), link
     * quality in percent, SNR in dB.
     *
     * @param txPowerMw uplink TX power in mW; sent as the index of the nearest step of [TX_POWER_MW]
     */
    fun linkStatistics(
        uplinkRssi1: Int,
        uplinkRssi2: Int,
        uplinkQuality: Int,
        uplinkSnr: Int,
        activeAntenna: Int,
        rfMode: Int,
        txPowerMw: Int,
        downlinkRssi: Int,
        downlinkQuality: Int,
        downlinkSnr: Int,
    ): CrsfFrame {
        val p = Writer(10)
        p.u8(abs(uplinkRssi1).coerceIn(0, 255))
        p.u8(abs(uplinkRssi2).coerceIn(0, 255))
        p.u8(uplinkQuality.coerceIn(0, 255))
        p.u8(uplinkSnr.coerceIn(-128, 127))
        p.u8(activeAntenna.coerceIn(0, 255))
        p.u8(rfMode.coerceIn(0, 255))
        p.u8(txPowerIndex(txPowerMw))
        p.u8(abs(downlinkRssi).coerceIn(0, 255))
        p.u8(downlinkQuality.coerceIn(0, 255))
        p.u8(downlinkSnr.coerceIn(-128, 127))
        return frame(CrsfFrame.TYPE_LINK_STATISTICS, p.bytes)
    }

    /** Attitude frame: pitch, roll and yaw in degrees (each wrapped to −180..+180). */
    fun attitude(pitchDeg: Double, rollDeg: Double, yawDeg: Double): CrsfFrame {
        val p = Writer(6)
        p.u16(radians(pitchDeg))
        p.u16(radians(rollDeg))
        p.u16(radians(yawDeg))
        return frame(CrsfFrame.TYPE_ATTITUDE, p.bytes)
    }

    /** Flight mode frame: [text] as NUL-terminated ASCII (non-ASCII characters become `?`). */
    fun flightMode(text: String): CrsfFrame {
        val clean = text.take(MAX_PAYLOAD - 1)
        val payload = ByteArray(clean.length + 1)
        for (i in clean.indices) {
            val c = clean[i]
            payload[i] = (if (c.code in 0x20..0x7E) c.code else '?'.code).toByte()
        }
        return frame(CrsfFrame.TYPE_FLIGHT_MODE, payload)
    }

    /**
     * Device info frame (`0x29`, extended header) as a CRSF device answers a ping: destination,
     * origin, NUL-terminated name, serial number, hardware and software version, number of
     * parameters and parameter protocol version.
     */
    fun deviceInfo(name: String, origin: Int, parameterCount: Int, destination: Int = ADDRESS_RADIO): CrsfFrame {
        val clean = name.take(MAX_PAYLOAD - 17)
        val p = Writer(2 + clean.length + 1 + 14)
        p.u8(destination)
        p.u8(origin)
        for (c in clean) p.u8(if (c.code in 0x20..0x7E) c.code else '?'.code)
        p.u8(0)
        p.i32(0x454C5253) // serial number: "ELRS"
        p.i32(0)          // hardware version
        p.i32(0x00030503) // software version 3.5.3
        p.u8(parameterCount.coerceIn(0, 255))
        p.u8(0)
        return frame(TYPE_DEVICE_INFO, p.bytes, destination)
    }

    /** Index of the step of [TX_POWER_MW] nearest to [milliwatt]. */
    fun txPowerIndex(milliwatt: Int): Int {
        var best = 0
        for (i in TX_POWER_MW.indices) {
            if (abs(TX_POWER_MW[i] - milliwatt) < abs(TX_POWER_MW[best] - milliwatt)) best = i
        }
        return best
    }

    private fun wrap360(degrees: Double): Double {
        val r = degrees % 360.0
        return if (r < 0) r + 360.0 else r
    }

    private fun wrap180(degrees: Double): Double {
        val r = wrap360(degrees)
        return if (r > 180.0) r - 360.0 else r
    }

    private fun radians(degrees: Double): Int =
        (wrap180(degrees) * PI / 180.0 * 10000.0).roundToInt().coerceIn(-32768, 32767)

    private fun scaled(value: Double, factor: Double, min: Int, max: Int): Int =
        if (value.isNaN()) 0 else (value * factor).roundToLong().coerceIn(min.toLong(), max.toLong()).toInt()

    /** Big-endian byte writer over a fixed-size payload. */
    private class Writer(size: Int) {
        val bytes = ByteArray(size)
        private var position = 0

        fun u8(value: Int) {
            bytes[position++] = value.toByte()
        }

        fun u16(value: Int) {
            u8(value shr 8)
            u8(value)
        }

        fun u24(value: Int) {
            u8(value shr 16)
            u8(value shr 8)
            u8(value)
        }

        fun i32(value: Int) {
            u8(value shr 24)
            u8(value shr 16)
            u8(value shr 8)
            u8(value)
        }
    }
}
