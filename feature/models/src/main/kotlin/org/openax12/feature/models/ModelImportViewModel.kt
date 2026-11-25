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
import org.openax12.core.data.ImportProblem
import org.openax12.core.data.ImportValidation
import org.openax12.core.data.ModelRepository

/** Where the import flow is. */
internal sealed interface ImportPhase {
    /** No file chosen yet. */
    data object Idle : ImportPhase

    /** The file is being validated. */
    data object Inspecting : ImportPhase

    /** The file is valid and waits for the user's go (and name). */
    data class Ready(val info: ImportValidation.Valid) : ImportPhase

    /** The file was rejected; nothing was written. */
    data class Invalid(val problem: ImportProblem, val detail: String) : ImportPhase

    /** The model is being imported. */
    data object Importing : ImportPhase
}

/**
 * State of the import screen.
 *
 * @property name the name the model will get (the suggestion when the file's name is taken)
 * @property existingNames names of the models that already exist
 */
internal data class ImportUiState(
    val phase: ImportPhase = ImportPhase.Idle,
    val name: String = "",
    val existingNames: List<String> = emptyList(),
) {
    /** Why [name] cannot be used, or null. */
    val nameProblem: NameProblem? get() = ModelNameRules.check(name, existingNames)

    /** True if "Import" is enabled. */
    val canImport: Boolean get() = phase is ImportPhase.Ready && nameProblem == null
}

/** One-shot results of the import view model. */
internal sealed interface ImportEvent {
    /** Show a toast. */
    data class Message(val message: UiMessage) : ImportEvent

    /** The model [name] was imported (it is not selected): leave the screen. */
    data class Imported(val name: String) : ImportEvent
}

/**
 * View model of the import flow (SPEC section 4): validate the chosen `.oax12` / stock model
 * file, let the user confirm or change the name, import. Never overwrites: a name clash is
 * resolved by renaming before anything is written.
 */
internal class ModelImportViewModel(
    private val models: ModelRepository,
) : ViewModel() {

    private data class Local(val phase: ImportPhase = ImportPhase.Idle, val name: String = "")

    private val local = MutableStateFlow(Local())
    private val eventChannel = Channel<ImportEvent>(Channel.BUFFERED)
    private var source: Uri? = null

    /** One-shot events. */
    val events: Flow<ImportEvent> = eventChannel.receiveAsFlow()

    /** The screen state. */
    val state: StateFlow<ImportUiState> = combine(local, models.models) { loc, list ->
        ImportUiState(loc.phase, loc.name, list.map { it.name })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportUiState())

    /** Validates [uri] (the chosen or delivered file). */
    fun inspect(uri: Uri) {
        source = uri
        local.value = Local(ImportPhase.Inspecting)
        viewModelScope.launch {
            when (val result = models.inspectImport(uri)) {
                is ImportValidation.Valid -> {
                    val name = if (result.nameExists) result.suggestedName else result.name
                    local.value = Local(ImportPhase.Ready(result), name)
                }
                is ImportValidation.Invalid -> local.value = Local(ImportPhase.Invalid(result.problem, result.detail))
            }
        }
    }

    /** Sets the name for the imported model. */
    fun setName(name: String) = local.update { it.copy(name = name) }

    /** Imports the validated file under the chosen name. */
    fun import() {
        val uri = source ?: return
        val s = state.value
        if (!s.canImport) return
        val name = ModelNameRules.normalize(s.name)
        val before = local.value
        local.update { it.copy(phase = ImportPhase.Importing) }
        viewModelScope.launch {
            val failure = models.importModel(uri, name).failureMessage()
            if (failure == null) {
                eventChannel.trySend(ImportEvent.Imported(name))
            } else {
                local.value = before
                eventChannel.trySend(ImportEvent.Message(failure))
            }
        }
    }
}
