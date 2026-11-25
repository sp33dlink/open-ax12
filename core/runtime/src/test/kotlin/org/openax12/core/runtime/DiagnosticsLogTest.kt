package org.openax12.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {

    @Test
    fun keepsTheNewestEntriesOldestFirst() {
        var now = 0L
        val log = DefaultDiagnosticsLog { ++now }
        repeat(DiagnosticsLog.CAPACITY + 5) { log.record(DiagnosticsLog.SOURCE_RUNTIME, "entry $it") }

        val entries = log.entries.value
        assertEquals(DiagnosticsLog.CAPACITY, entries.size)
        assertEquals("entry 5", entries.first().message)
        assertEquals("entry ${DiagnosticsLog.CAPACITY + 4}", entries.last().message)
        assertTrue(entries.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs })
    }

    @Test
    fun clearRemovesEverything() {
        val log = DefaultDiagnosticsLog()
        log.record(DiagnosticsLog.SOURCE_RADIO, "x")
        log.clear()
        assertTrue(log.entries.value.isEmpty())
    }
}
