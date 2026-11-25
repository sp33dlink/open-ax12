package org.openax12.feature.telemetry

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.data.DataError
import org.openax12.core.data.FlightLogFile
import org.openax12.core.data.FlightLogState
import org.openax12.core.data.StorageInfo
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.AxThemeMode
import org.openax12.core.model.AlarmOutputs
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.CompareOp
import org.openax12.core.model.GpsFix
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.SensorAlarm
import org.openax12.core.model.SensorOverride
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.Sensors

private const val NOW = 600_000L

@PreviewTest
@Ax12Preview
@Composable
fun SensorListScreenshot() {
    ScreenshotShell {
        SensorListScreen(
            state = SensorListState(
                sensors = DemoSensors,
                total = DemoSensors.size,
                values = DemoValues,
                overrides = mapOf(Sensors.AIRSPEED.key to SensorOverride(name = "Speed")),
            ),
            onAction = {},
            onBack = {},
            nowMs = NOW,
            liveValue = { SensorValue(42.0, unit = SensorUnit.PERCENT, timestampMs = NOW) },
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun SensorListFilteredDarkScreenshot() {
    ScreenshotShell(mode = AxThemeMode.DARK) {
        SensorListScreen(
            state = SensorListState(
                sensors = DemoSensors.filter { sensorCategory(it.id, it.origin) == SensorCategory.TRANSMITTER },
                total = DemoSensors.size,
                values = DemoValues,
                category = SensorCategory.TRANSMITTER,
            ),
            onAction = {},
            onBack = {},
            nowMs = NOW,
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun SensorOverrideDialogScreenshot() {
    ScreenshotShell {
        SensorListScreen(
            state = SensorListState(sensors = DemoSensors, total = DemoSensors.size, values = DemoValues),
            onAction = {},
            onBack = {},
            nowMs = NOW,
        )
        SensorOverrideDialog(
            info = DemoSensors.first { it.id == Sensors.AIRSPEED },
            current = SensorOverride(name = "Speed", precision = 1),
            onSave = {},
            onResetMinMax = {},
            onDismiss = {},
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun SensorListEmptyScreenshot() {
    ScreenshotShell {
        SensorListScreen(state = SensorListState(), onAction = {}, onBack = {}, nowMs = NOW)
    }
}

private val alarms = listOf(
    SensorAlarm(
        id = "a1", sensor = Sensors.BATTERY_VOLTAGE, op = CompareOp.LESS, threshold = 10.5, hysteresis = 0.2, delaySec = 2.0, repeatSec = 10,
        outputs = AlarmOutputs(voiceText = "Battery {RxBt} volts", beep = BeepPattern.WARNING, haptic = HapticPattern.TRIPLE),
    ),
    SensorAlarm(
        id = "a2", name = "Too low", sensor = Sensors.BARO_ALTITUDE, op = CompareOp.LESS, threshold = 30.0,
        outputs = AlarmOutputs(voiceText = "", beep = BeepPattern.LONG, popup = true),
    ),
    SensorAlarm(
        id = "a3", sensor = Sensors.LINK_RX_QUALITY, op = CompareOp.LESS, threshold = 60.0, enabled = false,
        outputs = AlarmOutputs(voiceText = "Link {RQly} percent", beep = null),
    ),
)

@PreviewTest
@Ax12Preview
@Composable
fun AlarmListScreenshot() {
    ScreenshotShell {
        AlarmScreen(state = AlarmUiState(alarms = alarms, sensors = DemoSensors, activeIds = setOf("a2")), onAction = {}, onBack = {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun AlarmEmptyScreenshot() {
    ScreenshotShell(mode = AxThemeMode.DARK) {
        AlarmScreen(state = AlarmUiState(sensors = DemoSensors), onAction = {}, onBack = {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun AlarmEditorScreenshot() {
    ScreenshotShell {
        AlarmScreen(
            state = AlarmUiState(alarms = alarms, sensors = DemoSensors, draft = alarms[0].copy(threshold = 10.0), soundFiles = listOf("lowbatt.wav")),
            onAction = {},
            onBack = {},
            previewText = { "Battery 11.4 volts" },
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun LastPositionScreenshot() {
    val fix = GpsFix(latitude = 48.13712, longitude = 11.57541, altitude = 512.0, groundSpeed = 48.0, heading = 72.0, satellites = 12, timeMs = 1_795_000_000_000)
    val home = GpsFix(latitude = 48.1361, longitude = 11.5700)
    ScreenshotShell {
        LastPositionScreen(
            state = LastPositionState(fix = fix, home = home),
            wallClockMs = fix.timeMs + 4 * 60_000L,
            onBack = {}, onOpenMaps = {}, onCopy = {}, onShare = {}, onUseAsHome = {},
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun LastPositionEmptyScreenshot() {
    ScreenshotShell(mode = AxThemeMode.DARK) {
        LastPositionScreen(
            state = LastPositionState(), wallClockMs = 0,
            onBack = {}, onOpenMaps = {}, onCopy = {}, onShare = {}, onUseAsHome = {},
        )
    }
}

private val logs = listOf(
    FlightLogFile("Glider-2026-11-19-134512.csv", "/sdcard/OpenAX12/logs/a.csv", "Glider", 1_795_000_000_000, 482_113),
    FlightLogFile("Glider-2026-11-18-101500.csv", "/sdcard/OpenAX12/logs/b.csv", "Glider", 1_794_900_000_000, 1_204_552),
    FlightLogFile("Wing-2026-11-12-170233.csv", "/sdcard/OpenAX12/logs/c.csv", "Wing", 1_794_300_000_000, 812),
)

private val storage = StorageInfo(libraryRoot = "/sdcard/OpenAX12", logsDir = "/sdcard/OpenAX12/logs")

@PreviewTest
@Ax12Preview
@Composable
fun FlightLogsRecordingScreenshot() {
    val recording = FlightLogFile(logs[0].name, logs[0].path, "Glider", logs[0].startedMs)
    ScreenshotShell {
        FlightLogsScreen(
            state = FlightLogsState(logs = logs, loaded = true, recording = FlightLogState.Recording(recording, recording.startedMs, 2150), storage = storage),
            onAction = {}, onBack = {}, onRequestStoragePermission = {},
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun FlightLogsPermissionScreenshot() {
    ScreenshotShell(mode = AxThemeMode.DARK) {
        FlightLogsScreen(
            state = FlightLogsState(logs = emptyList(), loaded = true, storage = storage.copy(permissionGranted = false)),
            onAction = {}, onBack = {}, onRequestStoragePermission = {},
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun FlightLogsFailedScreenshot() {
    ScreenshotShell {
        FlightLogsScreen(
            state = FlightLogsState(logs = logs.drop(1), loaded = true, recording = FlightLogState.Failed(DataError.IO, "No space left"), storage = storage),
            onAction = {}, onBack = {}, onRequestStoragePermission = {},
        )
    }
}
