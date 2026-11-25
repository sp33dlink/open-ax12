package org.openax12.core.widgets

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.openax12.core.model.SensorId
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import kotlin.math.max
import kotlin.math.min

/**
 * What every widget needs to draw itself apart from its data: colours, the text measurer and the
 * strings resolved during composition.
 *
 * @property accent the widget's accent colour (its `color` option or the theme accent)
 * @property title the title line; null = hidden
 * @property lostLabel text of the lost marker
 */
internal class WidgetChrome(
    val palette: WidgetPalette,
    val accent: Color,
    val measurer: TextMeasurer,
    val title: String?,
    val lostLabel: String,
)

/** Builds the [WidgetChrome] of a widget. [fallbackTitle] is used when neither a title nor a sensor name exists. */
@Composable
internal fun rememberChrome(
    config: WidgetConfig,
    source: WidgetDataSource,
    sensor: SensorId?,
    fallbackTitle: String = "",
): WidgetChrome {
    val palette = widgetPalette()
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val lostLabel = stringResource(R.string.widget_lost)
    val title = widgetTitle(config, source, sensor, fallbackTitle)
    return remember(palette, measurer, config, title, lostLabel) {
        WidgetChrome(palette, accentOf(config.option(WidgetOptions.COLOR), palette.accent), measurer, title, lostLabel)
    }
}

/**
 * The drawing surface of a widget. Everything, text included, is drawn in the draw phase so that
 * a new value never recomposes; [onDraw] reads the widget's `State`s.
 */
@Composable
internal fun WidgetCanvas(chrome: WidgetChrome, modifier: Modifier = Modifier, onDraw: WidgetScope.() -> Unit) {
    Spacer(modifier.fillMaxSize().drawBehind { WidgetScope(this, chrome).onDraw() })
}

/**
 * A [DrawScope] with the helpers shared by the widget renderers: metrics that scale with the cell
 * size, text that is fitted into a box, the title line and the lost marker.
 */
internal class WidgetScope(private val scope: DrawScope, val chrome: WidgetChrome) : DrawScope by scope {

    val palette: WidgetPalette get() = chrome.palette
    val accent: Color get() = chrome.accent

    /** Inner padding of the widget. */
    val pad: Float get() = (min(size.width, size.height) * 0.09f).coerceIn(5.dp.toPx(), 11.dp.toPx())

    /** Font size of the title line. */
    val titlePx: Float get() = (size.height * 0.15f).coerceIn(9.5.dp.toPx(), 14.dp.toPx())

    /** Font size of secondary labels (scale ends, sub lines). */
    val labelPx: Float get() = titlePx * 0.9f

    /** The area inside the padding. */
    val inner: Rect get() = Rect(pad, pad, size.width - pad, size.height - pad)

    private fun style(sizePx: Float, weight: FontWeight, tabular: Boolean, align: TextAlign = TextAlign.Unspecified) = TextStyle(
        fontSize = sizePx.toSp(),
        fontWeight = weight,
        fontFeatureSettings = if (tabular) "tnum" else null,
        textAlign = align,
    )

    /** Lays out one line of [text] without drawing it. */
    fun measure(text: String, sizePx: Float, weight: FontWeight = FontWeight.Medium, tabular: Boolean = false): TextLayoutResult =
        chrome.measurer.measure(text, style(sizePx, weight, tabular), softWrap = false, maxLines = 1)

    /**
     * Draws one line of [text] into [box], vertically centred on its capital height.
     *
     * The font size is [sizePx], limited by the box height; a text that is too wide is shrunk
     * (or, with [ellipsis], cut off with an ellipsis).
     *
     * @param alignX horizontal position in the box: 0 = start, 0.5 = centre, 1 = end
     * @param tabular fixed-width digits; the size is then fitted for the digit count, not the digits
     * @param minChars fit the size as if the text had at least this many characters, so a short
     *   number does not grow huge
     * @return the width of the drawn text
     */
    fun text(
        text: String,
        box: Rect,
        color: Color,
        sizePx: Float,
        weight: FontWeight = FontWeight.Medium,
        alignX: Float = 0f,
        tabular: Boolean = false,
        minChars: Int = 0,
        ellipsis: Boolean = false,
    ): Float {
        if (text.isEmpty() || box.width < 2f || box.height < 2f) return 0f
        var fontPx = min(sizePx, box.height)
        val minPx = MIN_FONT_DP.dp.toPx()
        var layout: TextLayoutResult
        if (ellipsis) {
            layout = ellipsized(text, fontPx, weight, tabular, box.width)
        } else {
            val sizing = sizingText(text, minChars, tabular)
            layout = measure(sizing, fontPx, weight, tabular)
            if (layout.size.width > box.width) {
                fontPx = max(fontPx * box.width / layout.size.width * FIT_MARGIN, minPx)
                layout = measure(text, fontPx, weight, tabular)
                if (layout.size.width > box.width) layout = ellipsized(text, fontPx, weight, tabular, box.width)
            } else if (sizing != text) {
                layout = measure(text, fontPx, weight, tabular)
            }
        }
        val x = box.left + (box.width - layout.size.width) * alignX
        val y = box.center.y + fontPx * CAP_HEIGHT / 2f - layout.firstBaseline
        drawText(layout, color = color, topLeft = Offset(x, y))
        return layout.size.width.toFloat()
    }

    private fun ellipsized(text: String, fontPx: Float, weight: FontWeight, tabular: Boolean, width: Float): TextLayoutResult =
        chrome.measurer.measure(
            text,
            style(fontPx, weight, tabular),
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1)),
        )

    /**
     * Draws a number with a smaller unit behind it on a common baseline, fitted into [box].
     *
     * @param sizePx wanted font size of the number
     * @param unitScale size of the unit relative to the number
     */
    fun valueText(
        number: String,
        unit: String,
        box: Rect,
        color: Color,
        unitColor: Color,
        sizePx: Float,
        alignX: Float = 0.5f,
        minChars: Int = 3,
        tabular: Boolean = true,
        weight: FontWeight = FontWeight.Bold,
        unitScale: Float = 0.42f,
    ) {
        if (box.width < 2f || box.height < 2f) return
        if (unit.isEmpty()) {
            text(number, box, color, sizePx, weight, alignX, tabular, minChars)
            return
        }
        var fontPx = min(sizePx, box.height)
        val minPx = MIN_FONT_DP.dp.toPx()
        val sizing = sizingText(number, minChars, tabular)
        var probe = measure(sizing, fontPx, weight, tabular)
        var unitLayout = measure(unit, max(fontPx * unitScale, minPx), FontWeight.Medium)
        val total = probe.size.width + fontPx * UNIT_GAP + unitLayout.size.width
        if (total > box.width) {
            fontPx = max(fontPx * box.width / total * FIT_MARGIN, minPx)
            unitLayout = measure(unit, max(fontPx * unitScale, minPx), FontWeight.Medium)
            probe = measure(number, fontPx, weight, tabular)
        } else if (sizing != number) {
            probe = measure(number, fontPx, weight, tabular)
        }
        val gap = fontPx * UNIT_GAP
        val width = probe.size.width + gap + unitLayout.size.width
        val x = box.left + (box.width - width).coerceAtLeast(0f) * alignX
        val baseline = box.center.y + fontPx * CAP_HEIGHT / 2f
        drawText(probe, color = color, topLeft = Offset(x, baseline - probe.firstBaseline))
        drawText(unitLayout, color = unitColor, topLeft = Offset(x + probe.size.width + gap, baseline - unitLayout.firstBaseline))
    }

    /**
     * Draws [text] centred in [box] on up to [maxLines] lines, with the largest font size up to
     * [maxSizePx] at which it fits.
     */
    fun paragraph(
        text: String,
        box: Rect,
        color: Color,
        maxSizePx: Float,
        weight: FontWeight = FontWeight.SemiBold,
        maxLines: Int = 3,
    ) {
        if (text.isBlank() || box.width < 2f || box.height < 2f) return
        val minPx = MIN_FONT_DP.dp.toPx()
        val width = box.width.toInt().coerceAtLeast(1)
        val constraints = Constraints(minWidth = width, maxWidth = width)
        // Never break inside a word: the longest word has to fit on a line.
        val longestWord = text.split(' ', '\n').maxByOrNull { it.length }.orEmpty()
        var high = min(maxSizePx, box.height).coerceAtLeast(minPx)
        val wordWidth = measure(longestWord, high, weight).size.width
        if (wordWidth > box.width) high = max(high * box.width / wordWidth * FIT_MARGIN, minPx)

        fun layout(fontPx: Float): TextLayoutResult = chrome.measurer.measure(
            text,
            style(fontPx, weight, tabular = false, align = TextAlign.Center).copy(lineHeight = (fontPx * 1.16f).toSp()),
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            maxLines = maxLines,
            constraints = constraints,
        )

        fun fits(result: TextLayoutResult) = !result.hasVisualOverflow && result.size.height <= box.height

        var best = layout(high)
        if (!fits(best)) {
            var low = minPx
            best = layout(low)
            repeat(FIT_STEPS) {
                val mid = (low + high) / 2f
                val candidate = layout(mid)
                if (fits(candidate)) {
                    low = mid
                    best = candidate
                } else {
                    high = mid
                }
            }
        }
        drawText(best, color = color, topLeft = Offset(box.left, box.top + (box.height - best.size.height) / 2f))
    }

    /**
     * Draws the title line with an optional [trailing] text at its end and, for a lost sensor,
     * the lost marker. Returns the area left for the widget body.
     *
     * @param reserveEnd width kept free at the end of the line for something the widget draws itself
     */
    fun header(
        status: ReadingStatus = ReadingStatus.OK,
        trailing: String? = null,
        trailingColor: Color = palette.textDim,
        reserveEnd: Float = 0f,
    ): Rect {
        val p = pad
        val fontPx = titlePx
        val rowHeight = fontPx * 1.3f
        val gap = fontPx * 0.5f
        var end = size.width - p - reserveEnd
        if (status == ReadingStatus.LOST) end -= lostMarker(end, p, rowHeight) + gap
        val title = chrome.title
        if (title == null && trailing == null && reserveEnd == 0f) return inner
        if (trailing != null && end - p > fontPx * 2f) {
            val width = text(
                trailing,
                Rect(p + (end - p) * 0.25f, p, end, p + rowHeight),
                trailingColor,
                fontPx * 1.05f,
                FontWeight.SemiBold,
                alignX = 1f,
                tabular = true,
            )
            end -= width + gap
        }
        if (!title.isNullOrEmpty() && end - p > fontPx) {
            text(title, Rect(p, p, end, p + rowHeight), palette.textDim, fontPx, FontWeight.Medium, ellipsis = true)
        }
        return Rect(p, p + rowHeight + fontPx * 0.25f, size.width - p, size.height - p)
    }

    /** Draws the lost marker with its end at [end]; returns its width. */
    private fun lostMarker(end: Float, top: Float, rowHeight: Float): Float {
        val layout = measure(chrome.lostLabel, titlePx * 0.74f, FontWeight.Bold)
        val padX = rowHeight * 0.32f
        val width = layout.size.width + 2f * padX
        drawRoundRect(
            color = palette.alarm.copy(alpha = 0.16f),
            topLeft = Offset(end - width, top),
            size = Size(width, rowHeight),
            cornerRadius = CornerRadius(rowHeight / 2f),
        )
        drawText(
            layout,
            color = palette.alarm,
            topLeft = Offset(end - width + padX, top + (rowHeight - layout.size.height) / 2f),
        )
        return width
    }

    /**
     * A rounded bar: the track of [rect] and the filled part between the fractions [from] and
     * [to] (left to right for a horizontal bar, bottom to top for a vertical one).
     */
    fun bar(rect: Rect, from: Float, to: Float, color: Color, track: Color = palette.track) {
        val radius = CornerRadius(min(rect.width, rect.height) / 2f)
        drawRoundRect(track, rect.topLeft, rect.size, radius)
        val lo = min(from, to).coerceIn(0f, 1f)
        val hi = max(from, to).coerceIn(0f, 1f)
        if (hi - lo <= 0f) return
        val outline = Path().apply { addRoundRect(RoundRect(rect, radius)) }
        clipPath(outline) {
            if (rect.width >= rect.height) {
                drawRect(color, Offset(rect.left + rect.width * lo, rect.top), Size(rect.width * (hi - lo), rect.height))
            } else {
                drawRect(color, Offset(rect.left, rect.bottom - rect.height * hi), Size(rect.width, rect.height * (hi - lo)))
            }
        }
    }

    /** A small filled triangle centred at [center], pointing up or down. */
    fun triangle(center: Offset, sizePx: Float, up: Boolean, color: Color) {
        val half = sizePx / 2f
        val dir = if (up) -1f else 1f
        val path = Path().apply {
            moveTo(center.x, center.y + dir * half)
            lineTo(center.x - half, center.y - dir * half)
            lineTo(center.x + half, center.y - dir * half)
            close()
        }
        drawPath(path, color)
    }

    private fun sizingText(text: String, minChars: Int, tabular: Boolean): String {
        if (!tabular && text.length >= minChars) return text
        val builder = StringBuilder(max(text.length, minChars))
        for (c in text) builder.append(if (tabular && c in '0'..'9') '0' else c)
        while (builder.length < minChars) builder.append('0')
        return builder.toString()
    }

    companion object {
        /** Capital height of the default typeface (Roboto) relative to the font size. */
        const val CAP_HEIGHT = 0.711f

        /** Smallest font size text is shrunk to. */
        const val MIN_FONT_DP = 7f

        /** Gap between a number and its unit relative to the font size. */
        const val UNIT_GAP = 0.12f

        private const val FIT_MARGIN = 0.985f
        private const val FIT_STEPS = 5
    }
}
