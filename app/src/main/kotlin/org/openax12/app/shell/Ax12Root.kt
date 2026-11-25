package org.openax12.app.shell

import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import org.openax12.app.R
import org.openax12.core.designsystem.AxBottomBar
import org.openax12.core.designsystem.AxDestination
import org.openax12.core.designsystem.AxFlightModeChip
import org.openax12.core.designsystem.AxScreen
import org.openax12.core.designsystem.AxStatusBar
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.FixedCanvas
import org.openax12.core.designsystem.LocalAxToastState
import org.openax12.core.designsystem.TouchLockOverlay
import org.openax12.core.designsystem.UnsavedChangesDialog
import org.openax12.core.model.RadioBackend
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.navigation.LocalShellActions
import org.openax12.core.navigation.ModelImport
import org.openax12.core.navigation.ModelSetup
import org.openax12.core.navigation.Models
import org.openax12.core.navigation.PreFlightCheck
import org.openax12.core.navigation.ShellActions
import org.openax12.core.navigation.TopLevelDestination
import org.openax12.core.navigation.rememberNavigator
import org.openax12.core.radio.ConnectionError
import org.openax12.core.runtime.AppGraph
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.core.runtime.SessionResult
import org.openax12.core.runtime.StartupState
import org.openax12.core.widgets.LocalWidgetDataSource
import org.openax12.feature.functions.functionsEntries
import org.openax12.feature.functions.functionsSetupTabs
import org.openax12.feature.home.homeEntries
import org.openax12.feature.modelsetup.modelSetupEntries
import org.openax12.feature.modelsetup.modelSetupTabs
import org.openax12.feature.models.modelsEntries
import org.openax12.feature.system.systemEntries
import org.openax12.feature.telemetry.telemetryEntries

/**
 * The root of the UI: theme, the fixed 853x480 dp canvas, the composition locals every screen
 * reads (`LocalAppGraph`, `LocalShellActions`, `LocalWidgetDataSource`), the startup gate and the
 * touch lock.
 *
 * @param graph the application's service locator
 * @param window the activity window (brightness, keep-screen-on, screenshots)
 * @param shellActions activity-only actions offered to screens
 * @param pendingImport URI string of a shared / opened `.oax12` file that still has to be routed
 *   to `ModelImport`, or null
 * @param onImportHandled called once [pendingImport] was routed (or dropped)
 */
@Composable
internal fun Ax12Root(
    graph: AppGraph,
    window: Window,
    shellActions: ShellActions,
    pendingImport: String?,
    onImportHandled: () -> Unit,
) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val touchLock = rememberTouchLockState()
    AxTheme(settings.theme) {
        FixedCanvas {
            CompositionLocalProvider(
                LocalAppGraph provides graph,
                LocalShellActions provides shellActions,
                LocalWidgetDataSource provides remember(graph) { GraphWidgetDataSource(graph) },
            ) {
                Box(modifier = Modifier.fillMaxSize().trackTouches(touchLock)) {
                    StartupGate(
                        graph = graph,
                        window = window,
                        touchLock = touchLock,
                        pendingImport = pendingImport,
                        onImportHandled = onImportHandled,
                    )
                }
                TouchLockOverlay(
                    locked = touchLock.locked,
                    onUnlock = touchLock::unlock,
                    holdMillis = settings.touchLock.unlockHoldMs,
                )
                WindowSettingsEffect(window, graph, settings, touchLock.locked)
                TouchLockAutomation(touchLock, graph, settings)
            }
        }
    }
}

/**
 * Shows the loading or error screen until the radio is up, then the shell. The shell stays once it
 * was shown: a connection that breaks later raises a dialog over it instead of replacing the UI.
 * "Continue without radio" opens the shell on local data (model library, settings, help).
 */
@Composable
private fun StartupGate(
    graph: AppGraph,
    window: Window,
    touchLock: TouchLockState,
    pendingImport: String?,
    onImportHandled: () -> Unit,
) {
    val startup by graph.startup.collectAsStateWithLifecycle()
    var everRunning by remember { mutableStateOf(false) }
    var continueOffline by remember { mutableStateOf(false) }
    var dismissedFor by remember { mutableStateOf<ConnectionError?>(null) }
    LaunchedEffect(startup) {
        if (startup.isRunning) {
            everRunning = true
            continueOffline = false
            dismissedFor = null
        }
    }
    val retry: () -> Unit = {
        dismissedFor = null
        graph.scope.launch { graph.start() }
    }

    if (startup.isRunning || everRunning || continueOffline) {
        Shell(graph, window, touchLock, pendingImport, onImportHandled)
        val failed = startup as? StartupState.RadioFailed
        if (failed != null && !continueOffline && dismissedFor != failed.reason) {
            ConnectionLostDialog(reason = failed.reason, onRetry = retry, onDismiss = { dismissedFor = failed.reason })
        }
    } else {
        AxScreen(
            statusBar = { ShellStatusBar(graph, title = stringResource(R.string.app_name), onTitleClick = null) },
        ) {
            when (val state = startup) {
                is StartupState.RadioFailed -> StartupErrorContent(
                    reason = state.reason,
                    onRetry = retry,
                    onContinue = { continueOffline = true },
                )
                StartupState.ConnectingRadio -> StartupLoadingContent(R.string.startup_connecting)
                StartupState.LoadingLibrary -> StartupLoadingContent(R.string.startup_loading_library)
                else -> StartupLoadingContent(R.string.startup_starting)
            }
        }
    }
}

/** The navigation shell: status bar, `NavDisplay` with all feature entries, bottom bar, guard and popups. */
@Composable
private fun Shell(
    graph: AppGraph,
    window: Window,
    touchLock: TouchLockState,
    pendingImport: String?,
    onImportHandled: () -> Unit,
) {
    val navState = rememberNavigator()
    val navigator = navState.navigator
    val scope = rememberCoroutineScope()
    val toasts = LocalAxToastState.current
    val context = LocalContext.current

    // Every feature installs its own entries; model setup gets the merged, sorted tab list.
    val setupTabs = remember { (modelSetupTabs() + functionsSetupTabs()).sortedBy { it.order } }
    val provider = remember(setupTabs) {
        entryProvider<NavKey> {
            homeEntries()
            modelSetupEntries(setupTabs)
            functionsEntries()
            modelsEntries()
            telemetryEntries()
            systemEntries()
        }
    }

    // ---- Unsaved-changes guard (owned by the shell, CONTRACTS.md rule 6) -------------------------
    val dirty by graph.configSession.dirty.collectAsStateWithLifecycle()
    var leave by remember { mutableStateOf<LeaveRequest?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmWrite by remember { mutableStateOf(false) }
    DisposableEffect(navigator, dirty) {
        if (dirty) {
            navigator.guardEntries(protects = { it is ModelSetup }) { proceed ->
                leave = LeaveRequest(proceed)
                false
            }
        }
        onDispose { navigator.clearGuard() }
    }

    fun failureToast(result: SessionResult.Failure) {
        val text = if (result.detail.isBlank()) {
            context.getString(R.string.message_save_failed)
        } else {
            context.getString(R.string.message_save_failed_detail, result.detail)
        }
        toasts.show(text, AxToastKind.Error)
    }

    fun saveAndLeave(request: LeaveRequest) {
        scope.launch {
            saving = true
            val result = graph.configSession.save()
            saving = false
            when (result) {
                is SessionResult.Success -> {
                    leave = null
                    request.proceed()
                }
                is SessionResult.Failure -> failureToast(result)
            }
        }
    }

    // ---- Shared / opened .oax12 files -> ModelImport ----------------------------------------------
    LaunchedEffect(pendingImport) {
        val uri = pendingImport ?: return@LaunchedEffect
        navigator.switchTopLevel(TopLevelDestination.MODELS)
        if (navigator.current == Models) {
            navigator.navigate(ModelImport(uri))
        } else {
            // The unsaved-changes dialog took over; do not queue an import behind it.
            toasts.show(context.getString(R.string.message_import_blocked), AxToastKind.Warning)
        }
        onImportHandled()
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        val currentModel by graph.models.currentModel.collectAsStateWithLifecycle()
        val topLevel = navigator.currentTopLevel
        val appName = stringResource(R.string.app_name)
        val title = if (topLevel == TopLevelDestination.HOME) {
            currentModel?.name?.takeIf { it.isNotBlank() } ?: appName
        } else {
            stringResource(topLevel.toAx().labelRes)
        }

        AxScreen(
            statusBar = {
                ShellStatusBar(
                    graph = graph,
                    title = title,
                    onTitleClick = if (topLevel == TopLevelDestination.HOME) {
                        { navigator.switchTopLevel(TopLevelDestination.MODELS) }
                    } else {
                        null
                    },
                )
            },
            bottomBar = {
                AxBottomBar(
                    selected = topLevel.toAx(),
                    onSelect = { destination ->
                        val target = destination.toTopLevel()
                        if (target == null) touchLock.lock() else navigator.switchTopLevel(target)
                    },
                )
            },
        ) {
            NavDisplay(
                backStack = navState.backStack,
                onBack = { navigator.back() },
                entryProvider = provider,
                // No slide / fade between screens: cheaper on the radio's GPU and instantly readable.
                transitionSpec = { ContentTransform(EnterTransition.None, ExitTransition.None) },
                popTransitionSpec = { ContentTransform(EnterTransition.None, ExitTransition.None) },
                predictivePopTransitionSpec = { ContentTransform(EnterTransition.None, ExitTransition.None) },
            )
        }

        // Back on the root screen does nothing: the radio app is not left by accident.
        BackHandler(enabled = navigator.entries.size == 1) {}

        leave?.let { request ->
            UnsavedChangesDialog(
                saving = saving,
                onSave = {
                    if (graph.radio.live.value.status.isLinkUp) confirmWrite = true else saveAndLeave(request)
                },
                onDiscard = {
                    scope.launch {
                        saving = true
                        val result = graph.configSession.discard()
                        saving = false
                        when (result) {
                            is SessionResult.Success -> {
                                leave = null
                                request.proceed()
                            }
                            is SessionResult.Failure -> failureToast(result)
                        }
                    }
                },
                onCancel = { leave = null },
            )
            if (confirmWrite) {
                ConfirmDialog(
                    title = stringResource(R.string.unsaved_linked_title),
                    message = stringResource(R.string.unsaved_linked_message),
                    confirmLabel = stringResource(R.string.unsaved_linked_confirm),
                    onConfirm = {
                        confirmWrite = false
                        saveAndLeave(request)
                    },
                    onDismiss = { confirmWrite = false },
                )
            }
        }

        RuntimeOverlays(graph = graph, window = window, onOpenPreFlight = { navigator.navigate(PreFlightCheck) })
    }
}

/** A navigation the guard blocked; [proceed] performs it once the user decided to leave. */
private class LeaveRequest(val proceed: () -> Unit)

/**
 * The status bar over every screen: link, centre title (with the active flight mode), RX / TX
 * voltage, clock and the "SIM" badge while the simulator backend is active. Live values are read
 * in draw lambdas, so a frame at 25 Hz recomposes nothing.
 */
@Composable
private fun ShellStatusBar(graph: AppGraph, title: String, onTitleClick: (() -> Unit)?) {
    val live = graph.radio.live.collectAsStateWithLifecycle()
    val flightMode by graph.runtime.flightMode.collectAsStateWithLifecycle()
    AxStatusBar(
        title = title,
        linkQuality = { live.value.status.linkQuality },
        rxVoltage = { live.value.status.rxVoltage },
        txVoltage = { live.value.status.txVoltage },
        simulator = graph.activeBackend == RadioBackend.SIMULATOR,
        onTitleClick = onTitleClick,
        flightMode = flightMode.name.takeIf { it.isNotEmpty() }?.let { name -> { AxFlightModeChip(name) } },
    )
}

private fun TopLevelDestination.toAx(): AxDestination = when (this) {
    TopLevelDestination.SYSTEM -> AxDestination.SYSTEM
    TopLevelDestination.MODEL_SETUP -> AxDestination.MODEL_SETUP
    TopLevelDestination.MODELS -> AxDestination.MODELS
    TopLevelDestination.HOME -> AxDestination.HOME
    TopLevelDestination.TELEMETRY -> AxDestination.TELEMETRY
    TopLevelDestination.HELP -> AxDestination.HELP
}

/** The navigation target of a bottom-bar button; null for the touch lock, which is an action. */
private fun AxDestination.toTopLevel(): TopLevelDestination? = when (this) {
    AxDestination.SYSTEM -> TopLevelDestination.SYSTEM
    AxDestination.MODEL_SETUP -> TopLevelDestination.MODEL_SETUP
    AxDestination.MODELS -> TopLevelDestination.MODELS
    AxDestination.HOME -> TopLevelDestination.HOME
    AxDestination.TELEMETRY -> TopLevelDestination.TELEMETRY
    AxDestination.TOUCH_LOCK -> null
    AxDestination.HELP -> TopLevelDestination.HELP
}
