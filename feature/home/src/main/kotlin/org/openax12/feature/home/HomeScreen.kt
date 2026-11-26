package org.openax12.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.widgets.LocalWidgetDataSource
import org.openax12.core.widgets.WidgetDataSource

/**
 * The home screen as in the stock app: left stick pad and channel list, centre card, right stick
 * pad with the switches / knobs panel. Fills the area between the status bar and the bottom bar
 * (388 dp high on the 853 x 480 dp canvas); nothing scrolls.
 *
 * Stateless: the structure comes in [state], live values come in through the lambdas and are read
 * in the draw phase of the composables that show them, so a 25 Hz stream never recomposes the screen.
 *
 * @param live latest radio frame; read it only inside draw lambdas
 * @param widgetDataSource data of the pinned widgets on the flipped centre card
 */
@Composable
internal fun HomeScreen(
    state: HomeUiState,
    live: () -> LiveSnapshot,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    widgetDataSource: WidgetDataSource = LocalWidgetDataSource.current,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.width(PadWidth).fillMaxHeight()) {
            HomeStickPad(PadSide.LEFT, state.stickMode, live)
            Spacer(Modifier.height(6.dp))
            ChannelList(
                channels = state.visibleChannels,
                page = state.channelPage,
                onSelectPage = { onAction(HomeAction.SelectChannelPage(it)) },
                live = live,
            )
        }
        CenterCard(
            state = state,
            live = live,
            widgetDataSource = widgetDataSource,
            onAction = onAction,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        Column(modifier = Modifier.width(PadWidth).fillMaxHeight()) {
            HomeStickPad(PadSide.RIGHT, state.stickMode, live)
            Spacer(Modifier.height(6.dp))
            InputPanel(
                state = state.inputs,
                live = live,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
