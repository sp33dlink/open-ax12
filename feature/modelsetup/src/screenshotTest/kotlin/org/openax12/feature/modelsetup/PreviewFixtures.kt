package org.openax12.feature.modelsetup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import org.openax12.core.designsystem.AxBottomBar
import org.openax12.core.designsystem.AxDestination
import org.openax12.core.designsystem.AxScreen
import org.openax12.core.designsystem.AxStatusBar
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxThemeMode
import org.openax12.core.designsystem.FixedCanvas
import org.openax12.core.designsystem.SetupTabItem
import org.openax12.core.engine.MixerMath
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurvePoint
import org.openax12.core.model.CurveType
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.ManagedCurve
import org.openax12.core.model.ManagedDualRate
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.ManagedMix
import org.openax12.core.model.MixLine
import org.openax12.core.model.MixMode
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.SOURCE_COUNT
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.TimerAlertMode
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.TimerState
import org.openax12.core.model.WorkingConfig
import org.openax12.feature.modelsetup.chrome.ChromeState
import org.openax12.feature.modelsetup.chrome.ModelSetupScreen
import org.openax12.feature.modelsetup.common.LiveFeed

/** Inputs of the sample model: the stick positions and switch positions of the first frame. */
internal val SampleLogic: Map<SourceId, Int> = mapOf(
    SourceId.AIL to 35,
    SourceId.ELE to -20,
    SourceId.THR to -62,
    SourceId.RUD to 0,
    SourceId.pot(1) to 40,
    SourceId.slider(1) to -30,
    SourceId.SA to SwitchPosition.UP.logic,
    SourceId.SB to SwitchPosition.MID.logic,
    SourceId.SC to SwitchPosition.DOWN.logic,
    SourceId.SF to SwitchPosition.DOWN.logic,
)

private fun source(index: Int): SourceInfo {
    val id = SourceId(index)
    val enabled = index in 0..5 || index in 12..13 || index in 20..27 || index in 36..37
    return SourceInfo(index = index, name = id.defaultName, enabled = enabled, logic = SampleLogic[id] ?: 0, raw = (SampleLogic[id] ?: 0) * 10)
}

private fun mix(channel: Int, index: Int, source: SourceId, count: Int, block: MixLine.() -> MixLine = { this }) =
    MixLine(channel = channel, mixIndex = index, source = source, sourceName = source.defaultName, mixCount = count).block()

/** A plausible glider-like model: ailerons, elevator with expo, throttle with cut, rudder, a flap channel. */
internal fun sampleConfig(): WorkingConfig {
    val mixes = listOf(
        mix(0, 0, SourceId.AIL, 1),
        mix(1, 0, SourceId.ELE, 1) { copy(curve = 0) },
        mix(2, 0, SourceId.THR, 1),
        mix(3, 0, SourceId.RUD, 1) { copy(weightUp = 80, weightDown = 80) },
        mix(4, 0, SourceId.SA, 2) { copy(mode = MixMode.ADD, slowUpMs = 500, slowDownMs = 500) },
        mix(4, 1, SourceId.pot(1), 2) { copy(weightUp = 60, weightDown = 40, offset = 5, curve = 1, switch = SwitchCondition.at(SourceId.SB, SwitchPosition.MID)) },
        mix(5, 0, SourceId.slider(1), 1) { copy(mode = MixMode.REPLACE) },
    )
    val channels = (0 until CHANNEL_COUNT).map { ch ->
        OutputChannelConfig(
            channel = ch,
            mixCount = mixes.count { it.channel == ch },
            min = if (ch == 1) -90 else -100,
            max = if (ch == 1) 90 else 100,
            middle = if (ch == 3) 4 else 0,
            weight = if (ch == 1) -100 else 100,
            curve = if (ch == 5) 2 else NO_CURVE,
            lock = if (ch == 2) SwitchCondition.at(SourceId.SF, SwitchPosition.DOWN) else SwitchCondition.NONE,
            lockValue = if (ch == 2) -100 else 0,
        )
    }
    val curves = (0 until 18).map { CurveConfig.empty(it) }.toMutableList()
    curves[0] = CurveConfig.parametric(0, CurveType.EXPO, 35)
    curves[1] = CurveConfig.custom(1, listOf(CurvePoint(-100, -100), CurvePoint(-40, -70), CurvePoint(0, 0), CurvePoint(50, 80), CurvePoint(100, 100)))
    curves[2] = CurveConfig.parametric(2, CurveType.DIFF, 30)
    curves[3] = CurveConfig.parametric(3, CurveType.FUNC, 2)
    val dualRates = (0 until CHANNEL_COUNT).flatMap { ch ->
        (0 until 3).map { slot ->
            when {
                ch == 0 && slot == 0 -> DualRateSlot(ch, slot, 2, SwitchCondition.at(SourceId.SB, SwitchPosition.UP), 100)
                ch == 0 && slot == 1 -> DualRateSlot(ch, slot, 2, SwitchCondition.at(SourceId.SB, SwitchPosition.DOWN), 60)
                ch == 1 && slot == 0 -> DualRateSlot(ch, slot, 1, SwitchCondition.at(SourceId.SC, SwitchPosition.DOWN), 70)
                else -> DualRateSlot(ch, slot)
            }
        }
    }
    val timers = listOf(
        TimerConfig(index = 0, switch = SwitchCondition(SourceId.THR, CompareOp.GREATER, -90), startTime = 600, countUp = false, alertMode = TimerAlertMode.VOICE, alertIntervalSec = 30, resetSwitch = SwitchCondition.at(SourceId.SD, SwitchPosition.DOWN)),
        TimerConfig(index = 1, switch = SwitchCondition.at(SourceId.SE, SwitchPosition.DOWN)),
        TimerConfig(index = 2),
    )
    val counters = listOf(
        TimerConfig(index = 0, switch = SwitchCondition.at(SourceId.SH, SwitchPosition.DOWN), startTime = 99),
        TimerConfig(index = 1, startTime = 1),
        TimerConfig(index = 2, startTime = 1),
    )
    return WorkingConfig(
        revision = 1,
        sources = (0 until SOURCE_COUNT).map(::source),
        channels = channels,
        mixes = mixes,
        curves = curves,
        dualRates = dualRates,
        timers = timers,
        counters = counters,
        system = SystemConfig().withPreFlightPreset(SourceId.THR, -100).withPreFlightPreset(SourceId.SF, SwitchPosition.UP.logic),
    )
}

/** Managed bookkeeping of the sample: the flap channel's pot line, curve 2 and a dual rate came from generators. */
internal fun sampleExtras(): ModelExtras = ModelExtras(
    managed = ManagedItems(
        mixes = listOf(ManagedMix(4, SourceId.pot(1).index, ManagedItems.GENERATOR_WING)),
        curves = listOf(ManagedCurve(1, ManagedItems.GENERATOR_FLIGHT_MODES)),
        dualRates = listOf(ManagedDualRate(0, 1, ManagedItems.GENERATOR_FLIGHT_MODES)),
    ),
)

/** The first live frame: inputs from [SampleLogic], outputs from the mixer model, a healthy link. */
internal fun sampleLive(config: WorkingConfig = sampleConfig()): LiveSnapshot {
    val logic = IntArray(SOURCE_COUNT) { SampleLogic[SourceId(it)] ?: 0 }
    val outputs = MixerMath.computeOutputs(config, logic).map { it.toInt() }
    return LiveSnapshot.of(
        logic = SampleLogic,
        channels = outputs,
        status = RadioStatus(linkQuality = 92, txVoltage = 7.9f, rxVoltage = 8.2f),
        timers = listOf(TimerState(0, false, 412, true, 600, true), TimerState(1), TimerState(2)),
        counters = listOf(TimerState(0, true, 17, true, 99), TimerState(1, true), TimerState(2, true)),
    )
}

@Composable
internal fun rememberLive(config: WorkingConfig = sampleConfig()): LiveFeed = remember { mutableStateOf(sampleLive(config)) }

/** Status bar, the content and the bottom bar of the app shell around a setup screen, on the fixed canvas. */
@Composable
internal fun ShellFrame(mode: AxThemeMode = AxThemeMode.LIGHT, content: @Composable () -> Unit) {
    AxTheme(mode) {
        FixedCanvas {
            AxScreen(
                statusBar = { AxStatusBar(title = "Alula 2", linkQuality = { 92 }, rxVoltage = { 8.2f }, txVoltage = { 7.9f }, dateTime = "Nov 19  12:30") },
                bottomBar = { AxBottomBar(selected = AxDestination.MODEL_SETUP, onSelect = {}) },
            ) {
                content()
            }
        }
    }
}

/** The model-setup chrome around [content] with [selected] as the active tab. */
@Composable
internal fun SetupFrame(
    selected: String,
    state: ChromeState = ChromeState(dirty = true),
    mode: AxThemeMode = AxThemeMode.LIGHT,
    curveTitle: String? = null,
    content: @Composable () -> Unit,
) {
    val tabs = if (curveTitle != null) listOf(SetupTabItem("curve", curveTitle)) else listOf(
        SetupTabItem("inputs", stringResource(R.string.tab_inputs)),
        SetupTabItem("output", stringResource(R.string.tab_output)),
        SetupTabItem("mixes", stringResource(R.string.tab_mixes)),
        SetupTabItem("dualrate", stringResource(R.string.tab_dualrate)),
        SetupTabItem("chlock", stringResource(R.string.tab_chlock)),
        SetupTabItem("curves", stringResource(R.string.tab_curves)),
        SetupTabItem("timers", stringResource(R.string.tab_timers)),
        SetupTabItem("flightmodes", "Flight modes"),
        SetupTabItem("wing", "Flaps / Wing"),
    )
    ShellFrame(mode) {
        ModelSetupScreen(
            state = state,
            tabs = tabs,
            selectedTabId = selected,
            onTabSelected = {},
            onBack = {},
            onSave = {},
            onConfirmSave = {},
            onDismissConfirm = {},
            content = content,
        )
    }
}
