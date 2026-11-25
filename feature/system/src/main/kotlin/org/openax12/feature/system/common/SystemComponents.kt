package org.openax12.feature.system.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxListGroup
import org.openax12.core.designsystem.AxSectionHeader
import org.openax12.core.designsystem.AxTheme

/** Scrolling column with the standard padding of a settings panel. */
@Composable
internal fun PanelColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A titled raised group of rows. */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AxSectionHeader(title = title)
        AxListGroup(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp), content = content)
    }
}

/** How strongly a [NoticeBox] is emphasised. */
internal enum class NoticeKind { Info, Warning, Error }

/**
 * Box with an icon and a message, optionally with one action: explains why controls are disabled
 * or what to do about a problem.
 */
@Composable
internal fun NoticeBox(
    text: String,
    modifier: Modifier = Modifier,
    kind: NoticeKind = NoticeKind.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = AxTheme.colors
    val tint = when (kind) {
        NoticeKind.Info -> colors.accent
        NoticeKind.Warning -> colors.warning
        NoticeKind.Error -> colors.error
    }
    val icon: ImageVector = when (kind) {
        NoticeKind.Info -> AxIcons.Info
        NoticeKind.Warning -> AxIcons.Warning
        NoticeKind.Error -> AxIcons.Error
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = if (colors.isLight) 0.12f else 0.2f), RoundedCornerShape(AxTheme.shapes.control))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AxIcon(icon = icon, contentDescription = null, tint = tint, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = AxTheme.typography.body,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            AxButton(text = actionLabel, onClick = onAction, variant = AxButtonVariant.Outlined)
        }
    }
}

/** Secondary value text at the end of a list row. */
@Composable
internal fun ValueText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AxTheme.colors.textMuted,
    maxWidth: Dp = 330.dp,
) {
    Text(
        text = text,
        style = AxTheme.typography.value,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = modifier
            .padding(start = 8.dp)
            .widthIn(max = maxWidth),
    )
}
