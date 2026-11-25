package org.openax12.feature.functions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.openax12.core.engine.FlightModeCompiler
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.ModelType
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchPosition
import org.openax12.core.runtime.SessionError
import org.openax12.feature.functions.common.Outcome
import org.openax12.feature.functions.flightmodes.FlightModeEdits
import org.openax12.feature.functions.flightmodes.FlightModesAction
import org.openax12.feature.functions.flightmodes.FlightModesViewModel
import org.openax12.feature.functions.flightmodes.PresetNames

private val NAMES = PresetNames("Normal", "Thermal", "Speed", "Sport", "Landing", "Angle", "Horizon", "Acro", "Idle1", "Idle2", "M1", "M2", "M3")

private fun threeModes() = FlightModesConfig(
    enabled = true,
    modeSwitch = SourceId.SC,
    modes = listOf(
        FlightModeConfig("A", SwitchPosition.UP),
        FlightModeConfig("B", SwitchPosition.MID, offsets = mapOf(2 to 10), rates = mapOf(0 to 80)),
        FlightModeConfig("C", SwitchPosition.DOWN),
    ),
)

class FlightModeEditsTest {
    @Test
    fun setPositionSwapsWithTheModeThatHadIt() {
        val result = FlightModeEdits.setPosition(threeModes(), 0, SwitchPosition.DOWN)
        assertEquals(listOf(SwitchPosition.DOWN, SwitchPosition.MID, SwitchPosition.UP), result.modes.map { it.position })
    }

    @Test
    fun setPositionToTheSameOneChangesNothing() {
        val config = threeModes()
        assertEquals(config, FlightModeEdits.setPosition(config, 1, SwitchPosition.MID))
    }

    @Test
    fun addModeTakesTheFirstFreePositionAndKeepsTheTableRectangular() {
        val two = threeModes().let { it.copy(modes = it.modes.take(2)) }
        val added = FlightModeEdits.addMode(two)
        assertEquals(3, added.modes.size)
        assertEquals(SwitchPosition.DOWN, added.modes[2].position)
        assertEquals(mapOf(0 to 0, 2 to 0), added.modes[2].offsets)
        assertEquals(mapOf(0 to 100, 2 to 100), added.modes[2].rates)
    }

    @Test
    fun addModeStopsAtThree() {
        val config = threeModes()
        assertEquals(config, FlightModeEdits.addMode(config))
    }

    @Test
    fun theLastModeCannotBeRemoved() {
        val single = FlightModesConfig(enabled = true, modes = listOf(FlightModeConfig("A")))
        assertEquals(single, FlightModeEdits.removeMode(single, 0))
        assertEquals(2, FlightModeEdits.removeMode(threeModes(), 1).modes.size)
    }

    @Test
    fun channelRowsAreTheUnionOfOffsetsAndRates() {
        assertEquals(listOf(0, 2), FlightModeEdits.channelRows(threeModes()))
    }

    @Test
    fun addAndRemoveChannelTouchEveryMode() {
        val added = FlightModeEdits.addChannel(threeModes(), 4)
        assertTrue(added.modes.all { it.offsets[4] == 0 && it.rates[4] == 100 })
        assertEquals(listOf(0, 2, 4), FlightModeEdits.channelRows(added))
        val removed = FlightModeEdits.removeChannel(added, 2)
        assertEquals(listOf(0, 4), FlightModeEdits.channelRows(removed))
    }

    @Test
    fun addChannelKeepsExistingValues() {
        val added = FlightModeEdits.addChannel(threeModes(), 2)
        assertEquals(10, added.modes[1].offsets[2])
    }

    @Test
    fun offsetsAndRatesAreClamped() {
        val config = FlightModeEdits.setRate(FlightModeEdits.setOffset(threeModes(), 0, 1, 150), 0, 1, -5)
        assertEquals(100, config.modes[0].offsets[1])
        assertEquals(0, config.modes[0].rates[1])
    }

    @Test
    fun namesAreNotPartOfTheCompiledSettings() {
        val a = threeModes()
        val renamed = FlightModeEdits.updateMode(a, 0) { it.copy(name = "Other", announce = false) }
        assertTrue(FlightModeEdits.compiledEquals(a, renamed))
        assertFalse(FlightModeEdits.compiledEquals(a, FlightModeEdits.setOffset(a, 1, 2, 11)))
        assertFalse(FlightModeEdits.compiledEquals(a, a.copy(modeSwitch = SourceId.SD)))
    }

    @Test
    fun multirotorPresetPutsTheModeValueOnCh5AndCompilesCleanly() {
        val preset = FlightModeEdits.preset(ModelType.MULTIROTOR, SourceId.SC, NAMES)
        assertEquals(listOf("Angle", "Horizon", "Acro"), preset.modes.map { it.name })
        assertEquals(listOf(-100, 0, 100), preset.modes.map { it.offsets[FlightModeEdits.MULTIROTOR_MODE_CHANNEL] })
        assertTrue(FlightModeCompiler.validate(preset, testConfig()).isEmpty())
        val plan = FlightModeCompiler.compile(preset, testConfig())
        assertEquals(1, plan.mixes.size)
        assertEquals(4, plan.mixes.single().channel)
    }

    @Test
    fun everyPresetIsValidAndUsesDistinctPositions() {
        for (type in ModelType.entries) {
            val preset = FlightModeEdits.preset(type, SourceId.SA, NAMES)
            assertEquals(3, preset.modes.map { it.position }.distinct().size)
            assertTrue("$type", FlightModeCompiler.validate(preset, testConfig()).isEmpty())
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FlightModesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun viewModel(initial: FlightModesConfig, session: FakeSession = FakeSession(MutableStateFlow(ModelExtras(flightModes = initial)))) =
        FlightModesViewModel(testDeps(session)) to session

    @Test
    fun editsChangeTheDraftOnlyUntilApplied() = runTest(main.dispatcher) {
        val (vm, session) = viewModel(threeModes())
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(FlightModesAction.SetOffset(1, 2, 25))
        assertEquals(25, vm.state.value.draft.modes[1].offsets[2])
        assertTrue(vm.state.value.dirty)
        assertEquals(10, session.extras.value.flightModes.modes[1].offsets[2])
        assertEquals(0, session.updateCount)
    }

    @Test
    fun applyCompilesTheDraftAndStagesItWithThePlan() = runTest(main.dispatcher) {
        val (vm, session) = viewModel(threeModes())
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(FlightModesAction.SetOffset(1, 2, 25))
        vm.onAction(FlightModesAction.RequestApply)
        assertNotNull(vm.state.value.pendingDiff)
        assertEquals(25, session.compiledFlightModes.single()?.modes?.get(1)?.offsets?.get(2))
        vm.onAction(FlightModesAction.ConfirmApply)
        assertEquals(1, session.appliedPlans.size)
        assertEquals(25, session.extras.value.flightModes.modes[1].offsets[2])
        assertNull(vm.state.value.pendingDiff)
        assertEquals(Outcome.Applied, vm.state.value.outcome)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun aRejectedPlanKeepsTheDraftAndReportsTheError() = runTest(main.dispatcher) {
        val (vm, session) = viewModel(threeModes())
        backgroundScope.launch { vm.state.collect {} }
        session.applyResult = failure(SessionError.PLAN_REJECTED)
        vm.onAction(FlightModesAction.SetOffset(1, 2, 25))
        vm.onAction(FlightModesAction.RequestApply)
        vm.onAction(FlightModesAction.ConfirmApply)
        assertEquals(Outcome.Failed(SessionError.PLAN_REJECTED), vm.state.value.outcome)
        assertTrue(vm.state.value.dirty)
        assertEquals(10, session.extras.value.flightModes.modes[1].offsets[2])
    }

    @Test
    fun dismissingTheDiffLeavesNoTrace() = runTest(main.dispatcher) {
        val (vm, session) = viewModel(threeModes())
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(FlightModesAction.SetOffset(1, 2, 25))
        vm.onAction(FlightModesAction.RequestApply)
        vm.onAction(FlightModesAction.DismissDiff)
        assertNull(vm.state.value.pendingDiff)
        assertTrue(session.appliedPlans.isEmpty())
    }

    @Test
    fun renamingIsStagedAtOnceWhileTheCompiledPartsAreUnchanged() = runTest(main.dispatcher) {
        val (vm, session) = viewModel(threeModes())
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(FlightModesAction.Rename(0, "Cruise"))
        assertEquals("Cruise", session.extras.value.flightModes.modes[0].name)
        assertFalse(vm.state.value.dirty)
        assertTrue(session.appliedPlans.isEmpty())
    }

    @Test
    fun renamingWaitsForApplyWhileOtherEditsArePending() = runTest(main.dispatcher) {
        val (vm, session) = viewModel(threeModes())
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(FlightModesAction.SetOffset(1, 2, 25))
        vm.onAction(FlightModesAction.Rename(0, "Cruise"))
        assertEquals("Cruise", vm.state.value.draft.modes[0].name)
        assertEquals("A", session.extras.value.flightModes.modes[0].name)
    }

    @Test
    fun theDraftFollowsTheModelWhenNothingIsPending() = runTest(main.dispatcher) {
        val (vm, session) = viewModel(threeModes())
        backgroundScope.launch { vm.state.collect {} }
        session.extras.value = ModelExtras(flightModes = FlightModesConfig())
        assertFalse(vm.state.value.draft.enabled)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun theDraftSurvivesAModelChangeWhileEditing() = runTest(main.dispatcher) {
        val (vm, session) = viewModel(threeModes())
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(FlightModesAction.SetOffset(1, 2, 25))
        session.extras.value = ModelExtras(flightModes = FlightModesConfig())
        assertEquals(25, vm.state.value.draft.modes[1].offsets[2])
        assertTrue(vm.state.value.dirty)
    }

    @Test
    fun enablingFillsInAModeAndTheFirstSwitch() = runTest(main.dispatcher) {
        val (vm, _) = viewModel(FlightModesConfig())
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(FlightModesAction.SetEnabled(true))
        val draft = vm.state.value.draft
        assertTrue(draft.enabled)
        assertEquals(1, draft.modes.size)
        assertEquals(SourceId.SA, draft.modeSwitch)
    }

    @Test
    fun anInvalidDraftCannotBeApplied() = runTest(main.dispatcher) {
        val (vm, _) = viewModel(threeModes().copy(modeSwitch = null))
        backgroundScope.launch { vm.state.collect {} }
        assertFalse(vm.state.value.valid)
    }

    @Test
    fun presetUsesTheModelType() = runTest(main.dispatcher) {
        val session = FakeSession(MutableStateFlow(ModelExtras(modelType = ModelType.MULTIROTOR)))
        val vm = FlightModesViewModel(testDeps(session))
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(FlightModesAction.ApplyPreset(NAMES))
        assertEquals(listOf("Angle", "Horizon", "Acro"), vm.state.value.draft.modes.map { it.name })
        assertEquals(SourceId.SA, vm.state.value.draft.modeSwitch)
    }
}
