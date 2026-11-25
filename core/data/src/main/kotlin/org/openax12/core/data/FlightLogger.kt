package org.openax12.core.data

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/** A CSV flight log in `…/OpenAX12/logs/`. */
data class FlightLogFile(
    val name: String,
    val path: String,
    val modelName: String = "",
    val startedMs: Long = 0,
    val sizeBytes: Long = 0,
)

/** State of the flight logger. */
sealed interface FlightLogState {

    /** Not recording. */
    data object Idle : FlightLogState

    /**
     * Recording.
     *
     * @property startedMs wall-clock start time
     * @property rows rows written so far (updated about once per second)
     */
    data class Recording(val file: FlightLogFile, val startedMs: Long, val rows: Long = 0) : FlightLogState

    /** Recording stopped because of an error (storage full, permission lost). */
    data class Failed(val error: DataError, val detail: String = "") : FlightLogState

    /** True for [Recording]. */
    val isRecording: Boolean get() = this is Recording
}

/**
 * Optional CSV log per flight with EdgeTX-like columns: date, time, every known sensor, the four
 * sticks, the switches, the 16 channels, flight mode and TX voltage. One row per
 * `AppSettings.logIntervalMs`.
 *
 * Started and stopped by the runtime (setting "logging" while linked, or the special function
 * "Start / stop logging"). Main-safe.
 */
interface FlightLogger {

    /** Whether a log is being recorded right now. */
    val state: StateFlow<FlightLogState>

    /**
     * Starts a new log file named `<model>-<yyyy-MM-dd>-<HHmmss>.csv`. If a log is already being
     * recorded that one is returned.
     */
    suspend fun start(): DataResult<FlightLogFile>

    /** Stops recording and closes the file. No-op when idle. */
    suspend fun stop()

    /** All log files, newest first. */
    suspend fun logs(): List<FlightLogFile>

    /** Deletes a log file (not the one being recorded). */
    suspend fun delete(log: FlightLogFile): DataResult<Unit>

    /** A shareable `content://` URI for a log file. */
    suspend fun share(log: FlightLogFile): DataResult<Uri>
}
