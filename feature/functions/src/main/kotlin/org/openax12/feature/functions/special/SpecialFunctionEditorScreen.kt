package org.openax12.feature.functions.special

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.PercentField
import org.openax12.core.designsystem.PickerDialog
import org.openax12.core.designsystem.PickerOption
import org.openax12.core.designsystem.TextEntryDialog
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.SourceId
import org.openax12.core.model.SpecialAction
import org.openax12.core.model.SpecialFunction
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.navigation.SetupTabIds
import org.openax12.feature.functions.R
import org.openax12.feature.functions.common.ActionKind
import org.openax12.feature.functions.common.BannerTone
import org.openax12.feature.functions.common.Caption
import org.openax12.feature.functions.common.EditorScaffold
import org.openax12.feature.functions.common.FormCard
import org.openax12.feature.functions.common.FormRow
import org.openax12.feature.functions.common.InfoBanner
import org.openax12.feature.functions.common.LabelDropdown
import org.openax12.feature.functions.common.NumberWidth
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.RepeatEditor
import org.openax12.feature.functions.common.ScreenPadding
import org.openax12.feature.functions.common.SensorField
import org.openax12.feature.functions.common.SourceField
import org.openax12.feature.functions.common.TemplateEditor
import org.openax12.feature.functions.common.TriggerEditor
import org.openax12.feature.functions.common.actionKindLabel
import org.openax12.feature.functions.common.beepLabel
import org.openax12.feature.functions.common.hapticLabel
import org.openax12.feature.functions.common.kind
import org.openax12.feature.functions.common.priorityLabel

/**
 * Editor of one special function: "when" (trigger and repeat) on the left, "do" (the action and
 * its parameters, test, delete) on the right.
 *
 * @param onOpenTab switches the model-setup tab (the vario action points to the Sounds tab)
 */
@Composable
internal fun SpecialFunctionEditorScreen(
    state: SpecialFunctionEditorState,
    onAction: (SpecialFunctionEditorAction) -> Unit,
    onBack: () -> Unit,
    onOpenTab: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    val requestBack = { if (state.dirty) confirmDiscard = true else onBack() }
    AxBackHandler(enabled = state.dirty && !confirmDiscard) { confirmDiscard = true }
    fun edit(transform: (SpecialFunction) -> SpecialFunction) = onAction(SpecialFunctionEditorAction.Edit(transform))

    EditorScaffold(
        title = stringResource(R.string.fn_sf_editor_title, draft.label),
        subtitle = draft.name.ifBlank { null },
        onBack = requestBack,
        onDone = { onAction(SpecialFunctionEditorAction.Done) },
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
                FormCard(title = stringResource(R.string.fn_sf_general)) {
                    FormRow(label = stringResource(R.string.fn_enabled)) {
                        AxSwitch(checked = draft.enabled, onCheckedChange = { value -> edit { it.copy(enabled = value) } })
                    }
                    FormRow(label = stringResource(R.string.fn_name)) {
                        AxValueBox(
                            text = draft.name.ifBlank { stringResource(R.string.fn_name_optional) },
                            onClick = { naming = true },
                            trailingIcon = AxIcons.Edit,
                            modifier = Modifier.width(220.dp),
                        )
                    }
                }
                FormCard(title = stringResource(R.string.fn_sf_when)) {
                    TriggerEditor(trigger = draft.trigger, onChange = { t -> edit { it.copy(trigger = t) } }, data = state.pickers)
                }
                if (!draft.action.isContinuous) {
                    RepeatEditor(draft.repeat, onChange = { repeat -> edit { it.copy(repeat = repeat) } })
                } else {
                    InfoBanner(text = stringResource(R.string.fn_sf_continuous_note))
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.problems.forEach { problem ->
                    InfoBanner(text = problemText(problem), tone = if (problem.blocking) BannerTone.ERROR else BannerTone.WARNING)
                }
                ActionCard(state, onAction, ::edit, onOpenTab)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AxButton(
                        text = stringResource(R.string.fn_test),
                        onClick = { onAction(SpecialFunctionEditorAction.Test) },
                        icon = AxIcons.Play,
                        enabled = state.canFinish,
                    )
                    if (!state.isNew) {
                        AxButton(
                            text = stringResource(R.string.fn_delete),
                            onClick = { onAction(SpecialFunctionEditorAction.Delete) },
                            variant = AxButtonVariant.Outlined,
                            icon = AxIcons.Delete,
                        )
                    }
                }
            }
        }
    }
    if (naming) {
        TextEntryDialog(
            title = stringResource(R.string.fn_name),
            initialValue = draft.name,
            onConfirm = {
                naming = false
                edit { f -> f.copy(name = it) }
            },
            onDismiss = { naming = false },
            maxLength = 24,
            allowEmpty = true,
        )
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
private fun problemText(problem: SpecialProblem): String = stringResource(
    when (problem) {
        SpecialProblem.TRIGGER_NEVER -> R.string.fn_problem_never
        SpecialProblem.TEXT_EMPTY -> R.string.fn_sfp_text_empty
        SpecialProblem.TEXT_INVALID -> R.string.fn_sfp_text_invalid
        SpecialProblem.FILE_MISSING -> R.string.fn_sfp_file_missing
        SpecialProblem.FILE_NOT_FOUND -> R.string.fn_sfp_file_not_found
        SpecialProblem.SENSOR_UNKNOWN -> R.string.fn_problem_sensor
        SpecialProblem.TIMER_INVALID -> R.string.fn_sfp_timer_invalid
    },
)

@Composable
private fun ActionCard(
    state: SpecialFunctionEditorState,
    onAction: (SpecialFunctionEditorAction) -> Unit,
    edit: ((SpecialFunction) -> SpecialFunction) -> Unit,
    onOpenTab: (String) -> Unit,
) {
    val draft = state.draft
    val action = draft.action
    var picking by rememberSaveable { mutableStateOf(false) }
    FormCard(title = stringResource(R.string.fn_sf_do)) {
        FormRow(label = stringResource(R.string.fn_sf_action)) {
            AxValueBox(
                text = actionKindLabel(action.kind()),
                onClick = { picking = true },
                trailingIcon = AxIcons.ExpandMore,
                modifier = Modifier.width(240.dp),
            )
        }
        ActionFields(
            action = action,
            data = state.pickers,
            preview = state.preview,
            unknown = state.unknown,
            onChange = { new -> edit { it.copy(action = new) } },
            onOpenTab = onOpenTab,
        )
    }
    if (picking) {
        PickerDialog(
            title = stringResource(R.string.fn_sf_action),
            options = SpecialFunctionRules.KINDS.map { PickerOption(it, actionKindLabel(it)) },
            selected = action.kind(),
            onSelect = {
                picking = false
                onAction(SpecialFunctionEditorAction.SetKind(it))
            },
            onDismiss = { picking = false },
            columns = 2,
            searchable = false,
            width = 520.dp,
        )
    }
}

@Composable
private fun ActionFields(
    action: SpecialAction,
    data: PickerData,
    preview: String?,
    unknown: List<String>,
    onChange: (SpecialAction) -> Unit,
    onOpenTab: (String) -> Unit,
) {
    when (action) {
        is SpecialAction.PlayText -> {
            TemplateEditor(
                text = action.text,
                onTextChange = { onChange(action.copy(text = it)) },
                preview = preview,
                unknown = unknown,
            )
            PriorityRow(action.priority) { onChange(action.copy(priority = it)) }
        }
        is SpecialAction.PlayValue -> {
            FormRow(label = stringResource(R.string.fn_sf_sensor)) {
                SensorField(selected = action.sensor, data = data, onSelected = { onChange(action.copy(sensor = it)) })
            }
            FormRow(label = stringResource(R.string.fn_sf_with_unit)) {
                AxSwitch(checked = action.withUnit, onCheckedChange = { onChange(action.copy(withUnit = it)) })
            }
            FormRow(label = stringResource(R.string.fn_sf_with_name)) {
                AxSwitch(checked = action.withName, onCheckedChange = { onChange(action.copy(withName = it)) })
            }
            PriorityRow(action.priority) { onChange(action.copy(priority = it)) }
        }
        is SpecialAction.PlaySound -> {
            SoundFileRow(action.file, data) { onChange(action.copy(file = it)) }
        }
        is SpecialAction.BackgroundMusic -> {
            SoundFileRow(action.file, data) { onChange(action.copy(file = it)) }
            FormRow(label = stringResource(R.string.fn_sf_loop)) {
                AxSwitch(checked = action.loop, onCheckedChange = { onChange(action.copy(loop = it)) })
            }
        }
        SpecialAction.Vario -> {
            InfoBanner(
                text = stringResource(R.string.fn_sf_vario_note),
                actionLabel = stringResource(R.string.fn_sf_open_sounds),
                onAction = { onOpenTab(SetupTabIds.SOUNDS) },
            )
        }
        is SpecialAction.Haptic -> {
            val patterns = HapticPattern.entries
            FormRow(label = stringResource(R.string.fn_sf_pattern)) {
                LabelDropdown(
                    labels = patterns.map { hapticLabel(it) },
                    selectedIndex = patterns.indexOf(action.pattern),
                    onSelected = { onChange(action.copy(pattern = patterns[it])) },
                )
            }
        }
        is SpecialAction.Beep -> {
            val patterns = BeepPattern.entries
            FormRow(label = stringResource(R.string.fn_sf_pattern)) {
                LabelDropdown(
                    labels = patterns.map { beepLabel(it) },
                    selectedIndex = patterns.indexOf(action.pattern),
                    onSelected = { onChange(action.copy(pattern = patterns[it])) },
                    title = stringResource(R.string.fn_sf_pattern),
                )
            }
        }
        is SpecialAction.ResetTimer -> {
            FormRow(label = stringResource(R.string.fn_sf_reset_what)) {
                AxSegmented(
                    options = listOf(stringResource(R.string.fn_sf_timer), stringResource(R.string.fn_sf_counter)),
                    selectedIndex = if (action.counter) 1 else 0,
                    onSelected = { onChange(action.copy(counter = it == 1)) },
                    modifier = Modifier.width(200.dp),
                )
            }
            val labels = List(TIMER_COUNT) { (if (action.counter) "CNT" else "T") + (it + 1) }
            FormRow(label = stringResource(R.string.fn_sf_number)) {
                LabelDropdown(
                    labels = labels,
                    selectedIndex = action.timer.coerceIn(0, TIMER_COUNT - 1),
                    onSelected = { onChange(action.copy(timer = it)) },
                    width = 100.dp,
                )
            }
        }
        SpecialAction.ResetTelemetry, SpecialAction.ResetFlight, SpecialAction.Logging, SpecialAction.Screenshot -> {
            Caption(stringResource(noteFor(action.kind())))
        }
        is SpecialAction.SetVolume -> {
            FormRow(label = stringResource(R.string.fn_sf_volume_source), description = stringResource(R.string.fn_sf_volume_help)) {
                SourceField(selected = action.source, options = data.knobs, onSelected = { onChange(action.copy(source = it)) })
            }
        }
        is SpecialAction.Brightness -> {
            AxSegmented(
                options = listOf(stringResource(R.string.fn_sf_brightness_fixed), stringResource(R.string.fn_sf_brightness_follow)),
                selectedIndex = if (action.source == null) 0 else 1,
                onSelected = { index ->
                    onChange(action.copy(source = if (index == 0) null else (data.knobs.firstOrNull()?.id ?: SourceId.pot(1))))
                },
                modifier = Modifier.width(300.dp),
            )
            val source = action.source
            if (source == null) {
                FormRow(label = stringResource(R.string.fn_sf_brightness_percent)) {
                    PercentField(
                        value = action.percent,
                        onValueChange = { onChange(action.copy(percent = it)) },
                        min = 0,
                        max = 100,
                        defaultValue = 100,
                        title = stringResource(R.string.fn_sf_brightness_percent),
                        modifier = Modifier.width(NumberWidth),
                    )
                }
            } else {
                FormRow(label = stringResource(R.string.fn_sf_volume_source)) {
                    SourceField(selected = source, options = data.knobs, onSelected = { onChange(action.copy(source = it)) })
                }
            }
        }
    }
}

private fun noteFor(kind: ActionKind): Int = when (kind) {
    ActionKind.RESET_TELEMETRY -> R.string.fn_sf_note_reset_telemetry
    ActionKind.RESET_FLIGHT -> R.string.fn_sf_note_reset_flight
    ActionKind.LOGGING -> R.string.fn_sf_note_logging
    else -> R.string.fn_sf_note_screenshot
}

@Composable
private fun PriorityRow(priority: SpeechPriority, onChange: (SpeechPriority) -> Unit) {
    val priorities = SpeechPriority.entries
    FormRow(label = stringResource(R.string.fn_priority)) {
        LabelDropdown(
            labels = priorities.map { priorityLabel(it) },
            selectedIndex = priorities.indexOf(priority),
            onSelected = { onChange(priorities[it]) },
            width = 140.dp,
        )
    }
}

@Composable
private fun SoundFileRow(file: String, data: PickerData, onChange: (String) -> Unit) {
    val files = if (file.isNotBlank() && file !in data.soundFiles) data.soundFiles + file else data.soundFiles
    if (files.isEmpty()) {
        Caption(stringResource(R.string.fn_sf_no_sounds))
        return
    }
    FormRow(label = stringResource(R.string.fn_sf_file)) {
        LabelDropdown(
            labels = files,
            selectedIndex = files.indexOf(file),
            onSelected = { onChange(files[it]) },
            width = 240.dp,
            title = stringResource(R.string.fn_sf_file),
        )
    }
}
