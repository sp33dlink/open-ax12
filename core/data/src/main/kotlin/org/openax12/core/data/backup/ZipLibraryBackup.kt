package org.openax12.core.data.backup

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.openax12.core.data.BackupInfo
import org.openax12.core.data.DataError
import org.openax12.core.data.DataResult
import org.openax12.core.data.LibraryBackup
import org.openax12.core.data.RestoreMode
import org.openax12.core.data.RestoreReport
import org.openax12.core.data.SettingsRepository
import org.openax12.core.data.archive.ArchiveProblem
import org.openax12.core.data.archive.ArchiveRejectedException
import org.openax12.core.data.archive.ScanResult
import org.openax12.core.data.archive.ZipLimits
import org.openax12.core.data.archive.ZipScanner
import org.openax12.core.data.library.DefaultModelRepository
import org.openax12.core.data.library.FolderRestore
import org.openax12.core.data.library.ModelNames
import org.openax12.core.data.library.SnapshotStore
import org.openax12.core.data.library.UriAccess
import org.openax12.core.data.settings.AppSettingsCodec
import org.openax12.core.data.storage.FileOps
import org.openax12.core.data.storage.StorageDecision
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.Ax12Json

/** `manifest.json` of a library backup. */
@Serializable
internal data class BackupManifest(
    val format: String = BackupArchive.FORMAT,
    val formatVersion: Int = BackupArchive.FORMAT_VERSION,
    val createdMs: Long = 0,
    val appVersion: String = "",
    val models: List<String> = emptyList(),
    val soundFiles: Int = 0,
    val includesSettings: Boolean = false,
)

/**
 * The backup zip: `manifest.json`, `models/<folder>/…` (model file, extras, picture, snapshots),
 * `sounds/…` and optionally `settings.json`. Pure `java.*`.
 */
internal object BackupArchive {

    const val FORMAT: String = "oax12-backup"
    const val FORMAT_VERSION: Int = 1

    const val MANIFEST_FILE: String = "manifest.json"
    const val SETTINGS_FILE: String = "settings.json"
    const val MODELS_PREFIX: String = StorageDecision.MODELS_DIR + "/"
    const val SOUNDS_PREFIX: String = StorageDecision.SOUNDS_DIR + "/"

    /** Limits of a backup: many files (snapshots, sounds), but bounded. */
    val LIMITS: ZipLimits = ZipLimits(maxEntries = 20_000, maxEntryBytes = 128L * 1024 * 1024, maxTotalBytes = 2L * 1024 * 1024 * 1024)

    private const val MAX_SMALL_FILE_BYTES = 1024 * 1024

    /** Everything [inspect] learned about an archive. */
    data class Inspection(val manifest: BackupManifest, val modelNames: List<String>, val soundFiles: Int, val hasSettings: Boolean, val totalBytes: Long)

    /** Result of [inspect]. */
    sealed interface InspectResult {
        data class Ok(val inspection: Inspection) : InspectResult
        data class Rejected(val error: DataError, val detail: String) : InspectResult
    }

    /**
     * Writes a backup. The stream is finished but not closed.
     *
     * @param modelFolders the model folders to include, each under `models/<folder name>/`
     * @param soundsDir the user sound folder, or null
     * @param settingsJson encoded app settings, or null to leave them out
     */
    fun write(output: OutputStream, createdMs: Long, appVersion: String, modelFolders: List<File>, soundsDir: File?, settingsJson: String?) {
        val zip = ZipOutputStream(output)
        val sounds = soundsDir?.takeIf { it.isDirectory }?.let(::filesBelow).orEmpty()
        val manifest = BackupManifest(
            createdMs = createdMs,
            appVersion = appVersion,
            models = modelFolders.map { it.name },
            soundFiles = sounds.size,
            includesSettings = settingsJson != null,
        )
        zip.putNextEntry(ZipEntry(MANIFEST_FILE))
        zip.write(Ax12Json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        if (settingsJson != null) {
            zip.putNextEntry(ZipEntry(SETTINGS_FILE))
            zip.write(settingsJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        for (folder in modelFolders) {
            for ((relative, file) in filesBelow(folder)) putFile(zip, "$MODELS_PREFIX${folder.name}/$relative", file)
        }
        for ((relative, file) in sounds) putFile(zip, "$SOUNDS_PREFIX$relative", file)
        zip.finish()
        zip.flush()
    }

    /** Validates a backup under [LIMITS] without writing anything. */
    fun inspect(input: InputStream): InspectResult {
        var manifestBytes: ByteArray? = null
        val models = LinkedHashSet<String>()
        var sounds = 0
        var hasSettings = false
        val result = ZipScanner.scan(input, LIMITS) { path, content ->
            when {
                path == MANIFEST_FILE -> manifestBytes = readSmall(content)
                path == SETTINGS_FILE -> hasSettings = true
                path.startsWith(SOUNDS_PREFIX) -> sounds++
                path.startsWith(MODELS_PREFIX) -> {
                    val parts = path.removePrefix(MODELS_PREFIX).split('/')
                    if (parts.size == 2 && parts[1] == SnapshotStore.MODEL_FILE) models += parts[0]
                }
            }
        }
        if (result is ScanResult.Rejected) return InspectResult.Rejected(dataError(result.problem), "${result.problem}: ${result.detail}")
        val manifest = when (val checked = checkManifest(manifestBytes)) {
            is ManifestCheck.Ok -> checked.manifest
            is ManifestCheck.Bad -> return InspectResult.Rejected(checked.error, checked.detail)
        }
        return InspectResult.Ok(Inspection(manifest, models.toList(), sounds, hasSettings, (result as ScanResult.Ok).totalBytes))
    }

    /** Outcome of [checkManifest]. */
    sealed interface ManifestCheck {
        data class Ok(val manifest: BackupManifest) : ManifestCheck
        data class Bad(val error: DataError, val detail: String) : ManifestCheck
    }

    /** Parses and checks the manifest of a backup. */
    fun checkManifest(bytes: ByteArray?): ManifestCheck {
        if (bytes == null) return ManifestCheck.Bad(DataError.INVALID_FILE, "not a library backup: no $MANIFEST_FILE")
        val manifest = try {
            Ax12Json.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return ManifestCheck.Bad(DataError.INVALID_FILE, "$MANIFEST_FILE is unreadable")
        }
        if (manifest.format != FORMAT) return ManifestCheck.Bad(DataError.INVALID_FILE, "not a library backup")
        if (manifest.formatVersion > FORMAT_VERSION) return ManifestCheck.Bad(DataError.SCHEMA_TOO_NEW, "backup format ${manifest.formatVersion}")
        return ManifestCheck.Ok(manifest)
    }

    /** Which entries a restore extracts; everything else in the archive is ignored. */
    fun isRestorable(path: String): Boolean =
        path == MANIFEST_FILE || path == SETTINGS_FILE || path.startsWith(MODELS_PREFIX) || path.startsWith(SOUNDS_PREFIX)

    /** Maps a zip rejection to the error reported to the caller. */
    fun dataError(problem: ArchiveProblem): DataError = when (problem) {
        ArchiveProblem.TOO_MANY_ENTRIES, ArchiveProblem.ENTRY_TOO_LARGE, ArchiveProblem.TOTAL_TOO_LARGE -> DataError.TOO_LARGE
        ArchiveProblem.UNREADABLE -> DataError.IO
        ArchiveProblem.NOT_A_ZIP, ArchiveProblem.PATH_TRAVERSAL, ArchiveProblem.DUPLICATE_ENTRY -> DataError.INVALID_FILE
    }

    private fun readSmall(content: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = content.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_SMALL_FILE_BYTES) throw ArchiveRejectedException(ArchiveProblem.ENTRY_TOO_LARGE, MANIFEST_FILE)
        }
        return out.toByteArray()
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /** All files below [root] as `relative path → file`, sorted, without temporary and hidden files. */
    private fun filesBelow(root: File): List<Pair<String, File>> {
        val result = ArrayList<Pair<String, File>>()
        fun walk(dir: File, prefix: String) {
            for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (child.name.startsWith('.') || child.name.endsWith(FileOps.TEMP_SUFFIX)) continue
                // Names that would not pass the reader's own validation are left out.
                if (ZipScanner.safePath(child.name) != child.name) continue
                if (child.isDirectory) walk(child, "$prefix${child.name}/") else result += "$prefix${child.name}" to child
            }
        }
        walk(root, "")
        return result
    }
}

/**
 * [LibraryBackup] on top of the model library.
 *
 * @param stagingRoot a private directory (the app cache) in which a backup is unpacked and
 *   validated before anything reaches the library
 */
internal class ZipLibraryBackup(
    private val models: DefaultModelRepository,
    private val settings: SettingsRepository,
    private val uriAccess: UriAccess,
    private val stagingRoot: File,
    private val dispatchers: AppDispatchers,
    private val appVersion: String = "",
    private val wallClock: () -> Long = System::currentTimeMillis,
) : LibraryBackup {

    private val mutex = Mutex()

    override suspend fun backup(destination: Uri?, includeSettings: Boolean): DataResult<Uri> = exclusive {
        val file = try {
            val shareDir = FileOps.ensureDir(uriAccess.shareDir)
            shareDir.listFiles()?.forEach { if (it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX)) it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(wallClock()))
            File(shareDir, "$FILE_PREFIX$stamp$FILE_SUFFIX")
        } catch (e: IOException) {
            return@exclusive DataResult.Failure(DataError.IO, e.message.orEmpty())
        }
        when (val written = backupToFile(file, includeSettings)) {
            is DataResult.Failure -> return@exclusive written
            is DataResult.Success -> Unit
        }
        if (destination == null) {
            return@exclusive uriAccess.shareUri(file)?.let { DataResult.Success(it) }
                ?: DataResult.Failure(DataError.IO, "the backup cannot be shared")
        }
        try {
            val out = uriAccess.openOutput(destination) ?: return@exclusive DataResult.Failure(DataError.IO, "the destination cannot be opened")
            out.use { target -> file.inputStream().use { it.copyTo(target) } }
            DataResult.Success(destination)
        } catch (e: IOException) {
            DataResult.Failure(DataError.IO, e.message.orEmpty())
        } finally {
            file.delete()
        }
    }

    /** Writes a backup zip to [target]. */
    internal suspend fun backupToFile(target: File, includeSettings: Boolean): DataResult<File> = withContext(dispatchers.io) {
        val folders = models.modelFolders()
        val soundsDir = models.storage.value.soundsDir.takeIf { it.isNotEmpty() }?.let(::File)
        val settingsJson = if (includeSettings) AppSettingsCodec.encode(settings.awaitLoaded()) else null
        try {
            target.parentFile?.let(FileOps::ensureDir)
            target.outputStream().buffered().use { out ->
                BackupArchive.write(out, wallClock(), appVersion, folders, soundsDir, settingsJson)
            }
            DataResult.Success(target)
        } catch (e: IOException) {
            target.delete()
            DataResult.Failure(DataError.IO, e.message.orEmpty())
        }
    }

    override suspend fun inspect(source: Uri): DataResult<BackupInfo> = inspect { uriAccess.openInput(source) }

    /** [inspect] for a stream source. */
    internal suspend fun inspect(open: () -> InputStream?): DataResult<BackupInfo> = withContext(dispatchers.io) {
        val stream = open() ?: return@withContext DataResult.Failure(DataError.IO, "the backup cannot be opened")
        val result = try {
            stream.use { BackupArchive.inspect(it) }
        } catch (e: IOException) {
            return@withContext DataResult.Failure(DataError.IO, e.message.orEmpty())
        }
        when (result) {
            is BackupArchive.InspectResult.Rejected -> DataResult.Failure(result.error, result.detail)
            is BackupArchive.InspectResult.Ok -> {
                val inspection = result.inspection
                DataResult.Success(
                    BackupInfo(
                        createdMs = inspection.manifest.createdMs,
                        appVersion = inspection.manifest.appVersion,
                        // What is really in the archive, not what the manifest claims.
                        modelNames = inspection.modelNames.sortedWith(String.CASE_INSENSITIVE_ORDER),
                        soundFiles = inspection.soundFiles,
                        includesSettings = inspection.hasSettings,
                        sizeBytes = inspection.totalBytes,
                    ),
                )
            }
        }
    }

    override suspend fun restore(source: Uri, mode: RestoreMode, restoreSettings: Boolean): DataResult<RestoreReport> =
        restore({ uriAccess.openInput(source) }, mode, restoreSettings)

    /** [restore] for a stream source. */
    internal suspend fun restore(open: () -> InputStream?, mode: RestoreMode, restoreSettings: Boolean): DataResult<RestoreReport> = exclusive {
        val stage = File(stagingRoot, "restore-${UUID.randomUUID()}")
        try {
            // Everything is unpacked into a private staging directory and validated there
            // (paths, sizes, manifest) before a single file reaches the library.
            val stream = open() ?: return@exclusive DataResult.Failure(DataError.IO, "the backup cannot be opened")
            val scan = stream.use { ZipScanner.extract(it, BackupArchive.LIMITS, stage, BackupArchive::isRestorable) }
            if (scan is ScanResult.Rejected) {
                return@exclusive DataResult.Failure(BackupArchive.dataError(scan.problem), "${scan.problem}: ${scan.detail}")
            }
            val manifestBytes = File(stage, BackupArchive.MANIFEST_FILE).takeIf { it.isFile && it.length() <= 1024 * 1024 }?.readBytes()
            when (val checked = BackupArchive.checkManifest(manifestBytes)) {
                is BackupArchive.ManifestCheck.Bad -> return@exclusive DataResult.Failure(checked.error, checked.detail)
                is BackupArchive.ManifestCheck.Ok -> Unit
            }

            val restored = ArrayList<String>()
            val added = ArrayList<String>()
            val skipped = ArrayList<String>()
            val problems = ArrayList<String>()
            var currentReplaced = false
            val overwrite = mode == RestoreMode.OVERWRITE_EXISTING

            val stagedModels = File(stage, StorageDecision.MODELS_DIR).listFiles().orEmpty()
                .filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            for (folder in stagedModels) {
                val name = folder.name
                if (ModelNames.validate(name) != name) {
                    problems += "'$name' is not a valid model name"
                    continue
                }
                when (val outcome = models.restoreFolder(folder, name, overwrite)) {
                    FolderRestore.Added -> added += name
                    is FolderRestore.Replaced -> {
                        restored += name
                        if (outcome.current) currentReplaced = true
                    }
                    is FolderRestore.Skipped -> {
                        skipped += name
                        if (overwrite) problems += "'$name' was skipped: ${outcome.reason}"
                    }
                    is FolderRestore.Failed -> problems += "'$name' was not restored: ${outcome.detail}"
                }
            }

            restoreSounds(File(stage, StorageDecision.SOUNDS_DIR), overwrite, problems)

            var settingsRestored = false
            if (restoreSettings) {
                val file = File(stage, BackupArchive.SETTINGS_FILE)
                val decoded = if (file.isFile && file.length() <= 1024 * 1024) AppSettingsCodec.decodeOrNull(file.readText(Charsets.UTF_8)) else null
                if (decoded != null) {
                    settings.update { decoded }
                    settingsRestored = true
                } else {
                    problems += "The backup has no readable settings"
                }
            }

            // The reconcile imports the added folders into the SDK.
            when (val reconcile = models.reconcileLibrary()) {
                is DataResult.Success -> problems += reconcile.value.problems
                is DataResult.Failure -> problems += "The library could not be reconciled: ${reconcile.detail}"
            }
            val known = models.models.value.mapTo(HashSet()) { ModelNames.key(it.name) }
            for (name in added) {
                if (ModelNames.key(name) in known) restored += name else problems += "'$name' was copied into the library but is not known to the radio yet"
            }
            DataResult.Success(
                RestoreReport(
                    restored = restored.sortedWith(String.CASE_INSENSITIVE_ORDER),
                    skipped = skipped,
                    problems = problems,
                    settingsRestored = settingsRestored,
                    currentModelReplaced = currentReplaced,
                ),
            )
        } catch (e: IOException) {
            DataResult.Failure(DataError.IO, e.message.orEmpty())
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun restoreSounds(staged: File, overwrite: Boolean, problems: MutableList<String>) {
        if (!staged.isDirectory) return
        val target = models.storage.value.soundsDir.takeIf { it.isNotEmpty() }?.let(::File)
        if (target == null) {
            problems += "The sound files were not restored: the library is not available"
            return
        }
        try {
            FileOps.copyDirectory(staged, target, overwrite = overwrite)
        } catch (e: IOException) {
            problems += "The sound files were not restored completely: ${e.message.orEmpty()}"
        }
    }

    /** One backup or restore at a time; a second call fails with [DataError.BUSY]. */
    private suspend fun <T> exclusive(block: suspend () -> DataResult<T>): DataResult<T> = withContext(dispatchers.io) {
        if (!mutex.tryLock()) return@withContext DataResult.Failure(DataError.BUSY, "a backup or restore is running")
        try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        private const val FILE_PREFIX = "OpenAX12-backup-"
        private const val FILE_SUFFIX = ".zip"
    }
}
