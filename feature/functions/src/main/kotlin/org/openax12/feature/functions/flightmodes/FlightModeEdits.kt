package org.openax12.feature.functions.flightmodes

import org.openax12.core.model.FLIGHT_MODE_MAX
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.ModelType
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchPosition

/**
 * Pure edits of a [FlightModesConfig] draft: everything the flight-modes tab does to its draft
 * before the compiler sees it. The functions never throw and always return a draft that obeys the
 * basic rules (at most [FLIGHT_MODE_MAX] modes, one mode per switch position, values in range).
 */
internal object FlightModeEdits {

    /** Channel (0-based) the multirotor preset puts the flight-mode value on: CH5, the usual AUX1. */
    const val MULTIROTOR_MODE_CHANNEL: Int = 4

    /** The channels (0-based) that have an offset or a rate in any mode, ascending. These are the rows of the channel table. */
    fun channelRows(config: FlightModesConfig): List<Int> =
        config.modes.flatMap { it.offsets.keys + it.rates.keys }.distinct().sorted()

    /** Copy with [index] replaced by `transform(mode)`; unchanged when [index] is out of range. */
    fun updateMode(config: FlightModesConfig, index: Int, transform: (FlightModeConfig) -> FlightModeConfig): FlightModesConfig {
        if (index !in config.modes.indices) return config
        return config.copy(modes = config.modes.mapIndexed { i, mode -> if (i == index) transform(mode) else mode })
    }

    /**
     * Moves mode [index] to [position]. If another mode already sits there the two swap
     * positions, so the draft never has two modes on one position.
     */
    fun setPosition(config: FlightModesConfig, index: Int, position: SwitchPosition): FlightModesConfig {
        if (index !in config.modes.indices) return config
        val old = config.modes[index].position
        if (old == position) return config
        return config.copy(
            modes = config.modes.mapIndexed { i, mode ->
                when {
                    i == index -> mode.copy(position = position)
                    mode.position == position -> mode.copy(position = old)
                    else -> mode
                }
            },
        )
    }

    /**
     * Appends a mode on the first free switch position. The new mode carries a neutral entry
     * (offset 0, rate 100) for every channel row so that the table stays rectangular.
     * Unchanged at [FLIGHT_MODE_MAX] modes.
     */
    fun addMode(config: FlightModesConfig): FlightModesConfig {
        if (config.modes.size >= FLIGHT_MODE_MAX) return config
        val free = SwitchPosition.entries.firstOrNull { position -> config.modes.none { it.position == position } }
            ?: return config
        val rows = channelRows(config)
        val mode = FlightModeConfig(
            position = free,
            offsets = rows.associateWith { 0 },
            rates = rows.associateWith { PERCENT_MAX },
        )
        return config.copy(modes = config.modes + mode)
    }

    /** Removes mode [index]; the last remaining mode cannot be removed. */
    fun removeMode(config: FlightModesConfig, index: Int): FlightModesConfig {
        if (config.modes.size <= 1 || index !in config.modes.indices) return config
        return config.copy(modes = config.modes.filterIndexed { i, _ -> i != index })
    }

    /** Adds a table row for [channel]: offset 0 and rate 100 in every mode (existing values are kept). */
    fun addChannel(config: FlightModesConfig, channel: Int): FlightModesConfig =
        config.copy(
            modes = config.modes.map { mode ->
                mode.copy(
                    offsets = if (channel in mode.offsets) mode.offsets else mode.offsets + (channel to 0),
                    rates = if (channel in mode.rates) mode.rates else mode.rates + (channel to PERCENT_MAX),
                )
            },
        )

    /** Removes the row of [channel] from every mode. */
    fun removeChannel(config: FlightModesConfig, channel: Int): FlightModesConfig =
        config.copy(modes = config.modes.map { it.copy(offsets = it.offsets - channel, rates = it.rates - channel) })

    /** Sets the offset of [channel] in mode [index] (clamped to ±100). */
    fun setOffset(config: FlightModesConfig, index: Int, channel: Int, value: Int): FlightModesConfig =
        updateMode(config, index) { it.copy(offsets = it.offsets + (channel to value.coerceIn(PERCENT_MIN, PERCENT_MAX))) }

    /** Sets the rate of [channel] in mode [index] (clamped to 0..100). */
    fun setRate(config: FlightModesConfig, index: Int, channel: Int, value: Int): FlightModesConfig =
        updateMode(config, index) { it.copy(rates = it.rates + (channel to value.coerceIn(0, PERCENT_MAX))) }

    /** True if the parts of both configs that are compiled into firmware items are equal (names and announce flags are not). */
    fun compiledEquals(a: FlightModesConfig, b: FlightModesConfig): Boolean =
        a.enabled == b.enabled &&
            a.modeSwitch == b.modeSwitch &&
            a.modes.size == b.modes.size &&
            a.modes.indices.all { i ->
                val x = a.modes[i]
                val y = b.modes[i]
                x.position == y.position && x.offsets == y.offsets && x.rates == y.rates && x.transitionMs == y.transitionMs
            }

    /**
     * Starting point for a model of [type] on [switch]: three modes on up / mid / down with
     * useful names. A multirotor additionally gets its mode channel: CH5 follows the
     * switch value (up +100 / mid 0 / down −100), which is what flight controllers read as the mode value.
     * Gliders, planes and wings get no offsets (they depend on the airframe); helicopters get the
     * usual normal / idle-up names.
     */
    fun preset(type: ModelType, switch: SourceId?, names: PresetNames): FlightModesConfig {
        val names3 = when (type) {
            ModelType.GLIDER -> listOf(names.normal, names.thermal, names.speed)
            ModelType.PLANE, ModelType.WING -> listOf(names.normal, names.sport, names.landing)
            ModelType.MULTIROTOR -> listOf(names.angle, names.horizon, names.acro)
            ModelType.HELICOPTER -> listOf(names.normal, names.idleUp1, names.idleUp2)
            ModelType.OTHER -> listOf(names.mode1, names.mode2, names.mode3)
        }
        val modes = SwitchPosition.entries.mapIndexed { i, position ->
            FlightModeConfig(
                name = names3[i],
                position = position,
                offsets = if (type == ModelType.MULTIROTOR) mapOf(MULTIROTOR_MODE_CHANNEL to position.logic) else emptyMap(),
                rates = if (type == ModelType.MULTIROTOR) mapOf(MULTIROTOR_MODE_CHANNEL to PERCENT_MAX) else emptyMap(),
            )
        }
        return FlightModesConfig(enabled = true, modeSwitch = switch, modes = modes)
    }
}

/** Mode names used by [FlightModeEdits.preset]; the UI passes them from string resources. */
internal data class PresetNames(
    val normal: String,
    val thermal: String,
    val speed: String,
    val sport: String,
    val landing: String,
    val angle: String,
    val horizon: String,
    val acro: String,
    val idleUp1: String,
    val idleUp2: String,
    val mode1: String,
    val mode2: String,
    val mode3: String,
)
