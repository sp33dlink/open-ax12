package org.openax12.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.ModelSnapshot
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDialog
import org.openax12.core.designsystem.AxDialogMessage
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxDropdown
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxListGroup
import org.openax12.core.designsystem.AxLoadingState
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.HoldConfirmDialog
import org.openax12.core.designsystem.ModelPicture
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.TextEntryDialog
import org.openax12.core.model.MODEL_PICTURE_ASPECT
import org.openax12.core.model.ModelType

/** Everything the detail screen can do; defaults are no-ops for previews. */
internal class DetailActions(
    val onBack: () -> Unit = {},
    val onSelect: () -> Unit = {},
    val onShowDialog: (DetailDialog) -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onDuplicate: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onSetType: (ModelType) -> Unit = {},
    val onSetModelMatch: (Int) -> Unit = {},
    val onSetNotes: (String) -> Unit = {},
    val onChangePicture: () -> Unit = {},
    val onRemovePicture: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onRestoreSnapshot: (ModelSnapshot) -> Unit = {},
    val onDeleteSnapshot: (ModelSnapshot) -> Unit = {},
    val onGuardSave: () -> Unit = {},
    val onGuardDiscard: () -> Unit = {},
    val onGuardConfirmLink: () -> Unit = {},
    val onGuardCancel: () -> Unit = {},
)

/** Largest model-match number the UI offers (ELRS model match is a byte, see the model-match entry in docs/ASSUMPTIONS.md). */
internal const val MODEL_MATCH_MAX: Int = 255

/**
 * Details of one model: large picture (change / remove), name, type, ELRS model match, notes,
 * and the actions duplicate, export, snapshots and delete; "Select" makes it the radio's model.
 */
@Composable
internal fun ModelDetailScreen(
    state: ModelDetailUiState,
    actions: DetailActions,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val entry = state.entry
    Box(modifier = modifier.fillMaxSize()) {
        if (entry == null) {
            AxLoadingState(modifier = Modifier.fillMaxSize())
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                ScreenHeader(title = entry.name, onBack = actions.onBack) {
                    if (entry.isCurrent) {
                        ActiveBadge()
                    } else {
                        AxButton(
                            text = stringResource(R.string.models_select),
                            onClick = actions.onSelect,
                            variant = AxButtonVariant.Primary,
                            icon = AxIcons.Check,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PictureColumn(entry, actions, Modifier.width(290.dp))
                    DetailsColumn(entry, state, actions, nowMs, Modifier.weight(1f))
                }
            }
            DetailDialogs(entry, state, actions)
        }
        GuardDialogs(
            state = state.guard,
            onSave = actions.onGuardSave,
            onDiscard = actions.onGuardDiscard,
            onConfirmLink = actions.onGuardConfirmLink,
            onCancel = actions.onGuardCancel,
        )
        if (state.busy) BusyOverlay(text = stringResource(R.string.models_busy))
    }
}

@Composable
private fun PictureColumn(entry: ModelEntry, actions: DetailActions, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AxCard(modifier = Modifier.fillMaxWidth(), onClick = actions.onChangePicture, contentPadding = PaddingValues(8.dp)) {
            ModelPicture(
                picturePath = entry.picturePath,
                pictureVersion = entry.pictureVersion,
                type = entry.type,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(MODEL_PICTURE_ASPECT)
                    .clip(RoundedCornerShape(AxTheme.shapes.panel)),
            )
        }
        AxButton(
            text = stringResource(R.string.models_picture_change),
            onClick = actions.onChangePicture,
            icon = AxIcons.Image,
            modifier = Modifier.fillMaxWidth(),
        )
        if (entry.picturePath != null) {
            AxButton(
                text = stringResource(R.string.models_picture_remove),
                onClick = actions.onRemovePicture,
                variant = AxButtonVariant.Outlined,
                icon = AxIcons.Close,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DetailsColumn(
    entry: ModelEntry,
    state: ModelDetailUiState,
    actions: DetailActions,
    nowMs: Long,
    modifier: Modifier,
) {
    val colors = AxTheme.colors
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AxListGroup {
            AxSettingRow(title = stringResource(R.string.models_field_name)) {
                AxValueBox(
                    text = entry.name,
                    onClick = { actions.onShowDialog(DetailDialog.Rename) },
                    trailingIcon = AxIcons.Edit,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.width(RowControlWidth),
                )
            }
            AxDivider()
            AxSettingRow(title = stringResource(R.string.models_field_type)) {
                val labels = ModelTypes.map { stringResource(it.labelRes()) }
                AxDropdown(
                    options = labels,
                    selectedIndex = ModelTypes.indexOf(entry.type),
                    onSelected = { actions.onSetType(ModelTypes[it]) },
                    title = stringResource(R.string.models_field_type),
                    columns = 2,
                    modifier = Modifier.width(RowControlWidth),
                )
            }
            AxDivider()
            AxSettingRow(title = stringResource(R.string.models_field_match)) {
                NumberField(
                    value = entry.modelMatch.toInt().coerceIn(0, MODEL_MATCH_MAX),
                    onValueChange = actions.onSetModelMatch,
                    min = 0,
                    max = MODEL_MATCH_MAX,
                    title = stringResource(R.string.models_field_match),
                    defaultValue = 0,
                    modifier = Modifier.width(120.dp),
                )
            }
            AxDivider()
            AxSettingRow(title = stringResource(R.string.models_field_notes)) {
                AxValueBox(
                    text = state.notes.ifBlank { stringResource(R.string.models_notes_empty) },
                    onClick = { actions.onShowDialog(DetailDialog.Notes) },
                    trailingIcon = AxIcons.Edit,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.width(RowControlWidth),
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = lastUsedOf(entry.lastUsedMs, nowMs).text(),
                style = AxTheme.typography.label,
                color = colors.textMuted,
            )
            Text(
                text = entry.folderPath?.let { stringResource(R.string.models_info_folder, it) }
                    ?: stringResource(R.string.models_info_no_folder),
                style = AxTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val pad = PaddingValues(horizontal = 8.dp)
            AxButton(
                text = stringResource(R.string.models_duplicate),
                onClick = { actions.onShowDialog(DetailDialog.Duplicate) },
                icon = AxIcons.Copy,
                contentPadding = pad,
                modifier = Modifier.weight(1f),
            )
            AxButton(
                text = stringResource(R.string.models_export),
                onClick = actions.onExport,
                icon = AxIcons.Share,
                contentPadding = pad,
                modifier = Modifier.weight(1f),
            )
            AxButton(
                text = stringResource(R.string.models_snapshots),
                onClick = { actions.onShowDialog(DetailDialog.Snapshots) },
                icon = AxIcons.History,
                contentPadding = pad,
                modifier = Modifier.weight(1f),
            )
            AxButton(
                text = stringResource(R.string.models_delete),
                onClick = { actions.onShowDialog(DetailDialog.Delete) },
                variant = AxButtonVariant.DangerOutlined,
                icon = AxIcons.Delete,
                enabled = !entry.isCurrent,
                contentPadding = pad,
                modifier = Modifier.weight(1f),
            )
        }
        if (entry.isCurrent) {
            Text(
                text = stringResource(R.string.models_delete_current_hint),
                style = AxTheme.typography.labelSmall,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * One-step delete confirmation: the red hold button fills while it is held.
 *
 * @param fillOverride fixes the fill fraction (0..1) of the hold button for screenshots
 */
@Composable
internal fun DeleteModelDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit, fillOverride: Float? = null) {
    HoldConfirmDialog(
        title = stringResource(R.string.models_delete_title, name),
        message = stringResource(R.string.models_delete_message),
        consequence = stringResource(R.string.models_delete_second),
        confirmLabel = stringResource(R.string.models_delete_hold),
        confirmDescription = stringResource(R.string.models_delete_hold_description),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        fillOverride = fillOverride,
    )
}

@Composable
private fun DetailDialogs(entry: ModelEntry, state: ModelDetailUiState, actions: DetailActions) {
    val problems = NameProblem.entries.associateWith { stringResource(it.messageRes()) }
    val taken = state.existingNames
    when (val dialog = state.dialog) {
        DetailDialog.None -> Unit
        DetailDialog.Rename -> TextEntryDialog(
            title = stringResource(R.string.models_rename_title),
            initialValue = entry.name,
            maxLength = ModelNameRules.MAX_LENGTH,
            confirmLabel = stringResource(R.string.models_rename),
            validate = { ModelNameRules.check(it, taken, ignore = entry.name)?.let(problems::getValue) },
            onConfirm = actions.onRename,
            onDismiss = actions.onDismissDialog,
        )
        DetailDialog.Duplicate -> {
            val suggestion = remember(entry.name, taken) { ModelNameRules.unique(entry.name, taken) }
            TextEntryDialog(
                title = stringResource(R.string.models_duplicate_title),
                initialValue = suggestion,
                maxLength = ModelNameRules.MAX_LENGTH,
                confirmLabel = stringResource(R.string.models_duplicate),
                validate = { ModelNameRules.check(it, taken)?.let(problems::getValue) },
                onConfirm = actions.onDuplicate,
                onDismiss = actions.onDismissDialog,
            )
        }
        DetailDialog.Notes -> TextEntryDialog(
            title = stringResource(R.string.models_notes_title),
            initialValue = state.notes,
            maxLength = NOTES_MAX_LENGTH,
            allowEmpty = true,
            onConfirm = actions.onSetNotes,
            onDismiss = actions.onDismissDialog,
        )
        DetailDialog.Delete -> DeleteModelDialog(
            name = entry.name,
            onConfirm = actions.onDelete,
            onDismiss = actions.onDismissDialog,
        )
        DetailDialog.Snapshots -> SnapshotsDialog(
            snapshots = state.snapshots,
            onRestore = { actions.onShowDialog(DetailDialog.RestoreSnapshot(it)) },
            onDelete = { actions.onShowDialog(DetailDialog.DeleteSnapshot(it)) },
            onClose = actions.onDismissDialog,
        )
        is DetailDialog.RestoreSnapshot -> {
            val whenText = formatDateTime(dialog.snapshot.timeMs)
            val base = stringResource(R.string.models_restore_message, entry.name, whenText)
            val extra = if (entry.isCurrent) {
                stringResource(
                    if (state.dirty) R.string.models_restore_current_dirty else R.string.models_restore_current,
                )
            } else {
                ""
            }
            ConfirmDialog(
                title = stringResource(R.string.models_restore_title),
                message = (base + " " + extra).trim(),
                confirmLabel = stringResource(R.string.models_restore),
                onConfirm = { actions.onRestoreSnapshot(dialog.snapshot) },
                onDismiss = { actions.onShowDialog(DetailDialog.Snapshots) },
            )
        }
        is DetailDialog.DeleteSnapshot -> ConfirmDialog(
            title = stringResource(R.string.models_snapshot_delete_title),
            message = stringResource(R.string.models_snapshot_delete_message, formatDateTime(dialog.snapshot.timeMs)),
            confirmLabel = stringResource(R.string.models_delete),
            destructive = true,
            onConfirm = { actions.onDeleteSnapshot(dialog.snapshot) },
            onDismiss = { actions.onShowDialog(DetailDialog.Snapshots) },
        )
    }
}

/** Longest notes text. */
internal const val NOTES_MAX_LENGTH: Int = 500

/** The snapshot list with restore and delete per snapshot; [snapshots] null = loading. */
@Composable
internal fun SnapshotsDialog(
    snapshots: List<ModelSnapshot>?,
    onRestore: (ModelSnapshot) -> Unit,
    onDelete: (ModelSnapshot) -> Unit,
    onClose: () -> Unit,
) {
    AxDialog(
        onDismissRequest = onClose,
        title = stringResource(R.string.models_snapshots_title),
        icon = AxIcons.History,
        width = 560.dp,
        contentPadding = PaddingValues(horizontal = 12.dp),
        buttons = { AxButton(text = stringResource(R.string.models_close), onClick = onClose, variant = AxButtonVariant.Primary) },
    ) {
        when {
            snapshots == null -> AxLoadingState(modifier = Modifier.fillMaxWidth().height(100.dp))
            snapshots.isEmpty() -> AxDialogMessage(stringResource(R.string.models_snapshots_empty))
            else -> {
                Text(
                    text = stringResource(R.string.models_snapshots_hint),
                    style = AxTheme.typography.label,
                    color = AxTheme.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(snapshots, key = { it.id }) { snapshot ->
                        SnapshotRow(snapshot, onRestore = { onRestore(snapshot) }, onDelete = { onDelete(snapshot) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapshotRow(snapshot: ModelSnapshot, onRestore: () -> Unit, onDelete: () -> Unit) {
    val colors = AxTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatDateTime(snapshot.timeMs),
                style = AxTheme.typography.bodyStrong,
                color = colors.text,
                maxLines = 1,
            )
            Text(
                text = stringResource(
                    R.string.models_snapshot_line,
                    stringResource(snapshot.reason.labelRes()),
                    formatSize(snapshot.sizeBytes),
                ),
                style = AxTheme.typography.label,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        AxButton(text = stringResource(R.string.models_restore), onClick = onRestore, icon = AxIcons.Restore)
        Spacer(Modifier.width(4.dp))
        AxIconButton(
            icon = AxIcons.Delete,
            contentDescription = stringResource(R.string.models_snapshot_delete_title),
            onClick = onDelete,
            tint = colors.textMuted,
        )
    }
    AxDivider(inset = 8.dp)
}
