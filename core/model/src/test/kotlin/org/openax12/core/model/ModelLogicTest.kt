package org.openax12.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLogicTest {

    @Test
    fun `source ids map to names and kinds`() {
        assertEquals(SOURCE_COUNT, SourceId.all.size)
        assertEquals("Rud", SourceId(0).defaultName)
        assertEquals("P8", SourceId(11).defaultName)
        assertEquals("S1", SourceId(12).defaultName)
        assertEquals("SA", SourceId(20).defaultName)
        assertEquals("SP", SourceId(35).defaultName)
        assertEquals("CNT6", SourceId(41).defaultName)
        assertEquals(SourceKind.SLIDER, SourceId.slider(8).kind)
        assertEquals(SourceKind.SWITCH, SourceId.switch('p').kind)
        assertEquals(SourceId(Sources.SP), SourceId.switch('P'))
        assertEquals(SourceId.THR, SourceId.fromName("thr"))
        assertNull(SourceId.fromName("nope"))
        assertFalse(SourceId(42).isValid)
        assertEquals(SourceId(Sources.CNT1), SourceId.counter(1))
    }

    @Test
    fun `switch position maps to the logic values the AX12 reports`() {
        // Verified on the radio (assumption 3): physical up = +100 %, mid = 0 %, down = -100 %.
        assertEquals(100, SwitchPosition.UP.logic)
        assertEquals(0, SwitchPosition.MID.logic)
        assertEquals(-100, SwitchPosition.DOWN.logic)
        assertEquals(SwitchPosition.UP, SwitchPosition.fromLogic(100))
        assertEquals(SwitchPosition.UP, SwitchPosition.fromLogic(51))
        assertEquals(SwitchPosition.MID, SwitchPosition.fromLogic(50))
        assertEquals(SwitchPosition.MID, SwitchPosition.fromLogic(0))
        assertEquals(SwitchPosition.MID, SwitchPosition.fromLogic(-50))
        assertEquals(SwitchPosition.DOWN, SwitchPosition.fromLogic(-51))
        assertEquals(SwitchPosition.DOWN, SwitchPosition.fromLogic(-100))
        // Round trip, and the ascending order custom curves need (down first).
        for (position in SwitchPosition.entries) assertEquals(position, SwitchPosition.fromLogic(position.logic))
        assertEquals(listOf(SwitchPosition.DOWN, SwitchPosition.MID, SwitchPosition.UP), SwitchPosition.byLogic)
        // A condition built for a position matches the value the switch reports there.
        assertEquals(CompareOp.EQUAL, SwitchCondition.at(SourceId.SA, SwitchPosition.UP).op)
        assertEquals(100, SwitchCondition.at(SourceId.SA, SwitchPosition.UP).value)
        assertEquals(-100, SwitchCondition.at(SourceId.SA, SwitchPosition.DOWN).value)
    }

    @Test
    fun `switch condition evaluates against live logic values`() {
        val live = LiveSnapshot.of(logic = mapOf(SourceId.SA to -100, SourceId.THR to -95, SourceId.SB to 100))
        assertTrue(SwitchCondition.at(SourceId.SA, SwitchPosition.DOWN).isActive(live))
        assertFalse(SwitchCondition.at(SourceId.SA, SwitchPosition.MID).isActive(live))
        assertTrue(SwitchCondition(SourceId.THR, CompareOp.LESS, -90).isActive(live))
        assertFalse(SwitchCondition(SourceId.THR, CompareOp.GREATER, -90).isActive(live))
        assertNull(SwitchCondition.NONE.evaluate(live))
        assertTrue(SwitchCondition.NONE.isActive(live))
        assertFalse(SwitchCondition.NONE.isActive(live, whenDisabled = false))
        assertEquals(SwitchPosition.UP, live.switchPosition(SourceId.SB))
        // Out-of-range reads are safe.
        assertEquals(0, live.logic(SourceId(99)))
        assertEquals(0, live.channel(99))
        assertEquals(0, LiveSnapshot.EMPTY.channel(3))
        assertTrue(LiveSnapshot.EMPTY.isEmpty)
    }

    @Test
    fun `logical switch states pack into bits`() {
        val states = LogicalSwitchStates.NONE.with(0, true).with(31, true).with(5, true).with(5, false)
        assertTrue(states[0])
        assertTrue(states[31])
        assertFalse(states[5])
        assertFalse(states[32])
        assertEquals(listOf(0, 31), states.activeIndices())
        assertEquals(states, states.with(40, true))
    }

    @Test
    fun `sensor ids parse their namespaces`() {
        assertEquals(SourceId.SA, SensorId.input(SourceId.SA).source)
        assertEquals("in.SA", SensorId.input(SourceId.SA).key)
        assertEquals(2, SensorId("ch.3").channelIndex)
        assertNull(SensorId("ch.17").channelIndex)
        assertEquals(0, SensorId.timer(0).timerIndex)
        assertEquals(31, SensorId.logicalSwitch(31).logicalSwitchIndex)
        assertTrue(SensorId.channel(0).isLive)
        assertFalse(Sensors.FLIGHT_MODE.isLive)
        assertEquals(Sensors.BATTERY_VOLTAGE, Sensors.byTemplateName("rxbt"))
        assertEquals("crsf.rf.1011", Sensors.rotorflight(0x1011).key)
    }

    @Test
    fun `flight mode follows the mode switch`() {
        val config = FlightModesConfig(
            enabled = true,
            modeSwitch = SourceId.SC,
            modes = listOf(
                FlightModeConfig("Cruise", SwitchPosition.MID),
                FlightModeConfig("Launch", SwitchPosition.UP),
                FlightModeConfig("Landing", SwitchPosition.DOWN),
            ),
        )
        assertEquals(1, config.activeModeIndex(LiveSnapshot.of(logic = mapOf(SourceId.SC to 100))))
        assertEquals(0, config.activeModeIndex(LiveSnapshot.of(logic = mapOf(SourceId.SC to 0))))
        assertEquals(2, config.activeModeIndex(LiveSnapshot.of(logic = mapOf(SourceId.SC to -100))))
        assertEquals(0, config.copy(enabled = false).activeModeIndex(LiveSnapshot.of(logic = mapOf(SourceId.SC to -100))))
    }

    @Test
    fun `curve helpers`() {
        val custom = CurveConfig.custom(4, listOf(CurvePoint(-100, 0), CurvePoint(0, 50), CurvePoint(100, 100)))
        assertEquals(3, custom.pointCount)
        assertEquals(CURVE_MAX_POINTS, custom.x.size)
        assertEquals(CurvePoint(0, 50), custom.points[1])
        assertTrue(custom.sameShapeAs(CurveConfig.custom(9, custom.points)))
        assertFalse(custom.sameShapeAs(CurveConfig.parametric(9, CurveType.EXPO, 30)))
        assertEquals(30, CurveConfig.parametric(1, CurveType.EXPO, 30).parameter)
        assertEquals(custom, PlannedCurve("k", CurveType.CUSTOM, custom.points).toConfig(4))
    }

    @Test
    fun `managed items bookkeeping`() {
        val fm = ManagedItems(mixes = listOf(ManagedMix(0, 20, "flightModes")), curves = listOf(ManagedCurve(17, "flightModes")))
        val wing = ManagedItems(mixes = listOf(ManagedMix(4, 3, "wing")), dualRates = listOf(ManagedDualRate(0, 2, "wing")))
        val all = fm + wing
        assertEquals("flightModes", all.generatorOfMix(0, 20))
        assertEquals("wing", all.generatorOfDualRate(0, 2))
        assertNull(all.generatorOfMix(1, 1))
        assertEquals(wing, all.without("flightModes"))
        assertEquals(fm, all.of("flightModes"))
        assertTrue(all.withoutMix(0, 20).of("flightModes").mixes.isEmpty())
    }

    @Test
    fun `managed fingerprints detect hand edits and survive re-stamping`() {
        val mix = MixLine(channel = 2, mixIndex = 0, source = SourceId.SA, curve = 17, weightUp = 40, weightDown = 40)
        val curve = CurveConfig.custom(17, listOf(CurvePoint(-100, -20), CurvePoint(0, 0), CurvePoint(100, 20)))
        val slot = DualRateSlot(channel = 2, slot = 1, switch = SwitchCondition.at(SourceId.SA, SwitchPosition.DOWN), weight = 70)
        val config = WorkingConfig(
            channels = listOf(OutputChannelConfig(2, mixCount = 1)),
            mixes = listOf(mix),
            curves = listOf(curve),
            dualRates = listOf(slot),
        )
        val recorded = ManagedItems(
            mixes = listOf(ManagedMix(2, Sources.SA, "flightModes")),
            curves = listOf(ManagedCurve(17, "flightModes")),
            dualRates = listOf(ManagedDualRate(2, 1, "flightModes")),
        )
        // Records without a fingerprint never count as modified.
        assertFalse(recorded.isModified(recorded.mixes[0], config))

        val stamped = recorded.withFingerprintsFrom(config)
        assertEquals(mix.managedFingerprint(), stamped.mixes[0].fingerprint)
        assertTrue(stamped.mixes[0].fingerprint != ManagedItems.FINGERPRINT_UNKNOWN)
        assertFalse(stamped.isModified(stamped.mixes[0], config))
        assertFalse(stamped.isModified(stamped.curves[0], config))
        assertFalse(stamped.isModified(stamped.dualRates[0], config))

        // Read-only and live fields do not matter, generated fields do.
        assertEquals(mix.managedFingerprint(), mix.copy(sourceName = "SA", sourceType = 3, mixCount = 4, mixIndex = 2).managedFingerprint())
        val edited = config.copy(
            mixes = listOf(mix.copy(weightUp = 45)),
            curves = listOf(CurveConfig.custom(17, listOf(CurvePoint(-100, -25), CurvePoint(0, 0), CurvePoint(100, 20)))),
            dualRates = listOf(slot.copy(weight = 60)),
        )
        assertTrue(stamped.isModified(stamped.mixes[0], edited))
        assertTrue(stamped.isModified(stamped.curves[0], edited))
        assertTrue(stamped.isModified(stamped.dualRates[0], edited))

        // A disabled condition ignores its leftover source / value.
        assertEquals(
            slot.copy(switch = SwitchCondition.NONE).managedFingerprint(),
            slot.copy(switch = SwitchCondition(SourceId.SB, CompareOp.DISABLED, 55)).managedFingerprint(),
        )

        // Missing items are not "modified"; plus() lets the newer record win.
        assertFalse(stamped.isModified(stamped.mixes[0], WorkingConfig.EMPTY))
        val merged = recorded + stamped
        assertEquals(1, merged.mixes.size)
        assertEquals(stamped.mixes[0].fingerprint, merged.mixes[0].fingerprint)
        assertTrue(stamped.withoutCurve(17).curves.isEmpty())
        assertTrue(stamped.withoutDualRate(2, 1).dualRates.isEmpty())
        assertEquals("flightModes", merged.mix(2, Sources.SA)?.generator)
    }

    @Test
    fun `parametric curves are shared only when x0 and y0 match`() {
        val expo = CurveConfig.parametric(3, CurveType.EXPO, 30)
        assertTrue(expo.sameShapeAs(CurveConfig.parametric(9, CurveType.EXPO, 30)))
        assertFalse(expo.sameShapeAs(CurveConfig.parametric(9, CurveType.EXPO, 31)))
        assertFalse(expo.sameShapeAs(CurveConfig.parametric(9, CurveType.DIFF, 30)))
        val otherX = expo.copy(x = List(CURVE_MAX_POINTS) { if (it == 0) 10 else 0 })
        assertFalse(expo.sameShapeAs(otherX))
        assertTrue(expo.managedFingerprint() != otherX.managedFingerprint())
    }

    @Test
    fun `blank blueprint neutralises the copied configuration`() {
        val blank = ModelBlueprint.blank(ModelType.GLIDER)
        assertTrue(blank.effectivePlan.resetConfig)
        assertTrue(blank.effectivePlan.replaceAllMixes)
        assertTrue(blank.effectivePlan.isEmpty)
        assertEquals(ManagedItems.GENERATOR_BLANK, blank.effectivePlan.generator)
        assertEquals(ModelBlueprint.BLANK_PLAN, ModelBlueprint(modelType = ModelType.PLANE).effectivePlan)
        assertTrue(WorkingConfig.EMPTY.isEmpty)
        assertEquals(MODEL_PICTURE_WIDTH_PX, (MODEL_PICTURE_HEIGHT_PX * MODEL_PICTURE_ASPECT).toInt())
    }

    @Test
    fun `extras list helpers keep lists sparse and sorted`() {
        var extras = ModelExtras()
        extras = extras.withLogicalSwitch(LogicalSwitch(5, function = LogicalFunction.OR))
        extras = extras.withLogicalSwitch(LogicalSwitch(1, function = LogicalFunction.AND))
        assertEquals(listOf(1, 5), extras.logicalSwitches.map { it.index })
        extras = extras.withLogicalSwitch(LogicalSwitch(5))
        assertEquals(listOf(1), extras.logicalSwitches.map { it.index })
        assertFalse(extras.logicalSwitch(5).isUsed)
        extras = extras.withSpecialFunction(SpecialFunction(3)).withSpecialFunction(SpecialFunction(0)).withoutSpecialFunction(3)
        assertEquals(listOf(0), extras.specialFunctions.map { it.index })
        extras = extras.withSensorOverride(Sensors.BARO_ALTITUDE, SensorOverride(name = "Height"))
        assertEquals("Height", extras.sensorOverride(Sensors.BARO_ALTITUDE)?.name)
        assertNull(extras.withSensorOverride(Sensors.BARO_ALTITUDE, null).sensorOverride(Sensors.BARO_ALTITUDE))
    }

    @Test
    fun `stick mode maps sources to pads`() {
        assertEquals(SourceId.THR, StickMode.MODE_2.leftVertical)
        assertEquals(SourceId.ELE, StickMode.MODE_2.rightVertical)
        assertEquals(SourceId.ELE, StickMode.MODE_1.leftVertical)
        assertEquals(SourceId.THR, StickMode.MODE_1.rightVertical)
        assertEquals(SourceId.RUD, StickMode.MODE_1.leftHorizontal)
        assertEquals(SourceId.AIL, StickMode.MODE_2.rightHorizontal)
        assertEquals(RfModuleMode.OFF, RfModuleMode.fromSdk(0xFF))
        assertEquals(CompareOp.DISABLED, CompareOp.fromSdk(17))
    }
}

class UnitConversionTest {

    @Test
    fun `metric passes through and imperial converts`() {
        assertEquals(DisplayValue(100.0, SensorUnit.METER, 0), UnitConversion.convert(100.0, SensorUnit.METER, UnitSystem.METRIC))
        val feet = UnitConversion.convert(100.0, SensorUnit.METER, UnitSystem.IMPERIAL, 0)
        assertEquals(SensorUnit.FEET, feet.unit)
        assertEquals(328.08, feet.value, 0.01)
        assertEquals(212.0, UnitConversion.convert(100.0, SensorUnit.CELSIUS, UnitSystem.IMPERIAL).value, 1e-9)
        assertEquals(SensorUnit.VOLT, UnitConversion.convert(7.4, SensorUnit.VOLT, UnitSystem.IMPERIAL).unit)
    }

    @Test
    fun `formatting is locale independent`() {
        assertEquals("7.40", UnitConversion.formatNumber(7.4, 2))
        assertEquals("0", UnitConversion.formatNumber(-0.2, 0))
        assertEquals("--", UnitConversion.formatNumber(Double.NaN, 1))
        assertEquals("12.4V", UnitConversion.format(SensorValue(12.42, unit = SensorUnit.VOLT, precision = 1), UnitSystem.METRIC))
        assertEquals("Thermal", UnitConversion.format(SensorValue(text = "Thermal", unit = SensorUnit.TEXT), UnitSystem.METRIC))
        assertEquals("--", UnitConversion.format(SensorValue.UNKNOWN, UnitSystem.METRIC))
        assertEquals("2:05", UnitConversion.formatTime(125))
        assertEquals("-0:07", UnitConversion.formatTime(-7))
        assertEquals("1:00:00", UnitConversion.formatTime(3600))
    }
}
