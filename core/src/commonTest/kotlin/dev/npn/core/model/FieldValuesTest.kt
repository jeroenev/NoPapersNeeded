package dev.npn.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldValuesTest {
    private val nl = FieldLocale(',')

    private fun ok(
        type: FieldType,
        raw: String?,
        locale: FieldLocale = FieldLocale.CANONICAL,
    ): String? = assertIs<FieldParse.Ok>(FieldValues.canonicalize(type, raw, locale)).canonical

    @Test
    fun blank_input_clears_for_every_type() {
        for (type in FieldType.entries) {
            assertNull(ok(type, null), type.name)
            assertNull(ok(type, ""), type.name)
            assertNull(ok(type, "   "), type.name)
        }
    }

    @Test
    fun text_is_trimmed_only() {
        assertEquals("Acme Ltd", ok(FieldType.TEXT, "  Acme Ltd "))
        assertEquals("line one\nline two", ok(FieldType.LONG_TEXT, "line one\nline two\n"))
    }

    @Test
    fun date_must_be_a_real_iso_date() {
        assertEquals("2026-07-15", ok(FieldType.DATE, " 2026-07-15 "))
        assertIs<FieldParse.Invalid>(FieldValues.canonicalize(FieldType.DATE, "15-07-2026"))
        assertIs<FieldParse.Invalid>(FieldValues.canonicalize(FieldType.DATE, "2026-13-01"))
        assertIs<FieldParse.Invalid>(FieldValues.canonicalize(FieldType.DATE, "2026-02-30"))
    }

    @Test
    fun number_accepts_grouping_and_rejects_fractions() {
        assertEquals("1234", ok(FieldType.NUMBER, "1,234"))
        assertEquals("1234", ok(FieldType.NUMBER, "1.234", nl))
        assertEquals("-42", ok(FieldType.NUMBER, "-42"))
        assertEquals("42", ok(FieldType.NUMBER, "+42"))
        assertIs<FieldParse.Invalid>(FieldValues.canonicalize(FieldType.NUMBER, "12.5"))
        assertIs<FieldParse.Invalid>(FieldValues.canonicalize(FieldType.NUMBER, "abc"))
    }

    @Test
    fun decimal_respects_locale_and_stores_a_dot() {
        assertEquals("1234.56", ok(FieldType.DECIMAL, "1.234,56", nl))
        assertEquals("1234.56", ok(FieldType.DECIMAL, "1,234.56"))
        assertEquals("12.5", ok(FieldType.DECIMAL, "12.5"))
        assertEquals("12", ok(FieldType.DECIMAL, "12"))
        assertIs<FieldParse.Invalid>(FieldValues.canonicalize(FieldType.DECIMAL, "€ 12,50", nl))
        // A Dutch user typing 1.234 means one thousand two hundred thirty-four, never 1.234.
        assertEquals("1234", ok(FieldType.DECIMAL, "1.234", nl))
    }

    @Test
    fun grouping_must_sit_on_a_group_boundary() {
        // Valid grouping still works in both locales.
        assertEquals("1234", ok(FieldType.NUMBER, "1,234"))
        assertEquals("1234", ok(FieldType.NUMBER, "1.234", nl))
        assertEquals("1234.56", ok(FieldType.DECIMAL, "1.234,56", nl))
        assertEquals("1234.56", ok(FieldType.DECIMAL, "1,234.56"))
        // A dot-decimal typed in a comma locale is refused, not silently re-read as 123456.
        assertIs<FieldParse.Invalid>(FieldValues.canonicalize(FieldType.DECIMAL, "1234.56", nl))
        assertIs<FieldParse.Invalid>(FieldValues.canonicalize(FieldType.NUMBER, "1234.56", nl))
        // Groups of the wrong size, and a grouping separator in a dot locale, are refused too.
        assertIs<FieldParse.Invalid>(FieldValues.canonicalize(FieldType.NUMBER, "12,34"))
        assertIs<FieldParse.Invalid>(FieldValues.canonicalize(FieldType.DECIMAL, "1.234,56"))
        assertIs<FieldParse.Invalid>(FieldValues.canonicalize(FieldType.DECIMAL, "1,234.5,6"))
    }

    @Test
    fun the_same_number_stores_the_same_canonical_form_in_every_language() {
        // Input language only decides how it is read; storage is always the canonical dot form.
        assertEquals("1234.56", ok(FieldType.DECIMAL, "1.234,56", nl))
        assertEquals("1234.56", ok(FieldType.DECIMAL, "1,234.56"))
    }

    @Test
    fun a_number_has_exactly_one_canonical_form() {
        assertEquals("7", ok(FieldType.NUMBER, "007"))
        assertEquals("-7", ok(FieldType.NUMBER, "-007"))
        assertEquals("100", ok(FieldType.NUMBER, "100"))
        assertEquals("0", ok(FieldType.NUMBER, "000"))
        assertEquals("0", ok(FieldType.NUMBER, "-0"))
        assertEquals("1.5", ok(FieldType.DECIMAL, "1.50"))
        assertEquals("2", ok(FieldType.DECIMAL, "2.0"))
        assertEquals("10", ok(FieldType.DECIMAL, "10.00"))
        assertEquals("0.5", ok(FieldType.DECIMAL, "0.5"))
        assertEquals("0.5", ok(FieldType.DECIMAL, "00.50"))
        assertEquals("0", ok(FieldType.DECIMAL, "0"))
        assertEquals("0", ok(FieldType.DECIMAL, "-0.00"))
        assertEquals("-0.25", ok(FieldType.DECIMAL, "-0.250"))
        assertEquals("1.5", ok(FieldType.DECIMAL, "1,50", nl))
        assertTrue(!FieldValues.isCanonical(FieldType.NUMBER, "007"))
        assertTrue(!FieldValues.isCanonical(FieldType.DECIMAL, "1.50"))
    }

    @Test
    fun a_stored_value_reads_back_in_todays_canonical_form() {
        assertEquals("7", FieldValues.normalized(FieldType.NUMBER, "007"))
        assertEquals("1.5", FieldValues.normalized(FieldType.DECIMAL, "1.50"))
        // Off-type and non-numeric values are kept verbatim, never rewritten or dropped.
        assertEquals("twelve", FieldValues.normalized(FieldType.DECIMAL, "twelve"))
        assertEquals("12.5", FieldValues.normalized(FieldType.NUMBER, "12.5"))
        assertEquals("007", FieldValues.normalized(FieldType.TEXT, "007"))
    }

    @Test
    fun display_localizes_decimals_and_leaves_the_rest_alone() {
        assertEquals("12,5", FieldValues.display(FieldType.DECIMAL, "12.5", nl))
        assertEquals("12.5", FieldValues.display(FieldType.DECIMAL, "12.5"))
        assertEquals("2026-07-15", FieldValues.display(FieldType.DATE, "2026-07-15", nl))
        // Off-type values are shown verbatim, never rewritten.
        assertEquals("twelve", FieldValues.display(FieldType.DECIMAL, "twelve", nl))
    }

    @Test
    fun numeric_shadow_only_for_numeric_types() {
        assertEquals(12.5, FieldValues.numeric(FieldType.DECIMAL, "12.5"))
        assertEquals(7.0, FieldValues.numeric(FieldType.NUMBER, "7"))
        assertNull(FieldValues.numeric(FieldType.NUMBER, "seven"))
        assertNull(FieldValues.numeric(FieldType.TEXT, "12"))
        assertNull(FieldValues.numeric(FieldType.DATE, "2026-01-01"))
    }

    @Test
    fun is_canonical_round_trips() {
        assertTrue(FieldValues.isCanonical(FieldType.DECIMAL, "12.5"))
        assertTrue(!FieldValues.isCanonical(FieldType.DECIMAL, "12,5"))
        assertTrue(!FieldValues.isCanonical(FieldType.TEXT, " x"))
    }
}

class FieldKeysTest {
    @Test
    fun normalize_collapses_whitespace_and_strips_symbols() {
        assertEquals("invoice_number", FieldKeys.normalize(" invoice  number "))
        assertEquals("Amount", FieldKeys.normalize("Amount"))
        assertEquals("amount_EUR", FieldKeys.normalize("amount (€)EUR"))
        assertNull(FieldKeys.normalize("€€€"))
        assertNull(FieldKeys.normalize("   "))
    }

    @Test
    fun same_key_is_case_insensitive() {
        assertTrue(FieldKeys.sameKey("Amount", "amount"))
        assertTrue(!FieldKeys.sameKey("amount", "amounts"))
    }

    @Test
    fun seed_matches_the_plan_table() {
        val seed = FieldKeys.seedDefinitions()
        assertEquals(listOf("title", "correspondent", "docType", "docDate", "notes"), seed.map { it.key })
        assertEquals(FieldType.DATE, seed.single { it.key == FieldKeys.DOC_DATE }.type)
        assertEquals(FieldType.LONG_TEXT, seed.single { it.key == FieldKeys.NOTES }.type)
        assertTrue(seed.all { it.showByDefault })
        assertEquals(setOf("notes"), seed.filterNot { it.aiEnabled }.map { it.key }.toSet())
        assertEquals((0 until 5).toList(), seed.map { it.position })
    }
}
