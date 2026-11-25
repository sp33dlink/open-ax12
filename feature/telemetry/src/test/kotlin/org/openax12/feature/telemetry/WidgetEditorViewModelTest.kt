package org.openax12.feature.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.Sensors
import org.openax12.core.model.WidgetOptions
import org.openax12.core.model.WidgetType

class WidgetEditorViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val sensors = MutableStateFlow(listOf(SensorInfo(Sensors.BATTERY_VOLTAGE, "RxBt")))

    private fun add(store: FakeExtrasStore, pageId: String = "p") = WidgetEditorViewModel(store, sensors, pageId, null).also { it.start() }

    private fun edit(store: FakeExtrasStore, widgetId: String) = WidgetEditorViewModel(store, sensors, "p", widgetId).also { it.start() }

    @Test
    fun `a new widget starts as a value widget on the first free spot`() {
        val store = FakeExtrasStore(extrasWith(page("p", widget("a", 0, 0, 2, 1))))
        val vm = add(store)
        val draft = vm.state.value.draft!!
        assertEquals(WidgetType.VALUE, draft.type)
        assertEquals(listOf(Sensors.BARO_ALTITUDE), draft.sensors)
        assertEquals(2 to 0, draft.x to draft.y)
        assertTrue(vm.state.value.isNew)
        assertFalse(vm.state.value.dirty)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `changing the type of a new widget takes the default size and sensors of that type`() {
        val vm = add(FakeExtrasStore(extrasWith(page("p"))))
        vm.onAction(WidgetEditorAction.SetType(WidgetType.HORIZON))
        val draft = vm.state.value.draft!!
        assertEquals(2 to 2, draft.w to draft.h)
        assertEquals(listOf(Sensors.ATTITUDE_PITCH, Sensors.ATTITUDE_ROLL), draft.sensors)
        assertTrue(vm.state.value.dirty)
    }

    @Test
    fun `an existing widget keeps its size when the type changes`() {
        val store = FakeExtrasStore(extrasWith(page("p", widget("a", 1, 1, 3, 1))))
        val vm = edit(store, "a")
        vm.onAction(WidgetEditorAction.SetType(WidgetType.BAR))
        val draft = vm.state.value.draft!!
        assertEquals(3 to 1, draft.w to draft.h)
        assertEquals(1 to 1, draft.x to draft.y)
    }

    @Test
    fun `sensor slots are bound in order and clearing one clears the rest`() {
        val vm = add(FakeExtrasStore(extrasWith(page("p"))))
        vm.onAction(WidgetEditorAction.SetType(WidgetType.BATTERY))
        assertEquals(listOf(Sensors.BATTERY_VOLTAGE), vm.state.value.draft!!.sensors)
        // Slot 2 (capacity) cannot be bound while slot 1 (current) is empty.
        vm.onAction(WidgetEditorAction.SetSensor(2, Sensors.BATTERY_CAPACITY))
        assertEquals(1, vm.state.value.draft!!.sensors.size)
        vm.onAction(WidgetEditorAction.SetSensor(1, Sensors.BATTERY_CURRENT))
        vm.onAction(WidgetEditorAction.SetSensor(2, Sensors.BATTERY_CAPACITY))
        assertEquals(3, vm.state.value.draft!!.sensors.size)
        vm.onAction(WidgetEditorAction.SetSensor(1, null))
        assertEquals(listOf(Sensors.BATTERY_VOLTAGE), vm.state.value.draft!!.sensors)
    }

    @Test
    fun `a required sensor that is unbound blocks saving`() {
        val vm = add(FakeExtrasStore(extrasWith(page("p"))))
        vm.onAction(WidgetEditorAction.SetType(WidgetType.GPS))
        vm.onAction(WidgetEditorAction.SetSensor(1, null))
        assertEquals(1, vm.state.value.missingSensors)
        assertFalse(vm.state.value.canSave)
        // A switch widget works without a sensor (logical switch option).
        vm.onAction(WidgetEditorAction.SetType(WidgetType.SWITCH_STATE))
        vm.onAction(WidgetEditorAction.SetSensor(0, null))
        assertEquals(0, vm.state.value.missingSensors)
    }

    @Test
    fun `options can be set and removed`() {
        val vm = add(FakeExtrasStore(extrasWith(page("p"))))
        vm.onAction(WidgetEditorAction.SetOption(WidgetOptions.WARN_BELOW, "11.5"))
        assertEquals("11.5", vm.state.value.draft!!.option(WidgetOptions.WARN_BELOW))
        vm.onAction(WidgetEditorAction.SetOption(WidgetOptions.WARN_BELOW, null))
        assertNull(vm.state.value.draft!!.option(WidgetOptions.WARN_BELOW))
    }

    @Test
    fun `size is limited to the grid`() {
        val vm = add(FakeExtrasStore(extrasWith(page("p", columns = 4, rows = 2))))
        vm.onAction(WidgetEditorAction.SetSize(9, 9))
        assertEquals(4 to 2, vm.state.value.draft!!.let { it.w to it.h })
    }

    @Test
    fun `saving a new widget adds it and pins it after the existing pins`() = runTest {
        val store = FakeExtrasStore(extrasWith(page("p", widget("a", 0, 0, pinned = true, pinOrder = 2))))
        val vm = add(store)
        vm.onAction(WidgetEditorAction.SetPinned(true))
        vm.onAction(WidgetEditorAction.Save)
        assertEquals(WidgetEditorEvent.Saved, vm.results.first())
        val widgets = store.layout.pages[0].widgets
        assertEquals(2, widgets.size)
        assertEquals(3, widgets[1].pinOrder)
        assertTrue(widgets[1].pinned)
    }

    @Test
    fun `saving to a full page reports it and changes nothing`() = runTest {
        val store = FakeExtrasStore(extrasWith(page("p", widget("a", 0, 0, 6, 4))))
        val vm = add(store)
        vm.onAction(WidgetEditorAction.Save)
        val event = vm.results.first() as WidgetEditorEvent.Message
        assertEquals(R.string.telemetry_msg_page_full, event.message.res)
        assertEquals(1, store.layout.pages[0].widgets.size)
    }

    @Test
    fun `saving an edited widget replaces it in place`() = runTest {
        val store = FakeExtrasStore(extrasWith(page("p", widget("a", 1, 1), widget("b", 3, 3))))
        val vm = edit(store, "a")
        assertFalse(vm.state.value.canSave)
        vm.onAction(WidgetEditorAction.SetTitle("Height"))
        assertTrue(vm.state.value.canSave)
        vm.onAction(WidgetEditorAction.Save)
        assertEquals(WidgetEditorEvent.Saved, vm.results.first())
        assertEquals("Height", store.layout.widget("a")!!.title)
        assertEquals(1 to 1, store.layout.widget("a")!!.let { it.x to it.y })
        assertEquals(2, store.layout.pages[0].widgets.size)
    }

    @Test
    fun `growing onto a neighbour is refused on save`() = runTest {
        val store = FakeExtrasStore(extrasWith(page("p", widget("a", 0, 0), widget("b", 1, 0))))
        val vm = edit(store, "a")
        vm.onAction(WidgetEditorAction.SetSize(2, 1))
        vm.onAction(WidgetEditorAction.Save)
        val event = vm.results.first() as WidgetEditorEvent.Message
        assertEquals(R.string.telemetry_msg_no_room_resize, event.message.res)
        assertEquals(1, store.layout.widget("a")!!.w)
    }

    @Test
    fun `editing a widget that does not exist closes the editor`() = runTest {
        val vm = edit(FakeExtrasStore(extrasWith(page("p"))), "nope")
        assertEquals(WidgetEditorEvent.Missing, vm.results.first())
        assertNull(vm.state.value.draft)
    }

    @Test
    fun `discard throws the draft away and start creates a fresh one`() {
        val vm = add(FakeExtrasStore(extrasWith(page("p"))))
        vm.onAction(WidgetEditorAction.SetTitle("x"))
        vm.discard()
        assertNull(vm.state.value.draft)
        vm.start()
        assertEquals("", vm.state.value.draft!!.title)
        assertNotNull(vm.state.value.draft)
    }

    @Test
    fun `unknown sensor ids are kept as bound`() {
        val vm = add(FakeExtrasStore(extrasWith(page("p"))))
        vm.onAction(WidgetEditorAction.SetSensor(0, SensorId("crsf.rf.1011")))
        assertEquals(SensorId("crsf.rf.1011"), vm.state.value.draft!!.sensor)
    }
}
