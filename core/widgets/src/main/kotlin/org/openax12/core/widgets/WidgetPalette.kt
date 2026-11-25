package org.openax12.core.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import org.openax12.core.designsystem.AxTheme

/**
 * The colours the widget renderers draw with, taken from the design system's colour tokens so the
 * widgets follow the light, dark and high-contrast themes; only the horizon colours are fixed
 * per brightness.
 *
 * @property dark true on a dark surface
 * @property card background of a raised widget card
 * @property cardFlat background of a flat widget tile (inside another card)
 * @property outline thin outline of cards and gauge frames
 * @property text primary text (values)
 * @property textDim secondary text (titles, units, scale labels)
 * @property track unfilled part of bars and gauges
 * @property accent default accent of bars, gauges and graphs
 * @property good healthy state (full battery, good link, climb, running)
 * @property warn warning state
 * @property alarm alarm state and the lost marker
 * @property switchUp / [switchDown] the stock colours of a switch in its up / down position
 */
@Immutable
internal data class WidgetPalette(
    val dark: Boolean,
    val card: Color,
    val cardFlat: Color,
    val outline: Color,
    val text: Color,
    val textDim: Color,
    val track: Color,
    val accent: Color,
    val good: Color,
    val warn: Color,
    val alarm: Color,
    val switchUp: Color,
    val switchDown: Color,
    val sky: Color,
    val skyHigh: Color,
    val ground: Color,
    val groundLow: Color,
) {
    /** [color] as drawn for a value with [status]: stale and lost values are dimmed. */
    fun tone(color: Color, status: ReadingStatus): Color = when (status) {
        ReadingStatus.OK -> color
        ReadingStatus.STALE, ReadingStatus.LOST -> color.copy(alpha = color.alpha * DIM_ALPHA)
        ReadingStatus.MISSING -> textDim.copy(alpha = textDim.alpha * MISSING_ALPHA)
    }

    companion object {
        /** Alpha factor of stale / lost values. */
        const val DIM_ALPHA = 0.42f

        /** Alpha factor of the `--` placeholder. */
        const val MISSING_ALPHA = 0.7f
    }
}

/** The widget palette for the current [AxTheme]. */
@Composable
@ReadOnlyComposable
internal fun widgetPalette(): WidgetPalette {
    val colors = AxTheme.colors
    val dark = !colors.isLight
    // Tracks are tinted from the text colour so they stay visible on raised and on sunken cards.
    val trackAlpha = when {
        dark -> 0.16f
        colors.neumorphic -> 0.11f
        else -> 0.2f
    }
    return WidgetPalette(
        dark = dark,
        card = colors.surface,
        cardFlat = colors.surfaceSunken,
        outline = colors.outline,
        text = colors.text,
        textDim = colors.textMuted,
        track = colors.text.copy(alpha = trackAlpha),
        accent = colors.accent,
        good = colors.success,
        warn = colors.warning,
        alarm = colors.error,
        switchUp = colors.orange,
        switchDown = colors.teal,
        sky = if (dark) Color(0xFF2B6CA3) else Color(0xFF58A9DD),
        skyHigh = if (dark) Color(0xFF173F66) else Color(0xFF2F7FB5),
        ground = if (dark) Color(0xFF7A5230) else Color(0xFFA9743F),
        groundLow = if (dark) Color(0xFF4A2F1A) else Color(0xFF7A4E26),
    )
}

/** The accent colour of a widget: its `color` option if valid, else [fallback]. */
internal fun accentOf(option: String?, fallback: Color): Color =
    WidgetFormat.parseColor(option)?.let { Color(it.toInt()) } ?: fallback
