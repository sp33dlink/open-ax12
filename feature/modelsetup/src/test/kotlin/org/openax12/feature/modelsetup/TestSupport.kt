package org.openax12.feature.modelsetup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.openax12.core.data.ModelSnapshot
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.MixPlan
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.WingConfig
import org.openax12.core.model.WorkingConfig
import org.openax12.core.data.ExternalChange
import org.openax12.core.data.ModelEntry
import org.openax12.core.radio.Radio
import org.openax12.core.radio.fake.FakeRadio
import org.openax12.core.radio.readWorkingConfig
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.SaveState
import org.openax12.core.runtime.SessionError
import org.openax12.core.runtime.SessionResult

/**
 * A [ConfigSession] over a real simulator radio: `edit` runs the block against it, marks the
 * session dirty when the configuration revision advanced and re-reads the working config like
 * the production session; extras are plain state. Everything else is not used by the setup tabs.
 */
internal class TestSession(private val radio: Radio) : ConfigSession {
    private val lock = Mutex()
    private val _dirty = MutableStateFlow(false)
    private val _config = MutableStateFlow(WorkingConfig.EMPTY)
    val extras = MutableStateFlow(ModelExtras())
    var editCount = 0
        private set
    var refreshesRequested = mutableListOf<Boolean>()
        private set

    override val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    override val deviceInSync: StateFlow<Boolean> = MutableStateFlow(true)
    override val saveState: StateFlow<SaveState> = MutableStateFlow<SaveState>(SaveState.Idle)
    override val workingConfig: StateFlow<WorkingConfig> = _config.asStateFlow()

    suspend fun refresh() {
        _config.value = radio.readWorkingConfig()
    }

    override suspend fun <T> edit(refresh: Boolean, block: suspend (Radio) -> T): T {
        // Serialised like the production session: two edits never interleave their read-modify-write.
        return lock.withLock {
            val revision = radio.configRevision.value
            val result = block(radio)
            editCount++
            refreshesRequested += refresh
            if (radio.configRevision.value != revision) _dirty.value = true
            if (refresh) refresh()
            result
        }
    }

    override suspend fun updateExtras(transform: (ModelExtras) -> ModelExtras): ModelExtras {
        extras.value = transform(extras.value)
        _dirty.value = true
        return extras.value
    }

    private fun unused(): Nothing = error("not used by the setup tabs")

    override suspend fun save(): SessionResult<ModelSnapshot> = unused()
    override suspend fun discard(): SessionResult<Unit> = unused()
    override suspend fun previewPlan(plan: MixPlan): PlanDiff = unused()
    override suspend fun applyPlan(plan: MixPlan, expected: PlanDiff?, stage: ((ModelExtras) -> ModelExtras)?): SessionResult<PlanDiff> = unused()
    override suspend fun compileFlightModes(config: FlightModesConfig?): Pair<MixPlan, PlanDiff> = unused()
    override suspend fun compileWing(config: WingConfig?): Pair<MixPlan, PlanDiff> = unused()
    override suspend fun updateSystemConfig(rfOffConfirmed: Boolean, transform: (SystemConfig) -> SystemConfig): SessionResult<SystemConfig> = unused()
    override suspend fun resetToDefaults(): SessionResult<Unit> = unused()
    override suspend fun selectModel(modelId: String, discardChanges: Boolean): SessionResult<ModelEntry> = unused()
    override suspend fun createModel(name: String, blueprint: ModelBlueprint): SessionResult<ModelEntry> = unused()
    override suspend fun restoreSnapshot(snapshot: ModelSnapshot): SessionResult<ModelEntry> = unused()
    override suspend fun reloadCurrentModel(discardChanges: Boolean): SessionResult<ModelEntry> = unused()
    override suspend fun acceptExternalChange(change: ExternalChange): SessionResult<ModelEntry> = unused()
    override suspend fun reloadFromDevice(): SessionResult<Unit> = unused()
}

/** A started deterministic simulator and a session over it; all work runs on the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.startedSession(): Pair<FakeRadio, TestSession> {
    val radio = FakeRadio(
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = { testScheduler.currentTime },
        options = FakeRadio.Options(demoMotion = false, realisticDelays = false, autoTick = false),
    )
    val state = radio.start()
    val session = TestSession(radio)
    session.refresh()
    check(!session.workingConfig.value.isEmpty) { "simulator not usable: $state" }
    return radio to session
}

internal val TestScope.viewModelScope: CoroutineScope get() = backgroundScope

/** Keeps a `WhileSubscribed` state flow alive and collects a view model's messages. */
internal fun TestScope.watch(
    state: kotlinx.coroutines.flow.Flow<*>,
    messages: kotlinx.coroutines.flow.Flow<org.openax12.feature.modelsetup.common.SetupMessage>? = null,
): List<org.openax12.feature.modelsetup.common.SetupMessage> {
    val collected = mutableListOf<org.openax12.feature.modelsetup.common.SetupMessage>()
    backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
    if (messages != null) {
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { messages.collect { collected += it } }
    }
    return collected
}

/**
 * Runs everything that is ready at the current virtual time, several rounds (view models launch
 * on the test scheduler and the simulator hands work to its own dispatcher). `advanceUntilIdle`
 * is not used: it leaves tasks of `backgroundScope` waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() {
    repeat(5) { testScheduler.runCurrent() }
}
