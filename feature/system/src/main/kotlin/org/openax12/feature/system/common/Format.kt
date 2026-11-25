package org.openax12.feature.system.common

import java.util.Locale

/** Human-readable size: `812 B`, `1.4 MB`, `12.3 GB`. Locale independent (decimal point). */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

/**
 * Firmware version as `01.02.0003` from the packed value `0x01020003` (`FirmwareInfo`); the
 * hardware version `0x00000102` is shown as `01.02` by [formatHardwareVersion].
 */
internal fun formatFirmwareVersion(packed: Long): String {
    val major = (packed shr 24) and 0xFF
    val minor = (packed shr 16) and 0xFF
    val build = packed and 0xFFFF
    return String.format(Locale.US, "%02d.%02d.%04d", major, minor, build)
}

/** Hardware version `0x00000102` as `01.02`. */
internal fun formatHardwareVersion(packed: Long): String {
    val major = (packed shr 8) and 0xFF
    val minor = packed and 0xFF
    return String.format(Locale.US, "%02d.%02d", major, minor)
}

/** Seconds as `12 h 05 min`, `4 min 10 s` or `38 s`. */
internal fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> String.format(Locale.US, "%d h %02d min", h, m)
        m > 0 -> String.format(Locale.US, "%d min %02d s", m, s % 60)
        else -> "$s s"
    }
}

/** Hex byte list of [bytes], space separated (`EE 06 14`). */
internal fun hexBytes(bytes: ByteArray): String =
    bytes.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

/** `0x14` style hex of a CRSF frame type. */
internal fun hexType(type: Int): String = String.format(Locale.US, "0x%02X", type)

/** Signed percentage `-12%` / `+35%` / `0%`. */
internal fun signedPercent(value: Int): String = if (value > 0) "+$value%" else "$value%"

/** Tenths as decimal: `38` -> `3.8`. */
internal fun tenths(value: Int): String =
    String.format(Locale.US, "%s%d.%d", if (value < 0) "-" else "", Math.abs(value) / 10, Math.abs(value) % 10)
