// checkAll's per-test PropTestConfig overload is still marked experimental by kotest.
@file:OptIn(ExperimentalKotest::class)

package dev.npn.core.model

import dev.npn.core.journal.propTest
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.test.Test

/**
 * The canonical-form contract of [FieldValues], for every [FieldType] in every number style, over
 * generated input: what a user types, a model answers or an older build stored.
 */
class FieldValuesPropertyTest {
    private val locales = listOf(FieldLocale.CANONICAL, FieldLocale(','), FieldLocale.forLanguageTag("nl-BE"))

    @Test
    fun canonicalFormIsStableAndRoundTripsThroughDisplay() =
        propTest {
            for (type in FieldType.entries) {
                for (locale in locales) {
                    checkAll(PropTestConfig(iterations = 400), input) { raw ->
                        val parsed = FieldValues.canonicalize(type, raw, locale)
                        val canonical = parsed.canonicalOrNull ?: return@checkAll
                        val case = "$type in '${locale.decimalSeparator}' from '$raw' to '$canonical'"
                        require(canonical.isNotBlank()) { "stored a blank value: $case" }
                        require(FieldValues.canonicalize(type, canonical) == parsed) { "not idempotent: $case" }
                        require(FieldValues.normalized(type, canonical) == canonical) { "normalized changed: $case" }
                        val shown = FieldValues.display(type, canonical, locale)
                        require(FieldValues.canonicalize(type, shown, locale) == parsed) {
                            "display '$shown' does not read back: $case"
                        }
                    }
                }
            }
        }

    @Test
    fun blankInputClearsTheField() =
        propTest {
            for (type in FieldType.entries) {
                for (locale in locales) {
                    checkAll(PropTestConfig(iterations = 100), blank) { raw ->
                        require(FieldValues.canonicalize(type, raw, locale) == FieldParse.Ok(null)) {
                            "$type in '${locale.decimalSeparator}' did not clear on '$raw'"
                        }
                    }
                }
            }
        }

    private companion object {
        /** One of [chars]' characters. */
        fun codepoints(chars: String): Arb<Codepoint> = Arb.of(chars.map { Codepoint(it.code) })

        val padding = Arb.of("", " ", "\t", "\n", " \u00A0")

        val blank: Arb<String?> = Arb.string(0..6, codepoints(" \t\n\r\u00A0")).orNull(0.2)

        /** Numbers written the ways people write them: signed, grouped, with either separator. */
        val numberLike: Arb<String> =
            Arb.bind(
                Arb.of("", "-", "+"),
                Arb.long(0L, 999_999_999_999L),
                Arb.of(null, ',', '.', ' ', '\u00A0', '\''),
                Arb.of(null, '.', ','),
                Arb.string(0..4, codepoints("0159")),
            ) { sign, magnitude, grouping, decimal, fraction ->
                val digits = magnitude.toString()
                val whole =
                    if (grouping == null) {
                        digits
                    } else {
                        digits
                            .reversed()
                            .chunked(3)
                            .joinToString(grouping.toString())
                            .reversed()
                    }
                sign + whole + decimal?.let { "$it$fraction" }.orEmpty()
            }

        /** Dates, real and impossible, in the stored shape. */
        val dateLike: Arb<String> =
            Arb.bind(Arb.int(0, 9999), Arb.int(0, 13), Arb.int(0, 32)) { year, month, day ->
                "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-" +
                    day.toString().padStart(2, '0')
            }

        /** Short strings of the characters the parsers care about, in any order. */
        val separatorSoup: Arb<String> = Arb.string(0..10, codepoints("017-+., 'a\n"))

        val input: Arb<String?> =
            Arb
                .bind(
                    padding,
                    Arb.choice(numberLike, dateLike, separatorSoup, Arb.string(0..12)),
                    padding,
                ) { before, value, after -> before + value + after }
                .orNull(0.05)
    }
}
