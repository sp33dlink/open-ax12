package org.openax12.core.data

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.ModelType

/**
 * One model of the library as shown in the Models screen.
 *
 * @property id stable, opaque id of the model (survives renames while the app is installed)
 * @property name model name (= SDK model name = library folder name)
 * @property type from the model's extras ([ModelType.OTHER] for SDK-only models)
 * @property modelMatch ELRS model-match number
 * @property picturePath absolute path of the custom picture, or null → show the default picture of [type]
 * @property pictureVersion changes whenever the picture file changes (use as image cache key)
 * @property lastUsedMs wall-clock time the model was last selected; 0 = never
 * @property isCurrent true for the model loaded in the SDK working config (see [ModelRepository.currentModel])
 * @property sdk the SDK's metadata of the model file (private copy)
 * @property folderPath absolute path of the library folder, or null if the library is unavailable
 * @property hasExtras false for SDK-only models (stock-app imports) that have no `extras.json` yet
 * @property externallyModified the library folder was changed outside the app since the last sync
 */
data class ModelEntry(
    val id: String,
    val name: String,
    val type: ModelType = ModelType.OTHER,
    val modelMatch: Long = 0,
    val picturePath: String? = null,
    val pictureVersion: Long = 0,
    val lastUsedMs: Long = 0,
    val isCurrent: Boolean = false,
    val sdk: ModelInfo = ModelInfo(),
    val folderPath: String? = null,
    val hasExtras: Boolean = true,
    val externallyModified: Boolean = false,
)

/** Why a snapshot was taken. */
enum class SnapshotReason {
    /** Automatic copy made by every save to the device. */
    SAVE,

    /** Safety copy made before a snapshot was restored. */
    BEFORE_RESTORE,

    /** Safety copy made before an externally modified folder was re-imported. */
    BEFORE_REIMPORT,
}

/**
 * A timestamped copy of a model (SDK model file + extras). The newest
 * [ModelRepository.MAX_SNAPSHOTS] per model are kept.
 */
data class ModelSnapshot(
    val id: String,
    val modelId: String,
    val timeMs: Long,
    val reason: SnapshotReason = SnapshotReason.SAVE,
    val sizeBytes: Long = 0,
)

/** What kind of file an import source is. */
enum class ImportKind {
    /** An open-ax12 export (`.oax12`: zip with `model.json`, `extras.json`, `picture.jpg`). */
    ARCHIVE,

    /** A stock-app model export (`.json` / `.rcm`): imported as an SDK-only model without extras. */
    STOCK_MODEL,
}

/** Why an import source was rejected. */
enum class ImportProblem {
    /** The URI cannot be opened or read. */
    NOT_READABLE,

    /** Neither a valid archive nor a stock model file. */
    NOT_A_MODEL,

    /** An archive entry tries to escape the target folder (`..`, absolute path). */
    PATH_TRAVERSAL,

    /** The archive or one of its entries exceeds the size limits, or it has too many entries. */
    TOO_LARGE,

    /** The archive has no `model.json`. */
    MISSING_MODEL_FILE,

    /** `extras.json` is present but unreadable. */
    CORRUPT_EXTRAS,

    /** `extras.json` was written by a newer app version. */
    SCHEMA_TOO_NEW,
}

/** Result of validating an import source before anything is written. */
sealed interface ImportValidation {

    /**
     * The source can be imported.
     *
     * @property name model name found in the source
     * @property nameExists a model of that name already exists — the import needs another name
     * @property suggestedName a free name derived from [name] (`"Swift (2)"`)
     * @property type model type from the extras; null for [ImportKind.STOCK_MODEL]
     */
    data class Valid(
        val kind: ImportKind,
        val name: String,
        val nameExists: Boolean,
        val suggestedName: String,
        val type: ModelType? = null,
        val hasPicture: Boolean = false,
        val sizeBytes: Long = 0,
    ) : ImportValidation

    /** The source was rejected; nothing was written. */
    data class Invalid(val problem: ImportProblem, val detail: String = "") : ImportValidation
}

/** A library folder that was changed (or appeared) outside the app. */
data class ExternalChange(
    val modelId: String?,
    val name: String,
    val folderPath: String,
    /** True if the SDK does not know this model at all (a folder copied into the library). */
    val isNew: Boolean,
)

/** What [ModelRepository.reconcileLibrary] did. */
data class ReconcileReport(
    /** Models whose library folder was (re)written from the SDK copy. */
    val exported: List<String> = emptyList(),
    /** Library folders unknown to the SDK that were imported automatically. */
    val imported: List<String> = emptyList(),
    /** Folders modified externally; listed in [ModelRepository.externalChanges] for the user to decide. */
    val modifiedExternally: List<String> = emptyList(),
    /** English diagnostics for folders that could not be processed. */
    val problems: List<String> = emptyList(),
)

/**
 * Where the library lives.
 *
 * @property libraryRoot absolute path of `…/OpenAX12`; empty only if no storage at all is writable
 * @property usesPublicStorage true while the library is in `/sdcard/OpenAX12` (API ≤ 29 with the
 *   storage permission); false when it is in the app's external files directory
 * @property permissionGranted false on API ≤ 29 while the storage permission is still missing.
 *   The library then works from the app's external files directory (not reachable for other
 *   apps on the radio); once the permission is granted, [ModelRepository.refreshStorage] /
 *   [ModelRepository.reconcileLibrary] move it to `/sdcard/OpenAX12`. Always true on newer API
 *   levels, where no permission is involved.
 * @property freeBytes usable space on the volume of [libraryRoot]
 */
data class StorageInfo(
    val libraryRoot: String = "",
    val modelsDir: String = "",
    val soundsDir: String = "",
    val logsDir: String = "",
    val usesPublicStorage: Boolean = false,
    val permissionGranted: Boolean = true,
    val freeBytes: Long = 0,
) {
    /**
     * True if the library can be read and written. A missing storage permission does not make
     * it unavailable (see [permissionGranted]): ask for the permission when
     * `!permissionGranted`, independently of this flag.
     */
    val isAvailable: Boolean get() = libraryRoot.isNotEmpty()
}

/** Normalized crop rectangle of a picked image (fractions 0..1 of the image's width / height). */
data class PictureCropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f)

/**
 * The model library: the SDK's private model files mirrored into the user-accessible folder
 * `…/OpenAX12/models/<name>/` (`model.json` = SDK model file, `extras.json` = [ModelExtras],
 * `picture.jpg`), plus the per-model extras of the current model.
 *
 * The repository only deals with files and metadata. **It never writes to the device and never
 * reads from it** (`Radio.saveConfigToDevice` / `readConfigFromDevice` / `resetToDefaults` are
 * never called here) and it does not compile mix plans — the orchestration (select + write to
 * device, create from template, save, reload) is `ConfigSession` in `:core:runtime`, which is
 * what screens call for those flows. Functions that load a model file into the working config
 * ([selectModel], [reloadCurrentModel], [restoreSnapshot] / [acceptExternalChange] of the current
 * model) say so; screens use the `ConfigSession` counterpart instead.
 *
 * All functions are main-safe and may be called from any dispatcher. Expected failures are
 * returned as [DataResult.Failure], never thrown.
 *
 * ## Extras: saved vs. working copy
 * [currentExtras] is the *working* copy that the runtime engine and the UI read. There are two
 * ways to change it:
 * - [updateExtras]: applied to the working copy **and** the saved copy and written to
 *   `extras.json` at once. For things outside the Save flow: telemetry layout, home preferences,
 *   sensor overrides, alarms, notes, checklist, model type, picture.
 * - [stageExtras]: applied to the working copy only. `ConfigSession` uses it for everything
 *   edited in model setup (the exact list is in the `ConfigSession` KDoc); it becomes permanent
 *   with [onDeviceConfigSaved] and is undone by [revertStagedExtras].
 *
 * Extras of a model that is not the current one are changed with [updateExtrasOf].
 *
 * ## The library folder is the reference copy
 * `model.json` in a model's library folder always holds the last *intended* configuration of the
 * model: it is rewritten by [onDeviceConfigSaved], [restoreSnapshot], [acceptExternalChange],
 * [importModel], [createModel] and [duplicateModel], and never by a device read. The SDK's private
 * file can be overwritten behind the app's back (a device read writes the device configuration
 * into the current model file, see `Radio`), so [reconcileLibrary] uses the folder to repair it
 * (see [deviceInSync]).
 */
interface ModelRepository {

    /** All models, sorted by name. Empty until the first [reconcileLibrary]. */
    val models: StateFlow<List<ModelEntry>>

    /**
     * The model loaded in the SDK **working config** (resolved with `Radio.getCurrentModel()`);
     * null until known. It is what the setup screens edit — whether the radio really flies it is
     * told by [deviceInSync].
     */
    val currentModel: StateFlow<ModelEntry?>

    /**
     * False while the device has not confirmed the model that is loaded in the working config:
     * from every function that loads a model file ([selectModel], [reloadCurrentModel],
     * [restoreSnapshot] / [acceptExternalChange] of the current model) until [onDeviceConfigSaved]
     * or [onDeviceConfigWritten]. True initially and after a reconcile that followed a device read.
     *
     * The flag and the id of the affected model are **persisted** when they change. If the app
     * dies while it is false, the next start's device read overwrites that model's SDK file with
     * the configuration of the model the device still holds; [reconcileLibrary] then restores the
     * SDK file from the library folder's `model.json`, loads it into the working config again and
     * keeps this flag false, so the UI still asks the user to write the model to the radio.
     */
    val deviceInSync: StateFlow<Boolean>

    /** Working copy of the current model's extras (defaults while no model is known). */
    val currentExtras: StateFlow<ModelExtras>

    /** True while staged extras changes exist that are not written to `extras.json` yet. */
    val hasStagedExtras: StateFlow<Boolean>

    /** Library folders changed outside the app that wait for a user decision. */
    val externalChanges: StateFlow<List<ExternalChange>>

    /** Storage location and permission state. */
    val storage: StateFlow<StorageInfo>

    // -- Library ------------------------------------------------------------------------------

    /**
     * Synchronizes SDK model files and library folders: exports models that have no (or an
     * outdated) folder, imports folders unknown to the SDK, detects externally modified folders,
     * determines the current model and loads its extras. Called at startup after the device
     * config was read, and again when the storage permission was granted.
     *
     * A model whose SDK file and library folder differ is exported only if the SDK file is the
     * newer intended state; for the model marked by a false [deviceInSync] the direction is
     * reversed (the folder repairs the SDK file, see there). Never selects a different model.
     */
    suspend fun reconcileLibrary(): DataResult<ReconcileReport>

    /** Re-checks the storage permission / location and updates [storage]. Call after the permission dialog. */
    suspend fun refreshStorage(): StorageInfo

    /**
     * Re-imports the externally modified folder of [change] into the SDK (a
     * [SnapshotReason.BEFORE_REIMPORT] snapshot is taken first) and re-reads its extras.
     *
     * If the change concerns the **current** model this alone leaves the working config and the
     * device on the old state — screens call `ConfigSession.acceptExternalChange`, which wraps
     * this function and reloads the model (`ConfigSession.reloadCurrentModel`).
     */
    suspend fun acceptExternalChange(change: ExternalChange): DataResult<ModelEntry>

    /** Discards the external modification: the folder is overwritten with the app's copy. */
    suspend fun rejectExternalChange(change: ExternalChange): DataResult<Unit>

    // -- Model management ---------------------------------------------------------------------

    /**
     * Creates a new model named [name] with `blueprint.extras` as its extras. The model is
     * **not** selected and `blueprint.plan` is **not** applied here — use
     * `ConfigSession.createModel` for the complete flow.
     *
     * The SDK cannot create a model file with default settings (assumption 20): the new file is a
     * **copy of the current working config** (`Radio.createModelInfo` +
     * `Radio.saveWorkingConfigToModel`), including the previous model's curves, dual rates,
     * limits, reversed channels, timers and channel locks. It only becomes a clean model when the
     * blueprint's plan (`MixPlan.resetConfig`) is applied, which `ConfigSession.createModel`
     * always does. Do not call this function from a screen.
     */
    suspend fun createModel(name: String, blueprint: ModelBlueprint): DataResult<ModelEntry>

    /** Copies model [modelId] (config, extras, picture; no snapshots) under [newName]. */
    suspend fun duplicateModel(modelId: String, newName: String): DataResult<ModelEntry>

    /** Renames a model (SDK metadata and library folder). */
    suspend fun renameModel(modelId: String, newName: String): DataResult<ModelEntry>

    /** Sets the ELRS model-match number of a model. */
    suspend fun setModelMatch(modelId: String, modelMatch: Long): DataResult<ModelEntry>

    /**
     * Deletes a model: SDK file, library folder and snapshots. The current model cannot be
     * deleted ([DataError.IS_CURRENT_MODEL]).
     */
    suspend fun deleteModel(modelId: String): DataResult<Unit>

    /**
     * Makes [modelId] the current model: loads its file into the SDK working config
     * (`Radio.loadModelFile`) and its extras into [currentExtras] (staged changes of the previous
     * model are dropped). The device is **not** written — `ConfigSession.selectModel` does that;
     * [deviceInSync] becomes false until then.
     */
    suspend fun selectModel(modelId: String): DataResult<ModelEntry>

    /**
     * Loads the current model's SDK file into the working config again and re-reads its extras
     * (staged changes are dropped). Used after the file was replaced ([acceptExternalChange],
     * backup restore) and to drop working-config edits while the device is not in sync. The
     * device is not written; [deviceInSync] becomes false. Screens use
     * `ConfigSession.reloadCurrentModel`.
     */
    suspend fun reloadCurrentModel(): DataResult<ModelEntry>

    // -- Extras -------------------------------------------------------------------------------

    /**
     * Applies [transform] to the working and the saved extras of the current model and persists
     * immediately. Returns the new working copy.
     */
    suspend fun updateExtras(transform: (ModelExtras) -> ModelExtras): ModelExtras

    /** Applies [transform] to the working copy only (see class doc). Returns the new working copy. */
    suspend fun stageExtras(transform: (ModelExtras) -> ModelExtras): ModelExtras

    /** Drops staged changes: the working copy becomes the saved copy again. */
    suspend fun revertStagedExtras(): ModelExtras

    /** Reads the saved extras of any model (defaults for SDK-only models). */
    suspend fun readExtras(modelId: String): DataResult<ModelExtras>

    /**
     * Applies [transform] to the **saved** extras of any model and persists immediately; creates
     * `extras.json` for an SDK-only model (stock import) and refreshes [models] (type, picture).
     * For the current model this behaves exactly like [updateExtras] (working and saved copy).
     * Used by the model detail screen for type, notes and checklist of models that are not loaded.
     */
    suspend fun updateExtrasOf(modelId: String, transform: (ModelExtras) -> ModelExtras): DataResult<ModelExtras>

    /**
     * Save hook: call after the working config was written to the device
     * (`Radio.saveConfigToDevice` completed) **as a user save**. Persists staged extras, exports
     * the SDK model file to the library folder, records a snapshot (keeping the newest
     * [MAX_SNAPSHOTS]) and sets [deviceInSync].
     */
    suspend fun onDeviceConfigSaved(): DataResult<ModelSnapshot>

    /**
     * Write hook without Save semantics: call after `Radio.saveConfigToDevice` completed for a
     * model select, a reload, a rollback or a system-configuration change. Sets [deviceInSync],
     * updates `lastUsedMs` and refreshes the library export of the SDK model file; staged extras
     * are **not** persisted and no snapshot is taken.
     */
    suspend fun onDeviceConfigWritten(): DataResult<ModelEntry>

    // -- Picture ------------------------------------------------------------------------------

    /**
     * Sets the custom picture of a model: decodes [source], crops it to [crop] (the crop screen
     * delivers a rectangle of aspect `MODEL_PICTURE_ASPECT`; any other rectangle is centre-cropped
     * to it), scales it to `MODEL_PICTURE_WIDTH_PX` × `MODEL_PICTURE_HEIGHT_PX` and stores it as
     * `MODEL_PICTURE_FILE_NAME` in the model folder (constants in `:core:model`). Works for any
     * model, current or not.
     */
    suspend fun setPicture(modelId: String, source: Uri, crop: PictureCropRect = PictureCropRect()): DataResult<ModelEntry>

    /** Removes the custom picture (the default picture of the model type is shown again). */
    suspend fun removePicture(modelId: String): DataResult<ModelEntry>

    // -- Export / import ----------------------------------------------------------------------

    /**
     * Exports a model as a `.oax12` archive into the app's share cache and returns a
     * `content://` URI (FileProvider) that can be put into an `ACTION_SEND` intent.
     */
    suspend fun exportModel(modelId: String): DataResult<Uri>

    /** Validates an import source without writing anything. */
    suspend fun inspectImport(source: Uri): ImportValidation

    /**
     * Imports a model from [source] after validating it again. Never overwrites: if a model
     * named [name] (default: the name in the source) exists the result is [DataError.NAME_EXISTS].
     * The imported model is not selected.
     */
    suspend fun importModel(source: Uri, name: String? = null): DataResult<ModelEntry>

    // -- Snapshots ----------------------------------------------------------------------------

    /** Snapshots of a model, newest first. */
    suspend fun listSnapshots(modelId: String): List<ModelSnapshot>

    /**
     * Restores a snapshot into its model (a [SnapshotReason.BEFORE_RESTORE] snapshot of the
     * present state is taken first). If the model is the current one its config is loaded into
     * the working config and its extras become the working copy, but the device is not written
     * ([deviceInSync] becomes false) — use `ConfigSession.restoreSnapshot` for the complete flow.
     */
    suspend fun restoreSnapshot(snapshot: ModelSnapshot): DataResult<ModelEntry>

    /** Deletes one snapshot. */
    suspend fun deleteSnapshot(snapshot: ModelSnapshot): DataResult<Unit>

    companion object {
        /** Snapshots kept per model. */
        const val MAX_SNAPSHOTS: Int = 20

        /** File extension of exported model archives (without dot). */
        const val EXPORT_EXTENSION: String = "oax12"

        /** Name of the library root folder. */
        const val LIBRARY_FOLDER: String = "OpenAX12"
    }
}
