package dev.npn.core.ai

import dev.npn.core.model.FieldKeys
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

// A prompt's date must be assertable, so every context in this file is built on a fixed clock.

/** The assistant's system prompt: tool contract, citations, date anchor and language rule. */
class ChatPromptTest {
    private val fields = FieldKeys.seedDefinitions()

    @Test
    fun chatDefaultsToTheAppLanguageButStillFollowsTheUser() {
        val prompt = AiPrompts.chatSystem(dutch, fields)
        prompt shouldContain "Dutch (nl)"
        prompt shouldContain "default"
        // The whole point: the app's language is a default, never a cage.
        prompt shouldContain "the language the user writes in always wins"
    }

    @Test
    fun chatFallsBackToTheUsersLanguageWhenTheAppLanguageIsUnknown() {
        val prompt = AiPrompts.chatSystem(promptContext(null), fields)
        prompt shouldContain "Answer in the language the user writes in"
        prompt shouldNotContain "runs in"
    }

    @Test
    fun chatCarriesTodaySoRelativeDatesResolve() {
        AiPrompts.chatSystem(dutch, fields) shouldContain dutch.today
    }

    @Test
    fun chatStillCarriesItsToolAndCitationContract() {
        val prompt = AiPrompts.chatSystem(dutch, fields)
        prompt shouldContain "[doc:<docId>]"
        prompt shouldContain "search_documents"
        // The field vocabulary is now built at runtime from the live registry.
        prompt shouldContain LibraryAiTools.fieldVocabulary(fields)
    }
}
