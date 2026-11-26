package org.openax12.core.model

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSerializationTest {

    private fun richExtras(): ModelExtras = ModelExtras(
        modelType = ModelType.HELICOPTER,
        pictureFile = "picture.jpg",
        notes = "Maiden done",
        checklist = listOf(ChecklistItem("Check CG", spoken = true)),
        flightModes = FlightModesConfig(
            enabled = true,
            modeSwitch = SourceId.SA,
            modes = listOf(
                FlightModeConfig("Cruise", SwitchPosition.UP),
                FlightModeConfig("Thermal", SwitchPosition.MID, offsets = mapOf(0 to 10, 4 to -5), rates = mapOf(1 to 70), transitionMs = 800),
                FlightModeConfig("Speed", SwitchPosition.DOWN, offsets = mapOf(0 to -4)),
            ),
        ),
        wing = WingConfig(
            enabled = true,
            wingType = WingType.TWO_AILERONS_TWO_FLAPS,
            tailType = TailType.V_TAIL,
            channels = WingChannels(aileronLeft = 0, aileronRight = 4, flapLeft = 5, flapRight = 6, elevator = 1, rudder = 3),
            aileronDifferential = 40,
            flaps = FlapConfig(FlapControl.OnSwitch(SourceId.SB, deflectionDown = 90, elevatorDown = -8), speedMs = 1200, aileronFollow = 30),
            butterfly = ButterflyConfig(enabled = true, elevatorCompensation = listOf(0, -3, -6, -8, -9)),
        ),
        logicalSwitches = listOf(
            LogicalSwitch(0, "Low batt", LogicalFunction.A_LESS_X, a = Sensors.BATTERY_VOLTAGE, x = 7.0, delaySec = 2.0),
            LogicalSwitch(3, function = LogicalFunction.AND, s1 = Trigger.SwitchAt(SourceId.SC, SwitchPosition.DOWN), s2 = Trigger.Logical(0), andCondition = Trigger.Link(LinkEvent.LINK_UP)),
            LogicalSwitch(31, function = LogicalFunction.TIMER, t1Sec = 1.0, t2Sec = 9.0),
        ),
        specialFunctions = listOf(
            SpecialFunction(0, trigger = Trigger.Logical(0), action = SpecialAction.PlayText("Battery {RxBt} volts", SpeechPriority.HIGH), repeat = Repeat(10)),
            SpecialFunction(1, trigger = Trigger.SwitchAt(SourceId.SD, SwitchPosition.DOWN), action = SpecialAction.Vario),
            SpecialFunction(2, trigger = Trigger.Timer(0, TimerEvent.REMAINING, 30), action = SpecialAction.PlayValue(SensorId.timer(0))),
            SpecialFunction(3, trigger = Trigger.Always, action = SpecialAction.SetVolume(SourceId.pot(1))),
            SpecialFunction(4, trigger = Trigger.FlightModeIs(1), action = SpecialAction.BackgroundMusic("thermal.mp3")),
            SpecialFunction(5, trigger = Trigger.Periodic(60), action = SpecialAction.Haptic(HapticPattern.DOUBLE)),
            SpecialFunction(6, trigger = Trigger.Input(SwitchCondition(SourceId.THR, CompareOp.GREATER, -90)), action = SpecialAction.Logging),
            SpecialFunction(7, trigger = Trigger.Telemetry(Sensors.BARO_ALTITUDE, CompareOp.GREATER, 150.0, 5.0), action = SpecialAction.ResetTimer(1)),
            SpecialFunction(8, enabled = false, trigger = Trigger.Never, action = SpecialAction.Brightness(percent = 40)),
            SpecialFunction(63, trigger = Trigger.Link(LinkEvent.TELEMETRY_LOST), action = SpecialAction.Screenshot),
        ),
        voiceAlerts = listOf(VoiceAlert("va1", "Altitude", text = "{Alt:0} metres", trigger = Trigger.SwitchAt(SourceId.SE, SwitchPosition.UP), repeat = Repeat(15))),
        vario = VarioConfig(alwaysOn = true, sensor = Sensors.VERTICAL_SPEED, centerSilent = false),
        telemetry = TelemetryLayout(
            pages = listOf(
                TelemetryPage(
                    id = "p1",
                    name = "Main",
                    widgets = listOf(
                        WidgetConfig("w1", WidgetType.BATTERY, 0, 0, 2, 2, listOf(Sensors.BATTERY_VOLTAGE), options = mapOf(WidgetOptions.CELLS to "3"), pinned = true),
                        WidgetConfig("w2", WidgetType.HORIZON, 2, 0, 2, 2, listOf(Sensors.ATTITUDE_PITCH, Sensors.ATTITUDE_ROLL)),
                    ),
                ),
            ),
        ),
        sensorOverrides = mapOf(Sensors.BATTERY_VOLTAGE.key to SensorOverride(name = "Flight pack", precision = 2)),
        alarms = listOf(SensorAlarm("a1", sensor = Sensors.BATTERY_VOLTAGE, threshold = 6.8, hysteresis = 0.2, repeatSec = 10, outputs = AlarmOutputs(voiceText = "Battery low", popup = true))),
        timerVoice = listOf(TimerVoiceOptions(0, minuteCall = true, countdown = CountdownStyle.VOICE)),
        home = HomePreferences(HomeCenterCard.WIDGETS, inputPage = 2),
        managed = ManagedItems(
            mixes = listOf(ManagedMix(0, Sources.SA, ManagedItems.GENERATOR_FLIGHT_MODES)),
            curves = listOf(ManagedCurve(17, ManagedItems.GENERATOR_FLIGHT_MODES)),
            dualRates = listOf(ManagedDualRate(1, 0, ManagedItems.GENERATOR_FLIGHT_MODES)),
        ),
        templateId = "glider-4",
    )

    @Test
    fun `default extras round-trip`() {
        val original = ModelExtras()
        val decoded = ModelExtrasCodec.decode(ModelExtrasCodec.encode(original))
        assertEquals(original, decoded.extras)
        assertFalse(decoded.migrated)
        assertFalse(decoded.newerThanApp)
        assertEquals(ModelExtras.CURRENT_SCHEMA_VERSION, decoded.fileVersion)
    }

    @Test
    fun `rich extras round-trip keeps every sealed subtype`() {
        val original = richExtras()
        val text = ModelExtrasCodec.encode(original)
        assertEquals(original, ModelExtrasCodec.decode(text).extras)
        // Stable discriminators that other tools may rely on.
        assertTrue(text.contains("\"type\": \"playText\""))
        assertTrue(text.contains("\"type\": \"switch\""))
    }

    @Test
    fun `empty object decodes to defaults and counts as migrated`() {
        val decoded = ModelExtrasCodec.decode("{}")
        assertEquals(ModelExtras(), decoded.extras)
        assertEquals(0, decoded.fileVersion)
        assertTrue(decoded.migrated)
    }

    @Test
    fun `unknown keys and unknown enum values are tolerated`() {
        val text = """
            {
              "schemaVersion": 2,
              "modelType": "ORNITHOPTER",
              "futureSection": { "a": [1, 2, 3] },
              "notes": "kept",
              "vario": { "alwaysOn": true, "someNewKnob": 5 },
              "home": { "centerCard": "HOLOGRAM", "inputPage": 3 },
              "specialFunctions": [
                { "index": 2, "trigger": { "type": "always", "extra": 1 }, "action": { "type": "vario" }, "newField": true }
              ]
            }
        """.trimIndent()
        val extras = ModelExtrasCodec.decode(text).extras
        assertEquals(ModelType.GLIDER, extras.modelType)
        assertEquals("kept", extras.notes)
        assertTrue(extras.vario.alwaysOn)
        assertNull(extras.home.centerCard)
        assertEquals(3, extras.home.inputPage)
        assertEquals(SpecialAction.Vario, extras.specialFunction(2)?.action)
        assertEquals(Trigger.Always, extras.specialFunction(2)?.trigger)
    }

    @Test
    fun `version 1 file drops the stored default of the centre card`() {
        val decoded = ModelExtrasCodec.decode(
            """{ "schemaVersion": 1, "home": { "centerCard": "PICTURE", "inputPage": 2, "channelPage": 1 } }""",
        )
        assertTrue(decoded.migrated)
        assertNull(decoded.extras.home.centerCard)
        assertEquals(2, decoded.extras.home.inputPage)
        assertEquals(1, decoded.extras.home.channelPage)
        // A choice made after the migration is kept.
        val chosen = ModelExtras(home = HomePreferences(HomeCenterCard.PICTURE))
        assertEquals(HomeCenterCard.PICTURE, ModelExtrasCodec.decode(ModelExtrasCodec.encode(chosen)).extras.home.centerCard)
    }

    @Test
    fun `centre card shows the widgets first when some are pinned`() {
        val auto = HomePreferences()
        assertEquals(HomeCenterCard.WIDGETS, auto.resolvedCenterCard(hasPinnedWidgets = true))
        assertEquals(HomeCenterCard.PICTURE, auto.resolvedCenterCard(hasPinnedWidgets = false))
        // A remembered choice wins, but without pinned widgets there is nothing to show.
        val picture = HomePreferences(centerCard = HomeCenterCard.PICTURE)
        assertEquals(HomeCenterCard.PICTURE, picture.resolvedCenterCard(hasPinnedWidgets = true))
        val widgets = HomePreferences(centerCard = HomeCenterCard.WIDGETS)
        assertEquals(HomeCenterCard.WIDGETS, widgets.resolvedCenterCard(hasPinnedWidgets = true))
        assertEquals(HomeCenterCard.PICTURE, widgets.resolvedCenterCard(hasPinnedWidgets = false))
    }

    @Test
    fun `file from a newer app version is flagged`() {
        val decoded = ModelExtrasCodec.decode("""{ "schemaVersion": 99, "notes": "from the future" }""")
        assertTrue(decoded.newerThanApp)
        assertFalse(decoded.migrated)
        assertEquals("from the future", decoded.extras.notes)
        assertEquals(ModelExtras.CURRENT_SCHEMA_VERSION, decoded.extras.schemaVersion)
    }

    @Test
    fun `invalid json yields null from decodeOrNull`() {
        assertNull(ModelExtrasCodec.decodeOrNull("not json"))
        assertNotNull(ModelExtrasCodec.decodeOrNull("{}"))
    }

    @Test
    fun `app settings round-trip with defaults and unknown keys`() {
        val original = AppSettings(theme = AppTheme.HIGH_CONTRAST, radioBackend = RadioBackend.SIMULATOR)
            .withVolume(AudioCategory.VARIO, 40)
        assertEquals(original, Ax12Json.decodeFromString<AppSettings>(Ax12Json.encodeToString(original)))
        assertEquals(AppSettings(), Ax12Json.decodeFromString<AppSettings>("""{ "unknown": 1, "theme": "NEON" }"""))
        assertEquals(40, original.volumeOf(AudioCategory.VARIO))
        assertEquals(100, original.volumeOf(AudioCategory.VOICE))
    }

    @Test
    fun `sdk mirrors round-trip`() {
        val config = WorkingConfig(
            revision = 7,
            sources = SourceId.all.map { SourceInfo(it.index, logic = it.index - 20) },
            channels = List(CHANNEL_COUNT) { OutputChannelConfig(it, lock = SwitchCondition.at(SourceId.SF, SwitchPosition.DOWN), lockValue = -100) },
            mixes = listOf(MixLine(0, 0, SourceId.AIL, "Ail", curve = 3, switch = SwitchCondition(SourceId.SA, CompareOp.EQUAL, 100), mode = MixMode.REPLACE, slowUpMs = 500)),
            curves = List(CURVE_COUNT) { if (it == 3) CurveConfig.custom(it, listOf(CurvePoint(-100, -50), CurvePoint(0, 0), CurvePoint(100, 80))) else CurveConfig.empty(it) },
            dualRates = listOf(DualRateSlot(0, 1, 1, SwitchCondition(SourceId.SB, CompareOp.LESS, 0), 60)),
            timers = List(TIMER_COUNT) { TimerConfig(it, startTime = 300, countUp = false, alertMode = TimerAlertMode.VOICE_HAPTIC) },
            counters = List(COUNTER_COUNT) { TimerConfig(it, startTime = 10) },
            system = SystemConfig(stickMode = StickMode.MODE_1, rfModuleMode = RfModuleMode.EXTERNAL, alarmVoltageDeciVolt = 68).withPreFlightPreset(SourceId.THR, -100),
        )
        assertEquals(config, Ax12Json.decodeFromString<WorkingConfig>(Ax12Json.encodeToString(config)))
    }

    @Test
    fun `telemetry hash survives as 64-bit`() {
        val hash = -0x0123456789ABCDEFL // high bit set: would be truncated or mangled as Int / Double
        val alarm = TelemetryAlarm(hash = hash, index = 2, trigger = CompareOp.LESS, triggerValue = 3.3)
        assertEquals(alarm, Ax12Json.decodeFromString<TelemetryAlarm>(Ax12Json.encodeToString(alarm)))
        assertEquals(hash, SensorId.sdk(hash).sdkHash)
    }

    @Test
    fun `mix plan and blueprint round-trip`() {
        val plan = MixPlan(
            generator = ManagedItems.GENERATOR_WING,
            mixes = listOf(
                PlannedMix(0, SourceId.AIL, curve = CurveRef.Planned("diffL")),
                PlannedMix(4, SourceId.AIL, weightUp = -100, weightDown = -100, curve = CurveRef.Existing(2)),
                PlannedMix(1, SourceId.SB, curve = CurveRef.None, slowUpMs = 1000, slowDownMs = 1000),
            ),
            curves = listOf(PlannedCurve("diffL", CurveType.DIFF, parameter = 40)),
            dualRates = listOf(PlannedDualRate(0, SwitchCondition.at(SourceId.SA, SwitchPosition.MID), 70)),
            channels = listOf(PlannedChannel(2, lock = SwitchCondition.at(SourceId.SF, SwitchPosition.UP), lockValue = -100)),
            replaceAllMixes = true,
        )
        val blueprint = ModelBlueprint("glider-4", ModelType.GLIDER, plan)
        assertEquals(blueprint, Ax12Json.decodeFromString<ModelBlueprint>(Ax12Json.encodeToString(blueprint)))
        assertEquals(ModelType.GLIDER, blueprint.extras.modelType)
    }
}
