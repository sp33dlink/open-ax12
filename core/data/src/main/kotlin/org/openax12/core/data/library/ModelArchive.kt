package org.openax12.core.data.library

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.openax12.core.data.ImportKind
import org.openax12.core.data.ImportProblem
import org.openax12.core.data.archive.ArchiveProblem
import org.openax12.core.data.archive.ScanResult
import org.openax12.core.data.archive.ZipLimits
import org.openax12.core.data.archive.ZipScanner
import org.openax12.core.model.Ax12Json
import org.openax12.core.model.MODEL_PICTURE_FILE_NAME
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.ModelExtrasCodec
import org.openax12.core.model.ModelFileCheck
import org.openax12.core.model.ModelType

/**
 * `manifest.json` of a `.oax12` model archive.
 *
 * @property formatVersion version of the archive layout; a larger value than
 *   [ModelArchive.FORMAT_VERSION] is refused
 */
@Serializable
internal data class ArchiveManifest(
    val format: String = ModelArchive.FORMAT,
    val formatVersion: Int = ModelArchive.FORMAT_VERSION,
    val name: String = "",
    val modelType: ModelType? = null,
    val createdMs: Long = 0,
    val appVersion: String = "",
    val extrasSchemaVersion: Int = 0,
)

/**
 * A validated import source, completely in memory. Nothing has been written yet.
 *
 * @property name sanitised model name found in the source
 * @property model content of the SDK model file
 * @property extras decoded and migrated extras; null for a stock model or an archive without extras
 * @property picture `picture.jpg`, if the archive carries one that looks like an image
 */
internal class ParsedImport(
    val kind: ImportKind,
    val name: String,
    val model: ByteArray,
    val extras: ModelExtras?,
    val picture: ByteArray?,
    val sizeBytes: Long,
)

/** Result of [ModelArchive.parse]. */
internal sealed interface ParseResult {
    class Ok(val import: ParsedImport) : ParseResult
    data class Rejected(val problem: ImportProblem, val detail: String = "") : ParseResult
}

/**
 * The `.oax12` model archive: a zip with `manifest.json`, `model.json` (SDK model file),
 * `extras.json` and `picture.jpg` at its root. Reading is strict (see [ZipScanner]) and done
 * completely in memory under [LIMITS]; nothing touches the file system. Pure `java.*`.
 */
internal object ModelArchive {

    const val FORMAT: String = "oax12-model"
    const val FORMAT_VERSION: Int = 1

    const val MANIFEST_FILE: String = "manifest.json"
    const val MODEL_FILE: String = SnapshotStore.MODEL_FILE

    /** Limits of a model archive: a handful of small files. */
    val LIMITS: ZipLimits = ZipLimits(maxEntries = 128, maxEntryBytes = 8L * 1024 * 1024, maxTotalBytes = 32L * 1024 * 1024)

    /** Largest stock model file (`.json` / `.rcm`) accepted. */
    const val MAX_STOCK_MODEL_BYTES: Int = 4 * 1024 * 1024

    /** Name given to an import whose source carries no usable name. */
    const val DEFAULT_IMPORT_NAME: String = "Imported model"

    /**
     * Validates an import source and reads it into memory.
     *
     * A zip is treated as a `.oax12` archive; anything else must be a stock model file: a JSON
     * object, or — for a file named `*.rcm` — opaque content that the SDK validates at import.
     *
     * @param displayName file name of the source, if known (used for the model name and the
     *   `.rcm` rule)
     */
    fun parse(input: InputStream, displayName: String?): ParseResult {
        val buffered = BufferedInputStream(input)
        val header = ByteArray(4)
        val headerLength = try {
            buffered.mark(header.size)
            val read = readFully(buffered, header)
            buffered.reset()
            read
        } catch (e: IOException) {
            return ParseResult.Rejected(ImportProblem.NOT_READABLE, e.message.orEmpty())
        }
        if (headerLength <= 0) return ParseResult.Rejected(ImportProblem.NOT_A_MODEL, "empty file")
        return if (ZipScanner.looksLikeZip(header)) parseArchive(buffered, displayName) else parseStockModel(buffered, displayName)
    }

    /**
     * Writes an archive. The stream is finished but not closed.
     *
     * @param extrasText encoded extras, or null for a model without extras
     */
    fun write(output: OutputStream, manifest: ArchiveManifest, model: ByteArray, extrasText: String?, picture: ByteArray?) {
        val zip = ZipOutputStream(output)
        fun put(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        put(MANIFEST_FILE, Ax12Json.encodeToString(ArchiveManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
        put(MODEL_FILE, model)
        if (extrasText != null) put(ModelExtras.FILE_NAME, extrasText.toByteArray(Charsets.UTF_8))
        if (picture != null) put(MODEL_PICTURE_FILE_NAME, picture)
        zip.finish()
        zip.flush()
    }

    /** True if [bytes] is a syntactically valid JSON object (what an SDK model file is). */
    fun isJsonObject(bytes: ByteArray): Boolean = parseJsonObject(bytes) != null

    /**
     * Why [bytes] cannot be used as an SDK model file, or null: not a JSON object, or an SDK file
     * that is incomplete ([ModelFileCheck.fileProblem]: without channel data the SDK loads it as an
     * empty configuration, assumption r11).
     */
    fun modelFileProblem(bytes: ByteArray): String? {
        if (!isJsonObject(bytes)) return "it is not a JSON object"
        return ModelFileCheck.fileProblem(bytes.toString(Charsets.UTF_8))
    }

    /** True if [bytes] starts like a JPEG or PNG image. */
    fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        return jpeg || png
    }

    /** Maps a zip rejection to the import problem shown to the user. */
    fun importProblem(problem: ArchiveProblem): ImportProblem = when (problem) {
        ArchiveProblem.PATH_TRAVERSAL -> ImportProblem.PATH_TRAVERSAL
        ArchiveProblem.TOO_MANY_ENTRIES, ArchiveProblem.ENTRY_TOO_LARGE, ArchiveProblem.TOTAL_TOO_LARGE -> ImportProblem.TOO_LARGE
        ArchiveProblem.NOT_A_ZIP, ArchiveProblem.DUPLICATE_ENTRY -> ImportProblem.NOT_A_MODEL
        ArchiveProblem.UNREADABLE -> ImportProblem.NOT_READABLE
    }

    // -- internals --------------------------------------------------------------------------------

    private fun parseArchive(input: InputStream, displayName: String?): ParseResult {
        val (result, files) = ZipScanner.readAll(input, LIMITS) { path -> !isSnapshotPath(path) }
        if (result is ScanResult.Rejected) return ParseResult.Rejected(importProblem(result.problem), "${result.problem}: ${result.detail}")
        val total = (result as ScanResult.Ok).totalBytes

        // Accept both the layout we write (files at the root) and a hand-made zip of the model
        // folder (everything below one top-level directory).
        var prefix = ""
        if (MODEL_FILE !in files) {
            val candidates = files.keys.filter { it.count { c -> c == '/' } == 1 && it.endsWith("/$MODEL_FILE") }
            if (candidates.size != 1) return ParseResult.Rejected(ImportProblem.MISSING_MODEL_FILE, "no $MODEL_FILE in the archive")
            prefix = candidates.single().removeSuffix(MODEL_FILE)
        }
        fun entry(name: String): ByteArray? = files[prefix + name]

        val model = entry(MODEL_FILE) ?: return ParseResult.Rejected(ImportProblem.MISSING_MODEL_FILE)
        modelFileProblem(model)?.let { return ParseResult.Rejected(ImportProblem.NOT_A_MODEL, "$MODEL_FILE: $it") }

        var manifest: ArchiveManifest? = null
        entry(MANIFEST_FILE)?.let { bytes ->
            manifest = try {
                Ax12Json.decodeFromString(ArchiveManifest.serializer(), bytes.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                return ParseResult.Rejected(ImportProblem.NOT_A_MODEL, "$MANIFEST_FILE is unreadable")
            }
        }
        manifest?.let {
            if (it.format != FORMAT) return ParseResult.Rejected(ImportProblem.NOT_A_MODEL, "unknown archive format '${it.format.take(40)}'")
            if (it.formatVersion > FORMAT_VERSION) return ParseResult.Rejected(ImportProblem.SCHEMA_TOO_NEW, "archive format ${it.formatVersion}")
        }

        var extras: ModelExtras? = null
        entry(ModelExtras.FILE_NAME)?.let { bytes ->
            val decoded = ModelExtrasCodec.decodeOrNull(bytes.toString(Charsets.UTF_8))
                ?: return ParseResult.Rejected(ImportProblem.CORRUPT_EXTRAS)
            if (decoded.newerThanApp) return ParseResult.Rejected(ImportProblem.SCHEMA_TOO_NEW, "extras schema ${decoded.fileVersion}")
            extras = decoded.extras
        }

        val picture = entry(MODEL_PICTURE_FILE_NAME)?.takeIf(::looksLikeImage)
        val rawName = manifest?.name?.takeIf { it.isNotBlank() }
            ?: prefix.trimEnd('/').takeIf { it.isNotBlank() }
            ?: baseName(displayName)
            ?: DEFAULT_IMPORT_NAME
        return ParseResult.Ok(
            ParsedImport(
                kind = ImportKind.ARCHIVE,
                name = ModelNames.sanitize(rawName, DEFAULT_IMPORT_NAME),
                model = model,
                extras = extras?.copy(pictureFile = if (picture != null) MODEL_PICTURE_FILE_NAME else null),
                picture = picture,
                sizeBytes = total,
            ),
        )
    }

    private fun parseStockModel(input: InputStream, displayName: String?): ParseResult {
        val bytes = try {
            readLimited(input, MAX_STOCK_MODEL_BYTES)
        } catch (e: IOException) {
            return ParseResult.Rejected(ImportProblem.NOT_READABLE, e.message.orEmpty())
        } ?: return ParseResult.Rejected(ImportProblem.TOO_LARGE, "larger than $MAX_STOCK_MODEL_BYTES bytes")
        val json = parseJsonObject(bytes)
        val isRcm = displayName?.endsWith(".rcm", ignoreCase = true) == true
        if (json == null && !isRcm) return ParseResult.Rejected(ImportProblem.NOT_A_MODEL, "neither an archive nor a JSON model file")
        val embeddedName = json?.let { root ->
            NAME_KEYS.firstNotNullOfOrNull { key -> (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank) }
        }
        val rawName = baseName(displayName) ?: embeddedName ?: DEFAULT_IMPORT_NAME
        return ParseResult.Ok(
            ParsedImport(
                kind = ImportKind.STOCK_MODEL,
                name = ModelNames.sanitize(rawName, DEFAULT_IMPORT_NAME),
                model = bytes,
                extras = null,
                picture = null,
                sizeBytes = bytes.size.toLong(),
            ),
        )
    }

    private fun parseJsonObject(bytes: ByteArray): JsonObject? {
        if (bytes.isEmpty()) return null
        return try {
            Ax12Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotEmpty() }
    }

    private fun isSnapshotPath(path: String): Boolean =
        path.startsWith("${SnapshotStore.DIR_NAME}/") || path.contains("/${SnapshotStore.DIR_NAME}/")

    /** File name without directory and extension; null if nothing usable is left. */
    private fun baseName(displayName: String?): String? {
        val name = displayName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        if (name.isEmpty()) return null
        val stem = if ('.' in name) name.substringBeforeLast('.') else name
        return stem.trim().takeIf { it.isNotEmpty() }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    /** Reads at most [limit] bytes; null if the stream holds more. */
    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    private val NAME_KEYS = listOf("modelName", "name")
}
