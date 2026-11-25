package org.openax12.feature.functions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.openax12.core.audio.TtsStatus
import org.openax12.core.engine.ValueResolver
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.SpecialFunction
import org.openax12.core.model.VoiceAlert
import org.openax12.core.model.WorkingConfig
import org.openax12.core.runtime.ActiveFlightMode
import org.openax12.core.runtime.AppGraph
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.core.runtime.RuntimeEngine
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.buildSensorOptions
import org.openax12.feature.functions.common.buildSourceOptions

/**
 * What the editors can ask the runtime to do for a preview ("Test" buttons, live values). A
 * narrow view of `RuntimeEngine` so that ViewModels can be tested without one.
 */
internal interface PreviewServices {
    /** A resolver over the latest tick for live previews; null when none is available. */
    fun resolver(): ValueResolver?

    /** Performs [function] once now. */
    fun preview(function: SpecialFunction)

    /** Speaks [alert] now. */
    fun preview(alert: VoiceAlert)

    /** Renders [template] and speaks it. */
    fun previewText(template: String)

    /** Sounds the vario for [verticalSpeed] m/s for two seconds. */
    fun previewVario(verticalSpeed: Double)
}

/** [PreviewServices] backed by the real [RuntimeEngine]. */
internal class RuntimePreviewServices(private val runtime: RuntimeEngine) : PreviewServices {
    override fun resolver(): ValueResolver? = runtime.valueResolver()
    override fun preview(function: SpecialFunction) = runtime.preview(function)
    override fun preview(alert: VoiceAlert) = runtime.preview(alert)
    override fun previewText(template: String) = runtime.previewText(template)
    override fun previewVario(verticalSpeed: Double) = runtime.previewVario(verticalSpeed)
}

/**
 * Everything the ViewModels of this module read from the app, as flows and one session: built
 * from the [AppGraph] in production, from plain `MutableStateFlow`s in tests.
 *
 * @property session staged edits of the model extras and the compile / apply flows
 * @property extras working copy of the current model's extras (staged edits included)
 * @property workingConfig the firmware configuration
 * @property pickerData sources, sensors, flight-mode names and sound files for the pickers
 */
internal class FunctionsDeps(
    val session: ConfigSession,
    val extras: StateFlow<ModelExtras>,
    val workingConfig: StateFlow<WorkingConfig>,
    val pickerData: StateFlow<PickerData>,
    val services: PreviewServices,
    val flightMode: StateFlow<ActiveFlightMode>,
    val logicalSwitches: StateFlow<LogicalSwitchStates>,
    val activeFunctions: StateFlow<Set<Int>>,
    val ttsStatus: StateFlow<TtsStatus>,
) {
    companion object {
        /** Wires the module to the app graph. */
        fun from(graph: AppGraph): FunctionsDeps {
            val soundFiles: Flow<List<String>> = flow { emit(graph.audio.listSoundFiles().map { it.name }) }
            val picker = combine(
                graph.configSession.workingConfig,
                graph.telemetry.sensorList,
                graph.models.currentExtras.map { extras -> extras.flightModes.modes.map { it.name } }.distinctUntilChanged(),
                soundFiles,
            ) { config, sensors, modeNames, sounds ->
                val sources = buildSourceOptions(config)
                PickerData(
                    sources = sources,
                    sensors = buildSensorOptions(sensors, config, sources),
                    flightModes = modeNames,
                    soundFiles = sounds,
                )
            }.stateIn(graph.scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PickerData())
            return FunctionsDeps(
                session = graph.configSession,
                extras = graph.models.currentExtras,
                workingConfig = graph.configSession.workingConfig,
                pickerData = picker,
                services = RuntimePreviewServices(graph.runtime),
                flightMode = graph.runtime.flightMode,
                logicalSwitches = graph.runtime.logicalSwitches,
                activeFunctions = graph.runtime.activeSpecialFunctions,
                ttsStatus = graph.audio.ttsStatus,
            )
        }

        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The module's dependencies, created once per app graph. */
@Composable
internal fun rememberFunctionsDeps(): FunctionsDeps {
    val graph = LocalAppGraph.current
    return remember(graph) { FunctionsDeps.from(graph) }
}
