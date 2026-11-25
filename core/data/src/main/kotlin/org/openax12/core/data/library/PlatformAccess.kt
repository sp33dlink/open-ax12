package org.openax12.core.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.openax12.core.data.OpenAx12FileProvider
import org.openax12.core.data.PictureCropRect
import org.openax12.core.data.storage.FileOps
import org.openax12.core.model.MODEL_PICTURE_ASPECT
import org.openax12.core.model.MODEL_PICTURE_HEIGHT_PX
import org.openax12.core.model.MODEL_PICTURE_WIDTH_PX

/**
 * The few Android services the file-based data layer needs, behind an interface so that the
 * model library can be exercised in plain JVM tests.
 */
internal interface UriAccess {

    /** Directory inside the app cache whose files can be shared through [shareUri]. */
    val shareDir: File

    /** Opens [uri] for reading; null if it cannot be opened. */
    fun openInput(uri: Uri): InputStream?

    /** Opens [uri] for writing (truncating); null if it cannot be opened. */
    fun openOutput(uri: Uri): OutputStream?

    /** File name of [uri] as the provider reports it, or its last path segment; null if unknown. */
    fun displayName(uri: Uri): String?

    /** A `content://` URI for [file] (inside [shareDir] or the library); null if it cannot be shared. */
    fun shareUri(file: File): Uri?
}

/** Decodes, crops and scales model pictures. */
internal interface PictureProcessor {

    /**
     * Decodes the image delivered by [open] (called up to three times), applies its EXIF
     * orientation, crops it to [crop] (centre-cropped to the model picture aspect if the
     * rectangle has another one), scales it to the model picture size and writes it as JPEG to
     * [target] atomically.
     *
     * @return false if the source is not a readable image or [target] cannot be written
     */
    fun process(open: () -> InputStream?, crop: PictureCropRect, target: File): Boolean

    /** True if [bytes] decodes as an image (bounds only). */
    fun isImage(bytes: ByteArray): Boolean
}

/** [UriAccess] backed by the content resolver and the module's `FileProvider`. */
internal class AndroidUriAccess(context: Context) : UriAccess {

    private val appContext = context.applicationContext

    override val shareDir: File get() = File(appContext.cacheDir, OpenAx12FileProvider.SHARE_DIR)

    override fun openInput(uri: Uri): InputStream? = try {
        appContext.contentResolver.openInputStream(uri)
    } catch (_: Exception) {
        // FileNotFoundException, SecurityException (revoked grant), IllegalArgumentException (bad URI)
        null
    }

    override fun openOutput(uri: Uri): OutputStream? = try {
        appContext.contentResolver.openOutputStream(uri, "wt")
    } catch (_: Exception) {
        try {
            appContext.contentResolver.openOutputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    override fun displayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0) cursor.getString(column)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            } catch (_: Exception) {
                // Providers are free to throw; fall back to the path.
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    override fun shareUri(file: File): Uri? = try {
        FileProvider.getUriForFile(appContext, OpenAx12FileProvider.authority(appContext), file)
    } catch (_: IllegalArgumentException) {
        // The file is outside the declared paths.
        null
    }
}

/** [PictureProcessor] using the platform bitmap codecs. */
internal class AndroidPictureProcessor : PictureProcessor {

    override fun isImage(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    override fun process(open: () -> InputStream?, crop: PictureCropRect, target: File): Boolean {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            (open() ?: return false).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            if (bounds.outWidth.toLong() * bounds.outHeight > MAX_SOURCE_PIXELS) return false

            // Subsample large photos, but keep enough pixels for a crop of a part of the image.
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MODEL_PICTURE_WIDTH_PX * 2 && bounds.outHeight / (sample * 2) >= MODEL_PICTURE_HEIGHT_PX * 2) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            decoded = (open() ?: return false).use { BitmapFactory.decodeStream(it, null, options) } ?: return false

            val rotation = open()?.use(::exifMatrix)
            oriented = if (rotation != null) {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, rotation, true)
            } else {
                decoded
            }

            val rect = cropRect(oriented.width, oriented.height, crop)
            cropped = Bitmap.createBitmap(oriented, rect[0], rect[1], rect[2], rect[3])
            scaled = Bitmap.createScaledBitmap(cropped, MODEL_PICTURE_WIDTH_PX, MODEL_PICTURE_HEIGHT_PX, true)

            val temp = File(target.parentFile, target.name + FileOps.TEMP_SUFFIX)
            FileOps.ensureDir(target.parentFile ?: return false)
            val written = FileOutputStream(temp).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            if (!written) {
                temp.delete()
                return false
            }
            FileOps.moveReplacing(temp, target)
            return true
        } catch (_: Exception) {
            return false
        } catch (_: OutOfMemoryError) {
            return false
        } finally {
            // createBitmap may return its source unchanged; recycle each bitmap once.
            val all = listOfNotNull(decoded, oriented, cropped, scaled).distinct()
            all.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    /** The matrix that undoes the EXIF orientation, or null if the image is upright. */
    private fun exifMatrix(input: InputStream): Matrix? {
        val orientation = try {
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return null
        }
        return matrix
    }

    companion object {
        private const val JPEG_QUALITY = 88

        /** Refuse absurd sources before decoding (100 megapixels). */
        private const val MAX_SOURCE_PIXELS = 100_000_000L

        /**
         * The pixel rectangle `[left, top, width, height]` for [crop] in an image of
         * [width] × [height]: the requested rectangle, shrunk around its centre to the model
         * picture aspect if it has another one.
         */
        internal fun cropRect(width: Int, height: Int, crop: PictureCropRect): IntArray {
            val l = min(crop.left, crop.right).coerceIn(0f, 1f)
            val r = max(crop.left, crop.right).coerceIn(0f, 1f)
            val t = min(crop.top, crop.bottom).coerceIn(0f, 1f)
            val b = max(crop.top, crop.bottom).coerceIn(0f, 1f)
            var left = l * width
            var top = t * height
            var w = ((r - l) * width).coerceAtLeast(1f)
            var h = ((b - t) * height).coerceAtLeast(1f)
            val aspect = w / h
            if (aspect > MODEL_PICTURE_ASPECT * 1.01f) {
                val newW = h * MODEL_PICTURE_ASPECT
                left += (w - newW) / 2f
                w = newW
            } else if (aspect < MODEL_PICTURE_ASPECT / 1.01f) {
                val newH = w / MODEL_PICTURE_ASPECT
                top += (h - newH) / 2f
                h = newH
            }
            val x = left.roundToInt().coerceIn(0, width - 1)
            val y = top.roundToInt().coerceIn(0, height - 1)
            val cw = w.roundToInt().coerceIn(1, width - x)
            val ch = h.roundToInt().coerceIn(1, height - y)
            return intArrayOf(x, y, cw, ch)
        }
    }
}
