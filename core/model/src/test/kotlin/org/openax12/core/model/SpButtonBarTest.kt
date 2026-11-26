package org.openax12.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SpButtonBarTest {

    @Test
    fun `each of the six levels gives its button`() {
        val levels = listOf(-100, -60, -20, 20, 60, 100)
        levels.forEachIndexed { i, logic -> assertEquals("logic $logic", i + 1, SpButtonBar.buttonFromLogic(logic)) }
    }

    @Test
    fun `values between the levels give the nearest button`() {
        assertEquals(1, SpButtonBar.buttonFromLogic(-85))
        assertEquals(2, SpButtonBar.buttonFromLogic(-79))
        assertEquals(2, SpButtonBar.buttonFromLogic(-41))
        assertEquals(3, SpButtonBar.buttonFromLogic(-39))
        assertEquals(3, SpButtonBar.buttonFromLogic(-1))
        assertEquals(4, SpButtonBar.buttonFromLogic(1))
        assertEquals(4, SpButtonBar.buttonFromLogic(39))
        assertEquals(5, SpButtonBar.buttonFromLogic(41))
        assertEquals(5, SpButtonBar.buttonFromLogic(79))
        assertEquals(6, SpButtonBar.buttonFromLogic(81))
    }

    @Test
    fun `a value exactly between two buttons gives the lower one`() {
        assertEquals(1, SpButtonBar.buttonFromLogic(-80))
        assertEquals(3, SpButtonBar.buttonFromLogic(0))
        assertEquals(5, SpButtonBar.buttonFromLogic(80))
    }

    @Test
    fun `values outside the range are clamped to the end buttons`() {
        assertEquals(1, SpButtonBar.buttonFromLogic(-500))
        assertEquals(6, SpButtonBar.buttonFromLogic(500))
        assertEquals(6, SpButtonBar.buttonFromLogic(Int.MAX_VALUE / 2))
    }

    @Test
    fun `logicOf is the inverse of buttonFromLogic`() {
        for (button in 1..SpButtonBar.BUTTON_COUNT) {
            assertEquals(button, SpButtonBar.buttonFromLogic(SpButtonBar.logicOf(button)))
        }
        assertEquals(-100, SpButtonBar.logicOf(0))
        assertEquals(100, SpButtonBar.logicOf(9))
    }

    @Test
    fun `a source that is absent reads zero and still maps to a valid button`() {
        // LiveSnapshot reports 0 for every source of a frame that has not arrived; the home panel
        // hides the row for a disabled SP, but the mapping itself must never fail.
        assertEquals(3, SpButtonBar.buttonFromLogic(LiveSnapshot.EMPTY.logic(SourceId(Sources.SP))))
        assertEquals(3, SpButtonBar.buttonFromLogic(LiveSnapshot.EMPTY.logicAt(SOURCE_COUNT + 5)))
    }
}
