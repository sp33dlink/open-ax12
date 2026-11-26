package org.openax12.core.data.telemetry

import org.openax12.core.data.crsf.RotorflightSensors
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorOrigin
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.Sources

/** When a sensor counts as lost. */
internal enum class LostPolicy {
    /** Transmitter-internal values: always current. */
    NEVER,

    /** Lost only while the receiver link is down. */
    LINK,

    /** Lost while the link is down or when no update arrived for the sensor timeout. */
    LINK_OR_TIMEOUT,
}

/** Default metadata of every sensor the app knows by itself (before per-model overrides). */
internal object SensorCatalog {

    private data class Def(val name: String, val unit: SensorUnit, val precision: Int, val policy: LostPolicy)

    private val CRSF = LostPolicy.LINK_OR_TIMEOUT

    private val WELL_KNOWN: Map<SensorId, Def> = mapOf(
        Sensors.TX_BATTERY to Def("TX battery", SensorUnit.VOLT, 1, LostPolicy.NEVER),
        Sensors.TX_LINK_QUALITY to Def("Link quality", SensorUnit.PERCENT, 0, LostPolicy.NEVER),
        Sensors.TX_RSSI to Def("RSSI", SensorUnit.DBM, 0, LostPolicy.NEVER),
        Sensors.TX_RX_BATTERY to Def("RX battery", SensorUnit.VOLT, 1, LostPolicy.LINK),
        Sensors.FLIGHT_MODE to Def("Flight mode", SensorUnit.TEXT, 0, LostPolicy.NEVER),

        Sensors.GPS_LATITUDE to Def("Latitude", SensorUnit.COORDINATE, 6, CRSF),
        Sensors.GPS_LONGITUDE to Def("Longitude", SensorUnit.COORDINATE, 6, CRSF),
        Sensors.GPS_GROUND_SPEED to Def("Ground speed", SensorUnit.KMH, 1, CRSF),
        Sensors.GPS_HEADING to Def("Heading", SensorUnit.DEGREE, 0, CRSF),
        Sensors.GPS_ALTITUDE to Def("GPS altitude", SensorUnit.METER, 0, CRSF),
        Sensors.GPS_SATELLITES to Def("Satellites", SensorUnit.COUNT, 0, CRSF),
        Sensors.GPS_HOME_DISTANCE to Def("Distance", SensorUnit.METER, 0, CRSF),
        Sensors.GPS_HOME_BEARING to Def("Bearing", SensorUnit.DEGREE, 0, CRSF),

        Sensors.VERTICAL_SPEED to Def("Vertical speed", SensorUnit.METER_PER_SECOND, 1, CRSF),

        Sensors.BATTERY_VOLTAGE to Def("Battery", SensorUnit.VOLT, 1, CRSF),
        Sensors.BATTERY_CURRENT to Def("Current", SensorUnit.AMPERE, 1, CRSF),
        Sensors.BATTERY_CAPACITY to Def("Capacity used", SensorUnit.MILLIAMPERE_HOUR, 0, CRSF),
        Sensors.BATTERY_REMAINING to Def("Battery remaining", SensorUnit.PERCENT, 0, CRSF),

        Sensors.BARO_ALTITUDE to Def("Altitude", SensorUnit.METER, 1, CRSF),
        Sensors.BARO_VERTICAL_SPEED to Def("Vertical speed (baro)", SensorUnit.METER_PER_SECOND, 1, CRSF),
        Sensors.AIRSPEED to Def("Airspeed", SensorUnit.KMH, 1, CRSF),

        Sensors.LINK_RSSI_1 to Def("RX RSSI 1", SensorUnit.DBM, 0, CRSF),
        Sensors.LINK_RSSI_2 to Def("RX RSSI 2", SensorUnit.DBM, 0, CRSF),
        Sensors.LINK_RX_QUALITY to Def("RX quality", SensorUnit.PERCENT, 0, CRSF),
        Sensors.LINK_RX_SNR to Def("RX SNR", SensorUnit.DB, 0, CRSF),
        Sensors.LINK_ANTENNA to Def("Antenna", SensorUnit.COUNT, 0, CRSF),
        Sensors.LINK_RF_MODE to Def("RF mode", SensorUnit.COUNT, 0, CRSF),
        Sensors.LINK_TX_POWER to Def("TX power", SensorUnit.MILLIWATT, 0, CRSF),
        Sensors.LINK_TX_RSSI to Def("TX RSSI", SensorUnit.DBM, 0, CRSF),
        Sensors.LINK_TX_QUALITY to Def("TX quality", SensorUnit.PERCENT, 0, CRSF),
        Sensors.LINK_TX_SNR to Def("TX SNR", SensorUnit.DB, 0, CRSF),

        Sensors.ATTITUDE_PITCH to Def("Pitch", SensorUnit.DEGREE, 1, CRSF),
        Sensors.ATTITUDE_ROLL to Def("Roll", SensorUnit.DEGREE, 1, CRSF),
        Sensors.ATTITUDE_YAW to Def("Yaw", SensorUnit.DEGREE, 1, CRSF),

        Sensors.FC_FLIGHT_MODE to Def("FC flight mode", SensorUnit.TEXT, 0, CRSF),
    )

    /** Template name per sensor: the first name listed in [Sensors.templateNames]. */
    private val TEMPLATE_NAMES: Map<SensorId, String> = buildMap {
        for ((name, id) in Sensors.templateNames) if (id !in this) put(id, name)
    }

    /** Sensors that exist from the start (transmitter values, timers, counters). */
    val ALWAYS_PRESENT: List<SensorId> = buildList {
        add(Sensors.TX_BATTERY)
        add(Sensors.TX_LINK_QUALITY)
        add(Sensors.TX_RSSI)
        add(Sensors.TX_RX_BATTERY)
        for (i in 0 until org.openax12.core.model.TIMER_COUNT) add(SensorId.timer(i))
        for (i in 0 until org.openax12.core.model.COUNTER_COUNT) add(SensorId.counter(i))
    }

    /** Ids of all input sources and output channels (served from the live frame). */
    val LIVE: List<SensorId> = buildList {
        for (index in Sources.ALL) add(SensorId.input(SourceId(index)))
        for (channel in 0 until org.openax12.core.model.CHANNEL_COUNT) add(SensorId.channel(channel))
    }

    /** True if the catalog has a real (readable) name for [id], i.e. [defaultInfo] is more than a copy of the key. */
    fun hasReadableName(id: SensorId): Boolean = id in WELL_KNOWN || RotorflightSensors.forSensor(id) != null

    /** Default metadata of [id]; a generic entry named after the id if nothing is known. */
    fun defaultInfo(id: SensorId): SensorInfo {
        WELL_KNOWN[id]?.let { def ->
            return SensorInfo(
                id = id,
                name = def.name,
                origin = originOf(id),
                unit = def.unit,
                precision = def.precision,
                templateName = TEMPLATE_NAMES[id],
            )
        }
        id.source?.let { source ->
            return SensorInfo(id, source.defaultName, origin = SensorOrigin.INTERNAL, unit = SensorUnit.PERCENT, templateName = source.defaultName)
        }
        id.channelIndex?.let { channel ->
            val name = "CH${channel + 1}"
            return SensorInfo(id, name, origin = SensorOrigin.INTERNAL, unit = SensorUnit.PERCENT, templateName = name)
        }
        id.timerIndex?.let { timer ->
            return SensorInfo(id, "Timer ${timer + 1}", origin = SensorOrigin.INTERNAL, unit = SensorUnit.SECOND, templateName = "T${timer + 1}")
        }
        id.counterIndex?.let { counter ->
            return SensorInfo(id, "Counter ${counter + 1}", origin = SensorOrigin.INTERNAL, unit = SensorUnit.COUNT, templateName = "CNT${counter + 1}")
        }
        id.logicalSwitchIndex?.let { index ->
            val name = "L${index + 1}"
            return SensorInfo(id, name, origin = SensorOrigin.INTERNAL, unit = SensorUnit.NONE, templateName = name)
        }
        RotorflightSensors.forSensor(id)?.let { def ->
            val average = id.key.endsWith(RotorflightSensors.AVERAGE_SUFFIX)
            return SensorInfo(
                id = id,
                name = if (average) def.name + " avg" else def.name,
                origin = SensorOrigin.CRSF,
                unit = def.unit,
                precision = def.precision,
            )
        }
        return SensorInfo(id = id, name = id.localName.ifEmpty { id.key }, origin = originOf(id))
    }

    /** Lost policy of [id]. */
    fun policyOf(id: SensorId): LostPolicy = WELL_KNOWN[id]?.policy ?: when (id.namespace) {
        SensorId.NS_CRSF, SensorId.NS_SDK -> LostPolicy.LINK_OR_TIMEOUT
        else -> LostPolicy.NEVER
    }

    private fun originOf(id: SensorId): SensorOrigin = when (id.namespace) {
        SensorId.NS_CRSF -> SensorOrigin.CRSF
        SensorId.NS_SDK -> SensorOrigin.SDK
        else -> SensorOrigin.INTERNAL
    }

    /** Display order of the namespaces in the sensor list. */
    private val GROUP_ORDER = listOf(
        SensorId.NS_TX,
        SensorId.NS_CRSF,
        SensorId.NS_SDK,
        SensorId.NS_TIMER,
        SensorId.NS_COUNTER,
        "fm",
        SensorId.NS_LOGICAL_SWITCH,
        SensorId.NS_INPUT,
        SensorId.NS_CHANNEL,
    )

    /**
     * Comparator of the sensor list: by group, then by a natural order inside the group (source
     * index, channel number, catalog order for well-known sensors, key otherwise).
     */
    val DISPLAY_ORDER: Comparator<SensorInfo> = compareBy<SensorInfo>(
        { GROUP_ORDER.indexOf(it.id.namespace).let { index -> if (index < 0) GROUP_ORDER.size else index } },
        { rankInGroup(it.id) },
        { it.id.key },
    )

    private val WELL_KNOWN_RANK: Map<SensorId, Int> = WELL_KNOWN.keys.withIndex().associate { (index, id) -> id to index }

    private fun rankInGroup(id: SensorId): Int =
        id.source?.index
            ?: id.channelIndex
            ?: id.timerIndex
            ?: id.counterIndex
            ?: id.logicalSwitchIndex
            ?: WELL_KNOWN_RANK[id]
            ?: Int.MAX_VALUE
}

/** Guesses unit and precision of an SDK telemetry item from its SDK-formatted text (`"11.8V"`). */
internal object SdkValueText {

    private val UNITS: List<Pair<String, SensorUnit>> = listOf(
        "mah" to SensorUnit.MILLIAMPERE_HOUR,
        "km/h" to SensorUnit.KMH,
        "kmh" to SensorUnit.KMH,
        "mph" to SensorUnit.MPH,
        "m/s" to SensorUnit.METER_PER_SECOND,
        "ft/s" to SensorUnit.FEET_PER_SECOND,
        "dbm" to SensorUnit.DBM,
        "db" to SensorUnit.DB,
        "rpm" to SensorUnit.RPM,
        "mw" to SensorUnit.MILLIWATT,
        "°c" to SensorUnit.CELSIUS,
        "°f" to SensorUnit.FAHRENHEIT,
        "hz" to SensorUnit.HERTZ,
        "km" to SensorUnit.KILOMETER,
        "kn" to SensorUnit.KNOT,
        "ft" to SensorUnit.FEET,
        "%" to SensorUnit.PERCENT,
        "°" to SensorUnit.DEGREE,
        "v" to SensorUnit.VOLT,
        "a" to SensorUnit.AMPERE,
        "w" to SensorUnit.WATT,
        "m" to SensorUnit.METER,
        "s" to SensorUnit.SECOND,
        "g" to SensorUnit.G,
    )

    private val NUMBER = Regex("""^\s*[-+]?\d+(?:[.,](\d+))?\s*(.*?)\s*$""")

    /**
     * Unit and number of decimals found in [text]. A text that does not start with a number is
     * a textual value: [SensorUnit.TEXT]. A number without a known suffix has [SensorUnit.NONE].
     */
    fun parse(text: String): Pair<SensorUnit, Int> {
        if (text.isBlank()) return SensorUnit.NONE to 0
        val match = NUMBER.matchEntire(text) ?: return SensorUnit.TEXT to 0
        val decimals = match.groupValues[1].length.coerceAtMost(6)
        val suffix = match.groupValues[2].lowercase()
        val unit = UNITS.firstOrNull { it.first == suffix }?.second ?: SensorUnit.NONE
        return unit to decimals
    }
}
