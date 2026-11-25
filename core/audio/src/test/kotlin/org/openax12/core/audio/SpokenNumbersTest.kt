package org.openax12.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.SensorUnit

class SpokenNumbersTest {

    private fun en(value: Double, unit: SensorUnit, precision: Int = 0) = SpokenNumbers.phrase(value, unit, precision, "en-US")

    private fun de(value: Double, unit: SensorUnit, precision: Int = 0) = SpokenNumbers.phrase(value, unit, precision, "de-DE")

    private fun fr(value: Double, unit: SensorUnit, precision: Int = 0) = SpokenNumbers.phrase(value, unit, precision, "fr-FR")

    @Test
    fun `numbers are spoken with unit names`() {
        assertEquals("12.4 volts", en(12.44, SensorUnit.VOLT, 1))
        assertEquals("153 metres", en(153.2, SensorUnit.METER))
        assertEquals("87 percent", en(87.0, SensorUnit.PERCENT))
        assertEquals("2200 milliamp hours", en(2200.0, SensorUnit.MILLIAMPERE_HOUR))
        assertEquals("3.5 metres per second", en(3.5, SensorUnit.METER_PER_SECOND, 1))
        assertEquals("120 feet", en(120.0, SensorUnit.FEET))
    }

    @Test
    fun `one is singular`() {
        assertEquals("1 volt", en(1.0, SensorUnit.VOLT, 1))
        assertEquals("1 foot", en(1.0, SensorUnit.FEET))
        assertEquals("1 metre per second", en(1.04, SensorUnit.METER_PER_SECOND, 1))
        assertEquals("1.1 volts", en(1.1, SensorUnit.VOLT, 1))
        assertEquals("0 volts", en(0.0, SensorUnit.VOLT, 1))
    }

    @Test
    fun `negative values say minus`() {
        assertEquals("minus 3.2 metres per second", en(-3.2, SensorUnit.METER_PER_SECOND, 1))
        assertEquals("minus 1 degree Celsius", en(-1.0, SensorUnit.CELSIUS))
        assertEquals("minus 92 d B m", en(-92.0, SensorUnit.DBM))
        assertEquals("minus 5", en(-5.0, SensorUnit.NONE))
    }

    @Test
    fun `rounding is sensible`() {
        // Requested precision, without trailing zeros.
        assertEquals("12 volts", en(12.0, SensorUnit.VOLT, 2))
        assertEquals("2.68 volts", en(2.675, SensorUnit.VOLT, 2))
        assertEquals("11.1 volts", en(11.05, SensorUnit.VOLT, 1))
        // No "minus zero".
        assertEquals("0 volts", en(-0.04, SensorUnit.VOLT, 1))
        // Big numbers lose decimals nobody wants to hear.
        assertEquals("153.3 metres", en(153.26, SensorUnit.METER, 2))
        assertEquals("1235 milliamp hours", en(1234.56, SensorUnit.MILLIAMPERE_HOUR, 2))
        assertEquals("100 percent", en(100.0, SensorUnit.PERCENT, 1))
        // Coordinates keep theirs.
        assertEquals("148.123456 degrees", en(148.123456, SensorUnit.COORDINATE, 6))
        // Out-of-range precision is clamped.
        assertEquals("3 volts", en(3.2, SensorUnit.VOLT, -4))
        assertEquals("3.2 volts", en(3.2, SensorUnit.VOLT, 40))
    }

    @Test
    fun `plain rounding helper`() {
        assertEquals("12.4", SpokenNumbers.rounded(12.44, SensorUnit.VOLT, 1))
        assertEquals("-3", SpokenNumbers.rounded(-3.0, SensorUnit.NONE, 2))
        assertEquals("0", SpokenNumbers.rounded(-0.0, SensorUnit.NONE, 0))
        assertEquals("1", SpokenNumbers.rounded(0.5, SensorUnit.NONE, 0))
        assertEquals("1000000", SpokenNumbers.rounded(1_000_000.0, SensorUnit.NONE, 0))
    }

    @Test
    fun `seconds become minutes and hours`() {
        assertEquals("12.5 seconds", en(12.5, SensorUnit.SECOND, 1))
        assertEquals("1 second", en(1.0, SensorUnit.SECOND))
        assertEquals("0 seconds", en(0.0, SensorUnit.SECOND))
        assertEquals("1 minute 25 seconds", en(85.0, SensorUnit.SECOND))
        assertEquals("1 minute", en(60.0, SensorUnit.SECOND))
        assertEquals("1 minute", en(59.6, SensorUnit.SECOND))
        assertEquals("2 minutes", en(120.0, SensorUnit.SECOND, 1))
        assertEquals("1 hour 2 minutes 5 seconds", en(3725.0, SensorUnit.SECOND))
        assertEquals("2 hours", en(7200.0, SensorUnit.SECOND))
        assertEquals("minus 1 minute 30 seconds", en(-90.0, SensorUnit.SECOND))
    }

    @Test
    fun `values without a unit are just numbers`() {
        assertEquals("42", en(42.0, SensorUnit.NONE))
        assertEquals("7", en(7.0, SensorUnit.COUNT))
        assertEquals("1", en(1.0, SensorUnit.COUNT))
        assertEquals("3.14", en(3.14159, SensorUnit.TEXT, 2))
    }

    @Test
    fun `missing values`() {
        assertEquals("no value", en(Double.NaN, SensorUnit.VOLT, 1))
        assertEquals("no value", en(Double.POSITIVE_INFINITY, SensorUnit.VOLT, 1))
        assertEquals("kein Wert", de(Double.NaN, SensorUnit.VOLT, 1))
        assertEquals("", fr(Double.NaN, SensorUnit.VOLT, 1))
    }

    @Test
    fun `german uses a decimal comma and german units`() {
        assertEquals("12,4 Volt", de(12.44, SensorUnit.VOLT, 1))
        assertEquals("ein Volt", de(1.0, SensorUnit.VOLT, 1))
        assertEquals("153 Meter", de(153.0, SensorUnit.METER))
        assertEquals("minus 2,5 Meter pro Sekunde", de(-2.5, SensorUnit.METER_PER_SECOND, 1))
        assertEquals("eine Minute 25 Sekunden", de(85.0, SensorUnit.SECOND))
        assertEquals("eine Sekunde", de(1.0, SensorUnit.SECOND))
        assertEquals("2 Stunden eine Minute", de(7260.0, SensorUnit.SECOND))
        assertEquals("3 Meilen", de(3.0, SensorUnit.MILE))
    }

    @Test
    fun `other languages get the symbol and the locale's separator`() {
        assertEquals("12,4 V", fr(12.44, SensorUnit.VOLT, 1))
        assertEquals("50%", fr(50.0, SensorUnit.PERCENT))
        assertEquals("21°C", fr(21.0, SensorUnit.CELSIUS))
        assertEquals("-3,2 m/s", fr(-3.2, SensorUnit.METER_PER_SECOND, 1))
        assertEquals("1 min 25 s", fr(85.0, SensorUnit.SECOND))
        assertEquals("42", fr(42.0, SensorUnit.NONE))
    }

    @Test
    fun `language tags are matched loosely`() {
        assertTrue(SpokenNumbers.hasUnitNames("en"))
        assertTrue(SpokenNumbers.hasUnitNames("en-GB"))
        assertTrue(SpokenNumbers.hasUnitNames("de_AT"))
        assertTrue(SpokenNumbers.hasUnitNames(""))
        assertFalse(SpokenNumbers.hasUnitNames("fr-FR"))
        assertEquals("12.4 volts", SpokenNumbers.phrase(12.4, SensorUnit.VOLT, 1, "EN-us"))
    }

    @Test
    fun `every unit has an english and a german phrase`() {
        for (unit in SensorUnit.entries) {
            for (language in listOf("en", "de")) {
                val phrase = SpokenNumbers.phrase(12.0, unit, 0, language)
                assertTrue("$unit in $language: '$phrase'", phrase.startsWith("12"))
                if (unit.symbol.isNotEmpty()) {
                    assertTrue("$unit in $language has no unit name: '$phrase'", phrase.length > 3)
                }
            }
        }
    }

    @Test
    fun `name prefix`() {
        assertEquals("Altitude 153 metres", SpokenNumbers.withName("Altitude", "153 metres"))
        assertEquals("153 metres", SpokenNumbers.withName(null, "153 metres"))
        assertEquals("153 metres", SpokenNumbers.withName("  ", "153 metres"))
        assertEquals("Altitude", SpokenNumbers.withName(" Altitude ", ""))
    }
}
