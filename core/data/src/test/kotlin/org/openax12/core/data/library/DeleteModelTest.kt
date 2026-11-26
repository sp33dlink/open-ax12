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
import org.junit.Assert.assertNotEquals
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
import org.openax12.core.model.ModelBlueprint

/**
 * Deleting models: the current model is protected, every other model can be deleted — also when
 * several library models stand on one SDK file (the SDK names new model files after the current
 * second, so models created within one second used to land on one file; the guard then mistook
 * every one of them for the current model).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteModelTest {

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

    @Test
    fun `a model that is not the current one can be deleted`() = runTest {
        val repository = repository(this)
        radio.loadModelFile(radio.addModel("Active").filePath)
        assertTrue(repository.reconcileLibrary() is DataResult.Success)
        val other = entry(repository.createModel("Other", ModelBlueprint()))
        assertEquals("Active", repository.models.value.single { it.isCurrent }.name)

        val result = repository.deleteModel(other.id)

        assertEquals(DataResult.Success(Unit), result)
        assertEquals(listOf("Active"), repository.models.value.map { it.name })
        assertEquals(1, radio.getModels().size)
    }

    @Test
    fun `the current model is refused`() = runTest {
        val repository = repository(this)
        radio.loadModelFile(radio.addModel("Active").filePath)
        repository.reconcileLibrary()
        val id = repository.models.value.single().id

        val result = repository.deleteModel(id)

        assertEquals(DataResult.Failure(DataError.IS_CURRENT_MODEL, "Active"), result)
        assertEquals(1, radio.getModels().size)
    }

    @Test
    fun `models created within one second get separate SDK files`() = runTest {
        val repository = repository(this)
        radio.loadModelFile(radio.addModel("Active").filePath)
        repository.reconcileLibrary()
        radio.collidingCreates = 1

        val one = entry(repository.createModel("One", ModelBlueprint()))
        radio.collidingCreates = 1
        val two = entry(repository.createModel("Two", ModelBlueprint()))

        assertNotEquals(one.sdk.filePath, two.sdk.filePath)
        assertEquals(setOf("Active", "One", "Two"), radio.getModels().map { it.name }.toSet())
    }

    @Test
    fun `an entry that shares its SDK file with the current model can be deleted`() = runTest {
        // The state the radio was left in by models created within one second: three library
        // entries on one SDK file, the first of them current. Deleting "b" used to fail with
        // IS_CURRENT_MODEL because the SDK's current file equals the file of "b".
        val shared = radio.addModel("Model-302")
        radio.loadModelFile(shared.filePath)
        val entries = listOf("a", "b", "c").map { id ->
            SyncEntry(id = id, name = "Model-$id", folder = "Model-$id", sdkFile = shared.filePath)
        }
        assertTrue(SyncIndexStore(File(privateDir, "library_index.json")).save(SyncIndex(entries = entries, currentId = "a")))
        val repository = repository(this)

        val result = repository.deleteModel("b")

        assertEquals(DataResult.Success(Unit), result)
        assertTrue("the SDK file of the current model must survive", File(shared.filePath).isFile)
        assertFalse(repository.models.value.any { it.id == "b" })
        assertEquals(DataResult.Failure(DataError.IS_CURRENT_MODEL, "Model-a"), repository.deleteModel("a"))
    }
}
