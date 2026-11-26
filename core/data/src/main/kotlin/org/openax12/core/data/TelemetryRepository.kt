package org.openax12.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.openax12.core.model.GpsFix
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorValue

/** One sample of a sensor's history. [timestampMs] is monotonic (same base as `LiveSnapshot.timestampMs`). */
data class HistoryPoint(val timestampMs: Long, val value: Double)

/**
 * Counters of the raw CRSF stream, for the diagnostics screen.
 *
 * @property framesByType number of frames received per CRSF frame type
 * @property crcErrors frames dropped because of a bad length or CRC
 * @property unknownRotorflightSids sensor ids of Rotorflight custom frames (0x88) that the decoder does not know
 * @property lastFrameAgeMs age of the newest frame at the time of the update; -1 if none was received
 * @property crcCheckDisabled true if the CRC comparison was switched off because not a single
 *   frame of the stream ever passed it (the SDK then evidently does not hand the CRC byte through,
 *   assumption 27); length checks still apply and [crcErrors] then counts bad lengths only
 */
data class CrsfStats(
    val totalFrames: Long = 0,
    val framesByType: Map<Int, Long> = emptyMap(),
    val crcErrors: Long = 0,
    val unknownRotorflightSids: Set<Int> = emptySet(),
    val lastFrameAgeMs: Long = -1,
    val crcCheckDisabled: Boolean = false,
)

/**
 * The unified sensor registry: values from the SDK telemetry list, from decoded raw CRSF frames
 * (GPS 0x02, vario 0x07, battery 0x08, baro altitude 0x09, airspeed 0x0A, link statistics 0x14,
 * attitude 0x1E, flight mode 0x21, Rotorflight custom 0x88) and internal values (TX voltage, LQ,
 * RSSI, timers, counters; flight mode and logical switches as published by the runtime).
 *
 * ## Update rates
 * - [sensors] and [sensorList] are throttled (a few updates per second) and safe to collect in UI.
 * - Input sources (`in.*`) and channels (`ch.*`) change with every live frame and are therefore
 *   **not** part of [sensors]; [sensor] and [value] serve them straight from `Radio.live`.
 *
 * Per-model overrides (name, unit, precision, hidden) from the current model's extras are applied
 * to [sensorList] and to the unit / precision of the values. Values are in the sensor's native
 * (metric) unit; convert for display with `UnitConversion`.
 *
 * Main-safe; callable from any dispatcher.
 */
interface TelemetryRepository {

    /** Current values of all known sensors except the live ones (see class doc). Throttled. */
    val sensors: StateFlow<Map<SensorId, SensorValue>>

    /** Metadata of all known sensors (including inputs, channels, timers), sorted for display. Changes rarely. */
    val sensorList: StateFlow<List<SensorInfo>>

    /** True while receiver telemetry is lost (no link); sensor values keep their last value with `lost = true`. */
    val linkLost: StateFlow<Boolean>

    /** The last valid GPS position, persisted across app restarts (lost-model finder); null if never seen. */
    val lastKnownPosition: StateFlow<GpsFix?>

    /** Home position used for distance / bearing: the first valid fix after a reset, or set by [setHome]. */
    val homePosition: StateFlow<GpsFix?>

    /** Counters of the raw CRSF stream. Updated about once per second. */
    val crsfStats: StateFlow<CrsfStats>

    /**
     * Cheap flow of one sensor: emits only when that sensor's value changed (conflated). Works
     * for every namespace including `in.*` / `ch.*`. Emits null while the sensor is unknown.
     */
    fun sensor(id: SensorId): Flow<SensorValue?>

    /** The value of one sensor right now; null if unknown. Works for every namespace. */
    fun value(id: SensorId): SensorValue?

    /** Metadata of one sensor; null if unknown. A well-known sensor that has not reported yet has its default metadata. */
    fun info(id: SensorId): SensorInfo?

    /**
     * Recent samples of a sensor for graphs, oldest first. The buffer holds at least the last
     * [HISTORY_SECONDS] seconds at up to [HISTORY_HZ] samples per second per sensor.
     *
     * @param windowSec how far back to go; clamped to the buffer length
     */
    fun history(id: SensorId, windowSec: Int = HISTORY_SECONDS): List<HistoryPoint>

    /** Resets min / max of one sensor, or of all sensors when [id] is null. */
    suspend fun resetMinMax(id: SensorId? = null)

    /** Sets the home position to [fix], or to the current position when null (no-op without a fix). */
    suspend fun setHome(fix: GpsFix? = null)

    /** Reset for a new flight: min / max, history and home position (the last known position is kept). */
    suspend fun resetFlight()

    /**
     * Publishes an internal value into the registry (used by the runtime for the flight mode and
     * the logical switches). The sensor appears in [sensorList] on first publication.
     */
    fun publish(id: SensorId, value: SensorValue, info: SensorInfo? = null)

    /** Starts consuming the radio's events and live data. Idempotent. Called by the app graph. */
    fun start()

    /** Stops consuming. Values are kept. */
    fun stop()

    companion object {
        /** Minimum length of the per-sensor history buffer in seconds. */
        const val HISTORY_SECONDS: Int = 300

        /** Maximum sample rate of the history buffer. */
        const val HISTORY_HZ: Int = 5
    }
}
