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
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.ManagedMix
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.ModelType
import org.openax12.core.model.SourceId
import org.openax12.core.model.StickMode
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.WingConfig
import org.openax12.core.radio.SimulatedFault
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

    private suspend fun TestScope.rig(startRadio: Boolean = true): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val radio = FakeRadio(
            scope = backgroundScope,
            directory = null,
            dispatcher = dispatcher,
            clock = { testScheduler.currentTime },
            options = deterministic,
        )
        val models = FakeModels(radio)
        val diagnostics = DefaultDiagnosticsLog()
        val session = DefaultConfigSession(
            radio = radio,
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
