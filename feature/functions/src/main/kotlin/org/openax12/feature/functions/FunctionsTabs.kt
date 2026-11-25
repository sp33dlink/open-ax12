package org.openax12.feature.functions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.navigation.LogicalSwitchEditor
import org.openax12.core.navigation.SetupTabIds
import org.openax12.core.navigation.SpecialFunctionEditor
import org.openax12.core.navigation.VoiceAlertEditor
import org.openax12.feature.functions.common.OutcomeEffect
import org.openax12.feature.functions.flightmodes.FlightModesAction
import org.openax12.feature.functions.flightmodes.FlightModesScreen
import org.openax12.feature.functions.flightmodes.FlightModesViewModel
import org.openax12.feature.functions.logical.LogicalSwitchEditorScreen
import org.openax12.feature.functions.logical.LogicalSwitchEditorViewModel
import org.openax12.feature.functions.logical.LogicalSwitchesScreen
import org.openax12.feature.functions.logical.LogicalSwitchesViewModel
import org.openax12.feature.functions.sounds.SoundsCallbacks
import org.openax12.feature.functions.sounds.SoundsScreen
import org.openax12.feature.functions.sounds.SoundsViewModel
import org.openax12.feature.functions.sounds.VoiceAlertEditorScreen
import org.openax12.feature.functions.sounds.VoiceAlertViewModel
import org.openax12.feature.functions.special.SpecialFunctionEditorScreen
import org.openax12.feature.functions.special.SpecialFunctionEditorViewModel
import org.openax12.feature.functions.special.SpecialFunctionsScreen
import org.openax12.feature.functions.special.SpecialFunctionsViewModel
import org.openax12.feature.functions.wing.WingAction
import org.openax12.feature.functions.wing.WingScreen
import org.openax12.feature.functions.wing.WingViewModel
import java.util.UUID

// The stateful wrappers: they create the ViewModel from the app graph, collect its state and
// route navigation. All layout lives in the stateless screens.

/** A ViewModel built from [FunctionsDeps]; [key] separates instances of the same class (editors of different items). */
@Composable
private inline fun <reified VM : ViewModel> functionsViewModel(key: String? = null, crossinline create: (FunctionsDeps) -> VM): VM {
    val deps = rememberFunctionsDeps()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(deps) } })
}

/** A key that is new every time an editor is opened, so a finished editor is never resurrected from the view-model store. */
@Composable
private fun rememberOpenKey(prefix: String): String = rememberSaveable { prefix + UUID.randomUUID() }

@Composable
internal fun FlightModesTab() {
    val vm = functionsViewModel { FlightModesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    OutcomeEffect(state.outcome) { vm.onAction(FlightModesAction.ConsumeOutcome) }
    FlightModesScreen(
        state = state,
        onAction = vm::onAction,
        onOpenMixes = { navigator.openSetupTab(SetupTabIds.MIXES) },
    )
}

@Composable
internal fun WingTab() {
    val vm = functionsViewModel { WingViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    OutcomeEffect(state.outcome) { vm.onAction(WingAction.ConsumeOutcome) }
    WingScreen(state = state, onAction = vm::onAction)
}

@Composable
internal fun LogicalSwitchesTab() {
    val vm = functionsViewModel { LogicalSwitchesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LogicalSwitchesScreen(
        state = state,
        live = { live },
        onOpen = { navigator.navigate(LogicalSwitchEditor(it)) },
    )
}

@Composable
internal fun SpecialFunctionsTab() {
    val vm = functionsViewModel { SpecialFunctionsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    SpecialFunctionsScreen(
        state = state,
        active = { active },
        onOpen = { navigator.navigate(SpecialFunctionEditor(it)) },
        onSetEnabled = vm::setEnabled,
        onOpenChannelLock = { navigator.openSetupTab(SetupTabIds.CH_LOCK) },
    )
}

@Composable
internal fun SoundsTab() {
    val vm = functionsViewModel { SoundsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val callbacks = remember(vm, navigator) {
        SoundsCallbacks(
            onOpenAlert = { navigator.navigate(VoiceAlertEditor(it)) },
            onSetAlertEnabled = vm::setAlertEnabled,
            onTestAlert = vm::testAlert,
            onEditVario = vm::editVario,
            onResetVario = vm::resetVario,
            onTestVario = vm::testVario,
            onEditAnnouncements = vm::editAnnouncements,
        )
    }
    SoundsScreen(state = state, callbacks = callbacks)
}

@Composable
internal fun LogicalSwitchEditorEntry(index: Int) {
    val key = rememberOpenKey("ls$index-")
    val vm = functionsViewModel(key) { LogicalSwitchEditorViewModel(index, it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(state.finished) { if (state.finished) navigator.back() }
    LogicalSwitchEditorScreen(state = state, onAction = vm::onAction, onBack = { navigator.back() })
}

@Composable
internal fun SpecialFunctionEditorEntry(index: Int) {
    val key = rememberOpenKey("sf$index-")
    val vm = functionsViewModel(key) { SpecialFunctionEditorViewModel(index, it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(state.finished) { if (state.finished) navigator.back() }
    SpecialFunctionEditorScreen(
        state = state,
        onAction = vm::onAction,
        onBack = { navigator.back() },
        onOpenTab = { navigator.openSetupTab(it) },
    )
}

@Composable
internal fun VoiceAlertEditorEntry(alertId: String?) {
    val key = rememberOpenKey("va${alertId.orEmpty()}-")
    val vm = functionsViewModel(key) { VoiceAlertViewModel(alertId, it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(state.finished) { if (state.finished) navigator.back() }
    VoiceAlertEditorScreen(state = state, onAction = vm::onAction, onBack = { navigator.back() })
}
