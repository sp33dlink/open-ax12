package org.openax12.feature.functions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.ButterflyConfig
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurveType
import org.openax12.core.model.FlapControl
import org.openax12.core.model.LogicalFunction
import org.openax12.core.model.LogicalSwitch
import org.openax12.core.model.MixLine
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.PlanConflict
import org.openax12.core.model.PlanConflictKind
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.PlanOp
import org.openax12.core.model.Repeat
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.SpecialAction
import org.openax12.core.model.SpecialFunction
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TailType
import org.openax12.core.model.TimerEvent
import org.openax12.core.model.Trigger
import org.openax12.core.model.VarioConfig
import org.openax12.core.model.VoiceAlert
import org.openax12.core.model.WingChannels
import org.openax12.core.model.WingConfig
import org.openax12.core.model.WingType
import org.openax12.core.model.WorkingConfig
import org.openax12.feature.functions.common.ActionKind
import org.openax12.feature.functions.common.SensorGroup
import org.openax12.feature.functions.common.TriggerKind
import org.openax12.feature.functions.common.TriggerProblem
import org.openax12.feature.functions.common.appendPlaceholder
import org.openax12.feature.functions.common.buildSensorOptions
import org.openax12.feature.functions.common.buildSourceOptions
import org.openax12.feature.functions.common.defaultTrigger
import org.openax12.feature.functions.common.firstTemplateError
import org.openax12.feature.functions.common.kind
import org.openax12.feature.functions.common.summarize
import org.openax12.feature.functions.common.triggerProblem
import org.openax12.feature.functions.logical.LogicalProblem
import org.openax12.feature.functions.logical.LogicalSwitchEditorAction
import org.openax12.feature.functions.logical.LogicalSwitchEditorViewModel
import org.openax12.feature.functions.logical.LogicalSwitchRules
import org.openax12.feature.functions.sounds.VarioEdits
import org.openax12.feature.functions.sounds.VoiceAlertEditorAction
import org.openax12.feature.functions.sounds.VoiceAlertViewModel
import org.openax12.feature.functions.sounds.VoiceProblem
import org.openax12.feature.functions.sounds.upsertAlert
import org.openax12.feature.functions.sounds.voiceProblems
import org.openax12.feature.functions.special.SpecialFunctionEditorAction
import org.openax12.feature.functions.special.SpecialFunctionEditorViewModel
import org.openax12.feature.functions.special.SpecialFunctionRules
import org.openax12.feature.functions.special.SpecialProblem
import org.openax12.feature.functions.wing.FlapKind
import org.openax12.feature.functions.wing.WingEdits
import org.openax12.feature.functions.wing.WingRole

class PickerDataTest {
    @Test
    fun onlyEnabledSourcesAreOffered() {
        val config = testConfig().let { c -> c.copy(sources = c.sources.map { if (it.index == SourceId.SB.index) it.copy(enabled = false) else it }) }
        val ids = buildSourceOptions(config).map { it.id }
        assertFalse(SourceId.SB in ids)
        assertTrue(SourceId.SA in ids)
    }

    @Test
    fun everySourceIsOfferedWhileTheRadioIsNotReady() {
        assertEquals(42, buildSourceOptions(WorkingConfig.EMPTY).size)
    }

    @Test
    fun sensorOptionsContainTheBasicsAndTheRegistry() {
        val sources = buildSourceOptions(testConfig())
        val options = buildSensorOptions(
            listOf(
                SensorInfo(Sensors.BARO_ALTITUDE, "Altitude", unit = SensorUnit.METER),
                SensorInfo(SensorId("crsf.secret"), "Secret", hidden = true),
            ),
            testConfig(),
            sources,
        )
        assertTrue(options.any { it.id == SensorId.channel(2) && it.group == SensorGroup.CHANNEL })
        assertTrue(options.any { it.id == SensorId.timer(0) })
        assertEquals("Altitude", options.first { it.id == Sensors.BARO_ALTITUDE }.name)
        // The well-known sensors can be picked before they were ever received.
        assertEquals(SensorUnit.VOLT, options.first { it.id == Sensors.BATTERY_VOLTAGE }.unit)
        // Hidden registry entries are not offered.
        assertTrue(options.none { it.name == "Secret" })
        // Groups come in picker order.
        assertEquals(options.map { it.group.ordinal }, options.map { it.group.ordinal }.sorted())
    }

    @Test
    fun userNamesOfSourcesWin() {
        val config = testConfig().let { c -> c.copy(sources = c.sources.map { if (it.index == SourceId.SA.index) it.copy(name = "Arm") else it }) }
        val sources = buildSourceOptions(config)
        assertEquals("Arm", sources.first { it.id == SourceId.SA }.name)
        assertEquals("Arm", buildSensorOptions(emptyList(), config, sources).first { it.id == SensorId.input(SourceId.SA) }.name)
    }
}

class TriggerKindsTest {
    private val data = testPickers(modes = listOf("A", "B"))

    @Test
    fun everyDefaultTriggerHasItsKind() {
        for (kind in TriggerKind.entries) assertEquals(kind, defaultTrigger(kind, data).kind())
    }

    @Test
    fun conditionsDoNotOfferPeriodic() {
        assertFalse(TriggerKind.PERIODIC in TriggerKind.CONDITIONS)
        assertEquals(TriggerKind.entries.size - 1, TriggerKind.CONDITIONS.size)
    }

    @Test
    fun problemsAreReported() {
        assertEquals(TriggerProblem.NEVER, triggerProblem(Trigger.Never, data))
        assertEquals(TriggerProblem.SELF_REFERENCE, triggerProblem(Trigger.Logical(3), data, selfLogical = 3))
        assertNull(triggerProblem(Trigger.Logical(2), data, selfLogical = 3))
        assertEquals(TriggerProblem.NO_SECONDS, triggerProblem(Trigger.Timer(0, TimerEvent.REMAINING, 0), data))
        assertNull(triggerProblem(Trigger.Timer(0, TimerEvent.REMAINING, 30), data))
        assertEquals(TriggerProblem.UNKNOWN_MODE, triggerProblem(Trigger.FlightModeIs(5), data))
        assertNull(triggerProblem(Trigger.FlightModeIs(Trigger.ANY_MODE), data))
        assertEquals(TriggerProblem.UNKNOWN_SENSOR, triggerProblem(Trigger.Telemetry(SensorId("crsf.nothing")), data))
        assertNull(triggerProblem(Trigger.Telemetry(Sensors.BATTERY_VOLTAGE), data))
    }
}

class TemplateTest {
    @Test
    fun placeholdersAreAppendedWithASpace() {
        assertEquals("{Alt}", appendPlaceholder("", "Alt"))
        assertEquals("Altitude {Alt}", appendPlaceholder("Altitude", "Alt"))
        assertEquals("Altitude {Alt}", appendPlaceholder("Altitude ", "Alt"))
    }

    @Test
    fun syntaxErrorsAreFound() {
        assertNull(firstTemplateError("Battery {RxBt} volts"))
        assertNotNull(firstTemplateError("Battery {RxBt volts"))
        assertNotNull(firstTemplateError("Battery {} volts"))
    }
}

class PlanSummaryTest {
    @Test
    fun opsAreCountedByKind() {
        val mix = MixLine(channel = 0)
        val curve = CurveConfig(index = 1, type = CurveType.CUSTOM)
        val diff = PlanDiff(
            generator = "x",
            ops = listOf(
                PlanOp.AddMix(mix), PlanOp.AddMix(mix), PlanOp.UpdateMix(mix, mix), PlanOp.RemoveMix(mix),
                PlanOp.SetCurve(curve, curve), PlanOp.SetCurve(curve, curve.copy(type = CurveType.NONE)),
            ),
        )
        val summary = summarize(diff)
        assertEquals(2, summary.mixesAdded)
        assertEquals(1, summary.mixesChanged)
        assertEquals(1, summary.mixesRemoved)
        assertEquals(1, summary.curvesWritten)
        assertEquals(1, summary.curvesFreed)
        assertFalse(summary.isEmpty)
        assertTrue(summarize(PlanDiff("x")).isEmpty)
    }

    @Test
    fun blockingConflictsForbidApplying() {
        val diff = PlanDiff("x").withConflicts(listOf(PlanConflict(PlanConflictKind.MIX_KEY_IN_USE)))
        assertFalse(diff.canApply)
    }
}

class WingEditsTest {
    private val fourServo = WingConfig(enabled = true, wingType = WingType.TWO_AILERONS_TWO_FLAPS, tailType = TailType.V_TAIL)

    @Test
    fun requiredRolesFollowTheLayout() {
        assertEquals(
            listOf(WingRole.AILERON_LEFT, WingRole.AILERON_RIGHT, WingRole.FLAP_LEFT, WingRole.FLAP_RIGHT, WingRole.ELEVATOR, WingRole.RUDDER),
            WingEdits.requiredRoles(fourServo),
        )
        assertEquals(listOf(WingRole.AILERON_LEFT), WingEdits.requiredRoles(WingConfig(wingType = WingType.ONE_AILERON)))
        assertTrue(WingEdits.requiredRoles(WingConfig()).isEmpty())
        assertTrue(WingEdits.hasFlapServos(fourServo))
        assertFalse(WingEdits.hasTips(fourServo))
    }

    @Test
    fun autoAssignFillsOnlyTheFreeRolesWithFreeChannels() {
        val partly = fourServo.copy(channels = WingChannels(aileronLeft = 0, elevator = 2))
        val filled = WingEdits.autoAssign(partly)
        assertEquals(0, filled.channels.aileronLeft)
        assertEquals(2, filled.channels.elevator)
        assertEquals(1, filled.channels.aileronRight)
        assertEquals(3, filled.channels.flapLeft)
        assertEquals(4, filled.channels.flapRight)
        assertEquals(5, filled.channels.rudder)
        val used = WingRole.entries.mapNotNull { it.get(filled.channels) }
        assertEquals(used.distinct(), used)
    }

    @Test
    fun autoAssignStopsWhenChannelsRunOut() {
        val filled = WingEdits.autoAssign(fourServo, channelCount = 3)
        assertNull(filled.channels.flapRight)
        assertEquals(3, WingRole.entries.mapNotNull { it.get(filled.channels) }.size)
    }

    @Test
    fun flapKindSwitchesAndKeepsTheSettingsOfTheSameKind() {
        val switched = WingEdits.setFlapKind(fourServo, FlapKind.SWITCH, SourceId.SB, SourceId.slider(1))
        assertEquals(FlapKind.SWITCH, WingEdits.flapKind(switched))
        assertEquals(SourceId.SB, (switched.flaps.control as FlapControl.OnSwitch).source)
        assertSame(switched, WingEdits.setFlapKind(switched, FlapKind.SWITCH, SourceId.SC, SourceId.slider(1)))
        val slider = WingEdits.setFlapKind(switched, FlapKind.SLIDER, SourceId.SB, SourceId.slider(2))
        assertEquals(SourceId.slider(2), (slider.flaps.control as FlapControl.OnSlider).source)
        assertEquals(FlapKind.NONE, WingEdits.flapKind(WingEdits.setFlapKind(slider, FlapKind.NONE, SourceId.SB, SourceId.slider(2))))
    }

    @Test
    fun compensationPointsAreFiveOrNone() {
        val none = ButterflyConfig()
        assertTrue(WingEdits.compensationPoints(none).isEmpty())
        val one = WingEdits.setCompensation(none, 2, 15)
        assertEquals(listOf(0, 0, 15, 0, 0), one.elevatorCompensation)
        assertEquals(listOf(0, 0, 15, 0, 0), WingEdits.compensationPoints(ButterflyConfig(elevatorCompensation = listOf(0, 0, 15))))
        assertEquals(100, WingEdits.setCompensation(one, 0, 500).elevatorCompensation[0])
        assertSame(one, WingEdits.setCompensation(one, 9, 1))
    }
}

class LogicalSwitchRulesTest {
    private val data = testPickers()

    @Test
    fun choosingAFunctionFillsTheEmptyOperands() {
        val compare = LogicalSwitchRules.withFunction(LogicalSwitch(0), LogicalFunction.A_GREATER_B, data)
        assertEquals(SensorId.input(SourceId.THR), compare.a)
        assertEquals(SensorId.input(SourceId.ELE), compare.b)
        val sticky = LogicalSwitchRules.withFunction(LogicalSwitch(0), LogicalFunction.STICKY, data)
        assertEquals(Trigger.SwitchAt(SourceId.SA, SwitchPosition.DOWN), sticky.s1)
        assertEquals(Trigger.SwitchAt(SourceId.SA, SwitchPosition.UP), sticky.s2)
        val timer = LogicalSwitchRules.withFunction(LogicalSwitch(0), LogicalFunction.TIMER, data)
        assertEquals(1.0, timer.t1Sec, 0.0)
        assertEquals(1.0, timer.t2Sec, 0.0)
    }

    @Test
    fun anExistingOperandIsNotReplaced() {
        val start = LogicalSwitch(0, function = LogicalFunction.A_GREATER_X, a = Sensors.BARO_ALTITUDE, x = 50.0)
        val changed = LogicalSwitchRules.withFunction(start, LogicalFunction.A_LESS_X, data)
        assertEquals(Sensors.BARO_ALTITUDE, changed.a)
        assertEquals(50.0, changed.x, 0.0)
    }

    @Test
    fun missingOperandsBlock() {
        val empty = LogicalSwitch(0, function = LogicalFunction.A_GREATER_X)
        assertEquals(listOf(LogicalProblem.MISSING_A), LogicalSwitchRules.problems(empty, data))
        assertTrue(LogicalSwitchRules.hasBlocking(LogicalSwitchRules.problems(empty, data)))
        assertTrue(LogicalSwitchRules.problems(LogicalSwitch(0), data).isEmpty())
    }

    @Test
    fun aSwitchCannotDependOnItself() {
        val ls = LogicalSwitch(
            4, function = LogicalFunction.AND,
            s1 = Trigger.Logical(4), s2 = Trigger.SwitchAt(SourceId.SA, SwitchPosition.UP),
        )
        assertTrue(LogicalProblem.SELF_REFERENCE in LogicalSwitchRules.problems(ls, data))
        val viaAnd = LogicalSwitch(4, function = LogicalFunction.A_GREATER_X, a = SensorId.input(SourceId.THR), andCondition = Trigger.Logical(4))
        assertTrue(LogicalProblem.SELF_REFERENCE in LogicalSwitchRules.problems(viaAnd, data))
    }

    @Test
    fun conditionsOfOtherFunctionsAreNotChecked() {
        val ls = LogicalSwitch(4, function = LogicalFunction.A_GREATER_X, a = SensorId.input(SourceId.THR), s1 = Trigger.Logical(4))
        assertFalse(LogicalProblem.SELF_REFERENCE in LogicalSwitchRules.problems(ls, data))
    }

    @Test
    fun aTimerWithoutTimesIsAWarning() {
        val ls = LogicalSwitch(0, function = LogicalFunction.TIMER)
        val problems = LogicalSwitchRules.problems(ls, data)
        assertEquals(listOf(LogicalProblem.TIMER_ZERO), problems)
        assertFalse(LogicalSwitchRules.hasBlocking(problems))
    }

    @Test
    fun slotsFillTheGaps() {
        val slots = LogicalSwitchRules.slots(listOf(LogicalSwitch(3, function = LogicalFunction.OR)), 32)
        assertEquals(32, slots.size)
        assertEquals(LogicalFunction.OR, slots[3].function)
        assertEquals(LogicalFunction.NONE, slots[4].function)
        assertEquals((0 until 32).toList(), slots.map { it.index })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LogicalSwitchEditorViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun setup(extras: ModelExtras = ModelExtras()): Pair<LogicalSwitchEditorViewModel, FakeSession> {
        val session = FakeSession(MutableStateFlow(extras))
        return LogicalSwitchEditorViewModel(2, testDeps(session)) to session
    }

    @Test
    fun doneStoresTheSwitchAtItsIndexAndFinishes() = runTest(main.dispatcher) {
        val (vm, session) = setup()
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(LogicalSwitchEditorAction.SetFunction(LogicalFunction.A_GREATER_X))
        vm.onAction(LogicalSwitchEditorAction.Edit { it.copy(a = Sensors.BARO_ALTITUDE, x = 100.0, index = 9) })
        assertTrue(vm.state.value.dirty)
        vm.onAction(LogicalSwitchEditorAction.Done)
        val stored = session.extras.value.logicalSwitch(2)
        assertEquals(LogicalFunction.A_GREATER_X, stored.function)
        assertEquals(100.0, stored.x, 0.0)
        assertEquals(2, stored.index)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun doneIsRefusedWhileAnOperandIsMissing() = runTest(main.dispatcher) {
        val (vm, session) = setup()
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(LogicalSwitchEditorAction.Edit { it.copy(function = LogicalFunction.A_GREATER_X) })
        assertFalse(vm.state.value.canFinish)
        vm.onAction(LogicalSwitchEditorAction.Done)
        assertFalse(vm.state.value.finished)
        assertEquals(0, session.updateCount)
    }

    @Test
    fun clearRemovesTheSwitch() = runTest(main.dispatcher) {
        val existing = LogicalSwitch(2, function = LogicalFunction.OR, s1 = Trigger.Always, s2 = Trigger.Never)
        val (vm, session) = setup(ModelExtras(logicalSwitches = listOf(existing)))
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(existing, vm.state.value.draft)
        vm.onAction(LogicalSwitchEditorAction.Clear)
        assertTrue(session.extras.value.logicalSwitches.isEmpty())
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun doneWithoutChangesWritesNothing() = runTest(main.dispatcher) {
        val existing = LogicalSwitch(2, function = LogicalFunction.OR, s1 = Trigger.Always, s2 = Trigger.Never)
        val (vm, session) = setup(ModelExtras(logicalSwitches = listOf(existing)))
        backgroundScope.launch { vm.state.collect {} }
        assertFalse(vm.state.value.dirty)
        vm.onAction(LogicalSwitchEditorAction.Done)
        assertEquals(0, session.updateCount)
        assertTrue(vm.state.value.finished)
    }
}

class SpecialFunctionRulesTest {
    private val data = testPickers(sounds = listOf("a.wav", "b.mp3"))

    @Test
    fun everyActionKindHasADefaultOfThatKind() {
        for (kind in ActionKind.entries) assertEquals(kind, SpecialFunctionRules.defaultAction(kind, data).kind())
    }

    @Test
    fun changingTheKindKeepsParametersOfTheSameKindOnly() {
        val say = SpecialFunction(0, action = SpecialAction.PlayText("Hello"), repeat = Repeat(10))
        assertSame(say, SpecialFunctionRules.withKind(say, ActionKind.PLAY_TEXT, data))
        val beep = SpecialFunctionRules.withKind(say, ActionKind.BEEP, data)
        assertEquals(SpecialAction.Beep(BeepPattern.SHORT), beep.action)
        assertEquals(10, beep.repeat.intervalSec)
    }

    @Test
    fun aContinuousActionResetsTheRepeat() {
        val say = SpecialFunction(0, action = SpecialAction.PlayText("Hello"), repeat = Repeat(10))
        val vario = SpecialFunctionRules.withKind(say, ActionKind.VARIO, data)
        assertTrue(vario.repeat.isOnce)
    }

    @Test
    fun soundActionsStartWithTheFirstFile() {
        assertEquals(SpecialAction.PlaySound("a.wav"), SpecialFunctionRules.defaultAction(ActionKind.PLAY_SOUND, data))
    }

    @Test
    fun nextFreeIndexSkipsTheUsedOnes() {
        val used = listOf(SpecialFunction(0), SpecialFunction(1), SpecialFunction(3))
        assertEquals(2, SpecialFunctionRules.nextFreeIndex(used, 64))
        assertNull(SpecialFunctionRules.nextFreeIndex(used, 2))
        assertEquals(0, SpecialFunctionRules.nextFreeIndex(emptyList(), 64))
    }

    @Test
    fun problemsOfEachAction() {
        fun problems(action: SpecialAction, trigger: Trigger = Trigger.Always) =
            SpecialFunctionRules.problems(SpecialFunction(0, trigger = trigger, action = action), data)
        assertEquals(listOf(SpecialProblem.TEXT_EMPTY), problems(SpecialAction.PlayText("  ")))
        assertEquals(listOf(SpecialProblem.TEXT_INVALID), problems(SpecialAction.PlayText("Battery {RxBt")))
        assertTrue(problems(SpecialAction.PlayText("Battery {RxBt} volts")).isEmpty())
        assertEquals(listOf(SpecialProblem.FILE_MISSING), problems(SpecialAction.PlaySound("")))
        assertEquals(listOf(SpecialProblem.FILE_NOT_FOUND), problems(SpecialAction.PlaySound("gone.wav")))
        assertTrue(problems(SpecialAction.PlaySound("a.wav")).isEmpty())
        assertEquals(listOf(SpecialProblem.TRIGGER_NEVER), problems(SpecialAction.Vario, Trigger.Never))
        assertEquals(listOf(SpecialProblem.TIMER_INVALID), problems(SpecialAction.ResetTimer(7)))
        assertEquals(listOf(SpecialProblem.SENSOR_UNKNOWN), problems(SpecialAction.PlayValue(SensorId("crsf.none"))))
    }

    @Test
    fun blockingProblemsComeFirst() {
        val problems = SpecialFunctionRules.problems(SpecialFunction(0, trigger = Trigger.Never, action = SpecialAction.PlayText("")), data)
        assertEquals(listOf(SpecialProblem.TEXT_EMPTY, SpecialProblem.TRIGGER_NEVER), problems)
        assertTrue(SpecialFunctionRules.hasBlocking(problems))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpecialFunctionEditorViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun aNewFunctionIsStoredWhenItIsComplete() = runTest(main.dispatcher) {
        val session = FakeSession(MutableStateFlow(ModelExtras()))
        val vm = SpecialFunctionEditorViewModel(4, testDeps(session))
        backgroundScope.launch { vm.state.collect {} }
        assertTrue(vm.state.value.isNew)
        assertFalse(vm.state.value.canFinish)
        vm.onAction(SpecialFunctionEditorAction.Edit { it.copy(trigger = Trigger.Always, action = SpecialAction.PlayText("Hi")) })
        assertTrue(vm.state.value.canFinish)
        vm.onAction(SpecialFunctionEditorAction.Done)
        assertEquals(SpecialAction.PlayText("Hi"), session.extras.value.specialFunction(4)?.action)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun deleteRemovesTheFunction() = runTest(main.dispatcher) {
        val existing = SpecialFunction(4, trigger = Trigger.Always, action = SpecialAction.Vario)
        val session = FakeSession(MutableStateFlow(ModelExtras(specialFunctions = listOf(existing, SpecialFunction(5)))))
        val vm = SpecialFunctionEditorViewModel(4, testDeps(session))
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(SpecialFunctionEditorAction.Delete)
        assertNull(session.extras.value.specialFunction(4))
        assertNotNull(session.extras.value.specialFunction(5))
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun setKindUsesTheSoundFilesOfTheModel() = runTest(main.dispatcher) {
        val session = FakeSession(MutableStateFlow(ModelExtras()))
        val vm = SpecialFunctionEditorViewModel(0, testDeps(session))
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(SpecialFunctionEditorAction.SetKind(ActionKind.PLAY_SOUND))
        assertEquals(SpecialAction.PlaySound("a.wav"), vm.state.value.draft.action)
    }

    @Test
    fun testRunsThePreview() = runTest(main.dispatcher) {
        val session = FakeSession(MutableStateFlow(ModelExtras()))
        val services = RecordingServices()
        val vm = SpecialFunctionEditorViewModel(0, testDeps(session, services = services))
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(SpecialFunctionEditorAction.Edit { it.copy(action = SpecialAction.Beep(BeepPattern.LONG)) })
        vm.onAction(SpecialFunctionEditorAction.Test)
        assertEquals(SpecialAction.Beep(BeepPattern.LONG), services.functions.single().action)
    }
}

class VarioEditsTest {
    @Test
    fun theDefaultsAreConsistent() {
        val defaults = VarioConfig()
        assertSame(defaults, VarioEdits.normalize(defaults))
    }

    @Test
    fun theDeadBandStaysAroundZero() {
        val result = VarioEdits.normalize(VarioConfig(centerMin = 2.0, centerMax = -3.0))
        assertEquals(0.0, result.centerMin, 0.0)
        assertEquals(0.0, result.centerMax, 0.0)
    }

    @Test
    fun theRangeStaysOutsideTheDeadBand() {
        val result = VarioEdits.normalize(VarioConfig(centerMin = -1.0, centerMax = 1.0, rangeMax = 0.5, rangeMin = -0.2))
        assertTrue(result.rangeMax >= 1.5)
        assertTrue(result.rangeMin <= -1.5)
    }

    @Test
    fun pitchesAndPeriodsAreClamped() {
        val result = VarioEdits.normalize(VarioConfig(pitchZeroHz = 5, pitchMaxHz = 90_000, repeatZeroMs = 1, repeatMaxMs = 99_999))
        assertEquals(100, result.pitchZeroHz)
        assertEquals(3000, result.pitchMaxHz)
        assertEquals(40, result.repeatZeroMs)
        assertEquals(2000, result.repeatMaxMs)
    }

    @Test
    fun tenthsRoundTrip() {
        assertEquals(-5, VarioEdits.tenths(-0.5))
        assertEquals(-0.5, VarioEdits.fromTenths(-5), 0.0)
    }
}

class VoiceAlertTest {
    @Test
    fun problems() {
        assertEquals(listOf(VoiceProblem.TEXT_EMPTY, VoiceProblem.TRIGGER_NEVER), voiceProblems(VoiceAlert("a")))
        assertEquals(listOf(VoiceProblem.TEXT_INVALID, VoiceProblem.TRIGGER_NEVER), voiceProblems(VoiceAlert("a", text = "{x")))
        assertTrue(voiceProblems(VoiceAlert("a", text = "Hi {LQ}", trigger = Trigger.Always)).isEmpty())
    }

    @Test
    fun upsertReplacesInPlaceOrAppends() {
        val a = VoiceAlert("a", text = "1")
        val b = VoiceAlert("b", text = "2")
        assertEquals(listOf(a.copy(text = "x"), b), upsertAlert(listOf(a, b), a.copy(text = "x")))
        assertEquals(listOf(a, b), upsertAlert(listOf(a), b))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAlertViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun aNewAlertGetsAnIdAndIsAppended() = runTest(main.dispatcher) {
        val existing = VoiceAlert("old", text = "Old", trigger = Trigger.Always)
        val session = FakeSession(MutableStateFlow(ModelExtras(voiceAlerts = listOf(existing))))
        val vm = VoiceAlertViewModel(null, testDeps(session))
        backgroundScope.launch { vm.state.collect {} }
        assertTrue(vm.state.value.isNew)
        assertTrue(vm.state.value.draft.id.isNotBlank())
        vm.onAction(VoiceAlertEditorAction.Edit { it.copy(text = "New {LQ}", trigger = Trigger.Always, id = "ignored") })
        vm.onAction(VoiceAlertEditorAction.Done)
        val alerts = session.extras.value.voiceAlerts
        assertEquals(2, alerts.size)
        assertEquals("New {LQ}", alerts[1].text)
        assertEquals(vm.state.value.draft.id, alerts[1].id)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun editingKeepsTheListOrder() = runTest(main.dispatcher) {
        val alerts = listOf(VoiceAlert("a", text = "A", trigger = Trigger.Always), VoiceAlert("b", text = "B", trigger = Trigger.Always))
        val session = FakeSession(MutableStateFlow(ModelExtras(voiceAlerts = alerts)))
        val vm = VoiceAlertViewModel("a", testDeps(session))
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(VoiceAlertEditorAction.Edit { it.copy(text = "A2") })
        vm.onAction(VoiceAlertEditorAction.Done)
        assertEquals(listOf("A2", "B"), session.extras.value.voiceAlerts.map { it.text })
    }

    @Test
    fun deleteRemovesTheAlert() = runTest(main.dispatcher) {
        val alerts = listOf(VoiceAlert("a", text = "A", trigger = Trigger.Always), VoiceAlert("b", text = "B", trigger = Trigger.Always))
        val session = FakeSession(MutableStateFlow(ModelExtras(voiceAlerts = alerts)))
        val vm = VoiceAlertViewModel("a", testDeps(session))
        backgroundScope.launch { vm.state.collect {} }
        vm.onAction(VoiceAlertEditorAction.Delete)
        assertEquals(listOf("b"), session.extras.value.voiceAlerts.map { it.id })
    }
}
