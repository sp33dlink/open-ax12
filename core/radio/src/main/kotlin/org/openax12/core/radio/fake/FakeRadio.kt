package org.openax12.core.radio.fake

import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.CURVE_COUNT
import org.openax12.core.model.CURVE_MAX_POINTS
import org.openax12.core.model.ChannelDualRate
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.ElrsStatus
import org.openax12.core.model.FirmwareInfo
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.MixLine
import org.openax12.core.model.MixMode
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.PREFLIGHT_NO_CHECK
import org.openax12.core.model.PREFLIGHT_SOURCE_COUNT
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.RfModuleMode
import org.openax12.core.model.SensorId
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.Sources
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TelemetryAlarm
import org.openax12.core.model.TelemetryItem
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.Trims
import org.openax12.core.radio.ConnectionError
import org.openax12.core.radio.ConnectionState
import org.openax12.core.radio.CrsfFrame
import org.openax12.core.radio.Radio
import org.openax12.core.radio.RadioEvent
import org.openax12.core.radio.SimulatedFault
import org.openax12.core.radio.SimulatorControl
import org.openax12.core.radio.TaskResult

/**
 * The simulator backend: a complete in-memory radio for the emulator, previews and tests.
 *
 * It keeps the three data locations of the real system apart — the working config, the model
 * files (JSON in [directory]) and the device config of the simulated MCU — and reproduces the SDK
 * behaviour the app's flows depend on:
 * - `set*` changes only the working config (and is previewed live by the mixer);
 *   [saveConfigToDevice] makes it the device config and writes the current model file;
 * - every completed device read overwrites the working config **and the current model file** with
 *   the device config;
 * - [loadModelFile] changes the working config and the current model, not the device;
 * - [saveWorkingConfigToModel] stores a copy of the working config; [createModelInfo] creates no file;
 * - [deleteDualRate] does nothing; a mix is deleted by `(channel, source)`;
 * - [setSystemConfig] applies only alarm voltage, RF module mode, stick mode and the pre-flight
 *   presets (failsafe and the other fields are not kept);
 * - setters overlay the writable fields onto what is stored and ignore read-only fields.
 *
 * Every 40 ms it reads its inputs (set through [simulator], or moved by the demo motion), runs
 * timers and counters, the mixer ([SimMixer]: mixes → dual rate → channel curve → weight →
 * middle / limits → channel lock), the flight model and publishes a [LiveSnapshot]. Telemetry is
 * delivered as raw CRSF frames plus the simulated SDK telemetry list.
 *
 * Simulator-only simplifications: trims are stored and reported but not mixed in (the documented
 * mixer has no trim input); deleting the current model is refused; counters 1–3 drive the sources
 * `CNT1`–`CNT3` (see [SimTimers]).
 *
 * ## Deterministic use in tests
 * Pass the test dispatcher and a virtual [clock]; with [Options.realisticDelays] `= false` no
 * call waits and all answers arrive before the call returns. With [Options.autoTick] `= false`
 * nothing moves by itself and [advance] steps the simulation explicitly.
 *
 * @param scope scope the simulator runs in; cancelling it stops everything
 * @param directory where model files, templates and the simulator state are kept; null keeps
 *   everything in memory
 * @param dispatcher dispatcher the simulator's serial worker is taken from
 * @param clock monotonic time in ms for [LiveSnapshot.timestampMs] and the frame pacing
 */
class FakeRadio(
    private val scope: CoroutineScope,
    directory: File? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val options: Options = Options(),
) : Radio {

    /**
     * Behaviour switches of the simulator.
     *
     * @property demoMotion whether the demo motion (sticks and flight model) is on initially
     * @property realisticDelays false = start-up, tasks and ELRS answers complete without any delay
     * @property autoTick false = no 25 Hz loop; frames are produced only by [advance]
     * @property tickMs frame period
     * @property seed seed of the flight model's turbulence
     */
    data class Options(
        val demoMotion: Boolean = true,
        val realisticDelays: Boolean = true,
        val autoTick: Boolean = true,
        val tickMs: Long = 40,
        val seed: Long = 1,
    )

    private val worker: CoroutineDispatcher = dispatcher.limitedParallelism(1)
    private val lifecycle = Mutex()

    private val store = SimModelStore(directory)
    private val inputs = SimInputs(options.demoMotion)
    private val telemetry = SimTelemetry(options.seed)
    private val timers = SimTimers()
    private val mixerState = SimMixerState()
    private var elrs = SimElrs()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    private val _live = MutableStateFlow(LiveSnapshot.EMPTY)
    private val _events = MutableSharedFlow<RadioEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _configRevision = MutableStateFlow(0L)
    private val _demoMotion = MutableStateFlow(options.demoMotion)
    private val _linkUp = MutableStateFlow(true)
    private val _telemetry = MutableStateFlow(telemetry.snapshot())

    // Everything below is confined to the worker.
    private var working: SimConfig = SimDefaults.factory()
    private var session: CoroutineScope? = null
    private val pendingInfos = HashMap<String, ModelInfo>()
    private val faults = HashSet<SimulatedFault>()
    private var saving = false
    private var reading = false
    private var menuBusy = false
    private var firmwareInfo: FirmwareInfo? = null
    private var calibrating = false
    private var usbSerialMode = 0

    private var simTimeMs = 0L
    private var manualOffsetMs = 0L
    private var lastTickAt = -1L
    private var sequence = 0L
    private var lastLogic: IntArray = inputs.snapshot()
    private var lastOutputs = DoubleArray(CHANNEL_COUNT)
    private var lastStatus = RadioStatus.EMPTY
    private var lastRxVoltage = 0f

    private var linkRequested = true
    private var linkWasUp = false
    private var rfRebootUntilMs = 0L
    private var alarmCheckDueMs = ALARM_CHECK_MS
    private var telemetryPublishDueMs = 0L
    private var telemetryPublishNow = false

    private var preFlightArmed = false
    private var preFlightActive = false
    private var preFlightStates: List<Int> = emptyList()
    private var lowVoltage = false
    private var lowVoltageAtMs = 0L
    private var flightMs = 0L

    @Volatile
    private var rfOn = true

    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    override val live: StateFlow<LiveSnapshot> = _live.asStateFlow()
    override val events: SharedFlow<RadioEvent> = _events.asSharedFlow()
    override val configRevision: StateFlow<Long> = _configRevision.asStateFlow()
    override val sdkVersion: String = "simulator"
    override val isSimulator: Boolean = true
    override val simulator: SimulatorControl = Control()

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    override suspend fun start(): ConnectionState = lifecycle.withLock {
        withContext(worker) {
            drainCommands()
            val current = _connection.value
            if (current is ConnectionState.Ready || current is ConnectionState.WaitingForDevice) return@withContext current

            _connection.value = ConnectionState.Initializing
            store.load()
            elrs = SimElrs(store.state.elrsValues)
            pause(INIT_DELAY_MS)
            if (faults.remove(SimulatedFault.SERIAL_PORT_BUSY)) {
                return@withContext fail(ConnectionError.SerialPortBusy())
            }

            // The SDK runs: local data is usable, live frames arrive, the device read is under way.
            working = localConfig()
            rfOn = working.system.rfModuleMode != RfModuleMode.OFF
            timers.restart(working)
            mixerState.reset()
            linkWasUp = false
            lastTickAt = -1L
            val newSession = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + worker)
            session = newSession
            _connection.value = ConnectionState.WaitingForDevice
            if (options.autoTick) {
                newSession.launch {
                    while (isActive) {
                        tick(null)
                        delay(options.tickMs)
                    }
                }
            }
            if (faults.remove(SimulatedFault.DEVICE_TIMEOUT)) {
                pause(DEVICE_TIMEOUT_MS)
                return@withContext fail(ConnectionError.DeviceTimeout())
            }
            pause(DEVICE_READ_DELAY_MS)
            if (_connection.value != ConnectionState.WaitingForDevice) return@withContext _connection.value

            completeDeviceRead()
            firmwareInfo = buildFirmwareInfo()
            emit(RadioEvent.FirmwareInfoUpdated)
            _connection.value = ConnectionState.Ready
            preFlightArmed = true
            ConnectionState.Ready
        }
    }

    override suspend fun stop() = lifecycle.withLock {
        withContext(worker) {
            if (_connection.value == ConnectionState.Stopped) return@withContext
            closeSession()
            persistState()
            _connection.value = ConnectionState.Stopped
        }
    }

    /**
     * Steps the simulation by [ms] of simulated time in whole frames, independent of the clock:
     * the deterministic way to let timers, slow mixes and the flight model run in a test
     * (normally with [Options.autoTick] off). Does nothing while the radio is not started.
     */
    suspend fun advance(ms: Long) = withContext(worker) {
        var left = ms
        while (left > 0 && usable) {
            val step = minOf(left, options.tickMs.coerceAtLeast(1))
            manualOffsetMs += step
            tick(step)
            left -= step
        }
    }

    // ------------------------------------------------------------------------------------------
    // Device configuration tasks
    // ------------------------------------------------------------------------------------------

    override suspend fun readConfigFromDevice(timeout: Duration): TaskResult = op {
        when {
            !usable -> return@op TaskResult.NOT_READY
            reading || _connection.value is ConnectionState.WaitingForDevice -> return@op TaskResult.BUSY
        }
        if (faults.remove(SimulatedFault.READ_TIMEOUT)) {
            if (options.realisticDelays) delay(timeout)
            return@op TaskResult.TIMED_OUT
        }
        reading = true
        awaitTask(timeout, DEVICE_READ_DELAY_MS) {
            completeDeviceRead()
            reading = false
        }
    }

    override suspend fun saveConfigToDevice(timeout: Duration): TaskResult = op {
        when {
            !ready -> return@op TaskResult.NOT_READY
            saving -> return@op TaskResult.BUSY
        }
        if (faults.remove(SimulatedFault.SAVE_TIMEOUT)) {
            if (options.realisticDelays) delay(timeout)
            return@op TaskResult.TIMED_OUT
        }
        saving = true
        val snapshot = working
        awaitTask(timeout, DEVICE_WRITE_DELAY_MS) {
            store.updateStateLazily(store.state.copy(device = snapshot))
            store.currentModelPath?.let { path ->
                store.model(path)?.let { store.writeModel(path, it.copy(config = snapshot)) }
            }
            persistState()
            saving = false
            emit(RadioEvent.ConfigWritten)
        }
    }

    override suspend fun resetToDefaults(): Boolean = op {
        if (!ready) return@op false
        val defaults = SimDefaults.factory()
        store.updateState(store.state.copy(device = defaults))
        replaceWorking(defaults)
        later(DEVICE_READ_DELAY_MS) { completeDeviceRead() }
        true
    }

    // ------------------------------------------------------------------------------------------
    // Inputs, outputs, trims, status
    // ------------------------------------------------------------------------------------------

    override suspend fun getSources(): List<SourceInfo> = op {
        if (!usable) return@op emptyList()
        val logic = lastLogic
        working.sources.map { source ->
            val value = logic.getOrElse(source.index) { 0 }
            source.copy(raw = value * RAW_HALF_SCALE / 100, logic = value, output = value * source.weight / 100)
        }
    }

    override suspend fun getOutputChannels(): List<OutputChannelConfig> = op {
        if (!usable) return@op emptyList()
        val config = working
        config.channels.map { channel ->
            val output = lastOutputs.getOrElse(channel.channel) { 0.0 }
            channel.copy(
                mixCount = config.mixes[channel.channel].size,
                output = output.roundToInt(),
                logic = output.toFloat(),
                dualRates = config.dualRates[channel.channel].map {
                    ChannelDualRate(switchSource = it.switch.source.index, switchAction = it.switch.value, weight = it.weight)
                },
            )
        }
    }

    override suspend fun getTrims(): Trims? = op { if (usable) working.trims else null }

    override suspend fun getRadioStatus(): RadioStatus? = op { if (usable) lastStatus else null }

    override suspend fun setOutputChannel(config: OutputChannelConfig): Boolean = op {
        if (!usable) return@op false
        val current = working.channels.getOrNull(config.channel) ?: return@op false
        if (!isCurveReference(config.curve) || !isCondition(config.lock)) return@op false
        val updated = current.copy(
            middle = config.middle.coerceIn(-100, 100),
            min = config.min.coerceIn(-100, 100),
            max = config.max.coerceIn(-100, 100),
            curve = config.curve,
            weight = config.weight.coerceIn(-WEIGHT_LIMIT, WEIGHT_LIMIT),
            lock = config.lock,
            lockValue = config.lockValue.coerceIn(-100, 100),
        )
        changeWorking(working.copy(channels = working.channels.with(config.channel, updated)))
        true
    }

    // ------------------------------------------------------------------------------------------
    // Mixes
    // ------------------------------------------------------------------------------------------

    override suspend fun getMix(channel: Int, mixIndex: Int): MixLine? = op {
        if (!usable) return@op null
        val lines = working.mixes.getOrNull(channel) ?: return@op null
        lines.getOrNull(mixIndex)?.let { described(it, lines.size) }
    }

    override suspend fun setMix(mix: MixLine): Boolean = op {
        if (!usable) return@op false
        val lines = working.mixes.getOrNull(mix.channel) ?: return@op false
        val current = lines.getOrNull(mix.mixIndex) ?: return@op false
        if (!mix.source.isValid || !isCurveReference(mix.curve) || !isCondition(mix.switch)) return@op false
        // A channel holds one mix per source: moving a line onto a source that is taken is refused.
        if (mix.source != current.source && lines.any { it.source == mix.source }) return@op false
        val updated = current.copy(
            source = mix.source,
            offset = mix.offset.coerceIn(-100, 100),
            curve = mix.curve,
            switch = mix.switch,
            mode = mix.mode,
            slowUpMs = mix.slowUpMs.coerceIn(0, SLOW_LIMIT_MS),
            slowDownMs = mix.slowDownMs.coerceIn(0, SLOW_LIMIT_MS),
            weightUp = mix.weightUp.coerceIn(-WEIGHT_LIMIT, WEIGHT_LIMIT),
            weightDown = mix.weightDown.coerceIn(-WEIGHT_LIMIT, WEIGHT_LIMIT),
        )
        if (mix.source != current.source) mixerState.forget(mix.channel, current.source.index)
        changeWorking(working.copy(mixes = working.mixes.with(mix.channel, lines.with(mix.mixIndex, updated))))
        true
    }

    override suspend fun addMix(channel: Int, source: SourceId): MixLine? = op {
        if (!usable) return@op null
        val lines = working.mixes.getOrNull(channel) ?: return@op null
        if (!source.isValid) return@op null
        if (faults.remove(SimulatedFault.ADD_MIX_FAILS)) return@op null
        if (lines.size >= SimDefaults.MAX_MIXES_PER_CHANNEL || lines.any { it.source == source }) return@op null
        val line = MixLine(channel = channel, mixIndex = lines.size, source = source, mode = MixMode.ADD)
        changeWorking(working.copy(mixes = working.mixes.with(channel, lines + line)))
        described(line, lines.size + 1)
    }

    override suspend fun deleteMix(channel: Int, source: SourceId): Int? = op {
        if (!usable) return@op null
        val lines = working.mixes.getOrNull(channel) ?: return@op null
        if (lines.none { it.source == source }) return@op null
        val remaining = lines.filter { it.source != source }.mapIndexed { i, line -> line.copy(mixIndex = i) }
        mixerState.forget(channel, source.index)
        changeWorking(working.copy(mixes = working.mixes.with(channel, remaining)))
        remaining.size
    }

    // ------------------------------------------------------------------------------------------
    // Dual rates
    // ------------------------------------------------------------------------------------------

    override suspend fun getDualRate(channel: Int, slot: Int): DualRateSlot? = op {
        if (!usable) return@op null
        val slots = working.dualRates.getOrNull(channel) ?: return@op null
        slots.getOrNull(slot)?.copy(count = slots.count { it.isEnabled })
    }

    override suspend fun findFreeDualRateSlot(channel: Int): DualRateSlot? = op {
        if (!usable) return@op null
        val slots = working.dualRates.getOrNull(channel) ?: return@op null
        slots.firstOrNull { !it.isEnabled }?.copy(count = slots.count { it.isEnabled })
    }

    override suspend fun setDualRate(slot: DualRateSlot): Int? = op {
        if (!usable) return@op null
        val slots = working.dualRates.getOrNull(slot.channel) ?: return@op null
        val current = slots.getOrNull(slot.slot) ?: return@op null
        if (!isCondition(slot.switch)) return@op null
        val updated = current.copy(switch = slot.switch, weight = slot.weight.coerceIn(-WEIGHT_LIMIT, WEIGHT_LIMIT))
        val newSlots = slots.with(slot.slot, updated)
        changeWorking(working.copy(dualRates = working.dualRates.with(slot.channel, newSlots)))
        newSlots.count { it.isEnabled }
    }

    /** `rcDelDrData` is a no-op in the SDK: the slot stays exactly as it is. */
    override suspend fun deleteDualRate(slot: DualRateSlot): Boolean = op {
        usable && working.dualRates.getOrNull(slot.channel)?.getOrNull(slot.slot) != null
    }

    // ------------------------------------------------------------------------------------------
    // Curves
    // ------------------------------------------------------------------------------------------

    override suspend fun getCurves(): List<CurveConfig> = op { if (usable) working.curves else emptyList() }

    override suspend fun getCurve(index: Int): CurveConfig? = op { if (usable) working.curves.getOrNull(index) else null }

    override suspend fun setCurve(curve: CurveConfig): Boolean = op {
        if (!usable || curve.index !in working.curves.indices || !SimCurves.isAcceptable(curve)) return@op false
        val stored = curve.copy(
            x = List(CURVE_MAX_POINTS) { curve.x.getOrElse(it) { 0 } },
            y = List(CURVE_MAX_POINTS) { curve.y.getOrElse(it) { 0 } },
        )
        changeWorking(working.copy(curves = working.curves.with(curve.index, stored)))
        true
    }

    override suspend fun applyCurve(curveIndex: Int, value: Double): Double = op {
        if (!usable || curveIndex == NO_CURVE) return@op value
        val curve = working.curves.getOrNull(curveIndex) ?: return@op value
        SimCurves.apply(curve, value)
    }

    // ------------------------------------------------------------------------------------------
    // Timers and counters
    // ------------------------------------------------------------------------------------------

    override suspend fun getTimerConfig(index: Int, isCounter: Boolean): TimerConfig? = op {
        if (!usable) return@op null
        timerList(isCounter).getOrNull(index)?.let { timers.withReadBack(it, isCounter) }
    }

    override suspend fun setTimerConfig(config: TimerConfig, isCounter: Boolean): Boolean = op {
        if (!usable) return@op false
        val list = timerList(isCounter)
        val current = list.getOrNull(config.index) ?: return@op false
        if (!isCondition(config.switch) || !isCondition(config.resetSwitch)) return@op false
        // A counter's startTime is its maximum and must not be 0; a timer's must not be negative.
        if (config.startTime < (if (isCounter) 1 else 0)) return@op false
        val updated = current.copy(
            mode = config.mode,
            switch = config.switch,
            startTime = config.startTime,
            countUp = config.countUp,
            alertMode = config.alertMode,
            alertIntervalSec = config.alertIntervalSec.coerceAtLeast(0),
            resetSwitch = config.resetSwitch,
            compareValue = config.compareValue,
            compareOp = config.compareOp,
            loopMode = config.loopMode,
            outputMode = config.outputMode,
            outputMin = config.outputMin.coerceIn(-100, 100),
            outputMax = config.outputMax.coerceIn(-100, 100),
            threshold = config.threshold,
        )
        val newList = list.with(config.index, updated)
        changeWorking(if (isCounter) working.copy(counters = newList) else working.copy(timers = newList))
        timers.configChanged(config.index, isCounter, current, updated)
        true
    }

    override suspend fun resetTimer(deviceIndex: Int): Boolean = op {
        usable && timers.reset(working, deviceIndex, ::emit)
    }

    // ------------------------------------------------------------------------------------------
    // System configuration and device control
    // ------------------------------------------------------------------------------------------

    override suspend fun getSystemConfig(): SystemConfig? = op { if (usable) working.system else null }

    override suspend fun setSystemConfig(config: SystemConfig): Boolean = op {
        if (!usable) return@op false
        // Only these four fields are mapped by the SDK; failsafe, stick beep, model match id and
        // CRSF baud rate are silently not kept.
        val updated = working.system.copy(
            stickMode = config.stickMode,
            rfModuleMode = config.rfModuleMode,
            alarmVoltageDeciVolt = config.alarmVoltageDeciVolt.coerceIn(0, 255),
            preFlightCheck = List(PREFLIGHT_SOURCE_COUNT) { i ->
                val preset = config.preFlightCheck.getOrElse(i) { PREFLIGHT_NO_CHECK }
                if (preset == PREFLIGHT_NO_CHECK) preset else preset.coerceIn(-100, 100)
            },
        )
        rfOn = updated.rfModuleMode != RfModuleMode.OFF
        changeWorking(working.copy(system = updated))
        true
    }

    override suspend fun skipPreFlightCheck(): Boolean = op {
        if (!usable) return@op false
        preFlightArmed = false
        preFlightActive = false
        if (preFlightStates.any { it != 0 }) {
            preFlightStates = List(PREFLIGHT_SOURCE_COUNT) { 0 }
            emit(RadioEvent.PreFlightCheck(preFlightStates))
        }
        true
    }

    override suspend fun setStickCalibration(active: Boolean): Boolean = op {
        if (!usable) return@op false
        calibrating = active
        true
    }

    override suspend fun setUsbSerialPassthrough(mode: Int): Boolean = op {
        if (!usable) return@op false
        usbSerialMode = mode
        true
    }

    override suspend fun rebootRfModule(): Boolean = op {
        if (!usable) return@op false
        rfRebootUntilMs = simTimeMs + RF_REBOOT_MS
        true
    }

    override suspend fun getFirmwareInfo(): FirmwareInfo? = op {
        if (usable) firmwareInfo ?: FirmwareInfo() else null
    }

    override suspend fun requestDeviceInfo(): Boolean = op {
        if (!usable) return@op false
        later(DEVICE_INFO_DELAY_MS) {
            firmwareInfo = buildFirmwareInfo()
            emit(RadioEvent.FirmwareInfoUpdated)
        }
        true
    }

    // ------------------------------------------------------------------------------------------
    // Models and templates
    // ------------------------------------------------------------------------------------------

    override suspend fun getModels(): List<ModelInfo> = op { if (usable) store.modelInfos() else emptyList() }

    override suspend fun getCurrentModelIndex(): Int? = op {
        if (usable) store.currentModel()?.index?.toInt() else null
    }

    override suspend fun createModelInfo(): ModelInfo? = op {
        if (!usable) return@op null
        val (index, path) = store.nextModel()
        ModelInfo(name = "Model $index", filePath = path, index = index).also { pendingInfos[File(path).path] = it }
    }

    override suspend fun updateModelInfo(model: ModelInfo): ModelInfo? = op {
        if (!usable) return@op null
        val existing = store.model(model.filePath) ?: return@op null
        val updated = existing.copy(
            name = model.name,
            image = model.image,
            modelMatch = model.modelMatch.coerceIn(0L, MODEL_MATCH_MAX),
        )
        if (!store.writeModel(model.filePath, updated)) return@op null
        updated.info(File(model.filePath).path)
    }

    override suspend fun saveWorkingConfigToModel(model: ModelInfo): Boolean = op {
        if (!usable || !store.isModelPath(model.filePath)) return@op false
        val existing = store.model(model.filePath)
        val index = existing?.index ?: modelIndexFor(model.filePath, model.index)
        val saved = store.writeModel(
            model.filePath,
            SimModelFile(
                name = model.name,
                image = model.image,
                index = index,
                modelMatch = model.modelMatch.coerceIn(0L, MODEL_MATCH_MAX),
                config = working,
            ),
        )
        if (saved) pendingInfos.remove(File(model.filePath).path)
        saved
    }

    override suspend fun loadModelFile(modelFile: String): Boolean = op {
        if (!usable) return@op false
        val model = store.model(modelFile) ?: return@op false
        store.setCurrent(modelFile)
        replaceWorking(model.config.withRadioSettingsOf(working.system))
        true
    }

    override suspend fun exportModel(modelFile: String, destinationFile: String): Boolean = op {
        if (!usable) return@op false
        val model = store.model(modelFile) ?: return@op false
        store.export(model, destinationFile)
    }

    override suspend fun importModel(importFile: String, targetModelFile: String): Boolean = op {
        if (!usable || !store.isModelPath(targetModelFile)) return@op false
        val imported = store.readForImport(importFile) ?: return@op false
        val existing = store.model(targetModelFile)
        val file = existing?.copy(config = imported.config) ?: SimModelFile(
            name = imported.name,
            image = imported.image,
            index = modelIndexFor(targetModelFile, 0),
            modelMatch = imported.modelMatch,
            config = imported.config,
        )
        val saved = store.writeModel(targetModelFile, file)
        if (saved) pendingInfos.remove(File(targetModelFile).path)
        saved
    }

    override suspend fun deleteModel(modelFile: String): Boolean = op {
        if (!usable) return@op false
        // The radio always flies a model: the current one cannot be deleted.
        if (File(modelFile).path == store.currentModelPath) return@op false
        store.deleteModel(modelFile)
    }

    override suspend fun getTemplates(): List<ModelInfo> = op { if (usable) store.templateInfos() else emptyList() }

    override suspend fun saveModelAsTemplate(modelFile: String, templateName: String): Boolean = op {
        if (!usable) return@op false
        val model = store.model(modelFile) ?: return@op false
        store.writeTemplate(templateName, model.config)
    }

    // ------------------------------------------------------------------------------------------
    // Telemetry list and SDK-side notifications
    // ------------------------------------------------------------------------------------------

    override suspend fun getTelemetryList(uiOnly: Boolean): List<TelemetryItem> = op {
        if (usable) telemetry.sdkItems(uiOnly) else emptyList()
    }

    override suspend fun getTelemetryAlarms(): List<TelemetryAlarm> = op {
        if (!usable) return@op emptyList()
        working.telemetryAlarms.mapIndexed { i, alarm -> telemetry.withLiveFields(alarm, i) }
    }

    override suspend fun getTelemetryAlarm(hash: Long, index: Int): TelemetryAlarm? = op {
        if (!usable) return@op null
        working.telemetryAlarms.getOrNull(index)?.let { telemetry.withLiveFields(it, index) }
    }

    override suspend fun setTelemetryAlarm(hash: Long, alarm: TelemetryAlarm, index: Int): Boolean = op {
        if (!usable || alarm.hash == 0L) return@op false
        val alarms = working.telemetryAlarms
        // Only what is configured is stored; value, texts and index are read-back fields.
        val stored = alarm.copy(index = -1, valueText = "", displayName = "", value = 0.0)
        val updated = if (hash == 0L) {
            if (alarms.size >= MAX_TELEMETRY_ALARMS) return@op false
            alarms + stored
        } else {
            if (index !in alarms.indices) return@op false
            alarms.with(index, stored)
        }
        telemetry.alarmsChanged()
        changeWorking(working.copy(telemetryAlarms = updated))
        true
    }

    override suspend fun deleteTelemetryAlarm(index: Int): Boolean = op {
        if (!usable || index !in working.telemetryAlarms.indices) return@op false
        telemetry.alarmsChanged()
        changeWorking(working.copy(telemetryAlarms = working.telemetryAlarms.filterIndexed { i, _ -> i != index }))
        true
    }

    override suspend fun pollTelemetryNotification(): TelemetryAlarm? = op {
        if (usable) telemetry.pollNotification() else null
    }

    // ------------------------------------------------------------------------------------------
    // ELRS and CRSF
    // ------------------------------------------------------------------------------------------

    override suspend fun requestElrsMenu(deviceAddress: Int): Boolean = op {
        if (!usable || menuBusy) return@op false
        elrs.beginRequest(deviceAddress)
        // With the RF module off, or for a device that does not exist, nobody answers.
        val (deviceName, items) = (if (rfOn) elrs.menu(deviceAddress, linkWasUp) else null) ?: return@op true
        menuBusy = true
        later(SimElrs.ITEM_DELAY_MS) {
            emit(RadioEvent.ElrsDeviceInfo(deviceName))
            for (item in items) {
                pause(SimElrs.ITEM_DELAY_MS)
                elrs.markDelivered(item.index)
                emit(RadioEvent.ElrsMenuItemReceived(item))
            }
            menuBusy = false
        }
        true
    }

    override suspend fun sendElrsMenuCommand(index: Int, value: Int): Boolean = op {
        if (!usable || menuBusy || !elrs.isDelivered(index)) return@op false
        val result = elrs.command(index, value) ?: return@op false
        store.updateStateLazily(store.state.copy(elrsValues = elrs.values()))
        later(0) {
            for ((i, step) in result.acks.withIndex()) {
                pause(step.first)
                if (i == 0 && result.item != null) emit(RadioEvent.ElrsMenuItemReceived(result.item))
                emit(RadioEvent.ElrsAcknowledged(step.second))
            }
        }
        true
    }

    override suspend fun requestElrsStatus(): Boolean = op {
        if (!usable || menuBusy) return@op false
        if (rfOn) {
            later(ELRS_STATUS_DELAY_MS) {
                val good = if (linkWasUp) elrs.packetRateHz else 0
                val bad = if (linkWasUp) (sequence % 3).toInt() else 0
                emit(RadioEvent.ElrsStatusReceived(ElrsStatus(badPackets = bad, goodPackets = good, modelMismatch = 0)))
            }
        }
        true
    }

    override suspend fun sendCrsfFrame(data: ByteArray): Boolean = op {
        if (!usable || data.isEmpty()) return@op false
        // The TX module answers a device ping like a real one, so that discovery code can be exercised.
        if (rfOn && CrsfEncoder.isValid(data) && (data[2].toInt() and 0xFF) == CrsfEncoder.TYPE_DEVICE_PING) {
            later(CRSF_ANSWER_DELAY_MS) {
                val parameters = SimElrs.txMenu().size
                emit(RadioEvent.CrsfReceived(CrsfEncoder.deviceInfo(SimElrs.TX_DEVICE_NAME, CrsfEncoder.ADDRESS_TX_MODULE, parameters)))
            }
        }
        true
    }

    // ------------------------------------------------------------------------------------------
    // The frame
    // ------------------------------------------------------------------------------------------

    /**
     * Produces one frame. [stepMs] null = paced by the clock (the 25 Hz loop); otherwise exactly
     * that much simulated time passes.
     */
    private fun tick(stepMs: Long?) {
        drainCommands()
        if (!usable) return
        val now = clock() + manualOffsetMs
        val dt = stepMs ?: if (lastTickAt < 0) options.tickMs else (now - lastTickAt).coerceIn(0L, MAX_STEP_MS)
        lastTickAt = now
        simTimeMs += dt

        inputs.applyDemo(simTimeMs)
        val logic = inputs.snapshot()
        val config = working
        timers.step(config, logic, dt, ::emit)
        val outputs = SimMixer.compute(config, logic, mixerState, dt)

        val linkUp = stepLink()
        stepTelemetry(config, logic, dt, linkUp)
        stepPreFlightCheck(config, logic)
        stepTxVoltage(config)
        if (linkUp && logic[Sources.THR] > THROTTLE_RUNNING) flightMs += dt

        val status = RadioStatus(
            linkQuality = if (linkUp) telemetry[SimSensor.TX_LINK_QUALITY].roundToInt() else 0,
            rssiDbm = if (linkUp) telemetry[SimSensor.TX_RSSI].roundToInt() else 0,
            lostCounter = if (linkUp) LINK_ALIVE_COUNTER else 0,
            txVoltage = centiVolts(telemetry[SimSensor.TX_BATTERY]),
            rxVoltage = lastRxVoltage,
            topKeyState = 0,
            calibrationState = if (calibrating) 1 else 0,
        )
        if (status != lastStatus) lastStatus = status

        sequence++
        lastLogic = logic
        lastOutputs = outputs
        val sources = config.sources
        _live.value = LiveSnapshot(
            sequence = sequence,
            timestampMs = now,
            sourceRaw = IntArray(logic.size) { logic[it] * RAW_HALF_SCALE / 100 },
            sourceLogic = logic,
            sourceOutput = IntArray(logic.size) { logic[it] * (sources.getOrNull(it)?.weight ?: 100) / 100 },
            channelOutput = IntArray(outputs.size) { outputs[it].roundToInt() },
            channelLogic = FloatArray(outputs.size) { outputs[it].toFloat() },
            trims = config.trims,
            status = lastStatus,
            timers = timers.timerStates,
            counters = timers.counterStates,
        )
    }

    /** The receiver link: up unless dropped by the panel, switched off with the RF module or rebooting. */
    private fun stepLink(): Boolean {
        val up = linkRequested && rfOn && simTimeMs >= rfRebootUntilMs
        if (up != linkWasUp) {
            linkWasUp = up
            emit(if (up) RadioEvent.TelemetryRecovered else RadioEvent.TelemetryLost)
        }
        if (_linkUp.value != up) _linkUp.value = up
        return up
    }

    private fun stepTelemetry(config: SimConfig, logic: IntArray, dt: Long, linkUp: Boolean) {
        telemetry.applyModule(elrs.rfMode, elrs.txPowerMw)
        if (inputs.demoMotion) {
            telemetry.stepFlight(dt, logic[Sources.THR], logic[Sources.AIL], logic[Sources.ELE])
        }
        if (linkUp) {
            telemetry.sendFrames(dt, ::emit)
            lastRxVoltage = centiVolts(telemetry[SimSensor.RX_BATTERY])
            alarmCheckDueMs -= dt
            if (alarmCheckDueMs <= 0) {
                alarmCheckDueMs = ALARM_CHECK_MS
                if (telemetry.checkAlarms(config.telemetryAlarms, ALARM_CHECK_MS)) emit(RadioEvent.TelemetryNotificationQueued)
            }
        }
        telemetryPublishDueMs -= dt
        if (telemetry.changed && (telemetryPublishDueMs <= 0 || telemetryPublishNow)) publishTelemetry()
    }

    private fun publishTelemetry() {
        _telemetry.value = telemetry.snapshot()
        telemetry.changed = false
        telemetryPublishNow = false
        telemetryPublishDueMs = TELEMETRY_PUBLISH_MS
    }

    /**
     * The pre-flight check as the firmware raises it after power-up: if a source is not at its
     * preset the alarm is announced and re-announced whenever the set of failing sources changes,
     * until everything is in place or the check is skipped.
     */
    private fun stepPreFlightCheck(config: SimConfig, logic: IntArray) {
        if (!preFlightArmed && !preFlightActive) return
        val states = List(PREFLIGHT_SOURCE_COUNT) { i ->
            val preset = config.system.preFlightCheck.getOrElse(i) { PREFLIGHT_NO_CHECK }
            val checked = preset != PREFLIGHT_NO_CHECK && config.sources.getOrNull(i)?.enabled == true
            if (checked && abs(logic.getOrElse(i) { 0 } - preset) > PREFLIGHT_TOLERANCE) 1 else 0
        }
        val failing = states.any { it != 0 }
        if (preFlightArmed) {
            preFlightArmed = false
            if (!failing) return
            preFlightActive = true
        } else if (states == preFlightStates) {
            return
        }
        preFlightStates = states
        if (!failing) preFlightActive = false
        emit(RadioEvent.PreFlightCheck(states))
    }

    private fun stepTxVoltage(config: SimConfig) {
        val threshold = config.system.alarmVoltage
        val low = threshold > 0f && telemetry[SimSensor.TX_BATTERY] < threshold
        if (low && (!lowVoltage || simTimeMs - lowVoltageAtMs >= LOW_VOLTAGE_REPEAT_MS)) {
            lowVoltageAtMs = simTimeMs
            emit(RadioEvent.LowVoltage)
        }
        lowVoltage = low
    }

    // ------------------------------------------------------------------------------------------
    // Simulator commands
    // ------------------------------------------------------------------------------------------

    private fun drainCommands() {
        val commands = inputs.drain()
        if (commands.isEmpty()) return
        for (command in commands) {
            when (command) {
                is SimCommand.Trim -> applyTrim(command.source, command.percent)
                is SimCommand.Telemetry -> if (telemetry.set(command.sensor, command.value)) telemetryPublishNow = true
                is SimCommand.FlightMode -> telemetry.flightModeText = command.text
                is SimCommand.Link -> linkRequested = command.up
                is SimCommand.Fault -> applyFault(command.fault)
                SimCommand.DemoRestarted -> telemetry.unpinAll()
                SimCommand.Reset -> {
                    telemetry.reset()
                    faults.clear()
                    linkRequested = true
                    rfRebootUntilMs = 0L
                    lowVoltage = false
                    telemetryPublishNow = true
                }
            }
        }
        if (telemetryPublishNow) publishTelemetry()
    }

    /** Trims are hardware-side: the radio keeps them itself, so they change in the working and the device config. */
    private fun applyTrim(source: Int, percent: Int) {
        if (!usable) return
        val mode = working.system.stickMode
        val value = percent.coerceIn(-100, 100)
        val trims = working.trims
        val (updated, code) = when (SourceId(source)) {
            mode.leftVertical -> trims.copy(leftVertical = value) to TRIM_LEFT_VERTICAL
            mode.leftHorizontal -> trims.copy(leftHorizontal = value) to TRIM_LEFT_HORIZONTAL
            mode.rightVertical -> trims.copy(rightVertical = value) to TRIM_RIGHT_VERTICAL
            mode.rightHorizontal -> trims.copy(rightHorizontal = value) to TRIM_RIGHT_HORIZONTAL
            else -> return
        }
        if (updated == trims) return
        working = working.copy(trims = updated)
        store.state.device?.let { store.updateStateLazily(store.state.copy(device = it.copy(trims = updated))) }
        emit(RadioEvent.TrimChanged(code))
    }

    private fun applyFault(fault: SimulatedFault) {
        when (fault) {
            SimulatedFault.SERIAL_PORT_BUSY,
            SimulatedFault.DEVICE_TIMEOUT,
            SimulatedFault.SAVE_TIMEOUT,
            SimulatedFault.READ_TIMEOUT,
            SimulatedFault.ADD_MIX_FAILS,
            -> faults += fault

            SimulatedFault.LOW_TX_VOLTAGE -> {
                val threshold = working.system.alarmVoltage.takeIf { it > 0f } ?: (SimDefaults.ALARM_VOLTAGE_DECIVOLT / 10f)
                telemetry.set(Sensors.TX_BATTERY, threshold - LOW_VOLTAGE_DROP)
                telemetryPublishNow = true
                lowVoltage = true
                lowVoltageAtMs = simTimeMs
                emit(RadioEvent.LowVoltage)
            }

            SimulatedFault.FIRMWARE_MISMATCH -> emit(RadioEvent.FirmwareVersionMismatch)

            SimulatedFault.PRE_FLIGHT_CHECK_FAILS -> {
                // A check that keeps failing whatever the inputs do, until it is skipped.
                preFlightArmed = false
                preFlightActive = false
                preFlightStates = List(PREFLIGHT_SOURCE_COUNT) { if (it == Sources.THR) 1 else 0 }
                emit(RadioEvent.PreFlightCheck(preFlightStates))
            }

            SimulatedFault.CONNECTION_LOST -> if (usable) {
                closeSession()
                _connection.value = ConnectionState.Error(ConnectionError.ConnectionLost())
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** Runs [block] on the serial worker, after the commands of the simulator panel were applied. */
    private suspend fun <T> op(block: suspend () -> T): T = withContext(worker) {
        drainCommands()
        block()
    }

    private val usable: Boolean
        get() = _connection.value.let { it is ConnectionState.Ready || it is ConnectionState.WaitingForDevice }

    private val ready: Boolean get() = _connection.value is ConnectionState.Ready

    private fun emit(event: RadioEvent) {
        _events.tryEmit(event)
    }

    private suspend fun pause(ms: Long) {
        if (options.realisticDelays && ms > 0) delay(ms)
    }

    /**
     * Runs [block] after [ms] in the current session (it is dropped when the radio stops or the
     * connection breaks). Without realistic delays it runs at once. Returns the job, or null if
     * the block already ran.
     */
    private suspend fun later(ms: Long, block: suspend () -> Unit): Job? {
        if (!options.realisticDelays) {
            block()
            return null
        }
        return session?.launch {
            delay(ms)
            block()
        }
    }

    /** Starts a device task that completes after [ms] and waits for it for at most [timeout]. */
    private suspend fun awaitTask(timeout: Duration, ms: Long, complete: suspend () -> Unit): TaskResult {
        val done = CompletableDeferred<Boolean>()
        val job = later(ms) {
            complete()
            done.complete(true)
        }
        if (job == null) {
            if (!done.isCompleted) done.complete(false)
        } else {
            // A task whose session ended will never complete: do not wait for the whole timeout.
            job.invokeOnCompletion { done.complete(false) }
        }
        return if (withTimeoutOrNull(timeout) { done.await() } == true) TaskResult.COMPLETED else TaskResult.TIMED_OUT
    }

    private fun fail(reason: ConnectionError): ConnectionState {
        closeSession()
        return ConnectionState.Error(reason).also { _connection.value = it }
    }

    private fun closeSession() {
        session?.cancel()
        session = null
        saving = false
        reading = false
        menuBusy = false
    }

    private fun persistState() {
        val total = store.state.flightTimeSec + flightMs / 1000L
        flightMs %= 1000L
        store.updateState(store.state.copy(flightTimeSec = total, elrsValues = elrs.values()))
    }

    /** What the SDK holds before the device answered: the current model file with the radio's own settings. */
    private fun localConfig(): SimConfig {
        val device = store.state.device ?: SimDefaults.factory()
        return store.currentModel()?.config?.withRadioSettingsOf(device.system) ?: device
    }

    /** `RC_MSGID_GETDEVCFG_END`: the device config becomes the working config and overwrites the current model file. */
    private fun completeDeviceRead() {
        val device = store.state.device ?: SimDefaults.factory()
        store.currentModelPath?.let { path ->
            store.model(path)?.let { if (it.config != device) store.writeModel(path, it.copy(config = device)) }
        }
        working = device
        rfOn = device.system.rfModuleMode != RfModuleMode.OFF
        mixerState.reset()
        telemetry.alarmsChanged()
        _configRevision.update { it + 1 }
        emit(RadioEvent.ConfigRead)
    }

    /** A setter changed the working config. */
    private fun changeWorking(config: SimConfig) {
        working = config
        _configRevision.update { it + 1 }
    }

    /** The whole working config was replaced (model loaded, defaults restored). */
    private fun replaceWorking(config: SimConfig) {
        working = config
        rfOn = config.system.rfModuleMode != RfModuleMode.OFF
        mixerState.reset()
        timers.restart(config)
        telemetry.alarmsChanged()
        _configRevision.update { it + 1 }
    }

    private fun timerList(isCounter: Boolean): List<TimerConfig> = if (isCounter) working.counters else working.timers

    private fun described(line: MixLine, count: Int): MixLine {
        val source = working.sources.getOrNull(line.source.index)
        return line.copy(
            sourceName = source?.name ?: line.source.defaultName,
            sourceType = source?.type ?: 0,
            mixCount = count,
        )
    }

    /** The index a model file at [path] gets: the one [createModelInfo] handed out for it, a free wish, or a new one. */
    private fun modelIndexFor(path: String, wanted: Long): Long {
        pendingInfos[File(path).path]?.let { return it.index }
        val taken = store.modelInfos().map { it.index }.toSet()
        if (wanted > 0 && wanted !in taken) {
            if (wanted > store.state.lastModelIndex) store.updateStateLazily(store.state.copy(lastModelIndex = wanted))
            return wanted
        }
        var index = maxOf(store.state.lastModelIndex, taken.maxOrNull() ?: 0L) + 1
        while (index in taken) index++
        store.updateStateLazily(store.state.copy(lastModelIndex = index))
        return index
    }

    private fun buildFirmwareInfo(): FirmwareInfo = FirmwareInfo(
        systemId = 0x41583132L,
        deviceId = 0x51AB0001L,
        flightTimeSec = store.state.flightTimeSec + flightMs / 1000L,
        buildDate = 260115L,
        firmwareVersion = 0x01020003L,
        hardwareVersion = 0x00000102L,
        deviceText = "AX12 Simulator",
        firmwareVersionText = "01.02.0003",
        buildTimeText = "Jan 15 2026 10:00:00",
        cachedFirmwareVersionText = "",
    )

    private fun isCurveReference(index: Int): Boolean = index == NO_CURVE || index in 0 until CURVE_COUNT

    /** A condition is acceptable if it is disabled or names an existing source. */
    private fun isCondition(condition: SwitchCondition): Boolean = !condition.isEnabled || condition.source.isValid

    private fun centiVolts(volts: Double): Float = (volts * 100.0).roundToInt() / 100f

    private fun <T> List<T>.with(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

    /** This model configuration with the settings that belong to the radio, not to a model, taken from [radio]. */
    private fun SimConfig.withRadioSettingsOf(radio: SystemConfig): SimConfig = copy(
        system = system.copy(
            stickMode = radio.stickMode,
            rfModuleMode = radio.rfModuleMode,
            alarmVoltageDeciVolt = radio.alarmVoltageDeciVolt,
        ),
    )

    // ------------------------------------------------------------------------------------------
    // SimulatorControl
    // ------------------------------------------------------------------------------------------

    private inner class Control : SimulatorControl {
        override val demoMotion: StateFlow<Boolean> = _demoMotion.asStateFlow()
        override val linkUp: StateFlow<Boolean> = _linkUp.asStateFlow()
        override val telemetry: StateFlow<Map<SensorId, Double>> = _telemetry.asStateFlow()

        override fun setDemoMotion(enabled: Boolean) {
            inputs.setDemoMotion(enabled)
            _demoMotion.value = enabled
        }

        override fun setSource(source: SourceId, percent: Int) = inputs.set(source, percent)

        override fun setStick(source: SourceId, percent: Int) {
            if (source.isStick) inputs.set(source, percent)
        }

        override fun setPot(source: SourceId, percent: Int) {
            if (source.isPot || source.isSlider) inputs.set(source, percent)
        }

        override fun setSwitch(source: SourceId, position: SwitchPosition) {
            if (source.isSwitch) inputs.set(source, position.logic)
        }

        override fun setTrim(source: SourceId, percent: Int) {
            if (source.isStick) inputs.post(SimCommand.Trim(source.index, percent))
        }

        override fun sourceValue(source: SourceId): Int =
            if (source.isCounter) _live.value.logic(source) else inputs.get(source)

        override fun setTelemetry(sensor: SensorId, value: Double) {
            val known = SimSensor.of(sensor) ?: return
            if (value.isNaN()) return
            inputs.post(SimCommand.Telemetry(sensor, value))
            // Shown at once; the next frame confirms it.
            _telemetry.update { it + (sensor to value.coerceIn(known.min, known.max)) }
        }

        override fun setTelemetryFlightMode(text: String) = inputs.post(SimCommand.FlightMode(text))

        override fun setTxVoltage(volts: Float) = setTelemetry(Sensors.TX_BATTERY, volts.toDouble())

        override fun setLinkUp(up: Boolean) {
            inputs.post(SimCommand.Link(up))
            _linkUp.value = up && rfOn
        }

        override fun injectFault(fault: SimulatedFault) = inputs.post(SimCommand.Fault(fault))

        override fun emitEvent(event: RadioEvent) = emit(event)

        override fun reset() {
            inputs.reset()
            _linkUp.value = rfOn
        }
    }

    companion object {
        /** Capacity of the event buffer; the oldest events are dropped when a collector is too slow. */
        const val EVENT_BUFFER = 512

        /** `rawVal` of a source at 100 %: half of the ±1024 full scale. */
        const val RAW_HALF_SCALE = 512

        /** Codes of [RadioEvent.TrimChanged]: which trim moved. */
        const val TRIM_LEFT_VERTICAL = 0
        const val TRIM_LEFT_HORIZONTAL = 1
        const val TRIM_RIGHT_VERTICAL = 2
        const val TRIM_RIGHT_HORIZONTAL = 3

        /** SDK-side telemetry notifications the simulator stores. */
        const val MAX_TELEMETRY_ALARMS = 32

        /** A source may be this far (in percent) from its preset and still pass the pre-flight check. */
        const val PREFLIGHT_TOLERANCE = 10

        /** Simulated durations in ms (skipped without realistic delays). */
        const val INIT_DELAY_MS = 150L
        const val DEVICE_READ_DELAY_MS = 350L
        const val DEVICE_WRITE_DELAY_MS = 450L
        const val DEVICE_TIMEOUT_MS = 5_000L
        const val DEVICE_INFO_DELAY_MS = 80L
        const val ELRS_STATUS_DELAY_MS = 60L
        const val CRSF_ANSWER_DELAY_MS = 40L

        /** How long the link is gone after `rebootRfModule()`, in simulated time. */
        const val RF_REBOOT_MS = 2_000L

        private const val WEIGHT_LIMIT = 500
        private const val SLOW_LIMIT_MS = 60_000
        private const val MODEL_MATCH_MAX = 0xFFFF_FFFFL
        private const val MAX_STEP_MS = 250L
        private const val ALARM_CHECK_MS = 200L
        private const val TELEMETRY_PUBLISH_MS = 250L
        private const val LOW_VOLTAGE_REPEAT_MS = 10_000L
        private const val LOW_VOLTAGE_DROP = 0.3
        private const val THROTTLE_RUNNING = -90
        private const val LINK_ALIVE_COUNTER = 10
    }
}
