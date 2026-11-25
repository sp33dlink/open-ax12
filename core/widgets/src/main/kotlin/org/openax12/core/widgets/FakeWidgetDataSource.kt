package org.openax12.core.widgets

import kotlinx.coroutines.flow.MutableStateFlow
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.TimerState
import org.openax12.core.model.UnitConversion
import org.openax12.core.model.UnitSystem
import kotlin.math.PI
import kotlin.math.sin

/**
 * An in-memory [WidgetDataSource] for previews, screenshot tests, unit tests and the widget
 * editor's catalog. Every value can be set at any time; widgets showing it update like they do
 * with real data. [demo] returns one filled with a plausible glider flight.
 *
 * The clock stands still at [now] unless it is changed, so renderings are reproducible.
 *
 * @param now value of [nowMs]; sensor timestamps are relative to it
 */
class FakeWidgetDataSource(var now: Long = 600_000L) : WidgetDataSource {

    private val sensors = HashMap<SensorId, MutableStateFlow<SensorValue?>>()
    private val streams = HashMap<SensorId, WidgetStream<SensorValue?>>()
    private val infos = HashMap<SensorId, SensorInfo>()
    private val histories = HashMap<SensorId, List<WidgetSample>>()
    private val timers = HashMap<Int, MutableStateFlow<TimerState?>>()
    private val timerStreams = HashMap<Int, WidgetStream<TimerState?>>()
    private val logicalNames = HashMap<Int, String>()
    private val flightModeState = MutableStateFlow(WidgetFlightMode())
    private val logicalState = MutableStateFlow(LogicalSwitchStates.NONE)
    private val unitState = MutableStateFlow(UnitSystem.METRIC)
    private val texts = HashMap<String, String>()

    /** See [WidgetDataSource.staleAfterMs]; changeable for tests. */
    override var staleAfterMs: Long = WidgetDataSource.DEFAULT_STALE_AFTER_MS

    private fun flowOf(id: SensorId): MutableStateFlow<SensorValue?> = sensors.getOrPut(id) { MutableStateFlow(null) }

    override fun sensor(id: SensorId): WidgetStream<SensorValue?> = streams.getOrPut(id) { WidgetStream.of(flowOf(id)) }

    override fun sensorInfo(id: SensorId): SensorInfo? = infos[id]

    override fun history(id: SensorId, windowSec: Int): List<WidgetSample> {
        histories[id]?.let { samples -> return samples.filter { now - it.timestampMs <= windowSec * 1000L } }
        val value = sensors[id]?.value ?: return emptyList()
        if (!value.hasValue) return emptyList()
        return syntheticHistory(value, windowSec)
    }

    override fun timer(index: Int, counter: Boolean): WidgetStream<TimerState?> {
        val key = timerKey(index, counter)
        return timerStreams.getOrPut(key) { WidgetStream.of(timers.getOrPut(key) { MutableStateFlow(null) }) }
    }

    override val flightMode: WidgetStream<WidgetFlightMode> = WidgetStream.of(flightModeState)
    override val logicalSwitches: WidgetStream<LogicalSwitchStates> = WidgetStream.of(logicalState)
    override val units: WidgetStream<UnitSystem> = WidgetStream.of(unitState)

    override fun logicalSwitchName(index: Int): String? = logicalNames[index]

    override fun resolveText(template: String): String = texts[template] ?: TEMPLATE_TOKEN.replace(template) { match ->
        val name = match.groupValues[1].substringBefore(':')
        val id = Sensors.byTemplateName(name)
        val value = id?.let { sensors[it]?.value }
        if (value == null) match.value else UnitConversion.format(value, unitState.value, withUnit = false)
    }

    override fun nowMs(): Long = now

    /**
     * Sets a numeric sensor.
     *
     * @param name display name (also the default widget title); null keeps the current one
     * @param ageMs how old the value is; a `crsf.*` / `sdk.*` value older than [staleAfterMs] is drawn stale
     */
    fun set(
        id: SensorId,
        value: Double,
        unit: SensorUnit = SensorUnit.NONE,
        precision: Int = 0,
        name: String? = null,
        min: Double = Double.NaN,
        max: Double = Double.NaN,
        lost: Boolean = false,
        ageMs: Long = 0,
    ): FakeWidgetDataSource {
        flowOf(id).value = SensorValue(value = value, unit = unit, precision = precision, min = min, max = max, timestampMs = now - ageMs, lost = lost)
        if (name != null) infos[id] = SensorInfo(id = id, name = name, unit = unit, precision = precision)
        return this
    }

    /** Sets a textual sensor (flight controller mode). */
    fun setText(id: SensorId, text: String, name: String? = null, lost: Boolean = false): FakeWidgetDataSource {
        flowOf(id).value = SensorValue(text = text, unit = SensorUnit.TEXT, timestampMs = now, lost = lost)
        if (name != null) infos[id] = SensorInfo(id = id, name = name, unit = SensorUnit.TEXT)
        return this
    }

    /** Flags a sensor as lost; its last value is kept. */
    fun setLost(id: SensorId, lost: Boolean = true): FakeWidgetDataSource {
        val flow = flowOf(id)
        flow.value = (flow.value ?: SensorValue.UNKNOWN).copy(lost = lost)
        return this
    }

    /** Makes a sensor's value [ageMs] old. */
    fun setAge(id: SensorId, ageMs: Long): FakeWidgetDataSource {
        val flow = flowOf(id)
        flow.value = flow.value?.copy(timestampMs = now - ageMs)
        return this
    }

    /** Removes a sensor: widgets bound to it show `--`. */
    fun remove(id: SensorId): FakeWidgetDataSource {
        flowOf(id).value = null
        infos.remove(id)
        histories.remove(id)
        return this
    }

    /** Sets the graph history of a sensor (oldest first). Without one, a history is synthesised around the current value. */
    fun setHistory(id: SensorId, samples: List<WidgetSample>): FakeWidgetDataSource {
        histories[id] = samples
        return this
    }

    /** Sets timer (or counter) [index]; null removes it. */
    fun setTimer(index: Int, counter: Boolean = false, state: TimerState?): FakeWidgetDataSource {
        timers.getOrPut(timerKey(index, counter)) { MutableStateFlow(null) }.value = state
        return this
    }

    /** Sets the active flight mode. */
    fun setFlightMode(index: Int, name: String): FakeWidgetDataSource {
        flightModeState.value = WidgetFlightMode(index, name)
        return this
    }

    /** Sets logical switch [index] (0-based) and optionally its name. */
    fun setLogicalSwitch(index: Int, on: Boolean, name: String? = null): FakeWidgetDataSource {
        logicalState.value = logicalState.value.with(index, on)
        if (name != null) logicalNames[index] = name
        return this
    }

    /** Sets the display unit system. */
    fun setUnits(system: UnitSystem): FakeWidgetDataSource {
        unitState.value = system
        return this
    }

    /** Fixes what [resolveText] returns for [template]. */
    fun setResolvedText(template: String, resolved: String): FakeWidgetDataSource {
        texts[template] = resolved
        return this
    }

    private fun timerKey(index: Int, counter: Boolean) = if (counter) index + COUNTER_KEY_OFFSET else index

    /** A deterministic, gently wandering curve that ends at the current value and stays within min..max. */
    private fun syntheticHistory(value: SensorValue, windowSec: Int): List<WidgetSample> {
        val lo = if (value.min.isNaN()) value.value - 1.0 else value.min
        val hi = if (value.max.isNaN()) value.value + 1.0 else value.max
        val span = (hi - lo).coerceAtLeast(1e-6)
        val steps = 48
        return List(steps + 1) { i ->
            val t = i.toDouble() / steps
            val wave = 0.5 + 0.34 * sin(t * 2.2 * PI + 0.6) + 0.14 * sin(t * 7.0 * PI)
            val raw = lo + span * wave.coerceIn(0.0, 1.0)
            // Blend towards the current value at the end so the line meets the read-out.
            val blend = (t * t * t).coerceIn(0.0, 1.0)
            WidgetSample(now - ((1.0 - t) * windowSec * 1000.0).toLong(), raw + (value.value - raw) * blend)
        }
    }

    companion object {
        private const val COUNTER_KEY_OFFSET = 100
        private val TEMPLATE_TOKEN = Regex("\\{([^}]+)\\}")

        /** A data source showing a glider in a thermal: every well-known sensor has a value. */
        fun demo(): FakeWidgetDataSource = FakeWidgetDataSource().apply {
            set(Sensors.BATTERY_VOLTAGE, 11.4, SensorUnit.VOLT, 1, "RxBt", min = 10.9, max = 12.5)
            set(Sensors.BATTERY_CURRENT, 12.3, SensorUnit.AMPERE, 1, "Curr", min = 0.0, max = 38.2)
            set(Sensors.BATTERY_CAPACITY, 850.0, SensorUnit.MILLIAMPERE_HOUR, 0, "Capa", min = 0.0, max = 850.0)
            set(Sensors.BATTERY_REMAINING, 62.0, SensorUnit.PERCENT, 0, "Bat%", min = 62.0, max = 100.0)
            set(Sensors.BARO_ALTITUDE, 123.0, SensorUnit.METER, 0, "Alt", min = 0.0, max = 187.0)
            set(Sensors.VERTICAL_SPEED, 1.8, SensorUnit.METER_PER_SECOND, 1, "VSpd", min = -3.2, max = 4.6)
            set(Sensors.AIRSPEED, 54.0, SensorUnit.KMH, 0, "ASpd", min = 0.0, max = 96.0)
            set(Sensors.GPS_LATITUDE, 48.13712, SensorUnit.COORDINATE, 5, "Lat")
            set(Sensors.GPS_LONGITUDE, 11.57541, SensorUnit.COORDINATE, 5, "Lon")
            set(Sensors.GPS_GROUND_SPEED, 48.0, SensorUnit.KMH, 0, "GSpd", min = 0.0, max = 88.0)
            set(Sensors.GPS_HEADING, 72.0, SensorUnit.DEGREE, 0, "Hdg")
            set(Sensors.GPS_ALTITUDE, 512.0, SensorUnit.METER, 0, "GAlt", min = 389.0, max = 576.0)
            set(Sensors.GPS_SATELLITES, 12.0, SensorUnit.COUNT, 0, "Sats")
            set(Sensors.GPS_HOME_DISTANCE, 152.0, SensorUnit.METER, 0, "Dist", min = 0.0, max = 318.0)
            set(Sensors.GPS_HOME_BEARING, 235.0, SensorUnit.DEGREE, 0, "Bearing")
            set(Sensors.ATTITUDE_PITCH, 6.0, SensorUnit.DEGREE, 0, "Ptch", min = -21.0, max = 34.0)
            set(Sensors.ATTITUDE_ROLL, -14.0, SensorUnit.DEGREE, 0, "Roll", min = -48.0, max = 52.0)
            set(Sensors.ATTITUDE_YAW, 72.0, SensorUnit.DEGREE, 0, "Yaw")
            set(Sensors.LINK_RX_QUALITY, 100.0, SensorUnit.PERCENT, 0, "RQly", min = 87.0, max = 100.0)
            set(Sensors.LINK_RSSI_1, -62.0, SensorUnit.DBM, 0, "1RSS", min = -91.0, max = -38.0)
            set(Sensors.LINK_RX_SNR, 9.0, SensorUnit.DB, 0, "RSNR", min = -2.0, max = 12.0)
            set(Sensors.LINK_TX_POWER, 100.0, SensorUnit.MILLIWATT, 0, "TPWR")
            setText(Sensors.FC_FLIGHT_MODE, "ACRO", "FC mode")
            set(Sensors.TX_LINK_QUALITY, 98.0, SensorUnit.PERCENT, 0, "LQ", min = 84.0, max = 100.0)
            set(Sensors.TX_RSSI, -62.0, SensorUnit.DBM, 0, "RSSI", min = -91.0, max = -38.0)
            set(Sensors.TX_BATTERY, 7.9, SensorUnit.VOLT, 1, "TxBt", min = 7.8, max = 8.3)
            set(Sensors.TX_RX_BATTERY, 5.1, SensorUnit.VOLT, 1, "RxV", min = 4.9, max = 5.2)
            set(SensorId.input(SourceId.THR), 42.0, SensorUnit.PERCENT, 0, "Thr")
            set(SensorId.input(SourceId.SA), -100.0, SensorUnit.PERCENT, 0, "SA")
            set(SensorId.input(SourceId.SB), 0.0, SensorUnit.PERCENT, 0, "SB")
            set(SensorId.input(SourceId.SC), 100.0, SensorUnit.PERCENT, 0, "SC")
            set(SensorId.channel(0), -24.0, SensorUnit.PERCENT, 0, "CH1")
            set(SensorId.channel(2), 42.0, SensorUnit.PERCENT, 0, "CH3")
            setTimer(0, state = TimerState(index = 0, count = 754, running = true))
            setTimer(1, state = TimerState(index = 1, count = 95, showsElapsed = false, startTime = 300, running = true))
            setTimer(2, state = TimerState(index = 2, count = 0))
            setTimer(0, counter = true, state = TimerState(index = 0, isCounter = true, count = 3))
            setFlightMode(1, "Thermal")
            setLogicalSwitch(0, true, "Motor on")
            setLogicalSwitch(1, false)
        }
    }
}
