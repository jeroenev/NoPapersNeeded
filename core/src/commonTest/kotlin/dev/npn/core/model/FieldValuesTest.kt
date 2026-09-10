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
