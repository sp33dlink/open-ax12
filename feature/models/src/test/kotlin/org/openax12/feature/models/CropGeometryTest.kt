package org.openax12.feature.models

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGeometryTest {

    private val delta = 1e-4f

    /** 4:3 frame of 400x300 px. */
    private fun geometry(w: Int, h: Int) = CropGeometry(w, h, 400f, 300f)

    @Test
    fun `at zoom one a 4 to 3 image is shown completely`() {
        val g = geometry(1600, 1200)
        val r = g.rect(CropView())
        assertEquals(0f, r.left, delta)
        assertEquals(0f, r.top, delta)
        assertEquals(1f, r.right, delta)
        assertEquals(1f, r.bottom, delta)
    }

    @Test
    fun `a wide image is cropped to a 4 to 3 window in pixels`() {
        val g = geometry(3000, 1000)
        val r = g.rect(CropView())
        // Full height, a 4:3 slice of the width centred.
        assertEquals(0f, r.top, delta)
        assertEquals(1f, r.bottom, delta)
        val pixelWidth = (r.right - r.left) * 3000
        val pixelHeight = (r.bottom - r.top) * 1000
        assertEquals(4f / 3f, pixelWidth / pixelHeight, 1e-3f)
        assertEquals(0.5f, (r.left + r.right) / 2f, delta)
    }

    @Test
    fun `a tall image is cropped to a 4 to 3 window in pixels`() {
        val g = geometry(1000, 3000)
        val r = g.rect(CropView())
        assertEquals(0f, r.left, delta)
        assertEquals(1f, r.right, delta)
        val pixelWidth = (r.right - r.left) * 1000
        val pixelHeight = (r.bottom - r.top) * 3000
        assertEquals(4f / 3f, pixelWidth / pixelHeight, 1e-3f)
    }

    @Test
    fun `zooming in halves the visible part and keeps it inside the image`() {
        val g = geometry(1600, 1200)
        val centred = g.rect(CropView(zoom = 2f))
        assertEquals(0.25f, centred.left, delta)
        assertEquals(0.75f, centred.right, delta)
        // A centre outside the allowed range is pulled back so the frame stays covered.
        val clamped = g.rect(CropView(zoom = 2f, cx = 0.95f, cy = 0.05f))
        assertEquals(0.5f, clamped.left, delta)
        assertEquals(1f, clamped.right, delta)
        assertEquals(0f, clamped.top, delta)
        assertEquals(0.5f, clamped.bottom, delta)
    }

    @Test
    fun `zoom is limited`() {
        val g = geometry(1600, 1200)
        assertEquals(1f, g.clamp(CropView(zoom = 0.2f)).zoom, delta)
        assertEquals(CROP_MAX_ZOOM, g.clamp(CropView(zoom = 99f)).zoom, delta)
    }

    @Test
    fun `dragging moves the content with the finger`() {
        val g = geometry(1600, 1200)
        val zoomed = CropView(zoom = 2f)
        // Dragging right by 100 px shows more of the left part of the image.
        val moved = g.transform(zoomed, pan = Offset(100f, 0f), zoomChange = 1f, centroid = Offset(200f, 150f))
        assertTrue(moved.cx < zoomed.cx)
        assertEquals(zoomed.cy, moved.cy, delta)
    }

    @Test
    fun `pinching keeps the point under the fingers in place`() {
        val g = geometry(1600, 1200)
        val before = CropView(zoom = 1.5f, cx = 0.5f, cy = 0.5f)
        val centroid = Offset(300f, 100f)
        val after = g.transform(before, Offset.Zero, 1.2f, centroid)
        // Image point under the centroid, before and after.
        fun pointUnder(v: CropView): Pair<Float, Float> {
            val topLeft = g.imageTopLeft(v)
            val size = g.imageSize(v)
            return ((centroid.x - topLeft.x) / size.x) to ((centroid.y - topLeft.y) / size.y)
        }
        val (bx, by) = pointUnder(before)
        val (ax, ay) = pointUnder(after)
        assertEquals(bx, ax, 1e-3f)
        assertEquals(by, ay, 1e-3f)
        assertTrue(after.zoom > before.zoom)
    }

    @Test
    fun `image top left matches the crop rectangle`() {
        val g = geometry(1600, 1200)
        val view = CropView(zoom = 2f, cx = 0.6f, cy = 0.4f)
        val r = g.rect(view)
        val topLeft = g.imageTopLeft(view)
        val size = g.imageSize(view)
        // The left edge of the visible part sits at x = 0 of the frame.
        assertEquals(0f, topLeft.x + r.left * size.x, 1e-2f)
        assertEquals(0f, topLeft.y + r.top * size.y, 1e-2f)
    }
}
