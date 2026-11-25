package org.openax12.core.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The in-memory [DiagnosticsLog]: a ring buffer of [DiagnosticsLog.CAPACITY] entries, thread-safe
 * (updates are atomic compare-and-set loops on a state flow).
 *
 * @param clock wall-clock time source; replaceable for tests
 */
class DefaultDiagnosticsLog(
    private val clock: () -> Long = System::currentTimeMillis,
) : DiagnosticsLog {

    private val state = MutableStateFlow<List<DiagnosticEntry>>(emptyList())

    override val entries: StateFlow<List<DiagnosticEntry>> = state.asStateFlow()

    override fun record(source: String, message: String) {
        val entry = DiagnosticEntry(timeMs = clock(), source = source, message = message)
        state.update { (it + entry).takeLast(DiagnosticsLog.CAPACITY) }
    }

    override fun clear() {
        state.value = emptyList()
    }
}
