package org.openax12.feature.telemetry

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.AxThemeMode
import org.openax12.core.model.Sensors
import org.openax12.core.model.WidgetOptions
import org.openax12.core.model.WidgetType
import org.openax12.core.widgets.WidgetCatalog

private fun state(draft: org.openax12.core.model.WidgetConfig, isNew: Boolean = false, dirty: Boolean = false) =
    WidgetEditorState(isNew = isNew, draft = draft, columns = 6, rows = 4, sensors = DemoSensors, dirty = dirty)

@PreviewTest
@Ax12Preview
@Composable
fun WidgetEditorBatteryScreenshot() {
    val widget = FlightPage.widgets.first { it.id == "e" }.withOption(WidgetOptions.CELLS, "3").withOption(WidgetOptions.WARN_BELOW, "10.5")
    ScreenshotShell(title = "Telemetry") {
        WidgetEditorScreen(state = state(widget, dirty = true), onAction = {}, onCancel = {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun WidgetEditorNewScreenshot() {
    val widget = WidgetCatalog.create(WidgetType.ARC_GAUGE, "new")
    ScreenshotShell {
        WidgetEditorScreen(state = state(widget, isNew = true), onAction = {}, onCancel = {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun WidgetEditorTextDarkScreenshot() {
    val widget = FlightPage.widgets.first { it.id == "j" }.withOption(WidgetOptions.COLOR, "#F2A65A")
    ScreenshotShell(mode = AxThemeMode.DARK) {
        WidgetEditorScreen(state = state(widget), onAction = {}, onCancel = {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun WidgetEditorSwitchScreenshot() {
    val widget = WidgetCatalog.create(WidgetType.SWITCH_STATE, "sw").copy(sensors = emptyList()).withOption(WidgetOptions.LOGICAL_SWITCH, "3")
    ScreenshotShell {
        WidgetEditorScreen(state = state(widget, dirty = true), onAction = {}, onCancel = {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun SensorPickerScreenshot() {
    ScreenshotShell {
        WidgetEditorScreen(state = state(FlightPage.widgets.first { it.id == "f" }), onAction = {}, onCancel = {})
        SensorPickerDialog(
            title = "Sensor",
            sensors = DemoSensors,
            suggestions = listOf(Sensors.BATTERY_VOLTAGE, Sensors.BARO_ALTITUDE, Sensors.VERTICAL_SPEED),
            selected = Sensors.BARO_ALTITUDE,
            allowNone = false,
            onSelect = {},
            onDismiss = {},
        )
    }
}
