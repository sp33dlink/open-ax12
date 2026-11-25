package org.openax12.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxDimens
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIconButtonVariant
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxWell
import org.openax12.core.designsystem.ModelPicture
import org.openax12.core.designsystem.TimerRow
import org.openax12.core.model.HomeCenterCard
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.MODEL_PICTURE_ASPECT
import org.openax12.core.widgets.PinnedWidgetGrid
import org.openax12.core.widgets.WidgetDataSource

/** Duration of the flip between model picture and pinned widgets. */
private const val FLIP_MILLIS = 420

/**
 * The centre card: the pinned telemetry widgets (or, when none are pinned or the user flipped it,
 * the model picture) filling all the height the timers leave, with a compact flip button in the
 * top-right corner. The model name is only shown in the status bar.
 *
 * Timers cannot be started or stopped by hand (the firmware has no such command): a tap on a row
 * or its gear opens the timer settings, a long-press resets the timer.
 */
@Composable
internal fun CenterCard(
    state: HomeUiState,
    live: () -> LiveSnapshot,
    widgetDataSource: WidgetDataSource,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    AxCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlipArea(
                state = state,
                widgetDataSource = widgetDataSource,
                onToggle = { onAction(HomeAction.ToggleCenterCard) },
                onPinWidgets = { onAction(HomeAction.OpenTelemetry) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            for (timer in state.timers) {
                val colors = AxTheme.colors
                TimerRow(
                    label = stringResource(R.string.home_timer_label, timer.index + 1),
                    seconds = { live().timer(timer.index)?.count ?: 0 },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AxDimens.MinTouch),
                    labelColor = when (timer.index) {
                        0 -> colors.orange
                        1 -> colors.teal
                        else -> colors.accent
                    },
                    running = { live().timer(timer.index)?.running == true },
                    onClick = { onAction(HomeAction.OpenTimerSettings) },
                    onLongClick = { onAction(HomeAction.ResetTimer(timer.index)) },
                    onSettings = { onAction(HomeAction.OpenTimerSettings) },
                )
            }
        }
        if (state.hasPinnedWidgets) {
            val showingWidgets = state.centerCard == HomeCenterCard.WIDGETS
            AxIconButton(
                icon = if (showingWidgets) AxIcons.Image else AxIcons.Widgets,
                contentDescription = stringResource(if (showingWidgets) R.string.home_cd_show_picture else R.string.home_cd_show_widgets),
                onClick = { onAction(HomeAction.ToggleCenterCard) },
                modifier = Modifier.align(Alignment.TopEnd),
                variant = AxIconButtonVariant.Raised,
                tint = AxTheme.colors.textMuted,
            )
        }
    }
}

/**
 * The area that flips between the model picture (front) and the pinned widgets (back). The
 * content swaps at the half-way point of the rotation. Without animation (first frame, screenshot
 * tests) it shows the target face at once.
 */
@Composable
private fun FlipArea(
    state: HomeUiState,
    widgetDataSource: WidgetDataSource,
    onToggle: () -> Unit,
    onPinWidgets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showWidgets = state.hasPinnedWidgets && state.centerCard == HomeCenterCard.WIDGETS
    val rotation by animateFloatAsState(
        targetValue = if (showWidgets) 180f else 0f,
        animationSpec = tween(FLIP_MILLIS),
        label = "centerCardFlip",
    )
    val cameraDistance = 14f * LocalDensity.current.density
    Box(
        modifier = modifier.graphicsLayer {
            rotationY = rotation
            this.cameraDistance = cameraDistance
        },
        contentAlignment = Alignment.Center,
    ) {
        if (rotation <= 90f) {
            PictureFace(state, onToggle, onPinWidgets)
        } else {
            // The back face is drawn mirrored by the rotation; turn it back.
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                WidgetsFace(state, widgetDataSource, onToggle)
            }
        }
    }
}

@Composable
private fun PictureFace(state: HomeUiState, onToggle: () -> Unit, onPinWidgets: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Without pinned widgets there is nothing to flip to: the picture is not a button then.
        val flip = if (state.hasPinnedWidgets) {
            Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.home_cd_show_widgets), onClick = onToggle)
        } else {
            Modifier
        }
        AxWell(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(MODEL_PICTURE_ASPECT, matchHeightConstraintsFirst = true)
                .then(flip),
            cornerRadius = 16.dp,
            clipContent = true,
            contentAlignment = Alignment.TopStart,
        ) {
            ModelPicture(
                picturePath = state.picturePath,
                pictureVersion = state.pictureVersion,
                type = state.modelType,
                modifier = Modifier.fillMaxSize(),
            )
            if (!state.hasPinnedWidgets) {
                AxButton(
                    text = stringResource(R.string.home_pin_widgets),
                    onClick = onPinWidgets,
                    modifier = Modifier.padding(start = 6.dp, top = 2.dp),
                    icon = AxIcons.Pin,
                )
            }
        }
    }
}

@Composable
private fun WidgetsFace(state: HomeUiState, widgetDataSource: WidgetDataSource, onToggle: () -> Unit) {
    AxWell(
        modifier = Modifier
            .fillMaxSize()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.home_cd_show_picture), onClick = onToggle),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(6.dp),
        clipContent = true,
    ) {
        PinnedWidgetGrid(
            widgets = state.pinnedWidgets,
            modifier = Modifier.fillMaxSize(),
            onWidgetClick = { onToggle() },
            dataSource = widgetDataSource,
        )
    }
}
