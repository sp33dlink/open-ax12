package org.openax12.feature.system.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.data.DataResult
import org.openax12.core.data.LibraryBackup
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.RestoreMode
import org.openax12.core.radio.Radio
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.DiagnosticsLog
import org.openax12.feature.system.common.Feedback
import org.openax12.feature.system.common.edit
import org.openax12.feature.system.common.linkUpFlow
import org.openax12.feature.system.common.toFeedback

/**
 * Backup and restore of the whole library through [LibraryBackup]. After a restore that
 * replaced the current model, the model is reloaded through [ConfigSession.reloadCurrentModel]
 * (after the user confirmed, because that writes to the radio).
 */
internal class BackupViewModel(
    radio: Radio,
    private val backup: LibraryBackup,
    models: ModelRepository,
    private val session: ConfigSession,
    private val diagnostics: DiagnosticsLog,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val local = MutableStateFlow(BackupUiState())
    private val feedbackChannel = Channel<Feedback>(Channel.BUFFERED)
    private val shareChannel = Channel<BackupShare>(Channel.BUFFERED)

    /** One-shot outcome messages for toasts. */
    val feedback: Flow<Feedback> = feedbackChannel.receiveAsFlow()

    /** Finished backups to hand to the share sheet. */
    val shares: Flow<BackupShare> = shareChannel.receiveAsFlow()

    /** The state of the screen. */
    val state: StateFlow<BackupUiState> = combine(
        local,
        models.storage,
        radio.linkUpFlow(),
        session.dirty,
    ) { s, storage, linkUp, dirty -> s.copy(storage = storage, linkUp = linkUp, dirty = dirty) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    /** Includes the app settings in the next backup. */
    fun setIncludeSettings(value: Boolean) = local.edit { it.copy(includeSettings = value) }

    /** Creates a backup in the share cache and hands it to the share sheet. */
    fun createForSharing() = create(destination = null)

    /** Creates a backup into the document the user chose. */
    fun createInto(destination: Uri) = create(destination)

    private fun create(destination: Uri?) {
        if (!local.value.idle) return
        scope.launch {
            local.edit { it.copy(work = BackupWork.CREATING) }
            val result = backup.backup(destination, local.value.includeSettings)
            local.edit { it.copy(work = BackupWork.NONE) }
            when (result) {
                is DataResult.Success -> {
                    if (destination == null) shareChannel.trySend(BackupShare(result.value))
                    feedbackChannel.trySend(Feedback.BACKUP_READY)
                }
                is DataResult.Failure -> {
                    diagnostics.record(DiagnosticsLog.SOURCE_LIBRARY, "backup failed: ${result.error} ${result.detail}")
                    feedbackChannel.trySend(failureFeedback(result.error, restoring = false))
                }
            }
        }
    }

    /** Validates a picked backup and shows what it contains. */
    fun inspect(source: Uri) {
        if (!local.value.idle) return
        scope.launch {
            local.edit { it.copy(work = BackupWork.INSPECTING, inspected = null, report = null) }
            val result = backup.inspect(source)
            when (result) {
                is DataResult.Success -> local.edit {
                    it.copy(
                        work = BackupWork.NONE,
                        inspected = InspectedBackup(source, result.value),
                        restoreSettings = result.value.includesSettings,
                    )
                }
                is DataResult.Failure -> {
                    local.edit { it.copy(work = BackupWork.NONE) }
                    diagnostics.record(DiagnosticsLog.SOURCE_LIBRARY, "backup inspect failed: ${result.error} ${result.detail}")
                    feedbackChannel.trySend(failureFeedback(result.error, restoring = true))
                }
            }
        }
    }

    /** Chooses how the backup is merged. */
    fun setRestoreMode(mode: RestoreMode) = local.edit { it.copy(restoreMode = mode) }

    /** Chooses whether the app settings of the backup are restored too. */
    fun setRestoreSettings(value: Boolean) = local.edit { it.copy(restoreSettings = value) }

    /** Restore pressed: asks for the confirmation. */
    fun requestRestore() = local.edit { it.copy(confirmRestore = true) }

    /** The confirmation was dismissed. */
    fun dismissRestore() = local.edit { it.copy(confirmRestore = false) }

    /** Runs the restore after the confirmation. */
    fun restore() {
        val inspected = local.value.inspected ?: return
        if (!local.value.idle) return
        scope.launch {
            val mode = local.value.restoreMode
            local.edit { it.copy(confirmRestore = false, work = BackupWork.RESTORING) }
            val result = backup.restore(inspected.uri, mode, local.value.restoreSettings)
            when (result) {
                is DataResult.Success -> {
                    local.edit {
                        it.copy(work = BackupWork.NONE, inspected = null, report = result.value, reloadPending = result.value.currentModelReplaced)
                    }
                    feedbackChannel.trySend(Feedback.RESTORE_DONE)
                }
                is DataResult.Failure -> {
                    local.edit { it.copy(work = BackupWork.NONE) }
                    diagnostics.record(DiagnosticsLog.SOURCE_LIBRARY, "restore failed: ${result.error} ${result.detail}")
                    feedbackChannel.trySend(failureFeedback(result.error, restoring = true))
                }
            }
        }
    }

    /** Loads the restored current model into the radio (writes to the device). */
    fun reloadCurrentModel() {
        scope.launch {
            local.edit { it.copy(work = BackupWork.RELOADING) }
            val result = session.reloadCurrentModel(discardChanges = true)
            local.edit { it.copy(work = BackupWork.NONE, reloadPending = !result.isSuccess) }
            feedbackChannel.trySend(result.toFeedback())
        }
    }

    /** The user chose to keep the radio as it is for now. */
    fun postponeReload() = local.edit { it.copy(reloadPending = false) }
}
