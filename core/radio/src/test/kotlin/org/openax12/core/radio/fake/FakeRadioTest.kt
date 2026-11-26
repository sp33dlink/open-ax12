package org.openax12.core.radio.fake

import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurvePoint
import org.openax12.core.model.CurveType
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.ElrsItemType
import org.openax12.core.model.FailsafeMode
import org.openax12.core.model.MixMode
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.RfModuleMode
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.Sources
import org.openax12.core.model.StickMode
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TelemetryAlarm
import org.openax12.core.model.TimerAlertMode
import org.openax12.core.radio.ConnectionError
import org.openax12.core.radio.ConnectionState
import org.openax12.core.radio.CrsfFrame
import org.openax12.core.radio.RadioEvent
import org.openax12.core.radio.SimulatedFault
import org.openax12.core.radio.TaskResult
import org.openax12.core.radio.addTelemetryAlarm
import org.openax12.core.radio.drainTelemetryNotifications
import org.openax12.core.radio.findDisabledDualRateSlot
import org.openax12.core.radio.findMix
import org.openax12.core.radio.getCurrentModel
import org.openax12.core.radio.getMixes
import org.openax12.core.radio.readWorkingConfig

@OptIn(ExperimentalCoroutinesApi::class)
class FakeRadioTest {

    @get:Rule
    val temporary = TemporaryFolder()

    /** No delays, no clock-driven frames, no demo: nothing happens unless the test asks for it. */
    private val deterministic = FakeRadio.Options(demoMotion = false, realisticDelays = false, autoTick = false)

    private fun TestScope.radio(directory: File? = null, options: FakeRadio.Options = deterministic): FakeRadio =
        FakeRadio(
            scope = backgroundScope,
            directory = directory,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
            options = options,
        )

    private fun TestScope.eventsOf(radio: FakeRadio): MutableList<RadioEvent> {
        val events = ArrayList<RadioEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { radio.events.collect { events += it } }
        return events
    }

    private fun List<RadioEvent>.frames(type: Int): List<CrsfFrame> =
        filterIsInstance<RadioEvent.CrsfReceived>().map { it.frame }.filter { it.type == type }

    private fun CrsfFrame.u16(offset: Int): Int = (payload(offset) shl 8) or payload(offset + 1)

    // ---- lifecycle ---------------------------------------------------------------------------

    @Test
    fun aStoppedRadioAnswersWithEmptyResults() = runTest {
        val radio = radio()
        assertEquals(ConnectionState.Stopped, radio.connection.value)
        assertTrue(radio.isSimulator)
        assertEquals("simulator", radio.sdkVersion)
        assertTrue(radio.getSources().isEmpty())
        assertTrue(radio.getOutputChannels().isEmpty())
        assertTrue(radio.getCurves().isEmpty())
        assertTrue(radio.getModels().isEmpty())
        assertNull(radio.getMix(0, 0))
        assertNull(radio.getSystemConfig())
        assertNull(radio.getCurrentModelIndex())
        assertNull(radio.addMix(0, SourceId.AIL))
        assertFalse(radio.setCurve(CurveConfig.parametric(0, CurveType.EXPO, 20)))
        assertFalse(radio.resetTimer(0))
        assertFalse(radio.loadModelFile("/simulator/models/model_1.json"))
        assertEquals(TaskResult.NOT_READY, radio.saveConfigToDevice())
        assertEquals(TaskResult.NOT_READY, radio.readConfigFromDevice())
        assertEquals(42.0, radio.applyCurve(0, 42.0), 0.0)
        assertTrue(radio.readWorkingConfig().isEmpty)
        assertEquals(0L, radio.configRevision.value)
    }

    @Test
    fun firstStartCreatesTheDefaultPlane() = runTest {
        val radio = radio()
        val events = eventsOf(radio)
        assertEquals(ConnectionState.Ready, radio.start())
        assertEquals(ConnectionState.Ready, radio.start())
        assertTrue(radio.configRevision.value >= 1)
        assertTrue(RadioEvent.ConfigRead in events)
        assertTrue(RadioEvent.FirmwareInfoUpdated in events)

        val models = radio.getModels()
        assertEquals(listOf("Plane"), models.map { it.name })
        assertEquals(models.single(), radio.getCurrentModel())
        assertEquals(setOf("Plane", "Glider", "Multirotor", "Helicopter"), radio.getTemplates().map { it.name }.toSet())

        val config = radio.readWorkingConfig()
        assertEquals(42, config.sources.size)
        assertEquals(16, config.channels.size)
        assertEquals(18, config.curves.size)
        assertEquals(48, config.dualRates.size)
        assertEquals(3, config.timers.size)
        assertEquals(3, config.counters.size)
        assertEquals(SourceId.AIL, config.mixesOf(0).single().source)
        assertEquals(SourceId.ELE, config.mixesOf(1).single().source)
        assertEquals(SourceId.THR, config.mixesOf(2).single().source)
        assertEquals(SourceId.RUD, config.mixesOf(3).single().source)
        assertEquals("Ail", radio.getMix(0, 0)!!.sourceName)
        assertEquals(1, radio.getMix(0, 0)!!.mixCount)
        assertEquals(1, radio.getOutputChannels()[0].mixCount)
        assertEquals(StickMode.MODE_2, config.system!!.stickMode)

        val firmware = radio.getFirmwareInfo()!!
        assertFalse(firmware.isEmpty)
        assertEquals("01.02.0003", firmware.firmwareVersionText)

        assertTrue(radio.live.value.isEmpty)
        radio.advance(40)
        val live = radio.live.value
        assertEquals(1L, live.sequence)
        assertEquals(42, live.sourceCount)
        assertEquals(16, live.channelCount)
        assertEquals(-100, live.logic(SourceId.THR))
        assertEquals(-512, live.raw(SourceId.THR))
        assertEquals(-100, live.channel(2))
        assertTrue(live.status.isLinkUp)
        assertTrue(live.status.txVoltage > 7f)

        radio.stop()
        assertEquals(ConnectionState.Stopped, radio.connection.value)
        assertTrue(radio.getSources().isEmpty())
    }

    @Test
    fun realisticStartSequenceAndFramePacing() = runTest {
        val radio = radio(options = FakeRadio.Options(demoMotion = false))
        val states = ArrayList<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { radio.connection.collect { states += it } }

        assertEquals(ConnectionState.Ready, radio.start())
        assertEquals(FakeRadio.INIT_DELAY_MS + FakeRadio.DEVICE_READ_DELAY_MS, testScheduler.currentTime)
        assertEquals(
            listOf(ConnectionState.Stopped, ConnectionState.Initializing, ConnectionState.WaitingForDevice, ConnectionState.Ready),
            states,
        )

        // 25 frames per second.
        val before = radio.live.value.sequence
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(25L, radio.live.value.sequence - before)
        // The newest frame is at most one frame period old.
        assertTrue(testScheduler.currentTime - radio.live.value.timestampMs in 0 until 40)

        // A save takes a moment; a second one meanwhile is refused.
        val started = testScheduler.currentTime
        val first = async { radio.saveConfigToDevice() }
        runCurrent()
        assertEquals(TaskResult.BUSY, radio.saveConfigToDevice())
        assertEquals(TaskResult.COMPLETED, first.await())
        assertEquals(FakeRadio.DEVICE_WRITE_DELAY_MS, testScheduler.currentTime - started)

        // A timeout shorter than the task: the caller gives up, the task still finishes.
        val events = eventsOf(radio)
        assertEquals(TaskResult.TIMED_OUT, radio.saveConfigToDevice(100.milliseconds))
        assertFalse(RadioEvent.ConfigWritten in events)
        advanceTimeBy(FakeRadio.DEVICE_WRITE_DELAY_MS)
        runCurrent()
        assertTrue(RadioEvent.ConfigWritten in events)

        radio.stop()
        val stopped = radio.live.value.sequence
        advanceTimeBy(1000)
        assertEquals(stopped, radio.live.value.sequence)
    }

    @Test
    fun startFaultsAndConnectionLoss() = runTest {
        val radio = radio()
        val sim = radio.simulator

        sim.injectFault(SimulatedFault.SERIAL_PORT_BUSY)
        val busy = radio.start() as ConnectionState.Error
        assertTrue(busy.reason is ConnectionError.SerialPortBusy)
        assertTrue(busy.reason.mayBeStockAppConflict)
        assertTrue(radio.getSources().isEmpty())

        sim.injectFault(SimulatedFault.DEVICE_TIMEOUT)
        val timeout = radio.start() as ConnectionState.Error
        assertTrue(timeout.reason is ConnectionError.DeviceTimeout)

        // Faults are one-shot: the retry connects.
        assertEquals(ConnectionState.Ready, radio.start())

        // An unsaved change is lost when the connection breaks and the device is read again.
        val channel = radio.getOutputChannels()[5]
        assertTrue(radio.setOutputChannel(channel.copy(middle = 33)))
        sim.injectFault(SimulatedFault.CONNECTION_LOST)
        radio.advance(40)
        val lost = radio.connection.value as ConnectionState.Error
        assertTrue(lost.reason is ConnectionError.ConnectionLost)
        assertTrue(radio.getOutputChannels().isEmpty())
        assertEquals(TaskResult.NOT_READY, radio.saveConfigToDevice())

        assertEquals(ConnectionState.Ready, radio.start())
        assertEquals(0, radio.getOutputChannels()[5].middle)
    }

    // ---- mixer through the radio -----------------------------------------------------------

    @Test
    fun channelsFollowTheInputsThroughTheConfiguredMixes() = runTest {
        val radio = radio()
        val sim = radio.simulator
        radio.start()

        sim.setStick(SourceId.AIL, 50)
        sim.setStick(SourceId.THR, 20)
        radio.advance(40)
        // Aileron goes through the 30 % expo of the default plane: 38.75.
        assertEquals(39, radio.live.value.channel(0))
        assertEquals(38.75f, radio.live.value.channelLogic(0), 1e-4f)
        assertEquals(20, radio.live.value.channel(2))
        assertEquals(50, sim.sourceValue(SourceId.AIL))
        assertEquals(39, radio.getOutputChannels()[0].output)
        assertEquals(50, radio.getSources()[Sources.AIL].logic)

        // Low rates on SB down (70 %), throttle cut on SF down, CH5 follows the 3-position switch SA.
        sim.setSwitch(SourceId.SB, SwitchPosition.DOWN)
        sim.setSwitch(SourceId.SF, SwitchPosition.DOWN)
        sim.setSwitch(SourceId.SA, SwitchPosition.MID)
        radio.advance(40)
        assertEquals(27, radio.live.value.channel(0))
        assertEquals(-100, radio.live.value.channel(2))
        assertEquals(0, radio.live.value.channel(4))
        assertEquals(SwitchPosition.DOWN, radio.live.value.switchPosition(SourceId.SB))

        // A 2-position switch has no middle; a stick is not a switch; values are coerced.
        sim.setSwitch(SourceId.SF, SwitchPosition.MID)
        sim.setSwitch(SourceId.AIL, SwitchPosition.DOWN)
        sim.setStick(SourceId.ELE, 400)
        sim.setPot(SourceId.slider(1), -30)
        sim.setSource(SourceId.SA, 80)
        assertEquals(SwitchPosition.UP.logic, sim.sourceValue(SourceId.SF))
        assertEquals(50, sim.sourceValue(SourceId.AIL))
        assertEquals(100, sim.sourceValue(SourceId.ELE))
        assertEquals(SwitchPosition.UP.logic, sim.sourceValue(SourceId.SA))
        radio.advance(40)
        assertEquals(-30, radio.live.value.channel(5))

        // Setters are previewed live without saving: reverse the rudder channel.
        sim.setStick(SourceId.RUD, 40)
        val rudder = radio.getOutputChannels()[3]
        assertTrue(radio.setOutputChannel(rudder.copy(weight = -100, middle = 5)))
        radio.advance(40)
        assertEquals(-35, radio.live.value.channel(3))
    }

    @Test
    fun slowMixRampsOverTime() = runTest {
        val radio = radio()
        val sim = radio.simulator
        radio.start()
        val line = radio.addMix(8, SourceId.SA)!!
        assertTrue(radio.setMix(line.copy(slowUpMs = 2000, slowDownMs = 2000)))
        radio.advance(40)
        // SA rests up (+100); moving it down (-100) ramps the channel over the slow time.
        assertEquals(100, radio.live.value.channel(8))

        sim.setSwitch(SourceId.SA, SwitchPosition.DOWN)
        radio.advance(1000)
        assertEquals(0, radio.live.value.channel(8))
        radio.advance(1000)
        assertEquals(-100, radio.live.value.channel(8))
    }

    // ---- setters ---------------------------------------------------------------------------

    @Test
    fun mixesAreAddedOncePerSourceAndDeletedBySource() = runTest {
        val radio = radio()
        radio.start()
        val revision = radio.configRevision.value

        val first = radio.addMix(6, SourceId.AIL)!!
        assertEquals(0, first.mixIndex)
        assertEquals(1, first.mixCount)
        assertEquals(MixMode.ADD, first.mode)
        assertEquals(100, first.weightUp)
        assertEquals(revision + 1, radio.configRevision.value)
        assertNull(radio.addMix(6, SourceId.AIL))
        val second = radio.addMix(6, SourceId.ELE)!!
        assertEquals(1, second.mixIndex)
        assertEquals(2, second.mixCount)

        // Read-only fields of the argument are ignored; the writable ones are stored.
        assertTrue(
            radio.setMix(
                second.copy(
                    weightUp = 60,
                    weightDown = 40,
                    offset = -5,
                    mode = MixMode.MULTIPLY,
                    curve = 3,
                    switch = SwitchCondition.at(SourceId.SC, SwitchPosition.UP),
                    sourceName = "bogus",
                    sourceType = 99,
                    mixCount = 77,
                ),
            ),
        )
        val stored = radio.getMix(6, 1)!!
        assertEquals(60, stored.weightUp)
        assertEquals(40, stored.weightDown)
        assertEquals(-5, stored.offset)
        assertEquals(MixMode.MULTIPLY, stored.mode)
        assertEquals(3, stored.curve)
        assertEquals("Ele", stored.sourceName)
        assertEquals(2, stored.mixCount)

        // Moving a line onto a source that already has a line is refused; invalid references too.
        assertFalse(radio.setMix(stored.copy(source = SourceId.AIL)))
        assertFalse(radio.setMix(stored.copy(curve = 18)))
        assertFalse(radio.setMix(stored.copy(source = SourceId(42))))
        assertFalse(radio.setMix(stored.copy(mixIndex = 2)))
        assertTrue(radio.setMix(stored.copy(source = SourceId.RUD)))
        assertEquals(SourceId.RUD, radio.findMix(6, SourceId.RUD)!!.source)

        // Deleting is keyed by the source and closes the gap.
        assertEquals(1, radio.deleteMix(6, SourceId.AIL))
        assertNull(radio.deleteMix(6, SourceId.AIL))
        val left = radio.getMixes(6).single()
        assertEquals(SourceId.RUD, left.source)
        assertEquals(0, left.mixIndex)
        assertEquals(0, radio.deleteMix(6, SourceId.RUD))

        assertNull(radio.addMix(16, SourceId.AIL))
        assertNull(radio.addMix(0, SourceId(42)))
        assertNull(radio.getMix(0, 1))
        assertNull(radio.getMix(-1, 0))
    }

    @Test
    fun aChannelTakesALimitedNumberOfMixesAndTheFaultMakesAnAddFail() = runTest {
        val radio = radio()
        radio.start()
        radio.simulator.injectFault(SimulatedFault.ADD_MIX_FAILS)
        assertNull(radio.addMix(9, SourceId.AIL))
        assertNotNull(radio.addMix(9, SourceId.AIL))
        // A source can only have one line per channel; AIL is taken already.
        val others = (0 until 42).filter { it != SourceId.AIL.index }
        for (source in others.take(SimDefaults.MAX_MIXES_PER_CHANNEL - 1)) assertNotNull(radio.addMix(9, SourceId(source)))
        assertNull(radio.addMix(9, SourceId(others[SimDefaults.MAX_MIXES_PER_CHANNEL - 1])))
    }

    @Test
    fun deletingADualRateDoesNothing() = runTest {
        val radio = radio()
        radio.start()
        val down = SwitchCondition.at(SourceId.SA, SwitchPosition.DOWN)

        assertEquals(0, radio.findFreeDualRateSlot(2)!!.slot)
        assertEquals(1, radio.setDualRate(DualRateSlot(channel = 2, slot = 1, switch = down, weight = 60, count = 99)))
        val revision = radio.configRevision.value

        // The SDK quirk: rcDelDrData returns success and leaves the slot as it is.
        assertTrue(radio.deleteDualRate(DualRateSlot(channel = 2, slot = 1)))
        val slot = radio.getDualRate(2, 1)!!
        assertTrue(slot.isEnabled)
        assertEquals(60, slot.weight)
        assertEquals(down, slot.switch)
        assertEquals(1, slot.count)
        assertEquals(revision, radio.configRevision.value)
        assertEquals(60, radio.getOutputChannels()[2].dualRates[1].weight)
        assertEquals(Sources.SA, radio.getOutputChannels()[2].dualRates[1].switchSource)

        // It still scales the channel.
        radio.simulator.setStick(SourceId.THR, 50)
        radio.simulator.setSwitch(SourceId.SA, SwitchPosition.DOWN)
        radio.advance(40)
        assertEquals(30, radio.live.value.channel(2))

        // "Deleting" = writing the slot back disabled.
        assertEquals(0, radio.findDisabledDualRateSlot(2)!!.slot)
        assertEquals(0, radio.setDualRate(slot.copy(switch = SwitchCondition.NONE)))
        assertFalse(radio.getDualRate(2, 1)!!.isEnabled)
        radio.advance(40)
        assertEquals(50, radio.live.value.channel(2))

        assertNull(radio.getDualRate(2, 3))
        assertNull(radio.getDualRate(16, 0))
        assertNull(radio.setDualRate(DualRateSlot(channel = 2, slot = 3)))
        assertFalse(radio.deleteDualRate(DualRateSlot(channel = 16, slot = 0)))
    }

    @Test
    fun curvesAreValidatedAndEvaluated() = runTest {
        val radio = radio()
        radio.start()
        val custom = CurveConfig.custom(5, listOf(CurvePoint(-100, -100), CurvePoint(0, 20), CurvePoint(100, 60)))
        assertTrue(radio.setCurve(custom))
        assertEquals(custom, radio.getCurve(5))
        assertEquals(40.0, radio.applyCurve(5, 50.0), 1e-9)
        assertEquals(50.0, radio.applyCurve(NO_CURVE, 50.0), 0.0)
        assertEquals(50.0, radio.applyCurve(18, 50.0), 0.0)

        assertFalse(radio.setCurve(custom.copy(index = 18)))
        assertFalse(radio.setCurve(CurveConfig.custom(5, listOf(CurvePoint(10, 0), CurvePoint(10, 50)))))
        assertFalse(radio.setCurve(CurveConfig(index = 5, type = CurveType.CUSTOM, pointCount = 13)))
        assertEquals(custom, radio.getCurve(5))
        assertNull(radio.getCurve(18))

        // Short coordinate lists are stored padded to the fixed array size.
        assertTrue(radio.setCurve(CurveConfig(index = 6, type = CurveType.EXPO, x = emptyList(), y = listOf(40))))
        assertEquals(12, radio.getCurve(6)!!.x.size)
        assertEquals(40, radio.getCurve(6)!!.parameter)
    }

    @Test
    fun outputChannelSetterOverlaysOnlyWritableFields() = runTest {
        val radio = radio()
        radio.start()
        val channel = radio.getOutputChannels()[0]
        assertTrue(
            radio.setOutputChannel(
                channel.copy(middle = 150, min = -70, max = 80, mixCount = 9, output = 55, dualRates = emptyList(), lockValue = 10),
            ),
        )
        val stored = radio.getOutputChannels()[0]
        assertEquals(100, stored.middle)
        assertEquals(-70, stored.min)
        assertEquals(80, stored.max)
        assertEquals(10, stored.lockValue)
        assertEquals(1, stored.mixCount)
        assertEquals(3, stored.dualRates.size)
        assertEquals(70, stored.dualRates[0].weight)

        assertFalse(radio.setOutputChannel(channel.copy(channel = 16)))
        assertFalse(radio.setOutputChannel(channel.copy(curve = 40)))
        assertFalse(radio.setOutputChannel(channel.copy(lock = SwitchCondition(SourceId(60), CompareOp.EQUAL, 0))))
    }

    @Test
    fun systemConfigKeepsOnlyTheFieldsTheSdkMaps() = runTest {
        val radio = radio()
        val events = eventsOf(radio)
        radio.start()
        radio.advance(40)
        val system = radio.getSystemConfig()!!
        assertEquals(-100, system.preFlightPreset(SourceId.THR))

        assertTrue(
            radio.setSystemConfig(
                system.copy(
                    stickMode = StickMode.MODE_1,
                    alarmVoltageDeciVolt = 70,
                    failsafeMode = FailsafeMode.HOLD,
                    stickBeep = 1,
                    crsfBaudRate = 2,
                ).withPreFlightPreset(SourceId.SA, -100),
            ),
        )
        val stored = radio.getSystemConfig()!!
        assertEquals(StickMode.MODE_1, stored.stickMode)
        assertEquals(70, stored.alarmVoltageDeciVolt)
        assertEquals(-100, stored.preFlightPreset(SourceId.SA))
        // Failsafe and the other fields are not persisted by the SDK.
        assertEquals(FailsafeMode.NONE, stored.failsafeMode)
        assertEquals(0, stored.stickBeep)
        assertEquals(0, stored.crsfBaudRate)

        // Switching the RF module off takes the link down.
        assertTrue(radio.setSystemConfig(stored.copy(rfModuleMode = RfModuleMode.OFF)))
        radio.advance(40)
        assertTrue(RadioEvent.TelemetryLost in events)
        assertFalse(radio.live.value.status.isLinkUp)
        assertFalse(radio.simulator.linkUp.value)
    }

    // ---- model files -------------------------------------------------------------------------

    @Test
    fun modelFileRoundTrip() = runTest {
        val directory = temporary.newFolder("sim")
        val custom = CurveConfig.custom(5, listOf(CurvePoint(-100, -80), CurvePoint(0, 10), CurvePoint(100, 90)))

        val radio = radio(directory)
        radio.start()
        assertTrue(File(directory, "simulator.json").isFile)
        assertTrue(File(directory, "models/model_1.json").isFile)
        assertEquals(4, File(directory, "templates").listFiles()!!.size)

        // A new model is metadata only until the working config is saved into it.
        val created = radio.createModelInfo()!!
        assertFalse(File(created.filePath).exists())
        assertEquals(1, radio.getModels().size)
        assertNull(radio.updateModelInfo(created.copy(name = "Nothing there yet")))
        assertFalse(radio.loadModelFile(created.filePath))

        assertTrue(radio.setCurve(custom))
        assertNotNull(radio.addMix(7, SourceId.RUD))
        val glider = created.copy(name = "Glider X", modelMatch = 7, image = "glider.png")
        assertTrue(radio.saveWorkingConfigToModel(glider))
        assertTrue(File(created.filePath).isFile)
        assertEquals(listOf("Plane", "Glider X"), radio.getModels().map { it.name })
        assertEquals(glider, radio.getModels()[1])

        // Saving a copy does not change the current model.
        assertEquals("Plane", radio.getCurrentModel()!!.name)

        // Export, then make the new model current and write it to the device.
        val exported = File(directory, "out/glider-export.json")
        assertTrue(radio.exportModel(created.filePath, exported.path))
        assertTrue(exported.isFile)
        assertTrue(exported.readText().contains(SimModelFile.FORMAT))
        val revision = radio.configRevision.value
        assertTrue(radio.loadModelFile(created.filePath))
        assertEquals(revision + 1, radio.configRevision.value)
        assertEquals(created.index.toInt(), radio.getCurrentModelIndex())
        assertEquals("Glider X", radio.getCurrentModel()!!.name)
        assertEquals(TaskResult.COMPLETED, radio.saveConfigToDevice())

        // Metadata edits, templates.
        val renamed = radio.updateModelInfo(glider.copy(name = "Glider Y"))!!
        assertEquals("Glider Y", renamed.name)
        assertEquals(created.index, renamed.index)
        assertTrue(radio.saveModelAsTemplate(created.filePath, "My glider"))
        assertTrue("My glider" in radio.getTemplates().map { it.name })
        radio.stop()

        // A new simulator on the same directory finds everything again.
        val second = radio(directory)
        assertEquals(ConnectionState.Ready, second.start())
        assertEquals(listOf("Plane", "Glider Y"), second.getModels().map { it.name })
        val current = second.getCurrentModel()!!
        assertEquals("Glider Y", current.name)
        assertEquals(7L, current.modelMatch)
        assertEquals("glider.png", current.image)
        assertEquals(custom, second.getCurve(5))
        assertEquals(SourceId.RUD, second.getMix(7, 0)!!.source)
        assertEquals(5, second.getTemplates().size)

        // Import the export as a third model; the plane was never touched.
        val target = second.createModelInfo()!!
        assertEquals(3L, target.index)
        assertTrue(second.importModel(exported.path, target.filePath))
        val imported = second.getModels().single { it.filePath == target.filePath }
        assertEquals("Glider X", imported.name)
        assertEquals(3L, imported.index)
        assertTrue(second.loadModelFile(target.filePath))
        assertEquals(custom, second.getCurve(5))
        assertTrue(second.loadModelFile(second.getModels()[0].filePath))
        assertEquals(CurveType.EXPO, second.getCurve(0)!!.type)
        assertEquals(CurveType.NONE, second.getCurve(5)!!.type)
        assertNull(second.getMix(7, 0))

        // A template can be imported into a new model as well.
        val fromTemplate = second.createModelInfo()!!
        val template = second.getTemplates().single { it.name == "Multirotor" }
        assertTrue(second.importModel(template.filePath, fromTemplate.filePath))
        assertEquals("Multirotor", second.getModels().single { it.filePath == fromTemplate.filePath }.name)

        // Deleting: any model but the current one.
        assertFalse(second.deleteModel(second.getCurrentModel()!!.filePath))
        assertTrue(second.deleteModel(target.filePath))
        assertFalse(File(target.filePath).exists())
        assertFalse(second.deleteModel(target.filePath))
        assertEquals(3, second.getModels().size)

        // Things that are not simulator models, or paths outside the model directory, are refused.
        val garbage = File(directory, "garbage.json").apply { writeText("{\"hello\": 1}") }
        val another = second.createModelInfo()!!
        assertFalse(second.importModel(garbage.path, another.filePath))
        assertFalse(second.importModel(File(directory, "missing.json").path, another.filePath))
        assertFalse(second.importModel(exported.path, File(directory, "elsewhere.json").path))
        assertFalse(second.saveWorkingConfigToModel(another.copy(filePath = File(directory, "models/../escape.json").path)))
        assertFalse(second.exportModel(File(directory, "models/none.json").path, exported.path))
    }

    @Test
    fun modelsWorkWithoutADirectory() = runTest {
        val radio = radio()
        radio.start()
        val created = radio.createModelInfo()!!
        assertTrue(radio.saveWorkingConfigToModel(created.copy(name = "Second")))
        assertTrue(radio.loadModelFile(created.filePath))
        assertEquals("Second", radio.getCurrentModel()!!.name)
        assertFalse(File(created.filePath).exists())

        val exported = File(temporary.root, "memory-export.json")
        assertTrue(radio.exportModel(created.filePath, exported.path))
        val third = radio.createModelInfo()!!
        assertTrue(radio.importModel(exported.path, third.filePath))
        assertEquals(3, radio.getModels().size)
    }

    @Test
    fun aDeviceReadOverwritesTheWorkingConfigAndTheCurrentModelFile() = runTest {
        val radio = radio()
        val events = eventsOf(radio)
        radio.start()

        // Model B = the plane with a subtrim on CH9, stored as a file only.
        val channel = radio.getOutputChannels()[8]
        assertTrue(radio.setOutputChannel(channel.copy(middle = 25)))
        val b = radio.createModelInfo()!!.copy(name = "B")
        assertTrue(radio.saveWorkingConfigToModel(b))

        // Reading the device drops the unsaved change from the working config.
        events.clear()
        val revision = radio.configRevision.value
        assertEquals(TaskResult.COMPLETED, radio.readConfigFromDevice())
        assertEquals(0, radio.getOutputChannels()[8].middle)
        assertEquals(revision + 1, radio.configRevision.value)
        assertEquals(listOf<RadioEvent>(RadioEvent.ConfigRead), events)

        // Load B (device still holds the plane) and read the device again: B's file is overwritten.
        assertTrue(radio.loadModelFile(b.filePath))
        assertEquals(25, radio.getOutputChannels()[8].middle)
        assertEquals(TaskResult.COMPLETED, radio.readConfigFromDevice())
        assertEquals(0, radio.getOutputChannels()[8].middle)
        assertTrue(radio.loadModelFile(b.filePath))
        assertEquals(0, radio.getOutputChannels()[8].middle)

        // Saving to the device is what makes a working config stick.
        assertTrue(radio.setOutputChannel(radio.getOutputChannels()[8].copy(middle = 12)))
        events.clear()
        assertEquals(TaskResult.COMPLETED, radio.saveConfigToDevice())
        assertEquals(listOf<RadioEvent>(RadioEvent.ConfigWritten), events)
        assertEquals(TaskResult.COMPLETED, radio.readConfigFromDevice())
        assertEquals(12, radio.getOutputChannels()[8].middle)
    }

    @Test
    fun taskFaultsAndResetToDefaults() = runTest {
        val radio = radio()
        val sim = radio.simulator
        val events = eventsOf(radio)
        radio.start()

        sim.injectFault(SimulatedFault.SAVE_TIMEOUT)
        assertEquals(TaskResult.TIMED_OUT, radio.saveConfigToDevice())
        assertFalse(RadioEvent.ConfigWritten in events)
        assertEquals(TaskResult.COMPLETED, radio.saveConfigToDevice())

        sim.injectFault(SimulatedFault.READ_TIMEOUT)
        assertEquals(TaskResult.TIMED_OUT, radio.readConfigFromDevice())
        assertEquals(TaskResult.COMPLETED, radio.readConfigFromDevice())

        // Defaults: the four sticks straight through, nothing else — also in the current model file.
        events.clear()
        assertTrue(radio.resetToDefaults())
        assertTrue(RadioEvent.ConfigRead in events)
        val config = radio.readWorkingConfig()
        assertEquals(4, config.mixes.size)
        assertTrue(config.curves.all { it.isUnused })
        assertTrue(config.dualRates.none { it.isEnabled })
        assertTrue(radio.loadModelFile(radio.getCurrentModel()!!.filePath))
        assertEquals(4, radio.readWorkingConfig().mixes.size)
    }

    // ---- timers through the radio ------------------------------------------------------------

    @Test
    fun timersAndCountersRunAndReport() = runTest {
        val radio = radio()
        val sim = radio.simulator
        val events = eventsOf(radio)
        radio.start()
        radio.advance(1000)
        assertEquals(0, radio.live.value.timer(0)!!.count)
        assertFalse(radio.live.value.timer(0)!!.running)
        assertEquals(300, radio.live.value.timer(1)!!.count)

        // The default plane runs T1 (up) and T2 (5 min countdown) while the throttle is open.
        sim.setStick(SourceId.THR, 0)
        radio.advance(3000)
        val live = radio.live.value
        assertEquals(3, live.timer(0)!!.count)
        assertTrue(live.timer(0)!!.running)
        assertTrue(live.timer(0)!!.showsElapsed)
        assertEquals(297, live.timer(1)!!.count)
        assertFalse(live.timer(1)!!.showsElapsed)
        assertEquals(300, live.timer(1)!!.startTime)
        assertEquals(1, live.timer(1)!!.deviceIndex)
        val reports = events.filterIsInstance<RadioEvent.TimerReported>().map { it.report }
        assertEquals(listOf(0, 1, 2, 3), reports.filter { it.index == 0 }.map { it.count })
        assertEquals(listOf(300, 299, 298, 297), reports.filter { it.index == 1 }.map { it.count })
        assertEquals(3, radio.getTimerConfig(0)!!.count)
        assertEquals(297, radio.getTimerConfig(1)!!.count)

        // Reset is the only command.
        events.clear()
        assertTrue(radio.resetTimer(0))
        val notify = events.filterIsInstance<RadioEvent.TimerNotified>().single().notify
        assertEquals(TimerAlertMode.NOTIFY_RESET, notify.code)
        assertEquals(0, notify.index)
        radio.advance(40)
        assertEquals(0, radio.live.value.timer(0)!!.count)
        assertFalse(radio.resetTimer(6))
        assertFalse(radio.resetTimer(-1))

        // Counter 1 of the default plane counts SH clicks and drives CNT1 from -100 to +100.
        repeat(3) {
            sim.setSwitch(SourceId.SH, SwitchPosition.DOWN)
            radio.advance(40)
            sim.setSwitch(SourceId.SH, SwitchPosition.UP)
            radio.advance(40)
        }
        val counter = radio.live.value.counter(0)!!
        assertEquals(3, counter.count)
        assertEquals(10, counter.startTime)
        assertEquals(3, counter.deviceIndex)
        assertEquals(-40, radio.live.value.logic(SourceId.counter(1)))
        assertEquals(-40, sim.sourceValue(SourceId.counter(1)))
        assertEquals(3, radio.getTimerConfig(0, isCounter = true)!!.count)
        assertTrue(radio.resetTimer(3))
        radio.advance(40)
        assertEquals(0, radio.live.value.counter(0)!!.count)

        // Configuration: a counter needs a maximum, indices are checked, read-back fields are ignored.
        val counterConfig = radio.getTimerConfig(0, isCounter = true)!!
        assertFalse(radio.setTimerConfig(counterConfig.copy(startTime = 0), isCounter = true))
        assertFalse(radio.setTimerConfig(counterConfig.copy(index = 3), isCounter = true))
        assertNull(radio.getTimerConfig(3))
        val timer = radio.getTimerConfig(2)!!
        assertTrue(radio.setTimerConfig(timer.copy(switch = SwitchCondition.at(SourceId.SC, SwitchPosition.UP), startTime = 10, countUp = false, count = 555)))
        radio.advance(2000)
        assertEquals(8, radio.live.value.timer(2)!!.count)
        assertEquals(8, radio.getTimerConfig(2)!!.count)
    }

    // ---- telemetry ---------------------------------------------------------------------------

    @Test
    fun telemetryArrivesAsWellFormedCrsfFramesAndAsTheSdkList() = runTest {
        val radio = radio()
        val sim = radio.simulator
        val events = eventsOf(radio)
        radio.start()
        assertTrue(radio.getTelemetryList().isEmpty())
        radio.advance(2400)

        assertEquals(1, events.count { it == RadioEvent.TelemetryRecovered })
        val frames = events.filterIsInstance<RadioEvent.CrsfReceived>().map { it.frame }
        assertTrue(frames.all { CrsfEncoder.isValid(it.bytes) && it.address == CrsfEncoder.ADDRESS_RADIO })
        assertEquals(
            setOf(
                CrsfFrame.TYPE_GPS,
                CrsfFrame.TYPE_VARIO,
                CrsfFrame.TYPE_BATTERY,
                CrsfFrame.TYPE_BARO_ALTITUDE,
                CrsfFrame.TYPE_AIRSPEED,
                CrsfFrame.TYPE_LINK_STATISTICS,
                CrsfFrame.TYPE_ATTITUDE,
                CrsfFrame.TYPE_FLIGHT_MODE,
            ),
            frames.map { it.type }.toSet(),
        )
        // Rates: link statistics 5 per second, battery once per second.
        assertEquals(12, events.frames(CrsfFrame.TYPE_LINK_STATISTICS).size)
        assertEquals(3, events.frames(CrsfFrame.TYPE_BATTERY).size)
        assertTrue(RadioEvent.TelemetryUpdated in events)
        assertTrue(events.any { it is RadioEvent.ElrsTelemetry })

        // Without the demo the values are the ones at rest: 11.6 V, 120 m.
        val battery = events.frames(CrsfFrame.TYPE_BATTERY).last()
        assertEquals(116, battery.u16(0))
        assertEquals(42, battery.u16(2))
        assertEquals(86, battery.payload(7))
        assertEquals(11200, events.frames(CrsfFrame.TYPE_BARO_ALTITUDE).last().u16(0))
        assertEquals("MANU", String(events.frames(CrsfFrame.TYPE_FLIGHT_MODE).last().bytes, 3, 4, Charsets.US_ASCII))
        assertEquals(11.6f, radio.live.value.status.rxVoltage, 1e-3f)
        assertEquals(100, radio.live.value.status.linkQuality)
        assertEquals(-58, radio.live.value.status.rssiDbm)

        val list = radio.getTelemetryList()
        assertEquals(11.6, list.single { it.name == "RxBt" }.value, 1e-9)
        assertEquals("11.6V", list.single { it.name == "RxBt" }.valueText)
        assertEquals("MANU", list.single { it.name == "FM" }.valueText)
        assertEquals(list.size, list.map { it.hash }.toSet().size)
        assertTrue(list.none { it.hash == 0L })
        assertTrue(radio.getTelemetryList(uiOnly = true).size < list.size)
        assertTrue(radio.getTelemetryList(uiOnly = true).all { it.showInUi })

        // Manual values go out in the next frames; the panel's map follows.
        events.clear()
        sim.setTelemetry(Sensors.BARO_ALTITUDE, 250.0)
        sim.setTelemetry(Sensors.VERTICAL_SPEED, -1.5)
        sim.setTelemetry(Sensors.BATTERY_VOLTAGE, 10.4)
        sim.setTelemetry(Sensors.TX_RX_BATTERY, 10.4)
        sim.setTelemetryFlightMode("CRUZ")
        sim.setTxVoltage(7.2f)
        assertEquals(250.0, sim.telemetry.value.getValue(Sensors.BARO_ALTITUDE), 0.0)
        radio.advance(2000)
        assertEquals(12500, events.frames(CrsfFrame.TYPE_BARO_ALTITUDE).last().u16(0))
        assertEquals(-150, events.frames(CrsfFrame.TYPE_VARIO).last().u16(0).toShort().toInt())
        assertEquals(104, events.frames(CrsfFrame.TYPE_BATTERY).last().u16(0))
        assertEquals("CRUZ", String(events.frames(CrsfFrame.TYPE_FLIGHT_MODE).last().bytes, 3, 4, Charsets.US_ASCII))
        assertEquals(10.4f, radio.live.value.status.rxVoltage, 1e-3f)
        assertEquals(7.2f, radio.live.value.status.txVoltage, 1e-3f)
        assertEquals(-1.5, sim.telemetry.value.getValue(Sensors.VERTICAL_SPEED), 0.0)
        // CRSF link values feed the decoders only, not the radio status.
        sim.setTelemetry(Sensors.LINK_RX_QUALITY, 40.0)
        radio.advance(40)
        assertEquals(100, radio.live.value.status.linkQuality)
        sim.setTelemetry(Sensors.TX_LINK_QUALITY, 55.0)
        radio.advance(40)
        assertEquals(55, radio.live.value.status.linkQuality)
    }

    @Test
    fun linkDropStopsTelemetryAndRestoreBringsItBack() = runTest {
        val radio = radio()
        val sim = radio.simulator
        val events = eventsOf(radio)
        radio.start()
        radio.advance(1000)
        assertTrue(sim.linkUp.value)

        events.clear()
        sim.setLinkUp(false)
        assertFalse(sim.linkUp.value)
        radio.advance(1000)
        assertEquals(listOf<RadioEvent>(RadioEvent.TelemetryLost), events.filter { it !is RadioEvent.TimerReported })
        assertEquals(0, radio.live.value.status.linkQuality)
        assertEquals(0, radio.live.value.status.lostCounter)
        assertFalse(radio.live.value.status.isLinkUp)
        // The last known receiver voltage stays on display.
        assertEquals(11.6f, radio.live.value.status.rxVoltage, 1e-3f)

        events.clear()
        sim.setLinkUp(true)
        radio.advance(1000)
        assertEquals(RadioEvent.TelemetryRecovered, events.first())
        assertTrue(events.frames(CrsfFrame.TYPE_LINK_STATISTICS).isNotEmpty())
        assertTrue(radio.live.value.status.isLinkUp)

        // Rebooting the RF module drops the link for a moment.
        events.clear()
        assertTrue(radio.rebootRfModule())
        radio.advance(FakeRadio.RF_REBOOT_MS + 200)
        assertEquals(1, events.count { it == RadioEvent.TelemetryLost })
        assertEquals(1, events.count { it == RadioEvent.TelemetryRecovered })
    }

    @Test
    fun demoFlightMovesSticksAndTelemetryDeterministically() = runTest {
        val options = deterministic.copy(demoMotion = true)
        val radio = radio(options = options)
        val sim = radio.simulator
        val events = eventsOf(radio)
        radio.start()
        assertTrue(sim.demoMotion.value)

        val ailerons = HashSet<Int>()
        val altitudes = HashSet<Double>()
        repeat(60) {
            radio.advance(1000)
            ailerons += radio.live.value.logic(SourceId.AIL)
            altitudes += sim.telemetry.value.getValue(Sensors.BARO_ALTITUDE)
        }
        assertTrue(ailerons.size > 10)
        assertTrue(altitudes.size > 30)
        assertTrue(altitudes.all { it in 0.0..2000.0 })
        // The battery drains and the aircraft moves away from its start point.
        assertTrue(sim.telemetry.value.getValue(Sensors.BATTERY_CAPACITY) > 310.0)
        assertTrue(sim.telemetry.value.getValue(Sensors.BATTERY_VOLTAGE) in 9.0..12.7)
        assertNotEquals(SimTelemetry.HOME_LATITUDE, sim.telemetry.value.getValue(Sensors.GPS_LATITUDE))
        assertTrue(events.filterIsInstance<RadioEvent.CrsfReceived>().all { CrsfEncoder.isValid(it.frame.bytes) })
        // The throttle idles at first, so the pre-flight check of the default plane passes.
        assertTrue(events.none { it is RadioEvent.PreFlightCheck })
        // T1 ran while the throttle was open.
        assertTrue(radio.live.value.timer(0)!!.count > 10)

        // Same seed, same flight.
        val twin = radio(options = options)
        twin.start()
        twin.advance(60_000)
        assertEquals(sim.telemetry.value, twin.simulator.telemetry.value)
        assertEquals(radio.live.value.channelValues().toList(), twin.live.value.channelValues().toList())

        // A hand on the stick wins over the demo; switching the demo off freezes the rest.
        sim.setStick(SourceId.AIL, -77)
        radio.advance(3000)
        assertEquals(-77, radio.live.value.logic(SourceId.AIL))
        sim.setDemoMotion(false)
        assertFalse(sim.demoMotion.value)
        radio.advance(40)
        val frozen = radio.live.value.logic(SourceId.ELE)
        val altitude = sim.telemetry.value.getValue(Sensors.BARO_ALTITUDE)
        radio.advance(3000)
        assertEquals(frozen, radio.live.value.logic(SourceId.ELE))
        assertEquals(altitude, sim.telemetry.value.getValue(Sensors.BARO_ALTITUDE), 0.0)

        // Reset: controls at rest, telemetry at rest.
        sim.reset()
        radio.advance(40)
        assertEquals(0, radio.live.value.logic(SourceId.AIL))
        assertEquals(-100, radio.live.value.logic(SourceId.THR))
        assertEquals(120.0, sim.telemetry.value.getValue(Sensors.BARO_ALTITUDE), 0.0)
    }

    @Test
    fun sdkTelemetryNotifications() = runTest {
        val radio = radio()
        val sim = radio.simulator
        val events = eventsOf(radio)
        radio.start()
        radio.advance(400)
        val altitude = radio.getTelemetryList().single { it.name == "Alt" }

        assertFalse(radio.addTelemetryAlarm(TelemetryAlarm(hash = 0, triggerValue = 1.0)))
        assertFalse(radio.setTelemetryAlarm(altitude.hash, TelemetryAlarm(hash = altitude.hash), index = 0))
        val alarm = TelemetryAlarm(
            hash = altitude.hash,
            trigger = CompareOp.GREATER,
            triggerValue = 200.0,
            intervalSec = 2,
            name = "Alt",
            text = "Too high",
            value = 999.0,
        )
        assertTrue(radio.addTelemetryAlarm(alarm))
        val stored = radio.getTelemetryAlarms().single()
        assertEquals(0, stored.index)
        assertEquals("Too high", stored.text)
        assertEquals(120.0, stored.value, 1e-9)
        assertEquals(stored, radio.getTelemetryAlarm(altitude.hash, 0))
        assertNull(radio.getTelemetryAlarm(altitude.hash, 1))

        radio.advance(1000)
        assertNull(radio.pollTelemetryNotification())

        events.clear()
        sim.setTelemetry(Sensors.BARO_ALTITUDE, 250.0)
        radio.advance(400)
        assertEquals(1, events.count { it == RadioEvent.TelemetryNotificationQueued })
        val fired = radio.pollTelemetryNotification()!!
        assertEquals(250.0, fired.value, 1e-9)
        assertEquals("Too high", fired.text)
        assertNull(radio.pollTelemetryNotification())

        // It repeats every interval while the condition holds.
        radio.advance(4200)
        assertEquals(2, radio.drainTelemetryNotifications().size)

        // Modify and delete by index.
        assertTrue(radio.setTelemetryAlarm(altitude.hash, alarm.copy(triggerValue = 500.0), index = 0))
        assertEquals(500.0, radio.getTelemetryAlarms().single().triggerValue, 0.0)
        assertFalse(radio.deleteTelemetryAlarm(1))
        assertTrue(radio.deleteTelemetryAlarm(0))
        assertTrue(radio.getTelemetryAlarms().isEmpty())
    }

    // ---- events ------------------------------------------------------------------------------

    @Test
    fun preFlightCheckIsRaisedUntilTheInputsAreInPlace() = runTest {
        val radio = radio()
        val sim = radio.simulator
        val events = eventsOf(radio)
        sim.setStick(SourceId.THR, 40)
        radio.start()
        radio.advance(200)
        val raised = events.filterIsInstance<RadioEvent.PreFlightCheck>().single()
        assertTrue(raised.hasFailures)
        assertEquals(36, raised.states.size)
        assertEquals(listOf(Sources.THR), raised.states.indices.filter { raised.states[it] != 0 })

        sim.setStick(SourceId.THR, -100)
        radio.advance(40)
        val cleared = events.filterIsInstance<RadioEvent.PreFlightCheck>().last()
        assertFalse(cleared.hasFailures)
        // Once passed it does not come back.
        sim.setStick(SourceId.THR, 40)
        radio.advance(200)
        assertEquals(2, events.count { it is RadioEvent.PreFlightCheck })

        // The injected failure stays until it is skipped.
        sim.injectFault(SimulatedFault.PRE_FLIGHT_CHECK_FAILS)
        radio.advance(200)
        assertTrue(events.filterIsInstance<RadioEvent.PreFlightCheck>().last().hasFailures)
        assertTrue(radio.skipPreFlightCheck())
        assertFalse(events.filterIsInstance<RadioEvent.PreFlightCheck>().last().hasFailures)
    }

    @Test
    fun trimsVoltageAndInjectedEvents() = runTest {
        val radio = radio()
        val sim = radio.simulator
        val events = eventsOf(radio)
        radio.start()
        radio.advance(40)

        // Mode 2: aileron trim = right horizontal, throttle trim = left vertical.
        sim.setTrim(SourceId.AIL, 12)
        sim.setTrim(SourceId.THR, -200)
        sim.setTrim(SourceId.SA, 50)
        radio.advance(40)
        assertEquals(12, radio.live.value.trims.rightHorizontal)
        assertEquals(-100, radio.live.value.trims.leftVertical)
        assertEquals(12, radio.getTrims()!!.rightHorizontal)
        assertEquals(
            listOf(FakeRadio.TRIM_RIGHT_HORIZONTAL, FakeRadio.TRIM_LEFT_VERTICAL),
            events.filterIsInstance<RadioEvent.TrimChanged>().map { it.code },
        )
        // Trims belong to the radio hardware: a device read does not undo them.
        assertEquals(TaskResult.COMPLETED, radio.readConfigFromDevice())
        assertEquals(12, radio.getTrims()!!.rightHorizontal)

        // Low transmitter battery: announced when it drops below the alarm voltage and repeated.
        events.clear()
        sim.setTxVoltage(6.5f)
        radio.advance(40)
        assertEquals(1, events.count { it == RadioEvent.LowVoltage })
        radio.advance(10_000)
        assertEquals(2, events.count { it == RadioEvent.LowVoltage })
        sim.setTxVoltage(7.9f)
        events.clear()
        radio.advance(20_000)
        assertEquals(0, events.count { it == RadioEvent.LowVoltage })
        sim.injectFault(SimulatedFault.LOW_TX_VOLTAGE)
        radio.advance(40)
        assertEquals(1, events.count { it == RadioEvent.LowVoltage })
        assertTrue(radio.live.value.status.txVoltage < radio.getSystemConfig()!!.alarmVoltage)

        events.clear()
        sim.injectFault(SimulatedFault.FIRMWARE_MISMATCH)
        sim.emitEvent(RadioEvent.TrimChanged(9))
        radio.advance(40)
        assertTrue(RadioEvent.FirmwareVersionMismatch in events)
        assertTrue(RadioEvent.TrimChanged(9) in events)

        assertTrue(radio.setStickCalibration(true))
        radio.advance(40)
        assertTrue(radio.live.value.status.isCalibrating)
        assertTrue(radio.getRadioStatus()!!.isCalibrating)
        assertTrue(radio.setStickCalibration(false))
        radio.advance(40)
        assertFalse(radio.live.value.status.isCalibrating)
        assertTrue(radio.setUsbSerialPassthrough(1))
        events.clear()
        assertTrue(radio.requestDeviceInfo())
        assertTrue(RadioEvent.FirmwareInfoUpdated in events)
    }

    // ---- ELRS --------------------------------------------------------------------------------

    @Test
    fun elrsMenuCommandsAndStatus() = runTest {
        val radio = radio()
        val events = eventsOf(radio)
        radio.start()
        radio.advance(400)

        // Nothing was delivered yet: commands are refused.
        assertFalse(radio.sendElrsMenuCommand(SimElrs.ITEM_PACKET_RATE, 5))

        events.clear()
        assertTrue(radio.requestElrsMenu())
        assertEquals(RadioEvent.ElrsDeviceInfo(SimElrs.TX_DEVICE_NAME), events.first())
        val items = events.filterIsInstance<RadioEvent.ElrsMenuItemReceived>().map { it.item }
        assertEquals(18, items.size)
        assertEquals(items.size, items.map { it.index }.toSet().size)
        val rate = items.single { it.name == "Packet Rate" }
        assertEquals(ElrsItemType.TEXT_SELECTION, rate.type)
        assertEquals("250Hz", rate.options[rate.value])
        assertTrue(items.any { it.name == "Telem Ratio" })
        val powerFolder = items.single { it.name == "TX Power" }
        assertEquals(ElrsItemType.FOLDER, powerFolder.type)
        val maxPower = items.single { it.name == "Max Power" }
        assertEquals(powerFolder.index, maxPower.parentIndex)
        assertEquals("mW", maxPower.units)
        val bind = items.single { it.name == "Bind" }
        assertEquals(ElrsItemType.COMMAND, bind.type)

        // Set the packet rate: the item comes back with the new value, then the acknowledgement.
        events.clear()
        assertTrue(radio.sendElrsMenuCommand(rate.index, 5))
        assertEquals(5, (events[0] as RadioEvent.ElrsMenuItemReceived).item.value)
        val ack = (events[1] as RadioEvent.ElrsAcknowledged).ack
        assertEquals(rate.index, ack.lastSetIndex)
        assertEquals(SimElrs.STATUS_READY, ack.status)

        // Invalid commands are not forwarded.
        assertFalse(radio.sendElrsMenuCommand(rate.index, 6))
        assertFalse(radio.sendElrsMenuCommand(powerFolder.index, 0))
        assertFalse(radio.sendElrsMenuCommand(99, 0))

        // Bind: in progress, then done.
        events.clear()
        assertTrue(radio.sendElrsMenuCommand(bind.index, SimElrs.STEP_START))
        val bindAcks = events.filterIsInstance<RadioEvent.ElrsAcknowledged>().map { it.ack }
        assertEquals(listOf(SimElrs.STATUS_PROGRESS, SimElrs.STATUS_READY), bindAcks.map { it.status })
        assertEquals("Binding...", bindAcks[0].options)
        assertTrue(bindAcks.all { it.type == ElrsItemType.COMMAND && it.lastSetIndex == bind.index })

        // The module's settings show up in the link statistics and survive a new menu request.
        events.clear()
        assertTrue(radio.sendElrsMenuCommand(maxPower.index, 4))
        radio.advance(400)
        val link = events.frames(CrsfFrame.TYPE_LINK_STATISTICS).last()
        assertEquals(5, link.payload(5))
        assertEquals(CrsfEncoder.txPowerIndex(250), link.payload(6))
        events.clear()
        assertTrue(radio.requestElrsMenu())
        assertEquals(5, events.filterIsInstance<RadioEvent.ElrsMenuItemReceived>().single { it.item.index == rate.index }.item.value)

        events.clear()
        assertTrue(radio.requestElrsStatus())
        val status = events.filterIsInstance<RadioEvent.ElrsStatusReceived>().single().status
        assertEquals(500, status.goodPackets)
        assertFalse(status.isModelMismatch)

        // The receiver has a menu of its own while the link is up; unknown devices do not answer.
        events.clear()
        assertTrue(radio.requestElrsMenu(CrsfEncoder.ADDRESS_RECEIVER))
        assertEquals(RadioEvent.ElrsDeviceInfo(SimElrs.RX_DEVICE_NAME), events.first())
        events.clear()
        assertTrue(radio.requestElrsMenu(0x10))
        assertTrue(events.none { it is RadioEvent.ElrsMenuItemReceived })
        assertFalse(radio.sendElrsMenuCommand(rate.index, 1))

        // A device ping is answered with a device info frame.
        events.clear()
        assertFalse(radio.sendCrsfFrame(ByteArray(0)))
        val ping = CrsfEncoder.frame(CrsfEncoder.TYPE_DEVICE_PING, byteArrayOf(0, CrsfEncoder.ADDRESS_RADIO.toByte()), CrsfEncoder.ADDRESS_FLIGHT_CONTROLLER)
        assertTrue(radio.sendCrsfFrame(ping.bytes))
        assertEquals(CrsfEncoder.TYPE_DEVICE_INFO, events.filterIsInstance<RadioEvent.CrsfReceived>().single().frame.type)
    }

    @Test
    fun elrsMenuIsDeliveredItemByItemWithRealisticDelays() = runTest {
        val radio = radio(options = FakeRadio.Options(demoMotion = false))
        val events = eventsOf(radio)
        radio.start()

        assertTrue(radio.requestElrsMenu())
        // While the menu is being read, further requests are ignored.
        assertFalse(radio.requestElrsMenu())
        assertFalse(radio.requestElrsStatus())
        runCurrent()
        assertTrue(events.none { it is RadioEvent.ElrsMenuItemReceived })
        advanceTimeBy(SimElrs.ITEM_DELAY_MS * 4 + 1)
        assertEquals(3, events.count { it is RadioEvent.ElrsMenuItemReceived })
        advanceTimeBy(SimElrs.ITEM_DELAY_MS * 30)
        assertEquals(18, events.count { it is RadioEvent.ElrsMenuItemReceived })
        assertTrue(radio.requestElrsStatus())

        // The bind acknowledgements are two seconds apart.
        events.clear()
        assertTrue(radio.sendElrsMenuCommand(SimElrs.ITEM_BIND, SimElrs.STEP_START))
        advanceTimeBy(SimElrs.ACK_DELAY_MS + 1)
        assertEquals(1, events.count { it is RadioEvent.ElrsAcknowledged })
        advanceTimeBy(SimElrs.BIND_DURATION_MS)
        assertEquals(2, events.count { it is RadioEvent.ElrsAcknowledged })
        radio.stop()
    }
}
