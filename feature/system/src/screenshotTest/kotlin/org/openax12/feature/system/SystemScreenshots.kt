package org.openax12.feature.system

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.openax12.core.designsystem.Ax12Theme
import org.openax12.feature.system.settings.AboutPanel
import org.openax12.feature.system.settings.AppPanel
import org.openax12.feature.system.settings.AudioPanel
import org.openax12.feature.system.settings.RadioPanel
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.data.BackupInfo
import org.openax12.core.data.RestoreMode
import org.openax12.core.data.RestoreReport
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.AxDestination
import org.openax12.core.designsystem.AxThemeMode
import org.openax12.core.model.AppTheme
import org.openax12.core.model.ChecklistItem
import org.openax12.core.model.ElrsStatus
import org.openax12.core.model.SensorId
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.StickMode
import org.openax12.core.radio.ConnectionState
import org.openax12.feature.system.backup.BackupScreen
import org.openax12.feature.system.backup.BackupUiState
import org.openax12.feature.system.backup.InspectedBackup
import org.openax12.feature.system.calibration.CalibrationScreen
import org.openax12.feature.system.calibration.CalibrationUiState
import org.openax12.feature.system.calibration.StickExtents
import org.openax12.feature.system.elrs.CommandProgress
import org.openax12.feature.system.elrs.ElrsScreen
import org.openax12.feature.system.elrs.ElrsUiState
import org.openax12.feature.system.diagnostics.DiagnosticsScreen
import org.openax12.feature.system.help.HelpScreen
import org.openax12.feature.system.help.HelpTopic
import org.openax12.feature.system.preflight.PreFlightRow
import org.openax12.feature.system.preflight.PreFlightScreen
import org.openax12.feature.system.preflight.PreFlightUiState
import org.openax12.feature.system.settings.RadioAction
import org.openax12.feature.system.settings.SettingsTab
import org.openax12.feature.system.settings.SystemSettingsScreen
import org.openax12.feature.system.simulator.SimulatorScreen
import org.openax12.feature.system.simulator.SimulatorUiState

// -- System settings -------------------------------------------------------------------------

@Composable
private fun Settings(tab: SettingsTab, state: org.openax12.feature.system.settings.SettingsUiState = sampleSettingsState(), mode: AxThemeMode = AxThemeMode.LIGHT) {
    Frame(mode = mode) {
        SystemSettingsScreen(state = state, appVersion = "0.1.0", selectedTab = tab, onTabSelected = {}, onEvent = {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun SettingsRadioScreenshot() = Settings(SettingsTab.RADIO)

@PreviewTest
@Ax12Preview
@Composable
fun SettingsRadioDarkScreenshot() = Settings(SettingsTab.RADIO, mode = AxThemeMode.DARK)

@PreviewTest
@Ax12Preview
@Composable
fun SettingsRadioSunScreenshot() = Settings(SettingsTab.RADIO, mode = AxThemeMode.HIGH_CONTRAST)

@PreviewTest
@Ax12Preview
@Composable
fun SettingsRadioNotConnectedScreenshot() = Settings(SettingsTab.RADIO, sampleSettingsState(connection = StockAppError))

@PreviewTest
@Ax12Preview
@Composable
fun SettingsRadioUnsavedScreenshot() = Settings(SettingsTab.RADIO, sampleSettingsState(dirty = true))

@PreviewTest
@Ax12Preview
@Composable
fun SettingsRestoreDialogScreenshot() = Settings(SettingsTab.RADIO, sampleSettingsState(pending = RadioAction.RestoreDefaults))

@PreviewTest
@Ax12Preview
@Composable
fun SettingsLinkedConfirmScreenshot() =
    Settings(SettingsTab.RADIO, sampleSettingsState(pending = RadioAction.SetStickMode(StickMode.MODE_1)).copy(linkUp = true))

@PreviewTest
@Ax12Preview
@Composable
fun SettingsAudioScreenshot() = Settings(SettingsTab.AUDIO)

@PreviewTest
@Ax12Preview
@Composable
fun SettingsAudioNoEngineScreenshot() =
    Settings(SettingsTab.AUDIO, sampleSettingsState().copy(tts = org.openax12.core.audio.TtsStatus.NoEngine, languages = emptyList()))

@PreviewTest
@Ax12Preview
@Composable
fun SettingsAppScreenshot() = Settings(SettingsTab.APP)

@PreviewTest
@Ax12Preview
@Composable
fun SettingsStorageScreenshot() = Settings(SettingsTab.STORAGE)

@PreviewTest
@Ax12Preview
@Composable
fun SettingsStoragePermissionScreenshot() =
    Settings(SettingsTab.STORAGE, sampleSettingsState().copy(storage = SampleStorage.copy(permissionGranted = false, usesPublicStorage = false, libraryRoot = "/storage/emulated/0/Android/data/org.openax12.app/files/OpenAX12")))

@PreviewTest
@Ax12Preview
@Composable
fun SettingsAboutScreenshot() = Settings(SettingsTab.ABOUT)

@PreviewTest
@Ax12Preview
@Composable
fun SettingsAboutSimulatorScreenshot() =
    Settings(SettingsTab.ABOUT, sampleSettingsState().copy(activeBackend = org.openax12.core.model.RadioBackend.SIMULATOR, firmware = null))

// -- ELRS ------------------------------------------------------------------------------------

@Composable
private fun Elrs(state: ElrsUiState) {
    Frame {
        ElrsScreen(
            state = state,
            onBack = {},
            onDevice = {},
            onReload = {},
            onStatus = {},
            onOpenFolder = {},
            onUp = {},
            onSetValue = { _, _ -> },
            onCommand = { _, _ -> },
        )
    }
}

private fun elrsState() = ElrsUiState(
    deviceName = "AX12 Internal ELRS",
    items = sampleElrsItems(),
    status = ElrsStatus(badPackets = 3, goodPackets = 2_411),
    connection = ConnectionState.Ready,
    linkUp = true,
)

@PreviewTest
@Ax12Preview
@Composable
fun ElrsRootScreenshot() = Elrs(elrsState().copy(commands = mapOf(8 to CommandProgress.Running("WiFi Running..."))))

@PreviewTest
@Ax12Preview
@Composable
fun ElrsFolderScreenshot() = Elrs(elrsState().copy(folder = 5))

@PreviewTest
@Ax12Preview
@Composable
fun ElrsMismatchScreenshot() = Elrs(elrsState().copy(status = ElrsStatus(badPackets = 840, goodPackets = 12, modelMismatch = 1)))

@PreviewTest
@Ax12Preview
@Composable
fun ElrsNoAnswerScreenshot() = Elrs(ElrsUiState(noAnswer = true, connection = ConnectionState.Ready))

@PreviewTest
@Ax12Preview
@Composable
fun ElrsNotConnectedScreenshot() = Elrs(ElrsUiState(connection = StockAppError, noAnswer = true))

// -- Calibration -----------------------------------------------------------------------------

@Composable
private fun Calibration(state: CalibrationUiState) {
    Frame {
        CalibrationScreen(
            state = state,
            live = { sampleLive() },
            onBack = {},
            onStart = {},
            onFinish = {},
            onConfirmStart = {},
            onDismissConfirm = {},
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun CalibrationIdleScreenshot() = Calibration(CalibrationUiState(connection = ConnectionState.Ready))

@PreviewTest
@Ax12Preview
@Composable
fun CalibrationRunningScreenshot() {
    val live = sampleLive()
    val extents = StickExtents().observe(live)
    Calibration(CalibrationUiState(connection = ConnectionState.Ready, calibrationState = 1, extents = extents.observe(live)))
}

@PreviewTest
@Ax12Preview
@Composable
fun CalibrationLinkedConfirmScreenshot() =
    Calibration(CalibrationUiState(connection = ConnectionState.Ready, linkUp = true, confirmStart = true))

// -- Pre-flight ------------------------------------------------------------------------------

@Composable
private fun PreFlight(state: PreFlightUiState) {
    Frame {
        PreFlightScreen(
            state = state,
            onBack = {},
            onSkip = {},
            onEditPresets = {},
            onToggleChecked = {},
            onResetChecks = {},
            onAdd = {},
            onRename = { _, _ -> },
            onToggleSpoken = {},
            onRemove = {},
            onMove = { _, _ -> },
            onReadAloud = {},
        )
    }
}

private fun preFlightState(failing: Boolean) = PreFlightUiState(
    connection = ConnectionState.Ready,
    rows = listOf(
        PreFlightRow(SourceId.THR, "Thr", -100, failing),
        PreFlightRow(SourceId.SA, "SA", -100, false),
        PreFlightRow(SourceId.SC, "SC", 0, false),
    ),
    alarmPending = failing,
    modelName = "Quad 5 inch",
    checklist = listOf(
        ChecklistItem("Props tight and undamaged", spoken = true),
        ChecklistItem("Battery strapped, voltage above 16 V", spoken = true),
        ChecklistItem("Throttle low, arm switch off"),
        ChecklistItem("Failsafe tested with the motor disarmed"),
    ),
    checked = setOf(0, 1),
)

@PreviewTest
@Ax12Preview
@Composable
fun PreFlightFailingScreenshot() = PreFlight(preFlightState(failing = true))

@PreviewTest
@Ax12Preview
@Composable
fun PreFlightPassingScreenshot() = PreFlight(preFlightState(failing = false))

@PreviewTest
@Ax12Preview
@Composable
fun PreFlightEmptyScreenshot() =
    PreFlight(PreFlightUiState(connection = ConnectionState.Ready, modelName = "Glider"))

// -- Diagnostics -----------------------------------------------------------------------------

@Composable
private fun Diagnostics(tab: String, state: org.openax12.feature.system.diagnostics.DiagnosticsUiState = sampleDiagnosticsState()) {
    Frame {
        DiagnosticsScreen(
            state = state,
            live = { sampleLive() },
            onBack = {},
            onRefresh = {},
            onExport = {},
            onRun = {},
            onSelectCurve = {},
            onClearLog = {},
            initialTab = tab,
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun DiagnosticsOverviewScreenshot() = Diagnostics("overview")

@PreviewTest
@Ax12Preview
@Composable
fun DiagnosticsOverviewErrorScreenshot() =
    Diagnostics("overview", sampleDiagnosticsState(sampleDiagnosticsData().copy(connection = StockAppError, firmware = null, system = null)))

@PreviewTest
@Ax12Preview
@Composable
fun DiagnosticsMonitorScreenshot() = Diagnostics("monitor")

@PreviewTest
@Ax12Preview
@Composable
fun DiagnosticsCrsfScreenshot() = Diagnostics("crsf")

@PreviewTest
@Ax12Preview
@Composable
fun DiagnosticsChecksScreenshot() = Diagnostics("checks")

@PreviewTest
@Ax12Preview
@Composable
fun DiagnosticsLogScreenshot() = Diagnostics("log")

// -- Simulator -------------------------------------------------------------------------------

@Composable
private fun Simulator(tab: String, state: SimulatorUiState) {
    Frame {
        SimulatorScreen(
            state = state,
            live = { sampleLive() },
            onBack = {},
            onStick = { _, _ -> },
            onPot = { _, _ -> },
            onSwitch = { _, _ -> },
            onTelemetry = { _, _ -> },
            onFlightModeText = {},
            onDemoMotion = {},
            onLinkUp = {},
            onFault = {},
            onReset = {},
            initialTab = tab,
        )
    }
}

private fun simulatorState() = SimulatorUiState(
    available = true,
    demoMotion = false,
    linkUp = true,
    telemetry = mapOf(
        Sensors.TX_BATTERY to 7.7,
        Sensors.TX_LINK_QUALITY to 92.0,
        Sensors.BATTERY_VOLTAGE to 16.4,
        Sensors.BATTERY_CURRENT to 12.5,
        Sensors.BARO_ALTITUDE to 120.0,
        Sensors.VERTICAL_SPEED to 1.8,
        Sensors.GPS_LATITUDE to 52.52,
        Sensors.GPS_LONGITUDE to 13.405,
        Sensors.GPS_SATELLITES to 11.0,
    ),
    sources = sampleSources(),
    stickMode = StickMode.MODE_2,
    connection = ConnectionState.Ready,
)

@PreviewTest
@Ax12Preview
@Composable
fun SimulatorInputsScreenshot() = Simulator("inputs", simulatorState())

@PreviewTest
@Ax12Preview
@Composable
fun SimulatorTelemetryScreenshot() = Simulator("telemetry", simulatorState())

@PreviewTest
@Ax12Preview
@Composable
fun SimulatorLinkScreenshot() = Simulator("link", simulatorState())

@PreviewTest
@Ax12Preview
@Composable
fun SimulatorUnavailableScreenshot() = Simulator("inputs", SimulatorUiState(available = false))

// -- Backup ----------------------------------------------------------------------------------

@Composable
private fun Backup(state: BackupUiState) {
    Frame {
        BackupScreen(
            state = state,
            onBack = {},
            onIncludeSettings = {},
            onShare = {},
            onSaveToFile = {},
            onPick = {},
            onMode = {},
            onRestoreSettings = {},
            onRequestRestore = {},
            onDismissRestore = {},
            onRestore = {},
            onReload = {},
            onPostponeReload = {},
            onGrantStorage = {},
        )
    }
}

private val SampleBackup = BackupInfo(
    createdMs = 1_795_000_000_000L,
    appVersion = "0.1.0",
    modelNames = listOf("Glider", "Quad 5 inch", "Heli 500", "Wing"),
    soundFiles = 12,
    includesSettings = true,
    sizeBytes = 4_820_000,
)

@PreviewTest
@Ax12Preview
@Composable
fun BackupInitialScreenshot() = Backup(BackupUiState(storage = SampleStorage))

@PreviewTest
@Ax12Preview
@Composable
fun BackupInspectedScreenshot() =
    Backup(BackupUiState(storage = SampleStorage, inspected = InspectedBackup(android.net.Uri.EMPTY, SampleBackup), restoreSettings = true))

@PreviewTest
@Ax12Preview
@Composable
fun BackupRestoredScreenshot() = Backup(
    BackupUiState(
        storage = SampleStorage,
        restoreMode = RestoreMode.OVERWRITE_EXISTING,
        report = RestoreReport(restored = listOf("Glider", "Wing"), skipped = listOf("Quad 5 inch"), problems = listOf("Heli 500: picture could not be read")),
    ),
)

@PreviewTest
@Ax12Preview
@Composable
fun BackupReloadDialogScreenshot() =
    Backup(BackupUiState(storage = SampleStorage, reloadPending = true, linkUp = true, report = RestoreReport(restored = listOf("Quad 5 inch"), currentModelReplaced = true)))

@PreviewTest
@Ax12Preview
@Composable
fun BackupOverwriteConfirmScreenshot() = Backup(
    BackupUiState(
        storage = SampleStorage,
        inspected = InspectedBackup(android.net.Uri.EMPTY, SampleBackup),
        restoreMode = RestoreMode.OVERWRITE_EXISTING,
        confirmRestore = true,
    ),
)

// -- Help ------------------------------------------------------------------------------------

@PreviewTest
@Ax12Preview
@Composable
fun HelpScreenshot() = Frame(selected = AxDestination.HELP, title = "Help") {
    HelpScreen(selected = HelpTopic.START, onSelect = {}, onBack = {})
}


// -- Tall renderings: the parts of the scrolling panels below the first screen --------------------

@Composable
private fun Tall(content: @Composable () -> Unit) {
    Ax12Theme {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

private const val TALL_BACKGROUND = 0xFFDDF2F6

@PreviewTest
@Preview(name = "tall", widthDp = 853, heightDp = 760, showBackground = true, backgroundColor = TALL_BACKGROUND)
@Composable
fun TallRadioPanelScreenshot() = Tall { RadioPanel(sampleSettingsState(), {}) }

@PreviewTest
@Preview(name = "tall", widthDp = 853, heightDp = 900, showBackground = true, backgroundColor = TALL_BACKGROUND)
@Composable
fun TallAudioPanelScreenshot() = Tall { AudioPanel(sampleSettingsState(), {}) }

@PreviewTest
@Preview(name = "tall", widthDp = 853, heightDp = 1060, showBackground = true, backgroundColor = TALL_BACKGROUND)
@Composable
fun TallAppPanelScreenshot() = Tall { AppPanel(sampleSettingsState(), {}) }

@PreviewTest
@Preview(name = "tall", widthDp = 853, heightDp = 860, showBackground = true, backgroundColor = TALL_BACKGROUND)
@Composable
fun TallAboutPanelScreenshot() =
    Tall { AboutPanel(sampleSettingsState().copy(activeBackend = org.openax12.core.model.RadioBackend.SIMULATOR), "0.1.0", {}) }

@PreviewTest
@Preview(name = "tall", widthDp = 853, heightDp = 720, showBackground = true, backgroundColor = TALL_BACKGROUND)
@Composable
fun TallDiagnosticsOverviewScreenshot() = Tall {
    DiagnosticsScreen(
        state = sampleDiagnosticsState(),
        live = { sampleLive() },
        onBack = {},
        onRefresh = {},
        onExport = {},
        onRun = {},
        onSelectCurve = {},
        onClearLog = {},
    )
}

@PreviewTest
@Ax12Preview
@Composable
fun DiagnosticsChecksActionsScreenshot() {
    val state = sampleDiagnosticsState()
    Diagnostics("checks", state.copy(checks = state.checks.filter { it.number in setOf(12, 13, 15, 26) }))
}

@PreviewTest
@Ax12Preview
@Composable
fun DiagnosticsChecksNoDataScreenshot() {
    val data = sampleDiagnosticsData().copy(
        connection = StockAppError,
        curveProbe = null,
        elrs = null,
        observedSwitches = emptyMap(),
        crsf = org.openax12.core.data.CrsfStats(),
        tts = org.openax12.core.audio.TtsStatus.NoEngine,
    )
    val state = sampleDiagnosticsState(data)
    Diagnostics("checks", state.copy(checks = state.checks.filter { it.number in setOf(1, 3, 7, 9, 12, 13) }))
}
