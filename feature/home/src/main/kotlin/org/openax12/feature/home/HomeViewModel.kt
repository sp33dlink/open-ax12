package org.openax12.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.data.ModelRepository
import org.openax12.core.model.HomeCenterCard
import org.openax12.core.model.HomePreferences
import org.openax12.core.runtime.AppGraph
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.RuntimeEngine

/** What the user can do on the home screen. Navigation actions are handled by the entry, the rest by the [HomeViewModel]. */
internal sealed interface HomeAction {

    /** Tap on the model name: open the Models screen. */
    data object OpenModels : HomeAction

    /** Flip the centre card between the model picture and the pinned telemetry widgets. */
    data object ToggleCenterCard : HomeAction

    /** Show the channel list page [page] (0 = CH01-08). */
    data class SelectChannelPage(val page: Int) : HomeAction

    /** Show input panel page [page]. */
    data class SelectInputPage(val page: Int) : HomeAction

    /** Open the timer settings (tap on a timer row or its gear). */
    data object OpenTimerSettings : HomeAction

    /** Reset timer [index] (long-press); the firmware has no manual start / stop. */
    data class ResetTimer(val index: Int) : HomeAction
}

/**
 * State holder of the home screen.
 *
 * It only derives the slowly changing structure of the screen ([HomeUiState]); live values are
 * read from `Radio.live` in the composables that draw them. Remembered choices (flipped centre
 * card, selected pages) are written to the extras of the current model at once and are not part
 * of the unsaved-changes state.
 */
internal class HomeViewModel(
    private val models: ModelRepository,
    configSession: ConfigSession,
    private val runtime: RuntimeEngine,
) : ViewModel() {

    /** The screen state; stays alive for 5 s after the last collector left so that rotation-like restarts are free. */
    val state: StateFlow<HomeUiState> = combine(
        models.currentModel,
        models.currentExtras,
        configSession.workingConfig,
        ::buildHomeState,
    )
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    /** Handles the actions that change state; navigation actions are ignored here. */
    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.ToggleCenterCard -> updateHome {
                it.copy(
                    centerCard = if (it.centerCard == HomeCenterCard.PICTURE) HomeCenterCard.WIDGETS else HomeCenterCard.PICTURE,
                )
            }
            is HomeAction.SelectChannelPage -> updateHome { it.copy(channelPage = action.page.coerceIn(0, CHANNEL_PAGES - 1)) }
            is HomeAction.SelectInputPage -> updateHome { it.copy(inputPage = action.page.coerceAtLeast(0)) }
            is HomeAction.ResetTimer -> viewModelScope.launch { runtime.resetTimer(action.index) }
            HomeAction.OpenModels, HomeAction.OpenTimerSettings -> Unit
        }
    }

    private fun updateHome(transform: (HomePreferences) -> HomePreferences) {
        viewModelScope.launch { models.updateExtras { it.copy(home = transform(it.home)) } }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Factory that takes the dependencies from the app graph. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(graph.models, graph.configSession, graph.runtime) }
        }
    }
}
