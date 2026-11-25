package org.openax12.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Largest height a dialog may take on the 480 dp canvas. */
val AxDialogMaxHeight: Dp = 452.dp

/**
 * Modal dialog on the fixed canvas. It is an in-tree overlay ([AxOverlay]), not a platform
 * window: it scales with the canvas, keeps the immersive mode and renders in screenshot tests.
 *
 * Show it by calling it while some state is true; remove the call to close it.
 *
 * @param onDismissRequest called for back and for taps on the scrim (if enabled)
 * @param title optional title
 * @param icon optional icon before the title
 * @param width dialog width
 * @param alignment position on the canvas; use [Alignment.TopCenter] for dialogs with a text
 *   field so the soft keyboard does not cover them
 * @param dismissOnOutsideClick taps on the scrim dismiss
 * @param dismissOnBack back dismisses; false swallows back (e.g. while saving)
 * @param buttons button row at the bottom, right-aligned
 * @param content dialog body; it is not scrollable by itself
 */
@Composable
fun AxDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = AxTheme.colors.accent,
    width: Dp = 420.dp,
    alignment: Alignment = Alignment.Center,
    dismissOnOutsideClick: Boolean = true,
    dismissOnBack: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AxTheme.colors
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    AxOverlay(
        zIndex = AX_OVERLAY_DIALOG,
        onBack = { if (dismissOnBack) currentDismiss() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .pointerInput(dismissOnOutsideClick) {
                    detectTapGestures { if (dismissOnOutsideClick) currentDismiss() }
                }
                .padding(12.dp),
            contentAlignment = alignment,
        ) {
            AxDialogSurface(
                modifier = modifier,
                title = title,
                icon = icon,
                iconTint = iconTint,
                width = width,
                contentPadding = contentPadding,
                buttons = buttons,
                content = content,
            )
        }
    }
}

/**
 * The dialog panel without scrim and overlay: use it to embed dialog-like content in a screen
 * or in a preview. [AxDialog] wraps it.
 */
@Composable
fun AxDialogSurface(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = AxTheme.colors.accent,
    width: Dp = 420.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AxTheme.colors
    val radius = AxTheme.shapes.panel
    val border = AxTheme.shapes.border
    CompositionLocalProvider(LocalContentColor provides colors.text) {
        Column(
            modifier = modifier
                .width(width)
                .heightIn(max = AxDialogMaxHeight)
                .drawBehind {
                    val r = CornerRadius(radius.toPx())
                    drawRoundRect(colors.surfaceBright, cornerRadius = r)
                    if (!colors.neumorphic) {
                        val b = border.toPx()
                        drawRoundRect(
                            color = colors.outline,
                            topLeft = Offset(b / 2f, b / 2f),
                            size = Size(size.width - b, size.height - b),
                            cornerRadius = r,
                            style = Stroke(b),
                        )
                    }
                }
                .clip(RoundedCornerShape(radius))
                // Taps inside the panel must not reach the scrim.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(top = 14.dp, bottom = if (buttons != null) 6.dp else 16.dp),
        ) {
            if (title != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        AxIcon(icon = icon, contentDescription = null, tint = iconTint, size = 22.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = title,
                        style = AxTheme.typography.title,
                        color = colors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
            if (buttons != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

/** Scrollable message text of a dialog. */
@Composable
fun AxDialogMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AxTheme.typography.body,
        color = AxTheme.colors.textMuted,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState()),
    )
}

/**
 * Yes / no question.
 *
 * @param destructive draws the confirm button in the error colour and shows a warning icon
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = stringResource(R.string.ax_ok),
    dismissLabel: String = stringResource(R.string.ax_cancel),
    destructive: Boolean = false,
    icon: ImageVector? = if (destructive) AxIcons.Warning else null,
) {
    AxDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = title,
        icon = icon,
        iconTint = if (destructive) AxTheme.colors.error else AxTheme.colors.accent,
        buttons = {
            AxButton(text = dismissLabel, onClick = onDismiss, variant = AxButtonVariant.Text)
            AxButton(
                text = confirmLabel,
                onClick = onConfirm,
                variant = if (destructive) AxButtonVariant.Danger else AxButtonVariant.Primary,
            )
        },
    ) {
        AxDialogMessage(message)
    }
}

/**
 * Two-step confirmation for destructive actions (delete a model, restore defaults, write to a
 * linked radio): the first step asks, the second explains the consequence and requires holding
 * the confirm button.
 *
 * @param message first question
 * @param secondMessage consequence shown in the second step ("This cannot be undone.")
 * @param confirmLabel label of the final hold-to-confirm button ("Hold to delete")
 */
@Composable
fun DoubleConfirmDialog(
    title: String,
    message: String,
    secondMessage: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    continueLabel: String = stringResource(R.string.ax_continue),
    dismissLabel: String = stringResource(R.string.ax_cancel),
    initialStep: Int = 0,
) {
    var step by remember { mutableIntStateOf(initialStep) }
    AxDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = title,
        icon = AxIcons.Warning,
        iconTint = AxTheme.colors.error,
        dismissOnOutsideClick = false,
        buttons = {
            AxButton(text = dismissLabel, onClick = onDismiss, variant = AxButtonVariant.Text)
            if (step == 0) {
                AxButton(text = continueLabel, onClick = { step = 1 }, variant = AxButtonVariant.Danger)
            } else {
                HoldToConfirmButton(text = confirmLabel, onConfirmed = onConfirm)
            }
        },
    ) {
        AxDialogMessage(if (step == 0) message else secondMessage)
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val colors = AxTheme.colors
            for (i in 0..1) {
                Box(
                    modifier = Modifier
                        .width(if (i == step) 18.dp else 8.dp)
                        .height(4.dp)
                        .background(if (i <= step) colors.error else colors.track, RoundedCornerShape(2.dp)),
                )
            }
            Text(
                text = stringResource(R.string.ax_step_of, step + 1, 2),
                style = AxTheme.typography.labelSmall,
                color = colors.textMuted,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * Button that must be held for [holdMillis] before it fires; a fill shows the progress and
 * releasing early cancels. Used as the last step of destructive confirmations and for the touch
 * lock.
 */
@Composable
fun HoldToConfirmButton(
    text: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 1200,
    color: Color = AxTheme.colors.error,
    contentColor: Color = Color.White,
    icon: ImageVector? = null,
    height: Dp = AxDimens.ControlHeight,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnConfirmed by rememberUpdatedState(onConfirmed)
    val radius = AxTheme.shapes.control
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = AxDimens.MinTouch)
            .pointerInput(holdMillis) {
                detectTapGestures(
                    onPress = {
                        val job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(durationMillis = holdMillis, easing = LinearEasing))
                            currentOnConfirmed()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) {
                            job.cancel()
                            scope.launch { progress.animateTo(0f, tween(durationMillis = 160)) }
                        }
                    },
                )
            }
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(height)
                .clip(RoundedCornerShape(radius))
                .drawBehind {
                    drawRect(color.copy(alpha = 0.55f))
                    drawRect(color, size = Size(size.width * progress.value, size.height))
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                AxIcon(icon = icon, contentDescription = null, tint = contentColor, size = 18.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(text = text, style = AxTheme.typography.button, color = contentColor, maxLines = 1)
        }
    }
}

/**
 * The Save / Discard / Cancel dialog the app shell shows when leaving model setup with unsaved
 * changes (shared UI contract, `docs/CONTRACTS.md`).
 *
 * @param saving true while the save is running: buttons are disabled and Save shows progress
 */
@Composable
fun UnsavedChangesDialog(saving: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AxDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = stringResource(R.string.ax_unsaved_title),
        icon = AxIcons.Save,
        dismissOnOutsideClick = false,
        dismissOnBack = !saving,
        width = 440.dp,
        buttons = {
            AxButton(text = stringResource(R.string.ax_cancel), onClick = onCancel, variant = AxButtonVariant.Text, enabled = !saving)
            AxButton(text = stringResource(R.string.ax_discard), onClick = onDiscard, variant = AxButtonVariant.Outlined, enabled = !saving)
            AxButton(
                text = stringResource(if (saving) R.string.ax_saving else R.string.ax_save),
                onClick = onSave,
                variant = AxButtonVariant.Primary,
                enabled = !saving,
            )
        },
    ) {
        AxDialogMessage(stringResource(R.string.ax_unsaved_message))
    }
}
