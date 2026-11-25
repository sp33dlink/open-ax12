package org.openax12.feature.models

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.designsystem.Ax12Preview

/** A synthetic 1600x1200 "photo": sky gradient, ground and a few markers to judge the crop. */
private fun samplePhoto(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.rgb(120, 180, 230)
    canvas.drawRect(0f, 0f, 1600f, 800f, paint)
    paint.color = Color.rgb(90, 150, 80)
    canvas.drawRect(0f, 800f, 1600f, 1200f, paint)
    paint.color = Color.WHITE
    canvas.drawCircle(1200f, 260f, 90f, paint)
    paint.color = Color.rgb(200, 60, 40)
    canvas.drawRect(500f, 500f, 1100f, 560f, paint)
    canvas.drawRect(780f, 440f, 820f, 620f, paint)
    return bitmap.asImageBitmap()
}

@PreviewTest
@Ax12Preview
@Composable
fun PictureCropScreenshot() {
    ShellFrame {
        PictureCropScreen(loading = false, image = samplePhoto(), saving = false, onBack = {}, onSave = {})
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun PictureCropFailedScreenshot() {
    ShellFrame { PictureCropScreen(loading = false, image = null, saving = false, onBack = {}, onSave = {}) }
}
