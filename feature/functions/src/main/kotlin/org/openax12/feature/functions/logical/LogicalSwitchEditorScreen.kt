package org.openax12.feature.functions.logical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxBackHandler
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.PickerDialog
import org.openax12.core.designsystem.PickerOption
import org.openax12.core.designsystem.TextEntryDialog
import org.openax12.core.model.LogicalFunction
import org.openax12.core.model.LogicalSwitch
import org.openax12.core.model.Trigger
import org.openax12.feature.functions.R
import org.openax12.feature.functions.common.BannerTone
import org.openax12.feature.functions.common.Caption
import org.openax12.feature.functions.common.EditorScaffold
import org.openax12.feature.functions.common.FormCard
import org.openax12.feature.functions.common.FormRow
import org.openax12.feature.functions.common.InfoBanner
import org.openax12.feature.functions.common.MAX_TENTHS
import org.openax12.feature.functions.common.NumberWidth
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.ScreenPadding
import org.openax12.feature.functions.common.SensorField
import org.openax12.feature.functions.common.StatePill
import org.openax12.feature.functions.common.TriggerEditor
import org.openax12.feature.functions.common.TriggerKind
import org.openax12.feature.functions.common.defaultTrigger
import org.openax12.feature.functions.common.functionHelp
import org.openax12.feature.functions.common.functionLabel
import kotlin.math.roundToInt

/** Editor of one logical switch: function and operands on the left, modifiers and state on the right. */
@Composable
internal fun LogicalSwitchEditorScreen(
    state: LogicalSwitchEditorState,
    onAction: (LogicalSwitchEditorAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestBack = { if (state.dirty) confirmDiscard = true else onBack() }
    AxBackHandler(enabled = state.dirty && !confirmDiscard) { confirmDiscard = true }
    fun edit(transform: (LogicalSwitch) -> LogicalSwitch) = onAction(LogicalSwitchEditorAction.Edit(transform))

    EditorScaffold(
        title = stringResource(R.string.fn_ls_editor_title, draft.label),
        subtitle = when {
            !draft.isUsed -> stringResource(R.string.fn_empty)
            draft.name.isNotBlank() -> draft.name + " · " + functionLabel(draft.function)
            else -> functionLabel(draft.function)
        },
        onBack = requestBack,
        onDone = { onAction(LogicalSwitchEditorAction.Done) },
        dirty = state.dirty,
        doneEnabled = state.canFinish,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FunctionCard(state, ::edit, onAction)
                if (draft.isUsed) OperandsCard(draft, state, ::edit)
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.problems.forEach { problem ->
                    InfoBanner(text = problemText(problem), tone = if (problem.blocking) BannerTone.ERROR else BannerTone.WARNING)
                }
                if (draft.isUsed) {
                    ModifiersCard(draft, state.pickers, ::edit)
                    StateCard(state, onClear = { onAction(LogicalSwitchEditorAction.Clear) })
                }
                InfoBanner(text = stringResource(R.string.fn_ls_banner), tone = BannerTone.INFO)
            }
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.fn_discard_title),
            message = stringResource(R.string.fn_discard_message),
            confirmLabel = stringResource(R.string.fn_discard),
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun problemText(problem: LogicalProblem): String = stringResource(
    when (problem) {
        LogicalProblem.MISSING_A -> R.string.fn_lsp_missing_a
        LogicalProblem.MISSING_B -> R.string.fn_lsp_missing_b
        LogicalProblem.MISSING_S1 -> R.string.fn_lsp_missing_s1
        LogicalProblem.MISSING_S2 -> R.string.fn_lsp_missing_s2
        LogicalProblem.SELF_REFERENCE -> R.string.fn_problem_self
        LogicalProblem.TIMER_ZERO -> R.string.fn_lsp_timer_zero
        LogicalProblem.UNKNOWN_SENSOR -> R.string.fn_problem_sensor
    },
)

@Composable
private fun FunctionCard(
    state: LogicalSwitchEditorState,
    edit: ((LogicalSwitch) -> LogicalSwitch) -> Unit,
    onAction: (LogicalSwitchEditorAction) -> Unit,
) {
    val draft = state.draft
    var picking by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    FormCard {
        FormRow(label = stringResource(R.string.fn_ls_function)) {
            AxValueBox(
                text = functionLabel(draft.function),
                onClick = { picking = true },
                trailingIcon = AxIcons.ExpandMore,
                modifier = Modifier.width(240.dp),
            )
        }
        if (draft.isUsed) {
            FormRow(label = stringResource(R.string.fn_name)) {
                AxValueBox(
                    text = draft.name.ifBlank { stringResource(R.string.fn_name_optional) },
                    onClick = { naming = true },
                    trailingIcon = AxIcons.Edit,
                    modifier = Modifier.width(240.dp),
                )
            }
        }
        Caption(functionHelp(draft.function))
    }
    if (picking) {
        val functions = LogicalFunction.entries
        PickerDialog(
            title = stringResource(R.string.fn_ls_function),
            options = functions.map { PickerOption(it, functionLabel(it), description = functionHelp(it)) },
            selected = draft.function,
            onSelect = {
                picking = false
                onAction(LogicalSwitchEditorAction.SetFunction(it))
            },
            onDismiss = { picking = false },
            columns = 1,
            searchable = false,
            width = 520.dp,
        )
    }
    if (naming) {
        TextEntryDialog(
            title = stringResource(R.string.fn_name),
            initialValue = draft.name,
            onConfirm = {
                naming = false
                edit { ls -> ls.copy(name = it) }
            },
            onDismiss = { naming = false },
            maxLength = 24,
            allowEmpty = true,
        )
    }
}

@Composable
private fun OperandsCard(
    draft: LogicalSwitch,
    state: LogicalSwitchEditorState,
    edit: ((LogicalSwitch) -> LogicalSwitch) -> Unit,
) {
    val function = draft.function
    val data = state.pickers
    FormCard(title = stringResource(R.string.fn_ls_operands)) {
        if (function.usesA) {
            FormRow(label = stringResource(if (function.usesB) R.string.fn_ls_operand_a else R.string.fn_ls_value)) {
                state.liveA?.let { Caption(text = it, modifier = Modifier.padding(end = 8.dp)) }
                SensorField(selected = draft.a, data = data, onSelected = { sensor -> edit { it.copy(a = sensor) } })
            }
        }
        if (function.usesB) {
            FormRow(label = stringResource(R.string.fn_ls_operand_b)) {
                state.liveB?.let { Caption(text = it, modifier = Modifier.padding(end = 8.dp)) }
                SensorField(selected = draft.b, data = data, onSelected = { sensor -> edit { it.copy(b = sensor) } })
            }
        }
        if (function.usesX) {
            val unit = data.sensorUnit(draft.a).symbol
            FormRow(label = stringResource(R.string.fn_ls_constant)) {
                NumberField(
                    value = (draft.x * 10).roundToInt(),
                    onValueChange = { v -> edit { it.copy(x = v / 10.0) } },
                    min = -MAX_TENTHS,
                    max = MAX_TENTHS,
                    decimals = 1,
                    unit = if (unit.isEmpty()) "" else " $unit",
                    title = stringResource(R.string.fn_ls_constant),
                    modifier = Modifier.width(130.dp),
                )
            }
        }
        if (function.usesConditions) {
            ConditionBlock(
                label = stringResource(if (LogicalSwitchRules.usesS2(function)) R.string.fn_ls_condition_1 else R.string.fn_ls_condition),
                trigger = draft.s1,
                onChange = { t -> edit { it.copy(s1 = t) } },
                data = data,
                selfIndex = draft.index,
            )
            if (LogicalSwitchRules.usesS2(function)) {
                ConditionBlock(
                    label = stringResource(if (function == LogicalFunction.STICKY) R.string.fn_ls_reset_by else R.string.fn_ls_condition_2),
                    trigger = draft.s2,
                    onChange = { t -> edit { it.copy(s2 = t) } },
                    data = data,
                    selfIndex = draft.index,
                )
            }
        }
        if (function.usesTimes) {
            val edge = function == LogicalFunction.EDGE
            FormRow(label = stringResource(if (edge) R.string.fn_ls_edge_min else R.string.fn_ls_timer_on)) {
                SecondsField(draft.t1Sec, { v -> edit { it.copy(t1Sec = v) } }, stringResource(if (edge) R.string.fn_ls_edge_min else R.string.fn_ls_timer_on))
            }
            FormRow(label = stringResource(if (edge) R.string.fn_ls_edge_max else R.string.fn_ls_timer_off)) {
                SecondsField(draft.t2Sec, { v -> edit { it.copy(t2Sec = v) } }, stringResource(if (edge) R.string.fn_ls_edge_max else R.string.fn_ls_timer_off))
            }
        }
    }
}

@Composable
private fun ConditionBlock(
    label: String,
    trigger: Trigger?,
    onChange: (Trigger) -> Unit,
    data: PickerData,
    selfIndex: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = AxTheme.typography.bodyStrong, color = AxTheme.colors.text)
        TriggerEditor(
            trigger = trigger ?: defaultTrigger(TriggerKind.SWITCH, data),
            onChange = onChange,
            data = data,
            kinds = TriggerKind.CONDITIONS,
            selfLogical = selfIndex,
        )
    }
}

/** Seconds with one decimal in a numeric field. */
@Composable
private fun SecondsField(seconds: Double, onChange: (Double) -> Unit, title: String) {
    NumberField(
        value = (seconds * 10).roundToInt(),
        onValueChange = { onChange(it / 10.0) },
        min = 0,
        max = 36_000,
        decimals = 1,
        unit = " s",
        title = title,
        defaultValue = 0,
        modifier = Modifier.width(NumberWidth + 16.dp),
    )
}

@Composable
private fun ModifiersCard(draft: LogicalSwitch, data: PickerData, edit: ((LogicalSwitch) -> LogicalSwitch) -> Unit) {
    FormCard(title = stringResource(R.string.fn_ls_modifiers)) {
        FormRow(label = stringResource(R.string.fn_ls_and_use), description = stringResource(R.string.fn_ls_and_help)) {
            AxSwitch(
                checked = draft.andCondition != null,
                onCheckedChange = { on ->
                    edit { it.copy(andCondition = if (on) defaultTrigger(TriggerKind.SWITCH, data) else null) }
                },
            )
        }
        draft.andCondition?.let { condition ->
            TriggerEditor(
                trigger = condition,
                onChange = { t -> edit { it.copy(andCondition = t) } },
                data = data,
                kinds = TriggerKind.CONDITIONS,
                selfLogical = draft.index,
            )
        }
        FormRow(label = stringResource(R.string.fn_ls_delay), description = stringResource(R.string.fn_ls_delay_help)) {
            SecondsField(draft.delaySec, { v -> edit { it.copy(delaySec = v) } }, stringResource(R.string.fn_ls_delay))
        }
        FormRow(label = stringResource(R.string.fn_ls_duration), description = stringResource(R.string.fn_ls_duration_help)) {
            SecondsField(draft.durationSec, { v -> edit { it.copy(durationSec = v) } }, stringResource(R.string.fn_ls_duration))
        }
    }
}

@Composable
private fun StateCard(state: LogicalSwitchEditorState, onClear: () -> Unit) {
    FormCard {
        FormRow(label = stringResource(R.string.fn_ls_state_now)) {
            StatePill(
                text = stringResource(if (state.isOn) R.string.fn_on else R.string.fn_off),
                active = state.isOn,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AxButton(
                text = stringResource(R.string.fn_ls_clear),
                onClick = onClear,
                variant = AxButtonVariant.Outlined,
                icon = AxIcons.Delete,
            )
        }
    }
}
