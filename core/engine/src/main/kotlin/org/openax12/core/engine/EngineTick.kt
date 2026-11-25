package org.openax12.core.engine

import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorValue
import org.openax12.core.model.TimerNotify

/** Something that happened since the previous tick. Passed to the evaluators inside [EngineTick.events]. */
sealed interface EngineEvent {

    /** The firmware sent a timer notification (alarm, interval, reset). */
    data class TimerNotified(val notify: TimerNotify) : EngineEvent

    /** Receiver telemetry was lost. */
    data object TelemetryLost : EngineEvent

    /** Receiver telemetry came back. */
    data object TelemetryRecovered : EngineEvent

    /** The transmitter reported low battery voltage. */
    data object LowTxVoltage : EngineEvent
}

/**
 * Everything the app-side engine knows at one evaluation step. The runtime builds one per live
 * frame (about every 40 ms) and passes it to the evaluators in this order:
 * `LogicalSwitchEvaluator` → `SpecialFunctionEvaluator` / `AlarmEvaluator`.
 *
 * The engine is pure: it never reads clocks or global state, only what is in the tick.
 *
 * @property nowMs monotonic time of the tick in ms; must never go backwards between ticks
 * @property live the live frame
 * @property sensors current sensor values (inputs and channels are *not* in this map, they are
 *   read from [live])
 * @property flightMode index of the active flight mode (0 = default)
 * @property flightModeName its name, for `{FM}`
 * @property logicalSwitches logical switch states. For the `LogicalSwitchEvaluator` these are the
 *   states of the *previous* tick (switches referencing other switches see last tick's value);
 *   for all later evaluators the runtime passes the freshly computed states.
 * @property modelName name of the current model, for `{model}`
 * @property linkUp true while the receiver link is up
 * @property events events since the previous tick
 * @property startup true for the first tick after a model was loaded or the engine was reset;
 *   used for `Repeat.skipAtStartup`
 */
class EngineTick(
    val nowMs: Long,
    val live: LiveSnapshot,
    val sensors: Map<SensorId, SensorValue> = emptyMap(),
    val flightMode: Int = 0,
    val flightModeName: String = "",
    val logicalSwitches: LogicalSwitchStates = LogicalSwitchStates.NONE,
    val modelName: String = "",
    val linkUp: Boolean = live.status.isLinkUp,
    val events: List<EngineEvent> = emptyList(),
    val startup: Boolean = false,
) {
    /** Copy with other logical switch states (used between the evaluation phases of one tick). */
    fun withLogicalSwitches(states: LogicalSwitchStates): EngineTick = EngineTick(
        nowMs, live, sensors, flightMode, flightModeName, states, modelName, linkUp, events, startup,
    )
}
