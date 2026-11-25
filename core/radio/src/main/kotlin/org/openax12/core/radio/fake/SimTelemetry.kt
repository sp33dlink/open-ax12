package org.openax12.core.radio.fake

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CrsfTelemetrySnapshot
import org.openax12.core.model.SensorId
import org.openax12.core.model.Sensors
import org.openax12.core.model.TelemetryAlarm
import org.openax12.core.model.TelemetryItem
import org.openax12.core.radio.CrsfFrame
import org.openax12.core.radio.RadioEvent

/**
 * `valueUnit` codes the simulator uses in its telemetry list. The real SDK's codes are not
 * documented; these are only meaningful together with the simulator.
 */
internal object SimUnits {
    const val NONE = 0
    const val VOLT = 1
    const val AMPERE = 2
    const val MILLIAMPERE_HOUR = 3
    const val PERCENT = 4
    const val DBM = 5
    const val DB = 6
    const val METER = 7
    const val METER_PER_SECOND = 8
    const val KMH = 9
    const val DEGREE = 10
    const val MILLIWATT = 11

    /** Display suffix of a unit code. */
    fun symbol(code: Int): String = when (code) {
        VOLT -> "V"
        AMPERE -> "A"
        MILLIAMPERE_HOUR -> "mAh"
        PERCENT -> "%"
        DBM -> "dBm"
        DB -> "dB"
        METER -> "m"
        METER_PER_SECOND -> "m/s"
        KMH -> "km/h"
        DEGREE -> "°"
        MILLIWATT -> "mW"
        else -> ""
    }
}

/**
 * The values the simulator knows, each with its sensor id, the name of its item in the simulated
 * SDK telemetry list (null = not in the list), unit, decimals, the value it has at rest and the
 * range a manual value is coerced to (the range its CRSF field can carry).
 */
internal enum class SimSensor(
    val id: SensorId,
    val sdkName: String?,
    val unit: Int,
    val precision: Int,
    val rest: Double,
    val min: Double,
    val max: Double,
) {
    GPS_LATITUDE(Sensors.GPS_LATITUDE, "Lat", SimUnits.DEGREE, 5, SimTelemetry.HOME_LATITUDE, -90.0, 90.0),
    GPS_LONGITUDE(Sensors.GPS_LONGITUDE, "Lon", SimUnits.DEGREE, 5, SimTelemetry.HOME_LONGITUDE, -180.0, 180.0),
    GPS_GROUND_SPEED(Sensors.GPS_GROUND_SPEED, "GSpd", SimUnits.KMH, 1, 42.0, 0.0, 6553.5),
    GPS_HEADING(Sensors.GPS_HEADING, "Hdg", SimUnits.DEGREE, 1, 90.0, 0.0, 359.99),
    GPS_ALTITUDE(Sensors.GPS_ALTITUDE, "GAlt", SimUnits.METER, 0, SimTelemetry.HOME_ELEVATION + 120.0, -1000.0, 64535.0),
    GPS_SATELLITES(Sensors.GPS_SATELLITES, "Sats", SimUnits.NONE, 0, 12.0, 0.0, 255.0),
    VERTICAL_SPEED(Sensors.VERTICAL_SPEED, "VSpd", SimUnits.METER_PER_SECOND, 2, 0.0, -327.0, 327.0),
    BATTERY_VOLTAGE(Sensors.BATTERY_VOLTAGE, "RxBt", SimUnits.VOLT, 1, 11.6, 0.0, 6553.5),
    BATTERY_CURRENT(Sensors.BATTERY_CURRENT, "Curr", SimUnits.AMPERE, 1, 4.2, 0.0, 6553.5),
    BATTERY_CAPACITY(Sensors.BATTERY_CAPACITY, "Capa", SimUnits.MILLIAMPERE_HOUR, 0, 310.0, 0.0, 16777215.0),
    BATTERY_REMAINING(Sensors.BATTERY_REMAINING, "Bat%", SimUnits.PERCENT, 0, 86.0, 0.0, 100.0),
    BARO_ALTITUDE(Sensors.BARO_ALTITUDE, "Alt", SimUnits.METER, 1, 120.0, -1000.0, 32767.0),
    BARO_VERTICAL_SPEED(Sensors.BARO_VERTICAL_SPEED, null, SimUnits.METER_PER_SECOND, 2, 0.0, -327.0, 327.0),
    AIRSPEED(Sensors.AIRSPEED, "ASpd", SimUnits.KMH, 1, 45.0, 0.0, 6553.5),
    LINK_RSSI_1(Sensors.LINK_RSSI_1, "1RSS", SimUnits.DBM, 0, -58.0, -255.0, 0.0),
    LINK_RSSI_2(Sensors.LINK_RSSI_2, "2RSS", SimUnits.DBM, 0, -61.0, -255.0, 0.0),
    LINK_RX_QUALITY(Sensors.LINK_RX_QUALITY, "RQly", SimUnits.PERCENT, 0, 100.0, 0.0, 100.0),
    LINK_RX_SNR(Sensors.LINK_RX_SNR, "RSNR", SimUnits.DB, 0, 9.0, -128.0, 127.0),
    LINK_ANTENNA(Sensors.LINK_ANTENNA, "ANT", SimUnits.NONE, 0, 0.0, 0.0, 255.0),
    LINK_RF_MODE(Sensors.LINK_RF_MODE, "RFMD", SimUnits.NONE, 0, 3.0, 0.0, 255.0),
    LINK_TX_POWER(Sensors.LINK_TX_POWER, "TPWR", SimUnits.MILLIWATT, 0, 100.0, 0.0, 2000.0),
    LINK_TX_RSSI(Sensors.LINK_TX_RSSI, "TRSS", SimUnits.DBM, 0, -60.0, -255.0, 0.0),
    LINK_TX_QUALITY(Sensors.LINK_TX_QUALITY, "TQly", SimUnits.PERCENT, 0, 100.0, 0.0, 100.0),
    LINK_TX_SNR(Sensors.LINK_TX_SNR, "TSNR", SimUnits.DB, 0, 8.0, -128.0, 127.0),
    ATTITUDE_PITCH(Sensors.ATTITUDE_PITCH, "Ptch", SimUnits.DEGREE, 1, 2.0, -180.0, 180.0),
    ATTITUDE_ROLL(Sensors.ATTITUDE_ROLL, "Roll", SimUnits.DEGREE, 1, -5.0, -180.0, 180.0),
    ATTITUDE_YAW(Sensors.ATTITUDE_YAW, "Yaw", SimUnits.DEGREE, 1, 90.0, -180.0, 180.0),
    TX_BATTERY(Sensors.TX_BATTERY, null, SimUnits.VOLT, 1, 7.9, 0.0, 30.0),
    RX_BATTERY(Sensors.TX_RX_BATTERY, null, SimUnits.VOLT, 1, 11.6, 0.0, 100.0),
    TX_LINK_QUALITY(Sensors.TX_LINK_QUALITY, null, SimUnits.PERCENT, 0, 100.0, 0.0, 100.0),
    TX_RSSI(Sensors.TX_RSSI, null, SimUnits.DBM, 0, -58.0, -130.0, 0.0);

    /** Hash of this sensor's item in the simulated SDK telemetry list (0 if it has none). */
    val hash: Long = sdkName?.let(SimHash::of) ?: 0L

    /** False for items the SDK list marks as not shown in the UI (`showUI == 0`). */
    val showInUi: Boolean get() = this != LINK_ANTENNA && this != LINK_RF_MODE

    companion object {
        private val byId: Map<SensorId, SimSensor> = entries.associateBy { it.id }

        /** The simulated value for a sensor id, or null if the simulator does not produce it. */
        fun of(id: SensorId): SimSensor? = byId[id]
    }
}

/**
 * Telemetry of the simulated aircraft: the current values, a small flight model that moves them
 * while the demo motion is on, the CRSF frame schedule, the simulated SDK telemetry list and the
 * SDK-side telemetry notifications. Not thread-safe (confined to the simulator worker).
 *
 * Units of the values: degrees for coordinates, heading and attitude; km/h for ground speed and
 * airspeed; metres; m/s; volts; amperes; mAh; percent; dBm / dB; mW for the TX power.
 *
 * The flight model is a glider with an electric motor circling over its start point: thermals
 * (two slow waves plus seeded turbulence) and the motor make it climb, otherwise it sinks; the
 * battery drains with the throttle and is "swapped" when nearly empty; signal strength follows
 * the distance to the pilot.
 */
internal class SimTelemetry(private val seed: Long) {

    private val values = DoubleArray(SimSensor.entries.size) { SimSensor.entries[it].rest }
    private val pinned = BooleanArray(SimSensor.entries.size)
    private val due = LongArray(Schedule.entries.size) { Schedule.entries[it].offsetMs }
    private var random = Random(seed)

    /** Text of the CRSF flight-mode frame. */
    var flightModeText: String = DEFAULT_FLIGHT_MODE

    /** True once a telemetry frame was "received" since the last [reset]: the SDK list has items. */
    var received: Boolean = false
        private set

    /** True if a value was changed since [changed] was last cleared. */
    var changed: Boolean = true

    // Flight model state.
    private var timeSec = 0.0
    private var altitude = SimSensor.BARO_ALTITUDE.rest
    private var verticalSpeed = 0.0
    private var heading = SimSensor.GPS_HEADING.rest
    private var north = 0.0
    private var east = HOME_OFFSET_M
    private var roll = 0.0
    private var pitch = 0.0
    private var turbulence = 0.0
    private var usedMah = SimSensor.BATTERY_CAPACITY.rest
    private var txBattery = SimSensor.TX_BATTERY.rest

    // SDK-side notifications.
    private val alarmActive = HashMap<Int, Boolean>()
    private val alarmFiredAt = HashMap<Int, Long>()
    private val notifications = ArrayDeque<TelemetryAlarm>()
    private var alarmClockMs = 0L

    /** Everything back to the values at rest; pins, the flight and queued notifications are forgotten. */
    fun reset() {
        for (s in SimSensor.entries) values[s.ordinal] = s.rest
        pinned.fill(false)
        for (s in Schedule.entries) due[s.ordinal] = s.offsetMs
        random = Random(seed)
        flightModeText = DEFAULT_FLIGHT_MODE
        received = false
        timeSec = 0.0
        altitude = SimSensor.BARO_ALTITUDE.rest
        verticalSpeed = 0.0
        heading = SimSensor.GPS_HEADING.rest
        north = 0.0
        east = HOME_OFFSET_M
        roll = 0.0
        pitch = 0.0
        turbulence = 0.0
        usedMah = SimSensor.BATTERY_CAPACITY.rest
        txBattery = SimSensor.TX_BATTERY.rest
        alarmActive.clear()
        alarmFiredAt.clear()
        notifications.clear()
        changed = true
    }

    /** Current value of [sensor]. */
    operator fun get(sensor: SimSensor): Double = values[sensor.ordinal]

    /**
     * Sets a value by hand and pins it: the flight model leaves it alone until [unpinAll].
     * Returns false for a sensor the simulator does not produce.
     */
    fun set(id: SensorId, value: Double): Boolean {
        val sensor = SimSensor.of(id) ?: return false
        if (value.isNaN()) return false
        values[sensor.ordinal] = value.coerceIn(sensor.min, sensor.max)
        pinned[sensor.ordinal] = true
        if (sensor == SimSensor.TX_BATTERY) txBattery = values[sensor.ordinal]
        changed = true
        return true
    }

    /** Hands every pinned value back to the flight model. */
    fun unpinAll() = pinned.fill(false)

    /** All values by sensor id (a new map). */
    fun snapshot(): Map<SensorId, Double> {
        val map = LinkedHashMap<SensorId, Double>(SimSensor.entries.size * 2)
        for (s in SimSensor.entries) map[s.id] = values[s.ordinal]
        return map
    }

    /** The values the ELRS module determines itself; they apply with and without the demo flight. */
    fun applyModule(rfMode: Int, txPowerMw: Int) {
        auto(SimSensor.LINK_RF_MODE, rfMode.toDouble())
        auto(SimSensor.LINK_TX_POWER, txPowerMw.toDouble())
    }

    /**
     * Advances the flight model by [dtMs] and updates every value that is not pinned.
     *
     * @param throttle throttle stick in percent (−100 = motor off)
     * @param aileron aileron stick in percent (turns the aircraft)
     * @param elevator elevator stick in percent
     */
    fun stepFlight(dtMs: Long, throttle: Int, aileron: Int, elevator: Int) {
        val dt = dtMs / 1000.0
        if (dt <= 0.0) return
        timeSec += dt
        val motor = ((throttle + 100) / 200.0).coerceIn(0.0, 1.0)

        // Vertical movement: thermals + motor - sink, kept inside a comfortable band.
        turbulence += (random.nextDouble() - 0.5) * 3.0 * dt
        turbulence *= (1.0 - dt / 4.0).coerceAtLeast(0.0)
        val thermal = 1.5 * sin(TWO_PI * timeSec / 47.0) + 1.0 * sin(TWO_PI * timeSec / 19.0 + 1.3) + turbulence
        var target = thermal - 0.7 + motor * 5.0 - elevator * 0.01
        if (altitude < 40.0) target += (40.0 - altitude) * 0.12
        if (altitude > 600.0) target -= (altitude - 600.0) * 0.05
        verticalSpeed += (target - verticalSpeed) * (dt / 1.2).coerceAtMost(1.0)
        altitude = max(0.0, altitude + verticalSpeed * dt)
        if (altitude == 0.0 && verticalSpeed < 0.0) verticalSpeed = 0.0

        // Horizontal movement: a wide circle, tightened or opened by the aileron stick.
        val airspeedKmh = 45.0 + motor * 25.0 - verticalSpeed * 1.5 + 2.0 * sin(TWO_PI * timeSec / 11.0)
        val speedMs = airspeedKmh.coerceAtLeast(20.0) / 3.6
        heading = wrap360(heading + (8.0 + aileron * 0.25) * dt)
        val headingRad = heading * PI / 180.0
        north += speedMs * cos(headingRad) * dt
        east += speedMs * sin(headingRad) * dt
        roll += (aileron * 0.45 + 12.0 - roll) * (dt / 0.4).coerceAtMost(1.0)
        pitch += (verticalSpeed * 2.5 - elevator * 0.15 - pitch) * (dt / 0.5).coerceAtMost(1.0)

        // Battery: 3S 2200 mAh.
        val current = 0.4 + motor * 22.0
        usedMah += current * dt / 3.6
        if (usedMah > BATTERY_MAH * 0.97) usedMah = 0.0
        val remaining = (1.0 - usedMah / BATTERY_MAH).coerceIn(0.0, 1.0)
        val voltage = 3 * (3.5 + 0.7 * remaining) - 0.02 * current

        // Radio link: free-space loss over the distance to the pilot.
        val distance = sqrt(north * north + east * east + altitude * altitude).coerceAtLeast(1.0)
        val rssi = (-12.0 - 20.0 * log10(distance) + (random.nextDouble() - 0.5) * 3.0).coerceIn(-120.0, -20.0)
        val quality = if (rssi > -100.0) 100.0 else (100.0 + (rssi + 100.0) * 5.0).coerceIn(0.0, 100.0)
        val snr = (12.0 + (rssi + 50.0) * 0.15).coerceIn(-10.0, 14.0)

        if (!pinned[SimSensor.TX_BATTERY.ordinal]) txBattery = max(6.4, txBattery - dt * 0.00002)

        auto(SimSensor.GPS_LATITUDE, HOME_LATITUDE + north / METERS_PER_DEGREE)
        auto(SimSensor.GPS_LONGITUDE, HOME_LONGITUDE + east / (METERS_PER_DEGREE * cos(HOME_LATITUDE * PI / 180.0)))
        auto(SimSensor.GPS_GROUND_SPEED, airspeedKmh.coerceAtLeast(20.0) * (1.0 + 0.1 * sin(headingRad)))
        auto(SimSensor.GPS_HEADING, heading)
        auto(SimSensor.GPS_ALTITUDE, HOME_ELEVATION + altitude)
        auto(SimSensor.GPS_SATELLITES, (12 + (timeSec / 30.0).toInt() % 3).toDouble())
        auto(SimSensor.VERTICAL_SPEED, verticalSpeed)
        auto(SimSensor.BARO_VERTICAL_SPEED, verticalSpeed)
        auto(SimSensor.BARO_ALTITUDE, altitude)
        auto(SimSensor.AIRSPEED, airspeedKmh.coerceAtLeast(20.0))
        auto(SimSensor.BATTERY_VOLTAGE, voltage)
        auto(SimSensor.BATTERY_CURRENT, current)
        auto(SimSensor.BATTERY_CAPACITY, usedMah)
        auto(SimSensor.BATTERY_REMAINING, (remaining * 100.0).roundToInt().toDouble())
        auto(SimSensor.LINK_RSSI_1, rssi.roundToInt().toDouble())
        auto(SimSensor.LINK_RSSI_2, (rssi - 3.0).roundToInt().toDouble())
        auto(SimSensor.LINK_RX_QUALITY, quality.roundToInt().toDouble())
        auto(SimSensor.LINK_RX_SNR, snr.roundToInt().toDouble())
        auto(SimSensor.LINK_ANTENNA, 0.0)
        auto(SimSensor.LINK_TX_RSSI, (rssi - 2.0).roundToInt().toDouble())
        auto(SimSensor.LINK_TX_QUALITY, quality.roundToInt().toDouble())
        auto(SimSensor.LINK_TX_SNR, (snr - 1.0).roundToInt().toDouble())
        auto(SimSensor.ATTITUDE_PITCH, pitch)
        auto(SimSensor.ATTITUDE_ROLL, roll)
        auto(SimSensor.ATTITUDE_YAW, wrap180(heading))
        auto(SimSensor.TX_BATTERY, txBattery)
        auto(SimSensor.RX_BATTERY, this[SimSensor.BATTERY_VOLTAGE])
        auto(SimSensor.TX_LINK_QUALITY, this[SimSensor.LINK_RX_QUALITY])
        auto(SimSensor.TX_RSSI, this[SimSensor.LINK_RSSI_1])
    }

    /**
     * Sends the frames that are due after [dtMs] more milliseconds of an established link:
     * link statistics, attitude and vario 5 times per second, baro altitude 2.5 times, GPS and
     * airspeed twice, battery once, the flight mode every 2 s; besides the raw frames the SDK's
     * own [RadioEvent.TelemetryUpdated] (5 per second) and [RadioEvent.ElrsTelemetry] (1 per second).
     */
    fun sendFrames(dtMs: Long, emit: (RadioEvent) -> Unit) {
        for (item in Schedule.entries) {
            due[item.ordinal] -= dtMs
            if (due[item.ordinal] > 0) continue
            // A long pause does not produce a burst: at most one frame per kind and step.
            due[item.ordinal] = (due[item.ordinal] + item.periodMs).coerceAtLeast(1)
            received = true
            when (item) {
                Schedule.LINK -> emit(RadioEvent.CrsfReceived(linkStatisticsFrame()))
                Schedule.ATTITUDE -> emit(RadioEvent.CrsfReceived(frame(CrsfFrame.TYPE_ATTITUDE)))
                Schedule.VARIO -> emit(RadioEvent.CrsfReceived(frame(CrsfFrame.TYPE_VARIO)))
                Schedule.BARO -> emit(RadioEvent.CrsfReceived(frame(CrsfFrame.TYPE_BARO_ALTITUDE)))
                Schedule.GPS -> emit(RadioEvent.CrsfReceived(frame(CrsfFrame.TYPE_GPS)))
                Schedule.AIRSPEED -> emit(RadioEvent.CrsfReceived(frame(CrsfFrame.TYPE_AIRSPEED)))
                Schedule.BATTERY -> emit(RadioEvent.CrsfReceived(frame(CrsfFrame.TYPE_BATTERY)))
                Schedule.FLIGHT_MODE -> emit(RadioEvent.CrsfReceived(frame(CrsfFrame.TYPE_FLIGHT_MODE)))
                Schedule.LIST_UPDATED -> emit(RadioEvent.TelemetryUpdated)
                Schedule.SDK_SNAPSHOT -> emit(RadioEvent.ElrsTelemetry(sdkSnapshot()))
            }
        }
    }

    /** The CRSF frame of [type] for the current values; an empty-payload frame for unknown types. */
    fun frame(type: Int): CrsfFrame = when (type) {
        CrsfFrame.TYPE_GPS -> CrsfEncoder.gps(
            latitude = this[SimSensor.GPS_LATITUDE],
            longitude = this[SimSensor.GPS_LONGITUDE],
            groundSpeedKmh = this[SimSensor.GPS_GROUND_SPEED],
            headingDeg = this[SimSensor.GPS_HEADING],
            altitudeM = this[SimSensor.GPS_ALTITUDE],
            satellites = this[SimSensor.GPS_SATELLITES].roundToInt(),
        )
        CrsfFrame.TYPE_VARIO -> CrsfEncoder.vario(this[SimSensor.VERTICAL_SPEED])
        CrsfFrame.TYPE_BATTERY -> CrsfEncoder.battery(
            voltage = this[SimSensor.BATTERY_VOLTAGE],
            current = this[SimSensor.BATTERY_CURRENT],
            capacityMah = this[SimSensor.BATTERY_CAPACITY],
            remainingPercent = this[SimSensor.BATTERY_REMAINING].roundToInt(),
        )
        CrsfFrame.TYPE_BARO_ALTITUDE ->
            CrsfEncoder.baroAltitude(this[SimSensor.BARO_ALTITUDE], this[SimSensor.BARO_VERTICAL_SPEED])
        CrsfFrame.TYPE_AIRSPEED -> CrsfEncoder.airspeed(this[SimSensor.AIRSPEED])
        CrsfFrame.TYPE_LINK_STATISTICS -> linkStatisticsFrame()
        CrsfFrame.TYPE_ATTITUDE -> CrsfEncoder.attitude(
            pitchDeg = this[SimSensor.ATTITUDE_PITCH],
            rollDeg = this[SimSensor.ATTITUDE_ROLL],
            yawDeg = this[SimSensor.ATTITUDE_YAW],
        )
        CrsfFrame.TYPE_FLIGHT_MODE -> CrsfEncoder.flightMode(flightModeText)
        else -> CrsfEncoder.frame(type, ByteArray(0))
    }

    /** The simulated SDK telemetry list; empty until telemetry was received. */
    fun sdkItems(uiOnly: Boolean): List<TelemetryItem> {
        if (!received) return emptyList()
        val items = ArrayList<TelemetryItem>()
        for (s in SimSensor.entries) {
            val name = s.sdkName ?: continue
            if (uiOnly && !s.showInUi) continue
            items += TelemetryItem(
                hash = s.hash,
                index = items.size,
                name = name,
                displayName = name,
                value = values[s.ordinal],
                valueText = format(s, values[s.ordinal]),
                unit = s.unit,
                showInUi = s.showInUi,
            )
        }
        items += TelemetryItem(
            hash = FLIGHT_MODE_HASH,
            index = items.size,
            name = FLIGHT_MODE_NAME,
            displayName = FLIGHT_MODE_NAME,
            value = 0.0,
            valueText = flightModeText,
            unit = SimUnits.NONE,
            showInUi = true,
        )
        return items
    }

    /** [alarm] with its live read-back fields (`valTxt`, `nameTxt`, `currValue`) and list [index] filled in. */
    fun withLiveFields(alarm: TelemetryAlarm, index: Int): TelemetryAlarm {
        val sensor = sensorOfHash(alarm.hash)
        val value = if (sensor != null && received) values[sensor.ordinal] else 0.0
        return alarm.copy(
            index = index,
            value = value,
            valueText = if (sensor != null && received) format(sensor, value) else "",
            displayName = sensor?.sdkName ?: if (alarm.hash == FLIGHT_MODE_HASH) FLIGHT_MODE_NAME else alarm.name,
        )
    }

    /**
     * Checks the SDK-side notifications against the current values, as the SDK does while
     * telemetry arrives. A notification fires when its condition becomes true and, with
     * `intervalSec > 0`, again every interval while it stays true. Fired notifications go into
     * the queue read by [pollNotification].
     *
     * @return true if at least one notification was queued by this call
     */
    fun checkAlarms(alarms: List<TelemetryAlarm>, dtMs: Long): Boolean {
        alarmClockMs += dtMs
        var queued = false
        for ((index, alarm) in alarms.withIndex()) {
            val sensor = sensorOfHash(alarm.hash)
            val value = if (sensor != null) values[sensor.ordinal] else Double.NaN
            val active = !value.isNaN() && when (alarm.trigger) {
                CompareOp.GREATER -> value > alarm.triggerValue
                CompareOp.LESS -> value < alarm.triggerValue
                CompareOp.EQUAL -> value == alarm.triggerValue
                CompareOp.DISABLED -> false
            }
            val wasActive = alarmActive[index] == true
            val firedAt = alarmFiredAt[index]
            val repeat = active && wasActive && alarm.intervalSec > 0 && firedAt != null &&
                alarmClockMs - firedAt >= alarm.intervalSec * 1000L
            if ((active && !wasActive) || repeat) {
                alarmFiredAt[index] = alarmClockMs
                if (notifications.size >= MAX_NOTIFICATIONS) notifications.removeFirst()
                notifications.addLast(withLiveFields(alarm, index))
                queued = true
            }
            alarmActive[index] = active
        }
        return queued
    }

    /** Forgets which notifications are active (the notification list changed). */
    fun alarmsChanged() {
        alarmActive.clear()
        alarmFiredAt.clear()
    }

    /** Takes the oldest triggered notification, or null. */
    fun pollNotification(): TelemetryAlarm? = notifications.removeFirstOrNull()

    private fun linkStatisticsFrame(): CrsfFrame = CrsfEncoder.linkStatistics(
        uplinkRssi1 = this[SimSensor.LINK_RSSI_1].roundToInt(),
        uplinkRssi2 = this[SimSensor.LINK_RSSI_2].roundToInt(),
        uplinkQuality = this[SimSensor.LINK_RX_QUALITY].roundToInt(),
        uplinkSnr = this[SimSensor.LINK_RX_SNR].roundToInt(),
        activeAntenna = this[SimSensor.LINK_ANTENNA].roundToInt(),
        rfMode = this[SimSensor.LINK_RF_MODE].roundToInt(),
        txPowerMw = this[SimSensor.LINK_TX_POWER].roundToInt(),
        downlinkRssi = this[SimSensor.LINK_TX_RSSI].roundToInt(),
        downlinkQuality = this[SimSensor.LINK_TX_QUALITY].roundToInt(),
        downlinkSnr = this[SimSensor.LINK_TX_SNR].roundToInt(),
    )

    private fun sdkSnapshot() = CrsfTelemetrySnapshot(
        latitude = this[SimSensor.GPS_LATITUDE],
        longitude = this[SimSensor.GPS_LONGITUDE],
        pitch = this[SimSensor.ATTITUDE_PITCH],
        roll = this[SimSensor.ATTITUDE_ROLL],
        yaw = this[SimSensor.ATTITUDE_YAW],
        voltage = this[SimSensor.BATTERY_VOLTAGE],
        current = this[SimSensor.BATTERY_CURRENT],
    )

    private fun auto(sensor: SimSensor, value: Double) {
        if (pinned[sensor.ordinal]) return
        val coerced = value.coerceIn(sensor.min, sensor.max)
        if (values[sensor.ordinal] != coerced) {
            values[sensor.ordinal] = coerced
            changed = true
        }
    }

    private fun sensorOfHash(hash: Long): SimSensor? =
        if (hash == 0L) null else SimSensor.entries.firstOrNull { it.hash == hash }

    private fun format(sensor: SimSensor, value: Double): String =
        String.format(Locale.US, "%.${sensor.precision}f", value) + SimUnits.symbol(sensor.unit)

    private fun wrap360(degrees: Double): Double {
        val r = degrees % 360.0
        return if (r < 0) r + 360.0 else r
    }

    private fun wrap180(degrees: Double): Double {
        val r = wrap360(degrees)
        return if (r > 180.0) r - 360.0 else r
    }

    /** What is sent how often once the link is up; the offsets spread the frames over the ticks. */
    private enum class Schedule(val periodMs: Long, val offsetMs: Long) {
        LINK(200, 40),
        ATTITUDE(200, 80),
        VARIO(200, 120),
        BARO(400, 160),
        GPS(500, 200),
        AIRSPEED(500, 240),
        BATTERY(1000, 280),
        FLIGHT_MODE(2000, 320),
        LIST_UPDATED(200, 200),
        SDK_SNAPSHOT(1000, 400),
    }

    companion object {
        /** Start point of the simulated flights (a hill with a model flying site). */
        const val HOME_LATITUDE = 50.49830
        const val HOME_LONGITUDE = 9.95360
        const val HOME_ELEVATION = 380.0

        const val DEFAULT_FLIGHT_MODE = "MANU"
        const val FLIGHT_MODE_NAME = "FM"

        /** Notifications the queue keeps; older ones are dropped. */
        const val MAX_NOTIFICATIONS = 32

        private const val TWO_PI = 2.0 * PI
        private const val METERS_PER_DEGREE = 111_320.0
        private const val BATTERY_MAH = 2200.0
        private const val HOME_OFFSET_M = 90.0

        /** Hash of the flight-mode item of the simulated telemetry list. */
        val FLIGHT_MODE_HASH: Long = SimHash.of(FLIGHT_MODE_NAME)
    }
}

/** The simulator's stand-in for the SDK's 64-bit `hashValue` of a telemetry item. */
internal object SimHash {

    /** 64-bit FNV-1a hash of an item name; never 0 (0 means "no item" in the SDK API). */
    fun of(name: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (c in name) {
            hash = hash xor (c.code.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return if (hash == 0L) 1L else hash
    }
}
