package org.openax12.feature.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.TelemetryLayout

class WidgetLayoutOpsTest {

    @Test
    fun `a widget must lie inside the grid and not overlap another`() {
        val p = page("p", widget("a", 0, 0, 2, 2))
        assertTrue(WidgetLayoutOps.canPlace(p, widget("b", 2, 0)))
        assertFalse(WidgetLayoutOps.canPlace(p, widget("b", 1, 1)))
        assertFalse(WidgetLayoutOps.canPlace(p, widget("b", 6, 0)))
        assertFalse(WidgetLayoutOps.canPlace(p, widget("b", 5, 3, 2, 1)))
        assertFalse(WidgetLayoutOps.canPlace(p, widget("b", -1, 0)))
        // A widget never collides with itself.
        assertTrue(WidgetLayoutOps.canPlace(p, widget("a", 1, 0, 2, 2)))
    }

    @Test
    fun `free spot is found row by row`() {
        val p = page("p", widget("a", 0, 0, 3, 1), widget("b", 3, 0, 3, 1))
        assertEquals(0 to 1, WidgetLayoutOps.findFreeSpot(p, 2, 2))
        assertNull(WidgetLayoutOps.findFreeSpot(p, 7, 1))
        assertEquals(3 to 0, WidgetLayoutOps.findFreeSpot(p, 3, 1, ignoreId = "b"))
    }

    @Test
    fun `add shrinks a widget that does not fit until it does`() {
        // Only a 1 x 1 hole is left: row 0 is full, row 1 has one free cell at the end.
        val p = page(
            "p",
            widget("a", 0, 0, 6, 1), widget("b", 0, 1, 5, 1), widget("c", 0, 2, 6, 2),
        )
        val added = WidgetLayoutOps.add(p, widget("n", 0, 0, 3, 2))
        assertNotNull(added)
        val placed = added!!.widgets.last()
        assertEquals(listOf(5, 1, 1, 1), listOf(placed.x, placed.y, placed.w, placed.h))
    }

    @Test
    fun `add prefers the largest area that fits`() {
        val p = page("p", widget("a", 0, 0, 5, 4), widget("b", 5, 0, 1, 1))
        val placed = WidgetLayoutOps.add(p, widget("n", 0, 0, 2, 2))!!.widgets.last()
        // Free: column 5 rows 1..3 -> a 1 x 3 strip; 2 x 2 does not fit, 1 x 2 does.
        assertEquals(listOf(5, 1, 1, 2), listOf(placed.x, placed.y, placed.w, placed.h))
    }

    @Test
    fun `add returns null on a full page`() {
        val p = page("p", widget("a", 0, 0, 6, 4))
        assertNull(WidgetLayoutOps.add(p, widget("n", 0, 0)))
    }

    @Test
    fun `move is refused outside the grid and onto other widgets`() {
        val p = page("p", widget("a", 0, 0, 2, 1), widget("b", 3, 0))
        assertEquals(1, WidgetLayoutOps.moveBy(p, "a", 0, 1)!!.widgets.first { it.id == "a" }.y)
        assertNull(WidgetLayoutOps.moveBy(p, "a", 2, 0))
        assertNull(WidgetLayoutOps.moveBy(p, "a", -1, 0))
        assertNull(WidgetLayoutOps.moveBy(p, "a", 0, 4))
        assertNull(WidgetLayoutOps.moveBy(p, "missing", 0, 1))
    }

    @Test
    fun `resize checks bounds and neighbours`() {
        val p = page("p", widget("a", 0, 0), widget("b", 2, 0))
        assertNotNull(WidgetLayoutOps.resizeTo(p, "a", 2, 2))
        assertNull(WidgetLayoutOps.resizeTo(p, "a", 3, 1))
        assertNull(WidgetLayoutOps.resizeTo(p, "a", 0, 1))
        assertNull(WidgetLayoutOps.resizeTo(p, "b", 1, 5))
    }

    @Test
    fun `grid can only shrink as far as the widgets allow`() {
        val p = page("p", widget("a", 4, 2, 2, 1))
        assertNull(WidgetLayoutOps.resizeGrid(p, 5, 4))
        assertNull(WidgetLayoutOps.resizeGrid(p, 6, 2))
        assertEquals(6, WidgetLayoutOps.resizeGrid(p, 6, 3)!!.columns)
        assertNull(WidgetLayoutOps.resizeGrid(p, 9, 4))
        assertNull(WidgetLayoutOps.resizeGrid(p, 6, 0))
    }

    @Test
    fun `pin order continues after the last pinned widget`() {
        val layout = TelemetryLayout(
            pages = listOf(
                page("p1", widget("a", 0, 0, pinned = true, pinOrder = 4), widget("b", 1, 0)),
                page("p2", widget("c", 0, 0, pinned = true, pinOrder = 1)),
            ),
        )
        assertEquals(5, WidgetLayoutOps.nextPinOrder(layout))
        assertEquals(0, WidgetLayoutOps.nextPinOrder(TelemetryLayout()))
    }
}
