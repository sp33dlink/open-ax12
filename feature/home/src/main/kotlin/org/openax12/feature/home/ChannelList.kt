package org.openax12.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.ChannelRow
import org.openax12.core.model.LiveSnapshot

/**
 * The channel list: eight rows with a centre-zero bar and the percentage, plus a pager for the
 * other eight channels. The whole block is one large touch target that switches the page (a tap
 * or a horizontal swipe); the two chips under the list only show which page is up.
 * Channel values are read in the draw phase of each row.
 */
@Composable
internal fun ChannelList(
    channels: List<ChannelItem>,
    page: Int,
    onSelectPage: (Int) -> Unit,
    live: () -> LiveSnapshot,
    modifier: Modifier = Modifier,
) {
    val first = page * CHANNELS_PER_PAGE
    val description = stringResource(R.string.home_cd_channel_pager, first + 1, first + CHANNELS_PER_PAGE)
    val other = (page + 1) % CHANNEL_PAGES
    Column(
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(page) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (drag < -SWIPE_THRESHOLD_PX) onSelectPage((page + 1).coerceAtMost(CHANNEL_PAGES - 1))
                        if (drag > SWIPE_THRESHOLD_PX) onSelectPage((page - 1).coerceAtLeast(0))
                    },
                    onHorizontalDrag = { _, delta -> drag += delta },
                )
            }
            .clickable(role = Role.Button) { onSelectPage(other) },
    ) {
        for (item in channels) {
            ChannelRow(
                label = stringResource(R.string.home_channel_label, item.index + 1),
                value = { live().channel(item.index).toFloat() },
                emphasized = item.emphasized,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (p in 0 until CHANNEL_PAGES) PageChip(
                text = stringResource(R.string.home_channel_range, p * CHANNELS_PER_PAGE + 1, (p + 1) * CHANNELS_PER_PAGE),
                selected = p == page,
            )
        }
    }
}

private const val SWIPE_THRESHOLD_PX = 40f

/** A small label that is filled while it is the page shown. */
@Composable
private fun PageChip(text: String, selected: Boolean) {
    val colors = AxTheme.colors
    Text(
        text = text,
        style = AxTheme.typography.labelSmall,
        color = if (selected) colors.onAccent else colors.textMuted,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .drawBehind { if (selected) drawRect(colors.accent) }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
