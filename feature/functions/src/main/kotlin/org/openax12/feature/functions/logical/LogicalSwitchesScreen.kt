package org.openax12.feature.functions.logical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.model.LogicalSwitch
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.feature.functions.R
import org.openax12.feature.functions.common.BannerTone
import org.openax12.feature.functions.common.InfoBanner
import org.openax12.feature.functions.common.LiveDot
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.ScreenPadding
import org.openax12.feature.functions.common.describeLogicalSwitch
import org.openax12.feature.functions.common.functionLabel

/**
 * The "Logical switches" tab: L1..L32 in two columns with a live on / off dot. The banner states
 * what logical switches are for: app actions and widgets, never mixes.
 *
 * @param live read inside the dots only, so a switch toggling does not recompose the list
 */
@Composable
internal fun LogicalSwitchesScreen(
    state: LogicalSwitchesState,
    live: () -> LogicalSwitchStates,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        InfoBanner(text = stringResource(R.string.fn_ls_banner), tone = BannerTone.INFO)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.slots, key = { it.index }) { ls ->
                SwitchCell(ls = ls, data = state.pickers, live = live, onClick = { onOpen(ls.index) })
            }
        }
    }
}

@Composable
private fun SwitchCell(ls: LogicalSwitch, data: PickerData, live: () -> LogicalSwitchStates, onClick: () -> Unit) {
    val colors = AxTheme.colors
    AxCard(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        cornerRadius = AxTheme.shapes.control + 2.dp,
        elevation = if (ls.isUsed) 3.dp else 1.5.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LiveDot(active = { ls.isUsed && live()[ls.index] })
            Text(
                text = ls.label,
                style = AxTheme.typography.bodyStrong,
                color = if (ls.isUsed) colors.text else colors.textFaint,
                modifier = Modifier.width(34.dp),
                maxLines = 1,
            )
            Column(modifier = Modifier.weight(1f)) {
                if (ls.isUsed) {
                    Text(
                        text = ls.name.ifBlank { functionLabel(ls.function) },
                        style = AxTheme.typography.body,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = describeLogicalSwitch(ls, data),
                        style = AxTheme.typography.label,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.fn_empty_tap),
                        style = AxTheme.typography.label,
                        color = colors.textFaint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
