package org.openax12.core.data.archive

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Hard limits applied while a zip from outside is read. All sizes are **uncompressed bytes that
 * were really produced**, never the sizes declared in the archive (zip-bomb protection).
 */
internal data class ZipLimits(
    val maxEntries: Int,
    val maxEntryBytes: Long,
    val maxTotalBytes: Long,
)

/** Why an archive was rejected. */
internal enum class ArchiveProblem {
    /** Not a zip file, or an empty one. */
    NOT_A_ZIP,

    /** An entry name is absolute, contains `..`, a backslash, a drive letter or control characters. */
    PATH_TRAVERSAL,

    /** More entries than [ZipLimits.maxEntries]. */
    TOO_MANY_ENTRIES,

    /** One entry is larger than [ZipLimits.maxEntryBytes]. */
    ENTRY_TOO_LARGE,

    /** All entries together are larger than [ZipLimits.maxTotalBytes]. */
    TOTAL_TOO_LARGE,

    /** Two entries have the same path (compared case-insensitively). */
    DUPLICATE_ENTRY,

    /** The archive is damaged or the source cannot be read. */
    UNREADABLE,
}

/** Result of reading an archive. */
internal sealed interface ScanResult {

    /** Every entry passed validation. */
    data class Ok(val entries: Int, val totalBytes: Long) : ScanResult

    /** The archive was rejected at [detail]; whatever was read before must be discarded. */
    data class Rejected(val problem: ArchiveProblem, val detail: String = "") : ScanResult
}

/**
 * Strict reader for zip files that come from outside the app (model imports, library backups).
 * Entries are validated one by one **before** their content is handed out: no path traversal, no
 * duplicates, bounded entry count and sizes. Nothing is ever written outside the directory given
 * to [extract].
 */
internal object ZipScanner {

    /**
     * Normalises an entry name to a relative path with `/` separators.
     *
     * @return null for anything that could leave the target directory or is not portable: an
     *   empty name, a NUL or control character, a backslash, a leading `/`, a drive letter, a
     *   `..` segment, or a segment ending in a dot or space
     */
    fun safePath(name: String): String? {
        if (name.isEmpty() || name.length > MAX_PATH_LENGTH) return null
        if (name.any { it.code < 0x20 || it.code == 0x7F }) return null
        if ('\\' in name) return null
        if (name.startsWith('/')) return null
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return null
        val segments = ArrayList<String>()
        for (segment in name.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> return null
                segment.endsWith('.') || segment.endsWith(' ') -> return null
                segment.any { it in ":*?\"<>|" } -> return null
                else -> segments += segment
            }
        }
        if (segments.isEmpty()) return null
        return segments.joinToString("/")
    }

    /** True if [header] starts with a zip local-file or end-of-directory signature. */
    fun looksLikeZip(header: ByteArray): Boolean =
        header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            ((header[2].toInt() == 3 && header[3].toInt() == 4) || (header[2].toInt() == 5 && header[3].toInt() == 6))

    /**
     * Reads every entry of the zip in [input] under [limits]. [onFile] is called for each file
     * entry with its validated path and a stream of its content; it may read all, some or none
     * of it. The stream is only valid during the call. [input] is not closed.
     *
     * [onFile] may throw [ArchiveRejectedException] to reject the archive.
     */
    fun scan(input: InputStream, limits: ZipLimits, onFile: (path: String, content: InputStream) -> Unit): ScanResult {
        var entries = 0
        var total = 0L
        val seen = HashSet<String>()
        var current = ""
        try {
            val zip = ZipInputStream(if (input is BufferedInputStream) input else BufferedInputStream(input))
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > limits.maxEntries) return ScanResult.Rejected(ArchiveProblem.TOO_MANY_ENTRIES, "more than ${limits.maxEntries} entries")
                val path = safePath(entry.name) ?: return ScanResult.Rejected(ArchiveProblem.PATH_TRAVERSAL, printable(entry.name))
                current = path
                if (entry.isDirectory) continue
                if (!seen.add(path.lowercase(Locale.ROOT))) return ScanResult.Rejected(ArchiveProblem.DUPLICATE_ENTRY, path)
                val bounded = BoundedEntryStream(zip, limits.maxEntryBytes, limits.maxTotalBytes - total)
                onFile(path, bounded)
                bounded.drain()
                total += bounded.count
            }
        } catch (e: ArchiveRejectedException) {
            return ScanResult.Rejected(e.problem, e.detail.ifEmpty { current })
        } catch (e: IOException) {
            // Includes ZipException for damaged data.
            return ScanResult.Rejected(if (entries == 0) ArchiveProblem.NOT_A_ZIP else ArchiveProblem.UNREADABLE, e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            // ZipInputStream reports undecodable entry names this way.
            return ScanResult.Rejected(ArchiveProblem.UNREADABLE, e.message.orEmpty())
        }
        if (entries == 0) return ScanResult.Rejected(ArchiveProblem.NOT_A_ZIP, "no zip entries")
        return ScanResult.Ok(entries, total)
    }

    /**
     * Reads the entries selected by [wanted] into memory (the others are only validated and
     * counted). On rejection the map is empty.
     */
    fun readAll(input: InputStream, limits: ZipLimits, wanted: (String) -> Boolean = { true }): Pair<ScanResult, Map<String, ByteArray>> {
        val files = LinkedHashMap<String, ByteArray>()
        val result = scan(input, limits) { path, content ->
            if (wanted(path)) {
                val buffer = ByteArrayOutputStream()
                content.copyTo(buffer)
                files[path] = buffer.toByteArray()
            }
        }
        return if (result is ScanResult.Ok) result to files else result to emptyMap()
    }

    /**
     * Extracts the entries selected by [accept] into [targetDir]. Every target path is checked
     * again against the canonical path of [targetDir]. On rejection the caller must delete
     * [targetDir]: it may hold the entries extracted before the bad one.
     */
    fun extract(input: InputStream, limits: ZipLimits, targetDir: File, accept: (String) -> Boolean = { true }): ScanResult {
        if (!targetDir.isDirectory && !targetDir.mkdirs() && !targetDir.isDirectory) {
            return ScanResult.Rejected(ArchiveProblem.UNREADABLE, "cannot create $targetDir")
        }
        return scan(input, limits) { path, content ->
            if (accept(path)) {
                val target = resolveInside(targetDir, path)
                    ?: throw ArchiveRejectedException(ArchiveProblem.PATH_TRAVERSAL, path)
                val parent = target.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                    throw IOException("Cannot create $parent")
                }
                FileOutputStream(target).use { content.copyTo(it) }
            }
        }
    }

    /**
     * The file for the validated relative path [relative] under [root], or null if it would not
     * lie inside [root] (second line of defence behind [safePath], also catches symbolic links).
     */
    fun resolveInside(root: File, relative: String): File? {
        val safe = safePath(relative) ?: return null
        return try {
            val canonicalRoot = root.canonicalFile
            val target = File(canonicalRoot, safe).canonicalFile
            if (target.toPath().startsWith(canonicalRoot.toPath()) && target != canonicalRoot) target else null
        } catch (_: IOException) {
            null
        }
    }

    private fun printable(name: String): String =
        name.take(80).map { if (it.code < 0x20 || it.code == 0x7F) '?' else it }.joinToString("")

    private const val MAX_PATH_LENGTH = 240
}

/** Thrown inside [ZipScanner.scan] to reject the archive. */
internal class ArchiveRejectedException(val problem: ArchiveProblem, val detail: String = "") : IOException("$problem $detail")

/**
 * The content of one zip entry with byte accounting: reading past the entry limit or the
 * remaining total budget rejects the archive. Closing it does not close the zip stream.
 */
private class BoundedEntryStream(
    private val source: InputStream,
    private val maxEntryBytes: Long,
    private val remainingTotal: Long,
) : InputStream() {

    var count: Long = 0
        private set

    override fun read(): Int {
        val value = source.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = source.read(buffer, offset, length)
        if (read > 0) account(read)
        return read
    }

    /** Reads (and counts) whatever the consumer left unread. */
    fun drain() {
        val buffer = ByteArray(8 * 1024)
        while (read(buffer, 0, buffer.size) >= 0) {
            // counting only
        }
    }

    override fun close() {
        // The zip stream stays open for the next entry.
    }

    private fun account(bytes: Int) {
        count += bytes
        if (count > maxEntryBytes) throw ArchiveRejectedException(ArchiveProblem.ENTRY_TOO_LARGE)
        if (count > remainingTotal) throw ArchiveRejectedException(ArchiveProblem.TOTAL_TOO_LARGE)
    }
}
