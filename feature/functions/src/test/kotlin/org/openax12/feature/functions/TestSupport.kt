package org.openax12.feature.functions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.openax12.core.audio.TtsStatus
import org.openax12.core.data.ExternalChange
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.ModelSnapshot
import org.openax12.core.engine.FlightModeCompiler
import org.openax12.core.engine.ValueResolver
import org.openax12.core.engine.WingCompiler
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.MixPlan
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.SpecialFunction
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.VoiceAlert
import org.openax12.core.model.WingConfig
import org.openax12.core.model.WorkingConfig
import org.openax12.core.radio.Radio
import org.openax12.core.runtime.ActiveFlightMode
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.SaveState
import org.openax12.core.runtime.SessionError
import org.openax12.core.runtime.SessionResult
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.buildSensorOptions
import org.openax12.feature.functions.common.buildSourceOptions

/** Runs `viewModelScope` on a test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: kotlinx.coroutines.test.TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** A radio configuration with all sources enabled and 16 channels. */
internal fun testConfig(): WorkingConfig = WorkingConfig(
    sources = SourceId.all.map { SourceInfo(it.index) },
    channels = List(CHANNEL_COUNT) { OutputChannelConfig(it) },
)

/** Picker data over [testConfig]. */
internal fun testPickers(modes: List<String> = emptyList(), sounds: List<String> = listOf("a.wav")): PickerData {
    val config = testConfig()
    val sources = buildSourceOptions(config)
    return PickerData(sources, buildSensorOptions(emptyList(), config, sources), modes, sounds)
}

/** Records what the editors ask the runtime to do. */
internal class RecordingServices : PreviewServices {
    val functions = mutableListOf<SpecialFunction>()
    val alerts = mutableListOf<VoiceAlert>()
    val varioSpeeds = mutableListOf<Double>()
    override fun resolver(): ValueResolver? = null
    override fun preview(function: SpecialFunction) { functions += function }
    override fun preview(alert: VoiceAlert) { alerts += alert }
    override fun previewText(template: String) = Unit
    override fun previewVario(verticalSpeed: Double) { varioSpeeds += verticalSpeed }
}

/**
 * A [ConfigSession] over a `MutableStateFlow` of extras: it stages edits like the real one and
 * records the compile / apply calls. Everything the tests do not need throws.
 */
internal class FakeSession(
    val extras: MutableStateFlow<ModelExtras>,
    private val config: WorkingConfig = testConfig(),
) : ConfigSession {
    val compiledFlightModes = mutableListOf<FlightModesConfig?>()
    val compiledWing = mutableListOf<WingConfig?>()
    val appliedPlans = mutableListOf<MixPlan>()
    var applyResult: SessionResult<PlanDiff>? = null
    var updateCount = 0

    override val dirty: StateFlow<Boolean> = MutableStateFlow(false)
    override val deviceInSync: StateFlow<Boolean> = MutableStateFlow(true)
    override val saveState: StateFlow<SaveState> = MutableStateFlow(SaveState.Idle)
    override val workingConfig: StateFlow<WorkingConfig> = MutableStateFlow(config)

    override suspend fun <T> edit(refresh: Boolean, block: suspend (Radio) -> T): T = throw UnsupportedOperationException()

    override suspend fun updateExtras(transform: (ModelExtras) -> ModelExtras): ModelExtras {
        updateCount++
        extras.value = transform(extras.value)
        return extras.value
    }

    override suspend fun compileFlightModes(config: FlightModesConfig?): Pair<MixPlan, PlanDiff> {
        compiledFlightModes += config
        val used = config ?: extras.value.flightModes
        val plan = FlightModeCompiler.compile(used, this.config)
        return plan to PlanDiff(plan.generator).withConflicts(FlightModeCompiler.validate(used, this.config))
    }

    override suspend fun compileWing(config: WingConfig?): Pair<MixPlan, PlanDiff> {
        compiledWing += config
        val used = config ?: extras.value.wing
        val plan = WingCompiler.compile(used, this.config)
        return plan to PlanDiff(plan.generator).withConflicts(WingCompiler.validate(used, this.config))
    }

    override suspend fun applyPlan(
        plan: MixPlan,
        expected: PlanDiff?,
        stage: ((ModelExtras) -> ModelExtras)?,
    ): SessionResult<PlanDiff> {
        appliedPlans += plan
        applyResult?.let { return it }
        if (stage != null) extras.value = stage(extras.value)
        return SessionResult.Success(expected ?: PlanDiff(plan.generator))
    }

    override suspend fun save(): SessionResult<ModelSnapshot> = throw UnsupportedOperationException()
    override suspend fun discard(): SessionResult<Unit> = throw UnsupportedOperationException()
    override suspend fun previewPlan(plan: MixPlan): PlanDiff = throw UnsupportedOperationException()
    override suspend fun updateSystemConfig(rfOffConfirmed: Boolean, transform: (SystemConfig) -> SystemConfig): SessionResult<SystemConfig> = throw UnsupportedOperationException()
    override suspend fun resetToDefaults(): SessionResult<Unit> = throw UnsupportedOperationException()
    override suspend fun selectModel(modelId: String, discardChanges: Boolean): SessionResult<ModelEntry> = throw UnsupportedOperationException()
    override suspend fun createModel(name: String, blueprint: ModelBlueprint): SessionResult<ModelEntry> = throw UnsupportedOperationException()
    override suspend fun restoreSnapshot(snapshot: ModelSnapshot): SessionResult<ModelEntry> = throw UnsupportedOperationException()
    override suspend fun reloadCurrentModel(discardChanges: Boolean): SessionResult<ModelEntry> = throw UnsupportedOperationException()
    override suspend fun acceptExternalChange(change: ExternalChange): SessionResult<ModelEntry> = throw UnsupportedOperationException()
    override suspend fun reloadFromDevice(): SessionResult<Unit> = throw UnsupportedOperationException()
}

/** [FunctionsDeps] over [session] with inert runtime flows. */
internal fun testDeps(
    session: FakeSession,
    pickers: PickerData = testPickers(),
    services: PreviewServices = RecordingServices(),
): FunctionsDeps = FunctionsDeps(
    session = session,
    extras = session.extras,
    workingConfig = session.workingConfig,
    pickerData = MutableStateFlow(pickers),
    services = services,
    flightMode = MutableStateFlow(ActiveFlightMode()),
    logicalSwitches = MutableStateFlow(LogicalSwitchStates()),
    activeFunctions = MutableStateFlow(emptySet<Int>()),
    ttsStatus = MutableStateFlow<TtsStatus>(TtsStatus.Initializing),
)

/** A failed session result. */
internal fun failure(error: SessionError): SessionResult<PlanDiff> = SessionResult.Failure(error)
