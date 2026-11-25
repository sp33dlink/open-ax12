package org.openax12.feature.functions.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.PercentField
import org.openax12.core.model.CompareOp
import org.openax12.core.model.LOGICAL_SWITCH_COUNT
import org.openax12.core.model.LinkEvent
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TimerEvent
import org.openax12.core.model.Trigger
import org.openax12.feature.functions.R
import kotlin.math.roundToInt

private val COMPARISONS = listOf(CompareOp.LESS, CompareOp.EQUAL, CompareOp.GREATER)

/** Name of a trigger kind in the editor's first dropdown. */
@Composable
internal fun triggerKindLabel(kind: TriggerKind): String = stringResource(
    when (kind) {
        TriggerKind.SWITCH -> R.string.fn_tk_switch
        TriggerKind.INPUT -> R.string.fn_tk_input
        TriggerKind.LOGICAL -> R.string.fn_tk_logical
        TriggerKind.TELEMETRY -> R.string.fn_tk_telemetry
        TriggerKind.LINK -> R.string.fn_tk_link
        TriggerKind.TIMER -> R.string.fn_tk_timer
        TriggerKind.FLIGHT_MODE -> R.string.fn_tk_flight_mode
        TriggerKind.ALWAYS -> R.string.fn_tk_always
        TriggerKind.PERIODIC -> R.string.fn_tk_periodic
        TriggerKind.NEVER -> R.string.fn_tk_never
    },
)

/** Warning text of a trigger problem. */
@Composable
internal fun triggerProblemText(problem: TriggerProblem): String = stringResource(
    when (problem) {
        TriggerProblem.NEVER -> R.string.fn_problem_never
        TriggerProblem.SELF_REFERENCE -> R.string.fn_problem_self
        TriggerProblem.NO_SECONDS -> R.string.fn_problem_seconds
        TriggerProblem.UNKNOWN_SENSOR -> R.string.fn_problem_sensor
        TriggerProblem.UNKNOWN_MODE -> R.string.fn_problem_mode
    },
)

/**
 * Editor of a [Trigger]: a dropdown for the kind and the fields of that kind next to it. Used by
 * special functions, voice alerts and the conditions of logical switches.
 *
 * @param kinds the kinds offered by the dropdown
 * @param selfLogical 0-based index of the logical switch the trigger belongs to (it cannot pick itself)
 */
@Composable
internal fun TriggerEditor(
    trigger: Trigger,
    onChange: (Trigger) -> Unit,
    data: PickerData,
    modifier: Modifier = Modifier,
    kinds: List<TriggerKind> = TriggerKind.entries,
    selfLogical: Int? = null,
) {
    val kind = trigger.kind()
    val shownKinds = remember(kinds, kind) { if (kind in kinds) kinds else kinds + kind }
    val kindLabels = shownKinds.map { triggerKindLabel(it) }
    val problem = triggerProblem(trigger, data, selfLogical)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ControlFlow {
            LabelDropdown(
                labels = kindLabels,
                selectedIndex = shownKinds.indexOf(kind),
                onSelected = { onChange(defaultTrigger(shownKinds[it], data)) },
                width = 176.dp,
                title = stringResource(R.string.fn_trigger_kind),
            )
            TriggerFields(trigger, onChange, data, selfLogical)
        }
        if (problem != null) {
            Caption(
                text = triggerProblemText(problem),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TriggerFields(trigger: Trigger, onChange: (Trigger) -> Unit, data: PickerData, selfLogical: Int?) {
    when (trigger) {
        is Trigger.SwitchAt -> {
            SourceField(
                selected = trigger.source,
                options = data.switches,
                onSelected = { onChange(trigger.copy(source = it)) },
            )
            val positions = SwitchPosition.entries
            AxSegmented(
                options = positions.map { positionLabel(it) },
                selectedIndex = positions.indexOf(trigger.position),
                onSelected = { onChange(trigger.copy(position = positions[it])) },
                modifier = Modifier.width(240.dp),
            )
        }
        is Trigger.Input -> SwitchConditionFields(
            condition = trigger.condition,
            onChange = { onChange(Trigger.Input(it)) },
            data = data,
        )
        is Trigger.Logical -> {
            val labels = remember { List(LOGICAL_SWITCH_COUNT) { "L${it + 1}" } }
            LabelDropdown(
                labels = labels,
                selectedIndex = trigger.index,
                onSelected = { onChange(trigger.copy(index = it)) },
                width = 96.dp,
                title = stringResource(R.string.fn_pick_logical),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AxSwitch(checked = trigger.inverted, onCheckedChange = { onChange(trigger.copy(inverted = it)) })
                Caption(stringResource(R.string.fn_inverted))
            }
        }
        is Trigger.Telemetry -> {
            SensorField(
                selected = trigger.sensor,
                data = data,
                onSelected = { onChange(trigger.copy(sensor = it)) },
            )
            AxSegmented(
                options = COMPARISONS.map { opSymbol(it) },
                selectedIndex = COMPARISONS.indexOf(trigger.op).coerceAtLeast(0),
                onSelected = { onChange(trigger.copy(op = COMPARISONS[it])) },
                modifier = Modifier.width(132.dp),
            )
            val unit = data.sensorUnit(trigger.sensor).symbol
            NumberField(
                value = (trigger.value * 10).roundToInt(),
                onValueChange = { onChange(trigger.copy(value = it / 10.0)) },
                min = -MAX_TENTHS,
                max = MAX_TENTHS,
                decimals = 1,
                unit = unit,
                title = stringResource(R.string.fn_threshold),
                modifier = Modifier.width(112.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption(stringResource(R.string.fn_hysteresis), maxLines = 1)
                NumberField(
                    value = (trigger.hysteresis * 10).roundToInt(),
                    onValueChange = { onChange(trigger.copy(hysteresis = it / 10.0)) },
                    min = 0,
                    max = MAX_TENTHS,
                    decimals = 1,
                    unit = unit,
                    title = stringResource(R.string.fn_hysteresis),
                    modifier = Modifier.width(100.dp),
                )
            }
        }
        is Trigger.Link -> {
            val events = LinkEvent.entries
            LabelDropdown(
                labels = events.map { linkEventLabel(it) },
                selectedIndex = events.indexOf(trigger.event),
                onSelected = { onChange(Trigger.Link(events[it])) },
                width = 240.dp,
            )
        }
        is Trigger.Timer -> {
            val timers = remember { List(TIMER_COUNT) { "T${it + 1}" } }
            LabelDropdown(
                labels = timers,
                selectedIndex = trigger.timer.coerceIn(0, TIMER_COUNT - 1),
                onSelected = { onChange(trigger.copy(timer = it)) },
                width = 80.dp,
            )
            val events = TimerEvent.entries
            LabelDropdown(
                labels = events.map { timerEventLabel(it) },
                selectedIndex = events.indexOf(trigger.event),
                onSelected = { onChange(trigger.copy(event = events[it])) },
                width = 200.dp,
            )
            if (trigger.event == TimerEvent.REMAINING || trigger.event == TimerEvent.EVERY) {
                NumberField(
                    value = trigger.seconds,
                    onValueChange = { onChange(trigger.copy(seconds = it)) },
                    min = 0,
                    max = 7200,
                    unit = " s",
                    title = stringResource(R.string.fn_seconds),
                    modifier = Modifier.width(NumberWidth),
                )
            }
        }
        is Trigger.FlightModeIs -> {
            val any = stringResource(R.string.fn_fm_any_change)
            val modeLabels = data.flightModes.indices.map { flightModeLabel(it, data) }
            val labels = listOf(any) + modeLabels
            LabelDropdown(
                labels = labels,
                selectedIndex = if (trigger.mode == Trigger.ANY_MODE) 0 else trigger.mode + 1,
                onSelected = { onChange(Trigger.FlightModeIs(if (it == 0) Trigger.ANY_MODE else it - 1)) },
                width = 220.dp,
            )
        }
        is Trigger.Periodic -> {
            NumberField(
                value = trigger.intervalSec,
                onValueChange = { onChange(trigger.copy(intervalSec = it)) },
                min = 1,
                max = 3600,
                unit = " s",
                title = stringResource(R.string.fn_interval),
                modifier = Modifier.width(NumberWidth),
            )
        }
        Trigger.Always, Trigger.Never -> Unit
    }
}

/** Largest threshold in tenths (±100000.0) accepted by the numeric fields of triggers and logical switches. */
internal const val MAX_TENTHS: Int = 1_000_000

/**
 * The three fields of a [SwitchCondition]: source, comparison and value in percent. A disabled
 * condition is shown as ">"; the first change enables it.
 */
@Composable
internal fun SwitchConditionFields(
    condition: SwitchCondition,
    onChange: (SwitchCondition) -> Unit,
    data: PickerData,
) {
    SourceField(
        selected = condition.source,
        options = data.sources,
        onSelected = { onChange(condition.copy(source = it, op = condition.op.takeIf { op -> op != CompareOp.DISABLED } ?: CompareOp.GREATER)) },
    )
    AxSegmented(
        options = COMPARISONS.map { opSymbol(it) },
        selectedIndex = COMPARISONS.indexOf(condition.op).coerceAtLeast(COMPARISONS.indexOf(CompareOp.GREATER)),
        onSelected = { onChange(condition.copy(op = COMPARISONS[it])) },
        modifier = Modifier.width(132.dp),
    )
    PercentField(
        value = condition.value,
        onValueChange = { onChange(condition.copy(op = condition.op.takeIf { op -> op != CompareOp.DISABLED } ?: CompareOp.GREATER, value = it)) },
        modifier = Modifier.width(NumberWidth),
        title = stringResource(R.string.fn_threshold),
    )
}
