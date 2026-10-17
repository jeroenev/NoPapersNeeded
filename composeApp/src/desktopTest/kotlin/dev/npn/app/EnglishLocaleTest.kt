package dev.npn.app

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Base class for desktop UI tests that change the JVM's default locale, as the app-language local
 * does while it is provided. Each test gets back whatever locale it started in, the machine's, the
 * IDE's or the Gradle test JVM's, so the next test does not run in the language this one left.
 */
open class LocaleRestoringTest {
    private var previousLocale: Locale? = null

    @BeforeTest
    fun rememberLocale() {
        previousLocale = Locale.getDefault()
    }

    @AfterTest
    fun restoreLocale() {
        previousLocale?.let { Locale.setDefault(it) }
    }
}

/**
 * Base class for desktop UI tests that name strings from the default `values/` resources. Compose
 * resources resolve against the JVM's default locale, so each test runs with it pinned to English,
 * and [LocaleRestoringTest] puts back the one it had. A superclass's `@BeforeTest` runs first, so
 * the locale is remembered before it is pinned.
 */
open class EnglishLocaleTest : LocaleRestoringTest() {
    @BeforeTest
    fun pinEnglishLocale() {
        Locale.setDefault(Locale.ENGLISH)
    }
}
