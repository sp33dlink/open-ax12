package org.openax12.feature.home

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.designsystem.LocalAxToastState
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.navigation.Home
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.navigation.Models
import org.openax12.core.navigation.SetupTabIds
import org.openax12.core.runtime.LocalAppGraph

/** Installs the home screen ([Home]) into the app shell's entry provider. */
fun EntryProviderScope<NavKey>.homeEntries() {
    entry<Home> { HomeRoute() }
}

/**
 * Stateful wrapper of [HomeScreen]: creates the [HomeViewModel] from the app graph, turns the
 * live flows into snapshot state that only the draw phase reads, and performs navigation.
 */
@Composable
internal fun HomeRoute() {
    val graph = LocalAppGraph.current
    val navigator = LocalNavigator.current
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(graph))
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Hold the State objects, never their values: reading `.value` here would recompose the whole
    // screen on every frame. The lambdas below are read in draw phases.
    val liveState: State<LiveSnapshot> = graph.radio.live.collectAsStateWithLifecycle()
    val live = remember(liveState) { { liveState.value } }

    val context = LocalContext.current
    val view = LocalView.current
    val toasts = LocalAxToastState.current

    HomeScreen(
        state = state,
        live = live,
        onAction = { action ->
            when (action) {
                HomeAction.OpenModels -> navigator.navigate(Models)
                HomeAction.OpenTimerSettings -> navigator.openSetupTab(SetupTabIds.TIMERS)
                is HomeAction.ResetTimer -> {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val label = context.getString(R.string.home_timer_label, action.index + 1)
                    toasts.show(context.getString(R.string.home_timer_reset, label), AxToastKind.Info)
                    viewModel.onAction(action)
                }
                else -> viewModel.onAction(action)
            }
        },
    )
}
