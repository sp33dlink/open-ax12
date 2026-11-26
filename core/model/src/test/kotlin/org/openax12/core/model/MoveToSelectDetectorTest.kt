package org.openax12.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveToSelectDetectorTest {

    private fun frame(vararg logic: Pair<SourceId, Int>, sequence: Long = 1) =
        LiveSnapshot.of(sequence = sequence, logic = logic.toMap())

    private fun detector(sources: List<SourceId>, isPresent: (SourceId) -> Boolean = { true }) =
        MoveToSelectDetector(sources, isPresent = isPresent)

    @Test
    fun `the first frame is the baseline and selects nothing`() {
        val d = detector(listOf(SourceId.THR, SourceId.AIL))
        assertNull(d.update(frame(SourceId.THR to 80, SourceId.AIL to -50)))
        assertNull(d.update(frame(SourceId.THR to 80, SourceId.AIL to -50)))
    }

    @Test
    fun `a drift of 9 percent does nothing`() {
        val d = detector(listOf(SourceId.AIL))
        d.update(frame(SourceId.AIL to 0))
        assertNull(d.update(frame(SourceId.AIL to 9)))
        assertNull(d.update(frame(SourceId.AIL to -9)))
    }

    @Test
    fun `exactly the threshold does nothing, one more selects`() {
        val d = detector(listOf(SourceId.AIL))
        d.update(frame(SourceId.AIL to 20))
        assertNull(d.update(frame(SourceId.AIL to 30)))
        assertEquals(SourceId.AIL, d.update(frame(SourceId.AIL to 31)))
    }

    @Test
    fun `movement is measured from the baseline, not from the previous frame`() {
        val d = detector(listOf(SourceId.AIL))
        d.update(frame(SourceId.AIL to 0))
        assertNull(d.update(frame(SourceId.AIL to 6)))
        assertNull(d.update(frame(SourceId.AIL to 9)))
        assertEquals(SourceId.AIL, d.update(frame(SourceId.AIL to 12)))
    }

    @Test
    fun `movement in both directions counts`() {
        val d = detector(listOf(SourceId.pot(1)))
        d.update(frame(SourceId.pot(1) to 50))
        assertEquals(SourceId.pot(1), d.update(frame(SourceId.pot(1) to 30)))
    }

    @Test
    fun `a switch selects when its position changes, not for a smaller move`() {
        val d = detector(listOf(SourceId.SA))
        d.update(frame(SourceId.SA to -100))
        assertNull(d.update(frame(SourceId.SA to -60)))
        assertEquals(SourceId.SA, d.update(frame(SourceId.SA to 0)))
    }

    @Test
    fun `a switch selects when it crosses 50 even with a small jump`() {
        val d = detector(listOf(SourceId.SB))
        d.update(frame(SourceId.SB to 49))
        assertEquals(SourceId.SB, d.update(frame(SourceId.SB to 51)))
    }

    @Test
    fun `a switch from up to mid and back to the baseline position`() {
        val d = detector(listOf(SourceId.SC))
        d.update(frame(SourceId.SC to 100))
        assertEquals(SourceId.SC, d.update(frame(SourceId.SC to 0)))
    }

    @Test
    fun `the SP bar selects for a different button only`() {
        val d = detector(listOf(SourceId.SP))
        d.update(frame(SourceId.SP to SpButtonBar.logicOf(3)))
        assertNull(d.update(frame(SourceId.SP to SpButtonBar.logicOf(3) + 5)))
        assertEquals(SourceId.SP, d.update(frame(SourceId.SP to SpButtonBar.logicOf(4))))
    }

    @Test
    fun `the largest movement wins`() {
        val d = detector(listOf(SourceId.AIL, SourceId.ELE, SourceId.SA))
        d.update(frame(SourceId.AIL to 0, SourceId.ELE to 0, SourceId.SA to -100))
        assertEquals(SourceId.ELE, d.update(frame(SourceId.AIL to 30, SourceId.ELE to -45, SourceId.SA to -100)))
    }

    @Test
    fun `a switch flip beats a smaller stick movement in the same frame`() {
        val d = detector(listOf(SourceId.AIL, SourceId.SA))
        d.update(frame(SourceId.AIL to 0, SourceId.SA to -100))
        assertEquals(SourceId.SA, d.update(frame(SourceId.AIL to 40, SourceId.SA to 100)))
    }

    @Test
    fun `sources the picker does not offer are ignored`() {
        val d = detector(listOf(SourceId.AIL))
        d.update(frame(SourceId.AIL to 0, SourceId.ELE to 0))
        assertNull(d.update(frame(SourceId.AIL to 0, SourceId.ELE to 80)))
    }

    @Test
    fun `absent sources are ignored`() {
        val d = detector(listOf(SourceId.AIL, SourceId.ELE), isPresent = { it != SourceId.ELE })
        d.update(frame(SourceId.AIL to 0, SourceId.ELE to 0))
        assertNull(d.update(frame(SourceId.AIL to 0, SourceId.ELE to 80)))
        assertEquals(SourceId.AIL, d.update(frame(SourceId.AIL to 50, SourceId.ELE to 80)))
    }

    @Test
    fun `counters are never watched`() {
        val d = detector(listOf(SourceId.counter(1)))
        assertTrue(d.isEmpty)
        d.update(frame(SourceId.counter(1) to 0))
        assertNull(d.update(frame(SourceId.counter(1) to 90)))
    }

    @Test
    fun `invalid indices are dropped`() {
        val d = MoveToSelectDetector(listOf(SourceId(-1), SourceId(SOURCE_COUNT + 3)))
        assertTrue(d.isEmpty)
        assertNull(d.update(frame()))
    }

    @Test
    fun `the empty placeholder frame is skipped and does not become the baseline`() {
        val d = detector(listOf(SourceId.AIL))
        assertNull(d.update(LiveSnapshot.EMPTY))
        d.update(frame(SourceId.AIL to 40))
        assertNull(d.update(frame(SourceId.AIL to 45)))
    }

    @Test
    fun `reset takes a new baseline`() {
        val d = detector(listOf(SourceId.AIL))
        d.update(frame(SourceId.AIL to 0))
        assertEquals(SourceId.AIL, d.update(frame(SourceId.AIL to 50)))
        d.reset()
        assertNull(d.update(frame(SourceId.AIL to 50)))
        assertNull(d.update(frame(SourceId.AIL to 55)))
    }

    @Test
    fun `a stick that is already deflected when the picker opens does not select`() {
        val d = detector(listOf(SourceId.THR))
        d.update(frame(SourceId.THR to 100))
        assertNull(d.update(frame(SourceId.THR to 100)))
    }
}
