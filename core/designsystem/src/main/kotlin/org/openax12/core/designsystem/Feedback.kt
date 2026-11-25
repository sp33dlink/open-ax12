package org.openax12.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Kind of a toast; selects icon and colour. */
enum class AxToastKind { Info, Success, Warning, Error }

/** A toast being shown. */
@Stable
class AxToastData internal constructor(
    val message: String,
    val kind: AxToastKind,
    val durationMillis: Long,
)

/**
 * Shows short messages at the bottom of the canvas ("Saved", "Model exported"). One toast at a
 * time; a new one replaces the current one.
 *
 * [FixedCanvas] provides an instance through [LocalAxToastState] and draws it:
 * `LocalAxToastState.current.show("Saved")`.
 */
@Stable
class AxToastState {
    /** The toast currently shown, or null. */
    var current: AxToastData? by mutableStateOf(null)
        private set

    /** Shows [message] for [durationMillis]. Safe to call from any thread that may write state. */
    fun show(message: String, kind: AxToastKind = AxToastKind.Info, durationMillis: Long = 2600L) {
        current = AxToastData(message, kind, durationMillis)
    }

    /** Hides the current toast. */
    fun dismiss() {
        current = null
    }

    internal fun dismiss(data: AxToastData) {
        if (current === data) current = null
    }
}

/** The toast state of the surrounding [FixedCanvas]. Outside of one, toasts are not displayed. */
val LocalAxToastState = staticCompositionLocalOf { AxToastState() }

/**
 * Draws the toast of [state] at the bottom centre, above the bottom bar. [FixedCanvas] already
 * contains one; use it directly only where no [FixedCanvas] is used.
 *
 * @param bottomPadding distance from the bottom edge
 */
@Composable
fun AxToastHost(
    state: AxToastState,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = AxDimens.BottomBarHeight + 10.dp,
) {
    val data = state.current ?: return
    LaunchedEffect(data) {
        delay(data.durationMillis)
        state.dismiss(data)
    }
    val colors = AxTheme.colors
    val (icon, tint) = when (data.kind) {
        AxToastKind.Info -> AxIcons.Info to colors.onChrome
        AxToastKind.Success -> AxIcons.Success to Color(0xFF4FE0A4)
        AxToastKind.Warning -> AxIcons.Warning to Color(0xFFFFC46B)
        AxToastKind.Error -> AxIcons.Error to Color(0xFFFF8A84)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .background(colors.chrome.copy(alpha = 0.94f), RoundedCornerShape(22.dp))
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AxIcon(icon = icon, contentDescription = null, tint = tint, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = data.message,
                style = AxTheme.typography.body,
                color = colors.onChrome,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Placeholder for an empty list or page: icon, title, optional explanation and action.
 */
@Composable
fun AxEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = AxIcons.Empty,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateBlock(
        title = title,
        modifier = modifier,
        icon = icon,
        iconTint = AxTheme.colors.textFaint,
        message = message,
        actionLabel = actionLabel,
        onAction = onAction,
        primaryAction = false,
    )
}

/**
 * Error block: what went wrong, an optional hint and a retry action.
 */
@Composable
fun AxErrorState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = AxIcons.Error,
    actionLabel: String? = stringResource(R.string.ax_retry),
    onAction: (() -> Unit)? = null,
) {
    StateBlock(
        title = title,
        modifier = modifier,
        icon = icon,
        iconTint = AxTheme.colors.error,
        message = message,
        actionLabel = actionLabel,
        onAction = onAction,
        primaryAction = true,
    )
}

@Composable
private fun StateBlock(
    title: String,
    modifier: Modifier,
    icon: ImageVector,
    iconTint: Color,
    message: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    primaryAction: Boolean,
) {
    val colors = AxTheme.colors
    Column(
        modifier = modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AxIcon(icon = icon, contentDescription = null, tint = iconTint, size = 40.dp)
        Text(
            text = title,
            style = AxTheme.typography.title,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (message != null) {
            Text(
                text = message,
                style = AxTheme.typography.body,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = 420.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            AxButton(
                text = actionLabel,
                onClick = onAction,
                variant = if (primaryAction) AxButtonVariant.Primary else AxButtonVariant.Raised,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** Indeterminate spinner. */
@Composable
fun AxSpinner(modifier: Modifier = Modifier, size: Dp = 28.dp, color: Color = AxTheme.colors.accent) {
    val transition = rememberInfiniteTransition(label = "AxSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    val track = AxTheme.colors.track
    Canvas(modifier = modifier.size(size)) {
        val stroke = this.size.minDimension * 0.14f
        val inset = stroke / 2f
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(color, angle - 90f, 100f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Centred spinner with an optional text ("Connecting to the radio…"). */
@Composable
fun AxLoadingState(modifier: Modifier = Modifier, text: String? = null) {
    Column(
        modifier = modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AxSpinner()
        if (text != null) {
            Text(
                text = text,
                style = AxTheme.typography.body,
                color = AxTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * Touch lock: while [locked] an overlay above everything (including dialogs) swallows all
 * touches and the back action, so nothing can be changed by accident in flight. The screen stays
 * fully visible. Holding the pill at the bottom for [holdMillis] calls [onUnlock].
 *
 * Place the call anywhere inside [FixedCanvas]; the app shell owns the `locked` state.
 */
@Composable
fun TouchLockOverlay(
    locked: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 1200,
) {
    if (!locked) return
    val currentOnUnlock by rememberUpdatedState(onUnlock)
    val colors = AxTheme.colors
    AxOverlay(zIndex = AX_OVERLAY_LOCK, onBack = { }) {
        Box(
            modifier = modifier
                .fillMaxSize()
                // Swallow every touch that does not hit the unlock pill.
                .pointerInput(Unit) { detectTapGestures { } }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            // Thin frame so the locked state is visible at a glance.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .height(AxDimens.MinTouch),
                contentAlignment = Alignment.Center,
            ) {
                HoldToConfirmButton(
                    text = stringResource(R.string.ax_touch_locked),
                    onConfirmed = { currentOnUnlock() },
                    holdMillis = holdMillis,
                    color = colors.chrome,
                    contentColor = colors.onChrome,
                    icon = AxIcons.TouchLock,
                )
            }
        }
    }
}
