package org.openax12.feature.telemetry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorOverride
import org.openax12.core.model.SensorValue
import org.openax12.core.model.UnitSystem

/** What the sensor list shows. */
internal data class SensorListState(
    /** The sensors that pass the filter, in registry order. */
    val sensors: List<SensorInfo> = emptyList(),
    /** Number of sensors before filtering. */
    val total: Int = 0,
    val values: Map<SensorId, SensorValue> = emptyMap(),
    val overrides: Map<String, SensorOverride> = emptyMap(),
    val category: SensorCategory? = null,
    val query: String = "",
    val units: UnitSystem = UnitSystem.METRIC,
)

/** What the user can do in the sensor list. */
internal sealed interface SensorListAction {
    /** Shows one category; null shows all. */
    data class SetCategory(val category: SensorCategory?) : SensorListAction
    data class SetQuery(val query: String) : SensorListAction

    /** Stores the override of [sensor]; an override that changes nothing removes it. */
    data class SaveOverride(val sensor: SensorId, val override: SensorOverride) : SensorListAction

    /** Resets min / max of one sensor, or of all sensors when null. */
    data class ResetMinMax(val sensor: SensorId?) : SensorListAction
}

/**
 * The sensor registry as a list with per-model overrides (name, unit, precision, hidden).
 *
 * @param values the throttled sensor values (inputs and channels are not part of it, see `TelemetryRepository`)
 * @param resetMinMax resets min / max of one sensor or, with null, of all
 */
internal class SensorListViewModel(
    private val store: ExtrasStore,
    sensorList: StateFlow<List<SensorInfo>>,
    values: StateFlow<Map<SensorId, SensorValue>>,
    units: Flow<UnitSystem>,
    private val resetMinMax: suspend (SensorId?) -> Unit,
) : ViewModel() {

    private val category = MutableStateFlow<SensorCategory?>(null)
    private val query = MutableStateFlow("")

    /** The state of the screen. */
    val state: StateFlow<SensorListState> = combine(
        sensorList, values, store.extras, category, query,
    ) { sensors, valueMap, extras, cat, q ->
        SensorListState(
            sensors = filterSensors(sensors, cat, q),
            total = sensors.size,
            values = valueMap,
            overrides = extras.sensorOverrides,
            category = cat,
            query = q,
        )
    }.combine(units) { s, u -> s.copy(units = u) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SensorListState())

    /** Handles [action]. */
    fun onAction(action: SensorListAction) {
        when (action) {
            is SensorListAction.SetCategory -> category.value = action.category
            is SensorListAction.SetQuery -> query.value = action.query
            is SensorListAction.SaveOverride -> viewModelScope.launch {
                val normalized = action.override.normalized()
                store.update { it.withSensorOverride(action.sensor, normalized) }
            }
            is SensorListAction.ResetMinMax -> viewModelScope.launch { resetMinMax(action.sensor) }
        }
    }

    internal companion object {
        /** Sensors of [category] (all if null) whose name, id or template name contains [query]. */
        fun filterSensors(sensors: List<SensorInfo>, category: SensorCategory?, query: String): List<SensorInfo> {
            val q = query.trim()
            return sensors.filter { info ->
                (category == null || sensorCategory(info.id, info.origin) == category) &&
                    (
                        q.isEmpty() ||
                            info.name.contains(q, ignoreCase = true) ||
                            info.id.key.contains(q, ignoreCase = true) ||
                            info.templateName?.contains(q, ignoreCase = true) == true
                        )
            }
        }
    }
}

/**
 * This override with a blank name cleaned up, or null if it changes nothing (no name, unit or
 * precision, not hidden) and can be removed from the extras.
 */
internal fun SensorOverride.normalized(): SensorOverride? {
    val cleaned = copy(name = name?.trim()?.takeIf { it.isNotEmpty() })
    return if (cleaned.name == null && cleaned.unit == null && cleaned.precision == null && !cleaned.hidden) null else cleaned
}
