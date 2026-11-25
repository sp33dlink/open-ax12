package org.openax12.feature.models

import androidx.compose.ui.geometry.Offset
import org.openax12.core.data.PictureCropRect
import kotlin.math.max

/** Largest zoom of the crop screen (1 = the image just covers the crop frame). */
internal const val CROP_MAX_ZOOM: Float = 4f

/**
 * The crop window onto the image: [zoom] relative to "just covers the frame" and the point of
 * the image at the centre of the frame as fractions ([cx], [cy]) of the image's width / height.
 * Independent of the frame's size on screen.
 */
internal data class CropView(val zoom: Float = 1f, val cx: Float = 0.5f, val cy: Float = 0.5f)

/**
 * Maths of the crop screen for an image of [imageWidth] x [imageHeight] pixels shown in a frame
 * of [boxWidth] x [boxHeight] pixels (aspect `MODEL_PICTURE_ASPECT`). The frame always stays
 * completely covered by the image.
 */
internal class CropGeometry(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val boxWidth: Float,
    private val boxHeight: Float,
) {
    /** Pixels of the frame per image pixel at zoom 1: the image just covers the frame. */
    val baseScale: Float = max(boxWidth / imageWidth, boxHeight / imageHeight)

    /** Pixels of the frame per image pixel at [zoom]. */
    fun scale(zoom: Float): Float = baseScale * zoom

    /** Keeps [view] inside the allowed range: zoom 1..[CROP_MAX_ZOOM], frame inside the image. */
    fun clamp(view: CropView): CropView {
        val zoom = view.zoom.coerceIn(1f, CROP_MAX_ZOOM)
        val halfW = boxWidth / (imageWidth * scale(zoom)) / 2f
        val halfH = boxHeight / (imageHeight * scale(zoom)) / 2f
        return CropView(
            zoom = zoom,
            cx = view.cx.coerceIn(halfW, 1f - halfW),
            cy = view.cy.coerceIn(halfH, 1f - halfH),
        )
    }

    /**
     * One step of a drag / pinch gesture: the content moves by [pan] (frame pixels), zooms by the
     * factor [zoomChange] around [centroid] (frame pixels, the point between the fingers).
     */
    fun transform(view: CropView, pan: Offset, zoomChange: Float, centroid: Offset): CropView {
        val oldScale = scale(view.zoom)
        val newZoom = (view.zoom * zoomChange).coerceIn(1f, CROP_MAX_ZOOM)
        val newScale = scale(newZoom)
        val dx = centroid.x - boxWidth / 2f
        val dy = centroid.y - boxHeight / 2f
        // The image point under the centroid stays under it; then the content follows the pan.
        val focusX = view.cx + dx / (imageWidth * oldScale)
        val focusY = view.cy + dy / (imageHeight * oldScale)
        return clamp(
            CropView(
                zoom = newZoom,
                cx = focusX - dx / (imageWidth * newScale) - pan.x / (imageWidth * newScale),
                cy = focusY - dy / (imageHeight * newScale) - pan.y / (imageHeight * newScale),
            ),
        )
    }

    /** [view] with another [zoom] around the frame centre. */
    fun withZoom(view: CropView, zoom: Float): CropView = clamp(view.copy(zoom = zoom))

    /** Top-left corner of the image in frame pixels. */
    fun imageTopLeft(view: CropView): Offset {
        val s = scale(view.zoom)
        return Offset(boxWidth / 2f - view.cx * imageWidth * s, boxHeight / 2f - view.cy * imageHeight * s)
    }

    /** Size of the scaled image in frame pixels. */
    fun imageSize(view: CropView): Offset {
        val s = scale(view.zoom)
        return Offset(imageWidth * s, imageHeight * s)
    }

    /** The visible part of the image as a normalized rectangle for `ModelRepository.setPicture`. */
    fun rect(view: CropView): PictureCropRect {
        val v = clamp(view)
        val halfW = boxWidth / (imageWidth * scale(v.zoom)) / 2f
        val halfH = boxHeight / (imageHeight * scale(v.zoom)) / 2f
        return PictureCropRect(
            left = (v.cx - halfW).coerceIn(0f, 1f),
            top = (v.cy - halfH).coerceIn(0f, 1f),
            right = (v.cx + halfW).coerceIn(0f, 1f),
            bottom = (v.cy + halfH).coerceIn(0f, 1f),
        )
    }
}
