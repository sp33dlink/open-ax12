package org.openax12.feature.telemetry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.model.GpsFix
import org.openax12.core.model.UnitSystem

/**
 * What the last-position screen shows.
 *
 * @property fix the last valid GPS position the receiver reported; null if there never was one
 * @property home the home position; null if none was set
 */
internal data class LastPositionState(
    val fix: GpsFix? = null,
    val home: GpsFix? = null,
    val units: UnitSystem = UnitSystem.METRIC,
) {
    /** Distance in metres from [home] to [fix]; null without both. */
    val distanceFromHomeM: Double?
        get() = if (fix != null && home != null) GeoMath.distanceMeters(home.latitude, home.longitude, fix.latitude, fix.longitude) else null

    /** Bearing in degrees from [home] to [fix]; null without both. */
    val bearingFromHome: Double?
        get() = if (fix != null && home != null) GeoMath.bearingDegrees(home.latitude, home.longitude, fix.latitude, fix.longitude) else null
}

/**
 * The lost-model finder: the last known position of the receiver, and its relation to home.
 *
 * @param setHome sets the home position (`TelemetryRepository.setHome`)
 */
internal class LastPositionViewModel(
    lastKnown: StateFlow<GpsFix?>,
    home: StateFlow<GpsFix?>,
    units: Flow<UnitSystem>,
    private val setHome: suspend (GpsFix?) -> Unit,
) : ViewModel() {

    /** The state of the screen. */
    val state: StateFlow<LastPositionState> = combine(lastKnown, home, units) { fix, h, u ->
        LastPositionState(fix = fix, home = h, units = u)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LastPositionState())

    /** Makes the last known position the home position. */
    fun useAsHome() {
        val fix = state.value.fix ?: return
        viewModelScope.launch { setHome(fix) }
    }
}
