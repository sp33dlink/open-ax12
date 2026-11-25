package org.openax12.feature.system

import androidx.compose.runtime.Composable
import org.openax12.core.audio.TtsStatus
import org.openax12.core.data.CrsfStats
import org.openax12.core.data.StorageInfo
import org.openax12.core.designsystem.AxBottomBar
import org.openax12.core.designsystem.AxDestination
import org.openax12.core.designsystem.AxScreen
import org.openax12.core.designsystem.AxStatusBar
import org.openax12.core.designsystem.AxThemeMode
import org.openax12.core.designsystem.Ax12Theme
import org.openax12.core.designsystem.FixedCanvas
import org.openax12.core.model.AppSettings
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CurveType
import org.openax12.core.model.ElrsItemType
import org.openax12.core.model.ElrsMenuItem
import org.openax12.core.model.FirmwareInfo
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.Sources
import org.openax12.core.model.StickMode
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.Trims
import org.openax12.core.radio.ConnectionError
import org.openax12.core.radio.ConnectionState
import org.openax12.core.runtime.DiagnosticEntry
import org.openax12.core.runtime.StartupState
import org.openax12.feature.system.diagnostics.AssumptionChecks
import org.openax12.feature.system.diagnostics.CurveProbe
import org.openax12.feature.system.diagnostics.CurveProbeRow
import org.openax12.feature.system.diagnostics.DiagnosticsData
import org.openax12.feature.system.diagnostics.DiagnosticsUiState
import org.openax12.feature.system.diagnostics.ElrsProbe
import org.openax12.feature.system.settings.SettingsUiState

/** The canvas of the AX12 with the app shell's bars around [content], as the entries are shown. */
@Composable
internal fun Frame(
    mode: AxThemeMode = AxThemeMode.LIGHT,
    selected: AxDestination? = AxDestination.SYSTEM,
    title: String = "System",
    content: @Composable () -> Unit,
) {
    Ax12Theme(mode) {
        FixedCanvas {
            AxScreen(
                statusBar = {
                    AxStatusBar(
                        title = title,
                        linkQuality = { 92 },
                        rxVoltage = { 7.9f },
                        txVoltage = { 7.7f },
                        dateTime = "2026-11-19  10:30",
                    )
                },
                bottomBar = { AxBottomBar(selected = selected, onSelect = {}) },
            ) { content() }
        }
    }
}

internal val SampleSystem = SystemConfig(
    stickMode = StickMode.MODE_2,
    alarmVoltageDeciVolt = 72,
    preFlightCheck = List(36) { if (it == Sources.THR) -100 else if (it == Sources.SA) -100 else 127 },
)

internal val SampleFirmware = FirmwareInfo(
    systemId = 3,
    deviceId = 12,
    flightTimeSec = 41_520,
    buildDate = 260_912,
    firmwareVersion = 0x01020003,
    hardwareVersion = 0x0102,
    deviceText = "AX12",
    firmwareVersionText = "1.2.3",
    buildTimeText = "2026-09-12 14:30",
)

internal val SampleStorage = StorageInfo(
    libraryRoot = "/sdcard/OpenAX12",
    modelsDir = "/sdcard/OpenAX12/models",
    soundsDir = "/sdcard/OpenAX12/sounds",
    logsDir = "/sdcard/OpenAX12/logs",
    usesPublicStorage = true,
    permissionGranted = true,
    freeBytes = 23_400_000_000L,
)

internal fun sampleSettingsState(
    connection: ConnectionState = ConnectionState.Ready,
    app: AppSettings = AppSettings(),
    pending: org.openax12.feature.system.settings.RadioAction? = null,
    dirty: Boolean = false,
) = SettingsUiState(
    app = app,
    system = if (connection.isReady) SampleSystem else null,
    connection = connection,
    linkUp = false,
    dirty = dirty,
    tts = TtsStatus.Ready("com.google.android.tts", "en-US"),
    languages = listOf("en-US", "de-DE", "fr-FR"),
    storage = SampleStorage,
    firmware = SampleFirmware,
    sdkVersion = "Oct  8 2026 16:07:03",
    pending = pending,
)

internal fun sampleSources(): List<SourceInfo> = (0 until 42).map { index ->
    SourceInfo(
        index = index,
        name = Sources.defaultName(index),
        enabled = index in 0..5 || index in 12..13 || index in Sources.SA..Sources.SH,
        raw = 0,
        logic = 0,
        output = 0,
    )
}

internal fun sampleLive(): LiveSnapshot = LiveSnapshot.of(
    logic = mapOf(
        SourceId.RUD to 12,
        SourceId.ELE to -35,
        SourceId.THR to -62,
        SourceId.AIL to 100,
        SourceId(Sources.P1) to 40,
        SourceId(Sources.S1) to -20,
        SourceId.SA to -100,
        SourceId.SB to 0,
        SourceId.SC to 100,
    ),
    channels = listOf(12, -35, -62, 100, 0, 0, 25, -50),
    trims = Trims.ZERO,
    status = RadioStatus(linkQuality = 92, rssiDbm = -63, txVoltage = 7.7f, rxVoltage = 7.9f),
)

internal fun sampleElrsItems(): List<ElrsMenuItem> = listOf(
    ElrsMenuItem(1, 0, 3, ElrsItemType.TEXT_SELECTION, name = "Packet Rate", options = listOf("50Hz", "100Hz Full", "150Hz", "250Hz", "333Hz Full", "500Hz")),
    ElrsMenuItem(2, 0, 0, ElrsItemType.TEXT_SELECTION, name = "Telem Ratio", options = listOf("Std", "Off", "1:128", "1:64")),
    ElrsMenuItem(3, 0, 1, ElrsItemType.TEXT_SELECTION, name = "Switch Mode", options = listOf("Wide", "Hybrid")),
    ElrsMenuItem(4, 0, 0, ElrsItemType.TEXT_SELECTION, name = "Model Match", options = listOf("Off", "On")),
    ElrsMenuItem(5, 0, 0, ElrsItemType.FOLDER, name = "TX Power"),
    ElrsMenuItem(6, 5, 3, ElrsItemType.TEXT_SELECTION, name = "Max Power", options = listOf("10", "25", "50", "100", "250"), units = "mW"),
    ElrsMenuItem(7, 5, 11, ElrsItemType.UINT8, name = "Channel"),
    ElrsMenuItem(8, 0, 0, ElrsItemType.COMMAND, name = "Enable WiFi"),
    ElrsMenuItem(9, 0, 0, ElrsItemType.COMMAND, name = "Bind"),
    ElrsMenuItem(10, 0, 0, ElrsItemType.INFO, name = "Bad/Good", options = listOf("0/250")),
)

internal fun sampleDiagnosticsData(): DiagnosticsData {
    val channels = List(16) { OutputChannelConfig(channel = it, mixCount = if (it < 6) 1 + it % 3 else 0, output = (it * 13) % 120 - 60) }
    return DiagnosticsData(
        capturedMs = 1_795_084_200_000L,
        sdkVersion = "Oct  8 2026 16:07:03",
        isSimulator = false,
        startup = StartupState.Running,
        connection = ConnectionState.Ready,
        firmware = SampleFirmware,
        system = SampleSystem,
        status = RadioStatus(linkQuality = 92, rssiDbm = -63, lostCounter = 3, txVoltage = 7.7f, rxVoltage = 7.9f),
        sources = sampleSources(),
        channels = channels,
        models = listOf(ModelInfo(name = "Glider", index = 1001), ModelInfo(name = "Quad", index = 1002)),
        currentModelRaw = 1,
        currentModel = ModelInfo(name = "Quad", index = 1002),
        timers = List(3) { TimerConfig(index = it, mode = 1, startTime = 300, switch = SwitchCondition(SourceId.SA, CompareOp.EQUAL, 100)) },
        counters = List(3) { TimerConfig(index = it, mode = 2, startTime = 10) },
        freeDualRateSlots = List(16) { if (it % 2 == 0) 1 else null },
        enabledDualRateSlots = List(16) { it % 3 },
        curveProbe = CurveProbe(
            curveIndex = 2,
            type = CurveType.EXPO,
            rows = CurveProbe.INPUTS.map { CurveProbeRow(it, it * 0.6, it * 0.6) },
        ),
        elrs = ElrsProbe("AX12 Internal ELRS", 18, mapOf(9 to 7, 11 to 3, 13 to 3, 12 to 2, 0 to 3)),
        crsf = CrsfStats(
            totalFrames = 18_204,
            framesByType = mapOf(0x14 to 9_100L, 0x08 to 3_040L, 0x07 to 1_520L, 0x09 to 1_520L, 0x21 to 810L, 0x02 to 3_040L),
            crcErrors = 2,
            lastFrameAgeMs = 38,
        ),
        tts = TtsStatus.Ready("com.google.android.tts", "en-US"),
        storage = SampleStorage,
        observedSwitches = mapOf(Sources.SA to setOf(-100, 0, 100), Sources.SB to setOf(-100, 100), Sources.SC to setOf(0)),
        log = listOf(
            DiagnosticEntry(1_795_084_100_000L, "radio", "initial device read completed"),
            DiagnosticEntry(1_795_084_130_000L, "library", "reconcile: 2 models, 0 problems"),
            DiagnosticEntry(1_795_084_160_000L, "audio", "text-to-speech ready: com.google.android.tts en-US"),
            DiagnosticEntry(1_795_084_190_000L, "session", "save finished in 410 ms"),
        ),
    )
}

internal fun sampleDiagnosticsState(data: DiagnosticsData = sampleDiagnosticsData()) =
    DiagnosticsUiState(data = data, checks = AssumptionChecks.evaluate(data), curveSelection = 2)

internal val StockAppError = ConnectionState.Error(ConnectionError.DeviceTimeout())
