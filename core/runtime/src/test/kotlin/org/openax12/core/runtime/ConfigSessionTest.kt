package org.openax12.core.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.data.DataResult
import org.openax12.core.data.RadioWideReport
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.ManagedMix
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.ModelType
import org.openax12.core.model.RadioWidePatch
import org.openax12.core.model.RfModuleMode
import org.openax12.core.model.SourceId
import org.openax12.core.model.StickMode
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.WingConfig
import org.openax12.core.radio.Radio
import org.openax12.core.radio.SimulatedFault
import org.openax12.core.radio.TaskResult
import org.openax12.core.radio.getCurrentModel
import org.openax12.core.radio.fake.FakeRadio
import org.openax12.core.radio.getMixes
import org.openax12.core.radio.readWorkingConfig

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigSessionTest {

    private class Rig(
        val radio: FakeRadio,
        val models: FakeModels,
        val session: DefaultConfigSession,
        val diagnostics: DefaultDiagnosticsLog,
    )

    /** No delays and no clock-driven frames: nothing happens unless the test asks for it. */
    private val deterministic = FakeRadio.Options(demoMotion = false, realisticDelays = false, autoTick = false)

    private suspend fun TestScope.rig(startRadio: Boolean = true, wrap: (FakeRadio) -> Radio = { it }): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val radio = FakeRadio(
            scope = backgroundScope,
            directory = null,
            dispatcher = dispatcher,
            clock = { testScheduler.currentTime },
            options = deterministic,
        )
        val seen = wrap(radio)
        val models = FakeModels(seen)
        val diagnostics = DefaultDiagnosticsLog()
        val session = DefaultConfigSession(
            radio = seen,
            models = models,
            diagnostics = diagnostics,
            scope = backgroundScope,
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
            clock = { 1234L },
        )
        if (startRadio) {
            radio.start()
            // Let the session's connection watcher finish its first read before anything else talks to the radio.
            runCurrent()
            models.reconcileLibrary()
            runCurrent()
        }
        return Rig(radio, models, session, diagnostics)
    }

    private suspend fun Rig.firstMixWeight(): Int = radio.readWorkingConfig().mixes.first().weightUp

    private suspend fun Rig.setFirstMixWeight(weight: Int) {
        session.edit { r ->
            val mix = r.readWorkingConfig().mixes.first()
            r.setMix(mix.copy(weightUp = weight))
        }
    }

    private suspend fun Rig.secondModel(): String {
        val info = radio.createModelInfo()!!.copy(name = "Second")
        assertTrue(radio.saveWorkingConfigToModel(info))
        models.refresh()
        return info.filePath
    }

    @Test
    fun editMarksDirtyOnlyWhenTheConfigurationChanged() = runTest {
        val rig = rig()
        assertFalse("initially dirty", rig.session.dirty.value)
        assertFalse("initially empty", rig.session.workingConfig.value.isEmpty)

        rig.session.edit { /* returns early, changes nothing */ }
        assertFalse("dirty after empty edit", rig.session.dirty.value)

        val before = rig.firstMixWeight()
        rig.setFirstMixWeight(before - 7)
        assertTrue(rig.session.dirty.value)
        // refresh = true: the caller sees its own change at once.
        assertEquals(before - 7, rig.session.workingConfig.value.mixes.first().weightUp)
    }

    @Test
    fun updateExtrasStagesAndMarksDirtyOnlyIfSomethingChanged() = runTest {
        val rig = rig()
        rig.session.updateExtras { it }
        assertFalse(rig.session.dirty.value)
        rig.session.updateExtras { it.copy(notes = "hello") }
        assertTrue(rig.session.dirty.value)
        assertTrue(rig.models.hasStagedExtras.value)
    }

    @Test
    fun saveWritesToTheDevicePersistsAndClearsDirty() = runTest {
        val rig = rig()
        rig.setFirstMixWeight(55)
        rig.session.updateExtras { it.copy(notes = "n") }

        val result = rig.session.save()

        assertTrue(result.isSuccess)
        assertFalse(rig.session.dirty.value)
        assertEquals(1, rig.models.savedCount)
        assertFalse(rig.models.hasStagedExtras.value)
        assertEquals(SaveState.Saved(1234L), rig.session.saveState.value)
    }

    @Test
    fun failedSaveKeepsTheChangesAndRecordsTheFailure() = runTest {
        val rig = rig()
        rig.setFirstMixWeight(55)
        rig.radio.simulator.injectFault(SimulatedFault.SAVE_TIMEOUT)
        rig.radio.advance(40)

        val result = rig.session.save()

        assertEquals(SessionError.DEVICE_TIMEOUT, result.errorOrNull())
        assertTrue(rig.session.dirty.value)
        assertTrue(rig.session.saveState.value is SaveState.Failed)
        assertTrue(rig.diagnostics.entries.value.any { it.source == DiagnosticsLog.SOURCE_SESSION })
        // The retry works: the fault was consumed.
        assertTrue(rig.session.save().isSuccess)
        assertFalse(rig.session.dirty.value)
    }

    @Test
    fun discardRevertsTheWorkingConfigAndTheStagedExtras() = runTest {
        val rig = rig()
        val original = rig.firstMixWeight()
        rig.setFirstMixWeight(original - 20)
        rig.session.updateExtras { it.copy(notes = "staged") }

        val result = rig.session.discard()
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertFalse(rig.session.dirty.value)
        assertEquals(original, rig.firstMixWeight())
        assertEquals("", rig.models.currentExtras.value.notes)
        assertEquals(original, rig.session.workingConfig.value.mixes.first().weightUp)
    }

    @Test
    fun everythingFailsFastWhileTheRadioIsNotReady() = runTest {
        val rig = rig(startRadio = false)

        assertEquals(SessionError.RADIO_NOT_READY, rig.session.save().errorOrNull())
        assertEquals(SessionError.RADIO_NOT_READY, rig.session.discard().errorOrNull())
        assertEquals(SessionError.RADIO_NOT_READY, rig.session.selectModel("x").errorOrNull())
        // An edit block still runs, but nothing becomes dirty.
        val applied = rig.session.edit { r -> r.getMix(0, 0)?.let { r.setMix(it) } ?: false }
        assertFalse(applied)
        assertFalse(rig.session.dirty.value)
        assertTrue(rig.session.workingConfig.value.isEmpty)
    }

    @Test
    fun reconnectDropsUnsavedChangesAndSaysSo() = runTest {
        val rig = rig()
        val original = rig.firstMixWeight()
        rig.setFirstMixWeight(original - 10)
        rig.session.updateExtras { it.copy(notes = "staged") }
        assertTrue(rig.session.dirty.value)

        rig.radio.stop()
        runCurrent()
        rig.radio.start()
        runCurrent()

        assertFalse(rig.session.dirty.value)
        assertFalse(rig.models.hasStagedExtras.value)
        val state = rig.session.saveState.value
        assertTrue(state.toString(), state is SaveState.Failed && state.error == SessionError.RADIO_NOT_READY)
    }

    // -- plans ----------------------------------------------------------------------------------

    private val draft = FlightModesConfig(
        enabled = true,
        modeSwitch = SourceId.SB,
        modes = listOf(
            FlightModeConfig(name = "Normal", position = SwitchPosition.UP),
            FlightModeConfig(name = "Speed", position = SwitchPosition.DOWN, offsets = mapOf(1 to -5), rates = mapOf(0 to 60)),
        ),
    )

    @Test
    fun appliedFlightModePlanIsStagedWithItsManagedItemsAndDiffsToNothingAfterwards() = runTest {
        val rig = rig()
        val (plan, diff) = rig.session.compileFlightModes(draft)
        assertTrue(diff.conflicts.toString(), diff.canApply)
        assertFalse(diff.isNoOp)

        val result = rig.session.applyPlan(plan, diff, stage = { it.copy(flightModes = draft) })

        assertTrue(result.isSuccess)
        assertTrue(rig.session.dirty.value)
        val extras = rig.models.currentExtras.value
        assertEquals(draft, extras.flightModes)
        assertFalse(extras.managed.isEmpty)
        assertTrue(extras.managed.mixes.all { it.generator == ManagedItems.GENERATOR_FLIGHT_MODES })
        // Every managed mix really exists in the firmware configuration now.
        val config = rig.radio.readWorkingConfig()
        assertTrue(extras.managed.mixes.all { config.mix(it.key) != null })
        assertTrue(rig.session.compileFlightModes().second.isNoOp)
    }

    @Test
    fun aPlanIsRejectedWhenTheConfigurationChangedSinceItsDiff() = runTest {
        val rig = rig()
        val (plan, diff) = rig.session.compileFlightModes(draft)
        rig.setFirstMixWeight(rig.firstMixWeight() - 1)

        val result = rig.session.applyPlan(plan, diff)

        assertEquals(SessionError.PLAN_REJECTED, result.errorOrNull())
    }

    @Test
    fun aPlanWithBlockingConflictsIsRejected() = runTest {
        val rig = rig()
        val invalid = draft.copy(modeSwitch = null)
        val (plan, diff) = rig.session.compileFlightModes(invalid)
        assertTrue(diff.hasBlockingConflicts)

        val result = rig.session.applyPlan(plan, diff, stage = { it.copy(flightModes = invalid) })

        assertEquals(SessionError.PLAN_REJECTED, result.errorOrNull())
        assertFalse(rig.session.dirty.value)
    }

    @Test
    fun anOperationThatFailsHalfWayStagesOnlyTheManagedRecords() = runTest {
        val rig = rig()
        val (plan, diff) = rig.session.compileFlightModes(draft)
        assertTrue(diff.ops.size > 1)
        rig.radio.simulator.injectFault(SimulatedFault.ADD_MIX_FAILS)
        rig.radio.advance(40)

        val result = rig.session.applyPlan(plan, diff, stage = { it.copy(flightModes = draft) })

        assertEquals(SessionError.PLAN_OPERATION_FAILED, result.errorOrNull())
        // The settings were not staged ...
        assertFalse(rig.models.currentExtras.value.flightModes.enabled)
        // ... and the session shows a failed operation.
        assertTrue(rig.session.saveState.value is SaveState.Failed)
    }

    // -- models ---------------------------------------------------------------------------------

    @Test
    fun selectingAModelWithUnsavedChangesIsRefusedUnlessDiscarded() = runTest {
        val rig = rig()
        val second = rig.secondModel()
        rig.models.setExtras(rig.models.currentExtras.value)
        rig.setFirstMixWeight(rig.firstMixWeight() - 3)

        assertEquals(SessionError.UNSAVED_CHANGES, rig.session.selectModel(second).errorOrNull())
        assertTrue(rig.session.dirty.value)

        val result = rig.session.selectModel(second, discardChanges = true)

        assertTrue(result.isSuccess)
        assertEquals(second, rig.models.currentModel.value?.id)
        assertFalse(rig.session.dirty.value)
        assertTrue(rig.models.deviceInSync.value)
        assertEquals(1, rig.models.writtenCount)
    }

    @Test
    fun anIncompleteModelLoadIsRefusedAndNeverReachesTheRadio() = runTest {
        val rig = rig()
        val second = rig.secondModel()
        val deviceMixes = rig.radio.readWorkingConfig().mixes.size
        assertTrue(deviceMixes > 0)
        rig.radio.simulator.injectFault(SimulatedFault.INCOMPLETE_MODEL_LOAD)
        rig.radio.advance(40)

        val result = rig.session.selectModel(second)

        // The radio refused to write the empty working config (nothing was sent) ...
        assertEquals(SessionError.CONFIG_INCOMPLETE, result.errorOrNull())
        assertEquals(0, rig.models.writtenCount)
        assertTrue(rig.diagnostics.entries.value.any { "refused to write an incomplete" in it.message })
        // ... and the session did not leave the empty config in place.
        assertFalse(rig.session.workingConfig.value.isEmpty)
        assertEquals(deviceMixes, rig.session.workingConfig.value.mixes.size)
        // The device still holds the original configuration.
        assertEquals(TaskResult.COMPLETED, rig.radio.readConfigFromDevice())
        assertEquals(deviceMixes, rig.radio.readWorkingConfig().mixes.size)
    }

    @Test
    fun savingAnEmptyWorkingConfigIsRefusedAndRecovered() = runTest {
        val rig = rig()
        val deviceMixes = rig.radio.readWorkingConfig().mixes.size
        rig.radio.simulator.injectFault(SimulatedFault.INCOMPLETE_MODEL_LOAD)
        rig.radio.advance(40)
        // Something loads the current model file behind the session's back.
        assertTrue(rig.radio.loadModelFile(rig.radio.getCurrentModel()!!.filePath))

        val result = rig.session.save()

        assertEquals(SessionError.CONFIG_INCOMPLETE, result.errorOrNull())
        assertEquals(0, rig.models.savedCount)
        assertEquals(deviceMixes, rig.radio.readWorkingConfig().mixes.size)
        assertFalse(rig.session.dirty.value)
    }

    @Test
    fun aDeviceWriteThatDoesNotCompleteLeavesTheModelLoadedAndSaveRetries() = runTest {
        val rig = rig()
        val second = rig.secondModel()
        rig.radio.simulator.injectFault(SimulatedFault.SAVE_TIMEOUT)
        rig.radio.advance(40)

        val result = rig.session.selectModel(second)

        assertEquals(SessionError.DEVICE_WRITE_PENDING, result.errorOrNull())
        assertEquals(second, rig.models.currentModel.value?.id)
        assertFalse(rig.session.deviceInSync.value)
        // While the device is not in sync other device-writing flows are refused.
        assertEquals(SessionError.DEVICE_WRITE_PENDING, rig.session.createModel("X", ModelBlueprint.blank()).errorOrNull())
        assertEquals(SessionError.DEVICE_WRITE_PENDING, rig.session.reloadFromDevice().errorOrNull())

        assertTrue(rig.session.save().isSuccess)
        assertTrue(rig.session.deviceInSync.value)
    }

    @Test
    fun createModelBuildsAndSavesABlankModel() = runTest {
        val rig = rig()

        val result = rig.session.createModel("Fresh", ModelBlueprint.blank(ModelType.PLANE))

        assertTrue(result.toString(), result.isSuccess)
        assertEquals("Fresh", rig.models.currentModel.value?.name)
        assertFalse(rig.session.dirty.value)
        assertEquals(1, rig.models.savedCount)
        // The blank plan neutralised the copied configuration.
        assertTrue(rig.radio.readWorkingConfig().mixes.isEmpty())
        assertEquals(ModelType.PLANE, rig.models.currentExtras.value.modelType)
    }

    @Test
    fun createModelRollsBackCompletelyWhenTheSaveFails() = runTest {
        val rig = rig()
        val previous = rig.models.currentModel.value!!
        val mixesBefore = rig.radio.getMixes(0).size
        rig.radio.simulator.injectFault(SimulatedFault.SAVE_TIMEOUT)
        rig.radio.advance(40)

        val result = rig.session.createModel("Fresh", ModelBlueprint.blank())

        assertEquals(SessionError.DEVICE_TIMEOUT, result.errorOrNull())
        assertEquals(previous.id, rig.models.currentModel.value?.id)
        assertNull(rig.models.models.value.firstOrNull { it.name == "Fresh" })
        assertEquals(mixesBefore, rig.radio.getMixes(0).size)
        assertTrue(rig.session.saveState.value is SaveState.Failed)
        // The name can be reused.
        assertTrue(rig.session.createModel("Fresh", ModelBlueprint.blank()).isSuccess)
    }

    // -- system configuration -------------------------------------------------------------------

    @Test
    fun systemConfigurationIsWrittenAtOnceButRefusedWhileDirty() = runTest {
        val rig = rig()
        rig.setFirstMixWeight(rig.firstMixWeight() - 2)
        val refused = rig.session.updateSystemConfig { it.copy(stickMode = StickMode.MODE_1) }
        assertEquals(SessionError.UNSAVED_CHANGES, refused.errorOrNull())

        rig.session.discard()
        val result = rig.session.updateSystemConfig { it.copy(stickMode = StickMode.MODE_1) }

        assertTrue(result.isSuccess)
        assertEquals(StickMode.MODE_1, result.getOrNull()?.stickMode)
        assertEquals(StickMode.MODE_1, rig.radio.getSystemConfig()!!.stickMode)
        assertEquals(1, rig.models.writtenCount)
        assertFalse(rig.session.dirty.value)
    }

    /** A radio whose system-configuration reads and writes are scripted; everything else is the simulator. */
    private class ScriptedSystemRadio(private val inner: FakeRadio) : Radio by inner {
        /** `null` = no trustworthy values; `UNSET` = ask the simulator. */
        var reported: SystemConfig? = UNSET
        var applyWrites = true

        /** The write changes the working config and then reports failure (a half-applied write). */
        var failAfterApplying = false
        val writes = mutableListOf<SystemConfig>()

        override suspend fun getSystemConfig(): SystemConfig? = if (reported === UNSET) inner.getSystemConfig() else reported

        override suspend fun setSystemConfig(config: SystemConfig): Boolean {
            writes += config
            if (failAfterApplying) {
                inner.setSystemConfig(config)
                return false
            }
            return if (applyWrites) inner.setSystemConfig(config) else true
        }

        companion object {
            val UNSET = SystemConfig(alarmVoltageDeciVolt = -12345)
        }
    }

    private lateinit var scripted: ScriptedSystemRadio

    private suspend fun TestScope.scriptedRig(): Rig = rig { fake -> ScriptedSystemRadio(fake).also { scripted = it } }

    @Test
    fun aChangedAlarmVoltageIsWrittenAndPassedToTheOtherModels() = runTest {
        val rig = rig()
        val before = rig.radio.getSystemConfig()!!

        val result = rig.session.updateSystemConfig { it.copy(alarmVoltageDeciVolt = 34) }

        assertTrue(result.toString(), result.isSuccess)
        assertEquals(34, rig.radio.getSystemConfig()!!.alarmVoltageDeciVolt)
        assertEquals(before.rfModuleMode, rig.radio.getSystemConfig()!!.rfModuleMode)
        // Only the changed field goes to the library.
        assertEquals(listOf(RadioWidePatch(alarmVoltageDeciVolt = 34)), rig.models.radioWidePatches.toList())
    }

    @Test
    fun aChangeThatChangesNothingIsNotPassedToTheLibrary() = runTest {
        val rig = rig()
        val result = rig.session.updateSystemConfig { it }
        assertTrue(result.isSuccess)
        assertTrue(rig.models.radioWidePatches.isEmpty())
    }

    @Test
    fun failuresOfSingleModelsDoNotFailTheChangeButAreRecorded() = runTest {
        val rig = rig()
        rig.models.radioWideReport = DataResult.Success(RadioWideReport(failed = listOf("Old plane: rcImportModelCfg failed")))
        val result = rig.session.updateSystemConfig { it.copy(alarmVoltageDeciVolt = 40) }
        assertTrue(result.isSuccess)
        assertTrue(rig.diagnostics.entries.value.any { "Old plane" in it.message })
    }

    @Test
    fun switchingTheRfModuleOffNeedsAnExplicitConfirmation() = runTest {
        val rig = scriptedRig()
        val refused = rig.session.updateSystemConfig { it.copy(rfModuleMode = RfModuleMode.OFF) }

        assertEquals(SessionError.RF_OFF_NOT_CONFIRMED, refused.errorOrNull())
        assertTrue("nothing may be written", scripted.writes.isEmpty())
        assertEquals(RfModuleMode.INTERNAL, rig.radio.getSystemConfig()!!.rfModuleMode)
        assertEquals(0, rig.models.writtenCount)
        assertFalse(rig.session.saveState.value is SaveState.Failed)

        val confirmed = rig.session.updateSystemConfig(rfOffConfirmed = true) { it.copy(rfModuleMode = RfModuleMode.OFF) }
        assertTrue(confirmed.toString(), confirmed.isSuccess)
        assertEquals(RfModuleMode.OFF, rig.radio.getSystemConfig()!!.rfModuleMode)
        assertEquals(listOf(RadioWidePatch(rfModuleMode = RfModuleMode.OFF.sdk)), rig.models.radioWidePatches.toList())
    }

    @Test
    fun theConfirmationIsOnlyNeededForTheStepIntoOff() = runTest {
        val rig = scriptedRig()
        assertTrue(rig.session.updateSystemConfig(rfOffConfirmed = true) { it.copy(rfModuleMode = RfModuleMode.OFF) }.isSuccess)
        // RF is off already: other changes and turning it back on need no confirmation.
        assertTrue(rig.session.updateSystemConfig { it.copy(alarmVoltageDeciVolt = 50) }.isSuccess)
        assertTrue(rig.session.updateSystemConfig { it.copy(rfModuleMode = RfModuleMode.INTERNAL) }.isSuccess)
    }

    @Test
    fun nothingIsWrittenWhenTheCurrentValuesCannotBeTrusted() = runTest {
        val rig = scriptedRig()

        scripted.reported = null
        val unavailable = rig.session.updateSystemConfig { it.copy(alarmVoltageDeciVolt = 34) }
        assertEquals(SessionError.SYSTEM_CONFIG_UNAVAILABLE, unavailable.errorOrNull())

        // The kind of value the SDK getter returned on the AX12: not a plausible alarm voltage / baud code.
        scripted.reported = SystemConfig(alarmVoltageDeciVolt = 300, crsfBaudRate = 206, modelMatchId = 181)
        val implausible = rig.session.updateSystemConfig { it.copy(alarmVoltageDeciVolt = 34) }
        assertEquals(SessionError.SYSTEM_CONFIG_UNAVAILABLE, implausible.errorOrNull())

        assertTrue("nothing may be written", scripted.writes.isEmpty())
        assertEquals(0, rig.models.writtenCount)
        assertTrue(rig.models.radioWidePatches.isEmpty())
        assertFalse(rig.session.saveState.value is SaveState.Failed)
    }

    @Test
    fun implausibleNewValuesAreRefusedBeforeTheyReachTheRadio() = runTest {
        val rig = scriptedRig()
        val result = rig.session.updateSystemConfig { it.copy(alarmVoltageDeciVolt = 256) }
        assertEquals(SessionError.SYSTEM_CONFIG_NOT_APPLIED, result.errorOrNull())
        assertTrue(scripted.writes.isEmpty())
    }

    @Test
    fun aWriteThatFailedHalfwayIsReadBackFromTheDeviceInsteadOfStayingInTheWorkingConfig() = runTest {
        val rig = scriptedRig()
        val before = rig.radio.getSystemConfig()!!.alarmVoltageDeciVolt
        scripted.failAfterApplying = true

        val result = rig.session.updateSystemConfig { it.copy(alarmVoltageDeciVolt = 34) }

        assertEquals(SessionError.SYSTEM_CONFIG_NOT_APPLIED, result.errorOrNull())
        assertEquals("the half-applied change is gone", before, rig.radio.getSystemConfig()!!.alarmVoltageDeciVolt)
        assertFalse(rig.session.dirty.value)
        assertTrue(rig.session.saveState.value is SaveState.Failed)
        assertTrue(rig.models.radioWidePatches.isEmpty())
    }

    @Test
    fun aChangeTheRadioDoesNotShowAfterTheWriteIsNotReportedAsDone() = runTest {
        val rig = scriptedRig()
        scripted.applyWrites = false

        val result = rig.session.updateSystemConfig { it.copy(alarmVoltageDeciVolt = 34) }

        assertEquals(SessionError.SYSTEM_CONFIG_NOT_APPLIED, result.errorOrNull())
        assertTrue(rig.session.saveState.value is SaveState.Failed)
        assertTrue("the library must not learn about a change that did not happen", rig.models.radioWidePatches.isEmpty())
    }

    @Test
    fun restoreDefaultsDropsChangesAndClearsTheGeneratedItems() = runTest {
        val rig = rig()
        rig.models.setExtras(
            rig.models.currentExtras.value.copy(
                wing = WingConfig(enabled = true),
                flightModes = draft,
                managed = ManagedItems(mixes = listOf(ManagedMix(0, 0, ManagedItems.GENERATOR_WING))),
            ),
        )
        rig.setFirstMixWeight(rig.firstMixWeight() - 4)

        val result = rig.session.resetToDefaults()

        assertTrue(result.toString(), result.isSuccess)
        assertFalse(rig.session.dirty.value)
        val extras = rig.models.currentExtras.value
        assertTrue(extras.managed.isEmpty)
        assertFalse(extras.wing.enabled)
        assertFalse(extras.flightModes.enabled)
        assertNotNull(rig.session.workingConfig.value.channels.firstOrNull())
    }
}
