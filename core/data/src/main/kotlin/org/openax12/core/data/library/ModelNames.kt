package org.openax12.core.data.library

import java.util.Locale

/**
 * Rules for model names. A model name is at the same time the SDK model name and the name of the
 * library folder, so it has to be a valid folder name on the radio's FAT / sdcardfs storage
 * (case-insensitive, no `\ / : * ? " < > |`, no trailing dot or space).
 */
internal object ModelNames {

    /** Longest allowed model name in characters. */
    const val MAX_LENGTH: Int = 32

    /** Name used when nothing usable is left after sanitising. */
    const val FALLBACK: String = "Model"

    private const val FORBIDDEN = "\\/:*?\"<>|"

    private val RESERVED: Set<String> = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        for (i in 1..9) {
            add("com$i")
            add("lpt$i")
        }
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Checks a name typed by the user. Surrounding whitespace is trimmed and inner whitespace
     * collapsed; everything else must already be valid.
     *
     * @return the normalised name, or null if it is empty, too long, reserved or contains a
     *   character that is not allowed in a folder name
     */
    fun validate(raw: String): String? {
        val name = raw.replace(WHITESPACE, " ").trim()
        if (name.isEmpty() || name.length > MAX_LENGTH) return null
        if (name.any(::isForbidden)) return null
        if (name.startsWith('.') || name.endsWith('.')) return null
        if (isReserved(name)) return null
        return name
    }

    /**
     * Turns any text (an SDK model name, a file name, an archive entry) into a valid model name:
     * forbidden characters become `_`, leading / trailing dots and spaces are dropped, the result
     * is cut to [MAX_LENGTH]. Never fails; returns [fallback] when nothing is left.
     */
    fun sanitize(raw: String, fallback: String = FALLBACK): String {
        val replaced = buildString(raw.length) {
            for (c in raw) append(if (isForbidden(c)) '_' else c)
        }
        var name = replaced.replace(WHITESPACE, " ").trim().trim('.').trim()
        if (name.length > MAX_LENGTH) name = name.substring(0, MAX_LENGTH).trim().trimEnd('.').trim()
        if (name.isEmpty() || name.all { it == '_' }) name = fallback
        if (isReserved(name)) name = "${name}_"
        return name
    }

    /** Key under which two names collide (the storage is case-insensitive). */
    fun key(name: String): String = name.trim().lowercase(Locale.ROOT)

    /** True if [name] collides with one of [taken]. */
    fun isTaken(name: String, taken: Collection<String>): Boolean {
        val key = key(name)
        return taken.any { key(it) == key }
    }

    /**
     * A free name derived from [base]: [base] itself, or `"base (2)"`, `"base (3)"`, … The base
     * is shortened when the suffix would exceed [MAX_LENGTH].
     */
    fun unique(base: String, taken: Collection<String>): String {
        val keys = taken.mapTo(HashSet()) { key(it) }
        if (key(base) !in keys) return base
        val stem = stripCounter(base)
        var counter = 2
        while (true) {
            val suffix = " ($counter)"
            val room = (MAX_LENGTH - suffix.length).coerceAtLeast(1)
            val candidate = stem.take(room).trimEnd() + suffix
            if (key(candidate) !in keys) return candidate
            counter++
        }
    }

    /** `"Swift (3)"` → `"Swift"`, so that copies of copies do not pile up suffixes. */
    private fun stripCounter(name: String): String {
        val match = COUNTER_SUFFIX.find(name) ?: return name
        val stem = name.substring(0, match.range.first).trimEnd()
        return stem.ifEmpty { name }
    }

    private val COUNTER_SUFFIX = Regex(" \\(\\d+\\)$")

    private fun isForbidden(c: Char): Boolean = c.code < 0x20 || c.code == 0x7F || c in FORBIDDEN

    private fun isReserved(name: String): Boolean =
        name == "." || name == ".." || name.lowercase(Locale.ROOT).substringBefore('.') in RESERVED
}
