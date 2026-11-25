package org.openax12.core.runtime

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openax12.core.data.DataError
import org.openax12.core.data.DataResult
import org.openax12.core.data.ExternalChange
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.ModelSnapshot
import org.openax12.core.engine.FlightModeCompiler
import org.openax12.core.engine.PlanDiffer
import org.openax12.core.engine.WingCompiler
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.MixPlan
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.PlanOp
import org.openax12.core.model.RadioWidePatch
import org.openax12.core.model.RfModuleMode
import org.openax12.core.model.SourceId
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.SystemConfigValidity
import org.openax12.core.model.WingConfig
import org.openax12.core.model.WorkingConfig
import org.openax12.core.radio.ConnectionState
import org.openax12.core.radio.Radio
import org.openax12.core.radio.RadioEvent
import org.openax12.core.radio.TaskResult
import org.openax12.core.radio.getMixes
import org.openax12.core.radio.readWorkingConfig

/**
 * The production [ConfigSession].
 *
 * Every public function takes the session's single [Mutex] for its whole duration, so edits,
 * saves, plan applications and model flows never interleave; the private `…Locked` functions
 * assume the mutex is held and are what the composite flows ([createModel], [acceptExternalChange])
 * call. The mutex is not re-entrant: a `…Locked` function must never call a public one.
 *
 * Failures are recorded in [diagnostics] with the source [DiagnosticsLog.SOURCE_SESSION]; the
 * graph does not record them a second time. "Refusals" (radio not ready, unsaved changes) are
 * recorded but leave [saveState] alone; real failures of a device-writing operation end in
 * [SaveState.Failed].
 *
 * @param radio the radio; the only thing this class calls device functions on
 * @param models the model library; the session orchestrates its load / hook functions
 * @param diagnostics where failure details are recorded
 * @param scope application scope for the configuration-revision and connection watchers
 * @param dispatchers [AppDispatchers.default] is used for the watchers
 * @param clock wall-clock time for [SaveState.Saved]
 * @param refreshDelayMs debounce of the `configRevision` watcher that re-reads [workingConfig]
 * @param resetTimeout how long [resetToDefaults] waits for the radio's re-read
 */
class DefaultConfigSession(
    private val radio: Radio,
    private val models: ModelRepository,
    private val diagnostics: DiagnosticsLog,
    scope: CoroutineScope,
    dispatchers: AppDispatchers = AppDispatchers(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshDelayMs: Long = REFRESH_DEBOUNCE_MS,
    private val resetTimeout: Duration = RESET_TIMEOUT,
) : ConfigSession {

    private val mutex = Mutex()
    private val _dirty = MutableStateFlow(false)
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    private val _workingConfig = MutableStateFlow(WorkingConfig.EMPTY)

    override val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    override val deviceInSync: StateFlow<Boolean> get() = models.deviceInSync
    override val saveState: StateFlow<SaveState> = _saveState.asStateFlow()
    override val workingConfig: StateFlow<WorkingConfig> = _workingConfig.asStateFlow()

    // Guards against an older read overwriting a newer one: a read may only publish if it started
    // after the last one that published.
    private val publishLock = Any()
    private var ticketCounter = 0L
    private var publishedTicket = 0L

    private var everReady = false
    private var wasReady = false

    init {
        scope.launch(dispatchers.default, start = CoroutineStart.UNDISPATCHED) {
            radio.connection.collect { onConnection(it) }
        }
        scope.launch(dispatchers.default, start = CoroutineStart.UNDISPATCHED) {
            radio.configRevision.collectLatest {
                delay(refreshDelayMs)
                refreshWorkingConfig()
            }
        }
    }

    // -- Editing ----------------------------------------------------------------------------------

    override suspend fun <T> edit(refresh: Boolean, block: suspend (Radio) -> T): T = mutex.withLock {
        val before = radio.configRevision.value
        try {
            block(radio)
        } finally {
            if (radio.configRevision.value != before) {
                _dirty.value = true
                if (refresh) withContext(NonCancellable) { refreshWorkingConfig() }
            }
        }
    }

    override suspend fun updateExtras(transform: (ModelExtras) -> ModelExtras): ModelExtras = mutex.withLock {
        val before = models.currentExtras.value
        val after = models.stageExtras(transform)
        if (after != before) _dirty.value = true
        after
    }

    // -- Save / discard ---------------------------------------------------------------------------

    override suspend fun save(): SessionResult<ModelSnapshot> = exclusive {
        notReady("save")?.let { return@exclusive it }
        _saveState.value = SaveState.Saving
        val task = radio.saveConfigToDevice()
        if (!task.isSuccess) return@exclusive fail(task.toError(), "save: the device write ended with $task")
        when (val saved = models.onDeviceConfigSaved()) {
            is DataResult.Failure -> failData("save: the library could not persist the model", saved)
            is DataResult.Success -> {
                _dirty.value = false
                _saveState.value = SaveState.Saved(clock())
                SessionResult.Success(saved.value)
            }
        }
    }

    override suspend fun discard(): SessionResult<Unit> = exclusive {
        notReady("discard")?.let { return@exclusive it }
        discardLocked("discard")
    }

    private suspend fun discardLocked(operation: String): SessionResult<Unit> {
        if (models.deviceInSync.value) {
            val task = radio.readConfigFromDevice()
            if (!task.isSuccess) return fail(task.toError(), "$operation: the device read ended with $task")
            if (models.hasStagedExtras.value) models.revertStagedExtras()
        } else {
            // A device read would overwrite the loaded model's file with the old device
            // configuration: reload the file instead.
            val reloaded = models.reloadCurrentModel()
            if (reloaded is DataResult.Failure) return failData("$operation: reloading the model file failed", reloaded)
        }
        _dirty.value = false
        _saveState.value = SaveState.Idle
        refreshWorkingConfig()
        return SessionResult.Success(Unit)
    }

    // -- Mix plans ----------------------------------------------------------------------------------

    override suspend fun previewPlan(plan: MixPlan): PlanDiff = mutex.withLock {
        PlanDiffer.diff(plan, freshConfig(), models.currentExtras.value.managed)
    }

    override suspend fun applyPlan(
        plan: MixPlan,
        expected: PlanDiff?,
        stage: ((ModelExtras) -> ModelExtras)?,
    ): SessionResult<PlanDiff> = exclusive {
        notReady("applyPlan")?.let { return@exclusive it }
        applyPlanLocked(plan, expected, stage)
    }

    override suspend fun compileFlightModes(config: FlightModesConfig?): Pair<MixPlan, PlanDiff> = mutex.withLock {
        compileFlightModesLocked(config)
    }

    override suspend fun compileWing(config: WingConfig?): Pair<MixPlan, PlanDiff> = mutex.withLock {
        compileWingLocked(config)
    }

    private suspend fun compileFlightModesLocked(config: FlightModesConfig?): Pair<MixPlan, PlanDiff> {
        val extras = models.currentExtras.value
        val draft = config ?: extras.flightModes
        val current = freshConfig()
        val plan = FlightModeCompiler.compile(draft, current)
        val diff = PlanDiffer.diff(plan, current, extras.managed)
            .withConflicts(FlightModeCompiler.validate(draft, current))
        return plan to diff
    }

    private suspend fun compileWingLocked(config: WingConfig?): Pair<MixPlan, PlanDiff> {
        val extras = models.currentExtras.value
        val draft = config ?: extras.wing
        val current = freshConfig()
        val plan = WingCompiler.compile(draft, current)
        val diff = PlanDiffer.diff(plan, current, extras.managed).withConflicts(WingCompiler.validate(draft, current))
        return plan to diff
    }

    private suspend fun applyPlanLocked(
        plan: MixPlan,
        expected: PlanDiff?,
        stage: ((ModelExtras) -> ModelExtras)?,
    ): SessionResult<PlanDiff> {
        val current = freshConfig()
        val extras = models.currentExtras.value
        val diff = PlanDiffer.diff(plan, current, extras.managed)
        if (expected != null) {
            if (expected.hasBlockingConflicts) {
                return refuse(SessionError.PLAN_REJECTED, "applyPlan: the confirmed diff has blocking conflicts")
            }
            if (expected.baseRevision != current.revision) {
                return refuse(
                    SessionError.PLAN_REJECTED,
                    "applyPlan: the configuration changed since the diff was made " +
                        "(revision ${expected.baseRevision} -> ${current.revision})",
                )
            }
        }
        if (diff.hasBlockingConflicts) {
            val first = diff.conflicts.first { it.isBlocking }
            return refuse(SessionError.PLAN_REJECTED, "applyPlan: blocking conflict ${first.kind}: ${first.detail}")
        }

        var executed = 0
        var failure: String? = null
        for (op in diff.ops) {
            val problem = execute(op)
            if (problem != null) {
                failure = problem
                break
            }
            executed++
        }

        val after = refreshWorkingConfig() ?: freshConfig()
        val managed = diff.managedAfter.withFingerprintsFrom(after)
        if (failure != null) {
            // Keep what was already applied known as generated, but do not stage the settings.
            if (executed > 0) {
                models.stageExtras { it.copy(managed = managed) }
                _dirty.value = true
            }
            return fail(SessionError.PLAN_OPERATION_FAILED, "applyPlan: $failure")
        }
        models.stageExtras { e -> (stage?.invoke(e) ?: e).copy(managed = managed) }
        if (diff.ops.isNotEmpty() || stage != null) _dirty.value = true
        return SessionResult.Success(diff)
    }

    /** Executes one plan operation; returns a problem description, or null on success. */
    private suspend fun execute(op: PlanOp): String? = when (op) {
        is PlanOp.RemoveMix ->
            if (radio.deleteMix(op.existing.channel, op.existing.source) != null) null
            else "deleting the mix CH${op.existing.channel + 1} <- ${op.existing.source.defaultName} was rejected"

        is PlanOp.AddMix -> {
            val added = radio.addMix(op.mix.channel, op.mix.source)
            when {
                added == null ->
                    "adding the mix CH${op.mix.channel + 1} <- ${op.mix.source.defaultName} was rejected (channel full?)"
                !radio.setMix(op.mix.copy(mixIndex = added.mixIndex)) ->
                    "writing the new mix CH${op.mix.channel + 1} <- ${op.mix.source.defaultName} was rejected"
                else -> null
            }
        }

        is PlanOp.UpdateMix -> {
            // Earlier removals of the same plan shift the mix indices: look the line up by its key.
            val index = currentMixIndex(op.after.channel, op.after.source)
            when {
                index == null ->
                    "the mix CH${op.after.channel + 1} <- ${op.after.source.defaultName} to update does not exist"
                !radio.setMix(op.after.copy(mixIndex = index)) ->
                    "updating the mix CH${op.after.channel + 1} <- ${op.after.source.defaultName} was rejected"
                else -> null
            }
        }

        is PlanOp.SetCurve ->
            if (radio.setCurve(op.after)) null else "writing curve ${op.after.index + 1} was rejected"

        is PlanOp.SetDualRate ->
            if (radio.setDualRate(op.after) != null) null
            else "writing dual rate ${op.after.slot + 1} of CH${op.after.channel + 1} was rejected"

        is PlanOp.SetChannel ->
            if (radio.setOutputChannel(op.after)) null else "writing channel CH${op.after.channel + 1} was rejected"

        is PlanOp.SetTimer ->
            if (radio.setTimerConfig(op.after, op.isCounter)) null
            else "writing ${if (op.isCounter) "counter" else "timer"} ${op.after.index + 1} was rejected"
    }

    private suspend fun currentMixIndex(channel: Int, source: SourceId): Int? =
        radio.getMixes(channel).firstOrNull { it.source == source }?.mixIndex

    // -- System configuration ---------------------------------------------------------------------

    override suspend fun updateSystemConfig(
        rfOffConfirmed: Boolean,
        transform: (SystemConfig) -> SystemConfig,
    ): SessionResult<SystemConfig> = exclusive {
        notReady("updateSystemConfig")?.let { return@exclusive it }
        if (_dirty.value) {
            return@exclusive refuse(SessionError.UNSAVED_CHANGES, "updateSystemConfig: the model has unsaved changes")
        }
        if (!models.deviceInSync.value) {
            return@exclusive refuse(
                SessionError.DEVICE_WRITE_PENDING,
                "updateSystemConfig: the loaded model is not confirmed on the device",
            )
        }
        // Everything is checked before the first byte goes out: a read-modify-write from values
        // that are not the radio's would write them to the radio.
        val before = radio.getSystemConfig()
            ?: return@exclusive refuse(
                SessionError.SYSTEM_CONFIG_UNAVAILABLE,
                "updateSystemConfig: the system configuration cannot be read in a trustworthy way",
            )
        SystemConfigValidity.problem(before)?.let {
            return@exclusive refuse(SessionError.SYSTEM_CONFIG_UNAVAILABLE, "updateSystemConfig: the current values are not plausible ($it)")
        }
        val wanted = transform(before)
        SystemConfigValidity.problem(wanted)?.let {
            return@exclusive refuse(SessionError.SYSTEM_CONFIG_NOT_APPLIED, "updateSystemConfig: the new values are not plausible ($it)")
        }
        if (wanted.rfModuleMode == RfModuleMode.OFF && before.rfModuleMode != RfModuleMode.OFF && !rfOffConfirmed) {
            return@exclusive refuse(SessionError.RF_OFF_NOT_CONFIRMED, "updateSystemConfig: switching the RF module off needs a confirmation")
        }
        _saveState.value = SaveState.Saving
        val revision = radio.configRevision.value
        if (!radio.setSystemConfig(wanted)) {
            // A write that failed half-way (the SDK backend repairs a side effect of its setter and
            // may not manage to) must not stay in the working config: it is read back from the
            // device, which still holds the state the session checked above.
            if (radio.configRevision.value != revision) discardLocked("updateSystemConfig")
            return@exclusive fail(SessionError.SYSTEM_CONFIG_NOT_APPLIED, "updateSystemConfig: the SDK rejected the configuration")
        }
        val task = radio.saveConfigToDevice()
        if (!task.isSuccess) {
            // The change stays in the working config: Save retries, Discard reverts it.
            _dirty.value = true
            refreshWorkingConfig()
            return@exclusive fail(task.toError(), "updateSystemConfig: the device write ended with $task")
        }
        val after = radio.getSystemConfig()
        if (after == null || !after.hasRadioWideValuesOf(wanted)) {
            // Never claim success for a change the radio does not show.
            refreshWorkingConfig()
            return@exclusive fail(
                SessionError.SYSTEM_CONFIG_NOT_APPLIED,
                "updateSystemConfig: after the write the radio reports ${after?.let { "stick ${it.stickMode}, RF ${it.rfModuleMode}, alarm ${it.alarmVoltageDeciVolt}" } ?: "nothing"}",
            )
        }
        when (val written = models.onDeviceConfigWritten()) {
            is DataResult.Failure -> failData("updateSystemConfig: the library could not refresh the model export", written)
            is DataResult.Success -> {
                propagateRadioWide(before, wanted)
                refreshWorkingConfig()
                _saveState.value = SaveState.Saved(clock())
                SessionResult.Success(after)
            }
        }
    }

    /** Writes the radio-wide fields that changed from [before] to [wanted] into the other models of the library. */
    private suspend fun propagateRadioWide(before: SystemConfig, wanted: SystemConfig) {
        val patch = RadioWidePatch(
            stickMode = wanted.stickMode.sdk.takeIf { wanted.stickMode != before.stickMode },
            rfModuleMode = wanted.rfModuleMode.sdk.takeIf { wanted.rfModuleMode != before.rfModuleMode },
            alarmVoltageDeciVolt = wanted.alarmVoltageDeciVolt.takeIf { wanted.alarmVoltageDeciVolt != before.alarmVoltageDeciVolt },
        )
        if (patch.isEmpty) return
        when (val result = models.applyRadioWideSettings(patch)) {
            is DataResult.Failure -> diagnostics.record(
                DiagnosticsLog.SOURCE_SESSION,
                "radio-wide settings could not be applied to the other models: ${result.error} ${result.detail}",
            )
            is DataResult.Success -> if (result.value.failed.isNotEmpty()) {
                diagnostics.record(
                    DiagnosticsLog.SOURCE_SESSION,
                    "radio-wide settings missed some models (applied when they are loaded): ${result.value.failed.joinToString("; ")}",
                )
            }
        }
    }

    /** True if stick mode, RF module and alarm voltage equal those of [other]. */
    private fun SystemConfig.hasRadioWideValuesOf(other: SystemConfig): Boolean =
        stickMode == other.stickMode && rfModuleMode == other.rfModuleMode && alarmVoltageDeciVolt == other.alarmVoltageDeciVolt

    override suspend fun resetToDefaults(): SessionResult<Unit> = exclusive {
        notReady("resetToDefaults")?.let { return@exclusive it }
        if (!models.deviceInSync.value) {
            return@exclusive refuse(
                SessionError.DEVICE_WRITE_PENDING,
                "resetToDefaults: the loaded model is not confirmed on the device",
            )
        }
        _saveState.value = SaveState.Saving
        val reread = coroutineScope {
            // Subscribe before the call: events have no replay.
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(resetTimeout) { radio.events.first { it is RadioEvent.ConfigRead } }
            }
            if (!radio.resetToDefaults()) {
                waiting.cancel()
                return@coroutineScope false to "the SDK rejected the reset"
            }
            if (waiting.await() == null) false to "the radio did not finish re-reading its configuration" else true to ""
        }
        if (!reread.first) {
            refreshWorkingConfig()
            return@exclusive fail(SessionError.DEVICE_TIMEOUT, "resetToDefaults: ${reread.second}")
        }
        // The SDK overwrote the current model's file with the defaults: make the extras match.
        if (models.hasStagedExtras.value) models.revertStagedExtras()
        models.updateExtras {
            it.copy(
                managed = ManagedItems.NONE,
                flightModes = it.flightModes.copy(enabled = false),
                wing = it.wing.copy(enabled = false),
            )
        }
        _dirty.value = false
        refreshWorkingConfig()
        // Working config, device and SDK file are now identical; this refreshes the library export.
        when (val written = models.onDeviceConfigWritten()) {
            is DataResult.Failure -> failData("resetToDefaults: the library could not refresh the model export", written)
            is DataResult.Success -> {
                _saveState.value = SaveState.Idle
                SessionResult.Success(Unit)
            }
        }
    }

    override suspend fun reloadFromDevice(): SessionResult<Unit> = exclusive {
        notReady("reloadFromDevice")?.let { return@exclusive it }
        if (!models.deviceInSync.value) {
            return@exclusive refuse(
                SessionError.DEVICE_WRITE_PENDING,
                "reloadFromDevice: the loaded model is not confirmed on the device (a device read would overwrite its file)",
            )
        }
        _saveState.value = SaveState.Saving
        discardLocked("reloadFromDevice")
    }

    // -- Model flows ------------------------------------------------------------------------------

    override suspend fun selectModel(modelId: String, discardChanges: Boolean): SessionResult<ModelEntry> =
        exclusive { selectLocked(modelId, discardChanges) }

    private suspend fun selectLocked(modelId: String, discardChanges: Boolean): SessionResult<ModelEntry> {
        notReady("selectModel")?.let { return it }
        if (_dirty.value && !discardChanges) {
            return refuse(SessionError.UNSAVED_CHANGES, "selectModel: the model has unsaved changes")
        }
        if (modelId == models.currentModel.value?.id) return reloadLocked(discardChanges)
        _saveState.value = SaveState.Saving
        val loaded = models.selectModel(modelId)
        if (loaded is DataResult.Failure) return failData("selectModel: loading the model failed", loaded)
        _dirty.value = false
        return writeLoadedModel("selectModel")
    }

    override suspend fun createModel(name: String, blueprint: ModelBlueprint): SessionResult<ModelEntry> = exclusive {
        notReady("createModel")?.let { return@exclusive it }
        if (_dirty.value) return@exclusive refuse(SessionError.UNSAVED_CHANGES, "createModel: the model has unsaved changes")
        if (!models.deviceInSync.value) {
            return@exclusive refuse(
                SessionError.DEVICE_WRITE_PENDING,
                "createModel: the loaded model is not confirmed on the device",
            )
        }
        val previousId = models.currentModel.value?.id
        _saveState.value = SaveState.Saving
        val created = models.createModel(name, blueprint)
        if (created is DataResult.Failure) return@exclusive failData("createModel: creating the model failed", created)
        val entry = (created as DataResult.Success).value

        val failure = populateNewModel(entry, blueprint)
        if (failure != null) {
            rollbackCreate(entry.id, previousId)
            // The rollback's own select flow set the save state: show the original failure.
            _saveState.value = SaveState.Failed(failure.error, failure.detail)
            return@exclusive failure
        }
        SessionResult.Success(models.models.value.firstOrNull { it.id == entry.id } ?: entry.copy(isCurrent = true))
    }

    /** Steps 2 to 4 of [createModel]; returns the failure of the first step that failed, or null. */
    private suspend fun populateNewModel(entry: ModelEntry, blueprint: ModelBlueprint): SessionResult.Failure? {
        val selected = models.selectModel(entry.id)
        if (selected is DataResult.Failure) return failData("createModel: loading the new model failed", selected)
        _dirty.value = false
        refreshWorkingConfig()

        (applyPlanLocked(blueprint.effectivePlan, null, null) as? SessionResult.Failure)?.let { return it }

        val wing = blueprint.extras.wing
        if (wing.enabled) {
            val (plan, diff) = compileWingLocked(wing)
            (applyPlanLocked(plan, diff, { it.copy(wing = wing) }) as? SessionResult.Failure)?.let { return it }
        }
        val flightModes = blueprint.extras.flightModes
        if (flightModes.enabled) {
            val (plan, diff) = compileFlightModesLocked(flightModes)
            (applyPlanLocked(plan, diff, { it.copy(flightModes = flightModes) }) as? SessionResult.Failure)?.let { return it }
        }
        return saveLocked("createModel")
    }

    /** Selects the previous model again and deletes the half-built new one. */
    private suspend fun rollbackCreate(newModelId: String, previousId: String?) {
        if (previousId != null && models.currentModel.value?.id != previousId) {
            // Normal select flow: file load + device write. If the write fails the previous model
            // stays current with deviceInSync false, as documented.
            selectLocked(previousId, discardChanges = true)
        }
        val deleted = models.deleteModel(newModelId)
        if (deleted is DataResult.Failure) {
            diagnostics.record(
                DiagnosticsLog.SOURCE_SESSION,
                "createModel rollback: deleting the new model failed: ${deleted.error} ${deleted.detail}",
            )
        }
    }

    /** [save] without the mutex, returning only the failure. */
    private suspend fun saveLocked(operation: String): SessionResult.Failure? {
        val task = radio.saveConfigToDevice()
        if (!task.isSuccess) return fail(task.toError(), "$operation: the device write ended with $task")
        return when (val saved = models.onDeviceConfigSaved()) {
            is DataResult.Failure -> failData("$operation: the library could not persist the model", saved)
            is DataResult.Success -> {
                _dirty.value = false
                _saveState.value = SaveState.Saved(clock())
                null
            }
        }
    }

    override suspend fun restoreSnapshot(snapshot: ModelSnapshot): SessionResult<ModelEntry> = exclusive {
        notReady("restoreSnapshot")?.let { return@exclusive it }
        val concernsCurrent = snapshot.modelId == models.currentModel.value?.id
        _saveState.value = SaveState.Saving
        val restored = models.restoreSnapshot(snapshot)
        if (restored is DataResult.Failure) return@exclusive failData("restoreSnapshot: restoring failed", restored)
        if (!concernsCurrent) {
            _saveState.value = SaveState.Saved(clock())
            return@exclusive SessionResult.Success((restored as DataResult.Success).value)
        }
        _dirty.value = false
        writeLoadedModel("restoreSnapshot")
    }

    override suspend fun reloadCurrentModel(discardChanges: Boolean): SessionResult<ModelEntry> =
        exclusive { reloadLocked(discardChanges) }

    private suspend fun reloadLocked(discardChanges: Boolean): SessionResult<ModelEntry> {
        notReady("reloadCurrentModel")?.let { return it }
        if (_dirty.value && !discardChanges) {
            return refuse(SessionError.UNSAVED_CHANGES, "reloadCurrentModel: the model has unsaved changes")
        }
        _saveState.value = SaveState.Saving
        val reloaded = models.reloadCurrentModel()
        if (reloaded is DataResult.Failure) return failData("reloadCurrentModel: reloading failed", reloaded)
        _dirty.value = false
        return writeLoadedModel("reloadCurrentModel")
    }

    override suspend fun acceptExternalChange(change: ExternalChange): SessionResult<ModelEntry> = exclusive {
        notReady("acceptExternalChange")?.let { return@exclusive it }
        val concernsCurrent = change.modelId != null && change.modelId == models.currentModel.value?.id
        _saveState.value = SaveState.Saving
        val accepted = models.acceptExternalChange(change)
        if (accepted is DataResult.Failure) return@exclusive failData("acceptExternalChange: re-import failed", accepted)
        if (!concernsCurrent) {
            _saveState.value = SaveState.Saved(clock())
            return@exclusive SessionResult.Success((accepted as DataResult.Success).value)
        }
        _dirty.value = false
        writeLoadedModel("acceptExternalChange")
    }

    /**
     * Writes the model that was just loaded into the working config to the device and tells the
     * library. A write that does not complete leaves the model loaded and current with
     * `deviceInSync` false ([SessionError.DEVICE_WRITE_PENDING]).
     */
    private suspend fun writeLoadedModel(operation: String): SessionResult<ModelEntry> {
        val task = radio.saveConfigToDevice()
        if (!task.isSuccess) {
            refreshWorkingConfig()
            return fail(
                SessionError.DEVICE_WRITE_PENDING,
                "$operation: the model is loaded but the device write ended with $task",
            )
        }
        val written = models.onDeviceConfigWritten()
        refreshWorkingConfig()
        return when (written) {
            is DataResult.Failure -> failData("$operation: the library could not refresh the model export", written)
            is DataResult.Success -> {
                _saveState.value = SaveState.Saved(clock())
                SessionResult.Success(written.value)
            }
        }
    }

    // -- Connection handling ----------------------------------------------------------------------

    private suspend fun onConnection(state: ConnectionState) {
        if (!state.isReady) {
            wasReady = false
            return
        }
        if (wasReady) return
        wasReady = true
        mutex.withLock {
            val reconnect = everReady
            everReady = true
            // The radio re-read the device configuration: whatever was unsaved is gone.
            val dropped = reconnect && (_dirty.value || models.hasStagedExtras.value)
            if (models.hasStagedExtras.value) models.revertStagedExtras()
            _dirty.value = false
            if (dropped) {
                _saveState.value = SaveState.Failed(
                    SessionError.RADIO_NOT_READY,
                    "the connection to the radio was re-established and unsaved changes were dropped",
                )
                diagnostics.record(
                    DiagnosticsLog.SOURCE_SESSION,
                    "unsaved changes were dropped because the radio connection was re-established",
                )
            }
            refreshWorkingConfig()
            if (reconnect && !models.deviceInSync.value) {
                val report = models.reconcileLibrary()
                if (report is DataResult.Failure) {
                    diagnostics.record(
                        DiagnosticsLog.SOURCE_SESSION,
                        "reconcile after reconnect failed: ${report.error} ${report.detail}",
                    )
                }
            }
        }
    }

    // -- Helpers --------------------------------------------------------------------------------

    /** Runs [block] holding the session mutex; keeps [saveState] from sticking in `Saving` on cancellation. */
    private suspend fun <T> exclusive(block: suspend () -> SessionResult<T>): SessionResult<T> = mutex.withLock {
        try {
            block()
        } catch (e: CancellationException) {
            if (_saveState.value.isSaving) _saveState.value = SaveState.Idle
            throw e
        }
    }

    private fun notReady(operation: String): SessionResult.Failure? =
        if (radio.connection.value.isReady) null
        else refuse(SessionError.RADIO_NOT_READY, "$operation: the radio is not ready")

    /** Reads the configuration from the radio now; the last published one while the radio is unusable. */
    private suspend fun freshConfig(): WorkingConfig {
        if (!radio.connection.value.isReady) return _workingConfig.value
        return radio.readWorkingConfig().takeUnless { it.isEmpty } ?: _workingConfig.value
    }

    /** Re-reads [workingConfig]; returns what was read, or null while the radio is unusable. */
    private suspend fun refreshWorkingConfig(): WorkingConfig? {
        if (!radio.connection.value.isReady) return null
        val ticket = synchronized(publishLock) { ++ticketCounter }
        val config = radio.readWorkingConfig()
        if (config.isEmpty) return null
        synchronized(publishLock) {
            if (ticket > publishedTicket) {
                publishedTicket = ticket
                _workingConfig.value = config
            }
        }
        return config
    }

    private fun TaskResult.toError(): SessionError = when (this) {
        TaskResult.COMPLETED -> SessionError.DATA
        TaskResult.TIMED_OUT -> SessionError.DEVICE_TIMEOUT
        TaskResult.NOT_READY -> SessionError.RADIO_NOT_READY
        TaskResult.BUSY -> SessionError.BUSY
    }

    /** A refusal: recorded, but the save state is left alone. */
    private fun refuse(error: SessionError, detail: String): SessionResult.Failure {
        diagnostics.record(DiagnosticsLog.SOURCE_SESSION, "$error: $detail")
        return SessionResult.Failure(error, detail)
    }

    /** A failed device-writing operation: recorded and shown as [SaveState.Failed]. */
    private fun fail(error: SessionError, detail: String): SessionResult.Failure {
        _saveState.value = SaveState.Failed(error, detail)
        return refuse(error, detail)
    }

    private fun failData(context: String, failure: DataResult.Failure): SessionResult.Failure {
        val error = if (failure.error == DataError.NOT_FOUND) SessionError.MODEL_NOT_FOUND else SessionError.DATA
        val detail = "$context (${failure.error}${if (failure.detail.isEmpty()) "" else ": " + failure.detail})"
        _saveState.value = SaveState.Failed(error, detail)
        diagnostics.record(DiagnosticsLog.SOURCE_SESSION, "$error: $detail")
        return SessionResult.Failure(error, detail, failure.error)
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 120L
        val RESET_TIMEOUT: Duration = 20.seconds
    }
}
