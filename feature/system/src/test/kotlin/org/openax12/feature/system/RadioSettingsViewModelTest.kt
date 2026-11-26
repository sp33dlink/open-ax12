package org.openax12.feature.system

import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.audio.AudioEngine
import org.openax12.core.audio.TtsStatus
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.SettingsRepository
import org.openax12.core.data.StorageInfo
import org.openax12.core.model.AppSettings
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.RadioBackend
import org.openax12.core.model.RfModuleMode
import org.openax12.core.model.StickMode
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.WorkingConfig
import org.openax12.core.radio.fake.FakeRadio
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.DiagnosticsLog
import org.openax12.core.runtime.RuntimeEngine
import org.openax12.core.runtime.SaveState
import org.openax12.core.runtime.SessionError
import org.openax12.core.runtime.SessionResult
import org.openax12.feature.system.common.Feedback
import org.openax12.feature.system.common.toFeedback
import org.openax12.feature.system.settings.RadioAction
import org.openax12.feature.system.settings.SettingsEvent
import org.openax12.feature.system.settings.SettingsViewModel
import org.openax12.feature.system.settings.needsConfirmation
import org.openax12.feature.system.settings.turnsRfOff

/** An interface fake: [handler] answers every call (suspend functions return their value directly). */
private inline fun <reified T : Any> proxy(crossinline handler: (String, List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        handler(method.name, (args ?: emptyArray()).toList().filter { it !is kotlin.coroutines.Continuation<*> })
    } as T

/** Radio panel: what asks for a confirmation and what reaches the session. */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioSettingsViewModelTest {

    private class UpdateCall(val rfOffConfirmed: Boolean, val result: SystemConfig)

    /** A [ConfigSession] that records `updateSystemConfig` and applies the transform to [system]. */
    private class SessionStub(val system: SystemConfig?) {
        val calls = mutableListOf<UpdateCall>()
        val working = MutableStateFlow(WorkingConfig.EMPTY)

        @Suppress("UNCHECKED_CAST")
        val session: ConfigSession = proxy { method, args ->
            when (method) {
                "getWorkingConfig" -> working
                "getDirty" -> MutableStateFlow(false)
                "getDeviceInSync" -> MutableStateFlow(true)
                "getSaveState" -> MutableStateFlow<SaveState>(SaveState.Idle)
                "updateSystemConfig" -> {
                    val confirmed = args[0] as Boolean
                    val transform = args[1] as (SystemConfig) -> SystemConfig
                    val result = transform(system ?: SystemConfig())
                    calls += UpdateCall(confirmed, result)
                    SessionResult.Success(result)
                }
                else -> error("unexpected ConfigSession.$method")
            }
        }
    }

    private class Rig(val vm: SettingsViewModel, val stub: SessionStub)

    private suspend fun TestScope.rig(system: SystemConfig? = SystemConfig(alarmVoltageDeciVolt = 33)): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val radio = FakeRadio(
            scope = backgroundScope,
            directory = null,
            dispatcher = dispatcher,
            clock = { testScheduler.currentTime },
            options = FakeRadio.Options(demoMotion = false, realisticDelays = false, autoTick = false),
        )
        radio.start()
        val stub = SessionStub(system)
        val settings: SettingsRepository = proxy { method, _ ->
            when (method) {
                "getSettings" -> MutableStateFlow(AppSettings())
                else -> error("unexpected SettingsRepository.$method")
            }
        }
        val audio: AudioEngine = proxy { method, _ ->
            when (method) {
                "getTtsStatus" -> MutableStateFlow<TtsStatus>(TtsStatus.Initializing)
                "getMusicPlaying" -> MutableStateFlow(false)
                "availableLanguages" -> emptyList<String>()
                else -> error("unexpected AudioEngine.$method")
            }
        }
        val models: ModelRepository = proxy { method, _ ->
            when (method) {
                "getStorage" -> MutableStateFlow(StorageInfo())
                else -> error("unexpected ModelRepository.$method")
            }
        }
        val runtime: RuntimeEngine = proxy { method, _ -> error("unexpected RuntimeEngine.$method") }
        val diagnostics: DiagnosticsLog = proxy { _, _ -> null }
        val vm = SettingsViewModel(
            radio = radio,
            session = stub.session,
            settings = settings,
            audio = audio,
            runtime = runtime,
            models = models,
            diagnostics = diagnostics,
            activeBackend = RadioBackend.SIMULATOR,
            scopeOverride = backgroundScope,
        )
        backgroundScope.launch { vm.state.collect { } }
        repeat(3) { runCurrent() }
        return Rig(vm, stub)
    }

    @Test
    fun turningTheRfModuleOffAsksFirstAndDoesNothingUntilConfirmed() = runTest {
        val rig = rig()

        rig.vm.onEvent(SettingsEvent.Radio(RadioAction.SetRfMode(RfModuleMode.OFF)))
        repeat(3) { runCurrent() }

        assertEquals(RadioAction.SetRfMode(RfModuleMode.OFF), rig.vm.state.value.pending)
        assertTrue("nothing may reach the session before the confirmation", rig.stub.calls.isEmpty())

        rig.vm.onEvent(SettingsEvent.ConfirmPending)
        repeat(3) { runCurrent() }

        val call = rig.stub.calls.single()
        assertTrue(call.rfOffConfirmed)
        assertEquals(RfModuleMode.OFF, call.result.rfModuleMode)
        assertNull(rig.vm.state.value.pending)
    }

    @Test
    fun cancellingTheRfOffConfirmationChangesNothing() = runTest {
        val rig = rig()
        rig.vm.onEvent(SettingsEvent.Radio(RadioAction.SetRfMode(RfModuleMode.OFF)))
        repeat(3) { runCurrent() }

        rig.vm.onEvent(SettingsEvent.DismissPending)
        repeat(3) { runCurrent() }

        assertNull(rig.vm.state.value.pending)
        assertTrue(rig.stub.calls.isEmpty())
    }

    @Test
    fun otherRadioChangesGoStraightThroughAndNeverClaimAConfirmation() = runTest {
        val rig = rig()

        rig.vm.onEvent(SettingsEvent.Radio(RadioAction.SetAlarmVoltage(34)))
        rig.vm.onEvent(SettingsEvent.Radio(RadioAction.SetRfMode(RfModuleMode.EXTERNAL)))
        repeat(3) { runCurrent() }

        assertEquals(2, rig.stub.calls.size)
        assertFalse(rig.stub.calls.any { it.rfOffConfirmed })
        assertEquals(34, rig.stub.calls[0].result.alarmVoltageDeciVolt)
        assertEquals(RfModuleMode.EXTERNAL, rig.stub.calls[1].result.rfModuleMode)
    }

    @Test
    fun rfOffIsConfirmedWithOrWithoutAReceiverLinkOtherActionsOnlyWhileLinked() {
        val off = RadioAction.SetRfMode(RfModuleMode.OFF)
        assertTrue(off.turnsRfOff)
        assertTrue(off.needsConfirmation(linkUp = false))
        assertTrue(off.needsConfirmation(linkUp = true))

        for (action in listOf(
            RadioAction.SetRfMode(RfModuleMode.INTERNAL),
            RadioAction.SetRfMode(RfModuleMode.EXTERNAL),
            RadioAction.SetAlarmVoltage(34),
            RadioAction.SetStickMode(StickMode.MODE_2),
        )) {
            assertFalse(action.toString(), action.turnsRfOff)
            assertFalse(action.toString(), action.needsConfirmation(linkUp = false))
            assertTrue(action.toString(), action.needsConfirmation(linkUp = true))
        }
    }

    @Test
    fun refusalsOfTheSessionBecomeTheirOwnMessages() {
        fun failure(error: SessionError) = SessionResult.Failure(error)
        assertEquals(Feedback.SYSTEM_UNAVAILABLE, failure(SessionError.SYSTEM_CONFIG_UNAVAILABLE).toFeedback())
        assertEquals(Feedback.SYSTEM_NOT_APPLIED, failure(SessionError.SYSTEM_CONFIG_NOT_APPLIED).toFeedback())
        assertEquals(Feedback.RF_OFF_UNCONFIRMED, failure(SessionError.RF_OFF_NOT_CONFIRMED).toFeedback())
    }

    @Test
    fun aRadioWithoutTrustworthySettingsShowsThemAsUnavailable() = runTest {
        // Connected, the channels were read, but the radio gave no trustworthy system configuration.
        val rig = rig(system = null)
        rig.stub.working.value = WorkingConfig(channels = listOf(OutputChannelConfig(channel = 0)), system = null)
        repeat(3) { runCurrent() }

        val state = rig.vm.state.value
        assertNull(state.system)
        assertTrue(state.connection.isReady)
        assertTrue(state.systemUnavailable)
        assertFalse(state.radioReady)
        assertFalse("nothing can be edited while the values are unknown", state.canEditDevice)
    }
}
