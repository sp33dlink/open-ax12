package org.openax12.feature.system

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.openax12.core.model.ElrsItemType
import org.openax12.core.radio.fake.FakeRadio
import org.openax12.feature.system.elrs.ElrsCommandStep
import org.openax12.feature.system.elrs.ElrsDevice
import org.openax12.feature.system.elrs.ElrsTree
import org.openax12.feature.system.elrs.ElrsViewModel
import org.junit.Test
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {

    private val deterministic = FakeRadio.Options(demoMotion = false, realisticDelays = false, autoTick = false)

    private fun kotlinx.coroutines.test.TestScope.radio(): FakeRadio {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return FakeRadio(
            scope = backgroundScope,
            directory = null,
            dispatcher = dispatcher,
            clock = { testScheduler.currentTime },
            options = deterministic,
        )
    }

    @Test
    fun elrsMenuLoadsItemsOnceAndStopsLoadingWhenTheyStopArriving() = runTest {
        val radio = radio()
        radio.start()
        val vm = ElrsViewModel(radio, scopeOverride = backgroundScope)
        val collector = backgroundScope.launchCollect(vm)
        vm.load(ElrsDevice.TX_MODULE)
        runCurrent()
        assertTrue(vm.state.value.loading)
        settle(ElrsViewModel.QUIET_MS + 100)
        val state = vm.state.value
        assertFalse(state.loading)
        assertFalse(state.noAnswer)
        assertTrue(state.items.size > 5)
        assertEquals("AX12 Internal ELRS", state.deviceName)
        assertTrue(state.visibleItems.all { it.parentIndex == ElrsTree.ROOT })
        assertNotNull(collector)
    }

    @Test
    fun settingAnElrsValueChangesTheItemAndTheModule() = runTest {
        val radio = radio()
        radio.start()
        val vm = ElrsViewModel(radio, scopeOverride = backgroundScope)
        backgroundScope.launchCollect(vm)
        vm.load()
        settle(ElrsViewModel.QUIET_MS + 100)
        val rate = vm.state.value.items.first { it.name == "Packet Rate" }
        vm.setValue(rate, 5)
        settle()
        assertEquals(5, vm.state.value.items.first { it.name == "Packet Rate" }.value)
        val bind = vm.state.value.items.first { it.type == ElrsItemType.COMMAND && it.name == "Bind" }
        vm.command(bind, ElrsCommandStep.START)
        settle()
        assertFalse(vm.state.value.commands.containsKey(bind.index) && vm.state.value.commands[bind.index] == null)
    }

    @Test
    fun anElrsRequestWithoutARadioEndsWithNoAnswer() = runTest {
        val radio = radio() // never started
        val vm = ElrsViewModel(radio, scopeOverride = backgroundScope)
        backgroundScope.launchCollect(vm)
        vm.load()
        settle()
        assertTrue(vm.state.value.noAnswer)
        assertFalse(vm.state.value.loading)
    }

    /** Work of a backgroundScope is not "idle work": run it explicitly, then move the clock. */
    private fun kotlinx.coroutines.test.TestScope.settle(ms: Long = 0) {
        repeat(3) { runCurrent() }
        if (ms > 0) advanceTimeBy(ms)
        repeat(3) { runCurrent() }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(vm: ElrsViewModel) =
        launch { vm.state.collect { } }
}
