package org.openax12.core.data.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** File helpers of the data layer: atomic writes, hashes, recursive copies. Blocking; call on the IO dispatcher. */
internal object FileOps {

    /** Suffix of the temporary file an atomic write goes through. Leftovers are removed by [removeTempFiles]. */
    const val TEMP_SUFFIX: String = ".tmp"

    /**
     * Writes [bytes] to [target] atomically: the content goes into `<name>.tmp` next to the
     * target, is synced and then renamed over it. A reader sees either the old or the new file.
     *
     * @throws IOException if the directory cannot be created or the file cannot be written
     */
    fun writeAtomic(target: File, bytes: ByteArray) {
        val temp = tempFor(target)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                sync(out)
            }
            moveReplacing(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** [writeAtomic] for UTF-8 text. */
    fun writeAtomic(target: File, text: String) = writeAtomic(target, text.toByteArray(Charsets.UTF_8))

    /** Copies [input] to [target] atomically (see [writeAtomic]); the stream is not closed. */
    fun writeAtomic(target: File, input: InputStream) {
        val temp = tempFor(target)
        try {
            FileOutputStream(temp).use { out ->
                input.copyTo(out)
                out.flush()
                sync(out)
            }
            moveReplacing(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Copies [source] to [target] atomically. */
    fun copyFile(source: File, target: File) {
        source.inputStream().use { writeAtomic(target, it) }
    }

    /**
     * Moves [source] over [target], atomically where the file system allows it. Falls back to a
     * plain replacing move and finally to copy + delete (different volumes).
     */
    fun moveReplacing(source: File, target: File) {
        target.parentFile?.let(::ensureDir)
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: Exception) {
            // Not atomic on this file system; try the weaker variants.
        }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: Exception) {
            // Fall through to copy + delete.
        }
        source.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        if (!source.delete()) source.deleteOnExit()
    }

    /** Creates [dir] (and parents). @throws IOException if that is not possible */
    fun ensureDir(dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create directory $dir")
        return dir
    }

    /**
     * Copies the directory [source] into [target] (created if missing). Existing files are
     * replaced unless [overwrite] is false. Temporary files are skipped.
     *
     * @return number of files copied
     */
    fun copyDirectory(source: File, target: File, overwrite: Boolean = true): Int {
        ensureDir(target)
        var copied = 0
        for (child in source.listFiles().orEmpty()) {
            val destination = File(target, child.name)
            when {
                child.isDirectory -> copied += copyDirectory(child, destination, overwrite)
                child.name.endsWith(TEMP_SUFFIX) -> Unit
                !overwrite && destination.exists() -> Unit
                else -> {
                    copyFile(child, destination)
                    copied++
                }
            }
        }
        return copied
    }

    /**
     * Moves the directory [source] to [target] (which must not exist): a rename where possible,
     * otherwise copy + delete. Returns false if nothing was moved.
     */
    fun moveDirectory(source: File, target: File): Boolean {
        if (!source.isDirectory || target.exists()) return false
        target.parentFile?.let { runCatching { ensureDir(it) } }
        if (source.renameTo(target)) return true
        return try {
            copyDirectory(source, target)
            source.deleteRecursively()
            true
        } catch (_: IOException) {
            target.deleteRecursively()
            false
        }
    }

    /** Deletes leftovers of interrupted atomic writes directly inside [dir]. */
    fun removeTempFiles(dir: File) {
        dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(TEMP_SUFFIX)) it.delete() }
    }

    /** Total size in bytes of a file or directory tree. */
    fun sizeOf(file: File): Long =
        if (file.isDirectory) file.listFiles().orEmpty().sumOf(::sizeOf) else file.length()

    /** SHA-256 of [file] as lowercase hex; empty string if the file does not exist or cannot be read. */
    fun sha256(file: File): String {
        if (!file.isFile) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            hex(digest.digest())
        } catch (_: IOException) {
            ""
        }
    }

    /** SHA-256 of [bytes] as lowercase hex. */
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * True if [file] is [root] or lies inside it after resolving `..` and symbolic links. Guards
     * every delete / share of a path that came from outside.
     */
    fun isInside(root: File, file: File): Boolean = try {
        val rootPath = root.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        filePath.startsWith(rootPath)
    } catch (_: IOException) {
        false
    }

    private fun tempFor(target: File): File {
        val dir = target.parentFile ?: throw IOException("No parent directory: $target")
        ensureDir(dir)
        return File(dir, target.name + TEMP_SUFFIX)
    }

    private fun sync(out: FileOutputStream) {
        try {
            out.fd.sync()
        } catch (_: IOException) {
            // Some FUSE-backed volumes refuse fsync; the rename still gives old-or-new semantics.
        }
    }

    private fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
