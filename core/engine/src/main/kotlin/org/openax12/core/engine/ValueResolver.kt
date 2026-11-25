package org.openax12.core.engine

import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.LOGICAL_SWITCH_COUNT
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceKind
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TimerState
import org.openax12.core.model.UnitConversion
import org.openax12.core.model.UnitSystem

/**
 * A value looked up for a template placeholder, a logical-switch operand or a spoken value.
 *
 * @property id the sensor id the lookup resolved to; null for pseudo values such as `{model}`
 * @property name display name of the value ("Altitude", "SA", "CH3")
 * @property number numeric value in [unit], already converted to the user's unit system; `NaN`
 *   for purely textual values
 * @property text display text without unit: the formatted number, or the text of textual values
 *   (flight mode name, model name, switch position `up`/`mid`/`down`, timer as `m:ss`)
 * @property unit unit of [number] after conversion
 * @property precision decimals used for [text]
 * @property lost true if the underlying sensor is lost / has never reported
 */
data class ResolvedValue(
    val id: SensorId?,
    val name: String,
    val number: Double,
    val text: String,
    val unit: SensorUnit = SensorUnit.NONE,
    val precision: Int = 0,
    val lost: Boolean = false,
) {
    /** True if the value is a number (not text-only). */
    val isNumeric: Boolean get() = !number.isNaN()
}

/**
 * Resolves names and ids to values at the current tick. Used by the template renderer, the
 * logical-switch / trigger / alarm evaluators, and by editors for live previews.
 *
 * Name lookup ([resolve] with a string, case-insensitive), first match wins:
 * 1. `model` → model name; `FM` → flight mode name
 * 2. input sources by default name or user name: `Thr`, `SA`, `P1` → logic value in percent
 *    (switches additionally get the text `up` / `mid` / `down`)
 * 3. `CH1`..`CH16` → channel output in percent
 * 4. `T1`..`T3` → timer (seconds as number, `m:ss` as text); `CNT1`..`CNT3` → counter value
 * 5. `L1`..`L32` → logical switch (1 / 0)
 * 6. template short names of `Sensors.templateNames` (`RxBt`, `Alt`, `VSpd`, `LQ`, …)
 * 7. sensor display names (with per-model overrides) and raw sensor id keys (`crsf.alt`)
 */
interface ValueResolver {

    /** Resolves a template name; null if nothing matches. */
    fun resolve(name: String): ResolvedValue?

    /** Resolves a sensor id (any namespace); null if the id is unknown. */
    fun resolve(sensor: SensorId): ResolvedValue?

    /** Logic value of an input source in percent. */
    fun resolve(source: SourceId): ResolvedValue

    /** Output of channel [index] (0-based) in percent; null if out of range. */
    fun resolveChannel(index: Int): ResolvedValue?

    /** The sensor id a template name stands for, without reading its value; null if unknown or a pseudo value. */
    fun sensorIdOf(name: String): SensorId?

    /**
     * Numeric value of [sensor] in its **native** unit (no unit-system conversion) — what
     * conditions and alarms compare against. Null if unknown, textual or lost.
     */
    fun number(sensor: SensorId): Double?

    /** All names that currently resolve, for auto-completion in the template editor. */
    fun knownNames(): List<String>
}

/**
 * The [ValueResolver] over one [EngineTick].
 *
 * @param sensorInfo registry metadata (names, units, precision) of the sensors in the tick
 * @param units unit system for [ResolvedValue.number] / [ResolvedValue.text]
 */
class EngineValueResolver(
    private val tick: EngineTick,
    private val extras: ModelExtras,
    private val sensorInfo: List<SensorInfo> = emptyList(),
    private val sourceNames: Map<SourceId, String> = emptyMap(),
    private val units: UnitSystem = UnitSystem.METRIC,
) : ValueResolver {

    private val infoById: Map<SensorId, SensorInfo> by lazy { sensorInfo.associateBy { it.id } }

    override fun resolve(name: String): ResolvedValue? {
        val key = name.trim()
        if (key.isEmpty()) return null
        if (key.equals(NAME_MODEL, ignoreCase = true)) {
            return ResolvedValue(id = null, name = NAME_MODEL, number = Double.NaN, text = tick.modelName)
        }
        val id = sensorIdOf(key) ?: return null
        return resolve(id)
    }

    override fun resolve(sensor: SensorId): ResolvedValue? {
        sensor.source?.let { return resolve(it) }
        sensor.channelIndex?.let { return resolveChannel(it) }
        sensor.timerIndex?.let { index -> return timerValue(tick.live.timer(index), "T${index + 1}", sensor) }
        sensor.counterIndex?.let { index -> return counterValue(tick.live.counter(index), "CNT${index + 1}", sensor) }
        sensor.logicalSwitchIndex?.let { index ->
            val on = tick.logicalSwitches[index]
            val label = extras.logicalSwitch(index).name.ifBlank { "L${index + 1}" }
            return ResolvedValue(sensor, label, if (on) 1.0 else 0.0, if (on) "1" else "0")
        }
        if (sensor == Sensors.FLIGHT_MODE) {
            val modeName = tick.flightModeName.ifBlank { extras.flightModeName(tick.flightMode) }
            return ResolvedValue(
                id = sensor,
                name = NAME_FLIGHT_MODE,
                number = tick.flightMode.toDouble(),
                text = modeName.ifBlank { tick.flightMode.toString() },
            )
        }
        if (isStructuralNamespace(sensor)) return null
        val info = infoById[sensor]
        val value = sensorValue(sensor)
        if (info == null && value == null) return null
        return sensorResolved(sensor, info, value ?: SensorValue.UNKNOWN)
    }

    override fun resolve(source: SourceId): ResolvedValue {
        val logic = tick.live.logic(source)
        val name = sourceNames[source]?.takeIf { it.isNotBlank() } ?: source.defaultName
        val id = SensorId.input(source)
        return when (source.kind) {
            SourceKind.SWITCH -> ResolvedValue(
                id = id,
                name = name,
                number = logic.toDouble(),
                text = positionText(SwitchPosition.fromLogic(logic)),
            )
            SourceKind.COUNTER -> ResolvedValue(id, name, logic.toDouble(), logic.toString())
            else -> ResolvedValue(id, name, logic.toDouble(), logic.toString(), SensorUnit.PERCENT)
        }
    }

    override fun resolveChannel(index: Int): ResolvedValue? {
        if (index !in 0 until CHANNEL_COUNT || index >= tick.live.channelCount) return null
        val value = tick.live.channel(index)
        return ResolvedValue(SensorId.channel(index), "CH${index + 1}", value.toDouble(), value.toString(), SensorUnit.PERCENT)
    }

    override fun sensorIdOf(name: String): SensorId? {
        val key = name.trim()
        if (key.isEmpty() || key.equals(NAME_MODEL, ignoreCase = true)) return null
        if (key.equals(NAME_FLIGHT_MODE, ignoreCase = true)) return Sensors.FLIGHT_MODE

        // Counters before sources: CNT1..CNT3 are also the default names of counter *sources*, but
        // a template means the counter's value.
        numbered(key, "CNT", COUNTER_COUNT)?.let { return SensorId.counter(it) }
        SourceId.fromName(key)?.let { return SensorId.input(it) }
        sourceNames.entries.firstOrNull { it.value.isNotBlank() && it.value.equals(key, ignoreCase = true) }
            ?.let { return SensorId.input(it.key) }
        numbered(key, "CH", CHANNEL_COUNT)?.let { return SensorId.channel(it) }
        numbered(key, "T", TIMER_COUNT)?.let { return SensorId.timer(it) }
        numbered(key, "L", LOGICAL_SWITCH_COUNT)?.let { return SensorId.logicalSwitch(it) }
        Sensors.byTemplateName(key)?.let { return it }
        sensorInfo.firstOrNull { it.templateName?.equals(key, ignoreCase = true) == true }?.let { return it.id }
        sensorInfo.firstOrNull { displayName(it.id, it).equals(key, ignoreCase = true) }?.let { return it.id }
        extras.sensorOverrides.entries.firstOrNull { it.value.name?.equals(key, ignoreCase = true) == true }
            ?.let { return SensorId(it.key) }

        // Raw sensor id keys ("crsf.alt", "in.SA", "ch.3", "timer.1", "fm").
        val raw = SensorId(key)
        if (isKnownId(raw)) return raw
        val lower = SensorId(key.lowercase())
        return if (isKnownId(lower)) lower else null
    }

    override fun number(sensor: SensorId): Double? {
        sensor.source?.let { return tick.live.logic(it).toDouble() }
        sensor.channelIndex?.let { index ->
            return if (index < tick.live.channelCount) tick.live.channel(index).toDouble() else null
        }
        sensor.timerIndex?.let { return tick.live.timer(it)?.count?.toDouble() }
        sensor.counterIndex?.let { return tick.live.counter(it)?.count?.toDouble() }
        sensor.logicalSwitchIndex?.let { return if (tick.logicalSwitches[it]) 1.0 else 0.0 }
        if (sensor == Sensors.FLIGHT_MODE) return tick.flightMode.toDouble()
        if (isStructuralNamespace(sensor)) return null
        val value = sensorValue(sensor) ?: return null
        return if (value.lost || !value.hasValue) null else value.value
    }

    override fun knownNames(): List<String> {
        val names = LinkedHashSet<String>()
        names += NAME_MODEL
        names += NAME_FLIGHT_MODE
        for (source in SourceId.all) {
            if (source.isCounter && source.index - SourceId.counter(1).index < COUNTER_COUNT) continue
            names += source.defaultName
        }
        sourceNames.values.filterTo(names) { it.isNotBlank() }
        for (i in 0 until minOf(CHANNEL_COUNT, tick.live.channelCount)) names += "CH${i + 1}"
        for (i in 0 until TIMER_COUNT) names += "T${i + 1}"
        for (i in 0 until COUNTER_COUNT) names += "CNT${i + 1}"
        extras.logicalSwitches.filter { it.isUsed && it.index in 0 until LOGICAL_SWITCH_COUNT }
            .sortedBy { it.index }.forEach { names += it.label }
        for ((templateName, id) in Sensors.templateNames) if (isKnownId(id)) names += templateName
        for (info in sensorInfo) {
            info.templateName?.takeIf { it.isNotBlank() }?.let { names += it }
            displayName(info.id, info).takeIf { it.isNotBlank() }?.let { names += it }
        }
        return names.toList()
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Namespaces whose ids are resolved from the live frame; an id that did not parse above is invalid. */
    private fun isStructuralNamespace(id: SensorId): Boolean = when (id.namespace) {
        SensorId.NS_INPUT, SensorId.NS_CHANNEL, SensorId.NS_TIMER, SensorId.NS_COUNTER, SensorId.NS_LOGICAL_SWITCH -> true
        else -> false
    }

    private fun isKnownId(id: SensorId): Boolean =
        id.source != null || id.channelIndex != null || id.timerIndex != null || id.counterIndex != null ||
            id.logicalSwitchIndex != null || id == Sensors.FLIGHT_MODE ||
            (!isStructuralNamespace(id) && (sensorValue(id) != null || infoById.containsKey(id)))

    /** The sensor's value from the tick, or — for the transmitter values — from the live status. */
    private fun sensorValue(id: SensorId): SensorValue? {
        tick.sensors[id]?.let { return it }
        val status = tick.live.status
        val now = tick.live.timestampMs
        return when (id) {
            Sensors.TX_BATTERY -> SensorValue(status.txVoltage.toDouble(), unit = SensorUnit.VOLT, precision = 1, timestampMs = now)
            Sensors.TX_LINK_QUALITY -> SensorValue(status.linkQuality.toDouble(), unit = SensorUnit.PERCENT, timestampMs = now)
            Sensors.TX_RSSI -> SensorValue(status.rssiDbm.toDouble(), unit = SensorUnit.DBM, timestampMs = now, lost = !tick.linkUp)
            Sensors.TX_RX_BATTERY ->
                SensorValue(status.rxVoltage.toDouble(), unit = SensorUnit.VOLT, precision = 1, timestampMs = now, lost = !tick.linkUp)
            else -> null
        }
    }

    private fun displayName(id: SensorId, info: SensorInfo?): String =
        extras.sensorOverride(id)?.name?.takeIf { it.isNotBlank() }
            ?: info?.name?.takeIf { it.isNotBlank() }
            ?: info?.templateName
            ?: Sensors.templateNames.entries.firstOrNull { it.value == id }?.key
            ?: id.key

    private fun sensorResolved(id: SensorId, info: SensorInfo?, value: SensorValue): ResolvedValue {
        val override = extras.sensorOverride(id)
        val name = displayName(id, info)
        val baseUnit = override?.unit ?: info?.unit?.takeIf { it != SensorUnit.NONE } ?: value.unit
        val precision = (override?.precision ?: info?.precision?.takeIf { info.unit != SensorUnit.NONE || it != 0 } ?: value.precision)
            .coerceIn(0, TemplateRenderer.MAX_PRECISION)
        if (!value.hasValue) {
            val text = value.text.orEmpty()
            return ResolvedValue(
                id = id,
                name = name,
                number = Double.NaN,
                text = text,
                unit = if (text.isEmpty()) baseUnit else SensorUnit.TEXT,
                precision = precision,
                lost = value.lost || text.isEmpty(),
            )
        }
        val display = UnitConversion.convert(value.value, baseUnit, units, precision)
        return ResolvedValue(
            id = id,
            name = name,
            number = display.value,
            text = UnitConversion.formatNumber(display.value, display.precision),
            unit = display.unit,
            precision = display.precision,
            lost = value.lost,
        )
    }

    private fun timerValue(state: TimerState?, name: String, id: SensorId): ResolvedValue? {
        state ?: return null
        return ResolvedValue(id, name, state.count.toDouble(), UnitConversion.formatTime(state.count), SensorUnit.SECOND)
    }

    private fun counterValue(state: TimerState?, name: String, id: SensorId): ResolvedValue? {
        state ?: return null
        return ResolvedValue(id, name, state.count.toDouble(), state.count.toString(), SensorUnit.COUNT)
    }

    /** Index (0-based) of `<prefix><n>` with n in 1..[count], case-insensitive; null otherwise. */
    private fun numbered(name: String, prefix: String, count: Int): Int? {
        if (name.length <= prefix.length || !name.startsWith(prefix, ignoreCase = true)) return null
        val digits = name.substring(prefix.length)
        if (digits.any { !it.isDigit() } || digits.length > 3) return null
        return digits.toIntOrNull()?.minus(1)?.takeIf { it in 0 until count }
    }

    private fun positionText(position: SwitchPosition): String = when (position) {
        SwitchPosition.UP -> POSITION_UP
        SwitchPosition.MID -> POSITION_MID
        SwitchPosition.DOWN -> POSITION_DOWN
    }

    companion object {
        /** Template name of the model name. */
        const val NAME_MODEL: String = "model"

        /** Template name of the active flight mode. */
        const val NAME_FLIGHT_MODE: String = "FM"

        /** [ResolvedValue.text] of a switch in the up position. */
        const val POSITION_UP: String = "up"

        /** [ResolvedValue.text] of a switch in the middle position. */
        const val POSITION_MID: String = "mid"

        /** [ResolvedValue.text] of a switch in the down position. */
        const val POSITION_DOWN: String = "down"
    }
}
