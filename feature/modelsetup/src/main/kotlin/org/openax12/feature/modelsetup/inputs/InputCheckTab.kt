package org.openax12.feature.modelsetup.inputs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxLiveText
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.ChannelBar
import org.openax12.core.designsystem.ChannelBarStyle
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.SetupHeaderCell
import org.openax12.core.designsystem.formatPercent
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.WorkingConfig
import org.openax12.core.radio.Radio
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.common.CollectMessages
import org.openax12.feature.modelsetup.common.LiveFeed
import org.openax12.feature.modelsetup.common.SetupColumnHeaderRow
import org.openax12.feature.modelsetup.common.SetupDims
import org.openax12.feature.modelsetup.common.SetupNotReady
import org.openax12.feature.modelsetup.common.SetupRowCard
import org.openax12.feature.modelsetup.common.SetupViewModel
import org.openax12.feature.modelsetup.common.collectLive
import org.openax12.feature.modelsetup.common.setupViewModel
import org.openax12.feature.modelsetup.common.sourceKindRes

/**
 * One row of the Input check tab.
 *
 * @property source the source as configured (name, type, enabled)
 * @property preset the pre-flight preset in percent, null = "do not check"
 * @property hasPreFlight false for sources without a preset (counters)
 */
internal data class InputRowUi(val source: SourceInfo, val preset: Int?, val hasPreFlight: Boolean)

/** State of the Input check tab. */
internal data class InputCheckState(val ready: Boolean = false, val rows: List<InputRowUi> = emptyList())

/** Builds the rows of [config]: every enabled source with its preset. */
internal fun inputRows(config: WorkingConfig): List<InputRowUi> =
    config.sources.filter { it.enabled }.map { info ->
        InputRowUi(info, config.system?.preFlightPreset(info.id), info.id.hasPreFlightCheck)
    }

/** The preset a source gets when its check is switched on: throttle low, switches up, the rest centred. */
internal fun defaultPreset(source: SourceId): Int = when {
    source == SourceId.THR -> PERCENT_MIN
    source.isSwitch -> SwitchPosition.UP.logic
    else -> 0
}

/** [logic] as a preset: switches snap to their nearest position, everything else is clamped to ±100. */
internal fun presetFromLive(source: SourceId, logic: Int): Int =
    if (source.isSwitch) SwitchPosition.fromLogic(logic).logic else logic.coerceIn(PERCENT_MIN, PERCENT_MAX)

/** View model of the Input check tab. Presets live in the system config and are edited like any other setup value. */
internal class InputCheckViewModel(
    session: ConfigSession,
    private val radio: Radio,
    scopeOverride: CoroutineScope? = null,
) : SetupViewModel(session, scopeOverride) {

    /** Rows for the screen. */
    val state: StateFlow<InputCheckState> = session.workingConfig
        .map { InputCheckState(ready = !it.isEmpty, rows = inputRows(it)) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), InputCheckState())

    /** Sets the pre-flight preset of [source]; null = do not check. */
    fun setPreset(source: SourceId, preset: Int?) {
        if (!source.hasPreFlightCheck) return
        edit { r ->
            val system = r.getSystemConfig() ?: return@edit false
            r.setSystemConfig(system.withPreFlightPreset(source, preset))
        }
    }

    /** Uses the current live position of [source] as its preset. */
    fun useCurrent(source: SourceId) {
        setPreset(source, presetFromLive(source, radio.live.value.logic(source)))
    }
}

/** The Input check tab. */
@Composable
internal fun InputCheckTab() {
    val graph = LocalAppGraph.current
    val vm = setupViewModel("modelsetup.inputs") { InputCheckViewModel(graph.configSession, graph.radio) }
    val state by vm.state.collectAsStateWithLifecycle()
    val live = graph.radio.live.collectLive()
    CollectMessages(vm.messages)
    InputCheckScreen(
        state = state,
        live = live,
        onSetPreset = vm::setPreset,
        onUseCurrent = vm::useCurrent,
    )
}

private val NameWidth = 118.dp
private val ValueWidth = 54.dp
private val RawWidth = 54.dp
private val PresetWidth = 262.dp

/** Stateless Input check screen. */
@Composable
internal fun InputCheckScreen(
    state: InputCheckState,
    live: LiveFeed,
    onSetPreset: (SourceId, Int?) -> Unit,
    onUseCurrent: (SourceId) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SetupColumnHeaderRow {
            SetupHeaderCell(stringResource(R.string.inputs_col_input), Modifier.width(NameWidth), Alignment.CenterStart)
            SetupHeaderCell(stringResource(R.string.inputs_col_live), Modifier.weight(1f))
            SetupHeaderCell(stringResource(R.string.inputs_col_value), Modifier.width(ValueWidth), Alignment.CenterEnd)
            SetupHeaderCell(stringResource(R.string.inputs_col_raw), Modifier.width(RawWidth), Alignment.CenterEnd)
            SetupHeaderCell(stringResource(R.string.inputs_col_preflight), Modifier.width(PresetWidth), Alignment.CenterEnd)
        }
        if (!state.ready) {
            SetupNotReady()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 3.dp),
            ) {
                items(state.rows, key = { it.source.index }) { row ->
                    InputRow(row, live, onSetPreset, onUseCurrent)
                }
            }
        }
    }
}

@Composable
private fun InputRow(
    row: InputRowUi,
    live: LiveFeed,
    onSetPreset: (SourceId, Int?) -> Unit,
    onUseCurrent: (SourceId) -> Unit,
) {
    val id = row.source.id
    val colors = AxTheme.colors
    SetupRowCard {
        Row(
            modifier = Modifier
                .height(SetupDims.Row)
                .padding(horizontal = SetupDims.Inner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(NameWidth)) {
                Text(
                    text = row.source.name.ifBlank { id.defaultName },
                    style = AxTheme.typography.bodyStrong,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(sourceKindRes(id.kind)),
                    style = AxTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            ChannelBar(
                value = { live.value.logic(id).toFloat() },
                style = ChannelBarStyle.Setup,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.width(ValueWidth), contentAlignment = Alignment.CenterEnd) {
                AxLiveText(
                    value = { live.value.logic(id) },
                    format = ::formatPercent,
                    template = "-100%",
                    style = AxTheme.typography.value,
                    color = colors.text,
                )
            }
            Box(modifier = Modifier.width(RawWidth), contentAlignment = Alignment.CenterEnd) {
                AxLiveText(
                    value = { live.value.raw(id) },
                    format = Int::toString,
                    template = "-1000",
                    style = AxTheme.typography.label,
                    color = colors.textMuted,
                )
            }
            Row(
                modifier = Modifier.width(PresetWidth),
                horizontalArrangement = Arrangement.spacedBy(SetupDims.Gap, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.hasPreFlight) {
                    PresetControls(row, onSetPreset, onUseCurrent)
                } else {
                    Text(
                        text = stringResource(R.string.inputs_no_preflight),
                        style = AxTheme.typography.label,
                        color = colors.textFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetControls(
    row: InputRowUi,
    onSetPreset: (SourceId, Int?) -> Unit,
    onUseCurrent: (SourceId) -> Unit,
) {
    val id = row.source.id
    val preset = row.preset
    if (preset != null) {
        if (id.isSwitch) {
            val positions = listOf(SwitchPosition.UP, SwitchPosition.MID, SwitchPosition.DOWN)
            AxSegmented(
                options = listOf(
                    stringResource(R.string.position_up),
                    stringResource(R.string.position_mid),
                    stringResource(R.string.position_down),
                ),
                selectedIndex = positions.indexOf(SwitchPosition.fromLogic(preset)),
                onSelected = { onSetPreset(id, positions[it].logic) },
                modifier = Modifier.width(150.dp),
            )
        } else {
            NumberField(
                value = preset,
                onValueChange = { onSetPreset(id, it) },
                min = PERCENT_MIN,
                max = PERCENT_MAX,
                unit = "%",
                title = stringResource(R.string.inputs_preset_title, row.source.name.ifBlank { id.defaultName }),
                defaultValue = defaultPreset(id),
                height = SetupDims.Field,
                modifier = Modifier.width(80.dp),
            )
            AxButton(
                text = stringResource(R.string.inputs_use_current),
                onClick = { onUseCurrent(id) },
                variant = AxButtonVariant.Raised,
                height = SetupDims.Field,
                contentPadding = PaddingValues(horizontal = 8.dp),
            )
        }
    } else {
        Text(
            text = stringResource(R.string.inputs_dont_check),
            style = AxTheme.typography.label,
            color = AxTheme.colors.textMuted,
            textAlign = TextAlign.End,
        )
    }
    Spacer(Modifier.width(2.dp))
    AxSwitch(
        checked = preset != null,
        onCheckedChange = { on -> onSetPreset(id, if (on) defaultPreset(id) else null) },
    )
}
