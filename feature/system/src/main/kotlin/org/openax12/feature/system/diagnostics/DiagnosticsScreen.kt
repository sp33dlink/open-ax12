package org.openax12.feature.system.diagnostics

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.openax12.core.audio.TtsStatus
import org.openax12.core.designsystem.AxBadge
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxListRow
import org.openax12.core.designsystem.AxLiveText
import org.openax12.core.designsystem.AxSpinner
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.SetupTabItem
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.Sources
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.radio.ConnectionState
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.core.runtime.StartupState
import org.openax12.feature.system.R
import org.openax12.feature.system.common.FeedbackEffect
import org.openax12.feature.system.common.NoticeBox
import org.openax12.feature.system.common.NoticeKind
import org.openax12.feature.system.common.PanelColumn
import org.openax12.feature.system.common.SettingsGroup
import org.openax12.feature.system.common.SystemBar
import org.openax12.feature.system.common.ValueText
import org.openax12.feature.system.common.formatDuration
import org.openax12.feature.system.common.formatFirmwareVersion
import org.openax12.feature.system.common.formatHardwareVersion
import org.openax12.feature.system.common.graphViewModel
import org.openax12.feature.system.common.hexType
import org.openax12.feature.system.settings.InfoRow

internal object DiagTab {
    const val OVERVIEW = "overview"
    const val MONITOR = "monitor"
    const val CRSF = "crsf"
    const val CHECKS = "checks"
    const val LOG = "log"
}

/** Stateful entry content of `Diagnostics`. */
@Composable
internal fun DiagnosticsRoute() {
    val graph = LocalAppGraph.current
    val viewModel = graphViewModel(graph) {
        DiagnosticsViewModel(
            radio = it.radio,
            audio = it.audio,
            runtime = it.runtime,
            models = it.models,
            telemetry = it.telemetry,
            diagnostics = it.diagnostics,
            startup = it.startup,
            backend = it.activeBackend,
            io = it.dispatchers.io,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val live = graph.radio.live.collectAsStateWithLifecycle()
    val appVersion = remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
    val shareTitle = stringResource(R.string.diag_share_title)
    FeedbackEffect(viewModel.feedback)
    LaunchedEffect(viewModel) {
        viewModel.reports.collect { report ->
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "open-ax12 diagnostics")
                .putExtra(Intent.EXTRA_TEXT, report.text)
            try {
                context.startActivity(Intent.createChooser(send, shareTitle))
            } catch (e: ActivityNotFoundException) {
                // No app can take the text: the file in the logs folder (if saved) is still there.
            }
        }
    }
    DiagnosticsScreen(
        state = state,
        live = { live.value },
        onBack = { navigator.back() },
        onRefresh = viewModel::refresh,
        onExport = { viewModel.export(appVersion) },
        onRun = viewModel::run,
        onSelectCurve = viewModel::selectCurve,
        onClearLog = viewModel::clearLog,
    )
}

/** The diagnostics screen with its five tabs. Stateless. */
@Composable
internal fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    live: () -> LiveSnapshot,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onRun: (CheckAction) -> Unit,
    onSelectCurve: (Int) -> Unit,
    onClearLog: () -> Unit,
    initialTab: String = DiagTab.OVERVIEW,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val tabs = listOf(
        SetupTabItem(DiagTab.OVERVIEW, stringResource(R.string.diag_tab_overview)),
        SetupTabItem(DiagTab.MONITOR, stringResource(R.string.diag_tab_monitor)),
        SetupTabItem(DiagTab.CRSF, stringResource(R.string.diag_tab_crsf)),
        SetupTabItem(DiagTab.CHECKS, stringResource(R.string.diag_tab_checks)),
        SetupTabItem(DiagTab.LOG, stringResource(R.string.diag_tab_log)),
    )
    Column(modifier = Modifier.fillMaxSize()) {
        SystemBar(
            title = stringResource(R.string.diag_title),
            onBack = onBack,
            tabs = tabs,
            selectedTabId = tab,
            onTabSelected = { tab = it },
            trailing = {
                if (state.refreshing) AxSpinner(size = 20.dp, color = AxTheme.colors.onChrome)
                AxIconButton(AxIcons.Refresh, stringResource(R.string.diag_refresh), onRefresh, tint = AxTheme.colors.onChrome)
                AxIconButton(AxIcons.Share, stringResource(R.string.diag_export), onExport, tint = AxTheme.colors.onChrome)
            },
        )
        when (tab) {
            DiagTab.MONITOR -> MonitorTab(state.data, live)
            DiagTab.CRSF -> CrsfTab(state.data)
            DiagTab.CHECKS -> ChecksTab(state, onRun, onSelectCurve)
            DiagTab.LOG -> LogTab(state.data, onClearLog)
            else -> OverviewTab(state.data)
        }
    }
}

// -- Overview --------------------------------------------------------------------------------

@Composable
private fun startupText(startup: StartupState): String = when (startup) {
    StartupState.NotStarted -> stringResource(R.string.diag_startup_not_started)
    StartupState.ConnectingRadio -> stringResource(R.string.diag_startup_connecting)
    StartupState.LoadingLibrary -> stringResource(R.string.diag_startup_library)
    StartupState.Running -> stringResource(R.string.diag_startup_running)
    is StartupState.RadioFailed -> stringResource(R.string.diag_startup_failed, startup.reason.message)
}

@Composable
private fun ttsText(status: TtsStatus): String = when (status) {
    TtsStatus.Initializing -> stringResource(R.string.tts_initializing_short)
    is TtsStatus.Ready -> "${status.engine} · ${status.language}"
    TtsStatus.NoEngine -> stringResource(R.string.tts_none_short)
    is TtsStatus.LanguageMissing -> stringResource(R.string.tts_language_missing_short, status.language)
    is TtsStatus.Error -> status.message
}

@Composable
private fun OverviewTab(d: DiagnosticsData) {
    val none = stringResource(R.string.value_unknown)
    PanelColumn {
        val error = (d.connection as? ConnectionState.Error)?.reason
        if (error != null) {
            NoticeBox(
                text = if (error.mayBeStockAppConflict) {
                    stringResource(R.string.conn_error_stock, error.message)
                } else {
                    stringResource(R.string.conn_error, error.message)
                },
                kind = NoticeKind.Error,
            )
        }
        NoticeBox(stringResource(R.string.about_firmware_hint), kind = NoticeKind.Info)
        SettingsGroup(stringResource(R.string.diag_group_connection)) {
            InfoRow(stringResource(R.string.about_sdk_version), d.sdkVersion.ifEmpty { none })
            AxDivider()
            InfoRow(stringResource(R.string.about_backend), d.backend.name)
            AxDivider()
            InfoRow(stringResource(R.string.diag_startup), startupText(d.startup))
            AxDivider()
            InfoRow(stringResource(R.string.about_connection), d.connection.toString())
            val lastError = d.log.lastOrNull { it.source == "radio" }
            if (lastError != null) {
                AxDivider()
                InfoRow(stringResource(R.string.diag_last_error), lastError.message)
            }
            AxDivider()
            InfoRow(stringResource(R.string.diag_tts), ttsText(d.tts))
        }
        SettingsGroup(stringResource(R.string.group_firmware)) {
            val fw = d.firmware
            if (fw == null || fw.isEmpty) {
                InfoRow(stringResource(R.string.about_firmware), stringResource(R.string.about_not_reported))
            } else {
                InfoRow(stringResource(R.string.about_device), fw.deviceText.ifEmpty { none })
                AxDivider()
                InfoRow(stringResource(R.string.about_firmware), fw.firmwareVersionText.ifEmpty { formatFirmwareVersion(fw.firmwareVersion) })
                AxDivider()
                InfoRow(stringResource(R.string.diag_firmware_packed), "0x" + fw.firmwareVersion.toString(16) + "  /  " + formatHardwareVersion(fw.hardwareVersion))
                AxDivider()
                InfoRow(stringResource(R.string.diag_ids), "sys ${fw.systemId}  dev ${fw.deviceId}")
                AxDivider()
                InfoRow(stringResource(R.string.about_build), fw.buildTimeText.ifEmpty { fw.buildDate.toString() })
                AxDivider()
                InfoRow(stringResource(R.string.about_flight_time), formatDuration(fw.flightTimeSec))
            }
        }
        SettingsGroup(stringResource(R.string.diag_group_system)) {
            val s = d.system
            if (s == null) {
                InfoRow(stringResource(R.string.diag_system_config), none)
            } else {
                InfoRow("stickMode", s.stickMode.sdk.toString())
                AxDivider()
                InfoRow("rfModuleMode", s.rfModuleMode.sdk.toString())
                AxDivider()
                InfoRow("alarmVoltage (0.1 V)", s.alarmVoltageDeciVolt.toString())
                AxDivider()
                InfoRow("failsafeMode / crsfBaudRate", "${s.failsafeMode.sdk} / ${s.crsfBaudRate}")
            }
        }
        SettingsGroup(stringResource(R.string.diag_group_status)) {
            val st = d.status
            InfoRow("LQ / RSSI / lost", "${st.linkQuality}% / ${st.rssiDbm} dBm / ${st.lostCounter}")
            AxDivider()
            InfoRow("TX / RX V", "${st.txVoltage} / ${st.rxVoltage}")
            AxDivider()
            InfoRow("topKeyState / calibrationState", "${st.topKeyState} / ${st.calibrationState}")
        }
    }
}

// -- Monitor ---------------------------------------------------------------------------------

@Composable
private fun MonitorTab(d: DiagnosticsData, live: () -> LiveSnapshot) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AxCard(modifier = Modifier.weight(1.2f).fillMaxSize().padding(2.dp), clipContent = true) {
            Column {
                MonitorHeader("#", stringResource(R.string.diag_col_source), stringResource(R.string.diag_col_raw), stringResource(R.string.diag_col_logic), stringResource(R.string.diag_col_output))
                AxDivider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items((0 until Sources.ALL.last + 1).toList(), key = { it }) { index ->
                        val name = d.sources.firstOrNull { it.index == index }?.name?.ifBlank { null } ?: Sources.defaultName(index)
                        MonitorRow(
                            index = (index).toString(),
                            name = name,
                            a = { live().raw(org.openax12.core.model.SourceId(index)) },
                            b = { live().logicAt(index) },
                            c = { live().output(org.openax12.core.model.SourceId(index)) },
                            formatB = { it.toString() },
                        )
                    }
                }
            }
        }
        AxCard(modifier = Modifier.weight(1f).fillMaxSize().padding(2.dp), clipContent = true) {
            Column {
                MonitorHeader("#", stringResource(R.string.diag_col_channel), stringResource(R.string.diag_col_output), stringResource(R.string.diag_col_logic_value), "")
                AxDivider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items((0 until 16).toList(), key = { it }) { channel ->
                        MonitorRow(
                            index = (channel + 1).toString(),
                            name = "CH%02d".format(Locale.US, channel + 1),
                            a = { live().channel(channel) },
                            b = { (live().channelLogic(channel) * 10f).toInt() },
                            c = null,
                            formatB = { String.format(Locale.US, "%.1f", it / 10f) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorHeader(a: String, b: String, c: String, d: String, e: String) {
    Row(modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val style = AxTheme.typography.label
        val muted = AxTheme.colors.textMuted
        Text(a, style = style, color = muted, modifier = Modifier.width(28.dp))
        Text(b, style = style, color = muted, modifier = Modifier.weight(1f))
        Text(c, style = style, color = muted, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
        Text(d, style = style, color = muted, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
        Text(e, style = style, color = muted, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
    }
}

@Composable
private fun MonitorRow(
    index: String,
    name: String,
    a: () -> Int,
    b: () -> Int,
    c: (() -> Int)?,
    formatB: (Int) -> String,
) {
    Row(modifier = Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(index, style = AxTheme.typography.label, color = AxTheme.colors.textMuted, modifier = Modifier.width(28.dp))
        Text(name, style = AxTheme.typography.body, color = AxTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        AxLiveText(value = a, format = { it.toString() }, template = "-100", modifier = Modifier.width(56.dp))
        AxLiveText(value = b, format = formatB, template = "-100.0", modifier = Modifier.width(60.dp))
        if (c != null) {
            AxLiveText(value = c, format = { it.toString() }, template = "-100", modifier = Modifier.width(56.dp))
        } else {
            Text("", modifier = Modifier.width(56.dp))
        }
    }
}

// -- CRSF ------------------------------------------------------------------------------------

@Composable
private fun CrsfTab(d: DiagnosticsData) {
    val crsf = d.crsf
    PanelColumn {
        if (crsf.crcCheckDisabled) NoticeBox(stringResource(R.string.diag_crc_disabled), kind = NoticeKind.Warning)
        SettingsGroup(stringResource(R.string.diag_crsf_summary)) {
            InfoRow(stringResource(R.string.diag_crsf_total), String.format(Locale.US, "%,d", crsf.totalFrames))
            AxDivider()
            InfoRow(stringResource(R.string.diag_crsf_crc), crsf.crcErrors.toString())
            AxDivider()
            InfoRow(
                stringResource(R.string.diag_crsf_age),
                if (crsf.lastFrameAgeMs < 0) stringResource(R.string.diag_crsf_none) else "${crsf.lastFrameAgeMs} ms",
            )
            if (crsf.unknownRotorflightSids.isNotEmpty()) {
                AxDivider()
                InfoRow(stringResource(R.string.diag_crsf_unknown_sids), crsf.unknownRotorflightSids.sorted().joinToString(" ") { "0x" + it.toString(16) })
            }
        }
        SettingsGroup(stringResource(R.string.diag_crsf_by_type)) {
            if (crsf.framesByType.isEmpty()) {
                Text(
                    text = stringResource(R.string.diag_crsf_empty),
                    style = AxTheme.typography.body,
                    color = AxTheme.colors.textMuted,
                    modifier = Modifier.padding(12.dp),
                )
            }
            crsf.framesByType.toSortedMap().entries.forEachIndexed { position, (type, count) ->
                if (position > 0) AxDivider()
                AxListRow(
                    title = hexType(type) + "  " + DiagnosticsReport.crsfTypeName(type),
                    minHeight = 40.dp,
                    trailing = { ValueText(String.format(Locale.US, "%,d", count), color = AxTheme.colors.text) },
                )
            }
        }
    }
}

// -- Checks ----------------------------------------------------------------------------------

private val ASSUMPTION_TITLES: IntArray = intArrayOf(
    R.string.asm_1, R.string.asm_2, R.string.asm_3, R.string.asm_4, R.string.asm_5, R.string.asm_6, R.string.asm_7,
    R.string.asm_8, R.string.asm_9, R.string.asm_10, R.string.asm_11, R.string.asm_12, R.string.asm_13, R.string.asm_14,
    R.string.asm_15, R.string.asm_16, R.string.asm_17, R.string.asm_18, R.string.asm_19, R.string.asm_20, R.string.asm_21,
    R.string.asm_22, R.string.asm_23, R.string.asm_24, R.string.asm_25, R.string.asm_26, R.string.asm_27, R.string.asm_28,
)

@StringRes
private fun verdictLabel(verdict: Verdict): Int = when (verdict) {
    Verdict.OK -> R.string.verdict_ok
    Verdict.SUSPICIOUS -> R.string.verdict_suspicious
    Verdict.INFO -> R.string.verdict_info
    Verdict.NO_DATA -> R.string.verdict_no_data
    Verdict.MANUAL -> R.string.verdict_manual
}

@StringRes
private fun actionLabel(action: CheckAction): Int = when (action) {
    CheckAction.CURVE_PROBE -> R.string.diag_action_curve
    CheckAction.ELRS_PROBE -> R.string.diag_action_elrs
    CheckAction.TEST_VARIO -> R.string.diag_action_vario
    CheckAction.TEST_BEEPS -> R.string.diag_action_beeps
    CheckAction.TEST_HAPTIC -> R.string.diag_action_haptic
}

@Composable
private fun verdictColor(verdict: Verdict): Color {
    val colors = AxTheme.colors
    return when (verdict) {
        Verdict.OK -> colors.success
        Verdict.SUSPICIOUS -> colors.error
        Verdict.INFO -> colors.accent
        Verdict.NO_DATA -> colors.textMuted
        Verdict.MANUAL -> colors.warning
    }
}

@Composable
private fun ChecksTab(state: DiagnosticsUiState, onRun: (CheckAction) -> Unit, onSelect: (Int) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            NoticeBox(stringResource(R.string.diag_checks_intro))
        }
        items(state.checks, key = { it.number }) { check ->
            CheckCard(check, state, onRun, onSelect)
        }
    }
}

@Composable
private fun CheckCard(check: AssumptionCheck, state: DiagnosticsUiState, onRun: (CheckAction) -> Unit, onSelect: (Int) -> Unit) {
    val colors = AxTheme.colors
    AxCard(modifier = Modifier.fillMaxWidth().padding(2.dp), contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${check.number}",
                    style = AxTheme.typography.titleSmall,
                    color = colors.accent,
                    modifier = Modifier.width(40.dp),
                )
                Text(
                    text = ASSUMPTION_TITLES.getOrNull(check.number - 1)?.let { stringResource(it) }.orEmpty(),
                    style = AxTheme.typography.bodyStrong,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                AxBadge(text = stringResource(verdictLabel(check.verdict)), color = verdictColor(check.verdict))
            }
            check.lines.forEach { line ->
                Text(
                    text = line,
                    style = AxTheme.typography.label,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textMuted,
                    modifier = Modifier.padding(start = 40.dp),
                )
            }
            if (check.actions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(start = 40.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (CheckAction.CURVE_PROBE in check.actions) {
                        Text(stringResource(R.string.diag_curve_label), style = AxTheme.typography.label, color = colors.textMuted)
                        NumberField(
                            value = state.curveSelection + 1,
                            onValueChange = { onSelect(it - 1) },
                            min = 1,
                            max = 18,
                            modifier = Modifier.width(80.dp),
                            title = stringResource(R.string.diag_curve_label),
                        )
                    }
                    check.actions.forEach { action ->
                        AxButton(
                            text = stringResource(actionLabel(action)),
                            onClick = { onRun(action) },
                            variant = AxButtonVariant.Outlined,
                            enabled = action !in state.probing,
                        )
                    }
                }
            }
        }
    }
}

// -- Log -------------------------------------------------------------------------------------

@Composable
private fun LogTab(d: DiagnosticsData, onClear: () -> Unit) {
    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.diag_log_count, d.log.size),
                style = AxTheme.typography.label,
                color = AxTheme.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            AxButton(text = stringResource(R.string.diag_log_clear), onClick = onClear, variant = AxButtonVariant.Text, enabled = d.log.isNotEmpty())
        }
        if (d.log.isEmpty()) {
            AxEmptyState(
                title = stringResource(R.string.diag_log_empty),
                message = stringResource(R.string.diag_log_empty_hint),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                val entries = d.log.asReversed()
                itemsIndexed(entries) { _, entry ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            text = format.format(Date(entry.timeMs)),
                            style = AxTheme.typography.value,
                            color = AxTheme.colors.textMuted,
                            modifier = Modifier.width(66.dp),
                        )
                        Text(
                            text = entry.source,
                            style = AxTheme.typography.label,
                            color = AxTheme.colors.accent,
                            modifier = Modifier.width(66.dp),
                        )
                        Text(
                            text = entry.message,
                            style = AxTheme.typography.body,
                            color = AxTheme.colors.text,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AxDivider(inset = 0.dp)
                }
            }
        }
    }
}
