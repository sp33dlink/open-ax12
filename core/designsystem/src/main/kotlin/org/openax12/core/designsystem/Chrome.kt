package org.openax12.core.designsystem

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Destinations of the bottom bar, in the order of SPEC section 1. The design system does not
 * know navigation keys; the app shell maps these to its keys.
 */
enum class AxDestination(val icon: ImageVector, @param:StringRes val labelRes: Int) {
    SYSTEM(AxIcons.System, R.string.ax_dest_system),
    MODEL_SETUP(AxIcons.ModelSetup, R.string.ax_dest_model_setup),
    MODELS(AxIcons.Models, R.string.ax_dest_models),
    HOME(AxIcons.Home, R.string.ax_dest_home),
    TELEMETRY(AxIcons.Telemetry, R.string.ax_dest_telemetry),
    TOUCH_LOCK(AxIcons.TouchLock, R.string.ax_dest_touch_lock),
    HELP(AxIcons.Help, R.string.ax_dest_help),
}

/**
 * Bottom bar with icon-only destinations as in the stock app. The selected destination sits in
 * a sunken well and is tinted with the accent colour.
 *
 * @param selected the destination of the current screen, or null if none of them
 * @param onSelect called for every tap, also on the selected one and on [AxDestination.TOUCH_LOCK]
 *   (which is an action, not a screen)
 * @param destinations which destinations to show, in order
 */
@Composable
fun AxBottomBar(
    selected: AxDestination?,
    onSelect: (AxDestination) -> Unit,
    modifier: Modifier = Modifier,
    destinations: List<AxDestination> = AxDestination.entries,
) {
    val colors = AxTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AxDimens.BottomBarHeight)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEach { destination ->
            val isSelected = destination == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val label = stringResource(destination.labelRes)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(destination) },
                    )
                    .semantics { this.selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected || pressed) {
                    Box(
                        modifier = Modifier
                            .size(width = 64.dp, height = 40.dp)
                            .axSunken(cornerRadius = 20.dp, depth = 2.dp),
                    )
                }
                AxIcon(
                    icon = destination.icon,
                    contentDescription = label,
                    tint = if (isSelected) colors.accent else colors.text,
                    size = 28.dp,
                )
            }
        }
    }
}

/**
 * Screen scaffold of the app shell: status bar on top, bottom bar below, [content] in between
 * (388 dp high with the default bars). The background comes from [FixedCanvas].
 */
@Composable
fun AxScreen(
    modifier: Modifier = Modifier,
    statusBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        statusBar()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            content = content,
        )
        bottomBar()
    }
}

/**
 * One tab of the model-setup chrome.
 *
 * @property id stable id reported by `onTabSelected`
 * @property label tab text
 */
@Immutable
data class SetupTabItem(val id: String, val label: String)

/**
 * Chrome of the model-setup screens, as in `stock-mixes.png`: dark navy bar with a back arrow, a
 * scrollable tab row whose selected tab is a light pill with rounded top corners, and an outlined
 * Save button with a "dirty" dot; below it an optional dark column-header row.
 *
 * @param tabs all tabs in display order
 * @param selectedTabId id of the selected tab
 * @param onTabSelected called with the id of a tapped tab
 * @param onBack back arrow
 * @param onSave Save button
 * @param dirty shows the dot on Save (unsaved changes)
 * @param saving shows the "saving" label and disables Save
 * @param saveEnabled enables Save
 * @param onHelp shows a help icon at the end of the column-header row
 * @param columnHeader content of the column-header row, see [SetupHeaderCell]; null hides the row
 * @param content the selected tab
 */
@Composable
fun SetupScaffold(
    tabs: List<SetupTabItem>,
    selectedTabId: String?,
    onTabSelected: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    dirty: Boolean = false,
    saving: Boolean = false,
    saveEnabled: Boolean = true,
    onHelp: (() -> Unit)? = null,
    columnHeader: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = AxTheme.colors
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AxDimens.SetupBarHeight)
                .background(colors.chrome),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AxIconButton(
                icon = AxIcons.Back,
                contentDescription = stringResource(R.string.ax_back),
                onClick = onBack,
                tint = colors.onChrome,
                modifier = Modifier.padding(start = 4.dp),
            )
            SetupTabRow(
                tabs = tabs,
                selectedTabId = selectedTabId,
                onTabSelected = onTabSelected,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            SaveButton(
                dirty = dirty,
                saving = saving,
                enabled = saveEnabled && !saving,
                onClick = onSave,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
            )
        }
        if (columnHeader != null) {
            SetupColumnHeader(onHelp = onHelp, content = columnHeader)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
private fun SetupTabRow(
    tabs: List<SetupTabItem>,
    selectedTabId: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AxTheme.colors
    val listState = rememberLazyListState()
    val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val info = visible.firstOrNull { it.index == selectedIndex }
            val viewportEnd = listState.layoutInfo.viewportEndOffset
            val fullyVisible = info != null && info.offset >= 0 && info.offset + info.size <= viewportEnd
            if (!fullyVisible) listState.animateScrollToItem((selectedIndex - 1).coerceAtLeast(0))
        }
    }
    LazyRow(
        state = listState,
        modifier = modifier.drawWithContent {
            drawContent()
            // Fade at the edges that can still scroll.
            val fade = 22.dp.toPx()
            if (listState.canScrollBackward) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(colors.chrome, Color.Transparent), startX = 0f, endX = fade),
                    size = Size(fade, size.height),
                )
            }
            if (listState.canScrollForward) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, colors.chrome),
                        startX = size.width - fade,
                        endX = size.width,
                    ),
                    topLeft = Offset(size.width - fade, 0f),
                    size = Size(fade, size.height),
                )
            }
        },
        verticalAlignment = Alignment.Bottom,
    ) {
        itemsIndexed(tabs, key = { _, tab -> tab.id }) { _, tab ->
            val selected = tab.id == selectedTabId
            Box(
                modifier = Modifier
                    .fillParentMaxHeight()
                    .padding(top = 6.dp)
                    .then(
                        if (selected) {
                            Modifier.background(colors.chromeSelected, RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onTabSelected(tab.id) },
                    )
                    .semantics { this.selected = selected }
                    .padding(horizontal = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    style = AxTheme.typography.chrome,
                    fontWeight = if (selected) FontWeight.Medium else null,
                    color = if (selected) colors.onChromeSelected else colors.onChrome,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SaveButton(
    dirty: Boolean,
    saving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(AxDimens.SetupBarHeight)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .drawBehind {
                    val b = 1.5.dp.toPx()
                    val r = CornerRadius(4.dp.toPx())
                    if (pressed) drawRoundRect(colors.onChrome.copy(alpha = 0.18f), cornerRadius = r)
                    drawRoundRect(
                        color = colors.onChrome.copy(alpha = if (enabled) 1f else 0.45f),
                        topLeft = Offset(b / 2f, b / 2f),
                        size = Size(size.width - b, size.height - b),
                        cornerRadius = r,
                        style = Stroke(width = b),
                    )
                    if (dirty) {
                        // Unsaved-changes dot on the top-right corner.
                        val c = Offset(size.width - 1.dp.toPx(), 1.dp.toPx())
                        drawCircle(colors.chrome, radius = 6.5.dp.toPx(), center = c)
                        drawCircle(colors.orange, radius = 4.5.dp.toPx(), center = c)
                    }
                }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(if (saving) R.string.ax_saving else R.string.ax_save),
                style = AxTheme.typography.chrome,
                color = colors.onChrome.copy(alpha = if (enabled) 1f else 0.45f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SetupColumnHeader(onHelp: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    val colors = AxTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 3.dp)
            .height(AxDimens.SetupHeaderHeight)
            .drawWithCache {
                val brush = Brush.verticalGradient(listOf(colors.chromeRaised, colors.chrome))
                val r = CornerRadius(5.dp.toPx())
                onDrawBehind { drawRoundRect(brush, cornerRadius = r) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onChrome) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
            if (onHelp != null) {
                AxIconButton(
                    icon = AxIcons.HelpOutline,
                    contentDescription = stringResource(R.string.ax_dest_help),
                    onClick = onHelp,
                    tint = colors.onChrome,
                    size = AxDimens.SetupHeaderHeight + 6.dp,
                )
            }
        }
    }
}

/**
 * One column title inside the column-header row of [SetupScaffold]. Give it the same width or
 * weight modifier as the column below.
 */
@Composable
fun SetupHeaderCell(text: String, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    Box(modifier = modifier.fillMaxHeight(), contentAlignment = alignment) {
        Text(
            text = text,
            style = AxTheme.typography.chrome,
            color = AxTheme.colors.onChrome,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Spacer that reproduces the gap between two [SetupHeaderCell]s. */
@Composable
fun SetupHeaderGap(width: androidx.compose.ui.unit.Dp = 6.dp) {
    Spacer(Modifier.width(width))
}
