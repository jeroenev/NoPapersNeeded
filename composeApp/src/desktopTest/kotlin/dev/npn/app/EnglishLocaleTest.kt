package dev.npn.app

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Base class for desktop UI tests that name strings from the default `values/` resources. Compose
 * resources resolve against the JVM's default locale, so each test runs with it pinned to English
 * and gets back whatever the machine, the IDE or the Gradle test JVM had.
 */
open class EnglishLocaleTest {
    private var previousLocale: Locale? = null

    @BeforeTest
    fun pinEnglishLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restoreLocale() {
        previousLocale?.let { Locale.setDefault(it) }
    }
}
