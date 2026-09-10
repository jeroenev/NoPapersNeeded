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
        fun forLanguageTag(tag: String?): FieldLocale {
            val language = tag?.substringBefore('-')?.substringBefore('_')?.lowercase()
            return if (language in COMMA_DECIMAL_LANGUAGES) FieldLocale(',') else CANONICAL
        }

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
 * | NUMBER | `-?\d+` | the value |
 * | DECIMAL | `-?\d+(\.\d+)?`, always `.` | the value |
 *
 * A blank input always canonicalizes to `Ok(null)`: a cleared field has no stored row (P4 in the
 * plan), and this is the one place that turns `""` into "no value".
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
                val cleaned = stripGrouping(trimmed, locale).removePrefix("+")
                if (integerPattern.matches(cleaned)) FieldParse.Ok(cleaned) else FieldParse.Invalid(trimmed)
            }

            FieldType.DECIMAL -> {
                val cleaned = stripGrouping(trimmed, locale).replace(locale.decimalSeparator, '.').removePrefix("+")
                if (decimalPattern.matches(cleaned)) FieldParse.Ok(cleaned) else FieldParse.Invalid(trimmed)
            }
        }
    }

    /** True when [canonical] is already in the canonical form for [type]. */
    fun isCanonical(
        type: FieldType,
        canonical: String,
    ): Boolean = (canonicalize(type, canonical) as? FieldParse.Ok)?.canonical == canonical

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

    private fun canonicalDate(trimmed: String): FieldParse =
        if (isIsoDate(trimmed)) FieldParse.Ok(trimmed) else FieldParse.Invalid(trimmed)

    private fun stripGrouping(
        value: String,
        locale: FieldLocale,
    ): String = value.filterNot { it == locale.groupingSeparator || it in GROUPING_WHITESPACE }

    /** Characters some locales use between digit groups besides the locale's own separator. */
    private const val GROUPING_WHITESPACE = " \u00A0'"
}
