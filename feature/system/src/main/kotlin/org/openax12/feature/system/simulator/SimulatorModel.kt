package org.openax12.feature.system.simulator

import org.openax12.core.model.SensorId
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.Sources
import org.openax12.core.model.StickMode
import org.openax12.core.model.SwitchPosition
import org.openax12.core.radio.ConnectionState

/** Everything the simulator panel shows besides the live input values. */
internal data class SimulatorUiState(
    /** False on the real radio: the panel then only explains that it is for the simulator. */
    val available: Boolean = false,
    val demoMotion: Boolean = false,
    val linkUp: Boolean = true,
    val telemetry: Map<SensorId, Double> = emptyMap(),
    val sources: List<SourceInfo> = emptyList(),
    val stickMode: StickMode = StickMode.MODE_2,
    val connection: ConnectionState = ConnectionState.Stopped,
) {
    /** Enabled switch sources, in index order. */
    val switches: List<SourceInfo> get() = sources.filter { it.enabled && it.index in Sources.SWITCHES }.sortedBy { it.index }

    /** Enabled knobs and sliders, in index order. */
    val knobs: List<SourceInfo>
        get() = sources.filter { it.enabled && (it.index in Sources.POTS || it.index in Sources.SLIDERS) }.sortedBy { it.index }
}

/** True for the stick axes that spring back to centre when released; the throttle stays where it is put. */
internal fun springsBack(source: SourceId): Boolean = source != SourceId.THR

/** Position of a switch from its logic value, for the 3-position selector. */
internal fun switchIndex(logic: Int): Int = when (SwitchPosition.fromLogic(logic)) {
    SwitchPosition.UP -> 0
    SwitchPosition.MID -> 1
    SwitchPosition.DOWN -> 2
}

/** Switch position for the selector index 0 / 1 / 2. */
internal fun switchPosition(index: Int): SwitchPosition = when (index) {
    0 -> SwitchPosition.UP
    1 -> SwitchPosition.MID
    else -> SwitchPosition.DOWN
}

/** Number of decimals the simulator panel offers for a sensor value (coordinates need more). */
internal fun decimalsFor(sensor: SensorId): Int =
    if (sensor.key.endsWith(".lat") || sensor.key.endsWith(".lon")) 5 else 1

/** Largest absolute value (in scaled units) the number pad accepts for a sensor. */
internal const val TELEMETRY_SCALED_LIMIT: Int = 18_000_000

/** Scales a sensor value to the integer the number field edits. */
internal fun scaleValue(value: Double, decimals: Int): Int {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    return Math.round(value * factor).coerceIn(-TELEMETRY_SCALED_LIMIT.toLong(), TELEMETRY_SCALED_LIMIT.toLong()).toInt()
}

/** Inverse of [scaleValue]. */
internal fun unscaleValue(scaled: Int, decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    return scaled / factor
}
