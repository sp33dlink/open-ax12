package org.openax12.feature.models

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openax12.core.runtime.ConfigSession

/** Which confirmation a [DeviceWriteGuard] is waiting for. */
internal enum class GuardStep {
    /** Nothing pending. */
    NONE,

    /** There are unsaved model-setup changes: Save / Discard / Cancel. */
    UNSAVED,

    /** The radio link is up: confirm that the aircraft is safe. */
    LINK_UP,
}

/**
 * The questions that come before an operation which writes a model to the device (select,
 * create, restore, "write now"), SPEC section 9: unsaved changes first (Save / Discard / Cancel),
 * then, if the link is up, a hold-to-confirm. Only after both did the user agree does the action
 * run. The screens show the dialog of the current [GuardState.step] and call the matching
 * function.
 *
 * @param linkUp reads whether the radio link is up right now (`LiveSnapshot.status.isLinkUp`)
 * @param onMessage receives the failure message when saving before the action fails
 */
internal class DeviceWriteGuard(
    private val scope: CoroutineScope,
    private val configSession: ConfigSession,
    private val linkUp: () -> Boolean,
    private val onMessage: (UiMessage) -> Unit,
) {
    /** Snapshot of the guard for the UI. [saving] is true while "Save" of the unsaved dialog runs. */
    data class GuardState(val step: GuardStep = GuardStep.NONE, val saving: Boolean = false)

    private val _state = MutableStateFlow(GuardState())

    /** The pending question. */
    val state: StateFlow<GuardState> = _state.asStateFlow()

    private var pending: (suspend (discardChanges: Boolean) -> Unit)? = null
    private var discard = false

    /**
     * Starts the questions for [action]; the action receives `true` when the user chose to
     * discard unsaved changes. Ignored while another request is pending.
     *
     * @param askUnsaved false for operations that drop unsaved changes by design (re-import,
     *   snapshot restore) or that save them themselves ("write now")
     */
    fun request(askUnsaved: Boolean = true, action: suspend (discardChanges: Boolean) -> Unit) {
        if (_state.value.step != GuardStep.NONE) return
        pending = action
        discard = false
        if (askUnsaved && configSession.dirty.value) {
            _state.value = GuardState(GuardStep.UNSAVED)
        } else {
            afterUnsaved()
        }
    }

    /** "Save" of the unsaved dialog: saves, then continues. A failed save cancels the request. */
    fun saveThenContinue() {
        if (_state.value.step != GuardStep.UNSAVED || _state.value.saving) return
        scope.launch {
            _state.update { it.copy(saving = true) }
            val result = configSession.save()
            val failure = result.failureMessage()
            if (failure == null) {
                discard = false
                afterUnsaved()
            } else {
                cancel()
                onMessage(failure)
            }
        }
    }

    /** "Discard" of the unsaved dialog: continues with `discardChanges = true`. */
    fun discardThenContinue() {
        if (_state.value.step != GuardStep.UNSAVED) return
        discard = true
        afterUnsaved()
    }

    /** The user confirmed the link-up question. */
    fun confirmLink() {
        if (_state.value.step != GuardStep.LINK_UP) return
        run()
    }

    /** The user cancelled (or dismissed a dialog): nothing happens. */
    fun cancel() {
        pending = null
        discard = false
        _state.value = GuardState()
    }

    private fun afterUnsaved() {
        if (linkUp()) _state.value = GuardState(GuardStep.LINK_UP) else run()
    }

    private fun run() {
        val action = pending
        val discardChanges = discard
        pending = null
        discard = false
        _state.value = GuardState()
        if (action != null) scope.launch { action(discardChanges) }
    }
}
