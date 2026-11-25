package org.openax12.core.audio

import java.util.Locale

/** Speech-language selection. Pure (no Android). */
internal object SpeechLanguage {

    /**
     * The locale meant by [languageTag]; empty, blank or malformed = [systemLocale].
     */
    fun requestedLocale(languageTag: String, systemLocale: Locale): Locale {
        val tag = languageTag.trim().replace('_', '-')
        if (tag.isEmpty()) return systemLocale
        val locale = Locale.forLanguageTag(tag)
        return if (locale.language.isEmpty()) systemLocale else locale
    }

    /**
     * The locales to try on the speech engine, best first: the requested one, the requested
     * language without country, the system locale, the system language and finally English.
     * The first two count as "the requested language" (see [isRequestedLanguage]).
     */
    fun candidates(languageTag: String, systemLocale: Locale): List<Locale> {
        val requested = requestedLocale(languageTag, systemLocale)
        val result = LinkedHashSet<Locale>()
        result += requested
        result += Locale.forLanguageTag(requested.language)
        result += systemLocale
        if (systemLocale.language.isNotEmpty()) result += Locale.forLanguageTag(systemLocale.language)
        result += Locale.US
        result += Locale.ENGLISH
        return result.filter { it.language.isNotEmpty() }
    }

    /** True if [candidate] speaks the language of [requested] (any country). */
    fun isRequestedLanguage(candidate: Locale, requested: Locale): Boolean =
        normalizedLanguage(candidate) == normalizedLanguage(requested)

    /** BCP-47 tag of [locale] with a two-letter language where one exists ("deu-DEU" → "de-DE"). */
    fun tag(locale: Locale): String {
        val language = normalizedLanguage(locale)
        val country = normalizedCountry(locale)
        return if (country.isEmpty()) language else "$language-$country"
    }

    /** Old engines report ISO 639-2 three-letter codes; map them back to two letters. */
    private fun normalizedLanguage(locale: Locale): String {
        val language = locale.language.lowercase(Locale.ROOT)
        if (language.length != 3) return language
        return iso3Languages[language] ?: language
    }

    private fun normalizedCountry(locale: Locale): String {
        val country = locale.country.uppercase(Locale.ROOT)
        if (country.length != 3 || country.all(Char::isDigit)) return country
        return iso3Countries[country] ?: country
    }

    private val iso3Languages: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (code in Locale.getISOLanguages()) {
            val iso3 = runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()
            if (!iso3.isNullOrEmpty()) map[iso3.lowercase(Locale.ROOT)] = code
        }
        map
    }

    private val iso3Countries: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (code in Locale.getISOCountries()) {
            val iso3 = runCatching { Locale.Builder().setRegion(code).build().isO3Country }.getOrNull()
            if (!iso3.isNullOrEmpty()) map[iso3.uppercase(Locale.ROOT)] = code
        }
        map
    }
}
