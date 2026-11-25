package org.openax12.feature.telemetry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDialog
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTextField
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.NumberField
import org.openax12.core.model.TelemetryPage
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetType
import org.openax12.core.widgets.WidgetGrid
import org.openax12.core.widgets.WidgetGridGeometry

/** Where the telemetry screen can go. */
internal data class TelemetryNavigation(
    val onOpenSensors: () -> Unit = {},
    val onOpenAlarms: () -> Unit = {},
    val onOpenPosition: () -> Unit = {},
    val onOpenLogs: () -> Unit = {},
    /** Opens the widget editor for a new widget on the page. */
    val onAddWidget: (pageId: String) -> Unit = {},
    /** Opens the widget editor for an existing widget. */
    val onEditWidget: (pageId: String, widgetId: String) -> Unit = { _, _ -> },
)

/** A move or resize of a widget in progress; the offsets are in whole grid cells. */
private data class WidgetDrag(val id: String, val resize: Boolean, val dx: Int, val dy: Int)

/**
 * The telemetry screen: tab row of pages, the widget grid of the selected page, and in edit mode
 * the tools to add, move, resize, configure, pin and delete widgets.
 *
 * Stateless: everything comes from [state]; changes go out through [onAction] and [navigation].
 */
@Composable
internal fun TelemetryScreen(
    state: TelemetryUiState,
    onAction: (TelemetryAction) -> Unit,
    navigation: TelemetryNavigation,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TelemetryTabBar(state, onAction, navigation)
        val page = state.page
        if (page == null) {
            AxEmptyState(
                title = stringResource(R.string.telemetry_no_pages_title),
                message = stringResource(R.string.telemetry_no_pages_message),
                icon = AxIcons.Dashboard,
                actionLabel = stringResource(R.string.telemetry_add_page),
                onAction = { onAction(TelemetryAction.AddPage) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            PageBody(state, page, onAction, navigation, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TelemetryTabBar(state: TelemetryUiState, onAction: (TelemetryAction) -> Unit, navigation: TelemetryNavigation) {
    val colors = AxTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(org.openax12.core.designsystem.AxDimens.SetupBarHeight)
            .background(colors.chrome),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.pages.isEmpty()) {
            Text(
                text = stringResource(R.string.telemetry_title),
                style = AxTheme.typography.chrome,
                color = colors.onChrome,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            )
        } else {
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                    val selected = page.id == state.page?.id
                    val label = page.name.ifBlank { stringResource(R.string.telemetry_page_default_name, index + 1) }
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
                                onClick = { onAction(TelemetryAction.SelectPage(page.id)) },
                            )
                            .semantics { this.selected = selected }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = AxTheme.typography.chrome,
                            color = if (selected) colors.onChromeSelected else colors.onChrome,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (state.editMode) {
                    item(key = "add-page") {
                        AxIconButton(
                            icon = AxIcons.Add,
                            contentDescription = stringResource(R.string.telemetry_add_page),
                            onClick = { onAction(TelemetryAction.AddPage) },
                            tint = colors.onChrome,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            AxIconButton(AxIcons.Sensor, stringResource(R.string.telemetry_cd_sensors), navigation.onOpenSensors, tint = colors.onChrome)
            AxIconButton(AxIcons.Alarm, stringResource(R.string.telemetry_cd_alarms), navigation.onOpenAlarms, tint = colors.onChrome)
            AxIconButton(AxIcons.Gps, stringResource(R.string.telemetry_cd_position), navigation.onOpenPosition, tint = colors.onChrome)
            AxIconButton(AxIcons.History, stringResource(R.string.telemetry_cd_logs), navigation.onOpenLogs, tint = colors.onChrome)
            Spacer(Modifier.width(8.dp))
            ChromeButton(
                text = stringResource(if (state.editMode) R.string.telemetry_done else R.string.telemetry_edit),
                onClick = { onAction(TelemetryAction.SetEditMode(!state.editMode)) },
                icon = if (state.editMode) AxIcons.Check else AxIcons.Edit,
                filled = state.editMode,
                enabled = state.pages.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun PageBody(
    state: TelemetryUiState,
    page: TelemetryPage,
    onAction: (TelemetryAction) -> Unit,
    navigation: TelemetryNavigation,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = if (state.editMode) 4.dp else 10.dp),
        ) {
            if (page.widgets.isEmpty() && !state.editMode) {
                AxEmptyState(
                    title = stringResource(R.string.telemetry_page_empty_title),
                    message = stringResource(R.string.telemetry_page_empty_message),
                    icon = AxIcons.Widgets,
                    actionLabel = stringResource(R.string.telemetry_add_widget),
                    onAction = {
                        onAction(TelemetryAction.SetEditMode(true))
                        navigation.onAddWidget(page.id)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PageGrid(state, page, onAction, navigation)
            }
        }
        if (state.editMode) EditToolbar(state, page, onAction, navigation)
    }
}

@Composable
private fun PageGrid(
    state: TelemetryUiState,
    page: TelemetryPage,
    onAction: (TelemetryAction) -> Unit,
    navigation: TelemetryNavigation,
) {
    val geometry = remember { mutableStateOf<WidgetGridGeometry?>(null) }
    val drag = remember { mutableStateOf<WidgetDrag?>(null) }
    val editMode = state.editMode
    val currentAction by rememberUpdatedState(onAction)
    val currentPage by rememberUpdatedState(page)
    val accent = AxTheme.colors.accent
    val error = AxTheme.colors.error
    Box(Modifier.fillMaxSize()) {
        WidgetGrid(
            widgets = page.widgets,
            columns = page.columns,
            rows = page.rows,
            modifier = Modifier.fillMaxSize(),
            editMode = editMode,
            onWidgetClick = { widget ->
                when {
                    editMode -> onAction(TelemetryAction.SelectWidget(widget.id))
                    widget.type == WidgetType.GPS -> navigation.onOpenPosition()
                }
            },
            onWidgetLongClick = if (editMode) null else { widget ->
                onAction(TelemetryAction.SetEditMode(true))
                onAction(TelemetryAction.SelectWidget(widget.id))
            },
            onEmptyCellClick = if (editMode) { _, _ -> onAction(TelemetryAction.SelectWidget(null)) } else null,
            selectedWidgetId = state.selectedWidgetId,
            onGeometryChanged = { geometry.value = it },
            widgetOverlay = if (editMode) { widget ->
                EditOverlay(
                    config = widget,
                    selected = widget.id == state.selectedWidgetId,
                    geometry = geometry,
                    onSelect = { currentAction(TelemetryAction.SelectWidget(widget.id)) },
                    onDrag = { drag.value = it },
                    onMoveEnd = { dx, dy -> if (dx != 0 || dy != 0) currentAction(TelemetryAction.MoveWidget(widget.id, dx, dy)) },
                    onResizeEnd = { dw, dh ->
                        val current = currentPage.widgets.firstOrNull { it.id == widget.id }
                        if (current != null && (dw != 0 || dh != 0)) {
                            currentAction(TelemetryAction.ResizeWidget(widget.id, (current.w + dw).coerceAtLeast(1), (current.h + dh).coerceAtLeast(1)))
                        }
                    },
                )
            } else null,
        )
        // The target of the drag in progress: accent while it can be placed there, red otherwise.
        Canvas(Modifier.fillMaxSize()) {
            val active = drag.value ?: return@Canvas
            val geo = geometry.value ?: return@Canvas
            val widget = page.widgets.firstOrNull { it.id == active.id } ?: return@Canvas
            val candidate = if (active.resize) {
                widget.copy(w = (widget.w + active.dx).coerceAtLeast(1), h = (widget.h + active.dy).coerceAtLeast(1))
            } else {
                widget.copy(x = widget.x + active.dx, y = widget.y + active.dy)
            }
            val color = if (WidgetLayoutOps.canPlace(page, candidate)) accent else error
            val rect = geo.rect(candidate.x, candidate.y, candidate.w, candidate.h)
            drawRoundRect(color.copy(alpha = 0.18f), rect.topLeft, rect.size, CornerRadius(14.dp.toPx()))
            drawRoundRect(color, rect.topLeft, rect.size, CornerRadius(14.dp.toPx()), style = Stroke(2.5.dp.toPx()))
        }
    }
}

/**
 * Edit-mode layer of one widget: tap selects, dragging moves (the grid cell under the finger
 * decides), and the selected widget has a resize handle in its bottom-right corner.
 */
@Composable
private fun BoxScope.EditOverlay(
    config: WidgetConfig,
    selected: Boolean,
    geometry: State<WidgetGridGeometry?>,
    onSelect: () -> Unit,
    onDrag: (WidgetDrag?) -> Unit,
    onMoveEnd: (dx: Int, dy: Int) -> Unit,
    onResizeEnd: (dw: Int, dh: Int) -> Unit,
) {
    val currentSelect by rememberUpdatedState(onSelect)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentMoveEnd by rememberUpdatedState(onMoveEnd)
    val currentResizeEnd by rememberUpdatedState(onResizeEnd)
    val widgetId = config.id
    Box(
        modifier = Modifier
            .matchParentSize()
            .pointerInput(widgetId) { detectTapGestures(onTap = { currentSelect() }) }
            .pointerInput(widgetId) {
                var total = Offset.Zero
                fun cells(): IntOffset = geometry.value?.cellsFor(total) ?: IntOffset.Zero
                detectDragGestures(
                    onDragStart = {
                        total = Offset.Zero
                        currentSelect()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        total += amount
                        val c = cells()
                        currentDrag(WidgetDrag(widgetId, resize = false, dx = c.x, dy = c.y))
                    },
                    onDragEnd = {
                        val c = cells()
                        currentDrag(null)
                        currentMoveEnd(c.x, c.y)
                    },
                    onDragCancel = { currentDrag(null) },
                )
            },
    )
    if (config.pinned) {
        AxIcon(
            icon = AxIcons.Pin,
            contentDescription = stringResource(R.string.telemetry_cd_pinned),
            tint = AxTheme.colors.accent,
            size = 16.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp),
        )
    }
    if (selected) {
        val accent = AxTheme.colors.accent
        val resizeDescription = stringResource(R.string.telemetry_cd_resize)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(44.dp)
                .semantics { contentDescription = resizeDescription }
                .pointerInput(widgetId) {
                    var total = Offset.Zero
                    fun cells(): IntOffset = geometry.value?.cellsFor(total) ?: IntOffset.Zero
                    detectDragGestures(
                        onDragStart = { total = Offset.Zero },
                        onDrag = { change, amount ->
                            change.consume()
                            total += amount
                            val c = cells()
                            currentDrag(WidgetDrag(widgetId, resize = true, dx = c.x, dy = c.y))
                        },
                        onDragEnd = {
                            val c = cells()
                            currentDrag(null)
                            currentResizeEnd(c.x, c.y)
                        },
                        onDragCancel = { currentDrag(null) },
                    )
                },
        ) {
            Canvas(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(20.dp),
            ) {
                val stroke = 2.5.dp.toPx()
                drawLine(accent, Offset(size.width, size.height * 0.15f), Offset(size.width * 0.15f, size.height), strokeWidth = stroke)
                drawLine(accent, Offset(size.width, size.height * 0.6f), Offset(size.width * 0.6f, size.height), strokeWidth = stroke)
            }
        }
    }
}

@Composable
private fun EditToolbar(
    state: TelemetryUiState,
    page: TelemetryPage,
    onAction: (TelemetryAction) -> Unit,
    navigation: TelemetryNavigation,
) {
    var showPageSettings by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val widget = state.selectedWidget
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (widget == null) {
            AxButton(
                text = stringResource(R.string.telemetry_add_widget),
                onClick = { navigation.onAddWidget(page.id) },
                variant = AxButtonVariant.Primary,
                icon = AxIcons.Add,
            )
            AxButton(
                text = stringResource(R.string.telemetry_page_settings),
                onClick = { showPageSettings = true },
                icon = AxIcons.Tune,
            )
            Text(
                text = stringResource(R.string.telemetry_edit_hint),
                style = AxTheme.typography.label,
                color = AxTheme.colors.textMuted,
                maxLines = 2,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            )
        } else {
            AxButton(
                text = stringResource(R.string.telemetry_configure),
                onClick = { navigation.onEditWidget(page.id, widget.id) },
                variant = AxButtonVariant.Primary,
                icon = AxIcons.Tune,
            )
            AxButton(
                text = stringResource(if (widget.pinned) R.string.telemetry_unpin else R.string.telemetry_pin),
                onClick = { onAction(TelemetryAction.TogglePin(widget.id)) },
                variant = if (widget.pinned) AxButtonVariant.Outlined else AxButtonVariant.Raised,
                icon = AxIcons.Pin,
            )
            Spacer(Modifier.weight(1f))
            SizeStepper(
                label = stringResource(R.string.telemetry_width_short),
                value = widget.w,
                onChange = { onAction(TelemetryAction.ResizeWidget(widget.id, it, widget.h)) },
                min = 1,
                max = page.columns - widget.x,
            )
            SizeStepper(
                label = stringResource(R.string.telemetry_height_short),
                value = widget.h,
                onChange = { onAction(TelemetryAction.ResizeWidget(widget.id, widget.w, it)) },
                min = 1,
                max = page.rows - widget.y,
            )
            AxIconButton(
                icon = AxIcons.Delete,
                contentDescription = stringResource(R.string.telemetry_delete_widget),
                onClick = { confirmDelete = true },
                tint = AxTheme.colors.error,
            )
        }
    }
    if (showPageSettings) {
        PageSettingsDialog(
            page = page,
            pageIndex = state.pages.indexOfFirst { it.id == page.id },
            pageCount = state.pages.size,
            onApply = { name, columns, rows ->
                showPageSettings = false
                onAction(TelemetryAction.UpdatePage(page.id, name, columns, rows))
            },
            onMove = { delta -> onAction(TelemetryAction.MovePage(page.id, delta)) },
            onDelete = {
                showPageSettings = false
                onAction(TelemetryAction.DeletePage(page.id))
            },
            onDismiss = { showPageSettings = false },
        )
    }
    if (confirmDelete && widget != null) {
        ConfirmDialog(
            title = stringResource(R.string.telemetry_delete_widget_title),
            message = stringResource(R.string.telemetry_delete_widget_message),
            confirmLabel = stringResource(R.string.telemetry_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onAction(TelemetryAction.DeleteWidget(widget.id))
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SizeStepper(label: String, value: Int, onChange: (Int) -> Unit, min: Int, max: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = AxTheme.typography.label, color = AxTheme.colors.textMuted)
        AxIconButton(
            icon = AxIcons.Remove,
            contentDescription = stringResource(R.string.telemetry_cd_smaller, label),
            onClick = { onChange(value - 1) },
            enabled = value > min,
        )
        Text(
            text = value.toString(),
            style = AxTheme.typography.value,
            color = AxTheme.colors.text,
            modifier = Modifier.width(16.dp),
        )
        AxIconButton(
            icon = AxIcons.Add,
            contentDescription = stringResource(R.string.telemetry_cd_larger, label),
            onClick = { onChange(value + 1) },
            enabled = value < max,
        )
    }
}

@Composable
internal fun PageSettingsDialog(
    page: TelemetryPage,
    pageIndex: Int,
    pageCount: Int,
    onApply: (name: String, columns: Int, rows: Int) -> Unit,
    onMove: (delta: Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(page.name) }
    var columns by rememberSaveable { mutableStateOf(page.columns) }
    var rows by rememberSaveable { mutableStateOf(page.rows) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    AxDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.telemetry_page_settings),
        icon = AxIcons.Tune,
        alignment = Alignment.TopCenter,
        width = 540.dp,
        dismissOnOutsideClick = false,
        buttons = {
            AxButton(
                text = stringResource(R.string.telemetry_delete_page),
                onClick = { confirmDelete = true },
                variant = AxButtonVariant.Text,
            )
            Spacer(Modifier.weight(1f))
            AxButton(text = stringResource(R.string.telemetry_cancel), onClick = onDismiss, variant = AxButtonVariant.Text)
            AxButton(text = stringResource(R.string.telemetry_apply), onClick = { onApply(name, columns, rows) }, variant = AxButtonVariant.Primary)
        },
    ) {
        AxTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.telemetry_page_name_hint),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.telemetry_columns), style = AxTheme.typography.body, color = AxTheme.colors.text)
            NumberField(
                value = columns,
                onValueChange = { columns = it },
                min = WidgetLayoutOps.MIN_COLUMNS,
                max = WidgetLayoutOps.MAX_COLUMNS,
                steppers = true,
                modifier = Modifier.width(170.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.telemetry_rows), style = AxTheme.typography.body, color = AxTheme.colors.text)
            NumberField(
                value = rows,
                onValueChange = { rows = it },
                min = WidgetLayoutOps.MIN_ROWS,
                max = WidgetLayoutOps.MAX_ROWS,
                steppers = true,
                modifier = Modifier.width(170.dp),
            )
        }
        if (pageCount > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AxButton(
                    text = stringResource(R.string.telemetry_move_left),
                    onClick = { onMove(-1) },
                    icon = AxIcons.ChevronLeft,
                    enabled = pageIndex > 0,
                )
                AxButton(
                    text = stringResource(R.string.telemetry_move_right),
                    onClick = { onMove(1) },
                    icon = AxIcons.ChevronRight,
                    enabled = pageIndex in 0 until pageCount - 1,
                )
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.telemetry_delete_page_title),
            message = stringResource(R.string.telemetry_delete_page_message),
            confirmLabel = stringResource(R.string.telemetry_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
