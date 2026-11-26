package org.openax12.feature.system.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.audio.AudioEngine
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.SettingsRepository
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.FirmwareInfo
import org.openax12.core.model.RadioBackend
import org.openax12.core.model.SpeechPriority
import org.openax12.core.radio.Radio
import org.openax12.core.radio.RadioEvent
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.DiagnosticsLog
import org.openax12.core.runtime.RuntimeEngine
import org.openax12.feature.system.common.Feedback
import org.openax12.feature.system.common.edit
import org.openax12.feature.system.common.linkUpFlow
import org.openax12.feature.system.common.toFeedback

/**
 * State and actions of the system settings screen (radio, audio, app, storage, about).
 *
 * Radio settings go through [ConfigSession.updateSystemConfig] (never `Radio.saveConfigToDevice`),
 * app settings through [SettingsRepository.update]; the app graph applies the latter to the audio
 * engine and the logger, so nothing here pushes volumes.
 *
 * @param scopeOverride scope for tests; production uses the ViewModel's own
 */
internal class SettingsViewModel(
    private val radio: Radio,
    private val session: ConfigSession,
    private val settings: SettingsRepository,
    private val audio: AudioEngine,
    private val runtime: RuntimeEngine,
    models: ModelRepository,
    private val diagnostics: DiagnosticsLog,
    private val activeBackend: RadioBackend,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    private data class Extra(
        val busy: Boolean = false,
        val pending: RadioAction? = null,
        val usb: Boolean = false,
        val languages: List<String> = emptyList(),
        val firmware: FirmwareInfo? = null,
    )

    private val extra = MutableStateFlow(Extra())
    private val feedbackChannel = Channel<Feedback>(Channel.BUFFERED)

    /** One-shot outcome messages for toasts. */
    val feedback: Flow<Feedback> = feedbackChannel.receiveAsFlow()

    private val first = combine(
        settings.settings,
        session.workingConfig,
        radio.connection,
        radio.linkUpFlow(),
        extra,
    ) { app, working, connection, linkUp, ex ->
        SettingsUiState(
            app = app,
            system = working.takeUnless { it.isEmpty }?.system,
            connection = connection,
            linkUp = linkUp,
            languages = ex.languages,
            firmware = ex.firmware,
            usbPassthrough = ex.usb,
            busy = ex.busy,
            pending = ex.pending,
            sdkVersion = radio.sdkVersion,
            activeBackend = activeBackend,
        )
    }

    /** The state of the screen. */
    val state: StateFlow<SettingsUiState> = combine(
        first,
        session.dirty,
        session.deviceInSync,
        audio.ttsStatus,
        models.storage,
    ) { base, dirty, inSync, tts, storage ->
        base.copy(dirty = dirty, deviceInSync = inSync, tts = tts, storage = storage)
    }.combine(audio.musicPlaying) { base, music -> base.copy(musicPlaying = music) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(activeBackend = activeBackend))

    init {
        scope.launch { reloadFirmware() }
        scope.launch {
            audio.ttsStatus.collect { status ->
                val languages = audio.availableLanguages()
                extra.edit { it.copy(languages = languages) }
            }
        }
        scope.launch {
            radio.events.collect { event -> if (event is RadioEvent.FirmwareInfoUpdated) reloadFirmware() }
        }
    }

    /** Handles every UI event. */
    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.Update -> scope.launch { settings.update(event.transform) }
            is SettingsEvent.Radio -> request(event.action)
            SettingsEvent.ConfirmPending -> extra.value.pending?.let { action ->
                extra.edit { it.copy(pending = null) }
                perform(action)
            }
            SettingsEvent.DismissPending -> extra.edit { it.copy(pending = null) }
            is SettingsEvent.TestSound -> testSound(event)
            is SettingsEvent.TestHaptic -> audio.haptic(event.pattern)
            SettingsEvent.RequestDeviceInfo -> scope.launch {
                if (!radio.requestDeviceInfo()) send(Feedback.RADIO_NOT_READY)
            }
            // Navigation and the storage permission belong to the activity-side wrapper.
            is SettingsEvent.Open, SettingsEvent.RequestStoragePermission -> Unit
        }
    }

    private fun request(action: RadioAction) {
        if (action.needsConfirmation(state.value.linkUp)) {
            extra.edit { it.copy(pending = action) }
        } else {
            perform(action)
        }
    }

    private fun perform(action: RadioAction) {
        scope.launch {
            extra.edit { it.copy(busy = true) }
            val outcome = try {
                run(action)
            } finally {
                extra.edit { it.copy(busy = false) }
            }
            send(outcome)
        }
    }

    private suspend fun run(action: RadioAction): Feedback = when (action) {
        is RadioAction.SetStickMode -> session.updateSystemConfig { it.copy(stickMode = action.mode) }.toFeedback()
        is RadioAction.SetAlarmVoltage -> session.updateSystemConfig { it.copy(alarmVoltageDeciVolt = action.deciVolt) }.toFeedback()
        // `perform` is reached for "RF off" only through the confirmation dialog (see `request`).
        is RadioAction.SetRfMode -> session.updateSystemConfig(rfOffConfirmed = action.turnsRfOff) {
            it.copy(rfModuleMode = action.mode)
        }.toFeedback()
        RadioAction.RestoreDefaults -> session.resetToDefaults().toFeedback()
        RadioAction.RebootRf -> if (!radio.connection.value.isReady) {
            Feedback.RADIO_NOT_READY
        } else if (radio.rebootRfModule()) {
            Feedback.DONE
        } else {
            Feedback.FAILED
        }
        is RadioAction.UsbPassthrough -> if (!radio.connection.value.isReady) {
            Feedback.RADIO_NOT_READY
        } else if (radio.setUsbSerialPassthrough(if (action.enabled) USB_PASSTHROUGH_ON else USB_PASSTHROUGH_OFF)) {
            extra.edit { it.copy(usb = action.enabled) }
            Feedback.DONE
        } else {
            Feedback.FAILED
        }
    }.also { feedback ->
        if (feedback == Feedback.FAILED) diagnostics.record(DiagnosticsLog.SOURCE_RADIO, "system action rejected: $action")
    }

    private fun testSound(test: SettingsEvent.TestSound) {
        scope.launch {
            if (test.afterSettingChange) delay(SETTLE_MS)
            when (test.category) {
                AudioCategory.VOICE -> audio.speak(test.speech, SpeechPriority.NORMAL, AudioCategory.VOICE, flush = true)
                AudioCategory.ALARM -> {
                    audio.beep(BeepPattern.WARNING, AudioCategory.ALARM)
                    audio.speak(test.speech, SpeechPriority.ALARM, AudioCategory.ALARM, flush = true)
                }
                AudioCategory.VARIO -> runtime.previewVario(VARIO_TEST_SPEED)
                AudioCategory.BEEP -> audio.beep(BeepPattern.CONFIRM, AudioCategory.BEEP)
                AudioCategory.SOUND -> {
                    val file = audio.listSoundFiles().firstOrNull()
                    if (file == null || !audio.playSound(file.name, SpeechPriority.NORMAL, AudioCategory.SOUND)) send(Feedback.NO_SOUND_FILES)
                }
                AudioCategory.MUSIC -> {
                    if (audio.musicPlaying.value) {
                        audio.stopMusic()
                    } else {
                        val file = audio.listSoundFiles().firstOrNull()
                        if (file == null || !audio.startMusic(file.name, loop = true)) send(Feedback.NO_SOUND_FILES)
                    }
                }
            }
        }
    }

    private suspend fun reloadFirmware() {
        val info = radio.getFirmwareInfo()
        extra.edit { it.copy(firmware = info) }
    }

    private fun send(feedback: Feedback) {
        feedbackChannel.trySend(feedback)
    }

    override fun onCleared() {
        // A music test must not keep playing after the screen is left.
        if (audio.musicPlaying.value) audio.stopMusic()
    }

    private companion object {
        const val SETTLE_MS = 250L
        const val VARIO_TEST_SPEED = 2.0
    }
}

/** Applies [transform] on top of the volume of one category; used by the volume rows. */
internal fun volumeUpdate(category: AudioCategory, percent: Int): SettingsEvent =
    SettingsEvent.Update { it.withVolume(category, percent) }

/** Master volume update. */
internal fun masterVolumeUpdate(percent: Int): SettingsEvent =
    SettingsEvent.Update { it.copy(masterVolume = percent.coerceIn(0, 100)) }

