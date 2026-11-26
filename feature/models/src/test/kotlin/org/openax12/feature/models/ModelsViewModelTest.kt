package org.openax12.feature.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.openax12.core.data.ModelEntry
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.RfModuleMode
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.WorkingConfig
import org.openax12.core.runtime.SessionError
import org.openax12.core.runtime.SessionResult

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {

    private val current = ModelEntry(id = "a", name = "Swift", isCurrent = true, lastUsedMs = 10)
    private val other = ModelEntry(id = "b", name = "Easy Star", lastUsedMs = 20)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Rig(models: List<ModelEntry>, var linkUp: Boolean = false) {
        val repo = RepoFake(models)
        val session = SessionFake()
        val selects = mutableListOf<Pair<String, Boolean>>()

        init {
            session.handlers["selectModel"] = { args ->
                selects += (args[0] as String) to (args[1] as Boolean)
                SessionResult.Success(models.first { it.id == args[0] })
            }
        }

        fun viewModel() = ModelsViewModel(repo.repository, session.session) { linkUp }
    }

    @Test
    fun `selecting a model while not linked switches at once and reports it`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(listOf(current, other))
        val vm = rig.viewModel()
        val events = mutableListOf<ModelsEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        backgroundScope.launch { vm.state.collect { } }

        vm.select(other)

        assertEquals(listOf("b" to false), rig.selects)
        val message = (events.single() as ModelsEvent.Message).message
        assertEquals(R.string.models_selected, message.res)
        assertEquals(listOf<Any>("Easy Star"), message.args)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `a model loaded while the radio's RF module is off warns that it will not transmit`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(listOf(current, other))
        rig.session.workingConfig.value = WorkingConfig(
            channels = listOf(OutputChannelConfig(channel = 0)),
            system = SystemConfig(rfModuleMode = RfModuleMode.OFF),
        )
        val vm = rig.viewModel()
        val events = mutableListOf<ModelsEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        backgroundScope.launch { vm.state.collect { } }

        vm.select(other)

        val messages = events.map { (it as ModelsEvent.Message).message }
        assertEquals(listOf(R.string.models_selected, R.string.models_rf_off_warning), messages.map { it.res })
        assertEquals(AxToastKind.Warning, messages.last().kind)
    }

    @Test
    fun `no RF warning while the radio transmits or the setting is unknown`() = runTest(UnconfinedTestDispatcher()) {
        for (system in listOf(SystemConfig(rfModuleMode = RfModuleMode.INTERNAL), null)) {
            val rig = Rig(listOf(current, other))
            rig.session.workingConfig.value = WorkingConfig(channels = listOf(OutputChannelConfig(channel = 0)), system = system)
            val vm = rig.viewModel()
            val events = mutableListOf<ModelsEvent>()
            backgroundScope.launch { vm.events.collect { events += it } }
            backgroundScope.launch { vm.state.collect { } }

            vm.select(other)

            assertEquals(1, events.size)
        }
    }

    @Test
    fun `selecting the current model does nothing`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(listOf(current, other))
        val vm = rig.viewModel()
        backgroundScope.launch { vm.state.collect { } }
        vm.select(current)
        assertTrue(rig.selects.isEmpty())
    }

    @Test
    fun `a linked radio needs the confirmation before the switch`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(listOf(current, other), linkUp = true)
        val vm = rig.viewModel()
        backgroundScope.launch { vm.state.collect { } }

        vm.select(other)
        assertEquals(GuardStep.LINK_UP, vm.state.value.guard.step)
        assertTrue(rig.selects.isEmpty())

        vm.guardConfirmLink()
        assertEquals(listOf("b" to false), rig.selects)
        assertEquals(GuardStep.NONE, vm.state.value.guard.step)
    }

    @Test
    fun `unsaved changes can be discarded for the switch`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(listOf(current, other))
        rig.session.dirty.value = true
        val vm = rig.viewModel()
        backgroundScope.launch { vm.state.collect { } }

        vm.select(other)
        assertEquals(GuardStep.UNSAVED, vm.state.value.guard.step)
        vm.guardDiscard()

        assertEquals(listOf("b" to true), rig.selects)
    }

    @Test
    fun `cancelling the guard keeps the current model`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(listOf(current, other), linkUp = true)
        val vm = rig.viewModel()
        backgroundScope.launch { vm.state.collect { } }
        vm.select(other)
        vm.guardCancel()
        assertTrue(rig.selects.isEmpty())
        assertEquals(GuardStep.NONE, vm.state.value.guard.step)
    }

    @Test
    fun `a write that the radio did not confirm is a warning, not an error`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(listOf(current, other))
        rig.session.handlers["selectModel"] = { SessionResult.Failure(SessionError.DEVICE_WRITE_PENDING) }
        val vm = rig.viewModel()
        val events = mutableListOf<ModelsEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        backgroundScope.launch { vm.state.collect { } }

        vm.select(other)

        val message = (events.single() as ModelsEvent.Message).message
        assertEquals(R.string.models_err_write_pending, message.res)
        assertEquals(AxToastKind.Warning, message.kind)
    }

    @Test
    fun `the list is sorted by the chosen order`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(listOf(current, other))
        val vm = rig.viewModel()
        backgroundScope.launch { vm.state.collect { } }
        assertEquals(listOf("Easy Star", "Swift"), vm.state.value.models.map { it.name })
        vm.setSort(ModelSort.LAST_USED)
        assertEquals(listOf("Easy Star", "Swift"), vm.state.value.models.map { it.name })
        rig.repo.models.value = listOf(current.copy(lastUsedMs = 99), other)
        assertEquals(listOf("Swift", "Easy Star"), vm.state.value.models.map { it.name })
    }
}
