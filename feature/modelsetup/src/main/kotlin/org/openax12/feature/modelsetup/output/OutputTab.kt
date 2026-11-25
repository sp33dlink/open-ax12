package org.openax12.feature.modelsetup.output

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.openax12.core.designsystem.AxLiveText
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.ChannelBar
import org.openax12.core.designsystem.ChannelBarStyle
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.SetupHeaderCell
import org.openax12.core.designsystem.formatPercent
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.CURVE_COUNT
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.WorkingConfig
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.common.CurvePickerDialog
import org.openax12.feature.modelsetup.common.CollectMessages
import org.openax12.feature.modelsetup.common.LiveFeed
import org.openax12.feature.modelsetup.common.SetupColumnHeaderRow
import org.openax12.feature.modelsetup.common.SetupDims
import org.openax12.feature.modelsetup.common.SetupNotReady
import org.openax12.feature.modelsetup.common.SetupRowCard
import org.openax12.feature.modelsetup.common.SetupViewModel
import org.openax12.feature.modelsetup.common.channelLabel
import org.openax12.feature.modelsetup.common.collectLive
import org.openax12.feature.modelsetup.common.curveLabel
import org.openax12.feature.modelsetup.common.setupViewModel

/** State of the Output tab. [config] is needed by the curve picker. */
internal data class OutputState(
    val ready: Boolean = false,
    val channels: List<OutputChannelConfig> = emptyList(),
    val config: WorkingConfig = WorkingConfig.EMPTY,
)

/** View model of the Output tab: limits, middle, curve, weight and reverse of every channel. */
internal class OutputViewModel(
    session: ConfigSession,
    scopeOverride: CoroutineScope? = null,
) : SetupViewModel(session, scopeOverride) {

    /** The 16 channels as configured. */
    val state: StateFlow<OutputState> = session.workingConfig
        .map { OutputState(ready = !it.isEmpty, channels = it.channels, config = it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), OutputState())

    /** Changes channel [channel] with [transform] (read-modify-write on a fresh read). */
    fun update(channel: Int, transform: (OutputChannelConfig) -> OutputChannelConfig) {
        if (channel !in 0 until CHANNEL_COUNT) return
        edit { radio ->
            val current = radio.getOutputChannels().firstOrNull { it.channel == channel } ?: return@edit false
            radio.setOutputChannel(sanitize(transform(current)))
        }
    }

    /** Sets the output curve; [curve] is 0..17 or [NO_CURVE]. */
    fun setCurve(channel: Int, curve: Int) {
        if (curve != NO_CURVE && curve !in 0 until CURVE_COUNT) return
        update(channel) { it.copy(curve = curve) }
    }
}

/** Keeps the limits ordered and every value inside the ±100 % the UI offers. */
internal fun sanitize(channel: OutputChannelConfig): OutputChannelConfig {
    val min = channel.min.coerceIn(PERCENT_MIN, PERCENT_MAX)
    val max = channel.max.coerceIn(min, PERCENT_MAX)
    return channel.copy(
        min = min,
        max = max,
        middle = channel.middle.coerceIn(PERCENT_MIN, PERCENT_MAX),
        weight = channel.weight.coerceIn(PERCENT_MIN, PERCENT_MAX),
    )
}

/** The Output tab. */
@Composable
internal fun OutputTab() {
    val graph = LocalAppGraph.current
    val vm = setupViewModel("modelsetup.output") { OutputViewModel(graph.configSession) }
    val state by vm.state.collectAsStateWithLifecycle()
    val live = graph.radio.live.collectLive()
    CollectMessages(vm.messages)
    OutputScreen(state = state, live = live, onUpdate = vm::update, onSetCurve = vm::setCurve)
}

private val ChWidth = 52.dp
private val NumWidth = 84.dp
private val CurveWidth = 72.dp
private val RevWidth = 58.dp
private val ValueWidth = 54.dp

/** Stateless Output screen. */
@Composable
internal fun OutputScreen(
    state: OutputState,
    live: LiveFeed,
    onUpdate: (Int, (OutputChannelConfig) -> OutputChannelConfig) -> Unit,
    onSetCurve: (Int, Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SetupColumnHeaderRow {
            SetupHeaderCell(stringResource(R.string.col_ch), Modifier.width(ChWidth), Alignment.CenterStart)
            SetupHeaderCell(stringResource(R.string.output_col_min), Modifier.width(NumWidth))
            SetupHeaderCell(stringResource(R.string.output_col_middle), Modifier.width(NumWidth))
            SetupHeaderCell(stringResource(R.string.output_col_max), Modifier.width(NumWidth))
            SetupHeaderCell(stringResource(R.string.output_col_curve), Modifier.width(CurveWidth))
            SetupHeaderCell(stringResource(R.string.output_col_weight), Modifier.width(NumWidth))
            SetupHeaderCell(stringResource(R.string.output_col_reverse), Modifier.width(RevWidth))
            SetupHeaderCell(stringResource(R.string.output_col_live), Modifier.weight(1f))
        }
        if (!state.ready) {
            SetupNotReady()
        } else {
            var curvePicker by remember { mutableStateOf<OutputChannelConfig?>(null) }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 3.dp)) {
                items(state.channels, key = { it.channel }) { channel ->
                    OutputRow(channel, live, onUpdate, onPickCurve = { curvePicker = channel })
                }
            }
            curvePicker?.let { channel ->
                CurvePickerDialog(
                    config = state.config,
                    title = stringResource(R.string.output_curve_title, channelLabel(channel.channel)),
                    selected = channel.curve,
                    onPick = {
                        onSetCurve(channel.channel, it)
                        curvePicker = null
                    },
                    onDismiss = { curvePicker = null },
                )
            }
        }
    }
}

@Composable
private fun OutputRow(
    channel: OutputChannelConfig,
    live: LiveFeed,
    onUpdate: (Int, (OutputChannelConfig) -> OutputChannelConfig) -> Unit,
    onPickCurve: () -> Unit,
) {
    val colors = AxTheme.colors
    val index = channel.channel
    SetupRowCard {
        Row(
            modifier = Modifier
                .height(SetupDims.Row)
                .padding(horizontal = SetupDims.Inner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Text(
                text = channelLabel(index),
                style = AxTheme.typography.bodyStrong,
                color = if (channel.mixCount > 0) colors.text else colors.textMuted,
                modifier = Modifier.width(ChWidth),
            )
            NumberCell(
                value = channel.min,
                onChange = { v -> onUpdate(index) { it.copy(min = v) } },
                min = PERCENT_MIN,
                max = channel.max,
                title = stringResource(R.string.output_min_title, channelLabel(index)),
                defaultValue = PERCENT_MIN,
            )
            NumberCell(
                value = channel.middle,
                onChange = { v -> onUpdate(index) { it.copy(middle = v) } },
                min = PERCENT_MIN,
                max = PERCENT_MAX,
                title = stringResource(R.string.output_middle_title, channelLabel(index)),
                defaultValue = 0,
            )
            NumberCell(
                value = channel.max,
                onChange = { v -> onUpdate(index) { it.copy(max = v) } },
                min = channel.min,
                max = PERCENT_MAX,
                title = stringResource(R.string.output_max_title, channelLabel(index)),
                defaultValue = PERCENT_MAX,
            )
            AxValueBox(
                text = curveLabel(channel.curve),
                onClick = onPickCurve,
                height = SetupDims.Field,
                modifier = Modifier.width(CurveWidth - SetupDims.Gap),
            )
            Spacer6()
            NumberCell(
                value = kotlin.math.abs(channel.weight),
                onChange = { v ->
                    onUpdate(index) { it.copy(weight = if (it.weight < 0) -v else v) }
                },
                min = 0,
                max = PERCENT_MAX,
                title = stringResource(R.string.output_weight_title, channelLabel(index)),
                defaultValue = PERCENT_MAX,
            )
            Row(modifier = Modifier.width(RevWidth), horizontalArrangement = Arrangement.Center) {
                AxSwitch(
                    checked = channel.weight < 0,
                    onCheckedChange = { reversed ->
                        onUpdate(index) {
                            val magnitude = kotlin.math.abs(it.weight)
                            it.copy(weight = if (reversed) -magnitude else magnitude)
                        }
                    },
                )
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
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun Spacer6() {
    androidx.compose.foundation.layout.Spacer(Modifier.width(SetupDims.Gap))
}

@Composable
private fun NumberCell(
    value: Int,
    onChange: (Int) -> Unit,
    min: Int,
    max: Int,
    title: String,
    defaultValue: Int,
) {
    NumberField(
        value = value,
        onValueChange = onChange,
        min = min,
        max = max,
        unit = "%",
        title = title,
        defaultValue = defaultValue,
        height = SetupDims.Field,
        modifier = Modifier.width(NumWidth - SetupDims.Gap),
    )
    Spacer6()
}
