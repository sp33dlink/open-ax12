package org.openax12.core.data.library

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import org.openax12.core.data.storage.FileOps
import org.openax12.core.model.Ax12Json
import org.openax12.core.model.ModelType

/**
 * What the app remembers about one model between runs.
 *
 * @property id stable id of the model (`ModelEntry.id`)
 * @property name model name as last seen in the SDK
 * @property folder name of the library folder inside `models/`
 * @property sdkFile `ModelInfo.filePath` of the SDK's private model file
 * @property modelHash SHA-256 of the folder's `model.json` as the app last wrote or accepted it;
 *   empty = never synced (a folder that was adopted)
 * @property extrasHash SHA-256 of the folder's `extras.json` as last written; empty = none
 * @property pictureStamp size and modification time of `picture.jpg`; empty = none
 */
@Serializable
internal data class SyncEntry(
    val id: String,
    val name: String,
    val folder: String,
    val sdkFile: String,
    val modelHash: String = "",
    val extrasHash: String = "",
    val pictureStamp: String = "",
    val pictureVersion: Long = 0,
    val lastUsedMs: Long = 0,
    val modelType: ModelType = ModelType.OTHER,
    val hasExtras: Boolean = false,
    val modelMatch: Long = 0,
)

/**
 * The sync index: the app-private record of the state in which the library folders were last
 * written by the app. Comparing a folder with its entry is how external modifications are told
 * apart from changes on the SDK side. Also carries the persisted `deviceInSync` flag.
 *
 * @property currentId id of the model that was current when the index was written
 * @property deviceInSync false while the device has not confirmed [pendingModelId]
 * @property ignoredFolders folders the user declined to import (`rejectExternalChange` of a new folder)
 * @property libraryRoot the root the folder names refer to; a different root triggers a migration
 */
@Serializable
internal data class SyncIndex(
    val version: Int = VERSION,
    val entries: List<SyncEntry> = emptyList(),
    val currentId: String? = null,
    val deviceInSync: Boolean = true,
    val pendingModelId: String? = null,
    val ignoredFolders: List<String> = emptyList(),
    val libraryRoot: String = "",
) {
    /** Entry of model [id], or null. */
    fun entry(id: String?): SyncEntry? = if (id == null) null else entries.firstOrNull { it.id == id }

    /** Copy with [entry] added or replacing the entry of the same id. */
    fun with(entry: SyncEntry): SyncIndex =
        if (entries.any { it.id == entry.id }) copy(entries = entries.map { if (it.id == entry.id) entry else it })
        else copy(entries = entries + entry)

    /** Copy without the entry of [id]. */
    fun without(id: String): SyncIndex = copy(entries = entries.filter { it.id != id })

    companion object {
        const val VERSION: Int = 1
    }
}

/** Loads and stores the [SyncIndex] as one JSON file in the app's private storage. */
internal class SyncIndexStore(private val file: File) {

    /** The stored index; an empty one if the file is missing or unreadable (the library is then re-adopted). */
    fun load(): SyncIndex {
        if (!file.isFile) return SyncIndex()
        return try {
            Ax12Json.decodeFromString(SyncIndex.serializer(), file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            SyncIndex()
        }
    }

    /** Writes [index] atomically. @return false if the write failed */
    fun save(index: SyncIndex): Boolean = try {
        FileOps.writeAtomic(file, Ax12Json.encodeToString(SyncIndex.serializer(), index))
        true
    } catch (_: IOException) {
        false
    }
}
