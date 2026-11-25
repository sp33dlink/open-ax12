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
import org.openax12.core.model.HomeCenterCard
import org.openax12.core.model.HomePreferences
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.ModelType
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.SensorId
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.Sources
import org.openax12.core.model.SpButtonBar
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
    centerCard: HomeCenterCard = HomeCenterCard.PICTURE,
    channelPage: Int = 0,
    modelType: ModelType = ModelType.GLIDER,
    pinned: List<WidgetConfig> = Pinned,
) = ModelExtras(
    modelType = modelType,
    home = HomePreferences(centerCard = centerCard, channelPage = channelPage),
    telemetry = TelemetryLayout(pages = listOf(TelemetryPage(id = "p", widgets = pinned))),
)

/** An AX12-like radio: SA-SF, S1, S2 and SP enabled (SP optional), CH01 / CH03 / CH05 / CH07 with mixes. */
private fun config(stick: StickMode = StickMode.MODE_2, thirdTimer: Boolean = false, sp: Boolean = true): WorkingConfig {
    val enabled = (Sources.SA..Sources.SF).toSet() + setOf(Sources.S1, Sources.S2) + (if (sp) setOf(Sources.SP) else emptySet())
    return WorkingConfig(
        sources = Sources.ALL.map { SourceInfo(index = it, enabled = it in enabled) },
        channels = List(16) { OutputChannelConfig(channel = it, mixCount = if (it % 2 == 0 && it < 10) 2 else 0) },
        timers = List(3) {
            if (it == 2 && thirdTimer) TimerConfig(index = it, switch = SwitchCondition(SourceId.SC, CompareOp.EQUAL, 100)) else TimerConfig(index = it)
        },
        system = SystemConfig(stickMode = stick),
    )
}

private fun state(
    extras: ModelExtras = extras(),
    config: WorkingConfig = config(),
    name: String = "Sunseeker 3.4",
) = buildHomeState(ModelEntry(id = "m1", name = name, type = extras.modelType, isCurrent = true), extras, config)

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
    spButton: Int = 1,
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
        SourceId.SP to SpButtonBar.logicOf(spButton),
        SourceId.slider(1) to -100,
        SourceId.slider(2) to 84,
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
) {
    Ax12Theme(mode) {
        // FixedCanvas paints this gradient in the app; the screenshot frame has to do it itself.
        val colors = AxTheme.colors
        AxScreen(
            modifier = Modifier.background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.background))),
            statusBar = {
                AxStatusBar(
                    title = state.modelName.ifBlank { "open-ax12" },
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
                onAction = {},
                modifier = Modifier,
                widgetDataSource = FakeWidgetDataSource.demo(),
            )
        }
    }
}

/** Default view: model picture, two timers (T1 running), stock layout (SA-SF above S1 / S2, SP button 1 active), throttle stick at the bottom. */
@PreviewTest
@Ax12Preview
@Composable
fun HomePictureScreenshot() = Frame(AxThemeMode.LIGHT, state(), frame(spButton = 1))

/** Centre card flipped to the pinned telemetry widgets; SP button 4 active. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeWidgetsScreenshot() = Frame(AxThemeMode.LIGHT, state(extras(centerCard = HomeCenterCard.WIDGETS)), frame(spButton = 4))

/** Channel list page 2 (CH09-CH16), a third timer and SP button 6 active. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeSecondPageScreenshot() = Frame(
    AxThemeMode.LIGHT,
    state(extras(channelPage = 1), config(thirdTimer = true)),
    frame(left = 0 to 0, right = 0 to 0, spButton = 6),
)

/** Mode 1 sticks at full deflection in a corner, T3 configured, SP button 3 active. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeMode1Screenshot() = Frame(
    AxThemeMode.LIGHT,
    state(extras(modelType = ModelType.PLANE), config(StickMode.MODE_1, thirdTimer = true)),
    frame(left = -100 to 100, right = 100 to -100, mode2 = false, spButton = 3),
)

/** Radio without an SP input: the button row is hidden. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeNoSpScreenshot() = Frame(AxThemeMode.LIGHT, state(config = config(sp = false)), frame())

/** Dark scheme, SP button 1. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeDarkScreenshot() = Frame(AxThemeMode.DARK, state(extras(modelType = ModelType.MULTIROTOR)), frame(left = 0 to 0, right = 0 to 0, spButton = 1))

/** Dark scheme, SP button 4. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeDarkSp4Screenshot() = Frame(AxThemeMode.DARK, state(), frame(spButton = 4))

/** Dark scheme, SP button 6 (last button). */
@PreviewTest
@Ax12Preview
@Composable
fun HomeDarkSp6Screenshot() = Frame(AxThemeMode.DARK, state(extras(centerCard = HomeCenterCard.WIDGETS)), frame(spButton = 6))

/** High-contrast (sun) scheme with the widgets face, SP button 2. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeHighContrastScreenshot() = Frame(
    AxThemeMode.HIGH_CONTRAST,
    state(extras(centerCard = HomeCenterCard.WIDGETS)),
    frame(left = 100 to 0, right = 100 to 100, spButton = 2),
)

/** Radio not ready yet: empty configuration and no live data; a model without a name and without pinned widgets. */
@PreviewTest
@Ax12Preview
@Composable
fun HomeEmptyScreenshot() = Frame(
    AxThemeMode.LIGHT,
    buildHomeState(null, extras(centerCard = HomeCenterCard.WIDGETS, pinned = emptyList()), WorkingConfig.EMPTY),
    LiveSnapshot.EMPTY,
)
