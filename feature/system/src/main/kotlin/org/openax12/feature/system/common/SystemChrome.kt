package org.openax12.feature.system.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxDimens
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.SetupTabItem
import org.openax12.feature.system.R

/**
 * Top bar of every system screen: the dark navy chrome of the model-setup screens with a back
 * arrow, the screen title, optional tab pills and trailing actions. There is no Save button:
 * system settings are applied immediately.
 *
 * @param onBack back arrow; null hides it
 * @param tabs optional tabs shown as light pills after the title
 * @param trailing content at the end of the bar (a status chip, a button)
 */
@Composable
internal fun SystemBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tabs: List<SetupTabItem> = emptyList(),
    selectedTabId: String? = null,
    onTabSelected: (String) -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = AxTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AxDimens.SetupBarHeight)
            .background(colors.chrome),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            AxIconButton(
                icon = AxIcons.Back,
                contentDescription = stringResource(R.string.system_back),
                onClick = onBack,
                tint = colors.onChrome,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            text = title,
            style = AxTheme.typography.chrome,
            color = colors.onChrome,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (onBack != null) 4.dp else 16.dp, end = 12.dp, top = if (tabs.isNotEmpty()) 5.dp else 0.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tabs.forEach { tab ->
                val selected = tab.id == selectedTabId
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(top = 6.dp)
                        .then(
                            if (selected) {
                                Modifier.background(colors.chromeSelected, RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                            } else {
                                Modifier
                            },
                        )
                        .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onTabSelected(tab.id) },
                        )
                        .semantics { this.selected = selected }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tab.label,
                        style = AxTheme.typography.chrome,
                        fontWeight = if (selected) FontWeight.Medium else null,
                        color = if (selected) colors.onChromeSelected else colors.onChrome,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = trailing,
        )
    }
}

/** Outlined button for the dark chrome bar (the regular buttons are drawn for light surfaces). */
@Composable
internal fun ChromeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = AxTheme.colors
    Row(
        modifier = modifier
            .height(36.dp)
            .border(1.dp, colors.onChrome.copy(alpha = if (enabled) 0.7f else 0.3f), RoundedCornerShape(AxTheme.shapes.control))
            .clip(RoundedCornerShape(AxTheme.shapes.control))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AxIcon(icon = icon, contentDescription = null, tint = colors.onChrome, size = 18.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, style = AxTheme.typography.button, color = colors.onChrome, maxLines = 1)
    }
}
