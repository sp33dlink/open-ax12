package org.openax12.feature.modelsetup.chlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxDialog
import org.openax12.core.designsystem.AxDropdown
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxLiveText
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.SetupHeaderCell
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.WorkingConfig
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.common.ConditionBox
import org.openax12.feature.modelsetup.common.DialogRow
import org.openax12.feature.modelsetup.common.CollectMessages
import org.openax12.feature.modelsetup.common.LiveFeed
import org.openax12.feature.modelsetup.common.SetupColumnHeaderRow
import org.openax12.feature.modelsetup.common.SetupDims
import org.openax12.feature.modelsetup.common.SetupNotReady
import org.openax12.feature.modelsetup.common.SetupRowCard
import org.openax12.feature.modelsetup.common.SetupViewModel
import org.openax12.feature.modelsetup.common.SwitchConditionDialog
import org.openax12.feature.modelsetup.common.channelLabel
import org.openax12.feature.modelsetup.common.collectLive
import org.openax12.feature.modelsetup.common.setupViewModel
import org.openax12.feature.modelsetup.logic.findThrottleChannel

/** State of the ChLock tab. */
internal data class ChLockState(
    val ready: Boolean = false,
    val config: WorkingConfig = WorkingConfig.EMPTY,
    val channels: List<OutputChannelConfig> = emptyList(),
    val throttleChannel: Int? = null,
)

/** The value a throttle cut holds the channel at. */
internal const val THROTTLE_CUT_VALUE: Int = PERCENT_MIN

/**
 * The switch a new throttle cut starts with: SF (the usual one) when the radio reports it,
 * else the first enabled switch, else SA.
 */
internal fun defaultCutSwitch(config: WorkingConfig): SwitchCondition {
    val switches = config.sources.filter { it.enabled && it.id.isSwitch }.map { it.id }
    val source = SourceId.SF.takeIf { it in switches || switches.isEmpty() } ?: switches.first()
    return SwitchCondition.at(source, SwitchPosition.DOWN)
}

/** View model of the ChLock tab: the channel lock of every channel and the throttle-cut helper. */
internal class ChLockViewModel(
    session: ConfigSession,
    scopeOverride: CoroutineScope? = null,
) : SetupViewModel(session, scopeOverride) {

    /** State for the screen. */
    val state: StateFlow<ChLockState> = session.workingConfig
        .map { ChLockState(ready = !it.isEmpty, config = it, channels = it.channels, throttleChannel = findThrottleChannel(it)) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ChLockState())

    /** Sets lock condition and value of [channel] in one write. */
    fun setLock(channel: Int, condition: SwitchCondition, value: Int) {
        update(channel) { it.copy(lock = condition, lockValue = value.coerceIn(PERCENT_MIN, PERCENT_MAX)) }
    }

    /** Sets only the locked output value. */
    fun setValue(channel: Int, value: Int) {
        update(channel) { it.copy(lockValue = value.coerceIn(PERCENT_MIN, PERCENT_MAX)) }
    }

    /** Sets only the condition. */
    fun setCondition(channel: Int, condition: SwitchCondition) {
        update(channel) { it.copy(lock = condition) }
    }

    /** Removes the lock of [channel]. */
    fun clear(channel: Int) {
        update(channel) { it.copy(lock = it.lock.copy(op = org.openax12.core.model.CompareOp.DISABLED, value = 0)) }
    }

    private fun update(channel: Int, transform: (OutputChannelConfig) -> OutputChannelConfig) {
        if (channel !in 0 until CHANNEL_COUNT) return
        edit { radio ->
            val current = radio.getOutputChannels().firstOrNull { it.channel == channel } ?: return@edit false
            radio.setOutputChannel(transform(current))
        }
    }
}

/** The ChLock tab. */
@Composable
internal fun ChLockTab() {
    val graph = LocalAppGraph.current
    val vm = setupViewModel("modelsetup.chlock") { ChLockViewModel(graph.configSession) }
    val state by vm.state.collectAsStateWithLifecycle()
    val live = graph.radio.live.collectLive()
    CollectMessages(vm.messages)
    ChLockScreen(
        state = state,
        live = live,
        onSetCondition = vm::setCondition,
        onSetValue = vm::setValue,
        onClear = vm::clear,
        onThrottleCut = vm::setLock,
    )
}

private val ChWidth = 54.dp
private val CondWidth = 190.dp
private val ValueWidth = 96.dp
private val ClearWidth = 44.dp

private sealed interface LockDialog {
    data class Condition(val channel: OutputChannelConfig) : LockDialog
    data object ThrottleCut : LockDialog
}

/** Stateless ChLock screen. */
@Composable
internal fun ChLockScreen(
    state: ChLockState,
    live: LiveFeed,
    onSetCondition: (Int, SwitchCondition) -> Unit,
    onSetValue: (Int, Int) -> Unit,
    onClear: (Int) -> Unit,
    onThrottleCut: (Int, SwitchCondition, Int) -> Unit,
) {
    var dialog by remember { mutableStateOf<LockDialog?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        SetupColumnHeaderRow {
            SetupHeaderCell(stringResource(R.string.col_ch), Modifier.width(ChWidth), Alignment.CenterStart)
            SetupHeaderCell(stringResource(R.string.chlock_col_switch), Modifier.width(CondWidth + SetupDims.Gap))
            SetupHeaderCell(stringResource(R.string.chlock_col_value), Modifier.width(ValueWidth + SetupDims.Gap))
            SetupHeaderCell(stringResource(R.string.chlock_col_state), Modifier.weight(1f))
        }
        if (!state.ready) {
            SetupNotReady()
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 3.dp)) {
                item(key = "throttle-cut") { ThrottleCutCard(state, onOpen = { dialog = LockDialog.ThrottleCut }) }
                items(state.channels, key = { it.channel }) { channel ->
                    LockRow(
                        channel = channel,
                        config = state.config,
                        live = live,
                        onEditCondition = { dialog = LockDialog.Condition(channel) },
                        onSetValue = { onSetValue(channel.channel, it) },
                        onClear = { onClear(channel.channel) },
                    )
                }
            }
        }
    }
    when (val d = dialog) {
        null -> Unit
        is LockDialog.Condition -> SwitchConditionDialog(
            title = stringResource(R.string.chlock_condition_title, channelLabel(d.channel.channel)),
            initial = if (d.channel.lock.isEnabled) d.channel.lock else defaultCutSwitch(state.config),
            config = state.config,
            allowOff = false,
            onConfirm = {
                onSetCondition(d.channel.channel, it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        LockDialog.ThrottleCut -> ThrottleCutDialog(
            state = state,
            onApply = { channel, condition, value ->
                onThrottleCut(channel, condition, value)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun ThrottleCutCard(state: ChLockState, onOpen: () -> Unit) {
    val colors = AxTheme.colors
    SetupRowCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SetupDims.Inner, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AxIcon(icon = AxIcons.Warning, contentDescription = null, tint = colors.orange, size = 24.dp)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    text = stringResource(R.string.chlock_cut_title),
                    style = AxTheme.typography.bodyStrong,
                    color = colors.text,
                )
                Text(
                    text = state.throttleChannel
                        ?.let { stringResource(R.string.chlock_cut_hint_found, channelLabel(it)) }
                        ?: stringResource(R.string.chlock_cut_hint),
                    style = AxTheme.typography.label,
                    color = colors.textMuted,
                    maxLines = 2,
                )
            }
            AxButton(
                text = stringResource(R.string.chlock_cut_button),
                onClick = onOpen,
                variant = AxButtonVariant.Primary,
                height = SetupDims.Field,
            )
        }
    }
}

@Composable
private fun LockRow(
    channel: OutputChannelConfig,
    config: WorkingConfig,
    live: LiveFeed,
    onEditCondition: () -> Unit,
    onSetValue: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val colors = AxTheme.colors
    val lock = channel.lock
    val lockedText = stringResource(R.string.chlock_state_locked)
    SetupRowCard {
        Row(
            modifier = Modifier
                .height(SetupDims.Row)
                .padding(horizontal = SetupDims.Inner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = channelLabel(channel.channel),
                style = AxTheme.typography.bodyStrong,
                color = if (lock.isEnabled) colors.text else colors.textMuted,
                modifier = Modifier.width(ChWidth),
            )
            ConditionBox(
                condition = lock,
                config = config,
                onClick = onEditCondition,
                modifier = Modifier.width(CondWidth),
            )
            Spacer(Modifier.width(SetupDims.Gap))
            NumberField(
                value = channel.lockValue,
                onValueChange = onSetValue,
                min = PERCENT_MIN,
                max = PERCENT_MAX,
                unit = "%",
                enabled = lock.isEnabled,
                title = stringResource(R.string.chlock_value_title, channelLabel(channel.channel)),
                defaultValue = PERCENT_MIN,
                height = SetupDims.Field,
                modifier = Modifier.width(ValueWidth),
            )
            Spacer(Modifier.width(SetupDims.Gap))
            if (lock.isEnabled) {
                AxIconButton(
                    icon = AxIcons.Close,
                    contentDescription = stringResource(R.string.chlock_clear),
                    onClick = onClear,
                    modifier = Modifier.size(ClearWidth),
                )
            } else {
                Spacer(Modifier.size(ClearWidth))
            }
            // Live: shows while the lock holds the channel.
            AxLiveText(
                value = { if (lock.isEnabled && lock.test(live.value.logicAt(lock.source.index)) == true) 1 else 0 },
                format = { if (it == 1) lockedText else "" },
                template = lockedText,
                style = AxTheme.typography.bodyStrong,
                color = colors.orange,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** The one-tap helper: pick channel, switch and value (pre-filled), apply. */
@Composable
internal fun ThrottleCutDialog(
    state: ChLockState,
    onApply: (Int, SwitchCondition, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var channel by remember { mutableStateOf(state.throttleChannel ?: DEFAULT_THROTTLE_CHANNEL) }
    var condition by remember {
        mutableStateOf(
            state.channels.firstOrNull { it.channel == channel }?.lock?.takeIf { it.isEnabled } ?: defaultCutSwitch(state.config),
        )
    }
    var value by remember { mutableStateOf(THROTTLE_CUT_VALUE) }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        SwitchConditionDialog(
            title = stringResource(R.string.chlock_cut_switch),
            initial = condition,
            config = state.config,
            allowOff = false,
            onConfirm = {
                condition = it
                picking = false
            },
            onDismiss = { picking = false },
        )
        return
    }
    val existing = state.channels.firstOrNull { it.channel == channel }?.lock?.takeIf { it.isEnabled }
    AxDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chlock_cut_title),
        width = 480.dp,
        buttons = {
            AxButton(
                text = stringResource(org.openax12.core.designsystem.R.string.ax_cancel),
                onClick = onDismiss,
                variant = AxButtonVariant.Text,
            )
            AxButton(
                text = stringResource(R.string.chlock_cut_apply),
                onClick = { onApply(channel, condition, value) },
                variant = AxButtonVariant.Primary,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.chlock_cut_explain),
                style = AxTheme.typography.label,
                color = AxTheme.colors.textMuted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            DialogRow(stringResource(R.string.chlock_cut_channel)) {
                AxDropdown(
                    options = (0 until CHANNEL_COUNT).map { channelLabel(it) },
                    selectedIndex = channel,
                    onSelected = { channel = it },
                    title = stringResource(R.string.chlock_cut_channel),
                    columns = 4,
                    height = SetupDims.Field,
                    modifier = Modifier.width(110.dp),
                )
            }
            DialogRow(stringResource(R.string.chlock_cut_switch)) {
                ConditionBox(condition = condition, config = state.config, onClick = { picking = true }, modifier = Modifier.width(150.dp))
            }
            DialogRow(stringResource(R.string.chlock_cut_value)) {
                NumberField(
                    value = value,
                    onValueChange = { value = it },
                    min = PERCENT_MIN,
                    max = PERCENT_MAX,
                    unit = "%",
                    title = stringResource(R.string.chlock_cut_value),
                    defaultValue = THROTTLE_CUT_VALUE,
                    height = SetupDims.Field,
                    modifier = Modifier.width(110.dp),
                )
            }
            if (existing != null) {
                Text(
                    text = stringResource(R.string.chlock_cut_replaces, channelLabel(channel)),
                    style = AxTheme.typography.label,
                    color = AxTheme.colors.warning,
                )
            }
        }
    }
}

/** CH03: the throttle of the usual AETR order, used when no channel carries the throttle stick yet. */
private const val DEFAULT_THROTTLE_CHANNEL = 2
