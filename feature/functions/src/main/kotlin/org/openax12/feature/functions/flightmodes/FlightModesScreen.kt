package org.openax12.feature.functions.flightmodes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.PercentField
import org.openax12.core.designsystem.PickerDialog
import org.openax12.core.designsystem.PickerOption
import org.openax12.core.designsystem.TextEntryDialog
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.FLIGHT_MODE_MAX
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.ModelType
import org.openax12.core.model.SwitchPosition
import org.openax12.feature.functions.R
import org.openax12.feature.functions.common.BannerTone
import org.openax12.feature.functions.common.Caption
import org.openax12.feature.functions.common.FormRow
import org.openax12.feature.functions.common.InfoBanner
import org.openax12.feature.functions.common.NumberWidth
import org.openax12.feature.functions.common.ScreenPadding
import org.openax12.feature.functions.common.SourceField
import org.openax12.feature.functions.common.conflictText
import org.openax12.feature.functions.common.PlanDiffDialog
import org.openax12.feature.functions.common.modelTypeLabel
import org.openax12.feature.functions.common.positionLabel

/** Pages of the flight-modes tab. */
private enum class FlightModesPage { MODES, CHANNELS }

private val ChannelColumnWidth = 76.dp
private val ValueColumnWidth = 76.dp

/**
 * The "Flight modes" tab: one switch selects up to three modes; each mode shifts channels
 * (offset) and scales their travel (rate). Everything compiles to firmware items on Apply.
 */
@Composable
internal fun FlightModesScreen(
    state: FlightModesState,
    onAction: (FlightModesAction) -> Unit,
    onOpenMixes: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
) {
    var page by rememberSaveable { mutableStateOf(FlightModesPage.entries[initialPage.coerceIn(0, FlightModesPage.entries.lastIndex)]) }
    var confirmPreset by rememberSaveable { mutableStateOf(false) }
    val draft = state.draft
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Toolbar(
            state = state,
            page = page,
            onPage = { page = it },
            onAction = onAction,
        )
        val problem = state.problems.firstOrNull()
        if (draft.enabled && problem != null) {
            val more = state.problems.size - 1
            InfoBanner(
                text = conflictText(problem) + if (more > 0) "  " + stringResource(R.string.fn_more_problems, more) else "",
                tone = BannerTone.WARNING,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!draft.enabled) {
                DisabledCard(state, onEnable = { onAction(FlightModesAction.SetEnabled(true)) })
            } else when (page) {
                FlightModesPage.MODES -> ModesPage(state, onAction, onPreset = { confirmPreset = true })
                FlightModesPage.CHANNELS -> ChannelsPage(state, onAction, onOpenMixes)
            }
        }
    }
    val diff = state.pendingDiff
    if (diff != null) {
        PlanDiffDialog(
            diff = diff,
            busy = state.applying,
            onApply = { onAction(FlightModesAction.ConfirmApply) },
            onDismiss = { onAction(FlightModesAction.DismissDiff) },
        )
    }
    if (confirmPreset) {
        val names = rememberPresetNames()
        ConfirmDialog(
            title = stringResource(R.string.fn_fm_preset_title),
            message = stringResource(R.string.fn_fm_preset_message, modelTypeLabel(state.modelType)),
            confirmLabel = stringResource(R.string.fn_fm_preset_confirm),
            onConfirm = {
                confirmPreset = false
                onAction(FlightModesAction.ApplyPreset(names))
            },
            onDismiss = { confirmPreset = false },
        )
    }
}

@Composable
private fun Toolbar(
    state: FlightModesState,
    page: FlightModesPage,
    onPage: (FlightModesPage) -> Unit,
    onAction: (FlightModesAction) -> Unit,
) {
    val draft = state.draft
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AxSwitch(checked = draft.enabled, onCheckedChange = { onAction(FlightModesAction.SetEnabled(it)) })
        Text(
            text = stringResource(R.string.fn_fm_title),
            style = AxTheme.typography.bodyStrong,
            color = AxTheme.colors.text,
            maxLines = 1,
        )
        if (draft.enabled) {
            Caption(stringResource(R.string.fn_fm_switch))
            SourceField(
                selected = draft.modeSwitch,
                options = state.pickers.switches,
                onSelected = { onAction(FlightModesAction.SetSwitch(it)) },
                title = stringResource(R.string.fn_fm_pick_switch),
            )
            AxSegmented(
                options = listOf(stringResource(R.string.fn_fm_page_modes), stringResource(R.string.fn_fm_page_channels)),
                selectedIndex = page.ordinal,
                onSelected = { onPage(FlightModesPage.entries[it]) },
                modifier = Modifier.width(210.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (state.dirty) {
            AxButton(
                text = stringResource(R.string.fn_revert),
                onClick = { onAction(FlightModesAction.Revert) },
                variant = AxButtonVariant.Text,
                enabled = !state.applying,
            )
        }
        AxButton(
            text = stringResource(R.string.fn_apply),
            onClick = { onAction(FlightModesAction.RequestApply) },
            variant = AxButtonVariant.Primary,
            enabled = state.dirty && state.valid && !state.applying,
        )
    }
}

@Composable
private fun DisabledCard(state: FlightModesState, onEnable: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AxEmptyState(
            title = stringResource(R.string.fn_fm_off_title),
            message = stringResource(R.string.fn_fm_off_message) + "\n" + typeHint(state.modelType),
            icon = AxIcons.FlightMode,
            actionLabel = stringResource(R.string.fn_fm_turn_on),
            onAction = onEnable,
        )
    }
}

@Composable
private fun typeHint(type: ModelType): String = stringResource(
    when (type) {
        ModelType.GLIDER -> R.string.fn_fm_hint_glider
        ModelType.PLANE -> R.string.fn_fm_hint_plane
        ModelType.WING -> R.string.fn_fm_hint_plane
        ModelType.MULTIROTOR -> R.string.fn_fm_hint_multirotor
        ModelType.HELICOPTER -> R.string.fn_fm_hint_helicopter
        ModelType.OTHER -> R.string.fn_fm_hint_other
    },
)

@Composable
private fun ModesPage(state: FlightModesState, onAction: (FlightModesAction) -> Unit, onPreset: () -> Unit) {
    var renaming by rememberSaveable { mutableStateOf<Int?>(null) }
    val modes = state.draft.modes
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (slot in 0 until FLIGHT_MODE_MAX) {
                val mode = modes.getOrNull(slot)
                when {
                    mode != null -> ModeCard(
                        index = slot,
                        mode = mode,
                        active = state.dirty.not() && state.activeMode == slot,
                        canRemove = modes.size > 1,
                        onAction = onAction,
                        onRename = { renaming = slot },
                        modifier = Modifier.weight(1f),
                    )
                    slot == modes.size -> AxCard(
                        modifier = Modifier.weight(1f).height(ModeCardMinHeight),
                        cornerRadius = AxTheme.shapes.panel,
                        elevation = 3.dp,
                        onClick = { onAction(FlightModesAction.AddMode) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AxIcon(AxIcons.Add, contentDescription = null, tint = AxTheme.colors.accent, size = 32.dp)
                            Text(
                                text = stringResource(R.string.fn_fm_add_mode),
                                style = AxTheme.typography.bodyStrong,
                                color = AxTheme.colors.accent,
                            )
                        }
                    }
                    else -> Spacer(Modifier.weight(1f))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.fn_fm_default_note),
                style = AxTheme.typography.label,
                color = AxTheme.colors.textMuted,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            AxButton(
                text = stringResource(R.string.fn_fm_preset_button, modelTypeLabel(state.modelType)),
                onClick = onPreset,
                variant = AxButtonVariant.Outlined,
                icon = AxIcons.Restore,
            )
        }
    }
    renaming?.let { index ->
        val mode = modes.getOrNull(index)
        if (mode != null) {
            TextEntryDialog(
                title = stringResource(R.string.fn_fm_rename_title),
                initialValue = mode.name,
                onConfirm = {
                    renaming = null
                    onAction(FlightModesAction.Rename(index, it))
                },
                onDismiss = { renaming = null },
                maxLength = 24,
                allowEmpty = true,
                placeholder = stringResource(R.string.fn_mode_n, index + 1),
            )
        }
    }
}

private val ModeCardMinHeight = 214.dp

@Composable
private fun ModeCard(
    index: Int,
    mode: FlightModeConfig,
    active: Boolean,
    canRemove: Boolean,
    onAction: (FlightModesAction) -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AxTheme.colors
    val positions = SwitchPosition.entries
    AxCard(
        modifier = modifier
            .height(ModeCardMinHeight)
            .then(if (active) Modifier.border(2.dp, colors.teal, RoundedCornerShape(AxTheme.shapes.panel)) else Modifier),
        cornerRadius = AxTheme.shapes.panel,
        elevation = 4.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val activeText = stringResource(R.string.fn_fm_active)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(if (active) colors.teal else colors.accent, CircleShape)
                        .semantics { if (active) contentDescription = activeText },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "${index + 1}", style = AxTheme.typography.bodyStrong, color = colors.onAccent)
                }
                AxValueBox(
                    text = mode.name.ifBlank { stringResource(R.string.fn_mode_n, index + 1) },
                    onClick = onRename,
                    trailingIcon = AxIcons.Edit,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
                if (canRemove) {
                    AxIconButton(
                        icon = AxIcons.Delete,
                        contentDescription = stringResource(R.string.fn_fm_remove_mode),
                        onClick = { onAction(FlightModesAction.RemoveMode(index)) },
                    )
                }
            }
            AxSegmented(
                options = positions.map { positionLabel(it) },
                selectedIndex = positions.indexOf(mode.position),
                onSelected = { onAction(FlightModesAction.SetPosition(index, positions[it])) },
                modifier = Modifier.fillMaxWidth(),
            )
            FormRow(label = stringResource(R.string.fn_fm_transition)) {
                NumberField(
                    value = mode.transitionMs / 100,
                    onValueChange = { onAction(FlightModesAction.SetTransition(index, it * 100)) },
                    min = 0,
                    max = 300,
                    decimals = 1,
                    unit = " s",
                    title = stringResource(R.string.fn_fm_transition),
                    modifier = Modifier.width(NumberWidth),
                )
            }
            FormRow(label = stringResource(R.string.fn_fm_announce)) {
                AxSwitch(checked = mode.announce, onCheckedChange = { onAction(FlightModesAction.SetAnnounce(index, it)) })
            }
        }
    }
}

@Composable
private fun ChannelsPage(state: FlightModesState, onAction: (FlightModesAction) -> Unit, onOpenMixes: () -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    val modes = state.draft.modes
    val rows = state.rows
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChannelHeader(modes)
        if (rows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = typeHint(state.modelType),
                    style = AxTheme.typography.body,
                    color = AxTheme.colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(rows, key = { it }) { channel ->
                    ChannelRow(channel, modes, onAction)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AxButton(
                text = stringResource(R.string.fn_fm_add_channel),
                onClick = { adding = true },
                icon = AxIcons.Add,
                enabled = rows.size < CHANNEL_COUNT,
            )
            Spacer(Modifier.weight(1f))
            if (!state.dirty && state.applied.isActive) {
                AxButton(
                    text = stringResource(R.string.fn_open_mixes),
                    onClick = onOpenMixes,
                    variant = AxButtonVariant.Text,
                    icon = AxIcons.Mix,
                )
            }
        }
    }
    if (adding) {
        PickerDialog(
            title = stringResource(R.string.fn_pick_channel),
            options = List(CHANNEL_COUNT) { channel -> PickerOption(channel, "CH${channel + 1}", enabled = channel !in rows) },
            selected = null,
            onSelect = {
                adding = false
                onAction(FlightModesAction.AddChannel(it))
            },
            onDismiss = { adding = false },
            columns = 4,
            searchable = false,
        )
    }
}

@Composable
private fun ChannelHeader(modes: List<FlightModeConfig>) {
    val colors = AxTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.chrome, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.fn_fm_col_channel),
            style = AxTheme.typography.chrome,
            color = colors.onChrome,
            modifier = Modifier.width(ChannelColumnWidth),
            maxLines = 1,
        )
        modes.forEachIndexed { index, mode ->
            Column(modifier = Modifier.width(ValueColumnWidth * 2), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = mode.name.ifBlank { stringResource(R.string.fn_mode_n, index + 1) },
                    style = AxTheme.typography.chrome,
                    color = colors.onChrome,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row {
                    Text(
                        text = stringResource(R.string.fn_fm_col_offset),
                        style = AxTheme.typography.labelSmall,
                        color = colors.onChromeMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(ValueColumnWidth),
                    )
                    Text(
                        text = stringResource(R.string.fn_fm_col_rate),
                        style = AxTheme.typography.labelSmall,
                        color = colors.onChromeMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(ValueColumnWidth),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun ChannelRow(channel: Int, modes: List<FlightModeConfig>, onAction: (FlightModesAction) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "CH${channel + 1}",
            style = AxTheme.typography.bodyStrong,
            color = AxTheme.colors.text,
            modifier = Modifier.width(ChannelColumnWidth),
        )
        modes.forEachIndexed { index, mode ->
            Row(modifier = Modifier.padding(end = 8.dp)) {
                PercentField(
                    value = mode.offsets[channel] ?: 0,
                    onValueChange = { onAction(FlightModesAction.SetOffset(index, channel, it)) },
                    modifier = Modifier.width(ValueColumnWidth),
                    defaultValue = 0,
                    title = stringResource(R.string.fn_fm_col_offset),
                )
                PercentField(
                    value = mode.rates[channel] ?: 100,
                    onValueChange = { onAction(FlightModesAction.SetRate(index, channel, it)) },
                    modifier = Modifier.width(ValueColumnWidth),
                    min = 0,
                    max = 100,
                    defaultValue = 100,
                    title = stringResource(R.string.fn_fm_col_rate),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        AxIconButton(
            icon = AxIcons.Delete,
            contentDescription = stringResource(R.string.fn_fm_remove_channel),
            onClick = { onAction(FlightModesAction.RemoveChannel(channel)) },
        )
    }
}

@Composable
private fun rememberPresetNames(): PresetNames {
    val normal = stringResource(R.string.fn_preset_normal)
    val thermal = stringResource(R.string.fn_preset_thermal)
    val speed = stringResource(R.string.fn_preset_speed)
    val sport = stringResource(R.string.fn_preset_sport)
    val landing = stringResource(R.string.fn_preset_landing)
    val angle = stringResource(R.string.fn_preset_angle)
    val horizon = stringResource(R.string.fn_preset_horizon)
    val acro = stringResource(R.string.fn_preset_acro)
    val idle1 = stringResource(R.string.fn_preset_idle_up_1)
    val idle2 = stringResource(R.string.fn_preset_idle_up_2)
    val m1 = stringResource(R.string.fn_mode_n, 1)
    val m2 = stringResource(R.string.fn_mode_n, 2)
    val m3 = stringResource(R.string.fn_mode_n, 3)
    return remember(normal, thermal, speed, sport, landing, angle, horizon, acro, idle1, idle2, m1, m2, m3) {
        PresetNames(normal, thermal, speed, sport, landing, angle, horizon, acro, idle1, idle2, m1, m2, m3)
    }
}
