package org.openax12.core.runtime

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import org.openax12.core.audio.AudioEngine
import org.openax12.core.audio.createAudioEngine
import org.openax12.core.data.FlightLogger
import org.openax12.core.data.LibraryBackup
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.SettingsRepository
import org.openax12.core.data.TelemetryRepository
import org.openax12.core.data.createDataLayer
import org.openax12.core.data.createSettingsRepository
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.RadioBackend
import org.openax12.core.radio.ConnectionError
import org.openax12.core.radio.Radio

/** Where the app is in its startup sequence (SPEC section 9). Shown by the UI as connecting / ready / error. */
sealed interface StartupState {

    /** [AppGraph.start] was not called yet. */
    data object NotStarted : StartupState

    /** Initializing the radio (SDK init, serial port, initial device read). */
    data object ConnectingRadio : StartupState

    /** Reconciling the model library with the SDK's models. */
    data object LoadingLibrary : StartupState

    /** Everything runs. */
    data object Running : StartupState

    /**
     * The radio could not be started, **or the running connection broke** (`Radio.connection`
     * left `Ready` for `Error`; the state then changes from [Running] to this). The repositories
     * and the UI still work on local data; [AppGraph.start] retries. The UI shows the
     * stock-app hint when `reason.mayBeStockAppConflict`.
     */
    data class RadioFailed(val reason: ConnectionError) : StartupState

    /** True for [Running]. */
    val isRunning: Boolean get() = this is Running
}

/**
 * The app's service locator: every long-lived object, created once per process and owned by the
 * application. There is no DI framework — composables read `LocalAppGraph.current`, ViewModels
 * get what they need as constructor arguments from a factory that reads the graph.
 *
 * ## Who applies settings
 * **The graph**, not the screens: for its whole lifetime it collects `settings.settings` and
 * forwards `masterVolume`, the per-category `volumes`, `speechLanguage` / `speechRate` and
 * `haptics` to the [audio] engine (the first value before `audio.start()`), and `logging` /
 * `logIntervalMs` to the flight logger through the runtime. Screens only call
 * `SettingsRepository.update`. Theme, units, keep-screen-on and touch lock are read by the app
 * shell from the same flow. `radioBackend` takes effect at the next process start.
 */
interface AppGraph {

    /** The radio: real SDK or simulator, chosen once per process from `AppSettings.radioBackend`. */
    val radio: Radio

    /** System-wide app settings. */
    val settings: SettingsRepository
    /** The model library and the current model's extras. */
    val models: ModelRepository
    /** The sensor registry. */
    val telemetry: TelemetryRepository
    /** CSV flight logging. */
    val flightLogger: FlightLogger
    /** Backup / restore of the whole library. */
    val backup: LibraryBackup
    /** Speech, tones, sounds, haptics. Configured from the settings by the graph, not by screens. */
    val audio: AudioEngine
    /** The app-side convenience logic (logical switches, special functions, alarms, announcements). */
    val runtime: RuntimeEngine
    /** The editing session: dirty state and every flow that writes to the device. */
    val configSession: ConfigSession

    /** History of errors and notable events for the diagnostics screen and its export. */
    val diagnostics: DiagnosticsLog

    /** Application-wide scope (SupervisorJob, default dispatcher); lives as long as the process. */
    val scope: CoroutineScope

    /** The dispatchers every long-lived object was created with. */
    val dispatchers: AppDispatchers

    /** The backend [radio] really is (never [RadioBackend.AUTO]). */
    val activeBackend: RadioBackend

    /** Progress of [start]. */
    val startup: StateFlow<StartupState>

    /**
     * Runs the startup sequence: start audio → start the radio and wait for the device config →
     * start the telemetry repository → reconcile the model library → start the runtime engine.
     * Idempotent while running; after [StartupState.RadioFailed] it retries. Called by the
     * foreground service (`RadioService`); safe to call from any thread.
     *
     * Everything that listens to `Radio.events` for retained state — the runtime engine's
     * pre-flight check state, the config session's connection handling, the diagnostics log —
     * exists and collects **before** `radio.start()` is called, because events have no replay.
     * After a connection loss the same sequence runs again from "start the radio".
     */
    suspend fun start(): StartupState

    /** Stops runtime, telemetry, audio and the radio (in that order). Called when the service is destroyed. */
    suspend fun shutdown()
}

/**
 * The [AppGraph] for composables. Provided by the app shell; screenshot tests provide a fake.
 * Prefer passing plain state into stateless composables and reading the graph only in the thin
 * stateful wrapper of a screen.
 */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("No AppGraph provided. Wrap the content in CompositionLocalProvider(LocalAppGraph provides …).")
}

/**
 * The production [AppGraph].
 *
 * Construction is cheap and does no I/O beyond reading the stored settings once to pick the radio
 * backend; nothing is started until [start].
 *
 * It records in [diagnostics]: every `ConnectionState.Error`, the failed start of the radio, the
 * reconcile report's problems, firmware mismatch events and `TtsStatus` errors (the details of
 * failed radio tasks and of `SessionResult.Failure` / `SaveState.Failed` are recorded by the
 * [ConfigSession] itself, which is where they arise). It applies the settings as described at
 * [AppGraph].
 *
 * @param context any context; the application context is used
 * @param radioFactory creates the radio for the configured backend. It receives the value from
 *   the settings **unresolved** — the factory (in `:app`) decides what [RadioBackend.AUTO] means
 *   (SDK on the AX12, simulator elsewhere), creating `SdkRadio` or `FakeRadio`. Called exactly once.
 */
class DefaultAppGraph(
    context: Context,
    private val radioFactory: (RadioBackend) -> Radio,
) : AppGraph {

    private val appContext: Context = context.applicationContext

    override val dispatchers: AppDispatchers = AppDispatchers()
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)
    override val diagnostics: DiagnosticsLog = DefaultDiagnosticsLog()
    override val settings: SettingsRepository = createSettingsRepository(appContext, scope, dispatchers)
    override val radio: Radio = radioFactory(configuredBackend())

    private val dataLayer = createDataLayer(appContext, radio, settings, scope, dispatchers)
    override val models: ModelRepository = dataLayer.models
    override val telemetry: TelemetryRepository = dataLayer.telemetry
    override val flightLogger: FlightLogger = dataLayer.flightLogger
    override val backup: LibraryBackup = dataLayer.backup

    override val audio: AudioEngine = createAudioEngine(appContext, scope, dispatchers) {
        models.storage.value.soundsDir.takeIf { it.isNotEmpty() }?.let(::File)
    }

    override val configSession: ConfigSession = DefaultConfigSession(radio, models, diagnostics, scope, dispatchers)

    override val runtime: RuntimeEngine = DefaultRuntimeEngine(
        radio = radio,
        models = models,
        telemetry = telemetry,
        settings = settings,
        audio = audio,
        flightLogger = flightLogger,
        diagnostics = diagnostics,
        phrases = RuntimePhrases.fromContext(appContext),
        scope = scope,
        dispatchers = dispatchers,
        sourceNames = SourceNameCache(configSession),
    )

    override val activeBackend: RadioBackend =
        if (radio.isSimulator) RadioBackend.SIMULATOR else RadioBackend.SDK

    private val lifecycle = GraphLifecycle(
        radio = radio,
        models = models,
        telemetry = telemetry,
        flightLogger = flightLogger,
        audio = audio,
        runtime = runtime,
        settings = settings,
        diagnostics = diagnostics,
        scope = scope,
        dispatchers = dispatchers,
    )

    override val startup: StateFlow<StartupState> get() = lifecycle.startup

    override suspend fun start(): StartupState = lifecycle.start()

    override suspend fun shutdown() = lifecycle.shutdown()

    /** The backend stored in the settings; reading them is the one blocking thing the constructor does. */
    private fun configuredBackend(): RadioBackend = try {
        runBlocking(dispatchers.io) { settings.awaitLoaded().radioBackend }
    } catch (e: Exception) {
        diagnostics.record(DiagnosticsLog.SOURCE_RADIO, "settings could not be read, using the automatic backend: ${e.message}")
        RadioBackend.AUTO
    }
}
