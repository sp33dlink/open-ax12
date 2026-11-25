package org.openax12.core.model

import java.util.Locale
import kotlin.math.abs

/** A value converted to the user's unit system, ready to be formatted. */
data class DisplayValue(val value: Double, val unit: SensorUnit, val precision: Int)

/** Conversion between metric and imperial units and number formatting shared by UI, speech and logs. */
object UnitConversion {

    /**
     * Converts [value] given in [unit] to [system]. Metric values pass through for
     * [UnitSystem.METRIC]; for [UnitSystem.IMPERIAL] metres become feet, m/s becomes ft/s,
     * km/h becomes mph, km becomes miles and °C becomes °F. Imperial input units are converted
     * the other way round for [UnitSystem.METRIC]. Everything else is returned unchanged.
     */
    fun convert(value: Double, unit: SensorUnit, system: UnitSystem, precision: Int = 0): DisplayValue {
        if (value.isNaN()) return DisplayValue(value, unit, precision)
        return when (system) {
            UnitSystem.IMPERIAL -> when (unit) {
                SensorUnit.METER -> DisplayValue(value * FEET_PER_METER, SensorUnit.FEET, precision)
                SensorUnit.METER_PER_SECOND -> DisplayValue(value * FEET_PER_METER, SensorUnit.FEET_PER_SECOND, precision)
                SensorUnit.KMH -> DisplayValue(value / KM_PER_MILE, SensorUnit.MPH, precision)
                SensorUnit.KILOMETER -> DisplayValue(value / KM_PER_MILE, SensorUnit.MILE, precision)
                SensorUnit.CELSIUS -> DisplayValue(value * 9.0 / 5.0 + 32.0, SensorUnit.FAHRENHEIT, precision)
                else -> DisplayValue(value, unit, precision)
            }
            UnitSystem.METRIC -> when (unit) {
                SensorUnit.FEET -> DisplayValue(value / FEET_PER_METER, SensorUnit.METER, precision)
                SensorUnit.FEET_PER_SECOND -> DisplayValue(value / FEET_PER_METER, SensorUnit.METER_PER_SECOND, precision)
                SensorUnit.MPH -> DisplayValue(value * KM_PER_MILE, SensorUnit.KMH, precision)
                SensorUnit.MILE -> DisplayValue(value * KM_PER_MILE, SensorUnit.KILOMETER, precision)
                SensorUnit.FAHRENHEIT -> DisplayValue((value - 32.0) * 5.0 / 9.0, SensorUnit.CELSIUS, precision)
                else -> DisplayValue(value, unit, precision)
            }
        }
    }

    /** [convert] for a [SensorValue]. */
    fun convert(value: SensorValue, system: UnitSystem): DisplayValue =
        convert(value.value, value.unit, system, value.precision)

    /**
     * Formats a number with a fixed number of decimals and a dot as decimal separator
     * (locale-independent). `NaN` becomes `"--"`. Negative zero is printed as zero.
     */
    fun formatNumber(value: Double, precision: Int): String {
        if (value.isNaN() || value.isInfinite()) return NO_VALUE
        val decimals = precision.coerceIn(0, 6)
        val text = String.format(Locale.ROOT, "%.${decimals}f", value)
        return if (text.startsWith("-") && abs(text.toDouble()) == 0.0) text.substring(1) else text
    }

    /**
     * Display text of a sensor value in [system]: the text of textual sensors, otherwise the
     * formatted number, followed by the unit symbol when [withUnit].
     */
    fun format(value: SensorValue, system: UnitSystem, withUnit: Boolean = true): String {
        if (!value.hasValue) return value.text ?: NO_VALUE
        val display = convert(value, system)
        val number = formatNumber(display.value, display.precision)
        return if (withUnit && display.unit.symbol.isNotEmpty()) number + display.unit.symbol else number
    }

    /** Formats seconds as `m:ss` (or `h:mm:ss` from one hour); negative values get a leading minus. */
    fun formatTime(seconds: Int): String {
        val total = abs(seconds)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val body = if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        return if (seconds < 0) "-$body" else body
    }

    /** Placeholder shown for a missing value. */
    const val NO_VALUE: String = "--"

    private const val FEET_PER_METER = 3.280839895
    private const val KM_PER_MILE = 1.609344
}
