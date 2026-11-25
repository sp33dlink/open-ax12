package org.openax12.feature.functions.common

import androidx.compose.runtime.Immutable
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.WorkingConfig

/** An input source that exists on the radio, with the name the user gave it. */
@Immutable
internal data class SourceOption(val id: SourceId, val name: String)

/** Which list of the sensor picker a sensor belongs to. Declaration order = order of the groups. */
internal enum class SensorGroup { INPUT, CHANNEL, TIMER, TELEMETRY, TRANSMITTER, LOGICAL }

/** A value a logical switch, trigger or vario can read. */
@Immutable
internal data class SensorOption(
    val id: SensorId,
    val name: String,
    val unit: SensorUnit,
    val group: SensorGroup,
)

/**
 * Everything the pickers of the editors need, collected once by a ViewModel from the working
 * config, the sensor registry, the flight modes and the sound folder.
 */
@Immutable
internal data class PickerData(
    val sources: List<SourceOption> = emptyList(),
    val sensors: List<SensorOption> = emptyList(),
    val flightModes: List<String> = emptyList(),
    val soundFiles: List<String> = emptyList(),
) {
    /** The switches SA..SP that exist. */
    val switches: List<SourceOption> get() = sources.filter { it.id.isSwitch }

    /** Sticks, knobs and sliders (everything that has a continuous value). */
    val analogSources: List<SourceOption> get() = sources.filter { it.id.isStick || it.id.isPot || it.id.isSlider }

    /** Knobs and sliders (volume / brightness controls). */
    val knobs: List<SourceOption> get() = sources.filter { it.id.isPot || it.id.isSlider }

    /** Name of [id] as the user sees it on the radio. */
    fun sourceName(id: SourceId): String = sources.firstOrNull { it.id == id }?.name ?: id.defaultName

    /** The option for [id], or null. */
    fun sensor(id: SensorId): SensorOption? = sensors.firstOrNull { it.id == id }

    /** Display name of [id]; unknown ids are shown by their key. */
    fun sensorName(id: SensorId): String = sensor(id)?.name ?: id.key

    /** Unit of [id]; [SensorUnit.NONE] when unknown. */
    fun sensorUnit(id: SensorId?): SensorUnit = id?.let { sensor(it)?.unit } ?: SensorUnit.NONE

    /** Name of flight mode [index]; blank when it has none (see `flightModeLabel`). */
    fun flightModeName(index: Int): String = flightModes.getOrNull(index).orEmpty()
}

/**
 * Sources that can be offered: the enabled ones of [config]. While the radio is not ready (no
 * sources read yet) all default sources are offered so that a model can be prepared offline.
 */
internal fun buildSourceOptions(config: WorkingConfig): List<SourceOption> {
    if (config.sources.isEmpty()) return SourceId.all.map { SourceOption(it, it.defaultName) }
    return SourceId.all.mapNotNull { id ->
        val info = config.source(id) ?: return@mapNotNull null
        if (info.enabled) SourceOption(id, config.sourceName(id)) else null
    }
}

/**
 * Sensors for the pickers: the registry's list (inputs, channels, timers, telemetry, transmitter
 * values) completed with every enabled input, the 16 channels, the timers and the counters, so
 * that a logical switch can always be built on the basics even when the registry is still empty.
 */
internal fun buildSensorOptions(registry: List<SensorInfo>, config: WorkingConfig, sources: List<SourceOption>): List<SensorOption> {
    val result = LinkedHashMap<SensorId, SensorOption>()
    fun add(option: SensorOption) {
        result.putIfAbsent(option.id, option)
    }
    for (source in sources) {
        val unit = if (source.id.isSwitch || source.id.isCounter) SensorUnit.NONE else SensorUnit.PERCENT
        add(SensorOption(SensorId.input(source.id), source.name, unit, SensorGroup.INPUT))
    }
    for (channel in 0 until CHANNEL_COUNT) {
        add(SensorOption(SensorId.channel(channel), "CH${channel + 1}", SensorUnit.PERCENT, SensorGroup.CHANNEL))
    }
    for (timer in 0 until TIMER_COUNT) {
        add(SensorOption(SensorId.timer(timer), "T${timer + 1}", SensorUnit.SECOND, SensorGroup.TIMER))
    }
    for (counter in 0 until COUNTER_COUNT) {
        add(SensorOption(SensorId.counter(counter), "CNT${counter + 1}", SensorUnit.COUNT, SensorGroup.TIMER))
    }
    for (info in registry) {
        if (info.hidden) continue
        val id = info.id
        val group = groupOf(id)
        // Inputs, channels and timers were added above with their user names.
        if (group == SensorGroup.INPUT || group == SensorGroup.CHANNEL || group == SensorGroup.TIMER) continue
        add(SensorOption(id, info.name, info.unit, group))
    }
    // The well-known telemetry sensors can be picked before the receiver ever reported them.
    for ((templateName, id) in Sensors.templateNames) {
        add(SensorOption(id, templateName, wellKnownUnit(id), groupOf(id)))
    }
    return result.values.sortedBy { it.group.ordinal }
}

/** Unit of the well-known sensors that are offered before the registry has seen them. */
internal fun wellKnownUnit(id: SensorId): SensorUnit = when (id) {
    Sensors.TX_BATTERY, Sensors.BATTERY_VOLTAGE -> SensorUnit.VOLT
    Sensors.BATTERY_CURRENT -> SensorUnit.AMPERE
    Sensors.BATTERY_CAPACITY -> SensorUnit.MILLIAMPERE_HOUR
    Sensors.BATTERY_REMAINING, Sensors.TX_LINK_QUALITY, Sensors.LINK_RX_QUALITY, Sensors.LINK_TX_QUALITY -> SensorUnit.PERCENT
    Sensors.TX_RSSI, Sensors.LINK_RSSI_1, Sensors.LINK_RSSI_2, Sensors.LINK_TX_RSSI -> SensorUnit.DBM
    Sensors.LINK_RX_SNR, Sensors.LINK_TX_SNR -> SensorUnit.DB
    Sensors.BARO_ALTITUDE, Sensors.GPS_ALTITUDE, Sensors.GPS_HOME_DISTANCE -> SensorUnit.METER
    Sensors.VERTICAL_SPEED, Sensors.BARO_VERTICAL_SPEED -> SensorUnit.METER_PER_SECOND
    Sensors.AIRSPEED, Sensors.GPS_GROUND_SPEED -> SensorUnit.KMH
    Sensors.GPS_HEADING, Sensors.ATTITUDE_PITCH, Sensors.ATTITUDE_ROLL, Sensors.ATTITUDE_YAW -> SensorUnit.DEGREE
    Sensors.GPS_SATELLITES -> SensorUnit.COUNT
    Sensors.LINK_TX_POWER -> SensorUnit.MILLIWATT
    else -> SensorUnit.NONE
}

/** The picker group of a sensor id, by its namespace. */
internal fun groupOf(id: SensorId): SensorGroup = when (id.namespace) {
    SensorId.NS_INPUT -> SensorGroup.INPUT
    SensorId.NS_CHANNEL -> SensorGroup.CHANNEL
    SensorId.NS_TIMER, SensorId.NS_COUNTER -> SensorGroup.TIMER
    SensorId.NS_LOGICAL_SWITCH -> SensorGroup.LOGICAL
    SensorId.NS_TX, "fm" -> SensorGroup.TRANSMITTER
    else -> SensorGroup.TELEMETRY
}
