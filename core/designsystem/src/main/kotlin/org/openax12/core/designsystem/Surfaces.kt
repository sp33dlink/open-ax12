package org.openax12.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Raised neumorphic card: the basic container of the stock look (stick pads, centre card,
 * list groups). Flat with an outline in the dark and high-contrast schemes.
 *
 * @param cornerRadius corner radius, [AxShapes.card] by default
 * @param elevation shadow offset; keep about twice this much free space around the card
 * @param color face colour
 * @param onClick makes the whole card a button that sinks in while pressed
 * @param contentPadding padding inside the card
 * @param clipContent clips the content to the rounded shape (pictures, lists that scroll)
 */
@Composable
fun AxCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = AxTheme.shapes.card,
    elevation: Dp = 5.dp,
    color: Color = AxTheme.colors.surface,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    clipContent: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = if (onClick != null) remember { MutableInteractionSource() } else null
    val pressed = if (interaction != null) {
        val isPressed by interaction.collectIsPressedAsState()
        isPressed
    } else {
        false
    }
    var m = modifier.axRaised(cornerRadius = cornerRadius, elevation = elevation, color = color, pressed = pressed)
    if (clipContent || onClick != null) m = m.clip(RoundedCornerShape(cornerRadius))
    if (onClick != null && interaction != null) {
        m = m.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    }
    CompositionLocalProvider(LocalContentColor provides AxTheme.colors.text) {
        Box(modifier = m.padding(contentPadding), contentAlignment = contentAlignment, content = content)
    }
}

/**
 * Sunken neumorphic well: tracks, value boxes, the picture frame of the centre card. Flat with
 * an outline in the dark and high-contrast schemes.
 *
 * @param cornerRadius corner radius, [AxShapes.control] by default
 * @param depth inner shadow depth
 * @param color face colour
 * @param contentPadding padding inside the well
 * @param clipContent clips the content to the rounded shape
 */
@Composable
fun AxWell(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = AxTheme.shapes.control,
    depth: Dp = 2.dp,
    color: Color = AxTheme.colors.surfaceSunken,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    clipContent: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    var m = modifier.axSunken(cornerRadius = cornerRadius, depth = depth, color = color)
    if (clipContent) m = m.clip(RoundedCornerShape(cornerRadius))
    CompositionLocalProvider(LocalContentColor provides AxTheme.colors.text) {
        Box(modifier = m.padding(contentPadding), contentAlignment = contentAlignment, content = content)
    }
}
