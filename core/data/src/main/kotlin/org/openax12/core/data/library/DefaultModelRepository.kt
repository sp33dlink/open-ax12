package org.openax12.core.data.library

import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openax12.core.data.DataError
import org.openax12.core.data.DataResult
import org.openax12.core.data.ExternalChange
import org.openax12.core.data.ImportProblem
import org.openax12.core.data.ImportValidation
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.ModelSnapshot
import org.openax12.core.data.PictureCropRect
import org.openax12.core.data.RadioWideReport
import org.openax12.core.data.ReconcileReport
import org.openax12.core.data.SnapshotReason
import org.openax12.core.data.StorageInfo
import org.openax12.core.data.storage.FileOps
import org.openax12.core.data.storage.StorageLocator
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.MODEL_PICTURE_FILE_NAME
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.ModelConfigFile
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.ModelExtrasCodec
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.ModelType
import org.openax12.core.model.RadioWidePatch
import org.openax12.core.model.RawSystemFields
import org.openax12.core.model.RfModuleMode
import org.openax12.core.model.StickMode
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.SystemConfigValidity
import org.openax12.core.radio.ConnectionState
import org.openax12.core.radio.Radio
import org.openax12.core.radio.RadioEvent
import org.openax12.core.radio.getCurrentModel

/** What a backup restore did with one model folder. */
internal sealed interface FolderRestore {
    /** The folder was added to the library; the following reconcile imports it into the SDK. */
    data object Added : FolderRestore

    /** An existing model was replaced. [current] = it is the current model (the caller must reload it). */
    data class Replaced(val current: Boolean) : FolderRestore

    /** Left untouched; [reason] is an English note for the report. */
    data class Skipped(val reason: String) : FolderRestore

    /** Could not be restored. */
    data class Failed(val detail: String) : FolderRestore
}

/**
 * The model library (see [ModelRepository]).
 *
 * Layout: `<library root>/models/<folder>/` with `model.json`, `extras.json`, `picture.jpg` and
 * `snapshots/`. App-private state lives in [privateDir]: the sync index, a shadow copy of every
 * model's extras (to restore a quarantined or externally damaged `extras.json`) and temporary
 * files.
 *
 * The SDK's model files are only ever touched through [Radio] (`exportModel` to read one,
 * `importModel` to replace its content), so the class works the same on the real SDK and on the
 * simulator. Every public function takes one mutex and runs on the IO dispatcher.
 *
 * @param privateDir a directory in the app's private storage (created on demand)
 * @param scope used only to watch the radio for device reads (see `deviceInSync`)
 */
internal class DefaultModelRepository(
    private val radio: Radio,
    private val locator: StorageLocator,
    private val privateDir: File,
    private val uriAccess: UriAccess,
    private val pictures: PictureProcessor,
    scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val appVersion: String = "",
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ModelRepository {

    private val mutex = Mutex()
    private val indexStore = SyncIndexStore(File(privateDir, INDEX_FILE))
    private val readTracker = DeviceReadTracker()

    // Everything below is guarded by [mutex].
    private var index: SyncIndex = indexStore.load()
    private var sdkModels: Map<String, ModelInfo> = emptyMap()
    private var savedExtras: ModelExtras = ModelExtras()
    private var extrasLoadedFor: String? = null
    private var reconciled = false

    /** [DeviceReadTracker.epoch] at the time the pending state was entered; -1 = in an earlier process. */
    private var pendingEpoch: Long = -1

    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    private val _currentModel = MutableStateFlow<ModelEntry?>(null)
    private val _deviceInSync = MutableStateFlow(index.deviceInSync)
    private val _currentExtras = MutableStateFlow(ModelExtras())
    private val _hasStagedExtras = MutableStateFlow(false)
    private val _externalChanges = MutableStateFlow<List<ExternalChange>>(emptyList())
    private val _storage = MutableStateFlow(locator.resolve())

    override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()
    override val currentModel: StateFlow<ModelEntry?> = _currentModel.asStateFlow()
    override val deviceInSync: StateFlow<Boolean> = _deviceInSync.asStateFlow()
    override val currentExtras: StateFlow<ModelExtras> = _currentExtras.asStateFlow()
    override val hasStagedExtras: StateFlow<Boolean> = _hasStagedExtras.asStateFlow()
    override val externalChanges: StateFlow<List<ExternalChange>> = _externalChanges.asStateFlow()
    override val storage: StateFlow<StorageInfo> = _storage.asStateFlow()

    init {
        // A completed device read overwrites the SDK's current model file. The pending logic of
        // reconcileLibrary needs to know whether one happened since a model file was loaded.
        scope.launch { radio.connection.collect(readTracker::noteConnection) }
        scope.launch { radio.events.collect { if (it is RadioEvent.ConfigRead) readTracker.noteRead() } }
    }

    // -- Library --------------------------------------------------------------------------------

    override suspend fun reconcileLibrary(): DataResult<ReconcileReport> = locked { reconcileLocked() }

    override suspend fun refreshStorage(): StorageInfo = locked {
        val info = refreshStorageLocked()
        publish()
        info
    }

    override suspend fun acceptExternalChange(change: ExternalChange): DataResult<ModelEntry> = locked { acceptLocked(change) }

    override suspend fun rejectExternalChange(change: ExternalChange): DataResult<Unit> = locked { rejectLocked(change) }

    // -- Model management -----------------------------------------------------------------------

    override suspend fun createModel(name: String, blueprint: ModelBlueprint): DataResult<ModelEntry> = locked {
        val valid = ModelNames.validate(name) ?: return@locked failure(DataError.NAME_INVALID, "'$name'")
        if (nameTaken(valid)) return@locked failure(DataError.NAME_EXISTS, valid)
        if (!radioReady()) return@locked notReady()
        val modelsDir = modelsDir() ?: return@locked storageFailure()
        val target = radio.createModelInfo()?.takeIf { it.filePath.isNotEmpty() }
            ?: return@locked failure(DataError.RADIO_FAILED, "rcCreateModelCfg failed")
        if (!radio.saveWorkingConfigToModel(target.copy(name = valid))) {
            return@locked failure(DataError.RADIO_FAILED, "rcSaveCurrentCfgToModel failed")
        }
        val info = findSdkModel(target.filePath) ?: return@locked failure(DataError.RADIO_FAILED, "the new model file did not appear")
        val extras = blueprint.extras.copy(templateId = blueprint.extras.templateId ?: blueprint.templateId, pictureFile = null)
        val entry = SyncEntry(id = newId(), name = info.name.ifEmpty { valid }, folder = valid, sdkFile = info.filePath, modelMatch = info.modelMatch)
        try {
            val folder = FileOps.ensureDir(File(modelsDir, entry.folder))
            writeModelFileFromSdk(info, folder) ?: throw IOException("cannot export the new model")
            val stored = persistExtras(entry, extras, folder)
            adopt(stamp(stored, folder), info)
        } catch (e: IOException) {
            radio.deleteModel(info.filePath)
            File(modelsDir, entry.folder).deleteRecursively()
            return@locked failure(DataError.IO, e.message.orEmpty())
        }
        saveIndex()
        publish()
        entryResult(entry.id)
    }

    override suspend fun duplicateModel(modelId: String, newName: String): DataResult<ModelEntry> = locked {
        val source = index.entry(modelId) ?: return@locked failure(DataError.NOT_FOUND, modelId)
        val valid = ModelNames.validate(newName) ?: return@locked failure(DataError.NAME_INVALID, "'$newName'")
        if (nameTaken(valid)) return@locked failure(DataError.NAME_EXISTS, valid)
        if (!radioReady()) return@locked notReady()
        val modelsDir = modelsDir() ?: return@locked storageFailure()
        val sourceInfo = sdkModel(source) ?: return@locked failure(DataError.RADIO_FAILED, "unknown SDK model")
        val export = exportSdkFile(sourceInfo.filePath) ?: return@locked failure(DataError.RADIO_FAILED, "cannot export '${source.name}'")
        val info = try {
            importIntoSdk(export, valid)
        } finally {
            export.delete()
        } ?: return@locked failure(DataError.RADIO_FAILED, "the SDK could not create the copy")
        val entry = SyncEntry(id = newId(), name = valid, folder = valid, sdkFile = info.filePath, modelMatch = info.modelMatch)
        try {
            val sourceFolder = File(modelsDir, source.folder)
            val folder = FileOps.ensureDir(File(modelsDir, entry.folder))
            writeModelFileFromSdk(info, folder) ?: throw IOException("cannot export the copy")
            val picture = File(sourceFolder, MODEL_PICTURE_FILE_NAME)
            if (picture.isFile) FileOps.copyFile(picture, File(folder, MODEL_PICTURE_FILE_NAME))
            // The saved extras: staged changes of the current model are not part of a copy.
            val extras = if (source.id == index.currentId && extrasLoadedFor == source.id) {
                savedExtras.takeIf { source.hasExtras }
            } else {
                (ExtrasStore.load(sourceFolder, quarantine = false) as? ExtrasLoad.Loaded)?.extras
            }
            val stored = if (extras != null) persistExtras(entry, extras, folder) else entry
            adopt(stamp(stored, folder), info)
        } catch (e: IOException) {
            radio.deleteModel(info.filePath)
            File(modelsDir, entry.folder).deleteRecursively()
            return@locked failure(DataError.IO, e.message.orEmpty())
        }
        saveIndex()
        publish()
        entryResult(entry.id)
    }

    override suspend fun renameModel(modelId: String, newName: String): DataResult<ModelEntry> = locked {
        val entry = index.entry(modelId) ?: return@locked failure(DataError.NOT_FOUND, modelId)
        val valid = ModelNames.validate(newName) ?: return@locked failure(DataError.NAME_INVALID, "'$newName'")
        if (valid == entry.name && valid == entry.folder) return@locked entryResult(entry.id)
        if (nameTaken(valid, except = entry)) return@locked failure(DataError.NAME_EXISTS, valid)
        if (!radioReady()) return@locked notReady()
        val info = sdkModel(entry) ?: return@locked failure(DataError.RADIO_FAILED, "unknown SDK model")
        val modelsDir = modelsDir() ?: return@locked storageFailure()

        // Folder first: it is the step that can fail for reasons the user can fix (open files).
        val oldFolder = File(modelsDir, entry.folder)
        val newFolder = File(modelsDir, valid)
        if (oldFolder.isDirectory && entry.folder != valid && !renameFolder(oldFolder, newFolder)) {
            return@locked failure(DataError.IO, "cannot rename the folder '${entry.folder}'")
        }
        val updated = radio.updateModelInfo(info.copy(name = valid))
        if (updated == null) {
            if (newFolder.isDirectory && entry.folder != valid) renameFolder(newFolder, oldFolder)
            return@locked failure(DataError.RADIO_FAILED, "rcSetModelCfg failed")
        }
        val newInfo = findSdkModel(updated.filePath.ifEmpty { info.filePath }) ?: updated.copy(filePath = updated.filePath.ifEmpty { info.filePath })
        var renamed = entry.copy(name = valid, folder = valid, sdkFile = newInfo.filePath, modelMatch = newInfo.modelMatch)
        runCatching {
            FileOps.ensureDir(newFolder)
            writeModelFileFromSdk(newInfo, newFolder)
            renamed = stamp(renamed, newFolder)
        }
        adopt(renamed, newInfo)
        _externalChanges.value = _externalChanges.value.map {
            if (it.modelId == entry.id) it.copy(name = valid, folderPath = newFolder.absolutePath) else it
        }
        saveIndex()
        publish()
        entryResult(entry.id)
    }

    override suspend fun setModelMatch(modelId: String, modelMatch: Long): DataResult<ModelEntry> = locked {
        val entry = index.entry(modelId) ?: return@locked failure(DataError.NOT_FOUND, modelId)
        if (modelMatch !in 0..MAX_MODEL_MATCH) return@locked failure(DataError.INVALID_FILE, "model match $modelMatch is out of range")
        if (!radioReady()) return@locked notReady()
        val info = sdkModel(entry) ?: return@locked failure(DataError.RADIO_FAILED, "unknown SDK model")
        val updated = radio.updateModelInfo(info.copy(modelMatch = modelMatch))
            ?: return@locked failure(DataError.RADIO_FAILED, "rcSetModelCfg failed")
        val newInfo = findSdkModel(info.filePath) ?: updated.copy(filePath = info.filePath)
        var changed = entry.copy(modelMatch = newInfo.modelMatch)
        folderOf(entry)?.takeIf { it.isDirectory }?.let { folder ->
            runCatching {
                writeModelFileFromSdk(newInfo, folder)
                changed = stamp(changed, folder)
            }
        }
        adopt(changed, newInfo)
        saveIndex()
        publish()
        entryResult(entry.id)
    }

    override suspend fun deleteModel(modelId: String): DataResult<Unit> = locked {
        val entry = index.entry(modelId) ?: return@locked failure(DataError.NOT_FOUND, modelId)
        if (entry.id == index.currentId) return@locked failure(DataError.IS_CURRENT_MODEL, entry.name)
        if (!radioReady()) return@locked notReady()
        val info = sdkModel(entry)
        if (info != null) {
            // The SDK's current model must never be deleted, whatever the index believes.
            if (radio.getCurrentModel()?.filePath == info.filePath) return@locked failure(DataError.IS_CURRENT_MODEL, entry.name)
            if (!radio.deleteModel(info.filePath)) return@locked failure(DataError.RADIO_FAILED, "rcDeleteModel failed")
        }
        folderOf(entry)?.deleteRecursively()
        shadowDir(entry.id).deleteRecursively()
        index = index.without(entry.id).let { if (it.pendingModelId == entry.id) it.copy(pendingModelId = null, deviceInSync = true) else it }
        sdkModels = sdkModels - entry.id
        _deviceInSync.value = index.deviceInSync
        _externalChanges.value = _externalChanges.value.filter { it.modelId != entry.id }
        saveIndex()
        publish()
        DataResult.Success(Unit)
    }

    override suspend fun selectModel(modelId: String): DataResult<ModelEntry> = locked { loadModelLocked(modelId) }

    override suspend fun reloadCurrentModel(): DataResult<ModelEntry> = locked {
        val id = index.currentId ?: return@locked failure(DataError.NOT_FOUND, "no current model")
        loadModelLocked(id)
    }

    // -- Radio-wide settings --------------------------------------------------------------------

    override suspend fun applyRadioWideSettings(patch: RadioWidePatch): DataResult<RadioWideReport> = locked {
        patch.problem()?.let { return@locked failure(DataError.INVALID_FILE, it) }
        if (patch.isEmpty) return@locked DataResult.Success(RadioWideReport())
        if (!radioReady()) return@locked notReady()
        val updated = ArrayList<String>()
        val unchanged = ArrayList<String>()
        val failed = ArrayList<String>()
        for (entry in index.entries.toList()) {
            if (entry.id == index.currentId) {
                unchanged += entry.name
                continue
            }
            val info = sdkModel(entry)
            if (info == null) {
                failed += "${entry.name}: unknown SDK model"
                continue
            }
            when (val outcome = patchSdkFileLocked(entry, info, patch::differingFrom)) {
                Patched.Changed -> {
                    syncFolderFromSdk(entry, info)
                    updated += entry.name
                }
                Patched.Unchanged -> unchanged += entry.name
                is Patched.Failed -> failed += "${entry.name}: ${outcome.detail}"
            }
        }
        saveIndex()
        publish()
        DataResult.Success(RadioWideReport(updated = updated, unchanged = unchanged, failed = failed))
    }

    // -- Extras ---------------------------------------------------------------------------------

    override suspend fun updateExtras(transform: (ModelExtras) -> ModelExtras): ModelExtras = locked { updateCurrentExtrasLocked(transform) }

    override suspend fun stageExtras(transform: (ModelExtras) -> ModelExtras): ModelExtras = locked {
        val working = transform(_currentExtras.value)
        _currentExtras.value = working
        _hasStagedExtras.value = working != savedExtras
        working
    }

    override suspend fun revertStagedExtras(): ModelExtras = locked {
        _currentExtras.value = savedExtras
        _hasStagedExtras.value = false
        savedExtras
    }

    override suspend fun readExtras(modelId: String): DataResult<ModelExtras> = locked {
        val entry = index.entry(modelId) ?: return@locked failure(DataError.NOT_FOUND, modelId)
        if (entry.id == index.currentId && extrasLoadedFor == entry.id) return@locked DataResult.Success(savedExtras)
        DataResult.Success(readSavedExtras(entry))
    }

    override suspend fun updateExtrasOf(modelId: String, transform: (ModelExtras) -> ModelExtras): DataResult<ModelExtras> = locked {
        val entry = index.entry(modelId) ?: return@locked failure(DataError.NOT_FOUND, modelId)
        if (entry.id == index.currentId) {
            updateCurrentExtrasLocked(transform)
            return@locked DataResult.Success(savedExtras)
        }
        val folder = folderOf(entry) ?: return@locked storageFailure()
        val updated = transform(readSavedExtras(entry))
        try {
            adopt(persistExtras(entry, updated, folder), sdkModels[entry.id])
        } catch (e: IOException) {
            return@locked failure(DataError.IO, e.message.orEmpty())
        }
        saveIndex()
        publish()
        DataResult.Success(updated)
    }

    override suspend fun onDeviceConfigSaved(): DataResult<ModelSnapshot> = locked {
        // The device holds the working config now, whatever happens to the files below.
        markInSync()
        savedExtras = _currentExtras.value
        _hasStagedExtras.value = false
        val entry = index.entry(index.currentId) ?: return@locked failure(DataError.NOT_FOUND, "no current model")
        val info = sdkModel(entry) ?: return@locked failure(DataError.RADIO_FAILED, "unknown SDK model")
        val folder = folderOf(entry) ?: return@locked storageFailure()
        try {
            FileOps.ensureDir(folder)
            var updated = entry
            val keepExtras = entry.hasExtras || savedExtras != SDK_ONLY_EXTRAS
            if (keepExtras) updated = persistExtras(updated, savedExtras, folder)
            val export = exportSdkFile(info.filePath) ?: return@locked failure(DataError.RADIO_FAILED, "cannot export '${entry.name}'")
            val snapshot = try {
                FileOps.copyFile(export, File(folder, SnapshotStore.MODEL_FILE))
                SnapshotStore(folder).create(
                    modelId = entry.id,
                    modelFile = export,
                    extrasText = if (keepExtras) ModelExtrasCodec.encode(savedExtras) else null,
                    reason = SnapshotReason.SAVE,
                    timeMs = wallClock(),
                    keep = ModelRepository.MAX_SNAPSHOTS,
                )
            } finally {
                export.delete()
            }
            adopt(stamp(updated, folder), info)
            saveIndex()
            publish()
            DataResult.Success(snapshot)
        } catch (e: IOException) {
            failure(DataError.IO, e.message.orEmpty())
        }
    }

    override suspend fun onDeviceConfigWritten(): DataResult<ModelEntry> = locked {
        markInSync()
        val entry = index.entry(index.currentId) ?: return@locked failure(DataError.NOT_FOUND, "no current model")
        var updated = entry.copy(lastUsedMs = wallClock())
        val info = sdkModel(entry)
        val folder = folderOf(entry)
        if (info != null && folder != null) {
            runCatching {
                FileOps.ensureDir(folder)
                if (writeModelFileFromSdk(info, folder) != null) updated = stamp(updated, folder)
            }
        }
        adopt(updated, info)
        saveIndex()
        publish()
        entryResult(entry.id)
    }

    // -- Picture --------------------------------------------------------------------------------

    override suspend fun setPicture(modelId: String, source: Uri, crop: PictureCropRect): DataResult<ModelEntry> =
        setPicture(modelId, { uriAccess.openInput(source) }, crop)

    /** [setPicture] for a stream source. */
    internal suspend fun setPicture(modelId: String, open: () -> InputStream?, crop: PictureCropRect): DataResult<ModelEntry> = locked {
        val entry = index.entry(modelId) ?: return@locked failure(DataError.NOT_FOUND, modelId)
        val folder = folderOf(entry) ?: return@locked storageFailure()
        try {
            FileOps.ensureDir(folder)
        } catch (e: IOException) {
            return@locked failure(DataError.IO, e.message.orEmpty())
        }
        if (!pictures.process(open, crop, File(folder, MODEL_PICTURE_FILE_NAME))) {
            return@locked failure(DataError.INVALID_FILE, "the picture could not be read or stored")
        }
        setPictureFile(entry, MODEL_PICTURE_FILE_NAME)
    }

    override suspend fun removePicture(modelId: String): DataResult<ModelEntry> = locked {
        val entry = index.entry(modelId) ?: return@locked failure(DataError.NOT_FOUND, modelId)
        val folder = folderOf(entry) ?: return@locked storageFailure()
        val picture = File(folder, MODEL_PICTURE_FILE_NAME)
        if (picture.exists() && !picture.delete()) return@locked failure(DataError.IO, "cannot delete the picture")
        setPictureFile(entry, null)
    }

    // -- Export / import ------------------------------------------------------------------------

    override suspend fun exportModel(modelId: String): DataResult<Uri> {
        val file = when (val result = exportModelToFile(modelId)) {
            is DataResult.Success -> result.value
            is DataResult.Failure -> return result
        }
        val uri = uriAccess.shareUri(file) ?: return failure(DataError.IO, "the export cannot be shared")
        return DataResult.Success(uri)
    }

    /** Writes the `.oax12` archive of a model into the share cache. */
    internal suspend fun exportModelToFile(modelId: String): DataResult<File> = locked {
        val entry = index.entry(modelId) ?: return@locked failure(DataError.NOT_FOUND, modelId)
        val folder = folderOf(entry)
        val export = sdkModel(entry)?.takeIf { radioReady() }?.let { exportSdkFile(it.filePath) }
        try {
            // The SDK's file is the freshest copy; the folder's is the fallback while the radio is down.
            val modelFile = export ?: folder?.let { File(it, SnapshotStore.MODEL_FILE) }?.takeIf { it.isFile }
                ?: return@locked failure(DataError.NOT_FOUND, "no model file for '${entry.name}'")
            val extras = if (entry.id == index.currentId && extrasLoadedFor == entry.id) {
                savedExtras.takeIf { entry.hasExtras }
            } else {
                folder?.let { ExtrasStore.load(it, quarantine = false) as? ExtrasLoad.Loaded }?.extras
            }
            val picture = folder?.let { File(it, MODEL_PICTURE_FILE_NAME) }
                ?.takeIf { it.isFile && it.length() <= ModelArchive.LIMITS.maxEntryBytes }?.readBytes()
            val manifest = ArchiveManifest(
                name = entry.name,
                modelType = extras?.modelType,
                createdMs = wallClock(),
                appVersion = appVersion,
                extrasSchemaVersion = if (extras != null) ModelExtras.CURRENT_SCHEMA_VERSION else 0,
            )
            val shareDir = FileOps.ensureDir(uriAccess.shareDir)
            shareDir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".${ModelRepository.EXPORT_EXTENSION}")) it.delete() }
            val target = File(shareDir, "${entry.folder}.${ModelRepository.EXPORT_EXTENSION}")
            target.outputStream().use { out ->
                ModelArchive.write(
                    output = out,
                    manifest = manifest,
                    model = modelFile.readBytes(),
                    extrasText = extras?.copy(pictureFile = if (picture != null) MODEL_PICTURE_FILE_NAME else null)?.let(ModelExtrasCodec::encode),
                    picture = picture,
                )
            }
            DataResult.Success(target)
        } catch (e: IOException) {
            failure(DataError.IO, e.message.orEmpty())
        } finally {
            export?.delete()
        }
    }

    override suspend fun inspectImport(source: Uri): ImportValidation =
        inspectImport({ uriAccess.openInput(source) }, withContext(dispatchers.io) { uriAccess.displayName(source) })

    /** [inspectImport] for a stream source. */
    internal suspend fun inspectImport(open: () -> InputStream?, displayName: String?): ImportValidation = locked {
        when (val parsed = parse(open, displayName)) {
            is ParseResult.Rejected -> ImportValidation.Invalid(parsed.problem, parsed.detail)
            is ParseResult.Ok -> {
                val import = parsed.import
                val taken = takenNames()
                ImportValidation.Valid(
                    kind = import.kind,
                    name = import.name,
                    nameExists = ModelNames.isTaken(import.name, taken),
                    suggestedName = ModelNames.unique(import.name, taken),
                    type = import.extras?.modelType,
                    hasPicture = import.picture != null,
                    sizeBytes = import.sizeBytes,
                )
            }
        }
    }

    override suspend fun importModel(source: Uri, name: String?): DataResult<ModelEntry> =
        importModel({ uriAccess.openInput(source) }, withContext(dispatchers.io) { uriAccess.displayName(source) }, name)

    /** [importModel] for a stream source. */
    internal suspend fun importModel(open: () -> InputStream?, displayName: String?, name: String?): DataResult<ModelEntry> = locked {
        val import = when (val parsed = parse(open, displayName)) {
            is ParseResult.Rejected -> return@locked failure(dataError(parsed.problem), "${parsed.problem} ${parsed.detail}".trim())
            is ParseResult.Ok -> parsed.import
        }
        val target = if (name != null) {
            ModelNames.validate(name) ?: return@locked failure(DataError.NAME_INVALID, "'$name'")
        } else {
            import.name
        }
        if (nameTaken(target)) return@locked failure(DataError.NAME_EXISTS, target)
        if (!radioReady()) return@locked notReady()
        val modelsDir = modelsDir() ?: return@locked storageFailure()

        // Imported models take over the radio's stick mode, RF module and alarm voltage.
        val modelBytes = harmonizedModelBytes(import.model, currentRadioWide())
        val temp = newTempFile("import")
        val info = try {
            temp.writeBytes(modelBytes)
            importIntoSdk(temp, target)
        } catch (e: IOException) {
            return@locked failure(DataError.IO, e.message.orEmpty())
        } finally {
            temp.delete()
        } ?: return@locked failure(DataError.INVALID_FILE, "the SDK did not accept the model file")

        val entry = SyncEntry(id = newId(), name = target, folder = target, sdkFile = info.filePath, modelMatch = info.modelMatch)
        try {
            val folder = FileOps.ensureDir(File(modelsDir, entry.folder))
            if (writeModelFileFromSdk(info, folder) == null) FileOps.writeAtomic(File(folder, SnapshotStore.MODEL_FILE), modelBytes)
            val picture = import.picture?.takeIf(pictures::isImage)
            if (picture != null) FileOps.writeAtomic(File(folder, MODEL_PICTURE_FILE_NAME), picture)
            val extras = import.extras?.copy(pictureFile = if (picture != null) MODEL_PICTURE_FILE_NAME else null)
            val stored = if (extras != null) persistExtras(entry, extras, folder) else entry
            adopt(stamp(stored, folder), info)
        } catch (e: IOException) {
            radio.deleteModel(info.filePath)
            File(modelsDir, entry.folder).deleteRecursively()
            shadowDir(entry.id).deleteRecursively()
            return@locked failure(DataError.IO, e.message.orEmpty())
        }
        saveIndex()
        publish()
        entryResult(entry.id)
    }

    // -- Snapshots ------------------------------------------------------------------------------

    override suspend fun listSnapshots(modelId: String): List<ModelSnapshot> = locked {
        val entry = index.entry(modelId) ?: return@locked emptyList()
        val folder = folderOf(entry)?.takeIf { it.isDirectory } ?: return@locked emptyList()
        SnapshotStore(folder).list(entry.id)
    }

    override suspend fun restoreSnapshot(snapshot: ModelSnapshot): DataResult<ModelEntry> = locked {
        val entry = index.entry(snapshot.modelId) ?: return@locked failure(DataError.NOT_FOUND, snapshot.modelId)
        val folder = folderOf(entry)?.takeIf { it.isDirectory } ?: return@locked failure(DataError.NOT_FOUND, "no library folder")
        val store = SnapshotStore(folder)
        val modelFile = store.modelFile(snapshot.id) ?: return@locked failure(DataError.NOT_FOUND, snapshot.id)
        if (!ModelArchive.isJsonObject(readLimited(modelFile) ?: ByteArray(0))) {
            return@locked failure(DataError.INVALID_FILE, "the snapshot's model file is damaged")
        }
        val restoredExtras = store.extrasFile(snapshot.id)?.let { file ->
            val decoded = ModelExtrasCodec.decodeOrNull(file.readText(Charsets.UTF_8))
                ?: return@locked failure(DataError.INVALID_FILE, "the snapshot's extras are damaged")
            if (decoded.newerThanApp) return@locked failure(DataError.SCHEMA_TOO_NEW, "extras schema ${decoded.fileVersion}")
            decoded.extras
        }
        if (!radioReady()) return@locked notReady()
        val info = sdkModel(entry) ?: return@locked failure(DataError.RADIO_FAILED, "unknown SDK model")
        val isCurrent = entry.id == index.currentId
        // Read the radio's values before the current model's file is replaced.
        val wide = currentRadioWide()
        try {
            snapshotAppCopy(entry, info, folder, SnapshotReason.BEFORE_RESTORE, protect = snapshot.id)
        } catch (e: IOException) {
            return@locked failure(DataError.IO, "safety snapshot failed: ${e.message.orEmpty()}")
        }
        if (isCurrent) markPending(entry.id)
        if (!radio.importModel(modelFile.absolutePath, info.filePath)) {
            return@locked failure(DataError.RADIO_FAILED, "rcImportModelCfg failed")
        }
        val newInfo = enforceName(info.filePath, entry.name) ?: info
        wide?.let { patchSdkFileLocked(entry, newInfo, patchTo(it)) }
        var updated = entry.copy(sdkFile = newInfo.filePath, modelMatch = newInfo.modelMatch)
        try {
            if (writeModelFileFromSdk(newInfo, folder) == null) FileOps.copyFile(modelFile, File(folder, SnapshotStore.MODEL_FILE))
            if (restoredExtras != null) {
                // The picture is not part of a snapshot: keep the reference to the one that exists.
                val picture = File(folder, MODEL_PICTURE_FILE_NAME).isFile
                updated = persistExtras(updated, restoredExtras.copy(pictureFile = if (picture) MODEL_PICTURE_FILE_NAME else null), folder)
            }
            updated = stamp(updated, folder)
        } catch (e: IOException) {
            adopt(updated, newInfo)
            saveIndex()
            return@locked failure(DataError.IO, e.message.orEmpty())
        }
        adopt(updated, newInfo)
        _externalChanges.value = _externalChanges.value.filter { it.modelId != entry.id }
        if (isCurrent) {
            if (!radio.loadModelFile(newInfo.filePath)) {
                saveIndex()
                publish()
                return@locked failure(DataError.RADIO_FAILED, "rcLoadModelFile failed")
            }
            loadCurrentExtras(force = true)
        }
        saveIndex()
        publish()
        entryResult(entry.id)
    }

    override suspend fun deleteSnapshot(snapshot: ModelSnapshot): DataResult<Unit> = locked {
        val entry = index.entry(snapshot.modelId) ?: return@locked failure(DataError.NOT_FOUND, snapshot.modelId)
        val folder = folderOf(entry) ?: return@locked storageFailure()
        if (SnapshotStore(folder).delete(snapshot.id)) DataResult.Success(Unit) else failure(DataError.NOT_FOUND, snapshot.id)
    }

    // -- Hooks for LibraryBackup ----------------------------------------------------------------

    /** The library folders that hold a model (`model.json`), for the backup. */
    internal suspend fun modelFolders(): List<File> = locked {
        modelsDir()?.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith('.') && File(it, SnapshotStore.MODEL_FILE).isFile }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Puts the model folder [staged] (already extracted and path-checked) into the library under
     * [name]. A model of that name is replaced only when [overwrite]; the current model is never
     * replaced while the receiver link is up.
     */
    internal suspend fun restoreFolder(staged: File, name: String, overwrite: Boolean): FolderRestore = locked {
        restoreFolderLocked(staged, name, overwrite)
    }

    // -- Reconcile ------------------------------------------------------------------------------

    private class Scratch {
        val exported = ArrayList<String>()
        val imported = ArrayList<String>()
        val modified = ArrayList<String>()
        val problems = ArrayList<String>()
        val changes = ArrayList<ExternalChange>()
        val repaired = HashSet<String>()
        var pendingClean = false
    }

    private suspend fun reconcileLocked(): DataResult<ReconcileReport> {
        readTracker.noteConnection(radio.connection.value)
        refreshStorageLocked()
        if (!radioReady()) {
            if (extrasLoadedFor != index.currentId) loadCurrentExtras(force = false)
            publish()
            return failure(DataError.RADIO_FAILED, "the radio is not ready: ${radio.connection.value}")
        }
        var sdkList = radio.getModels()
        if (sdkList.isEmpty() && index.entries.isNotEmpty()) {
            // An empty list while models are known is more likely a hiccup than the truth.
            delay(EMPTY_LIST_RETRY_MS)
            sdkList = radio.getModels()
        }
        if (!radioReady()) {
            publish()
            return failure(DataError.RADIO_FAILED, "the radio is not ready: ${radio.connection.value}")
        }

        val scratch = Scratch()
        val old = index
        cleanTemp()
        val modelsDir = modelsDir()?.takeIf { dir -> runCatching { FileOps.ensureDir(dir) }.isSuccess }
        if (modelsDir == null) scratch.problems += "The library folder is not available; only the radio's own models are listed"

        // 1. Match the SDK's models with what the app knows.
        val claimed = LinkedHashMap<String, Pair<SyncEntry, ModelInfo>>()
        for (model in sdkList) {
            if (model.filePath.isEmpty()) continue
            val known = old.entries.firstOrNull { it.id !in claimed && it.sdkFile == model.filePath }
                ?: old.entries.firstOrNull { e -> e.id !in claimed && e.name == model.name && sdkList.none { it.filePath == e.sdkFile } }
            val entry = known?.copy(name = model.name, sdkFile = model.filePath, modelMatch = model.modelMatch)
                ?: SyncEntry(id = newId(), name = model.name, folder = "", sdkFile = model.filePath, modelMatch = model.modelMatch)
            claimed[entry.id] = entry to model
        }
        // New models get a folder of their own; an unclaimed folder of the same name is adopted.
        val takenFolders = ArrayList<String>()
        claimed.values.mapNotNullTo(takenFolders) { it.first.folder.takeIf(String::isNotEmpty) }
        old.entries.filter { it.id !in claimed }.mapTo(takenFolders) { it.folder }
        for (item in claimed.entries) {
            val (entry, model) = item.value
            if (entry.folder.isNotEmpty()) continue
            val folder = ModelNames.unique(ModelNames.sanitize(model.name), takenFolders)
            takenFolders += folder
            item.setValue(entry.copy(folder = folder) to model)
        }

        if (modelsDir != null) {
            // 2. Bring each known model and its folder in line.
            val pendingId = old.pendingModelId.takeIf { !old.deviceInSync }
            for (item in claimed.entries) {
                val (entry, model) = item.value
                item.setValue(syncPair(entry, model, modelsDir, pendingId, scratch) to model)
            }

            // 3. Folders the SDK does not know: import them.
            val claimedFolders = claimed.values.mapTo(HashSet()) { ModelNames.key(it.first.folder) }
            for (dir in modelsDir.listFiles().orEmpty().sortedBy { it.name.lowercase() }) {
                if (!dir.isDirectory || dir.name.startsWith('.') || ModelNames.key(dir.name) in claimedFolders) continue
                if (!File(dir, SnapshotStore.MODEL_FILE).isFile) continue
                if (old.ignoredFolders.any { it.equals(dir.name, ignoreCase = true) }) continue
                val lost = old.entries.firstOrNull { it.id !in claimed && it.folder.equals(dir.name, ignoreCase = true) }
                val names = claimed.values.map { it.first.name } + claimed.values.map { it.first.folder }
                val imported = importFolder(dir, modelsDir, lost, names, scratch) ?: continue
                claimed[imported.first.id] = imported
                claimedFolders += ModelNames.key(imported.first.folder)
                scratch.imported += imported.first.name
            }
        }

        // 4. Forget models that exist neither in the SDK nor as a folder any more.
        old.entries.filter { it.id !in claimed }.forEach { shadowDir(it.id).deleteRecursively() }

        // 5. The current model and the pending device write.
        val currentFile = radio.getCurrentModel()?.filePath
        val currentId = claimed.values.firstOrNull { it.second.filePath == currentFile }?.first?.id
            ?: old.currentId?.takeIf { it in claimed }
        var inSync = old.deviceInSync
        var pending = old.pendingModelId
        if (!inSync) {
            val readHappened = readTracker.epoch() > pendingEpoch
            val pendingModel = pending?.let { claimed[it] }
            when {
                pendingModel == null -> inSync = true
                pending != currentId -> if (readHappened) inSync = true
                pending in scratch.repaired -> {
                    // The SDK's file was put right from the library; the working config must follow.
                    if (!radio.loadModelFile(pendingModel.second.filePath)) {
                        scratch.problems += "Cannot load '${pendingModel.first.name}' after repairing its file"
                    }
                    pendingEpoch = readTracker.epoch()
                }
                readHappened && scratch.pendingClean -> inSync = true
            }
            if (inSync) pending = null
        }

        sdkModels = claimed.values.associate { it.first.id to it.second }
        index = old.copy(
            entries = claimed.values.map { it.first },
            currentId = currentId,
            deviceInSync = inSync,
            pendingModelId = pending,
            libraryRoot = _storage.value.libraryRoot,
        )
        _deviceInSync.value = inSync
        _externalChanges.value = scratch.changes

        // 6. Extras of the current model (staged changes of an unchanged model survive).
        val keepWorkingCopy = reconciled && extrasLoadedFor == currentId && _hasStagedExtras.value && currentId !in scratch.repaired
        if (!keepWorkingCopy) loadCurrentExtras(force = true)

        if (!saveIndex()) scratch.problems += "The sync index could not be written"
        reconciled = true
        publish()
        return DataResult.Success(
            ReconcileReport(
                exported = scratch.exported,
                imported = scratch.imported,
                modifiedExternally = scratch.modified,
                problems = scratch.problems,
            ),
        )
    }

    /** Synchronises one SDK model with its library folder; returns the entry with fresh stamps. */
    private suspend fun syncPair(entry: SyncEntry, info: ModelInfo, modelsDir: File, pendingId: String?, scratch: Scratch): SyncEntry {
        val folder = File(modelsDir, entry.folder)
        val modelFile = File(folder, SnapshotStore.MODEL_FILE)
        if (folder.isDirectory) FileOps.removeTempFiles(folder)
        val export = exportSdkFile(info.filePath)
        if (export == null) scratch.problems += "Cannot read the SDK file of '${entry.name}'"
        try {
            if (!modelFile.isFile) {
                if (export == null) return entry
                FileOps.ensureDir(folder)
                FileOps.copyFile(export, modelFile)
                // The folder is new or was emptied: bring the extras back from the private copy.
                val shadow = shadowFile(entry.id)
                if (!ExtrasStore.file(folder).isFile && shadow.isFile) FileOps.copyFile(shadow, ExtrasStore.file(folder))
                scratch.exported += entry.name
                return refreshExtras(stamp(entry, folder), folder, scratch)
            }
            val known = entry.modelHash.isNotEmpty()
            val folderHash = FileOps.sha256(modelFile)
            val extrasHash = FileOps.sha256(ExtrasStore.file(folder))
            val differs = export != null && FileOps.sha256(export) != folderHash
            val modelExternal = if (known) folderHash != entry.modelHash && (export == null || differs) else differs
            val extrasExternal = known && extrasHash != entry.extrasHash && extrasHash != FileOps.sha256(shadowFile(entry.id))
            if (modelExternal || extrasExternal) {
                scratch.modified += entry.name
                scratch.changes += ExternalChange(modelId = entry.id, name = entry.name, folderPath = folder.absolutePath, isNew = false)
                return stampPicture(entry, folder)
            }
            if (differs) {
                if (entry.id == pendingId) {
                    // The device never confirmed this model: the library folder is the reference
                    // copy and the SDK's file may hold another model's configuration.
                    if (radio.importModel(modelFile.absolutePath, info.filePath)) {
                        enforceName(info.filePath, entry.name)
                        scratch.repaired += entry.id
                    } else {
                        scratch.problems += "Cannot repair the SDK file of '${entry.name}' from the library"
                    }
                } else {
                    FileOps.copyFile(export!!, modelFile)
                    scratch.exported += entry.name
                }
            } else if (entry.id == pendingId && export != null) {
                scratch.pendingClean = true
            }
            return refreshExtras(stamp(entry, folder), folder, scratch)
        } catch (e: IOException) {
            scratch.problems += "Folder '${entry.folder}': ${e.message.orEmpty()}"
            return entry
        } finally {
            export?.delete()
        }
    }

    /** Imports a library folder the SDK does not know. Null (with a problem noted) if that is not possible. */
    private suspend fun importFolder(
        dir: File,
        modelsDir: File,
        lost: SyncEntry?,
        takenNames: Collection<String>,
        scratch: Scratch,
    ): Pair<SyncEntry, ModelInfo>? {
        validateFolder(dir)?.let { problem ->
            scratch.problems += "Folder '${dir.name}' was not imported: ${problem.second}"
            return null
        }
        val name = ModelNames.unique(ModelNames.sanitize(dir.name), takenNames)
        var folder = dir
        if (name != dir.name) {
            val target = File(modelsDir, name)
            if (!renameFolder(dir, target)) {
                scratch.problems += "Folder '${dir.name}' has no valid model name and cannot be renamed"
                return null
            }
            folder = target
        }
        val info = importIntoSdk(File(folder, SnapshotStore.MODEL_FILE), name)
        if (info == null) {
            scratch.problems += "The SDK did not accept the model in folder '${folder.name}'"
            scratch.changes += ExternalChange(modelId = null, name = name, folderPath = folder.absolutePath, isNew = true)
            return null
        }
        var entry = (lost ?: SyncEntry(id = newId(), name = name, folder = folder.name, sdkFile = info.filePath))
            .copy(name = name, folder = folder.name, sdkFile = info.filePath, modelMatch = info.modelMatch)
        try {
            FileOps.removeTempFiles(folder)
            writeModelFileFromSdk(info, folder)
            entry = refreshExtras(stamp(entry, folder), folder, scratch)
        } catch (e: IOException) {
            scratch.problems += "Folder '${folder.name}': ${e.message.orEmpty()}"
        }
        return entry to info
    }

    private suspend fun acceptLocked(change: ExternalChange): DataResult<ModelEntry> {
        val modelsDir = modelsDir() ?: return storageFailure()
        if (!radioReady()) return notReady()
        val scratch = Scratch()
        val entry = index.entry(change.modelId)
        if (entry == null) {
            // A folder the SDK does not know and that could not be imported automatically: try again.
            val dir = File(change.folderPath)
            if (!dir.isDirectory || dir.parentFile == null || !FileOps.isInside(modelsDir, dir)) return failure(DataError.NOT_FOUND, change.folderPath)
            val names = index.entries.map { it.name } + index.entries.map { it.folder }
            val imported = importFolder(dir, modelsDir, null, names, scratch)
                ?: return failure(DataError.INVALID_FILE, scratch.problems.joinToString("; "))
            adopt(imported.first, imported.second)
            _externalChanges.value = _externalChanges.value.filter { it.folderPath != change.folderPath }
            saveIndex()
            publish()
            return entryResult(imported.first.id)
        }
        val info = sdkModel(entry) ?: return failure(DataError.RADIO_FAILED, "unknown SDK model")
        val folder = File(modelsDir, entry.folder)
        validateFolder(folder)?.let { return failure(it.first, it.second) }
        try {
            snapshotAppCopy(entry, info, folder, SnapshotReason.BEFORE_REIMPORT)
        } catch (e: IOException) {
            return failure(DataError.IO, "safety snapshot failed: ${e.message.orEmpty()}")
        }
        val isCurrent = entry.id == index.currentId
        // Read the radio's values before the current model's file is replaced.
        val wide = currentRadioWide()
        if (isCurrent) markPending(entry.id)
        if (!radio.importModel(File(folder, SnapshotStore.MODEL_FILE).absolutePath, info.filePath)) {
            return failure(DataError.RADIO_FAILED, "rcImportModelCfg failed")
        }
        val newInfo = enforceName(info.filePath, entry.name) ?: info
        wide?.let { patchSdkFileLocked(entry, newInfo, patchTo(it)) }
        var updated = entry.copy(sdkFile = newInfo.filePath, modelMatch = newInfo.modelMatch)
        try {
            writeModelFileFromSdk(newInfo, folder)
            updated = refreshExtras(stamp(updated, folder), folder, scratch)
        } catch (e: IOException) {
            return failure(DataError.IO, e.message.orEmpty())
        }
        adopt(updated, newInfo)
        _externalChanges.value = _externalChanges.value.filter { it.modelId != entry.id }
        if (isCurrent) loadCurrentExtras(force = true)
        saveIndex()
        publish()
        return entryResult(entry.id)
    }

    private suspend fun rejectLocked(change: ExternalChange): DataResult<Unit> {
        val entry = index.entry(change.modelId)
        if (entry == null) {
            // Nothing of ours to put back: just stop offering the folder.
            val folderName = File(change.folderPath).name
            if (index.ignoredFolders.none { it.equals(folderName, ignoreCase = true) }) {
                index = index.copy(ignoredFolders = index.ignoredFolders + folderName)
            }
            _externalChanges.value = _externalChanges.value.filter { it.folderPath != change.folderPath }
            saveIndex()
            return DataResult.Success(Unit)
        }
        val folder = folderOf(entry) ?: return storageFailure()
        if (!radioReady()) return notReady()
        val info = sdkModel(entry) ?: return failure(DataError.RADIO_FAILED, "unknown SDK model")
        try {
            FileOps.ensureDir(folder)
            writeModelFileFromSdk(info, folder) ?: return failure(DataError.RADIO_FAILED, "cannot export '${entry.name}'")
            val shadow = shadowFile(entry.id)
            val extrasFile = ExtrasStore.file(folder)
            when {
                shadow.isFile -> FileOps.copyFile(shadow, extrasFile)
                !entry.hasExtras && extrasFile.exists() -> extrasFile.delete()
            }
            adopt(refreshExtras(stamp(entry, folder), folder, Scratch()), info)
        } catch (e: IOException) {
            return failure(DataError.IO, e.message.orEmpty())
        }
        _externalChanges.value = _externalChanges.value.filter { it.modelId != entry.id }
        if (entry.id == index.currentId && !_hasStagedExtras.value) loadCurrentExtras(force = true)
        saveIndex()
        publish()
        return DataResult.Success(Unit)
    }

    private suspend fun restoreFolderLocked(staged: File, name: String, overwrite: Boolean): FolderRestore {
        val modelsDir = modelsDir() ?: return FolderRestore.Failed("the library is not available")
        if (ModelNames.validate(name) != name) return FolderRestore.Failed("'$name' is not a valid model name")
        validateFolder(staged)?.let { return FolderRestore.Failed(it.second) }
        val key = ModelNames.key(name)
        val existing = index.entries.firstOrNull { ModelNames.key(it.name) == key || ModelNames.key(it.folder) == key }
        val target = File(modelsDir, existing?.folder ?: name)
        try {
            if (existing == null) {
                if (target.exists()) {
                    if (!overwrite) return FolderRestore.Skipped("a folder named '$name' exists")
                    target.deleteRecursively()
                }
                FileOps.copyDirectory(staged, target)
                index = index.copy(ignoredFolders = index.ignoredFolders.filterNot { it.equals(name, ignoreCase = true) })
                return FolderRestore.Added
            }
            if (!overwrite) return FolderRestore.Skipped("exists")
            val isCurrent = existing.id == index.currentId
            if (isCurrent && radio.live.value.status.isLinkUp) return FolderRestore.Skipped("it is the current model and the receiver link is up")
            if (!radioReady()) return FolderRestore.Failed("the radio is not ready")
            val info = sdkModel(existing) ?: return FolderRestore.Failed("unknown SDK model")
            FileOps.ensureDir(target)
            snapshotAppCopy(existing, info, target, SnapshotReason.BEFORE_RESTORE)
            if (isCurrent) markPending(existing.id)
            if (!radio.importModel(File(staged, SnapshotStore.MODEL_FILE).absolutePath, info.filePath)) {
                return FolderRestore.Failed("the SDK did not accept the model file")
            }
            val newInfo = enforceName(info.filePath, existing.name) ?: info
            // Replace the model's own files; snapshots are merged, never removed.
            for (file in listOf(ModelExtras.FILE_NAME, MODEL_PICTURE_FILE_NAME)) {
                val source = File(staged, file)
                val destination = File(target, file)
                if (source.isFile) FileOps.copyFile(source, destination) else destination.delete()
            }
            if (writeModelFileFromSdk(newInfo, target) == null) {
                FileOps.copyFile(File(staged, SnapshotStore.MODEL_FILE), File(target, SnapshotStore.MODEL_FILE))
            }
            val stagedSnapshots = File(staged, SnapshotStore.DIR_NAME)
            if (stagedSnapshots.isDirectory) FileOps.copyDirectory(stagedSnapshots, File(target, SnapshotStore.DIR_NAME), overwrite = false)
            SnapshotStore(target).prune(existing.id, ModelRepository.MAX_SNAPSHOTS)
            val updated = refreshExtras(
                stamp(existing.copy(sdkFile = newInfo.filePath, modelMatch = newInfo.modelMatch), target),
                target,
                Scratch(),
            )
            adopt(updated, newInfo)
            _externalChanges.value = _externalChanges.value.filter { it.modelId != existing.id }
            if (isCurrent) loadCurrentExtras(force = true)
            saveIndex()
            publish()
            return FolderRestore.Replaced(isCurrent)
        } catch (e: IOException) {
            return FolderRestore.Failed(e.message.orEmpty())
        }
    }

    // -- Model loading and extras ---------------------------------------------------------------

    private suspend fun loadModelLocked(modelId: String): DataResult<ModelEntry> {
        val entry = index.entry(modelId) ?: return failure(DataError.NOT_FOUND, modelId)
        if (!radioReady()) return notReady()
        val info = sdkModel(entry) ?: return failure(DataError.RADIO_FAILED, "unknown SDK model")
        // Stick mode, RF module and alarm voltage belong to the radio, not to the model: bring the
        // model to the radio's values before the radio sees it. Read them first, while the current
        // model is still loaded. Reloading the current model needs nothing (it is the source).
        if (entry.id != index.currentId) currentRadioWide()?.let { harmonizeLocked(entry, info, it) }
        val before = index
        val epochBefore = pendingEpoch
        // Persist the pending state before the SDK is touched: if the app dies in between, the
        // next start must know that this model's file may get overwritten by the device read.
        markPending(entry.id)
        if (!radio.loadModelFile(info.filePath)) {
            index = index.copy(deviceInSync = before.deviceInSync, pendingModelId = before.pendingModelId)
            pendingEpoch = epochBefore
            _deviceInSync.value = index.deviceInSync
            saveIndex()
            return failure(DataError.RADIO_FAILED, "rcLoadModelFile failed")
        }
        index = index.copy(currentId = entry.id)
        loadCurrentExtras(force = true)
        saveIndex()
        publish()
        return entryResult(entry.id)
    }

    private fun updateCurrentExtrasLocked(transform: (ModelExtras) -> ModelExtras): ModelExtras {
        val working = transform(_currentExtras.value)
        val saved = transform(savedExtras)
        savedExtras = saved
        _currentExtras.value = working
        _hasStagedExtras.value = working != saved
        val entry = index.entry(index.currentId)
        if (entry != null && extrasLoadedFor == entry.id) {
            try {
                adopt(persistExtras(entry, saved, folderOf(entry)), sdkModels[entry.id])
                saveIndex()
            } catch (_: IOException) {
                // Kept in memory and in the shadow copy; the next successful write catches up.
            }
            publish()
        }
        return working
    }

    /** Loads saved and working extras of the current model from its folder. */
    private fun loadCurrentExtras(force: Boolean) {
        val entry = index.entry(index.currentId)
        if (!force && extrasLoadedFor == entry?.id) return
        savedExtras = if (entry == null) ModelExtras() else readSavedExtras(entry)
        extrasLoadedFor = entry?.id
        _currentExtras.value = savedExtras
        _hasStagedExtras.value = false
    }

    /** The saved extras of [entry]: the folder's file, the shadow copy if that is unusable, else defaults. */
    private fun readSavedExtras(entry: SyncEntry): ModelExtras {
        val folder = folderOf(entry)
        val loaded = folder?.takeIf { it.isDirectory }?.let { ExtrasStore.load(it, quarantine = true, nowMs = wallClock()) }
        if (loaded is ExtrasLoad.Loaded) return loaded.extras
        if (loaded is ExtrasLoad.Corrupt || folder == null || !folder.isDirectory) {
            val shadow = shadowFile(entry.id)
            if (shadow.isFile) {
                ModelExtrasCodec.decodeOrNull(runCatching { shadow.readText(Charsets.UTF_8) }.getOrDefault(""))?.let { return it.extras }
            }
        }
        return SDK_ONLY_EXTRAS
    }

    /**
     * Writes [extras] of [entry]: first the app-private shadow copy, then the library folder.
     *
     * @return [entry] with the extras fields updated
     * @throws IOException if the library folder cannot be written (the shadow copy is still updated)
     */
    private fun persistExtras(entry: SyncEntry, extras: ModelExtras, folder: File?): SyncEntry {
        val text = ModelExtrasCodec.encode(extras)
        runCatching { FileOps.writeAtomic(shadowFile(entry.id), text) }
        val updated = entry.copy(modelType = extras.modelType, hasExtras = true)
        if (folder == null) throw IOException("the library is not available")
        FileOps.ensureDir(folder)
        return updated.copy(extrasHash = ExtrasStore.saveText(folder, text))
    }

    /**
     * Re-reads the extras of [folder] after a sync: updates type / presence in the entry, keeps
     * the shadow copy current, repairs a corrupt file from the shadow copy.
     */
    private fun refreshExtras(entry: SyncEntry, folder: File, scratch: Scratch): SyncEntry {
        val shadow = shadowFile(entry.id)
        return when (val load = ExtrasStore.load(folder, quarantine = true, nowMs = wallClock())) {
            ExtrasLoad.Missing -> entry.copy(hasExtras = false, modelType = ModelType.OTHER, extrasHash = "")
            is ExtrasLoad.Loaded -> {
                if (load.newerThanApp) {
                    scratch.problems += "The extras of '${entry.name}' were written by a newer app version" +
                        (load.preservedAs?.let { "; the original is kept as $it" } ?: "")
                }
                val file = ExtrasStore.file(folder)
                val hash = FileOps.sha256(file)
                if (hash != FileOps.sha256(shadow)) runCatching { FileOps.copyFile(file, shadow) }
                entry.copy(hasExtras = true, modelType = load.extras.modelType, extrasHash = hash)
            }
            is ExtrasLoad.Corrupt -> {
                val aside = load.quarantinedAs?.let { " (kept as $it)" }.orEmpty()
                val restored = shadow.isFile && runCatching { FileOps.copyFile(shadow, ExtrasStore.file(folder)) }.isSuccess &&
                    ExtrasStore.load(folder, quarantine = false) is ExtrasLoad.Loaded
                if (restored) {
                    scratch.problems += "The extras of '${entry.name}' were unreadable$aside and were restored from the app's copy"
                    val reloaded = ExtrasStore.load(folder, quarantine = false) as ExtrasLoad.Loaded
                    entry.copy(hasExtras = true, modelType = reloaded.extras.modelType, extrasHash = FileOps.sha256(ExtrasStore.file(folder)))
                } else {
                    scratch.problems += "The extras of '${entry.name}' were unreadable$aside; the model continues without them"
                    ExtrasStore.file(folder).delete()
                    entry.copy(hasExtras = false, modelType = ModelType.OTHER, extrasHash = "")
                }
            }
        }
    }

    private fun setPictureFile(entry: SyncEntry, pictureFile: String?): DataResult<ModelEntry> {
        val folder = folderOf(entry) ?: return storageFailure()
        try {
            if (entry.id == index.currentId && extrasLoadedFor == entry.id) {
                updateCurrentExtrasLocked { it.copy(pictureFile = pictureFile) }
            } else if (entry.hasExtras || pictureFile != null) {
                adopt(persistExtras(entry, readSavedExtras(entry).copy(pictureFile = pictureFile), folder), sdkModels[entry.id])
            }
        } catch (e: IOException) {
            return failure(DataError.IO, e.message.orEmpty())
        }
        // Force a new cache key even when the new file happens to have the same size and time.
        val current = index.entry(entry.id) ?: entry
        adopt(stamp(current.copy(pictureStamp = "?"), folder), sdkModels[entry.id])
        saveIndex()
        publish()
        return entryResult(entry.id)
    }

    // -- Radio-wide settings (internals) --------------------------------------------------------

    /** Result of [patchSdkFileLocked]. */
    private sealed interface Patched {
        data object Changed : Patched
        data object Unchanged : Patched
        data class Failed(val detail: String) : Patched
    }

    /**
     * The radio-wide values the radio has **right now** (read through the current model file by
     * the radio backend), or null when the radio is not ready or has no trustworthy values. Must be
     * read before the current model's file is replaced or another model is loaded.
     */
    private suspend fun currentRadioWide(): SystemConfig? {
        if (!radioReady()) return null
        val system = radio.getSystemConfig() ?: return null
        return system.takeIf { SystemConfigValidity.problem(it) == null }
    }

    /**
     * The patch that gives a model file with the given fields the radio-wide values of [wide].
     * Fields are compared as mapped values, so an RF code the app only keeps verbatim (255) is not
     * rewritten.
     */
    private fun patchTo(wide: SystemConfig): (RawSystemFields) -> RadioWidePatch = { fields ->
        RadioWidePatch(
            stickMode = wide.stickMode.sdk.takeIf { StickMode.fromSdk(fields.stickMode) != wide.stickMode },
            rfModuleMode = wide.rfModuleMode.sdk.takeIf { RfModuleMode.fromSdk(fields.rfModuleMode) != wide.rfModuleMode },
            alarmVoltageDeciVolt = wide.alarmVoltageDeciVolt.takeIf { it != fields.alarmVoltageDeciVolt },
        )
    }

    /**
     * Rewrites the SDK file of [info] with the radio-wide fields chosen by [patchFor]: export,
     * patch the JSON text, `rcImportModelCfg` back. Nothing is written when the file already has
     * the values. The model must not be the one the radio is running (its file is the radio's).
     */
    private suspend fun patchSdkFileLocked(entry: SyncEntry, info: ModelInfo, patchFor: (RawSystemFields) -> RadioWidePatch): Patched {
        val export = exportSdkFile(info.filePath) ?: return Patched.Failed("cannot export the SDK file")
        val text = try {
            export.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return Patched.Failed("cannot read the exported file: ${e.message.orEmpty()}")
        } finally {
            export.delete()
        }
        val fields = ModelConfigFile.readSystem(text) ?: return Patched.Failed("the SDK file has no system fields")
        val patch = patchFor(fields)
        if (patch.isEmpty) return Patched.Unchanged
        val patched = ModelConfigFile.patch(text, patch) ?: return Patched.Failed("the radio-wide values cannot be written into the file")
        val temp = try {
            newTempFile("radiowide")
        } catch (e: IOException) {
            return Patched.Failed("no temporary file: ${e.message.orEmpty()}")
        }
        try {
            temp.writeText(patched, Charsets.UTF_8)
            if (!radio.importModel(temp.absolutePath, info.filePath)) return Patched.Failed("rcImportModelCfg failed")
        } catch (e: IOException) {
            return Patched.Failed(e.message.orEmpty())
        } finally {
            temp.delete()
        }
        enforceName(info.filePath, entry.name)
        return Patched.Changed
    }

    /**
     * Refreshes the library reference copy of [entry] from its (just changed) SDK file, unless the
     * folder was modified outside the app: that is the user's decision (`externalChanges`).
     */
    private suspend fun syncFolderFromSdk(entry: SyncEntry, info: ModelInfo) {
        val folder = folderOf(entry)?.takeIf { it.isDirectory } ?: return
        val known = index.entry(entry.id) ?: entry
        val modelFile = File(folder, SnapshotStore.MODEL_FILE)
        if (known.modelHash.isNotEmpty() && FileOps.sha256(modelFile) != known.modelHash) return
        runCatching {
            if (writeModelFileFromSdk(info, folder) != null) adopt(stamp(known, folder), info)
        }
    }

    /**
     * Applies the radio-wide values of [wide] to the model [entry] before it is loaded: its SDK
     * file and its library copy. A failure is not fatal; the model is loaded as it is.
     */
    private suspend fun harmonizeLocked(entry: SyncEntry, info: ModelInfo, wide: SystemConfig) {
        if (patchSdkFileLocked(entry, info, patchTo(wide)) == Patched.Changed) syncFolderFromSdk(entry, info)
    }

    /** [model] (the text of a model file) with the radio-wide values of [wide]; unchanged if that is not possible. */
    private fun harmonizedModelBytes(model: ByteArray, wide: SystemConfig?): ByteArray {
        if (wide == null) return model
        val text = model.toString(Charsets.UTF_8)
        val fields = ModelConfigFile.readSystem(text) ?: return model
        val patch = patchTo(wide)(fields)
        if (patch.isEmpty) return model
        return ModelConfigFile.patch(text, patch)?.toByteArray(Charsets.UTF_8) ?: model
    }

    // -- SDK access -----------------------------------------------------------------------------

    private fun radioReady(): Boolean = radio.connection.value.isReady

    private suspend fun findSdkModel(filePath: String): ModelInfo? =
        if (filePath.isEmpty()) null else radio.getModels().firstOrNull { it.filePath == filePath }

    /** The SDK metadata of [entry]: the cached one, else a fresh lookup by file. */
    private suspend fun sdkModel(entry: SyncEntry): ModelInfo? =
        sdkModels[entry.id] ?: findSdkModel(entry.sdkFile)?.also { sdkModels = sdkModels + (entry.id to it) }

    /**
     * Copies an SDK model file into a temporary file through `Radio.exportModel` and returns it,
     * or null if the SDK did not produce it. The caller deletes the file.
     */
    private suspend fun exportSdkFile(sdkFile: String): File? {
        if (sdkFile.isEmpty()) return null
        val temp = try {
            newTempFile("export")
        } catch (_: IOException) {
            return null
        }
        if (!radio.exportModel(sdkFile, temp.absolutePath)) return null
        // `rcExportModelCfg` returns nothing; give a slow file system a moment.
        repeat(EXPORT_WAIT_STEPS) {
            if (temp.isFile && temp.length() > 0) return temp
            delay(EXPORT_WAIT_STEP_MS)
        }
        temp.delete()
        return null
    }

    /** Writes the SDK's file of [info] as the folder's `model.json`. Null if the export failed. */
    private suspend fun writeModelFileFromSdk(info: ModelInfo, folder: File): File? {
        val export = exportSdkFile(info.filePath) ?: return null
        try {
            val target = File(folder, SnapshotStore.MODEL_FILE)
            FileOps.copyFile(export, target)
            return target
        } finally {
            export.delete()
        }
    }

    /**
     * Creates a new SDK model named [name] with the content of [source].
     *
     * `rcImportModelCfg` is documented as importing "into the target model" without saying
     * whether the target has to exist (assumption 26). The direct import into the path of
     * `rcCreateModelCfg` is tried first; if no model appears, a placeholder is created with
     * `rcSaveCurrentCfgToModel` and the import repeated on top of it.
     */
    private suspend fun importIntoSdk(source: File, name: String): ModelInfo? {
        val target = radio.createModelInfo()?.takeIf { it.filePath.isNotEmpty() } ?: return null
        var imported = radio.importModel(source.absolutePath, target.filePath)
        var found = findSdkModel(target.filePath)
        if (!imported || found == null) {
            if (!radio.saveWorkingConfigToModel(target.copy(name = name))) return null
            imported = radio.importModel(source.absolutePath, target.filePath)
            found = findSdkModel(target.filePath)
            if (!imported || found == null) {
                if (found != null) radio.deleteModel(target.filePath)
                return null
            }
        }
        return enforceName(found.filePath, name) ?: found
    }

    /** Makes sure the SDK model stored in [filePath] is called [name] (an import brings its own name along). */
    private suspend fun enforceName(filePath: String, name: String): ModelInfo? {
        val found = findSdkModel(filePath) ?: return null
        if (found.name == name) return found
        val updated = radio.updateModelInfo(found.copy(name = name)) ?: return found
        return findSdkModel(updated.filePath.ifEmpty { filePath }) ?: updated
    }

    /** Snapshot of the app's own state of a model: the SDK file plus the extras it last wrote. */
    private suspend fun snapshotAppCopy(entry: SyncEntry, info: ModelInfo, folder: File, reason: SnapshotReason, protect: String? = null) {
        val export = exportSdkFile(info.filePath) ?: throw IOException("cannot export '${entry.name}'")
        try {
            val extrasText = when {
                entry.id == index.currentId && extrasLoadedFor == entry.id && entry.hasExtras -> ModelExtrasCodec.encode(savedExtras)
                shadowFile(entry.id).isFile -> shadowFile(entry.id).readText(Charsets.UTF_8)
                else -> null
            }
            val store = SnapshotStore(folder)
            val created = store.create(entry.id, export, extrasText, reason, wallClock(), keep = Int.MAX_VALUE)
            // Prune without touching the new safety copy or the snapshot about to be restored.
            val all = store.list(entry.id)
            var excess = all.size - ModelRepository.MAX_SNAPSHOTS
            for (old in all.asReversed()) {
                if (excess <= 0) break
                if (old.id == created.id || old.id == protect) continue
                if (store.delete(old.id)) excess--
            }
        } finally {
            export.delete()
        }
    }

    // -- Small helpers --------------------------------------------------------------------------

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(dispatchers.io) { mutex.withLock { block() } }

    private fun parse(open: () -> InputStream?, displayName: String?): ParseResult {
        val stream = try {
            open()
        } catch (_: Exception) {
            null
        } ?: return ParseResult.Rejected(ImportProblem.NOT_READABLE, "the source cannot be opened")
        return try {
            stream.use { ModelArchive.parse(it, displayName) }
        } catch (e: IOException) {
            ParseResult.Rejected(ImportProblem.NOT_READABLE, e.message.orEmpty())
        }
    }

    /** Null if [folder] holds an importable model, else the error and an English reason. */
    private fun validateFolder(folder: File): Pair<DataError, String>? {
        val model = File(folder, SnapshotStore.MODEL_FILE)
        if (!model.isFile) return DataError.NOT_FOUND to "it has no ${SnapshotStore.MODEL_FILE}"
        val bytes = readLimited(model) ?: return DataError.TOO_LARGE to "${SnapshotStore.MODEL_FILE} is too large"
        if (!ModelArchive.isJsonObject(bytes)) return DataError.INVALID_FILE to "${SnapshotStore.MODEL_FILE} is not a JSON object"
        val extras = ExtrasStore.load(folder, quarantine = false)
        if (extras is ExtrasLoad.Loaded && extras.newerThanApp) return DataError.SCHEMA_TOO_NEW to "its extras were written by a newer app version"
        return null
    }

    private fun readLimited(file: File): ByteArray? = try {
        if (file.length() > ModelArchive.MAX_STOCK_MODEL_BYTES) null else file.readBytes()
    } catch (_: IOException) {
        null
    }

    private fun modelsDir(): File? = _storage.value.modelsDir.takeIf { it.isNotEmpty() }?.let(::File)

    private fun folderOf(entry: SyncEntry): File? = if (entry.folder.isEmpty()) null else modelsDir()?.let { File(it, entry.folder) }

    private fun shadowDir(id: String): File = File(privateDir, "$SHADOW_DIR/$id")

    private fun shadowFile(id: String): File = File(shadowDir(id), ModelExtras.FILE_NAME)

    private fun newTempFile(prefix: String): File {
        val dir = FileOps.ensureDir(File(privateDir, TEMP_DIR))
        val file = File(dir, "$prefix-${UUID.randomUUID()}.json")
        file.delete()
        return file
    }

    private fun cleanTemp() {
        File(privateDir, TEMP_DIR).listFiles()?.forEach { it.delete() }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    /** Names that a new model must not collide with: model names, folder names and existing directories. */
    private fun takenNames(except: SyncEntry? = null): List<String> = buildList {
        for (entry in index.entries) {
            if (entry.id == except?.id) continue
            add(entry.name)
            add(entry.folder)
        }
        modelsDir()?.list()?.forEach { if (except == null || !it.equals(except.folder, ignoreCase = true)) add(it) }
    }

    private fun nameTaken(name: String, except: SyncEntry? = null): Boolean = ModelNames.isTaken(name, takenNames(except))

    /** Renames a folder, also when only the letter case changes on a case-insensitive volume. */
    private fun renameFolder(from: File, to: File): Boolean {
        if (from.absolutePath == to.absolutePath) return true
        if (from.name.equals(to.name, ignoreCase = true)) {
            val temp = File(from.parentFile, "${from.name}.renaming")
            return from.renameTo(temp) && temp.renameTo(to)
        }
        if (to.exists()) return false
        return from.renameTo(to) || FileOps.moveDirectory(from, to)
    }

    /** Stores the current state of [folder] (`model.json`, `extras.json`, picture) in the entry. */
    private fun stamp(entry: SyncEntry, folder: File): SyncEntry = stampPicture(
        entry.copy(
            modelHash = FileOps.sha256(File(folder, SnapshotStore.MODEL_FILE)),
            extrasHash = FileOps.sha256(ExtrasStore.file(folder)),
            hasExtras = ExtrasStore.file(folder).isFile,
        ),
        folder,
    )

    private fun stampPicture(entry: SyncEntry, folder: File): SyncEntry {
        val picture = File(folder, MODEL_PICTURE_FILE_NAME)
        val pictureStamp = if (picture.isFile) "${picture.length()}-${picture.lastModified()}" else ""
        return if (pictureStamp == entry.pictureStamp) entry else entry.copy(pictureStamp = pictureStamp, pictureVersion = wallClock())
    }

    /** Puts [entry] (and its SDK metadata) into the in-memory index. */
    private fun adopt(entry: SyncEntry, info: ModelInfo?) {
        index = index.with(entry)
        if (info != null) sdkModels = sdkModels + (entry.id to info)
    }

    private fun saveIndex(): Boolean = indexStore.save(index)

    private fun markPending(id: String) {
        index = index.copy(deviceInSync = false, pendingModelId = id)
        pendingEpoch = readTracker.epoch()
        saveIndex()
        _deviceInSync.value = false
    }

    private fun markInSync() {
        if (!index.deviceInSync || index.pendingModelId != null) {
            index = index.copy(deviceInSync = true, pendingModelId = null)
            saveIndex()
        }
        _deviceInSync.value = true
    }

    /** Resolves the storage location again and moves the library if its root changed. */
    private fun refreshStorageLocked(): StorageInfo {
        val info = locator.resolve()
        val previous = index.libraryRoot
        if (info.libraryRoot.isNotEmpty() && info.libraryRoot != previous) {
            if (previous.isNotEmpty()) migrateRoot(File(previous), File(info.libraryRoot))
            index = index.copy(libraryRoot = info.libraryRoot)
            saveIndex()
        }
        _storage.value = info
        return info
    }

    /**
     * Moves models, sounds and logs from the previous library root to the new one (the storage
     * permission was granted or revoked). Items that already exist at the destination stay where
     * they are; nothing is overwritten.
     */
    private fun migrateRoot(from: File, to: File) {
        if (!from.isDirectory) return
        for (sub in from.listFiles().orEmpty()) {
            if (!sub.isDirectory) continue
            val targetSub = File(to, sub.name)
            for (child in sub.listFiles().orEmpty()) {
                val target = File(targetSub, child.name)
                if (target.exists()) continue
                runCatching {
                    if (child.isDirectory) FileOps.moveDirectory(child, target) else FileOps.moveReplacing(child, target)
                }
            }
            if (sub.list().isNullOrEmpty()) sub.delete()
        }
        if (from.list().isNullOrEmpty()) from.delete()
    }

    private fun publish() {
        val modified = _externalChanges.value.mapNotNullTo(HashSet()) { it.modelId }
        val modelsDir = modelsDir()
        val list = index.entries.map { entry ->
            val folder = if (entry.folder.isEmpty()) null else modelsDir?.let { File(it, entry.folder) }
            ModelEntry(
                id = entry.id,
                name = entry.name,
                type = if (entry.hasExtras) entry.modelType else ModelType.OTHER,
                modelMatch = entry.modelMatch,
                picturePath = folder?.takeIf { entry.pictureStamp.isNotEmpty() }?.let { File(it, MODEL_PICTURE_FILE_NAME).absolutePath },
                pictureVersion = entry.pictureVersion,
                lastUsedMs = entry.lastUsedMs,
                isCurrent = entry.id == index.currentId,
                sdk = sdkModels[entry.id] ?: ModelInfo(name = entry.name, filePath = entry.sdkFile, modelMatch = entry.modelMatch),
                folderPath = folder?.absolutePath,
                hasExtras = entry.hasExtras,
                externallyModified = entry.id in modified,
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        _models.value = list
        _currentModel.value = list.firstOrNull { it.isCurrent }
    }

    private fun entryResult(id: String): DataResult<ModelEntry> =
        _models.value.firstOrNull { it.id == id }?.let { DataResult.Success(it) } ?: failure(DataError.NOT_FOUND, id)

    private fun failure(error: DataError, detail: String = ""): DataResult.Failure = DataResult.Failure(error, detail)

    private fun notReady(): DataResult.Failure = failure(DataError.RADIO_FAILED, "the radio is not ready: ${radio.connection.value}")

    private fun storageFailure(): DataResult.Failure {
        val storage = _storage.value
        return if (!storage.permissionGranted) failure(DataError.STORAGE_PERMISSION, "the storage permission is missing")
        else failure(DataError.IO, "the library folder is not available")
    }

    private fun dataError(problem: ImportProblem): DataError = when (problem) {
        ImportProblem.NOT_READABLE -> DataError.IO
        ImportProblem.TOO_LARGE -> DataError.TOO_LARGE
        ImportProblem.SCHEMA_TOO_NEW -> DataError.SCHEMA_TOO_NEW
        ImportProblem.NOT_A_MODEL, ImportProblem.PATH_TRAVERSAL, ImportProblem.MISSING_MODEL_FILE, ImportProblem.CORRUPT_EXTRAS -> DataError.INVALID_FILE
    }

    /** Counts device reads: every transition of the connection into `Ready` and every `ConfigRead` event. */
    private class DeviceReadTracker {
        private var epoch = 0L
        private var wasReady = false

        @Synchronized
        fun noteConnection(state: ConnectionState) {
            val ready = state.isReady
            if (ready && !wasReady) epoch++
            wasReady = ready
        }

        @Synchronized
        fun noteRead() {
            epoch++
        }

        @Synchronized
        fun epoch(): Long = epoch
    }

    companion object {
        private const val INDEX_FILE = "library_index.json"
        private const val SHADOW_DIR = "shadow"
        private const val TEMP_DIR = "tmp"
        private const val EXPORT_WAIT_STEPS = 40
        private const val EXPORT_WAIT_STEP_MS = 25L
        private const val EMPTY_LIST_RETRY_MS = 300L

        /** `modelMatch` is an unsigned 32-bit field in the SDK. */
        private const val MAX_MODEL_MATCH = 0xFFFF_FFFFL

        /** Extras of a model that has no `extras.json` (a stock-app import). */
        val SDK_ONLY_EXTRAS: ModelExtras = ModelExtras(modelType = ModelType.OTHER)
    }
}
