package org.openax12.feature.telemetry

import org.openax12.core.model.TelemetryLayout
import org.openax12.core.model.TelemetryPage
import org.openax12.core.model.WidgetConfig

/**
 * Placement rules of the widgets on a telemetry page. Pure: every function returns a new page
 * (or null when the change is not possible) and never mutates its input.
 *
 * Rules: a widget lies completely inside the grid, has a size of at least 1 x 1 and does not
 * overlap another widget of the page.
 */
internal object WidgetLayoutOps {

    /** Smallest / largest number of grid columns of a page. */
    const val MIN_COLUMNS = 1
    const val MAX_COLUMNS = 8

    /** Smallest / largest number of grid rows of a page. */
    const val MIN_ROWS = 1
    const val MAX_ROWS = 6

    /** True if [widget] lies completely inside the grid of [page]. */
    fun inBounds(page: TelemetryPage, widget: WidgetConfig): Boolean =
        widget.x >= 0 && widget.y >= 0 && widget.w >= 1 && widget.h >= 1 &&
            widget.x + widget.w <= page.columns && widget.y + widget.h <= page.rows

    /** True if [widget] overlaps a widget of [page] other than itself. */
    fun overlapsOther(page: TelemetryPage, widget: WidgetConfig): Boolean =
        page.widgets.any { it.id != widget.id && it.overlaps(widget) }

    /** True if [widget] may be placed as it is: inside the grid and free of overlaps. */
    fun canPlace(page: TelemetryPage, widget: WidgetConfig): Boolean =
        inBounds(page, widget) && !overlapsOther(page, widget)

    /**
     * The first free spot (row by row) for a block of [w] x [h] cells, ignoring the widget
     * [ignoreId]; null if there is none.
     */
    fun findFreeSpot(page: TelemetryPage, w: Int, h: Int, ignoreId: String? = null): Pair<Int, Int>? {
        if (w < 1 || h < 1 || w > page.columns || h > page.rows) return null
        val probe = WidgetConfig(id = "\u0000probe", w = w, h = h)
        for (y in 0..page.rows - h) {
            for (x in 0..page.columns - w) {
                val candidate = probe.copy(x = x, y = y)
                if (page.widgets.none { it.id != ignoreId && it.overlaps(candidate) }) return x to y
            }
        }
        return null
    }

    /**
     * Adds [widget] at the first free spot. When its size does not fit anywhere it is made
     * smaller (largest area first) until a spot exists. Null if the page is full.
     */
    fun add(page: TelemetryPage, widget: WidgetConfig): TelemetryPage? {
        val w0 = widget.w.coerceIn(1, page.columns)
        val h0 = widget.h.coerceIn(1, page.rows)
        val sizes = buildList {
            for (w in w0 downTo 1) for (h in h0 downTo 1) add(w to h)
        }.sortedWith(compareByDescending<Pair<Int, Int>> { it.first * it.second }.thenByDescending { it.first })
        for ((w, h) in sizes) {
            val spot = findFreeSpot(page, w, h) ?: continue
            return page.copy(widgets = page.widgets + widget.copy(x = spot.first, y = spot.second, w = w, h = h))
        }
        return null
    }

    /** Moves the widget [id] by ([dx], [dy]) cells; null if it does not exist or the target is not free. */
    fun moveBy(page: TelemetryPage, id: String, dx: Int, dy: Int): TelemetryPage? {
        val widget = page.widgets.firstOrNull { it.id == id } ?: return null
        return replace(page, widget.copy(x = widget.x + dx, y = widget.y + dy))
    }

    /** Gives the widget [id] a size of [w] x [h] cells; null if it does not exist or the area is not free. */
    fun resizeTo(page: TelemetryPage, id: String, w: Int, h: Int): TelemetryPage? {
        val widget = page.widgets.firstOrNull { it.id == id } ?: return null
        return replace(page, widget.copy(w = w, h = h))
    }

    /** Replaces the widget with [widget]'s id; null if it does not exist or [widget] cannot be placed. */
    fun replace(page: TelemetryPage, widget: WidgetConfig): TelemetryPage? {
        if (page.widgets.none { it.id == widget.id }) return null
        if (!canPlace(page, widget)) return null
        return page.copy(widgets = page.widgets.map { if (it.id == widget.id) widget else it })
    }

    /** [page] without the widget [id]. */
    fun remove(page: TelemetryPage, id: String): TelemetryPage = page.copy(widgets = page.widgets.filterNot { it.id == id })

    /** True if every widget of [page] still lies inside a grid of [columns] x [rows]. */
    fun fitsGrid(page: TelemetryPage, columns: Int, rows: Int): Boolean =
        page.widgets.all { it.x + it.w <= columns && it.y + it.h <= rows }

    /** [page] with a grid of [columns] x [rows]; null if the size is out of range or widgets would fall off. */
    fun resizeGrid(page: TelemetryPage, columns: Int, rows: Int): TelemetryPage? {
        if (columns !in MIN_COLUMNS..MAX_COLUMNS || rows !in MIN_ROWS..MAX_ROWS) return null
        if (!fitsGrid(page, columns, rows)) return null
        return page.copy(columns = columns, rows = rows)
    }

    /** The pin order a newly pinned widget gets: after all pinned widgets of the layout. */
    fun nextPinOrder(layout: TelemetryLayout): Int = (layout.pinnedWidgets.maxOfOrNull { it.pinOrder } ?: -1) + 1

    /** Replaces the page with [page]'s id in [layout]. */
    fun withPage(layout: TelemetryLayout, page: TelemetryPage): TelemetryLayout =
        layout.copy(pages = layout.pages.map { if (it.id == page.id) page else it })
}
