package org.openax12.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// App-side logic stored in ModelExtras: triggers, logical switches, special functions, voice
// alerts, vario. None of this can influence channel outputs (design rule 1).

/** Events of the receiver link usable as a trigger. */
@Serializable
enum class LinkEvent {
    /** Pulse when telemetry is lost. */
    TELEMETRY_LOST,

    /** Pulse when telemetry comes back. */
    TELEMETRY_RECOVERED,

    /** Level: active while the link is up (LQ > 0). */
    LINK_UP,

    /** Level: active while the link is down. */
    LINK_DOWN,
}

/** Timer events usable as a trigger. */
@Serializable
enum class TimerEvent {
    /** Level: active while the timer runs. */
    RUNNING,

    /** Pulse when the timer starts running. */
    STARTED,

    /** Pulse when the timer stops. */
    STOPPED,

    /** Pulse when a countdown reaches zero. */
    EXPIRED,

    /** Pulse when the timer is reset. */
    RESET,

    /** Pulse when exactly `seconds` remain (countdown) — e.g. 30 for "30 seconds left". */
    REMAINING,

    /** Pulse every `seconds` of timer time (e.g. 60 = minute call). */
    EVERY,
}

/**
 * A boolean condition evaluated on every live tick. Used as the trigger of special functions and
 * voice alerts, as the operand of logical switches and as their AND-condition.
 *
 * *Level* triggers are active as long as their condition holds; *pulse* triggers (events) are
 * active for exactly one tick. JSON uses the class discriminator `"type"`.
 */
@Serializable
sealed interface Trigger {

    /** Level: switch [source] is at [position]. */
    @Serializable
    @SerialName("switch")
    data class SwitchAt(val source: SourceId, val position: SwitchPosition) : Trigger

    /** Level: generic comparison of an input source, e.g. "Thr > −90". A disabled condition is never active. */
    @Serializable
    @SerialName("input")
    data class Input(val condition: SwitchCondition) : Trigger

    /** Level: logical switch [index] (0-based) is on (or off when [inverted]). */
    @Serializable
    @SerialName("logical")
    data class Logical(val index: Int, val inverted: Boolean = false) : Trigger

    /**
     * Level: sensor value compared with [value]. Once active it stays active until the value moves
     * back past the threshold by [hysteresis]. A lost sensor is never active.
     */
    @Serializable
    @SerialName("telemetry")
    data class Telemetry(
        val sensor: SensorId,
        val op: CompareOp = CompareOp.LESS,
        val value: Double = 0.0,
        val hysteresis: Double = 0.0,
    ) : Trigger

    /** Link event or link state. */
    @Serializable
    @SerialName("link")
    data class Link(val event: LinkEvent) : Trigger

    /** Timer event. [timer] is the 0-based timer index; [seconds] is used by REMAINING / EVERY. */
    @Serializable
    @SerialName("timer")
    data class Timer(val timer: Int, val event: TimerEvent, val seconds: Int = 0) : Trigger

    /**
     * Flight mode. With [mode] ≥ 0: level, active while that mode is selected. With [ANY_MODE]:
     * pulse on every flight-mode change.
     */
    @Serializable
    @SerialName("flightMode")
    data class FlightModeIs(val mode: Int = ANY_MODE) : Trigger

    /** Level: always active (combine with [Repeat] for periodic actions). */
    @Serializable
    @SerialName("always")
    data object Always : Trigger

    /** Pulse every [intervalSec] seconds. */
    @Serializable
    @SerialName("periodic")
    data class Periodic(val intervalSec: Int = 60) : Trigger

    /** Level: never active (placeholder for "not configured"). */
    @Serializable
    @SerialName("never")
    data object Never : Trigger

    companion object {
        /** Value of [FlightModeIs.mode] meaning "any flight-mode change". */
        const val ANY_MODE: Int = -1
    }
}

/**
 * How often an action fires while its trigger is active.
 *
 * @property intervalSec 0 = once per activation; > 0 = again every N seconds while active
 * @property skipAtStartup do not fire if the trigger is already active when the model is loaded
 *   (EdgeTX "!1x")
 */
@Serializable
data class Repeat(
    val intervalSec: Int = 0,
    val skipAtStartup: Boolean = true,
) {
    /** True if the action runs once per activation. */
    val isOnce: Boolean get() = intervalSec <= 0

    companion object {
        /** Once per activation. */
        val ONCE = Repeat()
    }
}

/** Function of a logical switch (EdgeTX set). `a`, `b` are values, `x` a constant, `s1`/`s2` conditions. */
@Serializable
enum class LogicalFunction {
    /** Unused slot. */
    NONE,

    /** a > x */
    A_GREATER_X,

    /** a < x */
    A_LESS_X,

    /** |a| > x */
    ABS_A_GREATER_X,

    /** |a| < x */
    ABS_A_LESS_X,

    /** a ≈ x (within a small tolerance) */
    A_APPROX_X,

    /** a > b */
    A_GREATER_B,

    /** a < b */
    A_LESS_B,

    /** s1 AND s2 */
    AND,

    /** s1 OR s2 */
    OR,

    /** s1 XOR s2 */
    XOR,

    /** Pulse when s1 was on for between t1 and t2 seconds and is released (EdgeTX Edge). */
    EDGE,

    /** Set by s1, reset by s2. */
    STICKY,

    /** On for t1 seconds, off for t2 seconds, repeating. */
    TIMER,

    /** a changed by ≥ x since the last trigger (EdgeTX d≥x). */
    DELTA,

    /** |change of a| ≥ x (EdgeTX |d|≥x). */
    ABS_DELTA;

    /** True if the function reads the value operand [LogicalSwitch.a]. */
    val usesA: Boolean get() = this in VALUE_FUNCTIONS

    /** True if the function reads the second value operand [LogicalSwitch.b]. */
    val usesB: Boolean get() = this == A_GREATER_B || this == A_LESS_B

    /** True if the function reads the constant [LogicalSwitch.x]. */
    val usesX: Boolean get() = usesA && !usesB

    /** True if the function reads the conditions [LogicalSwitch.s1] / [LogicalSwitch.s2]. */
    val usesConditions: Boolean get() = this == AND || this == OR || this == XOR || this == EDGE || this == STICKY

    /** True if the function reads [LogicalSwitch.t1Sec] / [LogicalSwitch.t2Sec]. */
    val usesTimes: Boolean get() = this == EDGE || this == TIMER

    private companion object {
        val VALUE_FUNCTIONS = setOf(
            A_GREATER_X, A_LESS_X, ABS_A_GREATER_X, ABS_A_LESS_X, A_APPROX_X,
            A_GREATER_B, A_LESS_B, DELTA, ABS_DELTA,
        )
    }
}

/**
 * One app-side logical switch (L1..L32). Logical switches can trigger app-side actions and
 * widgets only; they **cannot** drive mixes (the firmware does not know them).
 *
 * @property index 0-based (L1 = 0)
 * @property a value operand (any sensor id: `in.Thr`, `ch.3`, `crsf.alt`, `timer.1`, …)
 * @property b second value operand for a>b / a<b
 * @property x constant in the unit of [a] (percent for inputs/channels)
 * @property s1 first condition (AND/OR/XOR operand, EDGE input, STICKY set)
 * @property s2 second condition (AND/OR/XOR operand, STICKY reset)
 * @property t1Sec EDGE: minimum press time; TIMER: on time
 * @property t2Sec EDGE: maximum press time (0 = no maximum); TIMER: off time
 * @property andCondition extra condition that must hold for the switch to be on; null = none
 * @property durationSec > 0: the switch stays on for at most this long after turning on
 * @property delaySec > 0: the condition must hold this long before the switch turns on
 */
@Serializable
data class LogicalSwitch(
    val index: Int,
    val name: String = "",
    val function: LogicalFunction = LogicalFunction.NONE,
    val a: SensorId? = null,
    val b: SensorId? = null,
    val x: Double = 0.0,
    val s1: Trigger? = null,
    val s2: Trigger? = null,
    val t1Sec: Double = 0.0,
    val t2Sec: Double = 0.0,
    val andCondition: Trigger? = null,
    val durationSec: Double = 0.0,
    val delaySec: Double = 0.0,
) {
    /** False for an empty slot. */
    val isUsed: Boolean get() = function != LogicalFunction.NONE

    /** `L1`..`L32`. */
    val label: String get() = "L${index + 1}"
}

/**
 * What a special function does. *Momentary* actions fire when the trigger activates (and again
 * per [Repeat]); *continuous* actions ([isContinuous]) are in effect for as long as the trigger is
 * active and are undone when it ends.
 *
 * Channel-affecting EdgeTX functions (override, instant trim, trainer) do not exist on purpose:
 * use ChLock / mixes. JSON uses the class discriminator `"type"`.
 */
@Serializable
sealed interface SpecialAction {

    /** True for actions that last as long as the trigger is active. */
    val isContinuous: Boolean get() = false

    /** Speak a text template (`"Battery {RxBt} volts"`). */
    @Serializable
    @SerialName("playText")
    data class PlayText(val text: String = "", val priority: SpeechPriority = SpeechPriority.NORMAL) : SpecialAction

    /** Speak the current value of a sensor, with its unit and optionally its name. */
    @Serializable
    @SerialName("playValue")
    data class PlayValue(
        val sensor: SensorId,
        val withUnit: Boolean = true,
        val withName: Boolean = false,
        val priority: SpeechPriority = SpeechPriority.NORMAL,
    ) : SpecialAction

    /** Play a user sound file (name relative to the library's `sounds/` folder). */
    @Serializable
    @SerialName("playSound")
    data class PlaySound(val file: String = "") : SpecialAction

    /** Continuous: play background music while active (paused when inactive). */
    @Serializable
    @SerialName("music")
    data class BackgroundMusic(val file: String = "", val loop: Boolean = true) : SpecialAction {
        override val isContinuous: Boolean get() = true
    }

    /** Continuous: the vario tone is on while active (parameters in [VarioConfig]). */
    @Serializable
    @SerialName("vario")
    data object Vario : SpecialAction {
        override val isContinuous: Boolean get() = true
    }

    /** Vibrate. */
    @Serializable
    @SerialName("haptic")
    data class Haptic(val pattern: HapticPattern = HapticPattern.SHORT) : SpecialAction

    /** Play a built-in beep. */
    @Serializable
    @SerialName("beep")
    data class Beep(val pattern: BeepPattern = BeepPattern.SHORT) : SpecialAction

    /** Reset a firmware timer ([counter] = false, index 0..2) or counter ([counter] = true). */
    @Serializable
    @SerialName("resetTimer")
    data class ResetTimer(val timer: Int = 0, val counter: Boolean = false) : SpecialAction

    /** Reset min/max of all sensors. */
    @Serializable
    @SerialName("resetTelemetry")
    data object ResetTelemetry : SpecialAction

    /** Reset flight: all timers, telemetry min/max, home position, logical-switch state. */
    @Serializable
    @SerialName("resetFlight")
    data object ResetFlight : SpecialAction

    /** Continuous: media volume follows input [source] (−100 % = silent, +100 % = full). */
    @Serializable
    @SerialName("volume")
    data class SetVolume(val source: SourceId) : SpecialAction {
        override val isContinuous: Boolean get() = true
    }

    /** Continuous: CSV flight logging runs while active. */
    @Serializable
    @SerialName("logging")
    data object Logging : SpecialAction {
        override val isContinuous: Boolean get() = true
    }

    /** Save a screenshot of the app to the library. */
    @Serializable
    @SerialName("screenshot")
    data object Screenshot : SpecialAction

    /**
     * Continuous: screen brightness. With a [source] the brightness follows that input; otherwise
     * the fixed [percent] (0..100) applies while active.
     */
    @Serializable
    @SerialName("brightness")
    data class Brightness(val source: SourceId? = null, val percent: Int = 100) : SpecialAction {
        override val isContinuous: Boolean get() = true
    }
}

/**
 * One special function (SF1..SF64): `trigger → action`.
 *
 * @property index 0-based (SF1 = 0)
 * @property repeat ignored for continuous actions
 */
@Serializable
data class SpecialFunction(
    val index: Int,
    val name: String = "",
    val enabled: Boolean = true,
    val trigger: Trigger = Trigger.Never,
    val action: SpecialAction = SpecialAction.PlayText(),
    val repeat: Repeat = Repeat.ONCE,
) {
    /** `SF1`..`SF64`. */
    val label: String get() = "SF${index + 1}"
}

/**
 * A spoken text with a trigger (Sounds tab).
 *
 * @property id stable id (see [newItemId])
 * @property text text template, see `TemplateRenderer` in `:core:engine`
 */
@Serializable
data class VoiceAlert(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val text: String = "",
    val trigger: Trigger = Trigger.Never,
    val repeat: Repeat = Repeat.ONCE,
    val priority: SpeechPriority = SpeechPriority.NORMAL,
)

/**
 * Vario tone parameters (EdgeTX style). The vario sounds while a special function with
 * [SpecialAction.Vario] is active, or always when [alwaysOn] is set.
 *
 * @property sensor vertical-speed sensor in m/s; null = [Sensors.VERTICAL_SPEED]
 * @property deriveFromAltitude compute the vertical speed from [altitudeSensor] instead
 * @property altitudeSensor altitude sensor used when deriving; null = [Sensors.BARO_ALTITUDE]
 * @property centerMin lower edge of the dead band in m/s (≤ 0)
 * @property centerMax upper edge of the dead band in m/s (≥ 0)
 * @property centerSilent true = no tone inside the dead band; false = steady tone at [pitchZeroHz]
 * @property rangeMin sink rate in m/s that maps to the lowest pitch [pitchMinHz]
 * @property rangeMax climb rate in m/s that maps to the highest pitch [pitchMaxHz]
 * @property pitchZeroHz pitch at zero climb
 * @property pitchMaxHz pitch at [rangeMax]
 * @property pitchMinHz pitch at [rangeMin] (sink is a continuous low tone)
 * @property repeatZeroMs beep period at zero climb; beeps get faster towards [rangeMax]
 * @property repeatMaxMs beep period at [rangeMax]
 */
@Serializable
data class VarioConfig(
    val alwaysOn: Boolean = false,
    val sensor: SensorId? = null,
    val deriveFromAltitude: Boolean = false,
    val altitudeSensor: SensorId? = null,
    val centerMin: Double = -0.5,
    val centerMax: Double = 0.5,
    val centerSilent: Boolean = true,
    val rangeMin: Double = -10.0,
    val rangeMax: Double = 10.0,
    val pitchZeroHz: Int = 700,
    val pitchMaxHz: Int = 1700,
    val pitchMinHz: Int = 300,
    val repeatZeroMs: Int = 500,
    val repeatMaxMs: Int = 80,
)

/** App-side voice options of one firmware timer (the firmware alert itself is [TimerConfig.alertMode]). */
@Serializable
data class TimerVoiceOptions(
    /** 0-based timer index. */
    val timer: Int,
    /** Speak the timer value every full minute. */
    val minuteCall: Boolean = false,
    /** Speak / beep the last seconds of a countdown. */
    val countdown: CountdownStyle = CountdownStyle.NONE,
    /** Where the countdown starts (seconds remaining), typically 10, 20 or 30. */
    val countdownFromSec: Int = 10,
    /** Announce "timer N expired" when a countdown reaches zero. */
    val announceExpired: Boolean = true,
    /** Vibrate together with the countdown / expiry. */
    val haptic: Boolean = false,
)

/** How the last seconds of a countdown are announced by the app. */
@Serializable
enum class CountdownStyle {
    NONE,
    BEEPS,
    VOICE,
}

/** Built-in announcements that need no special function. */
@Serializable
data class Announcements(
    val telemetryLost: Boolean = true,
    val telemetryRecovered: Boolean = true,
    val lowTxVoltage: Boolean = true,
    /** Speak the new flight mode's name (per-mode flag in [FlightModeConfig.announce] must be set too). */
    val flightModes: Boolean = true,
    /** Speak switch positions when a switch is moved. */
    val switchPositions: Boolean = false,
    /** Trim beeps with a distinct centre tone. */
    val trimBeeps: Boolean = true,
    /** Speak the model name when a model is selected. */
    val modelName: Boolean = true,
)

/** Random 12-character id for list items stored in [ModelExtras] (voice alerts, widgets, alarms, pages). */
fun newItemId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
