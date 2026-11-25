package org.openax12.feature.system.elrs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxDropdown
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxListRow
import org.openax12.core.designsystem.AxLoadingState
import org.openax12.core.designsystem.AxSectionHeader
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.NumberField
import org.openax12.core.model.ElrsItemType
import org.openax12.core.model.ElrsMenuItem
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.system.R
import org.openax12.feature.system.common.ConnectionNotice
import org.openax12.feature.system.common.FeedbackEffect
import org.openax12.feature.system.common.NoticeBox
import org.openax12.feature.system.common.NoticeKind
import org.openax12.feature.system.common.SystemBar
import org.openax12.feature.system.common.ValueText
import org.openax12.feature.system.common.graphViewModel

/** Stateful entry content of `ElrsMenu`. */
@Composable
internal fun ElrsRoute() {
    val graph = LocalAppGraph.current
    val viewModel = graphViewModel(graph) { ElrsViewModel(it.radio) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    FeedbackEffect(viewModel.feedback)
    // One request per visit; the SDK ignores requests while one is running and repeated fetching stutters.
    LaunchedEffect(Unit) { viewModel.load(ElrsDevice.TX_MODULE) }
    ElrsScreen(
        state = state,
        onBack = { navigator.back() },
        onDevice = viewModel::selectDevice,
        onReload = { viewModel.load() },
        onStatus = viewModel::requestStatus,
        onOpenFolder = viewModel::openFolder,
        onUp = viewModel::up,
        onSetValue = viewModel::setValue,
        onCommand = viewModel::command,
    )
}

/** The ELRS screen: link status on the left, the parameter menu on the right. Stateless. */
@Composable
internal fun ElrsScreen(
    state: ElrsUiState,
    onBack: () -> Unit,
    onDevice: (ElrsDevice) -> Unit,
    onReload: () -> Unit,
    onStatus: () -> Unit,
    onOpenFolder: (Int) -> Unit,
    onUp: () -> Unit,
    onSetValue: (ElrsMenuItem, Int) -> Unit,
    onCommand: (ElrsMenuItem, Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SystemBar(
            title = stringResource(R.string.elrs_title),
            onBack = onBack,
            trailing = {
                AxSegmented(
                    options = listOf(stringResource(R.string.elrs_tx_module), stringResource(R.string.elrs_receiver)),
                    selectedIndex = state.device.ordinal,
                    onSelected = { onDevice(ElrsDevice.entries[it]) },
                    enabled = state.radioReady,
                    modifier = Modifier.width(260.dp).height(36.dp),
                )
                AxIconButton(
                    icon = AxIcons.Refresh,
                    contentDescription = stringResource(R.string.elrs_reload),
                    onClick = onReload,
                    enabled = state.radioReady && !state.loading,
                    tint = AxTheme.colors.onChrome,
                )
            },
        )
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkStatusCard(state, onStatus, Modifier.width(230.dp).fillMaxHeight())
            MenuCard(state, onOpenFolder, onUp, onSetValue, onCommand, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun LinkStatusCard(state: ElrsUiState, onStatus: () -> Unit, modifier: Modifier) {
    val colors = AxTheme.colors
    AxCard(modifier = modifier.padding(4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AxSectionHeader(stringResource(R.string.elrs_link_status))
            val status = state.status
            StatusLine(stringResource(R.string.elrs_good_packets), status?.goodPackets?.toString() ?: "—")
            StatusLine(stringResource(R.string.elrs_bad_packets), status?.badPackets?.toString() ?: "—")
            StatusLine(
                stringResource(R.string.elrs_model_match),
                when {
                    status == null -> "—"
                    status.isModelMismatch -> stringResource(R.string.elrs_mismatch)
                    else -> stringResource(R.string.elrs_match_ok)
                },
                valueColor = if (status?.isModelMismatch == true) colors.error else colors.text,
            )
            if (state.deviceName.isNotEmpty()) {
                StatusLine(stringResource(R.string.elrs_device), state.deviceName)
            }
            StatusLine(
                stringResource(R.string.elrs_link),
                if (state.linkUp) stringResource(R.string.elrs_link_up) else stringResource(R.string.elrs_link_down),
            )
            AxButton(
                text = stringResource(R.string.elrs_refresh_status),
                onClick = onStatus,
                enabled = state.radioReady && !state.statusPending && !state.loading,
                variant = AxButtonVariant.Outlined,
                icon = AxIcons.Refresh,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.status?.isModelMismatch == true) {
                Text(
                    text = stringResource(R.string.elrs_mismatch_hint),
                    style = AxTheme.typography.label,
                    color = colors.error,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = AxTheme.colors.text) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = AxTheme.typography.label, color = AxTheme.colors.textMuted, modifier = Modifier.weight(1f))
        Text(text = value, style = AxTheme.typography.value, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MenuCard(
    state: ElrsUiState,
    onOpenFolder: (Int) -> Unit,
    onUp: () -> Unit,
    onSetValue: (ElrsMenuItem, Int) -> Unit,
    onCommand: (ElrsMenuItem, Int) -> Unit,
    modifier: Modifier,
) {
    AxCard(modifier = modifier.padding(4.dp), clipContent = true) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!state.radioReady) {
                Box(Modifier.padding(12.dp)) { ConnectionNotice(state.connection) }
            }
            if (state.folder != ElrsTree.ROOT) {
                BreadcrumbRow(state, onUp)
                AxDivider()
            }
            val items = state.visibleItems
            when {
                state.loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AxLoadingState(text = stringResource(R.string.elrs_loading))
                }
                items.isEmpty() && state.noAnswer -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AxEmptyState(
                        title = stringResource(R.string.elrs_no_answer),
                        message = stringResource(
                            if (state.device == ElrsDevice.RECEIVER) R.string.elrs_no_answer_rx else R.string.elrs_no_answer_tx,
                        ),
                        icon = AxIcons.Antenna,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                    items(items, key = { it.index }) { item ->
                        ElrsItemRow(
                            item = item,
                            progress = state.commands[item.index],
                            enabled = state.radioReady && item.enabled,
                            onOpen = { onOpenFolder(item.index) },
                            onSetValue = { onSetValue(item, it) },
                            onCommand = { onCommand(item, it) },
                        )
                        AxDivider()
                    }
                    if (state.loading) {
                        item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { AxLoadingState() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbRow(state: ElrsUiState, onUp: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AxIconButton(AxIcons.Back, stringResource(R.string.elrs_up), onUp)
        Text(
            text = state.breadcrumb.joinToString("  ›  ") { it.name },
            style = AxTheme.typography.titleSmall,
            color = AxTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One menu item: a folder, a selection, a number, a command, an info line. */
@Composable
internal fun ElrsItemRow(
    item: ElrsMenuItem,
    progress: CommandProgress?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onSetValue: (Int) -> Unit,
    onCommand: (Int) -> Unit,
) {
    when (item.type) {
        ElrsItemType.FOLDER -> AxListRow(
            title = item.name,
            leadingIcon = AxIcons.Folder,
            onClick = onOpen,
            enabled = enabled,
            trailing = { AxIcon(AxIcons.ChevronRight, null, tint = AxTheme.colors.textMuted) },
        )
        ElrsItemType.TEXT_SELECTION -> AxSettingRow(title = item.name, enabled = enabled) {
            val options = remember(item.options, item.units) {
                if (item.units.isEmpty()) item.options else item.options.map { "$it ${item.units}" }
            }
            AxDropdown(
                options = options,
                selectedIndex = item.value,
                onSelected = onSetValue,
                title = item.name,
                enabled = enabled,
                modifier = Modifier.width(230.dp),
            )
        }
        ElrsItemType.COMMAND -> CommandRow(item, progress ?: CommandProgress.Idle, enabled, onCommand)
        ElrsItemType.INFO -> AxListRow(
            title = item.name,
            trailing = { ValueText(item.options.firstOrNull().orEmpty(), maxWidth = 260.dp) },
        )
        else -> {
            val range = ElrsTree.numericRange(item.type)
            if (range != null) {
                AxSettingRow(title = item.name, enabled = enabled) {
                    NumberField(
                        value = item.value,
                        onValueChange = onSetValue,
                        min = range.first,
                        max = range.last,
                        unit = item.units.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty(),
                        title = item.name,
                        enabled = enabled,
                        modifier = Modifier.width(150.dp),
                    )
                }
            } else {
                AxListRow(
                    title = item.name,
                    subtitle = stringResource(R.string.elrs_read_only),
                    trailing = { ValueText(item.value.toString() + item.units.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()) },
                )
            }
        }
    }
}

@Composable
private fun CommandRow(item: ElrsMenuItem, progress: CommandProgress, enabled: Boolean, onCommand: (Int) -> Unit) {
    val text = when (progress) {
        is CommandProgress.Running -> progress.text.ifEmpty { stringResource(R.string.elrs_command_running) }
        is CommandProgress.NeedsConfirmation -> progress.text
        CommandProgress.Idle -> null
    }
    AxSettingRow(title = item.name, description = text, enabled = enabled, icon = AxIcons.Play) {
        when (progress) {
            CommandProgress.Idle -> AxButton(
                text = stringResource(R.string.elrs_run),
                onClick = { onCommand(ElrsCommandStep.START) },
                enabled = enabled,
            )
            is CommandProgress.Running -> AxButton(
                text = stringResource(R.string.elrs_cancel),
                onClick = { onCommand(ElrsCommandStep.CANCEL) },
                variant = AxButtonVariant.Outlined,
            )
            is CommandProgress.NeedsConfirmation -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AxButton(text = stringResource(R.string.elrs_cancel), onClick = { onCommand(ElrsCommandStep.CANCEL) }, variant = AxButtonVariant.Text)
                AxButton(text = stringResource(R.string.elrs_confirm), onClick = { onCommand(ElrsCommandStep.CONFIRM) }, variant = AxButtonVariant.Primary)
            }
        }
    }
}
