package org.openax12.core.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openax12.core.audio.AudioEngine
import org.openax12.core.audio.TtsStatus
import org.openax12.core.data.DataResult
import org.openax12.core.data.FlightLogger
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.SettingsRepository
import org.openax12.core.data.TelemetryRepository
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.AppSettings
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.SourceId
import org.openax12.core.model.WorkingConfig
import org.openax12.core.radio.ConnectionError
import org.openax12.core.radio.ConnectionState
import org.openax12.core.radio.Radio
import org.openax12.core.radio.RadioEvent

/**
 * The startup / shutdown sequence and the standing watchers of the app graph (SPEC section 9),
 * separated from [DefaultAppGraph] so that it can be tested without an Android context.
 *
 * Construction launches the watchers; nothing is started until [start]. The watchers that read
 * `Radio.events` subscribe immediately (events have no replay), before `Radio.start()`.
 */
internal class GraphLifecycle(
    private val radio: Radio,
    private val models: ModelRepository,
    private val telemetry: TelemetryRepository,
    private val flightLogger: FlightLogger,
    private val audio: AudioEngine,
    private val runtime: RuntimeEngine,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsLog,
    scope: CoroutineScope,
    dispatchers: AppDispatchers,
) {
    private val mutex = Mutex()
    private val _startup = MutableStateFlow<StartupState>(StartupState.NotStarted)

    /** Progress of [start]. */
    val startup: StateFlow<StartupState> = _startup.asStateFlow()

    private var appliedSpeech: Pair<String, Float>? = null

    init {
        scope.launch(dispatchers.default, start = CoroutineStart.UNDISPATCHED) {
            radio.connection.collect(::onConnection)
        }
        scope.launch(dispatchers.default, start = CoroutineStart.UNDISPATCHED) {
            radio.events.collect { event ->
                if (event is RadioEvent.FirmwareVersionMismatch) {
                    diagnostics.record(DiagnosticsLog.SOURCE_RADIO, "the device firmware version is not compatible with the SDK")
                }
            }
        }
        scope.launch(dispatchers.default) {
            audio.ttsStatus.collect { status ->
                val problem = when (status) {
                    TtsStatus.NoEngine -> "no text-to-speech engine is installed"
                    is TtsStatus.LanguageMissing -> "no voice data for ${status.language} in ${status.engine}"
                    is TtsStatus.Error -> "text-to-speech failed: ${status.message}"
                    is TtsStatus.Ready ->
                        if (status.requestedLanguageAvailable) null else "the speech language is missing, using ${status.language}"
                    TtsStatus.Initializing -> null
                }
                if (problem != null) diagnostics.record(DiagnosticsLog.SOURCE_AUDIO, problem)
            }
        }
        scope.launch(dispatchers.default) {
            applyAudioSettings(settings.awaitLoaded())
            settings.settings.collect(::applyAudioSettings)
        }
    }

    /** The startup sequence; see [AppGraph.start]. */
    suspend fun start(): StartupState = mutex.withLock {
        val current = _startup.value
        if (current is StartupState.Running) return current

        applyAudioSettings(settings.awaitLoaded())
        audio.start()

        _startup.value = StartupState.ConnectingRadio
        val reached = radio.start()
        if (reached !is ConnectionState.Ready) {
            val reason = (reached as? ConnectionState.Error)?.reason
                ?: ConnectionError.Unknown("the radio did not become ready ($reached)")
            diagnostics.record(DiagnosticsLog.SOURCE_RADIO, "the radio could not be started: ${reason.message}")
            return failed(reason)
        }

        telemetry.start()

        _startup.value = StartupState.LoadingLibrary
        when (val report = models.reconcileLibrary()) {
            is DataResult.Success -> report.value.problems.forEach {
                diagnostics.record(DiagnosticsLog.SOURCE_LIBRARY, it)
            }
            is DataResult.Failure ->
                diagnostics.record(DiagnosticsLog.SOURCE_LIBRARY, "reconciling the library failed: ${report.error} ${report.detail}")
        }

        runtime.start()
        // The connection may have broken while the library was loading.
        (radio.connection.value as? ConnectionState.Error)?.let {
            runtime.stop()
            return failed(it.reason)
        }
        _startup.value = StartupState.Running
        return StartupState.Running
    }

    /** Stops runtime, telemetry, audio and the radio, in that order. */
    suspend fun shutdown() {
        mutex.withLock {
            runtime.stop()
            telemetry.stop()
            flightLogger.stop()
            audio.shutdown()
            radio.stop()
            _startup.value = StartupState.NotStarted
        }
    }

    private fun failed(reason: ConnectionError): StartupState {
        val state = StartupState.RadioFailed(reason)
        _startup.value = state
        return state
    }

    private fun onConnection(state: ConnectionState) {
        if (state !is ConnectionState.Error) return
        diagnostics.record(DiagnosticsLog.SOURCE_RADIO, "connection error: ${state.reason.message}")
        // A running connection broke: the repositories keep working on local data, start() retries.
        if (_startup.value is StartupState.Running) {
            runtime.stop()
            _startup.value = StartupState.RadioFailed(state.reason)
        }
    }

    /** Pushes the audio-related settings into the audio engine (the graph's job, never the screens'). */
    private fun applyAudioSettings(settings: AppSettings) {
        audio.setMasterVolume(settings.masterVolume.coerceIn(0, 100))
        for (category in AudioCategory.entries) audio.setVolume(category, settings.volumeOf(category))
        audio.setHapticsEnabled(settings.haptics)
        val speech = settings.speechLanguage to settings.speechRate
        // Reconfiguring text-to-speech is not free: only when language or rate really changed.
        if (speech != appliedSpeech) {
            appliedSpeech = speech
            audio.setSpeech(speech.first, speech.second)
        }
    }
}

/**
 * The names the user gave to input sources (blank names left out) from the session's working
 * configuration; the map is rebuilt only when the working configuration is a new instance, so
 * calling it for every frame is cheap.
 */
internal class SourceNameCache(private val session: ConfigSession) : () -> Map<SourceId, String> {

    private var cachedFrom: WorkingConfig? = null
    private var cached: Map<SourceId, String> = emptyMap()

    @Synchronized
    override fun invoke(): Map<SourceId, String> {
        val config = session.workingConfig.value
        if (config !== cachedFrom) {
            cachedFrom = config
            cached = config.sources.filter { it.name.isNotBlank() }.associate { SourceId(it.index) to it.name }
        }
        return cached
    }
}
