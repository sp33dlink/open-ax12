package org.openax12.core.data.telemetry

import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.openax12.core.data.storage.FileOps
import org.openax12.core.model.Ax12Json
import org.openax12.core.model.GpsFix

/** Great-circle maths for the home / lost-model functions (spherical earth, plenty for RC ranges). */
object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Distance in metres between two positions given in decimal degrees (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Initial bearing in degrees (0 = north, 90 = east, range 0..360) when travelling from the
     * first position to the second. 0 if both positions are the same.
     */
    fun bearingDegrees(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val phi1 = Math.toRadians(fromLat)
        val phi2 = Math.toRadians(toLat)
        val dLambda = Math.toRadians(toLon - fromLon)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        if (abs(x) < 1e-12 && abs(y) < 1e-12) return 0.0
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Distance in metres between two fixes. */
    fun distanceMeters(a: GpsFix, b: GpsFix): Double = distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    /** Bearing in degrees from [from] to [to]. */
    fun bearingDegrees(from: GpsFix, to: GpsFix): Double = bearingDegrees(from.latitude, from.longitude, to.latitude, to.longitude)

    /**
     * True if the coordinates can be a real position: inside the valid ranges and not the
     * `0, 0` a GPS module reports before its first fix.
     */
    fun isPlausible(latitude: Double, longitude: Double): Boolean =
        !latitude.isNaN() && !longitude.isNaN() && abs(latitude) <= 90.0 && abs(longitude) <= 180.0 &&
            !(abs(latitude) < 1e-7 && abs(longitude) < 1e-7)
}

/** Persists the last known GPS position (the lost-model finder must survive an app restart). */
internal class LastPositionStore(private val file: File) {

    /** The stored position, or null if there is none or the file is unreadable. */
    fun load(): GpsFix? {
        if (!file.isFile) return null
        return try {
            Ax12Json.decodeFromString(GpsFix.serializer(), file.readText(Charsets.UTF_8))
                .takeIf { GeoMath.isPlausible(it.latitude, it.longitude) }
        } catch (_: Exception) {
            null
        }
    }

    /** Stores [fix] atomically. @return false if the write failed */
    fun save(fix: GpsFix): Boolean = try {
        FileOps.writeAtomic(file, Ax12Json.encodeToString(GpsFix.serializer(), fix))
        true
    } catch (_: IOException) {
        false
    }
}
