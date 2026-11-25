package org.openax12.feature.models

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxLoadingState
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.DoubleConfirmDialog
import org.openax12.core.designsystem.UnsavedChangesDialog

/**
 * Title row of a Models screen: optional back arrow, title (with an optional subtitle) and
 * [actions] at the end. The status bar and bottom bar belong to the app shell.
 */
@Composable
internal fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = AxTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = if (onBack != null) 6.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            AxIconButton(
                icon = AxIcons.Back,
                contentDescription = stringResource(R.string.models_back),
                onClick = onBack,
            )
            Spacer(Modifier.width(4.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = AxTheme.typography.title,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (subtitle != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(text = subtitle, style = AxTheme.typography.label, color = colors.textMuted, maxLines = 1)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Colour of an [InfoBanner]. */
internal enum class BannerTone { INFO, WARNING, ERROR }

/** One-line notice with an optional action, shown above a list. */
@Composable
internal fun InfoBanner(
    text: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = AxTheme.colors
    val tint = when (tone) {
        BannerTone.INFO -> colors.accent
        BannerTone.WARNING -> colors.warning
        BannerTone.ERROR -> colors.error
    }
    val icon: ImageVector = if (tone == BannerTone.INFO) AxIcons.Info else AxIcons.Warning
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(AxTheme.shapes.control))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AxIcon(icon = icon, contentDescription = null, tint = tint, size = 20.dp)
        Text(
            text = text,
            style = AxTheme.typography.label,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        if (actionLabel != null && onAction != null) {
            AxButton(text = actionLabel, onClick = onAction, variant = AxButtonVariant.Text)
        }
    }
}

/** Small pill: "Active" for the model loaded in the radio. */
@Composable
internal fun ActiveBadge(modifier: Modifier = Modifier) {
    val colors = AxTheme.colors
    Row(
        modifier = modifier
            .background(colors.teal, RoundedCornerShape(50))
            .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AxIcon(icon = AxIcons.Check, contentDescription = null, tint = Color.White, size = 14.dp)
        Spacer(Modifier.width(3.dp))
        Text(
            text = stringResource(R.string.models_active),
            style = AxTheme.typography.label,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** Scrim with a spinner that blocks the screen while a device operation runs. */
@Composable
internal fun BusyOverlay(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AxTheme.colors.scrim)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .background(AxTheme.colors.surfaceBright, RoundedCornerShape(AxTheme.shapes.panel))
                .padding(horizontal = 28.dp, vertical = 18.dp),
        ) {
            AxLoadingState(text = text)
        }
    }
}

/** The dialogs of a [DeviceWriteGuard]: unsaved changes, then the radio-link confirmation. */
@Composable
internal fun GuardDialogs(
    state: DeviceWriteGuard.GuardState,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onConfirmLink: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state.step) {
        GuardStep.NONE -> Unit
        GuardStep.UNSAVED -> UnsavedChangesDialog(
            saving = state.saving,
            onSave = onSave,
            onDiscard = onDiscard,
            onCancel = onCancel,
        )
        GuardStep.LINK_UP -> DoubleConfirmDialog(
            title = stringResource(R.string.models_link_title),
            message = stringResource(R.string.models_link_message),
            secondMessage = stringResource(R.string.models_link_second),
            confirmLabel = stringResource(R.string.models_link_hold),
            onConfirm = onConfirmLink,
            onDismiss = onCancel,
        )
    }
}

/** Width of the controls in settings rows of the detail, create and import screens. */
internal val RowControlWidth = 220.dp
