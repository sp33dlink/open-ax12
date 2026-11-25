package org.openax12.core.engine

import org.openax12.core.model.UnitConversion

/** One piece of a parsed text template. [start] / [end] are character offsets into the source ([end] exclusive). */
sealed interface TemplateToken {
    /** Offset of the first character of the token in the source. */
    val start: Int
    /** Offset after the last character of the token (exclusive). */
    val end: Int

    /** Literal text. `{{` and `}}` in the source are unescaped to single braces here. */
    data class Text(val text: String, override val start: Int, override val end: Int) : TemplateToken

    /**
     * A `{name}` or `{name:precision}` placeholder.
     *
     * @property name the name between the braces, trimmed
     * @property precision decimals requested with `:n`; null = the value's own precision
     */
    data class Placeholder(
        val name: String,
        val precision: Int?,
        override val start: Int,
        override val end: Int,
    ) : TemplateToken
}

/** What is wrong with a template. */
enum class TemplateErrorKind {
    /** `{` without a matching `}`. */
    UNCLOSED_BRACE,

    /** `}` without a preceding `{`. */
    UNEXPECTED_CLOSING_BRACE,

    /** `{}` or `{:1}`. */
    EMPTY_NAME,

    /** The part after `:` is not an integer between 0 and 6. */
    INVALID_PRECISION,

    /** The name does not resolve to anything (only reported when validating against a resolver). */
    UNKNOWN_NAME,
}

/**
 * A template error that points at the offending token.
 *
 * @property start / [end] character range of the bad token in the template source ([end] exclusive)
 * @property token the offending source text
 */
data class TemplateError(
    val kind: TemplateErrorKind,
    val start: Int,
    val end: Int,
    val token: String,
)

/** Result of parsing a template. Parsing never throws; a template with [errors] can still be rendered (bad tokens render as literal text). */
data class ParsedTemplate(
    val source: String,
    val tokens: List<TemplateToken>,
    val errors: List<TemplateError>,
) {
    /** True if the template has no errors. */
    val isValid: Boolean get() = errors.isEmpty()

    /** Names of all placeholders in order of appearance. */
    val placeholderNames: List<String> get() = tokens.filterIsInstance<TemplateToken.Placeholder>().map { it.name }
}

/** How placeholders are rendered. */
enum class RenderMode {
    /** For display: number followed by the unit symbol (`12.4V`, `153m`), timers as `m:ss`. */
    DISPLAY,

    /**
     * For text-to-speech: the number only, **without** a unit — the template text carries the
     * words ("Battery {RxBt} volts"). Timers render as `m:ss` unless a [ValueFormatter] says
     * otherwise.
     */
    SPEECH,
}

/**
 * Optional hook to format a placeholder value differently (the runtime passes the audio engine's
 * language-aware formatter for speech, e.g. timers as "1 minute 25 seconds").
 */
fun interface ValueFormatter {
    /**
     * @param precision decimals requested in the placeholder (`{Alt:0}`), or null
     * @return the text to insert, or null to use the renderer's default formatting
     */
    fun format(value: ResolvedValue, precision: Int?): String?
}

/**
 * Text templates of SPEC section 6: literal text with `{name}` / `{name:precision}` placeholders,
 * e.g. `Battery {RxBt} volts`, `{Alt:0} metres`, `{T1}`, `{FM}`, `{model}`, `{LQ}`.
 * `{{` and `}}` produce literal braces. Names are resolved by a [ValueResolver].
 */
object TemplateRenderer {

    /** Highest precision accepted after the colon of a placeholder. */
    const val MAX_PRECISION: Int = 6

    /** Parses [template] into tokens and syntax errors. Never throws. */
    fun parse(template: String): ParsedTemplate {
        val tokens = ArrayList<TemplateToken>()
        val errors = ArrayList<TemplateError>()
        val literal = StringBuilder()
        var literalStart = 0

        fun flushLiteral(end: Int) {
            if (literal.isNotEmpty()) {
                tokens += TemplateToken.Text(literal.toString(), literalStart, end)
                literal.setLength(0)
            }
        }

        fun appendLiteral(text: String, start: Int) {
            if (literal.isEmpty()) literalStart = start
            literal.append(text)
        }

        var i = 0
        val n = template.length
        while (i < n) {
            val c = template[i]
            when {
                c == '{' && i + 1 < n && template[i + 1] == '{' -> {
                    appendLiteral("{", i)
                    i += 2
                }
                c == '}' && i + 1 < n && template[i + 1] == '}' -> {
                    appendLiteral("}", i)
                    i += 2
                }
                c == '}' -> {
                    errors += TemplateError(TemplateErrorKind.UNEXPECTED_CLOSING_BRACE, i, i + 1, "}")
                    appendLiteral("}", i)
                    i += 1
                }
                c == '{' -> {
                    // Find the matching '}'. Another '{' first means this one was never closed.
                    var j = i + 1
                    while (j < n && template[j] != '}' && template[j] != '{') j++
                    if (j >= n || template[j] == '{') {
                        val bad = template.substring(i, j)
                        errors += TemplateError(TemplateErrorKind.UNCLOSED_BRACE, i, j, bad)
                        appendLiteral(bad, i)
                        i = j
                    } else {
                        val end = j + 1
                        val source = template.substring(i, end)
                        val inner = template.substring(i + 1, j)
                        val colon = inner.lastIndexOf(':')
                        val name = (if (colon >= 0) inner.substring(0, colon) else inner).trim()
                        val precisionText = if (colon >= 0) inner.substring(colon + 1).trim() else null
                        val precision = precisionText?.toIntOrNull()?.takeIf { it in 0..MAX_PRECISION }
                        when {
                            name.isEmpty() -> {
                                errors += TemplateError(TemplateErrorKind.EMPTY_NAME, i, end, source)
                                appendLiteral(source, i)
                            }
                            precisionText != null && precision == null -> {
                                errors += TemplateError(TemplateErrorKind.INVALID_PRECISION, i, end, source)
                                appendLiteral(source, i)
                            }
                            else -> {
                                flushLiteral(i)
                                tokens += TemplateToken.Placeholder(name, precision, i, end)
                            }
                        }
                        i = end
                    }
                }
                else -> {
                    appendLiteral(c.toString(), i)
                    i += 1
                }
            }
        }
        flushLiteral(n)
        return ParsedTemplate(template, tokens, errors)
    }

    /**
     * Syntax errors of [template] plus, when a [resolver] is given, an
     * [TemplateErrorKind.UNKNOWN_NAME] error for every placeholder that does not resolve.
     * Errors are ordered by position.
     */
    fun validate(template: String, resolver: ValueResolver? = null): List<TemplateError> {
        val parsed = parse(template)
        if (resolver == null) return parsed.errors.sortedBy { it.start }
        val unknown = parsed.tokens.filterIsInstance<TemplateToken.Placeholder>()
            .filter { resolveSafely(resolver, it.name) == null }
            .map { TemplateError(TemplateErrorKind.UNKNOWN_NAME, it.start, it.end, template.substring(it.start, it.end)) }
        return (parsed.errors + unknown).sortedBy { it.start }
    }

    /**
     * Renders a parsed template. Unknown names render as the placeholder's source text; lost or
     * missing values render as `--` in [RenderMode.DISPLAY] and as an empty string in
     * [RenderMode.SPEECH]. Never throws.
     */
    fun render(
        template: ParsedTemplate,
        resolver: ValueResolver,
        mode: RenderMode = RenderMode.DISPLAY,
        formatter: ValueFormatter? = null,
    ): String {
        val out = StringBuilder()
        for (token in template.tokens) {
            when (token) {
                is TemplateToken.Text -> out.append(token.text)
                is TemplateToken.Placeholder -> {
                    val value = resolveSafely(resolver, token.name)
                    if (value == null) {
                        out.append(template.source.substring(token.start, token.end))
                    } else {
                        out.append(formatValue(value, token.precision, mode, formatter))
                    }
                }
            }
        }
        return out.toString()
    }

    /** [parse] + [render]. */
    fun render(
        template: String,
        resolver: ValueResolver,
        mode: RenderMode = RenderMode.DISPLAY,
        formatter: ValueFormatter? = null,
    ): String = render(parse(template), resolver, mode, formatter)

    /**
     * Text of one resolved value as a placeholder would render it: the [formatter]'s result if it
     * returns one, else the value's own text (or the number with [precision] decimals), followed
     * by the unit symbol in [RenderMode.DISPLAY] when the text is the plain number.
     */
    fun formatValue(
        value: ResolvedValue,
        precision: Int?,
        mode: RenderMode = RenderMode.DISPLAY,
        formatter: ValueFormatter? = null,
    ): String {
        val missing = value.lost || (!value.isNumeric && value.text.isEmpty())
        if (missing) return if (mode == RenderMode.DISPLAY) UnitConversion.NO_VALUE else ""
        val custom = try {
            formatter?.format(value, precision)
        } catch (_: Exception) {
            null
        }
        if (custom != null) return custom
        if (!value.isNumeric) return value.text
        val ownNumber = UnitConversion.formatNumber(value.number, value.precision)
        // Values with a textual form (timer "1:25", switch "up", flight mode name) keep it unless
        // a precision is requested explicitly.
        val textual = value.text.isNotEmpty() && value.text != ownNumber
        if (precision == null && textual) return value.text
        val number = if (precision == null) ownNumber else UnitConversion.formatNumber(value.number, precision)
        return if (mode == RenderMode.DISPLAY && value.unit.symbol.isNotEmpty()) number + value.unit.symbol else number
    }

    private fun resolveSafely(resolver: ValueResolver, name: String): ResolvedValue? = try {
        resolver.resolve(name)
    } catch (_: Exception) {
        null
    }
}
