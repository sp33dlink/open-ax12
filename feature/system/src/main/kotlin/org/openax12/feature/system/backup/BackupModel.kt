package org.openax12.feature.system.backup

import android.net.Uri
import org.openax12.core.data.BackupInfo
import org.openax12.core.data.DataError
import org.openax12.core.data.RestoreMode
import org.openax12.core.data.RestoreReport
import org.openax12.core.data.StorageInfo
import org.openax12.feature.system.common.Feedback

/** What the backup screen is working on. */
internal enum class BackupWork { NONE, CREATING, INSPECTING, RESTORING, RELOADING }

/** A backup file the user picked and the library has validated. */
internal data class InspectedBackup(val uri: Uri, val info: BackupInfo)

/** Everything the backup screen shows. */
internal data class BackupUiState(
    val storage: StorageInfo = StorageInfo(),
    val work: BackupWork = BackupWork.NONE,
    val includeSettings: Boolean = true,
    val inspected: InspectedBackup? = null,
    val restoreMode: RestoreMode = RestoreMode.KEEP_EXISTING,
    val restoreSettings: Boolean = false,
    val report: RestoreReport? = null,
    val linkUp: Boolean = false,
    val dirty: Boolean = false,
    /** The user pressed Restore: waiting for the confirmation. */
    val confirmRestore: Boolean = false,
    /** The restore replaced the current model: waiting for the user to load it into the radio. */
    val reloadPending: Boolean = false,
) {
    /** Nothing is running. */
    val idle: Boolean get() = work == BackupWork.NONE

    /** Restoring needs the double confirmation: it replaces existing models. */
    val overwrites: Boolean get() = restoreMode == RestoreMode.OVERWRITE_EXISTING
}

/** A finished backup the screen hands to the share sheet. */
internal data class BackupShare(val uri: Uri)

/** The user-facing message of a failed backup or restore. */
internal fun failureFeedback(error: DataError, restoring: Boolean): Feedback = when (error) {
    DataError.STORAGE_PERMISSION -> Feedback.STORAGE_PERMISSION
    DataError.INVALID_FILE, DataError.SCHEMA_TOO_NEW, DataError.TOO_LARGE, DataError.NOT_FOUND ->
        if (restoring) Feedback.INVALID_BACKUP else Feedback.BACKUP_FAILED
    DataError.BUSY -> Feedback.BUSY
    else -> if (restoring) Feedback.RESTORE_FAILED else Feedback.BACKUP_FAILED
}
