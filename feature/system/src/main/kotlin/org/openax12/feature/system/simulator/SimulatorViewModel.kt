package org.openax12.feature.system.simulator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.openax12.core.model.SensorId
import org.openax12.core.model.SourceId
import org.openax12.core.model.StickMode
import org.openax12.core.model.SwitchPosition
import org.openax12.core.radio.Radio
import org.openax12.core.radio.SimulatedFault
import org.openax12.core.radio.SimulatorControl
import org.openax12.core.runtime.ConfigSession

/**
 * Drives `Radio.simulator`: sticks, switches, knobs, telemetry values, the receiver link and
 * fault injection. On the real radio `radio.simulator` is null and the state says so.
 */
internal class SimulatorViewModel(
    private val radio: Radio,
    session: ConfigSession,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val sim: SimulatorControl? = radio.simulator

    /** The state of the screen. */
    val state: StateFlow<SimulatorUiState> = run {
        val control = sim
        if (control == null) {
            kotlinx.coroutines.flow.MutableStateFlow(SimulatorUiState(available = false))
        } else {
            combine(
                control.demoMotion,
                control.linkUp,
                control.telemetry,
                session.workingConfig,
                radio.connection,
            ) { demo, link, telemetry, working, connection ->
                SimulatorUiState(
                    available = true,
                    demoMotion = demo,
                    linkUp = link,
                    telemetry = telemetry,
                    sources = working.sources,
                    stickMode = working.takeUnless { it.isEmpty }?.system?.stickMode ?: StickMode.MODE_2,
                    connection = connection,
                )
            }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SimulatorUiState(available = true))
        }
    }

    /** Sets a stick axis in percent. */
    fun setStick(source: SourceId, percent: Int) = sim?.setStick(source, percent) ?: Unit

    /** Sets a knob or slider in percent. */
    fun setPot(source: SourceId, percent: Int) = sim?.setPot(source, percent) ?: Unit

    /** Moves a switch. */
    fun setSwitch(source: SourceId, position: SwitchPosition) = sim?.setSwitch(source, position) ?: Unit

    /** Sets a telemetry value in the sensor's unit. */
    fun setTelemetry(sensor: SensorId, value: Double) = sim?.setTelemetry(sensor, value) ?: Unit

    /** Sets the flight-mode text of the flight-mode frame. */
    fun setFlightModeText(text: String) = sim?.setTelemetryFlightMode(text) ?: Unit

    /** Turns the demo motion on or off. */
    fun setDemoMotion(enabled: Boolean) = sim?.setDemoMotion(enabled) ?: Unit

    /** Drops or restores the receiver link. */
    fun setLinkUp(up: Boolean) = sim?.setLinkUp(up) ?: Unit

    /** Injects a fault. */
    fun inject(fault: SimulatedFault) = sim?.injectFault(fault) ?: Unit

    /** Resets inputs, telemetry, link and pending faults (the configuration is kept). */
    fun reset() = sim?.reset() ?: Unit
}
