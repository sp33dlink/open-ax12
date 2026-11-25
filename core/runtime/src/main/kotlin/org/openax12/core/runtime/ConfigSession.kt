package org.openax12.core.runtime

import kotlinx.coroutines.flow.StateFlow
import org.openax12.core.data.DataError
import org.openax12.core.data.ExternalChange
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.ModelSnapshot
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.MixPlan
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.WingConfig
import org.openax12.core.model.WorkingConfig
import org.openax12.core.radio.Radio

/** Progress of [ConfigSession.save] and the other device-writing operations. */
sealed interface SaveState {

    /** Nothing in progress. */
    data object Idle : SaveState

    /** A write to the device is in progress; the Save button shows a spinner and is disabled. */
    data object Saving : SaveState

    /** The last operation succeeded at [atMs] (wall clock). */
    data class Saved(val atMs: Long) : SaveState

    /** The last operation failed. Unsaved changes are still there (unless [error] says otherwise). */
    data class Failed(val error: SessionError, val detail: String = "") : SaveState

    /** True while a device write is in progress. */
    val isSaving: Boolean get() = this is Saving
}

/** Why a [ConfigSession] operation failed. */
enum class SessionError {
    /** The radio is not ready (not connected, or the connection broke). Nothing was changed. */
    RADIO_NOT_READY,

    /** The device did not confirm the write / read in time. */
    DEVICE_TIMEOUT,

    /** Another session operation is still running. */
    BUSY,

    /** A plan has blocking conflicts or the configuration changed since the diff was made. */
    PLAN_REJECTED,

    /** An SDK call rejected one of the plan's operations (e.g. the channel's mix list is full). */
    PLAN_OPERATION_FAILED,

    /** The model repository failed (see detail and [SessionResult.Failure.dataError]). */
    DATA,

    /** The model does not exist. */
    MODEL_NOT_FOUND,

    /** There are unsaved changes; save or discard first. */
    UNSAVED_CHANGES,

    /**
     * A model was loaded into the working config but the device did not confirm the write that
     * followed, or an operation was refused because such a write is still outstanding
     * ([ConfigSession.deviceInSync] is false). The loaded model stays current; [ConfigSession.save]
     * retries the write.
     */
    DEVICE_WRITE_PENDING,
}

/** Result of a [ConfigSession] operation. */
sealed interface SessionResult<out T> {

    /** The operation completed. */
    data class Success<T>(val value: T) : SessionResult<T>

    /**
     * @property dataError the repository error when [error] is [SessionError.DATA]
     * @property detail English diagnostic text for logs (also recorded in `AppGraph.diagnostics`)
     */
    data class Failure(
        val error: SessionError,
        val detail: String = "",
        val dataError: DataError? = null,
    ) : SessionResult<Nothing>

    /** True for [Success]. */
    val isSuccess: Boolean get() = this is Success

    /** The value, or null on failure. */
    fun getOrNull(): T? = (this as? Success)?.value

    /** The error, or null on success. */
    fun errorOrNull(): SessionError? = (this as? Failure)?.error
}

/**
 * The editing session of the current model: the one place that knows whether there are unsaved
 * changes and that performs **every flow that writes to or reads from the device**. Nothing else
 * calls `Radio.saveConfigToDevice`, `Radio.readConfigFromDevice`, `Radio.resetToDefaults` or
 * `Radio.loadModelFile` (the model repository loads files on behalf of the session only).
 *
 * ## Rules for screens
 * - Read configuration from [workingConfig] (or directly from [Radio] getters).
 * - **Change** firmware configuration only inside [edit] — it tracks [dirty] and refreshes
 *   [workingConfig]. Calling `Radio.set*` outside [edit] works but leaves the Save button unaware.
 * - Per-model extras are split by who persists them:
 *   - **staged** (part of the Save flow, changed with [updateExtras], undone by [discard]):
 *     `flightModes`, `wing`, `logicalSwitches`, `specialFunctions`, `voiceAlerts`, `vario`,
 *     `timerVoice`, `announcements`, `managed`;
 *   - **immediate** (`ModelRepository.updateExtras` / `updateExtrasOf`, persisted at once, never
 *     dirty): `telemetry`, `home`, `alarms`, `sensorOverrides`, `notes`, `checklist`,
 *     `modelType`, `pictureFile` (through `ModelRepository.setPicture`). `templateId` and
 *     `schemaVersion` are never edited.
 * - The *compiled* settings — all of `wing`, and of `flightModes` the fields `enabled`,
 *   `modeSwitch` and per mode `position`, `offsets`, `rates`, `transitionMs` — are staged **only
 *   through [applyPlan]** (`stage` parameter), together with the firmware items compiled from
 *   them, so that the stored intent can never differ from the mixes. The non-compiled fields
 *   (a mode's `name` and `announce`) go through [updateExtras].
 * - Hand-editing or deleting a managed mix / curve / dual rate: see `ManagedItems` (editing keeps
 *   the record; deleting also stages `managed.withoutMix(…)` etc. through [updateExtras]).
 * - System configuration from `:feature:system` (stick mode, alarm voltage, RF module mode) goes
 *   through [updateSystemConfig], "restore defaults" through [resetToDefaults]. (The Input check
 *   tab edits the pre-flight presets inside [edit] like any other setup value.)
 * - Operations that write to the device ([save], [selectModel], [createModel],
 *   [restoreSnapshot], [reloadCurrentModel], [acceptExternalChange], [updateSystemConfig],
 *   [resetToDefaults]) do not ask the user anything: **the caller shows the "link is up"
 *   confirmation first** (SPEC section 9).
 *
 * ## dirty and deviceInSync
 * - [dirty]: there are edits (working config or staged extras) since the last load / save.
 * - [deviceInSync]: the device has confirmed the model that is loaded. It is false after a model
 *   file was loaded and the following device write did not complete
 *   ([SessionError.DEVICE_WRITE_PENDING]). The UI shows "not written to the radio" and offers
 *   Save; the Save button carries its dot when `dirty || !deviceInSync`.
 * - While [deviceInSync] is false the session never reads from the device (a device read
 *   overwrites the current model file with the device's old configuration, see `Radio`):
 *   [discard] reloads the model file instead and [reloadFromDevice] is refused.
 *
 * ## Radio not ready
 * While `Radio.connection` is not `Ready`, every function returning a [SessionResult] fails fast
 * with [SessionError.RADIO_NOT_READY] and changes nothing; an [edit] block still runs, but its
 * `Radio` calls return `false` / `null` and nothing becomes dirty; [workingConfig] keeps its last
 * value. When the connection becomes `Ready` again (the radio re-read the device configuration)
 * the session behaves as after [discard]: staged extras are reverted and [dirty] is cleared; if
 * that dropped unsaved changes, [saveState] becomes `Failed(RADIO_NOT_READY)` once so that the UI
 * can tell the user. If [deviceInSync] was false at that moment the session runs
 * `ModelRepository.reconcileLibrary()` to repair the loaded model's file (see
 * `ModelRepository.deviceInSync`).
 *
 * All functions are main-safe, serialized against each other and never throw for expected
 * failures. Every failure detail is recorded in `AppGraph.diagnostics`.
 */
interface ConfigSession {

    /** True while the working config or the staged extras differ from what was last loaded / saved. */
    val dirty: StateFlow<Boolean>

    /**
     * False while the model loaded in the working config is not confirmed on the device (mirror
     * of `ModelRepository.deviceInSync`, see the class documentation).
     */
    val deviceInSync: StateFlow<Boolean>

    /** Progress / outcome of the last device-writing operation. */
    val saveState: StateFlow<SaveState>

    /**
     * The working configuration, re-read (debounced, conflated) whenever `Radio.configRevision`
     * changes. [WorkingConfig.EMPTY] until the radio is ready. Live values inside it are stale;
     * use `Radio.live` for those.
     */
    val workingConfig: StateFlow<WorkingConfig>

    /**
     * Runs [block] against the radio. Use it for every `set*` / `add*` / `delete*` call,
     * read-modify-write included:
     *
     * ```kotlin
     * session.edit { radio ->
     *     val mix = radio.getMix(channel, index) ?: return@edit
     *     radio.setMix(mix.copy(weightUp = 80, weightDown = 80))
     * }
     * ```
     *
     * [dirty] is set **only if `Radio.configRevision` advanced during the block** — a block that
     * returned early or whose calls were all rejected leaves the session clean.
     *
     * Blocks are serialized with each other and with [save] / [discard] / [applyPlan].
     *
     * @param refresh `true` (default): [workingConfig] is re-read before this function returns,
     *   so the caller sees its own change. `false`: the re-read is left to the debounced
     *   `configRevision` collector — use it for continuous gestures (dragging a curve point or a
     *   weight slider with live preview): keep the dragged value in local UI state, send each
     *   step with `edit(refresh = false)`, and finish the gesture with one normal `edit`.
     */
    suspend fun <T> edit(refresh: Boolean = true, block: suspend (Radio) -> T): T

    /**
     * Stages a change of the current model's *staged* extras (see the class documentation for
     * which fields) and marks the session dirty if the result differs. Compiled flight-mode /
     * wing settings must not be changed here — use [applyPlan] with `stage`.
     */
    suspend fun updateExtras(transform: (ModelExtras) -> ModelExtras): ModelExtras

    /**
     * Writes the working config to the device, then persists the model: staged extras, library
     * export and a snapshot (`ModelRepository.onDeviceConfigSaved`). Clears [dirty] and sets
     * [deviceInSync] on success. Also valid when not dirty (re-sends the configuration) — it is
     * the retry after [SessionError.DEVICE_WRITE_PENDING].
     */
    suspend fun save(): SessionResult<ModelSnapshot>

    /**
     * Throws away unsaved changes and clears [dirty]; staged extras are reverted.
     * - [deviceInSync] true: the configuration is read back from the device
     *   (`Radio.readConfigFromDevice`).
     * - [deviceInSync] false: the current model's file is loaded again
     *   (`ModelRepository.reloadCurrentModel`) — never a device read, which would overwrite that
     *   file with the configuration of the model the device still holds. [deviceInSync] stays
     *   false; to return to the previous model, select it.
     */
    suspend fun discard(): SessionResult<Unit>

    // -- Mix plans (flight modes, wing wizard, templates) ---------------------------------------

    /**
     * Dry run: what applying [plan] to the current working config would add, change and remove,
     * and which conflicts exist (`PlanDiffer`). Changes nothing.
     */
    suspend fun previewPlan(plan: MixPlan): PlanDiff

    /**
     * Applies [plan] in one serialized step: computes the diff against the current working
     * config, refuses on blocking conflicts ([SessionError.PLAN_REJECTED]), otherwise executes
     * the operations in order, re-reads the working config, stages `managedAfter` (fingerprints
     * re-stamped with `ManagedItems.withFingerprintsFrom`) and [stage] in the extras and marks
     * the session dirty. Nothing is written to the device until [save].
     *
     * If an operation fails half-way the already executed operations stay in the working config
     * (still unsaved) and `managedAfter` is staged so that those items stay known as generated,
     * but [stage] is **not** applied; the result is [SessionError.PLAN_OPERATION_FAILED]. The
     * user can apply again (missing items are recreated) or [discard].
     *
     * @param expected the diff the user confirmed (from [previewPlan] / [compileFlightModes] /
     *   [compileWing]). When given, the plan is rejected if the configuration changed since
     *   (`baseRevision` mismatch) or if [expected] itself has blocking conflicts — which includes
     *   the validation conflicts the `compile…` functions append — instead of applying something
     *   the user did not see.
     * @param stage the settings the plan was compiled from, staged atomically with the firmware
     *   items, e.g. `{ it.copy(flightModes = draft) }`. Null when the plan does not come from
     *   settings (or they are staged already).
     * @return the diff that was applied
     */
    suspend fun applyPlan(
        plan: MixPlan,
        expected: PlanDiff? = null,
        stage: ((ModelExtras) -> ModelExtras)? = null,
    ): SessionResult<PlanDiff>

    /**
     * Compiles flight-mode settings with `FlightModeCompiler` and returns the plan together with
     * its dry-run diff; `FlightModeCompiler.validate` problems are appended to the diff as
     * blocking conflicts. Changes nothing.
     *
     * Typical tab flow: keep a local draft → `compileFlightModes(draft)` → show the diff →
     * `applyPlan(plan, diff, stage = { it.copy(flightModes = draft) })`. Cancelling the diff
     * leaves no trace.
     *
     * @param config the draft to compile; null = the flight modes of the working extras
     */
    suspend fun compileFlightModes(config: FlightModesConfig? = null): Pair<MixPlan, PlanDiff>

    /**
     * Same as [compileFlightModes] for the wing / flaps wizard (`WingCompiler`); apply with
     * `applyPlan(plan, diff, stage = { it.copy(wing = draft) })`.
     *
     * @param config the draft to compile; null = the wing settings of the working extras
     */
    suspend fun compileWing(config: WingConfig? = null): Pair<MixPlan, PlanDiff>

    // -- System configuration ---------------------------------------------------------------------

    /**
     * Changes the system configuration (stick mode, alarm voltage, RF module mode) and persists
     * it: a serialized `Radio.getSystemConfig` → [transform] → `Radio.setSystemConfig`, followed
     * by a device write (`ModelRepository.onDeviceConfigWritten`; no snapshot). `:feature:system`
     * must use this and never call `Radio.saveConfigToDevice` itself.
     *
     * Refused with [SessionError.UNSAVED_CHANGES] while [dirty] (the write would silently save
     * the pending model edits — the caller offers save / discard first) and with
     * [SessionError.DEVICE_WRITE_PENDING] while [deviceInSync] is false. If the device write
     * fails the change stays in the working config and [dirty] becomes true, so [save] retries
     * and [discard] reverts it.
     *
     * @return the system configuration that was written
     */
    suspend fun updateSystemConfig(transform: (SystemConfig) -> SystemConfig): SessionResult<SystemConfig>

    /**
     * "Restore defaults" (`Radio.resetToDefaults`, double-confirmed by the caller): waits for the
     * re-read, drops unsaved changes and staged extras, and — because the SDK then overwrites the
     * **current model's file** with the default configuration — clears the current model's
     * `managed` items and disables its `flightModes` and `wing` settings so that the extras match
     * the firmware again; the library export is refreshed. The previous state of the model
     * remains available in its snapshots. Refused with [SessionError.DEVICE_WRITE_PENDING] while
     * [deviceInSync] is false.
     */
    suspend fun resetToDefaults(): SessionResult<Unit>

    // -- Model flows ----------------------------------------------------------------------------

    /**
     * Switches to another model: loads it (`ModelRepository.selectModel`) and writes it to the
     * device (`ModelRepository.onDeviceConfigWritten` on completion). Refused with
     * [SessionError.UNSAVED_CHANGES] while [dirty] unless [discardChanges]; selecting the model
     * that is already current is the same as [reloadCurrentModel].
     *
     * If the device write does not complete the result is [SessionError.DEVICE_WRITE_PENDING]:
     * the new model **stays loaded and current**, [deviceInSync] is false and [saveState] is
     * `Failed`. [save] retries the write; to go back, select the previous model again. The
     * session never reads from the device in this state (see the class documentation).
     */
    suspend fun selectModel(modelId: String, discardChanges: Boolean = false): SessionResult<ModelEntry>

    /**
     * Creates a model and makes it the current one — all or nothing:
     * 1. `ModelRepository.createModel` (the new file starts as a copy of the working config);
     * 2. select it (file load);
     * 3. apply `blueprint.effectivePlan` (`resetConfig` neutralises the copied configuration),
     *    then compile and apply `blueprint.extras.wing` (`WingCompiler`, generator `"wing"`) and
     *    `blueprint.extras.flightModes` (`FlightModeCompiler`, generator `"flightModes"`) against
     *    the resulting working config, each with its validation — see `ModelBlueprint`;
     * 4. [save].
     *
     * Refused with [SessionError.UNSAVED_CHANGES] while [dirty] and with
     * [SessionError.DEVICE_WRITE_PENDING] while [deviceInSync] is false.
     *
     * If any step after 1 fails, the session **rolls back**: it selects the previously current
     * model again through the normal select flow (file load + device write) and deletes the new
     * model, then returns the failure of the step that failed. After a failure the new model
     * never exists, so the call can be repeated with the same name. Should the rollback's own
     * device write fail, the previous model is current with [deviceInSync] false.
     */
    suspend fun createModel(name: String, blueprint: ModelBlueprint): SessionResult<ModelEntry>

    /**
     * Restores a snapshot (`ModelRepository.restoreSnapshot`). For the current model the restored
     * configuration is also written to the device and unsaved changes are dropped; a device write
     * that does not complete ends as described at [selectModel]
     * ([SessionError.DEVICE_WRITE_PENDING], the restored model stays loaded).
     */
    suspend fun restoreSnapshot(snapshot: ModelSnapshot): SessionResult<ModelEntry>

    /**
     * Loads the **current** model from its file again (`ModelRepository.reloadCurrentModel`),
     * reloads its extras and writes it to the device; clears [dirty]. Needed after the file was
     * replaced behind the working config (re-import of an externally edited folder, backup
     * restore with overwrite) — [reloadFromDevice] would do the opposite and destroy the new file.
     * Refused with [SessionError.UNSAVED_CHANGES] while [dirty] unless [discardChanges]. A device
     * write that does not complete ends as described at [selectModel].
     */
    suspend fun reloadCurrentModel(discardChanges: Boolean = false): SessionResult<ModelEntry>

    /**
     * Accepts an externally modified library folder: `ModelRepository.acceptExternalChange`, and
     * — when the change concerns the current model — [reloadCurrentModel] with
     * `discardChanges = true`. Screens call this instead of the repository function.
     */
    suspend fun acceptExternalChange(change: ExternalChange): SessionResult<ModelEntry>

    /**
     * Re-reads the configuration from the device into the working config
     * (`Radio.readConfigFromDevice`), dropping unsaved changes and staged extras. Used by
     * diagnostics. Note that the SDK writes what it read into the current model file; the call
     * is therefore refused with [SessionError.DEVICE_WRITE_PENDING] while [deviceInSync] is false.
     */
    suspend fun reloadFromDevice(): SessionResult<Unit>
}
