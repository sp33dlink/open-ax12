package org.openax12.feature.telemetry

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TelemetryViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private fun vm(store: FakeExtrasStore) = TelemetryViewModel(store)

    @Test
    fun `the first page is shown when none is selected`() {
        val vm = vm(FakeExtrasStore(extrasWith(page("a"), page("b"))))
        assertEquals("a", vm.state.value.page?.id)
        vm.onAction(TelemetryAction.SelectPage("b"))
        assertEquals("b", vm.state.value.page?.id)
        vm.onAction(TelemetryAction.SelectPage("gone"))
        assertEquals("a", vm.state.value.page?.id)
    }

    @Test
    fun `adding a page selects it and switches to edit mode`() {
        val store = FakeExtrasStore()
        val vm = vm(store)
        vm.onAction(TelemetryAction.AddPage)
        val state = vm.state.value
        assertEquals(1, store.layout.pages.size)
        assertEquals(store.layout.pages[0].id, state.page?.id)
        assertTrue(state.editMode)
        assertEquals(6, store.layout.pages[0].columns)
    }

    @Test
    fun `leaving edit mode clears the selection`() {
        val vm = vm(FakeExtrasStore(extrasWith(page("a", widget("w", 0, 0)))))
        vm.onAction(TelemetryAction.SetEditMode(true))
        vm.onAction(TelemetryAction.SelectWidget("w"))
        assertEquals("w", vm.state.value.selectedWidget?.id)
        vm.onAction(TelemetryAction.SetEditMode(false))
        assertNull(vm.state.value.selectedWidget)
    }

    @Test
    fun `a selection of a widget that is gone is dropped`() {
        val store = FakeExtrasStore(extrasWith(page("a", widget("w", 0, 0))))
        val vm = vm(store)
        vm.onAction(TelemetryAction.SelectWidget("w"))
        vm.onAction(TelemetryAction.DeleteWidget("w"))
        assertNull(vm.state.value.selectedWidgetId)
        assertTrue(store.layout.pages[0].widgets.isEmpty())
    }

    @Test
    fun `moving a widget onto another one is refused with a message`() = runTest {
        val store = FakeExtrasStore(extrasWith(page("a", widget("w", 0, 0), widget("x", 1, 0))))
        val vm = vm(store)
        vm.onAction(TelemetryAction.MoveWidget("w", 1, 0))
        assertEquals(0, store.layout.widget("w")!!.x)
        val message = vm.events.first()
        assertEquals(R.string.telemetry_msg_no_room_move, message.res)
        assertTrue(message.isError)
        vm.onAction(TelemetryAction.MoveWidget("w", 0, 1))
        assertEquals(1, store.layout.widget("w")!!.y)
    }

    @Test
    fun `resizing respects the grid`() {
        val store = FakeExtrasStore(extrasWith(page("a", widget("w", 5, 0))))
        val vm = vm(store)
        vm.onAction(TelemetryAction.ResizeWidget("w", 2, 1))
        assertEquals(1, store.layout.widget("w")!!.w)
        vm.onAction(TelemetryAction.ResizeWidget("w", 1, 3))
        assertEquals(3, store.layout.widget("w")!!.h)
    }

    @Test
    fun `pinning orders widgets after the existing pins and unpinning keeps the others`() {
        val store = FakeExtrasStore(
            extrasWith(
                page("a", widget("w1", 0, 0, pinned = true, pinOrder = 3), widget("w2", 1, 0)),
                page("b", widget("w3", 0, 0)),
            ),
        )
        val vm = vm(store)
        vm.onAction(TelemetryAction.TogglePin("w2"))
        vm.onAction(TelemetryAction.TogglePin("w3"))
        assertEquals(listOf("w1", "w2", "w3"), store.layout.pinnedWidgets.map { it.id })
        assertEquals(5, store.layout.widget("w3")!!.pinOrder)
        vm.onAction(TelemetryAction.TogglePin("w1"))
        assertEquals(listOf("w2", "w3"), store.layout.pinnedWidgets.map { it.id })
        assertEquals(2, vm.state.value.pinnedCount)
    }

    @Test
    fun `pinning more than the home card shows warns`() = runTest {
        val widgets = (0 until 7).map { widget("w$it", it % 6, it / 6) }.toTypedArray()
        val store = FakeExtrasStore(extrasWith(page("a", *widgets)))
        val vm = vm(store)
        widgets.forEach { vm.onAction(TelemetryAction.TogglePin(it.id)) }
        val message = vm.events.first()
        assertEquals(R.string.telemetry_msg_pin_limit, message.res)
        assertEquals(listOf(6), message.args)
    }

    @Test
    fun `page settings change name and grid but never drop widgets off the grid`() = runTest {
        val store = FakeExtrasStore(extrasWith(page("a", widget("w", 5, 3))))
        val vm = vm(store)
        vm.onAction(TelemetryAction.UpdatePage("a", "  Flight ", 8, 4))
        assertEquals("Flight", store.layout.pages[0].name)
        assertEquals(8, store.layout.pages[0].columns)
        vm.onAction(TelemetryAction.UpdatePage("a", "Flight", 4, 4))
        assertEquals(8, store.layout.pages[0].columns)
        assertEquals(R.string.telemetry_msg_grid_too_small, vm.events.first().res)
    }

    @Test
    fun `pages can be reordered and deleted`() {
        val store = FakeExtrasStore(extrasWith(page("a"), page("b"), page("c")))
        val vm = vm(store)
        vm.onAction(TelemetryAction.MovePage("c", -1))
        assertEquals(listOf("a", "c", "b"), store.layout.pages.map { it.id })
        vm.onAction(TelemetryAction.MovePage("a", -1))
        assertEquals(listOf("a", "c", "b"), store.layout.pages.map { it.id })
        vm.onAction(TelemetryAction.SelectPage("c"))
        vm.onAction(TelemetryAction.DeletePage("c"))
        assertEquals(listOf("a", "b"), store.layout.pages.map { it.id })
        assertEquals("a", vm.state.value.page?.id)
        assertFalse(vm.state.value.editMode)
    }
}
