package org.openax12.core.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.AlarmOutputs
import org.openax12.core.model.Announcements
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.CompareOp
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.LogicalFunction
import org.openax12.core.model.LogicalSwitch
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.SensorAlarm
import org.openax12.core.model.SensorId
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.SpecialAction
import org.openax12.core.model.SpecialFunction
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.Trigger
import org.openax12.core.model.VarioConfig
import org.openax12.core.model.VoiceAlert
import org.openax12.core.radio.RadioEvent
import org.openax12.core.radio.fake.FakeRadio

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeEngineTest {

    private class Rig(
        val radio: FakeRadio,
        val models: FakeModels,
        val telemetry: FakeTelemetry,
        val audio: RecordingAudio,
        val logger: FakeFlightLogger,
        val engine: DefaultRuntimeEngine,
        val requests: MutableList<RuntimeUiRequest>,
        val scope: TestScope,
    ) {
        val sim get() = radio.simulator

        /** One radio frame, then lets the engine and its workers run. */
        suspend fun frame(count: Int = 1) {
            repeat(count) {
                radio.advance(40)
                scope.runCurrent()
            }
        }

        fun extras(block: (ModelExtras) -> ModelExtras) = models.setExtras(block(models.currentExtras.value))
    }

    private val deterministic = FakeRadio.Options(demoMotion = false, realisticDelays = false, autoTick = false)

    private suspend fun TestScope.rig(startRadio: Boolean = true, start: Boolean = true): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val radio = FakeRadio(
            scope = backgroundScope,
            directory = null,
            dispatcher = dispatcher,
            clock = { testScheduler.currentTime },
            options = deterministic,
        )
        val models = FakeModels(radio)
        val telemetry = FakeTelemetry()
        val audio = RecordingAudio()
        val logger = FakeFlightLogger()
        val engine = DefaultRuntimeEngine(
            radio = radio,
            models = models,
            telemetry = telemetry,
            settings = FakeSettings(),
            audio = audio,
            flightLogger = logger,
            diagnostics = DefaultDiagnosticsLog(),
            phrases = TEST_PHRASES,
            scope = backgroundScope,
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
        )
        val requests = ArrayList<RuntimeUiRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.uiRequests.collect { requests += it } }
        if (startRadio) {
            radio.start()
            models.reconcileLibrary()
        }
        runCurrent()
        val rig = Rig(radio, models, telemetry, audio, logger, engine, requests, this)
        if (start) {
            engine.start()
            rig.frame()
        }
        return rig
    }

    private fun messages(rig: Rig, key: String) =
        rig.requests.filterIsInstance<RuntimeUiRequest.Message>().filter { it.messageKey == key }

    @Test
    fun aSpecialFunctionFiresOnceOnTheRisingEdgeOfItsTrigger() = runTest {
        val rig = rig(start = false)
        rig.extras {
            it.copy(
                specialFunctions = listOf(
                    SpecialFunction(
                        index = 0,
                        trigger = Trigger.SwitchAt(SourceId.SA, SwitchPosition.DOWN),
                        action = SpecialAction.PlayText("Gear down"),
                    ),
                ),
            )
        }
        rig.sim.setSwitch(SourceId.SA, SwitchPosition.UP)
        rig.engine.start()
        rig.frame(2)
        assertTrue(rig.audio.spokenTexts.none { it == "Gear down" })

        rig.sim.setSwitch(SourceId.SA, SwitchPosition.DOWN)
        rig.frame(3)

        assertEquals(1, rig.audio.spokenTexts.count { it == "Gear down" })
        assertEquals(setOf(0), rig.engine.activeSpecialFunctions.value)
        rig.sim.setSwitch(SourceId.SA, SwitchPosition.UP)
        rig.frame()
        assertTrue(rig.engine.activeSpecialFunctions.value.isEmpty())
    }

    @Test
    fun logicalSwitchesFollowTheirInputsAndArePublishedAsSensors() = runTest {
        val rig = rig(start = false)
        rig.extras {
            it.copy(
                logicalSwitches = listOf(
                    LogicalSwitch(
                        index = 0,
                        function = LogicalFunction.A_GREATER_X,
                        a = SensorId.input(SourceId.pot(1)),
                        x = 50.0,
                    ),
                ),
            )
        }
        rig.sim.setSource(SourceId.pot(1), 0)
        rig.engine.start()
        rig.frame(2)
        assertFalse(rig.engine.logicalSwitches.value[0])

        rig.sim.setSource(SourceId.pot(1), 80)
        rig.frame(2)
        assertTrue(rig.engine.logicalSwitches.value[0])
        assertEquals(1.0, rig.telemetry.published.last { it.first == SensorId.logicalSwitch(0) }.second.value, 0.0)

        rig.sim.setSource(SourceId.pot(1), 10)
        rig.frame(2)
        assertFalse(rig.engine.logicalSwitches.value[0])
    }

    @Test
    fun theFlightModeFollowsItsSwitchAndIsAnnounced() = runTest {
        val rig = rig(start = false)
        rig.extras {
            it.copy(
                flightModes = FlightModesConfig(
                    enabled = true,
                    modeSwitch = SourceId.SB,
                    modes = listOf(
                        FlightModeConfig(name = "Normal", position = SwitchPosition.UP),
                        FlightModeConfig(name = "Thermal", position = SwitchPosition.MID),
                        FlightModeConfig(name = "Speed", position = SwitchPosition.DOWN),
                    ),
                ),
            )
        }
        rig.sim.setSwitch(SourceId.SB, SwitchPosition.UP)
        rig.engine.start()
        rig.frame(2)
        assertEquals(ActiveFlightMode(0, "Normal"), rig.engine.flightMode.value)
        assertTrue("no announcement at start-up", rig.audio.spokenTexts.none { it == "Normal" })

        rig.sim.setSwitch(SourceId.SB, SwitchPosition.MID)
        rig.frame(2)

        assertEquals(ActiveFlightMode(1, "Thermal"), rig.engine.flightMode.value)
        assertEquals(1, rig.audio.spokenTexts.count { it == "Thermal" })
        assertEquals("Thermal", rig.telemetry.published.last { it.first == Sensors.FLIGHT_MODE }.second.text)
    }

    @Test
    fun theVarioSoundsFromTheVerticalSpeedAndStopsWithTheEngine() = runTest {
        val rig = rig(start = false)
        rig.extras { it.copy(vario = VarioConfig(alwaysOn = true)) }
        rig.telemetry.set(Sensors.VERTICAL_SPEED, 3.0)
        rig.engine.start()
        rig.frame(2)

        assertTrue(rig.engine.varioActive.value)
        assertFalse(rig.audio.varioTones.last().silent)

        rig.telemetry.set(Sensors.VERTICAL_SPEED, 0.0)
        rig.frame()
        assertTrue("inside the dead band", rig.audio.varioTones.last().silent)

        val stopsBefore = rig.audio.varioStops
        rig.engine.stop()
        assertFalse(rig.engine.varioActive.value)
        assertFalse(rig.engine.running.value)
        assertTrue(rig.audio.varioStops > stopsBefore)
    }

    @Test
    fun anAlarmIsRaisedSpokenAcknowledgedAndRaisedAgainAfterItCleared() = runTest {
        val rig = rig(start = false)
        val alarm = SensorAlarm(
            id = "low",
            name = "Low battery",
            sensor = Sensors.TX_BATTERY,
            op = CompareOp.LESS,
            threshold = 7.0,
            hysteresis = 0.2,
            onlyWhenLinked = false,
            outputs = AlarmOutputs(voiceText = "Battery low", beep = BeepPattern.WARNING, popup = true),
        )
        rig.extras { it.copy(alarms = listOf(alarm)) }
        rig.sim.setTxVoltage(8.0f)
        rig.engine.start()
        rig.frame(2)
        assertTrue(rig.engine.activeAlarms.value.isEmpty())

        rig.sim.setTxVoltage(6.0f)
        rig.frame(2)

        assertEquals(listOf("low"), rig.engine.activeAlarms.value.map { it.alarm.id })
        assertTrue(rig.audio.spoken.any { it.text == "Battery low" && it.priority == SpeechPriority.ALARM })
        assertTrue(rig.audio.beeps.contains(BeepPattern.WARNING))
        assertTrue(rig.requests.any { it is RuntimeUiRequest.AlarmPopup && it.text == "Battery low" })

        rig.engine.acknowledgeAlarms()
        assertTrue(rig.engine.activeAlarms.value.isEmpty())

        rig.sim.setTxVoltage(8.0f)
        rig.frame(2)
        rig.sim.setTxVoltage(6.0f)
        rig.frame(2)
        assertEquals(1, rig.engine.activeAlarms.value.size)
    }

    @Test
    fun radioEventsAreAnnouncedAndReportedToTheUi() = runTest {
        val rig = rig(start = false)
        rig.extras { it.copy(announcements = Announcements(telemetryLost = true, telemetryRecovered = false)) }
        rig.engine.start()
        rig.frame()
        // The simulator itself reports the link coming up at start-up.
        val recoveredBefore = messages(rig, RuntimeMessages.TELEMETRY_RECOVERED).size

        rig.sim.emitEvent(RadioEvent.TelemetryLost)
        rig.sim.emitEvent(RadioEvent.TelemetryRecovered)
        runCurrent()
        rig.frame()

        assertTrue(rig.audio.spokenTexts.contains("Telemetry lost"))
        assertFalse(rig.audio.spokenTexts.contains("Telemetry recovered"))
        assertEquals(1, messages(rig, RuntimeMessages.TELEMETRY_LOST).size)
        assertEquals(recoveredBefore + 1, messages(rig, RuntimeMessages.TELEMETRY_RECOVERED).size)
    }

    @Test
    fun theTrimBeepDependsOnWhereTheTrimWent() = runTest {
        val rig = rig()
        rig.sim.setTrim(SourceId.AIL, 10)
        rig.sim.emitEvent(RadioEvent.TrimChanged(1))
        runCurrent()
        rig.frame(2)
        assertTrue(rig.audio.beeps.contains(BeepPattern.TRIM_STEP))

        rig.sim.setTrim(SourceId.AIL, 0)
        rig.sim.emitEvent(RadioEvent.TrimChanged(2))
        runCurrent()
        rig.frame(2)
        assertTrue(rig.audio.beeps.contains(BeepPattern.TRIM_CENTER))
    }

    @Test
    fun thePreFlightCheckIsRetainedForLateCollectorsAndDismissed() = runTest {
        // The engine exists, the radio does not run yet: events have no replay.
        val rig = rig(startRadio = false, start = false)
        rig.radio.start()
        runCurrent()

        rig.sim.emitEvent(RadioEvent.PreFlightCheck(listOf(0, 1, 0)))
        runCurrent()

        assertEquals(listOf(0, 1, 0), rig.engine.preFlightCheck.value)
        assertEquals(listOf(0, 1, 0), rig.requests.filterIsInstance<RuntimeUiRequest.PreFlightCheck>().single().states)

        rig.engine.skipPreFlightCheck()
        assertNull(rig.engine.preFlightCheck.value)

        rig.sim.emitEvent(RadioEvent.PreFlightCheck(listOf(0, 0, 0)))
        runCurrent()
        assertNull("a passing check is not an alarm", rig.engine.preFlightCheck.value)
    }

    @Test
    fun loggingFollowsTheFunctionAndReportsToTheUi() = runTest {
        val rig = rig(start = false)
        rig.extras {
            it.copy(
                specialFunctions = listOf(
                    SpecialFunction(
                        index = 0,
                        trigger = Trigger.SwitchAt(SourceId.SA, SwitchPosition.DOWN),
                        action = SpecialAction.Logging,
                    ),
                ),
            )
        }
        rig.sim.setSwitch(SourceId.SA, SwitchPosition.UP)
        rig.engine.start()
        rig.frame(2)
        assertEquals(0, rig.logger.starts)

        rig.sim.setSwitch(SourceId.SA, SwitchPosition.DOWN)
        rig.frame(2)
        assertEquals(1, rig.logger.starts)
        assertEquals(1, messages(rig, RuntimeMessages.LOGGING_STARTED).size)

        rig.sim.setSwitch(SourceId.SA, SwitchPosition.UP)
        rig.frame(2)
        assertEquals(1, rig.logger.stops)
        assertEquals(1, messages(rig, RuntimeMessages.LOGGING_STOPPED).size)
    }

    @Test
    fun aMissingSoundFileIsReportedOnce() = runTest {
        val rig = rig(start = false)
        rig.audio.missingSounds = setOf("gone.wav")
        rig.extras {
            it.copy(
                specialFunctions = listOf(
                    SpecialFunction(
                        index = 0,
                        trigger = Trigger.Always,
                        action = SpecialAction.PlaySound("gone.wav"),
                        repeat = org.openax12.core.model.Repeat(intervalSec = 1, skipAtStartup = false),
                    ),
                ),
            )
        }
        rig.engine.start()
        rig.frame(60)

        assertEquals(1, messages(rig, RuntimeMessages.SOUND_FILE_MISSING).size)
    }

    @Test
    fun resetFlightResetsTelemetryAndTellsTheUi() = runTest {
        val rig = rig()

        rig.engine.resetFlight()

        assertTrue(rig.telemetry.calls.contains("resetFlight"))
        assertEquals(1, messages(rig, RuntimeMessages.FLIGHT_RESET).size)
    }

    @Test
    fun theResolverAndThePreviewsUseTheLatestTick() = runTest {
        val rig = rig()
        rig.telemetry.set(Sensors.BATTERY_VOLTAGE, 7.4)
        rig.frame(2)

        assertEquals("Plane", rig.engine.valueResolver().resolve("model")?.text)

        rig.engine.previewText("Battery {RxBt} volts, model {model}")
        runCurrent()
        assertTrue(rig.audio.spokenTexts.toString(), rig.audio.spokenTexts.contains("Battery 7.4 volts, model Plane"))

        rig.engine.preview(VoiceAlert(id = "v", text = "Hello {model}", priority = SpeechPriority.HIGH))
        runCurrent()
        assertTrue(rig.audio.spoken.any { it.text == "Hello Plane" && it.priority == SpeechPriority.HIGH })

        rig.engine.preview(SpecialFunction(index = 0, action = SpecialAction.Beep(BeepPattern.DOUBLE)))
        runCurrent()
        assertTrue(rig.audio.beeps.contains(BeepPattern.DOUBLE))
    }

    @Test
    fun aContinuousFunctionPreviewEndsAfterThreeSeconds() = runTest {
        val rig = rig()

        rig.engine.preview(SpecialFunction(index = 0, action = SpecialAction.BackgroundMusic("song.mp3")))
        runCurrent()
        assertEquals(listOf<String?>("song.mp3"), rig.audio.music.toList())

        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(listOf("song.mp3", null), rig.audio.music.toList())
    }

    @Test
    fun stopSilencesAndAStoppedEngineIgnoresFrames() = runTest {
        val rig = rig(start = false)
        rig.extras { it.copy(specialFunctions = listOf(SpecialFunction(0, trigger = Trigger.Always, action = SpecialAction.PlayText("tick")))) }
        rig.engine.start()
        rig.frame(2)
        val spokenWhileRunning = rig.audio.spoken.size

        rig.engine.stop()
        rig.frame(5)

        assertEquals(spokenWhileRunning, rig.audio.spoken.size)
        assertFalse(rig.engine.running.value)
        assertTrue(rig.audio.overrideVolume == null)
    }
}
