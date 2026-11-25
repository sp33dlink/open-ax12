package org.openax12.feature.models

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.openax12.core.data.PictureCropRect
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxErrorState
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxLoadingState
import org.openax12.core.designsystem.AxSlider
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.model.MODEL_PICTURE_ASPECT
import kotlin.math.roundToInt

/**
 * The crop screen (SPEC section 4): the picked image behind a 4:3 frame (the model-card aspect).
 * Drag to move, pinch or use the zoom controls to scale; **Save** stores exactly what the frame
 * shows. [image] null = loading / unreadable according to [loading].
 *
 * @param onSave called with the crop rectangle (fractions of the upright image)
 */
@Composable
internal fun PictureCropScreen(
    loading: Boolean,
    image: ImageBitmap?,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: (PictureCropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableStateOf(IntSize.Zero) }
    var view by remember(image) { mutableStateOf(CropView()) }
    val geometry = remember(image, frame) {
        if (image != null && frame.width > 0 && frame.height > 0) {
            CropGeometry(image.width, image.height, frame.width.toFloat(), frame.height.toFloat())
        } else {
            null
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.models_crop_title), onBack = onBack) {
            AxButton(
                text = stringResource(R.string.models_crop_save),
                onClick = { geometry?.let { onSave(it.rect(view)) } },
                variant = AxButtonVariant.Primary,
                icon = AxIcons.Check,
                enabled = geometry != null && !saving,
            )
        }
        when {
            loading -> AxLoadingState(text = stringResource(R.string.models_crop_loading), modifier = Modifier.fillMaxSize())
            image == null -> AxErrorState(
                title = stringResource(R.string.models_crop_failed_title),
                message = stringResource(R.string.models_crop_failed_message),
                actionLabel = stringResource(R.string.models_back),
                onAction = onBack,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    CropFrame(
                        image = image,
                        view = view,
                        onFrameSize = { frame = it },
                        onGesture = { pan, zoom, centroid -> geometry?.let { view = it.transform(view, pan, zoom, centroid) } },
                    )
                }
                Column(modifier = Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.models_crop_hint),
                        style = AxTheme.typography.body,
                        color = AxTheme.colors.textMuted,
                    )
                    AxCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AxIconButton(
                                icon = AxIcons.Remove,
                                contentDescription = stringResource(R.string.models_crop_zoom_out),
                                onClick = { geometry?.let { view = it.withZoom(view, view.zoom - 0.25f) } },
                                enabled = geometry != null && view.zoom > 1f,
                            )
                            AxSlider(
                                value = view.zoom,
                                onValueChange = { z -> geometry?.let { view = it.withZoom(view, z) } },
                                valueRange = 1f..CROP_MAX_ZOOM,
                                modifier = Modifier.weight(1f),
                            )
                            AxIconButton(
                                icon = AxIcons.Add,
                                contentDescription = stringResource(R.string.models_crop_zoom_in),
                                onClick = { geometry?.let { view = it.withZoom(view, view.zoom + 0.25f) } },
                                enabled = geometry != null && view.zoom < CROP_MAX_ZOOM,
                            )
                        }
                    }
                    AxButton(
                        text = stringResource(R.string.models_crop_reset),
                        onClick = { view = CropView() },
                        variant = AxButtonVariant.Outlined,
                        icon = AxIcons.Reset,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** The 4:3 frame that shows the part of [image] selected by [view]; reports its pixel size. */
@Composable
private fun CropFrame(
    image: ImageBitmap,
    view: CropView,
    onFrameSize: (IntSize) -> Unit,
    onGesture: (pan: Offset, zoom: Float, centroid: Offset) -> Unit,
) {
    val colors = AxTheme.colors
    // The gesture detector lives across recompositions: it must call the latest callback.
    val currentOnGesture by rememberUpdatedState(onGesture)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(MODEL_PICTURE_ASPECT, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(AxTheme.shapes.panel))
            .onSizeChanged(onFrameSize)
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ -> currentOnGesture(pan, zoom, centroid) }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color.Black)
            // Measured here, not taken from the size reported by onSizeChanged, so that the very
            // first frame already shows the image.
            val drawGeometry = CropGeometry(image.width, image.height, this.size.width, this.size.height)
            val topLeft = drawGeometry.imageTopLeft(view)
            val scaled = drawGeometry.imageSize(view)
            drawImage(
                image = image,
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                dstSize = IntSize(scaled.x.roundToInt(), scaled.y.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            // Thirds grid and frame as a visual aid.
            val line = Color.White.copy(alpha = 0.35f)
            for (i in 1..2) {
                drawLine(line, Offset(this.size.width * i / 3f, 0f), Offset(this.size.width * i / 3f, this.size.height), 1.dp.toPx())
                drawLine(line, Offset(0f, this.size.height * i / 3f), Offset(this.size.width, this.size.height * i / 3f), 1.dp.toPx())
            }
            drawRect(colors.accent, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
