package dev.npn.core.model

import kotlinx.datetime.LocalDate

/** Outcome of turning what a human (or a model) typed into a field's canonical stored form. */
sealed interface FieldParse {
    /** A canonical value, or null when the input was blank and the field should be cleared. */
    data class Ok(
        val canonical: String?,
    ) : FieldParse

    /** The input does not fit the field's type; nothing must be stored. */
    data class Invalid(
        val raw: String,
    ) : FieldParse
}

/** The canonical value an [FieldParse.Ok] holds; null for a blank input or one that does not fit. */
val FieldParse.canonicalOrNull: String? get() = (this as? FieldParse.Ok)?.canonical

/**
 * How numbers are written where the user is. Only what parsing needs: the decimal separator; the
 * grouping separator is whichever of `.`/`,` is not decimal, plus spaces.
 */
data class FieldLocale(
    val decimalSeparator: Char = '.',
) {
    val groupingSeparator: Char get() = if (decimalSeparator == '.') ',' else '.'

    companion object {
        val CANONICAL = FieldLocale('.')

        /** Best-effort mapping from a BCP-47 language tag to a decimal separator. */
        fun forLanguageTag(tag: String?): FieldLocale =
            if (AppLanguages.subtag(tag) in COMMA_DECIMAL_LANGUAGES) FieldLocale(',') else CANONICAL

        private val COMMA_DECIMAL_LANGUAGES =
            setOf("nl", "fr", "de", "es", "it", "pt", "da", "sv", "nb", "fi", "pl", "cs")
    }
}

/**
 * The sole authority on a field value's canonical form. Storage is always canonical; locale
 * formatting lives at the boundary and goes through [display]/[canonicalize] only.
 *
 * | Type | Canonical stored form | numeric shadow |
 * |---|---|---|
 * | TEXT / LONG_TEXT | trimmed string | — |
 * | DATE | `yyyy-MM-dd` (sorts lexicographically) | — |
 * | NUMBER | `-?\d+`, no leading zeros | the value |
 * | DECIMAL | `-?\d+(\.\d+)?`, always `.`, no leading zeros, no trailing fraction zeros | the value |
 *
 * Each number has exactly one canonical form (`007` is `7`, `1.50` is `1.5`, `2.0` is `2`, `-0` is
 * `0`), so comparing two canonical strings compares the numbers. Values stored before that by older
 * builds are read through [normalized].
 *
 * A blank input always canonicalizes to `Ok(null)`: a cleared field has no stored row, and this is
 * the one place that turns `""` into "no value".
 */
object FieldValues {
    private val integerPattern = Regex("^[-+]?\\d+$")
    private val decimalPattern = Regex("^[-+]?\\d+(\\.\\d+)?$")
    private val isoDatePattern = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    fun canonicalize(
        type: FieldType,
        raw: String?,
        locale: FieldLocale = FieldLocale.CANONICAL,
    ): FieldParse {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return FieldParse.Ok(null)
        return when (type) {
            FieldType.TEXT, FieldType.LONG_TEXT -> {
                FieldParse.Ok(trimmed)
            }

            FieldType.DATE -> {
                canonicalDate(trimmed)
            }

            FieldType.NUMBER -> {
                val cleaned = stripGrouping(trimmed, locale)?.removePrefix("+")
                if (cleaned != null && integerPattern.matches(cleaned)) {
                    FieldParse.Ok(uniqueNumber(cleaned))
                } else {
                    FieldParse.Invalid(trimmed)
                }
            }

            FieldType.DECIMAL -> {
                val cleaned = stripGrouping(trimmed, locale)?.replace(locale.decimalSeparator, '.')?.removePrefix("+")
                if (cleaned != null && decimalPattern.matches(cleaned)) {
                    FieldParse.Ok(uniqueNumber(cleaned))
                } else {
                    FieldParse.Invalid(trimmed)
                }
            }
        }
    }

    /**
     * [stored] as the fold reads it: a NUMBER or DECIMAL value that parses comes back in today's
     * canonical form, so `007` stored by an older build reads as `7`; anything else, an off-type
     * value included, comes back verbatim.
     */
    fun normalized(
        type: FieldType,
        stored: String,
    ): String {
        if (!type.isNumeric) return stored
        return canonicalize(type, stored).canonicalOrNull ?: stored
    }

    /** The user-facing rendering of a canonical value. Off-type values come back verbatim. */
    fun display(
        type: FieldType,
        canonical: String,
        locale: FieldLocale = FieldLocale.CANONICAL,
    ): String =
        when {
            type == FieldType.DECIMAL && decimalPattern.matches(canonical) -> {
                canonical.replace('.', locale.decimalSeparator)
            }

            else -> {
                canonical
            }
        }

    /** The numeric shadow of a canonical NUMBER/DECIMAL value, or null (including off-type). */
    fun numeric(
        type: FieldType,
        canonical: String,
    ): Double? {
        if (!type.isNumeric) return null
        return canonical.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** Structurally valid `yyyy-MM-dd` that also exists on the calendar. */
    fun isIsoDate(value: String): Boolean {
        if (!isoDatePattern.matches(value)) return false
        return runCatching { LocalDate.parse(value) }.isSuccess
    }

    /**
     * The one spelling of a sign-and-digits literal ([integerPattern] or [decimalPattern], no `+`):
     * no leading zeros before the point, no trailing zeros after it, no point without a fraction,
     * and no sign on zero.
     */
    private fun uniqueNumber(literal: String): String {
        val negative = literal.startsWith('-')
        val body = literal.removePrefix("-")
        val whole = body.substringBefore('.').trimStart('0').ifEmpty { "0" }
        val fraction = body.substringAfter('.', "").trimEnd('0')
        val magnitude = if (fraction.isEmpty()) whole else "$whole.$fraction"
        return if (negative && magnitude != "0") "-$magnitude" else magnitude
    }

    private fun canonicalDate(trimmed: String): FieldParse =
        if (isIsoDate(trimmed)) FieldParse.Ok(trimmed) else FieldParse.Invalid(trimmed)

    /**
     * Remove the locale's grouping from [value], and nothing else, refusing grouping that is not at
     * a valid group boundary.
     *
     * A grouping separator is only legitimate between groups of three digits in the integer part;
     * anywhere else — adjacent, leading, trailing, mis-sized, or inside the fraction — the input is
     * ambiguous and is rejected rather than silently re-read. This is what stops a dot-decimal
     * number typed in a comma locale (`1234.56`) from being accepted as `123456` (and vice versa).
     *
     * The locale's decimal separator is preserved for the caller to normalize, which is what keeps
     * the stored form canonical no matter which language the value was entered in. Returns null when
     * a separator sits at an invalid position or an unexpected character appears.
     */
    private fun stripGrouping(
        value: String,
        locale: FieldLocale,
    ): String? {
        val decimalIndex = value.indexOf(locale.decimalSeparator)
        val integerPart = if (decimalIndex == -1) value else value.substring(0, decimalIndex)
        val rest = if (decimalIndex == -1) "" else value.substring(decimalIndex)
        // Grouping never belongs in the fraction: `1.234,567` is fine, `1,234. 567` is not.
        if (rest.any { it == locale.groupingSeparator || it in GROUPING_WHITESPACE }) return null
        val normalizedInteger = normalizeGroupedInteger(integerPart, locale.groupingSeparator) ?: return null
        return normalizedInteger + rest
    }

    /**
     * Drop [grouping] from an integer literal only when every separator divides groups of three.
     * A sign is preserved; a value with no separator at all is a plain integer of any length.
     */
    private fun normalizeGroupedInteger(
        part: String,
        grouping: Char,
    ): String? {
        var body = part
        val sign =
            if (body.startsWith("+") || body.startsWith("-")) {
                body.take(1).also { body = body.drop(1) }
            } else {
                ""
            }
        if (body.isEmpty()) return null
        val groups = ArrayList<String>()
        var current = StringBuilder()
        for (ch in body) {
            when {
                ch.isDigit() -> {
                    current.append(ch)
                }

                ch == grouping || ch in GROUPING_WHITESPACE -> {
                    groups.add(current.toString())
                    current = StringBuilder()
                }

                else -> {
                    return null
                }
            }
        }
        groups.add(current.toString())
        if (groups.any { it.isEmpty() }) return null
        if (groups.size > 1 &&
            (groups.first().length !in 1..3 || groups.drop(1).any { it.length != 3 })
        ) {
            return null
        }
        return sign + groups.joinToString("")
    }

    /** Characters some locales use between digit groups besides the locale's own separator. */
    private const val GROUPING_WHITESPACE = " \u00A0'"
}
