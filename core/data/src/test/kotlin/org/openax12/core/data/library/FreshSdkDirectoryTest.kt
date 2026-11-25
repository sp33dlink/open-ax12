package org.openax12.core.data.library

import org.openax12.core.model.ModelExtras
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

/**
 * The state after the app's data was cleared (or a fresh install on a radio whose public library
 * exists): the SDK directory is empty and the SDK creates a "Model-<number>" file from the
 * radio's MCU. That file is the configuration of a library model and must not become another
 * library entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreshSdkDirectoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sdkDir by lazy { tmp.newFolder("sdk") }
    private val privateDir by lazy { tmp.newFolder("private") }
    private val library by lazy { tmp.newFolder("library") }
    private val shared by lazy { tmp.newFolder("share") }
    private val modelsDir by lazy { File(library, "models").also { it.mkdirs() } }
    private val radio by lazy { StubRadio(sdkDir) }

    private fun repository(scope: TestScope): DefaultModelRepository {
        val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        return DefaultModelRepository(
            radio = radio,
            locator = object : StorageLocator {
                override fun resolve() = StorageInfo(
                    libraryRoot = library.path,
                    modelsDir = modelsDir.path,
                    soundsDir = File(library, "sounds").path,
                    logsDir = File(library, "logs").path,
                )
            },
            privateDir = privateDir,
            uriAccess = object : UriAccess {
                override val shareDir: File = shared
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
            importedModelName = { date -> "Imported $date" },
        )
    }

    /** A library folder as the app wrote it earlier; the model file carries its own stamp like a real one. */
    private fun libraryModel(folder: String, config: String, stamp: String = "old", extras: Boolean = false, snapshots: Boolean = false) {
        val dir = File(modelsDir, folder).apply { mkdirs() }
        File(dir, "model.json").writeText("""{"modelName":"$stamp","modelMatch":0,"config":"$config"}""")
        if (extras) File(dir, "extras.json").writeText("""{"schemaVersion":${ModelExtras.CURRENT_SCHEMA_VERSION},"modelType":"WING"}""")
        if (snapshots) File(dir, "snapshots").mkdirs()
    }

    private fun libraryFolders(): List<String> = modelsDir.list().orEmpty().sorted()

    /** What the SDK creates on a fresh directory: a "Model-<number>" file made from the MCU's configuration. */
    private suspend fun freshSdkModel(config: String, name: String = "Model-233") {
        radio.loadModelFile(radio.addModel(name, config = config).filePath)
    }

    @Test
    fun `the radio's model is linked to the matching library model and adds no entry`() = runTest {
        libraryModel("Glider", "config G", extras = true)
        libraryModel("Other", "config O")
        freshSdkModel("config G")

        val result = repository(this).reconcileLibrary()

        assertTrue(result is DataResult.Success)
        assertEquals(listOf("Glider", "Other"), libraryFolders())
        val repository = repository(this)
        repository.reconcileLibrary()
        assertEquals(setOf("Glider", "Other"), repository.models.value.map { it.name }.toSet())
        assertEquals("Glider", repository.models.value.single { it.isCurrent }.name)
        assertTrue(repository.externalChanges.value.isEmpty())
        // The SDK model took over the library name; nothing is left called Model-233.
        assertFalse(radio.getModels().any { it.name == "Model-233" })
        assertEquals(setOf("Glider", "Other"), radio.getModels().map { it.name }.toSet())
    }

    @Test
    fun `the library keeps the name, extras and snapshots of the matched model`() = runTest {
        libraryModel("Glider", "config G", extras = true, snapshots = true)
        val extrasBefore = File(modelsDir, "Glider/extras.json").readText()
        freshSdkModel("config G")
        val repository = repository(this)

        repository.reconcileLibrary()

        val glider = repository.models.value.single { it.name == "Glider" }
        assertTrue(glider.isCurrent)
        assertTrue(glider.hasExtras)
        assertEquals(extrasBefore, File(modelsDir, "Glider/extras.json").readText())
        assertTrue(File(modelsDir, "Glider/snapshots").isDirectory)
        assertEquals(listOf("Glider"), libraryFolders())
    }

    @Test
    fun `of several identical library models the one with the most user data is linked`() = runTest {
        libraryModel("Model-61", "config G")
        libraryModel("Model-61 (2)", "config G", extras = true, snapshots = true)
        libraryModel("Model-477", "config G", extras = true)
        freshSdkModel("config G")
        val repository = repository(this)

        repository.reconcileLibrary()

        assertEquals("Model-61 (2)", repository.models.value.single { it.isCurrent }.name)
        assertEquals(listOf("Model-477", "Model-61", "Model-61 (2)"), libraryFolders())
        assertEquals(3, repository.models.value.size)
    }

    @Test
    fun `the SDK's stamps are ignored when comparing model files`() {
        // Name, model index, creation time and key order differ; the configuration is the same.
        val library = File(tmp.newFolder("cmp"), "a.json").apply {
            writeText("""{"MODEL_FILE_CFG":{"modelIdx":65,"modelName":"Model-61","timeStamp":1795643881,"modelImg":"a"},"RC_MODEL_CFG":{"b":1,"a":[1,2]}}""")
        }
        val sdk = File(library.parentFile, "b.json").apply {
            writeText("""{"RC_MODEL_CFG":{"a":[1,2],"b":1},"MODEL_FILE_CFG":{"timeStamp":1795644894,"modelName":"Model-302","modelImg":"a","modelIdx":1795644894}}""")
        }
        val changed = File(library.parentFile, "c.json").apply {
            writeText("""{"RC_MODEL_CFG":{"a":[1,3],"b":1},"MODEL_FILE_CFG":{"timeStamp":1,"modelName":"x","modelImg":"a","modelIdx":2}}""")
        }

        assertEquals(ModelContent.fingerprint(library), ModelContent.fingerprint(sdk))
        assertFalse(ModelContent.fingerprint(library) == ModelContent.fingerprint(changed))
        assertEquals(null, ModelContent.fingerprint(File(library.parentFile, "missing.json")))
    }

    @Test
    fun `a radio model that matches nothing gets one readable entry`() = runTest {
        libraryModel("Glider", "config G")
        freshSdkModel("config Z")
        val repository = repository(this)

        repository.reconcileLibrary()

        val names = repository.models.value.map { it.name }.sorted()
        assertEquals(2, names.size)
        assertEquals("Glider", names[0])
        assertTrue(names[1], names[1].startsWith("Imported "))
        assertFalse(names.any { it.startsWith("Model-") })
        assertEquals(names[1], repository.models.value.single { it.isCurrent }.name)
        assertEquals(setOf("Glider", names[1]), libraryFolders().toSet())
        assertEquals(setOf("Glider", names[1]), radio.getModels().map { it.name }.toSet())
    }

    @Test
    fun `a first start without any library gets one readable entry`() = runTest {
        freshSdkModel("config Z")
        val repository = repository(this)

        repository.reconcileLibrary()

        val entry = repository.models.value.single()
        assertTrue(entry.name, entry.name.startsWith("Imported "))
        assertTrue(entry.isCurrent)
        assertEquals(listOf(entry.name), libraryFolders())
    }

    @Test
    fun `repeated starts and a second data clear add nothing`() = runTest {
        libraryModel("Glider", "config G", extras = true)
        libraryModel("Other", "config O")
        freshSdkModel("config G")
        repository(this).reconcileLibrary()
        val folders = libraryFolders()

        // Normal restarts: a new repository instance on the same private directory.
        repeat(2) {
            val repository = repository(this)
            repository.reconcileLibrary()
            repository.reconcileLibrary()
            assertEquals(folders, libraryFolders())
            assertEquals(setOf("Glider", "Other"), repository.models.value.map { it.name }.toSet())
            assertEquals("Glider", repository.models.value.single { it.isCurrent }.name)
            assertEquals(2, radio.getModels().size)
        }

        // Data clear: the private directory and the SDK files are gone, the library stays.
        privateDir.deleteRecursively()
        privateDir.mkdirs()
        sdkDir.listFiles().orEmpty().forEach { it.delete() }
        radio.currentFile = null
        freshSdkModel("config G", name = "Model-302")
        val repository = repository(this)
        repository.reconcileLibrary()
        assertEquals(folders, libraryFolders())
        assertEquals(setOf("Glider", "Other"), repository.models.value.map { it.name }.toSet())
        assertEquals("Glider", repository.models.value.single { it.isCurrent }.name)
    }

    @Test
    fun `a model made later by the stock app is not mistaken for a library model`() = runTest {
        libraryModel("Glider", "config G")
        freshSdkModel("config G")
        val repository = repository(this)
        repository.reconcileLibrary()

        // An unknown SDK file that happens to hold the same configuration: normal handling, own entry.
        radio.addModel("Model-999", config = "config G")
        repository.reconcileLibrary()

        assertEquals(setOf("Glider", "Model-999"), repository.models.value.map { it.name }.toSet())
    }
}
