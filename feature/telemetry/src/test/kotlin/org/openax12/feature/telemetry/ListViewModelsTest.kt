package org.openax12.feature.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.openax12.core.data.DataError
import org.openax12.core.model.AlarmOutputs
import org.openax12.core.model.CompareOp
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.SensorAlarm
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorOrigin
import org.openax12.core.model.SensorOverride
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.UnitSystem

class SensorListViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val infos = listOf(
        SensorInfo(Sensors.BATTERY_VOLTAGE, "RxBt", origin = SensorOrigin.CRSF, unit = SensorUnit.VOLT, templateName = "RxBt"),
        SensorInfo(Sensors.TX_BATTERY, "TxBt", origin = SensorOrigin.INTERNAL, unit = SensorUnit.VOLT),
        SensorInfo(SensorId.input(SourceId.THR), "Thr", origin = SensorOrigin.INTERNAL),
        SensorInfo(SensorId.timer(0), "T1", origin = SensorOrigin.INTERNAL),
    )

    private fun vm(store: FakeExtrasStore, resets: MutableList<SensorId?> = mutableListOf()) = SensorListViewModel(
        store = store,
        sensorList = MutableStateFlow(infos),
        values = MutableStateFlow(emptyMap()),
        units = flowOf(UnitSystem.IMPERIAL),
        resetMinMax = { resets += it },
    )

    @Test
    fun `categories follow the id namespace`() {
        assertEquals(SensorCategory.RECEIVER, sensorCategory(Sensors.BATTERY_VOLTAGE))
        assertEquals(SensorCategory.RECEIVER, sensorCategory(SensorId.sdk(0x1234)))
        assertEquals(SensorCategory.TRANSMITTER, sensorCategory(Sensors.TX_BATTERY))
        assertEquals(SensorCategory.TRANSMITTER, sensorCategory(Sensors.FLIGHT_MODE))
        assertEquals(SensorCategory.CONTROLS, sensorCategory(SensorId.channel(2)))
        assertEquals(SensorCategory.LOGIC, sensorCategory(SensorId.logicalSwitch(3)))
    }

    @Test
    fun `filter by category and text`() {
        val vm = vm(FakeExtrasStore())
        assertEquals(4, vm.state.value.sensors.size)
        assertEquals(UnitSystem.IMPERIAL, vm.state.value.units)
        vm.onAction(SensorListAction.SetCategory(SensorCategory.TRANSMITTER))
        assertEquals(listOf("TxBt"), vm.state.value.sensors.map { it.name })
        vm.onAction(SensorListAction.SetCategory(null))
        vm.onAction(SensorListAction.SetQuery("batt.v"))
        assertEquals(listOf("RxBt"), vm.state.value.sensors.map { it.name })
        vm.onAction(SensorListAction.SetQuery("rxbt"))
        assertEquals(listOf("RxBt"), vm.state.value.sensors.map { it.name })
        assertEquals(4, vm.state.value.total)
    }

    @Test
    fun `saving an override stores it and an empty one removes it`() {
        val store = FakeExtrasStore()
        val vm = vm(store)
        vm.onAction(SensorListAction.SaveOverride(Sensors.BATTERY_VOLTAGE, SensorOverride(name = "Pack", precision = 2)))
        assertEquals("Pack", store.extras.value.sensorOverride(Sensors.BATTERY_VOLTAGE)?.name)
        assertTrue(vm.state.value.overrides.containsKey(Sensors.BATTERY_VOLTAGE.key))
        vm.onAction(SensorListAction.SaveOverride(Sensors.BATTERY_VOLTAGE, SensorOverride(name = " ")))
        assertNull(store.extras.value.sensorOverride(Sensors.BATTERY_VOLTAGE))
    }

    @Test
    fun `reset min max is forwarded`() {
        val resets = mutableListOf<SensorId?>()
        val vm = vm(FakeExtrasStore(), resets)
        vm.onAction(SensorListAction.ResetMinMax(Sensors.AIRSPEED))
        vm.onAction(SensorListAction.ResetMinMax(null))
        assertEquals(listOf(Sensors.AIRSPEED, null), resets)
    }
}

class AlarmViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val sensors = MutableStateFlow(listOf(SensorInfo(Sensors.BATTERY_VOLTAGE, "RxBt", unit = SensorUnit.VOLT, precision = 1)))
    private val existing = SensorAlarm(id = "a1", sensor = Sensors.TX_BATTERY, op = CompareOp.LESS, threshold = 7.0, outputs = AlarmOutputs(voiceText = "x"))

    private fun vm(store: FakeExtrasStore, openId: String? = null) = AlarmViewModel(
        store = store,
        sensorList = sensors,
        activeAlarmIds = MutableStateFlow(setOf("a1")),
        openId = openId,
        soundFiles = { listOf("lowbatt.wav") },
        defaultVoiceText = "Battery {RxBt} volts",
    ).also { it.start() }

    @Test
    fun `a new alarm starts from defaults and is stored on save`() {
        val store = FakeExtrasStore()
        val vm = vm(store)
        assertEquals(setOf("a1"), vm.state.value.activeIds)
        assertEquals(listOf("lowbatt.wav"), vm.state.value.soundFiles)
        vm.onAction(AlarmAction.Add)
        val draft = vm.state.value.draft!!
        assertTrue(vm.state.value.isNewDraft)
        assertEquals("Battery {RxBt} volts", draft.outputs.voiceText)
        assertEquals(Sensors.BATTERY_VOLTAGE, draft.sensor)
        assertEquals("RxBt", vm.state.value.draftSensor?.name)
        vm.onAction(AlarmAction.Edit(draft.copy(name = "  Low  ", threshold = 10.0)))
        vm.onAction(AlarmAction.Save)
        assertNull(vm.state.value.draft)
        assertEquals(1, store.extras.value.alarms.size)
        assertEquals("Low", store.extras.value.alarms[0].name)
        assertEquals(10.0, store.extras.value.alarms[0].threshold, 0.0)
    }

    @Test
    fun `editing a stored alarm replaces it and cancel keeps the stored one`() {
        val store = FakeExtrasStore(ModelExtras(alarms = listOf(existing)))
        val vm = vm(store)
        vm.onAction(AlarmAction.Open("a1"))
        assertFalse(vm.state.value.isNewDraft)
        vm.onAction(AlarmAction.Edit(vm.state.value.draft!!.copy(threshold = 6.5)))
        vm.onAction(AlarmAction.Cancel)
        assertEquals(7.0, store.extras.value.alarms[0].threshold, 0.0)
        vm.onAction(AlarmAction.Open("a1"))
        vm.onAction(AlarmAction.Edit(vm.state.value.draft!!.copy(threshold = 6.5)))
        vm.onAction(AlarmAction.Save)
        assertEquals(1, store.extras.value.alarms.size)
        assertEquals(6.5, store.extras.value.alarms[0].threshold, 0.0)
    }

    @Test
    fun `edits without an open editor are ignored`() {
        val vm = vm(FakeExtrasStore(ModelExtras(alarms = listOf(existing))))
        vm.onAction(AlarmAction.Edit(existing.copy(threshold = 1.0)))
        assertNull(vm.state.value.draft)
    }

    @Test
    fun `alarms can be switched off and deleted`() {
        val store = FakeExtrasStore(ModelExtras(alarms = listOf(existing, existing.copy(id = "a2"))))
        val vm = vm(store)
        vm.onAction(AlarmAction.SetEnabled("a2", false))
        assertTrue(store.extras.value.alarms[0].enabled)
        assertFalse(store.extras.value.alarms[1].enabled)
        vm.onAction(AlarmAction.Delete("a1"))
        assertEquals(listOf("a2"), store.extras.value.alarms.map { it.id })
    }

    @Test
    fun `opened on one alarm the screen closes when it is done`() = runTest {
        val store = FakeExtrasStore(ModelExtras(alarms = listOf(existing)))
        val vm = vm(store, openId = "a1")
        assertEquals("a1", vm.state.value.draft?.id)
        vm.onAction(AlarmAction.Cancel)
        assertEquals(AlarmEvent.Close, vm.results.first())
    }

    @Test
    fun `opening an alarm that does not exist closes the screen`() = runTest {
        val vm = vm(FakeExtrasStore(), openId = "gone")
        assertEquals(AlarmEvent.Close, vm.results.first())
        assertNull(vm.state.value.draft)
    }

    @Test
    fun `reset closes the editor silently`() {
        val vm = vm(FakeExtrasStore(ModelExtras(alarms = listOf(existing))))
        vm.onAction(AlarmAction.Add)
        vm.reset()
        assertNull(vm.state.value.draft)
        assertFalse(vm.state.value.isNewDraft)
    }
}

class FlightLogMessageTest {
    @Test
    fun `data errors map to a message`() {
        assertEquals(R.string.telemetry_msg_log_permission, FlightLogsViewModel.messageFor(DataError.STORAGE_PERMISSION))
        assertEquals(R.string.telemetry_msg_log_not_found, FlightLogsViewModel.messageFor(DataError.NOT_FOUND))
        assertEquals(R.string.telemetry_msg_log_busy, FlightLogsViewModel.messageFor(DataError.BUSY))
        assertEquals(R.string.telemetry_msg_log_failed, FlightLogsViewModel.messageFor(DataError.IO))
    }
}
