package org.openax12.feature.system.preflight

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSectionHeader
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.TextEntryDialog
import org.openax12.core.model.ChecklistItem
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.navigation.SetupTabIds
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.system.R
import org.openax12.feature.system.common.ConnectionNotice
import org.openax12.feature.system.common.SystemBar
import org.openax12.feature.system.common.graphViewModel

/** Longest checklist line. */
internal const val CHECKLIST_TEXT_MAX = 80

/** Stateful entry content of `PreFlightCheck`. */
@Composable
internal fun PreFlightRoute() {
    val graph = LocalAppGraph.current
    val viewModel = graphViewModel(graph) {
        PreFlightViewModel(it.radio, it.configSession, it.runtime, it.models, it.audio)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    PreFlightScreen(
        state = state,
        onBack = { navigator.back() },
        onSkip = viewModel::skip,
        onEditPresets = { navigator.openSetupTab(SetupTabIds.INPUT_CHECK) },
        onToggleChecked = viewModel::toggleChecked,
        onResetChecks = viewModel::resetChecks,
        onAdd = viewModel::add,
        onRename = viewModel::rename,
        onToggleSpoken = viewModel::toggleSpoken,
        onRemove = viewModel::remove,
        onMove = viewModel::move,
        onReadAloud = viewModel::readAloud,
    )
}

/** Pre-flight overview on the left, the model's checklist on the right. Stateless. */
@Composable
internal fun PreFlightScreen(
    state: PreFlightUiState,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onEditPresets: () -> Unit,
    onToggleChecked: (Int) -> Unit,
    onResetChecks: () -> Unit,
    onAdd: (String) -> Unit,
    onRename: (Int, String) -> Unit,
    onToggleSpoken: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onReadAloud: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SystemBar(title = stringResource(R.string.pf_title), onBack = onBack)
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OverviewCard(state, onSkip, onEditPresets, Modifier.width(330.dp).fillMaxHeight())
            ChecklistCard(
                state = state,
                onToggleChecked = onToggleChecked,
                onResetChecks = onResetChecks,
                onAdd = onAdd,
                onRename = onRename,
                onToggleSpoken = onToggleSpoken,
                onRemove = onRemove,
                onMove = onMove,
                onReadAloud = onReadAloud,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun OverviewCard(state: PreFlightUiState, onSkip: () -> Unit, onEditPresets: () -> Unit, modifier: Modifier) {
    val colors = AxTheme.colors
    AxCard(modifier = modifier.padding(4.dp), contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AxSectionHeader(stringResource(R.string.pf_overview))
            if (!state.connection.isReady) ConnectionNotice(state.connection)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AxIcon(
                    icon = if (state.alarmPending) AxIcons.Warning else AxIcons.Success,
                    contentDescription = null,
                    tint = if (state.alarmPending) colors.warning else colors.success,
                )
                Text(
                    text = if (state.alarmPending) {
                        pluralStringResource(R.plurals.pf_alarm_pending, state.failingCount, state.failingCount)
                    } else {
                        stringResource(R.string.pf_all_pass)
                    },
                    style = AxTheme.typography.bodyStrong,
                    color = colors.text,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (state.rows.isEmpty()) {
                Text(stringResource(R.string.pf_no_presets), style = AxTheme.typography.body, color = colors.textMuted)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 190.dp)) {
                    itemsIndexed(state.rows, key = { _, row -> row.source.index }) { _, row ->
                        Row(modifier = Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                            AxIcon(
                                icon = if (row.failing) AxIcons.Warning else AxIcons.Check,
                                contentDescription = null,
                                tint = if (row.failing) colors.warning else colors.success,
                                size = 16.dp,
                            )
                            Text(
                                text = row.name,
                                style = if (row.failing) AxTheme.typography.bodyStrong else AxTheme.typography.body,
                                color = colors.text,
                                modifier = Modifier.padding(start = 8.dp).weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = row.preset?.let { "$it%" } ?: stringResource(R.string.pf_not_checked),
                                style = AxTheme.typography.value,
                                color = colors.textMuted,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AxButton(
                    text = stringResource(R.string.pf_skip),
                    onClick = onSkip,
                    enabled = state.alarmPending && state.connection.isReady,
                    variant = AxButtonVariant.Primary,
                )
                AxButton(text = stringResource(R.string.pf_edit_presets), onClick = onEditPresets, variant = AxButtonVariant.Outlined)
            }
        }
    }
}

@Composable
private fun ChecklistCard(
    state: PreFlightUiState,
    onToggleChecked: (Int) -> Unit,
    onResetChecks: () -> Unit,
    onAdd: (String) -> Unit,
    onRename: (Int, String) -> Unit,
    onToggleSpoken: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onReadAloud: () -> Unit,
    modifier: Modifier,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    var deleting by rememberSaveable { mutableIntStateOf(-1) }
    AxCard(modifier = modifier.padding(4.dp), clipContent = true) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AxSectionHeader(
                    title = if (state.modelName.isEmpty()) {
                        stringResource(R.string.pf_checklist)
                    } else {
                        stringResource(R.string.pf_checklist_of, state.modelName)
                    },
                    modifier = Modifier.weight(1f),
                )
                AxIconButton(AxIcons.Voice, stringResource(R.string.pf_read_aloud), onReadAloud, enabled = state.checklist.any { it.spoken })
                AxIconButton(AxIcons.Reset, stringResource(R.string.pf_reset_checks), onResetChecks, enabled = state.checked.isNotEmpty())
                AxIconButton(AxIcons.Add, stringResource(R.string.pf_add_line), { adding = true })
            }
            AxDivider()
            if (state.checklist.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AxEmptyState(
                        title = stringResource(R.string.pf_checklist_empty),
                        message = stringResource(R.string.pf_checklist_empty_hint),
                        icon = AxIcons.Checklist,
                        actionLabel = stringResource(R.string.pf_add_line),
                        onAction = { adding = true },
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.checklist) { index, item ->
                        ChecklistRow(
                            item = item,
                            checked = index in state.checked,
                            canMoveUp = index > 0,
                            canMoveDown = index < state.checklist.lastIndex,
                            onToggleChecked = { onToggleChecked(index) },
                            onEdit = { editing = index },
                            onToggleSpoken = { onToggleSpoken(index) },
                            onMove = { onMove(index, it) },
                            onDelete = { deleting = index },
                        )
                        AxDivider()
                    }
                }
            }
        }
    }
    if (adding) {
        TextEntryDialog(
            title = stringResource(R.string.pf_add_line),
            initialValue = "",
            onConfirm = { adding = false; onAdd(it) },
            onDismiss = { adding = false },
            maxLength = CHECKLIST_TEXT_MAX,
        )
    }
    val editItem = state.checklist.getOrNull(editing)
    if (editItem != null) {
        TextEntryDialog(
            title = stringResource(R.string.pf_edit_line),
            initialValue = editItem.text,
            onConfirm = { onRename(editing, it); editing = -1 },
            onDismiss = { editing = -1 },
            maxLength = CHECKLIST_TEXT_MAX,
        )
    }
    val deleteItem = state.checklist.getOrNull(deleting)
    if (deleteItem != null) {
        ConfirmDialog(
            title = stringResource(R.string.pf_delete_title),
            message = stringResource(R.string.pf_delete_message, deleteItem.text),
            onConfirm = { onRemove(deleting); deleting = -1 },
            onDismiss = { deleting = -1 },
            confirmLabel = stringResource(R.string.pf_delete),
            destructive = true,
        )
    }
}

@Composable
private fun ChecklistRow(
    item: ChecklistItem,
    checked: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleChecked: () -> Unit,
    onEdit: () -> Unit,
    onToggleSpoken: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AxTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onToggleChecked),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AxIcon(
                icon = if (checked) AxIcons.Success else AxIcons.Checklist,
                contentDescription = null,
                tint = if (checked) colors.success else colors.textFaint,
                modifier = Modifier.padding(start = 8.dp).size(24.dp),
            )
            Text(
                text = item.text,
                style = AxTheme.typography.body,
                color = if (checked) colors.textMuted else colors.text,
                textDecoration = if (checked) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
        AxIconButton(
            icon = AxIcons.Voice,
            contentDescription = stringResource(R.string.pf_spoken),
            onClick = onToggleSpoken,
            tint = if (item.spoken) colors.accent else colors.textFaint,
        )
        AxIconButton(AxIcons.ExpandLess, stringResource(R.string.pf_move_up), { onMove(-1) }, enabled = canMoveUp)
        AxIconButton(AxIcons.ExpandMore, stringResource(R.string.pf_move_down), { onMove(1) }, enabled = canMoveDown)
        AxIconButton(AxIcons.Edit, stringResource(R.string.pf_edit_line), onEdit)
        AxIconButton(AxIcons.Delete, stringResource(R.string.pf_delete), onDelete)
    }
}
