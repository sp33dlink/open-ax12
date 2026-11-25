package org.openax12.feature.functions.special

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.model.SPECIAL_FUNCTION_COUNT
import org.openax12.core.model.SpecialFunction
import org.openax12.feature.functions.R
import org.openax12.feature.functions.common.BannerTone
import org.openax12.feature.functions.common.Caption
import org.openax12.feature.functions.common.InfoBanner
import org.openax12.feature.functions.common.LiveDot
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.ScreenPadding
import org.openax12.feature.functions.common.describeAction
import org.openax12.feature.functions.common.describeTrigger

/**
 * The "Special functions" tab: the configured functions as rows (trigger → action) with an
 * enable switch and a live dot, an "Add" button for the next free slot, and a notice that the
 * channel-affecting EdgeTX functions are deliberately missing.
 *
 * @param active read inside the dots only
 * @param onOpenChannelLock jumps to the channel-lock tab
 */
@Composable
internal fun SpecialFunctionsScreen(
    state: SpecialFunctionsState,
    active: () -> Set<Int>,
    onOpen: (Int) -> Unit,
    onSetEnabled: (Int, Boolean) -> Unit,
    onOpenChannelLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption(
                text = stringResource(R.string.fn_sf_count, state.used, SPECIAL_FUNCTION_COUNT),
                modifier = Modifier.weight(1f),
            )
            AxButton(
                text = stringResource(R.string.fn_sf_add),
                onClick = { state.nextFree?.let(onOpen) },
                icon = AxIcons.Add,
                enabled = state.nextFree != null,
            )
        }
        if (state.functions.isEmpty()) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                AxEmptyState(
                    title = stringResource(R.string.fn_sf_empty_title),
                    message = stringResource(R.string.fn_sf_empty_message),
                    icon = AxIcons.Function,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ChannelNotice(onOpenChannelLock)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.functions, key = { it.index }) { function ->
                    FunctionRow(
                        function = function,
                        data = state.pickers,
                        active = active,
                        onClick = { onOpen(function.index) },
                        onSetEnabled = { onSetEnabled(function.index, it) },
                    )
                }
                item(key = "notice") { ChannelNotice(onOpenChannelLock) }
            }
        }
    }
}

@Composable
private fun ChannelNotice(onOpenChannelLock: () -> Unit) {
    InfoBanner(
        text = stringResource(R.string.fn_sf_banner),
        tone = BannerTone.INFO,
        actionLabel = stringResource(R.string.fn_sf_open_chlock),
        onAction = onOpenChannelLock,
    )
}

@Composable
private fun FunctionRow(
    function: SpecialFunction,
    data: PickerData,
    active: () -> Set<Int>,
    onClick: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    val colors = AxTheme.colors
    AxCard(
        modifier = Modifier.fillMaxWidth().height(60.dp),
        cornerRadius = AxTheme.shapes.control + 2.dp,
        elevation = 3.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LiveDot(active = { function.enabled && function.index in active() })
            Text(
                text = function.label,
                style = AxTheme.typography.bodyStrong,
                color = if (function.enabled) colors.text else colors.textFaint,
                modifier = Modifier.width(44.dp),
                maxLines = 1,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = describeAction(function.action, data),
                    style = AxTheme.typography.body,
                    color = if (function.enabled) colors.text else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = (if (function.name.isNotBlank()) function.name + " · " else "") + describeTrigger(function.trigger, data),
                    style = AxTheme.typography.label,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AxSwitch(checked = function.enabled, onCheckedChange = onSetEnabled)
        }
    }
}
