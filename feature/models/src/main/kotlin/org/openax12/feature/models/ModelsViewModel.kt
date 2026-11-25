package org.openax12.feature.models

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
import org.openax12.core.data.ExternalChange
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.ModelRepository
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.model.RfModuleMode
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.SessionResult

/**
 * State of the model list.
 *
 * @property models the library, already sorted for display
 * @property deviceInSync false while the loaded model is not confirmed on the radio
 * @property needsStoragePermission the library works from the app folder because storage access is missing
 * @property libraryUnavailable no writable storage at all
 * @property externalChanges library folders modified outside the app
 * @property reviewing the external change the user is deciding on
 * @property busy a device operation runs: the screen is blocked
 * @property guard the pending confirmation of a device-writing operation
 */
internal data class ModelsUiState(
    val loaded: Boolean = false,
    val models: List<ModelEntry> = emptyList(),
    val layout: ModelsLayout = ModelsLayout.GRID,
    val sort: ModelSort = ModelSort.NAME,
    val deviceInSync: Boolean = true,
    val needsStoragePermission: Boolean = false,
    val libraryUnavailable: Boolean = false,
    val externalChanges: List<ExternalChange> = emptyList(),
    val reviewing: ExternalChange? = null,
    val busy: Boolean = false,
    val guard: DeviceWriteGuard.GuardState = DeviceWriteGuard.GuardState(),
)

/** One-shot results of the list's view model. */
internal sealed interface ModelsEvent {
    /** Show a toast. */
    data class Message(val message: UiMessage) : ModelsEvent
}

/**
 * View model of the model list: select (with the unsaved-changes and radio-link questions),
 * sort / layout, "write to radio" and the decisions about library folders changed outside the app.
 *
 * Create, rename, duplicate, delete and the rest live on the other screens.
 */
internal class ModelsViewModel(
    private val models: ModelRepository,
    private val configSession: ConfigSession,
    linkUp: () -> Boolean,
) : ViewModel() {

    private data class Local(
        val layout: ModelsLayout = ModelsLayout.GRID,
        val sort: ModelSort = ModelSort.NAME,
        val reviewing: ExternalChange? = null,
        val busy: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val eventChannel = Channel<ModelsEvent>(Channel.BUFFERED)

    /** One-shot messages. */
    val events: Flow<ModelsEvent> = eventChannel.receiveAsFlow()

    private val guard = DeviceWriteGuard(viewModelScope, configSession, linkUp) { emit(it) }

    private val library = combine(
        models.models,
        models.storage,
        models.externalChanges,
        configSession.deviceInSync,
    ) { list, storage, changes, inSync -> Library(list, storage.permissionGranted, storage.isAvailable, changes, inSync) }

    private class Library(
        val models: List<ModelEntry>,
        val permissionGranted: Boolean,
        val available: Boolean,
        val changes: List<ExternalChange>,
        val inSync: Boolean,
    )

    /** The screen state. */
    val state: StateFlow<ModelsUiState> = combine(library, local, guard.state) { lib, loc, g ->
        ModelsUiState(
            loaded = true,
            models = sortModels(lib.models, loc.sort),
            layout = loc.layout,
            sort = loc.sort,
            deviceInSync = lib.inSync,
            needsStoragePermission = !lib.permissionGranted,
            libraryUnavailable = !lib.available,
            externalChanges = lib.changes,
            reviewing = loc.reviewing?.takeIf { it in lib.changes },
            busy = loc.busy,
            guard = g,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUiState())

    /** Switches between grid and list. */
    fun setLayout(layout: ModelsLayout) = local.update { it.copy(layout = layout) }

    /** Changes the sort order. */
    fun setSort(sort: ModelSort) = local.update { it.copy(sort = sort) }

    /** Selects [entry] as the model of the radio, after the unsaved-changes / link questions. */
    fun select(entry: ModelEntry) {
        if (entry.isCurrent || local.value.busy) return
        guard.request { discard ->
            runBusy {
                val result = configSession.selectModel(entry.id, discardChanges = discard)
                report(result, UiMessage(R.string.models_selected, listOf(entry.name), AxToastKind.Success))
                warnIfRfOff(result)
            }
        }
    }

    /** "Write to radio": saves the working config (re-sends it) after the link question. */
    fun writeToRadio() {
        if (local.value.busy) return
        guard.request(askUnsaved = false) {
            runBusy {
                val result = configSession.save()
                report(result, UiMessage(R.string.models_written, kind = AxToastKind.Success))
            }
        }
    }

    /** Opens the review dialog for [change]. */
    fun review(change: ExternalChange) = local.update { it.copy(reviewing = change) }

    /** Closes the review dialog without deciding. */
    fun dismissReview() = local.update { it.copy(reviewing = null) }

    /** Re-imports the externally modified folder (writes to the radio if it is the current model). */
    fun acceptChange(change: ExternalChange) {
        local.update { it.copy(reviewing = null) }
        val current = models.models.value.firstOrNull { it.id == change.modelId }?.isCurrent == true
        val run: suspend (Boolean) -> Unit = {
            runBusy {
                val result = configSession.acceptExternalChange(change)
                report(result, UiMessage(R.string.models_change_accepted, listOf(change.name), AxToastKind.Success))
            }
        }
        if (current) guard.request(askUnsaved = false, action = run) else viewModelScope.launch { run(false) }
    }

    /** Keeps the app's copy (or ignores a new folder): the folder is overwritten / no longer offered. */
    fun rejectChange(change: ExternalChange) {
        local.update { it.copy(reviewing = null) }
        viewModelScope.launch {
            val result = models.rejectExternalChange(change)
            result.failureMessage()?.let(::emit)
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

    /**
     * The RF module is a setting of the radio, not of the model, and a model that was just loaded
     * flies with it: if it is off the model does not transmit, which the user must hear about.
     */
    private fun warnIfRfOff(result: SessionResult<*>) {
        if (result.isSuccess && configSession.workingConfig.value.system?.rfModuleMode == RfModuleMode.OFF) {
            emit(UiMessage(R.string.models_rf_off_warning, kind = AxToastKind.Warning))
        }
    }

    private fun report(result: SessionResult<*>, success: UiMessage) {
        emit(result.failureMessage() ?: success)
    }

    private fun emit(message: UiMessage) {
        eventChannel.trySend(ModelsEvent.Message(message))
    }
}
