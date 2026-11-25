package org.openax12.app.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openax12.app.R
import org.openax12.app.showStockAppActions
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxDialog
import org.openax12.core.designsystem.AxDialogMessage
import org.openax12.core.designsystem.AxErrorState
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxLoadingState
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.radio.ConnectionError

/**
 * The hint under a connection error, or null when there is none for it. With [stockInstalled] the
 * stock-app hint names the buttons of the error screen; without the stock app on the device it
 * only says that another app holds the port.
 */
@StringRes
internal fun connectionHintRes(reason: ConnectionError, stockInstalled: Boolean = true): Int? = when {
    reason.mayBeStockAppConflict ->
        if (stockInstalled) R.string.startup_error_stock_hint else R.string.startup_error_port_hint
    reason is ConnectionError.NativeLibraryUnavailable -> R.string.startup_error_native_hint
    reason is ConnectionError.FirmwareMismatch -> R.string.startup_error_firmware_hint
    else -> null
}

/**
 * Spinner with the current startup step ("Connecting to the radio…"). With [showStockHint] a note
 * and a "Close stock app" button appear below it: the connection takes suspiciously long, the
 * stock app may still hold the serial port.
 */
@Composable
internal fun StartupLoadingContent(
    @StringRes textRes: Int,
    modifier: Modifier = Modifier,
    showStockHint: Boolean = false,
    onCloseStock: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AxLoadingState(text = stringResource(textRes))
            if (showStockHint) {
                Text(
                    text = stringResource(R.string.startup_slow_hint),
                    style = AxTheme.typography.body,
                    color = AxTheme.colors.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .widthIn(max = 520.dp)
                        .background(AxTheme.colors.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                AxButton(
                    text = stringResource(R.string.stock_close_button),
                    onClick = onCloseStock,
                    variant = AxButtonVariant.Primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * Full-screen startup error: what failed, the stock-app hint for
 * [ConnectionError.mayBeStockAppConflict], Retry and "Continue without radio". When the error may
 * be the stock app and it is installed ([stockInstalled]) the primary action is "Close stock app"
 * (closes it and retries) next to "Open app settings" (its App info page, for Force stop).
 * Stateless so that it can be rendered in screenshot tests.
 */
@Composable
internal fun StartupErrorContent(
    reason: ConnectionError,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    stockInstalled: Boolean = false,
    onCloseStock: () -> Unit = {},
    onOpenStockSettings: () -> Unit = {},
) {
    val colors = AxTheme.colors
    val stockActions = showStockAppActions(reason, stockInstalled)
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AxErrorState(
                title = stringResource(R.string.startup_error_title),
                message = reason.message,
                actionLabel = null,
                icon = AxIcons.Error,
            )
            connectionHintRes(reason, stockInstalled)?.let { hint ->
                Text(
                    text = stringResource(hint),
                    style = AxTheme.typography.body,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .widthIn(max = 560.dp)
                        .background(colors.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Text(
                text = stringResource(R.string.startup_error_footer),
                style = AxTheme.typography.label,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .widthIn(max = 520.dp),
            )
            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AxButton(text = stringResource(R.string.startup_error_continue), onClick = onContinue, variant = AxButtonVariant.Outlined)
                if (stockActions) {
                    AxButton(text = stringResource(R.string.stock_open_settings), onClick = onOpenStockSettings, variant = AxButtonVariant.Outlined)
                    AxButton(text = stringResource(R.string.startup_error_retry), onClick = onRetry, variant = AxButtonVariant.Outlined)
                    AxButton(text = stringResource(R.string.stock_close_button), onClick = onCloseStock, variant = AxButtonVariant.Primary)
                } else {
                    AxButton(text = stringResource(R.string.startup_error_retry), onClick = onRetry, variant = AxButtonVariant.Primary)
                }
            }
        }
    }
}

/** Dialog shown over the running UI when the connection breaks after a successful start. */
@Composable
internal fun ConnectionLostDialog(
    reason: ConnectionError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    stockInstalled: Boolean = true,
) {
    AxDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.connection_lost_title),
        icon = AxIcons.Error,
        iconTint = AxTheme.colors.error,
        width = 480.dp,
        buttons = {
            AxButton(text = stringResource(R.string.connection_lost_dismiss), onClick = onDismiss, variant = AxButtonVariant.Text)
            AxButton(text = stringResource(R.string.startup_error_retry), onClick = onRetry, variant = AxButtonVariant.Primary)
        },
    ) {
        AxDialogMessage(reason.message)
        connectionHintRes(reason, stockInstalled)?.let { AxDialogMessage(stringResource(it)) }
    }
}
