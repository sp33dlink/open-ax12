package org.openax12.core.data.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openax12.core.data.CrsfStats
import org.openax12.core.data.HistoryPoint
import org.openax12.core.data.TelemetryRepository
import org.openax12.core.data.crsf.CrsfDecodeResult
import org.openax12.core.data.crsf.CrsfDecoder
import org.openax12.core.data.crsf.CrsfFrameError
import org.openax12.core.data.crsf.CrsfGps
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.CrsfTelemetrySnapshot
import org.openax12.core.model.GpsFix
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorOrigin
import org.openax12.core.model.SensorOverride
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.Sensors
import org.openax12.core.model.TelemetryItem
import org.openax12.core.model.TimerState
import org.openax12.core.radio.CrsfFrame
import org.openax12.core.radio.Radio
import org.openax12.core.radio.RadioEvent

/**
 * The sensor registry of the app (see [TelemetryRepository]).
 *
 * Four workers run between [start] and [stop]:
 * - the **event worker** decodes every raw CRSF frame and feeds the registry (per-sensor flows
 *   update at once, the map does not);
 * - the **live worker** samples `Radio.live` [LIVE_HZ] times per second for the transmitter
 *   values, timers and counters and derives the link state;
 * - the **SDK list worker** re-reads the SDK telemetry list at most every [SDK_LIST_INTERVAL_MS]
 *   after the SDK announced a change;
 * - the **publisher** emits the sensor map, the sensor list and the frame statistics, the map at
 *   most every [PUBLISH_INTERVAL_MS].
 *
 * @param overrides the per-model sensor overrides of the current model (key = sensor id)
 * @param positionStore where the last known GPS position is kept across restarts
 * @param monotonicClock milliseconds on the same base as `LiveSnapshot.timestampMs`
 */
internal class DefaultTelemetryRepository(
    private val radio: Radio,
    private val overrides: Flow<Map<String, SensorOverride>>,
    private val positionStore: LastPositionStore?,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val monotonicClock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val registry: SensorRegistry = SensorRegistry(),
) : TelemetryRepository {

    private val _sensors = MutableStateFlow<Map<SensorId, SensorValue>>(emptyMap())
    private val _sensorList = MutableStateFlow<List<SensorInfo>>(emptyList())
    private val _linkLost = MutableStateFlow(true)
    private val _lastKnownPosition = MutableStateFlow<GpsFix?>(null)
    private val _homePosition = MutableStateFlow<GpsFix?>(null)
    private val _crsfStats = MutableStateFlow(CrsfStats())

    override val sensors: StateFlow<Map<SensorId, SensorValue>> = _sensors.asStateFlow()
    override val sensorList: StateFlow<List<SensorInfo>> = _sensorList.asStateFlow()
    override val linkLost: StateFlow<Boolean> = _linkLost.asStateFlow()
    override val lastKnownPosition: StateFlow<GpsFix?> = _lastKnownPosition.asStateFlow()
    override val homePosition: StateFlow<GpsFix?> = _homePosition.asStateFlow()
    override val crsfStats: StateFlow<CrsfStats> = _crsfStats.asStateFlow()

    private val lifecycleLock = Any()
    private var jobs: List<Job> = emptyList()

    /** Wakes the SDK list worker; conflated, so a burst of updates costs one read. */
    private val sdkListSignal = Channel<Unit>(Channel.CONFLATED)

    // Link state: lost while the SDK said so or the transmitter reports no link quality.
    private val linkLock = Any()
    private var eventLost = false
    private var noLinkQuality = true

    // CRSF stream state, touched by the event worker and read by the publisher.
    private val statsLock = Any()
    private var totalFrames = 0L
    private val framesByType = HashMap<Int, Long>()
    private var droppedFrames = 0L
    private var validFrames = 0L
    private var crcFailureStreak = 0
    private var verifyCrc = true
    private val unknownSids = HashSet<Int>()
    private var lastFrameMs = -1L

    private var lastVarioFrameMs = Long.MIN_VALUE / 2
    private var lastGpsFrameMs = Long.MIN_VALUE / 2
    @Volatile private var positionDirty = false
    private var lastPositionSaveMs = 0L

    /** Last value and time written per internal sensor, to skip identical updates. */
    private val internalCache = HashMap<SensorId, DoubleArray>()

    /** Stable metadata of the SDK telemetry items (precision only ever grows). */
    private val sdkInfos = HashMap<SensorId, SensorInfo>()

    /** History of live sensors (inputs, channels), recorded only for ids somebody asked for. */
    private val liveHistories = HashMap<SensorId, HistoryBuffer>()

    init {
        val now = monotonicClock()
        registry.setLinkLost(true, now)
        SensorCatalog.ALWAYS_PRESENT.forEach { registry.register(it) }
        registry.consumeListDirty()
        _sensorList.value = buildSensorList()
        _lastKnownPosition.value = positionStore?.load()
    }

    // -- TelemetryRepository --------------------------------------------------------------------

    override fun sensor(id: SensorId): Flow<SensorValue?> {
        if (!id.isLive) return registry.flow(id)
        val reader = liveReader(id) ?: return flowOf(null)
        return radio.live
            .map { live -> if (live.isEmpty) null else reader(live) }
            .distinctUntilChanged()
            .map { percent -> percent?.let { liveValue(id, it, radio.live.value.timestampMs) } }
            .conflate()
    }

    override fun value(id: SensorId): SensorValue? {
        if (!id.isLive) return registry.value(id)
        val reader = liveReader(id) ?: return null
        val live = radio.live.value
        return if (live.isEmpty) null else liveValue(id, reader(live), live.timestampMs)
    }

    override fun info(id: SensorId): SensorInfo? {
        if (!id.isLive) {
            // A well-known sensor that has not reported yet (no receiver bound) still has its readable
            // name, so that widgets show "Altitude" instead of the raw key "alt".
            return registry.info(id) ?: if (SensorCatalog.hasReadableName(id)) registry.withOverrides(SensorCatalog.defaultInfo(id)) else null
        }
        if (liveReader(id) == null) return null
        return registry.withOverrides(SensorCatalog.defaultInfo(id))
    }

    override fun history(id: SensorId, windowSec: Int): List<HistoryPoint> {
        val now = monotonicClock()
        if (!id.isLive) return registry.history(id, windowSec, now)
        if (liveReader(id) == null) return emptyList()
        val window = windowSec.coerceIn(1, TelemetryRepository.HISTORY_SECONDS) * 1000L
        return synchronized(liveHistories) {
            // Recording starts with the first request: nobody pays for 58 buffers nobody graphs.
            liveHistories.getOrPut(id) { HistoryBuffer(HISTORY_CAPACITY, HISTORY_INTERVAL_MS) }.since(now - window)
        }
    }

    override suspend fun resetMinMax(id: SensorId?) {
        registry.resetMinMax(id, monotonicClock())
        publishValues(force = true)
    }

    override suspend fun setHome(fix: GpsFix?) {
        val home = fix ?: currentFix() ?: return
        _homePosition.value = home
        updateHomeSensors(currentFix() ?: home, home, monotonicClock())
        publishValues(force = true)
    }

    override suspend fun resetFlight() {
        registry.resetMinMax(null, monotonicClock())
        registry.clearHistory()
        synchronized(liveHistories) { liveHistories.values.forEach { it.clear() } }
        // The next valid fix becomes the new home position.
        _homePosition.value = null
        publishValues(force = true)
    }

    override fun publish(id: SensorId, value: SensorValue, info: SensorInfo?) {
        if (id.isLive) return
        val metadata = info ?: registry.info(id).let { existing ->
            if (existing != null) {
                null
            } else {
                // First publication without metadata: take the catalog entry, typed after the value.
                val default = SensorCatalog.defaultInfo(id)
                when {
                    value.text != null && !value.hasValue && default.unit == SensorUnit.NONE -> default.copy(unit = SensorUnit.TEXT)
                    value.unit != SensorUnit.NONE && default.unit == SensorUnit.NONE -> default.copy(unit = value.unit, precision = value.precision)
                    else -> default
                }
            }
        }
        registry.update(id, value.value, monotonicClock(), text = value.text, info = metadata, policy = LostPolicy.NEVER)
    }

    override fun start() {
        synchronized(lifecycleLock) {
            if (jobs.isNotEmpty()) return
            jobs = listOf(
                scope.launch(dispatchers.default) { eventWorker() },
                scope.launch(dispatchers.default) { liveWorker() },
                scope.launch(dispatchers.default) { sdkListWorker() },
                scope.launch(dispatchers.default) { publisher() },
                scope.launch(dispatchers.default) { overrides.collect { registry.setOverrides(it, monotonicClock()) } },
            )
        }
        // Values may exist before the first frame of this run (a restart of the radio).
        sdkListSignal.trySend(Unit)
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            jobs.forEach { it.cancel() }
            jobs = emptyList()
        }
        if (positionDirty) {
            positionDirty = false
            val fix = _lastKnownPosition.value
            if (fix != null && positionStore != null) scope.launch(dispatchers.io) { positionStore.save(fix) }
        }
    }

    // -- Workers --------------------------------------------------------------------------------

    private suspend fun eventWorker() {
        radio.events.collect { event ->
            when (event) {
                is RadioEvent.CrsfReceived -> onFrame(event.frame, monotonicClock())
                RadioEvent.TelemetryUpdated -> sdkListSignal.trySend(Unit)
                RadioEvent.TelemetryLost -> setLink(eventLost = true)
                RadioEvent.TelemetryRecovered -> setLink(eventLost = false)
                is RadioEvent.TimerReported -> updateInternal(SensorId.timer(event.report.index), event.report.count.toDouble(), monotonicClock())
                is RadioEvent.CounterReported -> updateInternal(SensorId.counter(event.report.index), event.report.count.toDouble(), monotonicClock())
                is RadioEvent.ElrsTelemetry -> onSdkSnapshot(event.snapshot, monotonicClock())
                else -> Unit
            }
        }
    }

    private suspend fun liveWorker() {
        var lastSequence = -1L
        while (true) {
            val live = radio.live.value
            if (!live.isEmpty && live.sequence != lastSequence) {
                lastSequence = live.sequence
                onLive(live, monotonicClock())
            }
            delay(1000L / LIVE_HZ)
        }
    }

    private suspend fun sdkListWorker() {
        for (signal in sdkListSignal) {
            val items = radio.getTelemetryList(uiOnly = false)
            onSdkList(items, monotonicClock())
            delay(SDK_LIST_INTERVAL_MS)
        }
    }

    private suspend fun publisher() {
        var lastStatsMs = Long.MIN_VALUE / 2
        while (true) {
            val now = monotonicClock()
            registry.checkTimeouts(now)
            publishValues(force = false)
            if (registry.consumeListDirty()) _sensorList.value = buildSensorList()
            if (now - lastStatsMs >= STATS_INTERVAL_MS) {
                lastStatsMs = now
                _crsfStats.value = snapshotStats(now)
            }
            savePositionIfDue(now)
            delay(PUBLISH_INTERVAL_MS)
        }
    }

    // -- Sources (internal for tests: they are plain functions of their input and a time) -------

    /** Decodes one raw CRSF frame and stores its readings. */
    internal fun onFrame(frame: CrsfFrame, nowMs: Long) {
        val decoded = validate(frame.bytes, nowMs) ?: return
        for (reading in decoded.readings) {
            registry.update(reading.id, reading.value, nowMs, text = reading.text)
        }
        when (decoded.type) {
            CrsfFrame.TYPE_VARIO -> lastVarioFrameMs = nowMs
            CrsfFrame.TYPE_BARO_ALTITUDE -> {
                // Receivers that send no vario frame carry the vertical speed in the baro frame;
                // mirror it so that `VSpd` and the vario work with either.
                val speed = decoded.readings.firstOrNull { it.id == Sensors.BARO_VERTICAL_SPEED }
                if (speed != null && nowMs - lastVarioFrameMs > VARIO_FRAME_TIMEOUT_MS) {
                    registry.update(Sensors.VERTICAL_SPEED, speed.value, nowMs)
                }
            }
            CrsfFrame.TYPE_GPS -> decoded.gps?.let {
                lastGpsFrameMs = nowMs
                onGps(it, nowMs)
            }
        }
    }

    /** Stores the transmitter-side values of one live frame. */
    internal fun onLive(live: LiveSnapshot, nowMs: Long) {
        val status = live.status
        updateInternal(Sensors.TX_BATTERY, status.txVoltage.toDouble(), nowMs)
        updateInternal(Sensors.TX_LINK_QUALITY, status.linkQuality.toDouble(), nowMs)
        updateInternal(Sensors.TX_RSSI, status.rssiDbm.toDouble(), nowMs)
        // The receiver voltage is only meaningful while there is a link (the sensor is then
        // marked lost and keeps its last value).
        if (status.isLinkUp) updateInternal(Sensors.TX_RX_BATTERY, status.rxVoltage.toDouble(), nowMs)
        for (timer in live.timers) updateTimer(timer, nowMs)
        for (counter in live.counters) updateTimer(counter, nowMs)
        setLink(noLinkQuality = !status.isLinkUp)
        synchronized(liveHistories) {
            for ((id, buffer) in liveHistories) {
                val reader = liveReader(id) ?: continue
                buffer.add(nowMs, reader(live).toDouble())
            }
        }
    }

    /** Stores the SDK telemetry list. */
    internal fun onSdkList(items: List<TelemetryItem>, nowMs: Long) {
        for (item in items) {
            val id = SensorId.sdk(item.hash)
            val (unit, precision) = SdkValueText.parse(item.valueText)
            val name = item.displayName.ifBlank { item.name }.ifBlank { id.localName }
            val previous = sdkInfos[id]
            val info = if (previous != null && previous.name == name && previous.unit == unit) {
                if (precision > previous.precision) previous.copy(precision = precision) else previous
            } else {
                SensorInfo(id = id, name = name, origin = SensorOrigin.SDK, unit = unit, precision = precision)
            }
            sdkInfos[id] = info
            // The list carries no age: an item counts as lost with the link, not by a timeout.
            if (unit == SensorUnit.TEXT) {
                registry.update(id, Double.NaN, nowMs, text = item.valueText, info = info, policy = LostPolicy.LINK)
            } else {
                registry.update(id, item.value, nowMs, info = info, policy = LostPolicy.LINK)
            }
        }
    }

    /** Forces the throttled outputs to be recomputed now (tests, and after resets). */
    internal fun publishNow() {
        registry.checkTimeouts(monotonicClock())
        publishValues(force = true)
        if (registry.consumeListDirty()) _sensorList.value = buildSensorList()
        _crsfStats.value = snapshotStats(monotonicClock())
    }

    // -- internals ------------------------------------------------------------------------------

    /** Validates and decodes a frame and keeps the stream statistics; null if the frame was dropped. */
    private fun validate(bytes: ByteArray, nowMs: Long): CrsfDecodeResult.Decoded? = synchronized(statsLock) {
        totalFrames++
        var result = CrsfDecoder.decode(bytes, verifyCrc)
        if (result is CrsfDecodeResult.Invalid && result.error == CrsfFrameError.BAD_CRC) {
            crcFailureStreak++
            // Not one frame ever passed: the SDK evidently does not deliver the CRC byte as
            // received (assumption 27). Go on with length validation only.
            if (verifyCrc && validFrames == 0L && crcFailureStreak >= CRC_FALLBACK_FRAMES) {
                verifyCrc = false
                result = CrsfDecoder.decode(bytes, verifyCrc = false)
            }
        }
        val decoded = result as? CrsfDecodeResult.Decoded
        if (decoded == null) {
            droppedFrames++
        } else {
            validFrames++
            crcFailureStreak = 0
            lastFrameMs = nowMs
            framesByType[decoded.type] = (framesByType[decoded.type] ?: 0L) + 1L
            unknownSids.addAll(decoded.unknownSids)
        }
        decoded
    }

    private fun onGps(gps: CrsfGps, nowMs: Long) {
        if (gps.satellites <= 0 || !GeoMath.isPlausible(gps.latitude, gps.longitude)) return
        val fix = GpsFix(
            latitude = gps.latitude,
            longitude = gps.longitude,
            altitude = gps.altitudeM,
            groundSpeed = gps.groundSpeedKmh,
            heading = gps.headingDeg,
            satellites = gps.satellites,
            timeMs = wallClock(),
        )
        _lastKnownPosition.value = fix
        positionDirty = true
        var home = _homePosition.value
        if (home == null && gps.satellites >= HOME_MIN_SATELLITES) {
            home = fix
            _homePosition.value = fix
        }
        if (home != null) updateHomeSensors(fix, home, nowMs)
    }

    /**
     * The SDK's own position snapshot. Only used for the lost-model finder, and only while no
     * raw GPS frame arrives (assumption 7): its units are not documented, so the coordinates are
     * accepted only if they are plausible decimal degrees.
     */
    private fun onSdkSnapshot(snapshot: CrsfTelemetrySnapshot, nowMs: Long) {
        if (nowMs - lastGpsFrameMs < GPS_FRAME_TIMEOUT_MS) return
        if (!GeoMath.isPlausible(snapshot.latitude, snapshot.longitude)) return
        _lastKnownPosition.value = GpsFix(latitude = snapshot.latitude, longitude = snapshot.longitude, timeMs = wallClock())
        positionDirty = true
    }

    private fun updateHomeSensors(position: GpsFix, home: GpsFix, nowMs: Long) {
        registry.update(Sensors.GPS_HOME_DISTANCE, GeoMath.distanceMeters(position, home), nowMs)
        // "Bearing to home": the course from the aircraft back to the home position.
        registry.update(Sensors.GPS_HOME_BEARING, GeoMath.bearingDegrees(position, home), nowMs)
    }

    /** The current GPS position from the registry, if the GPS sensors hold a valid, live fix. */
    private fun currentFix(): GpsFix? {
        val latitude = registry.value(Sensors.GPS_LATITUDE)?.takeIf { it.hasValue && !it.lost } ?: return null
        val longitude = registry.value(Sensors.GPS_LONGITUDE)?.takeIf { it.hasValue && !it.lost } ?: return null
        if (!GeoMath.isPlausible(latitude.value, longitude.value)) return null
        return GpsFix(
            latitude = latitude.value,
            longitude = longitude.value,
            altitude = registry.value(Sensors.GPS_ALTITUDE)?.value?.takeIf { !it.isNaN() } ?: 0.0,
            groundSpeed = registry.value(Sensors.GPS_GROUND_SPEED)?.value?.takeIf { !it.isNaN() } ?: 0.0,
            heading = registry.value(Sensors.GPS_HEADING)?.value?.takeIf { !it.isNaN() } ?: 0.0,
            satellites = registry.value(Sensors.GPS_SATELLITES)?.value?.takeIf { !it.isNaN() }?.toInt() ?: 0,
            timeMs = wallClock(),
        )
    }

    private fun updateTimer(timer: TimerState, nowMs: Long) {
        val id = if (timer.isCounter) SensorId.counter(timer.index) else SensorId.timer(timer.index)
        if (id.timerIndex == null && id.counterIndex == null) return
        updateInternal(id, timer.count.toDouble(), nowMs)
    }

    /** Writes an internal value if it changed or the stored sample is older than a second. */
    private fun updateInternal(id: SensorId, value: Double, nowMs: Long) {
        synchronized(internalCache) {
            val cached = internalCache[id]
            if (cached != null && cached[0] == value && nowMs - cached[1].toLong() < INTERNAL_REFRESH_MS) return
            if (cached == null) internalCache[id] = doubleArrayOf(value, nowMs.toDouble()) else {
                cached[0] = value
                cached[1] = nowMs.toDouble()
            }
        }
        registry.update(id, value, nowMs)
    }

    private fun setLink(eventLost: Boolean? = null, noLinkQuality: Boolean? = null) {
        val lost = synchronized(linkLock) {
            if (eventLost != null) this.eventLost = eventLost
            if (noLinkQuality != null) {
                // A link that comes up again also ends a loss the SDK announced.
                if (this.noLinkQuality && !noLinkQuality) this.eventLost = false
                this.noLinkQuality = noLinkQuality
            }
            this.eventLost || this.noLinkQuality
        }
        registry.setLinkLost(lost, monotonicClock())
        _linkLost.value = lost
    }

    private fun publishValues(force: Boolean) {
        if (registry.consumeValuesDirty() || force) _sensors.value = registry.snapshot()
    }

    private fun buildSensorList(): List<SensorInfo> {
        val live = SensorCatalog.LIVE.map { registry.withOverrides(SensorCatalog.defaultInfo(it)) }
        return (registry.infos() + live).sortedWith(SensorCatalog.DISPLAY_ORDER)
    }

    private fun snapshotStats(nowMs: Long): CrsfStats = synchronized(statsLock) {
        CrsfStats(
            totalFrames = totalFrames,
            framesByType = HashMap(framesByType),
            crcErrors = droppedFrames,
            unknownRotorflightSids = HashSet(unknownSids),
            lastFrameAgeMs = if (lastFrameMs < 0) -1 else (nowMs - lastFrameMs).coerceAtLeast(0),
            crcCheckDisabled = !verifyCrc,
        )
    }

    private suspend fun savePositionIfDue(nowMs: Long) {
        if (!positionDirty || positionStore == null || nowMs - lastPositionSaveMs < POSITION_SAVE_INTERVAL_MS) return
        val fix = _lastKnownPosition.value ?: return
        positionDirty = false
        lastPositionSaveMs = nowMs
        withContext(dispatchers.io) { positionStore.save(fix) }
    }

    /** Reads the percent value of a live sensor from a frame; null if [id] is not a valid live id. */
    private fun liveReader(id: SensorId): ((LiveSnapshot) -> Int)? {
        id.source?.let { source -> return { live -> live.logic(source) } }
        id.channelIndex?.let { channel -> return { live -> live.channel(channel) } }
        return null
    }

    private fun liveValue(id: SensorId, percent: Int, timestampMs: Long): SensorValue =
        registry.withOverrides(id, SensorValue(value = percent.toDouble(), unit = SensorUnit.PERCENT, precision = 0, timestampMs = timestampMs))

    companion object {
        /** Sample rate of the transmitter-side values. */
        const val LIVE_HZ: Int = 5

        /** Shortest time between two emissions of the sensor map (4 per second). */
        const val PUBLISH_INTERVAL_MS: Long = 250L

        /** Shortest time between two reads of the SDK telemetry list. */
        const val SDK_LIST_INTERVAL_MS: Long = 500L

        /** Satellites needed before a fix is taken as the home position automatically. */
        const val HOME_MIN_SATELLITES: Int = 5

        /** Consecutive CRC failures (with no valid frame ever) after which the CRC check is dropped. */
        const val CRC_FALLBACK_FRAMES: Int = 50

        private const val STATS_INTERVAL_MS = 1000L
        private const val INTERNAL_REFRESH_MS = 1000L
        private const val VARIO_FRAME_TIMEOUT_MS = 3000L
        private const val GPS_FRAME_TIMEOUT_MS = 5000L
        private const val POSITION_SAVE_INTERVAL_MS = 10_000L
        private const val HISTORY_CAPACITY = TelemetryRepository.HISTORY_SECONDS * TelemetryRepository.HISTORY_HZ
        private const val HISTORY_INTERVAL_MS = 1000L / TelemetryRepository.HISTORY_HZ
    }
}
