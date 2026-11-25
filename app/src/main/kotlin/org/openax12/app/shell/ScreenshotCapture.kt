package org.openax12.app.shell

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Captures the window (`PixelCopy`, API 26+) into `<library>/screenshots/<timestamp>.png`.
 * Implements the "Screenshot" special function (`RuntimeUiRequest.Screenshot`).
 */
internal object ScreenshotCapture {

    /** Name of the library sub-folder. */
    const val FOLDER = "screenshots"

    /**
     * @param libraryRoot `StorageInfo.libraryRoot`; empty = no library available
     * @return the written file, or null if the capture or the write failed
     */
    suspend fun capture(window: Window, libraryRoot: String, io: CoroutineDispatcher): File? {
        if (libraryRoot.isEmpty()) return null
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) return null
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(window, bitmap, { result -> if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            } catch (_: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(false)
            }
        }
        if (!copied) {
            bitmap.recycle()
            return null
        }
        return withContext(io) {
            try {
                val directory = File(libraryRoot, FOLDER).also { it.mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(directory, "$stamp.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                file
            } catch (_: Exception) {
                null
            } finally {
                bitmap.recycle()
            }
        }
    }
}
