package org.openax12.core.audio

import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The user's sound files: name → file resolution, listing and a memory of files that turned out
 * to be undecodable. Only `java.io`, so it is unit-tested on the JVM. Thread-safe.
 *
 * @param directory the library's `sounds/` folder; null while there is none
 */
internal class SoundLibrary(private val directory: () -> File?) {

    private val undecodable = ConcurrentHashMap.newKeySet<String>()
    private val durations = ConcurrentHashMap<String, Long>()

    /**
     * The file meant by [name] (relative to the sounds folder, `/` or `\` as separator), or null
     * if it does not exist, is empty, is not a supported audio file or lies outside the folder.
     * A name without extension matches the first existing `name.<ext>` of [EXTENSIONS].
     */
    fun resolve(name: String): File? {
        val root = root() ?: return null
        val relative = name.trim().replace('\\', '/').trim('/')
        if (relative.isEmpty()) return null
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null

        val direct = File(root, relative)
        val candidates = if (isSupported(direct.name)) {
            listOf(direct)
        } else {
            EXTENSIONS.map { File(root, "$relative.$it") }
        }
        for (candidate in candidates) {
            if (!candidate.isFile || !candidate.canRead() || candidate.length() <= 0L) continue
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: continue
            // A symlink must not lead out of the folder.
            if (!canonical.path.startsWith(root.path + File.separator)) continue
            return canonical
        }
        return null
    }

    /** [file] as a name relative to the sounds folder (the value [resolve] accepts). */
    fun relativeName(file: File): String {
        val root = root() ?: return file.name
        val path = runCatching { file.canonicalPath }.getOrDefault(file.path)
        val prefix = root.path + File.separator
        return if (path.startsWith(prefix)) path.substring(prefix.length).replace(File.separatorChar, '/') else file.name
    }

    /**
     * All sound files in the folder and its sub-folders (up to [MAX_DEPTH] levels, at most
     * [MAX_FILES]), sorted by name without regard to case. Blocking file access.
     *
     * @param durationMs returns the duration of a file or 0; results are remembered per file version
     */
    fun list(durationMs: (File) -> Long = { 0L }): List<SoundFile> {
        val root = root() ?: return emptyList()
        val files = ArrayList<File>()
        collect(root, 0, files)
        val prefix = root.path + File.separator
        return files
            .map { file ->
                val key = versionKey(file)
                val duration = durations[key] ?: durationMs(file).coerceAtLeast(0L).also { durations[key] = it }
                SoundFile(
                    name = file.path.removePrefix(prefix).replace(File.separatorChar, '/'),
                    path = file.path,
                    sizeBytes = file.length(),
                    durationMs = duration,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SoundFile::name))
    }

    /** Remembers that this version of [file] cannot be decoded. */
    fun markUndecodable(file: File) {
        undecodable += versionKey(file)
    }

    /** True if this version of [file] failed to decode earlier. */
    fun isUndecodable(file: File): Boolean = versionKey(file) in undecodable

    private fun root(): File? {
        val dir = runCatching { directory() }.getOrNull() ?: return null
        if (!dir.isDirectory) return null
        return runCatching { dir.canonicalFile }.getOrNull()
    }

    private fun collect(dir: File, depth: Int, into: MutableList<File>) {
        val children = dir.listFiles() ?: return
        children.sortBy { it.name.lowercase(Locale.ROOT) }
        for (child in children) {
            if (into.size >= MAX_FILES) return
            if (child.name.startsWith('.')) continue
            if (child.isDirectory) {
                if (depth < MAX_DEPTH) collect(child, depth + 1, into)
            } else if (child.isFile && isSupported(child.name) && child.length() > 0L) {
                into += child
            }
        }
    }

    private fun versionKey(file: File): String = "${file.path}|${file.length()}|${file.lastModified()}"

    companion object {
        /** Playable file types, in the order tried for a name without extension. */
        val EXTENSIONS = listOf("wav", "mp3", "ogg", "oga", "m4a", "aac", "flac")

        /** Sub-folder levels below the sounds folder that are listed. */
        const val MAX_DEPTH = 3

        /** Upper bound of listed files. */
        const val MAX_FILES = 1000

        /** True if [fileName] has one of the [EXTENSIONS]. */
        fun isSupported(fileName: String): Boolean {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0 || dot == fileName.lastIndex) return false
            return fileName.substring(dot + 1).lowercase(Locale.ROOT) in EXTENSIONS
        }
    }
}
