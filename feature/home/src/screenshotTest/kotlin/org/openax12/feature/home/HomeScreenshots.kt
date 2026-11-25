package org.openax12.feature.home

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import org.openax12.core.designsystem.AxTheme
import androidx.compose.ui.Modifier
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.data.ModelEntry
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.Ax12Theme
import org.openax12.core.designsystem.AxBottomBar
import org.openax12.core.designsystem.AxDestination
import org.openax12.core.designsystem.AxScreen
import org.openax12.core.designsystem.AxStatusBar
import org.openax12.core.designsystem.AxThemeMode
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.HomeCenterCard
import org.openax12.core.model.HomePreferences
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.LogicalFunction
import org.openax12.core.model.LogicalSwitch
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.ModelType
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.SensorId
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.Sources
import org.openax12.core.model.StickMode
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.TelemetryLayout
import org.openax12.core.model.TelemetryPage
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.TimerState
import org.openax12.core.model.Trims
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import org.openax12.core.model.WidgetType
import org.openax12.core.model.Sensors
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.CompareOp
import org.openax12.core.model.WorkingConfig
import org.openax12.core.runtime.ActiveFlightMode
import org.openax12.core.widgets.FakeWidgetDataSource

// Screenshot functions of the home screen (CLAUDE.md > Visual checks). The screen is rendered from
// plain state between the real status bar and bottom bar of the design system, so the PNGs show
// the 388 dp the screen really gets.

private val Pinned: List<WidgetConfig> = listOf(
    WidgetConfig(id = "alt", type = WidgetType.VALUE_MIN_MAX, sensors = listOf(Sensors.BARO_ALTITUDE), pinned = true, pinOrder = 0),
    WidgetConfig(id = "vario", type = WidgetType.VARIO, sensors = listOf(Sensors.VERTICAL_SPEED), pinned = true, pinOrder = 1),
    WidgetConfig(id = "batt", type = WidgetType.BATTERY, sensors = listOf(Sensors.BATTERY_VOLTAGE), pinned = true, pinOrder = 2),
    WidgetConfig(id = "lq", type = WidgetType.LINK_QUALITY, sensors = listOf(Sensors.TX_LINK_QUALITY), pinned = true, pinOrder = 3),
    WidgetConfig(id = "t1", type = WidgetType.TIMER, options = mapOf(WidgetOptions.TIMER to "0"), pinned = true, pinOrder = 4),
    WidgetConfig(id = "fm", type = WidgetType.FLIGHT_MODE, pinned = true, pinOrder = 5),
)

private fun extras(
    centerCard: HomeCenterCard? = null,
    inputPage: Int = 0,
    channelPage: Int = 0,
    modelType: ModelType = ModelType.GLIDER,
    flightModes: Boolean = false,
    logicSwitches: Int = 0,
    pinned: List<WidgetConfig> = Pinned,
) = ModelExtras(
    modelType = modelType,
    home = HomePreferences(centerCard = centerCard, inputPage = inputPage, channelPage = channelPage),
    telemetry = TelemetryLayout(pages = listOf(TelemetryPage(id = "p", widgets = pinned))),
    flightModes = if (flightModes) {
        FlightModesConfig(
            enabled = true,
            modeSwitch = SourceId.SB,
            modes = listOf(FlightModeConfig("Cruise"), FlightModeConfig("Thermal"), FlightModeConfig("Speed")),
        )
    } else {
        FlightModesConfig()
    },
    logicalSwitches = List(logicSwitches) { LogicalSwitch(index = it, function = LogicalFunction.A_GREATER_X) },
)

/** An AX12-like radio: SA-SH, S1, S2, P1, P2 and CNT1 enabled, CH01 / CH03 / CH05 / CH07 with mixes. */
private fun config(stick: StickMode = StickMode.MODE_2, thirdTimer: Boolean = false): WorkingConfig {
    val enabled = (Sources.SA..Sources.SH).toSet() + setOf(Sources.S1, Sources.S2, Sources.P1, Sources.P2, Sources.CNT1)
    return WorkingConfig(
        sources = Sources.ALL.map { SourceInfo(index = it, enabled = it in enabled) },
        channels = List(16) { OutputChannelConfig(channel = it, mixCount = if (it % 2 == 0 && it < 10) 2 else 0) },
        timers = List(3) {
            if (it == 2 && thirdTimer) TimerConfig(index = it, switch = SwitchCondition(SourceId.SC, CompareOp.EQUAL, 100)) else TimerConfig(index = it)
        },
        system = SystemConfig(stickMode = stick),
    )
}

private const val ModelName = "Sunseeker 3.4"

private fun state(
    extras: ModelExtras = extras(),
    config: WorkingConfig = config(),
) = buildHomeState(ModelEntry(id = "m1", name = ModelName, type = extras.modelType, isCurrent = true), extras, config)

/** Live frame of a glider on the field: throttle closed, a little elevator, flaps switch up. */
private fun frame(
    left: Pair<Int, Int> = 0 to -100,
    right: Pair<Int, Int> = 35 to 20,
    trims: Trims = Trims(leftVertical = -12, leftHorizontal = 8, rightVertical = 20, rightHorizontal = -5),
    timers: List<TimerState> = listOf(
        TimerState(index = 0, count = 754, running = true),
        TimerState(index = 1, count = 0),
        TimerState(index = 2, count = 95),
    ),
    mode2: Boolean = true,
): LiveSnapshot {
    val logic = mutableMapOf(
        SourceId.RUD to left.first,
        (if (mode2) SourceId.THR else SourceId.ELE) to left.second,
        (if (mode2) SourceId.ELE else SourceId.THR) to right.second,
        SourceId.AIL to right.first,
        SourceId.SA to -100,
        SourceId.SB to -100,
        SourceId.SC to 0,
        SourceId.SD to 0,
        SourceId.SE to 100,
        SourceId.SF to 100,
        SourceId.SG to -100,
        SourceId.SH to 0,
        SourceId.slider(1) to -100,
        SourceId.slider(2) to 84,
        SourceId.pot(1) to 35,
        SourceId.pot(2) to -60,
        SourceId.counter(1) to 12,
    )
    return LiveSnapshot.of(
        logic = logic,
        channels = listOf(0, 12, -100, 0, -100, -100, -100, 40, 100, -35, 0, 0, 0, 0, 0, 0),
        trims = trims,
        status = RadioStatus(linkQuality = 98, rssiDbm = -62, rxVoltage = 7.9f, txVoltage = 8.1f),
        timers = timers,
    )
}

@Composable
private fun Frame(
    mode: AxThemeMode,
    state: HomeUiState,
    live: LiveSnapshot,
    flightMode: ActiveFlightMode = ActiveFlightMode(),
    logic: LogicalSwitchStates = LogicalSwitchStates.NONE,
) {
    Ax12Theme(mode) {
        // FixedCanvas paints this gradient in the app; the screenshot frame has to do it itself.
        val colors = AxTheme.colors
        AxScreen(
            modifier = Modifier.background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.background))),
            statusBar = {
                AxStatusBar(
                    title = ModelName,
                    linkQuality = { 98 },
                    rxVoltage = { 7.9f },
                    txVoltage = { 8.1f },
                    dateTime = "2026-11-19  09:41",
                    simulator = false,
                )
            },
            bottomBar = { AxBottomBar(selected = AxDestination.HOME, onSelect = {}) },
        ) {
            HomeScreen(
                state = state,
                live = { live },
                flightMode = { flightMode },
                logicalSwitches = { logic },
                onAction = {},
                modifier = Modifier,
                widgetDataSource = FakeWidgetDataSource.demo(),
            )
        }
    }
}

/** Default view of a model with pinned widgets: the widgets fill the centre card, the name is only in the status bar. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeWidgetsScreenshot() = Frame(AxThemeMode.LIGHT, state(), frame())

/** Dark scheme, widgets by default. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeDarkScreenshot() = Frame(AxThemeMode.DARK, state(extras(modelType = ModelType.MULTIROTOR)), frame(left = 0 to 0, right = 0 to 0))

/** Flipped by the user: the model picture with the flip button, stock first page (SA-SF above S1 / S2). */
@PreviewTest
@Ax12Preview
@Composable
fun HomePictureScreenshot() = Frame(AxThemeMode.LIGHT, state(extras(centerCard = HomeCenterCard.PICTURE)), frame())

/** Flipped by the user, dark scheme. */
@PreviewTest
@Ax12Preview
@Composable
fun HomePictureDarkScreenshot() = Frame(AxThemeMode.DARK, state(extras(centerCard = HomeCenterCard.PICTURE)), frame())

/** No widgets pinned: the picture with the hint that opens Telemetry; no flip button. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeNoWidgetsScreenshot() = Frame(AxThemeMode.LIGHT, state(extras(pinned = emptyList())), frame())

/** No widgets pinned, dark scheme. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeNoWidgetsDarkScreenshot() = Frame(AxThemeMode.DARK, state(extras(pinned = emptyList())), frame())

/** Channel list page 2 (CH09-CH16), input page 2 (SG, SH and the pots) and a third timer. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeSecondPagesScreenshot() = Frame(
    AxThemeMode.LIGHT,
    state(extras(inputPage = 1, channelPage = 1), config(thirdTimer = true)),
    frame(left = 0 to 0, right = 0 to 0),
)

/** Flight-mode page with three modes and logical switches, T3 configured, mode 1 sticks at full deflection in a corner. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeModesPageScreenshot() = Frame(
    AxThemeMode.LIGHT,
    state(extras(inputPage = 3, flightModes = true, logicSwitches = 14, modelType = ModelType.PLANE), config(StickMode.MODE_1, thirdTimer = true)),
    frame(left = -100 to 100, right = 100 to -100, mode2 = false),
    flightMode = ActiveFlightMode(1, "Thermal"),
    logic = LogicalSwitchStates.NONE.with(0, true).with(3, true).with(9, true),
)

/** High-contrast (sun) scheme with the widgets face. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeHighContrastScreenshot() = Frame(
    AxThemeMode.HIGH_CONTRAST,
    state(),
    frame(left = 100 to 0, right = 100 to 100),
)

/** Radio not ready yet: empty configuration and no live data; without pinned widgets. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeEmptyScreenshot() = Frame(
    AxThemeMode.LIGHT,
    buildHomeState(null, extras(pinned = emptyList()), WorkingConfig.EMPTY),
    LiveSnapshot.EMPTY,
)
