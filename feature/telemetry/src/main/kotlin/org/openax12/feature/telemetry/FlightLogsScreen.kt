package org.openax12.feature.telemetry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.data.FlightLogFile
import org.openax12.core.data.FlightLogState
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxLoadingState
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.ConfirmDialog
import java.text.DateFormat
import java.util.Date

/**
 * The CSV flight logs: a record button, the list with share and delete, and the state of the
 * recording.
 *
 * @param onRequestStoragePermission asks for the storage permission (API 28 / 29)
 */
@Composable
internal fun FlightLogsScreen(
    state: FlightLogsState,
    onAction: (FlightLogsAction) -> Unit,
    onBack: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val recording = state.recording
    Column(modifier.fillMaxSize()) {
        TelemetryTopBar(
            title = stringResource(R.string.telemetry_logs_title),
            onBack = onBack,
            actions = {
                if (recording.isRecording) {
                    ChromeButton(
                        text = stringResource(R.string.telemetry_logs_stop),
                        onClick = { onAction(FlightLogsAction.StopLogging) },
                        icon = AxIcons.Stop,
                        filled = true,
                    )
                } else {
                    ChromeButton(
                        text = stringResource(R.string.telemetry_logs_start),
                        onClick = { onAction(FlightLogsAction.StartLogging) },
                        icon = AxIcons.Play,
                        enabled = state.storage.isAvailable,
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusBanner(state, onRequestStoragePermission)
            when {
                !state.loaded -> AxLoadingState(modifier = Modifier.weight(1f).fillMaxWidth())
                state.logs.isEmpty() -> AxEmptyState(
                    title = stringResource(R.string.telemetry_logs_empty),
                    message = stringResource(R.string.telemetry_logs_empty_message),
                    icon = AxIcons.History,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                else -> AxCard(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    cornerRadius = AxTheme.shapes.panel,
                    elevation = 4.dp,
                    clipContent = true,
                ) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.logs, key = { it.path }) { log ->
                            LogRow(
                                log = log,
                                isRecording = (recording as? FlightLogState.Recording)?.file?.path == log.path,
                                onShare = { onAction(FlightLogsAction.Share(log)) },
                                onDelete = { confirmDelete = log.path },
                            )
                            AxDivider()
                        }
                    }
                }
            }
            if (state.storage.logsDir.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.telemetry_logs_folder, state.storage.logsDir),
                    style = AxTheme.typography.labelSmall,
                    color = AxTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
    val toDelete = confirmDelete?.let { path -> state.logs.firstOrNull { it.path == path } }
    if (toDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.telemetry_logs_delete_title),
            message = stringResource(R.string.telemetry_logs_delete_message, toDelete.name),
            confirmLabel = stringResource(R.string.telemetry_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = null
                onAction(FlightLogsAction.Delete(toDelete))
            },
            onDismiss = { confirmDelete = null },
        )
    } else if (confirmDelete != null) {
        confirmDelete = null
    }
}

@Composable
private fun StatusBanner(state: FlightLogsState, onRequestStoragePermission: () -> Unit) {
    val colors = AxTheme.colors
    val recording = state.recording
    when {
        !state.storage.permissionGranted -> Banner(
            color = colors.warning,
            text = stringResource(R.string.telemetry_logs_permission),
        ) {
            AxButton(
                text = stringResource(R.string.telemetry_logs_allow_storage),
                onClick = onRequestStoragePermission,
                variant = AxButtonVariant.Primary,
            )
        }
        recording is FlightLogState.Recording -> Banner(
            color = colors.error,
            text = stringResource(R.string.telemetry_logs_recording, recording.file.name, recording.rows),
            dot = true,
        )
        recording is FlightLogState.Failed -> Banner(
            color = colors.error,
            text = stringResource(R.string.telemetry_logs_failed, recording.detail.ifBlank { recording.error.name }),
        )
        !state.storage.isAvailable -> Banner(color = colors.warning, text = stringResource(R.string.telemetry_logs_no_storage))
    }
}

@Composable
private fun Banner(
    color: androidx.compose.ui.graphics.Color,
    text: String,
    dot: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.2f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (dot) Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(text = text, style = AxTheme.typography.body, color = AxTheme.colors.text, modifier = Modifier.weight(1f), maxLines = 2)
        if (action != null) action()
    }
}

@Composable
private fun LogRow(log: FlightLogFile, isRecording: Boolean, onShare: () -> Unit, onDelete: () -> Unit) {
    val colors = AxTheme.colors
    val whenText = remember(log.startedMs) {
        if (log.startedMs > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(log.startedMs)) else ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(log.name, style = AxTheme.typography.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOf(log.modelName, whenText, formatFileSize(log.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "),
                style = AxTheme.typography.label,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isRecording) {
            StatusTag(stringResource(R.string.telemetry_logs_tag_recording), colors.error, Modifier.padding(end = 8.dp))
        } else {
            AxIconButton(AxIcons.Delete, stringResource(R.string.telemetry_logs_cd_delete, log.name), onDelete, tint = colors.textMuted)
        }
        AxIconButton(AxIcons.Share, stringResource(R.string.telemetry_logs_cd_share, log.name), onShare, tint = colors.accent)
    }
}

/** `812 B`, `14.2 kB`, `3.1 MB`. */
internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f kB", bytes / 1024.0)
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}
