package org.openax12.core.designsystem

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign

private class LiveTextCache {
    var value: Int = 0
    var layout: TextLayoutResult? = null
}

/**
 * Text for a live value that changes up to 25 times per second. The value is read in the draw
 * phase, so updates neither recompose nor re-layout anything; the text is only measured again
 * when the integer actually changes.
 *
 * The layout size is fixed by [template], which should be the widest text that can occur
 * (e.g. `"-100%"`), so neighbours never move.
 *
 * @param value the live value, read on every draw; scale decimals into the integer (tenths of a
 *   volt, seconds, …)
 * @param format turns the value into text; only called when the value changed
 * @param template widest possible text; defines the size
 * @param color text colour
 * @param colorProducer optional live colour (e.g. red below a threshold); overrides [color]
 * @param textAlign alignment inside the template width: Start, Center or End
 */
@Composable
fun AxLiveText(
    value: () -> Int,
    format: (Int) -> String,
    template: String,
    modifier: Modifier = Modifier,
    style: TextStyle = AxTheme.typography.value,
    color: Color = LocalContentColor.current,
    colorProducer: ColorProducer? = null,
    textAlign: TextAlign = TextAlign.End,
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val templateLayout = remember(template, style, density) {
        measurer.measure(text = template, style = style, maxLines = 1, softWrap = false)
    }
    val cache = remember(style, density, format) { LiveTextCache() }
    val width = with(density) { templateLayout.size.width.toDp() }
    val height = with(density) { templateLayout.size.height.toDp() }
    Spacer(
        modifier = modifier
            .size(width, height)
            .graphicsLayer()
            .drawBehind {
                val v = value()
                var layout = cache.layout
                if (layout == null || cache.value != v) {
                    layout = measurer.measure(text = format(v), style = style, maxLines = 1, softWrap = false)
                    cache.value = v
                    cache.layout = layout
                }
                val x = when (textAlign) {
                    TextAlign.End, TextAlign.Right -> size.width - layout.size.width
                    TextAlign.Center -> (size.width - layout.size.width) / 2f
                    else -> 0f
                }
                val y = (size.height - layout.size.height) / 2f
                drawText(
                    textLayoutResult = layout,
                    color = colorProducer?.invoke() ?: color,
                    topLeft = Offset(x, y),
                )
            },
    )
}

/** Formats a percentage for live display: `-100%` … `100%`. Strings are shared, nothing is allocated. */
fun formatPercent(value: Int): String = when {
    value in -PERCENT_RANGE..PERCENT_RANGE -> PercentStrings[value + PERCENT_RANGE]
    else -> "$value%"
}

private const val PERCENT_RANGE = 150
private val PercentStrings: Array<String> = Array(2 * PERCENT_RANGE + 1) { "${it - PERCENT_RANGE}%" }

/** Formats tenths of a volt: `38` → `3.8V`. */
fun formatTenthsVolt(tenths: Int): String {
    val sign = if (tenths < 0) "-" else ""
    val abs = if (tenths < 0) -tenths else tenths
    return "$sign${abs / 10}.${abs % 10}V"
}

/** Formats seconds as `HH:MM:SS` (negative values get a leading minus). */
fun formatTimer(totalSeconds: Int): String {
    val abs = if (totalSeconds < 0) -totalSeconds else totalSeconds
    val h = abs / 3600
    val m = (abs % 3600) / 60
    val s = abs % 60
    val sb = StringBuilder(9)
    if (totalSeconds < 0) sb.append('-')
    if (h < 10) sb.append('0')
    sb.append(h).append(':')
    if (m < 10) sb.append('0')
    sb.append(m).append(':')
    if (s < 10) sb.append('0')
    sb.append(s)
    return sb.toString()
}
