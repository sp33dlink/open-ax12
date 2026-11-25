package org.openax12.feature.system.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.openax12.core.audio.TtsStatus
import org.openax12.core.radio.ConnectionState
import org.openax12.feature.system.R

/**
 * Explains why the radio cannot be used: not connected yet, or the connection error with the
 * "close the stock RadioMaster app" hint when [org.openax12.core.radio.ConnectionError.mayBeStockAppConflict]
 * (SPEC section 8). Shows nothing while the connection is ready.
 */
@Composable
internal fun ConnectionNotice(connection: ConnectionState, modifier: Modifier = Modifier) {
    when (connection) {
        ConnectionState.Stopped, ConnectionState.Initializing, ConnectionState.WaitingForDevice ->
            NoticeBox(stringResource(R.string.conn_not_ready), modifier, NoticeKind.Info)
        ConnectionState.Ready -> Unit
        is ConnectionState.Error -> {
            val reason = connection.reason
            val text = if (reason.mayBeStockAppConflict) {
                stringResource(R.string.conn_error_stock, reason.message)
            } else {
                stringResource(R.string.conn_error, reason.message)
            }
            NoticeBox(text, modifier, NoticeKind.Error)
        }
    }
}

/** Status of the text-to-speech engine, with the clear warning the spec asks for when it does not work. */
@Composable
internal fun TtsNotice(status: TtsStatus, modifier: Modifier = Modifier) {
    when (status) {
        TtsStatus.Initializing -> NoticeBox(stringResource(R.string.tts_initializing), modifier, NoticeKind.Info)
        is TtsStatus.Ready -> if (status.requestedLanguageAvailable) {
            NoticeBox(stringResource(R.string.tts_ready, status.engine, status.language), modifier, NoticeKind.Info)
        } else {
            NoticeBox(stringResource(R.string.tts_language_fallback, status.language), modifier, NoticeKind.Warning)
        }
        TtsStatus.NoEngine -> NoticeBox(stringResource(R.string.tts_no_engine), modifier, NoticeKind.Error)
        is TtsStatus.LanguageMissing -> NoticeBox(stringResource(R.string.tts_language_missing, status.engine, status.language), modifier, NoticeKind.Warning)
        is TtsStatus.Error -> NoticeBox(stringResource(R.string.tts_error, status.message), modifier, NoticeKind.Error)
    }
}
