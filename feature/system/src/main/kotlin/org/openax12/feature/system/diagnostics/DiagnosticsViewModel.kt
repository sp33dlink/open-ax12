package org.openax12.feature.system.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext
import org.openax12.core.audio.AudioEngine
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.TelemetryRepository
import org.openax12.core.engine.CurveMath
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.CurveType
import org.openax12.core.model.ELRS_TX_ADDRESS
import org.openax12.core.model.ElrsMenuItem
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.RadioBackend
import org.openax12.core.model.Sources
import org.openax12.core.radio.Radio
import org.openax12.core.radio.RadioEvent
import org.openax12.core.radio.getCurrentModel
import org.openax12.core.radio.getDualRates
import org.openax12.core.radio.getTimerConfigs
import org.openax12.core.runtime.DiagnosticsLog
import org.openax12.core.runtime.RuntimeEngine
import org.openax12.core.runtime.StartupState
import org.openax12.feature.system.common.Feedback
import org.openax12.feature.system.common.edit

/** The screen state: the data, the computed checks and what the user selected. */
internal data class DiagnosticsUiState(
    val data: DiagnosticsData = DiagnosticsData(),
    val checks: List<AssumptionCheck> = emptyList(),
    /** Curve (0-based) the probe evaluates. */
    val curveSelection: Int = 0,
    val refreshing: Boolean = false,
    val probing: Set<CheckAction> = emptySet(),
)

/** A finished export: the report text and where it was saved (if it could be). */
internal data class ReportOutput(val text: String, val savedAs: String?)

/**
 * Diagnostics: reads everything needed to verify `docs/ASSUMPTIONS.md` on the device, runs the
 * on-demand probes (curve evaluation, ELRS item types, audio / haptic tests) and exports a text
 * report. Reads only; the only thing it writes is the report file in the library's logs folder.
 */
internal class DiagnosticsViewModel(
    private val radio: Radio,
    private val audio: AudioEngine,
    private val runtime: RuntimeEngine,
    models: ModelRepository,
    telemetry: TelemetryRepository,
    private val diagnostics: DiagnosticsLog,
    startup: StateFlow<StartupState>,
    private val backend: RadioBackend,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val snapshot = MutableStateFlow(DiagnosticsData(isSimulator = radio.isSimulator, backend = backend, sdkVersion = radio.sdkVersion))
    private val local = MutableStateFlow(DiagnosticsUiState())
    private val observed = MutableStateFlow<Map<Int, Set<Int>>>(emptyMap())
    private val feedbackChannel = Channel<Feedback>(Channel.BUFFERED)
    private val reportChannel = Channel<ReportOutput>(Channel.BUFFERED)
    private var refreshJob: Job? = null

    /** One-shot outcome messages for toasts. */
    val feedback: Flow<Feedback> = feedbackChannel.receiveAsFlow()

    /** Finished exports, to be shared by the screen. */
    val reports: Flow<ReportOutput> = reportChannel.receiveAsFlow()

    private val liveParts = combine(
        snapshot,
        radio.connection,
        startup,
        telemetry.crsfStats,
        audio.ttsStatus,
    ) { base, connection, startupState, crsf, tts ->
        base.copy(connection = connection, startup = startupState, crsf = crsf, tts = tts)
    }

    /** The state of the screen. */
    val state: StateFlow<DiagnosticsUiState> = combine(
        liveParts,
        models.storage,
        diagnostics.entries,
        observed,
        local,
    ) { base, storage, log, switches, ui ->
        val data = base.copy(storage = storage, log = log, observedSwitches = switches)
        ui.copy(data = data, checks = AssumptionChecks.evaluate(data))
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    init {
        scope.launch {
            radio.live.collect { frame ->
                val known = observed.value
                var next: MutableMap<Int, Set<Int>>? = null
                for (index in Sources.SWITCHES) {
                    val value = frame.logicAt(index)
                    val seen = (next ?: known)[index]
                    if (seen == null || value !in seen) {
                        if (next == null) next = known.toMutableMap()
                        next[index] = (seen ?: emptySet()) + value
                    }
                }
                if (next != null) observed.value = next
            }
        }
        refresh()
    }

    /** Re-reads the configuration based facts from the radio. */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            local.edit { it.copy(refreshing = true) }
            try {
                val sources = radio.getSources()
                val channels = radio.getOutputChannels()
                val modelsList = radio.getModels()
                val freeSlots = channels.map { radio.findFreeDualRateSlot(it.channel)?.slot }
                val enabled = channels.map { channel -> radio.getDualRates(channel.channel).count { it.isEnabled } }
                val curves = if (snapshot.value.curveProbe == null) radio.getCurves() else emptyList()
                snapshot.edit {
                    it.copy(
                        capturedMs = clock(),
                        firmware = radio.getFirmwareInfo(),
                        system = radio.getSystemConfig(),
                        status = radio.getRadioStatus() ?: radio.live.value.status,
                        sources = sources,
                        channels = channels,
                        models = modelsList,
                        currentModelRaw = radio.getCurrentModelIndex(),
                        currentModel = radio.getCurrentModel(),
                        timers = radio.getTimerConfigs(isCounter = false),
                        counters = radio.getTimerConfigs(isCounter = true),
                        freeDualRateSlots = freeSlots,
                        enabledDualRateSlots = enabled,
                    )
                }
                if (curves.isNotEmpty()) {
                    val first = curves.firstOrNull { it.type == CurveType.CUSTOM } ?: curves.first()
                    local.edit { ui -> ui.copy(curveSelection = first.index) }
                }
            } finally {
                local.edit { it.copy(refreshing = false) }
            }
        }
    }

    /** Selects the curve (0-based) of the probe. */
    fun selectCurve(index: Int) = local.edit { it.copy(curveSelection = index) }

    /** Runs one probe or test button of a check. */
    fun run(action: CheckAction) {
        when (action) {
            CheckAction.CURVE_PROBE -> runCurveProbe()
            CheckAction.ELRS_PROBE -> runElrsProbe()
            CheckAction.TEST_VARIO -> runtime.previewVario(VARIO_TEST_SPEED)
            CheckAction.TEST_BEEPS -> scope.launch {
                audio.beep(BeepPattern.TRIM_STEP)
                delay(BEEP_GAP_MS)
                audio.beep(BeepPattern.TRIM_CENTER)
                delay(BEEP_GAP_MS)
                audio.beep(BeepPattern.TRIM_LIMIT)
            }
            CheckAction.TEST_HAPTIC -> audio.haptic(HapticPattern.LONG)
        }
    }

    private fun runCurveProbe() {
        val index = local.value.curveSelection
        scope.launch {
            val curve = radio.getCurve(index)
            if (curve == null) {
                feedbackChannel.trySend(Feedback.RADIO_NOT_READY)
                return@launch
            }
            val rows = CurveProbe.INPUTS.map { input ->
                CurveProbeRow(input, radio.applyCurve(index, input.toDouble()), CurveMath.apply(curve, input.toDouble()))
            }
            snapshot.edit { it.copy(curveProbe = CurveProbe(index, curve.type, rows)) }
        }
    }

    private fun runElrsProbe() {
        if (CheckAction.ELRS_PROBE in local.value.probing) return
        scope.launch {
            local.edit { it.copy(probing = it.probing + CheckAction.ELRS_PROBE) }
            try {
                val items = LinkedHashMap<Int, ElrsMenuItem>()
                var deviceName = ""
                val collector = launch {
                    radio.events.collect { event ->
                        when (event) {
                            is RadioEvent.ElrsDeviceInfo -> deviceName = event.deviceName
                            is RadioEvent.ElrsMenuItemReceived -> items[event.item.index] = event.item
                            else -> Unit
                        }
                    }
                }
                if (radio.requestElrsMenu(ELRS_TX_ADDRESS)) {
                    var last = -1
                    var step = 0
                    while (step < ELRS_PROBE_STEPS) {
                        delay(ELRS_PROBE_STEP_MS)
                        step++
                        if (items.isNotEmpty() && items.size == last) break
                        last = items.size
                    }
                }
                collector.cancel()
                val counts = items.values.groupingBy { it.type }.eachCount()
                snapshot.edit { it.copy(elrs = ElrsProbe(deviceName, items.size, counts)) }
            } finally {
                local.edit { it.copy(probing = it.probing - CheckAction.ELRS_PROBE) }
            }
        }
    }

    /** Clears the diagnostics history. */
    fun clearLog() = diagnostics.clear()

    /**
     * Builds the report, saves it into the logs folder (when there is one) and hands it to the
     * screen for sharing.
     */
    fun export(appVersion: String) {
        scope.launch {
            val ui = state.value
            val text = DiagnosticsReport.build(ui.data.copy(capturedMs = clock()), ui.checks, appVersion)
            val dir = ui.data.storage.logsDir
            var savedAs: String? = null
            if (dir.isNotEmpty()) {
                val name = "diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock())) + ".txt"
                savedAs = withContext(io) {
                    try {
                        val folder = File(dir)
                        folder.mkdirs()
                        File(folder, name).also { it.writeText(text) }.absolutePath
                    } catch (e: java.io.IOException) {
                        diagnostics.record(DiagnosticsLog.SOURCE_LIBRARY, "diagnostics export failed: ${e.message}")
                        null
                    }
                }
            }
            feedbackChannel.trySend(if (savedAs != null) Feedback.REPORT_SAVED else Feedback.REPORT_FAILED)
            reportChannel.trySend(ReportOutput(text, savedAs))
        }
    }

    private companion object {
        const val VARIO_TEST_SPEED = 2.0
        const val BEEP_GAP_MS = 350L
        const val ELRS_PROBE_STEPS = 8
        const val ELRS_PROBE_STEP_MS = 600L
    }
}
