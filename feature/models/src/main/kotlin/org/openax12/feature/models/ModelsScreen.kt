package org.openax12.feature.models

import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.data.ExternalChange
import org.openax12.core.data.ModelEntry
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDialog
import org.openax12.core.designsystem.AxDialogMessage
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxLoadingState
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.ModelPicture
import org.openax12.core.model.MODEL_PICTURE_ASPECT

/** Everything the model list can do; defaults are no-ops so previews need no wiring. */
internal class ModelsActions(
    val onOpen: (ModelEntry) -> Unit = {},
    val onSelect: (ModelEntry) -> Unit = {},
    val onNew: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onLayout: (ModelsLayout) -> Unit = {},
    val onSort: (ModelSort) -> Unit = {},
    val onAllowStorage: () -> Unit = {},
    val onWriteToRadio: () -> Unit = {},
    val onReview: (ExternalChange) -> Unit = {},
    val onAcceptChange: (ExternalChange) -> Unit = {},
    val onRejectChange: (ExternalChange) -> Unit = {},
    val onDismissReview: () -> Unit = {},
    val onGuardSave: () -> Unit = {},
    val onGuardDiscard: () -> Unit = {},
    val onGuardConfirmLink: () -> Unit = {},
    val onGuardCancel: () -> Unit = {},
)

/**
 * The model list (SPEC section 4): grid or list of the library with picture, name, type,
 * model-match id and last use; the current model is highlighted; tapping a model opens its
 * details, "Select" makes it the model of the radio.
 *
 * @param nowMs wall-clock "now" for the relative last-used text
 */
@Composable
internal fun ModelsScreen(
    state: ModelsUiState,
    actions: ModelsActions,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = stringResource(R.string.models_title),
                subtitle = if (state.loaded) pluralStringResource(R.plurals.models_count, state.models.size, state.models.size) else null,
            ) {
                AxSegmented(
                    options = listOf(stringResource(R.string.models_sort_name), stringResource(R.string.models_sort_recent)),
                    selectedIndex = state.sort.ordinal,
                    onSelected = { actions.onSort(ModelSort.entries[it]) },
                    modifier = Modifier.width(190.dp),
                )
                AxIconButton(
                    icon = if (state.layout == ModelsLayout.GRID) AxIcons.Dashboard else AxIcons.Checklist,
                    contentDescription = stringResource(
                        if (state.layout == ModelsLayout.GRID) R.string.models_layout_list else R.string.models_layout_grid,
                    ),
                    onClick = {
                        actions.onLayout(if (state.layout == ModelsLayout.GRID) ModelsLayout.LIST else ModelsLayout.GRID)
                    },
                )
                AxButton(
                    text = stringResource(R.string.models_import),
                    onClick = actions.onImport,
                    icon = AxIcons.Import,
                )
                AxButton(
                    text = stringResource(R.string.models_new),
                    onClick = actions.onNew,
                    variant = AxButtonVariant.Primary,
                    icon = AxIcons.Add,
                )
            }
            when {
                !state.loaded -> AxLoadingState(modifier = Modifier.fillMaxSize())
                state.models.isEmpty() -> AxEmptyState(
                    title = stringResource(R.string.models_empty_title),
                    message = stringResource(R.string.models_empty_message),
                    actionLabel = stringResource(R.string.models_new),
                    onAction = actions.onNew,
                    modifier = Modifier.fillMaxSize(),
                )
                state.layout == ModelsLayout.GRID -> ModelGrid(state, actions, nowMs)
                else -> ModelList(state, actions, nowMs)
            }
        }
        GuardDialogs(
            state = state.guard,
            onSave = actions.onGuardSave,
            onDiscard = actions.onGuardDiscard,
            onConfirmLink = actions.onGuardConfirmLink,
            onCancel = actions.onGuardCancel,
        )
        state.reviewing?.let { change ->
            ExternalChangeDialog(
                change = change,
                position = state.externalChanges.indexOf(change) + 1,
                total = state.externalChanges.size,
                onAccept = { actions.onAcceptChange(change) },
                onReject = { actions.onRejectChange(change) },
                onLater = actions.onDismissReview,
            )
        }
        if (state.busy) BusyOverlay(text = stringResource(R.string.models_busy_switching))
    }
}

@Composable
private fun Banners(state: ModelsUiState, actions: ModelsActions) {
    val banners = buildList<@Composable () -> Unit> {
        if (state.libraryUnavailable) {
            add { InfoBanner(stringResource(R.string.models_banner_no_storage), BannerTone.ERROR) }
        } else if (state.needsStoragePermission) {
            add {
                InfoBanner(
                    text = stringResource(R.string.models_banner_permission),
                    tone = BannerTone.WARNING,
                    actionLabel = stringResource(R.string.models_banner_permission_action),
                    onAction = actions.onAllowStorage,
                )
            }
        }
        if (state.externalChanges.isNotEmpty()) {
            add {
                InfoBanner(
                    text = pluralStringResource(R.plurals.models_banner_external, state.externalChanges.size, state.externalChanges.size),
                    tone = BannerTone.WARNING,
                    actionLabel = stringResource(R.string.models_banner_review),
                    onAction = { actions.onReview(state.externalChanges.first()) },
                )
            }
        }
        if (!state.deviceInSync) {
            add {
                InfoBanner(
                    text = stringResource(R.string.models_banner_not_written),
                    tone = BannerTone.WARNING,
                    actionLabel = stringResource(R.string.models_banner_write),
                    onAction = actions.onWriteToRadio,
                )
            }
        }
    }
    if (banners.isEmpty()) return
    Column(
        modifier = Modifier.padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        banners.forEach { it() }
    }
}

@Composable
private fun ModelGrid(state: ModelsUiState, actions: ModelsActions, nowMs: Long) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Banners(state, actions) }
        items(state.models, key = { it.id }) { entry ->
            ModelCard(
                entry = entry,
                deviceInSync = state.deviceInSync,
                nowMs = nowMs,
                onOpen = { actions.onOpen(entry) },
                onSelect = { actions.onSelect(entry) },
            )
        }
    }
}

@Composable
private fun ModelList(state: ModelsUiState, actions: ModelsActions, nowMs: Long) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Banners(state, actions) }
        items(state.models, key = { it.id }) { entry ->
            ModelRow(
                entry = entry,
                deviceInSync = state.deviceInSync,
                nowMs = nowMs,
                onOpen = { actions.onOpen(entry) },
                onSelect = { actions.onSelect(entry) },
            )
        }
    }
}

/** "Glider · ID 3" line below a model name. */
@Composable
private fun typeLine(entry: ModelEntry): String {
    val type = stringResource(entry.type.labelRes())
    return if (entry.modelMatch > 0) stringResource(R.string.models_type_match, type, entry.modelMatch) else type
}

/** Grid card of one model. */
@Composable
internal fun ModelCard(
    entry: ModelEntry,
    deviceInSync: Boolean,
    nowMs: Long,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AxTheme.colors
    val radius = AxTheme.shapes.card
    AxCard(modifier = modifier.fillMaxWidth(), cornerRadius = radius, onClick = onOpen) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box {
                ModelPicture(
                    picturePath = entry.picturePath,
                    pictureVersion = entry.pictureVersion,
                    type = entry.type,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(MODEL_PICTURE_ASPECT)
                        .clip(RoundedCornerShape(radius - 8.dp)),
                )
                if (entry.isCurrent) ActiveBadge(modifier = Modifier.padding(6.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = AxTheme.typography.bodyStrong,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.externallyModified) {
                    Spacer(Modifier.width(4.dp))
                    AxIcon(AxIcons.Warning, stringResource(R.string.models_cd_changed_outside), tint = colors.warning, size = 16.dp)
                }
            }
            Text(
                text = typeLine(entry),
                style = AxTheme.typography.label,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = lastUsedOf(entry.lastUsedMs, nowMs).text(),
                style = AxTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            SelectArea(entry, deviceInSync, onSelect, Modifier.fillMaxWidth().height(44.dp))
        }
        if (entry.isCurrent) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(2.dp, colors.accent, RoundedCornerShape(radius)),
            )
        }
    }
}

/** List row of one model. */
@Composable
internal fun ModelRow(
    entry: ModelEntry,
    deviceInSync: Boolean,
    nowMs: Long,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AxTheme.colors
    val radius = AxTheme.shapes.panel
    AxCard(modifier = modifier.fillMaxWidth(), cornerRadius = radius, elevation = 4.dp, onClick = onOpen) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelPicture(
                picturePath = entry.picturePath,
                pictureVersion = entry.pictureVersion,
                type = entry.type,
                modifier = Modifier
                    .width(76.dp)
                    .aspectRatio(MODEL_PICTURE_ASPECT)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.name,
                        style = AxTheme.typography.bodyStrong,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.externallyModified) {
                        Spacer(Modifier.width(4.dp))
                        AxIcon(AxIcons.Warning, stringResource(R.string.models_cd_changed_outside), tint = colors.warning, size = 16.dp)
                    }
                }
                Text(
                    text = stringResource(R.string.models_row_detail, typeLine(entry), lastUsedOf(entry.lastUsedMs, nowMs).text()),
                    style = AxTheme.typography.label,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SelectArea(entry, deviceInSync, onSelect, Modifier.width(132.dp).height(44.dp))
            AxIcon(AxIcons.ChevronRight, null, tint = colors.textFaint, modifier = Modifier.padding(start = 6.dp))
        }
        if (entry.isCurrent) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(2.dp, colors.accent, RoundedCornerShape(radius)),
            )
        }
    }
}

/** "Select" button, or the status of the current model. */
@Composable
private fun SelectArea(entry: ModelEntry, deviceInSync: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            !entry.isCurrent -> AxButton(
                text = stringResource(R.string.models_select),
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
                height = 38.dp,
            )
            deviceInSync -> Row(verticalAlignment = Alignment.CenterVertically) {
                AxIcon(AxIcons.Check, null, tint = AxTheme.colors.teal, size = 18.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.models_on_radio),
                    style = AxTheme.typography.label,
                    color = AxTheme.colors.teal,
                    maxLines = 1,
                )
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                AxIcon(AxIcons.Warning, null, tint = AxTheme.colors.warning, size = 18.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.models_not_on_radio),
                    style = AxTheme.typography.label,
                    color = AxTheme.colors.warning,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ExternalChangeDialog(
    change: ExternalChange,
    position: Int,
    total: Int,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onLater: () -> Unit,
) {
    AxDialog(
        onDismissRequest = onLater,
        title = stringResource(if (change.isNew) R.string.models_change_new_title else R.string.models_change_title, change.name),
        icon = AxIcons.Warning,
        iconTint = AxTheme.colors.warning,
        width = 460.dp,
        buttons = {
            AxButton(text = stringResource(R.string.models_change_later), onClick = onLater, variant = AxButtonVariant.Text)
            AxButton(
                text = stringResource(if (change.isNew) R.string.models_change_ignore else R.string.models_change_keep),
                onClick = onReject,
            )
            AxButton(
                text = stringResource(if (change.isNew) R.string.models_change_import else R.string.models_change_reimport),
                onClick = onAccept,
                variant = AxButtonVariant.Primary,
            )
        },
    ) {
        AxDialogMessage(
            stringResource(if (change.isNew) R.string.models_change_new_message else R.string.models_change_message, change.name),
        )
        if (total > 1) {
            Text(
                text = stringResource(R.string.models_change_position, position, total),
                style = AxTheme.typography.labelSmall,
                color = AxTheme.colors.textMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
