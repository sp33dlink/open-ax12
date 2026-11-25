package org.openax12.feature.functions.sounds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.audio.TtsStatus
import org.openax12.core.model.Announcements
import org.openax12.core.model.VarioConfig
import org.openax12.core.model.VoiceAlert
import org.openax12.feature.functions.FunctionsDeps
import org.openax12.feature.functions.common.PickerData

/** What the Sounds tab shows. */
internal data class SoundsState(
    val alerts: List<VoiceAlert> = emptyList(),
    val vario: VarioConfig = VarioConfig(),
    val announcements: Announcements = Announcements(),
    val pickers: PickerData = PickerData(),
    val tts: TtsStatus = TtsStatus.Initializing,
)

/** ViewModel of the Sounds tab: voice alerts, vario and the built-in announcements. All edits are staged like any model edit. */
internal class SoundsViewModel(private val deps: FunctionsDeps) : ViewModel() {

    /** UI state. */
    val state: StateFlow<SoundsState> = combine(deps.extras, deps.pickerData, deps.ttsStatus) { extras, pickers, tts ->
        SoundsState(extras.voiceAlerts, extras.vario, extras.announcements, pickers, tts)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        deps.extras.value.let { SoundsState(it.voiceAlerts, it.vario, it.announcements, deps.pickerData.value, deps.ttsStatus.value) },
    )

    /** Enables or disables the alert [id]. */
    fun setAlertEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            deps.session.updateExtras { extras ->
                extras.copy(voiceAlerts = extras.voiceAlerts.map { if (it.id == id) it.copy(enabled = enabled) else it })
            }
        }
    }

    /** Speaks the alert [id] now. */
    fun testAlert(id: String) {
        state.value.alerts.firstOrNull { it.id == id }?.let(deps.services::preview)
    }

    /** Changes the vario settings; the result is normalised (see [VarioEdits.normalize]). */
    fun editVario(transform: (VarioConfig) -> VarioConfig) {
        viewModelScope.launch {
            deps.session.updateExtras { extras -> extras.copy(vario = VarioEdits.normalize(transform(extras.vario))) }
        }
    }

    /** Back to the default vario settings. */
    fun resetVario() {
        viewModelScope.launch { deps.session.updateExtras { it.copy(vario = VarioConfig()) } }
    }

    /** Sounds the vario for [verticalSpeed] m/s for two seconds. */
    fun testVario(verticalSpeed: Double) {
        deps.services.previewVario(verticalSpeed)
    }

    /** Changes the built-in announcements. */
    fun editAnnouncements(transform: (Announcements) -> Announcements) {
        viewModelScope.launch {
            deps.session.updateExtras { it.copy(announcements = transform(it.announcements)) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
