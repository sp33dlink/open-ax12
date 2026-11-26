package org.openax12.feature.modelsetup

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.AxThemeMode
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurveType
import org.openax12.core.model.MixKey
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TimerVoiceOptions
import org.openax12.core.model.CountdownStyle
import org.openax12.feature.modelsetup.chlock.ChLockScreen
import org.openax12.feature.modelsetup.chlock.ChLockState
import org.openax12.feature.modelsetup.chlock.ThrottleCutDialog
import org.openax12.feature.modelsetup.chrome.ChromeState
import org.openax12.feature.modelsetup.common.SwitchConditionDialog
import org.openax12.feature.modelsetup.curves.CurveEditorScreen
import org.openax12.feature.modelsetup.curves.CurveEditorState
import org.openax12.feature.modelsetup.curves.CurvesScreen
import org.openax12.feature.modelsetup.curves.CurvesState
import org.openax12.feature.modelsetup.curves.curveRows
import org.openax12.feature.modelsetup.dualrate.DualRateScreen
import org.openax12.feature.modelsetup.dualrate.DualRateState
import org.openax12.feature.modelsetup.dualrate.dualRateRows
import org.openax12.feature.modelsetup.inputs.InputCheckScreen
import org.openax12.feature.modelsetup.inputs.InputCheckState
import org.openax12.feature.modelsetup.inputs.inputRows
import org.openax12.feature.modelsetup.logic.ManagedKind
import org.openax12.feature.modelsetup.logic.curveUsage
import org.openax12.feature.modelsetup.logic.findThrottleChannel
import org.openax12.feature.modelsetup.mixes.MixDetailsDialog
import org.openax12.feature.modelsetup.mixes.MixesScreen
import org.openax12.feature.modelsetup.mixes.MixesState
import org.openax12.feature.modelsetup.mixes.mixChannels
import org.openax12.feature.modelsetup.output.OutputScreen
import org.openax12.feature.modelsetup.output.OutputState
import org.openax12.feature.modelsetup.timers.TimerRef
import org.openax12.feature.modelsetup.timers.TimersScreen
import org.openax12.feature.modelsetup.timers.TimersState

// Screenshot tests of every tab and the curve editor (CLAUDE.md > Visual checks).

private val Config = sampleConfig()
private val Extras = sampleExtras()

@PreviewTest
@Ax12Preview
@Composable
fun InputCheckScreenshot() {
    SetupFrame("inputs") {
        InputCheckScreen(InputCheckState(true, inputRows(Config)), rememberLive(Config), { _, _ -> }, {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun OutputScreenshot() {
    SetupFrame("output") {
        OutputScreen(OutputState(true, Config.channels, Config), rememberLive(Config), { _, _ -> }, { _, _ -> })
    }
}

internal fun mixesState(expanded: Int?, acknowledged: Set<MixKey> = emptySet()) = MixesState(
    ready = true,
    config = Config,
    channels = mixChannels(Config, Extras),
    expanded = expanded,
    acknowledged = acknowledged,
)

@PreviewTest
@Ax12Preview
@Composable
fun MixesCollapsedScreenshot() {
    SetupFrame("mixes") {
        MixesScreen(mixesState(null), rememberLive(Config), {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun MixesExpandedScreenshot() {
    SetupFrame("mixes") {
        MixesScreen(mixesState(1), rememberLive(Config), {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun MixesManagedExpandedScreenshot() {
    SetupFrame("mixes") {
        MixesScreen(mixesState(4), rememberLive(Config), {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun MixDetailsDialogScreenshot() {
    SetupFrame("mixes") {
        MixesScreen(mixesState(4), rememberLive(Config), {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
        MixDetailsDialog(line = Config.mixes[5], onConfirm = {}, onDismiss = {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun SwitchConditionDialogScreenshot() {
    SetupFrame("mixes") {
        MixesScreen(mixesState(4), rememberLive(Config), {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
        SwitchConditionDialog(
            title = stringResource(R.string.mixes_pick_switch),
            initial = SwitchCondition.at(SourceId.SB, SwitchPosition.MID),
            config = Config,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun DualRateScreenshot() {
    SetupFrame("dualrate") {
        DualRateScreen(
            DualRateState(true, Config, dualRateRows(Config, Extras)),
            rememberLive(Config),
            { _, _, _ -> }, { _, _, _ -> }, { _, _ -> }, {},
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun ChLockScreenshot() {
    SetupFrame("chlock") {
        ChLockScreen(
            ChLockState(true, Config, Config.channels, findThrottleChannel(Config)),
            rememberLive(Config),
            { _, _ -> }, { _, _ -> }, {}, { _, _, _ -> },
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun ThrottleCutDialogScreenshot() {
    SetupFrame("chlock") {
        val state = ChLockState(true, Config, Config.channels, findThrottleChannel(Config))
        ChLockScreen(state, rememberLive(Config), { _, _ -> }, { _, _ -> }, {}, { _, _, _ -> })
        ThrottleCutDialog(state = state, onApply = { _, _, _ -> }, onDismiss = {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun CurvesScreenshot() {
    SetupFrame("curves") {
        CurvesScreen(CurvesState(true, Config, curveRows(Config, Extras)), {})
    }
}

private fun editorState(index: Int, curve: CurveConfig = Config.curve(index)!!, locked: Boolean = false, point: Int = 2) = CurveEditorState(
    ready = true,
    index = index,
    config = Config,
    curve = curve,
    selectedPoint = point,
    managed = Extras.managed.generatorOfCurve(index).let { ManagedKind.of(it) },
    locked = locked,
    uses = curveUsage(Config, index),
    markerSource = if (index == 1) SourceId.pot(1) else SourceId.ELE,
)

@Composable
private fun EditorFrame(state: CurveEditorState, firmwareY: Double? = null) {
    val live = rememberLive(Config)
    SetupFrameCurve(state.index) {
        CurveEditorScreen(
            state = state, live = live, firmwareY = firmwareY,
            onAcknowledge = {}, onSetType = {}, onDragParameter = {}, onSetParameter = {}, onSelect = {},
            onDragPoint = { _, _, _ -> }, onFinishDrag = {}, onSetPointX = { _, _ -> }, onSetPointY = { _, _ -> },
            onAddPoint = {}, onRemovePoint = {}, onStraighten = {}, onMarkerSource = {},
        )
    }
}

@Composable
private fun SetupFrameCurve(index: Int, content: @Composable () -> Unit) {
    SetupFrame(selected = "curve", state = ChromeState(dirty = true), content = content, curveTitle = "Curve ${index + 1}")
}

@PreviewTest
@Ax12Preview
@Composable
fun CurveEditorCustomScreenshot() {
    EditorFrame(editorState(1, locked = false), firmwareY = 12.0)
}

@PreviewTest
@Ax12Preview
@Composable
fun CurveEditorManagedLockedScreenshot() {
    EditorFrame(editorState(1, locked = true))
}

@PreviewTest
@Ax12Preview
@Composable
fun CurveEditorExpoScreenshot() {
    EditorFrame(editorState(0))
}

@PreviewTest
@Ax12Preview
@Composable
fun CurveEditorFreeScreenshot() {
    EditorFrame(editorState(7, curve = CurveConfig.empty(7)))
}

@PreviewTest
@Ax12Preview
@Composable
fun TimersTimerScreenshot() {
    SetupFrame("timers") {
        TimersScreen(timersState(TimerRef(0)), rememberLive(Config), {}, { _, _ -> }, { _, _ -> }, {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun TimersCounterScreenshot() {
    SetupFrame("timers") {
        TimersScreen(timersState(TimerRef(0, isCounter = true)), rememberLive(Config), {}, { _, _ -> }, { _, _ -> }, {})
    }
}

private fun timersState(selected: TimerRef) = TimersState(
    ready = true,
    config = Config,
    timers = Config.timers,
    counters = Config.counters,
    voice = List(TIMER_COUNT) { TimerVoiceOptions(it, minuteCall = it == 0, countdown = if (it == 0) CountdownStyle.VOICE else CountdownStyle.NONE) },
    selected = selected,
)

@PreviewTest
@Ax12Preview
@Composable
fun NotReadyScreenshot() {
    SetupFrame("mixes", state = ChromeState(dirty = false, deviceInSync = false)) {
        MixesScreen(MixesState(), rememberLive(Config), {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun MixesExpandedDarkScreenshot() {
    SetupFrame("mixes", mode = AxThemeMode.DARK) {
        MixesScreen(mixesState(4), rememberLive(Config), {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun OutputHighContrastScreenshot() {
    SetupFrame("output", mode = AxThemeMode.HIGH_CONTRAST) {
        OutputScreen(OutputState(true, Config.channels, Config), rememberLive(Config), { _, _ -> }, { _, _ -> })
    }
}
