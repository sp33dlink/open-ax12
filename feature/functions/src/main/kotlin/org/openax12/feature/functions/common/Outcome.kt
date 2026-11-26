package org.openax12.feature.functions.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.designsystem.LocalAxToastState
import org.openax12.core.runtime.SessionError
import org.openax12.feature.functions.R

/** Result of an apply / save action, shown once as a toast. */
internal sealed interface Outcome {
    /** The settings were applied to the working config (still unsaved). */
    data object Applied : Outcome

    /** Applying failed with [error]. */
    data class Failed(val error: SessionError) : Outcome
}

/**
 * Shows [outcome] once as a toast and reports it consumed. Put it next to the screen that owns
 * the outcome.
 */
@Composable
internal fun OutcomeEffect(outcome: Outcome?, onConsumed: () -> Unit) {
    val toast = LocalAxToastState.current
    val applied = stringResource(R.string.fn_applied)
    val failed = (outcome as? Outcome.Failed)?.let { sessionErrorText(it.error) }
    LaunchedEffect(outcome) {
        when (outcome) {
            Outcome.Applied -> toast.show(applied, AxToastKind.Success)
            is Outcome.Failed -> toast.show(failed.orEmpty(), AxToastKind.Error)
            null -> return@LaunchedEffect
        }
        onConsumed()
    }
}

/** Toast text of a failed session operation. */
@Composable
internal fun sessionErrorText(error: SessionError): String = stringResource(
    when (error) {
        SessionError.RADIO_NOT_READY -> R.string.fn_err_radio_not_ready
        SessionError.DEVICE_TIMEOUT -> R.string.fn_err_timeout
        SessionError.BUSY -> R.string.fn_err_busy
        SessionError.PLAN_REJECTED -> R.string.fn_err_plan_rejected
        SessionError.PLAN_OPERATION_FAILED -> R.string.fn_err_plan_failed
        SessionError.DATA -> R.string.fn_err_data
        SessionError.MODEL_NOT_FOUND -> R.string.fn_err_model
        SessionError.UNSAVED_CHANGES -> R.string.fn_err_unsaved
        SessionError.DEVICE_WRITE_PENDING -> R.string.fn_err_write_pending
        SessionError.CONFIG_INCOMPLETE -> R.string.fn_err_config_incomplete
        // Only `ConfigSession.updateSystemConfig` produces these; no function flow does.
        SessionError.SYSTEM_CONFIG_UNAVAILABLE,
        SessionError.SYSTEM_CONFIG_NOT_APPLIED,
        SessionError.RF_OFF_NOT_CONFIRMED,
        -> R.string.fn_err_plan_failed
    },
)
