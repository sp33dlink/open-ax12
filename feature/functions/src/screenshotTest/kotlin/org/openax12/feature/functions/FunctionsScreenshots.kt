package org.openax12.feature.functions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.audio.TtsStatus
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.Ax12Theme
import org.openax12.core.designsystem.AxBottomBar
import org.openax12.core.designsystem.AxDestination
import org.openax12.core.designsystem.AxScreen
import org.openax12.core.designsystem.AxStatusBar
import org.openax12.core.designsystem.AxThemeMode
import org.openax12.core.designsystem.FixedCanvas
import org.openax12.core.designsystem.SetupScaffold
import org.openax12.core.designsystem.SetupTabItem
import org.openax12.core.engine.FlightModeCompiler
import org.openax12.core.engine.WingCompiler
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.CompareOp
import org.openax12.core.model.FlapControl
import org.openax12.core.model.FlapConfig
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurveType
import org.openax12.core.model.LinkEvent
import org.openax12.core.model.LogicalFunction
import org.openax12.core.model.LogicalSwitch
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.MixLine
import org.openax12.core.model.ButterflyConfig
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.ModelType
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.PlanConflict
import org.openax12.core.model.PlanConflictKind
import org.openax12.core.model.PlanConflictSeverity
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.PlanOp
import org.openax12.core.model.Repeat
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.SpecialAction
import org.openax12.core.model.SpecialFunction
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TailType
import org.openax12.core.model.TimerEvent
import org.openax12.core.model.Trigger
import org.openax12.core.model.VarioConfig
import org.openax12.core.model.VoiceAlert
import org.openax12.core.model.WingChannels
import org.openax12.core.model.WingConfig
import org.openax12.core.model.WingType
import org.openax12.core.model.WorkingConfig
import org.openax12.core.model.Announcements
import org.openax12.core.model.BeepPattern
import org.openax12.core.navigation.SetupTabIds
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.PlanDiffDialog
import org.openax12.feature.functions.common.buildSensorOptions
import org.openax12.feature.functions.common.buildSourceOptions
import org.openax12.feature.functions.flightmodes.FlightModeEdits
import org.openax12.feature.functions.flightmodes.FlightModesScreen
import org.openax12.feature.functions.flightmodes.FlightModesState
import org.openax12.feature.functions.flightmodes.PresetNames
import org.openax12.feature.functions.logical.LogicalProblem
import org.openax12.feature.functions.logical.LogicalSwitchEditorScreen
import org.openax12.feature.functions.logical.LogicalSwitchEditorState
import org.openax12.feature.functions.logical.LogicalSwitchRules
import org.openax12.feature.functions.logical.LogicalSwitchesScreen
import org.openax12.feature.functions.logical.LogicalSwitchesState
import org.openax12.feature.functions.sounds.SoundsCallbacks
import org.openax12.feature.functions.sounds.SoundsScreen
import org.openax12.feature.functions.sounds.SoundsState
import org.openax12.feature.functions.sounds.VoiceAlertEditorScreen
import org.openax12.feature.functions.sounds.VoiceAlertEditorState
import org.openax12.feature.functions.special.SpecialFunctionEditorScreen
import org.openax12.feature.functions.special.SpecialFunctionEditorState
import org.openax12.feature.functions.special.SpecialFunctionsScreen
import org.openax12.feature.functions.special.SpecialFunctionsState
import org.openax12.feature.functions.wing.WingScreen
import org.openax12.feature.functions.wing.WingState

// Screenshots of every tab and editor for the visual checks (CLAUDE.md > Visual checks). Tabs are
// framed by the real setup chrome, editors by the real status and bottom bars.

private val SETUP_TABS = listOf(
    SetupTabItem(SetupTabIds.INPUT_CHECK, "Input check"),
    SetupTabItem(SetupTabIds.OUTPUT, "Output"),
    SetupTabItem(SetupTabIds.MIXES, "Mixes"),
    SetupTabItem(SetupTabIds.DUAL_RATE, "DualRate"),
    SetupTabItem(SetupTabIds.CH_LOCK, "ChLock"),
    SetupTabItem(SetupTabIds.CURVES, "Curves"),
    SetupTabItem(SetupTabIds.TIMERS, "Timers"),
    SetupTabItem(SetupTabIds.FLIGHT_MODES, "Flight modes"),
    SetupTabItem(SetupTabIds.WING, "Flaps / Wing"),
    SetupTabItem(SetupTabIds.LOGICAL_SWITCHES, "Logical switches"),
    SetupTabItem(SetupTabIds.SPECIAL_FUNCTIONS, "Special functions"),
    SetupTabItem(SetupTabIds.SOUNDS, "Sounds"),
)

private fun config(): WorkingConfig = WorkingConfig(
    sources = SourceId.all.map { SourceInfo(it.index) },
    channels = List(CHANNEL_COUNT) { OutputChannelConfig(it) },
)

private fun pickers(modes: List<String> = listOf("Normal", "Thermal", "Speed")): PickerData {
    val config = config()
    val sources = buildSourceOptions(config)
    val registry = listOf(
        SensorInfo(Sensors.BARO_ALTITUDE, "Alt", unit = SensorUnit.METER),
        SensorInfo(Sensors.VERTICAL_SPEED, "VSpd", unit = SensorUnit.METER_PER_SECOND),
        SensorInfo(Sensors.BATTERY_VOLTAGE, "RxBt", unit = SensorUnit.VOLT),
    )
    return PickerData(
        sources = sources,
        sensors = buildSensorOptions(registry, config, sources),
        flightModes = modes,
        soundFiles = listOf("landing.wav", "thermal.mp3"),
    )
}

@Composable
private fun TabFrame(tabId: String, dirty: Boolean = true, mode: AxThemeMode = AxThemeMode.LIGHT, content: @Composable () -> Unit) {
    Ax12Theme(mode) {
        FixedCanvas {
            AxScreen(
                statusBar = { AxStatusBar(title = "Skyhawk", linkQuality = { 98 }, rxVoltage = { 8.2f }, txVoltage = { 7.9f }, dateTime = "2026-11-19  10:42") },
                bottomBar = { AxBottomBar(selected = AxDestination.MODEL_SETUP, onSelect = {}) },
            ) {
                SetupScaffold(
                    tabs = SETUP_TABS,
                    selectedTabId = tabId,
                    onTabSelected = {},
                    onBack = {},
                    onSave = {},
                    dirty = dirty,
                    content = { content() },
                )
            }
        }
    }
}

@Composable
private fun EditorFrame(mode: AxThemeMode = AxThemeMode.LIGHT, content: @Composable () -> Unit) {
    Ax12Theme(mode) {
        FixedCanvas {
            AxScreen(
                statusBar = { AxStatusBar(title = "Skyhawk", linkQuality = { 98 }, rxVoltage = { 8.2f }, txVoltage = { 7.9f }, dateTime = "2026-11-19  10:42") },
                bottomBar = { AxBottomBar(selected = AxDestination.MODEL_SETUP, onSelect = {}) },
            ) { content() }
        }
    }
}

// -- Flight modes ---------------------------------------------------------------------------------

private fun gliderModes(): FlightModesConfig = FlightModesConfig(
    enabled = true,
    modeSwitch = SourceId.SC,
    modes = listOf(
        FlightModeConfig("Normal", SwitchPosition.UP),
        FlightModeConfig(
            "Thermal", SwitchPosition.MID,
            offsets = mapOf(0 to -8, 1 to 8, 4 to -8, 5 to 8, 2 to 4),
            rates = mapOf(0 to 80, 2 to 90),
            transitionMs = 1500,
        ),
        FlightModeConfig(
            "Speed", SwitchPosition.DOWN,
            offsets = mapOf(0 to 5, 1 to -5, 4 to 5, 5 to -5, 2 to -3),
            rates = mapOf(0 to 70, 2 to 100),
            transitionMs = 1500,
            announce = false,
        ),
    ),
)

private fun flightModesState(
    draft: FlightModesConfig = gliderModes(),
    applied: FlightModesConfig = draft,
    type: ModelType = ModelType.GLIDER,
) = FlightModesState(
    draft = draft,
    applied = applied,
    modelType = type,
    problems = FlightModeCompiler.validate(draft, config()),
    activeMode = 1,
    pickers = pickers(draft.modes.map { it.name }),
)

/** Flight modes, modes page, applied. */
@PreviewTest
@Ax12Preview
@Composable
fun FlightModesModesScreenshot() {
    TabFrame(SetupTabIds.FLIGHT_MODES, dirty = false) {
        FlightModesScreen(state = flightModesState(), onAction = {}, onOpenMixes = {})
    }
}

/** Flight modes, modes page with an unapplied change. */
@PreviewTest
@Ax12Preview
@Composable
fun FlightModesDirtyScreenshot() {
    val draft = gliderModes().let { it.copy(modes = it.modes.take(2)) }
    TabFrame(SetupTabIds.FLIGHT_MODES) {
        FlightModesScreen(state = flightModesState(draft = draft, applied = gliderModes()), onAction = {}, onOpenMixes = {})
    }
}

/** Flight modes, channel table. */
@PreviewTest
@Ax12Preview
@Composable
fun FlightModesChannelsScreenshot() {
    TabFrame(SetupTabIds.FLIGHT_MODES, dirty = false) {
        FlightModesScreen(state = flightModesState(), onAction = {}, onOpenMixes = {}, initialPage = 1)
    }
}

/** Flight modes switched off. */
@PreviewTest
@Ax12Preview
@Composable
fun FlightModesOffScreenshot() {
    TabFrame(SetupTabIds.FLIGHT_MODES, dirty = false) {
        FlightModesScreen(state = flightModesState(draft = FlightModesConfig()), onAction = {}, onOpenMixes = {})
    }
}

/** Multirotor preset: the mode channel on CH5, plus a validation problem (no switch chosen). */
@PreviewTest
@Ax12Preview
@Composable
fun FlightModesMultirotorScreenshot() {
    val names = PresetNames("Normal", "Thermal", "Speed", "Sport", "Landing", "Angle", "Horizon", "Acro", "Idle-up 1", "Idle-up 2", "Mode 1", "Mode 2", "Mode 3")
    val draft = FlightModeEdits.preset(ModelType.MULTIROTOR, null, names)
    TabFrame(SetupTabIds.FLIGHT_MODES) {
        FlightModesScreen(
            state = flightModesState(draft = draft, applied = FlightModesConfig(), type = ModelType.MULTIROTOR),
            onAction = {},
            onOpenMixes = {},
            initialPage = 1,
        )
    }
}

private fun sampleDiff(blocking: Boolean): PlanDiff {
    val mix = MixLine(channel = 0, source = SourceId.SC, offset = 0)
    val curve = CurveConfig(index = 3, type = CurveType.CUSTOM)
    return PlanDiff(
        generator = "flightModes",
        ops = listOf(PlanOp.AddMix(mix), PlanOp.AddMix(mix.copy(channel = 1)), PlanOp.AddMix(mix.copy(channel = 4)), PlanOp.SetCurve(curve, curve), PlanOp.RemoveMix(mix.copy(channel = 5))),
        conflicts = buildList {
            add(PlanConflict(PlanConflictKind.MANAGED_ITEM_MODIFIED, PlanConflictSeverity.WARNING, channel = 1, source = SourceId.SC))
            if (blocking) add(PlanConflict(PlanConflictKind.MIX_KEY_IN_USE, channel = 4, source = SourceId.SC))
        },
    )
}

/** The dry-run dialog with a warning. */
@PreviewTest
@Ax12Preview
@Composable
fun PlanDiffDialogScreenshot() {
    TabFrame(SetupTabIds.FLIGHT_MODES) {
        FlightModesScreen(
            state = flightModesState().copy(pendingDiff = sampleDiff(blocking = false)),
            onAction = {},
            onOpenMixes = {},
        )
    }
}

/** The dry-run dialog with a blocking conflict (Apply disabled). */
@PreviewTest
@Ax12Preview
@Composable
fun PlanDiffDialogBlockedScreenshot() {
    Ax12Theme {
        FixedCanvas { PlanDiffDialog(diff = sampleDiff(blocking = true), busy = false, onApply = {}, onDismiss = {}) }
    }
}

// -- Wing -----------------------------------------------------------------------------------------

private fun wingConfig(): WingConfig = WingConfig(
    enabled = true,
    wingType = WingType.TWO_AILERONS_TWO_FLAPS,
    tailType = TailType.V_TAIL,
    channels = WingChannels(aileronLeft = 0, aileronRight = 5, flapLeft = 1, flapRight = 4, elevator = 2, rudder = 3, throttle = 6),
    aileronDifferential = 40,
    flaps = FlapConfig(control = FlapControl.OnSwitch(SourceId.SB, 0, 40, 100, 0, 3, 6), speedMs = 1500, aileronFollow = 50),
    butterfly = ButterflyConfig(
        enabled = true, source = SourceId.THR, flapDown = 80, aileronUp = 30, deadband = 5,
        elevatorCompensation = listOf(0, 5, 10, 15, 20),
        activeWhen = SwitchCondition(SourceId.SF, CompareOp.EQUAL, 100),
    ),
)

private fun wingState(draft: WingConfig = wingConfig()) = WingState(
    draft = draft,
    applied = draft,
    pickers = pickers(),
)

/** Wing wizard, layout page. */
@PreviewTest
@Ax12Preview
@Composable
fun WingLayoutScreenshot() {
    TabFrame(SetupTabIds.WING, dirty = false) { WingScreen(state = wingState(), onAction = {}, initialPage = 0) }
}

/** Wing wizard, flaps page. */
@PreviewTest
@Ax12Preview
@Composable
fun WingFlapsScreenshot() {
    TabFrame(SetupTabIds.WING, dirty = false) { WingScreen(state = wingState(), onAction = {}, initialPage = 1) }
}

/** Wing wizard, butterfly page. */
@PreviewTest
@Ax12Preview
@Composable
fun WingButterflyScreenshot() {
    TabFrame(SetupTabIds.WING, dirty = false) { WingScreen(state = wingState(), onAction = {}, initialPage = 2) }
}

/** Wing wizard, mixing page, with a missing-channel problem on an unapplied draft. */
@PreviewTest
@Ax12Preview
@Composable
fun WingMixingScreenshot() {
    val draft = wingConfig().copy(channels = wingConfig().channels.copy(aileronRight = 0))
    TabFrame(SetupTabIds.WING) {
        WingScreen(
            state = wingState(draft).copy(
                applied = wingConfig(),
                problems = WingCompiler.validate(draft, config()),
            ),
            onAction = {},
            initialPage = 3,
        )
    }
}

/** Wing wizard switched off on a multirotor. */
@PreviewTest
@Ax12Preview
@Composable
fun WingOffScreenshot() {
    TabFrame(SetupTabIds.WING, dirty = false) {
        WingScreen(state = wingState(WingConfig()).copy(modelType = ModelType.MULTIROTOR), onAction = {})
    }
}

// -- Logical switches -----------------------------------------------------------------------------

private fun logicalSwitches(): List<LogicalSwitch> = listOf(
    LogicalSwitch(0, "Safe altitude", LogicalFunction.A_GREATER_X, a = Sensors.BARO_ALTITUDE, x = 100.0),
    LogicalSwitch(1, "", LogicalFunction.AND, s1 = Trigger.SwitchAt(SourceId.SA, SwitchPosition.DOWN), s2 = Trigger.Logical(0)),
    LogicalSwitch(2, "Low pack", LogicalFunction.A_LESS_X, a = Sensors.BATTERY_VOLTAGE, x = 7.2, delaySec = 2.0),
    LogicalSwitch(3, "", LogicalFunction.STICKY, s1 = Trigger.Link(LinkEvent.TELEMETRY_LOST), s2 = Trigger.SwitchAt(SourceId.SD, SwitchPosition.UP)),
    LogicalSwitch(6, "Pulse", LogicalFunction.TIMER, t1Sec = 1.0, t2Sec = 4.0),
    LogicalSwitch(9, "", LogicalFunction.A_GREATER_B, a = SensorId.input(SourceId.THR), b = SensorId.input(SourceId.ELE)),
)

/** The logical-switch list with a few lit switches. */
@PreviewTest
@Ax12Preview
@Composable
fun LogicalSwitchesScreenshot() {
    TabFrame(SetupTabIds.LOGICAL_SWITCHES, dirty = false) {
        LogicalSwitchesScreen(
            state = LogicalSwitchesState(LogicalSwitchRules.slots(logicalSwitches(), 32), pickers()),
            live = { LogicalSwitchStates(0b1001L) },
            onOpen = {},
        )
    }
}

/** Editor of a comparison with an AND-condition and a delay. */
@PreviewTest
@Ax12Preview
@Composable
fun LogicalSwitchEditorScreenshot() {
    val draft = logicalSwitches()[0].copy(andCondition = Trigger.SwitchAt(SourceId.SA, SwitchPosition.DOWN), delaySec = 0.5, durationSec = 3.0)
    EditorFrame {
        LogicalSwitchEditorScreen(
            state = LogicalSwitchEditorState(draft = draft, original = logicalSwitches()[0], pickers = pickers(), liveA = "87 m", isOn = true),
            onAction = {},
            onBack = {},
        )
    }
}

/** Editor of a sticky switch with two conditions. */
@PreviewTest
@Ax12Preview
@Composable
fun LogicalSwitchEditorStickyScreenshot() {
    val draft = logicalSwitches()[3]
    EditorFrame {
        LogicalSwitchEditorScreen(
            state = LogicalSwitchEditorState(
                draft = draft,
                original = draft,
                pickers = pickers(),
                problems = listOf(LogicalProblem.UNKNOWN_SENSOR),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

// -- Special functions ----------------------------------------------------------------------------

private fun specialFunctions(): List<SpecialFunction> = listOf(
    SpecialFunction(
        0, "Low battery", true,
        Trigger.Telemetry(Sensors.BATTERY_VOLTAGE, CompareOp.LESS, 7.2, 0.2),
        SpecialAction.PlayText("Battery {RxBt} volts", SpeechPriority.HIGH),
        Repeat(30),
    ),
    SpecialFunction(1, "", true, Trigger.SwitchAt(SourceId.SD, SwitchPosition.DOWN), SpecialAction.Vario),
    SpecialFunction(2, "Timer reset", true, Trigger.SwitchAt(SourceId.SE, SwitchPosition.DOWN), SpecialAction.ResetTimer(0)),
    SpecialFunction(3, "", false, Trigger.Timer(0, TimerEvent.REMAINING, 30), SpecialAction.Beep(BeepPattern.DOUBLE)),
    SpecialFunction(4, "Logging", true, Trigger.Logical(0), SpecialAction.Logging),
)

/** The special-function list. */
@PreviewTest
@Ax12Preview
@Composable
fun SpecialFunctionsScreenshot() {
    TabFrame(SetupTabIds.SPECIAL_FUNCTIONS, dirty = false) {
        SpecialFunctionsScreen(
            state = SpecialFunctionsState(specialFunctions(), pickers(), nextFree = 5),
            active = { setOf(1) },
            onOpen = {},
            onSetEnabled = { _, _ -> },
            onOpenChannelLock = {},
        )
    }
}

/** The empty special-function list. */
@PreviewTest
@Ax12Preview
@Composable
fun SpecialFunctionsEmptyScreenshot() {
    TabFrame(SetupTabIds.SPECIAL_FUNCTIONS, dirty = false) {
        SpecialFunctionsScreen(
            state = SpecialFunctionsState(emptyList(), pickers(), nextFree = 0),
            active = { emptySet() },
            onOpen = {},
            onSetEnabled = { _, _ -> },
            onOpenChannelLock = {},
        )
    }
}

/** Editor of a "say text" function with a telemetry trigger and a repeat. */
@PreviewTest
@Ax12Preview
@Composable
fun SpecialFunctionEditorTextScreenshot() {
    val function = specialFunctions()[0]
    EditorFrame {
        SpecialFunctionEditorScreen(
            state = SpecialFunctionEditorState(
                draft = function,
                original = function.copy(name = "Battery"),
                pickers = pickers(),
                preview = "Battery 7.1 volts",
            ),
            onAction = {},
            onBack = {},
            onOpenTab = {},
        )
    }
}

/** Editor of a continuous function (vario) that points to the Sounds tab. */
@PreviewTest
@Ax12Preview
@Composable
fun SpecialFunctionEditorVarioScreenshot() {
    val function = specialFunctions()[1]
    EditorFrame {
        SpecialFunctionEditorScreen(
            state = SpecialFunctionEditorState(draft = function, original = function, pickers = pickers()),
            onAction = {},
            onBack = {},
            onOpenTab = {},
        )
    }
}

// -- Sounds ---------------------------------------------------------------------------------------

private val NO_CALLBACKS = SoundsCallbacks(
    onOpenAlert = {},
    onSetAlertEnabled = { _, _ -> },
    onTestAlert = {},
    onEditVario = {},
    onResetVario = {},
    onTestVario = {},
    onEditAnnouncements = {},
)

private fun soundsState(tts: TtsStatus = TtsStatus.Ready("tts", "en-US")) = SoundsState(
    alerts = listOf(
        VoiceAlert("a1", "Link", true, "Telemetry {LQ} percent", Trigger.Periodic(60), Repeat(60), SpeechPriority.NORMAL),
        VoiceAlert("a2", "", true, "Altitude {Alt:0} metres", Trigger.SwitchAt(SourceId.SG, SwitchPosition.DOWN), Repeat(10), SpeechPriority.LOW),
        VoiceAlert("a3", "Thermal", false, "Thermal mode", Trigger.FlightModeIs(1), Repeat.ONCE, SpeechPriority.HIGH),
    ),
    vario = VarioConfig(),
    announcements = Announcements(switchPositions = true),
    pickers = pickers(),
    tts = tts,
)

/** Sounds: voice alerts. */
@PreviewTest
@Ax12Preview
@Composable
fun SoundsAlertsScreenshot() {
    TabFrame(SetupTabIds.SOUNDS, dirty = false) { SoundsScreen(state = soundsState(), callbacks = NO_CALLBACKS) }
}

/** Sounds: voice alerts with the missing text-to-speech warning. */
@PreviewTest
@Ax12Preview
@Composable
fun SoundsNoTtsScreenshot() {
    TabFrame(SetupTabIds.SOUNDS, dirty = false) { SoundsScreen(state = soundsState(TtsStatus.NoEngine), callbacks = NO_CALLBACKS) }
}

/** Sounds: vario settings. */
@PreviewTest
@Ax12Preview
@Composable
fun SoundsVarioScreenshot() {
    TabFrame(SetupTabIds.SOUNDS, dirty = false) { SoundsScreen(state = soundsState(), callbacks = NO_CALLBACKS, initialPage = 1) }
}

/** Sounds: built-in announcements. */
@PreviewTest
@Ax12Preview
@Composable
fun SoundsAnnouncementsScreenshot() {
    TabFrame(SetupTabIds.SOUNDS, dirty = false) { SoundsScreen(state = soundsState(), callbacks = NO_CALLBACKS, initialPage = 2) }
}

/** Voice-alert editor. */
@PreviewTest
@Ax12Preview
@Composable
fun VoiceAlertEditorScreenshot() {
    val alert = soundsState().alerts[1]
    EditorFrame {
        VoiceAlertEditorScreen(
            state = VoiceAlertEditorState(draft = alert, original = alert.copy(text = "Altitude"), pickers = pickers(), preview = "Altitude 87 metres"),
            onAction = {},
            onBack = {},
        )
    }
}

/** Dark theme sample of a busy tab. */
@PreviewTest
@Ax12Preview
@Composable
fun FlightModesDarkScreenshot() {
    TabFrame(SetupTabIds.FLIGHT_MODES, dirty = false, mode = AxThemeMode.DARK) {
        FlightModesScreen(state = flightModesState(), onAction = {}, onOpenMixes = {})
    }
}
