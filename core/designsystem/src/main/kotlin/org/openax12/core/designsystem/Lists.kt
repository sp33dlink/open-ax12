package org.openax12.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Title of a group of rows or settings.
 *
 * @param trailing optional content at the end (a button, a count)
 */
@Composable
fun AxSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = AxTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 30.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .drawBehind { drawRoundRect(colors.accent, cornerRadius = CornerRadius(size.width / 2f)) },
        )
        Text(
            text = title,
            style = AxTheme.typography.titleSmall,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        )
        if (trailing != null) trailing()
    }
}

/**
 * List row: optional leading icon, title with optional subtitle, optional trailing content
 * (value, switch, chevron). At least 48 dp high.
 *
 * Rows are flat; put them in an [AxListGroup] or an [AxCard] to get the raised container. The
 * selected row gets the accent border of the stock setup screens.
 *
 * @param onClick makes the row clickable
 * @param selected highlights the row (current model, row being edited)
 * @param leading replaces [leadingIcon] with arbitrary content (a picture)
 * @param trailing content at the end of the row
 */
@Composable
fun AxListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = AxTheme.colors
    val radius = AxTheme.shapes.control
    val border = AxTheme.shapes.border + 0.5.dp
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .drawBehind {
                val r = CornerRadius(radius.toPx())
                if (selected) drawRoundRect(colors.accent.copy(alpha = 0.10f), cornerRadius = r)
                if (pressed) drawRoundRect(colors.accent.copy(alpha = 0.12f), cornerRadius = r)
                if (selected) {
                    val b = border.toPx()
                    drawRoundRect(
                        color = colors.outlineStrong,
                        topLeft = Offset(b / 2f, b / 2f),
                        size = Size(size.width - b, size.height - b),
                        cornerRadius = r,
                        style = Stroke(width = b),
                    )
                }
            }
            .clip(RoundedCornerShape(radius))
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics { this.selected = selected }
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        } else if (leadingIcon != null) {
            AxIcon(icon = leadingIcon, contentDescription = null, tint = if (selected) colors.accent else colors.textMuted, size = 22.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = AxTheme.typography.bodyStrong,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = AxTheme.typography.label,
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Hairline between rows of an [AxListGroup]. */
@Composable
fun AxDivider(modifier: Modifier = Modifier, inset: Dp = 12.dp) {
    val color = AxTheme.colors.outline
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(1.dp)
            .drawBehind { drawRect(color.copy(alpha = color.alpha * 0.6f)) },
    )
}

/**
 * Raised card holding a column of rows ([AxListRow], [AxSettingRow]) separated by [AxDivider]s.
 */
@Composable
fun AxListGroup(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    AxCard(modifier = modifier, cornerRadius = AxTheme.shapes.panel, elevation = 4.dp, contentPadding = contentPadding) {
        Column(content = content)
    }
}

/**
 * Settings row: label (with optional description) on the left, a control on the right
 * ([AxSwitch], [NumberField], [AxDropdown], [AxSegmented] …).
 */
@Composable
fun AxSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    control: @Composable RowScope.() -> Unit,
) {
    AxListRow(
        title = title,
        modifier = modifier,
        subtitle = description,
        leadingIcon = icon,
        enabled = enabled,
        trailing = control,
    )
}
