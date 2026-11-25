package org.openax12.feature.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.ModelSnapshot
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.ModelType
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.SessionResult

/** The dialog open on the detail screen. */
internal sealed interface DetailDialog {
    /** No dialog. */
    data object None : DetailDialog

    /** Rename the model. */
    data object Rename : DetailDialog

    /** Name for the copy. */
    data object Duplicate : DetailDialog

    /** Edit the notes. */
    data object Notes : DetailDialog

    /** Double confirmation of the deletion. */
    data object Delete : DetailDialog

    /** The snapshot list. */
    data object Snapshots : DetailDialog

    /** Confirmation of restoring [snapshot]. */
    data class RestoreSnapshot(val snapshot: ModelSnapshot) : DetailDialog

    /** Confirmation of deleting [snapshot]. */
    data class DeleteSnapshot(val snapshot: ModelSnapshot) : DetailDialog
}

/**
 * State of the detail screen.
 *
 * @property entry the model, null until the library delivered it (or after it was deleted)
 * @property notFound the library is loaded and has no such model (deleted): the screen closes
 * @property notes the saved notes of the model
 * @property snapshots snapshots, newest first; null while loading
 * @property existingNames names of all models, for the rename / duplicate pre-check
 * @property linked the radio link is up (the restore dialog warns)
 * @property dirty the current model has unsaved changes (the restore dialog warns)
 */
internal data class ModelDetailUiState(
    val entry: ModelEntry? = null,
    val notFound: Boolean = false,
    val notes: String = "",
    val deviceInSync: Boolean = true,
    val dialog: DetailDialog = DetailDialog.None,
    val snapshots: List<ModelSnapshot>? = null,
    val existingNames: List<String> = emptyList(),
    val busy: Boolean = false,
    val guard: DeviceWriteGuard.GuardState = DeviceWriteGuard.GuardState(),
    val linked: Boolean = false,
    val dirty: Boolean = false,
)

/** One-shot results of the detail view model. */
internal sealed interface DetailEvent {
    /** Show a toast. */
    data class Message(val message: UiMessage) : DetailEvent

    /** Open the share sheet for the exported archive at [uri]. */
    data class Share(val uri: Uri, val modelName: String) : DetailEvent
}

/**
 * View model of the detail screen of one model (any model, current or not): rename, duplicate,
 * delete, type, model match, notes, picture removal, export and snapshots, plus selecting it.
 */
internal class ModelDetailViewModel(
    private val modelId: String,
    private val models: ModelRepository,
    private val configSession: ConfigSession,
    private val linkUp: () -> Boolean,
) : ViewModel() {

    private data class Local(
        val notes: String = "",
        val dialog: DetailDialog = DetailDialog.None,
        val snapshots: List<ModelSnapshot>? = null,
        val busy: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val eventChannel = Channel<DetailEvent>(Channel.BUFFERED)

    /** One-shot events. */
    val events: Flow<DetailEvent> = eventChannel.receiveAsFlow()

    private val guard = DeviceWriteGuard(viewModelScope, configSession, linkUp) { emit(it) }

    /** The screen state. */
    val state: StateFlow<ModelDetailUiState> = combine(
        models.models,
        configSession.deviceInSync,
        configSession.dirty,
        local,
        guard.state,
    ) { list, inSync, dirty, loc, g ->
        ModelDetailUiState(
            entry = list.firstOrNull { it.id == modelId },
            notFound = list.isNotEmpty() && list.none { it.id == modelId },
            notes = loc.notes,
            deviceInSync = inSync,
            dialog = loc.dialog,
            snapshots = loc.snapshots,
            existingNames = list.map { it.name },
            busy = loc.busy,
            guard = g,
            linked = linkUp(),
            dirty = dirty,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelDetailUiState())

    init {
        viewModelScope.launch {
            models.readExtras(modelId).getOrNull()?.let { extras -> local.update { it.copy(notes = extras.notes) } }
        }
    }

    // -- Dialog navigation --------------------------------------------------------------------

    /** Opens [dialog]; opening the snapshot list loads the snapshots. */
    fun showDialog(dialog: DetailDialog) {
        local.update { it.copy(dialog = dialog) }
        if (dialog == DetailDialog.Snapshots) loadSnapshots()
    }

    /** Closes the open dialog. */
    fun dismissDialog() = local.update { it.copy(dialog = DetailDialog.None) }

    private fun loadSnapshots() {
        local.update { it.copy(snapshots = null) }
        viewModelScope.launch {
            val list = models.listSnapshots(modelId)
            local.update { it.copy(snapshots = list) }
        }
    }

    // -- Model management ---------------------------------------------------------------------

    /** Renames the model. */
    fun rename(name: String) {
        dismissDialog()
        viewModelScope.launch {
            val result = models.renameModel(modelId, name)
            emit(result.failureMessage() ?: UiMessage(R.string.models_renamed, listOf(name), AxToastKind.Success))
        }
    }

    /** Copies the model under [name]. */
    fun duplicate(name: String) {
        dismissDialog()
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val result = models.duplicateModel(modelId, name)
            local.update { it.copy(busy = false) }
            emit(result.failureMessage() ?: UiMessage(R.string.models_duplicated, listOf(name), AxToastKind.Success))
        }
    }

    /** Deletes the model (never the current one) and closes the screen. */
    fun delete() {
        dismissDialog()
        val entry = state.value.entry ?: return
        if (entry.isCurrent) {
            emit(UiMessage(R.string.models_err_is_current, kind = AxToastKind.Error))
            return
        }
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val result = models.deleteModel(modelId)
            local.update { it.copy(busy = false) }
            val failure = result.failureMessage()
            if (failure != null) {
                emit(failure)
            } else {
                // The screen closes itself when the model disappears from the library (notFound).
                emit(UiMessage(R.string.models_deleted, listOf(entry.name), AxToastKind.Success))
            }
        }
    }

    /** Sets the model type (written to `extras.json` at once, also for models that are not loaded). */
    fun setType(type: ModelType) {
        viewModelScope.launch {
            models.updateExtrasOf(modelId) { it.copy(modelType = type) }.failureMessage()?.let(::emit)
        }
    }

    /** Sets the ELRS model-match number. */
    fun setModelMatch(value: Int) {
        viewModelScope.launch {
            models.setModelMatch(modelId, value.toLong()).failureMessage()?.let(::emit)
        }
    }

    /** Saves the notes. */
    fun setNotes(text: String) {
        dismissDialog()
        viewModelScope.launch {
            val result = models.updateExtrasOf(modelId) { it.copy(notes = text) }
            val extras: ModelExtras? = result.getOrNull()
            if (extras != null) local.update { it.copy(notes = extras.notes) } else result.failureMessage()?.let(::emit)
        }
    }

    /** Back to the default picture of the model type. */
    fun removePicture() {
        viewModelScope.launch {
            val result = models.removePicture(modelId)
            result.failureMessage()?.let(::emit)
        }
    }

    /** Exports the model as `.oax12` and asks the screen to open the share sheet. */
    fun export() {
        val entry = state.value.entry ?: return
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val result = models.exportModel(modelId)
            local.update { it.copy(busy = false) }
            val uri = result.getOrNull()
            if (uri != null) eventChannel.trySend(DetailEvent.Share(uri, entry.name)) else result.failureMessage()?.let(::emit)
        }
    }

    // -- Select / snapshots (device-writing flows) ------------------------------------------------

    /** Makes the model the model of the radio, after the unsaved-changes / link questions. */
    fun select() {
        val entry = state.value.entry ?: return
        if (entry.isCurrent || local.value.busy) return
        guard.request { discard ->
            runBusy {
                val result = configSession.selectModel(entry.id, discardChanges = discard)
                report(result, UiMessage(R.string.models_selected, listOf(entry.name), AxToastKind.Success))
            }
        }
    }

    /** Restores [snapshot]; for the current model this writes to the radio, so the link question comes first. */
    fun restore(snapshot: ModelSnapshot) {
        dismissDialog()
        val current = state.value.entry?.isCurrent == true
        val run: suspend (Boolean) -> Unit = {
            runBusy {
                val result = configSession.restoreSnapshot(snapshot)
                report(result, UiMessage(R.string.models_snapshot_restored, kind = AxToastKind.Success))
                loadSnapshots()
            }
        }
        if (current) guard.request(askUnsaved = false, action = run) else viewModelScope.launch { run(false) }
    }

    /** Deletes [snapshot] and returns to the snapshot list. */
    fun deleteSnapshot(snapshot: ModelSnapshot) {
        local.update { it.copy(dialog = DetailDialog.Snapshots) }
        viewModelScope.launch {
            val failure = models.deleteSnapshot(snapshot).failureMessage()
            if (failure != null) emit(failure)
            loadSnapshots()
        }
    }

    /** "Save" of the unsaved-changes dialog. */
    fun guardSave() = guard.saveThenContinue()

    /** "Discard" of the unsaved-changes dialog. */
    fun guardDiscard() = guard.discardThenContinue()

    /** The user confirmed the link question. */
    fun guardConfirmLink() = guard.confirmLink()

    /** The user cancelled a guard dialog. */
    fun guardCancel() = guard.cancel()

    private suspend fun runBusy(block: suspend () -> Unit) {
        local.update { it.copy(busy = true) }
        try {
            block()
        } finally {
            local.update { it.copy(busy = false) }
        }
    }

    private fun report(result: SessionResult<*>, success: UiMessage) = emit(result.failureMessage() ?: success)

    private fun emit(message: UiMessage) {
        eventChannel.trySend(DetailEvent.Message(message))
    }
}
