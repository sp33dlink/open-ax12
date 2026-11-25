package org.openax12.core.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.audio.TtsStatus
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.AppSettings
import org.openax12.core.model.AudioCategory
import org.openax12.core.radio.ConnectionState
import org.openax12.core.radio.SimulatedFault
import org.openax12.core.radio.fake.FakeRadio

@OptIn(ExperimentalCoroutinesApi::class)
class GraphLifecycleTest {

    private class Rig(
        val radio: FakeRadio,
        val models: FakeModels,
        val telemetry: FakeTelemetry,
        val audio: RecordingAudio,
        val settings: FakeSettings,
        val diagnostics: DefaultDiagnosticsLog,
        val engine: DefaultRuntimeEngine,
        val lifecycle: GraphLifecycle,
    )

    private val deterministic = FakeRadio.Options(demoMotion = false, realisticDelays = false, autoTick = false)

    private fun TestScope.rig(settings: AppSettings = AppSettings()): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher)
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
        val fakeSettings = FakeSettings(settings)
        val diagnostics = DefaultDiagnosticsLog()
        val logger = FakeFlightLogger()
        val engine = DefaultRuntimeEngine(
            radio, models, telemetry, fakeSettings, audio, logger, diagnostics, TEST_PHRASES, backgroundScope, dispatchers,
        )
        val lifecycle = GraphLifecycle(
            radio = radio,
            models = models,
            telemetry = telemetry,
            flightLogger = logger,
            audio = audio,
            runtime = engine,
            settings = fakeSettings,
            diagnostics = diagnostics,
            scope = backgroundScope,
            dispatchers = dispatchers,
        )
        runCurrent()
        return Rig(radio, models, telemetry, audio, fakeSettings, diagnostics, engine, lifecycle)
    }

    @Test
    fun startRunsTheSequenceInOrderAndEndsRunning() = runTest {
        val rig = rig(AppSettings(masterVolume = 40, speechLanguage = "de-DE", speechRate = 1.25f, haptics = false))
        assertEquals(StartupState.NotStarted, rig.lifecycle.startup.value)

        val state = rig.lifecycle.start()

        assertEquals(StartupState.Running, state)
        assertEquals(StartupState.Running, rig.lifecycle.startup.value)
        assertTrue(rig.radio.connection.value.isReady)
        assertTrue(rig.engine.running.value)
        assertEquals(listOf("audio.start"), rig.audio.calls.filter { it.startsWith("audio.start") })
        assertEquals(listOf("telemetry.start"), rig.telemetry.calls)
        assertEquals(listOf("reconcile"), rig.models.calls)
        // Settings reached the audio engine, the library report's problems the diagnostics.
        assertEquals(40, rig.audio.master)
        assertEquals("de-DE" to 1.25f, rig.audio.speech)
        assertEquals(false, rig.audio.hapticsEnabled)
        assertTrue(rig.diagnostics.entries.value.any { it.source == DiagnosticsLog.SOURCE_LIBRARY && it.message == "a problem" })
        // A second call while running does nothing.
        assertEquals(StartupState.Running, rig.lifecycle.start())
        assertEquals(listOf("reconcile"), rig.models.calls)
    }

    @Test
    fun aFailedRadioStartIsReportedAndStartRetries() = runTest {
        val rig = rig()
        rig.radio.simulator.injectFault(SimulatedFault.SERIAL_PORT_BUSY)

        val state = rig.lifecycle.start()

        assertTrue(state is StartupState.RadioFailed)
        assertTrue((state as StartupState.RadioFailed).reason.mayBeStockAppConflict)
        assertFalse(rig.engine.running.value)
        assertTrue(rig.diagnostics.entries.value.any { it.source == DiagnosticsLog.SOURCE_RADIO })
        assertTrue(rig.models.calls.isEmpty())

        assertEquals(StartupState.Running, rig.lifecycle.start())
        assertTrue(rig.engine.running.value)
    }

    @Test
    fun aBrokenConnectionMovesARunningGraphToRadioFailed() = runTest {
        val rig = rig()
        rig.lifecycle.start()

        rig.radio.simulator.injectFault(SimulatedFault.CONNECTION_LOST)
        rig.radio.advance(40)
        advanceUntilIdle()

        assertTrue(rig.radio.connection.value is ConnectionState.Error)
        assertTrue(rig.lifecycle.startup.value is StartupState.RadioFailed)
        assertFalse(rig.engine.running.value)
        assertTrue(rig.diagnostics.entries.value.any { it.message.startsWith("connection error") })
    }

    @Test
    fun shutdownStopsEverythingInOrder() = runTest {
        val rig = rig()
        rig.lifecycle.start()

        rig.lifecycle.shutdown()

        assertFalse(rig.engine.running.value)
        assertEquals(listOf("telemetry.start", "telemetry.stop"), rig.telemetry.calls)
        assertTrue(rig.audio.calls.contains("audio.shutdown"))
        assertEquals(ConnectionState.Stopped, rig.radio.connection.value)
        assertEquals(StartupState.NotStarted, rig.lifecycle.startup.value)
        // And it can start again.
        assertEquals(StartupState.Running, rig.lifecycle.start())
    }

    @Test
    fun settingsChangesAreAppliedAndSpeechIsReconfiguredOnlyWhenItChanged() = runTest {
        val rig = rig()
        rig.lifecycle.start()
        runCurrent()
        assertEquals("" to 1.0f, rig.audio.speech)
        rig.audio.speech = null

        rig.settings.update { it.withVolume(AudioCategory.ALARM, 30).copy(masterVolume = 55) }
        runCurrent()

        assertEquals(55, rig.audio.master)
        assertEquals(30, rig.audio.categoryVolumes[AudioCategory.ALARM])
        assertEquals("speech was not touched", null, rig.audio.speech)

        rig.settings.update { it.copy(speechRate = 0.8f) }
        runCurrent()
        assertEquals("" to 0.8f, rig.audio.speech)
    }

    @Test
    fun textToSpeechProblemsAreRecorded() = runTest {
        val rig = rig()

        rig.audio.setTts(TtsStatus.NoEngine)
        runCurrent()

        assertTrue(rig.diagnostics.entries.value.any { it.source == DiagnosticsLog.SOURCE_AUDIO })
    }
}
