package org.openax12.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openax12.core.model.CompareOp
import org.openax12.core.model.ButterflyConfig
import org.openax12.core.model.FlapConfig
import org.openax12.core.model.FlapControl
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.MixLine
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition

/**
 * The AX12 reports a switch as +100 % up / 0 % mid / -100 % down (assumption 3, verified on the
 * radio). These tests run the compiled firmware items through the mixer simulation and check that
 * every position still selects what the editor labelled it with.
 */
class SwitchDirectionTest {

    private val up = SwitchPosition.UP.logic
    private val mid = SwitchPosition.MID.logic
    private val down = SwitchPosition.DOWN.logic

    @Test
    fun flightModeOffsetsFollowThePhysicalPosition() {
        val config = FlightModesConfig(
            enabled = true,
            modeSwitch = SourceId.SA,
            modes = listOf(
                FlightModeConfig("Cruise", SwitchPosition.MID),
                FlightModeConfig("Speed", SwitchPosition.UP, offsets = mapOf(0 to 7)),
                FlightModeConfig("Thermal", SwitchPosition.DOWN, offsets = mapOf(0 to -9)),
            ),
        )
        val plan = FlightModeCompiler.compile(config, neutralConfig())
        // The curve points ascend in x (down, mid, up) and carry the offset of that position.
        val points = plan.curves.single().points
        assertEquals(listOf(-100, 0, 100), points.map { it.x })
        assertEquals(listOf(-9, 0, 7), points.map { it.y })

        val applied = neutralConfig().applyPlan(plan).config
        assertEquals(7.0, applied.outputs(SourceId.SA to up)[0], EPS)
        assertEquals(0.0, applied.outputs(SourceId.SA to mid)[0], EPS)
        assertEquals(-9.0, applied.outputs(SourceId.SA to down)[0], EPS)
    }

    @Test
    fun flightModeRatesFollowThePhysicalPosition() {
        val config = FlightModesConfig(
            enabled = true,
            modeSwitch = SourceId.SB,
            modes = listOf(
                FlightModeConfig("Normal", SwitchPosition.MID),
                FlightModeConfig("Soft", SwitchPosition.UP, rates = mapOf(0 to 50)),
                FlightModeConfig("Hard", SwitchPosition.DOWN, rates = mapOf(0 to 30)),
            ),
        )
        val plan = FlightModeCompiler.compile(config, neutralConfig())
        // The dual-rate conditions compare with the value the switch reports in that position.
        assertEquals(setOf(up, down), plan.dualRates.map { it.switch.value }.toSet())
        assertEquals(setOf(CompareOp.EQUAL), plan.dualRates.map { it.switch.op }.toSet())

        val base = neutralConfig().withMix(MixLine(channel = 0, source = SourceId.AIL))
        val applied = base.applyPlan(plan).config
        assertEquals(50.0, applied.outputs(SourceId.AIL to 100, SourceId.SB to up)[0], EPS)
        assertEquals(100.0, applied.outputs(SourceId.AIL to 100, SourceId.SB to mid)[0], EPS)
        assertEquals(30.0, applied.outputs(SourceId.AIL to 100, SourceId.SB to down)[0], EPS)
    }

    @Test
    fun helicopterIdleUpFollowsTheModeSwitch() {
        val template = ModelTemplates.byId(ModelTemplates.HELICOPTER)!!
        val options = TemplateOptions(modeSwitch = SourceId.SA, throttleHoldSwitch = SourceId.SF)
        val blueprint = ModelTemplates.build(template, options, neutralConfig())
        val applied = neutralConfig().applyPlan(blueprint.effectivePlan).config
        val throttle = ModelTemplates.CH_THROTTLE

        fun out(mode: Int, hold: Int) = applied.outputs(SourceId.THR to 0, SourceId.SA to mode, SourceId.SF to hold)[throttle]
        // Throttle hold: SF down is "live".
        assertEquals("normal (SA up)", 0.0, out(up, down), EPS)
        assertEquals("idle-up 1 (SA mid)", 40.0, out(mid, down), EPS)
        assertEquals("idle-up 2 (SA down)", 70.0, out(down, down), EPS)
        // Hold switch not down (mid or up): the channel is locked low.
        assertEquals(-100.0, out(down, up), EPS)
        assertEquals(-100.0, out(down, mid), EPS)

        // The helicopter's flight modes are Normal on up, Idle up 1 on mid, Idle up 2 on down.
        val modes = blueprint.extras.flightModes.modes
        assertEquals(listOf("Normal", "Idle up 1", "Idle up 2"), modes.map { it.name })
        assertEquals(listOf(SwitchPosition.UP, SwitchPosition.MID, SwitchPosition.DOWN), modes.map { it.position })
    }

    @Test
    fun armSwitchIsLiveOnlyWhenDown() {
        val template = ModelTemplates.byId(ModelTemplates.PLANE)!!
        val blueprint = ModelTemplates.build(template, TemplateOptions(armSwitch = SourceId.SF), neutralConfig())
        val lock = blueprint.effectivePlan.channels.single { it.channel == ModelTemplates.CH_THROTTLE }.lock!!
        // "Disarmed" is every position but down: the lock is active at up and mid, not at down.
        assertEquals(true, lock.isActive(frame(mapOf(SourceId.SF to up))))
        assertEquals(true, lock.isActive(frame(mapOf(SourceId.SF to mid))))
        assertEquals(false, lock.isActive(frame(mapOf(SourceId.SF to down))))
        assertEquals(SwitchCondition(SourceId.SF, CompareOp.GREATER, ModelTemplates.ARMED_BELOW), lock)
    }

    @Test
    fun flapSwitchDeflectionsFollowThePhysicalPosition() {
        val template = ModelTemplates.byId(ModelTemplates.GLIDER)!!
        val wing = ModelTemplates.build(template, TemplateOptions(wingServos = 4), neutralConfig()).extras.wing
            .copy(
                butterfly = ButterflyConfig(),
                flaps = FlapConfig(FlapControl.OnSwitch(SourceId.SB, deflectionUp = 20, deflectionMid = 50, deflectionDown = 100)),
            )
        val plan = WingCompiler.compile(wing, neutralConfig())
        val curve = plan.curves.single { it.key == WingCompiler.CURVE_FLAP }
        // Down first: the flap goes to 100 % at down, 50 % at mid and 20 % at up (negative = down on the wing).
        assertEquals(listOf(-100, 0, 100), curve.points.map { it.x })
        assertEquals(listOf(-100, -50, -20), curve.points.map { it.y })
        val applied = neutralConfig().applyPlan(plan).config
        val flap = wing.channels.flapRight!!
        assertEquals(-20.0, applied.outputs(SourceId.SB to up)[flap], EPS)
        assertEquals(-50.0, applied.outputs(SourceId.SB to mid)[flap], EPS)
        assertEquals(-100.0, applied.outputs(SourceId.SB to down)[flap], EPS)
    }
}
