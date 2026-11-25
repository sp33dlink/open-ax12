package org.openax12.core.data

import android.net.Uri

/** Summary of a library backup archive. */
data class BackupInfo(
    val createdMs: Long = 0,
    val appVersion: String = "",
    val modelNames: List<String> = emptyList(),
    val soundFiles: Int = 0,
    val includesSettings: Boolean = false,
    val sizeBytes: Long = 0,
)

/** How a backup is merged into the existing library. */
enum class RestoreMode {
    /** Add models that do not exist yet; existing models stay untouched. */
    KEEP_EXISTING,

    /** Add missing models and replace existing models of the same name (a snapshot is taken first). */
    OVERWRITE_EXISTING,
}

/** What a restore did. */
data class RestoreReport(
    val restored: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val settingsRestored: Boolean = false,
    /** True if the files of the current model were replaced; the caller must reload it (see [LibraryBackup.restore]). */
    val currentModelReplaced: Boolean = false,
)

/**
 * Backup and restore of the whole library (all models with extras, pictures and snapshots, user
 * sounds, optionally the app settings) as one zip. Main-safe.
 */
interface LibraryBackup {

    /**
     * Writes a backup zip to [destination] (a document URI from `ACTION_CREATE_DOCUMENT`), or —
     * when null — into the app's share cache, returning a shareable `content://` URI.
     */
    suspend fun backup(destination: Uri? = null, includeSettings: Boolean = true): DataResult<Uri>

    /** Validates a backup zip (no path traversal, size limits, manifest) without writing anything. */
    suspend fun inspect(source: Uri): DataResult<BackupInfo>

    /**
     * Restores a backup and reconciles the library (`ModelRepository.reconcileLibrary()` is run
     * internally). The current model is never replaced while the receiver link is up (it is
     * listed in [RestoreReport.skipped]).
     *
     * If the current model's files were replaced ([RestoreReport.currentModelReplaced]) the
     * working config and the device still hold the old model: the caller must then call
     * `ConfigSession.reloadCurrentModel(discardChanges = true)` (after the usual "link is up"
     * confirmation), otherwise the next Save would overwrite the restored model.
     */
    suspend fun restore(source: Uri, mode: RestoreMode = RestoreMode.KEEP_EXISTING, restoreSettings: Boolean = false): DataResult<RestoreReport>
}
