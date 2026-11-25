package org.openax12.core.data.telemetry

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class GeoMathTest {

    @Test
    fun oneDegreeOfLatitudeIsAbout111Km() {
        assertEquals(111_195.0, GeoMath.distanceMeters(0.0, 0.0, 1.0, 0.0), 300.0)
    }

    @Test
    fun bearingToTheEastIsNinety() {
        assertEquals(90.0, GeoMath.bearingDegrees(0.0, 0.0, 0.0, 1.0), 0.01)
    }

    @Test
    fun plausibilityRejectsOutOfRange() {
        assertTrue(GeoMath.isPlausible(52.5, 13.4))
        assertFalse(GeoMath.isPlausible(95.0, 13.4))
    }
}
