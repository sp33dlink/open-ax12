package org.openax12.sdk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.FirmwareInfo
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.MixLine
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TelemetryAlarm
import org.openax12.core.model.TelemetryItem
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.Trims
import org.openax12.core.radio.ConnectionError
import org.openax12.core.radio.ConnectionState
import org.openax12.core.radio.Radio
import org.openax12.core.radio.RadioEvent
import org.openax12.core.radio.SimulatorControl
import org.openax12.core.radio.TaskResult

/**
 * Everything [SdkRadio] needs from the outside world besides the native layer; replaced in the
 * unit tests.
 *
 * @property loadError why the native library could not be loaded, or null
 * @property prepareDataDirectory creates the SDK data directory and returns its absolute path, or null
 * @property clock monotonic milliseconds (`SystemClock.elapsedRealtime()`)
 * @property probePort best-effort look at the serial port after a silent start
 * @property fileExists true if the path is an existing regular file
 */
internal class SdkEnvironment(
    val loadError: Throwable?,
    val prepareDataDirectory: () -> String?,
    val clock: () -> Long,
    val probePort: () -> PortProbeResult,
    val fileExists: (String) -> Boolean,
    val info: (String) -> Unit = {},
    val warn: (String, Throwable?) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "SdkRadio"

        fun forDevice(context: Context, loadError: Throwable?): SdkEnvironment = SdkEnvironment(
            loadError = loadError,
            prepareDataDirectory = {
                val directory = File(context.filesDir, SdkAssumptions.DATA_DIRECTORY_NAME)
                if (directory.isDirectory || directory.mkdirs()) directory.absolutePath else null
            },
            clock = SystemClock::elapsedRealtime,
            probePort = { SerialPortProbe.forDevice().probe() },
            fileExists = { File(it).isFile },
            info = { Log.i(TAG, it) },
            warn = { message, error -> Log.w(TAG, message, error) },
        )
    }
}

/**
 * The real radio backend: [Radio] on top of the prebuilt `rmAxSdk1` library through the JNI
 * bridge `libopenax12jni.so`.
 *
 * - **Loading:** the native libraries are loaded when the first `SdkRadio` is constructed, never
 *   earlier (the simulator backend must work where they cannot load). A load failure is reported
 *   by [start] as [ConnectionError.NativeLibraryUnavailable].
 * - **Threading:** every SDK call runs on one dedicated thread (`ax12-sdk`), in call order. The
 *   SDK message callback (SDK output thread) only enqueues: live updates are conflated, all other
 *   messages go through a bounded queue to the same thread, where they are decoded and published.
 * - **Live data:** after `RC_MSGID_MIXOUT_UPDATE` sources, channels, trims and status are fetched
 *   with one JNI call into preallocated buffers and published as a [LiveSnapshot].
 * - **Safety:** every index and array length is validated against the lists the SDK returned
 *   before a call is forwarded, every setter overlays onto a fresh native read
 *   ([SdkMapping]), and the firmware-update / app-update functions are not bound at all.
 * - **Unverified behaviour** is collected in [SdkAssumptions].
 *
 * @param context any context; only the application context is kept
 * @param scope application scope; the message pump and the start procedure run in it
 * @param dispatchers unused: the backend brings its own serial dispatcher (see [Radio] "Threading")
 */
class SdkRadio internal constructor(
    private val native: NativeApi,
    private val scope: CoroutineScope,
    private val sdkDispatcher: CoroutineDispatcher,
    private val environment: SdkEnvironment,
) : Radio {

    constructor(
        context: Context,
        scope: CoroutineScope,
        @Suppress("UNUSED_PARAMETER") dispatchers: AppDispatchers = AppDispatchers(),
    ) : this(
        native = SdkNative,
        scope = scope,
        sdkDispatcher = sharedSdkDispatcher,
        environment = SdkEnvironment.forDevice(context.applicationContext ?: context, SdkNative.ensureLoaded()),
    )

    // ------------------------------------------------------------------------------------------
    // Public state
    // ------------------------------------------------------------------------------------------

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _live = MutableStateFlow(LiveSnapshot.EMPTY)
    override val live: StateFlow<LiveSnapshot> = _live.asStateFlow()

    private val _events = MutableSharedFlow<RadioEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<RadioEvent> = _events.asSharedFlow()

    private val _configRevision = MutableStateFlow(0L)
    override val configRevision: StateFlow<Long> = _configRevision.asStateFlow()

    /** `version()` is the one SDK function that may be called before `initialize()` and off the SDK thread. */
    override val sdkVersion: String = readSdkVersion()

    override val isSimulator: Boolean get() = false

    override val simulator: SimulatorControl? get() = null

    // ------------------------------------------------------------------------------------------
    // Message intake (written from the SDK output thread)
    // ------------------------------------------------------------------------------------------

    private val telemetryChangePending = AtomicBoolean(false)

    private val inbox = Channel<RawMessage>(
        capacity = INBOX_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { if (it.msgId == SdkMsg.TELEMETRY_UPDATED) telemetryChangePending.set(false) },
    )

    private val liveSignal = Channel<Unit>(Channel.CONFLATED)

    /** Runs on the SDK output thread: enqueue and return, nothing else. */
    private val sink = NativeMessageSink { msgId, ints, doubles, bytes, strings ->
        when (msgId) {
            // About every 40 ms; only the fact matters, so it is conflated.
            SdkMsg.MIXOUT_UPDATE -> liveSignal.trySend(Unit)
            // Can arrive with every telemetry frame; one queued notice is enough.
            SdkMsg.TELEMETRY_UPDATED ->
                if (telemetryChangePending.compareAndSet(false, true)) inbox.trySend(RawMessage(msgId, null, null, null, null))
            else -> inbox.trySend(RawMessage(msgId, ints, doubles, bytes, strings))
        }
    }

    // ------------------------------------------------------------------------------------------
    // State confined to the SDK thread
    // ------------------------------------------------------------------------------------------

    private class TaskSlot {
        var pending: CompletableDeferred<Boolean>? = null
    }

    private var sdkInitialized = false
    private var startJob: Deferred<ConnectionState>? = null
    private var pumpJob: Job? = null
    private var pumpScope: CoroutineScope? = null

    private val readSlot = TaskSlot()
    private val writeSlot = TaskSlot()

    private var liveSeen = false
    private var firmwareMismatchSeen = false
    private var lastLiveAtMs = 0L
    private var readyAtMs = 0L
    private var lastTaskEndAtMs = 0L

    private val liveInts = IntArray(NativeLayout.LIVE_INT_SIZE)
    private val liveFloats = FloatArray(NativeLayout.LIVE_FLOAT_SIZE)
    private val liveDecoder = LiveFrameDecoder()
    private val timerTracker = TimerTracker()

    private val countsBuffer = IntArray(NativeLayout.COUNTS_SIZE)
    private var cachedCounts: SdkCounts? = null
    private var cachedCountsAtMs = 0L

    private val elrsMenu = ElrsMenuTracker()
    private var deviceInfoRequestedAtMs = NEVER

    private val notifications = ArrayDeque<TelemetryAlarm>()
    private var lastTelemetryEventAtMs = NEVER
    private var telemetryTrailingJob: Job? = null
    private var telemetryListAll: List<TelemetryItem>? = null
    private var telemetryListAllAtMs = NEVER
    private var telemetryListUi: List<TelemetryItem>? = null
    private var telemetryListUiAtMs = NEVER

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    override suspend fun start(): ConnectionState {
        val running: Deferred<ConnectionState>? = withContext(sdkDispatcher) {
            val active = startJob?.takeIf { it.isActive }
            when {
                active != null -> active
                _connection.value is ConnectionState.Stopped || _connection.value is ConnectionState.Error ->
                    // Owned by the radio, not by the caller: cancelling the caller only stops the waiting.
                    scope.async(sdkDispatcher) { runStartGuarded() }.also { startJob = it }
                else -> null
            }
        }
        return running?.await() ?: _connection.value
    }

    override suspend fun stop() {
        withContext(sdkDispatcher + NonCancellable) {
            startJob?.let { if (it.isActive) it.cancelAndJoin() }
            startJob = null
            shutdownSdk()
            stopPump()
            _connection.value = ConnectionState.Stopped
            _live.value = LiveSnapshot.EMPTY
        }
    }

    /**
     * [runStart] with the "never throws" guarantee of [Radio]: an unexpected failure (for example a
     * native function that cannot be bound) ends in [ConnectionState.Error] instead of failing the
     * deferred, which would rethrow in [start] and cancel a scope without a supervisor.
     */
    private suspend fun runStartGuarded(): ConnectionState = try {
        runStart()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        environment.warn("start failed unexpectedly", t)
        try {
            shutdownSdk()
        } catch (shutdownFailure: Throwable) {
            environment.warn("shutdown after a failed start threw", shutdownFailure)
        }
        fail(ConnectionError.Unknown("Start failed: ${t.message ?: t.javaClass.simpleName}"))
    }

    private suspend fun runStart(): ConnectionState {
        environment.loadError?.let {
            return fail(ConnectionError.NativeLibraryUnavailable("SDK library could not be loaded: ${it.message ?: it.javaClass.simpleName}"))
        }
        // A retry after an error starts from scratch.
        if (sdkInitialized) shutdownSdk()
        _connection.value = ConnectionState.Initializing
        resetSession()

        val dataPath = try {
            environment.prepareDataDirectory()
        } catch (e: Exception) {
            environment.warn("data directory", e)
            null
        } ?: return fail(ConnectionError.Unknown("The SDK data directory cannot be created"))

        native.setMessageSink(sink)
        startPump()
        val code = try {
            native.initialize(dataPath)
        } catch (t: Throwable) {
            environment.warn("initialize threw", t)
            SdkInitCode.NATIVE_EXCEPTION
        }
        if (!SdkInitCode.isSuccess(code)) {
            val error = initializationError(code)
            shutdownSdk()
            return fail(error)
        }
        sdkInitialized = true
        environment.info("SDK initialized (code $code), reading the device configuration")
        _connection.value = ConnectionState.WaitingForDevice

        val done = CompletableDeferred<Boolean>()
        readSlot.pending = done
        val completed = try {
            native.readConfigFromDevice()
            awaitInitialRead(done)
        } finally {
            if (readSlot.pending === done) readSlot.pending = null
        }
        if (completed) return _connection.value

        val error = classifySilentStart()
        environment.info("start failed: ${error.message}")
        shutdownSdk()
        return fail(error)
    }

    /** Waits for the first `RC_MSGID_GETDEVCFG_END`, repeating the request periodically. */
    private suspend fun awaitInitialRead(done: CompletableDeferred<Boolean>): Boolean {
        val retryAfter = SdkAssumptions.INITIAL_DEVICE_READ_RETRY_AFTER_MS
        val total = SdkAssumptions.INITIAL_DEVICE_READ_TIMEOUT_MS
        var waited = 0L
        while (waited < total) {
            withTimeoutOrNull(retryAfter) { done.await() }?.let { return it }
            waited += retryAfter
            environment.info("no device configuration after $waited ms, repeating the request")
            native.readConfigFromDevice()
        }
        return false
    }

    private fun fail(error: ConnectionError): ConnectionState =
        ConnectionState.Error(error).also { _connection.value = it }

    private fun initializationError(code: Int): ConnectionError = when (code) {
        SdkInitCode.THREAD_START_FAILED -> ConnectionError.ThreadStartFailed()
        SdkInitCode.COMMUNICATION_INIT_FAILED -> {
            val probe = probePort()
            if (probe.diagnosis == PortDiagnosis.BUSY) {
                ConnectionError.SerialPortBusy("Serial port is in use by another app (${probe.detail})")
            } else {
                ConnectionError.CommunicationInitFailed("SDK communication init failed (${probe.detail})")
            }
        }
        SdkInitCode.CALLBACK_REJECTED -> ConnectionError.Unknown("The SDK rejected the message callback")
        SdkInitCode.BAD_ARGUMENT -> ConnectionError.Unknown("The SDK data path is not usable")
        SdkInitCode.NATIVE_EXCEPTION -> ConnectionError.Unknown("The SDK bridge failed during initialize")
        else -> ConnectionError.Unknown("SDK initialize returned $code")
    }

    /**
     * Why the initial device read did not complete. Without any sign of life the serial port is
     * the suspect, and the usual reason is the stock app holding it (assumptions 1 and 24).
     */
    private fun classifySilentStart(): ConnectionError {
        val seconds = SdkAssumptions.INITIAL_DEVICE_READ_TIMEOUT_MS / 1000
        if (firmwareMismatchSeen) return ConnectionError.FirmwareMismatch()
        if (liveSeen) {
            return ConnectionError.DeviceTimeout("The radio sends live data but did not deliver its configuration within $seconds s")
        }
        val probe = probePort()
        return when (probe.diagnosis) {
            PortDiagnosis.OPEN_BY_US ->
                ConnectionError.DeviceTimeout("The serial port is open but the radio did not answer within $seconds s (${probe.detail})")
            PortDiagnosis.BUSY -> ConnectionError.SerialPortBusy("Serial port is in use by another app (${probe.detail})")
            PortDiagnosis.UNAVAILABLE -> ConnectionError.SerialPortUnavailable("Serial port not available (${probe.detail})")
            PortDiagnosis.INCONCLUSIVE -> ConnectionError.SerialPortBusy(
                "No data from the radio within $seconds s; the serial port is probably held by another app (${probe.detail})",
            )
        }
    }

    private fun probePort(): PortProbeResult = try {
        environment.probePort()
    } catch (e: Exception) {
        PortProbeResult(PortDiagnosis.INCONCLUSIVE, "probe failed: ${e.message}")
    }

    /**
     * Shutdown in the order of SDK manual 7.3: (1) no further business calls — they all run on
     * this thread and are refused from here on; (2) clear the callback; (3) drop the messages
     * still queued on our side; (4) nothing else is held (the bridge copies every payload);
     * (5) `shutdown()`.
     */
    private fun shutdownSdk() {
        sdkInitialized = false
        abortTask(readSlot)
        abortTask(writeSlot)
        try {
            native.clearCallback()
        } catch (t: Throwable) {
            environment.warn("clearCallback threw", t)
        }
        native.setMessageSink(null)
        drainQueues()
        try {
            native.shutdown()
        } catch (t: Throwable) {
            environment.warn("shutdown threw", t)
        }
        // Anything an in-flight callback still managed to enqueue belongs to the dead session.
        drainQueues()
    }

    private fun abortTask(slot: TaskSlot) {
        slot.pending?.complete(false)
        slot.pending = null
    }

    private fun drainQueues() {
        while (inbox.tryReceive().isSuccess) Unit
        while (liveSignal.tryReceive().isSuccess) Unit
        telemetryChangePending.set(false)
    }

    private fun resetSession() {
        drainQueues()
        liveSeen = false
        firmwareMismatchSeen = false
        lastLiveAtMs = 0L
        readyAtMs = 0L
        lastTaskEndAtMs = 0L
        cachedCounts = null
        timerTracker.reset()
        elrsMenu.reset()
        deviceInfoRequestedAtMs = NEVER
        notifications.clear()
        lastTelemetryEventAtMs = NEVER
        telemetryListAll = null
        telemetryListUi = null
    }

    // ------------------------------------------------------------------------------------------
    // Message pump (SDK thread)
    // ------------------------------------------------------------------------------------------

    private fun startPump() {
        if (pumpJob?.isActive == true) return
        val job = SupervisorJob(scope.coroutineContext[Job])
        val pump = CoroutineScope(scope.coroutineContext + job + sdkDispatcher)
        pumpJob = job
        pumpScope = pump
        pump.launch { for (message in inbox) guarded("message") { handleMessage(message) } }
        pump.launch { for (signal in liveSignal) guarded("live frame") { handleLiveSignal() } }
        pump.launch {
            while (true) {
                delay(SdkAssumptions.WATCHDOG_PERIOD_MS)
                guarded("watchdog") { watchdog() }
            }
        }
    }

    private fun stopPump() {
        pumpJob?.cancel()
        pumpJob = null
        pumpScope = null
        telemetryTrailingJob = null
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            environment.warn("$what failed", t)
        }
    }

    private fun emit(event: RadioEvent) {
        _events.tryEmit(event)
    }

    private fun handleMessage(raw: RawMessage) {
        if (raw.msgId == SdkMsg.TELEMETRY_UPDATED) telemetryChangePending.set(false)
        if (!sdkInitialized) return
        val message = SdkMessageDecoder.decode(raw) ?: return
        val now = environment.clock()
        when (message) {
            SdkMessage.LiveUpdated -> handleLiveSignal()

            SdkMessage.DeviceConfigRead -> {
                configChanged()
                val state = _connection.value
                if (state is ConnectionState.WaitingForDevice || isConnectionLost(state)) {
                    readyAtMs = now
                    _connection.value = ConnectionState.Ready
                }
                lastTaskEndAtMs = now
                emit(RadioEvent.ConfigRead)
                readSlot.pending?.complete(true)
            }

            SdkMessage.DeviceConfigWritten -> {
                lastTaskEndAtMs = now
                emit(RadioEvent.ConfigWritten)
                writeSlot.pending?.complete(true)
            }

            SdkMessage.TelemetryNotifyQueued -> drainTelemetryNotifications()

            SdkMessage.TelemetryListChanged -> emitTelemetryUpdated(now)

            SdkMessage.FirmwareMismatch -> {
                firmwareMismatchSeen = true
                emit(RadioEvent.FirmwareVersionMismatch)
            }

            SdkMessage.FirmwareInfoUpdated -> {
                deviceInfoRequestedAtMs = NEVER
                emit(RadioEvent.FirmwareInfoUpdated)
            }

            is SdkMessage.TimerReported -> {
                timerTracker.onReport(message.report, message.isCounter, now)
                emit(if (message.isCounter) RadioEvent.CounterReported(message.report) else RadioEvent.TimerReported(message.report))
            }

            is SdkMessage.TimerNotified -> {
                timerTracker.onNotify(message.notify, now)
                emit(RadioEvent.TimerNotified(message.notify))
            }

            is SdkMessage.ElrsMenuItemReceived -> {
                elrsMenu.onItem(message.item.index, now)
                emit(RadioEvent.ElrsMenuItemReceived(message.item))
            }

            is SdkMessage.Event -> emit(message.event)
        }
    }

    private fun isConnectionLost(state: ConnectionState): Boolean =
        state is ConnectionState.Error && state.reason is ConnectionError.ConnectionLost

    /** One JNI call for sources, channels, trims and status; publishes the frame. */
    private fun handleLiveSignal() {
        if (!sdkInitialized) return
        val state = _connection.value
        val lost = isConnectionLost(state)
        if (!(state is ConnectionState.Ready || state is ConnectionState.WaitingForDevice || lost)) return
        if (!native.readLive(liveInts, liveFloats)) return
        val now = environment.clock()
        timerTracker.refresh(now)
        val snapshot = liveDecoder.decode(liveInts, liveFloats, now, timerTracker.timers, timerTracker.counters) ?: return
        lastLiveAtMs = now
        liveSeen = true
        _live.value = snapshot
        if (lost) {
            environment.info("live data is back, connection restored")
            readyAtMs = now
            _connection.value = ConnectionState.Ready
        }
    }

    /** A running connection without live frames for too long is reported as lost. */
    private fun watchdog() {
        if (!sdkInitialized || _connection.value !is ConnectionState.Ready) return
        if (readSlot.pending != null || writeSlot.pending != null) return
        val now = environment.clock()
        val lastSignOfLife = maxOf(lastLiveAtMs, readyAtMs, lastTaskEndAtMs)
        if (now - lastSignOfLife > SdkAssumptions.LIVE_LOST_TIMEOUT_MS) {
            environment.info("no live data for ${now - lastSignOfLife} ms, connection lost")
            _connection.value = ConnectionState.Error(ConnectionError.ConnectionLost())
        }
    }

    /** Empties the SDK's queue of triggered notifications into our own bounded buffer (manual 18.3). */
    private fun drainTelemetryNotifications() {
        var taken = 0
        while (taken < SdkAssumptions.TELEMETRY_NOTIFY_DRAIN_LIMIT) {
            val raw = native.pollTelemetryNotify() ?: break
            if (notifications.size >= SdkAssumptions.TELEMETRY_NOTIFY_BUFFER) notifications.removeFirst()
            notifications.addLast(SdkMapping.telemetryAlarm(raw))
            taken++
        }
        if (notifications.isNotEmpty()) emit(RadioEvent.TelemetryNotificationQueued)
    }

    /** At most one `TelemetryUpdated` per interval, and the last change is never swallowed. */
    private fun emitTelemetryUpdated(now: Long) {
        val interval = SdkAssumptions.TELEMETRY_UPDATED_EVENT_INTERVAL_MS
        if (lastTelemetryEventAtMs == NEVER || now - lastTelemetryEventAtMs >= interval) {
            lastTelemetryEventAtMs = now
            emit(RadioEvent.TelemetryUpdated)
            return
        }
        if (telemetryTrailingJob?.isActive == true) return
        val wait = interval - (now - lastTelemetryEventAtMs)
        telemetryTrailingJob = pumpScope?.launch {
            delay(wait)
            if (sdkInitialized) {
                lastTelemetryEventAtMs = environment.clock()
                emit(RadioEvent.TelemetryUpdated)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Call plumbing
    // ------------------------------------------------------------------------------------------

    /** True while calls may be forwarded: the SDK runs and the state is WaitingForDevice or Ready. */
    private fun usable(): Boolean {
        if (!sdkInitialized) return false
        val state = _connection.value
        return state is ConnectionState.Ready || state is ConnectionState.WaitingForDevice
    }

    /**
     * Runs [block] on the SDK thread if the radio is usable, otherwise returns [fallback]. A
     * failure inside the native layer also yields [fallback]; nothing is thrown to the caller.
     */
    private suspend fun <T> onSdk(fallback: T, block: () -> T): T = withContext(sdkDispatcher) {
        if (!usable()) {
            fallback
        } else {
            try {
                block()
            } catch (t: Throwable) {
                environment.warn("SDK call failed", t)
                fallback
            }
        }
    }

    /** The SDK list sizes indices are validated against; re-read after every change. */
    private fun counts(): SdkCounts {
        val now = environment.clock()
        cachedCounts?.let { if (now - cachedCountsAtMs in 0 until SdkAssumptions.COUNTS_MAX_AGE_MS) return it }
        val fresh = if (native.readCounts(countsBuffer)) SdkCounts.decode(countsBuffer) else SdkCounts.EMPTY
        cachedCounts = fresh
        cachedCountsAtMs = now
        return fresh
    }

    /** The working config may differ from what callers read before. */
    private fun configChanged() {
        cachedCounts = null
        _configRevision.value += 1
    }

    private suspend fun runDeviceTask(slot: TaskSlot, timeout: Duration, send: () -> Unit): TaskResult =
        withContext(sdkDispatcher) {
            if (!usable()) return@withContext TaskResult.NOT_READY
            if (slot.pending != null) return@withContext TaskResult.BUSY
            val done = CompletableDeferred<Boolean>()
            slot.pending = done
            try {
                try {
                    send()
                } catch (t: Throwable) {
                    environment.warn("device task could not be started", t)
                    return@withContext TaskResult.NOT_READY
                }
                val completed = withTimeoutOrNull(timeout) { done.await() }
                if (completed == true) TaskResult.COMPLETED else TaskResult.TIMED_OUT
            } finally {
                if (slot.pending === done) slot.pending = null
                lastTaskEndAtMs = environment.clock()
            }
        }

    private fun readSdkVersion(): String {
        if (environment.loadError != null) return "unavailable"
        return try {
            // The native function can return null although the declaration says otherwise.
            (native.version() as String?)?.takeIf { it.isNotBlank() } ?: "unknown"
        } catch (t: Throwable) {
            environment.warn("version failed", t)
            "unknown"
        }
    }

    // ------------------------------------------------------------------------------------------
    // Device configuration tasks
    // ------------------------------------------------------------------------------------------

    override suspend fun readConfigFromDevice(timeout: Duration): TaskResult =
        runDeviceTask(readSlot, timeout) { native.readConfigFromDevice() }

    override suspend fun saveConfigToDevice(timeout: Duration): TaskResult =
        runDeviceTask(writeSlot, timeout) { native.saveConfigToDevice() }

    override suspend fun resetToDefaults(): Boolean = onSdk(false) {
        native.resetDefault()
        // The re-read the SDK triggers is announced by GETDEVCFG_END (another revision, ConfigRead).
        configChanged()
        true
    }

    // ------------------------------------------------------------------------------------------
    // Inputs, outputs, trims, status
    // ------------------------------------------------------------------------------------------

    override suspend fun getSources(): List<SourceInfo> =
        onSdk(emptyList()) { native.getSources()?.let(SdkMapping::sources).orEmpty() }

    override suspend fun getOutputChannels(): List<OutputChannelConfig> =
        onSdk(emptyList()) { native.getChannels()?.let(SdkMapping::outputChannels).orEmpty() }

    override suspend fun getTrims(): Trims? =
        onSdk(null) { if (native.readLive(liveInts, liveFloats)) liveDecoder.trims(liveInts) else null }

    override suspend fun getRadioStatus(): RadioStatus? =
        onSdk(null) { if (native.readLive(liveInts, liveFloats)) liveDecoder.status(liveInts, liveFloats) else null }

    override suspend fun setOutputChannel(config: OutputChannelConfig): Boolean = onSdk(false) { writeOutputChannel(config) }

    private fun writeOutputChannel(config: OutputChannelConfig): Boolean {
        val counts = counts()
        if (!counts.isChannel(config.channel)) return false
        val fresh = native.getChannels() ?: return false
        val raw = SdkMapping.findChannel(fresh, config.channel) ?: return false
        if (!SdkMapping.overlayChannel(raw, config, counts)) return false
        if (!native.setChannel(raw)) return false
        configChanged()
        return true
    }

    // ------------------------------------------------------------------------------------------
    // Mixes
    // ------------------------------------------------------------------------------------------

    override suspend fun getMix(channel: Int, mixIndex: Int): MixLine? = onSdk(null) {
        if (!counts().isMix(channel, mixIndex)) null else native.getMix(channel, mixIndex)?.let { SdkMapping.mixLine(it, channel, mixIndex) }
    }

    override suspend fun setMix(mix: MixLine): Boolean = onSdk(false) { writeMix(mix) }

    private fun writeMix(mix: MixLine): Boolean {
        val counts = counts()
        if (!counts.isMix(mix.channel, mix.mixIndex)) return false
        val raw = native.getMix(mix.channel, mix.mixIndex) ?: return false
        // One mix per source on a channel (assumption 5): a changed source must not collide.
        if (mix.source.index != raw.srcIdx && findMixOfSource(mix.channel, mix.source.index, counts.mixCount(mix.channel)) >= 0) {
            return false
        }
        if (!SdkMapping.overlayMix(raw, mix, counts)) return false
        if (!native.setMix(raw)) return false
        configChanged()
        return true
    }

    /** Index of the mix of [source] among the first [mixCount] lines of [channel], or -1. */
    private fun findMixOfSource(channel: Int, source: Int, mixCount: Int): Int {
        for (index in 0 until mixCount) {
            if (native.getMix(channel, index)?.srcIdx == source) return index
        }
        return -1
    }

    override suspend fun addMix(channel: Int, source: SourceId): MixLine? = onSdk(null) { createMix(channel, source.index) }

    private fun createMix(channel: Int, source: Int): MixLine? {
        val before = counts()
        if (!before.isChannel(channel) || !before.isSource(source)) return null
        val countBefore = before.mixCount(channel)
        if (findMixOfSource(channel, source, countBefore) >= 0) return null
        val returned = native.addMix(channel, source)
        cachedCounts = null
        // The SDK reports failure only through a default struct: trust the lists, not the result.
        var countAfter = counts().mixCount(channel)
        if (countAfter <= countBefore && returned != null && returned.mixesCount == countBefore + 1) countAfter = countBefore + 1
        if (countAfter <= countBefore) return null
        // The SDK did add a line: the working config changed even if reading it back fails below.
        configChanged()
        val index = findMixOfSource(channel, source, countAfter)
        if (index < 0) return null
        val raw = native.getMix(channel, index) ?: return null
        return SdkMapping.mixLine(raw, channel, index)
    }

    override suspend fun deleteMix(channel: Int, source: SourceId): Int? = onSdk(null) { removeMix(channel, source.index) }

    private fun removeMix(channel: Int, source: Int): Int? {
        val counts = counts()
        if (!counts.isChannel(channel) || !counts.isSource(source)) return null
        if (findMixOfSource(channel, source, counts.mixCount(channel)) < 0) return null
        val remaining = native.delMix(channel, source)
        cachedCounts = null
        if (remaining < 0) return null
        configChanged()
        return remaining
    }

    // ------------------------------------------------------------------------------------------
    // Dual rates
    // ------------------------------------------------------------------------------------------

    override suspend fun getDualRate(channel: Int, slot: Int): DualRateSlot? = onSdk(null) {
        if (!counts().isChannel(channel) || !SdkMapping.isDualRateSlot(slot)) {
            null
        } else {
            native.getDr(channel, slot)?.let { SdkMapping.dualRate(it, channel, slot) }
        }
    }

    override suspend fun findFreeDualRateSlot(channel: Int): DualRateSlot? = onSdk(null) { askForFreeDualRateSlot(channel) }

    private fun askForFreeDualRateSlot(channel: Int): DualRateSlot? {
        if (!counts().isChannel(channel)) return null
        val proposal = native.getDr(channel, -1) ?: return null
        // A failed lookup comes back zero-initialized: accept only a consistent answer (assumption 18).
        if (proposal.chIdx != channel || !SdkMapping.isDualRateSlot(proposal.idx)) return null
        val slot = native.getDr(channel, proposal.idx) ?: return null
        if (slot.swCmpType != 0) return null
        return SdkMapping.dualRate(slot, channel, proposal.idx)
    }

    override suspend fun setDualRate(slot: DualRateSlot): Int? = onSdk(null) { writeDualRate(slot) }

    private fun writeDualRate(slot: DualRateSlot): Int? {
        val counts = counts()
        if (!counts.isChannel(slot.channel) || !SdkMapping.isDualRateSlot(slot.slot)) return null
        val raw = native.getDr(slot.channel, slot.slot) ?: return null
        if (!SdkMapping.overlayDualRate(raw, slot, counts)) return null
        val inUse = native.setDr(raw)
        if (inUse < 0) return null
        configChanged()
        return inUse
    }

    override suspend fun deleteDualRate(slot: DualRateSlot): Boolean = onSdk(false) {
        if (!counts().isChannel(slot.channel) || !SdkMapping.isDualRateSlot(slot.slot)) {
            false
        } else {
            val raw = native.getDr(slot.channel, slot.slot)
            if (raw == null) {
                false
            } else {
                raw.chIdx = slot.channel
                raw.idx = slot.slot
                // A no-op in the current SDK; kept for completeness (see the Radio KDoc).
                val accepted = native.delDr(raw) >= 0
                if (accepted) configChanged()
                accepted
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Curves
    // ------------------------------------------------------------------------------------------

    override suspend fun getCurves(): List<CurveConfig> =
        onSdk(emptyList()) { native.getCurves(-1)?.let(SdkMapping::curves).orEmpty() }

    override suspend fun getCurve(index: Int): CurveConfig? = onSdk(null) { readCurve(index)?.let { SdkMapping.curve(it, index) } }

    /** Fresh native struct of curve [index] (validated), or null. */
    private fun readCurve(index: Int): RawCurve? {
        if (!counts().isCurve(index)) return null
        val result = native.getCurves(index) ?: return null
        // Documented: exactly that curve. Tolerate an implementation that returns the whole list.
        return when (result.size) {
            0 -> null
            1 -> result[0]
            else -> result.firstOrNull { it.idx == index } ?: result.getOrNull(index)
        }
    }

    override suspend fun setCurve(curve: CurveConfig): Boolean = onSdk(false) {
        val raw = readCurve(curve.index)
        if (raw == null || !SdkMapping.overlayCurve(raw, curve) || native.setCurve(raw) != 0) {
            false
        } else {
            configChanged()
            true
        }
    }

    override suspend fun applyCurve(curveIndex: Int, value: Double): Double = onSdk(value) {
        if (curveIndex == NO_CURVE || !value.isFinite() || !counts().isCurve(curveIndex)) value else native.curveApply(curveIndex, value)
    }

    // ------------------------------------------------------------------------------------------
    // Timers and counters
    // ------------------------------------------------------------------------------------------

    private fun isTimerIndex(index: Int, isCounter: Boolean): Boolean =
        index in 0 until (if (isCounter) COUNTER_COUNT else TIMER_COUNT)

    override suspend fun getTimerConfig(index: Int, isCounter: Boolean): TimerConfig? = onSdk(null) {
        if (!isTimerIndex(index, isCounter)) {
            null
        } else {
            val (timerIdx, counterFlag) = SdkAssumptions.timerArguments(index, isCounter)
            native.getTimer(timerIdx, counterFlag)?.let { SdkMapping.timerConfig(it, index) }
        }
    }

    override suspend fun setTimerConfig(config: TimerConfig, isCounter: Boolean): Boolean = onSdk(false) { writeTimer(config, isCounter) }

    private fun writeTimer(config: TimerConfig, isCounter: Boolean): Boolean {
        if (!isTimerIndex(config.index, isCounter)) return false
        val (timerIdx, counterFlag) = SdkAssumptions.timerArguments(config.index, isCounter)
        val raw = native.getTimer(timerIdx, counterFlag) ?: return false
        if (!SdkMapping.overlayTimer(raw, config, timerIdx, isCounter, counts())) return false
        if (!native.setTimer(raw, counterFlag)) return false
        configChanged()
        return true
    }

    override suspend fun resetTimer(deviceIndex: Int): Boolean = onSdk(false) {
        if (deviceIndex !in SdkAssumptions.RESET_TIMER_DEVICE_INDICES) {
            false
        } else {
            native.resetTimer(deviceIndex)
            true
        }
    }

    // ------------------------------------------------------------------------------------------
    // System configuration and device control
    // ------------------------------------------------------------------------------------------

    override suspend fun getSystemConfig(): SystemConfig? = onSdk(null) { native.getSysConfig()?.let(SdkMapping::systemConfig) }

    override suspend fun setSystemConfig(config: SystemConfig): Boolean = onSdk(false) {
        val raw = native.getSysConfig()
        if (raw == null || !SdkMapping.overlaySystemConfig(raw, config) || !native.setSysConfig(raw)) {
            false
        } else {
            configChanged()
            true
        }
    }

    override suspend fun skipPreFlightCheck(): Boolean = onSdk(false) {
        native.skipPreFlightCheck()
        true
    }

    override suspend fun setStickCalibration(active: Boolean): Boolean = onSdk(false) {
        native.setAdcCalibration(if (active) 1 else 0)
        true
    }

    override suspend fun setUsbSerialPassthrough(mode: Int): Boolean = onSdk(false) {
        if (mode !in SdkAssumptions.USB_PASSTHROUGH_MODES) {
            false
        } else {
            native.setUsbToVcp(mode)
            true
        }
    }

    override suspend fun rebootRfModule(): Boolean = onSdk(false) {
        native.rebootRfModule()
        true
    }

    override suspend fun getFirmwareInfo(): FirmwareInfo? = onSdk(null) { native.getFwInfo()?.let(SdkMapping::firmwareInfo) }

    override suspend fun requestDeviceInfo(): Boolean = onSdk(false) {
        val now = environment.clock()
        val pending = deviceInfoRequestedAtMs != NEVER && now - deviceInfoRequestedAtMs < SdkAssumptions.DEVICE_INFO_TIMEOUT_MS
        if (!pending) {
            // Completed by RC_MSGID_FWINF_UPDATED; a request without answer expires by itself.
            deviceInfoRequestedAtMs = now
            native.requestDeviceInfo()
        }
        true
    }

    // ------------------------------------------------------------------------------------------
    // Models and templates
    // ------------------------------------------------------------------------------------------

    override suspend fun getModels(): List<ModelInfo> =
        onSdk(emptyList()) { native.getModels()?.map(SdkMapping::modelInfo).orEmpty() }

    override suspend fun getCurrentModelIndex(): Int? = onSdk(null) { native.getCurrentModelIndex().takeIf { it >= 0 } }

    override suspend fun createModelInfo(): ModelInfo? = onSdk(null) {
        native.createModel()?.takeIf { it.filePath.isNotBlank() }?.let(SdkMapping::modelInfo)
    }

    override suspend fun updateModelInfo(model: ModelInfo): ModelInfo? = onSdk(null) {
        val raw = SdkMapping.rawModel(model)
        if (raw == null || !environment.fileExists(model.filePath)) {
            null
        } else {
            val updated = native.setModel(raw)?.takeIf { it.filePath.isNotBlank() }?.let(SdkMapping::modelInfo)
            // "Loads the target file" (manual 17.2): do not trust list sizes read before (assumption s2).
            cachedCounts = null
            updated
        }
    }

    override suspend fun saveWorkingConfigToModel(model: ModelInfo): Boolean = onSdk(false) {
        val raw = SdkMapping.rawModel(model)
        raw != null && native.saveCurrentCfgToModel(raw)
    }

    override suspend fun loadModelFile(modelFile: String): Boolean = onSdk(false) {
        if (!SdkMapping.isUsablePath(modelFile) || !environment.fileExists(modelFile) || !native.loadModelFile(modelFile)) {
            false
        } else {
            configChanged()
            true
        }
    }

    override suspend fun exportModel(modelFile: String, destinationFile: String): Boolean = onSdk(false) {
        SdkMapping.isUsablePath(modelFile) && SdkMapping.isUsablePath(destinationFile) && modelFile != destinationFile &&
            environment.fileExists(modelFile) && native.exportModel(modelFile, destinationFile)
    }

    override suspend fun importModel(importFile: String, targetModelFile: String): Boolean = onSdk(false) {
        val imported = SdkMapping.isUsablePath(importFile) && SdkMapping.isUsablePath(targetModelFile) && importFile != targetModelFile &&
            environment.fileExists(importFile) && native.importModel(importFile, targetModelFile) == 0
        // The target may be the model the working config was loaded from (assumption s2).
        if (imported) cachedCounts = null
        imported
    }

    override suspend fun deleteModel(modelFile: String): Boolean = onSdk(false) {
        // Only files the SDK itself lists as models are ever deleted.
        SdkMapping.isUsablePath(modelFile) && native.getModels().orEmpty().any { it.filePath == modelFile } &&
            native.deleteModel(modelFile)
    }

    override suspend fun getTemplates(): List<ModelInfo> =
        onSdk(emptyList()) { native.getTemplates()?.map(SdkMapping::modelInfo).orEmpty() }

    override suspend fun saveModelAsTemplate(modelFile: String, templateName: String): Boolean = onSdk(false) {
        SdkMapping.isUsablePath(modelFile) && SdkMapping.isUsableName(templateName) && environment.fileExists(modelFile) &&
            native.modelToTemplate(modelFile, templateName)
    }

    // ------------------------------------------------------------------------------------------
    // Telemetry list and SDK-side notifications
    // ------------------------------------------------------------------------------------------

    override suspend fun getTelemetryList(uiOnly: Boolean): List<TelemetryItem> = onSdk(emptyList()) {
        val now = environment.clock()
        val cached = if (uiOnly) telemetryListUi else telemetryListAll
        val cachedAt = if (uiOnly) telemetryListUiAtMs else telemetryListAllAtMs
        if (cached != null && cachedAt != NEVER && now - cachedAt in 0 until SdkAssumptions.TELEMETRY_LIST_MIN_INTERVAL_MS) {
            cached
        } else {
            val fresh = native.getTelemetryList(if (uiOnly) 1 else 0)?.map(SdkMapping::telemetryItem).orEmpty()
            if (uiOnly) {
                telemetryListUi = fresh
                telemetryListUiAtMs = now
            } else {
                telemetryListAll = fresh
                telemetryListAllAtMs = now
            }
            fresh
        }
    }

    override suspend fun getTelemetryAlarms(): List<TelemetryAlarm> = onSdk(emptyList()) {
        // `index` is what set / delete send back to the SDK and what `Radio.updateTelemetryAlarm`
        // checks against the list: the position in the list, not the reported `idx` (assumption s3).
        native.getNotifyCfgList().orEmpty().mapIndexed { position, raw -> SdkMapping.telemetryAlarm(raw).copy(index = position) }
    }

    override suspend fun getTelemetryAlarm(hash: Long, index: Int): TelemetryAlarm? = onSdk(null) {
        if (index !in 0 until native.getNotifyCfgCount()) {
            null
        } else {
            native.getNotifyCfgItem(hash, index)?.let { SdkMapping.telemetryAlarm(it).copy(index = index) }
        }
    }

    override suspend fun setTelemetryAlarm(hash: Long, alarm: TelemetryAlarm, index: Int): Boolean =
        onSdk(false) { writeTelemetryAlarm(hash, alarm, index) }

    private fun writeTelemetryAlarm(hash: Long, alarm: TelemetryAlarm, index: Int): Boolean {
        if (alarm.hash == 0L) return false
        val adding = hash == 0L
        val raw: RawTelemetry
        val sdkIndex: Int
        if (adding) {
            // Nothing to read for a new notification: start from a blank struct.
            raw = RawTelemetry().apply { idx = -1 }
            sdkIndex = -1
        } else {
            if (index !in 0 until native.getNotifyCfgCount()) return false
            raw = native.getNotifyCfgItem(hash, index) ?: return false
            sdkIndex = index
        }
        if (!SdkMapping.overlayTelemetryAlarm(raw, alarm)) return false
        if (!native.setNotifyCfgItem(hash, raw, sdkIndex)) return false
        configChanged()
        return true
    }

    override suspend fun deleteTelemetryAlarm(index: Int): Boolean = onSdk(false) {
        if (index !in 0 until native.getNotifyCfgCount()) {
            false
        } else {
            native.delNotifyItem(index)
            configChanged()
            true
        }
    }

    override suspend fun pollTelemetryNotification(): TelemetryAlarm? = onSdk(null) {
        notifications.removeFirstOrNull() ?: native.pollTelemetryNotify()?.let(SdkMapping::telemetryAlarm)
    }

    // ------------------------------------------------------------------------------------------
    // ELRS and CRSF
    // ------------------------------------------------------------------------------------------

    override suspend fun requestElrsMenu(deviceAddress: Int): Boolean = onSdk(false) {
        if (deviceAddress !in SdkAssumptions.CRSF_ADDRESS_RANGE) {
            false
        } else {
            val now = environment.clock()
            // The SDK ignores a request while one is in progress; forwarding it would only make
            // us forget the items that are being delivered right now.
            if (!elrsMenu.isRequestOpen(now)) {
                elrsMenu.onRequest(now)
                native.requestElrsMenu(deviceAddress)
            }
            true
        }
    }

    override suspend fun sendElrsMenuCommand(index: Int, value: Int): Boolean = onSdk(false) {
        if (!elrsMenu.isKnown(index)) {
            false
        } else {
            native.elrsSendMenuCmd(index, value)
            true
        }
    }

    override suspend fun requestElrsStatus(): Boolean = onSdk(false) { native.requestElrsStatus() == 0 }

    override suspend fun sendCrsfFrame(data: ByteArray): Boolean = onSdk(false) {
        data.size in 1..SdkAssumptions.CRSF_MAX_FRAME_BYTES && native.sendCrsf(data.copyOf())
    }

    private companion object {
        /** Capacity of `events`; the oldest events are dropped for slow collectors. */
        const val EVENT_BUFFER = 512

        /** Messages waiting for the SDK thread; the oldest are dropped if it is stuck. */
        const val INBOX_CAPACITY = 2048

        /** "No timestamp yet". */
        const val NEVER = Long.MIN_VALUE

        /**
         * The one thread all SDK calls of the process run on, shared by every instance (the SDK is
         * a process-wide singleton).
         */
        val sharedSdkDispatcher: CoroutineDispatcher by lazy {
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ax12-sdk").apply { isDaemon = true } }
                .asCoroutineDispatcher()
        }
    }
}
