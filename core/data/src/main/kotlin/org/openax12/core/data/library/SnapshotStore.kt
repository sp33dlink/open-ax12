package org.openax12.core.data.library

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import org.openax12.core.data.ModelSnapshot
import org.openax12.core.data.SnapshotReason
import org.openax12.core.data.storage.FileOps
import org.openax12.core.model.Ax12Json
import org.openax12.core.model.ModelExtras

/** `snapshot.json` inside a snapshot directory. */
@Serializable
internal data class SnapshotMeta(
    val timeMs: Long = 0,
    val reason: String = SnapshotReason.SAVE.name,
) {
    /** [reason] as enum; unknown names (written by a newer version) count as [SnapshotReason.SAVE]. */
    val snapshotReason: SnapshotReason
        get() = SnapshotReason.entries.firstOrNull { it.name.equals(reason, ignoreCase = true) } ?: SnapshotReason.SAVE
}

/**
 * Snapshots of one model: `<model folder>/snapshots/<id>/` with `model.json` (SDK model file),
 * `extras.json` (if the model had extras) and `snapshot.json`. The id is the directory name
 * (`2026-11-19_08-45-12_save`), readable in a file manager. Blocking; pure `java.io`.
 */
internal class SnapshotStore(private val modelFolder: File) {

    private val root: File get() = File(modelFolder, DIR_NAME)

    /** All snapshots, newest first. */
    fun list(modelId: String): List<ModelSnapshot> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, MODEL_FILE).isFile }
            .map { dir ->
                val meta = readMeta(dir)
                ModelSnapshot(
                    id = dir.name,
                    modelId = modelId,
                    timeMs = meta.timeMs,
                    reason = meta.snapshotReason,
                    sizeBytes = FileOps.sizeOf(dir),
                )
            }
            .sortedWith(compareByDescending<ModelSnapshot> { it.timeMs }.thenByDescending { it.id })

    /**
     * Stores a snapshot and prunes the oldest ones beyond [keep].
     *
     * @param modelFile the SDK model file content to keep (copied)
     * @param extrasText encoded extras, or null for a model without extras
     * @throws IOException if the snapshot cannot be written
     */
    fun create(modelId: String, modelFile: File, extrasText: String?, reason: SnapshotReason, timeMs: Long, keep: Int): ModelSnapshot {
        val dir = newDirectory(timeMs, reason)
        try {
            FileOps.ensureDir(dir)
            FileOps.copyFile(modelFile, File(dir, MODEL_FILE))
            if (extrasText != null) FileOps.writeAtomic(File(dir, ModelExtras.FILE_NAME), extrasText)
            FileOps.writeAtomic(File(dir, META_FILE), Ax12Json.encodeToString(SnapshotMeta.serializer(), SnapshotMeta(timeMs, reason.name)))
        } catch (e: IOException) {
            dir.deleteRecursively()
            throw e
        }
        prune(modelId, keep, protect = dir.name)
        return ModelSnapshot(id = dir.name, modelId = modelId, timeMs = timeMs, reason = reason, sizeBytes = FileOps.sizeOf(dir))
    }

    /** Directory of snapshot [id], or null if it does not exist (or [id] is not a plain name). */
    fun directory(id: String): File? {
        if (id.isEmpty() || id.contains('/') || id.contains('\\') || id == "." || id == "..") return null
        val dir = File(root, id)
        return dir.takeIf { it.isDirectory && File(it, MODEL_FILE).isFile }
    }

    /** The SDK model file of snapshot [id], or null. */
    fun modelFile(id: String): File? = directory(id)?.let { File(it, MODEL_FILE) }

    /** The extras file of snapshot [id], or null if the snapshot has none. */
    fun extrasFile(id: String): File? = directory(id)?.let { File(it, ModelExtras.FILE_NAME) }?.takeIf { it.isFile }

    /** Deletes snapshot [id]. @return false if it did not exist */
    fun delete(id: String): Boolean = directory(id)?.deleteRecursively() ?: false

    /** Deletes the oldest snapshots so that at most [keep] remain; [protect] is never deleted. */
    fun prune(modelId: String, keep: Int, protect: String? = null) {
        val all = list(modelId)
        if (all.size <= keep) return
        var excess = all.size - keep
        for (snapshot in all.asReversed()) {
            if (excess <= 0) break
            if (snapshot.id == protect) continue
            if (delete(snapshot.id)) excess--
        }
    }

    private fun readMeta(dir: File): SnapshotMeta {
        val file = File(dir, META_FILE)
        if (file.isFile) {
            try {
                return Ax12Json.decodeFromString(SnapshotMeta.serializer(), file.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                // Fall back to the directory name below.
            }
        }
        return SnapshotMeta(timeMs = parseTime(dir.name) ?: dir.lastModified(), reason = parseReason(dir.name).name)
    }

    private fun newDirectory(timeMs: Long, reason: SnapshotReason): File {
        val base = "${NAME_FORMAT.get()!!.format(Date(timeMs))}_${reason.name.lowercase(Locale.ROOT)}"
        var dir = File(root, base)
        var counter = 2
        while (dir.exists()) {
            dir = File(root, "$base-$counter")
            counter++
        }
        return dir
    }

    private fun parseTime(name: String): Long? = try {
        if (name.length >= TIME_LENGTH) NAME_FORMAT.get()!!.parse(name.substring(0, TIME_LENGTH))?.time else null
    } catch (_: Exception) {
        null
    }

    private fun parseReason(name: String): SnapshotReason {
        val tag = name.drop(TIME_LENGTH + 1).substringBefore('-')
        return SnapshotReason.entries.firstOrNull { it.name.equals(tag, ignoreCase = true) } ?: SnapshotReason.SAVE
    }

    companion object {
        /** Name of the snapshot directory inside a model folder. */
        const val DIR_NAME: String = "snapshots"

        /** Name of the SDK model file inside a model folder and inside a snapshot. */
        const val MODEL_FILE: String = "model.json"

        const val META_FILE: String = "snapshot.json"

        private const val TIME_PATTERN = "yyyy-MM-dd_HH-mm-ss"
        private const val TIME_LENGTH = TIME_PATTERN.length

        private val NAME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat(TIME_PATTERN, Locale.ROOT)
        }
    }
}
