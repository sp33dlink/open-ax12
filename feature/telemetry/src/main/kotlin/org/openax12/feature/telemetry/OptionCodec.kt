package org.openax12.feature.telemetry

import java.math.BigDecimal
import kotlin.math.roundToInt

/**
 * Widget options are strings; the number pad edits integers scaled by 10^decimals. These
 * helpers convert between the two. Pure.
 */
internal object OptionCodec {

    /** Decimals used for numeric widget options (scale ends, thresholds). */
    const val NUMBER_DECIMALS = 2

    private fun factor(decimals: Int): Double = Math.pow(10.0, decimals.toDouble())

    /** [text] as a scaled integer, or null if it is not a number. */
    fun toScaled(text: String?, decimals: Int = NUMBER_DECIMALS): Int? {
        val number = text?.trim()?.toDoubleOrNull() ?: return null
        if (number.isNaN() || number.isInfinite()) return null
        return (number * factor(decimals)).roundToInt()
    }

    /** The scaled integer [value] as the shortest plain decimal string (`1150` with 2 decimals becomes `11.5`). */
    fun fromScaled(value: Int, decimals: Int = NUMBER_DECIMALS): String =
        BigDecimal(value).movePointLeft(decimals).stripTrailingZeros().toPlainString()

    /** The range limit [limit] (or [fallback] when null) as a scaled integer. */
    fun scaledLimit(limit: Double?, fallback: Double, decimals: Int = NUMBER_DECIMALS): Int =
        ((limit ?: fallback) * factor(decimals)).roundToInt()
}
