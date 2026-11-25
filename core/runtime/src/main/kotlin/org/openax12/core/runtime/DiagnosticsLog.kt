package org.openax12.core.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * One line of the diagnostics history.
 *
 * @property timeMs wall-clock time of the entry
 * @property source short origin tag, see [DiagnosticsLog] for the ones the graph uses
 * @property message English diagnostic text (the `detail` / `message` strings of the result types)
 */
data class DiagnosticEntry(
    val timeMs: Long,
    val source: String,
    val message: String,
)

/**
 * In-memory ring buffer of errors and notable events ("connection state and last errors" of the
 * diagnostics screen, SPEC section 8), exposed as `AppGraph.diagnostics`. Not persisted; the
 * diagnostics export writes [entries] into its report.
 *
 * `DefaultAppGraph` records the radio, session, library and audio failures itself (see there).
 * Features record their own `DataResult.Failure` details where the user only sees a generic
 * message. Thread-safe.
 */
interface DiagnosticsLog {

    /** The newest [CAPACITY] entries, oldest first. */
    val entries: StateFlow<List<DiagnosticEntry>>

    /** Appends an entry stamped with the current wall-clock time; the oldest one is dropped when full. */
    fun record(source: String, message: String)

    /** Removes all entries. */
    fun clear()

    companion object {
        /** Number of entries kept. */
        const val CAPACITY: Int = 200

        /** [DiagnosticEntry.source] of connection errors and failed radio tasks. */
        const val SOURCE_RADIO: String = "radio"

        /** [DiagnosticEntry.source] of `ConfigSession` failures. */
        const val SOURCE_SESSION: String = "session"

        /** [DiagnosticEntry.source] of model library / storage failures. */
        const val SOURCE_LIBRARY: String = "library"

        /** [DiagnosticEntry.source] of audio / text-to-speech failures. */
        const val SOURCE_AUDIO: String = "audio"

        /** [DiagnosticEntry.source] of runtime engine problems (missing sound file, logging failures). */
        const val SOURCE_RUNTIME: String = "runtime"
    }
}
