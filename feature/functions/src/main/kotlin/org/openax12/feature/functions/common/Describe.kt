package org.openax12.feature.functions.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.CompareOp
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.LinkEvent
import org.openax12.core.model.LogicalFunction
import org.openax12.core.model.LogicalSwitch
import org.openax12.core.model.ModelType
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SpecialAction
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TimerEvent
import org.openax12.core.model.Trigger
import org.openax12.core.model.UnitConversion
import org.openax12.feature.functions.R

// One-line descriptions of triggers, actions and logical switches, as shown in the lists and as
// the header of the editors. Composable because the words come from string resources.

/** "Up" / "Middle" / "Down". */
@Composable
internal fun positionLabel(position: SwitchPosition): String = stringResource(
    when (position) {
        SwitchPosition.UP -> R.string.fn_pos_up
        SwitchPosition.MID -> R.string.fn_pos_mid
        SwitchPosition.DOWN -> R.string.fn_pos_down
    },
)

/** The comparison symbol of [op]. */
internal fun opSymbol(op: CompareOp): String = when (op) {
    CompareOp.DISABLED -> "–"
    CompareOp.GREATER -> ">"
    CompareOp.EQUAL -> "="
    CompareOp.LESS -> "<"
}

/** Text of a number with at most [precision] decimals, followed by the unit symbol of [unit]. */
internal fun numberWithUnit(value: Double, unit: SensorUnit, precision: Int = 1): String {
    val number = UnitConversion.formatNumber(value, precision)
    return if (unit.symbol.isEmpty()) number else "$number ${unit.symbol}"
}

/** "SA down", "Thr > -90 %": a firmware-style switch condition. */
@Composable
internal fun describeSwitchCondition(condition: SwitchCondition, data: PickerData): String {
    if (!condition.isEnabled) return stringResource(R.string.fn_always)
    val name = data.sourceName(condition.source)
    if (condition.source.isSwitch && condition.op == CompareOp.EQUAL) {
        val position = SwitchPosition.entries.minByOrNull { kotlin.math.abs(it.logic - condition.value) } ?: SwitchPosition.UP
        return stringResource(R.string.fn_trig_switch, name, positionLabel(position))
    }
    return stringResource(R.string.fn_trig_input, name, opSymbol(condition.op), condition.value)
}

/** One-line description of [trigger], e.g. "SA: down" or "RxBt < 7.4 V". */
@Composable
internal fun describeTrigger(trigger: Trigger, data: PickerData): String = when (trigger) {
    is Trigger.SwitchAt -> stringResource(R.string.fn_trig_switch, data.sourceName(trigger.source), positionLabel(trigger.position))
    is Trigger.Input -> describeSwitchCondition(trigger.condition, data)
    is Trigger.Logical -> stringResource(
        if (trigger.inverted) R.string.fn_trig_logical_off else R.string.fn_trig_logical_on,
        "L${trigger.index + 1}",
    )
    is Trigger.Telemetry -> stringResource(
        R.string.fn_trig_telemetry,
        data.sensorName(trigger.sensor),
        opSymbol(trigger.op),
        numberWithUnit(trigger.value, data.sensorUnit(trigger.sensor)),
    )
    is Trigger.Link -> linkEventLabel(trigger.event)
    is Trigger.Timer -> describeTimerTrigger(trigger)
    is Trigger.FlightModeIs -> if (trigger.mode == Trigger.ANY_MODE) {
        stringResource(R.string.fn_trig_fm_any)
    } else {
        stringResource(R.string.fn_trig_fm, flightModeLabel(trigger.mode, data))
    }
    Trigger.Always -> stringResource(R.string.fn_always)
    is Trigger.Periodic -> stringResource(R.string.fn_trig_periodic, trigger.intervalSec)
    Trigger.Never -> stringResource(R.string.fn_trig_never)
}

@Composable
private fun describeTimerTrigger(trigger: Trigger.Timer): String {
    val name = "T${trigger.timer + 1}"
    return when (trigger.event) {
        TimerEvent.RUNNING -> stringResource(R.string.fn_trig_timer_running, name)
        TimerEvent.STARTED -> stringResource(R.string.fn_trig_timer_started, name)
        TimerEvent.STOPPED -> stringResource(R.string.fn_trig_timer_stopped, name)
        TimerEvent.EXPIRED -> stringResource(R.string.fn_trig_timer_expired, name)
        TimerEvent.RESET -> stringResource(R.string.fn_trig_timer_reset, name)
        TimerEvent.REMAINING -> stringResource(R.string.fn_trig_timer_remaining, name, trigger.seconds)
        TimerEvent.EVERY -> stringResource(R.string.fn_trig_timer_every, name, trigger.seconds)
    }
}

/** Label of a link trigger. */
@Composable
internal fun linkEventLabel(event: LinkEvent): String = stringResource(
    when (event) {
        LinkEvent.TELEMETRY_LOST -> R.string.fn_link_lost
        LinkEvent.TELEMETRY_RECOVERED -> R.string.fn_link_recovered
        LinkEvent.LINK_UP -> R.string.fn_link_up
        LinkEvent.LINK_DOWN -> R.string.fn_link_down
    },
)

/** Label of a timer event (the part after "T1"). */
@Composable
internal fun timerEventLabel(event: TimerEvent): String = stringResource(
    when (event) {
        TimerEvent.RUNNING -> R.string.fn_timer_ev_running
        TimerEvent.STARTED -> R.string.fn_timer_ev_started
        TimerEvent.STOPPED -> R.string.fn_timer_ev_stopped
        TimerEvent.EXPIRED -> R.string.fn_timer_ev_expired
        TimerEvent.RESET -> R.string.fn_timer_ev_reset
        TimerEvent.REMAINING -> R.string.fn_timer_ev_remaining
        TimerEvent.EVERY -> R.string.fn_timer_ev_every
    },
)

/** The name of flight mode [index], or "Mode n" when it has none. */
@Composable
internal fun flightModeLabel(index: Int, data: PickerData): String =
    data.flightModeName(index).ifBlank { stringResource(R.string.fn_mode_n, index + 1) }

/** Label of a built-in beep. */
@Composable
internal fun beepLabel(pattern: BeepPattern): String = stringResource(
    when (pattern) {
        BeepPattern.SHORT -> R.string.fn_beep_short
        BeepPattern.DOUBLE -> R.string.fn_beep_double
        BeepPattern.TRIPLE -> R.string.fn_beep_triple
        BeepPattern.LONG -> R.string.fn_beep_long
        BeepPattern.WARNING -> R.string.fn_beep_warning
        BeepPattern.ERROR -> R.string.fn_beep_error
        BeepPattern.TRIM_STEP -> R.string.fn_beep_trim_step
        BeepPattern.TRIM_CENTER -> R.string.fn_beep_trim_center
        BeepPattern.TRIM_LIMIT -> R.string.fn_beep_trim_limit
        BeepPattern.TIMER_TICK -> R.string.fn_beep_timer_tick
        BeepPattern.TIMER_EXPIRED -> R.string.fn_beep_timer_expired
        BeepPattern.CONFIRM -> R.string.fn_beep_confirm
    },
)

/** Label of a vibration pattern. */
@Composable
internal fun hapticLabel(pattern: HapticPattern): String = stringResource(
    when (pattern) {
        HapticPattern.SHORT -> R.string.fn_haptic_short
        HapticPattern.DOUBLE -> R.string.fn_haptic_double
        HapticPattern.TRIPLE -> R.string.fn_haptic_triple
        HapticPattern.LONG -> R.string.fn_haptic_long
        HapticPattern.WARNING -> R.string.fn_haptic_warning
    },
)

/** Label of a speech priority. */
@Composable
internal fun priorityLabel(priority: SpeechPriority): String = stringResource(
    when (priority) {
        SpeechPriority.LOW -> R.string.fn_prio_low
        SpeechPriority.NORMAL -> R.string.fn_prio_normal
        SpeechPriority.HIGH -> R.string.fn_prio_high
        SpeechPriority.ALARM -> R.string.fn_prio_alarm
    },
)

/** Label of a logical-switch function, with the formula EdgeTX users know. */
@Composable
internal fun functionLabel(function: LogicalFunction): String = stringResource(
    when (function) {
        LogicalFunction.NONE -> R.string.fn_lf_none
        LogicalFunction.A_GREATER_X -> R.string.fn_lf_a_gt_x
        LogicalFunction.A_LESS_X -> R.string.fn_lf_a_lt_x
        LogicalFunction.ABS_A_GREATER_X -> R.string.fn_lf_abs_a_gt_x
        LogicalFunction.ABS_A_LESS_X -> R.string.fn_lf_abs_a_lt_x
        LogicalFunction.A_APPROX_X -> R.string.fn_lf_a_approx_x
        LogicalFunction.A_GREATER_B -> R.string.fn_lf_a_gt_b
        LogicalFunction.A_LESS_B -> R.string.fn_lf_a_lt_b
        LogicalFunction.AND -> R.string.fn_lf_and
        LogicalFunction.OR -> R.string.fn_lf_or
        LogicalFunction.XOR -> R.string.fn_lf_xor
        LogicalFunction.EDGE -> R.string.fn_lf_edge
        LogicalFunction.STICKY -> R.string.fn_lf_sticky
        LogicalFunction.TIMER -> R.string.fn_lf_timer
        LogicalFunction.DELTA -> R.string.fn_lf_delta
        LogicalFunction.ABS_DELTA -> R.string.fn_lf_abs_delta
    },
)

/** A short explanation of what a logical-switch function does. */
@Composable
internal fun functionHelp(function: LogicalFunction): String = stringResource(
    when (function) {
        LogicalFunction.NONE -> R.string.fn_lf_help_none
        LogicalFunction.A_GREATER_X, LogicalFunction.A_LESS_X -> R.string.fn_lf_help_compare_x
        LogicalFunction.ABS_A_GREATER_X, LogicalFunction.ABS_A_LESS_X -> R.string.fn_lf_help_abs_x
        LogicalFunction.A_APPROX_X -> R.string.fn_lf_help_approx
        LogicalFunction.A_GREATER_B, LogicalFunction.A_LESS_B -> R.string.fn_lf_help_compare_b
        LogicalFunction.AND, LogicalFunction.OR, LogicalFunction.XOR -> R.string.fn_lf_help_bool
        LogicalFunction.EDGE -> R.string.fn_lf_help_edge
        LogicalFunction.STICKY -> R.string.fn_lf_help_sticky
        LogicalFunction.TIMER -> R.string.fn_lf_help_timer
        LogicalFunction.DELTA, LogicalFunction.ABS_DELTA -> R.string.fn_lf_help_delta
    },
)

/** Name of a model type. */
@Composable
internal fun modelTypeLabel(type: ModelType): String = stringResource(
    when (type) {
        ModelType.GLIDER -> R.string.fn_type_glider
        ModelType.PLANE -> R.string.fn_type_plane
        ModelType.WING -> R.string.fn_type_wing
        ModelType.MULTIROTOR -> R.string.fn_type_multirotor
        ModelType.HELICOPTER -> R.string.fn_type_helicopter
        ModelType.OTHER -> R.string.fn_type_other
    },
)

/** One-line description of a logical switch, e.g. "Alt > 100 m" or "SA down AND L2 is on". */
@Composable
internal fun describeLogicalSwitch(ls: LogicalSwitch, data: PickerData): String {
    if (!ls.isUsed) return stringResource(R.string.fn_empty)
    val a = ls.a?.let { data.sensorName(it) } ?: "—"
    val b = ls.b?.let { data.sensorName(it) } ?: "—"
    val x = numberWithUnit(ls.x, data.sensorUnit(ls.a))
    val s1 = ls.s1?.let { describeTrigger(it, data) } ?: "—"
    val s2 = ls.s2?.let { describeTrigger(it, data) } ?: "—"
    return when (ls.function) {
        LogicalFunction.NONE -> stringResource(R.string.fn_empty)
        LogicalFunction.A_GREATER_X -> "$a > $x"
        LogicalFunction.A_LESS_X -> "$a < $x"
        LogicalFunction.ABS_A_GREATER_X -> "|$a| > $x"
        LogicalFunction.ABS_A_LESS_X -> "|$a| < $x"
        LogicalFunction.A_APPROX_X -> "$a ≈ $x"
        LogicalFunction.A_GREATER_B -> "$a > $b"
        LogicalFunction.A_LESS_B -> "$a < $b"
        LogicalFunction.AND -> stringResource(R.string.fn_ls_and, s1, s2)
        LogicalFunction.OR -> stringResource(R.string.fn_ls_or, s1, s2)
        LogicalFunction.XOR -> stringResource(R.string.fn_ls_xor, s1, s2)
        LogicalFunction.EDGE -> stringResource(R.string.fn_ls_edge, s1, UnitConversion.formatNumber(ls.t1Sec, 1), UnitConversion.formatNumber(ls.t2Sec, 1))
        LogicalFunction.STICKY -> stringResource(R.string.fn_ls_sticky, s1, s2)
        LogicalFunction.TIMER -> stringResource(R.string.fn_ls_timer, UnitConversion.formatNumber(ls.t1Sec, 1), UnitConversion.formatNumber(ls.t2Sec, 1))
        LogicalFunction.DELTA -> stringResource(R.string.fn_ls_delta, a, x)
        LogicalFunction.ABS_DELTA -> stringResource(R.string.fn_ls_abs_delta, a, x)
    }
}

/** The kind of a special action, for the action picker and the list rows. */
internal enum class ActionKind {
    PLAY_TEXT, PLAY_VALUE, PLAY_SOUND, MUSIC, VARIO, HAPTIC, BEEP, RESET_TIMER, RESET_TELEMETRY,
    RESET_FLIGHT, VOLUME, LOGGING, SCREENSHOT, BRIGHTNESS,
}

/** The [ActionKind] of this action. */
internal fun SpecialAction.kind(): ActionKind = when (this) {
    is SpecialAction.PlayText -> ActionKind.PLAY_TEXT
    is SpecialAction.PlayValue -> ActionKind.PLAY_VALUE
    is SpecialAction.PlaySound -> ActionKind.PLAY_SOUND
    is SpecialAction.BackgroundMusic -> ActionKind.MUSIC
    SpecialAction.Vario -> ActionKind.VARIO
    is SpecialAction.Haptic -> ActionKind.HAPTIC
    is SpecialAction.Beep -> ActionKind.BEEP
    is SpecialAction.ResetTimer -> ActionKind.RESET_TIMER
    SpecialAction.ResetTelemetry -> ActionKind.RESET_TELEMETRY
    SpecialAction.ResetFlight -> ActionKind.RESET_FLIGHT
    is SpecialAction.SetVolume -> ActionKind.VOLUME
    SpecialAction.Logging -> ActionKind.LOGGING
    SpecialAction.Screenshot -> ActionKind.SCREENSHOT
    is SpecialAction.Brightness -> ActionKind.BRIGHTNESS
}

/** Name of an action kind. */
@Composable
internal fun actionKindLabel(kind: ActionKind): String = stringResource(
    when (kind) {
        ActionKind.PLAY_TEXT -> R.string.fn_act_play_text
        ActionKind.PLAY_VALUE -> R.string.fn_act_play_value
        ActionKind.PLAY_SOUND -> R.string.fn_act_play_sound
        ActionKind.MUSIC -> R.string.fn_act_music
        ActionKind.VARIO -> R.string.fn_act_vario
        ActionKind.HAPTIC -> R.string.fn_act_haptic
        ActionKind.BEEP -> R.string.fn_act_beep
        ActionKind.RESET_TIMER -> R.string.fn_act_reset_timer
        ActionKind.RESET_TELEMETRY -> R.string.fn_act_reset_telemetry
        ActionKind.RESET_FLIGHT -> R.string.fn_act_reset_flight
        ActionKind.VOLUME -> R.string.fn_act_volume
        ActionKind.LOGGING -> R.string.fn_act_logging
        ActionKind.SCREENSHOT -> R.string.fn_act_screenshot
        ActionKind.BRIGHTNESS -> R.string.fn_act_brightness
    },
)

/** One-line description of an action with its main parameter, e.g. `Play text "Battery low"`. */
@Composable
internal fun describeAction(action: SpecialAction, data: PickerData): String {
    val kind = actionKindLabel(action.kind())
    return when (action) {
        is SpecialAction.PlayText -> if (action.text.isBlank()) kind else "$kind: “${action.text}”"
        is SpecialAction.PlayValue -> "$kind: ${data.sensorName(action.sensor)}"
        is SpecialAction.PlaySound -> if (action.file.isBlank()) kind else "$kind: ${action.file}"
        is SpecialAction.BackgroundMusic -> if (action.file.isBlank()) kind else "$kind: ${action.file}"
        is SpecialAction.Haptic -> "$kind: ${hapticLabel(action.pattern)}"
        is SpecialAction.Beep -> "$kind: ${beepLabel(action.pattern)}"
        is SpecialAction.ResetTimer -> "$kind: ${if (action.counter) "CNT" else "T"}${action.timer + 1}"
        is SpecialAction.SetVolume -> "$kind: ${data.sourceName(action.source)}"
        is SpecialAction.Brightness -> {
            val source = action.source
            if (source != null) "$kind: ${data.sourceName(source)}" else "$kind: ${action.percent} %"
        }
        SpecialAction.Vario, SpecialAction.ResetTelemetry, SpecialAction.ResetFlight, SpecialAction.Logging, SpecialAction.Screenshot -> kind
    }
}
