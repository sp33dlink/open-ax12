package org.openax12.feature.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.SensorOverride
import org.openax12.core.model.UnitSystem

class HelpersTest {

    @Test
    fun `option codec round trips decimals`() {
        assertEquals(1150, OptionCodec.toScaled("11.5"))
        assertEquals("11.5", OptionCodec.fromScaled(1150))
        assertEquals("120", OptionCodec.fromScaled(12000))
        assertEquals("-0.25", OptionCodec.fromScaled(-25))
        assertEquals("0", OptionCodec.fromScaled(0))
        assertNull(OptionCodec.toScaled("abc"))
        assertNull(OptionCodec.toScaled(null))
        assertEquals(-10000, OptionCodec.scaledLimit(null, -100.0))
        assertEquals(500, OptionCodec.scaledLimit(5.0, 99.0))
    }

    @Test
    fun `distance and bearing between two positions`() {
        // One degree of latitude is about 111.2 km, due north.
        val d = GeoMath.distanceMeters(48.0, 11.0, 49.0, 11.0)
        assertEquals(111_195.0, d, 200.0)
        assertEquals(0.0, GeoMath.bearingDegrees(48.0, 11.0, 49.0, 11.0), 0.01)
        assertEquals(180.0, GeoMath.bearingDegrees(49.0, 11.0, 48.0, 11.0), 0.01)
        assertEquals(90.0, GeoMath.bearingDegrees(0.0, 0.0, 0.0, 1.0), 0.01)
        assertEquals(270.0, GeoMath.bearingDegrees(0.0, 1.0, 0.0, 0.0), 0.01)
        assertEquals(0.0, GeoMath.distanceMeters(48.1, 11.5, 48.1, 11.5), 0.001)
    }

    @Test
    fun `coordinates are formatted with a dot and hemispheres`() {
        assertEquals("48.13712, 11.57541", GeoMath.formatDecimal(48.13712, 11.57541))
        assertEquals("-33.86000, 151.20000", GeoMath.formatDecimal(-33.86, 151.2))
        assertEquals("48°08'13.6\"N", GeoMath.formatDms(48.13712, isLatitude = true))
        assertEquals("33°51'36.0\"S", GeoMath.formatDms(-33.86, isLatitude = true))
        assertEquals("11°34'31.5\"E", GeoMath.formatDms(11.57541, isLatitude = false))
        // 59.96 seconds round up to a full minute, which carries into the degrees.
        assertEquals("11°00'00.0\"E", GeoMath.formatDms(11.0 - 0.04 / 3600.0, isLatitude = false))
    }

    @Test
    fun `age picks the largest sensible unit`() {
        assertEquals(Age(AgeUnit.SECONDS, 5), Age.of(5_400))
        assertEquals(Age(AgeUnit.MINUTES, 4), Age.of(4 * 60_000L + 59_000))
        assertEquals(Age(AgeUnit.HOURS_MINUTES, 1, 5), Age.of(65 * 60_000L))
        assertEquals(Age(AgeUnit.DAYS, 2), Age.of(2 * 86_400_000L + 3_600_000))
        assertEquals(Age(AgeUnit.SECONDS, 0), Age.of(-1_000))
    }

    @Test
    fun `distance formatting follows the unit system`() {
        assertEquals("152m", formatDistance(152.0, UnitSystem.METRIC))
        assertEquals("1.4km", formatDistance(1_420.0, UnitSystem.METRIC))
        assertEquals("499ft", formatDistance(152.0, UnitSystem.IMPERIAL))
        assertEquals("1.0mi", formatDistance(1_609.344, UnitSystem.IMPERIAL))
    }

    @Test
    fun `file sizes`() {
        assertEquals("812 B", formatFileSize(812))
        assertEquals("1.0 kB", formatFileSize(1024))
        assertEquals("1.1 MB", formatFileSize(1_204_552))
    }

    @Test
    fun `an override that changes nothing is dropped`() {
        assertNull(SensorOverride().normalized())
        assertNull(SensorOverride(name = "   ").normalized())
        assertEquals(SensorOverride(name = "Speed"), SensorOverride(name = " Speed ").normalized())
        assertTrue(SensorOverride(hidden = true).normalized()?.hidden == true)
        assertEquals(2, SensorOverride(precision = 2).normalized()?.precision)
    }
}
