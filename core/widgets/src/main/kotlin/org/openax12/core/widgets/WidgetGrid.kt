package org.openax12.core.widgets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openax12.core.model.WidgetConfig
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pixel geometry of a widget grid: where the cells are. Pure; the telemetry screen uses it to
 * translate drags into cells when moving and resizing widgets.
 *
 * @property width / [height] size of the grid in pixels
 * @property spacing gap between cells in pixels
 */
@Immutable
data class WidgetGridGeometry(
    val width: Float,
    val height: Float,
    val columns: Int,
    val rows: Int,
    val spacing: Float,
) {
    /** Width of one cell. */
    val cellWidth: Float get() = ((width - spacing * (columns - 1)) / columns).coerceAtLeast(0f)

    /** Height of one cell. */
    val cellHeight: Float get() = ((height - spacing * (rows - 1)) / rows).coerceAtLeast(0f)

    /** True if the grid has cells and a size. */
    val isValid: Boolean get() = columns > 0 && rows > 0 && cellWidth > 0f && cellHeight > 0f

    /** The rectangle of the cell block at ([x], [y]) spanning [w] x [h] cells (not clamped). */
    fun rect(x: Int, y: Int, w: Int = 1, h: Int = 1): Rect {
        val left = x * (cellWidth + spacing)
        val top = y * (cellHeight + spacing)
        return Rect(left, top, left + w * cellWidth + (w - 1) * spacing, top + h * cellHeight + (h - 1) * spacing)
    }

    /**
     * The rectangle of [config], with its span cut off at the grid edges; null if the widget
     * lies outside the grid completely.
     */
    fun rectOf(config: WidgetConfig): Rect? {
        if (!isValid) return null
        val x0 = config.x.coerceAtLeast(0)
        val y0 = config.y.coerceAtLeast(0)
        val x1 = (config.x + config.w.coerceAtLeast(1)).coerceAtMost(columns)
        val y1 = (config.y + config.h.coerceAtLeast(1)).coerceAtMost(rows)
        if (x1 <= x0 || y1 <= y0) return null
        return rect(x0, y0, x1 - x0, y1 - y0)
    }

    /** The cell (column, row) under [position]; positions in a gap count to the cell before it. Null outside the grid. */
    fun cellAt(position: Offset): IntOffset? {
        if (!isValid || position.x < 0f || position.y < 0f || position.x > width || position.y > height) return null
        val column = floor(position.x / (cellWidth + spacing)).toInt().coerceIn(0, columns - 1)
        val row = floor(position.y / (cellHeight + spacing)).toInt().coerceIn(0, rows - 1)
        return IntOffset(column, row)
    }

    /** Converts a drag distance in pixels to whole cells (for move / resize handles). */
    fun cellsFor(delta: Offset): IntOffset {
        if (!isValid) return IntOffset.Zero
        return IntOffset((delta.x / (cellWidth + spacing)).roundToInt(), (delta.y / (cellHeight + spacing)).roundToInt())
    }
}

/**
 * Grid of telemetry widgets (a telemetry page, the pinned widgets in the home centre card).
 *
 * Every widget is placed at its `x` / `y` with its `w` x `h` span on a [columns] x [rows] grid
 * that fills the given space; widgets (or parts of them) outside the grid are not shown.
 * The grid only places and renders: moving, resizing and adding widgets is the caller's job,
 * built from [editMode], [widgetOverlay], the tap callbacks and [onGeometryChanged].
 *
 * @param editMode draws the cell outlines and a frame around every widget
 * @param onWidgetClick tap on a widget
 * @param onWidgetLongClick long-press on a widget; null = no long-press handling
 * @param onEmptyCellClick tap on a free cell (column, row); null = taps on free cells pass through
 * @param selectedWidgetId the widget drawn with the selection frame
 * @param cardStyle background of the widgets
 * @param spacing gap between cells
 * @param dataSource where the widgets read their values; by default [LocalWidgetDataSource]
 * @param onGeometryChanged reports the pixel geometry whenever the grid is (re)sized
 * @param widgetOverlay drawn on top of each widget, in a box of the widget's size (drag handles,
 *   delete buttons, a pin marker)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetGrid(
    widgets: List<WidgetConfig>,
    columns: Int,
    rows: Int,
    modifier: Modifier = Modifier,
    editMode: Boolean = false,
    onWidgetClick: (WidgetConfig) -> Unit = {},
    onWidgetLongClick: ((WidgetConfig) -> Unit)? = null,
    onEmptyCellClick: ((column: Int, row: Int) -> Unit)? = null,
    selectedWidgetId: String? = null,
    cardStyle: WidgetCardStyle = WidgetCardStyle.RAISED,
    spacing: Dp = WidgetDefaults.GridSpacing,
    dataSource: WidgetDataSource = LocalWidgetDataSource.current,
    onGeometryChanged: ((WidgetGridGeometry) -> Unit)? = null,
    widgetOverlay: (@Composable BoxScope.(WidgetConfig) -> Unit)? = null,
) {
    val safeColumns = columns.coerceAtLeast(1)
    val safeRows = rows.coerceAtLeast(1)
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val palette = widgetPalette()
    val shape = RoundedCornerShape(WidgetDefaults.CardCornerRadius)
    val cornerPx = with(LocalDensity.current) { WidgetDefaults.CardCornerRadius.toPx() }
    val currentEmptyClick = rememberUpdatedState(onEmptyCellClick)
    val currentGeometryChanged = rememberUpdatedState(onGeometryChanged)

    var background: Modifier = Modifier
    if (editMode) {
        background = background.drawBehind {
            val geometry = WidgetGridGeometry(size.width, size.height, safeColumns, safeRows, spacingPx)
            if (!geometry.isValid) return@drawBehind
            val stroke = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
            for (row in 0 until safeRows) {
                for (column in 0 until safeColumns) {
                    val cell = geometry.rect(column, row)
                    drawRoundRect(palette.textDim.copy(alpha = 0.4f), cell.topLeft, cell.size, CornerRadius(cornerPx), style = stroke)
                }
            }
        }
    }
    if (onEmptyCellClick != null) {
        background = background.pointerInput(safeColumns, safeRows, spacingPx) {
            detectTapGestures { position ->
                val geometry = WidgetGridGeometry(size.width.toFloat(), size.height.toFloat(), safeColumns, safeRows, spacingPx)
                geometry.cellAt(position)?.let { currentEmptyClick.value?.invoke(it.x, it.y) }
            }
        }
    }

    Layout(
        content = {
            Box(background)
            for (config in widgets) {
                key(config.id) {
                    Box {
                        WidgetCard(
                            config = config,
                            modifier = Modifier.fillMaxSize(),
                            style = cardStyle,
                            selected = config.id == selectedWidgetId,
                            outlined = editMode,
                            dataSource = dataSource,
                        )
                        // The touch layer lies over the card so that the card's shadow is not clipped.
                        Box(
                            Modifier
                                .matchParentSize()
                                .clip(shape)
                                .combinedClickable(
                                    onClick = { onWidgetClick(config) },
                                    onLongClick = onWidgetLongClick?.let { handler -> { handler(config) } },
                                ),
                        )
                        widgetOverlay?.invoke(this, config)
                    }
                }
            }
        },
        modifier = modifier.onSizeChanged { size ->
            currentGeometryChanged.value?.invoke(
                WidgetGridGeometry(size.width.toFloat(), size.height.toFloat(), safeColumns, safeRows, spacingPx),
            )
        },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val geometry = WidgetGridGeometry(width.toFloat(), height.toFloat(), safeColumns, safeRows, spacingPx)
        val backgroundPlaceable = measurables[0].measure(Constraints.fixed(width, height))
        val rects = arrayOfNulls<Rect>(widgets.size)
        val placeables = widgets.mapIndexed { index, config ->
            val rect = geometry.rectOf(config)
            rects[index] = rect
            val measurable = measurables.getOrNull(index + 1)
            if (rect == null || measurable == null) {
                null
            } else {
                measurable.measure(Constraints.fixed(rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0)))
            }
        }
        layout(width, height) {
            backgroundPlaceable.place(0, 0)
            placeables.forEachIndexed { index, placeable ->
                val rect = rects[index]
                if (placeable != null && rect != null) placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
            }
        }
    }
}

/** One slot of a pinned arrangement, in cells of its [PinnedArrangement]. */
@Immutable
internal data class PinnedSlot(val x: Int, val y: Int, val w: Int, val h: Int)

/** How up to [MAX_PINNED] pinned widgets share the home centre card. Pure; unit-tested. */
@Immutable
internal data class PinnedArrangement(val columns: Int, val rows: Int, val slots: List<PinnedSlot>) {
    companion object {
        /** The most widgets a pinned grid shows. */
        const val MAX_PINNED = 6

        /**
         * The arrangement for [count] widgets in an area with the given [aspect] (width / height):
         * one widget fills the card, two share it along its longer side, three get a wide one on
         * top, four a 2 x 2 grid, five two over three, six a 3 x 2 grid.
         */
        fun of(count: Int, aspect: Float): PinnedArrangement {
            val wide = aspect >= 1.15f
            return when (count.coerceIn(0, MAX_PINNED)) {
                0 -> PinnedArrangement(1, 1, emptyList())
                1 -> PinnedArrangement(1, 1, listOf(PinnedSlot(0, 0, 1, 1)))
                2 -> if (wide) row(2) else column(2)
                3 -> when {
                    aspect >= 2.4f -> row(3)
                    aspect <= 0.55f -> column(3)
                    else -> PinnedArrangement(2, 2, listOf(PinnedSlot(0, 0, 2, 1), PinnedSlot(0, 1, 1, 1), PinnedSlot(1, 1, 1, 1)))
                }
                4 -> if (aspect >= 3.2f) row(4) else PinnedArrangement(2, 2, grid(2, 2))
                5 -> PinnedArrangement(
                    6, 2,
                    listOf(
                        PinnedSlot(0, 0, 3, 1), PinnedSlot(3, 0, 3, 1),
                        PinnedSlot(0, 1, 2, 1), PinnedSlot(2, 1, 2, 1), PinnedSlot(4, 1, 2, 1),
                    ),
                )
                else -> if (aspect >= 0.9f) PinnedArrangement(3, 2, grid(3, 2)) else PinnedArrangement(2, 3, grid(2, 3))
            }
        }

        private fun row(count: Int) = PinnedArrangement(count, 1, List(count) { PinnedSlot(it, 0, 1, 1) })
        private fun column(count: Int) = PinnedArrangement(1, count, List(count) { PinnedSlot(0, it, 1, 1) })
        private fun grid(columns: Int, rows: Int) = List(columns * rows) { PinnedSlot(it % columns, it / columns, 1, 1) }
    }
}

/**
 * The pinned widgets of a model, laid out automatically for the home centre card: the first
 * (up to six) [widgets] share the given space, whatever their size on their telemetry page.
 * Shows a hint when nothing is pinned.
 *
 * @param widgets the pinned widgets in display order (`TelemetryLayout.pinnedWidgets`)
 * @param onWidgetClick tap on a widget (the original configuration is passed)
 * @param cardStyle background of the widgets; flat by default because the grid sits inside a card
 */
@Composable
fun PinnedWidgetGrid(
    widgets: List<WidgetConfig>,
    modifier: Modifier = Modifier,
    onWidgetClick: (WidgetConfig) -> Unit = {},
    onWidgetLongClick: ((WidgetConfig) -> Unit)? = null,
    cardStyle: WidgetCardStyle = WidgetCardStyle.FLAT,
    spacing: Dp = 6.dp,
    dataSource: WidgetDataSource = LocalWidgetDataSource.current,
) {
    if (widgets.isEmpty()) {
        PinnedEmptyHint(modifier)
        return
    }
    BoxWithConstraints(modifier) {
        val aspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
        val shown = widgets.take(PinnedArrangement.MAX_PINNED)
        val arrangement = remember(shown.size, aspect) { PinnedArrangement.of(shown.size, aspect) }
        val placed = remember(shown, arrangement) {
            shown.mapIndexed { index, config ->
                val slot = arrangement.slots[index]
                config.copy(x = slot.x, y = slot.y, w = slot.w, h = slot.h)
            }
        }
        val originals = remember(shown) { shown.associateBy { it.id } }
        WidgetGrid(
            widgets = placed,
            columns = arrangement.columns,
            rows = arrangement.rows,
            modifier = Modifier.fillMaxSize(),
            onWidgetClick = { onWidgetClick(originals[it.id] ?: it) },
            onWidgetLongClick = onWidgetLongClick?.let { handler -> { handler(originals[it.id] ?: it) } },
            cardStyle = cardStyle,
            spacing = spacing,
            dataSource = dataSource,
        )
    }
}

@Composable
private fun PinnedEmptyHint(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.widget_pinned_empty),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.widget_pinned_empty_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
