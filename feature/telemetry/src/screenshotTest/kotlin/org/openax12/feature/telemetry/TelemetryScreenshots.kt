package org.openax12.feature.telemetry

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.AxThemeMode

private val twoPages = listOf(FlightPage, GpsPage)

@PreviewTest
@Ax12Preview
@Composable
fun TelemetryViewScreenshot() {
    ScreenshotShell {
        TelemetryScreen(
            state = TelemetryUiState(pages = twoPages, selectedPageId = "flight", pinnedCount = 2),
            onAction = {},
            navigation = TelemetryNavigation(),
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun TelemetryViewDarkScreenshot() {
    ScreenshotShell(mode = AxThemeMode.DARK) {
        TelemetryScreen(
            state = TelemetryUiState(pages = twoPages, selectedPageId = "gps", pinnedCount = 2),
            onAction = {},
            navigation = TelemetryNavigation(),
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun TelemetryEditSelectedScreenshot() {
    ScreenshotShell {
        TelemetryScreen(
            state = TelemetryUiState(pages = twoPages, selectedPageId = "flight", editMode = true, selectedWidgetId = "e", pinnedCount = 2),
            onAction = {},
            navigation = TelemetryNavigation(),
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun TelemetryEditNothingSelectedScreenshot() {
    ScreenshotShell {
        TelemetryScreen(
            state = TelemetryUiState(pages = twoPages, selectedPageId = "gps", editMode = true, pinnedCount = 2),
            onAction = {},
            navigation = TelemetryNavigation(),
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun TelemetryNoPagesScreenshot() {
    ScreenshotShell {
        TelemetryScreen(state = TelemetryUiState(), onAction = {}, navigation = TelemetryNavigation())
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun TelemetryEmptyPageScreenshot() {
    ScreenshotShell {
        TelemetryScreen(
            state = TelemetryUiState(pages = listOf(org.openax12.core.model.TelemetryPage(id = "x")), selectedPageId = "x"),
            onAction = {},
            navigation = TelemetryNavigation(),
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun PageSettingsDialogScreenshot() {
    ScreenshotShell {
        TelemetryScreen(
            state = TelemetryUiState(pages = twoPages, selectedPageId = "flight", editMode = true),
            onAction = {},
            navigation = TelemetryNavigation(),
        )
        PageSettingsDialog(page = FlightPage, pageIndex = 0, pageCount = 2, onApply = { _, _, _ -> }, onMove = {}, onDelete = {}, onDismiss = {})
    }
}
