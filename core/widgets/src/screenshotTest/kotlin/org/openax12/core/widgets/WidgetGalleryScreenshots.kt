package org.openax12.core.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxThemeMode
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import org.openax12.core.model.WidgetType

// Gallery of every widget type for the visual checks (CLAUDE.md > Visual checks): all types in a
// single 1x1 cell with the missing / stale / lost states, the same types in 2x2 and 3x2 cells,
// and the pinned grid of the home centre card with 1, 3 and 6 widgets; light and dark.

private val LOST_ALT = SensorId("crsf.test.lostalt")
private val STALE_VOLT = SensorId("crsf.test.stale")
private val UNKNOWN = SensorId("crsf.test.unknown")
private val LOST_BATT = SensorId("crsf.test.lostbatt")
private val DEAD_LQ = SensorId("tx.test.lq")
private val DEAD_RSSI = SensorId("crsf.test.rssi")
private val SINK = SensorId("crsf.test.sink")

private fun galleryData(): FakeWidgetDataSource = FakeWidgetDataSource.demo().apply {
    set(LOST_ALT, 87.0, SensorUnit.METER, 0, "Alt", min = 0.0, max = 187.0, lost = true)
    set(STALE_VOLT, 9.8, SensorUnit.VOLT, 1, "RxBt", min = 9.8, max = 12.4, ageMs = 10_000)
    set(LOST_BATT, 10.2, SensorUnit.VOLT, 1, "RxBt", min = 10.2, max = 12.5, lost = true)
    set(DEAD_LQ, 0.0, SensorUnit.PERCENT, 0, "LQ", lost = true)
    set(DEAD_RSSI, -118.0, SensorUnit.DBM, 0, "RSSI", lost = true)
    set(SINK, -2.4, SensorUnit.METER_PER_SECOND, 1, "VSpd", min = -3.2, max = 4.6)
}

private fun widget(
    type: WidgetType,
    x: Int,
    y: Int,
    w: Int = 1,
    h: Int = 1,
    sensors: List<SensorId> = emptyList(),
    options: Map<String, String> = emptyMap(),
    title: String = "",
) = WidgetConfig(id = "$type-$x-$y", type = type, x = x, y = y, w = w, h = h, sensors = sensors, options = options, title = title)

private val smallWidgets: List<WidgetConfig> = listOf(
    widget(WidgetType.VALUE, 0, 0, sensors = listOf(Sensors.BARO_ALTITUDE)),
    widget(WidgetType.VALUE_MIN_MAX, 1, 0, sensors = listOf(Sensors.BATTERY_VOLTAGE)),
    widget(WidgetType.BAR, 2, 0, sensors = listOf(Sensors.BATTERY_REMAINING), options = mapOf(WidgetOptions.WARN_BELOW to "25")),
    widget(WidgetType.ARC_GAUGE, 3, 0, sensors = listOf(Sensors.AIRSPEED), options = mapOf(WidgetOptions.MIN to "0", WidgetOptions.MAX to "120")),
    widget(WidgetType.GRAPH, 4, 0, sensors = listOf(Sensors.BARO_ALTITUDE)),
    widget(WidgetType.BATTERY, 5, 0, sensors = listOf(Sensors.BATTERY_VOLTAGE)),
    widget(WidgetType.LINK_QUALITY, 0, 1),
    widget(WidgetType.TIMER, 1, 1, options = mapOf(WidgetOptions.TIMER to "0")),
    widget(WidgetType.VARIO, 2, 1, sensors = listOf(Sensors.VERTICAL_SPEED)),
    widget(WidgetType.HORIZON, 3, 1),
    widget(WidgetType.GPS, 4, 1),
    widget(WidgetType.SWITCH_STATE, 5, 1, sensors = listOf(SensorId.input(SourceId.SA))),
    widget(WidgetType.FLIGHT_MODE, 0, 2),
    widget(WidgetType.TEXT, 1, 2, options = mapOf(WidgetOptions.TEXT to "Launch height {Alt} m")),
    widget(WidgetType.SWITCH_STATE, 2, 2, options = mapOf(WidgetOptions.LOGICAL_SWITCH to "0")),
    widget(WidgetType.TIMER, 3, 2, options = mapOf(WidgetOptions.TIMER to "1")),
    widget(WidgetType.VALUE, 4, 2, sensors = listOf(Sensors.BATTERY_VOLTAGE), options = mapOf(WidgetOptions.WARN_BELOW to "11.5")),
    widget(WidgetType.VARIO, 5, 2, sensors = listOf(SINK)),
    // States: lost, stale, missing.
    widget(WidgetType.VALUE, 0, 3, sensors = listOf(LOST_ALT)),
    widget(WidgetType.VALUE_MIN_MAX, 1, 3, sensors = listOf(STALE_VOLT)),
    widget(WidgetType.VALUE, 2, 3, sensors = listOf(UNKNOWN), title = "Temp"),
    widget(WidgetType.BATTERY, 3, 3, sensors = listOf(LOST_BATT)),
    widget(WidgetType.LINK_QUALITY, 4, 3, sensors = listOf(DEAD_LQ, DEAD_RSSI)),
    widget(WidgetType.GPS, 5, 3, sensors = listOf(UNKNOWN, UNKNOWN), title = "GPS 2"),
)

private val mediumWidgetsA: List<WidgetConfig> = listOf(
    widget(WidgetType.VALUE_MIN_MAX, 0, 0, 2, 2, sensors = listOf(Sensors.BARO_ALTITUDE)),
    widget(WidgetType.BAR, 2, 0, 2, 2, sensors = listOf(SensorId.input(SourceId.THR))),
    widget(
        WidgetType.ARC_GAUGE, 4, 0, 2, 2, sensors = listOf(Sensors.AIRSPEED),
        options = mapOf(WidgetOptions.MIN to "0", WidgetOptions.MAX to "120", WidgetOptions.WARN_ABOVE to "100"),
    ),
    widget(WidgetType.GRAPH, 0, 2, 2, 2, sensors = listOf(Sensors.VERTICAL_SPEED)),
    widget(WidgetType.BATTERY, 2, 2, 2, 2, sensors = listOf(Sensors.BATTERY_VOLTAGE, Sensors.BATTERY_CURRENT, Sensors.BATTERY_CAPACITY)),
    widget(WidgetType.LINK_QUALITY, 4, 2, 2, 2),
)

private val mediumWidgetsB: List<WidgetConfig> = listOf(
    widget(WidgetType.TIMER, 0, 0, 2, 2, options = mapOf(WidgetOptions.TIMER to "1")),
    widget(WidgetType.VARIO, 2, 0, 2, 2, sensors = listOf(Sensors.VERTICAL_SPEED)),
    widget(WidgetType.HORIZON, 4, 0, 2, 2),
    widget(WidgetType.GPS, 0, 2, 2, 2),
    widget(WidgetType.SWITCH_STATE, 2, 2, 2, 2, sensors = listOf(SensorId.input(SourceId.SC))),
    widget(WidgetType.FLIGHT_MODE, 4, 2, 2, 2),
)

private val largeWidgets: List<WidgetConfig> = listOf(
    widget(WidgetType.GRAPH, 0, 0, 3, 2, sensors = listOf(Sensors.BARO_ALTITUDE), options = mapOf(WidgetOptions.HISTORY_SEC to "120")),
    widget(WidgetType.HORIZON, 3, 0, 3, 2),
    widget(WidgetType.GPS, 0, 2, 3, 2),
    widget(WidgetType.VALUE, 3, 2, 3, 1, sensors = listOf(Sensors.GPS_GROUND_SPEED), options = mapOf(WidgetOptions.COLOR to "#1FB8A6")),
    widget(WidgetType.BATTERY, 3, 3, 2, 1, sensors = listOf(Sensors.BATTERY_VOLTAGE, Sensors.BATTERY_CURRENT, Sensors.BATTERY_CAPACITY)),
    widget(WidgetType.TEXT, 5, 3, 1, 1, options = mapOf(WidgetOptions.TEXT to "Launch height {Alt} m")),
)

private val pinnedWidgets: List<WidgetConfig> = listOf(
    widget(WidgetType.VALUE_MIN_MAX, 0, 0, sensors = listOf(Sensors.BARO_ALTITUDE)),
    widget(WidgetType.VARIO, 1, 0, sensors = listOf(Sensors.VERTICAL_SPEED)),
    widget(WidgetType.BATTERY, 2, 0, sensors = listOf(Sensors.BATTERY_VOLTAGE)),
    widget(WidgetType.LINK_QUALITY, 3, 0),
    widget(WidgetType.TIMER, 4, 0, options = mapOf(WidgetOptions.TIMER to "0")),
    widget(WidgetType.FLIGHT_MODE, 5, 0),
)

/** A telemetry page as it sits between the status bar and the bottom bar. */
@Composable
private fun GalleryPage(mode: AxThemeMode, widgets: List<WidgetConfig>, editMode: Boolean = false) {
    AxTheme(mode) {
        Box(
            Modifier
                .size(853.dp, 388.dp)
                .background(AxTheme.colors.background)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            WidgetGrid(
                widgets = widgets,
                columns = 6,
                rows = 4,
                editMode = editMode,
                selectedWidgetId = if (editMode) widgets.firstOrNull()?.id else null,
                dataSource = galleryData(),
            )
        }
    }
}

/** The home centre card flipped to the pinned widgets, with 1, 3 and 6 of them. */
@Composable
private fun PinnedGallery(mode: AxThemeMode) {
    AxTheme(mode) {
        Row(
            Modifier
                .background(AxTheme.colors.background)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val data = galleryData()
            for (count in intArrayOf(1, 3, 6)) {
                AxCard(Modifier.size(260.dp, 250.dp)) {
                    PinnedWidgetGrid(pinnedWidgets.take(count), Modifier.padding(12.dp), dataSource = data)
                }
            }
        }
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun WidgetGallerySmallLight() = GalleryPage(AxThemeMode.LIGHT, smallWidgets)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetGallerySmallDark() = GalleryPage(AxThemeMode.DARK, smallWidgets)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetGallerySmallHighContrast() = GalleryPage(AxThemeMode.HIGH_CONTRAST, smallWidgets)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetGalleryMediumALight() = GalleryPage(AxThemeMode.LIGHT, mediumWidgetsA)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetGalleryMediumADark() = GalleryPage(AxThemeMode.DARK, mediumWidgetsA)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetGalleryMediumBLight() = GalleryPage(AxThemeMode.LIGHT, mediumWidgetsB)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetGalleryMediumBDark() = GalleryPage(AxThemeMode.DARK, mediumWidgetsB)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetGalleryLargeLight() = GalleryPage(AxThemeMode.LIGHT, largeWidgets)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetGalleryLargeDark() = GalleryPage(AxThemeMode.DARK, largeWidgets)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetGalleryEditModeLight() = GalleryPage(AxThemeMode.LIGHT, largeWidgets.take(3), editMode = true)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetPinnedLight() = PinnedGallery(AxThemeMode.LIGHT)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetPinnedDark() = PinnedGallery(AxThemeMode.DARK)
