package org.openax12.feature.functions.sounds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openax12.core.engine.ValueResolver
import org.openax12.core.model.Trigger
import org.openax12.core.model.VoiceAlert
import org.openax12.core.model.newItemId
import org.openax12.feature.functions.FunctionsDeps
import org.openax12.feature.functions.common.MAX_TEMPLATE_LENGTH
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.firstTemplateError
import org.openax12.feature.functions.common.renderPreview
import org.openax12.feature.functions.common.unknownPlaceholders

/** What is wrong with a voice alert as edited. */
internal enum class VoiceProblem(val blocking: Boolean) {
    /** The text is empty. */
    TEXT_EMPTY(true),

    /** The text template has a syntax error or is too long. */
    TEXT_INVALID(true),

    /** The trigger is "never": the alert would never be spoken. */
    TRIGGER_NEVER(false),
}

/** The problems of [alert], blocking ones first. */
internal fun voiceProblems(alert: VoiceAlert): List<VoiceProblem> {
    val found = ArrayList<VoiceProblem>()
    when {
        alert.text.isBlank() -> found += VoiceProblem.TEXT_EMPTY
        alert.text.length > MAX_TEMPLATE_LENGTH || firstTemplateError(alert.text) != null -> found += VoiceProblem.TEXT_INVALID
    }
    if (alert.trigger == Trigger.Never) found += VoiceProblem.TRIGGER_NEVER
    return found.sortedBy { !it.blocking }
}

/** Replaces the alert with the same id in place, or appends [alert] as a new one. */
internal fun upsertAlert(alerts: List<VoiceAlert>, alert: VoiceAlert): List<VoiceAlert> =
    if (alerts.any { it.id == alert.id }) alerts.map { if (it.id == alert.id) alert else it } else alerts + alert

/** What the voice-alert editor shows. */
internal data class VoiceAlertEditorState(
    val draft: VoiceAlert,
    val original: VoiceAlert?,
    val pickers: PickerData = PickerData(),
    val problems: List<VoiceProblem> = emptyList(),
    val preview: String? = null,
    val unknown: List<String> = emptyList(),
    val finished: Boolean = false,
) {
    /** The alert has not been stored yet. */
    val isNew: Boolean get() = original == null

    /** The draft differs from the stored alert (a new alert counts once it has any content). */
    val dirty: Boolean get() = draft != (original ?: VoiceAlert(id = draft.id))

    /** Done is allowed. */
    val canFinish: Boolean get() = problems.none { it.blocking }
}

/** Everything the user can do in the voice-alert editor. */
internal sealed interface VoiceAlertEditorAction {
    data class Edit(val transform: (VoiceAlert) -> VoiceAlert) : VoiceAlertEditorAction
    data object Test : VoiceAlertEditorAction
    data object Done : VoiceAlertEditorAction
    data object Delete : VoiceAlertEditorAction
}

/** ViewModel of the editor of the voice alert [alertId]; null creates a new alert. */
internal class VoiceAlertViewModel(alertId: String?, private val deps: FunctionsDeps) : ViewModel() {

    private val original: VoiceAlert? = alertId?.let { id -> deps.extras.value.voiceAlerts.firstOrNull { it.id == id } }
    private val id: String = original?.id ?: alertId ?: newItemId()

    private data class Local(val draft: VoiceAlert, val resolver: ValueResolver? = null, val finished: Boolean = false)

    private val local = MutableStateFlow(Local(draft = original ?: VoiceAlert(id = id)))

    /** UI state. */
    val state: StateFlow<VoiceAlertEditorState> = combine(local, deps.pickerData) { local, pickers ->
        VoiceAlertEditorState(
            draft = local.draft,
            original = original,
            pickers = pickers,
            problems = voiceProblems(local.draft),
            preview = renderPreview(local.draft.text, local.resolver),
            unknown = unknownPlaceholders(local.draft.text, local.resolver),
            finished = local.finished,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), VoiceAlertEditorState(draft = local.value.draft, original = original))

    init {
        viewModelScope.launch {
            while (true) {
                val resolver = deps.services.resolver() ?: break
                local.update { it.copy(resolver = resolver) }
                delay(PREVIEW_PERIOD_MS)
            }
        }
    }

    /** Handles a user action. */
    fun onAction(action: VoiceAlertEditorAction) {
        when (action) {
            is VoiceAlertEditorAction.Edit -> local.update { it.copy(draft = action.transform(it.draft).copy(id = id)) }
            VoiceAlertEditorAction.Test -> deps.services.preview(local.value.draft)
            VoiceAlertEditorAction.Done -> store()
            VoiceAlertEditorAction.Delete -> viewModelScope.launch {
                if (original != null) deps.session.updateExtras { extras -> extras.copy(voiceAlerts = extras.voiceAlerts.filterNot { it.id == id }) }
                local.update { it.copy(finished = true) }
            }
        }
    }

    private fun store() {
        val draft = local.value.draft
        if (voiceProblems(draft).any { it.blocking }) return
        viewModelScope.launch {
            if (draft != original) deps.session.updateExtras { extras -> extras.copy(voiceAlerts = upsertAlert(extras.voiceAlerts, draft)) }
            local.update { it.copy(finished = true) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val PREVIEW_PERIOD_MS = 1_000L
    }
}
