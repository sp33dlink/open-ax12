package org.openax12.feature.functions.special

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
import org.openax12.core.model.SPECIAL_FUNCTION_COUNT
import org.openax12.core.model.SpecialAction
import org.openax12.core.model.SpecialFunction
import org.openax12.feature.functions.FunctionsDeps
import org.openax12.feature.functions.common.ActionKind
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.renderPreview
import org.openax12.feature.functions.common.unknownPlaceholders

/** The list tab. */
internal data class SpecialFunctionsState(
    val functions: List<SpecialFunction> = emptyList(),
    val pickers: PickerData = PickerData(),
    val nextFree: Int? = 0,
) {
    /** Slots in use. */
    val used: Int get() = functions.size
}

/** ViewModel of the special-functions tab. */
internal class SpecialFunctionsViewModel(private val deps: FunctionsDeps) : ViewModel() {

    /** UI state. */
    val state: StateFlow<SpecialFunctionsState> = combine(deps.extras, deps.pickerData) { extras, pickers ->
        val functions = extras.specialFunctions.sortedBy { it.index }
        SpecialFunctionsState(functions, pickers, SpecialFunctionRules.nextFreeIndex(functions, SPECIAL_FUNCTION_COUNT))
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        deps.extras.value.specialFunctions.sortedBy { it.index }.let {
            SpecialFunctionsState(it, deps.pickerData.value, SpecialFunctionRules.nextFreeIndex(it, SPECIAL_FUNCTION_COUNT))
        },
    )

    /** Indices of the functions whose trigger is active right now. */
    val active: StateFlow<Set<Int>> = deps.activeFunctions

    /** Enables or disables function [index] (staged like every model edit). */
    fun setEnabled(index: Int, enabled: Boolean) {
        viewModelScope.launch {
            deps.session.updateExtras { extras ->
                val current = extras.specialFunction(index) ?: return@updateExtras extras
                extras.withSpecialFunction(current.copy(enabled = enabled))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** What the special-function editor shows. */
internal data class SpecialFunctionEditorState(
    val draft: SpecialFunction,
    val original: SpecialFunction?,
    val pickers: PickerData = PickerData(),
    val problems: List<SpecialProblem> = emptyList(),
    /** The text of a "play text" action rendered with the current values, or null. */
    val preview: String? = null,
    /** Placeholders of the text that cannot be resolved (hint only). */
    val unknown: List<String> = emptyList(),
    val finished: Boolean = false,
) {
    /** The slot has no stored function yet. */
    val isNew: Boolean get() = original == null

    /** The draft differs from the stored function (a new slot counts as changed once it is configured). */
    val dirty: Boolean get() = draft != (original ?: SpecialFunction(draft.index))

    /** Done is allowed. */
    val canFinish: Boolean get() = !SpecialFunctionRules.hasBlocking(problems)
}

/** Everything the user can do in the special-function editor. */
internal sealed interface SpecialFunctionEditorAction {
    /** Replace the draft by `transform(draft)`. */
    data class Edit(val transform: (SpecialFunction) -> SpecialFunction) : SpecialFunctionEditorAction
    data class SetKind(val kind: ActionKind) : SpecialFunctionEditorAction
    data object Test : SpecialFunctionEditorAction
    data object Done : SpecialFunctionEditorAction
    data object Delete : SpecialFunctionEditorAction
}

/** ViewModel of the editor of special function [index] (0-based). */
internal class SpecialFunctionEditorViewModel(private val index: Int, private val deps: FunctionsDeps) : ViewModel() {

    private val original: SpecialFunction? = deps.extras.value.specialFunction(index)

    private data class Local(val draft: SpecialFunction, val resolver: ValueResolver? = null, val finished: Boolean = false)

    private val local = MutableStateFlow(Local(draft = original ?: SpecialFunction(index)))

    /** UI state. */
    val state: StateFlow<SpecialFunctionEditorState> = combine(local, deps.pickerData) { local, pickers ->
        val text = (local.draft.action as? SpecialAction.PlayText)?.text.orEmpty()
        SpecialFunctionEditorState(
            draft = local.draft,
            original = original,
            pickers = pickers,
            problems = SpecialFunctionRules.problems(local.draft, pickers),
            preview = renderPreview(text, local.resolver),
            unknown = unknownPlaceholders(text, local.resolver),
            finished = local.finished,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        SpecialFunctionEditorState(draft = local.value.draft, original = original),
    )

    init {
        // A snapshot of the current values for the text preview, refreshed once a second.
        viewModelScope.launch {
            while (true) {
                val resolver = deps.services.resolver() ?: break
                local.update { it.copy(resolver = resolver) }
                delay(PREVIEW_PERIOD_MS)
            }
        }
    }

    /** Handles a user action. */
    fun onAction(action: SpecialFunctionEditorAction) {
        when (action) {
            is SpecialFunctionEditorAction.Edit -> local.update { it.copy(draft = action.transform(it.draft).copy(index = index)) }
            is SpecialFunctionEditorAction.SetKind ->
                local.update { it.copy(draft = SpecialFunctionRules.withKind(it.draft, action.kind, deps.pickerData.value)) }
            SpecialFunctionEditorAction.Test -> deps.services.preview(local.value.draft)
            SpecialFunctionEditorAction.Done -> store()
            SpecialFunctionEditorAction.Delete -> viewModelScope.launch {
                if (original != null) deps.session.updateExtras { it.withoutSpecialFunction(index) }
                local.update { it.copy(finished = true) }
            }
        }
    }

    private fun store() {
        val draft = local.value.draft
        if (SpecialFunctionRules.hasBlocking(SpecialFunctionRules.problems(draft, deps.pickerData.value))) return
        viewModelScope.launch {
            if (draft != original) deps.session.updateExtras { it.withSpecialFunction(draft) }
            local.update { it.copy(finished = true) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val PREVIEW_PERIOD_MS = 1_000L
    }
}
