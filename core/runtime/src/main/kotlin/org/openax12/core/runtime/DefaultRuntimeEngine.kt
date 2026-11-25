package org.openax12.core.runtime

import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openax12.core.audio.AudioEngine
import org.openax12.core.audio.SpokenText
import org.openax12.core.data.DataResult
import org.openax12.core.data.FlightLogState
import org.openax12.core.data.FlightLogger
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.SettingsRepository
import org.openax12.core.data.TelemetryRepository
import org.openax12.core.engine.ActionCommand
import org.openax12.core.engine.AlarmEvaluator
import org.openax12.core.engine.AlarmEvent
import org.openax12.core.engine.AlarmEventKind
import org.openax12.core.engine.EngineEvent
import org.openax12.core.engine.EngineTick
import org.openax12.core.engine.EngineValueResolver
import org.openax12.core.engine.LogicalSwitchEvaluator
import org.openax12.core.engine.RenderMode
import org.openax12.core.engine.SpecialFunctionEvaluator
import org.openax12.core.engine.TemplateRenderer
import org.openax12.core.engine.ValueFormatter
import org.openax12.core.engine.ValueResolver
import org.openax12.core.engine.VarioMath
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.CountdownStyle
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.SensorAlarm
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.SpecialAction
import org.openax12.core.model.SpecialFunction
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TimerState
import org.openax12.core.model.Trims
import org.openax12.core.model.VarioConfig
import org.openax12.core.model.VarioTone
import org.openax12.core.model.VoiceAlert
import org.openax12.core.radio.Radio
import org.openax12.core.radio.RadioEvent

/**
 * The production [RuntimeEngine].
 *
 * ## Threading
 * One coroutine collects `Radio.live` and runs [process] for every frame while holding [lock];
 * everything that touches the evaluators, the per-tick memory or executes actions (previews,
 * manual resets) takes the same lock, so the not-thread-safe evaluators of `:core:engine` are
 * only ever used by one coroutine at a time. State that other threads read ([valueResolver],
 * alarm acknowledgement, pre-flight check) is kept in state flows, volatile fields or under a
 * small monitor.
 *
 * ## Configuration
 * The extras are read from `ModelRepository.currentExtras` (the working copy, so staged edits
 * take effect at once) on every frame; a change of the instance reconfigures the evaluators
 * (unchanged items keep their state), a change of the current model id resets all state and
 * counts as a start-up (`EngineTick.startup`), which also announces the model name.
 *
 * ## Sensor values
 * Triggers, logical switches and alarms use `TelemetryRepository.sensors` (throttled to a few
 * updates per second); the vario reads `TelemetryRepository.value` directly for low latency.
 *
 * ## Radio events
 * `Radio.events` are subscribed to in the constructor, before [start] and before the radio is
 * started (the pre-flight check alarm arrives right after power-up and has no replay).
 *
 * @param sourceNames user names of the input sources for `{name}` lookups and switch announcements
 */
class DefaultRuntimeEngine(
    private val radio: Radio,
    private val models: ModelRepository,
    private val telemetry: TelemetryRepository,
    private val settings: SettingsRepository,
    private val audio: AudioEngine,
    private val flightLogger: FlightLogger,
    private val diagnostics: DiagnosticsLog,
    private val phrases: RuntimePhrases,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val sourceNames: () -> Map<SourceId, String> = { emptyMap() },
) : RuntimeEngine {

    private val _running = MutableStateFlow(false)
    private val _flightMode = MutableStateFlow(ActiveFlightMode())
    private val _logicalSwitches = MutableStateFlow(LogicalSwitchStates.NONE)
    private val _activeSpecialFunctions = MutableStateFlow<Set<Int>>(emptySet())
    private val _activeAlarms = MutableStateFlow<List<ActiveAlarm>>(emptyList())
    private val _varioActive = MutableStateFlow(false)
    private val _brightness = MutableStateFlow<Int?>(null)
    private val _preFlightCheck = MutableStateFlow<List<Int>?>(null)
    private val _uiRequests = MutableSharedFlow<RuntimeUiRequest>(
        replay = 0,
        extraBufferCapacity = UI_REQUEST_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val running: StateFlow<Boolean> = _running.asStateFlow()
    override val flightMode: StateFlow<ActiveFlightMode> = _flightMode.asStateFlow()
    override val logicalSwitches: StateFlow<LogicalSwitchStates> = _logicalSwitches.asStateFlow()
    override val activeSpecialFunctions: StateFlow<Set<Int>> = _activeSpecialFunctions.asStateFlow()
    override val activeAlarms: StateFlow<List<ActiveAlarm>> = _activeAlarms.asStateFlow()
    override val varioActive: StateFlow<Boolean> = _varioActive.asStateFlow()
    override val lastSpoken: StateFlow<SpokenText?> get() = audio.lastSpoken
    override val brightnessOverride: StateFlow<Int?> = _brightness.asStateFlow()
    override val preFlightCheck: StateFlow<List<Int>?> = _preFlightCheck.asStateFlow()
    override val uiRequests: SharedFlow<RuntimeUiRequest> = _uiRequests.asSharedFlow()

    // ---- evaluation state: guarded by [lock] ----------------------------------------------------

    private val lock = Mutex()
    private val speechFormatter = ValueFormatter { value, _ ->
        if (value.unit == SensorUnit.SECOND && value.isNumeric) audio.spokenValue(value.number, SensorUnit.SECOND, 0) else null
    }
    private val logicalEvaluator = LogicalSwitchEvaluator()
    private val functionEvaluator = SpecialFunctionEvaluator(speechFormatter = speechFormatter)
    private val alarmEvaluator = AlarmEvaluator(speechFormatter)
    private val altitudeDerivative = VarioMath.AltitudeDerivative()

    private var appliedExtras: ModelExtras? = null
    private var appliedModelId: String? = null
    private var needsReset = true
    private var lastNowMs = 0L
    private var announceModel = false

    private var previousFlightMode = -1
    private val previousPositions = arrayOfNulls<SwitchPosition>(SWITCH_COUNT)
    private var previousTrims = Trims.ZERO
    private val timerMemory = Array(TIMER_COUNT) { TimerMemory() }
    private val publishedLogicalSwitches = HashSet<Int>()
    private var publishedFlightMode: ActiveFlightMode? = null

    private var functionVario = false
    private var varioOn = false
    private var lastVarioTone: VarioTone? = null
    private var lastAltitudeTimestamp = Long.MIN_VALUE
    private var derivedSpeed: Double? = null

    private var functionLogging = false
    private var loggingDesired = false
    private val missingSoundsReported = HashSet<String>()
    private var lastTickError: String? = null

    // ---- shared with other threads --------------------------------------------------------------

    private val pendingEvents = ArrayList<RadioEvent>()
    private val alarmState = Any()
    private val raisedAlarms = LinkedHashMap<String, ActiveAlarm>()
    private val acknowledgedAlarms = HashSet<String>()

    @Volatile
    private var snapshot: Snapshot? = null

    private val loggingRequests = Channel<Boolean>(Channel.CONFLATED)
    private val lifecycleLock = Any()
    private var loops: List<Job> = emptyList()
    private var previewVarioJob: Job? = null

    init {
        // Retained state must not depend on anyone collecting later: subscribe right now.
        scope.launch(dispatchers.default, start = CoroutineStart.UNDISPATCHED) {
            radio.events.collect(::onRadioEvent)
        }
        scope.launch(dispatchers.default) { loggingWorker() }
        scope.launch(dispatchers.default) { loggerStateWatcher() }
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    override fun start() {
        synchronized(lifecycleLock) {
            if (!_running.compareAndSet(expect = false, update = true)) return
            needsReset = true
            loops = listOf(
                scope.launch(dispatchers.default) {
                    radio.live.collect { live ->
                        try {
                            lock.withLock { process(live) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            reportTickError(e)
                        }
                    }
                },
            )
        }
    }

    override fun stop() {
        val toCancel = synchronized(lifecycleLock) {
            if (!_running.compareAndSet(expect = true, update = false)) return
            loops.also { loops = emptyList() }
        }
        toCancel.forEach { it.cancel() }
        silence()
        // A tick that was already running may still have touched the audio engine: silence again once it is out.
        scope.launch(dispatchers.default) {
            toCancel.forEach { it.join() }
            silence()
        }
    }

    /** Switches off everything continuous and clears the published state (the evaluators reset at the next start). */
    private fun silence() {
        previewVarioJob?.cancel()
        audio.stopVario()
        audio.stopMusic()
        audio.setVolumeOverride(null)
        synchronized(alarmState) {
            raisedAlarms.clear()
            acknowledgedAlarms.clear()
        }
        _activeAlarms.value = emptyList()
        _activeSpecialFunctions.value = emptySet()
        _varioActive.value = false
        _brightness.value = null
        loggingRequests.trySend(false)
    }

    // ---- radio events (not under the lock) --------------------------------------------------------

    private fun onRadioEvent(event: RadioEvent) {
        when (event) {
            is RadioEvent.PreFlightCheck -> {
                _preFlightCheck.value = event.states.takeIf { event.hasFailures }
                if (event.hasFailures) _uiRequests.tryEmit(RuntimeUiRequest.PreFlightCheck(event.states))
            }
            is RadioEvent.TimerNotified,
            RadioEvent.TelemetryLost,
            RadioEvent.TelemetryRecovered,
            RadioEvent.LowVoltage,
            is RadioEvent.TrimChanged,
            -> if (_running.value) synchronized(pendingEvents) { pendingEvents += event }
            else -> Unit
        }
    }

    private fun drainEvents(): List<RadioEvent> = synchronized(pendingEvents) {
        if (pendingEvents.isEmpty()) emptyList() else pendingEvents.toList().also { pendingEvents.clear() }
    }

    // ---- the tick -------------------------------------------------------------------------------

    private suspend fun process(live: LiveSnapshot) {
        if (live.isEmpty || !_running.value) return
        val extras = models.currentExtras.value
        val model = models.currentModel.value
        val modelName = model?.name.orEmpty()

        var startup = false
        if (needsReset || model?.id != appliedModelId) {
            resetEvaluators()
            appliedModelId = model?.id
            needsReset = false
            startup = true
            announceModel = true
        }
        if (extras !== appliedExtras) {
            applyExtras(extras)
            appliedExtras = extras
        }

        val now = max(lastNowMs, live.timestampMs)
        lastNowMs = now
        val flightIndex = extras.flightModes.activeModeIndex(live)
        val flightName = extras.flightModeName(flightIndex)
        val radioEvents = drainEvents()
        val engineEvents = radioEvents.mapNotNull(::toEngineEvent)

        val units = settings.settings.value.units
        val infos = telemetry.sensorList.value
        val names = sourceNames()
        val base = EngineTick(
            nowMs = now,
            live = live,
            sensors = telemetry.sensors.value,
            flightMode = flightIndex,
            flightModeName = flightName,
            logicalSwitches = logicalEvaluator.states,
            modelName = modelName,
            linkUp = live.status.isLinkUp,
            events = engineEvents,
            startup = startup,
        )
        val switches = logicalEvaluator.evaluate(base, EngineValueResolver(base, extras, infos, names, units))
        val tick = base.withLogicalSwitches(switches)
        val resolver = EngineValueResolver(tick, extras, infos, names, units)
        snapshot = Snapshot(tick, extras)

        publishFlightMode(flightIndex, flightName)
        publishLogicalSwitches(switches, extras)

        execute(functionEvaluator.evaluate(tick, resolver), resolver)
        _activeSpecialFunctions.value = functionEvaluator.activeFunctions
        handleAlarms(alarmEvaluator.evaluate(tick, resolver), resolver, tick.nowMs)

        updateVario(extras.vario, now)
        announce(extras, tick, names, radioEvents)
        updateLogging(live)
    }

    private fun toEngineEvent(event: RadioEvent): EngineEvent? = when (event) {
        is RadioEvent.TimerNotified -> EngineEvent.TimerNotified(event.notify)
        RadioEvent.TelemetryLost -> EngineEvent.TelemetryLost
        RadioEvent.TelemetryRecovered -> EngineEvent.TelemetryRecovered
        RadioEvent.LowVoltage -> EngineEvent.LowTxVoltage
        else -> null
    }

    private fun reportTickError(e: Exception) {
        val message = "${e::class.simpleName}: ${e.message}"
        // A failing tick would repeat 25 times a second: report each distinct problem once.
        if (message != lastTickError) {
            lastTickError = message
            diagnostics.record(DiagnosticsLog.SOURCE_RUNTIME, "evaluation failed: $message")
        }
    }

    /** Forgets all evaluation state (model change or restart). Runs under the lock. */
    private suspend fun resetEvaluators() {
        val stopCommands = functionEvaluator.reset()
        logicalEvaluator.reset()
        alarmEvaluator.reset()
        appliedExtras = null
        synchronized(alarmState) {
            raisedAlarms.clear()
            acknowledgedAlarms.clear()
        }
        _activeAlarms.value = emptyList()
        _logicalSwitches.value = LogicalSwitchStates.NONE
        _activeSpecialFunctions.value = emptySet()
        publishedLogicalSwitches.clear()
        publishedFlightMode = null
        previousFlightMode = -1
        previousPositions.fill(null)
        timerMemory.forEach { it.initialized = false }
        missingSoundsReported.clear()
        lastAltitudeTimestamp = Long.MIN_VALUE
        derivedSpeed = null
        altitudeDerivative.reset()
        execute(stopCommands, null)
    }

    private suspend fun applyExtras(extras: ModelExtras) {
        logicalEvaluator.update(extras.logicalSwitches)
        execute(functionEvaluator.update(extras.specialFunctions, extras.voiceAlerts), null)
        alarmEvaluator.update(extras.alarms)
    }

    // ---- published state ------------------------------------------------------------------------

    private fun publishFlightMode(index: Int, name: String) {
        val active = ActiveFlightMode(index, name)
        _flightMode.value = active
        if (active == publishedFlightMode) return
        publishedFlightMode = active
        telemetry.publish(
            Sensors.FLIGHT_MODE,
            SensorValue(value = index.toDouble(), text = name.ifBlank { (index + 1).toString() }, unit = SensorUnit.TEXT),
        )
    }

    private fun publishLogicalSwitches(states: LogicalSwitchStates, extras: ModelExtras) {
        val previous = _logicalSwitches.value
        _logicalSwitches.value = states
        for (switch in extras.logicalSwitches) {
            if (!switch.isUsed) continue
            val index = switch.index
            if (publishedLogicalSwitches.add(index) || states[index] != previous[index]) {
                telemetry.publish(SensorId.logicalSwitch(index), SensorValue(value = if (states[index]) 1.0 else 0.0))
            }
        }
    }

    // ---- alarms ---------------------------------------------------------------------------------

    private fun handleAlarms(events: List<AlarmEvent>, resolver: ValueResolver, nowMs: Long) {
        if (events.isEmpty()) return
        for (event in events) {
            val alarm = event.alarm
            when (event.kind) {
                AlarmEventKind.RAISED -> {
                    synchronized(alarmState) {
                        acknowledgedAlarms -= alarm.id
                        raisedAlarms.remove(alarm.id)
                        raisedAlarms[alarm.id] = ActiveAlarm(alarm, event.value, nowMs)
                    }
                    fireAlarm(event, resolver, popup = true)
                }
                AlarmEventKind.REPEATED -> {
                    val silenced = synchronized(alarmState) {
                        raisedAlarms[alarm.id]?.let { raisedAlarms[alarm.id] = it.copy(value = event.value) }
                        alarm.id in acknowledgedAlarms
                    }
                    if (!silenced) fireAlarm(event, resolver, popup = false)
                }
                AlarmEventKind.CLEARED -> synchronized(alarmState) {
                    raisedAlarms.remove(alarm.id)
                    acknowledgedAlarms -= alarm.id
                }
            }
        }
        publishAlarms()
    }

    private fun publishAlarms() {
        _activeAlarms.value = synchronized(alarmState) {
            raisedAlarms.values.filter { it.alarm.id !in acknowledgedAlarms }
        }
    }

    private fun fireAlarm(event: AlarmEvent, resolver: ValueResolver, popup: Boolean) {
        val alarm = event.alarm
        val outputs = alarm.outputs
        if (event.voiceText.isNotBlank()) audio.speak(event.voiceText, alarm.priority, AudioCategory.ALARM)
        outputs.beep?.let { audio.beep(it, AudioCategory.ALARM) }
        outputs.haptic?.let(audio::haptic)
        if (outputs.soundFile.isNotBlank() && !audio.playSound(outputs.soundFile, alarm.priority, AudioCategory.ALARM)) {
            soundMissing(outputs.soundFile)
        }
        if (popup && outputs.popup) _uiRequests.tryEmit(RuntimeUiRequest.AlarmPopup(alarm, popupText(alarm, resolver)))
    }

    private fun popupText(alarm: SensorAlarm, resolver: ValueResolver): String {
        val text = TemplateRenderer.render(alarm.outputs.voiceText, resolver, RenderMode.DISPLAY).trim()
        if (text.isNotEmpty()) return text
        if (alarm.name.isNotBlank()) return alarm.name
        return resolver.resolve(alarm.sensor)?.name ?: alarm.sensor.key
    }

    override fun acknowledgeAlarms() {
        synchronized(alarmState) { acknowledgedAlarms += raisedAlarms.keys }
        publishAlarms()
        audio.flush(AudioCategory.ALARM)
    }

    // ---- vario ----------------------------------------------------------------------------------

    private fun updateVario(config: VarioConfig, nowMs: Long) {
        val wanted = config.alwaysOn || functionVario
        if (wanted != varioOn) {
            varioOn = wanted
            _varioActive.value = wanted
            if (!wanted) {
                audio.stopVario()
                lastVarioTone = null
                altitudeDerivative.reset()
                lastAltitudeTimestamp = Long.MIN_VALUE
                derivedSpeed = null
            }
        }
        if (!wanted) return
        val speed = verticalSpeed(config)
        val tone = if (speed == null) VarioTone.SILENT else VarioMath.tone(speed, config)
        if (tone != lastVarioTone) {
            lastVarioTone = tone
            audio.setVarioTone(tone)
        }
    }

    /** The vertical speed in m/s the vario should sound for, or null while there is no usable value. */
    private fun verticalSpeed(config: VarioConfig): Double? {
        val speedSensor = VarioMath.speedSensor(config)
        if (speedSensor != null) return usableValue(telemetry.value(speedSensor))
        val altitude = telemetry.value(VarioMath.altitudeSensor(config))
        val value = usableValue(altitude) ?: return null
        if (altitude != null && altitude.timestampMs != lastAltitudeTimestamp) {
            lastAltitudeTimestamp = altitude.timestampMs
            derivedSpeed = altitudeDerivative.update(value, altitude.timestampMs)
        }
        return derivedSpeed
    }

    private fun usableValue(value: SensorValue?): Double? =
        value?.takeIf { it.hasValue && !it.lost }?.value

    override fun previewVario(verticalSpeed: Double) {
        previewVarioJob?.cancel()
        previewVarioJob = scope.launch(dispatchers.default) {
            audio.setVarioTone(VarioMath.tone(verticalSpeed, models.currentExtras.value.vario))
            delay(VARIO_PREVIEW_MS)
            if (!varioOn) audio.stopVario()
        }
    }

    // ---- built-in announcements -----------------------------------------------------------------

    private fun announce(extras: ModelExtras, tick: EngineTick, names: Map<SourceId, String>, radioEvents: List<RadioEvent>) {
        val announcements = extras.announcements
        val live = tick.live

        for (event in radioEvents) {
            when (event) {
                RadioEvent.TelemetryLost -> {
                    if (announcements.telemetryLost) audio.speak(phrases.telemetryLost, SpeechPriority.HIGH)
                    _uiRequests.tryEmit(RuntimeUiRequest.Message(RuntimeMessages.TELEMETRY_LOST))
                }
                RadioEvent.TelemetryRecovered -> {
                    if (announcements.telemetryRecovered) audio.speak(phrases.telemetryRecovered, SpeechPriority.HIGH)
                    _uiRequests.tryEmit(RuntimeUiRequest.Message(RuntimeMessages.TELEMETRY_RECOVERED))
                }
                RadioEvent.LowVoltage -> {
                    if (announcements.lowTxVoltage) {
                        audio.speak(phrases.lowTxVoltage, SpeechPriority.ALARM, AudioCategory.ALARM)
                        audio.beep(BeepPattern.WARNING, AudioCategory.ALARM)
                    }
                    val volts = live.status.txVoltage
                    _uiRequests.tryEmit(RuntimeUiRequest.Message(RuntimeMessages.LOW_TX_VOLTAGE, "%.1f".format(java.util.Locale.ROOT, volts)))
                }
                else -> Unit
            }
        }

        // Model name once after a model was loaded or the runtime started.
        if (announceModel) {
            announceModel = false
            if (announcements.modelName && tick.modelName.isNotBlank()) audio.speak(tick.modelName, SpeechPriority.NORMAL)
        }

        // Flight mode changes.
        val index = tick.flightMode
        if (!tick.startup && previousFlightMode >= 0 && index != previousFlightMode) {
            val mode = extras.flightModes.modes.getOrNull(index)
            if (announcements.flightModes && extras.flightModes.isActive && mode?.announce == true && mode.name.isNotBlank()) {
                audio.speak(mode.name, SpeechPriority.HIGH)
            }
        }
        previousFlightMode = index

        // Switch positions.
        for (i in 0 until SWITCH_COUNT) {
            val source = SourceId(FIRST_SWITCH + i)
            val position = live.switchPosition(source)
            val before = previousPositions[i]
            previousPositions[i] = position
            if (announcements.switchPositions && !tick.startup && before != null && before != position) {
                val name = names[source]?.takeIf { it.isNotBlank() } ?: source.defaultName
                audio.speak("$name ${positionWord(position)}", SpeechPriority.LOW)
            }
        }

        // Trim beeps.
        val trims = live.trims
        if (radioEvents.any { it is RadioEvent.TrimChanged } && announcements.trimBeeps && !tick.startup) {
            audio.beep(trimBeep(previousTrims, trims), AudioCategory.BEEP)
        }
        previousTrims = trims

        announceTimers(extras, live, tick.startup)
    }

    private fun positionWord(position: SwitchPosition): String = when (position) {
        SwitchPosition.UP -> phrases.switchUp
        SwitchPosition.MID -> phrases.switchMiddle
        SwitchPosition.DOWN -> phrases.switchDown
    }

    /** Centre tone when a trim reaches zero, limit tone at its end, otherwise one step. */
    private fun trimBeep(before: Trims, after: Trims): BeepPattern {
        val pairs = listOf(
            before.leftVertical to after.leftVertical,
            before.leftHorizontal to after.leftHorizontal,
            before.rightVertical to after.rightVertical,
            before.rightHorizontal to after.rightHorizontal,
        )
        val changed = pairs.firstOrNull { it.first != it.second } ?: return BeepPattern.TRIM_STEP
        return when {
            changed.second == 0 -> BeepPattern.TRIM_CENTER
            abs(changed.second) >= TRIM_LIMIT -> BeepPattern.TRIM_LIMIT
            else -> BeepPattern.TRIM_STEP
        }
    }

    private class TimerMemory {
        var initialized = false
        var elapsed = 0
        var remaining = 0
    }

    private fun elapsedOf(timer: TimerState): Int = if (timer.showsElapsed) timer.count else timer.startTime - timer.count

    private fun remainingOf(timer: TimerState): Int = if (timer.showsElapsed) timer.startTime - timer.count else timer.count

    /**
     * Voice and beeps of the timers (`timerVoice` options): minute calls, the countdown before a
     * countdown timer expires and the expiry. Derived from the live timer states; the firmware's
     * own alert notifications are not rendered (see docs/ASSUMPTIONS.md).
     */
    private fun announceTimers(extras: ModelExtras, live: LiveSnapshot, startup: Boolean) {
        for (index in 0 until TIMER_COUNT) {
            val timer = live.timer(index) ?: continue
            val memory = timerMemory[index]
            val elapsed = elapsedOf(timer)
            val remaining = remainingOf(timer)
            val countdown = timer.startTime > 0
            if (!startup && memory.initialized && timer.running) {
                val options = extras.timerVoiceOptions(index)
                if (options.minuteCall) {
                    val minute = when {
                        countdown && remaining > 0 && remaining / 60 < memory.remaining / 60 -> (memory.remaining / 60) * 60
                        !countdown && elapsed / 60 > memory.elapsed / 60 -> (elapsed / 60) * 60
                        else -> 0
                    }
                    if (minute > 0) audio.speakValue(minute.toDouble(), SensorUnit.SECOND, 0, priority = SpeechPriority.NORMAL)
                }
                if (countdown) {
                    if (options.countdown != CountdownStyle.NONE && remaining in 1..options.countdownFromSec && remaining < memory.remaining) {
                        if (options.countdown == CountdownStyle.BEEPS) {
                            audio.beep(BeepPattern.TIMER_TICK, AudioCategory.BEEP)
                        } else {
                            audio.speak(remaining.toString(), SpeechPriority.HIGH)
                        }
                        if (options.haptic) audio.haptic(HapticPattern.SHORT)
                    }
                    if (options.announceExpired && memory.remaining > 0 && remaining <= 0) {
                        audio.beep(BeepPattern.TIMER_EXPIRED, AudioCategory.BEEP)
                        audio.speak(phrases.timerExpired(index + 1), SpeechPriority.HIGH)
                        if (options.haptic) audio.haptic(HapticPattern.WARNING)
                    }
                }
            }
            memory.initialized = true
            memory.elapsed = elapsed
            memory.remaining = remaining
        }
    }

    // ---- logging --------------------------------------------------------------------------------

    private fun updateLogging(live: LiveSnapshot) {
        val wanted = functionLogging || (settings.settings.value.logging && live.status.isLinkUp)
        if (wanted != loggingDesired) {
            loggingDesired = wanted
            loggingRequests.trySend(wanted)
        }
    }

    private suspend fun loggingWorker() {
        for (wanted in loggingRequests) {
            if (wanted) {
                when (val started = flightLogger.start()) {
                    is DataResult.Success ->
                        _uiRequests.tryEmit(RuntimeUiRequest.Message(RuntimeMessages.LOGGING_STARTED, started.value.name))
                    is DataResult.Failure -> {
                        diagnostics.record(DiagnosticsLog.SOURCE_RUNTIME, "logging could not start: ${started.error} ${started.detail}")
                        if (flightLogger.state.value !is FlightLogState.Failed) {
                            _uiRequests.tryEmit(RuntimeUiRequest.Message(RuntimeMessages.LOGGING_FAILED, started.error.name))
                        }
                    }
                }
            } else if (flightLogger.state.value.isRecording) {
                flightLogger.stop()
                _uiRequests.tryEmit(RuntimeUiRequest.Message(RuntimeMessages.LOGGING_STOPPED))
            }
        }
    }

    private suspend fun loggerStateWatcher() {
        flightLogger.state.collect { state ->
            if (state is FlightLogState.Failed) {
                diagnostics.record(DiagnosticsLog.SOURCE_RUNTIME, "logging stopped: ${state.error} ${state.detail}")
                _uiRequests.tryEmit(RuntimeUiRequest.Message(RuntimeMessages.LOGGING_FAILED, state.error.name))
            }
        }
    }

    // ---- executing commands ---------------------------------------------------------------------

    /** Performs [commands]; [resolver] is needed only for value announcements (null skips them). Runs under the lock. */
    private suspend fun execute(commands: List<ActionCommand>, resolver: ValueResolver?) {
        for (command in commands) {
            when (command) {
                is ActionCommand.Speak -> audio.speak(command.text, command.priority)
                is ActionCommand.SpeakValue -> speakValue(command, resolver)
                is ActionCommand.PlaySound -> if (!audio.playSound(command.file)) soundMissing(command.file)
                is ActionCommand.Music -> {
                    val file = command.file
                    if (file == null) audio.stopMusic() else if (!audio.startMusic(file, command.loop)) soundMissing(file)
                }
                is ActionCommand.Vario -> functionVario = command.enabled
                is ActionCommand.Haptic -> audio.haptic(command.pattern)
                is ActionCommand.Beep -> audio.beep(command.pattern)
                is ActionCommand.ResetTimer -> radio.resetTimer(command.deviceIndex)
                is ActionCommand.ResetTelemetry -> telemetry.resetMinMax(null)
                is ActionCommand.ResetFlight -> resetFlightLocked()
                is ActionCommand.SetVolume -> audio.setVolumeOverride(command.percent)
                is ActionCommand.Logging -> functionLogging = command.enabled
                is ActionCommand.Screenshot -> _uiRequests.tryEmit(RuntimeUiRequest.Screenshot)
                is ActionCommand.Brightness -> _brightness.value = command.percent?.coerceIn(0, 100)
            }
        }
    }

    private fun speakValue(command: ActionCommand.SpeakValue, resolver: ValueResolver?) {
        val value = resolver?.resolve(command.sensor) ?: return
        if (value.lost) return
        if (value.isNumeric) {
            audio.speakValue(
                value = value.number,
                unit = if (command.withUnit) value.unit else SensorUnit.NONE,
                precision = value.precision,
                name = if (command.withName) value.name else null,
                priority = command.priority,
            )
        } else if (value.text.isNotBlank()) {
            audio.speak(if (command.withName) "${value.name} ${value.text}" else value.text, command.priority)
        }
    }

    private fun soundMissing(file: String) {
        if (!missingSoundsReported.add(file)) return
        diagnostics.record(DiagnosticsLog.SOURCE_RUNTIME, "sound file is missing or cannot be decoded: $file")
        _uiRequests.tryEmit(RuntimeUiRequest.Message(RuntimeMessages.SOUND_FILE_MISSING, file))
    }

    private suspend fun resetFlightLocked() {
        for (device in 0 until TIMER_COUNT + COUNTER_COUNT) radio.resetTimer(device)
        telemetry.resetFlight()
        logicalEvaluator.reset()
        _logicalSwitches.value = LogicalSwitchStates.NONE
        publishedLogicalSwitches.clear()
        timerMemory.forEach { it.initialized = false }
        _uiRequests.tryEmit(RuntimeUiRequest.Message(RuntimeMessages.FLIGHT_RESET))
    }

    // ---- RuntimeEngine: resolver and manual triggers --------------------------------------------

    override fun valueResolver(): ValueResolver {
        val current = snapshot
        val extras = current?.extras ?: models.currentExtras.value
        val tick = current?.tick ?: EngineTick(
            nowMs = lastNowMs,
            live = radio.live.value,
            sensors = telemetry.sensors.value,
            modelName = models.currentModel.value?.name.orEmpty(),
        )
        return EngineValueResolver(tick, extras, telemetry.sensorList.value, sourceNames(), settings.settings.value.units)
    }

    override suspend fun resetFlight() {
        lock.withLock { resetFlightLocked() }
    }

    override suspend fun resetTelemetry() {
        telemetry.resetMinMax(null)
    }

    override suspend fun resetTimer(index: Int, isCounter: Boolean) {
        val limit = if (isCounter) COUNTER_COUNT else TIMER_COUNT
        if (index !in 0 until limit) return
        radio.resetTimer(if (isCounter) index + TIMER_COUNT else index)
    }

    override suspend fun skipPreFlightCheck() {
        if (!radio.skipPreFlightCheck()) {
            diagnostics.record(DiagnosticsLog.SOURCE_RUNTIME, "the radio rejected skipping the pre-flight check")
        }
        _preFlightCheck.value = null
    }

    // ---- RuntimeEngine: previews ----------------------------------------------------------------

    override fun preview(alert: VoiceAlert) {
        scope.launch(dispatchers.default) {
            val text = renderSpeech(alert.text)
            if (text.isNotEmpty()) audio.speak(text, alert.priority)
        }
    }

    override fun previewText(template: String) {
        scope.launch(dispatchers.default) {
            val text = renderSpeech(template)
            if (text.isNotEmpty()) audio.speak(text, SpeechPriority.NORMAL)
        }
    }

    private fun renderSpeech(template: String): String =
        TemplateRenderer.render(template, valueResolver(), RenderMode.SPEECH, speechFormatter).trim()

    override fun preview(function: SpecialFunction) {
        scope.launch(dispatchers.default) {
            val resolver = valueResolver()
            val origin = "preview:${function.label}"
            val (start, stop) = previewCommands(function.action, resolver, origin)
            lock.withLock { execute(start, resolver) }
            if (stop.isNotEmpty()) {
                delay(FUNCTION_PREVIEW_MS)
                lock.withLock { execute(stop, resolver) }
            }
        }
    }

    /** The commands that perform [action] once, and the ones that undo a continuous action again. */
    private fun previewCommands(
        action: SpecialAction,
        resolver: ValueResolver,
        origin: String,
    ): Pair<List<ActionCommand>, List<ActionCommand>> {
        val none = emptyList<ActionCommand>()
        return when (action) {
            is SpecialAction.PlayText -> {
                val text = TemplateRenderer.render(action.text, resolver, RenderMode.SPEECH, speechFormatter).trim()
                (if (text.isEmpty()) none else listOf(ActionCommand.Speak(text, action.priority, origin))) to none
            }
            is SpecialAction.PlayValue ->
                listOf<ActionCommand>(ActionCommand.SpeakValue(action.sensor, action.withUnit, action.withName, action.priority, origin)) to none
            is SpecialAction.PlaySound ->
                (if (action.file.isBlank()) none else listOf(ActionCommand.PlaySound(action.file, origin))) to none
            is SpecialAction.BackgroundMusic ->
                if (action.file.isBlank()) {
                    none to none
                } else {
                    listOf<ActionCommand>(ActionCommand.Music(action.file, action.loop, origin)) to
                        listOf(ActionCommand.Music(null, false, origin))
                }
            SpecialAction.Vario ->
                listOf<ActionCommand>(ActionCommand.Vario(true, origin)) to listOf(ActionCommand.Vario(false, origin))
            is SpecialAction.Haptic -> listOf<ActionCommand>(ActionCommand.Haptic(action.pattern, origin)) to none
            is SpecialAction.Beep -> listOf<ActionCommand>(ActionCommand.Beep(action.pattern, origin)) to none
            is SpecialAction.ResetTimer ->
                listOf<ActionCommand>(ActionCommand.ResetTimer(action.timer + if (action.counter) TIMER_COUNT else 0, origin)) to none
            SpecialAction.ResetTelemetry -> listOf<ActionCommand>(ActionCommand.ResetTelemetry(origin)) to none
            SpecialAction.ResetFlight -> listOf<ActionCommand>(ActionCommand.ResetFlight(origin)) to none
            is SpecialAction.SetVolume ->
                listOf<ActionCommand>(ActionCommand.SetVolume(inputPercent(action.source), origin)) to
                    listOf(ActionCommand.SetVolume(null, origin))
            // Starting a log file for three seconds is not a useful preview.
            SpecialAction.Logging -> none to none
            SpecialAction.Screenshot -> listOf<ActionCommand>(ActionCommand.Screenshot(origin)) to none
            is SpecialAction.Brightness -> {
                val percent = action.source?.let(::inputPercent) ?: action.percent
                listOf<ActionCommand>(ActionCommand.Brightness(percent, origin)) to listOf(ActionCommand.Brightness(null, origin))
            }
        }
    }

    /** An input source's value (-100..100) as 0..100 percent. */
    private fun inputPercent(source: SourceId): Int = ((radio.live.value.logic(source) + 100) / 2).coerceIn(0, 100)

    private class Snapshot(val tick: EngineTick, val extras: ModelExtras)

    private companion object {
        const val UI_REQUEST_BUFFER = 16
        const val VARIO_PREVIEW_MS = 2_000L
        const val FUNCTION_PREVIEW_MS = 3_000L
        const val FIRST_SWITCH = 20
        const val SWITCH_COUNT = 16
        const val TRIM_LIMIT = 100
    }
}
