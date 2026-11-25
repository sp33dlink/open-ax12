package org.openax12.core.model

import kotlinx.serialization.Serializable

/**
 * Stable string key of a sensor in the unified sensor registry. Serialized as a plain string, so
 * it survives in `extras.json` across app versions.
 *
 * Namespaces:
 * - `crsf.<name>` — decoded from raw CRSF frames, see [Sensors]
 * - `sdk.<hash>` — an item of the SDK telemetry list (hash as unsigned decimal)
 * - `tx.<name>` — transmitter values (`tx.batt`, `tx.lq`, `tx.rssi`, `tx.rxbatt`)
 * - `in.<source>` — an input source by default name (`in.SA`, `in.Thr`), logic value in percent
 * - `ch.<n>` — output channel, 1-based (`ch.3`), in percent
 * - `timer.<n>` / `cnt.<n>` — timers and counters, 1-based
 * - `ls.<n>` — logical switch, 1-based, 0 or 1
 * - `fm` — active flight mode (index as value, name as text)
 */
@Serializable
@JvmInline
value class SensorId(val key: String) : Comparable<SensorId> {

    /** Part before the first dot, or the whole key (`fm`). */
    val namespace: String get() = key.substringBefore('.')

    /** Part after the first dot, or empty. */
    val localName: String get() = key.substringAfter('.', "")

    /** The input source of an `in.*` id, else null. */
    val source: SourceId? get() = if (namespace == NS_INPUT) SourceId.fromName(localName) else null

    /** 0-based channel index of a `ch.*` id, else null. */
    val channelIndex: Int? get() = indexIn(NS_CHANNEL, CHANNEL_COUNT)

    /** 0-based timer index of a `timer.*` id, else null. */
    val timerIndex: Int? get() = indexIn(NS_TIMER, TIMER_COUNT)

    /** 0-based counter index of a `cnt.*` id, else null. */
    val counterIndex: Int? get() = indexIn(NS_COUNTER, COUNTER_COUNT)

    /** 0-based logical switch index of an `ls.*` id, else null. */
    val logicalSwitchIndex: Int? get() = indexIn(NS_LOGICAL_SWITCH, LOGICAL_SWITCH_COUNT)

    /** Hash of an `sdk.*` id, else null. */
    val sdkHash: Long? get() = if (namespace == NS_SDK) localName.toULongOrNull()?.toLong() else null

    /**
     * True for ids whose value changes with every live frame (inputs, channels). These are not
     * part of the throttled sensor map; they are resolved straight from the [LiveSnapshot].
     */
    val isLive: Boolean get() = namespace == NS_INPUT || namespace == NS_CHANNEL

    private fun indexIn(ns: String, count: Int): Int? =
        if (namespace == ns) localName.toIntOrNull()?.minus(1)?.takeIf { it in 0 until count } else null

    override fun compareTo(other: SensorId): Int = key.compareTo(other.key)

    override fun toString(): String = key

    companion object {
        const val NS_CRSF = "crsf"
        const val NS_SDK = "sdk"
        const val NS_TX = "tx"
        const val NS_INPUT = "in"
        const val NS_CHANNEL = "ch"
        const val NS_TIMER = "timer"
        const val NS_COUNTER = "cnt"
        const val NS_LOGICAL_SWITCH = "ls"

        /** `sdk.<hash>` for an SDK telemetry item (hash printed as unsigned decimal). */
        fun sdk(hash: Long): SensorId = SensorId("$NS_SDK.${hash.toULong()}")

        /** `in.<default name>` for an input source. */
        fun input(source: SourceId): SensorId = SensorId("$NS_INPUT.${source.defaultName}")

        /** `ch.<n>` for a 0-based channel index. */
        fun channel(index: Int): SensorId = SensorId("$NS_CHANNEL.${index + 1}")

        /** `timer.<n>` for a 0-based timer index. */
        fun timer(index: Int): SensorId = SensorId("$NS_TIMER.${index + 1}")

        /** `cnt.<n>` for a 0-based counter index. */
        fun counter(index: Int): SensorId = SensorId("$NS_COUNTER.${index + 1}")

        /** `ls.<n>` for a 0-based logical switch index. */
        fun logicalSwitch(index: Int): SensorId = SensorId("$NS_LOGICAL_SWITCH.${index + 1}")
    }
}

/**
 * Well-known sensor ids. The short EdgeTX-style template names (`RxBt`, `Alt`, `VSpd`, `LQ`, …)
 * that resolve to them are listed in [templateNames].
 */
object Sensors {
    // Transmitter / link (from RadioStatus)
    val TX_BATTERY = SensorId("tx.batt")
    val TX_LINK_QUALITY = SensorId("tx.lq")
    val TX_RSSI = SensorId("tx.rssi")
    val TX_RX_BATTERY = SensorId("tx.rxbatt")

    // Internal
    val FLIGHT_MODE = SensorId("fm")

    // CRSF GPS frame 0x02
    val GPS_LATITUDE = SensorId("crsf.gps.lat")
    val GPS_LONGITUDE = SensorId("crsf.gps.lon")
    val GPS_GROUND_SPEED = SensorId("crsf.gps.gspd")
    val GPS_HEADING = SensorId("crsf.gps.hdg")
    val GPS_ALTITUDE = SensorId("crsf.gps.alt")
    val GPS_SATELLITES = SensorId("crsf.gps.sats")

    /** Distance to the home position in metres (computed by the telemetry repository). */
    val GPS_HOME_DISTANCE = SensorId("crsf.gps.dist")

    /** Bearing to the home position in degrees (computed by the telemetry repository). */
    val GPS_HOME_BEARING = SensorId("crsf.gps.bearing")

    // CRSF vario frame 0x07
    val VERTICAL_SPEED = SensorId("crsf.vspd")

    // CRSF battery frame 0x08
    val BATTERY_VOLTAGE = SensorId("crsf.batt.v")
    val BATTERY_CURRENT = SensorId("crsf.batt.a")
    val BATTERY_CAPACITY = SensorId("crsf.batt.mah")
    val BATTERY_REMAINING = SensorId("crsf.batt.pct")

    // CRSF baro altitude frame 0x09
    val BARO_ALTITUDE = SensorId("crsf.alt")

    /** Vertical speed carried in the baro altitude frame 0x09 (when present). */
    val BARO_VERTICAL_SPEED = SensorId("crsf.alt.vspd")

    // CRSF airspeed frame 0x0A
    val AIRSPEED = SensorId("crsf.aspd")

    // CRSF link statistics frame 0x14
    val LINK_RSSI_1 = SensorId("crsf.link.rssi1")
    val LINK_RSSI_2 = SensorId("crsf.link.rssi2")
    val LINK_RX_QUALITY = SensorId("crsf.link.rqly")
    val LINK_RX_SNR = SensorId("crsf.link.rsnr")
    val LINK_ANTENNA = SensorId("crsf.link.ant")
    val LINK_RF_MODE = SensorId("crsf.link.rfmd")
    val LINK_TX_POWER = SensorId("crsf.link.tpwr")
    val LINK_TX_RSSI = SensorId("crsf.link.trss")
    val LINK_TX_QUALITY = SensorId("crsf.link.tqly")
    val LINK_TX_SNR = SensorId("crsf.link.tsnr")

    // CRSF attitude frame 0x1E
    val ATTITUDE_PITCH = SensorId("crsf.att.pitch")
    val ATTITUDE_ROLL = SensorId("crsf.att.roll")
    val ATTITUDE_YAW = SensorId("crsf.att.yaw")

    // CRSF flight mode frame 0x21 (text sensor)
    val FC_FLIGHT_MODE = SensorId("crsf.fm")

    /** Rotorflight custom telemetry (frame 0x88): `crsf.rf.<sid as 4 hex digits>`, e.g. `crsf.rf.1011`. */
    fun rotorflight(sid: Int): SensorId = SensorId("crsf.rf.${sid.toString(16).padStart(4, '0')}")

    /**
     * Short names usable in text templates and their sensor, EdgeTX style. Lookup is
     * case-insensitive. Inputs (`SA`, `Thr`), channels (`CH3`), timers (`T1`), counters (`CNT1`),
     * logical switches (`L4`), `FM` and `model` are resolved by the value resolver itself and are
     * not listed here.
     */
    val templateNames: Map<String, SensorId> = mapOf(
        "TxBt" to TX_BATTERY,
        "LQ" to TX_LINK_QUALITY,
        "RSSI" to TX_RSSI,
        "dBm" to TX_RSSI,
        "RxBt" to BATTERY_VOLTAGE,
        "Curr" to BATTERY_CURRENT,
        "Capa" to BATTERY_CAPACITY,
        "Bat%" to BATTERY_REMAINING,
        "Alt" to BARO_ALTITUDE,
        "VSpd" to VERTICAL_SPEED,
        "ASpd" to AIRSPEED,
        "GSpd" to GPS_GROUND_SPEED,
        "Hdg" to GPS_HEADING,
        "GAlt" to GPS_ALTITUDE,
        "Sats" to GPS_SATELLITES,
        "Dist" to GPS_HOME_DISTANCE,
        "Ptch" to ATTITUDE_PITCH,
        "Roll" to ATTITUDE_ROLL,
        "Yaw" to ATTITUDE_YAW,
        "1RSS" to LINK_RSSI_1,
        "2RSS" to LINK_RSSI_2,
        "RQly" to LINK_RX_QUALITY,
        "RSNR" to LINK_RX_SNR,
        "TPWR" to LINK_TX_POWER,
        "TRSS" to LINK_TX_RSSI,
        "TQly" to LINK_TX_QUALITY,
        "TSNR" to LINK_TX_SNR,
    )

    /** Sensor for a short template name (case-insensitive), or null. */
    fun byTemplateName(name: String): SensorId? =
        templateNames.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** Physical unit of a sensor value. [symbol] is the short display suffix; spoken names live in `:core:audio`. */
@Serializable
enum class SensorUnit(val symbol: String) {
    NONE(""),
    VOLT("V"),
    AMPERE("A"),
    MILLIAMPERE_HOUR("mAh"),
    WATT("W"),
    MILLIWATT("mW"),
    PERCENT("%"),
    DBM("dBm"),
    DB("dB"),
    METER("m"),
    FEET("ft"),
    KILOMETER("km"),
    MILE("mi"),
    METER_PER_SECOND("m/s"),
    FEET_PER_SECOND("ft/s"),
    KMH("km/h"),
    MPH("mph"),
    KNOT("kn"),
    DEGREE("°"),
    CELSIUS("°C"),
    FAHRENHEIT("°F"),
    RPM("rpm"),
    HERTZ("Hz"),
    SECOND("s"),
    G("g"),

    /** Geographic coordinate in decimal degrees. */
    COORDINATE("°"),

    /** A count without unit (satellites, packets). */
    COUNT(""),

    /** The sensor is textual ([SensorValue.text] carries the content). */
    TEXT(""),
}

/** Where a sensor value comes from. */
@Serializable
enum class SensorOrigin {
    /** The SDK telemetry list. */
    SDK,

    /** Decoded by the app from raw CRSF frames. */
    CRSF,

    /** Transmitter / app internal (voltage, link, timers, inputs, channels, flight mode, logical switches). */
    INTERNAL,
}

/**
 * Current value of one sensor. Not persisted.
 *
 * @property value numeric value in [unit]; `NaN` for purely textual sensors or before the first sample
 * @property text preformatted text for textual sensors (flight mode name) or SDK-formatted values; null otherwise
 * @property precision number of decimals to display / speak
 * @property min smallest value seen since the last reset; `NaN` if none
 * @property max largest value seen since the last reset; `NaN` if none
 * @property timestampMs monotonic time of the last update (same base as [LiveSnapshot.timestampMs])
 * @property lost true if the sensor stopped updating (telemetry lost or the sensor timed out); the
 *   last value is kept
 */
data class SensorValue(
    val value: Double = Double.NaN,
    val text: String? = null,
    val unit: SensorUnit = SensorUnit.NONE,
    val precision: Int = 0,
    val min: Double = Double.NaN,
    val max: Double = Double.NaN,
    val timestampMs: Long = 0,
    val lost: Boolean = false,
) {
    /** False while the sensor has not delivered a number. */
    val hasValue: Boolean get() = !value.isNaN()
    /** True once minimum and maximum were recorded. */
    val hasMinMax: Boolean get() = !min.isNaN() && !max.isNaN()

    /** Age of the value in ms relative to [nowMs] (monotonic). */
    fun ageMs(nowMs: Long): Long = (nowMs - timestampMs).coerceAtLeast(0)

    companion object {
        /** Placeholder for a sensor that has never reported. */
        val UNKNOWN = SensorValue(lost = true)
    }
}

/**
 * Static description of a sensor in the registry, with the per-model overrides already applied.
 *
 * @property name display name (override or default)
 * @property defaultName name before overrides
 * @property templateName short name usable in templates (`RxBt`), if any
 */
data class SensorInfo(
    val id: SensorId,
    val name: String,
    val defaultName: String = name,
    val origin: SensorOrigin = SensorOrigin.INTERNAL,
    val unit: SensorUnit = SensorUnit.NONE,
    val precision: Int = 0,
    val templateName: String? = null,
    val hidden: Boolean = false,
)

/**
 * A GPS position, used for the home position and the "last known position" card.
 *
 * @property timeMs wall-clock time (`System.currentTimeMillis()`) of the fix
 */
@Serializable
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val groundSpeed: Double = 0.0,
    val heading: Double = 0.0,
    val satellites: Int = 0,
    val timeMs: Long = 0,
)
