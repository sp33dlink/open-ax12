package org.openax12.core.audio

import org.openax12.core.model.SensorUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * Turns numbers with units into text a speech engine pronounces well ("12.4 volts",
 * "1 minute 25 seconds"). Pure: no Android, no state.
 *
 * English and German have full unit names with singular / plural. Every other language gets the
 * number with the locale's decimal separator followed by the unit symbol ("12,4 V"), which speech
 * engines expand themselves in their own language.
 */
internal object SpokenNumbers {

    /** Highest number of decimals ever spoken (coordinates). */
    private const val MAX_PRECISION = 6

    /** Singular phrase (number included, e.g. "1 volt" / "ein Volt") and plural unit name. */
    private class UnitWords(val single: String, val plural: String)

    private class Phrases(
        val decimalSeparator: Char,
        val minus: String,
        val noValue: String,
        val hour: UnitWords,
        val minute: UnitWords,
        val second: UnitWords,
        val units: Map<SensorUnit, UnitWords>,
    )

    private fun en(single: String, plural: String = single + "s") = UnitWords("1 $single", plural)

    private val english = Phrases(
        decimalSeparator = '.',
        minus = "minus",
        noValue = "no value",
        hour = en("hour"),
        minute = en("minute"),
        second = en("second"),
        units = mapOf(
            SensorUnit.VOLT to en("volt"),
            SensorUnit.AMPERE to en("amp"),
            SensorUnit.MILLIAMPERE_HOUR to en("milliamp hour"),
            SensorUnit.WATT to en("watt"),
            SensorUnit.MILLIWATT to en("milliwatt"),
            SensorUnit.PERCENT to en("percent", "percent"),
            SensorUnit.DBM to en("d B m", "d B m"),
            SensorUnit.DB to en("decibel"),
            SensorUnit.METER to en("metre"),
            SensorUnit.FEET to en("foot", "feet"),
            SensorUnit.KILOMETER to en("kilometre"),
            SensorUnit.MILE to en("mile"),
            SensorUnit.METER_PER_SECOND to en("metre per second", "metres per second"),
            SensorUnit.FEET_PER_SECOND to en("foot per second", "feet per second"),
            SensorUnit.KMH to en("kilometre per hour", "kilometres per hour"),
            SensorUnit.MPH to en("mile per hour", "miles per hour"),
            SensorUnit.KNOT to en("knot"),
            SensorUnit.DEGREE to en("degree"),
            SensorUnit.COORDINATE to en("degree"),
            SensorUnit.CELSIUS to en("degree Celsius", "degrees Celsius"),
            SensorUnit.FAHRENHEIT to en("degree Fahrenheit", "degrees Fahrenheit"),
            SensorUnit.RPM to en("R P M", "R P M"),
            SensorUnit.HERTZ to en("hertz", "hertz"),
            SensorUnit.G to en("G", "G"),
        ),
    )

    private fun deN(single: String, plural: String = single) = UnitWords("ein $single", plural)

    private fun deF(single: String, plural: String) = UnitWords("eine $single", plural)

    private val german = Phrases(
        decimalSeparator = ',',
        minus = "minus",
        noValue = "kein Wert",
        hour = deF("Stunde", "Stunden"),
        minute = deF("Minute", "Minuten"),
        second = deF("Sekunde", "Sekunden"),
        units = mapOf(
            SensorUnit.VOLT to deN("Volt"),
            SensorUnit.AMPERE to deN("Ampere"),
            SensorUnit.MILLIAMPERE_HOUR to deF("Milliamperestunde", "Milliamperestunden"),
            SensorUnit.WATT to deN("Watt"),
            SensorUnit.MILLIWATT to deN("Milliwatt"),
            SensorUnit.PERCENT to deN("Prozent"),
            SensorUnit.DBM to UnitWords("1 d B m", "d B m"),
            SensorUnit.DB to deN("Dezibel"),
            SensorUnit.METER to deN("Meter"),
            SensorUnit.FEET to deN("Fuß"),
            SensorUnit.KILOMETER to deN("Kilometer"),
            SensorUnit.MILE to deF("Meile", "Meilen"),
            SensorUnit.METER_PER_SECOND to deN("Meter pro Sekunde"),
            SensorUnit.FEET_PER_SECOND to deN("Fuß pro Sekunde"),
            SensorUnit.KMH to deN("Kilometer pro Stunde"),
            SensorUnit.MPH to deF("Meile pro Stunde", "Meilen pro Stunde"),
            SensorUnit.KNOT to deN("Knoten"),
            SensorUnit.DEGREE to deN("Grad"),
            SensorUnit.COORDINATE to deN("Grad"),
            SensorUnit.CELSIUS to deN("Grad Celsius"),
            SensorUnit.FAHRENHEIT to deN("Grad Fahrenheit"),
            SensorUnit.RPM to deF("Umdrehung pro Minute", "Umdrehungen pro Minute"),
            SensorUnit.HERTZ to deN("Hertz"),
            SensorUnit.G to UnitWords("1 G", "G"),
        ),
    )

    /** True if [languageTag] has full unit names; otherwise symbols are used. */
    fun hasUnitNames(languageTag: String): Boolean = phrasesFor(languageTag) != null

    /**
     * The phrase for [value] in [unit].
     *
     * Rounding: [precision] decimals (0..6), trailing zeros removed; values from 100 keep at most
     * one decimal and values from 1000 none (coordinates keep all of them). `-0` becomes `0`.
     * [SensorUnit.SECOND] is spoken as hours / minutes / seconds once it reaches a minute.
     *
     * @param languageTag BCP-47 tag of the speech language; empty = English
     */
    fun phrase(value: Double, unit: SensorUnit, precision: Int, languageTag: String): String {
        val phrases = phrasesFor(languageTag)
        if (value.isNaN() || value.isInfinite()) return phrases?.noValue ?: ""
        return if (phrases != null) named(value, unit, precision, phrases) else symbolic(value, unit, precision, languageTag)
    }

    /** [phrase] preceded by [name] when there is one. */
    fun withName(name: String?, phrase: String): String {
        val trimmed = name?.trim().orEmpty()
        return when {
            trimmed.isEmpty() -> phrase
            phrase.isEmpty() -> trimmed
            else -> "$trimmed $phrase"
        }
    }

    /**
     * [value] rounded for speech as a plain decimal string with '.' as separator and a leading
     * '-' for negative numbers ("12.4", "-3", "0").
     */
    fun rounded(value: Double, unit: SensorUnit, precision: Int): String {
        val magnitude = abs(value)
        var decimals = precision.coerceIn(0, MAX_PRECISION)
        if (unit != SensorUnit.COORDINATE) {
            if (magnitude >= 1000.0) decimals = 0 else if (magnitude >= 100.0) decimals = minOf(decimals, 1)
        }
        // valueOf goes through the shortest decimal representation, so 2.675 rounds to 2.68.
        var number = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP)
        if (number.signum() == 0) return "0"
        number = number.stripTrailingZeros()
        if (number.scale() < 0) number = number.setScale(0)
        return number.toPlainString()
    }

    private fun phrasesFor(languageTag: String): Phrases? {
        val language = languageTag.trim().substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        return when (language) {
            "", "en" -> english
            "de" -> german
            else -> null
        }
    }

    private fun named(value: Double, unit: SensorUnit, precision: Int, phrases: Phrases): String {
        if (unit == SensorUnit.SECOND) return duration(value, precision, phrases)
        val number = rounded(value, unit, precision)
        val words = phrases.units[unit] ?: return spokenNumber(number, phrases)
        return quantity(number, words, phrases)
    }

    /** "1 volt" / "12.4 volts" / "minus 3 volts". */
    private fun quantity(number: String, words: UnitWords, phrases: Phrases): String = when (number) {
        "1" -> words.single
        "-1" -> "${phrases.minus} ${words.single}"
        else -> "${spokenNumber(number, phrases)} ${words.plural}"
    }

    private fun spokenNumber(number: String, phrases: Phrases): String {
        val negative = number.startsWith('-')
        val digits = number.removePrefix("-").replace('.', phrases.decimalSeparator)
        return if (negative) "${phrases.minus} $digits" else digits
    }

    private fun duration(value: Double, precision: Int, phrases: Phrases): String {
        val magnitude = abs(value)
        if (magnitude < 59.5) {
            // Short times keep their decimals ("12.5 seconds").
            return quantity(rounded(value, SensorUnit.SECOND, precision), phrases.second, phrases)
        }
        val total = Math.round(magnitude)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        val parts = ArrayList<String>(4)
        if (value < 0) parts += phrases.minus
        if (hours > 0) parts += quantity(hours.toString(), phrases.hour, phrases)
        if (minutes > 0) parts += quantity(minutes.toString(), phrases.minute, phrases)
        if (seconds > 0) parts += quantity(seconds.toString(), phrases.second, phrases)
        return parts.joinToString(" ")
    }

    /** Number + symbol for languages without unit names; the speech engine expands the symbol. */
    private fun symbolic(value: Double, unit: SensorUnit, precision: Int, languageTag: String): String {
        val locale = Locale.forLanguageTag(languageTag)
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        fun localized(number: String) = number.replace('.', separator)

        if (unit == SensorUnit.SECOND && abs(value) >= 59.5) {
            val total = Math.round(abs(value))
            val parts = ArrayList<String>(4)
            if (total / 3600 > 0) parts += "${total / 3600} h"
            if ((total % 3600) / 60 > 0) parts += "${(total % 3600) / 60} min"
            if (total % 60 > 0) parts += "${total % 60} s"
            val text = parts.joinToString(" ")
            return if (value < 0) "-$text" else text
        }
        val number = localized(rounded(value, unit, precision))
        return when {
            unit.symbol.isEmpty() -> number
            // "12°" / "12°C" read better without a space.
            unit.symbol.startsWith("°") || unit == SensorUnit.PERCENT -> number + unit.symbol
            else -> "$number ${unit.symbol}"
        }
    }
}
