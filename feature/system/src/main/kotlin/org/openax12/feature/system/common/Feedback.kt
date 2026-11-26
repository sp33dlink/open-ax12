package org.openax12.feature.system.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.designsystem.LocalAxToastState
import org.openax12.core.runtime.SessionError
import org.openax12.core.runtime.SessionResult
import org.openax12.feature.system.R

/**
 * Short outcome messages a ViewModel hands to its screen (the ViewModel has no resources).
 * The screen shows them as toasts with [FeedbackEffect].
 */
internal enum class Feedback(val kind: AxToastKind, @param:StringRes val text: Int) {
    DONE(AxToastKind.Success, R.string.fb_done),
    RADIO_NOT_READY(AxToastKind.Warning, R.string.fb_radio_not_ready),
    UNSAVED_CHANGES(AxToastKind.Warning, R.string.fb_unsaved_changes),
    DEVICE_WRITE_PENDING(AxToastKind.Warning, R.string.fb_device_write_pending),
    BUSY(AxToastKind.Warning, R.string.fb_busy),
    SYSTEM_UNAVAILABLE(AxToastKind.Warning, R.string.fb_system_unavailable),
    SYSTEM_NOT_APPLIED(AxToastKind.Error, R.string.fb_system_not_applied),
    RF_OFF_UNCONFIRMED(AxToastKind.Warning, R.string.fb_rf_off_unconfirmed),
    CONFIG_INCOMPLETE(AxToastKind.Error, R.string.fb_config_incomplete),
    TIMEOUT(AxToastKind.Error, R.string.fb_timeout),
    FAILED(AxToastKind.Error, R.string.fb_failed),
    REJECTED(AxToastKind.Error, R.string.fb_rejected),
    NO_SOUND_FILES(AxToastKind.Info, R.string.fb_no_sound_files),
    LINK_DOWN(AxToastKind.Info, R.string.fb_link_down),
    BACKUP_READY(AxToastKind.Success, R.string.fb_backup_ready),
    BACKUP_FAILED(AxToastKind.Error, R.string.fb_backup_failed),
    RESTORE_DONE(AxToastKind.Success, R.string.fb_restore_done),
    RESTORE_FAILED(AxToastKind.Error, R.string.fb_restore_failed),
    INVALID_BACKUP(AxToastKind.Error, R.string.fb_invalid_backup),
    STORAGE_PERMISSION(AxToastKind.Warning, R.string.fb_storage_permission),
    REPORT_SAVED(AxToastKind.Success, R.string.fb_report_saved),
    REPORT_FAILED(AxToastKind.Error, R.string.fb_report_failed),
    SIMULATOR_ONLY(AxToastKind.Info, R.string.fb_simulator_only),
}

/** The user-facing outcome of a [ConfigSession][org.openax12.core.runtime.ConfigSession] call. */
internal fun SessionResult<*>.toFeedback(): Feedback = when (this) {
    is SessionResult.Success -> Feedback.DONE
    is SessionResult.Failure -> when (error) {
        SessionError.RADIO_NOT_READY -> Feedback.RADIO_NOT_READY
        SessionError.DEVICE_TIMEOUT -> Feedback.TIMEOUT
        SessionError.BUSY -> Feedback.BUSY
        SessionError.UNSAVED_CHANGES -> Feedback.UNSAVED_CHANGES
        SessionError.DEVICE_WRITE_PENDING -> Feedback.DEVICE_WRITE_PENDING
        SessionError.SYSTEM_CONFIG_UNAVAILABLE -> Feedback.SYSTEM_UNAVAILABLE
        SessionError.SYSTEM_CONFIG_NOT_APPLIED -> Feedback.SYSTEM_NOT_APPLIED
        SessionError.RF_OFF_NOT_CONFIRMED -> Feedback.RF_OFF_UNCONFIRMED
        SessionError.CONFIG_INCOMPLETE -> Feedback.CONFIG_INCOMPLETE
        else -> Feedback.FAILED
    }
}

/** Shows every [Feedback] of [events] as a toast on the surrounding canvas. */
@Composable
internal fun FeedbackEffect(events: Flow<Feedback>) {
    val toast = LocalAxToastState.current
    val context = LocalContext.current
    LaunchedEffect(events) {
        events.collect { feedback -> toast.show(context.getString(feedback.text), feedback.kind) }
    }
}
