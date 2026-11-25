package org.openax12.feature.system.backup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import org.openax12.core.data.OpenAx12FileProvider
import org.openax12.core.data.RestoreMode
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxLoadingState
import org.openax12.core.designsystem.AxSectionHeader
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.DoubleConfirmDialog
import org.openax12.core.model.AppSettings
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.navigation.LocalShellActions
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.system.R
import org.openax12.feature.system.common.FeedbackEffect
import org.openax12.feature.system.common.NoticeBox
import org.openax12.feature.system.common.NoticeKind
import org.openax12.feature.system.common.SystemBar
import org.openax12.feature.system.common.formatBytes
import org.openax12.feature.system.common.graphViewModel

/** Stateful entry content of `Backup`: wires the document pickers and the share sheet. */
@Composable
internal fun BackupRoute() {
    val graph = LocalAppGraph.current
    val viewModel = graphViewModel(graph) {
        BackupViewModel(it.radio, it.backup, it.models, it.configSession, it.diagnostics)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val shell = LocalShellActions.current
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.backup_share_title)
    FeedbackEffect(viewModel.feedback)
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) viewModel.createInto(uri)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.inspect(uri)
    }
    LaunchedEffect(viewModel) {
        viewModel.shares.collect { share ->
            val send = Intent(Intent.ACTION_SEND)
                .setType(OpenAx12FileProvider.MIME_ZIP)
                .putExtra(Intent.EXTRA_STREAM, share.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(Intent.createChooser(send, shareTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: ActivityNotFoundException) {
                // No app accepts the file: the backup stays in the share cache.
            }
        }
    }
    val fileName = stringResource(R.string.backup_file_name)
    BackupScreen(
        state = state,
        onBack = { navigator.back() },
        onIncludeSettings = viewModel::setIncludeSettings,
        onShare = viewModel::createForSharing,
        onSaveToFile = { createDocument.launch(fileName) },
        onPick = { openDocument.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
        onMode = viewModel::setRestoreMode,
        onRestoreSettings = viewModel::setRestoreSettings,
        onRequestRestore = viewModel::requestRestore,
        onDismissRestore = viewModel::dismissRestore,
        onRestore = viewModel::restore,
        onReload = viewModel::reloadCurrentModel,
        onPostponeReload = viewModel::postponeReload,
        onGrantStorage = shell.requestStoragePermission,
    )
}

/** Backup on the left, restore on the right. Stateless. */
@Composable
internal fun BackupScreen(
    state: BackupUiState,
    onBack: () -> Unit,
    onIncludeSettings: (Boolean) -> Unit,
    onShare: () -> Unit,
    onSaveToFile: () -> Unit,
    onPick: () -> Unit,
    onMode: (RestoreMode) -> Unit,
    onRestoreSettings: (Boolean) -> Unit,
    onRequestRestore: () -> Unit,
    onDismissRestore: () -> Unit,
    onRestore: () -> Unit,
    onReload: () -> Unit,
    onPostponeReload: () -> Unit,
    onGrantStorage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SystemBar(title = stringResource(R.string.backup_title), onBack = onBack)
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.storage.permissionGranted) {
                NoticeBox(
                    text = stringResource(R.string.storage_permission_missing),
                    kind = NoticeKind.Warning,
                    actionLabel = stringResource(R.string.storage_grant),
                    onAction = onGrantStorage,
                )
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CreateCard(state, onIncludeSettings, onShare, onSaveToFile, Modifier.weight(1f).fillMaxHeight())
                RestoreCard(state, onPick, onMode, onRestoreSettings, onRequestRestore, Modifier.weight(1.25f).fillMaxHeight())
            }
        }
    }
    if (state.confirmRestore) {
        val message = stringResource(if (state.overwrites) R.string.restore_overwrite_message else R.string.restore_keep_message)
        if (state.overwrites) {
            DoubleConfirmDialog(
                title = stringResource(R.string.restore_confirm_title),
                message = message,
                secondMessage = stringResource(R.string.restore_overwrite_second),
                confirmLabel = stringResource(R.string.restore_hold_button),
                onConfirm = onRestore,
                onDismiss = onDismissRestore,
            )
        } else {
            ConfirmDialog(
                title = stringResource(R.string.restore_confirm_title),
                message = message,
                onConfirm = onRestore,
                onDismiss = onDismissRestore,
                confirmLabel = stringResource(R.string.restore_button),
            )
        }
    }
    if (state.reloadPending) {
        ConfirmDialog(
            title = stringResource(R.string.reload_title),
            message = stringResource(if (state.linkUp) R.string.reload_message_linked else R.string.reload_message),
            onConfirm = onReload,
            onDismiss = onPostponeReload,
            confirmLabel = stringResource(R.string.reload_confirm),
            dismissLabel = stringResource(R.string.reload_later),
            destructive = state.linkUp,
        )
    }
}

@Composable
private fun CreateCard(
    state: BackupUiState,
    onIncludeSettings: (Boolean) -> Unit,
    onShare: () -> Unit,
    onSaveToFile: () -> Unit,
    modifier: Modifier,
) {
    AxCard(modifier = modifier.padding(4.dp), contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AxSectionHeader(stringResource(R.string.backup_create))
            Text(stringResource(R.string.backup_create_desc), style = AxTheme.typography.body, color = AxTheme.colors.textMuted)
            AxSettingRow(title = stringResource(R.string.backup_include_settings)) {
                AxSwitch(state.includeSettings, onIncludeSettings, enabled = state.idle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AxButton(
                    text = stringResource(R.string.backup_share),
                    onClick = onShare,
                    icon = AxIcons.Share,
                    variant = AxButtonVariant.Primary,
                    enabled = state.idle,
                )
                AxButton(
                    text = stringResource(R.string.backup_save_file),
                    onClick = onSaveToFile,
                    icon = AxIcons.Save,
                    variant = AxButtonVariant.Outlined,
                    enabled = state.idle,
                )
            }
            if (state.work == BackupWork.CREATING) AxLoadingState(text = stringResource(R.string.backup_working))
        }
    }
}

@Composable
private fun RestoreCard(
    state: BackupUiState,
    onPick: () -> Unit,
    onMode: (RestoreMode) -> Unit,
    onRestoreSettings: (Boolean) -> Unit,
    onRequestRestore: () -> Unit,
    modifier: Modifier,
) {
    val colors = AxTheme.colors
    AxCard(modifier = modifier.padding(4.dp), contentPadding = PaddingValues(12.dp)) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AxSectionHeader(stringResource(R.string.backup_restore))
            Text(stringResource(R.string.backup_restore_desc), style = AxTheme.typography.body, color = colors.textMuted)
            AxButton(
                text = stringResource(R.string.backup_choose),
                onClick = onPick,
                icon = AxIcons.Import,
                variant = AxButtonVariant.Outlined,
                enabled = state.idle,
            )
            if (state.work == BackupWork.INSPECTING || state.work == BackupWork.RESTORING || state.work == BackupWork.RELOADING) {
                AxLoadingState(text = stringResource(R.string.backup_working))
            }
            val inspected = state.inspected
            if (inspected != null) {
                val info = inspected.info
                AxDivider(inset = 0.dp)
                val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
                Text(
                    text = stringResource(
                        R.string.backup_info,
                        info.modelNames.size,
                        info.soundFiles,
                        formatBytes(info.sizeBytes),
                        if (info.createdMs > 0) format.format(Date(info.createdMs)) else "—",
                        info.appVersion.ifEmpty { "—" },
                    ),
                    style = AxTheme.typography.body,
                    color = colors.text,
                )
                if (info.modelNames.isNotEmpty()) {
                    Text(
                        text = info.modelNames.joinToString(", "),
                        style = AxTheme.typography.label,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AxSegmented(
                    options = listOf(stringResource(R.string.restore_mode_keep), stringResource(R.string.restore_mode_overwrite)),
                    selectedIndex = if (state.overwrites) 1 else 0,
                    onSelected = { onMode(if (it == 0) RestoreMode.KEEP_EXISTING else RestoreMode.OVERWRITE_EXISTING) },
                    enabled = state.idle,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (info.includesSettings) {
                        Text(
                            text = stringResource(R.string.restore_settings),
                            style = AxTheme.typography.body,
                            color = colors.text,
                            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                        )
                        AxSwitch(state.restoreSettings, onRestoreSettings, enabled = state.idle)
                    }
                    Spacer(Modifier.weight(1f))
                    AxButton(
                        text = stringResource(R.string.restore_button),
                        onClick = onRequestRestore,
                        variant = if (state.overwrites) AxButtonVariant.Danger else AxButtonVariant.Primary,
                        icon = AxIcons.Restore,
                        enabled = state.idle,
                    )
                }
            }
            val report = state.report
            if (report != null) {
                AxDivider(inset = 0.dp)
                Text(
                    text = stringResource(R.string.restore_report, report.restored.size, report.skipped.size, report.problems.size),
                    style = AxTheme.typography.bodyStrong,
                    color = colors.text,
                )
                if (report.skipped.isNotEmpty()) {
                    Text(stringResource(R.string.restore_skipped, report.skipped.joinToString(", ")), style = AxTheme.typography.label, color = colors.textMuted)
                }
                report.problems.take(4).forEach {
                    Text(it, style = AxTheme.typography.label, color = colors.error)
                }
                if (report.settingsRestored) {
                    Text(stringResource(R.string.restore_settings_done), style = AxTheme.typography.label, color = colors.textMuted)
                }
            }
        }
    }
}
