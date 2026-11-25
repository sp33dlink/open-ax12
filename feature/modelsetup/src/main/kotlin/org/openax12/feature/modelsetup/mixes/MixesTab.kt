package org.openax12.feature.modelsetup.mixes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxDialog
import org.openax12.core.designsystem.AxDropdown
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxLiveText
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.ChannelBar
import org.openax12.core.designsystem.ChannelBarStyle
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.SetupHeaderCell
import org.openax12.core.designsystem.formatPercent
import org.openax12.core.engine.MixerMath
import org.openax12.core.model.MixKey
import org.openax12.core.model.MixLine
import org.openax12.core.model.MixMode
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.SourceId
import org.openax12.core.model.WorkingConfig
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.common.CurvePickerDialog
import org.openax12.feature.modelsetup.common.ConditionBox
import org.openax12.feature.modelsetup.common.DialogRow
import org.openax12.feature.modelsetup.common.CollectMessages
import org.openax12.feature.modelsetup.common.LiveFeed
import org.openax12.feature.modelsetup.common.ManagedBadge
import org.openax12.feature.modelsetup.common.SetupColumnHeaderRow
import org.openax12.feature.modelsetup.common.SetupDims
import org.openax12.feature.modelsetup.common.SetupNotReady
import org.openax12.feature.modelsetup.common.SetupRowCard
import org.openax12.feature.modelsetup.common.SourcePickerDialog
import org.openax12.feature.modelsetup.common.SwitchConditionDialog
import org.openax12.feature.modelsetup.common.channelLabel
import org.openax12.feature.modelsetup.common.collectLive
import org.openax12.feature.modelsetup.common.curveLabel
import org.openax12.feature.modelsetup.common.setupViewModel
import org.openax12.feature.modelsetup.logic.ExplainTemplates
import org.openax12.feature.modelsetup.logic.ManagedKind
import org.openax12.feature.modelsetup.logic.explainSteps
import org.openax12.feature.modelsetup.logic.renderExplain
import org.openax12.feature.modelsetup.logic.symbol

/** The Mixes tab. */
@Composable
internal fun MixesTab() {
    val graph = LocalAppGraph.current
    val vm = setupViewModel("modelsetup.mixes") { MixesViewModel(graph.configSession, graph.models.currentExtras) }
    val state by vm.state.collectAsStateWithLifecycle()
    val live = graph.radio.live.collectLive()
    CollectMessages(vm.messages)
    MixesScreen(
        state = state,
        live = live,
        onToggle = vm::toggle,
        onAcknowledge = vm::acknowledge,
        onUpdate = vm::update,
        onSetSource = vm::setSource,
        onAdd = vm::add,
        onDelete = vm::delete,
    )
}

private val ChWidth = 54.dp
private val InputWidth = 98.dp
private val WeightWidth = 106.dp
private val CurveWidth = 86.dp
private val SwitchWidth = 146.dp
private val ModeWidth = 118.dp
private val SlowWidth = 94.dp
private val DeleteWidth = 76.dp

/** A dialog of the Mixes tab; at most one is open. */
private sealed interface MixDialog {
    data class Source(val line: MixLineUi) : MixDialog
    data class Curve(val line: MixLineUi) : MixDialog
    data class Switch(val line: MixLineUi) : MixDialog
    data class Details(val line: MixLineUi) : MixDialog
    data class AddInput(val channel: Int) : MixDialog
    data class ManagedWarning(val line: MixLineUi, val proceed: () -> Unit) : MixDialog
    data class DeleteManaged(val line: MixLineUi) : MixDialog
}

/** Stateless Mixes screen: channel rows with live bars, expandable to their mix lines. */
@Composable
internal fun MixesScreen(
    state: MixesState,
    live: LiveFeed,
    onToggle: (Int) -> Unit,
    onAcknowledge: (MixKey) -> Unit,
    onUpdate: (MixLine, (MixLine) -> MixLine) -> Unit,
    onSetSource: (MixLine, SourceId) -> Unit,
    onAdd: (Int, SourceId) -> Unit,
    onDelete: (MixLine) -> Unit,
) {
    var dialog by remember { mutableStateOf<MixDialog?>(null) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.expanded ?: 0)
    LaunchedEffect(state.expanded) {
        state.expanded?.let { listState.animateScrollToItem(it) }
    }

    /** Runs [action] right away, or after the "generated line" warning when the line is managed and not yet accepted. */
    fun guarded(line: MixLineUi, action: () -> Unit) {
        if (line.managed != null && line.line.key !in state.acknowledged) {
            dialog = MixDialog.ManagedWarning(line, action)
        } else {
            action()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SetupColumnHeaderRow {
            SetupHeaderCell(stringResource(R.string.col_ch), Modifier.width(ChWidth), Alignment.CenterStart)
            SetupHeaderCell(stringResource(R.string.mixes_col_input), Modifier.width(InputWidth + SetupDims.Gap))
            SetupHeaderCell(stringResource(R.string.mixes_col_weight), Modifier.width(WeightWidth + SetupDims.Gap))
            SetupHeaderCell(stringResource(R.string.mixes_col_curve), Modifier.width(CurveWidth + SetupDims.Gap))
            SetupHeaderCell(stringResource(R.string.mixes_col_switch), Modifier.width(SwitchWidth + SetupDims.Gap))
            SetupHeaderCell(stringResource(R.string.mixes_col_mode), Modifier.width(ModeWidth + SetupDims.Gap))
            SetupHeaderCell(stringResource(R.string.mixes_col_slow), Modifier.width(SlowWidth + SetupDims.Gap))
        }
        if (!state.ready) {
            SetupNotReady()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 3.dp),
            ) {
                items(state.channels, key = { it.channel }) { channel ->
                    ChannelCard(
                        channel = channel,
                        expanded = state.expanded == channel.channel,
                        state = state,
                        live = live,
                        onToggle = { onToggle(channel.channel) },
                        onAddInput = { dialog = MixDialog.AddInput(channel.channel) },
                        onUpdate = { line, transform -> guarded(line) { onUpdate(line.line, transform) } },
                        onOpen = { open -> guarded(open.first) { dialog = open.second } },
                        onDelete = { line ->
                            if (line.managed != null) dialog = MixDialog.DeleteManaged(line) else onDelete(line.line)
                        },
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is MixDialog.Source -> SourcePickerDialog(
            config = state.config,
            title = stringResource(R.string.mixes_pick_input),
            selected = d.line.line.source,
            disabled = state.config.mixesOf(d.line.line.channel).map { it.source }.toSet() - d.line.line.source,
            onPick = {
                onSetSource(d.line.line, it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is MixDialog.AddInput -> SourcePickerDialog(
            config = state.config,
            title = stringResource(R.string.mixes_add_input_title, channelLabel(d.channel)),
            selected = null,
            disabled = state.config.mixesOf(d.channel).map { it.source }.toSet(),
            onPick = {
                onAdd(d.channel, it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is MixDialog.Curve -> CurvePickerDialog(
            config = state.config,
            title = stringResource(R.string.mixes_pick_curve),
            selected = d.line.line.curve,
            onPick = { curve ->
                onUpdate(d.line.line) { it.copy(curve = curve) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is MixDialog.Switch -> SwitchConditionDialog(
            title = stringResource(R.string.mixes_pick_switch),
            initial = d.line.line.switch,
            config = state.config,
            onConfirm = { condition ->
                onUpdate(d.line.line) { it.copy(switch = condition) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is MixDialog.Details -> MixDetailsDialog(
            line = d.line.line,
            onConfirm = { edited ->
                onUpdate(d.line.line) {
                    it.copy(
                        weightUp = edited.weightUp,
                        weightDown = edited.weightDown,
                        offset = edited.offset,
                        slowUpMs = edited.slowUpMs,
                        slowDownMs = edited.slowDownMs,
                    )
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is MixDialog.ManagedWarning -> {
            val kind = d.line.managed ?: ManagedKind.OTHER
            ConfirmDialog(
                title = stringResource(R.string.managed_edit_title),
                message = stringResource(R.string.managed_edit_message, managedName(kind)),
                confirmLabel = stringResource(R.string.managed_edit_confirm),
                destructive = true,
                onConfirm = {
                    onAcknowledge(d.line.line.key)
                    dialog = null
                    d.proceed()
                },
                onDismiss = { dialog = null },
            )
        }
        is MixDialog.DeleteManaged -> {
            val kind = d.line.managed ?: ManagedKind.OTHER
            ConfirmDialog(
                title = stringResource(R.string.managed_delete_title),
                message = stringResource(R.string.managed_delete_message, managedName(kind)),
                confirmLabel = stringResource(R.string.mixes_delete),
                destructive = true,
                onConfirm = {
                    onDelete(d.line.line)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
    }
}

@Composable
private fun managedName(kind: ManagedKind): String = stringResource(
    when (kind) {
        ManagedKind.FLIGHT_MODES -> R.string.managed_name_flight_modes
        ManagedKind.WING -> R.string.managed_name_wing
        ManagedKind.TEMPLATE -> R.string.managed_name_template
        ManagedKind.OTHER -> R.string.managed_name_other
    },
)

@Composable
private fun ChannelCard(
    channel: ChannelMixesUi,
    expanded: Boolean,
    state: MixesState,
    live: LiveFeed,
    onToggle: () -> Unit,
    onAddInput: () -> Unit,
    onUpdate: (MixLineUi, (MixLine) -> MixLine) -> Unit,
    onOpen: (Pair<MixLineUi, MixDialog>) -> Unit,
    onDelete: (MixLineUi) -> Unit,
) {
    val colors = AxTheme.colors
    val index = channel.channel
    SetupRowCard(highlighted = expanded) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SetupDims.Row)
                    .clickable(role = Role.Button, onClick = onToggle)
                    .padding(horizontal = SetupDims.Inner),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.width(ChWidth)) {
                    Text(
                        text = channelLabel(index),
                        style = AxTheme.typography.bodyStrong,
                        color = if (channel.lines.isEmpty()) colors.textMuted else colors.text,
                        maxLines = 1,
                    )
                    if (channel.lines.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.mixes_line_count, channel.lines.size),
                            style = AxTheme.typography.labelSmall,
                            color = colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
                ChannelBar(
                    value = { live.value.channel(index).toFloat() },
                    style = ChannelBarStyle.Setup,
                    modifier = Modifier.weight(1f),
                )
                AxLiveText(
                    value = { live.value.channel(index) },
                    format = ::formatPercent,
                    template = "-100%",
                    style = AxTheme.typography.value,
                    color = colors.text,
                    modifier = Modifier.padding(start = 6.dp),
                )
                AxIcon(
                    icon = if (expanded) AxIcons.ExpandLess else AxIcons.ExpandMore,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (expanded) {
                for (line in channel.lines) {
                    MixLineRow(line, state.config, onUpdate, onOpen, onDelete)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SetupDims.Inner, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AxButton(
                        text = stringResource(R.string.mixes_add_input),
                        onClick = onAddInput,
                        variant = AxButtonVariant.Outlined,
                        icon = AxIcons.Add,
                        height = SetupDims.Field,
                    )
                    Spacer(Modifier.width(12.dp))
                    ExplainLine(state.config, index, live, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MixLineRow(
    ui: MixLineUi,
    config: WorkingConfig,
    onUpdate: (MixLineUi, (MixLine) -> MixLine) -> Unit,
    onOpen: (Pair<MixLineUi, MixDialog>) -> Unit,
    onDelete: (MixLineUi) -> Unit,
) {
    val colors = AxTheme.colors
    val line = ui.line
    Column(modifier = Modifier.padding(horizontal = SetupDims.Inner)) {
        if (ui.managed != null) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ManagedBadge(ui.managed)
                if (ui.modified) {
                    Text(
                        text = stringResource(R.string.managed_modified),
                        style = AxTheme.typography.labelSmall,
                        color = colors.warning,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.height(SetupDims.Row),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = line.mode.symbol(),
                style = AxTheme.typography.bodyStrong,
                color = colors.textMuted,
                modifier = Modifier.width(ChWidth),
            )
            AxValueBox(
                text = ui.sourceName,
                // The mix key is its source: a generated line keeps it so the bookkeeping stays valid.
                enabled = ui.managed == null,
                onClick = { onOpen(ui to MixDialog.Source(ui)) },
                height = SetupDims.Field,
                modifier = Modifier.width(InputWidth),
            )
            Gap()
            if (line.weightUp == line.weightDown) {
                NumberField(
                    value = line.weightUp,
                    onValueChange = { v -> onUpdate(ui) { it.copy(weightUp = v, weightDown = v) } },
                    min = PERCENT_MIN,
                    max = PERCENT_MAX,
                    unit = "%",
                    title = stringResource(R.string.mixes_weight_title),
                    defaultValue = PERCENT_MAX,
                    height = SetupDims.Field,
                    modifier = Modifier.width(WeightWidth),
                )
            } else {
                AxValueBox(
                    text = stringResource(R.string.mixes_weight_pair, line.weightUp, line.weightDown),
                    onClick = { onOpen(ui to MixDialog.Details(ui)) },
                    height = SetupDims.Field,
                    modifier = Modifier.width(WeightWidth),
                )
            }
            Gap()
            AxValueBox(
                text = curveLabel(line.curve),
                onClick = { onOpen(ui to MixDialog.Curve(ui)) },
                height = SetupDims.Field,
                modifier = Modifier.width(CurveWidth),
            )
            Gap()
            ConditionBox(
                condition = line.switch,
                config = config,
                onClick = { onOpen(ui to MixDialog.Switch(ui)) },
                modifier = Modifier.width(SwitchWidth),
            )
            Gap()
            val modes = MixMode.entries
            AxDropdown(
                options = modes.map { stringResource(modeLabel(it)) },
                selectedIndex = modes.indexOf(line.mode),
                onSelected = { i -> onUpdate(ui) { it.copy(mode = modes[i]) } },
                title = stringResource(R.string.mixes_col_mode),
                height = SetupDims.Field,
                modifier = Modifier.width(ModeWidth),
            )
            Gap()
            AxValueBox(
                text = slowText(line),
                onClick = { onOpen(ui to MixDialog.Details(ui)) },
                height = SetupDims.Field,
                modifier = Modifier.width(SlowWidth),
            )
            Gap()
            AxButton(
                text = stringResource(R.string.mixes_delete),
                onClick = { onDelete(ui) },
                height = SetupDims.Field,
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.width(DeleteWidth),
            )
        }
    }
}

@Composable
private fun Gap() {
    Spacer(Modifier.width(SetupDims.Gap))
}

private fun modeLabel(mode: MixMode): Int = when (mode) {
    MixMode.REPLACE -> R.string.mixes_mode_replace
    MixMode.ADD -> R.string.mixes_mode_add
    MixMode.MULTIPLY -> R.string.mixes_mode_multiply
}

@Composable
private fun slowText(line: MixLine): String =
    if (line.slowUpMs == line.slowDownMs) {
        stringResource(R.string.mixes_slow_single, line.slowUpMs / 1000f)
    } else {
        stringResource(R.string.mixes_slow_pair, line.slowUpMs / 1000f, line.slowDownMs / 1000f)
    }

/**
 * How the channel value is composed right now: every mix line's contribution, dual rate, curve,
 * weight, limits, lock and the result. It is the app's model of the firmware mixer (assumption
 * 17); the bar above shows what the radio really outputs.
 *
 * The text is derived from the live frame, so only this text recomposes when the composition changes.
 */
@Composable
private fun ExplainLine(config: WorkingConfig, channel: Int, live: LiveFeed, modifier: Modifier = Modifier) {
    val templates = ExplainTemplates(
        line = stringResource(R.string.explain_line),
        lineOff = stringResource(R.string.explain_line_off),
        dualRate = stringResource(R.string.explain_dual_rate),
        curve = stringResource(R.string.explain_curve),
        weight = stringResource(R.string.explain_weight),
        limits = stringResource(R.string.explain_limits),
        lock = stringResource(R.string.explain_lock),
        output = stringResource(R.string.explain_output),
    )
    val text by remember(config, channel, templates) {
        derivedStateOf {
            val explanation = MixerMath.explain(config, live.value, channel)
            if (explanation == null) {
                ""
            } else {
                renderExplain(explainSteps(explanation, config.channel(channel)), config::sourceName, templates)
            }
        }
    }
    Text(
        text = text,
        style = AxTheme.typography.label,
        color = AxTheme.colors.textMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Dialog for the fine settings of a line: weights, offset and slow times. */
@Composable
internal fun MixDetailsDialog(line: MixLine, onConfirm: (MixLine) -> Unit, onDismiss: () -> Unit) {
    var weightUp by remember { mutableStateOf(line.weightUp) }
    var weightDown by remember { mutableStateOf(line.weightDown) }
    var offset by remember { mutableStateOf(line.offset) }
    var slowUp by remember { mutableStateOf(line.slowUpMs / 100) }
    var slowDown by remember { mutableStateOf(line.slowDownMs / 100) }
    AxDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.mixes_details_title, line.sourceName.ifBlank { line.source.defaultName }),
        width = 440.dp,
        buttons = {
            AxButton(
                text = stringResource(org.openax12.core.designsystem.R.string.ax_cancel),
                onClick = onDismiss,
                variant = AxButtonVariant.Text,
            )
            AxButton(
                text = stringResource(org.openax12.core.designsystem.R.string.ax_ok),
                variant = AxButtonVariant.Primary,
                onClick = {
                    onConfirm(
                        line.copy(
                            weightUp = weightUp,
                            weightDown = weightDown,
                            offset = offset,
                            // Untouched values keep their exact milliseconds (the field shows tenths).
                            slowUpMs = if (slowUp == line.slowUpMs / 100) line.slowUpMs else slowUp * 100,
                            slowDownMs = if (slowDown == line.slowDownMs / 100) line.slowDownMs else slowDown * 100,
                        ),
                    )
                },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DialogRow(stringResource(R.string.mixes_weight_up)) {
                PercentBox(weightUp, stringResource(R.string.mixes_weight_up)) { weightUp = it }
            }
            DialogRow(stringResource(R.string.mixes_weight_down)) {
                PercentBox(weightDown, stringResource(R.string.mixes_weight_down)) { weightDown = it }
            }
            DialogRow(stringResource(R.string.mixes_offset)) {
                PercentBox(offset, stringResource(R.string.mixes_offset), default = 0) { offset = it }
            }
            DialogRow(stringResource(R.string.mixes_slow_up)) {
                SecondsBox(slowUp, stringResource(R.string.mixes_slow_up)) { slowUp = it }
            }
            DialogRow(stringResource(R.string.mixes_slow_down)) {
                SecondsBox(slowDown, stringResource(R.string.mixes_slow_down)) { slowDown = it }
            }
        }
    }
}

@Composable
private fun PercentBox(value: Int, title: String, default: Int = PERCENT_MAX, onChange: (Int) -> Unit) {
    NumberField(
        value = value,
        onValueChange = onChange,
        min = PERCENT_MIN,
        max = PERCENT_MAX,
        unit = "%",
        title = title,
        defaultValue = default,
        height = SetupDims.Field,
        modifier = Modifier.width(110.dp),
    )
}

@Composable
private fun SecondsBox(tenths: Int, title: String, onChange: (Int) -> Unit) {
    NumberField(
        value = tenths,
        onValueChange = onChange,
        min = 0,
        max = 600,
        decimals = 1,
        unit = "s",
        title = title,
        defaultValue = 0,
        height = SetupDims.Field,
        modifier = Modifier.width(110.dp),
    )
}
