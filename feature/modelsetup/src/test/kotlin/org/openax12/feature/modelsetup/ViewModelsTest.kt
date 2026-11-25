package org.openax12.feature.modelsetup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CountdownStyle
import org.openax12.core.model.CurveType
import org.openax12.core.model.ManagedCurve
import org.openax12.core.model.ManagedDualRate
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.ManagedMix
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TimerAlertMode
import org.openax12.core.radio.SimulatedFault
import org.openax12.feature.modelsetup.chlock.ChLockViewModel
import org.openax12.feature.modelsetup.common.SetupMessage
import org.openax12.feature.modelsetup.curves.CurveEditorViewModel
import org.openax12.feature.modelsetup.curves.CurvesViewModel
import org.openax12.feature.modelsetup.dualrate.DualRateViewModel
import org.openax12.feature.modelsetup.inputs.InputCheckViewModel
import org.openax12.feature.modelsetup.logic.ManagedKind
import org.openax12.feature.modelsetup.mixes.MixesViewModel
import org.openax12.feature.modelsetup.output.OutputViewModel
import org.openax12.feature.modelsetup.timers.TimerRef
import org.openax12.feature.modelsetup.timers.TimersViewModel

/** The view models against the simulator radio: what they write, what they stage, what they report. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {

    @Test
    fun mixesAddChangeSourceAndEditALine() = runTest {
        val (_, session) = startedSession()
        val vm = MixesViewModel(session, session.extras, backgroundScope)
        val messages = watch(vm.state, vm.messages)
        settle()
        assertTrue(vm.state.value.ready)
        assertEquals(listOf(SourceId.AIL), session.workingConfig.value.mixesOf(0).map { it.source })

        vm.add(0, SourceId.pot(1))
        settle()
        assertEquals(listOf(SourceId.AIL, SourceId.pot(1)), session.workingConfig.value.mixesOf(0).map { it.source })
        assertTrue(session.dirty.value)

        // Change the input in place; the line keeps its position.
        val pot = session.workingConfig.value.mixesOf(0)[1]
        vm.setSource(pot, SourceId.slider(1))
        settle()
        assertEquals(listOf(SourceId.AIL, SourceId.slider(1)), session.workingConfig.value.mixesOf(0).map { it.source })
        assertTrue(messages.isEmpty())

        // A source the channel already uses is refused with a message and nothing changes.
        vm.setSource(session.workingConfig.value.mixesOf(0)[1], SourceId.AIL)
        settle()
        assertEquals(listOf(R.string.mixes_source_failed), messages.map { it.textRes })
        assertEquals(listOf(SourceId.AIL, SourceId.slider(1)), session.workingConfig.value.mixesOf(0).map { it.source })

        vm.update(session.workingConfig.value.mixesOf(0)[0]) { it.copy(weightUp = 70, weightDown = 70, curve = 2) }
        settle()
        val edited = session.workingConfig.value.mixesOf(0)[0]
        assertEquals(70, edited.weightUp)
        assertEquals(70, edited.weightDown)
        assertEquals(2, edited.curve)
    }

    @Test
    fun mixesReportAFailedAdd() = runTest {
        val (radio, session) = startedSession()
        val vm = MixesViewModel(session, session.extras, backgroundScope)
        val messages = watch(vm.state, vm.messages)
        settle()
        radio.simulator!!.injectFault(SimulatedFault.ADD_MIX_FAILS)
        vm.add(0, SourceId.pot(1))
        settle()
        assertEquals(listOf(R.string.mixes_add_failed), messages.map { it.textRes })
        assertEquals(1, session.workingConfig.value.mixesOf(0).size)
    }

    @Test
    fun mixesDeleteDropsTheManagedRecordOfAGeneratedLine() = runTest {
        val (_, session) = startedSession()
        session.extras.value = ModelExtras(
            managed = ManagedItems(mixes = listOf(ManagedMix(0, SourceId.AIL.index, ManagedItems.GENERATOR_FLIGHT_MODES))),
        )
        val vm = MixesViewModel(session, session.extras, backgroundScope)
        watch(vm.state)
        settle()
        val line = vm.state.value.channels[0].lines.single()
        assertEquals(ManagedKind.FLIGHT_MODES, line.managed)

        vm.delete(line.line)
        settle()
        assertTrue(session.workingConfig.value.mixesOf(0).isEmpty())
        assertTrue(session.extras.value.managed.isEmpty)
    }

    @Test
    fun mixesExpandAndAcknowledge() = runTest {
        val (_, session) = startedSession()
        val vm = MixesViewModel(session, session.extras, backgroundScope)
        watch(vm.state)
        settle()
        vm.toggle(3)
        settle()
        assertEquals(3, vm.state.value.expanded)
        vm.toggle(3)
        settle()
        assertNull(vm.state.value.expanded)
        val key = session.workingConfig.value.mixesOf(0)[0].key
        vm.acknowledge(key)
        settle()
        assertTrue(key in vm.state.value.acknowledged)
    }

    @Test
    fun outputEditsKeepLimitsOrdered() = runTest {
        val (_, session) = startedSession()
        val vm = OutputViewModel(session, backgroundScope)
        watch(vm.state)
        settle()
        vm.update(0) { it.copy(min = 30, max = 10, weight = -80) }
        settle()
        val channel = session.workingConfig.value.channel(0)!!
        assertEquals(30, channel.min)
        assertEquals(30, channel.max)
        assertEquals(-80, channel.weight)
        // A curve outside the 18 is ignored; none is accepted.
        vm.setCurve(0, 40)
        vm.setCurve(0, 5)
        settle()
        assertEquals(5, session.workingConfig.value.channel(0)!!.curve)
        vm.setCurve(0, NO_CURVE)
        settle()
        assertEquals(NO_CURVE, session.workingConfig.value.channel(0)!!.curve)
        // An invalid channel never reaches the radio.
        val edits = session.editCount
        vm.update(99) { it }
        settle()
        assertEquals(edits, session.editCount)
    }

    @Test
    fun inputCheckPresetsAreWrittenToTheSystemConfig() = runTest {
        val (radio, session) = startedSession()
        val vm = InputCheckViewModel(session, radio, backgroundScope)
        watch(vm.state)
        settle()
        assertTrue(vm.state.value.ready)
        assertEquals(-100, vm.state.value.rows.first { it.source.index == SourceId.THR.index }.preset)

        vm.setPreset(SourceId.THR, null)
        vm.setPreset(SourceId.SA, SwitchPosition.UP.logic)
        settle()
        val system = session.workingConfig.value.system!!
        assertNull(system.preFlightPreset(SourceId.THR))
        assertEquals(-100, system.preFlightPreset(SourceId.SA))
        // Counters have no preset: ignored without touching the radio.
        val edits = session.editCount
        vm.setPreset(SourceId.counter(1), 0)
        settle()
        assertEquals(edits, session.editCount)
    }

    @Test
    fun dualRateDisableDropsTheManagedRecord() = runTest {
        val (_, session) = startedSession()
        session.extras.value = ModelExtras(managed = ManagedItems(dualRates = listOf(ManagedDualRate(0, 0, ManagedItems.GENERATOR_FLIGHT_MODES))))
        val vm = DualRateViewModel(session, session.extras, backgroundScope)
        watch(vm.state)
        settle()
        vm.setSwitch(0, 0, SwitchCondition.at(SourceId.SB, SwitchPosition.DOWN))
        vm.setWeight(0, 0, 65)
        settle()
        val slot = session.workingConfig.value.dualRate(0, 0)!!
        assertTrue(slot.isEnabled)
        assertEquals(65, slot.weight)
        assertEquals(ManagedKind.FLIGHT_MODES, vm.state.value.rows[0].slots[0].managed)

        vm.disable(0, 0)
        settle()
        assertFalse(session.workingConfig.value.dualRate(0, 0)!!.isEnabled)
        assertTrue(session.extras.value.managed.dualRates.isEmpty())
            }

    @Test
    fun chLockThrottleCutSetsTheLockInOneWrite() = runTest {
        val (_, session) = startedSession()
        val vm = ChLockViewModel(session, backgroundScope)
        watch(vm.state)
        settle()
        assertEquals(2, vm.state.value.throttleChannel)

        val edits = session.editCount
        vm.setLock(2, SwitchCondition.at(SourceId.SF, SwitchPosition.DOWN), -100)
        settle()
        assertEquals(edits + 1, session.editCount)
        val channel = session.workingConfig.value.channel(2)!!
        assertEquals(SwitchCondition.at(SourceId.SF, SwitchPosition.DOWN), channel.lock)
        assertEquals(-100, channel.lockValue)

        vm.setValue(2, -90)
        settle()
        assertEquals(-90, session.workingConfig.value.channel(2)!!.lockValue)
        assertEquals(SourceId.SF, session.workingConfig.value.channel(2)!!.lock.source)

        vm.clear(2)
        settle()
        assertEquals(CompareOp.DISABLED, session.workingConfig.value.channel(2)!!.lock.op)
    }

    @Test
    fun curveEditorDragPushesAndCommits() = runTest {
        val (radio, session) = startedSession()
        radio.setCurve(org.openax12.core.model.CurveConfig.custom(0, listOf(
            org.openax12.core.model.CurvePoint(-100, -100), org.openax12.core.model.CurvePoint(0, 0), org.openax12.core.model.CurvePoint(100, 100))))
        session.refresh()
        val vm = CurveEditorViewModel(0, session, radio, session.extras, backgroundScope)
        watch(vm.state)
        settle()
        assertTrue(vm.state.value.ready)
        assertEquals(3, vm.state.value.curve.pointCount)

        // Dragging: the draft shows at once, the radio follows without refreshing the working config.
        vm.dragPoint(1, 10, 55)
        settle()
        assertEquals(org.openax12.core.model.CurvePoint(10, 55), vm.state.value.curve.points[1])
        assertEquals(org.openax12.core.model.CurvePoint(10, 55), radio.getCurve(0)!!.points[1])
        assertTrue(session.refreshesRequested.contains(false))
        // Finishing the gesture sends a normal edit.
        vm.finishDrag()
        settle()
        assertEquals(true, session.refreshesRequested.last())
        assertEquals(org.openax12.core.model.CurvePoint(10, 55), session.workingConfig.value.curve(0)!!.points[1])
        assertEquals(1, vm.state.value.selectedPoint)

        // The dragged x can not pass its neighbour.
        vm.dragPoint(1, 500, 0)
        vm.finishDrag()
        settle()
        assertEquals(99, session.workingConfig.value.curve(0)!!.points[1].x)
    }

    @Test
    fun curveEditorTypeChangesAndManagedBookkeeping() = runTest {
        val (radio, session) = startedSession()
        session.extras.value = ModelExtras(managed = ManagedItems(curves = listOf(ManagedCurve(4, ManagedItems.GENERATOR_WING))))
        radio.setCurve(org.openax12.core.model.CurveConfig.parametric(4, CurveType.EXPO, 30))
        session.refresh()
        val vm = CurveEditorViewModel(4, session, radio, session.extras, backgroundScope)
        val messages = watch(vm.state, vm.messages)
        settle()
        assertEquals(ManagedKind.WING, vm.state.value.managed)
        assertTrue(vm.state.value.locked)
        vm.acknowledge()
        settle()
        assertFalse(vm.state.value.locked)

        vm.setParameter(-40)
        settle()
        assertEquals(-40, session.workingConfig.value.curve(4)!!.parameter)

        vm.setType(CurveType.CUSTOM)
        settle()
        assertEquals(CurveType.CUSTOM, session.workingConfig.value.curve(4)!!.type)
        assertEquals(9, session.workingConfig.value.curve(4)!!.pointCount)

        vm.select(4)
        vm.addPoint()
        settle()
        assertEquals(10, session.workingConfig.value.curve(4)!!.pointCount)
        assertEquals(5, vm.state.value.selectedPoint)
        vm.removePoint()
        settle()
        assertEquals(9, session.workingConfig.value.curve(4)!!.pointCount)

        // Freeing the curve drops its managed record.
        vm.setType(CurveType.NONE)
        settle()
        assertTrue(session.workingConfig.value.curve(4)!!.isUnused)
        assertTrue(session.extras.value.managed.curves.isEmpty())
        assertTrue(messages.isEmpty())
    }

    @Test
    fun curveEditorNeverSendsAnInvalidCurve() = runTest {
        val (radio, session) = startedSession()
        val vm = CurveEditorViewModel(7, session, radio, session.extras, backgroundScope)
        watch(vm.state)
        settle()
        vm.setType(CurveType.CUSTOM)
        settle()
        // Two points is the minimum: removing one more is refused with a message.
        val messages = mutableListOf<SetupMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        repeat(2) { vm.removePoint() }
        settle()
        assertEquals(2, session.workingConfig.value.curve(7)!!.pointCount)
        assertEquals(listOf(R.string.curve_cannot_remove), messages.map { it.textRes })
        assertTrue(vm.state.value.issues.isEmpty())
    }

    @Test
    fun curveListShowsUsageAndManagedKind() = runTest {
        val (_, session) = startedSession()
        session.extras.value = ModelExtras(managed = ManagedItems(curves = listOf(ManagedCurve(2, ManagedItems.GENERATOR_FLIGHT_MODES))))
        val vm = CurvesViewModel(session, session.extras, backgroundScope)
        watch(vm.state)
        settle()
        assertEquals(18, vm.state.value.rows.size)
        assertEquals(ManagedKind.FLIGHT_MODES, vm.state.value.rows[2].managed)
        assertNull(vm.state.value.rows[0].managed)
    }

    @Test
    fun timersWriteFirmwareSettingsAndStageVoiceOptions() = runTest {
        val (_, session) = startedSession()
        val resets = mutableListOf<Pair<Int, Boolean>>()
        val vm = TimersViewModel(session, session.extras, { index, isCounter -> resets += index to isCounter }, backgroundScope)
        watch(vm.state)
        settle()
        assertTrue(vm.state.value.ready)
        assertEquals(3, vm.state.value.timers.size)
        assertEquals(3, vm.state.value.counters.size)

        vm.update(TimerRef(1)) { it.copy(startTime = 300, countUp = false, alertMode = TimerAlertMode.VOICE, switch = SwitchCondition.at(SourceId.SE, SwitchPosition.DOWN)) }
        settle()
        val timer = session.workingConfig.value.timers[1]
        assertEquals(300, timer.startTime)
        assertFalse(timer.countUp)
        assertEquals(TimerAlertMode.VOICE, timer.alertMode)
        assertEquals(SourceId.SE, timer.switch.source)

        // A counter's maximum can not become 0.
        vm.update(TimerRef(0, isCounter = true)) { it.copy(startTime = 0) }
        settle()
        assertEquals(1, session.workingConfig.value.counters[0].startTime)

        vm.updateVoice(1) { it.copy(minuteCall = true, countdown = CountdownStyle.VOICE, countdownFromSec = 20) }
        settle()
        val voice = session.extras.value.timerVoiceOptions(1)
        assertTrue(voice.minuteCall)
        assertEquals(20, voice.countdownFromSec)
        assertEquals(1, voice.timer)

        vm.select(TimerRef(2, isCounter = true))
        settle()
        assertEquals(TimerRef(2, true), vm.state.value.selected)
        assertEquals(2, vm.state.value.selectedConfig!!.index)

        vm.reset(TimerRef(2, isCounter = true))
        settle()
        assertEquals(listOf(2 to true), resets)
        // Out-of-range timers never reach the radio.
        val edits = session.editCount
        vm.update(TimerRef(5)) { it }
        settle()
        assertEquals(edits, session.editCount)
    }
}
