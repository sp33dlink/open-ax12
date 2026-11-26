package org.openax12.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

@Composable
private fun PadRow(vararg pads: Pair<Float, Float>) {
    Ax12Theme {
        Row(
            modifier = Modifier.fillMaxSize().background(AxTheme.colors.background).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pads.forEach { (px, py) ->
                StickPad(
                    x = { px },
                    y = { py },
                    modifier = Modifier.size(200.dp),
                    trimX = { 10f },
                    trimY = { -15f },
                )
            }
        }
    }
}

/** Dead centre (centre marker lit), partial deflection, and the four max-deflection borders. */
@PreviewTest
@Ax12Preview
@Composable
fun StickPadStatesScreenshot() {
    PadRow(0f to 0f, 40f to -30f, 100f to 0f, -100f to 0f)
}

/** Top and bottom maxima plus a corner. */
@PreviewTest
@Ax12Preview
@Composable
fun StickPadMaximaScreenshot() {
    PadRow(0f to 100f, 0f to -100f, 100f to 100f, -100f to -100f)
}

/** Describes one pad of [TrimRow]: stick position, trims and the edge of the vertical trim. */
private data class TrimPad(val x: Float, val y: Float, val trimX: Float, val trimY: Float, val edge: StickPadTrimEdge)

@Composable
private fun TrimRow(mode: AxThemeMode, vararg pads: TrimPad) {
    Ax12Theme(mode = mode) {
        Row(
            modifier = Modifier.fillMaxSize().background(AxTheme.colors.background).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pads.forEach { p ->
                StickPad(
                    x = { p.x },
                    y = { p.y },
                    modifier = Modifier.size(192.dp),
                    trimX = { p.trimX },
                    trimY = { p.trimY },
                    verticalTrimEdge = p.edge,
                )
            }
        }
    }
}

private val TrimPads = arrayOf(
    // Trims at the end of their range on the lit border, left pad.
    TrimPad(-100f, 0f, 0f, 100f, StickPadTrimEdge.Start),
    // Bottom trim at its negative end on the lit bottom border.
    TrimPad(0f, -100f, -100f, 0f, StickPadTrimEdge.Start),
    // Right pad: trim on the right edge, corner deflection, both trims at the far end.
    TrimPad(100f, -100f, 100f, -100f, StickPadTrimEdge.End),
    // Neutral trims, centred stick: the ticks mark the neutral position.
    TrimPad(0f, 0f, 0f, 0f, StickPadTrimEdge.End),
)

/** Trims riding on the border, at their extremes while that border is lit (light theme). */
@PreviewTest
@Ax12Preview
@Composable
fun StickPadTrimsScreenshot() {
    TrimRow(AxThemeMode.LIGHT, *TrimPads)
}

/** The same in the dark theme. */
@PreviewTest
@Ax12Preview
@Composable
fun StickPadTrimsDarkScreenshot() {
    TrimRow(AxThemeMode.DARK, *TrimPads)
}

/** The same in the high-contrast theme. */
@PreviewTest
@Ax12Preview
@Composable
fun StickPadTrimsHighContrastScreenshot() {
    TrimRow(AxThemeMode.HIGH_CONTRAST, *TrimPads)
}
