package org.openax12.core.data.library

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.openax12.core.data.storage.FileOps
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.ModelExtrasCodec

/** What [ExtrasStore.load] found. */
internal sealed interface ExtrasLoad {

    /** The folder has no `extras.json` (an SDK-only model). */
    data object Missing : ExtrasLoad

    /**
     * The extras were read.
     *
     * @property migrated the file was older and has been rewritten in the current schema
     * @property newerThanApp the file was written by a newer app version; the original was kept
     *   as a copy next to it ([preservedAs]) because writing it back drops what this version
     *   does not know
     */
    data class Loaded(
        val extras: ModelExtras,
        val migrated: Boolean = false,
        val newerThanApp: Boolean = false,
        val preservedAs: String? = null,
    ) : ExtrasLoad

    /**
     * The file could not be parsed. It was moved aside as [quarantinedAs] (null if even that
     * failed) so that the next write does not destroy what might still be recoverable by hand.
     */
    data class Corrupt(val quarantinedAs: String?, val detail: String) : ExtrasLoad
}

/**
 * Reads and writes `extras.json` of a model folder: schema migration on load, atomic writes
 * (temp file + rename) and quarantine of unreadable files. Blocking; pure `java.io`.
 */
internal object ExtrasStore {

    /** The extras file of [folder]. */
    fun file(folder: File): File = File(folder, ModelExtras.FILE_NAME)

    /**
     * Loads the extras of [folder].
     *
     * @param quarantine false only inspects: a corrupt or newer file is reported but left alone
     *   and a migrated file is not rewritten
     */
    fun load(folder: File, quarantine: Boolean = true, nowMs: Long = System.currentTimeMillis()): ExtrasLoad {
        val file = file(folder)
        if (!file.isFile) return ExtrasLoad.Missing
        val text = try {
            if (file.length() > MAX_BYTES) throw IOException("extras.json is larger than $MAX_BYTES bytes")
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return ExtrasLoad.Corrupt(if (quarantine) moveAside(file, CORRUPT_TAG, nowMs) else null, e.message.orEmpty())
        }
        val decoded = try {
            ModelExtrasCodec.decode(text)
        } catch (e: Exception) {
            return ExtrasLoad.Corrupt(if (quarantine) moveAside(file, CORRUPT_TAG, nowMs) else null, e.message.orEmpty().take(200))
        }
        if (decoded.newerThanApp) {
            val copy = if (quarantine) copyAside(file, "v${decoded.fileVersion}", nowMs) else null
            return ExtrasLoad.Loaded(decoded.extras, newerThanApp = true, preservedAs = copy)
        }
        if (decoded.migrated && quarantine) {
            try {
                save(folder, decoded.extras)
            } catch (_: IOException) {
                // The migrated value is still usable in memory; the next save writes it.
            }
        }
        return ExtrasLoad.Loaded(decoded.extras, migrated = decoded.migrated)
    }

    /**
     * Writes [extras] into [folder] atomically and returns the SHA-256 of the written bytes.
     *
     * @throws IOException if the folder cannot be written
     */
    fun save(folder: File, extras: ModelExtras): String = saveText(folder, ModelExtrasCodec.encode(extras))

    /** [save] for already encoded extras. */
    fun saveText(folder: File, text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        FileOps.writeAtomic(file(folder), bytes)
        return FileOps.sha256(bytes)
    }

    /** Names of the files [load] moved or copied aside in [folder] (for diagnostics and tests). */
    fun quarantined(folder: File): List<String> =
        folder.list().orEmpty().filter { it.startsWith("extras.") && it != ModelExtras.FILE_NAME && it.endsWith(".json") }.sorted()

    private fun moveAside(file: File, tag: String, nowMs: Long): String? {
        val target = asideFile(file, tag, nowMs)
        return try {
            FileOps.moveReplacing(file, target)
            target.name
        } catch (_: IOException) {
            null
        }
    }

    private fun copyAside(file: File, tag: String, nowMs: Long): String? {
        val target = asideFile(file, tag, nowMs)
        return try {
            FileOps.copyFile(file, target)
            target.name
        } catch (_: IOException) {
            null
        }
    }

    private fun asideFile(file: File, tag: String, nowMs: Long): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(nowMs))
        var candidate = File(file.parentFile, "extras.$tag-$stamp.json")
        var counter = 2
        while (candidate.exists()) {
            candidate = File(file.parentFile, "extras.$tag-$stamp-$counter.json")
            counter++
        }
        return candidate
    }

    private const val CORRUPT_TAG = "corrupt"

    /** Upper bound for an `extras.json`; anything larger is not ours. */
    const val MAX_BYTES: Long = 4L * 1024 * 1024
}
