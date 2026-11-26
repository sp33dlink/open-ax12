package org.openax12.core.radio

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.ELRS_TX_ADDRESS
import org.openax12.core.model.FirmwareInfo
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.MixLine
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.TelemetryAlarm
import org.openax12.core.model.TelemetryItem
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.Trims

/**
 * The only way anything in the app talks to the radio. Implemented by `SdkRadio` (`:sdk`, JNI to
 * `rmAxSdk1`) and `FakeRadio` (`:core:radio`, simulator) — nothing else (design rule 3).
 *
 * The interface mirrors every function of `rmAxSdkApi.h` with idiomatic names; the SDK function is
 * named in each KDoc. The firmware-update and app-update functions and `postMessage` are
 * deliberately absent and must never be added (design rule 4).
 *
 * ## The three data locations
 * - **working config**: the SDK's in-memory configuration. All `get*` / `set*` functions act on it.
 *   `set*` only changes the working config and sends a live preview to the radio; nothing is
 *   persisted.
 * - **model files**: JSON files in the SDK's private data directory ([getModels], [loadModelFile],
 *   [saveWorkingConfigToModel], …).
 * - **device config**: what the radio MCU really flies with. [saveConfigToDevice] persists the
 *   working config there (and into the current model file); [readConfigFromDevice] reads it back
 *   — **and overwrites the current model file with it** (SDK manual 9.2).
 *
 * ## Device reads overwrite the current model file
 * Every completed `taskReadConfigFromDevice` — the initial one of [start], [readConfigFromDevice]
 * and the re-read triggered by [resetToDefaults] — makes the SDK write the device configuration
 * into the file of the *current* model (assumption 22: the model loaded last). After
 * [loadModelFile] the device and that file disagree until a [saveConfigToDevice] completed; a
 * device read in that window replaces the newly loaded model's file with the configuration of
 * the model the device still holds. Rules for callers (in practice `ConfigSession`):
 * - never use [readConfigFromDevice] to undo a [loadModelFile] — load the previous file again;
 * - if the [saveConfigToDevice] after a [loadModelFile] did not complete, either retry it or call
 *   [loadModelFile] with the previous file before anything can trigger a device read;
 * - keep a copy of the model file outside the SDK (the library mirror) to repair the case where
 *   the app dies inside that window and the next [start] performs the read.
 *
 * ## Threading
 * All functions are safe to call from any thread and any dispatcher, including `Dispatchers.Main`:
 * implementations switch to their own serial worker context, so calls never block the caller's
 * thread and are executed one at a time in call order. Flows may be collected from any context;
 * they emit on the implementation's own dispatcher. Never call the SDK from anywhere else.
 *
 * ## Failure semantics
 * The SDK is `noexcept` and reports failure only through empty / default results. This interface
 * makes that explicit and never throws for runtime conditions:
 * - **Radio not usable** ([connection] is [ConnectionState.Stopped], [ConnectionState.Initializing]
 *   or [ConnectionState.Error]): list getters return an empty list, single-item getters return
 *   `null`, setters / commands return `false` and do nothing, tasks return [TaskResult.NOT_READY].
 *   In [ConnectionState.WaitingForDevice] calls work on the local working config, but it does not
 *   reflect the device yet.
 * - **The SDK rejects a call** (returns `-1`): `false` or `null` as documented per function.
 * - **Invalid index / argument** (channel, source, curve, slot, timer out of range, too many curve
 *   points, …): the call is **not** forwarded to the SDK (out-of-range indices are undefined
 *   behaviour there, design rule 6) and returns `false` / `null` like a rejected call. It does not
 *   throw. Implementations validate against **what the SDK actually returned**, with the
 *   constants of `:core:model` only as an upper bound: a channel index against the size of
 *   `rcGetChOutList()`, a curve index against `rcGetCurveList()`, a source index against
 *   `rcGetSrcCfgList()`, a mix index against the channel's `mixesNum`, a notification index
 *   against `rcGetNotifyCfgList()`, an ELRS menu index against the items delivered since the last
 *   menu request. The `isValid…` helpers of `RadioExtensions.kt` are cheap UI pre-checks against
 *   the constants, not this validation.
 * - A `true` from a setter means "handed to the SDK", not "the device accepted it": `void` SDK
 *   functions give no feedback.
 * - Cancelling a suspended call cancels only the waiting; an SDK call already under way completes.
 *
 * ## Read-modify-write (binding for implementations)
 * Callers read a struct, `copy()` the fields to change and write it back (design rule 6). That
 * convention alone would not be safe — plans and templates build structs from scratch, and the
 * model types map some SDK integers to enums lossily — so **every setter performs the SDK read
 * itself immediately before the write and overlays only the writable fields** onto that fresh
 * native struct:
 * - Fields documented as read-only / read-back / live in the model type are never taken from the
 *   argument; they go back to the SDK exactly as just read. The writable fields are listed per
 *   setter.
 * - A field that the model type maps to an enum or a Boolean ([org.openax12.core.model.StickMode],
 *   [org.openax12.core.model.RfModuleMode], [org.openax12.core.model.CompareOp],
 *   [org.openax12.core.model.MixMode], [org.openax12.core.model.CurveType],
 *   [org.openax12.core.model.TimerAlertMode], `countUp`, `popup`, `showInUi`, …) is written only
 *   if the argument's value differs from the mapped value of the fresh read; otherwise the raw
 *   native integer stays untouched. An unknown device code is therefore never rewritten by a
 *   write that changes another field.
 * - If the fresh read is not possible (invalid index, radio not usable) nothing is written and the
 *   setter returns `false` / `null`.
 * - The Kotlin ↔ SDK index mapping of timers and counters lives in exactly one function of each
 *   implementation (assumption 21).
 *
 * `FakeRadio` holds the model types natively and follows the same rules: read-only fields of the
 * argument are ignored.
 */
interface Radio {

    // ------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------

    /** Connection state machine. Starts as [ConnectionState.Stopped]. */
    val connection: StateFlow<ConnectionState>

    /**
     * Latest frame of live data (sources, channels, trims, status, timers), refreshed after every
     * `RC_MSGID_MIXOUT_UPDATE` (about every 40 ms). Conflated: slow collectors skip frames.
     * [LiveSnapshot.EMPTY] until the first frame. Read it in the smallest possible composable or
     * in a draw lambda; never rebuild a screen per frame.
     */
    val live: StateFlow<LiveSnapshot>

    /**
     * All other SDK messages (see [RadioEvent]). Hot, no replay; a slow collector does not block
     * the radio — when its buffer (at least 256 events) overflows the oldest events are dropped.
     */
    val events: SharedFlow<RadioEvent>

    /**
     * Increments whenever the working config may differ from what a caller read before: after
     * every successful `set*` / `add*` / `delete*` call of this interface, after
     * [readConfigFromDevice], [loadModelFile], [resetToDefaults] and after the initial device read.
     * Collect it to know when to re-read configuration; it says nothing about *what* changed.
     */
    val configRevision: StateFlow<Long>

    /** `rmaxSdk::version()`: SDK build timestamp string; available before [start]. Simulator: `"simulator"`. */
    val sdkVersion: String

    /** True for `FakeRadio`. The UI shows a "SIM" badge. */
    val isSimulator: Boolean

    /** Simulator controls; `null` on the real radio. */
    val simulator: SimulatorControl?

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    /**
     * `initialize(appDataPath)` + `setMessageCallback` + the initial `taskReadConfigFromDevice`.
     *
     * Moves [connection] through [ConnectionState.Initializing] → [ConnectionState.WaitingForDevice]
     * → [ConnectionState.Ready], or to [ConnectionState.Error]. Returns once one of the final two
     * states is reached (the device read has its own internal timeout →
     * [ConnectionError.DeviceTimeout]). Idempotent: calling it while started returns the current
     * state; calling it in [ConnectionState.Error] retries.
     *
     * Side effect: when the initial device read completes the SDK overwrites the current model
     * file with the device configuration (see "Device reads overwrite the current model file").
     * A port held by the stock app usually shows up as [ConnectionError.DeviceTimeout] or
     * [ConnectionError.CommunicationInitFailed], see [ConnectionError.mayBeStockAppConflict].
     *
     * @return the state reached
     */
    suspend fun start(): ConnectionState

    /**
     * Clears the message callback and calls `shutdown()` in the order the SDK manual prescribes.
     * [connection] becomes [ConnectionState.Stopped]. Safe to call repeatedly.
     */
    suspend fun stop()

    // ------------------------------------------------------------------------------------------
    // Device configuration tasks
    // ------------------------------------------------------------------------------------------

    /**
     * `taskReadConfigFromDevice()`: reads the device configuration into the working config and
     * suspends until `RC_MSGID_GETDEVCFG_END` or [timeout]. Unsaved working-config changes are lost.
     *
     * **On completion the SDK also overwrites the current model file with the device
     * configuration.** Never call it between a [loadModelFile] and the completed
     * [saveConfigToDevice] that follows (see the class documentation).
     */
    suspend fun readConfigFromDevice(timeout: Duration = DEFAULT_TASK_TIMEOUT): TaskResult

    /**
     * `taskSaveConfigToDevice()`: writes the working config to the device (the SDK also saves the
     * current model file) and suspends until `RC_MSGID_CFGTODEV_END` or [timeout].
     *
     * Never writes an empty or partial working config: if `ModelFileCheck.workingProblem` finds
     * one (no output channels, or every channel limit 0) the result is [TaskResult.REFUSED] and
     * nothing is sent. `taskSaveConfigToDevice` writes the whole configuration, so such a config
     * would wipe the radio's mixes and channel setup.
     */
    suspend fun saveConfigToDevice(timeout: Duration = DEFAULT_TASK_TIMEOUT): TaskResult

    /**
     * `rcResetDefault()`: restores the default configuration and triggers a re-read. Overwrites
     * the working config **and, when the re-read completes, the current model file** (the current
     * model becomes a default model; its previous state survives only in the library snapshots).
     * Destructive — the UI must double-confirm. `true` = handed to the SDK; the re-read is
     * announced by [RadioEvent.ConfigRead] and a new [configRevision].
     */
    suspend fun resetToDefaults(): Boolean

    // ------------------------------------------------------------------------------------------
    // Inputs, outputs, trims, status
    // ------------------------------------------------------------------------------------------

    /** `rcGetSrcCfgList()`: all input sources ordered by index, with live values. */
    suspend fun getSources(): List<SourceInfo>

    /** `rcGetChOutList()`: all output channels with configuration and live values. */
    suspend fun getOutputChannels(): List<OutputChannelConfig>

    /** `rcGetTrimming()`. */
    suspend fun getTrims(): Trims?

    /** `rcGetRadioStatus()`. Prefer [live] for display. */
    suspend fun getRadioStatus(): RadioStatus?

    /**
     * `rcSetChOutCfgData()`: writes one output channel. The implementation takes a fresh
     * `rcGetChOutList()[chIdx]` and overlays `middle`, `min`, `max`, `curveIdx`, `weight` and the
     * lock fields (`lockChSwIdx` / `lockChSwType` / `lockChSwVal` / `lockChVal`); `dr1..dr3`,
     * `mixesNum`, `chOut` and `logicVal` go back exactly as just read, so
     * [OutputChannelConfig.dualRates], [OutputChannelConfig.mixCount] and the live fields of
     * [config] are ignored (see "Read-modify-write").
     */
    suspend fun setOutputChannel(config: OutputChannelConfig): Boolean

    // ------------------------------------------------------------------------------------------
    // Mixes
    // ------------------------------------------------------------------------------------------

    /**
     * `rcGetMixCfgData(chIdx, mixIdx)`: mix line [mixIndex] of [channel]; `null` if the indices
     * are out of range ([mixIndex] must be below the channel's mix count).
     */
    suspend fun getMix(channel: Int, mixIndex: Int): MixLine?

    /**
     * `rcSetMixCfgData()`: rewrites the mix line identified by `(mix.channel, mix.mixIndex)`. The
     * implementation takes a fresh `rcGetMixCfgData(chIdx, mixIdx)` and overlays `srcIdx`,
     * `offset`, `curveIdx`, the switch triple, `mixesMode`, `slowMsUp` / `slowMsDown` and
     * `weightUp` / `weightDown`; `srcName`, `srcType` and `mixesCount` go back as read
     * ([MixLine.sourceName], [MixLine.sourceType] and [MixLine.mixCount] of [mix] are ignored).
     */
    suspend fun setMix(mix: MixLine): Boolean

    /**
     * `rcAddMixCfgData(chIdx, srcIdx)`: adds a mix of [source] to [channel] and returns the new
     * line with its defaults (write the wanted fields with [setMix] afterwards). `null` if the SDK
     * could not add it (channel full) or `(channel, source)` already exists (assumption 5).
     */
    suspend fun addMix(channel: Int, source: SourceId): MixLine?

    /**
     * `rcDelMixCfgData(chIdx, srcIdx)`: deletes the mix of [source] on [channel] — the key is the
     * **source**, not the mix index.
     *
     * @return the number of mixes left on the channel, or `null` on failure
     */
    suspend fun deleteMix(channel: Int, source: SourceId): Int?

    // ------------------------------------------------------------------------------------------
    // Dual rates
    // ------------------------------------------------------------------------------------------

    /** `rcGetDrData(chIdx, idx)`: dual-rate [slot] (0..2) of [channel]. */
    suspend fun getDualRate(channel: Int, slot: Int): DualRateSlot?

    /**
     * `rcGetDrData(chIdx, -1)`: the SDK's own idea of a free slot of [channel]. **Unverified raw
     * call — do not allocate with it** (assumption 18): the SDK returns a struct by value, so a
     * failed or exhausted lookup comes back zero-initialized and is indistinguishable from "slot 0
     * of channel 0"; and since `rcDelDrData` does nothing, the SDK may never count a slot the app
     * disabled as free. The result is passed on only if its `chIdx` equals [channel], its `idx`
     * is 0..2 and that slot reads back disabled (`swCmpType == 0`); otherwise `null`.
     * Allocate with `Radio.findDisabledDualRateSlot` (first disabled slot of the channel) instead.
     */
    suspend fun findFreeDualRateSlot(channel: Int): DualRateSlot?

    /**
     * `rcSetDrData()`: writes the switch condition and the weight of a slot; `drCount` goes back
     * as read ([DualRateSlot.count] of [slot] is ignored).
     *
     * @return the number of dual rates in use on the channel, or `null` on failure
     */
    suspend fun setDualRate(slot: DualRateSlot): Int?

    /**
     * `rcDelDrData()`. **Known SDK limitation: this is a no-op** that only returns 0; the slot is
     * not cleared. To remove a dual rate write it back disabled with [setDualRate]. Provided for
     * completeness.
     */
    suspend fun deleteDualRate(slot: DualRateSlot): Boolean

    // ------------------------------------------------------------------------------------------
    // Curves
    // ------------------------------------------------------------------------------------------

    /** `rcGetCurveList(-1)`: all curves (18 by default; rely on the returned list). */
    suspend fun getCurves(): List<CurveConfig>

    /** `rcGetCurveList(idx)`: one curve. */
    suspend fun getCurve(index: Int): CurveConfig?

    /** `rcSetCurveCfg()`: `true` if the SDK returned 0. */
    suspend fun setCurve(curve: CurveConfig): Boolean

    /**
     * `rcCurveApply(curveIdx, rawVal)`: the firmware's own evaluation of curve [curveIndex] for
     * input [value]. Returns [value] unchanged for [org.openax12.core.model.NO_CURVE], an invalid
     * index or when the radio is not usable.
     *
     * **Unit unverified** (assumption 19): this interface treats input and output as percent
     * (−100..+100), but the SDK parameter is called `rawVal` — the name of the half-scale raw
     * field of a source — and no unit is documented. Diagnostics checks it with an identity
     * custom curve (input 50 must give 50, ±100 must clamp); until then the curve editor's marker
     * and the comparison with `CurveMath` (assumption 12) are provisional.
     */
    suspend fun applyCurve(curveIndex: Int, value: Double): Double

    // ------------------------------------------------------------------------------------------
    // Timers and counters
    // ------------------------------------------------------------------------------------------

    /**
     * `rcGetTimerCfgData(timerIdx, isCounter)`: [index] is always the **in-group** index 0..2 on
     * this interface.
     *
     * How that maps to the SDK arguments is unverified (assumption 21): the manual says
     * `isCounter` selects the array and each group uses 0..2, the header comment says "0-2 are
     * timers, 3-5 are counters". Implementations follow the manual and keep the mapping in one
     * function so that a device finding changes one place; Diagnostics reads counter 0 both ways.
     */
    suspend fun getTimerConfig(index: Int, isCounter: Boolean = false): TimerConfig?

    /**
     * `rcSetTimerCfgData(timerCfg, isCounter)`: `config.index` (in-group, see [getTimerConfig])
     * selects the timer / counter. The implementation takes a fresh `rcGetTimerCfgData` and
     * overlays the configuration fields; `trDirection`, `trStartTime`, `timeCount` and
     * `notifyCode` go back as read (the read-back properties of [config] are ignored).
     */
    suspend fun setTimerConfig(config: TimerConfig, isCounter: Boolean = false): Boolean

    /**
     * `rcResetTimer(timerIdx)`: resets a timer or counter on the device. [deviceIndex] is the
     * **device** timer number: 0..2 = timers, 3..5 = counters
     * (see [org.openax12.core.model.TimerState.deviceIndex]).
     *
     * Reset is the only timer command the SDK has: there is **no manual start / stop** — a timer
     * runs while the switch condition of its configuration holds.
     */
    suspend fun resetTimer(deviceIndex: Int): Boolean

    // ------------------------------------------------------------------------------------------
    // System configuration and device control
    // ------------------------------------------------------------------------------------------

    /**
     * The system configuration of the radio: stick mode, RF module mode, alarm voltage, pre-flight
     * presets and the fields the SDK carries along.
     *
     * Null when no trustworthy values exist. The SDK backend does **not** use `rcGetRcSysConfig()`
     * (it returns an uninitialised struct on the AX12, assumption r5): it reads the `RC_MODEL_CFG`
     * of the current model file and returns null unless the values are plausible
     * (`SystemConfigValidity`). Callers treat null as "unavailable" and never substitute defaults.
     */
    suspend fun getSystemConfig(): SystemConfig?

    /**
     * `rcSetRcSysConfig()`. Only alarm voltage, RF module mode, stick mode and the pre-flight
     * presets are really applied by the SDK. The implementation overlays [config] onto a fresh
     * [getSystemConfig]; `stickMode` and `rfModuleMode` are written only when they really
     * changed (an unknown device code such as RF mode `0xFF` survives, see "Read-modify-write").
     * Returns false when the current values are not trustworthy or the result would not be
     * plausible. The SDK setter damages curve 0; the SDK backend repairs it (assumption r6) and
     * returns false, leaving the working config to be discarded, if it cannot.
     *
     * Callers must build [config] from a fresh [getSystemConfig] inside the same edit — never
     * from a cached `WorkingConfig.system` or from `SystemConfig()` defaults. The change is
     * persisted by [saveConfigToDevice] like everything else (`:feature:system` goes through
     * `ConfigSession.updateSystemConfig`).
     */
    suspend fun setSystemConfig(config: SystemConfig): Boolean

    /** `rcSkipPreFlightCheck()`: dismisses the current pre-flight check alarm. */
    suspend fun skipPreFlightCheck(): Boolean

    /**
     * `rcSetAdcCalibration(isStart)`: starts (`true`) or stops (`false`) the stick calibration.
     * Progress is in [RadioStatus.calibrationState].
     */
    suspend fun setStickCalibration(active: Boolean): Boolean

    /** `rcSetUsbToVcp(mode)`: USB serial passthrough mode; the value is device-protocol-defined (0 = off). */
    suspend fun setUsbSerialPassthrough(mode: Int): Boolean

    /** `rcRebootRFModule()`. */
    suspend fun rebootRfModule(): Boolean

    /** `rcGetFwInf()`: cached firmware info (read-only; may be empty until the device answered). */
    suspend fun getFirmwareInfo(): FirmwareInfo?

    /** `rcTaskRequestDeviceInf()`: asks the device for its info; answered by [RadioEvent.FirmwareInfoUpdated]. */
    suspend fun requestDeviceInfo(): Boolean

    // ------------------------------------------------------------------------------------------
    // Models and templates (SDK-private model files)
    // ------------------------------------------------------------------------------------------

    /** `rcGetRcModelList()`. */
    suspend fun getModels(): List<ModelInfo>

    /**
     * `rcGetCurrentModelCfgIndex()`: the **raw** SDK value, or `null` on failure (`-1`).
     *
     * Its meaning is unverified (assumption 15): the SDK returns a plain `int` here while
     * [ModelInfo.index] mirrors a 64-bit `modelIdx`, so it may be the model's `modelIdx` or its
     * position in [getModels]. Do not interpret it — `Radio.getCurrentModel()` is the only resolver.
     */
    suspend fun getCurrentModelIndex(): Int?

    /**
     * `rcCreateModelCfg()`: new model metadata with a target path. **No file is created** until
     * [saveWorkingConfigToModel] is called with it — which stores a copy of the *current working
     * config*. The SDK has no way to create a model file with default settings; a new model is
     * therefore a copy that the first applied plan neutralises
     * (`MixPlan.resetConfig`, assumption 20).
     */
    suspend fun createModelInfo(): ModelInfo?

    /**
     * `rcSetModelCfg()`: edits the metadata (name, image, model match) of an existing model file.
     * Not a substitute for saving the working config.
     */
    suspend fun updateModelInfo(model: ModelInfo): ModelInfo?

    /** `rcSaveCurrentCfgToModel()`: writes the working config plus [model]'s metadata into that model file ("save as"). */
    suspend fun saveWorkingConfigToModel(model: ModelInfo): Boolean

    /**
     * `rcLoadModelFile()`: loads [modelFile] as the working config (previewed on the radio, not
     * yet saved to the device — call [saveConfigToDevice] to make it the flying config) and makes
     * it the SDK's current model file.
     *
     * `true` only means "the SDK accepted the file". A file without a `channelOut` section loads
     * successfully into a working config without mixes and with all channel limits 0 (assumption
     * r11): check the file with `ModelFileCheck.fileProblem` before and compare the working
     * config afterwards (`ModelFileCheck.mismatch`).
     *
     * From this call until a [saveConfigToDevice] completed, the device and the current model
     * file disagree and **any device read would overwrite [modelFile] with the device's old
     * configuration**. If that save does not complete, retry it or load the previous file again
     * before anything can trigger a read; never undo a load with [readConfigFromDevice].
     */
    suspend fun loadModelFile(modelFile: String): Boolean

    /**
     * `rcExportModelCfg(source, destination)`: copies the model file [modelFile] to
     * [destinationFile]. `true` = handed to the SDK; check that the destination exists.
     */
    suspend fun exportModel(modelFile: String, destinationFile: String): Boolean

    /**
     * Imports the model file [importFile] into the existing model file [targetModelFile]: the
     * target takes over everything of the source (configuration, **channels and mixes**) and keeps
     * its own identity (index, name, image). `true` = the target was replaced; `false` = nothing
     * was written (the target does not exist, or the source is not a complete model file).
     *
     * The SDK backend does not use `rcImportModelCfg` for this: it leaves the target with 16 empty
     * channels and no mixes (assumption r12), which loads as an empty configuration. It merges the
     * files itself (`ModelFileCheck.importInto`).
     */
    suspend fun importModel(importFile: String, targetModelFile: String): Boolean

    /** `rcDeleteModel(filePath)`. */
    suspend fun deleteModel(modelFile: String): Boolean

    /** `rcGetTemplateList()`: the SDK's own (stock) templates. */
    suspend fun getTemplates(): List<ModelInfo>

    /** `rcModelToTemplate(modelFile, tempName)`. */
    suspend fun saveModelAsTemplate(modelFile: String, templateName: String): Boolean

    // ------------------------------------------------------------------------------------------
    // Telemetry list and SDK-side notifications
    // ------------------------------------------------------------------------------------------

    /**
     * `rcGetTelemetryList(uiOnly)`: the SDK's telemetry items. Re-read at most a few times per
     * second in response to [RadioEvent.TelemetryUpdated].
     */
    suspend fun getTelemetryList(uiOnly: Boolean = false): List<TelemetryItem>

    /** `rcGetNotifyCfgList()`: configured SDK-side telemetry notifications. */
    suspend fun getTelemetryAlarms(): List<TelemetryAlarm>

    /**
     * `rcGetNotifyCfgItem(hashValue, index)`. [index] is checked against the current size of
     * `rcGetNotifyCfgList()`; out of range → `null` without calling the SDK.
     */
    suspend fun getTelemetryAlarm(hash: Long, index: Int): TelemetryAlarm?

    /**
     * `rcSetNotifyCfgItem(hashValue, notifyCfg, index)` — the **raw form**; prefer
     * `Radio.addTelemetryAlarm` / `Radio.updateTelemetryAlarm`.
     *
     * The first argument only selects the operation: [hash] `== 0` **adds** a notification
     * ([index] is ignored, pass -1); any other value modifies the notification at [index], which
     * is checked against the current size of `rcGetNotifyCfgList()`. In both cases
     * [TelemetryAlarm.hash] of [alarm] must be the hash of the watched telemetry item — an alarm
     * with hash 0 is rejected (`false`) because it would be bound to no item.
     */
    suspend fun setTelemetryAlarm(hash: Long, alarm: TelemetryAlarm, index: Int): Boolean

    /**
     * `rcDelNotifyItem(index)`. [index] is checked against the current size of
     * `rcGetNotifyCfgList()`; out of range → `false` without calling the SDK.
     */
    suspend fun deleteTelemetryAlarm(index: Int): Boolean

    /**
     * `RCgetTelemetryItemNotify()`: takes one triggered notification from the SDK queue, or `null`
     * when the queue is empty. Call in a loop after [RadioEvent.TelemetryNotificationQueued].
     */
    suspend fun pollTelemetryNotification(): TelemetryAlarm?

    // ------------------------------------------------------------------------------------------
    // ELRS and CRSF
    // ------------------------------------------------------------------------------------------

    /**
     * `rcTaskRequestElrsMenuList(reqSetDevID)`: requests the parameter menu of the CRSF device
     * [deviceAddress]. Answered by [RadioEvent.ElrsDeviceInfo] and one
     * [RadioEvent.ElrsMenuItemReceived] per item. Requests while one is in progress are ignored.
     */
    suspend fun requestElrsMenu(deviceAddress: Int = ELRS_TX_ADDRESS): Boolean

    /**
     * `rcElrsSendMenuCmd(idx, val)`: sets menu item [index] to [value]; acknowledged by
     * [RadioEvent.ElrsAcknowledged]. [index] indexes the SDK's internal menu list: it is forwarded
     * only if a [RadioEvent.ElrsMenuItemReceived] with that [org.openax12.core.model.ElrsMenuItem.index]
     * was delivered since the last [requestElrsMenu]; otherwise `false`.
     */
    suspend fun sendElrsMenuCommand(index: Int, value: Int): Boolean

    /**
     * `rcRequestElrsStatus()`: `true` if the request was sent (answer: [RadioEvent.ElrsStatusReceived]);
     * `false` while a menu request is in progress or on failure.
     */
    suspend fun requestElrsStatus(): Boolean

    /** `rcSendCrsfRawData()`: sends a raw CRSF frame. An empty array is not sent (`false`). */
    suspend fun sendCrsfFrame(data: ByteArray): Boolean

    companion object {
        /** Default timeout of the device read / write tasks. */
        val DEFAULT_TASK_TIMEOUT: Duration = 15.seconds
    }
}
