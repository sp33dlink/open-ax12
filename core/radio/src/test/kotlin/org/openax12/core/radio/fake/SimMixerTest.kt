package org.openax12.core.radio.fake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurvePoint
import org.openax12.core.model.CurveType
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.MixLine
import org.openax12.core.model.MixMode
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.SOURCE_COUNT
import org.openax12.core.model.SourceId
import org.openax12.core.model.Sources
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition

class SimMixerTest {

    private val eps = 1e-9

    private fun logic(vararg values: Pair<Int, Int>): IntArray =
        IntArray(SOURCE_COUNT).also { array -> values.forEach { (index, value) -> array[index] = value } }

    private fun config(
        vararg lines: MixLine,
        channel: OutputChannelConfig = OutputChannelConfig(channel = 0),
        curves: List<CurveConfig> = emptyList(),
        rates: List<DualRateSlot> = emptyList(),
    ): SimConfig {
        val base = SimConfig()
        return base.copy(
            channels = base.channels.toMutableList().also { it[0] = channel },
            mixes = base.mixes.toMutableList().also { it[0] = lines.toList() },
            curves = base.curves.toMutableList().also { list -> curves.forEach { list[it.index] = it } },
            dualRates = base.dualRates.toMutableList().also { list ->
                list[0] = list[0].toMutableList().also { slots -> rates.forEach { slots[it.slot] = it } }
            },
        ).normalized()
    }

    private fun line(source: Int, mode: MixMode = MixMode.ADD, weight: Int = 100) =
        MixLine(channel = 0, source = SourceId(source), mode = mode, weightUp = weight, weightDown = weight)

    private fun out(config: SimConfig, logic: IntArray): Double = SimMixer.compute(config, logic)[0]

    // ---- mix lines -------------------------------------------------------------------------

    @Test
    fun channelWithoutMixesIsZero() {
        assertEquals(0.0, out(config(), logic(Sources.AIL to 80)), eps)
    }

    @Test
    fun linesCombineInOrderByMode() {
        val cfg = config(
            line(Sources.AIL),
            line(Sources.ELE, weight = 50),
            line(Sources.RUD, MixMode.REPLACE),
            line(Sources.THR, MixMode.MULTIPLY),
        )
        // 40 -> +10 = 50 -> replaced by -30 -> -30 * 50 % = -15
        val result = out(cfg, logic(Sources.AIL to 40, Sources.ELE to 20, Sources.RUD to -30, Sources.THR to 50))
        assertEquals(-15.0, result, eps)
    }

    @Test
    fun weightIsChosenBySignAndOffsetIsAdded() {
        val cfg = config(MixLine(channel = 0, source = SourceId.AIL, weightUp = 50, weightDown = 80, offset = 10))
        assertEquals(40.0, out(cfg, logic(Sources.AIL to 60)), eps)
        assertEquals(-38.0, out(cfg, logic(Sources.AIL to -60)), eps)
        assertEquals(10.0, out(cfg, logic(Sources.AIL to 0)), eps)
    }

    @Test
    fun weightSideFollowsTheCurveOutputNotTheInput() {
        val abs = CurveConfig.parametric(3, CurveType.FUNC, 2)
        val cfg = config(
            MixLine(channel = 0, source = SourceId.AIL, curve = 3, weightUp = 50, weightDown = 80, offset = 10),
            curves = listOf(abs),
        )
        // |−60| = 60 is positive: weightUp applies.
        assertEquals(40.0, out(cfg, logic(Sources.AIL to -60)), eps)
    }

    @Test
    fun switchConditionGatesALine() {
        val gated = MixLine(channel = 0, source = SourceId.ELE, switch = SwitchCondition.at(SourceId.SA, SwitchPosition.DOWN))
        val cfg = config(line(Sources.AIL), gated)
        assertEquals(30.0, out(cfg, logic(Sources.AIL to 30, Sources.ELE to 50, Sources.SA to -100)), eps)
        assertEquals(30.0, out(cfg, logic(Sources.AIL to 30, Sources.ELE to 50, Sources.SA to 0)), eps)
        assertEquals(80.0, out(cfg, logic(Sources.AIL to 30, Sources.ELE to 50, Sources.SA to 100)), eps)
    }

    @Test
    fun greaterAndLessConditions() {
        val above = MixLine(channel = 0, source = SourceId.AIL, switch = SwitchCondition(SourceId.THR, CompareOp.GREATER, -90))
        val below = MixLine(channel = 0, source = SourceId.ELE, switch = SwitchCondition(SourceId.THR, CompareOp.LESS, -90))
        val cfg = config(above, below)
        assertEquals(10.0, out(cfg, logic(Sources.AIL to 10, Sources.ELE to 20, Sources.THR to 0)), eps)
        assertEquals(20.0, out(cfg, logic(Sources.AIL to 10, Sources.ELE to 20, Sources.THR to -100)), eps)
    }

    @Test
    fun slowLimitsTheRateUpAndDown() {
        val cfg = config(MixLine(channel = 0, source = SourceId.AIL, slowUpMs = 2000, slowDownMs = 1000))
        val state = SimMixerState()
        fun step(aileron: Int, dt: Long) = SimMixer.compute(cfg, logic(Sources.AIL to aileron), state, dt)[0]

        // The first step has no history and jumps to the target.
        assertEquals(-100.0, step(-100, 40), eps)
        // Up: a full sweep of 200 takes 2000 ms -> 50 per 500 ms.
        assertEquals(-50.0, step(100, 500), eps)
        assertEquals(0.0, step(100, 500), eps)
        assertEquals(50.0, step(100, 500), eps)
        assertEquals(100.0, step(100, 500), eps)
        assertEquals(100.0, step(100, 500), eps)
        // Down: 1000 ms for the full sweep -> 50 per 250 ms.
        assertEquals(50.0, step(-100, 250), eps)
        assertEquals(-100.0, step(-100, 5000), eps)
    }

    @Test
    fun slowWithoutStateIsInstant() {
        val cfg = config(MixLine(channel = 0, source = SourceId.AIL, slowUpMs = 2000, slowDownMs = 2000))
        assertEquals(100.0, out(cfg, logic(Sources.AIL to 100)), eps)
    }

    @Test
    fun slowOnlyInOneDirection() {
        val cfg = config(MixLine(channel = 0, source = SourceId.AIL, slowUpMs = 0, slowDownMs = 1000))
        val state = SimMixerState()
        fun step(aileron: Int, dt: Long) = SimMixer.compute(cfg, logic(Sources.AIL to aileron), state, dt)[0]
        assertEquals(0.0, step(0, 40), eps)
        assertEquals(100.0, step(100, 40), eps)
        assertEquals(80.0, step(-100, 100), eps)
    }

    @Test
    fun aLineThatWasSwitchedOffStartsItsRampAnew() {
        val gated = MixLine(
            channel = 0,
            source = SourceId.AIL,
            slowUpMs = 2000,
            slowDownMs = 2000,
            switch = SwitchCondition.at(SourceId.SA, SwitchPosition.DOWN),
        )
        val cfg = config(gated)
        val state = SimMixerState()
        fun step(aileron: Int, switch: Int) =
            SimMixer.compute(cfg, logic(Sources.AIL to aileron, Sources.SA to switch), state, 100)[0]
        assertEquals(-100.0, step(-100, 100), eps)
        assertEquals(-90.0, step(100, 100), eps)
        assertEquals(0.0, step(100, -100), eps)
        assertEquals(100.0, step(100, 100), eps)
    }

    // ---- dual rate, channel curve, weight, limits, lock --------------------------------------

    @Test
    fun firstActiveDualRateSlotScalesTheSum() {
        val down = SwitchCondition.at(SourceId.SB, SwitchPosition.DOWN)
        val cfg = config(
            line(Sources.AIL),
            rates = listOf(
                DualRateSlot(0, 0),
                DualRateSlot(0, 1, switch = down, weight = 70),
                DualRateSlot(0, 2, switch = down, weight = 30),
            ),
        )
        assertEquals(80.0, out(cfg, logic(Sources.AIL to 80, Sources.SB to -100)), eps)
        assertEquals(56.0, out(cfg, logic(Sources.AIL to 80, Sources.SB to 100)), eps)
    }

    @Test
    fun dualRateIsAppliedBeforeTheChannelCurve() {
        val step = CurveConfig.parametric(0, CurveType.FUNC, 3)
        val cfg = config(
            line(Sources.AIL),
            channel = OutputChannelConfig(channel = 0, curve = 0),
            curves = listOf(step),
            rates = listOf(DualRateSlot(0, 0, switch = SwitchCondition(SourceId.SB, CompareOp.GREATER, -200), weight = 50)),
        )
        // 100 -> rate 50 % = 50 -> step function f>0 = 100. The other order would give 50.
        assertEquals(100.0, out(cfg, logic(Sources.AIL to 100)), eps)
    }

    @Test
    fun channelCurveWeightMiddleAndLimits() {
        val curve = CurveConfig.custom(2, listOf(CurvePoint(-100, -50), CurvePoint(0, 0), CurvePoint(100, 100)))
        val cfg = config(
            line(Sources.AIL),
            channel = OutputChannelConfig(channel = 0, curve = 2, weight = -100, middle = 10, min = -80, max = 60),
            curves = listOf(curve),
        )
        // 50 -> curve 50 -> reversed -50 -> +10 = -40
        assertEquals(-40.0, out(cfg, logic(Sources.AIL to 50)), eps)
        // -100 -> curve -50 -> reversed 50 -> +10 = 60 (exactly the upper limit)
        assertEquals(60.0, out(cfg, logic(Sources.AIL to -100)), eps)
        // 100 -> curve 100 -> reversed -100 -> +10 = -90 -> clamped to -80
        assertEquals(-80.0, out(cfg, logic(Sources.AIL to 100)), eps)
    }

    @Test
    fun swappedLimitsStillClamp() {
        val cfg = config(line(Sources.AIL), channel = OutputChannelConfig(channel = 0, min = 50, max = -50))
        assertEquals(50.0, out(cfg, logic(Sources.AIL to 100)), eps)
        assertEquals(-50.0, out(cfg, logic(Sources.AIL to -100)), eps)
    }

    @Test
    fun channelLockOverridesEverything() {
        val cfg = config(
            line(Sources.THR),
            channel = OutputChannelConfig(
                channel = 0,
                middle = 20,
                min = -50,
                lock = SwitchCondition.at(SourceId.SF, SwitchPosition.DOWN),
                lockValue = -100,
            ),
        )
        assertEquals(70.0, out(cfg, logic(Sources.THR to 50, Sources.SF to -100)), eps)
        // The lock value is not clamped by the limits.
        assertEquals(-100.0, out(cfg, logic(Sources.THR to 50, Sources.SF to 100)), eps)
    }

    @Test
    fun sumsBeyondTheRangeAreOnlyClampedAtTheEnd() {
        val cfg = config(line(Sources.AIL), line(Sources.ELE), line(Sources.RUD, weight = -100))
        // 100 + 100 - 80 = 120 -> limit 100
        assertEquals(100.0, out(cfg, logic(Sources.AIL to 100, Sources.ELE to 100, Sources.RUD to 80)), eps)
        // 100 + 100 - 100 = 100: the intermediate 200 was not clamped
        assertEquals(100.0, out(cfg, logic(Sources.AIL to 100, Sources.ELE to 100, Sources.RUD to 100)), eps)
    }

    @Test
    fun defaultPlaneBehavesLikeAPlane() {
        val plane = SimDefaults.plane()
        val rest = IntArray(SOURCE_COUNT) { SimDefaults.restPosition(it) }
        val idle = SimMixer.compute(plane, rest)
        assertEquals(0.0, idle[0], eps)
        assertEquals(0.0, idle[1], eps)
        assertEquals(-100.0, idle[2], eps)
        assertEquals(0.0, idle[3], eps)
        assertEquals(-100.0, idle[4], eps)

        val flying = rest.copyOf().also {
            it[Sources.AIL] = 50
            it[Sources.THR] = 20
        }
        // Expo 30 %: 0.5 * 0.7 + 0.125 * 0.3 = 0.3875
        assertEquals(38.75, SimMixer.compute(plane, flying)[0], eps)
        assertEquals(20.0, SimMixer.compute(plane, flying)[2], eps)

        // Low rates on SB down, throttle cut on SF down.
        flying[Sources.SB] = 100
        flying[Sources.SF] = 100
        assertEquals(38.75 * 0.7, SimMixer.compute(plane, flying)[0], eps)
        assertEquals(-100.0, SimMixer.compute(plane, flying)[2], eps)
    }

    // ---- curves ----------------------------------------------------------------------------

    @Test
    fun noCurvePassesThroughAndNoneClamps() {
        assertEquals(150.0, SimCurves.apply(null, 150.0), eps)
        assertEquals(100.0, SimCurves.apply(CurveConfig.empty(0), 150.0), eps)
        assertEquals(-42.0, SimCurves.apply(CurveConfig.empty(0), -42.0), eps)
    }

    @Test
    fun differential() {
        val positive = CurveConfig.parametric(0, CurveType.DIFF, 40)
        assertEquals(-30.0, SimCurves.apply(positive, -50.0), eps)
        assertEquals(50.0, SimCurves.apply(positive, 50.0), eps)
        val negative = CurveConfig.parametric(0, CurveType.DIFF, -40)
        assertEquals(30.0, SimCurves.apply(negative, 50.0), eps)
        assertEquals(-50.0, SimCurves.apply(negative, -50.0), eps)
    }

    @Test
    fun expo() {
        val soft = CurveConfig.parametric(0, CurveType.EXPO, 50)
        assertEquals(31.25, SimCurves.apply(soft, 50.0), eps)
        assertEquals(-31.25, SimCurves.apply(soft, -50.0), eps)
        assertEquals(100.0, SimCurves.apply(soft, 100.0), eps)
        assertEquals(-100.0, SimCurves.apply(soft, -100.0), eps)
        assertEquals(0.0, SimCurves.apply(soft, 0.0), eps)

        assertEquals(12.5, SimCurves.apply(CurveConfig.parametric(0, CurveType.EXPO, 100), 50.0), eps)
        assertEquals(50.0, SimCurves.apply(CurveConfig.parametric(0, CurveType.EXPO, 0), 50.0), eps)

        val sharp = CurveConfig.parametric(0, CurveType.EXPO, -50)
        assertEquals(68.75, SimCurves.apply(sharp, 50.0), eps)
        assertEquals(-68.75, SimCurves.apply(sharp, -50.0), eps)
        assertEquals(100.0, SimCurves.apply(sharp, 100.0), eps)
    }

    @Test
    fun functions() {
        fun f(number: Int, x: Double) = SimCurves.apply(CurveConfig.parametric(0, CurveType.FUNC, number), x)
        assertEquals(40.0, f(0, 40.0), eps)
        assertEquals(0.0, f(0, -40.0), eps)
        assertEquals(0.0, f(1, 40.0), eps)
        assertEquals(-40.0, f(1, -40.0), eps)
        assertEquals(40.0, f(2, -40.0), eps)
        assertEquals(100.0, f(3, 1.0), eps)
        assertEquals(0.0, f(3, -1.0), eps)
        assertEquals(-100.0, f(4, -1.0), eps)
        assertEquals(0.0, f(4, 1.0), eps)
        assertEquals(100.0, f(5, 3.0), eps)
        assertEquals(-100.0, f(5, -3.0), eps)
        assertEquals(0.0, f(5, 0.0), eps)
        // Unknown function numbers are the identity.
        assertEquals(-40.0, f(42, -40.0), eps)
    }

    @Test
    fun customCurveInterpolatesAndIsFlatOutside() {
        val curve = CurveConfig.custom(0, listOf(CurvePoint(-100, -100), CurvePoint(0, 20), CurvePoint(100, 60)))
        assertEquals(-40.0, SimCurves.apply(curve, -50.0), eps)
        assertEquals(20.0, SimCurves.apply(curve, 0.0), eps)
        assertEquals(40.0, SimCurves.apply(curve, 50.0), eps)

        val narrow = CurveConfig.custom(0, listOf(CurvePoint(-50, -30), CurvePoint(50, 30)))
        assertEquals(-30.0, SimCurves.apply(narrow, -100.0), eps)
        assertEquals(30.0, SimCurves.apply(narrow, 100.0), eps)
        assertEquals(15.0, SimCurves.apply(narrow, 25.0), eps)
    }

    @Test
    fun threePointSwitchCurveGivesPerPositionOffsets() {
        // The flight-mode compiler's shape: one offset per switch position.
        val offsets = CurveConfig.custom(0, listOf(CurvePoint(-100, 0), CurvePoint(0, 12), CurvePoint(100, -25)))
        val cfg = config(
            line(Sources.ELE),
            MixLine(channel = 0, source = SourceId.SA, curve = 0),
            curves = listOf(offsets),
        )
        assertEquals(10.0, out(cfg, logic(Sources.ELE to 10, Sources.SA to -100)), eps)
        assertEquals(22.0, out(cfg, logic(Sources.ELE to 10, Sources.SA to 0)), eps)
        assertEquals(-15.0, out(cfg, logic(Sources.ELE to 10, Sources.SA to 100)), eps)
    }

    @Test
    fun invalidCustomCurvesAreTheIdentityAndAreNotAccepted() {
        val descending = CurveConfig.custom(0, listOf(CurvePoint(50, 0), CurvePoint(-50, 100)))
        assertEquals(33.0, SimCurves.apply(descending, 33.0), eps)
        assertFalse(SimCurves.isAcceptable(descending))

        val single = CurveConfig.custom(0, listOf(CurvePoint(0, 0)))
        assertEquals(33.0, SimCurves.apply(single, 33.0), eps)
        assertFalse(SimCurves.isAcceptable(single))

        val outOfRange = CurveConfig.custom(0, listOf(CurvePoint(-100, -150), CurvePoint(100, 100)))
        assertFalse(SimCurves.isAcceptable(outOfRange))

        assertFalse(SimCurves.isAcceptable(CurveConfig(index = 0, type = CurveType.EXPO, pointCount = 13)))
        assertTrue(SimCurves.isAcceptable(CurveConfig.parametric(0, CurveType.EXPO, 30)))
        assertTrue(SimCurves.isAcceptable(CurveConfig.custom(0, listOf(CurvePoint(-100, -100), CurvePoint(100, 100)))))
    }

    @Test
    fun normalizedRepairsADamagedConfiguration() {
        val damaged = SimConfig(
            channels = listOf(OutputChannelConfig(channel = 3, curve = 77)),
            mixes = listOf(
                listOf(
                    MixLine(channel = 9, mixIndex = 4, source = SourceId.AIL, curve = 99),
                    MixLine(channel = 0, source = SourceId.AIL),
                    MixLine(channel = 0, source = SourceId(500)),
                ),
            ),
            curves = listOf(CurveConfig(index = 2, type = CurveType.EXPO, pointCount = 40, x = emptyList(), y = listOf(30))),
        ).normalized()
        assertEquals(16, damaged.channels.size)
        assertEquals(org.openax12.core.model.NO_CURVE, damaged.channels[3].curve)
        assertEquals(1, damaged.mixes[0].size)
        assertEquals(0, damaged.mixes[0][0].channel)
        assertEquals(0, damaged.mixes[0][0].mixIndex)
        assertEquals(org.openax12.core.model.NO_CURVE, damaged.mixes[0][0].curve)
        assertEquals(18, damaged.curves.size)
        assertEquals(30, damaged.curves[2].parameter)
        assertEquals(12, damaged.curves[2].x.size)
        // Nothing in a normalized configuration can make the mixer fail.
        SimMixer.compute(damaged, IntArray(SOURCE_COUNT) { 100 })
    }
}
