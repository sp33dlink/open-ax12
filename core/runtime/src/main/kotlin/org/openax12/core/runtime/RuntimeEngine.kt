package org.openax12.core.runtime

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.openax12.core.audio.SpokenText
import org.openax12.core.engine.ValueResolver
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.SensorAlarm
import org.openax12.core.model.SpecialFunction
import org.openax12.core.model.VoiceAlert

/**
 * The flight mode that is active right now.
 *
 * @property index 0-based index into the model's flight modes (0 = default mode)
 * @property name its name; empty when the model has no flight modes
 */
data class ActiveFlightMode(val index: Int = 0, val name: String = "")

/** An app-side alarm that is currently raised. */
data class ActiveAlarm(
    val alarm: SensorAlarm,
    /** Sensor value when the alarm was raised or last repeated (native unit). */
    val value: Double,
    /** Monotonic time the alarm was raised. */
    val raisedAtMs: Long,
)

/** Something the UI layer must do or show on behalf of the runtime; delivered on [RuntimeEngine.uiRequests]. */
sealed interface RuntimeUiRequest {

    /** Show an alarm popup over the current screen ([text] is already rendered). */
    data class AlarmPopup(val alarm: SensorAlarm, val text: String) : RuntimeUiRequest

    /**
     * Show the pre-flight check dialog: the firmware reports sources away from their presets.
     * This is only the *trigger* for the popup and can be missed (no replay); the state itself is
     * retained in [RuntimeEngine.preFlightCheck], which the shell also checks when it starts
     * collecting.
     */
    data class PreFlightCheck(val states: List<Int>) : RuntimeUiRequest

    /** Take a screenshot of the current screen and store it in the library (special function). */
    data object Screenshot : RuntimeUiRequest

    /** A short message for a snackbar / toast (e.g. "Logging started"). [messageKey] is one of [RuntimeMessages]. */
    data class Message(val messageKey: String, val argument: String = "") : RuntimeUiRequest
}

/** Keys of [RuntimeUiRequest.Message]; the app shell maps them to string resources. */
object RuntimeMessages {
    const val LOGGING_STARTED = "logging_started"
    const val LOGGING_STOPPED = "logging_stopped"
    const val LOGGING_FAILED = "logging_failed"
    const val TELEMETRY_LOST = "telemetry_lost"
    const val TELEMETRY_RECOVERED = "telemetry_recovered"
    const val LOW_TX_VOLTAGE = "low_tx_voltage"
    const val FLIGHT_RESET = "flight_reset"
    const val SOUND_FILE_MISSING = "sound_file_missing"
}

/**
 * The app-side "convenience logic" (design rule 2), running in the foreground service for as long
 * as the app process lives — independent of any screen.
 *
 * On every live frame it evaluates the flight mode, the logical switches, special functions,
 * voice alerts and alarms of the current model (from `ModelRepository.currentExtras`) and drives
 * the audio engine, the flight logger and the telemetry repository accordingly. It also performs
 * the built-in announcements (telemetry lost / recovered, low TX voltage, flight mode, trim beeps,
 * timer countdowns, switch positions). It never changes channel outputs.
 *
 * All members are thread-safe.
 */
interface RuntimeEngine {

    /** True between [start] and [stop]. */
    val running: StateFlow<Boolean>

    /** The active flight mode (follows the mode switch; index 0 without flight modes). */
    val flightMode: StateFlow<ActiveFlightMode>

    /** States of the logical switches L1..L32. */
    val logicalSwitches: StateFlow<LogicalSwitchStates>

    /** Indices of the special functions whose trigger is currently active. */
    val activeSpecialFunctions: StateFlow<Set<Int>>

    /** Alarms that are currently raised, oldest first. */
    val activeAlarms: StateFlow<List<ActiveAlarm>>

    /** True while the vario tone is on. */
    val varioActive: StateFlow<Boolean>

    /** The text spoken last (same as `AudioEngine.lastSpoken`), for the UI. */
    val lastSpoken: StateFlow<SpokenText?>

    /**
     * Screen brightness requested by a special function in percent (0..100), or null = leave the
     * system brightness alone. The activity applies it to its window.
     */
    val brightnessOverride: StateFlow<Int?>

    /**
     * The firmware's pending pre-flight check alarm: the per-source states of the latest
     * `RadioEvent.PreFlightCheck` (non-zero = that source is not at its preset), or `null` when
     * there is none — never raised, all sources pass, or dismissed with [skipPreFlightCheck].
     *
     * Retained state, unlike the one-shot [RuntimeUiRequest.PreFlightCheck]: the firmware raises
     * the alarm right after power-up, usually before any screen collects. The engine therefore
     * subscribes to `Radio.events` for this **when it is constructed**, not in [start] — the app
     * graph creates the runtime engine before it calls `Radio.start()`. The PreFlightCheck screen
     * and the shell's popup read this flow.
     */
    val preFlightCheck: StateFlow<List<Int>?>

    /**
     * Requests to the UI layer (popups, screenshot, messages). Hot, no replay, buffered (16, drop
     * oldest) — an emission without a collector is lost, so nothing that must not be missed lives
     * only here (see [preFlightCheck], [activeAlarms]).
     */
    val uiRequests: SharedFlow<RuntimeUiRequest>

    /** Starts evaluating. Idempotent. Called by the app graph once the radio is ready. */
    fun start()

    /** Stops evaluating and silences vario / music. Idempotent. */
    fun stop()

    /**
     * A resolver over the latest tick, for live previews in editors ("Battery {RxBt} volts" →
     * "Battery 7.4 volts"). The returned object is a snapshot; fetch a new one to refresh.
     */
    fun valueResolver(): ValueResolver

    // -- Manual triggers -------------------------------------------------------------------------

    /** Reset flight: all firmware timers, telemetry min / max and history, home position, logical-switch state. */
    suspend fun resetFlight()

    /** Resets telemetry min / max of all sensors. */
    suspend fun resetTelemetry()

    /**
     * Resets one firmware timer or counter ([index] is the in-group index 0..2).
     *
     * Reset is all there is: the firmware has **no manual start / stop** — a timer runs while the
     * switch condition of its `TimerConfig` holds. The home screen therefore maps long-press to
     * this function and tap to `ModelSetup(SetupTabIds.TIMERS)`; it must not emulate start / stop
     * by rewriting the timer's switch (that would make the model dirty from the home screen).
     */
    suspend fun resetTimer(index: Int, isCounter: Boolean = false)

    /**
     * Dismisses the firmware's pre-flight check alarm (`Radio.skipPreFlightCheck`) and clears
     * [preFlightCheck]. Screens call this instead of the radio function so that the retained
     * state follows.
     */
    suspend fun skipPreFlightCheck()

    /** Raised alarms are silenced until they clear and trigger again. */
    fun acknowledgeAlarms()

    /** Speaks a voice alert now, regardless of its trigger (the editor's "test" button). */
    fun preview(alert: VoiceAlert)

    /** Performs the action of a special function once now (momentary actions only; continuous ones run for 3 s). */
    fun preview(function: SpecialFunction)

    /** Renders [template] with the latest tick and speaks it (the template editor's "test" button). */
    fun previewText(template: String)

    /** Sounds the vario for a fixed vertical speed for 2 s (the vario settings preview). */
    fun previewVario(verticalSpeed: Double)
}
