package org.openax12.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/** The fixed virtual canvas every screen is laid out on. */
object AxCanvas {
    /** Canvas width in dp. */
    const val WIDTH_DP: Int = 853

    /** Canvas height in dp. */
    const val HEIGHT_DP: Int = 480

    /** Canvas width. */
    val Width: Dp = WIDTH_DP.dp

    /** Canvas height. */
    val Height: Dp = HEIGHT_DP.dp
}

/**
 * Lays [content] out on the fixed virtual canvas of 853x480 dp, whatever the real screen is.
 *
 * It does not scale pixels: it overrides [LocalDensity] so that 853x480 dp exactly fit the
 * available space (the limiting dimension decides) and ignores the system font scale. Everything
 * is therefore still rendered at native resolution and touch input needs no mapping. If the
 * aspect ratio differs the canvas is centred and the rest is filled with [letterboxColor].
 *
 * The canvas paints the theme background, hosts in-tree overlays ([AxOverlayHost]: dialogs,
 * touch lock) and the toast host ([LocalAxToastState]).
 *
 * @param letterboxColor colour of the bars outside the canvas
 * @param content the UI; its box is exactly [AxCanvas.Width] x [AxCanvas.Height]
 */
@Composable
fun FixedCanvas(
    modifier: Modifier = Modifier,
    letterboxColor: Color = Color.Black,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(letterboxColor),
        contentAlignment = Alignment.Center,
    ) {
        val outer = LocalDensity.current
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else (AxCanvas.WIDTH_DP * outer.density).roundToInt()
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight else (AxCanvas.HEIGHT_DP * outer.density).roundToInt()
        val scale = min(maxW / AxCanvas.WIDTH_DP.toFloat(), maxH / AxCanvas.HEIGHT_DP.toFloat()).coerceAtLeast(0.1f)
        val widthPx = min(maxW, (AxCanvas.WIDTH_DP * scale).roundToInt())
        val heightPx = min(maxH, (AxCanvas.HEIGHT_DP * scale).roundToInt())
        val density = remember(scale) { Density(density = scale, fontScale = 1f) }
        val colors = AxTheme.colors
        val toastState = remember { AxToastState() }

        CompositionLocalProvider(
            LocalDensity provides density,
            LocalAxToastState provides toastState,
        ) {
            Box(
                modifier = Modifier
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints.fixed(widthPx, heightPx))
                        layout(widthPx, heightPx) { placeable.place(0, 0) }
                    }
                    .clipToBounds()
                    .drawWithCache {
                        val brush = Brush.verticalGradient(listOf(colors.backgroundTop, colors.background))
                        onDrawBehind { drawRect(brush) }
                    },
            ) {
                AxOverlayHost(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), content = content)
                }
                AxToastHost(state = toastState)
            }
        }
    }
}
