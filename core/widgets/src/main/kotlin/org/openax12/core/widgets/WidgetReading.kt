package org.openax12.core.widgets

import androidx.compose.runtime.Immutable
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.UnitConversion
import org.openax12.core.model.UnitSystem
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** How trustworthy a displayed value is. */
internal enum class ReadingStatus {
    /** Fresh value. */
    OK,

    /** Telemetry value that has not been refreshed for a while but is not flagged lost yet: dimmed. */
    STALE,

    /** The sensor stopped updating: the last value is kept, dimmed, with a lost marker. */
    LOST,

    /** No sensor bound, the sensor is unknown or never reported: `--`. */
    MISSING,
}

/**
 * A sensor value prepared for drawing: converted to the display unit system and formatted.
 *
 * @property value numeric value in display units; `NaN` if there is none
 * @property number formatted number, the text of a textual sensor, or `--`
 * @property unit unit symbol; empty for textual sensors
 * @property min / [max] extremes since the last reset in display units; `NaN` if unknown
 */
@Immutable
internal data class Reading(
    val status: ReadingStatus = ReadingStatus.MISSING,
    val value: Double = Double.NaN,
    val number: String = UnitConversion.NO_VALUE,
    val unit: String = "",
    val min: Double = Double.NaN,
    val max: Double = Double.NaN,
    val minText: String = UnitConversion.NO_VALUE,
    val maxText: String = UnitConversion.NO_VALUE,
    val isText: Boolean = false,
    val precision: Int = 0,
) {
    /** True if [value] is a number. */
    val hasValue: Boolean get() = !value.isNaN()

    /** True if anything can be shown (a number or a text). */
    val hasContent: Boolean get() = status != ReadingStatus.MISSING

    companion object {
        /** The reading of a widget without a usable sensor. */
        val MISSING = Reading()
    }
}

/** Builds [Reading]s from sensor values. Pure; unit-tested. */
internal object Readings {

    /**
     * @param id the sensor the value belongs to (decides whether the stale rule applies); may be null
     * @param nowMs monotonic now on the same base as [SensorValue.timestampMs]
     * @param staleAfterMs age that makes a telemetry value stale; 0 disables the rule
     */
    fun of(id: SensorId?, value: SensorValue?, system: UnitSystem, nowMs: Long, staleAfterMs: Long): Reading {
        if (value == null) return Reading.MISSING
        if (!value.hasValue) {
            val text = value.text ?: return Reading.MISSING
            return Reading(
                status = if (value.lost) ReadingStatus.LOST else ReadingStatus.OK,
                number = text,
                isText = true,
            )
        }
        val status = when {
            value.lost -> ReadingStatus.LOST
            isStale(id, value, nowMs, staleAfterMs) -> ReadingStatus.STALE
            else -> ReadingStatus.OK
        }
        if (value.unit == SensorUnit.SECOND && id != null && id.timerIndex != null) {
            return Reading(
                status = status,
                value = value.value,
                number = UnitConversion.formatTime(value.value.roundToInt()),
                min = value.min,
                max = value.max,
                minText = if (value.min.isNaN()) UnitConversion.NO_VALUE else UnitConversion.formatTime(value.min.roundToInt()),
                maxText = if (value.max.isNaN()) UnitConversion.NO_VALUE else UnitConversion.formatTime(value.max.roundToInt()),
            )
        }
        val display = UnitConversion.convert(value, system)
        val min = UnitConversion.convert(value.min, value.unit, system).value
        val max = UnitConversion.convert(value.max, value.unit, system).value
        return Reading(
            status = status,
            value = display.value,
            number = UnitConversion.formatNumber(display.value, display.precision),
            unit = display.unit.symbol,
            min = min,
            max = max,
            minText = UnitConversion.formatNumber(min, display.precision),
            maxText = UnitConversion.formatNumber(max, display.precision),
            precision = display.precision,
        )
    }

    /** Only receiver telemetry goes stale; internal and live values are event driven. */
    fun isStale(id: SensorId?, value: SensorValue, nowMs: Long, staleAfterMs: Long): Boolean {
        if (staleAfterMs <= 0 || id == null || value.timestampMs <= 0 || nowMs <= 0) return false
        if (id.namespace != SensorId.NS_CRSF && id.namespace != SensorId.NS_SDK) return false
        return nowMs - value.timestampMs > staleAfterMs
    }

    /**
     * Converts an option given in the sensor's native unit (scale end, warning threshold) to the
     * display unit system. `NaN` if the option is absent.
     */
    fun displayOption(option: Double?, value: SensorValue?, system: UnitSystem): Double = when {
        option == null -> Double.NaN
        value == null -> option
        else -> UnitConversion.convert(option, value.unit, system).value
    }

    /** The worst of several statuses (MISSING > LOST > STALE > OK). */
    fun worst(vararg statuses: ReadingStatus): ReadingStatus = statuses.maxByOrNull { it.ordinal } ?: ReadingStatus.MISSING
}

/**
 * Scale of a bar / gauge / graph and the warning thresholds, in display units.
 * `NaN` thresholds are absent.
 */
@Immutable
internal data class Scale(
    val min: Double = 0.0,
    val max: Double = 100.0,
    val warnBelow: Double = Double.NaN,
    val warnAbove: Double = Double.NaN,
) {
    /** Position of [value] on the scale, 0..1 (0 for `NaN`). */
    fun fraction(value: Double): Float {
        if (value.isNaN() || max <= min) return 0f
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    }

    /** Where filling starts: the zero point for scales that cross zero, else the lower end. */
    val originFraction: Float get() = if (min < 0.0 && max > 0.0) fraction(0.0) else 0f

    /** True if the scale crosses zero. */
    val isBipolar: Boolean get() = min < 0.0 && max > 0.0

    /** True if [value] is beyond a warning threshold. */
    fun isWarn(value: Double): Boolean {
        if (value.isNaN()) return false
        return (!warnBelow.isNaN() && value < warnBelow) || (!warnAbove.isNaN() && value > warnAbove)
    }

    companion object {
        /**
         * The scale of a widget: its MIN / MAX options where present, otherwise a default for
         * the sensor (percent 0..100, inputs and channels −100..100, anything else from the
         * extremes seen so far rounded to nice numbers).
         */
        fun resolve(config: WidgetConfig, id: SensorId?, value: SensorValue?, reading: Reading, system: UnitSystem): Scale {
            val optMin = Readings.displayOption(config.optionDouble(WidgetOptions.MIN), value, system)
            val optMax = Readings.displayOption(config.optionDouble(WidgetOptions.MAX), value, system)
            val warnBelow = Readings.displayOption(config.optionDouble(WidgetOptions.WARN_BELOW), value, system)
            val warnAbove = Readings.displayOption(config.optionDouble(WidgetOptions.WARN_ABOVE), value, system)
            var lo: Double
            var hi: Double
            when {
                id != null && id.isLive -> {
                    lo = -100.0
                    hi = 100.0
                }
                value?.unit == SensorUnit.PERCENT -> {
                    lo = 0.0
                    hi = 100.0
                }
                else -> {
                    val seenLo = listOf(reading.min, reading.value).filter { !it.isNaN() }.minOrNull() ?: 0.0
                    val seenHi = listOf(reading.max, reading.value).filter { !it.isNaN() }.maxOrNull() ?: 100.0
                    lo = if (seenLo < 0.0) -niceCeil(-seenLo) else 0.0
                    hi = if (seenHi > 0.0) niceCeil(seenHi) else if (lo < 0.0) 0.0 else 100.0
                }
            }
            if (!optMin.isNaN()) lo = optMin
            if (!optMax.isNaN()) hi = optMax
            if (hi <= lo) hi = lo + 1.0
            return Scale(lo, hi, warnBelow, warnAbove)
        }

        /** Smallest of 1, 2, 2.5, 5, 10 × 10ⁿ that is ≥ [value] (for [value] > 0). */
        fun niceCeil(value: Double): Double {
            if (value <= 0.0 || value.isNaN() || value.isInfinite()) return 1.0
            val exponent = floor(log10(value))
            val base = 10.0.pow(exponent)
            val mantissa = value / base
            val nice = NICE_STEPS.firstOrNull { mantissa <= it + 1e-9 } ?: 10.0
            return nice * base
        }

        private val NICE_STEPS = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)
    }
}

/** A reading with its scale; what the value / bar / gauge / vario widgets draw. */
@Immutable
internal data class Gauge(val reading: Reading = Reading.MISSING, val scale: Scale = Scale()) {
    /** True if the value is beyond a warning threshold. */
    val warn: Boolean get() = scale.isWarn(reading.value)
}

/** Number formatting helpers shared by the renderers. Pure; unit-tested. */
internal object WidgetFormat {

    /** A scale label: no decimals for whole or large numbers, one otherwise. */
    fun scaleLabel(value: Double): String {
        if (value.isNaN()) return UnitConversion.NO_VALUE
        val whole = abs(value - Math.rint(value)) < 1e-6
        return UnitConversion.formatNumber(value, if (whole || abs(value) >= 100.0) 0 else 1)
    }

    /** A value with an explicit plus sign for positive numbers (vario). */
    fun signed(reading: Reading): String =
        if (reading.hasValue && reading.value > 0.0 && !reading.number.startsWith("+") && reading.number.any { it in '1'..'9' }) {
            "+" + reading.number
        } else {
            reading.number
        }

    /** `48.13712°` + hemisphere letter. [positive] / [negative] are the letters for the sign. */
    fun coordinate(degrees: Double, positive: String, negative: String): String {
        if (degrees.isNaN()) return UnitConversion.NO_VALUE
        val letter = if (degrees < 0.0) negative else positive
        return String.format(Locale.ROOT, "%.5f° %s", abs(degrees), letter)
    }

    /**
     * Distance for display: metres below 1000 m, kilometres above (feet / miles for imperial).
     * Returns number and unit symbol.
     */
    fun distance(meters: Double, system: UnitSystem): Pair<String, String> {
        if (meters.isNaN()) return UnitConversion.NO_VALUE to ""
        return when (system) {
            UnitSystem.METRIC ->
                if (abs(meters) < 1000.0) {
                    UnitConversion.formatNumber(meters, 0) to SensorUnit.METER.symbol
                } else {
                    UnitConversion.formatNumber(meters / 1000.0, 2) to SensorUnit.KILOMETER.symbol
                }
            UnitSystem.IMPERIAL -> {
                val feet = UnitConversion.convert(meters, SensorUnit.METER, system).value
                if (abs(feet) < FEET_PER_MILE) {
                    UnitConversion.formatNumber(feet, 0) to SensorUnit.FEET.symbol
                } else {
                    UnitConversion.formatNumber(feet / FEET_PER_MILE, 2) to SensorUnit.MILE.symbol
                }
            }
        }
    }

    /** Index 0..7 of the compass octant of [bearingDeg] (0 = N, 1 = NE, … 7 = NW). */
    fun compassOctant(bearingDeg: Double): Int {
        val normalized = ((bearingDeg % 360.0) + 360.0) % 360.0
        return ((normalized + 22.5) / 45.0).toInt() % 8
    }

    /** Parses `#RRGGBB` (or `#AARRGGBB`) to ARGB; null if malformed. */
    fun parseColor(text: String?): Long? {
        val hex = text?.trim()?.removePrefix("#") ?: return null
        val parsed = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> 0xFF000000L or parsed
            8 -> parsed
            else -> null
        }
    }

    private const val FEET_PER_MILE = 5280.0
}

/** LiPo battery maths for the BATTERY widget. Pure; unit-tested. */
internal object BatteryMath {

    /** Highest per-cell voltage a charged LiPo cell shows (with a little margin). */
    private const val MAX_CELL_VOLTAGE = 4.3

    /**
     * Number of cells: [configured] if > 0, otherwise detected from the highest voltage seen
     * ([maxVoltage], falls back to [voltage]) assuming LiPo cells. 0 if unknown.
     */
    fun cellCount(configured: Int, voltage: Double, maxVoltage: Double): Int {
        if (configured > 0) return configured
        val reference = if (!maxVoltage.isNaN() && maxVoltage > 0.0) maxOf(maxVoltage, voltage.takeIf { !it.isNaN() } ?: 0.0) else voltage
        if (reference.isNaN() || reference < 0.5) return 0
        return ceil(reference / MAX_CELL_VOLTAGE).toInt().coerceIn(1, 14)
    }

    /** Remaining charge in percent of a LiPo cell at rest voltage [cellVoltage]; −1 if unknown. */
    fun percent(cellVoltage: Double): Int {
        if (cellVoltage.isNaN() || cellVoltage <= 0.0) return -1
        if (cellVoltage >= CURVE.first().first) return 100
        if (cellVoltage <= CURVE.last().first) return 0
        for (i in 0 until CURVE.size - 1) {
            val (vHigh, pHigh) = CURVE[i]
            val (vLow, pLow) = CURVE[i + 1]
            if (cellVoltage <= vHigh && cellVoltage >= vLow) {
                val t = (cellVoltage - vLow) / (vHigh - vLow)
                return (pLow + t * (pHigh - pLow)).roundToInt()
            }
        }
        return 0
    }

    /** LiPo discharge curve: cell voltage → percent, descending. */
    private val CURVE: List<Pair<Double, Double>> = listOf(
        4.20 to 100.0, 4.15 to 95.0, 4.11 to 90.0, 4.08 to 85.0, 4.02 to 80.0, 3.98 to 75.0,
        3.95 to 70.0, 3.91 to 65.0, 3.87 to 60.0, 3.85 to 55.0, 3.84 to 50.0, 3.82 to 45.0,
        3.80 to 40.0, 3.79 to 35.0, 3.77 to 30.0, 3.75 to 25.0, 3.73 to 20.0, 3.71 to 15.0,
        3.69 to 10.0, 3.61 to 5.0, 3.27 to 0.0,
    )
}
