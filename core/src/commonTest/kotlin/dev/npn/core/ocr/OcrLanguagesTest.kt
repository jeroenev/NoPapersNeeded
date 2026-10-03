package dev.npn.core.ocr

import kotlin.test.Test
import kotlin.test.assertEquals

class OcrLanguagesTest {
    @Test
    fun automaticIsTheAppLanguagePlusEnglish() {
        assertEquals(listOf("nld", "eng"), OcrLanguages.forAppLanguage("nl"))
        assertEquals(listOf("por", "eng"), OcrLanguages.forAppLanguage("pt_BR"))
        assertEquals(listOf("fra", "eng"), OcrLanguages.forAppLanguage("fr-BE"))
        assertEquals(listOf("eng"), OcrLanguages.forAppLanguage("en"))
    }

    @Test
    fun appLanguageWithoutAModelFallsBackToEnglish() {
        assertEquals(listOf("eng"), OcrLanguages.forAppLanguage("ja"))
        assertEquals(listOf("eng"), OcrLanguages.forAppLanguage(null))
    }

    @Test
    fun parseAcceptsTesseractSyntaxAndHandTypedLists() {
        assertEquals(listOf("nld", "eng"), OcrLanguages.parse("nld+eng"))
        assertEquals(listOf("ita", "pol"), OcrLanguages.parse(" ita, pol "))
        assertEquals(listOf("deu"), OcrLanguages.parse("deu+deu"))
        assertEquals(emptyList(), OcrLanguages.parse(" + "))
    }

    @Test
    fun effectiveUsesTheSettingOrFollowsTheAppLanguage() {
        assertEquals("deu+fra", OcrLanguages.effective("deu+fra", "nl"))
        assertEquals("spa+eng", OcrLanguages.effective(null, "es"))
        assertEquals("spa+eng", OcrLanguages.effective(" ", "es"))
    }
}
