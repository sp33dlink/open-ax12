package org.openax12.feature.functions.sounds

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
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.TextEntryDialog
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.VoiceAlert
import org.openax12.feature.functions.R
import org.openax12.feature.functions.common.BannerTone
import org.openax12.feature.functions.common.EditorScaffold
import org.openax12.feature.functions.common.FormCard
import org.openax12.feature.functions.common.FormRow
import org.openax12.feature.functions.common.InfoBanner
import org.openax12.feature.functions.common.LabelDropdown
import org.openax12.feature.functions.common.RepeatEditor
import org.openax12.feature.functions.common.ScreenPadding
import org.openax12.feature.functions.common.TemplateEditor
import org.openax12.feature.functions.common.TriggerEditor
import org.openax12.feature.functions.common.priorityLabel

/** Editor of one voice alert: text template and priority on the left, trigger and repeat on the right. */
@Composable
internal fun VoiceAlertEditorScreen(
    state: VoiceAlertEditorState,
    onAction: (VoiceAlertEditorAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    val requestBack = { if (state.dirty) confirmDiscard = true else onBack() }
    AxBackHandler(enabled = state.dirty && !confirmDiscard) { confirmDiscard = true }
    fun edit(transform: (VoiceAlert) -> VoiceAlert) = onAction(VoiceAlertEditorAction.Edit(transform))

    EditorScaffold(
        title = stringResource(if (state.isNew) R.string.fn_alert_new else R.string.fn_alert_edit),
        subtitle = draft.name.ifBlank { null },
        onBack = requestBack,
        onDone = { onAction(VoiceAlertEditorAction.Done) },
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
                FormCard(title = stringResource(R.string.fn_alert_what)) {
                    FormRow(label = stringResource(R.string.fn_enabled)) {
                        AxSwitch(checked = draft.enabled, onCheckedChange = { v -> edit { it.copy(enabled = v) } })
                    }
                    FormRow(label = stringResource(R.string.fn_name)) {
                        AxValueBox(
                            text = draft.name.ifBlank { stringResource(R.string.fn_name_optional) },
                            onClick = { naming = true },
                            trailingIcon = AxIcons.Edit,
                            modifier = Modifier.width(220.dp),
                        )
                    }
                    TemplateEditor(
                        text = draft.text,
                        onTextChange = { text -> edit { it.copy(text = text) } },
                        preview = state.preview,
                        unknown = state.unknown,
                    )
                    val priorities = SpeechPriority.entries
                    FormRow(label = stringResource(R.string.fn_priority)) {
                        LabelDropdown(
                            labels = priorities.map { priorityLabel(it) },
                            selectedIndex = priorities.indexOf(draft.priority),
                            onSelected = { index -> edit { it.copy(priority = priorities[index]) } },
                            width = 140.dp,
                        )
                    }
                }
                state.problems.forEach { problem ->
                    InfoBanner(text = problemText(problem), tone = if (problem.blocking) BannerTone.ERROR else BannerTone.WARNING)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AxButton(
                        text = stringResource(R.string.fn_test),
                        onClick = { onAction(VoiceAlertEditorAction.Test) },
                        icon = AxIcons.Play,
                        enabled = state.canFinish,
                    )
                    if (!state.isNew) {
                        AxButton(
                            text = stringResource(R.string.fn_delete),
                            onClick = { onAction(VoiceAlertEditorAction.Delete) },
                            variant = AxButtonVariant.Outlined,
                            icon = AxIcons.Delete,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormCard(title = stringResource(R.string.fn_alert_when)) {
                    TriggerEditor(trigger = draft.trigger, onChange = { t -> edit { it.copy(trigger = t) } }, data = state.pickers)
                }
                RepeatEditor(draft.repeat, onChange = { repeat -> edit { it.copy(repeat = repeat) } })
            }
        }
    }
    if (naming) {
        TextEntryDialog(
            title = stringResource(R.string.fn_name),
            initialValue = draft.name,
            onConfirm = {
                naming = false
                edit { a -> a.copy(name = it) }
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
private fun problemText(problem: VoiceProblem): String = stringResource(
    when (problem) {
        VoiceProblem.TEXT_EMPTY -> R.string.fn_sfp_text_empty
        VoiceProblem.TEXT_INVALID -> R.string.fn_sfp_text_invalid
        VoiceProblem.TRIGGER_NEVER -> R.string.fn_problem_never
    },
)
