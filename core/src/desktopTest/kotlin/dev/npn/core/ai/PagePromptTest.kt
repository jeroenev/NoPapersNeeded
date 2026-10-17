package dev.npn.core.ai

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

// A prompt's date must be assertable, so every context in this file is built on a fixed clock.

/** The page-transcription prompt: verbatim discipline, date preservation, blank-page contract. */
class PagePromptTest {
    @Test
    fun ocrCarriesTodayAsAReadingAidOnly() {
        val prompt = AiPrompts.ocrSystem(dutch)
        prompt shouldContain dutch.today
        prompt shouldContain "never overrides what the page shows"
    }

    @Test
    fun ocrForbidsReformattingADateSoALaterStepCanStillReadIt() {
        val prompt = AiPrompts.ocrSystem(dutch)
        prompt shouldContain "03/04/2024 stays 03/04/2024"
        prompt shouldContain "never expand a two-digit year"
    }

    @Test
    fun ocrNeverMentionsTheAppLanguage() {
        // A transcription is the page's own words; the app's language could only bias it.
        AiPrompts.ocrSystem(dutch) shouldNotContain "Dutch"
        AiPrompts.ocrSystem(dutch) shouldContain "the page's own language"
    }

    @Test
    fun ocrKeepsTheBlankPageContract() {
        AiPrompts.ocrSystem(dutch) shouldContain AiText.BLANK_PAGE_MARKER
    }

    @Test
    fun pageRequestPassesTheLanguageHint() {
        AiPrompts.pageRequest("nld") shouldContain "Language hint: nld"
    }
}
