package org.openax12.feature.telemetry

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import org.openax12.core.designsystem.Ax12Theme
import org.openax12.core.designsystem.AxBottomBar
import org.openax12.core.designsystem.AxDestination
import org.openax12.core.designsystem.AxScreen
import org.openax12.core.designsystem.AxStatusBar
import org.openax12.core.designsystem.AxThemeMode
import org.openax12.core.designsystem.FixedCanvas
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorOrigin
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.TelemetryPage
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import org.openax12.core.model.WidgetType
import org.openax12.core.widgets.FakeWidgetDataSource
import org.openax12.core.widgets.LocalWidgetDataSource

/** The app shell around a screen: status bar, bottom bar, fixed canvas, fake widget data. */
@Composable
internal fun ScreenshotShell(
    mode: AxThemeMode = AxThemeMode.LIGHT,
    title: String = "Telemetry",
    content: @Composable BoxScope.() -> Unit,
) {
    val data = remember { FakeWidgetDataSource.demo() }
    Ax12Theme(mode) {
        CompositionLocalProvider(LocalWidgetDataSource provides data) {
            FixedCanvas {
                AxScreen(
                    statusBar = {
                        AxStatusBar(
                            title = title,
                            linkQuality = { 98 },
                            rxVoltage = { 7.9f },
                            txVoltage = { 8.1f },
                            dateTime = "2026-11-19  14:03",
                        )
                    },
                    bottomBar = { AxBottomBar(selected = AxDestination.TELEMETRY, onSelect = {}) },
                    content = content,
                )
            }
        }
    }
}

private fun w(
    id: String,
    type: WidgetType,
    x: Int,
    y: Int,
    wd: Int = 1,
    h: Int = 1,
    sensors: List<SensorId> = emptyList(),
    options: Map<String, String> = emptyMap(),
    pinned: Boolean = false,
    title: String = "",
) = WidgetConfig(id = id, type = type, x = x, y = y, w = wd, h = h, sensors = sensors, options = options, pinned = pinned, title = title)

/** A realistic 6 x 4 page of a glider model. */
internal val FlightPage = TelemetryPage(
    id = "flight",
    name = "Flight",
    widgets = listOf(
        w("a", WidgetType.ARC_GAUGE, 0, 0, 2, 2, listOf(Sensors.AIRSPEED), mapOf(WidgetOptions.MIN to "0", WidgetOptions.MAX to "120")),
        w("b", WidgetType.GRAPH, 2, 0, 3, 2, listOf(Sensors.BARO_ALTITUDE), title = "Height"),
        w("c", WidgetType.VARIO, 5, 0, 1, 1, listOf(Sensors.VERTICAL_SPEED)),
        w("d", WidgetType.LINK_QUALITY, 5, 1, 1, 1, listOf(Sensors.TX_LINK_QUALITY, Sensors.TX_RSSI)),
        w("e", WidgetType.BATTERY, 0, 2, 2, 1, listOf(Sensors.BATTERY_VOLTAGE), pinned = true),
        w("f", WidgetType.VALUE_MIN_MAX, 2, 2, 2, 1, listOf(Sensors.BARO_ALTITUDE), pinned = true),
        w("g", WidgetType.TIMER, 4, 2, 1, 1, options = mapOf(WidgetOptions.TIMER to "0")),
        w("h", WidgetType.FLIGHT_MODE, 5, 2, 1, 1),
        w("i", WidgetType.BAR, 0, 3, 3, 1, listOf(Sensors.BATTERY_REMAINING), mapOf(WidgetOptions.WARN_BELOW to "25")),
        w("j", WidgetType.TEXT, 3, 3, 2, 1, options = mapOf(WidgetOptions.TEXT to "Launch height {Alt} m")),
        w("k", WidgetType.SWITCH_STATE, 5, 3, 1, 1, listOf(SensorId.input(SourceId.SA))),
    ),
)

/** A second page with the GPS and the horizon. */
internal val GpsPage = TelemetryPage(
    id = "gps",
    name = "GPS",
    widgets = listOf(
        w("l", WidgetType.GPS, 0, 0, 2, 2, listOf(Sensors.GPS_LATITUDE, Sensors.GPS_LONGITUDE)),
        w("m", WidgetType.HORIZON, 2, 0, 2, 2, listOf(Sensors.ATTITUDE_PITCH, Sensors.ATTITUDE_ROLL)),
    ),
)

/** Registry entries for the pickers and lists. */
internal val DemoSensors: List<SensorInfo> = listOf(
    SensorInfo(Sensors.BATTERY_VOLTAGE, "RxBt", origin = SensorOrigin.CRSF, unit = SensorUnit.VOLT, precision = 1, templateName = "RxBt"),
    SensorInfo(Sensors.BATTERY_CURRENT, "Curr", origin = SensorOrigin.CRSF, unit = SensorUnit.AMPERE, precision = 1, templateName = "Curr"),
    SensorInfo(Sensors.BATTERY_REMAINING, "Bat%", origin = SensorOrigin.CRSF, unit = SensorUnit.PERCENT, templateName = "Bat%"),
    SensorInfo(Sensors.BARO_ALTITUDE, "Alt", origin = SensorOrigin.CRSF, unit = SensorUnit.METER, templateName = "Alt"),
    SensorInfo(Sensors.VERTICAL_SPEED, "VSpd", origin = SensorOrigin.CRSF, unit = SensorUnit.METER_PER_SECOND, precision = 1, templateName = "VSpd"),
    SensorInfo(Sensors.AIRSPEED, "Speed", defaultName = "ASpd", origin = SensorOrigin.CRSF, unit = SensorUnit.KMH, templateName = "ASpd"),
    SensorInfo(Sensors.GPS_SATELLITES, "Sats", origin = SensorOrigin.CRSF, unit = SensorUnit.COUNT, templateName = "Sats"),
    SensorInfo(Sensors.LINK_RX_QUALITY, "RQly", origin = SensorOrigin.CRSF, unit = SensorUnit.PERCENT, templateName = "RQly"),
    SensorInfo(Sensors.TX_BATTERY, "TxBt", origin = SensorOrigin.INTERNAL, unit = SensorUnit.VOLT, precision = 1, templateName = "TxBt"),
    SensorInfo(Sensors.TX_LINK_QUALITY, "LQ", origin = SensorOrigin.INTERNAL, unit = SensorUnit.PERCENT, templateName = "LQ"),
    SensorInfo(Sensors.TX_RSSI, "RSSI", origin = SensorOrigin.INTERNAL, unit = SensorUnit.DBM, templateName = "RSSI"),
    SensorInfo(Sensors.FC_FLIGHT_MODE, "FC mode", origin = SensorOrigin.CRSF, unit = SensorUnit.TEXT),
    SensorInfo(SensorId.input(SourceId.THR), "Thr", origin = SensorOrigin.INTERNAL, unit = SensorUnit.PERCENT),
    SensorInfo(SensorId.channel(2), "CH3", origin = SensorOrigin.INTERNAL, unit = SensorUnit.PERCENT, hidden = true),
    SensorInfo(SensorId.timer(0), "T1", origin = SensorOrigin.INTERNAL, unit = SensorUnit.SECOND),
)

/** Values for [DemoSensors], one of them lost. */
internal val DemoValues: Map<SensorId, SensorValue> = mapOf(
    Sensors.BATTERY_VOLTAGE to SensorValue(11.4, unit = SensorUnit.VOLT, precision = 1, min = 10.9, max = 12.5, timestampMs = 598_000),
    Sensors.BATTERY_CURRENT to SensorValue(12.3, unit = SensorUnit.AMPERE, precision = 1, min = 0.0, max = 38.2, timestampMs = 599_000),
    Sensors.BATTERY_REMAINING to SensorValue(62.0, unit = SensorUnit.PERCENT, min = 62.0, max = 100.0, timestampMs = 598_500),
    Sensors.BARO_ALTITUDE to SensorValue(123.0, unit = SensorUnit.METER, min = 0.0, max = 187.0, timestampMs = 599_500, lost = true),
    Sensors.VERTICAL_SPEED to SensorValue(1.8, unit = SensorUnit.METER_PER_SECOND, precision = 1, min = -3.2, max = 4.6, timestampMs = 599_900),
    Sensors.AIRSPEED to SensorValue(54.0, unit = SensorUnit.KMH, min = 0.0, max = 96.0, timestampMs = 599_900),
    Sensors.GPS_SATELLITES to SensorValue(12.0, unit = SensorUnit.COUNT, timestampMs = 595_000),
    Sensors.LINK_RX_QUALITY to SensorValue(100.0, unit = SensorUnit.PERCENT, min = 87.0, max = 100.0, timestampMs = 599_000),
    Sensors.TX_BATTERY to SensorValue(7.9, unit = SensorUnit.VOLT, precision = 1, min = 7.8, max = 8.3, timestampMs = 599_000),
    Sensors.TX_LINK_QUALITY to SensorValue(98.0, unit = SensorUnit.PERCENT, min = 84.0, max = 100.0, timestampMs = 599_900),
    Sensors.TX_RSSI to SensorValue(-62.0, unit = SensorUnit.DBM, min = -91.0, max = -38.0, timestampMs = 599_900),
    Sensors.FC_FLIGHT_MODE to SensorValue(text = "ACRO", unit = SensorUnit.TEXT, timestampMs = 599_000),
)
