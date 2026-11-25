package org.openax12.core.data.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.StorageInfo

/** Decides where the user-accessible library (`…/OpenAX12`) lives. */
internal interface StorageLocator {

    /** Resolves the library root right now (permission state, writability, free space). */
    fun resolve(): StorageInfo
}

/** The pure part of the decision, separated from Android so that it can be unit-tested. */
internal object StorageDecision {

    /** Highest API level on which the app may use a folder in the root of the shared storage. */
    const val MAX_PUBLIC_API: Int = 29

    const val MODELS_DIR: String = "models"
    const val SOUNDS_DIR: String = "sounds"
    const val LOGS_DIR: String = "logs"

    /**
     * Picks the library root.
     *
     * - API ≤ [MAX_PUBLIC_API] with the storage permission and a writable [publicRoot]: the public
     *   folder (`/sdcard/OpenAX12`).
     * - Otherwise the first writable of [fallbackRoots] (the app's external files directory, then
     *   its internal files directory).
     *
     * @param isWritable creates the directory if needed and tells whether files can be written
     */
    fun choose(
        apiLevel: Int,
        permissionGranted: Boolean,
        publicRoot: File?,
        fallbackRoots: List<File>,
        isWritable: (File) -> Boolean,
    ): StorageInfo {
        val publicAllowed = apiLevel <= MAX_PUBLIC_API
        if (publicAllowed && permissionGranted && publicRoot != null && isWritable(publicRoot)) {
            return info(publicRoot, usesPublicStorage = true, permissionGranted = true)
        }
        // The permission only matters where the public folder could be used at all.
        val granted = !publicAllowed || permissionGranted
        val fallback = fallbackRoots.firstOrNull(isWritable)
            ?: return StorageInfo(usesPublicStorage = false, permissionGranted = granted)
        return info(fallback, usesPublicStorage = false, permissionGranted = granted)
    }

    private fun info(root: File, usesPublicStorage: Boolean, permissionGranted: Boolean) = StorageInfo(
        libraryRoot = root.absolutePath,
        modelsDir = File(root, MODELS_DIR).absolutePath,
        soundsDir = File(root, SOUNDS_DIR).absolutePath,
        logsDir = File(root, LOGS_DIR).absolutePath,
        usesPublicStorage = usesPublicStorage,
        permissionGranted = permissionGranted,
        freeBytes = runCatching { root.usableSpace }.getOrDefault(0L),
    )

    /** Creates [dir] if necessary and proves with a probe file that it can be written. */
    fun probeWritable(dir: File): Boolean = try {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            false
        } else {
            val probe = File(dir, ".probe-${System.nanoTime()}")
            val created = probe.createNewFile()
            if (created) probe.delete()
            created
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * [StorageLocator] of the app: `/sdcard/OpenAX12` on API ≤ 29 once the storage permission is
 * granted (the AX12 runs API 28), otherwise `<external files dir>/OpenAX12`, and as a last resort
 * `<internal files dir>/OpenAX12` when no external storage is mounted.
 */
internal class AndroidStorageLocator(context: Context) : StorageLocator {

    private val appContext = context.applicationContext

    override fun resolve(): StorageInfo {
        val granted = appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val publicRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            ?.let { File(it, ModelRepository.LIBRARY_FOLDER) }
        val fallbacks = buildList {
            runCatching { appContext.getExternalFilesDir(null) }.getOrNull()
                ?.let { add(File(it, ModelRepository.LIBRARY_FOLDER)) }
            add(File(appContext.filesDir, ModelRepository.LIBRARY_FOLDER))
        }
        return StorageDecision.choose(
            apiLevel = Build.VERSION.SDK_INT,
            permissionGranted = granted,
            publicRoot = publicRoot,
            fallbackRoots = fallbacks,
            isWritable = StorageDecision::probeWritable,
        )
    }
}
