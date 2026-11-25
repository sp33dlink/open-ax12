package org.openax12.feature.telemetry

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Geographic helpers of the last-position screen. Pure. */
internal object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres between two positions in decimal degrees (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing in degrees (0 = north, clockwise, 0..360) from the first to the second position. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** `48.13712, 11.57541`: decimal degrees with five decimals, dot as separator. */
    fun formatDecimal(latitude: Double, longitude: Double): String =
        String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)

    /** One coordinate in degrees, minutes and seconds: `48°08'13.6"N`. */
    fun formatDms(value: Double, isLatitude: Boolean): String {
        val hemisphere = when {
            isLatitude -> if (value >= 0) 'N' else 'S'
            else -> if (value >= 0) 'E' else 'W'
        }
        val absolute = abs(value)
        var degrees = floor(absolute).toInt()
        val minutesFull = (absolute - degrees) * 60.0
        var minutes = floor(minutesFull).toInt()
        var seconds = Math.round((minutesFull - minutes) * 600.0) / 10.0
        if (seconds >= 60.0) {
            seconds -= 60.0
            minutes += 1
        }
        if (minutes >= 60) {
            minutes -= 60
            degrees += 1
        }
        return String.format(Locale.ROOT, "%d°%02d'%04.1f\"%c", degrees, minutes, seconds, hemisphere)
    }
}

/** A time span split into the largest sensible unit, for "seen 3 min ago". */
internal data class Age(val unit: AgeUnit, val first: Int, val second: Int = 0) {
    companion object {
        /** The [Age] of [deltaMs] milliseconds: seconds below a minute, minutes below an hour, hours + minutes below a day, then days. */
        fun of(deltaMs: Long): Age {
            val seconds = (deltaMs.coerceAtLeast(0) / 1000).toInt()
            return when {
                seconds < 60 -> Age(AgeUnit.SECONDS, seconds)
                seconds < 3600 -> Age(AgeUnit.MINUTES, seconds / 60)
                seconds < 86_400 -> Age(AgeUnit.HOURS_MINUTES, seconds / 3600, (seconds % 3600) / 60)
                else -> Age(AgeUnit.DAYS, seconds / 86_400)
            }
        }
    }
}

/** See [Age]. */
internal enum class AgeUnit { SECONDS, MINUTES, HOURS_MINUTES, DAYS }
