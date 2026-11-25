package org.openax12.feature.modelsetup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.engine.MixerMath
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurvePoint
import org.openax12.core.model.CurveType
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.MixLine
import org.openax12.core.model.MixMode
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.SOURCE_COUNT
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.WorkingConfig
import org.openax12.feature.modelsetup.logic.ConditionText
import org.openax12.feature.modelsetup.logic.CurveEdit
import org.openax12.feature.modelsetup.logic.CurveUse
import org.openax12.feature.modelsetup.logic.ExplainStep
import org.openax12.feature.modelsetup.logic.ExplainTemplates
import org.openax12.feature.modelsetup.logic.ManagedKind
import org.openax12.feature.modelsetup.logic.activeDualRateSlot
import org.openax12.feature.modelsetup.logic.conditionFor
import org.openax12.feature.modelsetup.logic.curveUsage
import org.openax12.feature.modelsetup.logic.defaultMarkerSource
import org.openax12.feature.modelsetup.logic.describe
import org.openax12.feature.modelsetup.logic.explainSteps
import org.openax12.feature.modelsetup.logic.findThrottleChannel
import org.openax12.feature.modelsetup.logic.renderExplain
import org.openax12.feature.modelsetup.logic.symbol
import org.openax12.feature.modelsetup.output.sanitize as sanitizeChannel
import org.openax12.feature.modelsetup.timers.formatMinutesSeconds
import org.openax12.feature.modelsetup.timers.sanitize as sanitizeTimer
import org.openax12.core.model.TimerConfig

class SetupLogicTest {

    @Test
    fun managedKindFollowsTheGeneratorName() {
        assertNull(ManagedKind.of(null))
        assertEquals(ManagedKind.FLIGHT_MODES, ManagedKind.of(ManagedItems.GENERATOR_FLIGHT_MODES))
        assertEquals(ManagedKind.WING, ManagedKind.of(ManagedItems.GENERATOR_WING))
        assertEquals(ManagedKind.TEMPLATE, ManagedKind.of("template:glider"))
        assertEquals(ManagedKind.OTHER, ManagedKind.of("something"))
    }

    @Test
    fun describesSwitchPositionsAndComparisons() {
        assertEquals(ConditionText.Off, SwitchCondition.NONE.describe())
        assertEquals(
            ConditionText.SwitchAt(SourceId.SA, SwitchPosition.MID),
            SwitchCondition.at(SourceId.SA, SwitchPosition.MID).describe(),
        )
        // A stick compared with "=" is a comparison, not a switch position.
        val stick = SwitchCondition(SourceId.THR, CompareOp.EQUAL, 100)
        assertEquals(ConditionText.Compare(SourceId.THR, CompareOp.EQUAL, 100), stick.describe())
        // A switch with a value between the positions is a comparison too.
        val odd = SwitchCondition(SourceId.SA, CompareOp.GREATER, -100)
        assertEquals(ConditionText.Compare(SourceId.SA, CompareOp.GREATER, -100), odd.describe())
    }

    @Test
    fun conditionForPicksSensibleDefaults() {
        // A switch with "=" starts at "down"; switching the operator keeps the value.
        val fresh = conditionFor(SourceId.SB, CompareOp.EQUAL, SwitchCondition.NONE)
        assertEquals(SwitchCondition(SourceId.SB, CompareOp.EQUAL, 100), fresh)
        val up = SwitchCondition.at(SourceId.SB, SwitchPosition.UP)
        assertEquals(SwitchCondition(SourceId.SB, CompareOp.GREATER, -100), conditionFor(SourceId.SB, CompareOp.GREATER, up))
        // Another source starts neutral; "Off" clears the value.
        assertEquals(SwitchCondition(SourceId.THR, CompareOp.GREATER, 0), conditionFor(SourceId.THR, CompareOp.GREATER, up))
        assertEquals(SwitchCondition(SourceId.THR, CompareOp.DISABLED, 0), conditionFor(SourceId.THR, CompareOp.DISABLED, up))
        // A value between two positions snaps when "=" is chosen for a switch.
        val between = SwitchCondition(SourceId.SB, CompareOp.GREATER, 60)
        assertEquals(100, conditionFor(SourceId.SB, CompareOp.EQUAL, between).value)
    }

    @Test
    fun mixModeSymbols() {
        assertEquals(":=", MixMode.REPLACE.symbol())
        assertEquals("+=", MixMode.ADD.symbol())
        assertEquals("*=", MixMode.MULTIPLY.symbol())
    }

    private fun config(): WorkingConfig {
        val mixes = listOf(
            MixLine(channel = 0, mixIndex = 0, source = SourceId.AIL, weightUp = 50, weightDown = 50),
            MixLine(channel = 0, mixIndex = 1, source = SourceId.SA, switch = SwitchCondition.at(SourceId.SB, SwitchPosition.DOWN)),
            MixLine(channel = 1, mixIndex = 0, source = SourceId.ELE, curve = 3),
            MixLine(channel = 2, mixIndex = 0, source = SourceId.THR),
        )
        val channels = List(4) { OutputChannelConfig(channel = it) }.toMutableList()
        channels[0] = channels[0].copy(weight = 80, curve = 2)
        channels[3] = channels[3].copy(curve = 3)
        return WorkingConfig(
            channels = channels,
            mixes = mixes,
            curves = List(18) { CurveConfig.empty(it) },
        )
    }

    @Test
    fun explainStepsAndTextCoverEveryStage() {
        val base = config()
        val cfg = base.copy(
            curves = base.curves.toMutableList().also { it[2] = CurveConfig.parametric(2, CurveType.EXPO, 0) },
            dualRates = listOf(DualRateSlot(0, 1, 1, SwitchCondition.at(SourceId.SC, SwitchPosition.UP), 50)),
        )
        val logic = IntArray(SOURCE_COUNT)
        logic[SourceId.AIL.index] = 80
        logic[SourceId.SA.index] = 100
        logic[SourceId.SB.index] = 0 // the SA line is off
        logic[SourceId.SC.index] = -100 // dual rate slot 2 active
        val explanation = MixerMath.explain(cfg, logic, 0)!!
        val steps = explainSteps(explanation, cfg.channel(0))
        assertEquals(ExplainStep.Line(SourceId.AIL, true, MixMode.ADD, 40, 40), steps[0])
        assertEquals(ExplainStep.Line(SourceId.SA, false, MixMode.ADD, 100, 40), steps[1])
        assertEquals(ExplainStep.DualRate(1, 50, 20), steps[2])
        assertTrue(steps[3] is ExplainStep.OutputCurve)
        assertEquals(ExplainStep.Weight(80, 16), steps[4])
        assertEquals(ExplainStep.Output(16), steps.last())

        val templates = ExplainTemplates(
            line = "%1\$s %2\$s %3\$d", lineOff = "%1\$s off", dualRate = "DR%1\$d %2\$d=%3\$d", curve = "C%1\$d=%2\$d",
            weight = "w%1\$d=%2\$d", limits = "lim=%1\$d", lock = "lock %1\$d", output = "=%1\$d", separator = " | ",
        )
        assertEquals("+= Ail 40 | SA off | DR2 50=20 | C3=20 | w80=16 | =16", renderExplain(steps, cfg::sourceName, templates))
    }

    @Test
    fun explainStepsReportLimitsAndLock() {
        val cfg = WorkingConfig(
            channels = listOf(
                OutputChannelConfig(channel = 0, max = 50, lock = SwitchCondition.at(SourceId.SA, SwitchPosition.DOWN), lockValue = -100),
            ),
            mixes = listOf(MixLine(channel = 0, source = SourceId.AIL)),
        )
        val logic = IntArray(SOURCE_COUNT)
        logic[SourceId.AIL.index] = 90
        val clamped = explainSteps(MixerMath.explain(cfg, logic, 0)!!, cfg.channel(0))
        assertTrue(clamped.any { it == ExplainStep.Limits(50) })
        logic[SourceId.SA.index] = 100
        val locked = explainSteps(MixerMath.explain(cfg, logic, 0)!!, cfg.channel(0))
        assertTrue(locked.any { it == ExplainStep.Lock(-100) })
        assertEquals(ExplainStep.Output(-100), locked.last())
    }

    @Test
    fun curveUsageListsMixesAndOutputs() {
        val uses = curveUsage(config(), 3)
        assertEquals(listOf(CurveUse.Mix(1, SourceId.ELE), CurveUse.Output(3)), uses)
        assertTrue(curveUsage(config(), 10).isEmpty())
    }

    @Test
    fun markerSourceFollowsTheCurveUse() {
        assertEquals(SourceId.ELE, defaultMarkerSource(config(), 3))
        // An output curve takes the first mix line of its channel.
        assertEquals(SourceId.AIL, defaultMarkerSource(config(), 2))
        assertEquals(SourceId.AIL, defaultMarkerSource(config(), 9))
    }

    @Test
    fun throttleChannelIsTheOneWithTheThrottleMix() {
        assertEquals(2, findThrottleChannel(config()))
        assertNull(findThrottleChannel(WorkingConfig.EMPTY))
    }

    @Test
    fun activeDualRateSlotIsTheFirstEnabledMatchingOne() {
        val slots = listOf(
            DualRateSlot(0, 0, 0, SwitchCondition.NONE, 100),
            DualRateSlot(0, 1, 0, SwitchCondition.at(SourceId.SA, SwitchPosition.DOWN), 80),
            DualRateSlot(0, 2, 0, SwitchCondition.at(SourceId.SB, SwitchPosition.DOWN), 60),
        )
        val sa = SourceId.SA.index
        val sb = SourceId.SB.index
        assertEquals(1, activeDualRateSlot(slots) { if (it == sa || it == sb) 100 else 0 })
        assertEquals(2, activeDualRateSlot(slots) { if (it == sb) 100 else 0 })
        assertNull(activeDualRateSlot(slots) { 0 })
    }

    // ---- curve editing --------------------------------------------------------------------

    private val custom = CurveConfig.custom(
        4,
        listOf(CurvePoint(-100, -100), CurvePoint(0, 20), CurvePoint(60, 60), CurvePoint(100, 100)),
    )

    @Test
    fun identityCurveIsStraight() {
        val c = CurveEdit.identity(1, 5)
        assertEquals(CurveType.CUSTOM, c.type)
        assertEquals(listOf(-100, -50, 0, 50, 100), c.points.map { it.x })
        assertEquals(c.points.map { it.x }, c.points.map { it.y })
    }

    @Test
    fun movePointStaysBetweenItsNeighboursAndInRange() {
        // Middle point: x is clamped to (previous, next) exclusive.
        val moved = CurveEdit.movePoint(custom, 1, 500, 500)
        assertEquals(CurvePoint(59, 100), moved.points[1])
        val left = CurveEdit.movePoint(custom, 1, -500, -500)
        assertEquals(CurvePoint(-99, -100), left.points[1])
        // The ends may reach the edges of the plot.
        assertEquals(CurvePoint(-100, 30), CurveEdit.movePoint(custom, 0, -150, 30).points[0])
        assertEquals(CurvePoint(100, -100), CurveEdit.movePoint(custom, 3, 150, -150).points[3])
        // Not a custom curve or a bad index: unchanged.
        assertEquals(custom, CurveEdit.movePoint(custom, 7, 0, 0))
        val expo = CurveConfig.parametric(0, CurveType.EXPO, 20)
        assertEquals(expo, CurveEdit.movePoint(expo, 0, 0, 0))
    }

    @Test
    fun addPointInsertsOnTheCurve() {
        val added = CurveEdit.addPoint(custom, 1)!!
        assertEquals(5, added.pointCount)
        assertEquals(CurvePoint(30, 40), added.points[2])
        // After the last point it extends to the right while there is room, else it refuses.
        val short = CurveConfig.custom(4, listOf(CurvePoint(-100, -100), CurvePoint(40, 40)))
        // Beyond the last point the curve continues flat, so the new point sits on that.
        assertEquals(CurvePoint(60, 40), CurveEdit.addPoint(short, 1)!!.points[2])
        assertNull(CurveEdit.addPoint(custom, 3))
        // No room between neighbours one apart.
        val tight = CurveConfig.custom(4, listOf(CurvePoint(0, 0), CurvePoint(1, 1), CurvePoint(100, 100)))
        assertNull(CurveEdit.addPoint(tight, 0))
        // Full curve.
        assertNull(CurveEdit.addPoint(CurveEdit.identity(4, 12), 3))
    }

    @Test
    fun removePointKeepsAtLeastTwo() {
        assertEquals(3, CurveEdit.removePoint(custom, 1)!!.pointCount)
        val two = CurveEdit.identity(4, 2)
        assertNull(CurveEdit.removePoint(two, 0))
    }

    @Test
    fun typeChangesKeepTheShapeOrStartNeutral() {
        val expo = CurveConfig.parametric(5, CurveType.EXPO, 50)
        val asCustom = CurveEdit.withType(expo, CurveType.CUSTOM)
        assertEquals(CurveType.CUSTOM, asCustom.type)
        assertEquals(9, asCustom.pointCount)
        assertEquals(0, asCustom.points[4].y) // centre stays at zero
        assertTrue(asCustom.points[6].y < asCustom.points[6].x) // expo flattens around the centre
        assertEquals(CurveType.CUSTOM, CurveEdit.withType(CurveConfig.empty(5), CurveType.CUSTOM).type)
        assertEquals(CurveConfig.parametric(5, CurveType.DIFF, 0), CurveEdit.withType(expo, CurveType.DIFF))
        assertEquals(CurveConfig.empty(5), CurveEdit.withType(expo, CurveType.NONE))
        assertEquals(expo, CurveEdit.withType(expo, CurveType.EXPO))
    }

    @Test
    fun parameterIsClamped() {
        val expo = CurveConfig.parametric(5, CurveType.EXPO, 0)
        assertEquals(100, CurveEdit.withParameter(expo, 500).parameter)
        assertEquals(-100, CurveEdit.withParameter(expo, -500).parameter)
    }

    @Test
    fun plotMappingRoundTrips() {
        val size = 300f
        for (v in listOf(-100, -37, 0, 64, 100)) {
            assertEquals(v, CurveEdit.fromPlotX(CurveEdit.toPlotX(v, size), size))
            assertEquals(v, CurveEdit.fromPlotY(CurveEdit.toPlotY(v, size), size))
        }
        // The top of the plot is +100, the bottom -100.
        assertEquals(0f, CurveEdit.toPlotY(100, size), 0.001f)
        assertEquals(size, CurveEdit.toPlotY(-100, size), 0.001f)
        assertEquals(100, CurveEdit.fromPlotX(10_000f, size))
    }

    // ---- sanitising -----------------------------------------------------------------------

    @Test
    fun channelLimitsStayOrderedAndInRange() {
        val c = sanitizeChannel(OutputChannelConfig(channel = 0, min = 40, max = 10, middle = 300, weight = -300))
        assertEquals(40, c.min)
        assertEquals(40, c.max)
        assertEquals(100, c.middle)
        assertEquals(-100, c.weight)
        assertEquals(NO_CURVE, c.curve)
    }

    @Test
    fun counterMaximumIsNeverZero() {
        assertEquals(1, sanitizeTimer(TimerConfig(index = 0, startTime = 0), isCounter = true).startTime)
        assertEquals(0, sanitizeTimer(TimerConfig(index = 0, startTime = 0), isCounter = false).startTime)
        assertEquals(35_999, sanitizeTimer(TimerConfig(index = 0, startTime = 99_999), isCounter = false).startTime)
    }

    @Test
    fun timerTextShowsHoursOnlyWhenNeeded() {
        assertEquals("10:00", formatMinutesSeconds(600))
        assertEquals("00:05", formatMinutesSeconds(5))
        assertEquals("1:01:01", formatMinutesSeconds(3661))
        assertNotNull(formatMinutesSeconds(0))
    }
}
