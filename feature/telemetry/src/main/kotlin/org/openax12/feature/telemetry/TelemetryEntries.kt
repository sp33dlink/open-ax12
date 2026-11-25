package org.openax12.feature.telemetry

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.designsystem.LocalAxToastState
import org.openax12.core.engine.RenderMode
import org.openax12.core.engine.TemplateRenderer
import org.openax12.core.model.GpsFix
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorValue
import org.openax12.core.navigation.AlarmEditor
import org.openax12.core.navigation.FlightLogs
import org.openax12.core.navigation.LastPosition
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.navigation.LocalShellActions
import org.openax12.core.navigation.SensorList
import org.openax12.core.navigation.Telemetry
import org.openax12.core.navigation.TelemetryWidgetEditor
import org.openax12.core.runtime.AppGraph
import org.openax12.core.runtime.LocalAppGraph

/**
 * Installs the entries of the telemetry feature: [Telemetry], [TelemetryWidgetEditor],
 * [SensorList], [AlarmEditor], [LastPosition] and [FlightLogs] (CONTRACTS.md, "Feature entry
 * points"). Everything else in this module is internal.
 */
fun EntryProviderScope<NavKey>.telemetryEntries() {
    entry<Telemetry> { TelemetryEntry() }
    entry<TelemetryWidgetEditor> { key -> WidgetEditorEntry(key) }
    entry<SensorList> { SensorListEntry() }
    entry<AlarmEditor> { key -> AlarmEntry(key) }
    entry<LastPosition> { LastPositionEntry() }
    entry<FlightLogs> { FlightLogsEntry() }
}

/** Shows the messages of [flow] as toasts. */
@Composable
private fun ToastMessages(flow: Flow<UiMessage>) {
    val context = LocalContext.current
    val toasts = LocalAxToastState.current
    LaunchedEffect(flow) {
        flow.collect { toasts.show(it.resolve(context), if (it.isError) AxToastKind.Warning else AxToastKind.Info) }
    }
}

@Composable
private fun TelemetryEntry() {
    val graph = LocalAppGraph.current
    val navigator = LocalNavigator.current
    val vm: TelemetryViewModel = viewModel(factory = viewModelFactory { initializer { TelemetryViewModel(ModelExtrasStore(graph.models)) } })
    val state by vm.state.collectAsStateWithLifecycle()
    ToastMessages(vm.events)
    val navigation = remember(navigator) {
        TelemetryNavigation(
            onOpenSensors = { navigator.navigate(SensorList) },
            onOpenAlarms = { navigator.navigate(AlarmEditor()) },
            onOpenPosition = { navigator.navigate(LastPosition) },
            onOpenLogs = { navigator.navigate(FlightLogs) },
            onAddWidget = { pageId -> navigator.navigate(TelemetryWidgetEditor(pageId, null)) },
            onEditWidget = { pageId, widgetId -> navigator.navigate(TelemetryWidgetEditor(pageId, widgetId)) },
        )
    }
    TelemetryScreen(state = state, onAction = vm::onAction, navigation = navigation)
}

@Composable
private fun WidgetEditorEntry(key: TelemetryWidgetEditor) {
    val graph = LocalAppGraph.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val toasts = LocalAxToastState.current
    val vm: WidgetEditorViewModel = viewModel(
        key = "telemetry-widget-editor/${key.pageId}/${key.widgetId}",
        factory = viewModelFactory {
            initializer { WidgetEditorViewModel(ModelExtrasStore(graph.models), graph.telemetry.sensorList, key.pageId, key.widgetId) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.start() }
    DisposableEffect(vm) { onDispose { vm.discard() } }
    LaunchedEffect(vm) {
        vm.results.collect { result ->
            when (result) {
                WidgetEditorEvent.Saved, WidgetEditorEvent.Missing -> navigator.back()
                is WidgetEditorEvent.Message -> toasts.show(result.message.resolve(context), AxToastKind.Warning)
            }
        }
    }
    WidgetEditorScreen(state = state, onAction = vm::onAction, onCancel = { navigator.back() })
}

@Composable
private fun SensorListEntry() {
    val graph = LocalAppGraph.current
    val navigator = LocalNavigator.current
    val vm: SensorListViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SensorListViewModel(
                    store = ModelExtrasStore(graph.models),
                    sensorList = graph.telemetry.sensorList,
                    values = graph.telemetry.sensors,
                    units = graph.settings.settings.map { it.units }.distinctUntilChanged(),
                    resetMinMax = { graph.telemetry.resetMinMax(it) },
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberNowMs()
    SensorListScreen(
        state = state,
        onAction = vm::onAction,
        onBack = { navigator.back() },
        nowMs = now,
        liveValue = { id -> liveSensorValue(graph, id) },
    )
}

/** The value of a live sensor (input, channel), read in the row that shows it so only that row redraws. */
@Composable
private fun liveSensorValue(graph: AppGraph, id: SensorId): SensorValue? {
    val flow = remember(id) { graph.telemetry.sensor(id) }
    val value by flow.collectAsStateWithLifecycle(initialValue = graph.telemetry.value(id))
    return value
}

@Composable
private fun AlarmEntry(key: AlarmEditor) {
    val graph = LocalAppGraph.current
    val navigator = LocalNavigator.current
    val defaultVoice = stringResource(R.string.telemetry_alarm_default_voice)
    val vm: AlarmViewModel = viewModel(
        key = "telemetry-alarms/${key.alarmId}",
        factory = viewModelFactory {
            initializer {
                AlarmViewModel(
                    store = ModelExtrasStore(graph.models),
                    sensorList = graph.telemetry.sensorList,
                    activeAlarmIds = graph.runtime.activeAlarms.map { active -> active.map { it.alarm.id }.toSet() },
                    openId = key.alarmId,
                    soundFiles = { graph.audio.listSoundFiles().map { it.name } },
                    defaultVoiceText = defaultVoice,
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.start() }
    DisposableEffect(vm) { onDispose { vm.reset() } }
    LaunchedEffect(vm) {
        vm.results.collect { if (it == AlarmEvent.Close) navigator.back() }
    }
    AlarmScreen(
        state = state,
        onAction = vm::onAction,
        onBack = { navigator.back() },
        previewText = { text -> TemplateRenderer.render(text, graph.runtime.valueResolver(), RenderMode.DISPLAY) },
    )
}

@Composable
private fun LastPositionEntry() {
    val graph = LocalAppGraph.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val toasts = LocalAxToastState.current
    val vm: LastPositionViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                LastPositionViewModel(
                    lastKnown = graph.telemetry.lastKnownPosition,
                    home = graph.telemetry.homePosition,
                    units = graph.settings.settings.map { it.units }.distinctUntilChanged(),
                    setHome = { graph.telemetry.setHome(it) },
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val wallClock by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(10_000)
            value = System.currentTimeMillis()
        }
    }
    val fix = state.fix
    val label = stringResource(R.string.telemetry_position_marker)
    val copied = stringResource(R.string.telemetry_position_copied)
    val noApp = stringResource(R.string.telemetry_position_no_app)
    LastPositionScreen(
        state = state,
        wallClockMs = wallClock,
        onBack = { navigator.back() },
        onOpenMaps = { fix?.let { if (!openInMaps(context, it, label)) toasts.show(noApp, AxToastKind.Warning) } },
        onCopy = {
            fix?.let {
                copyText(context, GeoMath.formatDecimal(it.latitude, it.longitude))
                toasts.show(copied, AxToastKind.Success)
            }
        },
        onShare = { fix?.let { if (!shareText(context, shareMessage(it, label))) toasts.show(noApp, AxToastKind.Warning) } },
        onUseAsHome = vm::useAsHome,
    )
}

@Composable
private fun FlightLogsEntry() {
    val graph = LocalAppGraph.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val toasts = LocalAxToastState.current
    val shell = LocalShellActions.current
    val vm: FlightLogsViewModel = viewModel(
        factory = viewModelFactory { initializer { FlightLogsViewModel(graph.flightLogger, graph.models.storage) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val noApp = stringResource(R.string.telemetry_position_no_app)
    LaunchedEffect(vm) {
        vm.results.collect { result ->
            when (result) {
                is FlightLogsEvent.ShareReady -> if (!shareFile(context, result.uri)) toasts.show(noApp, AxToastKind.Warning)
                is FlightLogsEvent.Message -> toasts.show(result.message.resolve(context), AxToastKind.Warning)
            }
        }
    }
    FlightLogsScreen(
        state = state,
        onAction = vm::onAction,
        onBack = { navigator.back() },
        onRequestStoragePermission = shell.requestStoragePermission,
    )
}

// -- Intents ----------------------------------------------------------------------------------

private fun startSafely(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/** `geo:` URI that opens a maps app at [fix] with a marker called [label]. */
internal fun geoUri(latitude: Double, longitude: Double, label: String): Uri {
    val coordinates = String.format(java.util.Locale.ROOT, "%.6f,%.6f", latitude, longitude)
    return Uri.parse("geo:$coordinates?q=$coordinates(${Uri.encode(label)})")
}

private fun openInMaps(context: Context, fix: GpsFix, label: String): Boolean =
    startSafely(context, Intent(Intent.ACTION_VIEW, geoUri(fix.latitude, fix.longitude, label)))

private fun shareMessage(fix: GpsFix, label: String): String {
    val coordinates = String.format(java.util.Locale.ROOT, "%.6f,%.6f", fix.latitude, fix.longitude)
    return "$label: ${GeoMath.formatDecimal(fix.latitude, fix.longitude)}\nhttps://maps.google.com/?q=$coordinates"
}

private fun shareText(context: Context, text: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    return startSafely(context, Intent.createChooser(send, null))
}

private fun shareFile(context: Context, uri: Uri): Boolean {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return startSafely(context, Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("position", text))
}
