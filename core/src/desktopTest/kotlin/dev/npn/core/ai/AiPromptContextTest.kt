package dev.npn.core.ai

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import kotlin.test.Test

// A prompt's date must be assertable, so every context in this file is built on a fixed clock.

/**
 * [AiPromptContext]: how an app-language tag and a clock become prompt-ready facts.
 *
 * The prompt tests assert the *facts* a prompt carries (today's date, the app's language,
 * who decides how to read a date) rather than its wording, so rephrasing a prompt stays free while
 * dropping one of its guarantees does not.
 */
class AiPromptContextTest {
    @Test
    fun namesAKnownLanguageAndKeepsItsTag() {
        promptContext("nl").appLanguage shouldContain "Dutch (nl)"
    }

    @Test
    fun acceptsARegionalTagAndReducesItToTheLanguage() {
        // Locale.getDefault().toString() can hand us nl_NL, and settings could hold nl-NL.
        promptContext("nl-NL").appLanguage shouldContain "Dutch (nl)"
        promptContext("nl_NL").appLanguage shouldContain "Dutch (nl)"
        promptContext("NL").appLanguage shouldContain "Dutch (nl)"
    }

    @Test
    fun passesAnUnnamedLanguageThroughAsItsBareSubtag() {
        // Better a prompt saying "pt" than no language at all; we do not name every locale.
        promptContext("pt-BR").appLanguage shouldContain "pt"
    }

    @Test
    fun reportsNoLanguageWhenTheTagIsMissingOrBlank() {
        promptContext(null).appLanguage shouldBe null
        promptContext("  ").appLanguage shouldBe null
    }

    @Test
    fun todayIsAnIsoDate() {
        // Rendered in the device's own timezone, so only the shape is fixed across machines.
        promptContext("nl").today shouldMatch Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}
