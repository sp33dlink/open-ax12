package org.openax12.app.shell

import android.content.Context
import android.view.Window
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.openax12.app.R
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxDialog
import org.openax12.core.designsystem.AxDialogMessage
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.designsystem.LocalAxToastState
import org.openax12.core.model.Sources
import org.openax12.core.runtime.AppGraph
import org.openax12.core.runtime.RuntimeMessages
import org.openax12.core.runtime.RuntimeUiRequest

/**
 * Everything the runtime engine asks the shell to show (`CONTRACTS.md`, "What the app shell
 * owns"): messages as toasts, alarm popups, the screenshot special function and the pre-flight
 * check popup (from the retained `RuntimeEngine.preFlightCheck`, so that an alarm raised before
 * the UI collected is still shown).
 *
 * @param onOpenPreFlight opens the pre-flight check screen
 */
@Composable
internal fun RuntimeOverlays(graph: AppGraph, window: Window, onOpenPreFlight: () -> Unit) {
    val context = LocalContext.current
    val toasts = LocalAxToastState.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val alarms = remember { mutableStateListOf<RuntimeUiRequest.AlarmPopup>() }

    LaunchedEffect(graph, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            graph.runtime.uiRequests.collect { request ->
                when (request) {
                    is RuntimeUiRequest.AlarmPopup -> if (alarms.lastOrNull()?.text != request.text) alarms += request
                    is RuntimeUiRequest.Message -> {
                        val (text, kind) = messageOf(context, request)
                        toasts.show(text, kind)
                    }
                    RuntimeUiRequest.Screenshot -> {
                        val file = ScreenshotCapture.capture(window, graph.models.storage.value.libraryRoot, graph.dispatchers.io)
                        if (file != null) {
                            toasts.show(context.getString(R.string.message_screenshot_saved), AxToastKind.Success)
                        } else {
                            toasts.show(context.getString(R.string.message_screenshot_failed), AxToastKind.Error)
                        }
                    }
                    // Only a trigger; the retained state below drives the popup.
                    is RuntimeUiRequest.PreFlightCheck -> Unit
                }
            }
        }
    }

    // An alarm that cleared on its own takes its popup with it.
    val activeAlarms by graph.runtime.activeAlarms.collectAsStateWithLifecycle()
    LaunchedEffect(activeAlarms.isEmpty()) { if (activeAlarms.isEmpty()) alarms.clear() }

    alarms.firstOrNull()?.let { popup ->
        val acknowledge = {
            graph.runtime.acknowledgeAlarms()
            alarms.clear()
        }
        AxDialog(
            onDismissRequest = acknowledge,
            title = stringResource(R.string.alarm_popup_title),
            icon = AxIcons.Warning,
            iconTint = AxTheme.colors.warning,
            buttons = { AxButton(text = stringResource(R.string.alarm_popup_ok), onClick = acknowledge, variant = AxButtonVariant.Primary) },
        ) {
            AxDialogMessage(popup.text)
        }
    }

    val preFlight by graph.runtime.preFlightCheck.collectAsStateWithLifecycle()
    var hiddenFor by remember { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(preFlight == null) { if (preFlight == null) hiddenFor = null }
    preFlight?.takeIf { it != hiddenFor }?.let { states ->
        val names = remember(states) { failingSourceNames(graph, states) }
        AxDialog(
            onDismissRequest = { hiddenFor = states },
            title = stringResource(R.string.preflight_title),
            icon = AxIcons.Warning,
            iconTint = AxTheme.colors.warning,
            dismissOnOutsideClick = false,
            buttons = {
                AxButton(
                    text = stringResource(R.string.preflight_skip),
                    onClick = { scope.launch { graph.runtime.skipPreFlightCheck() } },
                    variant = AxButtonVariant.Outlined,
                )
                AxButton(
                    text = stringResource(R.string.preflight_open),
                    onClick = {
                        hiddenFor = states
                        onOpenPreFlight()
                    },
                    variant = AxButtonVariant.Primary,
                )
            },
        ) {
            AxDialogMessage(stringResource(R.string.preflight_message, names))
        }
    }
}

/** Names (the user's own, else the default) of the sources whose state is non-zero, comma separated. */
private fun failingSourceNames(graph: AppGraph, states: List<Int>): String {
    val sources = graph.configSession.workingConfig.value.sources
    return states.withIndex()
        .filter { it.value != 0 }
        .joinToString(", ") { (index, _) ->
            sources.firstOrNull { it.index == index }?.name?.takeIf { it.isNotBlank() } ?: Sources.defaultName(index)
        }
}

private fun messageOf(context: Context, request: RuntimeUiRequest.Message): Pair<String, AxToastKind> {
    @StringRes val res: Int
    val kind: AxToastKind
    when (request.messageKey) {
        RuntimeMessages.LOGGING_STARTED -> { res = R.string.message_logging_started; kind = AxToastKind.Info }
        RuntimeMessages.LOGGING_STOPPED -> { res = R.string.message_logging_stopped; kind = AxToastKind.Info }
        RuntimeMessages.LOGGING_FAILED -> { res = R.string.message_logging_failed; kind = AxToastKind.Error }
        RuntimeMessages.TELEMETRY_LOST -> { res = R.string.message_telemetry_lost; kind = AxToastKind.Warning }
        RuntimeMessages.TELEMETRY_RECOVERED -> { res = R.string.message_telemetry_recovered; kind = AxToastKind.Success }
        RuntimeMessages.LOW_TX_VOLTAGE -> { res = R.string.message_low_tx_voltage; kind = AxToastKind.Warning }
        RuntimeMessages.FLIGHT_RESET -> { res = R.string.message_flight_reset; kind = AxToastKind.Info }
        RuntimeMessages.SOUND_FILE_MISSING -> { res = R.string.message_sound_file_missing; kind = AxToastKind.Warning }
        else -> return request.messageKey to AxToastKind.Info
    }
    return context.getString(res, request.argument) to kind
}
