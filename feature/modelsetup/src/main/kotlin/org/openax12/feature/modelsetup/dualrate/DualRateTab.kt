package org.openax12.feature.modelsetup.dualrate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.SetupHeaderCell
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.DR_SLOTS
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.WorkingConfig
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.common.ConditionBox
import org.openax12.feature.modelsetup.common.CollectMessages
import org.openax12.feature.modelsetup.common.LiveFeed
import org.openax12.feature.modelsetup.common.ManagedBadge
import org.openax12.feature.modelsetup.common.SetupColumnHeaderRow
import org.openax12.feature.modelsetup.common.SetupDims
import org.openax12.feature.modelsetup.common.SetupNotReady
import org.openax12.feature.modelsetup.common.SetupRowCard
import org.openax12.feature.modelsetup.common.SetupViewModel
import org.openax12.feature.modelsetup.common.SwitchConditionDialog
import org.openax12.feature.modelsetup.common.channelLabel
import org.openax12.feature.modelsetup.common.collectLive
import org.openax12.feature.modelsetup.common.setupViewModel
import org.openax12.feature.modelsetup.logic.ManagedKind
import org.openax12.feature.modelsetup.logic.activeDualRateSlot
import org.openax12.core.model.SourceId

/** Identity of a dual-rate slot. */
internal data class DrKey(val channel: Int, val slot: Int)

/** One slot for the UI; [managed] / [modified] as for mix lines. */
internal data class DualRateSlotUi(val slot: DualRateSlot, val managed: ManagedKind?, val modified: Boolean)

/** One channel with its three slots. */
internal data class DualRateRowUi(val channel: Int, val slots: List<DualRateSlotUi>)

/** State of the DualRate tab. */
internal data class DualRateState(
    val ready: Boolean = false,
    val config: WorkingConfig = WorkingConfig.EMPTY,
    val rows: List<DualRateRowUi> = emptyList(),
    val acknowledged: Set<DrKey> = emptySet(),
)

/** Builds the rows: all channels, always [DR_SLOTS] slots (missing ones as disabled defaults). */
internal fun dualRateRows(config: WorkingConfig, extras: ModelExtras): List<DualRateRowUi> =
    config.channels.sortedBy { it.channel }.map { channel ->
        DualRateRowUi(
            channel = channel.channel,
            slots = (0 until DR_SLOTS).map { index ->
                val slot = config.dualRate(channel.channel, index) ?: DualRateSlot(channel.channel, index)
                val record = extras.managed.dualRate(channel.channel, index)
                DualRateSlotUi(
                    slot = slot,
                    managed = ManagedKind.of(record?.generator),
                    modified = record != null && extras.managed.isModified(record, config),
                )
            },
        )
    }

/**
 * View model of the DualRate tab. The SDK cannot delete a slot (`rcDelDrData` does nothing), so
 * "delete" writes the slot back with its condition disabled; the tab says so.
 */
internal class DualRateViewModel(
    session: ConfigSession,
    extras: StateFlow<ModelExtras>,
    scopeOverride: CoroutineScope? = null,
) : SetupViewModel(session, scopeOverride) {

    private val acknowledged = MutableStateFlow<Set<DrKey>>(emptySet())

    /** State for the screen. */
    val state: StateFlow<DualRateState> = combine(session.workingConfig, extras, acknowledged) { config, ex, ack ->
        DualRateState(ready = !config.isEmpty, config = config, rows = dualRateRows(config, ex), acknowledged = ack)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), DualRateState())

    /** The user accepted to edit the generated slot [key] by hand. */
    fun acknowledge(key: DrKey) {
        acknowledged.update { it + key }
    }

    /** Sets the condition of a slot; a disabled condition frees it (and drops its managed record). */
    fun setSwitch(channel: Int, slot: Int, condition: SwitchCondition) {
        if (channel !in 0 until CHANNEL_COUNT || slot !in 0 until DR_SLOTS) return
        scope.launch {
            val ok = session.edit { radio ->
                val current = radio.getDualRate(channel, slot) ?: return@edit false
                radio.setDualRate(current.copy(switch = condition)) != null
            }
            if (!ok) {
                message(R.string.setup_write_failed)
            } else if (!condition.isEnabled) {
                session.updateExtras { it.copy(managed = it.managed.withoutDualRate(channel, slot)) }
            }
        }
    }

    /** Disables the slot (the closest thing to deleting it). */
    fun disable(channel: Int, slot: Int) {
        setSwitch(channel, slot, SwitchCondition.NONE)
    }

    /** Sets the weight of a slot. */
    fun setWeight(channel: Int, slot: Int, weight: Int) {
        if (channel !in 0 until CHANNEL_COUNT || slot !in 0 until DR_SLOTS) return
        edit { radio ->
            val current = radio.getDualRate(channel, slot) ?: return@edit false
            radio.setDualRate(current.copy(weight = weight.coerceIn(PERCENT_MIN, PERCENT_MAX))) != null
        }
    }
}

/** The DualRate tab. */
@Composable
internal fun DualRateTab() {
    val graph = LocalAppGraph.current
    val vm = setupViewModel("modelsetup.dualrate") { DualRateViewModel(graph.configSession, graph.models.currentExtras) }
    val state by vm.state.collectAsStateWithLifecycle()
    val live = graph.radio.live.collectLive()
    CollectMessages(vm.messages)
    DualRateScreen(
        state = state,
        live = live,
        onSetSwitch = vm::setSwitch,
        onSetWeight = vm::setWeight,
        onDisable = vm::disable,
        onAcknowledge = vm::acknowledge,
    )
}

private val ChWidth = 54.dp
private val DotWidth = 12.dp
private val CondWidth = 108.dp
private val WeightWidth = 72.dp
private val ClearWidth = 44.dp
private val SmallGap = 4.dp
private val SlotWidth = DotWidth + CondWidth + WeightWidth + ClearWidth + SmallGap * 2

private sealed interface DrDialog {
    data class Condition(val ui: DualRateSlotUi) : DrDialog
    data class Warning(val ui: DualRateSlotUi, val proceed: () -> Unit) : DrDialog
}

/** Stateless DualRate screen. */
@Composable
internal fun DualRateScreen(
    state: DualRateState,
    live: LiveFeed,
    onSetSwitch: (Int, Int, SwitchCondition) -> Unit,
    onSetWeight: (Int, Int, Int) -> Unit,
    onDisable: (Int, Int) -> Unit,
    onAcknowledge: (DrKey) -> Unit,
) {
    var dialog by remember { mutableStateOf<DrDialog?>(null) }

    fun guarded(ui: DualRateSlotUi, action: () -> Unit) {
        val key = DrKey(ui.slot.channel, ui.slot.slot)
        if (ui.managed != null && key !in state.acknowledged) dialog = DrDialog.Warning(ui, action) else action()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SetupColumnHeaderRow {
            SetupHeaderCell(stringResource(R.string.col_ch), Modifier.width(ChWidth), Alignment.CenterStart)
            for (i in 1..DR_SLOTS) {
                SetupHeaderCell(stringResource(R.string.dualrate_col_slot, i), Modifier.width(SlotWidth + SetupDims.Gap))
            }
        }
        if (!state.ready) {
            SetupNotReady()
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 3.dp)) {
                item(key = "hint") { HintRow() }
                items(state.rows, key = { it.channel }) { row ->
                    DualRateRow(
                        row = row,
                        config = state.config,
                        live = live,
                        onEditCondition = { ui -> guarded(ui) { dialog = DrDialog.Condition(ui) } },
                        onWeight = { ui, weight -> guarded(ui) { onSetWeight(ui.slot.channel, ui.slot.slot, weight) } },
                        onDisable = { ui -> guarded(ui) { onDisable(ui.slot.channel, ui.slot.slot) } },
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is DrDialog.Condition -> SwitchConditionDialog(
            title = stringResource(R.string.dualrate_condition_title, channelLabel(d.ui.slot.channel), d.ui.slot.slot + 1),
            initial = if (d.ui.slot.isEnabled) d.ui.slot.switch else d.ui.slot.switch.copy(source = SourceId.SA),
            config = state.config,
            allowOff = false,
            onConfirm = {
                onSetSwitch(d.ui.slot.channel, d.ui.slot.slot, it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is DrDialog.Warning -> ConfirmDialog(
            title = stringResource(R.string.managed_edit_title),
            message = stringResource(R.string.dualrate_managed_message),
            confirmLabel = stringResource(R.string.managed_edit_confirm),
            destructive = true,
            onConfirm = {
                onAcknowledge(DrKey(d.ui.slot.channel, d.ui.slot.slot))
                dialog = null
                d.proceed()
            },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun HintRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SetupDims.Edge + SetupDims.Inner, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AxIcon(icon = AxIcons.Info, contentDescription = null, tint = AxTheme.colors.accent, size = 18.dp)
        Text(
            text = stringResource(R.string.dualrate_hint),
            style = AxTheme.typography.label,
            color = AxTheme.colors.textMuted,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun DualRateRow(
    row: DualRateRowUi,
    config: WorkingConfig,
    live: LiveFeed,
    onEditCondition: (DualRateSlotUi) -> Unit,
    onWeight: (DualRateSlotUi, Int) -> Unit,
    onDisable: (DualRateSlotUi) -> Unit,
) {
    val colors = AxTheme.colors
    val slots = row.slots.map { it.slot }
    val anyManaged = row.slots.firstOrNull { it.managed != null }
    SetupRowCard {
        Column {
            if (anyManaged?.managed != null) {
                Row(modifier = Modifier.padding(start = SetupDims.Inner, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ManagedBadge(anyManaged.managed)
                    if (row.slots.any { it.modified }) {
                        Text(
                            text = stringResource(R.string.managed_modified),
                            style = AxTheme.typography.labelSmall,
                            color = colors.warning,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .height(SetupDims.Row)
                    .padding(horizontal = SetupDims.Inner),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = channelLabel(row.channel),
                    style = AxTheme.typography.bodyStrong,
                    color = if (slots.any { it.isEnabled }) colors.text else colors.textMuted,
                    modifier = Modifier.width(ChWidth),
                )
                row.slots.forEach { ui ->
                    SlotCell(ui, slots, config, live, onEditCondition, onWeight, onDisable)
                    Spacer(Modifier.width(SetupDims.Gap))
                }
            }
        }
    }
}

@Composable
private fun SlotCell(
    ui: DualRateSlotUi,
    slots: List<DualRateSlot>,
    config: WorkingConfig,
    live: LiveFeed,
    onEditCondition: (DualRateSlotUi) -> Unit,
    onWeight: (DualRateSlotUi, Int) -> Unit,
    onDisable: (DualRateSlotUi) -> Unit,
) {
    val colors = AxTheme.colors
    val slot = ui.slot
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Live marker: this is the slot that is in effect right now.
        Box(
            modifier = Modifier
                .size(DotWidth, SetupDims.Field)
                .drawBehind {
                    val active = activeDualRateSlot(slots) { live.value.logicAt(it) } == slot.slot
                    val r = 4.dp.toPx()
                    drawCircle(
                        color = if (active) colors.teal else Color.Transparent,
                        radius = r,
                        center = Offset(size.width / 2f, size.height / 2f),
                    )
                },
        )
        ConditionBox(
            condition = slot.switch,
            config = config,
            onClick = { onEditCondition(ui) },
            modifier = Modifier.width(CondWidth),
        )
        Spacer(Modifier.width(SmallGap))
        NumberField(
            value = slot.weight,
            onValueChange = { onWeight(ui, it) },
            min = PERCENT_MIN,
            max = PERCENT_MAX,
            unit = "%",
            enabled = slot.isEnabled,
            title = stringResource(R.string.dualrate_weight_title, slot.slot + 1),
            defaultValue = PERCENT_MAX,
            height = SetupDims.Field,
            modifier = Modifier.width(WeightWidth),
        )
        Spacer(Modifier.width(SmallGap))
        if (slot.isEnabled) {
            AxIconButton(
                icon = AxIcons.Close,
                contentDescription = stringResource(R.string.dualrate_disable),
                onClick = { onDisable(ui) },
                modifier = Modifier.size(ClearWidth),
            )
        } else {
            Spacer(Modifier.size(ClearWidth))
        }
    }
}
