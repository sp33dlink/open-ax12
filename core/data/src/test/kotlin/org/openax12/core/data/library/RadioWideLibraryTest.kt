package org.openax12.core.data.library

import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openax12.core.data.DataResult
import org.openax12.core.data.PictureCropRect
import org.openax12.core.data.StorageInfo
import org.openax12.core.data.StubRadio
import org.openax12.core.data.storage.StorageLocator
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.ModelConfigFile
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.RadioWidePatch
import org.openax12.core.model.RawSystemFields
import org.openax12.core.radio.getCurrentModel
import java.io.ByteArrayInputStream

/**
 * Stick mode, RF module and alarm voltage are radio-wide: a change is written into every model of
 * the library, and a model that disagrees with the radio is corrected before the radio sees it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioWideLibraryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Rig(
        val radio: StubRadio,
        val repo: DefaultModelRepository,
        val library: File,
        val a: ModelInfo,
        val b: ModelInfo,
        val c: ModelInfo,
    ) {
        fun libraryCopy(name: String): RawSystemFields? =
            ModelConfigFile.readSystem(File(library, "models/$name/model.json").readText())
    }

    private class FixedLocator(private val root: File) : StorageLocator {
        override fun resolve(): StorageInfo = StorageInfo(
            libraryRoot = root.absolutePath,
            modelsDir = File(root, "models").absolutePath,
            soundsDir = File(root, "sounds").absolutePath,
            logsDir = File(root, "logs").absolutePath,
        )
    }

    private class NoUris : UriAccess {
        override val shareDir: File = File("share")
        override fun openInput(uri: Uri): InputStream? = null
        override fun openOutput(uri: Uri): OutputStream? = null
        override fun displayName(uri: Uri): String? = null
        override fun shareUri(file: File): Uri? = null
    }

    private class NoPictures : PictureProcessor {
        override fun process(open: () -> InputStream?, crop: PictureCropRect, target: File): Boolean = false
        override fun isImage(bytes: ByteArray): Boolean = false
    }

    /**
     * Three models. A is the current one and holds the radio's values (alarm 3.3 V, RF internal);
     * B and C carry a distinct `modelMatchId` so that "stays per model" can be checked.
     */
    private suspend fun TestScope.rig(
        b: (StubRadio) -> ModelInfo = { it.addModel("B", modelMatchId = 5) },
    ): Rig {
        val radio = StubRadio(temp.newFolder("sdk"))
        val a = radio.addModel("A", modelMatchId = 1)
        val modelB = b(radio)
        val c = radio.addModel("C", modelMatchId = 9)
        radio.loadModelFile(a.filePath)
        val library = temp.newFolder("library")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultModelRepository(
            radio = radio,
            locator = FixedLocator(library),
            privateDir = temp.newFolder("private"),
            uriAccess = NoUris(),
            pictures = NoPictures(),
            scope = backgroundScope,
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
        )
        assertTrue(repo.reconcileLibrary() is DataResult.Success)
        return Rig(radio, repo, library, a, modelB, c)
    }

    private fun Rig.id(name: String): String = repo.models.value.first { it.name == name }.id

    @Test
    fun aChangedAlarmVoltageReachesEveryOtherModelAndItsLibraryCopy() = runTest {
        val rig = rig()
        // The radio path changed the current model (A) already.
        assertTrue(rig.radio.setSystemConfig(rig.radio.getSystemConfig()!!.copy(alarmVoltageDeciVolt = 34)))

        val result = rig.repo.applyRadioWideSettings(RadioWidePatch(alarmVoltageDeciVolt = 34))

        val report = (result as DataResult.Success).value
        assertEquals(setOf("B", "C"), report.updated.toSet())
        assertEquals(listOf("A"), report.unchanged)
        assertTrue(report.failed.isEmpty())
        for ((model, match) in listOf(rig.b to 5, rig.c to 9)) {
            val sdk = rig.radio.systemOf(model.filePath)!!
            assertEquals(34, sdk.alarmVoltageDeciVolt)
            assertEquals("modelMatchId stays per model", match, sdk.modelMatchId)
            assertEquals(0, sdk.rfModuleMode)
            assertEquals(34, rig.libraryCopy(model.name)!!.alarmVoltageDeciVolt)
        }
        // The current model is the radio's business, the library never touched its SDK file.
        assertEquals(34, rig.radio.systemOf(rig.a.filePath)!!.alarmVoltageDeciVolt)
        assertEquals(1, rig.radio.systemOf(rig.a.filePath)!!.modelMatchId)
    }

    @Test
    fun onlyTheFieldsOfThePatchAreWritten() = runTest {
        val rig = rig { it.addModel("B", modelMatchId = 5, rf = 1, alarm = 70) }
        rig.repo.applyRadioWideSettings(RadioWidePatch(rfModuleMode = 2))
        val b = rig.radio.systemOf(rig.b.filePath)!!
        assertEquals(2, b.rfModuleMode)
        assertEquals("alarm voltage untouched", 70, b.alarmVoltageDeciVolt)
        assertEquals(1, b.stickMode)
    }

    @Test
    fun modelsThatAlreadyHaveTheValuesAreNotRewritten() = runTest {
        val rig = rig()
        val before = rig.radio.importCalls
        val report = (rig.repo.applyRadioWideSettings(RadioWidePatch(alarmVoltageDeciVolt = 33)) as DataResult.Success).value
        assertTrue(report.updated.isEmpty())
        assertEquals(before, rig.radio.importCalls)
    }

    @Test
    fun implausiblePatchesAreRefused() = runTest {
        val rig = rig()
        assertTrue(rig.repo.applyRadioWideSettings(RadioWidePatch(alarmVoltageDeciVolt = 999)) is DataResult.Failure)
        assertTrue(rig.repo.applyRadioWideSettings(RadioWidePatch(rfModuleMode = 7)) is DataResult.Failure)
        assertEquals(33, rig.radio.systemOf(rig.b.filePath)!!.alarmVoltageDeciVolt)
    }

    @Test
    fun aFolderThatWasChangedOutsideTheAppIsLeftForTheUserToDecide() = runTest {
        val rig = rig()
        val file = File(rig.library, "models/B/model.json")
        file.writeText(file.readText() + " ")
        rig.repo.applyRadioWideSettings(RadioWidePatch(alarmVoltageDeciVolt = 40))
        assertEquals(40, rig.radio.systemOf(rig.b.filePath)!!.alarmVoltageDeciVolt)
        assertEquals("the externally modified copy is not overwritten", 33, rig.libraryCopy("B")!!.alarmVoltageDeciVolt)
        assertEquals(40, rig.libraryCopy("C")!!.alarmVoltageDeciVolt)
    }

    @Test
    fun aBrokenModelFileIsReportedAndTheOthersAreStillUpdated() = runTest {
        val rig = rig()
        File(rig.b.filePath).writeText("""{"modelName":"B","modelMatch":0,"config":"x"}""")
        val report = (rig.repo.applyRadioWideSettings(RadioWidePatch(alarmVoltageDeciVolt = 40)) as DataResult.Success).value
        assertEquals(listOf("C"), report.updated)
        assertEquals(1, report.failed.size)
        assertTrue(report.failed.single().startsWith("B:"))
    }

    // -- apply on load -------------------------------------------------------------------------

    @Test
    fun selectingAModelWithOtherRadioValuesAppliesTheRadiosValuesFirst() = runTest {
        // B was not in the library when the radio's RF mode and alarm voltage were changed.
        val rig = rig { it.addModel("B", modelMatchId = 5, rf = 1, alarm = 70) }

        val result = rig.repo.selectModel(rig.id("B"))

        assertTrue(result is DataResult.Success)
        assertEquals(rig.b.filePath, rig.radio.getCurrentModel()?.filePath)
        val b = rig.radio.systemOf(rig.b.filePath)!!
        assertEquals("RF follows the radio", 0, b.rfModuleMode)
        assertEquals("alarm follows the radio", 33, b.alarmVoltageDeciVolt)
        assertEquals("modelMatchId stays per model", 5, b.modelMatchId)
        // The radio now reports the values it had before, whichever model it flies.
        assertEquals(33, rig.radio.getSystemConfig()!!.alarmVoltageDeciVolt)
        assertEquals(33, rig.libraryCopy("B")!!.alarmVoltageDeciVolt)
        assertEquals(0, rig.libraryCopy("B")!!.rfModuleMode)
    }

    @Test
    fun selectingTurnsRfOffOnlyWhenTheRadioHasItOff() = runTest {
        val rig = rig()
        // The radio's RF module is off; B says internal.
        rig.radio.setSystemConfig(rig.radio.getSystemConfig()!!.copy(rfModuleMode = org.openax12.core.model.RfModuleMode.OFF))

        rig.repo.selectModel(rig.id("B"))

        assertEquals(2, rig.radio.systemOf(rig.b.filePath)!!.rfModuleMode)
        assertEquals(org.openax12.core.model.RfModuleMode.OFF, rig.radio.getSystemConfig()!!.rfModuleMode)
    }

    @Test
    fun aModelThatAlreadyMatchesIsLoadedWithoutRewritingIt() = runTest {
        val rig = rig()
        val before = rig.radio.importCalls
        assertTrue(rig.repo.selectModel(rig.id("B")) is DataResult.Success)
        assertEquals(before, rig.radio.importCalls)
    }

    @Test
    fun noTrustworthyRadioValuesMeansNothingIsApplied() = runTest {
        val rig = rig { it.addModel("B", modelMatchId = 5, rf = 1, alarm = 70) }
        // The current model's file is damaged: the radio cannot say what its settings are.
        File(rig.a.filePath).writeText("""{"modelName":"A","modelMatch":0,"config":"x"}""")
        assertEquals(null, rig.radio.getSystemConfig())

        assertTrue(rig.repo.selectModel(rig.id("B")) is DataResult.Success)

        val b = rig.radio.systemOf(rig.b.filePath)!!
        assertEquals("B is loaded as it is", 70, b.alarmVoltageDeciVolt)
        assertEquals(1, b.rfModuleMode)
    }

    @Test
    fun anImportedModelTakesOverTheRadiosValues() = runTest {
        val rig = rig()
        val text = rig.radio.modelFileText("Imported", rf = 1, alarm = 70, modelMatchId = 3)

        val result = rig.repo.importModel({ ByteArrayInputStream(text.toByteArray()) }, "Imported.json", null)

        val entry = (result as DataResult.Success).value
        val sdk = rig.radio.systemOf(entry.sdk.filePath)!!
        assertEquals(0, sdk.rfModuleMode)
        assertEquals(33, sdk.alarmVoltageDeciVolt)
        assertEquals(3, sdk.modelMatchId)
        assertEquals(33, rig.libraryCopy("Imported")!!.alarmVoltageDeciVolt)
        assertNotNull(rig.repo.models.value.firstOrNull { it.name == "Imported" })
        assertFalse(rig.repo.models.value.first { it.name == "Imported" }.isCurrent)
    }
}
