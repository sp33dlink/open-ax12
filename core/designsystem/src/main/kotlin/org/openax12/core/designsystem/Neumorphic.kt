package org.openax12.core.designsystem

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Neumorphic surfaces without per-frame blur work.
//
// A raised or sunken rounded rectangle is rendered once into a small bitmap (soft shadows made
// with a software BlurMaskFilter), cached process-wide by its parameters, and then drawn as a
// nine-patch: corners 1:1, edges and centre stretched. A surface that is too small to stretch in
// one direction gets a bitmap of exactly that extent, so pills and small keys keep true corners.
// Drawing a surface is therefore at most nine bitmap blits and allocates nothing.

/**
 * A pre-rendered surface.
 *
 * @property image the bitmap
 * @property margin pixels the bitmap extends beyond the shape on every side (outer shadows)
 * @property sliceX width of the unstretched left and right columns, or 0 if the bitmap has the
 *   exact width of the target and is not stretched horizontally
 * @property sliceY like [sliceX] for rows
 */
internal class SurfacePatch(
    val image: ImageBitmap,
    val margin: Int,
    val sliceX: Int,
    val sliceY: Int,
)

private data class PatchKey(
    val sunken: Boolean,
    val radius: Int,
    val blur: Int,
    val offset: Int,
    val shapeWidth: Int,
    val shapeHeight: Int,
    val face: Int,
    val light: Int,
    val dark: Int,
)

internal object NeumorphicCache {
    private val cache = object : LruCache<PatchKey, SurfacePatch>(96) {}

    /** Number of centre pixels that get stretched. */
    private const val STRETCH = 2

    /** Visible reach in pixels of a BlurMaskFilter of [blur] (sigma = 0.577 * blur + 0.5, 3 sigma). */
    private fun blurSpread(blur: Int): Int = ceil(blur * 1.75f).toInt() + 2

    fun raised(width: Int, height: Int, radius: Int, blur: Int, offset: Int, face: Color, light: Color, dark: Color): SurfacePatch {
        val r = min(radius, min(width, height) / 2)
        val spread = blurSpread(blur)
        val inner = r + spread
        val stretchX = width >= 2 * inner + STRETCH
        val stretchY = height >= 2 * inner + STRETCH
        val shapeW = if (stretchX) 2 * inner + STRETCH else width
        val shapeH = if (stretchY) 2 * inner + STRETCH else height
        val key = PatchKey(false, r, blur, offset, shapeW, shapeH, face.toArgb(), light.toArgb(), dark.toArgb())
        synchronized(cache) {
            cache.get(key)?.let { return it }
            val margin = offset + spread + 1
            val bitmap = Bitmap.createBitmap(shapeW + 2 * margin, shapeH + 2 * margin, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val rect = RectF(margin.toFloat(), margin.toFloat(), (margin + shapeW).toFloat(), (margin + shapeH).toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            if (blur > 0) paint.maskFilter = BlurMaskFilter(blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
            val shadowRect = RectF(rect)
            // The shadows are slightly smaller than the face so that they fade right at its edge.
            val inset = blur * 0.25f
            shadowRect.inset(inset, inset)
            val shadowRadius = max(0f, r - inset)
            paint.color = dark.toArgb()
            shadowRect.offset(offset.toFloat(), offset.toFloat())
            canvas.drawRoundRect(shadowRect, shadowRadius, shadowRadius, paint)
            paint.color = light.toArgb()
            shadowRect.offset(-2f * offset, -2f * offset)
            canvas.drawRoundRect(shadowRect, shadowRadius, shadowRadius, paint)
            paint.maskFilter = null
            paint.color = face.toArgb()
            canvas.drawRoundRect(rect, r.toFloat(), r.toFloat(), paint)
            val patch = SurfacePatch(
                image = bitmap.asImageBitmap(),
                margin = margin,
                sliceX = if (stretchX) margin + inner else 0,
                sliceY = if (stretchY) margin + inner else 0,
            )
            cache.put(key, patch)
            return patch
        }
    }

    fun sunken(width: Int, height: Int, radius: Int, blur: Int, offset: Int, face: Color, light: Color, dark: Color): SurfacePatch {
        val r = min(radius, min(width, height) / 2)
        val inner = r + blurSpread(blur) + offset
        val stretchX = width >= 2 * inner + STRETCH
        val stretchY = height >= 2 * inner + STRETCH
        val shapeW = if (stretchX) 2 * inner + STRETCH else width
        val shapeH = if (stretchY) 2 * inner + STRETCH else height
        val key = PatchKey(true, r, blur, offset, shapeW, shapeH, face.toArgb(), light.toArgb(), dark.toArgb())
        synchronized(cache) {
            cache.get(key)?.let { return it }
            val bitmap = Bitmap.createBitmap(max(1, shapeW), max(1, shapeH), Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val rect = RectF(0f, 0f, shapeW.toFloat(), shapeH.toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = face.toArgb()
            canvas.drawRoundRect(rect, r.toFloat(), r.toFloat(), paint)
            if (blur > 0) paint.maskFilter = BlurMaskFilter(blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
            val far = (max(shapeW, shapeH) + 4 * (blur + offset + 4)).toFloat()
            val hole = RectF()
            val frame = Path()
            fun innerShadow(dx: Float, dy: Float, color: Color) {
                hole.set(rect)
                hole.offset(dx, dy)
                frame.rewind()
                frame.fillType = Path.FillType.EVEN_ODD
                frame.addRect(-far, -far, shapeW + far, shapeH + far, Path.Direction.CW)
                frame.addRoundRect(hole, r.toFloat(), r.toFloat(), Path.Direction.CW)
                paint.color = color.toArgb()
                canvas.drawPath(frame, paint)
            }
            innerShadow(-offset.toFloat(), -offset.toFloat(), light)
            innerShadow(offset.toFloat(), offset.toFloat(), dark)
            // Cut everything back to the shape.
            paint.maskFilter = null
            // Clear the complement of the rounded rect (rather than DST_IN on the rect itself, which
            // layoutlib only applies inside the drawn shape and so leaves the corners dirty).
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            frame.rewind()
            frame.fillType = Path.FillType.EVEN_ODD
            frame.addRect(-far, -far, shapeW + far, shapeH + far, Path.Direction.CW)
            frame.addRoundRect(rect, r.toFloat(), r.toFloat(), Path.Direction.CW)
            canvas.drawPath(frame, paint)
            val patch = SurfacePatch(
                image = bitmap.asImageBitmap(),
                margin = 0,
                sliceX = if (stretchX) inner else 0,
                sliceY = if (stretchY) inner else 0,
            )
            cache.put(key, patch)
            return patch
        }
    }
}

/**
 * Draws [patch] so that its shape covers a [w] x [h] pixel rectangle at the origin (by default
 * the whole draw scope).
 */
internal fun DrawScope.drawSurfacePatch(
    patch: SurfacePatch,
    w: Int = size.width.roundToInt(),
    h: Int = size.height.roundToInt(),
) {
    val m = patch.margin
    val imgW = patch.image.width
    val imgH = patch.image.height
    // Column and row boundaries in source and destination space.
    val srcX: IntArray
    val dstX: IntArray
    if (patch.sliceX > 0) {
        srcX = intArrayOf(0, patch.sliceX, imgW - patch.sliceX, imgW)
        dstX = intArrayOf(-m, -m + patch.sliceX, w + m - patch.sliceX, w + m)
    } else {
        srcX = intArrayOf(0, imgW)
        dstX = intArrayOf(-m, w + m)
    }
    val srcY: IntArray
    val dstY: IntArray
    if (patch.sliceY > 0) {
        srcY = intArrayOf(0, patch.sliceY, imgH - patch.sliceY, imgH)
        dstY = intArrayOf(-m, -m + patch.sliceY, h + m - patch.sliceY, h + m)
    } else {
        srcY = intArrayOf(0, imgH)
        dstY = intArrayOf(-m, h + m)
    }
    for (row in 0 until srcY.size - 1) {
        for (col in 0 until srcX.size - 1) {
            val dw = dstX[col + 1] - dstX[col]
            val dh = dstY[row + 1] - dstY[row]
            if (dw <= 0 || dh <= 0) continue
            drawImage(
                image = patch.image,
                srcOffset = IntOffset(srcX[col], srcY[row]),
                srcSize = IntSize(srcX[col + 1] - srcX[col], srcY[row + 1] - srcY[row]),
                dstOffset = IntOffset(dstX[col], dstY[row]),
                dstSize = IntSize(dw, dh),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

private fun DrawScope.drawFlatSurface(face: Color, outline: Color, radiusPx: Float, borderPx: Float) {
    val r = min(radiusPx, size.minDimension / 2f)
    drawRoundRect(color = face, cornerRadius = CornerRadius(r, r))
    if (borderPx > 0f && outline.alpha > 0f) {
        val half = borderPx / 2f
        drawRoundRect(
            color = outline,
            topLeft = Offset(half, half),
            size = Size(size.width - borderPx, size.height - borderPx),
            cornerRadius = CornerRadius(max(0f, r - half), max(0f, r - half)),
            style = Stroke(width = borderPx),
        )
    }
}

/**
 * Background of a raised neumorphic surface: face plus a light shadow towards the top-left and a
 * dark one towards the bottom-right. In schemes without neumorphism ([AxColors.neumorphic] is
 * false) it draws a flat face with an outline instead.
 *
 * The shadows extend beyond the bounds, so leave about 2 x [elevation] of free space around the
 * element and do not clip its parent tightly.
 *
 * @param cornerRadius corner radius; larger than half the size gives a pill
 * @param elevation shadow offset; the blur is derived from it
 * @param color face colour
 * @param pressed draws the surface pushed in (sunken) instead, for touch feedback
 */
@Composable
fun Modifier.axRaised(
    cornerRadius: Dp = AxTheme.shapes.card,
    elevation: Dp = 5.dp,
    color: Color = AxTheme.colors.surface,
    pressed: Boolean = false,
): Modifier {
    val colors = AxTheme.colors
    val border = AxTheme.shapes.border
    if (!colors.neumorphic) {
        val face = if (pressed) colors.surfaceSunken else color
        val outline = if (pressed) colors.outlineStrong else colors.outline
        return drawBehind { drawFlatSurface(face, outline, cornerRadius.toPx(), border.toPx()) }
    }
    if (pressed) return axSunken(cornerRadius = cornerRadius, depth = elevation * 0.5f, color = colors.surfaceSunken)
    return drawWithCache {
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        if (w <= 0 || h <= 0) return@drawWithCache onDrawBehind { }
        val patch = NeumorphicCache.raised(
            width = w,
            height = h,
            radius = cornerRadius.roundToPx(),
            blur = max(1, (elevation * 1.6f).roundToPx()),
            offset = max(1, elevation.roundToPx()),
            face = color,
            light = colors.shadowLight.copy(alpha = 0.95f),
            dark = colors.shadowDark.copy(alpha = 0.62f),
        )
        onDrawBehind { drawSurfacePatch(patch) }
    }
}

/**
 * Background of a sunken neumorphic surface (a "well"): face with an inner dark shadow at the
 * top-left and an inner highlight at the bottom-right. Flat face with outline in schemes without
 * neumorphism. Nothing is drawn outside the bounds.
 *
 * @param cornerRadius corner radius; larger than half the size gives a pill
 * @param depth inner shadow offset; the blur is derived from it
 * @param color face colour
 */
@Composable
fun Modifier.axSunken(
    cornerRadius: Dp = AxTheme.shapes.control,
    depth: Dp = 2.dp,
    color: Color = AxTheme.colors.surfaceSunken,
): Modifier {
    val colors = AxTheme.colors
    val border = AxTheme.shapes.border
    if (!colors.neumorphic) {
        return drawBehind { drawFlatSurface(color, colors.outline, cornerRadius.toPx(), border.toPx()) }
    }
    return drawWithCache {
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        if (w <= 0 || h <= 0) return@drawWithCache onDrawBehind { }
        val patch = NeumorphicCache.sunken(
            width = w,
            height = h,
            radius = cornerRadius.roundToPx(),
            blur = max(1, (depth * 1.5f).roundToPx()),
            offset = max(1, depth.roundToPx()),
            face = color,
            light = colors.shadowLight.copy(alpha = 0.9f),
            dark = colors.shadowDark.copy(alpha = 0.75f),
        )
        onDrawBehind { drawSurfacePatch(patch) }
    }
}
