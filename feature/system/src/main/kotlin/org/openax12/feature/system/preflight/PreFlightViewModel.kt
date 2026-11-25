package org.openax12.feature.system.preflight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.audio.AudioEngine
import org.openax12.core.data.ModelRepository
import org.openax12.core.model.ChecklistItem
import org.openax12.core.model.SpeechPriority
import org.openax12.core.radio.Radio
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.RuntimeEngine
import org.openax12.feature.system.common.edit

/**
 * Pre-flight overview (retained firmware alarm, presets of the system configuration) and the
 * per-model checklist. The checklist lives in the model's extras and is an *immediate* extra:
 * `ModelRepository.updateExtras` persists it at once and it never makes the session dirty.
 */
internal class PreFlightViewModel(
    radio: Radio,
    session: ConfigSession,
    private val runtime: RuntimeEngine,
    private val models: ModelRepository,
    private val audio: AudioEngine,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val checked = MutableStateFlow<Set<Int>>(emptySet())

    private val first = combine(
        radio.connection,
        session.workingConfig,
        runtime.preFlightCheck,
        models.currentExtras,
        models.currentModel,
    ) { connection, working, states, extras, model ->
        PreFlightUiState(
            connection = connection,
            rows = preFlightRows(working.takeUnless { it.isEmpty }?.system, working.sources, states),
            alarmPending = states != null,
            modelName = model?.name.orEmpty(),
            checklist = extras.checklist,
        )
    }

    /** The state of the screen. */
    val state: StateFlow<PreFlightUiState> = combine(first, checked) { base, ticked ->
        base.copy(checked = ticked.filter { it in base.checklist.indices }.toSet())
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), PreFlightUiState())

    /** Dismisses the firmware's pre-flight alarm. */
    fun skip() {
        scope.launch { runtime.skipPreFlightCheck() }
    }

    /** Ticks or unticks a checklist line. */
    fun toggleChecked(index: Int) = checked.edit { if (index in it) it - index else it + index }

    /** Clears all ticks. */
    fun resetChecks() {
        checked.value = emptySet()
    }

    /** Appends a checklist line. */
    fun add(text: String) = change { ChecklistOps.add(it, text) }

    /** Changes the text of a line. */
    fun rename(index: Int, text: String) = change { ChecklistOps.rename(it, index, text) }

    /** Toggles "read aloud" of a line. */
    fun toggleSpoken(index: Int) = change { ChecklistOps.toggleSpoken(it, index) }

    /** Deletes a line. */
    fun remove(index: Int) {
        checked.value = emptySet()
        change { ChecklistOps.remove(it, index) }
    }

    /** Moves a line up (-1) or down (+1). */
    fun move(index: Int, delta: Int) {
        checked.value = emptySet()
        change { ChecklistOps.move(it, index, delta) }
    }

    /** Reads the lines marked "read aloud" through the speech engine. */
    fun readAloud() {
        val texts = ChecklistOps.spokenTexts(state.value.checklist)
        texts.forEachIndexed { position, text ->
            audio.speak(text, SpeechPriority.NORMAL, flush = position == 0)
        }
    }

    private fun change(transform: (List<ChecklistItem>) -> List<ChecklistItem>) {
        scope.launch { models.updateExtras { it.copy(checklist = transform(it.checklist)) } }
    }
}
