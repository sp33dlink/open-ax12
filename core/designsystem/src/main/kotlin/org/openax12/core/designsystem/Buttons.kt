package org.openax12.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Single-colour icon from [AxIcons] (or any [ImageVector]). */
@Composable
fun AxIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Icon(imageVector = icon, contentDescription = contentDescription, modifier = modifier.size(size), tint = tint)
}

/** Visual styles of [AxButton]. */
enum class AxButtonVariant {
    /** Neumorphic raised button that sinks in while pressed: the default. */
    Raised,

    /** Filled with the accent colour: the one main action of a dialog or screen. */
    Primary,

    /** Outline only, as the stock "Add input" button. */
    Outlined,

    /** Text only, for low-emphasis actions. */
    Text,

    /** Filled with the error colour: destructive actions. */
    Danger,

    /** Outline and label in the error colour, no fill: the entry point of a destructive action. */
    DangerOutlined,
}

/**
 * Button with a 44 dp touch target.
 *
 * @param text label
 * @param variant visual style
 * @param icon optional leading icon
 * @param height visual height; the touch target is never smaller than 44 dp
 */
@Composable
fun AxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AxButtonVariant = AxButtonVariant.Raised,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = AxDimens.ControlHeight,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val colors = AxTheme.colors
    val shapes = AxTheme.shapes
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val contentColor = when (variant) {
        AxButtonVariant.Raised -> colors.text
        AxButtonVariant.Primary, AxButtonVariant.Danger -> colors.onAccent
        AxButtonVariant.Outlined -> colors.text
        AxButtonVariant.DangerOutlined -> colors.error
        AxButtonVariant.Text -> colors.accent
    }
    val fill = when (variant) {
        AxButtonVariant.Primary -> colors.accent
        AxButtonVariant.Danger -> colors.error
        else -> Color.Unspecified
    }
    val radius = shapes.control
    val visual: Modifier = when (variant) {
        AxButtonVariant.Raised -> Modifier.axRaised(cornerRadius = radius, elevation = 3.dp, color = colors.surfaceRaised, pressed = pressed && enabled)
        AxButtonVariant.Primary, AxButtonVariant.Danger -> {
            val base = if (colors.neumorphic) {
                Modifier.axRaised(cornerRadius = radius, elevation = 3.dp, color = fill)
            } else {
                Modifier.drawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(radius.toPx())) }
            }
            base.drawBehind {
                if (pressed) drawRoundRect(Color.Black.copy(alpha = 0.18f), cornerRadius = CornerRadius(radius.toPx()))
            }
        }
        AxButtonVariant.Outlined, AxButtonVariant.DangerOutlined -> {
            val borderColor = when {
                variant == AxButtonVariant.DangerOutlined -> colors.error
                colors.neumorphic -> colors.outlineStrong
                else -> colors.text
            }
            val border = shapes.border + 0.5.dp
            Modifier.drawBehind {
                val b = border.toPx()
                val r = CornerRadius(radius.toPx())
                if (pressed) drawRoundRect(colors.accent.copy(alpha = 0.14f), cornerRadius = r)
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset(b / 2f, b / 2f),
                    size = Size(size.width - b, size.height - b),
                    cornerRadius = r,
                    style = Stroke(width = b),
                )
            }
        }
        AxButtonVariant.Text -> Modifier.drawBehind {
            if (pressed) drawRoundRect(colors.accent.copy(alpha = 0.14f), cornerRadius = CornerRadius(radius.toPx()))
        }
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = AxDimens.MinTouch)
            .alpha(if (enabled) 1f else 0.42f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The visual is as wide as the touch target but only [height] tall, centred.
        Box(
            modifier = Modifier
                .matchParentSize()
                .wrapContentHeight()
                .height(height)
                .then(visual),
        )
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                AxIcon(icon = icon, contentDescription = null, tint = contentColor, size = 18.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = AxTheme.typography.button,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Visual styles of [AxIconButton]. */
enum class AxIconButtonVariant {
    /** Icon only. */
    Plain,

    /** Icon on a small raised disc (the gear of a timer row). */
    Raised,
}

/**
 * Icon button with a 44 dp touch target.
 *
 * @param contentDescription spoken label; required because the button has no text
 * @param tint icon colour; defaults to the content colour of the surroundings
 * @param size touch target size
 * @param iconSize size of the glyph
 */
@Composable
fun AxIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AxIconButtonVariant = AxIconButtonVariant.Plain,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    size: Dp = AxDimens.MinTouch,
    iconSize: Dp = 24.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (variant) {
            AxIconButtonVariant.Raised -> Box(
                modifier = Modifier
                    .size(size - 12.dp)
                    .axRaised(cornerRadius = size, elevation = 2.5.dp, color = AxTheme.colors.surfaceRaised, pressed = pressed && enabled),
            )
            AxIconButtonVariant.Plain -> if (pressed) {
                val highlight = tint.copy(alpha = 0.14f)
                Box(modifier = Modifier.fillMaxSize().drawBehind { drawCircle(highlight) })
            }
        }
        AxIcon(icon = icon, contentDescription = contentDescription, tint = tint, size = iconSize)
    }
}

/**
 * On / off switch: sunken track, raised thumb, accent fill when on. 44 dp touch target.
 */
@Composable
fun AxSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AxTheme.colors
    val position by animateFloatAsState(targetValue = if (checked) 1f else 0f, label = "AxSwitch")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(width = 56.dp, height = AxDimens.MinTouch)
            .alpha(if (enabled) 1f else 0.42f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 26.dp)
                .axSunken(cornerRadius = 13.dp, depth = 2.dp, color = colors.surfaceSunken)
                .drawBehind {
                    if (position > 0f) {
                        drawRoundRect(
                            color = colors.accent.copy(alpha = position),
                            cornerRadius = CornerRadius(size.height / 2f),
                        )
                    }
                    val thumb = 9.5.dp.toPx()
                    val start = size.height / 2f
                    val end = size.width - size.height / 2f
                    val cx = start + (end - start) * position
                    if (colors.neumorphic) {
                        drawCircle(colors.shadowDark.copy(alpha = 0.45f), radius = thumb, center = Offset(cx + 1.dp.toPx(), size.height / 2f + 1.5.dp.toPx()))
                    }
                    drawCircle(if (colors.neumorphic) Color.White else colors.surfaceRaised, radius = thumb, center = Offset(cx, size.height / 2f))
                    if (!colors.neumorphic) {
                        drawCircle(colors.outline, radius = thumb, center = Offset(cx, size.height / 2f), style = Stroke(1.dp.toPx()))
                    }
                },
        )
    }
}

/**
 * Segmented selector for 2 to about 5 short options: sunken track, the selected option is a
 * raised pill.
 */
@Composable
fun AxSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AxTheme.colors
    Row(
        modifier = modifier
            .height(AxDimens.ControlHeight)
            .alpha(if (enabled) 1f else 0.42f)
            .axSunken(cornerRadius = AxTheme.shapes.control + 2.dp, depth = 2.dp)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (selected) {
                            Modifier.axRaised(
                                cornerRadius = AxTheme.shapes.control - 1.dp,
                                elevation = 2.dp,
                                color = if (colors.neumorphic) colors.surfaceBright else colors.accent,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(RoundedCornerShape(AxTheme.shapes.control))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = { onSelected(index) },
                    )
                    .semantics { this.selected = selected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = if (selected) AxTheme.typography.button else AxTheme.typography.body,
                    color = when {
                        !selected -> colors.textMuted
                        colors.neumorphic -> colors.accent
                        else -> colors.onAccent
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/**
 * The stock "value box": a slightly raised field showing a value that is edited by tapping it
 * (source, weight, curve, mode … in the setup rows). Base of [NumberField] and [AxDropdown].
 *
 * @param text the value; use an em dash for "none"
 * @param onClick opens the editor; null makes the box read-only (drawn sunken)
 * @param trailingIcon e.g. the dropdown chevron
 * @param highlighted draws the accent border (the row or field being edited)
 */
@Composable
fun AxValueBox(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
    leadingIcon: ImageVector? = null,
    highlighted: Boolean = false,
    textStyle: TextStyle = AxTheme.typography.body,
    textAlign: TextAlign = TextAlign.Center,
    height: Dp = AxDimens.ControlHeight,
) {
    val colors = AxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val radius = AxTheme.shapes.small
    val border = AxTheme.shapes.border + 0.5.dp
    val base = if (onClick != null) {
        Modifier.axRaised(cornerRadius = radius, elevation = 2.dp, color = colors.surfaceRaised, pressed = pressed && enabled)
    } else {
        Modifier.axSunken(cornerRadius = radius, depth = 1.5.dp)
    }
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = 44.dp)
            .height(height)
            .alpha(if (enabled) 1f else 0.42f)
            .then(base)
            .drawBehind {
                if (highlighted) {
                    val b = border.toPx()
                    drawRoundRect(
                        color = colors.outlineStrong,
                        topLeft = Offset(b / 2f, b / 2f),
                        size = Size(size.width - b, size.height - b),
                        cornerRadius = CornerRadius(radius.toPx()),
                        style = Stroke(width = b),
                    )
                }
            }
            .clip(RoundedCornerShape(radius))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            AxIcon(icon = leadingIcon, contentDescription = null, tint = colors.textMuted, size = 16.dp)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = textStyle,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.weight(1f),
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(2.dp))
            AxIcon(icon = trailingIcon, contentDescription = null, tint = colors.textMuted, size = 18.dp)
        }
    }
}

/**
 * Slider for a continuous setting (volume, brightness): sunken track with accent fill and a
 * raised thumb. Tap or drag anywhere on the track.
 *
 * @param value current value in [valueRange]
 * @param onValueChange called while dragging
 * @param steps number of discrete positions between the ends (0 = continuous)
 * @param onValueChangeFinished called when the finger lifts
 */
@Composable
fun AxSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = AxTheme.colors
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    fun valueAt(x: Float, width: Float, thumb: Float): Float {
        val usable = (width - 2f * thumb).coerceAtLeast(1f)
        var f = ((x - thumb) / usable).coerceIn(0f, 1f)
        if (steps > 0) {
            val n = steps + 1
            f = (f * n).roundToInt() / n.toFloat()
        }
        return valueRange.start + f * span
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 120.dp)
            .height(AxDimens.MinTouch)
            .alpha(if (enabled) 1f else 0.42f)
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(valueRange, steps) {
                            val thumb = 11.dp.toPx()
                            detectTapGestures { offset ->
                                currentOnChange(valueAt(offset.x, size.width.toFloat(), thumb))
                                currentOnFinished?.invoke()
                            }
                        }
                        .pointerInput(valueRange, steps) {
                            val thumb = 11.dp.toPx()
                            detectHorizontalDragGestures(
                                onDragEnd = { currentOnFinished?.invoke() },
                                onDragCancel = { currentOnFinished?.invoke() },
                            ) { change, _ ->
                                change.consume()
                                currentOnChange(valueAt(change.position.x, size.width.toFloat(), thumb))
                            }
                        }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 17.dp, horizontal = 4.dp)
                .axSunken(cornerRadius = 5.dp, depth = 1.5.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val thumb = 11.dp.toPx()
                    val cy = size.height / 2f
                    val cx = thumb + (size.width - 2f * thumb) * fraction
                    val trackH = 6.dp.toPx()
                    drawRoundRect(
                        color = colors.accent,
                        topLeft = Offset(6.dp.toPx(), cy - trackH / 2f),
                        size = Size((cx - 6.dp.toPx()).coerceAtLeast(0f), trackH),
                        cornerRadius = CornerRadius(trackH / 2f),
                    )
                    if (colors.neumorphic) {
                        drawCircle(colors.shadowDark.copy(alpha = 0.5f), radius = thumb, center = Offset(cx + 1.5.dp.toPx(), cy + 2.dp.toPx()))
                        drawCircle(Color.White, radius = thumb, center = Offset(cx, cy))
                    } else {
                        drawCircle(colors.surfaceRaised, radius = thumb, center = Offset(cx, cy))
                        drawCircle(colors.text, radius = thumb, center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
                    }
                    drawCircle(colors.accent, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
                }
                .semantics { stateDescription = "${(fraction * 100).roundToInt()}%" },
        )
    }
}
