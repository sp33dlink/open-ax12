package org.openax12.core.data.library

import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openax12.core.data.DataError
import org.openax12.core.data.DataResult
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.PictureCropRect
import org.openax12.core.data.StorageInfo
import org.openax12.core.data.StubRadio
import org.openax12.core.data.storage.StorageLocator
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.ModelFileCheck
import org.openax12.core.radio.TaskResult
import org.openax12.core.radio.readWorkingConfig

/**
 * `rcLoadModelFile` says "loaded" for a model file without channel data and leaves an empty
 * working config (assumption r11); a save would wipe the radio. The repository must refuse such a
 * file before the SDK sees it, notice an incomplete load afterwards, and read the radio's own
 * configuration back instead of leaving the empty one in place. Nothing may reach the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncompleteLoadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sdkDir by lazy { tmp.newFolder("sdk") }
    private val privateDir by lazy { tmp.newFolder("private") }
    private val library by lazy { tmp.newFolder("library") }
    private val radio by lazy { StubRadio(sdkDir) }

    private fun repository(scope: TestScope): DefaultModelRepository {
        val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        return DefaultModelRepository(
            radio = radio,
            locator = object : StorageLocator {
                override fun resolve() = StorageInfo(
                    libraryRoot = library.path,
                    modelsDir = File(library, "models").path,
                    soundsDir = File(library, "sounds").path,
                    logsDir = File(library, "logs").path,
                )
            },
            privateDir = privateDir,
            uriAccess = object : UriAccess {
                override val shareDir: File = tmp.newFolder("share")
                override fun openInput(uri: Uri): InputStream? = null
                override fun openOutput(uri: Uri): OutputStream? = null
                override fun displayName(uri: Uri): String? = null
                override fun shareUri(file: File): Uri? = null
            },
            pictures = object : PictureProcessor {
                override fun process(open: () -> InputStream?, crop: PictureCropRect, target: File) = false
                override fun isImage(bytes: ByteArray) = false
            },
            scope = scope.backgroundScope,
            dispatchers = AppDispatchers(main = dispatcher, default = dispatcher, io = dispatcher),
        )
    }

    private fun entry(result: DataResult<ModelEntry>): ModelEntry =
        (result as? DataResult.Success)?.value ?: error("unexpected $result")

    private fun DataResult<*>.error(): DataError? = (this as? DataResult.Failure)?.error

    /** A radio flying "Active" (complete SDK file, loaded, written) and a library with "Other". */
    private suspend fun TestScope.flyingActiveWithOther(): Triple<DefaultModelRepository, ModelEntry, ModelEntry> {
        val repository = repository(this)
        val active = radio.addModel("Active", sdkChannels = true)
        radio.loadModelFile(active.filePath)
        radio.saveConfigToDevice()
        assertTrue(repository.reconcileLibrary() is DataResult.Success)
        val other = entry(repository.createModel("Other", org.openax12.core.model.ModelBlueprint()))
        return Triple(repository, repository.models.value.single { it.name == "Active" }, other)
    }

    @Test
    fun `a complete SDK model loads and passes the check`() = runTest {
        val (repository, active, other) = flyingActiveWithOther()

        val result = repository.selectModel(other.id)
        val back = repository.selectModel(active.id)

        assertTrue(result.toString(), result is DataResult.Success)
        assertTrue(back.toString(), back is DataResult.Success)
        assertEquals("Active", repository.currentModel.value?.name)
        assertNull(ModelFileCheck.workingProblem(radio.readWorkingConfig()))
    }

    @Test
    fun `a file without channel data is refused before the SDK loads it`() = runTest {
        val (repository, active, _) = flyingActiveWithOther()
        val broken = radio.addSdkModel("Broken", withChannels = false)
        repository.reconcileLibrary()
        val brokenId = repository.models.value.single { it.name == "Broken" }.id
        val mixesBefore = radio.workingMixCount
        val writesBefore = radio.deviceWrites
        val readsBefore = radio.deviceReads

        val result = repository.selectModel(brokenId)

        assertEquals(DataError.CONFIG_INCOMPLETE, result.error())
        // The SDK was never touched: same current file, same working config, no device traffic.
        assertEquals(active.sdk.filePath, radio.currentFile)
        assertEquals(mixesBefore, radio.workingMixCount)
        assertEquals(writesBefore, radio.deviceWrites)
        assertEquals(readsBefore, radio.deviceReads)
        assertEquals("Active", repository.currentModel.value?.name)
        assertTrue(File(broken.filePath).isFile)
    }

    @Test
    fun `an incomplete load is detected and the previous model is loaded again`() = runTest {
        val (repository, active, other) = flyingActiveWithOther()
        val deviceMixes = radio.deviceChannels.sumOf { it.mixes.size }
        assertTrue(deviceMixes > 0)
        val writesBefore = radio.deviceWrites
        radio.incompleteLoads = 1

        val result = repository.selectModel(other.id)

        assertEquals(DataError.CONFIG_INCOMPLETE, result.error())
        assertEquals("nothing was written to the radio", writesBefore, radio.deviceWrites)
        // The SDK does not answer a device read while a load is outstanding: the way back is the
        // previous model's file. The working config is the device's again.
        assertEquals(deviceMixes, radio.workingMixCount)
        assertNull(ModelFileCheck.workingProblem(radio.readWorkingConfig()))
        assertEquals(active.sdk.filePath, radio.currentFile)
        assertEquals("Active", repository.currentModel.value?.name)
        // The SDK still has a load outstanding, so the library says "not confirmed on the device".
        assertFalse(repository.deviceInSync.value)
        // The failed model's own file is untouched.
        assertNotNull(ModelFileCheck.summarize(File(other.sdk.filePath).readText()))
        // Writing now is possible and writes the verified previous configuration.
        assertEquals(TaskResult.COMPLETED, radio.saveConfigToDevice())
        assertEquals(deviceMixes, radio.deviceChannels.sumOf { it.mixes.size })
    }

    @Test
    fun `an incomplete reload of the current model never reaches the device`() = runTest {
        val (repository, _, _) = flyingActiveWithOther()
        val writesBefore = radio.deviceWrites
        radio.incompleteLoads = 1

        val result = repository.reloadCurrentModel()

        assertEquals(DataError.CONFIG_INCOMPLETE, result.error())
        assertEquals(writesBefore, radio.deviceWrites)
        assertFalse(repository.deviceInSync.value)
        // The empty working config is still in place, but the radio refuses to write it.
        assertEquals(TaskResult.REFUSED, radio.saveConfigToDevice())
        assertEquals(writesBefore, radio.deviceWrites)
    }

    @Test
    fun `the radio refuses to save an empty working config`() = runTest {
        val (_, active, _) = flyingActiveWithOther()
        radio.incompleteLoads = 1
        radio.loadModelFile(active.sdk.filePath)
        assertEquals(0, radio.workingMixCount)
        val writes = radio.deviceWrites

        assertEquals(TaskResult.REFUSED, radio.saveConfigToDevice())

        assertEquals(writes, radio.deviceWrites)
        assertFalse(radio.deviceChannels.all { it.mixes.isEmpty() })
    }

    @Test
    fun `an import of a file without channel data is rejected`() = runTest {
        val repository = repository(this)
        val bytes = """{"MODEL_FILE_CFG":{"modelName":"x"},"RC_MODEL_CFG":{}}""".toByteArray()

        assertNotNull(ModelArchive.modelFileProblem(bytes))
        assertEquals(null, ModelArchive.modelFileProblem("""{"modelName":"plain","config":"c"}""".toByteArray()))
        assertTrue(repository.models.value.isEmpty())
    }
}
