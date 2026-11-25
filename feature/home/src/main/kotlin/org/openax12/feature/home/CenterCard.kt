package org.openax12.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDimens
import org.openax12.core.designsystem.AxIconButton
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
 * The centre card: model name, the picture that flips to the pinned telemetry widgets, and the
 * timers.
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
    AxCard(modifier = modifier, contentPadding = PaddingValues(12.dp)) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NameRow(
                name = state.modelName.ifBlank { stringResource(R.string.home_no_model) },
                showingWidgets = state.centerCard == HomeCenterCard.WIDGETS,
                onOpenModels = { onAction(HomeAction.OpenModels) },
                onToggle = { onAction(HomeAction.ToggleCenterCard) },
            )
            FlipArea(
                state = state,
                widgetDataSource = widgetDataSource,
                onToggle = { onAction(HomeAction.ToggleCenterCard) },
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
    }
}

/** Model name (opens Models) with the flip toggle on the right; the name stays centred. */
@Composable
private fun NameRow(
    name: String,
    showingWidgets: Boolean,
    onOpenModels: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AxDimens.MinTouch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(AxDimens.MinTouch))
        val openModels = stringResource(R.string.home_cd_open_models)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(role = Role.Button, onClickLabel = openModels, onClick = onOpenModels),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name,
                style = AxTheme.typography.title,
                color = AxTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        AxIconButton(
            icon = if (showingWidgets) AxIcons.Image else AxIcons.Widgets,
            contentDescription = stringResource(if (showingWidgets) R.string.home_cd_show_picture else R.string.home_cd_show_widgets),
            onClick = onToggle,
            tint = AxTheme.colors.textMuted,
        )
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
    modifier: Modifier = Modifier,
) {
    val showWidgets = state.centerCard == HomeCenterCard.WIDGETS
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
            PictureFace(state, onToggle)
        } else {
            // The back face is drawn mirrored by the rotation; turn it back.
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                WidgetsFace(state, widgetDataSource, onToggle)
            }
        }
    }
}

@Composable
private fun PictureFace(state: HomeUiState, onToggle: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AxWell(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(MODEL_PICTURE_ASPECT, matchHeightConstraintsFirst = true)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.home_cd_show_widgets), onClick = onToggle),
            cornerRadius = 16.dp,
            clipContent = true,
        ) {
            ModelPicture(
                picturePath = state.picturePath,
                pictureVersion = state.pictureVersion,
                type = state.modelType,
                modifier = Modifier.fillMaxSize(),
            )
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
